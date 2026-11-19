// cwl_proto.h: CWL2 (Chromatic Wi-Fi Link) protocol core.
//
// Portable C, no OS calls: the host supplies time and I/O through callbacks.
// Shared by the Chromatic ESP32 firmware and the desktop emulator peer, so
// both ends run identical protocol logic. See PROTOCOL.md.
//
// The slave side is authoritative: a master byte is exchanged only when the
// slave's link port actually accepted it (it was armed), and the master
// completes only with the slave's acknowledgement. A master that gives up
// cancels and waits for the slave's verdict, so the two games can never
// disagree about whether a byte was exchanged.
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

enum {
    kCwlPort = 47820,
    kCwlPacketLen = 6,
    kCwlVersion = 2,

    kCwlType_Req = 1,        // master -> slave: seq, master byte
    kCwlType_Ack = 2,        // slave -> master: seq, slave byte (exchange done)
    kCwlType_Cancel = 3,     // master -> slave: seq (master wants to give up)
    kCwlType_Cancelled = 4,  // slave -> master: seq (exchange did NOT happen)
    kCwlType_Hello = 5,
    kCwlType_HelloProbe = 6, // like HELLO, and asks the peer to answer with a HELLO now
    // Send-ahead (PROTOCOL.md "Send-ahead"); only between two sides that both
    // advertise kCwlCap_SendAhead. A CWL2 peer ignores these types.
    // CAPS: seq = capability bits, byte = low byte of the sender's session.
    // Sent only to a peer whose HELLO session carries the send-ahead
    // signature (low byte = kCwlSessionSig(high byte)), at most kCwlCapsTries
    // times per peer session, and once in answer to the peer's CAPS: a CWL2
    // peer (which counts any non-HELLO packet as link use, keeping its radio
    // at full power) sees at most a few, only on a 1 in 256 session match.
    kCwlType_Caps = 7,
    kCwlType_Push = 8,       // slave -> master: my locked port's byte; seq = the master seq it may answer
    kCwlType_Take = 9,       // master -> slave: I took the pushed byte for seq; byte = master byte (deliver it)
    kCwlType_Revoke = 10,    // slave -> master: my game wants to rewrite the port; give up seq if not taken
    kCwlType_Revoked = 11,   // master -> slave: seq was not taken and never will be

    kCwlCap_SendAhead = 1 << 0,

    kCwlResend_ms = 20,
    kCwlHello_ms = 500,
    kCwlPeerAlive_ms = 2000,
    kCwlDefaultMasterTimeout_ms = 250,
    kCwlOfferTimeout_ms = 500,   // a port that never answers an offer counts as not armed
    kCwlProbe_ms = 100,          // HELLO_PROBE interval while holding for the peer
    kCwlReplyInFlight_ms = 10,   // a reply sent this recently may still be on its way to the port
    kCwlLockTimeout_ms = 500,    // a LOCK the port never answers counts as refused
    kCwlCapsTries = 10,
    kCwlPushWait_ms = 8,         // after a TAKE's ACK, wait this long for the slave's next PUSH before a REQ
};

