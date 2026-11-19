// SPDX-License-Identifier: GPL-3.0-only
#include "gamebub_cwl_peers.h"
#include "gamebub_cwl.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>

static void list_test(void)
{
    CwlLxCands_t c = {0};
    gamebub_cwl_partner_t out[6];
    uint8_t a[6] = {2,0,0,0,1,1}, b[6] = {2,0,0,0,2,2};
    assert(gb_cwl_partner_list(&c, 0, NULL, "", out) == 0);
    assert(gb_cwl_partner_list(&c, 0, a, "SAVED", out) == 1);
    assert(!out[0].seen && !strcmp(out[0].name, "SAVED"));
    CwlLx_CandSeen(&c, a, -80, 100);
    CwlLx_CandName(&c, a, "ALPHA", 5);
    CwlLx_CandSeen(&c, b, -30, 101);
    CwlLx_CandName(&c, b, "BETA", 4);
    assert(gb_cwl_partner_list(&c, 101, NULL, "", out) == 2);
    assert(!memcmp(out[0].mac, b, 6)); // RSSI order
    assert(gb_cwl_partner_list(&c, 101, a, "OLD", out) == 2);
    assert(out[0].seen && !strcmp(out[0].name, "ALPHA")); // remembered first, no duplicate
    assert(!memcmp(out[1].mac, b, 6));
    assert(gb_cwl_partner_list(&c, 10100, a, "SAVED", out) == 2);
    assert(!out[0].seen && !strcmp(out[0].name, "SAVED")); // exact expiry
    assert(gb_cwl_partner_list(&c, 10101, NULL, "", out) == 0);
    memset(&c, 0, sizeof(c));
    CwlLx_CandSeen(&c, a, -40, UINT32_MAX - 9);
    assert(gb_cwl_partner_list(&c, 10, NULL, "", out) == 1); // clock wrap
    for (int i = 0; i < 6; i++) {
        b[5] = i;
        CwlLx_CandSeen(&c, b, -20-i, 20);
    }
    a[5] = 99;
    assert(gb_cwl_partner_list(&c, 20, a, "ABSENT", out) == 6);
}
static void partner_test(void)
{
    GbCwlLogic s = {0};
    uint8_t a[6] = {2,0,0,0,1,1}, b[6] = {2,0,0,0,2,2};
    GbCwlIo io = {0};
    gb_cwl_logic_init(&s, &io, a, 1, 0);
    s.ping_pending = true;
    gb_cwl_apply_partner(&s, b);
    assert(s.peer.fixed && s.peer.locked && !s.ping_pending);
    assert(!CwlLx_PeerAccept(&s.peer, a, true, 1));
    assert(CwlLx_PeerAccept(&s.peer, b, true, 1));
    gb_cwl_apply_partner(&s, NULL);
    assert(!s.peer.fixed && !s.peer.locked && !s.peer.confirmed);
    assert(CwlLx_PeerAccept(&s.peer, a, true, 2));
}
int main(void)
{
    list_test();
    partner_test();
    assert(gamebub_cwl_states_allowed(GB_CWL_OFF));
    assert(!gamebub_cwl_states_allowed(GB_CWL_SEARCHING)); // also Partner lost
    assert(!gamebub_cwl_states_allowed(GB_CWL_CONNECTED));
    puts("peers_test: OK (list, expiry, RSSI, fixed/Any, save-state refusal)");
}
