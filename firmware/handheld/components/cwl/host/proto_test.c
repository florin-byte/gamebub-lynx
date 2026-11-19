// cwl_proto_test.c: two CWL2 peers over a simulated network (delay, jitter,
// loss, duplication) running scripted "games".
//
// Every completed exchange is logged on both sides: the master logs
// (its byte, peer byte), the slave logs (byte received, byte it sent). The
// two logs must be identical, in order: that is the property a link cable
// gives and that games depend on.
//
// Modes:
//   fixed  - peer 0 is always master, peer 1 always slave (counter check)
//   chaos  - both peers arm as slave, wait a random time, withdraw and
//            become master (Tetris DX-style role switching), forever
//
// Options per run:
//   kick     - a waiting master restarts its transfer every 16 ms (Tetris DX
//              writes SC = 0x81 every frame until the first byte completes)
//   lost_arm - half of the ARMED hints never reach the protocol (game armed
//              before the link came up, ESP reset, lost UART event)
//   restart  - a peer restarts its protocol (new session) mid-run
//   nosess   - ... and before the restart it had no session id (older build)
//   sleep    - every 5 s peer 0's player stops (an armed port disarms), and
//              once its protocol has been idle for 2.5 s it powers the radio
//              off (CWL dormant) for 2.5 s; its next master wakes it: the
//              radio needs 700 ms to
//              come back, the protocol holds the master for the peer
//              (Cwl_HoldForPeer) instead of reading 0xFF
//   nohold   - sleep without the hold (the old behaviour): reads 0xFF
//   abandon  - a master gives up 1-15 ms after starting (the game rewrites
//              SB/SC; the port stalls the CPU and asks for the verdict): it
//              must take the peer's byte if the peer exchanged, else be
//              dropped silently, and both logs must still agree
//   collide  - masters stall the CPU (fast clock): a peer request refused
//              by a port holding a master reports a collision; both masters
//              must resolve to 0xFF at once instead of the master timeout
//   lostmaster - with abandon: a quarter of the MASTER events never reach
//              the protocol (the FPGA's 4-deep event queue overflowed on
//              Pokemon Gold's partner search), and those masters are always
//              abandoned: the protocol must release them at once (no request
//              went out, nobody exchanged) instead of leaving the CPU stalled
//   standing - the port keeps an offer it cannot take (not armed) and
//              exchanges it the moment the game arms (FPGA 18.20); the
//              protocol withdraws it on CANCEL / peer restart / peer gone
//   brief    - chaos: a game arms and becomes master in the same instant
//              (Pokemon Gold/Silver partner search: armed ~20 us per frame);
//              only standing offers can ever land a peer request there
//   early    - the slave arms only 400 ms in, so the first request is
//              cancelled; the run must then wrap the 8-bit seq many times
//              without another timeout (a stale CANCELLED from 256 requests
//              earlier must not answer a new request with the same number)
//
// cc -O2 -I../mcu/components/cwl cwl_proto_test.c ../mcu/components/cwl/cwl_proto.c -o cwl_proto_test
#include "cwl_proto.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef struct { uint32_t due; int to; uint8_t pkt[kCwlPacketLen]; } Msg;
static Msg net[8192];
static int net_n = 0;
static uint32_t now = 0;
static int delay_ms, jitter_ms, loss_pct, dup_pct;

enum { kIdle, kArmed, kMaster };

typedef struct Peer {
    int id;
    CwlState_t cwl;
    int state;
    uint8_t sb;               // byte in SB while armed / master byte
    uint32_t until;           // chaos: when an armed wait gives up
    uint16_t log[200000];     // (master byte << 8) | slave byte
    int log_n;
    int timeouts;
    int completed;
    int drops;
    int abandoned;
    uint32_t abandon_at;
    int st_pend;              // standing: an offer waits at the port
    uint8_t st_byte;
    int lost;                 // lostmaster: this master's MASTER event was lost
    int lost_masters;
    uint32_t max_stall;       // longest abandoned (CPU-stalled) master, ms
    uint32_t next_kick;
    // sleep option
    int radio_on;
    uint32_t radio_on_at;     // 0: no wake in progress
    uint32_t sleep_until;     // game paused (dormant) until then
    int wakes;
    int want_sleep;
    uint32_t quiet_since;
} Peer;
static Peer *peers[2];
static int chaos = 0, kick = 0, lost_arm = 0, sleepy = 0, hold = 0, early = 0, abandon = 0, collide = 0, lost_master = 0, standing = 0, brief = 0;

