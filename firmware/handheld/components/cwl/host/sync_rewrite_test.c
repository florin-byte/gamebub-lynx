// SPDX-License-Identifier: GPL-3.0-only
// GB-150: a partner-buttons write that the FPGA did not take is written again
// when the FPGA reports that it waits for that frame.
#include "../gamebub_cwl_sync.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>

static uint32_t status, control;
static uint32_t partner[32];
static unsigned partner_n, drop_frame = 0xffff, rewrite_lines;

static bool rd(void *ctx, uint32_t a, uint32_t *v)
{
    (void)ctx;
    *v = a == LINK_SYNC_CAPS ? GB_SYNC_CAPS : a == LINK_SYNC_STATUS ? status : 0;
    return true;
}
static bool wr(void *ctx, uint32_t a, uint32_t v)
{
    (void)ctx;
    if (a == LINK_SYNC_CONTROL) control = v;
    if (a == LINK_SYNC_PARTNER) {
        if ((v >> 8) == drop_frame) { drop_frame = 0xffff; return true; }  // lost on the way
        assert(partner_n < 32);
        partner[partner_n++] = v;
    }
    return true;
}
static void send(void *ctx, const uint8_t *u, size_t n) { (void)ctx; (void)u; (void)n; }
static void logline(void *ctx, const char *line) { (void)ctx; if (strstr(line, "REWRITE")) rewrite_lines++; }

int main(void)
{
    GbSync s;
    const GbSyncIo io = {.read = rd, .write = wr, .send = send, .log = logline};
    gb_sync_init(&s, &io, 2, 0x11, 0x22);
    gb_sync_poll(&s, 0, true);
    const uint8_t ready[] = {'L', GB_SYNC_UNIT_READY, GB_SYNC_READY_LEN, GB_SYNC_VERSION, 2, GB_SYNC_READY_ACCEPT,
                             0x11, 0, 0, 0, 0x22, 0, 0, 0, 6, 1, 0, 0};
    gb_sync_receive(&s, ready, sizeof(ready), 1);
    gb_sync_accept(&s);
    gb_sync_poll(&s, 2, true);
    assert(s.state == GB_SYNC_RUNNING && control == 0x323);

    // Frames 2, 3, 4 from the partner; the write of frame 3 is lost.
    drop_frame = 3;
    const uint8_t input[] = {'L', GB_SYNC_UNIT_INPUT, 3 + 3 + 10, 4, 0, 3, 0xa2, 0xa3, 0xa4,
                             0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    gb_sync_receive(&s, input, sizeof(input), 3);
    assert(s.expected == 5 && partner_n == 2 && partner[0] == 0x2a2 && partner[1] == 0x4a4);

    // Not waiting (cores run, or menu open): nothing is rewritten.
    status = 1u << 24 | 2;
    gb_sync_poll(&s, 100, true);
    assert(s.rewrites == 0 && partner_n == 2);
    // Waiting for frame 3, which this side believes it wrote: write it again.
    status = 1u << 24 | 1u << 21 | 1u << 20 | 3;
    gb_sync_poll(&s, 200, true);
    assert(s.rewrites == 1 && partner_n == 3 && partner[2] == 0x3a3 && rewrite_lines == 1);
    // Not again within 20 ms.
    gb_sync_poll(&s, 205, true);
    assert(s.rewrites == 1);
    // Waiting for frame 5, not received yet: nothing to rewrite.
    status = 1u << 24 | 1u << 21 | 1u << 20 | 5;
    gb_sync_poll(&s, 300, true);
    assert(s.rewrites == 1 && partner_n == 3);
    puts("sync_rewrite_test: PASS");
    return 0;
}
