// cwl_proto.c: CWL2 protocol core. See cwl_proto.h and PROTOCOL.md.
#include "cwl_proto.h"

#include <stdio.h>
#include <string.h>

// Sequence numbers wrap at 256; "a is newer than b" in serial-number terms.
static bool SeqNewer(uint8_t a, uint8_t b)
{
    return (int8_t)(uint8_t)(a - b) > 0;
}

static void Send(CwlState_t *s, uint8_t type, uint8_t seq, uint8_t byte)
{
    const uint8_t pkt[kCwlPacketLen] = {'C', 'W', kCwlVersion, type, seq, byte};
    s->host.send(s->host.ctx, pkt);
}

// ---------------------------------------------------------------- xstat

static uint32_t NowUs(const CwlState_t *s, uint32_t now_ms)
{
    return s->host.now_us ? s->host.now_us(s->host.ctx) : now_ms * 1000u;
}

static int XBucket(uint32_t us)
{
    if (us < 1000) return 0;
    if (us < 8000) return 1;
    if (us < 17000) return 2;
    if (us < 33000) return 3;
    return 4;
}

static void XCount(CwlState_t *s, uint32_t now_ms)
{
    CwlXstat_t *x = &s->xs;
    uint32_t guard = 0;
    while (now_ms - x->win_start_ms >= 1000 && guard++ < kCwlX_Windows + 1) {
        if (x->win_count > x->win_max) x->win_max = (uint16_t)(x->win_count > 0xFFFF ? 0xFFFF : x->win_count);
        x->win[x->win_n % kCwlX_Windows] = (uint16_t)(x->win_count > 0xFFFF ? 0xFFFF : x->win_count);
        x->win_n++;
        x->windows++;
        x->win_count = 0;
        x->win_start_ms += 1000;
    }
    if (now_ms - x->win_start_ms >= 1000) x->win_start_ms = now_ms;   // long gap: restart the window
    x->win_count++;
}

static void XHoldOpen(CwlState_t *s, uint32_t now_ms)
{
    s->xs.hold_open = true;
    s->xs.hold_start_us = NowUs(s, now_ms);
}

static void XHoldClose(CwlState_t *s, uint32_t now_ms)
{
    if (!s->xs.hold_open) return;
    s->xs.hold_open = false;
    const uint32_t us = NowUs(s, now_ms) - s->xs.hold_start_us;
    s->xs.hold[XBucket(us)]++;
    s->xs.hold_us_sum += us;
}

// CPU parked from start_us to end_us: per 16.74 ms frame window, count the
// windows parked for more than half of it.
static void XParkSpan(CwlXstat_t *x, uint32_t start_us, uint32_t end_us)
{
    const uint32_t kFrame = 16742, kHalf = 8371;
    uint32_t a = start_us;
    for (int guard = 0; guard < 600 && (int32_t)(end_us - a) > 0; guard++) {
        const uint32_t idx = a / kFrame;
        const uint32_t wend = (idx + 1) * kFrame;
        const uint32_t b = (int32_t)(end_us - wend) < 0 ? end_us : wend;
        if (idx != x->frame_idx) {
            x->frame_idx = idx;
            x->frame_park_us = 0;
        }
        const bool was = x->frame_park_us > kHalf;
        x->frame_park_us += b - a;
        if (!was && x->frame_park_us > kHalf) x->frames_half_parked++;
        a = b;
    }
}

static void XParkOpen(CwlState_t *s, uint32_t now_ms)
{
    s->xs.park_open = true;
    s->xs.park_start_us = NowUs(s, now_ms);
}

static void XParkClose(CwlState_t *s, uint32_t now_ms, char outcome)
{
    if (!s->xs.park_open) return;
    s->xs.park_open = false;
    const uint32_t end = NowUs(s, now_ms);
    const uint32_t us = end - s->xs.park_start_us;
    s->xs.park[XBucket(us)]++;
    s->xs.park_us_sum += us;
    XParkSpan(&s->xs, s->xs.park_start_us, end);
    if (s->xs.ab_n > 0) {
        CwlXAbandon_t *a = &s->xs.ab[(s->xs.ab_n - 1) % kCwlX_Abandons];
        if (a->outcome == '?') {
            a->outcome = (uint8_t)outcome;
            a->park_us = us;
        }
    }
}

static void XAbandon(CwlState_t *s, uint8_t sb, uint32_t now_ms, char outcome)
{
    CwlXAbandon_t *a = &s->xs.ab[s->xs.ab_n % kCwlX_Abandons];
    s->xs.ab_n++;
    a->at_ms = now_ms - s->xs.reset_ms;
    a->since_start_us = s->m_state != kCwlM_Idle ? NowUs(s, now_ms) - s->xs.hold_start_us : 0;
    a->park_us = 0;
    a->sb = sb;
    a->outcome = (uint8_t)outcome;
}

static void XRttOpen(CwlState_t *s, uint8_t seq, uint32_t now_ms)
{
    s->xs.rtt_open = true;
    s->xs.rtt_seq = seq;
    s->xs.rtt_open_ms = now_ms;
}

static void XRttClose(CwlState_t *s, uint8_t seq, uint32_t now_ms)
{
    if (!s->xs.rtt_open || seq != s->xs.rtt_seq) return;
    s->xs.rtt_open = false;
    const uint32_t ms = now_ms - s->xs.rtt_open_ms;
    static const uint16_t lim[kCwlX_RttBuckets - 1] = {2, 4, 8, 17, 33, 67, 150};
    int b = 0;
    while (b < kCwlX_RttBuckets - 1 && ms >= lim[b]) b++;
    s->xs.rtt[b]++;
    if (ms > s->xs.rtt_max_ms) s->xs.rtt_max_ms = ms;
}

void Cwl_XstatReset(CwlState_t *s, uint32_t now_ms)
{
    memset(&s->xs, 0, sizeof(s->xs));
    s->xs.reset_ms = now_ms;
    s->xs.win_start_ms = now_ms;
    s->xs.frame_idx = 0xFFFFFFFFu;
}

