// SPDX-License-Identifier: GPL-3.0-only
#pragma once
#include "gamebub_cwl_logic.h"

// Any is represented by the UI separately. The remembered row precedes fresh
// candidates, without duplicates; at most six concrete choices in total.
typedef struct {
    uint8_t mac[6];
    char name[16];
    bool seen;
} gamebub_cwl_partner_t;
uint32_t gb_cwl_partner_list(const CwlLxCands_t *, uint32_t now,
    const uint8_t *fixed, const char *remembered_name, gamebub_cwl_partner_t out[6]);
void gb_cwl_apply_partner(GbCwlLogic *, const uint8_t *fixed);
bool gamebub_cwl_states_allowed(uint32_t state);

// Thread-safe owner/UI API, implemented beside the radio synchronization.
uint32_t gamebub_cwl_partners(gamebub_cwl_partner_t out[6]);
void gamebub_cwl_set_partner(const uint8_t *mac, const char *name);
void gamebub_cwl_set_name(const char *name);