typedef struct CwlHost {
    void *ctx;
    // Send one 6-byte packet to the current peer (or broadcast if none yet).
    void (*send)(void *ctx, const uint8_t pkt[kCwlPacketLen]);
    // Complete the local held master transfer with the peer's byte.
    void (*master_reply)(void *ctx, uint8_t byte);
    // Offer the peer master's byte to the local link port. The host answers
    // with Cwl_OnLocalSlaveResult(): done (armed, exchange completed, with
    // the byte the port shifted out) or not (port not armed). It may answer
    // from inside this call or later.
    void (*slave_offer)(void *ctx, uint8_t byte);
    // Optional. The game abandoned its held master (Cwl_OnLocalMasterAbandon)
    // and the peer did not exchange: release the transfer WITHOUT completing
    // it (no IRQ, SB untouched). Completing it with 0xFF raised the serial IRQ
    // after the game's own SB write, so the handler read that byte as the
    // peer's: Pokemon Gold/Silver read its own 0x02 as "partner is slave" on
    // both sides and both became master. NULL: master_reply(0xFF).
    void (*master_dropped)(void *ctx);
    // Optional, used with Cwl_SetStandingOffers(true): take back the offer
    // that is still standing at the port. The host answers with
    // Cwl_OnLocalSlaveResult(): done (the port exchanged it before the
    // withdrawal arrived) or not (withdrawn).
    void (*slave_withdraw)(void *ctx);
    // Optional, send-ahead (FPGA 18.22+, Cwl_SetSendAhead): lock the armed
    // slave port. While locked the game's SB/SC writes are parked (CPU
    // stalled) and reported (Cwl_OnLocalSlaveAbandon); an offer exchanges
    // the locked port and ends the lock. The host answers slave_lock with
    // Cwl_OnLocalLocked(): ok (the port was armed: locked, with its SB) or not.
    void (*slave_lock)(void *ctx);
    // End the lock: a parked write lands, the CPU runs on. No answer.
    void (*slave_unlock)(void *ctx);
    // Optional: microseconds (any epoch, wraps) for xstat's sub-ms histograms;
    // NULL: milliseconds * 1000.
    uint32_t (*now_us)(void *ctx);
} CwlHost_t;

typedef struct CwlStats {
    uint32_t master_done;      // master transfers completed with a peer byte
    uint32_t master_timeouts;  // master transfers that read 0xFF
    uint32_t slave_done;       // slave transfers completed
    uint32_t slave_offers_refused;
    uint32_t cancels;
    uint32_t rx_packets;
    uint32_t rx_bad;
    uint32_t max_master_wait_ms;
    uint32_t master_restarts;  // game restarted a transfer still in flight (continued)
    uint32_t offer_probes;     // offers made without an ARMED hint
    uint32_t peer_resets;      // peer restarted (new session): slave state dropped
    uint32_t held_masters;     // masters that waited for the peer after a radio wake
    uint32_t probes_answered;  // HELLO_PROBEs answered with a HELLO
    uint32_t dropped_requests; // peer requests dropped because the peer went away
    uint32_t master_abandons;  // game rewrote SB/SC under a held master: verdict asked at once
    uint32_t master_collisions;// both sides held masters with stalled CPUs: verdict asked at once
    uint32_t unknown_abandons; // ABANDON for a master we never heard of (its MASTER event was lost): dropped
    // send-ahead
    uint32_t sa_locks;         // LOCK asked
    uint32_t sa_lock_naks;     // ... the game had already withdrawn
    uint32_t sa_pushes;        // PUSHes started (a locked port lent to the peer)
    uint32_t sa_taken;         // as slave: a pushed byte delivered (TAKE, or a REQ for it)
    uint32_t sa_revokes;       // as slave: the game rewrote a lent port (CPU parked until the verdict)
    uint32_t sa_revoked;       // ... and the master had not taken it
    uint32_t sa_retargets;     // as slave: a lent port re-pushed for a newer seq (the old one was cancelled)
    uint32_t sa_suppressed;    // arms not lent because recent pushes kept being revoked
    uint32_t sa_mismatch;      // as slave: the port shifted out another byte than the one lent (must stay 0)
    uint32_t sa_stray_take;    // as slave: a TAKE for a seq we never lent (must stay 0)
    uint32_t sa_used;          // as master: transfers completed at once from a pushed byte
    uint32_t sa_stale;         // as master: pushes dropped (old seq, revoked, or arrived mid-request)
    uint32_t sa_wait_take;     // as master: a new transfer held until the previous TAKE was acknowledged
    uint32_t sa_wait_push;     // as master: ... then for the slave's next PUSH
    uint32_t sa_wait_push_hit; // ... which came in time
} CwlStats_t;

