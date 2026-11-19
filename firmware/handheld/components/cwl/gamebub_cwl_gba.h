// SPDX-License-Identifier: GPL-3.0-only
#pragma once
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define GBA_CWL_CAPS 0x57470101u
// LX extension kind 0x10, version 1. Existing CWL2 peers skip this unit.
enum {
    GBA_CWL_HELLO, GBA_CWL_READY, GBA_CWL_REQUEST, GBA_CWL_ACK,
    GBA_CWL_READY16, GBA_CWL_ROUND16, GBA_CWL_RESULT16, GBA_CWL_GRANT16, GBA_CWL_OP_COUNT
};
enum { GBA_CWL_START = 1, GBA_CWL_DONE = 2, GBA_CWL_CANCEL = 3 };
enum {
    GBA_RX_PARSE, GBA_RX_ROUTE, GBA_RX_SHAPE, GBA_RX_SESSION,
    GBA_RX_EPOCH, GBA_RX_DEST, GBA_RX_TOKEN, GBA_RX_REJECT_COUNT
};
enum {
    GBA_STOP_EXPLICIT, GBA_STOP_UNAVAILABLE, GBA_STOP_COMMAND,
    GBA_STOP_STATUS, GBA_STOP_FIFO
};
// Microsecond split of a transfer: 250 us bins, bucket 32 includes all >=8 ms.
// WAIT: parent START -> first ROUND/REQUEST sent (child READY + MCU poll).
// REMOTE: parent first ROUND/REQUEST -> RESULT/ACK accepted (radio + child).
// CHILD_WAIT: child START -> ROUND/REQUEST accepted. CHILD_RUN: ROUND -> DONE.
// CHILD_BEHIND: guest time the child still had to run to the round cycle when
// its ROUND command was applied (measurement, GB-113).
enum { GBA_SPLIT_WAIT, GBA_SPLIT_REMOTE, GBA_SPLIT_CHILD_WAIT, GBA_SPLIT_CHILD_RUN,
       GBA_SPLIT_CHILD_BEHIND, GBA_SPLIT_COUNT };
typedef struct { uint16_t bins[33]; uint32_t n, max, sum; } GbaSplit;
// GB-115: every step of a round, in microseconds (count, sum, max; 12 bytes each).
// Parent: DETECT FPGA START IRQ -> START handled; RTT ROUND sent -> RESULT in the
// radio callback; RXQ RESULT callback -> handled; DONE handled -> COMPLETE written.
// Child: RXQ ROUND callback -> handled; CMD handled -> command written; RUN command
// -> DONE IRQ (emulation to the round cycle); DETECT DONE IRQ -> DONE handled;
// SEND DONE handled -> RESULT sent; GRANT GRANT callback -> applied in the FPGA.
enum { GBA_HOP_P_DETECT, GBA_HOP_P_RTT, GBA_HOP_P_RXQ, GBA_HOP_P_DONE,
       GBA_HOP_C_RXQ, GBA_HOP_C_CMD, GBA_HOP_C_RUN, GBA_HOP_C_DETECT, GBA_HOP_C_SEND,
       GBA_HOP_C_GRANT, GBA_HOP_COUNT };
