// SPDX-License-Identifier: GPL-3.0-only
// GB-147 host test for gamebub_cwl_sync.c. Two consoles, each with a fake FPGA,
// exchange units through in-memory packet queues that the test delivers, drops
// or reorders.
#include "gamebub_cwl_sync.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>

#define DELAY 2
#define FRAMES 200
#define FIFOCAP 1024u
#define RECCAP 1024
#define PKT_MAX 64
#define QCAP 256
#define ROM_CRC 0x11223344u
#define SAVE_CRC 0x55667788u

typedef struct { uint8_t d[PKT_MAX]; size_t n; } Packet;
typedef struct { uint16_t apply; uint8_t buttons; uint32_t dv, dh; } Sample;
typedef struct { uint16_t frame; uint8_t buttons; } Rec;

typedef struct {
    GbSync s;
    GbSyncIo io;
    int id;
    // fake FPGA
    uint32_t control;
    int control_writes, run_writes;
    Sample fifo[FIFOCAP];
    unsigned head, tail;
    Rec rec[RECCAP];            // PARTNER writes, arrival order
    int nrec;
    // units this side sent, not yet delivered to the partner
    Packet out[QCAP];
    int nout;
    // log
    int lines, diverge, refused;
    char last_line[160];
    char diverge_line[160];
    uint32_t now;
} Side;

static bool side_read(void *ctx, uint32_t a, uint32_t *v)
{
    Side *x = ctx;
    switch (a) {
    case LINK_SYNC_CAPS: *v = GB_SYNC_CAPS; return true;
    case LINK_SYNC_CONTROL: *v = x->control; return true;
    case LINK_SYNC_LOCAL:
        if (x->head == x->tail) {
            *v = 0;
        } else {
            const Sample *h = &x->fifo[x->head % FIFOCAP];
            *v = (1u << 31) | ((uint32_t)h->apply << 8) | h->buttons;
        }
        return true;
    case LINK_SYNC_DIGEST_V: *v = x->head == x->tail ? 0u : x->fifo[x->head % FIFOCAP].dv; return true;
    case LINK_SYNC_DIGEST_H: *v = x->head == x->tail ? 0u : x->fifo[x->head % FIFOCAP].dh; return true;
    case LINK_SYNC_WAIT: *v = 0; return true;
    default: return false;
    }
}

static bool side_write(void *ctx, uint32_t a, uint32_t v)
{
    Side *x = ctx;
    switch (a) {
    case LINK_SYNC_CONTROL:
        x->control = v;
        x->control_writes++;
        if (v & 2u) x->run_writes++;
        return true;
    case LINK_SYNC_POP:
        if (v == 1) {
            assert(x->head != x->tail);   // POP on an empty FIFO would be a module bug
            x->head++;
        }
        return true;
    case LINK_SYNC_PARTNER:
        assert(x->nrec < RECCAP);
        x->rec[x->nrec].frame = (uint16_t)((v >> 8) & 0xffffu);
        x->rec[x->nrec].buttons = (uint8_t)(v & 0xffu);
        x->nrec++;
        return true;
    case LINK_SYNC_PHASE: return true;
    default: return false;
    }
}

static void side_send(void *ctx, const uint8_t *unit, size_t n)
{
    Side *x = ctx;
    assert(n <= PKT_MAX && x->nout < QCAP);
    memcpy(x->out[x->nout].d, unit, n);
    x->out[x->nout].n = n;
    x->nout++;
}

static void side_log(void *ctx, const char *line)
{
    Side *x = ctx;
    x->lines++;
    snprintf(x->last_line, sizeof(x->last_line), "%s", line);
    if (strstr(line, "DIVERGE")) {
        if (x->diverge == 0) snprintf(x->diverge_line, sizeof(x->diverge_line), "%s", line);
        x->diverge++;
    }
    if (strstr(line, "REFUSED")) x->refused++;
}