static void enqueue(int to, const uint8_t *pkt)
{
    if (!peers[to]->radio_on || !peers[1 - to]->radio_on) return;   // radio off: lost
    if ((rand() % 100) < loss_pct) return;
    int copies = ((rand() % 100) < dup_pct) ? 2 : 1;
    for (int c = 0; c < copies && net_n < 8192; c++) {
        Msg *m = &net[net_n++];
        m->due = now + delay_ms + (jitter_ms ? rand() % (jitter_ms + 1) : 0);
        m->to = to;
        memcpy(m->pkt, pkt, kCwlPacketLen);
    }
}

static void host_send(void *ctx, const uint8_t pkt[kCwlPacketLen]) { enqueue(1 - ((Peer *)ctx)->id, pkt); }

static void host_master_reply(void *ctx, uint8_t b)
{
    Peer *p = ctx;
    if (p->state != kMaster) { printf("  peer %d: master reply while not master\n", p->id); return; }
    if (p->cwl.stats.master_timeouts != (uint32_t)p->timeouts) p->timeouts++;   // 0xFF by timeout
    else { p->log[p->log_n++] = (uint16_t)(p->sb << 8 | b); p->completed++; }
    p->state = kIdle;
}

static void host_master_dropped(void *ctx)
{
    Peer *p = ctx;
    if (p->state != kMaster || !p->abandoned) { printf("  peer %d: drop while not an abandoned master\n", p->id); return; }
    p->timeouts++;   // counted as a master timeout by the core
    p->drops++;
    p->state = kIdle;
}

// The local "link port": completes only if armed right now (standing: or
// at the next arm).
static void host_slave_offer(void *ctx, uint8_t b)
{
    Peer *p = ctx;
    if (standing && p->state != kArmed) {
        p->st_pend = 1;
        p->st_byte = b;
        if (collide && p->state == kMaster) Cwl_OnLocalMasterCollision(&p->cwl, now);
        return;
    }
    if (p->state == kArmed) {
        p->log[p->log_n++] = (uint16_t)(b << 8 | p->sb);
        p->completed++;
        p->state = kIdle;
        Cwl_OnLocalSlaveResult(&p->cwl, true, p->sb, now);
    } else {
        Cwl_OnLocalSlaveResult(&p->cwl, false, 0, now);
        if (collide && p->state == kMaster) Cwl_OnLocalMasterCollision(&p->cwl, now);
    }
}

static void host_slave_withdraw(void *ctx)
{
    Peer *p = ctx;
    if (!p->st_pend) return;      // already exchanged: its SLAVE_DONE answered
    p->st_pend = 0;
    Cwl_OnLocalSlaveResult(&p->cwl, false, 0, now);
}

static void wake_radio(Peer *p)
{
    if (!p->radio_on && p->radio_on_at == 0) {
        // A link-port event while dormant: start the radio (700 ms to
        // associate) and hold masters for the peer meanwhile.
        p->radio_on_at = now + 700;
        p->wakes++;
        if (hold) Cwl_HoldForPeer(&p->cwl, now, 2000);
    }
}

static void arm(Peer *p, uint8_t b)
{
    wake_radio(p);
    p->state = kArmed;
    p->sb = b;
    if (p->st_pend) {             // a standing offer: exchanged as the game arms
        p->st_pend = 0;
        p->log[p->log_n++] = (uint16_t)(p->st_byte << 8 | p->sb);
        p->completed++;
        p->state = kIdle;
        Cwl_OnLocalSlaveResult(&p->cwl, true, p->sb, now);
        return;
    }
    // brief: the ARMED hint crosses the UART after the game already became
    // master again, so it never brings an offer in time.
    if (!brief && (!lost_arm || (rand() & 1))) Cwl_OnLocalArmed(&p->cwl, now);
}
static void master(Peer *p, uint8_t b)
{
    wake_radio(p);
    p->state = kMaster;
    p->sb = b;
    p->next_kick = now + 16;
    p->abandoned = 0;
    p->abandon_at = abandon && (rand() % 3) == 0 ? now + 1 + (uint32_t)(rand() % 15) : 0;
    p->lost = lost_master && (rand() % 4) == 0;
    if (p->lost) {                       // the MASTER event never arrives
        // Abandoned after the reply-in-flight window (kCwlReplyInFlight_ms):
        // an ABANDON right after a reply is taken for the replied master.
        p->abandon_at = now + kCwlReplyInFlight_ms + 1 + (uint32_t)(rand() % 15);
        p->lost_masters++;
        return;
    }
    Cwl_OnLocalMaster(&p->cwl, b, now);
}