typedef struct { uint32_t n, sum, max; } GbaHop;
// One FPGA link access in a batch; same layout as Rust fpga::LinkOp.
typedef struct { uint32_t address, value, write; } GbLinkOp;
// Counters survive a bridge reset and belong to the link task.
typedef struct {
    GbaSplit split[GBA_SPLIT_COUNT];
    uint32_t tx[GBA_CWL_OP_COUNT], rx[GBA_CWL_OP_COUNT], reject[GBA_RX_REJECT_COUNT];
    uint32_t start[2], done, cancel, timeout, stop, config_error;
    uint32_t command_ok, command_error, send_error;
    uint32_t status, token, outgoing, incoming, command_word;
    uint32_t epoch, remote_epoch, seen_epoch;
    // Millisecond completion histogram; bucket 64 includes all >=64 ms.
    uint32_t round_ms[65], round_n, round_max_ms;
    uint32_t ready_idle, ready_stale;
    // Measurement (GB-113). baud: multi STARTs per SIOCNT rate 9600/38400/57600/115200.
    // spi_*: every FPGA access. crit_*: SPI accesses on the critical path of a
    // round (parent START -> COMPLETE, child ROUND -> RESULT). to_*: last parent timeout.
    uint32_t baud[4], spi_ops, spi_us, spi_batches;
    uint32_t crit_parent_n, crit_parent_ops, crit_parent_spi_us;
    uint32_t crit_child_n, crit_child_ops, crit_child_spi_us;
    uint32_t to_n, to_ready_age_ms, to_round_tx;
    bool to_requesting, to_ready;
    GbaHop hop[GBA_HOP_COUNT];
    uint32_t unsync_switches; // GB-116: CONFIG changes between synced and unsynced rounds
    bool unsynced;            // GB-116: current mode, for the log
    // GB-117: child RESULT sent at BEGIN (early), with a DONE word that differed
    // (early_mismatch, must stay 0); parent rounds completed from the one-round
    // buffer (buffered) or not, while buffering was allowed (buffer_miss).
    uint32_t early, early_mismatch, buffered, buffer_miss;
    uint32_t buffer_wait; // parent START held until the previous exchange finished
    unsigned role, stop_reason;
} GbaCwlTrace;

typedef struct {
    bool on, fault, active, master, remote_ready, awaiting_done, cached, requesting;
    uint32_t epoch, remote_epoch, token, word, remote_token;
    uint32_t request_token, request_slave, request_word, cached_word;
    uint32_t beacon_at, retry_at, started_at, remote_ready_at;
    uint8_t generation, config;
    bool multi_supported, local_multi, peer_multi_supported, peer_multi;
    bool multi, ready_multi, cached_multi, remote_token_known;
    bool sync_supported, peer_sync_supported, sync, cached_sync;
    uint32_t cycles, request_cycles;
    bool grant_supported, peer_grant_supported, grant_confirmed;
    uint32_t grant_parent, grant_child, grant_at, grant_at_us;
    uint32_t crit_ops0, crit_us0; // SPI totals at the start of the current critical path
    uint32_t cmd_done_us, grant_rx_us; // GB-115: child command written / GRANT received
    // GB-116: unsync = this side asks for unsynced rounds; unsynced = applied to CONFIG.
    bool unsync, peer_unsync, unsynced;
    // Parent START spacing: pairing rounds come ~5 ms apart, paired ones ~21 ms.
    uint32_t slow_streak, fast_streak, last_start_at;
    bool last_start_known;
    uint32_t status_read_us; // GB-116: last FPGA STATUS read
    // GB-117: FPGA has SAMPLED + CONFIG pair bit (CONFIG read bit 28); the peer too.
    bool sampled_supported, peer_pair;
    bool answered;            // child: RESULT already sent at BEGIN
    // Parent one-round buffer: prev_pair = last exchanged (child<<16)|parent pair;
    // pair_round = this round was completed at START with round_pair.
    bool have_prev, pair_round;
    uint32_t prev_pair, round_pair;
    // A parent START that arrived before the previous buffered exchange ended;
    // it starts once that RESULT is in, so both games keep the same round count.
    bool queued;
    uint32_t q_event, q_id, q_word, q_cycles;
    // GB-117: a paused emulator (menu) on either side holds the link in cycle sync.
    bool peer_paused, sent_paused;
    bool grant_pending;
    uint32_t grant_rx_anchor, grant_rx_cycles, grant_applied_at;
    uint32_t peer_multi_at;
    uint32_t started_us, request_us;
    GbaCwlTrace trace;
} GbaCwl;
struct GbCwlLogic;
void gba_cwl_poll(struct GbCwlLogic *, uint32_t now);
void gba_cwl_stop(struct GbCwlLogic *);
void gba_cwl_receive(struct GbCwlLogic *, const uint8_t *, size_t);
// GB-117: allow the parent's one-round buffer (package B); default GBA_CWL_BUFFER.
extern bool gba_cwl_buffer;
void gba_split_add(GbaSplit *, uint32_t us);
uint32_t gba_split_percentile(const GbaSplit *, unsigned percent);