static int Fmt(char *buf, int len, int at, const char *fmt, ...) __attribute__((format(printf, 4, 5)));
#include <stdarg.h>
static int Fmt(char *buf, int len, int at, const char *fmt, ...)
{
    if (at >= len) return at;
    va_list ap;
    va_start(ap, fmt);
    const int n = vsnprintf(buf + at, (size_t)(len - at), fmt, ap);
    va_end(ap);
    if (n < 0) return at;
    return at + n >= len ? len - 1 : at + n;
}

int Cwl_XstatFormat(const CwlState_t *s, char *buf, int len, uint32_t now_ms)
{
    if (len <= 0) return 0;
    buf[0] = '\0';
    const CwlXstat_t *x = &s->xs;
    int at = 0;
    const uint32_t secs = (now_ms - x->reset_ms) / 1000u;
    at = Fmt(buf, len, at, "xstat %lu s: exch/s now %lu, last", (unsigned long)secs, (unsigned long)x->win_count);
    const uint32_t shown = x->win_n < kCwlX_Windows ? x->win_n : kCwlX_Windows;
    for (uint32_t i = 0; i < shown; i++) at = Fmt(buf, len, at, " %u", x->win[(x->win_n - shown + i) % kCwlX_Windows]);
    at = Fmt(buf, len, at, ", max %u over %lu windows\n", x->win_max, (unsigned long)x->windows);
    uint32_t nh = 0, np = 0;
    for (int b = 0; b < kCwlX_Buckets; b++) { nh += x->hold[b]; np += x->park[b]; }
    at = Fmt(buf, len, at, "hold  <1/<8/<17/<33/>=33 ms: %lu/%lu/%lu/%lu/%lu (n %lu, avg %lu us)\n",
             (unsigned long)x->hold[0], (unsigned long)x->hold[1], (unsigned long)x->hold[2], (unsigned long)x->hold[3],
             (unsigned long)x->hold[4], (unsigned long)nh, (unsigned long)(nh ? x->hold_us_sum / nh : 0));
    at = Fmt(buf, len, at, "park  <1/<8/<17/<33/>=33 ms: %lu/%lu/%lu/%lu/%lu (n %lu, avg %lu us), frames >half parked %lu\n",
             (unsigned long)x->park[0], (unsigned long)x->park[1], (unsigned long)x->park[2], (unsigned long)x->park[3],
             (unsigned long)x->park[4], (unsigned long)np, (unsigned long)(np ? x->park_us_sum / np : 0),
             (unsigned long)x->frames_half_parked);
    at = Fmt(buf, len, at, "slave at REQ: armed %lu, lent %lu, waited <1/<8/<17/<33/>=33 ms %lu/%lu/%lu/%lu/%lu, refused %lu\n",
             (unsigned long)x->req_armed, (unsigned long)x->req_pushed, (unsigned long)x->req_wait[0],
             (unsigned long)x->req_wait[1], (unsigned long)x->req_wait[2], (unsigned long)x->req_wait[3],
             (unsigned long)x->req_wait[4], (unsigned long)x->req_refused);
    at = Fmt(buf, len, at, "rtt   <2/<4/<8/<17/<33/<67/<150/>=150 ms: %lu/%lu/%lu/%lu/%lu/%lu/%lu/%lu, max %lu\n",
             (unsigned long)x->rtt[0], (unsigned long)x->rtt[1], (unsigned long)x->rtt[2], (unsigned long)x->rtt[3],
             (unsigned long)x->rtt[4], (unsigned long)x->rtt[5], (unsigned long)x->rtt[6], (unsigned long)x->rtt[7],
             (unsigned long)x->rtt_max_ms);
    const CwlStats_t *st = &s->stats;
    at = Fmt(buf, len, at, "send-ahead %s (peer %s, lock %s): lent %lu taken %lu revoked %lu/%lu retarget %lu suppressed %lu "
             "lock_nak %lu; used %lu stale %lu wait_take %lu wait_push %lu/%lu; mismatch %lu stray %lu\n",
             s->sa_on ? "on" : "off", s->peer_sa ? "yes" : "no", s->sa_can_lock ? "yes" : "no",
             (unsigned long)st->sa_pushes, (unsigned long)st->sa_taken, (unsigned long)st->sa_revoked,
             (unsigned long)st->sa_revokes, (unsigned long)st->sa_retargets, (unsigned long)st->sa_suppressed,
             (unsigned long)st->sa_lock_naks, (unsigned long)st->sa_used, (unsigned long)st->sa_stale,
             (unsigned long)st->sa_wait_take, (unsigned long)st->sa_wait_push_hit, (unsigned long)st->sa_wait_push,
             (unsigned long)st->sa_mismatch, (unsigned long)st->sa_stray_take);
    const uint32_t na = x->ab_n < kCwlX_Abandons ? x->ab_n : kCwlX_Abandons;
    at = Fmt(buf, len, at, "abandons %lu, last %lu (at ms: since master start / park, sb, outcome A=ack C=cancelled N=none U=unknown):",
             (unsigned long)x->ab_n, (unsigned long)na);
    for (uint32_t i = 0; i < na; i++) {
        const CwlXAbandon_t *a = &x->ab[(x->ab_n - na + i) % kCwlX_Abandons];
        at = Fmt(buf, len, at, " %lu:%lu.%01lu/%lu.%01lu,%02x,%c", (unsigned long)a->at_ms,
                 (unsigned long)(a->since_start_us / 1000), (unsigned long)(a->since_start_us % 1000 / 100),
                 (unsigned long)(a->park_us / 1000), (unsigned long)(a->park_us % 1000 / 100), a->sb, a->outcome);
    }
    at = Fmt(buf, len, at, "\n");
    return at;
}

// ---------------------------------------------------------------- master

static void MasterFinish(CwlState_t *s, uint8_t byte, uint32_t now_ms)
{
    const uint32_t waited = now_ms - s->m_start_ms;
    if (waited > s->stats.max_master_wait_ms) s->stats.max_master_wait_ms = waited;
    s->m_state = kCwlM_Idle;
    s->m_abandoned = false;
    s->m_replied = true;
    s->m_reply_ms = now_ms;
    XHoldClose(s, now_ms);
    s->host.master_reply(s->host.ctx, byte);
}

