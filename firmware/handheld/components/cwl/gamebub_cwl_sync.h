// SPDX-License-Identifier: GPL-3.0-only
// GB-147: frame-locked button exchange for two GB cores per console
// (FPGA: DualCoreSync). Portable; the radio task and the host test share it.
//
// Each console runs its own game (visible core) and the partner's game
// (hidden core) on a local link cable. Only buttons cross the radio. The FPGA
// samples the local buttons on every emulated frame and stops both cores
// until the partner's buttons for the current frame are written, so the two
// consoles need no common clock.
#pragma once
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

// GB core registers (HandheldGameboy with GB_DUAL_CORE=1).
enum {
    LINK_SYNC_CAPS = 0xa0,      // r: 'SY', ABI
    LINK_SYNC_CONTROL = 0xa4,   // rw: enable[0], run[1], delay[6:4]
    LINK_SYNC_PARTNER = 0xac,   // w: frame[23:8], buttons[7:0]
    LINK_SYNC_LOCAL = 0xb0,     // r: valid[31], apply frame[23:8], buttons[7:0]
    LINK_SYNC_DIGEST_V = 0xb4,  // r: visible core digest at (apply frame - delay)
    LINK_SYNC_DIGEST_H = 0xb8,  // r: hidden core digest at the same frame
    LINK_SYNC_POP = 0xbc,       // w: 1 removes the LOCAL head
    LINK_SYNC_STATUS = 0xc0,
    LINK_SYNC_WAIT = 0xc4,      // r: system clocks stopped at a frame boundary
    LINK_SYNC_PHASE = 0xc8,     // rw: core clocks by which player 2's core starts late
    LINK_SYNC_SERIAL_V = 0xcc,  // r: visible core cable: completed[31:16], started as master[15:0]
    LINK_SYNC_SERIAL_H = 0xd0,  // r: hidden core, same layout
    LINK_SYNC_SERIAL_X = 0xd4,  // r: external-clock completions hidden[31:24] visible[23:16], both master[15:0]
    LINK_SYNC_WRITES = 0xd8,    // r: partner writes accepted[31:16], strobes seen[15:0]
    LINK_SYNC_REJECTS = 0xdc,   // r: refused: frame already running[31:16], outside the window[15:0]
    LINK_SYNC_LAST_REJECT = 0xe0, // r: module frame[31:16], written frame[15:0]
    // r: 12 probes, 4 bytes apart. 0 core clocks; stopped by 1 visible cartridge, 2 hidden,
    // 3 both, 4 frame sync; SDRAM ROM reads 5 visible, 6 hidden; clocks with a read pending
    // 7 visible, 8 hidden; answered from kept words 9 visible, 10 hidden; 11 prefetch reads h<<16 | v.
    LINK_SYNC_PROBE = 0xe4,
};
enum { GB_SYNC_PROBES = 12 };
#define GB_SYNC_CAPS 0x53590004u
// GB-156: HandheldGba with GBA_DUAL_CORE=1. Same registers; ten buttons, so the frame sits at
// [25:10] in PARTNER and LOCAL, and 0xe4.. are five stall counters (EWRAM 1, cartridge 1,
// EWRAM 2, cartridge 2, frame boundary). CONTROL has no ROM cache bits.
#define GBA_SYNC_CAPS 0x53594701u
#define GBA_SYNC_PROBES 5
// Core clocks by which player 2's core starts late in a GBA game (a quarter of a frame).
#define GBA_SYNC_PHASE 70000u
// CONTROL bit 7: the local (visible) core is player 2 and starts late by PHASE core clocks.
// CONTROL bit 8: keep the last ROM words per core; bit 9: fetch the next word ahead (speed only).
// About a quarter of a frame. Two cores started in the same clock run the same code in phase;
// in SMB Deluxe the slave then re-arms its port exactly when the master sends, every time
// (GB-150, seen in simulation). The low two bits also decide how the two cores' ROM reads
// fall against each other on the shared SDRAM; player 1 picks them per start (experiment).
#define GB_SYNC_PHASE_BASE 35000u