// xstat (console `cwl xstat`): what a game's link traffic looks like, to see
// where a link costs frames. Times are measured where the core runs (the
// ESP32): add ~1-2 ms of UART for the port's view.
enum {
    kCwlX_Buckets = 5,           // <1, <8, <17, <33, >=33 ms
    kCwlX_RttBuckets = 8,        // <2, <4, <8, <17, <33, <67, <150, >=150 ms
    kCwlX_Windows = 8,           // exchanges per 1 s, last windows
    kCwlX_Abandons = 8,          // last abandons kept
};
typedef struct {
    uint32_t at_ms;              // since the xstat reset
    uint32_t since_start_us;     // master start -> the game's rewrite
    uint32_t park_us;            // rewrite -> verdict (CPU parked)
    uint8_t sb;                  // the event byte (SB under the held master)
    uint8_t outcome;             // 'A' ACK (exchanged), 'C' CANCELLED (dropped), 'N' nothing sent (dropped), 'U' unknown master, 'T' taken (send-ahead), '?' open
} CwlXAbandon_t;
typedef struct {
    uint32_t reset_ms;
    // exchanges (as master or slave, with the partner) per 1 s window
    uint32_t win_start_ms, win_n, win_count;
    uint16_t win[kCwlX_Windows];
    uint16_t win_max;
    uint32_t windows;
    // master hold (MASTER event -> reply / drop) and CPU park (ABANDON -> verdict)
    uint32_t hold[kCwlX_Buckets], park[kCwlX_Buckets];
    uint64_t hold_us_sum, park_us_sum;
    uint32_t hold_start_us;
    bool hold_open;
    uint32_t park_start_us;
    bool park_open;
    // frames (16.74 ms windows) in which the CPU was parked more than half
    uint32_t frame_idx;          // window index of frame_park_us
    uint32_t frame_park_us;
    uint32_t frames_half_parked;
    CwlXAbandon_t ab[kCwlX_Abandons];
    uint32_t ab_n;
    // slave readiness when a peer request arrives
    uint32_t req_armed;          // the port was armed: exchanged at once
    uint32_t req_wait[kCwlX_Buckets];   // the port armed after this long
    uint32_t req_refused;        // the master gave up first (CANCELLED)
    uint32_t req_pushed;         // the request found our port lent (send-ahead): exchanged at once
    uint32_t req_open_us;
    bool req_open, req_open_armed;
    uint8_t req_seq;
    // exchange round trip (REQ first sent -> ACK / CANCELLED)
    uint32_t rtt[kCwlX_RttBuckets];
    uint32_t rtt_max_ms;
    uint32_t rtt_open_ms;
    bool rtt_open;
    uint8_t rtt_seq;
} CwlXstat_t;

typedef enum {
    kCwlM_Idle,
    kCwlM_Requesting,
    kCwlM_Cancelling,
    kCwlM_WaitPeer,      // held: the radio is coming up, the peer not seen yet (Cwl_HoldForPeer)
    kCwlM_WaitTake,      // held: send-ahead, the previous transfer's TAKE is not acknowledged yet
    kCwlM_WaitPush,      // held: send-ahead, the TAKE was acknowledged; the slave's next lent byte is due
} CwlMasterState_t;

typedef enum {
    kCwlSa_Idle,
    kCwlSa_Locking,      // LOCK sent to the port, no answer yet
    kCwlSa_Pushed,       // the locked port's byte is lent to the master for seq sa_t
    kCwlSa_Revoking,     // the game wrote under the lock (parked): asking the master
    kCwlSa_Spent,        // the locked port is being exchanged by an offer (no push)
} CwlSaState_t;

