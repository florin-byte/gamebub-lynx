// SPDX-License-Identifier: GPL-3.0-only
// Two software ABI-2 bridges, each running the production adapter + CWL2.
#include "gamebub_cwl_logic.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>

typedef struct {
    GbCwlLogic s;
    unsigned id;
    uint8_t mac[6], generation, sb, offer_byte;
    bool running, radio_off;
    bool enabled, held, armed, standing, parked, overflow, fast;
    uint8_t transfer_id;
    unsigned guard_at;
    uint32_t fifo[16];
    unsigned head, count, commands, dropped, config_writes, control_writes;
    uint8_t sent[4096], received[4096];
    unsigned completed;
} End;
typedef struct { unsigned to, due, n; uint8_t frame[48]; bool unicast; } Packet;
static End ends[2];
static Packet packets[4096];
static unsigned pending, now, seed = 17;
static unsigned verify_start;
static bool lossy, disconnected, verdicts_blocked;
static unsigned random_u(void) { seed = seed * 1664525u + 1013904223u; return seed; }
static void event(End *e, unsigned op, uint8_t byte)
{
    if (op <= 2 && e->count >= 12) return;
    if (e->count == 16) { e->overflow = true; return; }
    e->fifo[(e->head + e->count++) % 16] = ((uint32_t)e->transfer_id << 24) | op * 256 | byte;
}
static void complete(End *e, uint8_t received)
{
    assert(e->completed < sizeof(e->sent));
    e->sent[e->completed] = e->sb;
    e->received[e->completed++] = received;
    e->sb = received;
    e->held = e->armed = e->parked = false;
}
static void arm(End *e, uint8_t byte)
{
    assert(!e->held);
    e->sb = byte;
    e->armed = true;
    event(e, 1, byte);
    // Standing offer accepts even a one-cycle arm, using that cycle's SB.
    if (e->standing) {
        event(e, 4, byte);
        complete(e, e->offer_byte);
        e->standing = false;
    }
}
static void master(End *e, uint8_t byte)
{
    assert(e->enabled && !e->held);
    e->sb = byte;
    e->armed = false;
    e->held = true;
    e->transfer_id++;
    e->guard_at = now + 4000;
    event(e, 3, byte);
}
static void abandon(End *e) { assert(e->held); e->parked = true; event(e, 6, e->sb); }
static bool read_reg(void *ctx, uint32_t a, uint32_t *v)
{
    End *e = ctx;
    if (!e->running) return false;
    switch (a) {
    case 0x40: assert(0 && "C CAPS read must never be used"); return false;
    case 0x48: *v = e->count | (e->overflow ? 512 : 0) | (e->generation << 16) |
        (e->held ? 1024 : 0) | (e->armed ? 2048 : 0) | ((uint32_t)e->transfer_id << 24); break;
    case 0x4c: *v = e->count ? e->fifo[e->head] : 0; break;
    default: assert(0);
    }
    return true;
}
static bool write_reg(void *ctx, uint32_t a, uint32_t v)
{
    End *e = ctx;
    if (!e->running) return false;
    switch (a) {
    case 0x58:
        e->control_writes++;
        assert(v == 1);
        if (e->held) {
            if (e->parked) e->dropped++;
            else complete(e, 255);
        }
        e->generation++;
        e->enabled = e->held = e->armed = e->parked = e->standing = e->overflow = false;
        e->head = e->count = 0;
        break;
    case 0x44:
        e->config_writes++;
        assert(e->control_writes > 0);
        assert(v == 13);
        e->enabled = true;
        e->generation++;
        e->count = e->head = 0;
        break;
    case 0x50:
        assert(e->count && v == 1);
        e->head = (e->head + 1) % 16;
        e->count--;
        break;
    case 0x54:
        assert(e->enabled);
        e->commands++;
        switch ((v >> 8) & 7) {
        case 1:
            if (e->held && (uint8_t)(v >> 24) == e->transfer_id) complete(e, v);
            break;
        case 4:
            if (e->held && (uint8_t)(v >> 24) == e->transfer_id) {
                e->held = e->parked = false; e->dropped++;
            }
            break;
        case 2:
            if (e->armed && !e->held) {
                event(e, 4, e->sb);
                complete(e, v);
            } else {
                e->standing = true;
                e->offer_byte = v;
                if (e->held && (e->parked || e->fast)) event(e, 7, e->sb);
            }
            break;
        case 6:
            if (e->standing) event(e, 5, e->sb);
            e->standing = false;
            break;
        default: assert(0);
        }
        break;
    default: assert(0);
    }
    return true;
}
static void send_frame(void *ctx, const uint8_t dest[6], const uint8_t *data, size_t n)
{
    End *e = ctx;
    if (disconnected || (verdicts_blocked && n == 6 &&
        (data[3] == kCwlType_Ack || data[3] == kCwlType_Cancelled))) return;
    if (lossy && random_u() % 100 < 20) return;
    unsigned copies = lossy && random_u() % 100 < 20 ? 2 : 1;
    while (copies--) {
        assert(pending < 4096 && n <= 48);
        Packet *p = &packets[pending++];
        *p = (Packet){.to = 1-e->id, .due = now+1+(lossy ? random_u()%20 : 0),
                      .n = n, .unicast = !CwlLx_MacIsBroadcast(dest)};
        memcpy(p->frame, data, n);
    }
}
static void channel(void *ctx, uint8_t ch) { (void)ctx; (void)ch; }
static void init(unsigned id, uint16_t session)
{
    End *e = &ends[id];
    e->id = id;
    e->running = true;
    e->mac[0] = 2; e->mac[5] = id+1;
    GbCwlIo io = {.ctx=e, .read=read_reg, .write=write_reg, .send=send_frame, .channel=channel};
    gb_cwl_logic_init(&e->s, &io, e->mac, session, now);
    e->s.core_running = e->s.caps_read = true;
    e->s.caps = 0x574c021f;
    gb_cwl_bridge_stop(&e->s);
}
static void tick(unsigned duration)
{
    while (duration--) {
        for (unsigned i=0; i<pending;) {
            if (packets[i].due <= now) {
                Packet p = packets[i]; packets[i] = packets[--pending];
                if (disconnected) continue;
                unsigned from = 1-p.to;
                gb_cwl_logic_receive(&ends[p.to].s, ends[from].mac, p.unicast,
                                    -40, p.frame, p.n, now);
            } else i++;
        }
        for (unsigned i=0; i<2; i++) {
            End *e = &ends[i];
            if (e->held && now >= e->guard_at) {
                complete(e, 255); // RTL guard: IRQ even for a parked write.
                e->transfer_id++;
            }
            if (e->radio_off) continue;
            gb_cwl_bridge_poll(&ends[i].s, now);
            gb_cwl_logic_poll(&ends[i].s, now);
            gb_cwl_bridge_poll(&ends[i].s, now);
        }
        now++;
    }
}
static void ready(void)
{
    unsigned deadline = now + 8000;
    while ((!ends[0].s.bridge_on || !ends[1].s.bridge_on) && now < deadline) tick(1);
    assert(ends[0].s.bridge_on && ends[1].s.bridge_on);
}
static void verify(void)
{
    assert(ends[0].completed == ends[1].completed);
    unsigned n = ends[0].completed;
    assert(n >= verify_start);
    assert(!memcmp(ends[0].sent + verify_start, ends[1].received + verify_start, n - verify_start));
    assert(!memcmp(ends[1].sent + verify_start, ends[0].received + verify_start, n - verify_start));
}
static void exchange(unsigned id, unsigned index, bool late_arm, bool rewrite)
{
    End *m = &ends[id], *s = &ends[1-id];
    unsigned before = m->completed;
    if (!late_arm) arm(s, (index*3+19)%254);
    master(m, (index*7+11)%254);
    tick(late_arm ? 40 : 1);
    if (late_arm) arm(s, (index*3+19)%254);
    if (rewrite && m->held && s->completed > before) abandon(m);
    unsigned deadline = now+4000;
    while ((m->held || m->completed == before) && now < deadline) tick(1);
    if (m->held || m->completed != before+1)
        fprintf(stderr, "exchange id=%u index=%u now=%u held=%u complete=%u/%u slave=%u state=%u bridge=%u/%u\n", id,index,now,m->held,m->completed,before,s->completed,m->s.core.m_state,m->s.bridge_on,s->s.bridge_on);
    assert(!m->held && m->completed == before+1);
    tick(50);
    verify();
}
static void reset_pair(void)
{
    verify_start = 0;
    memset(ends, 0, sizeof(ends)); pending = 0;
    lossy = disconnected = verdicts_blocked = false;
    init(0, 900); init(1, 1000); ready(); tick(20);
}
static void dx_pause_diagnostics_test(void)
{
    reset_pair();
    ends[0].s.trace_enabled = ends[1].s.trace_enabled = true;
    // Cross both CWL sequence and ABI transfer-id wrap in both roles.
    for (unsigned i = 0; i < 604; i++) exchange(i % 2, i, false, false);
    assert(ends[0].s.xmaster == 302 && ends[0].s.xslave == 302);
    // Reproduce the observed counter/status pattern, without assuming its
    // hardware cause: four requests find an unarmed peer and complete as FF.
    for (unsigned i = 0; i < 4; i++) {
        master(&ends[0], 0x40 + i);
        tick(1700);
        assert(!ends[0].held && ends[0].sb == 255);
    }
    arm(&ends[0], 0);
    tick(50);
    GbCwlLogic *s = &ends[0].s;
    assert(s->xmaster == 306 && s->xslave == 302 && s->xdrop == 0);
    assert((s->bridge_status & 0xff00ffffu) == 0x32000800u);
    assert(s->core.stats.master_done == 302 && s->core.stats.master_timeouts == 4);
    assert(s->core.m_state == kCwlM_Idle && !s->core.pend_valid && !s->core.offer_in_flight);
    assert(s->transfer_id == 0x32 && s->trace_count == GB_CWL_TRACE_CAPACITY);
    // The recorder retains game traffic, including FF verdicts, across ring
    // wrap and a long live-radio pause. Keepalive frames must not overwrite it.
    bool ff_reply = false, arm_event = false;
    uint32_t previous = 0;
    for (unsigned j = 0; j < s->trace_count; j++) {
        unsigned i = (s->trace_head + GB_CWL_TRACE_CAPACITY - s->trace_count + j) % GB_CWL_TRACE_CAPACITY;
        assert(s->trace[i].at >= previous);
        previous = s->trace[i].at;
        if (s->trace[i].kind == 'C' && (s->trace[i].value & 0x7ff) == 0x1ff) ff_reply = true;
        if (s->trace[i].kind == 'E' && (s->trace[i].value & 0x7ff) == 0x100) arm_event = true;
    }
    assert(ff_reply && arm_event);
    uint32_t rx = s->rx, tx = s->tx;
    unsigned head = s->trace_head;
    tick(6000);
    assert(s->rx > rx && s->tx > tx && gb_cwl_logic_connected(s, now));
    assert(s->trace_head == head && s->xmaster == 306 && s->xslave == 302);
    // An armed idle slave is still usable when the partner starts a master.
    unsigned a = ends[0].completed, b = ends[1].completed;
    master(&ends[1], 0x59); tick(100);
    assert(ends[0].completed == a + 1 && ends[1].completed == b + 1);
    assert(ends[0].sb == 0x59 && ends[1].sb == 0);
    s->trace_enabled = false;
    head = s->trace_head;
    gb_cwl_trace(s, 'E', 0x1234);
    assert(s->trace_head == head);
    puts("bridge: DX 306/302 pause, FF vs ACK, id/seq wrap, trace retention and resume PASS");
}
static void first_bytes_tests(void)
{
    static const uint8_t data[16] = {
        0x29, 0x00, 0x04, 0x08, 0x0c, 0x10, 0x14, 0x18,
        0x00, 0x08, 0x04, 0x10, 0x0c, 0x18, 0x14, 0x00
    };
    for (unsigned role = 0; role < 2; role++) for (unsigned late = 0; late < 2; late++) {
        reset_pair();
        End *m = &ends[role], *s = &ends[1-role];
        for (unsigned i = 0; i < 16; i++) {
            uint8_t reply = i ? 0x40 + i : 0x55;
            if (!late) arm(s, reply);
            master(m, data[i]);
            if (late) { tick(40); arm(s, reply); }
            unsigned deadline = now + 1000;
            while (m->held && now < deadline) tick(1);
            tick(10);
            assert(m->completed == i + 1 && s->completed == i + 1);
            assert(m->received[i] == reply && s->received[i] == data[i]);
            assert(m->s.first_n == i + 1 && s->s.first_n == i + 1);
            assert(m->s.first_tx[i] == data[i] && m->s.first_rx[i] == reply);
            assert(s->s.first_tx[i] == reply && s->s.first_rx[i] == data[i]);
        }
        verify();
        gb_cwl_bridge_stop(&m->s);
        assert(m->s.first_n == 0 && m->s.first_reported == 0);
    }
    puts("bridge: first 16 session bytes exact, 29/55 handshake, both roles, early/late arm PASS");
}
static void lost_master_tests(void)
{
    for (unsigned fast=0; fast<2; fast++) for (unsigned abandoned=0; abandoned<2; abandoned++) {
        reset_pair();
        End *e = &ends[0]; e->fast = fast;
        master(e, 42); tick(20);
        if (abandoned) abandon(e);
        disconnected = true;
        tick(2500);
        assert(!e->held && !e->s.bridge_on);
        assert(e->completed == !abandoned && e->dropped == abandoned);
        if (!abandoned) assert(e->received[0] == 255);
    }
    puts("bridge: lost partner, normal/abandoned master, normal/fast clock PASS");
    for (unsigned fast=0; fast<2; fast++) for (unsigned abandoned=0; abandoned<2; abandoned++) {
        reset_pair(); ends[0].fast = fast;
        master(&ends[0], 42); tick(10);
        if (abandoned) abandon(&ends[0]); // Still queued when Stop arrives.
        gb_cwl_bridge_stop(&ends[0].s);
        assert(!ends[0].held && ends[0].completed == !abandoned);
        assert(ends[0].dropped == abandoned);
        if (!abandoned) assert(ends[0].received[0] == 255);
    }
    puts("bridge: explicit stop atomically completes/drops held master, both clocks PASS");
}
static void guard_tests(void)
{
    reset_pair();
    End *e = &ends[0];
    verdicts_blocked = true;
    master(e, 42); tick(20);
    uint8_t seq = e->s.core.m_seq, old_id = e->transfer_id;
    uint8_t ack[] = {'C','W',kCwlVersion,kCwlType_Ack,seq,0x99};
    tick(3981); // HELLO stays live, but A ends at the RTL guard.
    assert(e->completed == 1 && e->received[0] == 255 && !e->held);
    assert(e->s.core.m_state == kCwlM_Idle);
    gb_cwl_logic_receive(&e->s, ends[1].mac, true, -40, ack, sizeof(ack), now);
    assert(e->completed == 1);
    master(e, 43);
    // Old callback and old tagged command cannot affect B before FIFO polling.
    gb_cwl_bridge_reply(&e->s, 0x99);
    write_reg(e, 0x54, ((uint32_t)old_id << 24) | 0x199);
    assert(e->held && e->completed == 1 && e->sb == 43);
    gb_cwl_bridge_poll(&e->s, now);
    assert(e->s.core.m_state == kCwlM_Requesting && e->s.core.m_seq != seq);
    gb_cwl_logic_receive(&e->s, ends[1].mac, true, -40, ack, sizeof(ack), now);
    assert(e->held && e->completed == 1);
    ack[4] = e->s.core.m_seq; ack[5] = 0x77;
    gb_cwl_logic_receive(&e->s, ends[1].mac, true, -40, ack, sizeof(ack), now);
    assert(!e->held && e->completed == 2 && e->received[1] == 0x77);
    // Repeat with firmware delayed across the guard and the next MASTER.
    reset_pair(); e = &ends[0]; verdicts_blocked = true;
    master(e, 42); tick(20); seq = e->s.core.m_seq;
    assert(e->s.core.m_state == kCwlM_Requesting);
    now = e->guard_at;
    complete(e, 255); e->transfer_id++; // Guard edge before firmware can poll.
    master(e, 43);
    ack[4] = seq; ack[5] = 0x99;
    gb_cwl_logic_receive(&e->s, ends[1].mac, true, -40, ack, sizeof(ack), now);
    assert(e->held && e->completed == 1 && e->sb == 43);
    gb_cwl_bridge_poll(&e->s, now);
    assert(e->s.core.m_state == kCwlM_Requesting && e->s.core.m_seq != seq);
    ack[4] = e->s.core.m_seq; ack[5] = 0x66;
    gb_cwl_logic_receive(&e->s, ends[1].mac, true, -40, ack, sizeof(ack), now);
    assert(!e->held && e->completed == 2 && e->received[1] == 0x66);
    puts("bridge: guard, late ACK before/after next master, atomic id rejection PASS");
}
static void any_tests(void)
{
    reset_pair();
    End *e = &ends[0];
    assert(e->s.peer.confirmed && !e->s.peer.fixed);
    disconnected = true; tick(kCwlLx_PeerLost_ms + 1);
    assert(!e->s.peer.locked && !e->s.peer.fixed);
    uint8_t b[6] = {2,0,0,0,0,3};
    uint8_t hello[] = {'C','W',kCwlVersion,kCwlType_Hello,4,0};
    gb_cwl_logic_receive(&e->s, b, true, -40, hello, sizeof(hello), now);
    gb_cwl_bridge_poll(&e->s, now);
    assert(gb_cwl_logic_connected(&e->s, now) && e->s.bridge_on);
    assert(!e->s.peer.fixed && !memcmp(e->s.peer.mac, b, 6));
    CwlLx_PeerFix(&e->s.peer, b);
    CwlLx_PeerPoll(&e->s.peer, now + kCwlLx_PeerLost_ms);
    assert(e->s.peer.fixed && e->s.peer.locked);
    assert(!CwlLx_PeerAccept(&e->s.peer, ends[1].mac, true, now));
    puts("bridge: Any loses A and pairs B; explicit fixed selection retained PASS");
}
static void lifecycle_tests(void)
{
    // Rust has probed Running before the page starts a fresh radio session.
    // No peer state is injected: real NAME/HELLO traffic establishes Connected.
    for (unsigned empty_name = 0; empty_name < 2; empty_name++) {
        memset(ends, 0, sizeof(ends)); pending = 0;
        lossy = disconnected = verdicts_blocked = false;
        init(0, 900); init(1, 1000);
        if (empty_name) ends[1].s.name[0] = 0;
        assert(!gb_cwl_logic_connected(&ends[0].s, now));
        assert(!ends[0].config_writes);
        ends[0].s.caps = 0;
        unsigned pairing_deadline = now + 8000;
        while (!gb_cwl_logic_connected(&ends[0].s, now) && now < pairing_deadline) tick(1);
        assert(gb_cwl_logic_connected(&ends[0].s, now));
        assert(!ends[0].config_writes);
        assert(!strcmp(gb_cwl_bridge_config_reason(&ends[0].s, now),
                       "blocked: incompatible caps"));
        ends[0].s.caps_read = false;
        tick(10);
        assert(!ends[0].config_writes);
        assert(!strcmp(gb_cwl_bridge_config_reason(&ends[0].s, now),
                       "blocked: caps read failed"));
        ends[0].s.caps_read = true;
        ends[0].s.caps = 0x574c021f;
        ready(); tick(20);
        assert(ends[0].config_writes == 1);
        assert(!strcmp(gb_cwl_bridge_config_reason(&ends[0].s, now), "written"));
        printf("bridge: Running -> page start -> radio -> %s NAME -> Connected; passed caps only PASS\n",
               empty_name ? "empty" : "default");
        assert(ends[0].enabled && ends[1].enabled);
        exchange(0, 1, true, false);
        puts("bridge: enable when radio starts after Running PASS");
    }

    // Radio and partner can also precede Running; I/O is gated as in Rust.
    reset_pair();
    gb_cwl_bridge_stop(&ends[0].s);
    ends[0].running = false;
    ends[0].s.core_running = false;
    tick(100);
    assert(gb_cwl_logic_connected(&ends[0].s, now));
    assert(!ends[0].enabled && !ends[0].s.bridge_on);
    unsigned config_before = ends[0].config_writes;
    unsigned control_before = ends[0].control_writes;
    ends[0].running = true;
    ends[0].s.core_running = true;
    gb_cwl_bridge_poll(&ends[0].s, now);
    assert(ends[0].config_writes == config_before + 1);
    assert(ends[0].control_writes == control_before + 1);
    assert(ends[0].enabled && ends[0].s.bridge_on);
    gb_cwl_bridge_poll(&ends[0].s, now);
    assert(ends[0].config_writes == config_before + 1);
    assert(ends[0].control_writes == control_before + 1);
    ready();
    exchange(0, 2, true, false);
    puts("bridge: enable when Running follows partner PASS");

    disconnected = true;
    tick(2500);
    assert(!ends[0].enabled && !ends[0].s.bridge_on);
    disconnected = false;
    ready();
    exchange(0, 3, true, false);
    puts("bridge: disable on partner loss and re-enable on found PASS");

    // Turn off joins the radio task after CONTROL, while GB I/O is valid.
    gb_cwl_bridge_stop(&ends[0].s);
    ends[0].radio_off = true;
    uint8_t generation = ends[0].generation;
    tick(100);
    assert(ends[0].running && !ends[0].enabled && !ends[0].s.bridge_on);
    assert(ends[0].generation == generation);
    puts("bridge: Turn off leaves running GB on physical port PASS");
}
static uint32_t test_us;
static uint32_t test_clock(void *ctx) { (void)ctx; return test_us; }
static void latency_test(void)
{
    GbCwlLogic s = {0};
    s.trace_enabled = true;
    s.io.clock_us = test_clock;
    for (unsigned i = 0; i < 70; i++) {
        s.irq_us = 100 + i * 1000;
        test_us = s.irq_us + 10; gb_cwl_trace(&s, 'E', 3 << 8);
        test_us += 20; gb_cwl_trace(&s, 'T', 0);
        s.received_us = test_us + 300;
        test_us += 310; gb_cwl_trace(&s, 'R', 0);
        test_us += 10; gb_cwl_trace(&s, 'V', 1 << 8);
        test_us += 30; gb_cwl_trace(&s, 'C', 1 << 8);
    }
    assert(s.latency_n == 70 && s.hop_max[0] == 10 && s.hop_max[1] == 20);
    assert(s.hop_max[2] == 300 && s.hop_max[3] == 20 && s.hop_max[4] == 30);
    assert(s.latency[5][5] - s.latency[5][0] == 380);
    s.irq_us = test_us;
    test_us += 10; gb_cwl_trace(&s, 'E', 3 << 8);
    gb_cwl_trace(&s, 'S', 0);
    gb_cwl_trace(&s, 'C', 1 << 8);
    assert(s.latency_n == 70); // Stale verdict cannot complete a timing sample.
    puts("bridge: microsecond hops, RX queue delay, bounded ring, stale verdict PASS");
}