static void side_init(Side *x, int id, uint8_t delay, uint32_t rom, uint32_t save)
{
    memset(x, 0, sizeof(*x));
    x->id = id;
    x->io.ctx = x;
    x->io.read = side_read;
    x->io.write = side_write;
    x->io.send = side_send;
    x->io.log = side_log;
    gb_sync_init(&x->s, &x->io, delay, rom, save);
}

static void poll_side(Side *x, uint32_t now)
{
    x->now = now;
    gb_sync_poll(&x->s, now, true);
}

// Units sent by a go to b and units sent by b go to a, in send order.
// lossy (seq != NULL): packet number *seq + i is dropped when it is a multiple
// of 3 (a running count, so both sides lose units); the rest of the batch is
// delivered in reverse order.
static void deliver(Side *a, Side *b, bool lossy, unsigned *seq)
{
    struct { Side *to; const Packet *p; } q[2 * QCAP];
    int n = 0;
    for (int i = 0; i < a->nout; i++) { q[n].to = b; q[n].p = &a->out[i]; n++; }
    for (int i = 0; i < b->nout; i++) { q[n].to = a; q[n].p = &b->out[i]; n++; }
    if (!lossy) {
        for (int i = 0; i < n; i++)
            gb_sync_receive(&q[i].to->s, q[i].p->d, q[i].p->n, q[i].to->now);
    } else {
        for (int i = n - 1; i >= 0; i--)
            if ((*seq + (unsigned)i) % 3u != 0)
                gb_sync_receive(&q[i].to->s, q[i].p->d, q[i].p->n, q[i].to->now);
        *seq += (unsigned)n;
    }
    a->nout = 0;
    b->nout = 0;
}

static void push_sample(Side *x, uint16_t apply, uint8_t buttons, uint32_t dv, uint32_t dh)
{
    assert(x->tail - x->head < FIFOCAP);
    Sample *s = &x->fifo[x->tail % FIFOCAP];
    s->apply = apply;
    s->buttons = buttons;
    s->dv = dv;
    s->dh = dh;
    x->tail++;
}

static uint32_t vfun(uint32_t f) { return 0x10000000u + f * 3u; }
static uint32_t hfun(uint32_t f) { return 0x20000000u + f * 5u; }
static uint8_t btn(int id, uint32_t f) { return (uint8_t)(f * 7u + (uint32_t)id); }

// Sample for frame f on side x: apply frame f + DELAY. Side 0 (A): dv = v, dh = h.
// Side 1 (B): dv = h, dh = v, so A.visible == B.hidden and B.visible == A.hidden.
// bad_from >= 0: side B reports a wrong hidden digest from that frame on.
static void push_frame(Side *x, uint32_t f, int bad_from)
{
    uint32_t dv, dh;
    if (x->id == 0) {
        dv = vfun(f);
        dh = hfun(f);
    } else {
        dv = hfun(f);
        dh = vfun(f);
        if (bad_from >= 0 && (int)f >= bad_from) dh = vfun(f) + 1u;
    }
    push_sample(x, (uint16_t)(f + DELAY), btn(x->id, f), dv, dh);
}

// Frame f may be pushed on x only if x's FPGA has already received a PARTNER write for frame f.
static bool can_push(const Side *x, uint32_t f)
{
    if (f < DELAY) return true;
    for (int i = 0; i < x->nrec; i++)
        if (x->rec[i].frame == f) return true;
    return false;
}

// READY exchange, then CONTROL on both sides. Lossless.
static void start_pair(Side *a, Side *b, uint32_t *now)
{
    for (int r = 0; r < 5; r++) {
        *now += 1;
        poll_side(a, *now);
        poll_side(b, *now);
        deliver(a, b, false, NULL);
        // Both players press A as soon as the partner is shown.
        gb_sync_accept(&a->s);
        gb_sync_accept(&b->s);
    }
}

// PARTNER writes must be frames DELAY, DELAY+1, ... with the partner's buttons.
static void check_records(const Side *x, int partner_id)
{
    assert(x->nrec == FRAMES);
    for (int k = 0; k < FRAMES; k++) {
        assert(x->rec[k].frame == DELAY + k);
        assert(x->rec[k].buttons == btn(partner_id, (uint32_t)k));
    }
}