static void game_step(Peer *p)
{
    if (p->radio_on_at && now >= p->radio_on_at) { p->radio_on = 1; p->radio_on_at = 0; }
    if (sleepy && p->id == 1 && ((peers[0]->want_sleep && peers[0]->state != kMaster) || now < peers[0]->sleep_until)) {
        // Both players stopped (chaos): the partner stops too, once the
        // byte in flight is done (a partner that vanishes under a held
        // master is the unplugged cable: 0xFF after the master timeout).
        if (p->state == kArmed) { p->state = kIdle; Cwl_OnLocalDisarm(&p->cwl, now); }
        return;
    }
    if (sleepy && p->id == 0) {
        if (now < p->sleep_until) return;                   // dormant, game idle
        if ((now % 5000) == 0 && p->radio_on && !p->want_sleep) { p->want_sleep = 1; p->quiet_since = now; }
        if (p->want_sleep) {
            // The player stopped: no new transfers; an armed port gives up.
            if (p->state == kArmed) { p->state = kIdle; Cwl_OnLocalDisarm(&p->cwl, now); }
            if (p->state != kIdle || Cwl_Busy(&p->cwl)) p->quiet_since = now;
            if (now - p->quiet_since >= 2500) {
                p->want_sleep = 0;
                p->radio_on = 0;                            // CWL goes dormant
                p->sleep_until = now + 2500;
            }
            return;
        }
    }
    if (p->state == kMaster && p->abandon_at && !p->abandoned && now >= p->abandon_at) {
        p->abandoned = 1;                         // the game wrote SB/SC: CPU stalls
        Cwl_OnLocalMasterAbandon(&p->cwl, now);
    }
    if (p->state == kMaster && p->abandoned) {
        if (p->lost && now - p->abandon_at > p->max_stall) p->max_stall = now - p->abandon_at;
        return;
    }
    if (kick && p->state == kMaster && now >= p->next_kick && !p->lost) {
        p->next_kick = now + 16;
        Cwl_OnLocalMaster(&p->cwl, p->sb, now);   // the restart of a held transfer
    }
    if (!chaos) {
        if (p->state != kIdle) return;
        if (early && p->id == 1 && now < 2600) return;
        if (p->id == 0) master(p, (uint8_t)p->completed);
        else arm(p, (uint8_t)(p->completed ^ 0xA5));
        return;
    }
    switch (p->state) {
    case kIdle:
        arm(p, (uint8_t)rand());
        p->until = now + (uint32_t)(rand() % 40);
        if (brief && p->state == kArmed) {     // armed for no time at all
            p->state = kIdle;
            Cwl_OnLocalDisarm(&p->cwl, now);
            master(p, (uint8_t)rand());
        }
        break;
    case kArmed:
        if (now >= p->until) {                 // give up waiting, become master
            p->state = kIdle;
            Cwl_OnLocalDisarm(&p->cwl, now);
            master(p, (uint8_t)rand());
        }
        break;
    default:
        break;
    }
}

enum { kOptKick = 1, kOptLostArm = 2, kOptRestart = 4, kOptNoSession = 8, kOptSleep = 16, kOptNoHold = 32, kOptEarlyCancel = 64, kOptAbandon = 128, kOptCollide = 256, kOptDeviceTimeout = 512, kOptLostMaster = 1024, kOptStanding = 2048, kOptBrief = 4096 };