static void trace_retry_and_notification_test(void)
{
    GbCwlLogic s = {0};
    s.trace_enabled = true;
    s.io.clock_us = test_clock;
    for (unsigned i = 0; i < GB_CWL_TRACE_CAPACITY; i++) {
        s.now = i; test_us = 100 + i;
        gb_cwl_trace(&s, 'E', i);
    }
    s.now++; test_us++;
    gb_cwl_trace(&s, 'T', 0x019f00);
    unsigned head = s.trace_head;
    uint32_t oldest_at = s.trace[head].at;
    for (unsigned i = 0; i < 1000; i++) {
        s.now++; test_us++;
        gb_cwl_trace(&s, 'T', 0x019f00);
    }
    assert(s.trace_count == GB_CWL_TRACE_CAPACITY && s.trace_head == head);
    assert(s.trace[head].at == oldest_at); // Full-ring retry cannot corrupt oldest entry.
    unsigned last = (head + GB_CWL_TRACE_CAPACITY - 1) % GB_CWL_TRACE_CAPACITY;
    assert(s.trace[last].repeats == 1000 && s.trace[last].last_us == test_us);
    gb_cwl_trace(&s, 'E', 2 << 8); // State change breaks packet coalescing.
    gb_cwl_trace(&s, 'T', 0x019f00);
    assert(s.trace_head == (head + 2) % GB_CWL_TRACE_CAPACITY);

    memset(&s, 0, sizeof(s)); s.trace_enabled = true; s.io.clock_us = test_clock;
    s.irq_us = 100;
    for (unsigned i = 0; i < 2; i++) {
        test_us = 110 + i * 1000; gb_cwl_trace(&s, 'E', 3 << 8);
        test_us += 20; gb_cwl_trace(&s, 'T', 1);
        s.received_us = test_us + 100; test_us += 110; gb_cwl_trace(&s, 'R', 2);
        test_us += 10; gb_cwl_trace(&s, 'V', 1 << 8);
        test_us += 10; gb_cwl_trace(&s, 'C', 1 << 8);
    }
    assert(s.latency_n == 2 && s.irq_us == 0);
    assert(s.latency[0][0] == 100 && s.latency[1][0] == 0);
    assert(s.latency[1][5] - s.latency[1][1] == 150);
    puts("bridge: retry coalescing preserves history, notification consumed once, unknown timestamp retains event timing PASS");
}