// No exchange happened: 0xFF (no partner), or a silent release if the game
// already abandoned the transfer.
static void MasterNoExchange(CwlState_t *s, uint32_t now_ms)
{
    s->stats.master_timeouts++;
    if (s->m_abandoned && s->host.master_dropped) {
        const uint32_t waited = now_ms - s->m_start_ms;
        if (waited > s->stats.max_master_wait_ms) s->stats.max_master_wait_ms = waited;
        s->m_state = kCwlM_Idle;
        s->m_abandoned = false;
        s->m_replied = true;
        s->m_reply_ms = now_ms;
        XHoldClose(s, now_ms);
        XParkClose(s, now_ms, 'C');
        s->host.master_dropped(s->host.ctx);
        return;
    }
    XParkClose(s, now_ms, 'C');
    MasterFinish(s, 0xFF, now_ms);
}

// The slave revoked revoked_t; once our seq has passed it, it is moot.
static void ClearMootRevoke(CwlState_t *s)
{
    if (s->revoked_valid && !SeqNewer(s->revoked_t, s->m_seq)) s->revoked_valid = false;
}

static bool PushUsable(const CwlState_t *s)
{
    return s->push_valid && s->push_t == (uint8_t)(s->m_seq + 1) &&
           !(s->revoked_valid && s->revoked_t == s->push_t);
}

// The game started a transfer, nothing else in flight, the peer alive.
static void StartMaster(CwlState_t *s, uint8_t byte, uint32_t now_ms)
{
    if (PushUsable(s)) {
        // Send-ahead: the slave's locked port lent us its byte for exactly
        // this seq. The exchange is decided now (the slave's port cannot
        // change until it gets our byte): complete at once, deliver ours.
        s->m_seq = s->push_t;
        s->push_valid = false;
        ClearMootRevoke(s);
        s->m_byte = byte;
        s->take_pending = true;
        s->take_t = s->m_seq;
        s->take_b = byte;
        s->take_last_send_ms = now_ms;
        Send(s, kCwlType_Take, s->take_t, byte);
        s->stats.sa_used++;
        s->stats.master_done++;
        XCount(s, now_ms);
        MasterFinish(s, s->push_b, now_ms);
        return;
    }
    if (s->push_valid) {
        s->stats.sa_stale++;
        s->push_valid = false;
    }
    s->m_seq++;
    ClearMootRevoke(s);
    s->m_byte = byte;
    s->m_state = kCwlM_Requesting;
    s->m_last_send_ms = now_ms;
    XRttOpen(s, s->m_seq, now_ms);
    Send(s, kCwlType_Req, s->m_seq, byte);
}

void Cwl_OnLocalMaster(CwlState_t *s, uint8_t byte, uint32_t now_ms)
{
    if (s->m_state != kCwlM_Idle) {
        // The game restarted a transfer that is still held (Tetris DX writes
        // SC = 0x81 every frame until its first byte completes; on hardware
        // that byte took 1 ms). Starting a new request here would let the
        // peer exchange twice. Keep the one in flight.
        (void)byte;
        s->stats.master_restarts++;
        return;
    }
    s->local_armed = false;
    s->m_start_ms = now_ms;
    XHoldOpen(s, now_ms);
    if (s->take_pending) {
        // Send-ahead: the previous transfer's byte is still on its way to the
        // slave's port. A new request now could overtake it; wait for its ACK
        // (the slave cannot arm for this one before it has that byte anyway).
        s->m_byte = byte;
        s->m_state = kCwlM_WaitTake;
        s->stats.sa_wait_take++;
        return;
    }
    if (!Cwl_PeerAlive(s, now_ms) && Cwl_Holding(s, now_ms)) {
        // The radio just woke: the peer may well be there, wait for it.
        s->m_byte = byte;
        s->m_state = kCwlM_WaitPeer;
        s->stats.held_masters++;
        return;
    }
    if (!Cwl_PeerAlive(s, now_ms)) {
        // Nobody on the other end: behave like an unplugged cable.
        s->stats.master_timeouts++;
        MasterFinish(s, 0xFF, now_ms);
        return;
    }
    StartMaster(s, byte, now_ms);
}

static void AskVerdictNow(CwlState_t *s, uint32_t now_ms, bool silent, uint8_t sb)
{
    if (s->m_state == kCwlM_Requesting) {
        // The peer may have exchanged already (an armed port answers at once):
        // only its verdict can tell, and the CPU is stalled until it comes.
        s->stats.master_abandons++;
        if (silent) { XAbandon(s, sb, now_ms, '?'); XParkOpen(s, now_ms); }
        s->m_abandoned = silent;
        s->m_state = kCwlM_Cancelling;
        s->m_last_send_ms = now_ms;
        Send(s, kCwlType_Cancel, s->m_seq, 0);
    } else if (s->m_state == kCwlM_Cancelling) {
        if (silent && !s->m_abandoned) { XAbandon(s, sb, now_ms, '?'); XParkOpen(s, now_ms); }
        if (silent) s->m_abandoned = true;  // verdict already being asked
    } else if (s->m_state == kCwlM_WaitPeer || s->m_state == kCwlM_WaitTake || s->m_state == kCwlM_WaitPush) {
        // No request went out (radio still waking, or send-ahead waiting for
        // the previous TAKE's ACK): nobody exchanged.
        s->stats.master_abandons++;
        if (silent) { XAbandon(s, sb, now_ms, '?'); XParkOpen(s, now_ms); }
        s->m_abandoned = silent;
        MasterNoExchange(s, now_ms);
        if (silent && s->xs.ab_n > 0) s->xs.ab[(s->xs.ab_n - 1) % kCwlX_Abandons].outcome = 'N';
    } else if (silent && s->host.master_dropped &&
               (!s->m_replied || (now_ms - s->m_reply_ms) >= kCwlReplyInFlight_ms)) {
        // Idle, and no reply is on its way to the port: the port holds a
        // master we never heard of (its MASTER event was lost on the UART).
        // No request went out, so nobody exchanged: release it now. Ignoring
        // it left the CPU stalled until the FPGA's ~4 s safety timeout, which
        // then completed it with 0xFF and an IRQ after the parked write had
        // landed: Pokemon Gold read its own SB = 0x02 as the partner's and
        // "connected" alone (the link then closed for inactivity).
        s->stats.unknown_abandons++;
        s->stats.master_timeouts++;
        s->m_replied = true;
        s->m_reply_ms = now_ms;
        XAbandon(s, sb, now_ms, 'U');
        s->host.master_dropped(s->host.ctx);
    } else if (silent) {
        // The reply is on its way to the port (a send-ahead completion lands
        // here: decided at once, the reply crosses the UART).
        XAbandon(s, sb, now_ms, s->take_pending ? 'T' : 'A');
    }
}