typedef struct CwlState {
    CwlHost_t host;
    uint32_t master_timeout_ms;

    // local master
    CwlMasterState_t m_state;
    uint8_t m_seq;
    uint8_t m_byte;
    uint32_t m_start_ms;
    uint32_t m_last_send_ms;
    bool m_abandoned;          // the game moved on: a CANCELLED verdict drops it silently
    bool m_replied;            // a master was completed or dropped at m_reply_ms
    uint32_t m_reply_ms;

    // local slave (serving the peer's master)
    bool local_armed;          // hint from the link port (ARMED / DISARM events)
    bool pend_valid;           // a peer request waiting for our port to arm
    uint8_t pend_seq;
    uint8_t pend_byte;
    bool offer_in_flight;
    bool cancel_after_offer;
    bool have_seen_seq;
    uint8_t newest_seen_seq;   // newest peer request seq seen
    bool have_acked;
    uint8_t acked_seq, acked_byte;
    bool have_cancelled;
    uint8_t cancelled_seq;
    uint32_t last_offer_ms;
    // Standing offers (Cwl_SetStandingOffers): an offer stays at the port
    // until the game arms (exchanged at once) or we withdraw it.
    bool standing;
    bool withdraw_sent;
    uint32_t withdraw_ms;

    // sessions: each side announces a random id in HELLO; a new id means the
    // peer restarted and our memory of its sequence numbers is stale
    uint16_t session;
    bool peer_session_known;
    uint16_t peer_session;

    // peer
    uint32_t peer_last_seen_ms;
    bool peer_seen;
    uint32_t last_hello_ms;

    // radio wake (Cwl_HoldForPeer)
    uint32_t hold_start_ms;
    uint32_t hold_ms;          // 0: not holding
    CwlStats_t stats;

    // send-ahead (Cwl_SetSendAhead), PROTOCOL.md "Send-ahead"
    bool sa_on;                // we speak it (advertised in CAPS)
    bool sa_can_lock;          // ... and our port can be locked (FPGA 18.22+): we may push
    bool peer_sa;              // the current peer advertised it (CAPS)
    bool peer_caps_known;      // a CAPS of the current peer session arrived
    uint8_t caps_sent_n;       // CAPS sent to this peer session (bounded: a CWL2 peer never answers)
    bool caps_replied;
    // as master
    bool push_valid;           // a pushed slave byte for seq push_t
    uint8_t push_t, push_b;
    bool revoked_valid;        // the slave revoked seq revoked_t: never take it
    uint8_t revoked_t;
    bool take_pending;         // TAKE(take_t, take_b) sent, not acknowledged
    uint8_t take_t, take_b;
    uint32_t take_last_send_ms;
    uint32_t push_wait_ms;     // WaitPush since
    bool push_wait_ok;         // the last WaitPush got its PUSH (else do not wait next time)
    // as slave
    CwlSaState_t sa;
    uint8_t sa_t, sa_b;        // lent byte and the seq it answers
    bool sa_b_lent;            // Spent: the offer exchanging the port is for the seq we lent
    bool sa_was_revoking;      // Spent: the game's write is parked behind it
    uint32_t sa_since_ms, sa_last_send_ms;
    bool no_push_valid;        // a seq we revoked: never lend for it again
    uint8_t no_push_t;
    uint8_t sa_hist;           // last 8 lends, bit = revoked (newest in bit 0)
    uint8_t sa_hist_n;
    uint16_t sa_backoff;       // arms left not lent (revokes kept happening)

    CwlXstat_t xs;
} CwlState_t;

static inline uint8_t kCwlSessionSig(uint8_t hi) { return (uint8_t)(hi * 7u + 0x5Au); }