static void run_lockstep(Side *a, Side *b, uint32_t *now, int bad_from)
{
    for (uint32_t f = 0; f < FRAMES; f++) {
        push_frame(a, f, -1);
        push_frame(b, f, bad_from);
        *now += 1;
        poll_side(a, *now);
        *now += 1;
        poll_side(b, *now);
        deliver(a, b, false, NULL);
    }
    for (int r = 0; r < 20; r++) {
        *now += 1;
        poll_side(a, *now);
        poll_side(b, *now);
        deliver(a, b, false, NULL);
    }
}

// Pushes are gated by can_push; rounds without pushes advance now by 10 ms so
// resends happen. Returns when all frames are pushed and received on both sides,
// then runs 20 more quiet rounds.
static void run_lossy(Side *a, Side *b, uint32_t *now)
{
    Side *side[2] = {a, b};
    uint32_t pushed[2] = {0, 0};
    unsigned seq = 0;
    int rounds;
    for (rounds = 0; rounds < 20000; rounds++) {
        if (pushed[0] == FRAMES && pushed[1] == FRAMES && a->nrec == FRAMES && b->nrec == FRAMES)
            break;
        for (int i = 0; i < 2; i++) {
            if (pushed[i] < FRAMES && can_push(side[i], pushed[i])) {
                push_frame(side[i], pushed[i], -1);
                pushed[i]++;
            }
        }
        *now += 10;
        poll_side(a, *now);
        poll_side(b, *now);
        deliver(a, b, true, &seq);
    }
    assert(rounds < 20000);
    for (int r = 0; r < 20; r++) {
        *now += 10;
        poll_side(a, *now);
        poll_side(b, *now);
        deliver(a, b, true, &seq);
    }
}

static void case1_start(void)
{
    static Side a, b;
    uint32_t now = 0;
    side_init(&a, 0, DELAY, ROM_CRC, SAVE_CRC);
    side_init(&b, 1, DELAY, ROM_CRC, SAVE_CRC);
    start_pair(&a, &b, &now);
    assert(a.s.state == GB_SYNC_RUNNING);
    assert(b.s.state == GB_SYNC_RUNNING);
    assert(a.control == 0x323 && b.control == 0x323);
    assert(a.control_writes == 1 && b.control_writes == 1);
    assert(a.refused == 0 && b.refused == 0);
    assert(gb_sync_phase(&a.s) == GB_SYNC_PHASE_RUNNING && gb_sync_phase(&b.s) == GB_SYNC_PHASE_RUNNING);
    assert(a.s.why == GB_SYNC_WHY_NONE && a.s.player == 1);
    puts("case 1 ok: start");
}

static void case2_mismatch(void)
{
    static Side a, b;
    uint32_t now = 0;
    side_init(&a, 0, DELAY, ROM_CRC, SAVE_CRC);
    side_init(&b, 1, DELAY, ROM_CRC, SAVE_CRC ^ 0x1u);
    for (int r = 0; r < 3; r++) {
        now += 1;
        poll_side(&a, now);
        poll_side(&b, now);
        deliver(&a, &b, false, NULL);
    }
    // Both sides receive the other's READY and both refuse it, but keep waiting (GB-162).
    assert(a.s.state == GB_SYNC_WAITING && b.s.state == GB_SYNC_WAITING);
    assert(a.s.why == GB_SYNC_WHY_SAVE && b.s.why == GB_SYNC_WHY_SAVE);
    assert(gb_sync_phase(&a.s) == GB_SYNC_PHASE_REFUSED && gb_sync_phase(&b.s) == GB_SYNC_PHASE_REFUSED);
    assert(a.run_writes == 0 && b.run_writes == 0);
    // The reason is logged once, not with every READY (one every 200 ms).
    for (int r = 0; r < 2000; r++) {
        now += 1;
        poll_side(&a, now);
        poll_side(&b, now);
        deliver(&a, &b, false, NULL);
    }
    assert(a.refused == 1 && b.refused == 1);
    assert(a.s.why == GB_SYNC_WHY_SAVE && a.run_writes == 0);
    // The partner starts the game again with the matching save: both start, nothing else needed.
    side_init(&b, 1, DELAY, ROM_CRC, SAVE_CRC);
    for (int r = 0; r < 500 && (a.s.state != GB_SYNC_RUNNING || b.s.state != GB_SYNC_RUNNING); r++) {
        now += 1;
        poll_side(&a, now);
        poll_side(&b, now);
        deliver(&a, &b, false, NULL);
        gb_sync_accept(&a.s);
        gb_sync_accept(&b.s);
    }
    assert(a.s.state == GB_SYNC_RUNNING && b.s.state == GB_SYNC_RUNNING);
    assert(a.s.why == GB_SYNC_WHY_NONE && gb_sync_phase(&a.s) == GB_SYNC_PHASE_RUNNING);
    puts("case 2 ok: mismatch, then match");
}