void Cwl_OnLocalMasterAbandon(CwlState_t *s, uint32_t now_ms) { AskVerdictNow(s, now_ms, true, 0); }
void Cwl_OnLocalMasterAbandonB(CwlState_t *s, uint8_t sb, uint32_t now_ms) { AskVerdictNow(s, now_ms, true, sb); }

void Cwl_OnLocalMasterCollision(CwlState_t *s, uint32_t now_ms)
{
    s->stats.master_collisions++;
    AskVerdictNow(s, now_ms, false, 0);
}

// ---------------------------------------------------------------- slave

// Offer the pending peer byte to the port. Normally only when the port said
// it is armed; `probe` offers anyway (the port answers NAK if not armed), so
// an ARMED event that was missed or never sent cannot stall the peer.
static void TryOfferAt(CwlState_t *s, uint32_t now_ms, bool probe)
{
    if (!s->pend_valid || s->offer_in_flight || (!s->local_armed && !probe)) return;
    if (!s->local_armed) s->stats.offer_probes++;
    s->offer_in_flight = true;
    s->last_offer_ms = now_ms;
    s->host.slave_offer(s->host.ctx, s->pend_byte);   // may call back synchronously
}

// ---- send-ahead, slave side (we lend our armed byte to the master)

static bool CanLend(const CwlState_t *s, uint32_t now_ms)
{
    return s->sa_on && s->sa_can_lock && s->peer_sa && s->host.slave_lock && s->host.slave_unlock &&
           s->have_seen_seq && Cwl_PeerAlive(s, now_ms);
}

// A lend resolved: remember whether the game rewrote the port first.
static void LendDone(CwlState_t *s, bool revoked)
{
    s->sa_hist = (uint8_t)(s->sa_hist << 1 | (revoked ? 1 : 0));
    if (s->sa_hist_n < 8) s->sa_hist_n++;
    int ones = 0;
    for (int i = 0; i < s->sa_hist_n; i++) ones += (s->sa_hist >> i) & 1;
    if (s->sa_hist_n >= 4 && ones * 2 >= s->sa_hist_n) {
        // This game keeps withdrawing an armed port after we lent it (Tetris
        // DX arms, then becomes master): each costs it a parked round trip.
        // Stop lending for a while.
        s->sa_backoff = 64;
        s->sa_hist = 0;
        s->sa_hist_n = 0;
    }
}

static void Unlock(CwlState_t *s)
{
    s->sa = kCwlSa_Idle;
    s->host.slave_unlock(s->host.ctx);
}

static void MaybeLock(CwlState_t *s, uint32_t now_ms)
{
    if (s->sa != kCwlSa_Idle || s->pend_valid || s->offer_in_flight || !CanLend(s, now_ms)) return;
    if (s->no_push_valid && s->no_push_t == (uint8_t)(s->newest_seen_seq + 1)) return;
    if (s->sa_backoff) {
        s->sa_backoff--;
        s->stats.sa_suppressed++;
        return;
    }
    s->sa = kCwlSa_Locking;
    s->sa_since_ms = now_ms;
    s->stats.sa_locks++;
    s->host.slave_lock(s->host.ctx);
}

void Cwl_OnLocalArmed(CwlState_t *s, uint32_t now_ms)
{
    s->local_armed = true;
    TryOfferAt(s, now_ms, false);
    MaybeLock(s, now_ms);
}

void Cwl_OnLocalDisarm(CwlState_t *s, uint32_t now_ms)
{
    (void)now_ms;
    s->local_armed = false;
}

void Cwl_OnLocalLocked(CwlState_t *s, bool ok, uint8_t sb, uint32_t now_ms)
{
    if (s->sa != kCwlSa_Locking) {
        // Not asked (any more): give it back.
        if (ok && s->host.slave_unlock) s->host.slave_unlock(s->host.ctx);
        return;
    }
    if (!ok) {
        s->sa = kCwlSa_Idle;        // the game withdrew before the lock landed
        s->stats.sa_lock_naks++;
        return;
    }
    s->local_armed = true;
    if (s->offer_in_flight || s->pend_valid) {
        // A peer request is (or will at once be) offered to this locked
        // port: that exchange ends the lock. Nothing to lend.
        s->sa = kCwlSa_Spent;
        TryOfferAt(s, now_ms, true);
        return;
    }
    const uint8_t t = (uint8_t)(s->newest_seen_seq + 1);
    if (!CanLend(s, now_ms) || (s->no_push_valid && s->no_push_t == t)) {
        Unlock(s);
        return;
    }
    s->sa = kCwlSa_Pushed;
    s->sa_t = t;
    s->sa_b = sb;
    s->sa_since_ms = now_ms;
    s->sa_last_send_ms = now_ms;
    s->stats.sa_pushes++;
    Send(s, kCwlType_Push, t, sb);
}

void Cwl_OnLocalSlaveAbandon(CwlState_t *s, uint32_t now_ms)
{
    switch (s->sa) {
    case kCwlSa_Pushed:
        // The game rewrites its port while the master may already have taken
        // the byte (and completed its game's transfer): the write waits
        // (parked) until the master says, as the CWL2 write barrier does.
        s->sa = kCwlSa_Revoking;
        s->stats.sa_revokes++;
        XAbandon(s, s->sa_b, now_ms, '?');
        XParkOpen(s, now_ms);
        s->sa_last_send_ms = now_ms;
        Send(s, kCwlType_Revoke, s->sa_t, 0);
        break;
    case kCwlSa_Spent:      // an offer is exchanging the port; the write lands after it
    case kCwlSa_Revoking:   // already asking
        break;
    case kCwlSa_Locking:    // nothing was lent yet (LOCKED lost?): just give it back
    case kCwlSa_Idle:
    default:
        if (s->host.slave_unlock) Unlock(s);
        break;
    }
}