static int run(const char *name, int is_chaos, int d, int j, int loss, int dup, uint32_t ms, int opts)
{
    chaos = is_chaos; delay_ms = d; jitter_ms = j; loss_pct = loss; dup_pct = dup;
    kick = (opts & kOptKick) != 0; lost_arm = (opts & kOptLostArm) != 0;
    sleepy = (opts & kOptSleep) != 0; hold = (opts & kOptNoHold) == 0;
    early = (opts & kOptEarlyCancel) != 0;
    abandon = (opts & kOptAbandon) != 0;
    collide = (opts & kOptCollide) != 0;
    lost_master = (opts & kOptLostMaster) != 0;
    standing = (opts & kOptStanding) != 0;
    brief = (opts & kOptBrief) != 0;
    const uint32_t restart_at = (opts & kOptRestart) ? now + 1000 + ms / 2 : 0;
    int restart_log0 = -1, restart_log1 = -1;
    net_n = 0; now = 1000; srand(7);
    for (int i = 0; i < 2; i++) {
        memset(peers[i], 0, sizeof(Peer));
        peers[i]->id = i;
        peers[i]->radio_on = 1;
        CwlHost_t h = {peers[i], host_send, host_master_reply, host_slave_offer, host_master_dropped, host_slave_withdraw};
        Cwl_Init(&peers[i]->cwl, &h, now);
        Cwl_SetSession(&peers[i]->cwl, (uint16_t)(0x1234 + i));
        Cwl_SetStandingOffers(&peers[i]->cwl, standing);
        if (opts & kOptNoSession) peers[i]->cwl.session = 0;   // HELLO without a session
        if (opts & kOptDeviceTimeout) peers[i]->cwl.master_timeout_ms = 1500;   // the ESP32 default
    }
    const uint32_t end = now + ms;
    while (now < end) {
        if (restart_at && now == 1000 + ms / 2) {
            // A peer restarts (power cycle): fresh protocol state, idle game.
            // Fixed mode restarts the master (its sequence numbers start over).
            Peer *p = peers[chaos ? 1 : 0];
            CwlHost_t h = {p, host_send, host_master_reply, host_slave_offer, host_master_dropped, host_slave_withdraw};
            Cwl_Init(&p->cwl, &h, now);
            Cwl_SetSession(&p->cwl, 0x5678);
            Cwl_SetStandingOffers(&p->cwl, standing);
            p->st_pend = 0;               // the port restarted too
            p->state = kIdle;
            p->timeouts = 0;
            restart_log0 = peers[0]->log_n;
            restart_log1 = peers[1]->log_n;
        }
        for (int i = 0; i < net_n;) {
            if (net[i].due <= now) {
                Msg m = net[i];
                net[i] = net[--net_n];
                Cwl_OnPacket(&peers[m.to]->cwl, m.pkt, kCwlPacketLen, now);
            } else i++;
        }
        for (int i = 0; i < 2; i++) {
            Cwl_Poll(&peers[i]->cwl, now);
            if (now > 2200) game_step(peers[i]);
        }
        now++;
    }
    // Compare logs: same exchanges, same order.
    // The run can stop with one exchange in flight (slave done, ACK unsent).
    // After a restart, compare what happened since; the exchange in flight at
    // the restart may be logged on one side only, so allow skipping one.
    int ok = 0, n = 0;
    const int skips = restart_at ? 1 : 0;
    for (int d0 = 0; d0 <= skips && !ok; d0++) {
        for (int d1 = 0; d1 <= skips && !ok; d1++) {
            const int b0 = (restart_at ? restart_log0 : 0) + d0, b1 = (restart_at ? restart_log1 : 0) + d1;
            const int n0 = peers[0]->log_n - b0, n1 = peers[1]->log_n - b1;
            if (abs(n0 - n1) > 1) continue;
            n = n0 < n1 ? n0 : n1;
            int same = 1;
            for (int i = 0; i < n && same; i++) same = peers[0]->log[b0 + i] == peers[1]->log[b1 + i];
            ok = same;
        }
    }
    if (!ok) printf("  logs differ\n");
    if (restart_at && n < 100) { ok = 0; printf("  no progress after restart (%d)\n", n); }
    if (!chaos && peers[0]->log_n < 100) ok = 0;   // must make progress
    if (chaos && peers[0]->log_n < 50) ok = 0;
    if (sleepy) {
        // Every wake must have found the peer: no 0xFF for the sleeping master
        // (on a clean network), unless the hold is off, which must show them.
        printf("  wakes %d, held masters %u, peer 0 timeouts %d, probes answered %u\n", peers[0]->wakes,
               peers[0]->cwl.stats.held_masters, peers[0]->timeouts, peers[1]->cwl.stats.probes_answered);
        if (peers[0]->wakes < 3) ok = 0;
        if (hold && !chaos && loss_pct == 0 && peers[0]->timeouts - peers[0]->drops != 0) ok = 0;
        // Every held master must be resolved by the ESP32 before the FPGA's
        // ~4 s safety timeout (the ESP32 keeps 3.6 s): past it the FPGA reads
        // 0xFF on its own while the peer may still exchange (desync), and a
        // CPU stalled by the write barrier / fast clock waits for all of it.
        for (int i = 0; i < 2; i++) {
            if (peers[i]->cwl.stats.max_master_wait_ms >= 3600) {
                ok = 0;
                printf("  peer %d: a master waited %u ms (FPGA safety ~4 s)\n", i, peers[i]->cwl.stats.max_master_wait_ms);
            }
        }
        if (!hold && peers[0]->timeouts == 0) ok = 0;
    }
    if (abandon) {
        printf("  abandons %u/%u, dropped %d/%d\n", peers[0]->cwl.stats.master_abandons, peers[1]->cwl.stats.master_abandons,
               peers[0]->drops, peers[1]->drops);
        if (peers[0]->cwl.stats.master_abandons < 20) ok = 0;
    }
    if (lost_master) {
        // Every lost master released about at once (the old core ignored the
        // ABANDON: the CPU stayed stalled until the FPGA's ~4 s timeout).
        printf("  lost masters %d/%d, released %u/%u, longest stall %u/%u ms\n", peers[0]->lost_masters, peers[1]->lost_masters,
               peers[0]->cwl.stats.unknown_abandons, peers[1]->cwl.stats.unknown_abandons, peers[0]->max_stall, peers[1]->max_stall);
        for (int i = 0; i < 2; i++) {
            if (peers[i]->lost_masters < 10 || peers[i]->max_stall > 100 ||
                peers[i]->cwl.stats.unknown_abandons != (uint32_t)peers[i]->lost_masters) ok = 0;
        }
    }
    if (collide) {
        // A collision resolves in about a round trip, never the 250 ms timeout.
        printf("  collisions %u/%u, max master wait %u/%u ms\n", peers[0]->cwl.stats.master_collisions,
               peers[1]->cwl.stats.master_collisions, peers[0]->cwl.stats.max_master_wait_ms, peers[1]->cwl.stats.max_master_wait_ms);
        if (peers[0]->cwl.stats.master_collisions < 20) ok = 0;
        if (loss_pct == 0 && peers[0]->cwl.stats.max_master_wait_ms >= peers[0]->cwl.master_timeout_ms) ok = 0;
    }
    if (early) {
        // Exactly the one early timeout, and the seq wrapped past it.
        if (peers[0]->timeouts != 1) { ok = 0; printf("  timeouts after the early cancel: %d\n", peers[0]->timeouts - 1); }
        if (peers[0]->log_n < 600) ok = 0;
    }
    printf("%-46s %s  exchanges %d/%d  timeouts %d/%d  cancels %u/%u  rate %.0f/s\n", name, ok ? "PASS" : "FAIL",
           peers[0]->log_n, peers[1]->log_n, peers[0]->timeouts, peers[1]->timeouts,
           peers[0]->cwl.stats.cancels, peers[1]->cwl.stats.cancels,
           peers[0]->log_n / ((ms - 1200) / 1000.0));
    return ok;
}