// GB-162: both players choose to play without saves; each side replaces its save while waiting.
static void case2b_blank_save(void)
{
    static Side a, b;
    uint32_t now = 0;
    const uint32_t BLANK = 0x0B1A4C00u;
    side_init(&a, 0, DELAY, ROM_CRC, SAVE_CRC);
    side_init(&b, 1, DELAY, ROM_CRC, SAVE_CRC ^ 0x1u);
    for (int r = 0; r < 300; r++) {
        now += 1;
        poll_side(&a, now);
        poll_side(&b, now);
        deliver(&a, &b, false, NULL);
        gb_sync_accept(&a.s);   // A on a refusal starts nothing
    }
    assert(gb_sync_phase(&a.s) == GB_SYNC_PHASE_REFUSED && a.s.why == GB_SYNC_WHY_SAVE && !a.s.accepted);
    // Only one side chose so far: still refused on both, nothing runs.
    gb_sync_set_save(&a.s, BLANK);
    for (int r = 0; r < 500; r++) {
        now += 1;
        poll_side(&a, now);
        poll_side(&b, now);
        deliver(&a, &b, false, NULL);
    }
    assert(a.s.why == GB_SYNC_WHY_SAVE && b.s.why == GB_SYNC_WHY_SAVE);
    assert(a.s.state == GB_SYNC_WAITING && b.s.state == GB_SYNC_WAITING && a.run_writes == 0 && b.run_writes == 0);
    // The other side chooses the same: that choice is the acceptance, no second question.
    gb_sync_set_save(&b.s, BLANK);
    for (int r = 0; r < 500 && (a.s.state != GB_SYNC_RUNNING || b.s.state != GB_SYNC_RUNNING); r++) {
        now += 1;
        poll_side(&a, now);
        poll_side(&b, now);
        deliver(&a, &b, false, NULL);
    }
    assert(a.s.state == GB_SYNC_RUNNING && b.s.state == GB_SYNC_RUNNING);
    // A running game keeps its save id.
    gb_sync_set_save(&a.s, SAVE_CRC);
    assert(a.s.save_crc == BLANK);
    puts("case 2b ok: both play without saves");
}

static void put_u32(uint8_t *p, uint32_t v) { p[0] = v; p[1] = v >> 8; p[2] = v >> 16; p[3] = v >> 24; }
// A READY as some firmware would send it: version, payload length, ids.
static size_t make_ready(uint8_t *u, uint8_t version, uint8_t len, uint32_t rom, uint32_t save, bool gba)
{
    memset(u, 0xee, 3u + len);      // unknown trailing bytes are not zero
    u[0] = 'L'; u[1] = GB_SYNC_UNIT_READY; u[2] = len; u[3] = version; u[4] = DELAY; u[5] = GB_SYNC_READY_ACCEPT;
    put_u32(u + 6, rom);
    put_u32(u + 10, save);
    u[14] = 0;
    u[15] = gba ? 0x80 : 0;
    if (len > 13) u[16] = 0;
    if (len > 14) u[17] = 0;        // no title
    return 3u + len;
}