static void Withdraw(CwlState_t *s, uint32_t now_ms);

void Cwl_OnLocalSlaveResult(CwlState_t *s, bool done, uint8_t sent_byte, uint32_t now_ms)
{
    (void)now_ms;
    if (!s->offer_in_flight) return;
    s->offer_in_flight = false;
    s->withdraw_sent = false;
    s->local_armed = false;   // completed, or found not armed
    const uint8_t seq = s->pend_seq;

    if (s->sa == kCwlSa_Spent) {
        // The offer exchanged our locked port (that ends the lock), or found
        // it gone (never: a locked port stays armed; give the lock back).
        const bool lent = s->sa_t == seq && s->stats.sa_pushes > 0 && s->sa_b_lent;
        if (done && lent) {
            s->stats.sa_taken++;
            if (sent_byte != s->sa_b) s->stats.sa_mismatch++;
            if (s->xs.req_open && s->xs.req_seq == seq) { s->xs.req_open = false; s->xs.req_pushed++; }
        }
        if (s->sa_was_revoking) XParkClose(s, now_ms, 'A');
        if (lent) LendDone(s, s->sa_was_revoking);
        s->sa = kCwlSa_Idle;
        s->sa_b_lent = false;
        s->sa_was_revoking = false;
        if (!done && s->host.slave_unlock) s->host.slave_unlock(s->host.ctx);
    }

    if (done) {
        s->pend_valid = false;
        s->cancel_after_offer = false;
        s->have_acked = true;
        s->acked_seq = seq;
        s->acked_byte = sent_byte;
        s->stats.slave_done++;
        XCount(s, now_ms);
        if (s->xs.req_open && s->xs.req_seq == seq) {
            s->xs.req_open = false;
            if (s->xs.req_open_armed) s->xs.req_armed++;
            else s->xs.req_wait[XBucket(NowUs(s, now_ms) - s->xs.req_open_us)]++;
        }
        Send(s, kCwlType_Ack, seq, sent_byte);
        return;
    }
    s->stats.slave_offers_refused++;
    if (s->cancel_after_offer) {
        s->cancel_after_offer = false;
        s->pend_valid = false;
        s->have_cancelled = true;
        s->cancelled_seq = seq;
        if (s->xs.req_open && s->xs.req_seq == seq) { s->xs.req_open = false; s->xs.req_refused++; }
        Send(s, kCwlType_Cancelled, seq, 0);
    }
    // otherwise keep the request pending until the port arms
}

// Standing offers: ask the port to give the offer back (it answers with the
// exchange if the game armed first).
static void Withdraw(CwlState_t *s, uint32_t now_ms)
{
    if (!s->standing || !s->offer_in_flight || s->withdraw_sent || !s->host.slave_withdraw) return;
    s->withdraw_sent = true;
    s->withdraw_ms = now_ms;
    s->host.slave_withdraw(s->host.ctx);
}

void Cwl_SetStandingOffers(CwlState_t *s, bool on)
{
    s->standing = on && s->host.slave_withdraw != NULL;
}

// A send-ahead side marks its session (HELLO) with the signature; the peer
// asks with CAPS only then.
static void SignSession(CwlState_t *s)
{
    if (s->sa_on) s->session = (uint16_t)((s->session & 0xFF00u) | kCwlSessionSig((uint8_t)(s->session >> 8)));
}

void Cwl_SetSendAhead(CwlState_t *s, bool on, bool can_lock)
{
    const bool was = s->sa_on;
    s->sa_on = on;
    s->sa_can_lock = on && can_lock && s->host.slave_lock != NULL && s->host.slave_unlock != NULL;
    if (on && !was) SignSession(s);     // the peer sees a new session (as after a restart)
}

static bool PeerSigned(const CwlState_t *s)
{
    return s->peer_session_known && (uint8_t)s->peer_session == kCwlSessionSig((uint8_t)(s->peer_session >> 8));
}

static void SendCaps(CwlState_t *s)
{
    Send(s, kCwlType_Caps, s->sa_on ? kCwlCap_SendAhead : 0, (uint8_t)s->session);
}

bool Cwl_SendAheadActive(const CwlState_t *s, uint32_t now_ms)
{
    return s->sa_on && s->peer_sa && Cwl_PeerAlive(s, now_ms);
}

// ---------------------------------------------------------------- network

void Cwl_Init(CwlState_t *s, const CwlHost_t *host, uint32_t now_ms)
{
    memset(s, 0, sizeof(*s));
    s->host = *host;
    s->master_timeout_ms = kCwlDefaultMasterTimeout_ms;
    s->push_wait_ok = true;
    s->last_hello_ms = now_ms - kCwlHello_ms;  // say hello on the first poll
    s->session = (uint16_t)(now_ms * 2654435761u >> 16);
    if (s->session == 0) s->session = 1;
    Cwl_XstatReset(s, now_ms);
}

void Cwl_SetSession(CwlState_t *s, uint16_t session)
{
    s->session = session ? session : 1;
    SignSession(s);
}

void Cwl_HoldForPeer(CwlState_t *s, uint32_t now_ms, uint32_t hold_ms)
{
    s->hold_start_ms = now_ms;
    s->hold_ms = hold_ms;
    s->last_hello_ms = now_ms - kCwlHello_ms;   // probe on the next poll
}

bool Cwl_Holding(const CwlState_t *s, uint32_t now_ms)
{
    return s->hold_ms != 0 && (now_ms - s->hold_start_ms) < s->hold_ms;
}

bool Cwl_Busy(const CwlState_t *s)
{
    return s->m_state != kCwlM_Idle || s->pend_valid || s->offer_in_flight || s->take_pending || s->sa != kCwlSa_Idle;
}