// A peer sends a request while our port is not armed, then vanishes (power
// off, out of range). Our side must stop offering it once the peer is gone,
// or it keeps the link busy (and the radio awake) forever.
static int run_vanish(void)
{
    net_n = 0; now = 1000; loss_pct = dup_pct = jitter_ms = 0; delay_ms = 5; chaos = 0; sleepy = 0;
    for (int i = 0; i < 2; i++) {
        memset(peers[i], 0, sizeof(Peer));
        peers[i]->id = i;
        peers[i]->radio_on = 1;
        CwlHost_t h = {peers[i], host_send, host_master_reply, host_slave_offer};
        Cwl_Init(&peers[i]->cwl, &h, now);
        Cwl_SetSession(&peers[i]->cwl, (uint16_t)(0x1234 + i));
    }
    int busy_before = 0, ok = 1;
    for (; now < 9000; now++) {
        if (now == 2000) { peers[0]->cwl.master_timeout_ms = 1500; master(peers[0], 0x42); }   // peer 1 never arms
        if (now == 2300) { busy_before = Cwl_Busy(&peers[1]->cwl); peers[0]->radio_on = 0; }   // peer 0 vanishes
        for (int i = 0; i < net_n;) {
            if (net[i].due <= now) { Msg m = net[i]; net[i] = net[--net_n]; Cwl_OnPacket(&peers[m.to]->cwl, m.pkt, kCwlPacketLen, now); }
            else i++;
        }
        Cwl_Poll(&peers[0]->cwl, now);
        Cwl_Poll(&peers[1]->cwl, now);
    }
    if (!busy_before) { ok = 0; printf("  peer 1 never had the request pending\n"); }
    if (Cwl_Busy(&peers[1]->cwl)) { ok = 0; printf("  peer 1 still busy after the peer vanished\n"); }
    if (peers[1]->log_n != 0) { ok = 0; printf("  peer 1 exchanged with an unarmed port\n"); }
    printf("%-46s %s  dropped %u, offers refused %u\n", "vanished peer: pending request dropped", ok ? "PASS" : "FAIL",
           peers[1]->cwl.stats.dropped_requests, peers[1]->cwl.stats.slave_offers_refused);
    return ok;
}