// GB-162: each mismatch has its reason; an older firmware is told apart from another game,
// and a READY longer than ours (a later firmware of the same version) is accepted.
static void case6_reasons(void)
{
    static Side a;
    uint8_t u[64];
    uint32_t now = 10;
    side_init(&a, 0, DELAY, ROM_CRC, SAVE_CRC);
    poll_side(&a, now);
    assert(gb_sync_phase(&a.s) == GB_SYNC_PHASE_WAITING);

    gb_sync_receive(&a.s, u, make_ready(u, 3, 13, ROM_CRC, SAVE_CRC, false), now);
    assert(a.s.why == GB_SYNC_WHY_FIRMWARE && a.s.state == GB_SYNC_WAITING);
    gb_sync_receive(&a.s, u, make_ready(u, GB_SYNC_VERSION + 1, 30, ROM_CRC, SAVE_CRC, false), now);
    assert(a.s.why == GB_SYNC_WHY_FIRMWARE);
    gb_sync_receive(&a.s, u, make_ready(u, GB_SYNC_VERSION, GB_SYNC_READY_LEN, ROM_CRC, SAVE_CRC, true), now);
    assert(a.s.why == GB_SYNC_WHY_SYSTEM);
    gb_sync_receive(&a.s, u, make_ready(u, GB_SYNC_VERSION, GB_SYNC_READY_LEN, ROM_CRC ^ 1u, SAVE_CRC ^ 1u, false), now);
    assert(a.s.why == GB_SYNC_WHY_GAME);
    gb_sync_receive(&a.s, u, make_ready(u, GB_SYNC_VERSION, GB_SYNC_READY_LEN, ROM_CRC, SAVE_CRC ^ 1u, false), now);
    assert(a.s.why == GB_SYNC_WHY_SAVE);
    assert(gb_sync_phase(&a.s) == GB_SYNC_PHASE_REFUSED && !a.s.partner_ready && a.run_writes == 0);
    assert(a.refused == 4);     // one line per change of reason
    assert(a.s.partner_title[0] == 0);

    // The other game's title is known while refused.
    make_ready(u, GB_SYNC_VERSION, GB_SYNC_READY_LEN + 5, ROM_CRC ^ 1u, SAVE_CRC, false);
    u[17] = 5; memcpy(u + 18, "Kirby", 5);
    gb_sync_receive(&a.s, u, 3u + u[2], now);
    assert(a.s.why == GB_SYNC_WHY_GAME && !strcmp(a.s.partner_title, "Kirby"));

    // A longer READY: a title, then bytes this version does not know.
    make_ready(u, GB_SYNC_VERSION, 30, ROM_CRC, SAVE_CRC, false);
    u[5] = 0;                       // the partner's player has not accepted
    u[17] = 4; memcpy(u + 18, "Test", 4);
    gb_sync_receive(&a.s, u, 3u + u[2], now);
    assert(a.s.why == GB_SYNC_WHY_NONE && a.s.partner_ready && !strcmp(a.s.partner_title, "Test"));
    // A title length that does not fit the unit is another firmware, not a crash.
    make_ready(u, GB_SYNC_VERSION, GB_SYNC_READY_LEN + 2, ROM_CRC, SAVE_CRC, false);
    u[17] = 200;
    gb_sync_receive(&a.s, u, 3u + u[2], now);
    assert(a.s.why == GB_SYNC_WHY_FIRMWARE);
    make_ready(u, GB_SYNC_VERSION, 30, ROM_CRC, SAVE_CRC, false);
    u[5] = 0;
    gb_sync_receive(&a.s, u, 3u + u[2], now);

    // Nobody accepted: found, not started.
    poll_side(&a, ++now);
    assert(a.s.state == GB_SYNC_WAITING && gb_sync_phase(&a.s) == GB_SYNC_PHASE_FOUND && a.run_writes == 0);
    // Only this player accepted: still not started, and the READY says so at once.
    a.nout = 0;
    gb_sync_accept(&a.s);
    poll_side(&a, ++now);
    assert(a.s.state == GB_SYNC_WAITING && gb_sync_phase(&a.s) == GB_SYNC_PHASE_ACCEPTED && a.run_writes == 0);
    assert(a.nout == 1 && (a.out[0].d[5] & GB_SYNC_READY_ACCEPT));
    // Both accepted.
    gb_sync_receive(&a.s, u, make_ready(u, GB_SYNC_VERSION, 30, ROM_CRC, SAVE_CRC, false), now);
    poll_side(&a, ++now);
    assert(a.s.state == GB_SYNC_RUNNING);

    // Once running, a partner that shows up in another game ends this one.
    gb_sync_receive(&a.s, u, make_ready(u, GB_SYNC_VERSION, GB_SYNC_READY_LEN, ROM_CRC ^ 1u, SAVE_CRC, false), now);
    assert(a.s.state == GB_SYNC_FAULT && a.s.why == GB_SYNC_WHY_GAME);
    assert(gb_sync_phase(&a.s) == GB_SYNC_PHASE_FAILED);
    puts("case 6 ok: reasons");
}

