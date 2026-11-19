// SPDX-License-Identifier: GPL-3.0-only
// GB-156 host test: gamebub_cwl_sync.c with the GBA core layout (ten buttons, frame at
// [25:10], GBA caps). Two consoles with a fake FPGA each; every third unit is lost.
#include "gamebub_cwl_sync.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>

#define DELAY 2
#define FRAMES 300
#define QCAP 64
#define PKT_MAX 64

typedef struct { uint8_t d[PKT_MAX]; size_t n; } Packet;
typedef struct Side {
    GbSync s;
    int id;
    uint32_t control, phase;
    unsigned produced, popped;      // local samples made and removed
    unsigned written;               // partner frames taken, in order from DELAY
    uint16_t partner[FRAMES + 16];
    Packet out[QCAP];
    int nout, diverge, refused, go;
    unsigned sends;
} Side;

// Ten-bit buttons that use both bits above the GB eight.
static uint16_t buttons_of(int id, unsigned frame) { return (uint16_t)((frame * 37u + (unsigned)id * 0x155u + 0x200u) & 0x3ffu); }
// Digest of player `p` at a frame: the same on both consoles.
static uint32_t digest_of(int p, unsigned frame) { return 0x9e3779b1u * (frame + 1u) + (uint32_t)p; }

static bool side_read(void *ctx, uint32_t a, uint32_t *v)
{
    Side *x = ctx;
    const unsigned n = x->popped;
    const bool have = x->popped < x->produced;
    switch (a) {
    case LINK_SYNC_CAPS: *v = GBA_SYNC_CAPS; return true;
    case LINK_SYNC_LOCAL: *v = have ? (1u << 31) | ((n + DELAY) << 10) | buttons_of(x->id, n + DELAY) : 0u; return true;
    // Player 1 is side 0: its visible core is player 1; side 1 sees player 2.
    case LINK_SYNC_DIGEST_V: *v = have ? digest_of(x->id, n) : 0u; return true;
    case LINK_SYNC_DIGEST_H: *v = have ? digest_of(1 - x->id, n) : 0u; return true;
    case LINK_SYNC_STATUS: *v = x->produced; return true;
    default: *v = 0; return true;
    }
}
static bool side_write(void *ctx, uint32_t a, uint32_t v)
{
    Side *x = ctx;
    switch (a) {
    case LINK_SYNC_CONTROL: x->control = v; return true;
    case LINK_SYNC_PHASE: x->phase = v; return true;
    case LINK_SYNC_POP: assert(x->popped < x->produced); x->popped++; return true;
    case LINK_SYNC_PARTNER: {
        const unsigned frame = (v >> 10) & 0xffffu;
        assert(frame == DELAY + x->written);            // in order, no holes, no repeats
        assert(frame < FRAMES + 16);
        x->partner[frame] = v & 0x3ffu;
        x->written++;
        return true;
    }
    default: return true;
    }
}
static void side_send(void *ctx, const uint8_t *unit, size_t n)
{
    Side *x = ctx;
    assert(n <= PKT_MAX);
    x->sends++;
    if (x->sends % 3 == 0 || x->nout == QCAP) return;   // lost
    memcpy(x->out[x->nout].d, unit, n);
    x->out[x->nout++].n = n;
}
static void side_log(void *ctx, const char *line)
{
    Side *x = ctx;
    if (strstr(line, "DIVERGE")) x->diverge++;
    if (strstr(line, "REFUSED") || strstr(line, "FAULT")) { x->refused++; printf("side %d: %s\n", x->id, line); }
    if (strstr(line, "sync: GO")) x->go++;
}
static bool side_player2(void *ctx) { return ((Side *)ctx)->id == 1; }

// The fake module: a frame boundary passes when the partner's buttons for it are there.
static void fpga_step(Side *x)
{
    if (!(x->control & 2u) || x->produced - x->popped >= 8 || x->produced >= FRAMES) return;
    const unsigned frame = x->produced;
    if (frame >= DELAY && x->written + DELAY <= frame) return;
    x->produced++;
}

int main(void)
{
    static Side side[2];
    for (int i = 0; i < 2; i++) {
        Side *x = &side[i];
        x->id = i;
        const GbSyncIo io = {.ctx = x, .read = side_read, .write = side_write, .send = side_send,
                             .log = side_log, .player2 = side_player2};
        gb_sync_init(&x->s, &io, DELAY, 0x11223344u, 0x55667788u);
        x->s.gba = true;
    }
    for (uint32_t now = 1; now < 200000 && (side[0].popped < FRAMES || side[1].popped < FRAMES); now++) {
        for (int i = 0; i < 2; i++) {
            fpga_step(&side[i]);
            gb_sync_poll(&side[i].s, now, true);
            gb_sync_accept(&side[i].s);     // both players press A as soon as they may
        }
        for (int i = 0; i < 2; i++) {
            Side *from = &side[i], *to = &side[1 - i];
            for (int k = 0; k < from->nout; k++) gb_sync_receive(&to->s, from->out[k].d, from->out[k].n, now);
            from->nout = 0;
        }
    }
    for (int i = 0; i < 2; i++) {
        Side *x = &side[i];
        assert(x->go == 1);
        assert(x->refused == 0 && x->diverge == 0);
        assert(x->popped == FRAMES);
        assert((x->control & 0xffu) == (3u | DELAY << 4 | (unsigned)i << 7));   // no ROM cache bits
        assert(x->phase == GBA_SYNC_PHASE);
        assert(x->s.compared > FRAMES / 2);
        // Every partner frame arrived with all ten bits.
        assert(x->written >= FRAMES - DELAY - 8);
        for (unsigned f = DELAY; f < DELAY + x->written; f++) assert(x->partner[f] == buttons_of(1 - i, f));
    }
    printf("sync_gba_test: %u frames, compared %u/%u, gaps %u/%u: OK\n", (unsigned)FRAMES,
           (unsigned)side[0].s.compared, (unsigned)side[1].s.compared, (unsigned)side[0].s.gaps, (unsigned)side[1].s.gaps);
    return 0;
}
