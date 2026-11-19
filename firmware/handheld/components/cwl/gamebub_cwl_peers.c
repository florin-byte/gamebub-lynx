// SPDX-License-Identifier: GPL-3.0-only
#include "gamebub_cwl_peers.h"
#include "gamebub_cwl.h"
#include <stdio.h>
#include <string.h>

uint32_t gb_cwl_partner_list(const CwlLxCands_t *candidates, uint32_t now,
    const uint8_t *fixed, const char *remembered_name, gamebub_cwl_partner_t out[6])
{
    CwlLxCand_t fresh[6];
    int count = CwlLx_CandList(candidates, now, fresh, 6);
    uint32_t n = 0;
    if (fixed) {
        memset(&out[n], 0, sizeof(out[n]));
        memcpy(out[n].mac, fixed, 6);
        snprintf(out[n].name, sizeof(out[n].name), "%s", remembered_name);
        for (int i = 0; i < count; i++) {
            if (!memcmp(fresh[i].mac, fixed, 6)) {
                out[n].seen = true;
                snprintf(out[n].name, sizeof(out[n].name), "%s", fresh[i].name);
            }
        }
        n++;
    }
    for (int i = 0; i < count && n < 6; i++) {
        if (fixed && !memcmp(fresh[i].mac, fixed, 6)) continue;
        memset(&out[n], 0, sizeof(out[n]));
        memcpy(out[n].mac, fresh[i].mac, 6);
        snprintf(out[n].name, sizeof(out[n].name), "%s", fresh[i].name);
        out[n++].seen = true;
    }
    return n;
}
void gb_cwl_apply_partner(GbCwlLogic *s, const uint8_t *fixed)
{
    // Reset both the protocol and FPGA port before changing the destination.
    gb_cwl_bridge_stop(s);
    CwlLx_PeerInit(&s->peer, fixed);
    s->ping_pending = false;
    s->name_at = s->ping_at = s->now;
}
bool gamebub_cwl_states_allowed(uint32_t state)
{
    return state == GB_CWL_OFF;
}