// GB-162: searching, a partner that is not in a link game, a partner that went away.
static void case7_partner(void)
{
    static Side a;
    uint8_t u[64];
    uint32_t now = 100;
    side_init(&a, 0, DELAY, ROM_CRC, SAVE_CRC);
    for (int i = 0; i < 5000; i++) gb_sync_poll(&a.s, ++now, false);
    assert(gb_sync_phase(&a.s) == GB_SYNC_PHASE_SEARCHING && a.nout == 0);

    for (int i = 0; i < GB_SYNC_SILENT_MS - 1; i++) gb_sync_poll(&a.s, ++now, true);
    assert(gb_sync_phase(&a.s) == GB_SYNC_PHASE_WAITING);
    a.nout = 0;
    for (int i = 0; i < 10; i++) gb_sync_poll(&a.s, ++now, true);
    assert(a.s.why == GB_SYNC_WHY_SILENT && gb_sync_phase(&a.s) == GB_SYNC_PHASE_REFUSED);
    // It keeps asking: the partner may start the game later.
    for (int i = 0; i < 1000; i++) { a.nout = 0; gb_sync_poll(&a.s, ++now, true); }
    gb_sync_receive(&a.s, u, make_ready(u, GB_SYNC_VERSION, GB_SYNC_READY_LEN, ROM_CRC, SAVE_CRC, false), now);
    assert(a.s.why == GB_SYNC_WHY_NONE);

    side_init(&a, 0, DELAY, ROM_CRC, SAVE_CRC);
    gb_sync_poll(&a.s, ++now, true);
    gb_sync_poll(&a.s, ++now, false);
    assert(gb_sync_phase(&a.s) == GB_SYNC_PHASE_LOST);
    // Back again: the silence is counted from now.
    for (int i = 0; i < GB_SYNC_SILENT_MS - 1; i++) { a.nout = 0; gb_sync_poll(&a.s, ++now, true); }
    assert(gb_sync_phase(&a.s) == GB_SYNC_PHASE_WAITING);
    puts("case 7 ok: partner");
}

// GB-162: a console that leaves says so; the other one does not wait for a timeout.
static void case8_leave(void)
{
    static Side a, b;
    uint32_t now = 0;
    side_init(&a, 0, DELAY, ROM_CRC, SAVE_CRC);
    side_init(&b, 1, DELAY, ROM_CRC, SAVE_CRC);
    snprintf(b.s.title, sizeof(b.s.title), "%.24s", "Mario Kart Super Circuit Long Name");
    // Found, not accepted: the partner leaves.
    for (int r = 0; r < 3; r++) {
        now += 1;
        poll_side(&a, now);
        poll_side(&b, now);
        deliver(&a, &b, false, NULL);
    }
    assert(gb_sync_phase(&a.s) == GB_SYNC_PHASE_FOUND);
    assert(strlen(a.s.partner_title) == GB_SYNC_TITLE && !strncmp(a.s.partner_title, "Mario Kart Super Circuit", 24));
    gb_sync_stop(&b.s);
    deliver(&a, &b, false, NULL);
    assert(a.s.state == GB_SYNC_WAITING && a.s.why == GB_SYNC_WHY_SILENT && !a.s.partner_ready);
    assert(a.s.partner_title[0] == 0 && gb_sync_phase(&a.s) == GB_SYNC_PHASE_REFUSED);

    // Running: the partner leaves.
    side_init(&a, 0, DELAY, ROM_CRC, SAVE_CRC);
    side_init(&b, 1, DELAY, ROM_CRC, SAVE_CRC);
    start_pair(&a, &b, &now);
    assert(a.s.state == GB_SYNC_RUNNING && b.s.state == GB_SYNC_RUNNING);
    gb_sync_stop(&b.s);
    deliver(&a, &b, false, NULL);
    assert(a.s.state == GB_SYNC_FAULT && a.s.why == GB_SYNC_WHY_LEFT);
    assert(gb_sync_phase(&a.s) == GB_SYNC_PHASE_FAILED);
    puts("case 8 ok: leave");
}