bool Cwl_PeerAlive(const CwlState_t *s, uint32_t now_ms)
{
    return s->peer_seen && (now_ms - s->peer_last_seen_ms) < kCwlPeerAlive_ms;
}

// A request or cancel newer than any seen: the master has finished every older
// sequence number, so the ACK / CANCELLED we remember for them is obsolete.
// Without this, after the 8-bit seq wraps, a CANCELLED remembered from 256
// requests earlier answered a brand-new request with the same number: the
// master got 0xFF after a full timeout instead of the exchange (Pokemon Crystal
// vs Gold: a handshake cancel at seq 2 struck again 256 bytes into the trade).
static void ForgetOlder(CwlState_t *s, uint8_t seq)
{
    if (s->have_seen_seq && !SeqNewer(seq, s->newest_seen_seq)) return;
    s->have_acked = false;
    s->have_cancelled = false;
}

// Send-ahead: the master moved past the seq our lent byte answers (it
// cancelled a request we never got, or its seq ran on while we did not hear
// it); it can no longer take it. Lend the still-locked port for the next
// seq, or give it back if the game is waiting to rewrite it.
static void LendRetarget(CwlState_t *s, uint32_t now_ms)
{
    if (s->sa != kCwlSa_Pushed && s->sa != kCwlSa_Revoking) return;
    if (SeqNewer(s->sa_t, s->newest_seen_seq)) return;   // still the next one
    if (s->sa == kCwlSa_Revoking) {
        XParkClose(s, now_ms, 'C');
        s->stats.sa_revoked++;
        LendDone(s, true);
        Unlock(s);
        return;
    }
    s->sa_t = (uint8_t)(s->newest_seen_seq + 1);
    s->sa_last_send_ms = now_ms;
    s->stats.sa_retargets++;
    Send(s, kCwlType_Push, s->sa_t, s->sa_b);
}

static void OnReq(CwlState_t *s, uint8_t seq, uint8_t byte, uint32_t now_ms, bool take)
{
    ForgetOlder(s, seq);
    if (s->have_acked && seq == s->acked_seq) {          // our ACK was lost
        Send(s, kCwlType_Ack, seq, s->acked_byte);
        return;
    }
    if (s->have_cancelled && seq == s->cancelled_seq) {  // our CANCELLED was lost
        Send(s, kCwlType_Cancelled, seq, 0);
        return;
    }
    if (s->have_seen_seq && !SeqNewer(seq, s->newest_seen_seq)) return;  // late duplicate
    const bool lent = (s->sa == kCwlSa_Pushed || s->sa == kCwlSa_Revoking);
    if (take && !(lent && seq == s->sa_t)) s->stats.sa_stray_take++;
    s->have_seen_seq = true;
    s->newest_seen_seq = seq;
    // A newer request replaces an older pending one (the master gave up on it
    // through CANCEL, which we answered or will answer).
    if (s->offer_in_flight) {
        // Cannot swap the byte under an in-flight offer; the master resends.
        // A standing offer for an older request is taken back first.
        s->newest_seen_seq = (uint8_t)(seq - 1);
        if (s->standing) {
            s->pend_valid = false;   // the master moved on: never re-offer the old byte
            Withdraw(s, now_ms);
        }
        return;
    }
    s->pend_valid = true;
    s->pend_seq = seq;
    s->pend_byte = byte;
    s->xs.req_open = true;
    s->xs.req_seq = seq;
    s->xs.req_open_us = NowUs(s, now_ms);
    s->xs.req_open_armed = s->local_armed || lent;
    if (lent) {
        // Our port is locked and armed: this exchanges it (the lent byte
        // answers this seq, or the master never took it and moved on).
        s->sa_b_lent = seq == s->sa_t;
        s->sa_was_revoking = s->sa == kCwlSa_Revoking;
        if (!s->sa_b_lent && s->sa_was_revoking) {
            // Not the seq we lent: the master never took it. The exchange
            // ends the lock; count the revoke resolved.
            s->stats.sa_revoked++;
        }
        s->sa_t = seq;
        s->sa = kCwlSa_Spent;
        TryOfferAt(s, now_ms, true);
        return;
    }
    TryOfferAt(s, now_ms, false);
}

static void OnCancel(CwlState_t *s, uint8_t seq, uint32_t now_ms)
{
    ForgetOlder(s, seq);
    if (s->have_acked && seq == s->acked_seq) {          // already exchanged: master must take it
        Send(s, kCwlType_Ack, seq, s->acked_byte);
        return;
    }
    if (s->pend_valid && s->pend_seq == seq) {
        if (s->offer_in_flight) {                        // the port decides first
            s->cancel_after_offer = true;
            Withdraw(s, now_ms);                         // a standing offer: take it back
            return;
        }
        s->pend_valid = false;
    }
    s->have_cancelled = true;
    s->cancelled_seq = seq;
    if (!s->have_seen_seq || SeqNewer(seq, s->newest_seen_seq)) {
        s->have_seen_seq = true;                         // a REQ that never arrived stays dead
        s->newest_seen_seq = seq;
    }
    if (s->xs.req_open && s->xs.req_seq == seq) { s->xs.req_open = false; s->xs.req_refused++; }
    s->stats.cancels++;
    Send(s, kCwlType_Cancelled, seq, 0);
    LendRetarget(s, now_ms);
}

// The peer restarted: its master sequence numbers start over, so forget what
// we remember about them (or its new requests would be ignored as old, or be
// answered with a stale ACK).
static void ForgetPeer(CwlState_t *s, uint32_t now_ms)
{
    s->stats.peer_resets++;
    Withdraw(s, now_ms);   // a standing offer of the old session must not exchange later
    s->pend_valid = false;
    s->cancel_after_offer = false;
    s->have_seen_seq = false;
    s->have_acked = false;
    s->have_cancelled = false;
    // send-ahead state of the old session
    s->peer_sa = false;
    s->peer_caps_known = false;
    s->caps_sent_n = 0;
    s->caps_replied = false;
    s->push_valid = false;
    s->revoked_valid = false;
    s->no_push_valid = false;
    if (s->take_pending) s->take_pending = false;
    if (s->m_state == kCwlM_WaitTake || s->m_state == kCwlM_WaitPush) MasterNoExchange(s, now_ms);
    if (s->sa == kCwlSa_Pushed || s->sa == kCwlSa_Revoking || s->sa == kCwlSa_Locking) {
        if (s->sa == kCwlSa_Revoking) XParkClose(s, now_ms, 'C');
        Unlock(s);
    }
}