enum {
    GB_SYNC_UNIT_READY = 0x20,  // 'L' unit kinds
    GB_SYNC_UNIT_INPUT = 0x21,
    // 2: READY carries the radio channel; 3: and the start phase; 4 (GB-162): and a capability
    // byte. From 4 on a READY may be longer than this side knows: the extra bytes are ignored,
    // optional features are announced in the capability byte, and the version changes only
    // when two versions cannot play together.
    // READY payload: version, delay, flags, rom id, save id, channel, phase | gba << 7, capability
    // byte, title length, title.
    GB_SYNC_VERSION = 4,
    GB_SYNC_READY_LEN = 15,     // payload bytes this version sends at least (an empty title)
    GB_SYNC_TITLE = 24,         // game title characters at most
    GB_SYNC_READY_ACCEPT = 2,   // flags bit: this side's player chose to play with this partner
    GB_SYNC_UNIT_BYE = 0x22,    // 'L' unit kind: this side left the link game (no payload)
    GB_SYNC_LOCAL_CAPS = 0,     // capability bits of this firmware (none yet)
    GB_SYNC_SILENT_MS = 3000,   // a connected partner that sends no READY this long is not in a link game
    GB_SYNC_CACHE_MS = 10000,   // the ROM word cache is switched on and off this often (experiment)
    GB_SYNC_HISTORY = 8,        // button samples per INPUT unit
    GB_SYNC_RING = 16,
    GB_SYNC_MAX_DELAY = 4,      // with 8 samples per unit no frame can be skipped up to this delay
    GB_SYNC_READY_MS = 200,
    GB_SYNC_RESEND_MS = 8,
    GB_SYNC_LOG_FRAMES = 60,
};

typedef enum { GB_SYNC_OFF, GB_SYNC_WAITING, GB_SYNC_RUNNING, GB_SYNC_FAULT } GbSyncState;

// GB-162: why the two consoles are not playing. A mismatch seen while waiting is not final:
// this side keeps waiting and starts when the partner's READY matches.
typedef enum {
    GB_SYNC_WHY_NONE,
    GB_SYNC_WHY_SILENT,     // the partner is connected but not in a link game
    GB_SYNC_WHY_FIRMWARE,   // another READY version
    GB_SYNC_WHY_SYSTEM,     // GB against GBA
    GB_SYNC_WHY_GAME,       // another ROM (or cartridge type, or GB/GBC mode)
    GB_SYNC_WHY_SAVE,       // another save
    GB_SYNC_WHY_SETTINGS,   // another button delay
    GB_SYNC_WHY_CORE,       // the loaded core has no second core
    GB_SYNC_WHY_INTERNAL,
    GB_SYNC_WHY_LEFT,       // the partner left the running game
} GbSyncWhy;

// What a screen shows; gb_sync_phase() derives it.
typedef enum {
    GB_SYNC_PHASE_OFF,
    GB_SYNC_PHASE_SEARCHING,    // no partner yet
    GB_SYNC_PHASE_WAITING,      // partner connected, its READY has not come yet
    GB_SYNC_PHASE_REFUSED,      // partner connected, see why
    GB_SYNC_PHASE_RUNNING,
    GB_SYNC_PHASE_LOST,         // the partner was here and is gone
    GB_SYNC_PHASE_DIVERGED,     // the two consoles no longer run the same game state
    GB_SYNC_PHASE_FAILED,       // final until the game is started again, see why
    GB_SYNC_PHASE_FOUND,        // the partner is in the same game: this player has not accepted yet
    GB_SYNC_PHASE_ACCEPTED,     // this player accepted, the partner's has not
} GbSyncPhase;

typedef struct {
    void *ctx;
    bool (*read)(void *, uint32_t address, uint32_t *value);
    bool (*write)(void *, uint32_t address, uint32_t value);
    // One 'L' unit for the partner (unicast).
    void (*send)(void *, const uint8_t *unit, size_t n);
    void (*log)(void *, const char *line);
    // Optional, asked once at the start: this console is player 2 (both consoles must
    // answer differently, e.g. by comparing their addresses).
    bool (*player2)(void *);
    // Optional: the radio channel in use, and a way to move to the partner's. Two consoles that
    // found each other across adjacent channels lose a large share of their packets.
    uint8_t (*channel)(void *);
    void (*set_channel)(void *, uint8_t);
} GbSyncIo;