static void case3_healthy(void)
{
    static Side a, b;
    uint32_t now = 0;
    side_init(&a, 0, DELAY, ROM_CRC, SAVE_CRC);
    side_init(&b, 1, DELAY, ROM_CRC, SAVE_CRC);
    start_pair(&a, &b, &now);
    assert(a.s.state == GB_SYNC_RUNNING && b.s.state == GB_SYNC_RUNNING);
    run_lockstep(&a, &b, &now, -1);
    check_records(&a, 1);
    check_records(&b, 0);
    assert(a.diverge == 0 && b.diverge == 0);
    assert(a.refused == 0 && b.refused == 0);
    assert(a.s.gaps == 0 && b.s.gaps == 0);
    assert(a.s.compared > 0 && b.s.compared > 0);
    puts("case 3 ok: healthy 200 frames");
}

static void case4_lossy(void)
{
    static Side a, b;
    uint32_t now = 0;
    side_init(&a, 0, DELAY, ROM_CRC, SAVE_CRC);
    side_init(&b, 1, DELAY, ROM_CRC, SAVE_CRC);
    start_pair(&a, &b, &now);
    assert(a.s.state == GB_SYNC_RUNNING && b.s.state == GB_SYNC_RUNNING);
    run_lossy(&a, &b, &now);
    check_records(&a, 1);
    check_records(&b, 0);
    assert(a.s.state == GB_SYNC_RUNNING && b.s.state == GB_SYNC_RUNNING);
    assert(a.diverge == 0 && b.diverge == 0);
    assert(a.refused == 0 && b.refused == 0);
    assert(a.s.compared > 0 && b.s.compared > 0);
    puts("case 4 ok: loss and reordering");
}

static void case5_divergence(void)
{
    static Side a, b;
    uint32_t now = 0;
    side_init(&a, 0, DELAY, ROM_CRC, SAVE_CRC);
    side_init(&b, 1, DELAY, ROM_CRC, SAVE_CRC);
    start_pair(&a, &b, &now);
    assert(a.s.state == GB_SYNC_RUNNING && b.s.state == GB_SYNC_RUNNING);
    run_lockstep(&a, &b, &now, 50);
    check_records(&a, 1);
    check_records(&b, 0);
    assert(a.diverge + b.diverge >= 1);
    assert(a.diverge <= 1 && b.diverge <= 1);
    if (a.diverge) assert(strstr(a.diverge_line, "frame=50") != NULL);
    if (a.diverge) assert(gb_sync_phase(&a.s) == GB_SYNC_PHASE_DIVERGED);
    if (b.diverge) assert(strstr(b.diverge_line, "frame=50") != NULL);
    puts("case 5 ok: divergence");
}

// Case 6 (frame number wrap) is skipped: the first apply frame is fixed by
// gb_sync_init (expected = delay), and the test cannot start higher without
// touching module internals.

int main(void)
{
    setvbuf(stdout, NULL, _IONBF, 0);
    case1_start();
    case2_mismatch();
    case2b_blank_save();
    case3_healthy();
    case4_lossy();
    case5_divergence();
    case6_reasons();
    case7_partner();
    case8_leave();
    printf("sync_test: PASS\n");
    return 0;
}