// As master: the slave asks to take back the byte it lent for seq.
static void OnRevoke(CwlState_t *s, uint8_t seq, uint32_t now_ms)
{
    (void)now_ms;
    if (s->take_pending && seq == s->take_t) {
        // Too late, we took it: our byte must reach its port (it is parked
        // there waiting for exactly this).
        s->take_last_send_ms = now_ms;
        Send(s, kCwlType_Take, s->take_t, s->take_b);
        return;
    }
    if (s->push_valid && s->push_t == seq) s->push_valid = false;
    if (seq == (uint8_t)(s->m_seq + 1)) {
        s->revoked_valid = true;      // a late copy of its PUSH must not be taken
        s->revoked_t = seq;
    }
    Send(s, kCwlType_Revoked, seq, 0);
}

bool Cwl_OnPacket(CwlState_t *s, const uint8_t *pkt, size_t len, uint32_t now_ms)
{
    if (len != kCwlPacketLen || pkt[0] != 'C' || pkt[1] != 'W' || pkt[2] != kCwlVersion) {
        s->stats.rx_bad++;
        return false;
    }
    s->stats.rx_packets++;
    s->peer_seen = true;
    s->peer_last_seen_ms = now_ms;

    const uint8_t type = pkt[3], seq = pkt[4], byte = pkt[5];
    switch (type) {
    case kCwlType_Req:
        OnReq(s, seq, byte, now_ms, false);
        break;
    case kCwlType_Take:
        OnReq(s, seq, byte, now_ms, true);
        break;
    case kCwlType_Cancel:
        OnCancel(s, seq, now_ms);
        break;
    case kCwlType_Ack:
        if (s->take_pending && seq == s->take_t) {
            // Our TAKE reached the slave's port. A transfer the game started
            // meanwhile goes out now, or, if the slave has been lending its
            // re-armed port, once its next PUSH is here (it follows the ACK
            // by the slave game's re-arm and a UART round trip; a REQ would
            // cost a whole network round trip).
            s->take_pending = false;
            if (s->m_state == kCwlM_WaitTake) {
                s->m_state = kCwlM_Idle;
                if (!Cwl_PeerAlive(s, now_ms)) break;
                if (PushUsable(s) || !s->push_wait_ok) {
                    StartMaster(s, s->m_byte, now_ms);
                } else {
                    s->m_state = kCwlM_WaitPush;
                    s->push_wait_ms = now_ms;
                    s->stats.sa_wait_push++;
                }
            }
            break;
        }
        if ((s->m_state == kCwlM_Requesting || s->m_state == kCwlM_Cancelling) && seq == s->m_seq) {
            s->stats.master_done++;
            XCount(s, now_ms);
            XRttClose(s, seq, now_ms);
            XParkClose(s, now_ms, 'A');
            MasterFinish(s, byte, now_ms);
        }
        break;
    case kCwlType_Cancelled:
        if (s->m_state == kCwlM_Cancelling && seq == s->m_seq) {
            XRttClose(s, seq, now_ms);
            MasterNoExchange(s, now_ms);
        }
        break;
    case kCwlType_Hello:
    case kCwlType_HelloProbe: {
        if (type == kCwlType_HelloProbe) {
            // The peer just woke its radio: tell it we are here now rather
            // than at our next keepalive (it may be holding a master for us).
            s->stats.probes_answered++;
            s->last_hello_ms = now_ms;
            Send(s, kCwlType_Hello, (uint8_t)(s->session >> 8), (uint8_t)s->session);
        }
        const uint16_t session = (uint16_t)(seq << 8 | byte);   // 0: peer without sessions
        if (session != 0) {
            // A new session, or the first one we learn (what we remember may
            // be from a peer without sessions, e.g. an older build).
            if (!s->peer_session_known || session != s->peer_session) ForgetPeer(s, now_ms);
            s->peer_session_known = true;
            s->peer_session = session;
        }
        if (s->sa_on && PeerSigned(s) && !s->peer_caps_known && s->caps_sent_n < kCwlCapsTries) {
            s->caps_sent_n++;
            SendCaps(s);
        }
        break;
    }
    case kCwlType_Caps:
        // Capabilities of the peer's current session (CWL2 peers never send it).
        if (s->peer_session_known && byte == (uint8_t)s->peer_session) {
            s->peer_caps_known = true;
            s->peer_sa = (seq & kCwlCap_SendAhead) != 0;
            if (!s->caps_replied) {
                s->caps_replied = true;
                SendCaps(s);
            }
        }
        break;
    case kCwlType_Push:
        if (!s->sa_on) break;
        if (seq == (uint8_t)(s->m_seq + 1) && !(s->revoked_valid && s->revoked_t == seq)) {
            s->push_valid = true;
            s->push_t = seq;
            s->push_b = byte;
            if (s->take_pending && s->take_t == s->m_seq) {
                // A PUSH for the next seq proves our TAKE was delivered (the
                // slave lends for newest seen + 1, and a TAKE is never
                // cancelled): no need to wait for its ACK.
                s->take_pending = false;
            }
            if (s->m_state == kCwlM_WaitTake || s->m_state == kCwlM_WaitPush) {
                if (s->m_state == kCwlM_WaitPush) s->stats.sa_wait_push_hit++;
                s->push_wait_ok = true;
                s->m_state = kCwlM_Idle;
                if (Cwl_PeerAlive(s, now_ms)) StartMaster(s, s->m_byte, now_ms);
                else MasterNoExchange(s, now_ms);
            }
        } else {
            s->stats.sa_stale++;
        }
        break;
    case kCwlType_Revoke:
        OnRevoke(s, seq, now_ms);
        break;
    case kCwlType_Revoked:
        if (s->sa == kCwlSa_Revoking && seq == s->sa_t) {
            s->stats.sa_revoked++;
            s->no_push_valid = true;      // never lend for this seq again
            s->no_push_t = seq;
            XParkClose(s, now_ms, 'C');
            LendDone(s, true);
            Unlock(s);                    // the parked write lands
        }
        break;
    default:
        break;
    }
    return true;
}