typedef struct { uint16_t frame; bool used; uint32_t visible, hidden; } GbSyncDigest;

typedef struct {
    GbSyncIo io;
    GbSyncState state;
    uint8_t delay;
    uint32_t rom_crc, save_crc;
    bool caps_ok, partner_ready, partner_input, diverged, fresh;
    uint32_t ready_at, resend_at;
    // Local samples, newest last: buttons for apply frames newest-n+1 .. newest.
    uint16_t buttons[GB_SYNC_HISTORY];
    uint8_t samples;            // valid entries in buttons, at most GB_SYNC_HISTORY
    uint16_t newest;            // apply frame of the newest local sample
    bool any_local;
    uint16_t expected;          // next partner apply frame to write to the FPGA
    GbSyncDigest own[GB_SYNC_RING], partner[GB_SYNC_RING];
    uint16_t digest_frame;      // frame of the newest own digest
    uint32_t frames, compared, gaps, sent, received, write_errors;
    uint32_t log_frames, now;
    // Once-a-second state line (GB-150 diagnosis).
    uint32_t state_at;
    // Recovery: partner buttons already written, by frame, so a write the FPGA
    // did not take can be repeated when it reports waiting for that frame.
    uint16_t partner_buttons[GB_SYNC_RING];
    uint32_t rewrites, fresh_at, rewrite_at;
    uint16_t last_newest;       // newest apply frame in the last INPUT unit received
    uint8_t last_count;
    // Start phase: low two bits, set by the caller; player 1's value is used by both.
    uint8_t phase_idx;
    // GBA core (GB-156): set by the caller right after gb_sync_init. Ten buttons, two bytes
    // per sample in an INPUT unit.
    bool gba;
    // Set by the caller right after gb_sync_init: no once-a-second measurement lines (and
    // none of their register reads). Refusals and faults are always logged.
    bool quiet;
    uint32_t control, cache_at;
    // GB-162: session state for the screen.
    GbSyncWhy why;
    bool connected, met;        // the partner is connected now; was connected at least once
    uint32_t heard_at;          // last READY of any kind, or the moment the partner connected
    uint8_t partner_caps;
    uint8_t player;             // 1 or 2 once started, else 0
    // The game starts when the players of both consoles accepted (gb_sync_accept).
    bool accepted, partner_accepted;
    // The player chose to play without the saves (gb_sync_set_save): that is also the
    // acceptance, given as soon as the partner chose the same.
    bool accept_on_match;
    // Set by the caller right after gb_sync_init; the partner's comes with its READY, whatever game.
    char title[GB_SYNC_TITLE + 1], partner_title[GB_SYNC_TITLE + 1];
    uint32_t probe[GB_SYNC_PROBES];
} GbSync;

// delay: frames between a button sample and its use, 1..GB_SYNC_MAX_DELAY.
// The caller has already written CONTROL = enable | delay << 4 before the core left reset.
void gb_sync_init(GbSync *, const GbSyncIo *, uint8_t delay, uint32_t rom_crc, uint32_t save_crc);
// Tells a connected partner that this side leaves.
void gb_sync_stop(GbSync *);
// Call often (every wake of the radio task). connected: a confirmed, live partner.
void gb_sync_poll(GbSync *, uint32_t now_ms, bool connected);
// unit: 'L', kind, length, payload.
void gb_sync_receive(GbSync *, const uint8_t *unit, size_t n, uint32_t now_ms);
GbSyncPhase gb_sync_phase(const GbSync *);
// The local player chose to play with the connected partner.
void gb_sync_accept(GbSync *);
// The save both cores hold was replaced while waiting (GB-162: play without saves). The
// partner hears the new id with the next READY; ignored once the game runs.
void gb_sync_set_save(GbSync *, uint32_t save_crc);