void Cwl_Init(CwlState_t *s, const CwlHost_t *host, uint32_t now_ms);
// A random non-zero id for this run (call after Cwl_Init; hosts have their own RNG).
void Cwl_SetSession(CwlState_t *s, uint16_t session);
// The port keeps an offer it cannot take yet and exchanges it the moment the
// game arms (FPGA 18.20+, CONFIG bit 3); the host must provide slave_withdraw.
// Without it an offer is answered at once, so a slave that arms only briefly
// (Pokemon Gold/Silver arm for ~20 us per frame while both sides search for a
// partner) can never take a request that crosses the UART and Wi-Fi.
void Cwl_SetStandingOffers(CwlState_t *s, bool on);
bool Cwl_PeerAlive(const CwlState_t *s, uint32_t now_ms);
// Send-ahead: on = we speak it (CAPS); can_lock = our port can be locked
// (host provides slave_lock / slave_unlock, FPGA 18.22+), so we may lend our
// armed byte. Used only while the peer advertises it too.
void Cwl_SetSendAhead(CwlState_t *s, bool on, bool can_lock);
bool Cwl_SendAheadActive(const CwlState_t *s, uint32_t now_ms);
// Answer to slave_lock: ok = the port was still armed and is now locked, sb =
// the byte it will shift out.
void Cwl_OnLocalLocked(CwlState_t *s, bool ok, uint8_t sb, uint32_t now_ms);
// The game wrote SB/SC under the lock: the write is parked, the CPU stalled.
void Cwl_OnLocalSlaveAbandon(CwlState_t *s, uint32_t now_ms);

// Local link-port events.
void Cwl_OnLocalArmed(CwlState_t *s, uint32_t now_ms);
void Cwl_OnLocalDisarm(CwlState_t *s, uint32_t now_ms);
void Cwl_OnLocalMaster(CwlState_t *s, uint8_t byte, uint32_t now_ms);
void Cwl_OnLocalSlaveResult(CwlState_t *s, bool done, uint8_t sent_byte, uint32_t now_ms);
// The game wrote SB or SC (other than restarting the same master) while its
// master transfer is held. On hardware the transfer would long be complete,
// so the port stalls the CPU until it is; this asks the peer for its verdict
// now (CANCEL: ACK if the peer exchanged, else CANCELLED -> 0xFF) instead of
// waiting out the master timeout. The reply arrives through master_reply().
void Cwl_OnLocalMasterAbandon(CwlState_t *s, uint32_t now_ms);
// The same, with the event's byte for xstat.
void Cwl_OnLocalMasterAbandonB(CwlState_t *s, uint8_t sb, uint32_t now_ms);
// The port refused the peer's byte because it holds a master with the CPU
// stalled (fast clock): both sides are masters and neither CPU can arm, so
// waiting out the master timeout would freeze both games for it. Ask the
// verdict now; nobody exchanged, so both masters complete with 0xFF (IRQ),
// what two masters clocking at once read on hardware.
void Cwl_OnLocalMasterCollision(CwlState_t *s, uint32_t now_ms);

// A packet from the network. Returns false if it was not a CWL2 packet.
bool Cwl_OnPacket(CwlState_t *s, const uint8_t *pkt, size_t len, uint32_t now_ms);

// The host's radio is (re)starting (a link-port event woke it from a power
// save state where the peer could not be heard). For the next hold_ms a local
// master waits for the peer instead of reading 0xFF at once ("unplugged"),
// and HELLO_PROBEs go out every kCwlProbe_ms so the peer answers at once
// rather than at its next keepalive. The host keeps hold_ms + master timeout
// under the FPGA's ~4 s safety timeout.
void Cwl_HoldForPeer(CwlState_t *s, uint32_t now_ms, uint32_t hold_ms);
bool Cwl_Holding(const CwlState_t *s, uint32_t now_ms);
// True while an exchange is in progress (a local master in flight, or a peer
// request pending or being offered): the host must not power the radio down.
bool Cwl_Busy(const CwlState_t *s);

// Call every few milliseconds: resends, keepalive, master timeout.
void Cwl_Poll(CwlState_t *s, uint32_t now_ms);

// xstat: reset, and one human-readable report (returns the length written).
void Cwl_XstatReset(CwlState_t *s, uint32_t now_ms);
int Cwl_XstatFormat(const CwlState_t *s, char *buf, int len, uint32_t now_ms);

#ifdef __cplusplus
}
#endif