int main(void)
{
    init(0, 10); init(1, 20); ready();
    for (unsigned i=0; i<30; i++) exchange(i%2, i, i%3==0, false);
    puts("bridge: bidirectional bytes, standing offers and FIFO drain PASS");
    lossy = true;
    for (unsigned i=30; i<630; i++) exchange(i%2, i, i%3==0, i%7==0);
    puts("bridge: 20% loss, duplicates, jitter, ACK/CANCEL race and sequence wrap PASS");
    // A cancelled request has not exchanged either game byte; no serial IRQ.
    tick(100);
    unsigned n = ends[0].completed, dropped = ends[0].dropped;
    master(&ends[0], 42); tick(50); abandon(&ends[0]);
    unsigned deadline = now+4000;
    while (ends[0].held && now < deadline) tick(1);
    assert(!ends[0].held && ends[0].completed == n && ends[0].dropped == dropped+1);
    tick(100); verify();
    puts("bridge: abandon -> CANCEL -> withdraw -> CANCELLED -> MASTER_DROP PASS");
    // GB-046: all decisive events, ABANDON before coincident SLAVE_NAK.
    master(&ends[0], 43); tick(1);
    abandon(&ends[0]); event(&ends[0], 5, 0);
    tick(300); assert(!ends[0].held); verify();
    puts("bridge: coincident ABANDON/SLAVE_NAK ordering PASS");
    gb_cwl_bridge_stop(&ends[1].s);
    init(1, 500); ready(); tick(1000);
    assert(ends[0].s.bridge_on && ends[1].s.bridge_on);
    for (unsigned i=0; i<20; i++) exchange(i%2, i, true, false);
    puts("bridge: peer restart, new HELLO session, resume without deadlock PASS");
    // Overflow/generation invalidate before a stale event can issue a command.
    unsigned commands = ends[0].commands;
    for (unsigned i=0; i<17; i++) event(&ends[0], 3, i);
    gb_cwl_bridge_poll(&ends[0].s, now);
    assert(!ends[0].s.bridge_on && !ends[0].count && ends[0].commands == commands);
    ready();
    ends[0].generation++;
    event(&ends[0], 3, 99);
    gb_cwl_bridge_poll(&ends[0].s, now);
    assert(!ends[0].s.bridge_on && !ends[0].count && ends[0].commands == commands);
    ready();
    exchange(0, 999, true, false); verify();
    gb_cwl_bridge_stop(&ends[0].s); gb_cwl_bridge_stop(&ends[1].s);
    assert(!ends[0].enabled && !ends[1].enabled);
    puts("bridge: FIFO overflow, generation change, stale verdict rejection, stop PASS");
    unsigned exchanged = ends[0].completed;
    // Both fast-clock masters stall before they can arm as slaves. Event 7
    // keeps the standing offer until CANCEL causes withdrawal and NAK.
    memset(ends, 0, sizeof(ends)); pending = 0; lossy = false;
    init(0, 700); init(1, 800); ready();
    master(&ends[0], 11); master(&ends[1], 22);
    ends[0].fast = ends[1].fast = true; // fast-clock CPU stalls, no ABANDON
    tick(500);
    assert(!ends[0].held && !ends[1].held);
    assert(ends[0].completed == 1 && ends[1].completed == 1);
    assert(ends[0].received[0] == 255 && ends[1].received[0] == 255);
    assert(ends[0].s.core.stats.master_collisions && ends[1].s.core.stats.master_collisions);
    assert(!ends[0].standing && !ends[1].standing);
    puts("bridge: fast-clock master collision, standing offer withdrawal PASS");
    // Same firmware at both ends: no hard-coded Chromatic/Game Bub role.
    for (unsigned fast=0; fast<2; fast++) {
        reset_pair();
        ends[0].fast = ends[1].fast = fast;
        master(&ends[0], 0x12); master(&ends[1], 0x34);
        tick(1800);
        assert(!ends[0].held && !ends[1].held);
        assert(ends[0].completed == 1 && ends[1].completed == 1);
        assert(ends[0].received[0] == 255 && ends[1].received[0] == 255);
        verify_start = ends[0].completed;
        for (unsigned role=0; role<2; role++) exchange(role, 1100+role, true, false);
    }
    puts("bridge: two Game Bub slow/fast masters collide, then both roles resume PASS");
    first_bytes_tests(); lost_master_tests(); guard_tests(); any_tests(); lifecycle_tests();
    dx_pause_diagnostics_test();
    latency_test();
    trace_retry_and_notification_test();
    printf("bridge_test: ALL PASS (%u bytes each way)\n", exchanged);
}
