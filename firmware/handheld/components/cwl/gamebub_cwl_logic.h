// SPDX-License-Identifier: GPL-3.0-only
// Portable session policy; the ESP-IDF task and host tests use this same code.
#pragma once
#include "cwl_lx.h"
#include "cwl_proto.h"
#include "gamebub_cwl_gba.h"

#define GB_CWL_TRACE_CAPACITY 128u

typedef struct {
    void *ctx;
    void (*send)(void *, const uint8_t dest[6], const uint8_t *, size_t);
    void (*channel)(void *, uint8_t);
    // One short SPI transaction; false when no running GB core or on I/O error.
    bool (*read)(void *, uint32_t address, uint32_t *value);
    bool (*write)(void *, uint32_t address, uint32_t value);
    // Optional grouped EVENT+POP, preserving the post-POP generation guard.
    bool (*event_pop)(void *, uint32_t *);
    uint32_t (*clock_us)(void *);
    // Optional: run accesses in order under one lock/bus acquisition; returns
    // how many completed (GB-114). Without it, read/write run one by one.
    size_t (*batch)(void *, GbLinkOp *, size_t);
} GbCwlIo;

typedef struct GbCwlLogic {
    GbaCwl gba;
    CwlState_t core;
    CwlLxPeer_t peer;
    CwlLxCands_t candidates;
    GbCwlIo io;
    char name[16];
    uint32_t now, sweep_at, name_at, ping_at;
    uint32_t rx, tx, bad, rtt_ms, pongs;
    uint32_t ping_time;
    uint8_t channel, sweep_idx, ping_id;
    bool ping_pending;
    bool core_running, caps_read, paused;
    uint32_t caps;
    bool bridge_on, bridge_fault;
    uint8_t generation, transfer_id;
    uint32_t xmaster, xslave, xdrop, master_at;
    // First completed exchanges in each bridge session, in game byte order.
    uint8_t first_tx[16], first_rx[16], first_n, first_reported;
    uint8_t master_byte, slave_byte;
    // Bounded one-second RTT sample; allocated with the session, never on main.
    uint32_t rtt[256], rtt_n, rtt_max;
    // Diagnostic flight recorder, owned by the radio task. No hot-path logging.
    // E=FPGA event, C=command written, R/T=CWL game packet, I=id invalidation,
    // S=stale event/verdict rejected, X=channel reset. HELLO traffic does not
    // displace game bytes.
    bool trace_enabled;
    struct { uint32_t at, at_us, value, last_us, repeats; char kind; } trace[GB_CWL_TRACE_CAPACITY];
    unsigned trace_head, trace_count;
    uint32_t bridge_status;
    // Session-owned latency samples; no allocation or logging per exchange.
    uint32_t irq_us, received_us, hops_us[6], latency[64][6];
    uint32_t latency_n, hop_max[5];
    bool timing_master;

} GbCwlLogic;

void gb_cwl_trace(GbCwlLogic *, char kind, uint32_t value);

void gb_cwl_logic_init(GbCwlLogic *, const GbCwlIo *, const uint8_t mac[6], uint16_t session, uint32_t now);
void gb_cwl_logic_receive(GbCwlLogic *, const uint8_t src[6], bool unicast,
                          int8_t rssi, const uint8_t *, size_t, uint32_t now);
void gb_cwl_logic_poll(GbCwlLogic *, uint32_t now);
bool gb_cwl_logic_connected(const GbCwlLogic *, uint32_t now);
const char *gb_cwl_logic_partner(const GbCwlLogic *);

// Owned by the radio task, including protocol callbacks and all FPGA commands.
const char *gb_cwl_bridge_config_reason(const GbCwlLogic *, uint32_t now);
void gb_cwl_bridge_poll(GbCwlLogic *, uint32_t now);
void gb_cwl_bridge_stop(GbCwlLogic *);
void gb_cwl_bridge_event(GbCwlLogic *, uint32_t event);
void gb_cwl_bridge_reply(void *, uint8_t);
void gb_cwl_bridge_drop(void *);
void gb_cwl_bridge_offer(void *, uint8_t);
void gb_cwl_bridge_withdraw(void *);