int main(void)
{
    peers[0] = calloc(1, sizeof(Peer));
    peers[1] = calloc(1, sizeof(Peer));
    int ok = 1;
    ok &= run("fixed  LAN 5 ms", 0, 5, 0, 0, 0, 20000, 0);
    ok &= run("fixed  LAN 5 ms + 10 ms jitter", 0, 5, 10, 0, 0, 20000, 0);
    ok &= run("fixed  internet 40 ms + 20 ms jitter", 0, 40, 20, 0, 0, 30000, 0);
    ok &= run("fixed  10% loss + 10% dup", 0, 5, 5, 10, 10, 20000, 0);
    ok &= run("chaos  LAN 5 ms", 1, 5, 0, 0, 0, 60000, 0);
    ok &= run("chaos  LAN 5 ms + 10 ms jitter", 1, 5, 10, 0, 0, 60000, 0);
    ok &= run("chaos  internet 40 ms + 20 ms jitter", 1, 40, 20, 0, 0, 120000, 0);
    ok &= run("chaos  10% loss + 10% dup", 1, 5, 5, 10, 10, 60000, 0);
    ok &= run("chaos  30% loss + 20% dup", 1, 10, 10, 30, 20, 120000, 0);
    ok &= run("fixed  kick 16 ms, 15 ms + 10 ms jitter", 0, 15, 10, 0, 0, 20000, kOptKick);
    ok &= run("chaos  kick 16 ms, 15 ms + 10 ms jitter", 1, 15, 10, 0, 0, 60000, kOptKick);
    ok &= run("chaos  kick, 10% loss + 10% dup", 1, 5, 5, 10, 10, 60000, kOptKick);
    ok &= run("fixed  lost ARMED hints", 0, 5, 5, 0, 0, 20000, kOptLostArm);
    ok &= run("chaos  lost ARMED hints + kick", 1, 5, 5, 0, 0, 60000, kOptLostArm | kOptKick);
    ok &= run("fixed  peer restart", 0, 5, 5, 0, 0, 20000, kOptRestart);
    ok &= run("fixed  restart from a peer without sessions", 0, 5, 5, 0, 0, 20000, kOptRestart | kOptNoSession);
    ok &= run("chaos  peer restart + kick + 5% loss", 1, 5, 5, 5, 5, 60000, kOptRestart | kOptKick);
    ok &= run("fixed  early cancel, then seq wraps (stale CANCELLED)", 0, 5, 5, 0, 0, 20000, kOptEarlyCancel);
    ok &= run("fixed  early cancel, seq wraps, 10% loss + 10% dup", 0, 5, 5, 10, 10, 30000, kOptEarlyCancel);
    ok &= run("fixed  master abandons (write barrier)", 0, 5, 5, 0, 0, 20000, kOptAbandon);
    ok &= run("chaos  master abandons, 15 ms + 10 ms jitter", 1, 15, 10, 0, 0, 60000, kOptAbandon);
    ok &= run("chaos  master abandons + kick, 10% loss + 10% dup", 1, 5, 5, 10, 10, 60000, kOptAbandon | kOptKick);
    ok &= run("chaos  lost MASTER events, abandoned, 15 ms", 1, 15, 10, 0, 0, 60000, kOptAbandon | kOptLostMaster);
    ok &= run("chaos  lost MASTER + kick, 10% loss + 10% dup", 1, 5, 5, 10, 10, 60000, kOptAbandon | kOptLostMaster | kOptKick);
    ok &= run("chaos  stalled masters collide, 15 ms + 10 ms jitter", 1, 15, 10, 0, 0, 60000, kOptCollide);
    ok &= run("chaos  stalled masters collide, 10% loss + 10% dup", 1, 5, 5, 10, 10, 60000, kOptCollide);
    ok &= run_vanish();
    // Standing offers (FPGA 18.20): the port keeps an offer until the game arms.
    ok &= run("fixed  standing offers, 15 ms + 10 ms jitter", 0, 15, 10, 0, 0, 20000, kOptStanding);
    ok &= run("chaos  standing offers, 10% loss + 10% dup", 1, 5, 5, 10, 10, 60000, kOptStanding);
    ok &= run("chaos  standing + abandons + kick, 15 ms", 1, 15, 10, 0, 0, 60000, kOptStanding | kOptAbandon | kOptKick);
    ok &= run("chaos  standing + collide, 10% loss + dup", 1, 5, 5, 10, 10, 60000, kOptStanding | kOptCollide);
    ok &= run("chaos  standing + peer restart + 5% loss", 1, 5, 5, 5, 5, 60000, kOptStanding | kOptRestart | kOptKick);
    ok &= run("chaos  standing + sleeps + abandons, 1500 ms", 1, 15, 10, 0, 0, 120000, kOptStanding | kOptSleep | kOptAbandon | kOptKick | kOptDeviceTimeout);
    ok &= run("chaos  brief arms (Gen 2 search) + standing", 1, 15, 10, 0, 0, 60000, kOptStanding | kOptBrief | kOptAbandon);
    ok &= !run("chaos  brief arms WITHOUT standing (expect FAIL)", 1, 15, 10, 0, 0, 20000, kOptBrief | kOptAbandon);
    ok &= run("chaos  brief arms + standing, 10% loss + dup", 1, 5, 5, 10, 10, 60000, kOptStanding | kOptBrief | kOptAbandon);
    ok &= run("fixed  master radio sleeps, wakes on master", 0, 5, 5, 0, 0, 30000, kOptSleep);
    ok &= run("fixed  ... same without the hold (expect 0xFF)", 0, 5, 5, 0, 0, 30000, kOptSleep | kOptNoHold);
    ok &= run("fixed  radio sleeps, 10% loss + 10% dup", 0, 5, 5, 10, 10, 30000, kOptSleep);
    ok &= run("chaos  radio sleeps + kick, 15 ms + 10 ms jitter", 1, 15, 10, 0, 0, 60000, kOptSleep | kOptKick);
    // Power x compatibility: masters that stall the CPU (write barrier, fast
    // clock) across radio sleep and wake, at the device's 1500 ms timeout.
    ok &= run("fixed  radio sleeps + master abandons, 1500 ms", 0, 5, 5, 0, 0, 40000, kOptSleep | kOptAbandon | kOptDeviceTimeout);
    ok &= run("chaos  radio sleeps + abandons + kick, 15 ms", 1, 15, 10, 0, 0, 120000, kOptSleep | kOptAbandon | kOptKick | kOptDeviceTimeout);
    ok &= run("chaos  radio sleeps + stalled masters collide", 1, 15, 10, 0, 0, 120000, kOptSleep | kOptCollide | kOptDeviceTimeout);
    ok &= run("chaos  sleeps + collide + abandon, 10% loss + dup", 1, 5, 5, 10, 10, 60000, kOptSleep | kOptCollide | kOptAbandon | kOptDeviceTimeout);
    printf(ok ? "ALL PASSED\n" : "FAILED\n");
    return ok ? 0 : 1;
}