void Cwl_Poll(CwlState_t *s, uint32_t now_ms)
{
    if (s->m_state == kCwlM_WaitPeer) {
        if (Cwl_PeerAlive(s, now_ms)) {
            // The peer is back: the request starts now (its timeout too).
            s->m_start_ms = now_ms;
            StartMaster(s, s->m_byte, now_ms);
        } else if (!Cwl_Holding(s, now_ms)) {
            s->stats.master_timeouts++;     // nobody came: unplugged
            MasterFinish(s, 0xFF, now_ms);
        }
    }
    if (s->m_state == kCwlM_Requesting && (now_ms - s->m_start_ms) >= s->master_timeout_ms) {
        s->m_state = kCwlM_Cancelling;
        s->m_last_send_ms = now_ms - kCwlResend_ms;   // send CANCEL now
    }
    if ((s->m_state == kCwlM_Requesting || s->m_state == kCwlM_Cancelling) &&
        (now_ms - s->m_last_send_ms) >= kCwlResend_ms) {
        s->m_last_send_ms = now_ms;
        if (s->m_state == kCwlM_Requesting) Send(s, kCwlType_Req, s->m_seq, s->m_byte);
        else Send(s, kCwlType_Cancel, s->m_seq, 0);
    }
    if (s->m_state == kCwlM_Cancelling && !Cwl_PeerAlive(s, now_ms)) {
        // The peer is gone; nobody can disagree any more.
        MasterNoExchange(s, now_ms);
    }
    if (s->m_state == kCwlM_WaitPush && (now_ms - s->push_wait_ms) >= kCwlPushWait_ms) {
        // The slave did not lend its port in time: ask it the CWL2 way.
        s->push_wait_ok = false;
        s->m_state = kCwlM_Idle;
        if (Cwl_PeerAlive(s, now_ms)) StartMaster(s, s->m_byte, now_ms);
        else MasterNoExchange(s, now_ms);
    }
    // send-ahead, master: our TAKE must reach the slave (no timeout: the
    // exchange already happened here).
    if (s->take_pending) {
        if (!Cwl_PeerAlive(s, now_ms)) {
            s->take_pending = false;
            if (s->m_state == kCwlM_WaitTake) MasterNoExchange(s, now_ms);
        } else if ((now_ms - s->take_last_send_ms) >= kCwlResend_ms) {
            s->take_last_send_ms = now_ms;
            Send(s, kCwlType_Take, s->take_t, s->take_b);
        }
    }
    // send-ahead, slave
    if (s->sa == kCwlSa_Locking && (now_ms - s->sa_since_ms) >= kCwlLockTimeout_ms) {
        s->sa = kCwlSa_Idle;     // never answered: an answer that comes later is given back
    }
    if ((s->sa == kCwlSa_Pushed || s->sa == kCwlSa_Revoking) && !Cwl_PeerAlive(s, now_ms)) {
        // The master went away: nobody can take the byte any more.
        if (s->sa == kCwlSa_Revoking) XParkClose(s, now_ms, 'C');
        Unlock(s);
    } else if ((s->sa == kCwlSa_Pushed || s->sa == kCwlSa_Revoking) && (now_ms - s->sa_last_send_ms) >= kCwlResend_ms) {
        s->sa_last_send_ms = now_ms;
        if (s->sa == kCwlSa_Pushed) Send(s, kCwlType_Push, s->sa_t, s->sa_b);
        else Send(s, kCwlType_Revoke, s->sa_t, 0);
    }
    if (s->offer_in_flight && !s->standing && (now_ms - s->last_offer_ms) >= kCwlOfferTimeout_ms) {
        // The port never answered (lost UART event): treat it as not armed
        // rather than leaving the peer master waiting forever.
        Cwl_OnLocalSlaveResult(s, false, 0, now_ms);
    }
    if (s->offer_in_flight && s->withdraw_sent && (now_ms - s->withdraw_ms) >= kCwlOfferTimeout_ms) {
        // A standing offer whose withdrawal was never answered (lost on the
        // UART, or an FPGA without standing offers): count it as not taken.
        Cwl_OnLocalSlaveResult(s, false, 0, now_ms);
    }
    if (s->offer_in_flight && s->standing && !s->withdraw_sent && !s->pend_valid) Withdraw(s, now_ms);
    if (s->offer_in_flight && s->standing && !s->withdraw_sent && !Cwl_PeerAlive(s, now_ms)) {
        // The peer went away with its request standing at our port.
        s->cancel_after_offer = true;
        Withdraw(s, now_ms);
    }
    if (s->pend_valid && !s->offer_in_flight && !Cwl_PeerAlive(s, now_ms)) {
        // The peer went away with its request still waiting for our port: its
        // master has long given up (0xFF). Stop offering it; if the peer comes
        // back and resends that seq it is ignored as old, and its CANCEL gets
        // CANCELLED (never exchanged).
        s->pend_valid = false;
        s->cancel_after_offer = false;
        s->stats.dropped_requests++;
    }
    if (s->pend_valid && !s->offer_in_flight && (now_ms - s->last_offer_ms) >= kCwlResend_ms) {
        TryOfferAt(s, now_ms, true);
    }
    const bool probing = Cwl_Holding(s, now_ms) && !Cwl_PeerAlive(s, now_ms);
    if ((now_ms - s->last_hello_ms) >= (probing ? (uint32_t)kCwlProbe_ms : (uint32_t)kCwlHello_ms)) {
        s->last_hello_ms = now_ms;
        Send(s, probing ? kCwlType_HelloProbe : kCwlType_Hello, (uint8_t)(s->session >> 8), (uint8_t)s->session);
    }
}
