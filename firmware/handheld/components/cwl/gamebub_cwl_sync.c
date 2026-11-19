// SPDX-License-Identifier: GPL-3.0-only
#include "gamebub_cwl_sync.h"
#include <stdio.h>
#include <string.h>

static bool rd(GbSync *s, uint32_t a, uint32_t *v) { return s->io.read && s->io.read(s->io.ctx, a, v); }
static bool wr(GbSync *s, uint32_t a, uint32_t v) { return s->io.write && s->io.write(s->io.ctx, a, v); }
static void say(GbSync *s, const char *line) { if (s->io.log) s->io.log(s->io.ctx, line); }
static void put32(uint8_t *p, uint32_t v) { p[0] = v; p[1] = v >> 8; p[2] = v >> 16; p[3] = v >> 24; }
static uint32_t get32(const uint8_t *p)
{
    return (uint32_t)p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24;
}

static void fault(GbSync *s, GbSyncWhy why, const char *line)
{
    s->state = GB_SYNC_FAULT;
    s->why = why;
    say(s, line);
}

void gb_sync_init(GbSync *s, const GbSyncIo *io, uint8_t delay, uint32_t rom_crc, uint32_t save_crc)
{
    memset(s, 0, sizeof(*s));
    s->io = *io;
    s->delay = delay;
    s->rom_crc = rom_crc;
    s->save_crc = save_crc;
    s->expected = delay;    // frames below `delay` use no buttons
    s->state = GB_SYNC_WAITING;
    if (delay < 1 || delay > GB_SYNC_MAX_DELAY) fault(s, GB_SYNC_WHY_SETTINGS, "sync: REFUSED delay out of range");
}

void gb_sync_stop(GbSync *s)
{
    if ((s->state == GB_SYNC_WAITING || s->state == GB_SYNC_RUNNING) && s->connected && s->io.send) {
        const uint8_t u[3] = {'L', GB_SYNC_UNIT_BYE, 0};
        s->io.send(s->io.ctx, u, sizeof(u));
        s->io.send(s->io.ctx, u, sizeof(u));
    }
    s->state = GB_SYNC_OFF;
}

void gb_sync_accept(GbSync *s)
{
    if (s->state != GB_SYNC_WAITING || !s->partner_ready || s->accepted) return;
    s->accepted = true;
    s->ready_at = s->now;   // the partner hears it with the next poll, not in 200 ms
}

void gb_sync_set_save(GbSync *s, uint32_t save_crc)
{
    if (s->state != GB_SYNC_WAITING) return;
    s->save_crc = save_crc;
    // An acceptance was for the game with the old save. This choice accepts the new one.
    s->accepted = false;
    s->accept_on_match = true;
    s->ready_at = s->now;
}

// The mirror rule: my visible core is the partner's hidden core and the reverse.
static void compare(GbSync *s, uint16_t frame)
{
    const GbSyncDigest *a = &s->own[frame % GB_SYNC_RING], *b = &s->partner[frame % GB_SYNC_RING];
    if (!a->used || !b->used || a->frame != frame || b->frame != frame) return;
    s->compared++;
    if (s->diverged || (a->visible == b->hidden && a->hidden == b->visible)) return;
    s->diverged = true;
    char line[112];
    snprintf(line, sizeof(line), "sync: DIVERGE frame=%u v=%08x h=%08x partner_v=%08x partner_h=%08x",
             (unsigned)frame, (unsigned)a->visible, (unsigned)a->hidden, (unsigned)b->visible, (unsigned)b->hidden);
    say(s, line);
}

static void send_ready(GbSync *s)
{
    size_t title = 0;
    while (title < GB_SYNC_TITLE && s->title[title]) title++;
    uint8_t u[3 + GB_SYNC_READY_LEN + GB_SYNC_TITLE] = {
        'L', GB_SYNC_UNIT_READY, (uint8_t)(GB_SYNC_READY_LEN + title), GB_SYNC_VERSION, s->delay,
        (uint8_t)((s->partner_ready ? 1 : 0) | (s->accepted ? GB_SYNC_READY_ACCEPT : 0))};
    put32(u + 6, s->rom_crc);
    put32(u + 10, s->save_crc);
    u[14] = s->io.channel ? s->io.channel(s->io.ctx) : 0;
    // Bit 7: GBA sample layout; both consoles must agree (the ROM id differs anyway).
    u[15] = s->phase_idx | (s->gba ? 0x80 : 0);
    u[16] = GB_SYNC_LOCAL_CAPS;
    u[17] = (uint8_t)title;
    memcpy(u + 18, s->title, title);
    s->io.send(s->io.ctx, u, 3u + u[2]);
}

static void send_input(GbSync *s)
{
    // newest u16, n u8, buttons[n] (u8 each; u16 each for GBA), digest frame u16, visible u32, hidden u32
    uint8_t u[3 + 3 + 2 * GB_SYNC_HISTORY + 10] = {'L', GB_SYNC_UNIT_INPUT};
    const uint8_t n = s->samples, width = s->gba ? 2 : 1;
    u[2] = 3 + n * width + 10;
    u[3] = s->newest; u[4] = s->newest >> 8; u[5] = n;
    for (uint8_t i = 0; i < n; i++) {
        const uint16_t b = s->buttons[GB_SYNC_HISTORY - n + i];
        u[6 + i * width] = b;
        if (s->gba) u[7 + i * width] = b >> 8;
    }
    const GbSyncDigest *d = &s->own[s->digest_frame % GB_SYNC_RING];
    uint8_t *p = u + 6 + n * width;
    p[0] = d->frame; p[1] = d->frame >> 8;
    put32(p + 2, d->visible);
    put32(p + 6, d->hidden);
    s->io.send(s->io.ctx, u, 3u + u[2]);
    s->sent++;
}

// Move every queued local sample out of the FPGA.
static void drain(GbSync *s)
{
    for (int i = 0; i < GB_SYNC_HISTORY; i++) {
        uint32_t local, visible, hidden;
        if (!rd(s, LINK_SYNC_LOCAL, &local) || !(local >> 31)) return;
        if (!rd(s, LINK_SYNC_DIGEST_V, &visible) || !rd(s, LINK_SYNC_DIGEST_H, &hidden)) return;
        if (!wr(s, LINK_SYNC_POP, 1)) return;
        const uint16_t apply = s->gba ? local >> 10 : local >> 8;
        if (s->any_local && apply != (uint16_t)(s->newest + 1)) {
            fault(s, GB_SYNC_WHY_INTERNAL, "sync: FAULT local frame skipped");
            return;
        }
        memmove(s->buttons, s->buttons + 1, (GB_SYNC_HISTORY - 1) * sizeof(s->buttons[0]));
        s->buttons[GB_SYNC_HISTORY - 1] = s->gba ? local & 0x3ffu : local & 0xffu;
        if (s->samples < GB_SYNC_HISTORY) s->samples++;
        s->newest = apply;
        s->any_local = s->fresh = true;
        s->fresh_at = s->now;
        const uint16_t frame = apply - s->delay;
        GbSyncDigest *d = &s->own[frame % GB_SYNC_RING];
        d->frame = frame; d->used = true; d->visible = visible; d->hidden = hidden;
        s->digest_frame = frame;
        s->frames++;
        compare(s, frame);
        if (s->frames - s->log_frames >= GB_SYNC_LOG_FRAMES) {
            s->log_frames = s->frames;
            uint32_t wait = 0;
            rd(s, LINK_SYNC_WAIT, &wait);
            char line[112];
            snprintf(line, sizeof(line), "sync: frame=%u v=%08x h=%08x wait=%u compared=%u gaps=%u",
                     (unsigned)frame, (unsigned)visible, (unsigned)hidden, (unsigned)wait,
                     (unsigned)s->compared, (unsigned)s->gaps);
            say(s, line);
        }
    }
}

// The cores stand still and the FPGA waits for a partner frame this side has
// already written: the write did not arrive. Write it again.
static void recover(GbSync *s, uint32_t now)
{
    if ((uint32_t)(now - s->fresh_at) < 20 || (uint32_t)(now - s->rewrite_at) < 20) return;
    s->rewrite_at = now;
    uint32_t status;
    if (!rd(s, LINK_SYNC_STATUS, &status) || !(status >> 21 & 1)) return;
    const uint16_t frame = status;
    const int16_t written = (int16_t)(s->expected - frame);
    if (written <= 0 || written > GB_SYNC_RING) return;
    const bool ok = wr(s, LINK_SYNC_PARTNER, (uint32_t)frame << (s->gba ? 10 : 8) | s->partner_buttons[frame % GB_SYNC_RING]);
    if (s->rewrites++ < 20) {
        char line[96];
        snprintf(line, sizeof(line), "sync: REWRITE frame=%u expected=%u ok=%d n=%u",
                 (unsigned)frame, (unsigned)s->expected, (int)ok, (unsigned)s->rewrites);
        say(s, line);
    }
}

void gb_sync_poll(GbSync *s, uint32_t now, bool connected)
{
    s->now = now;
    if (s->state != GB_SYNC_WAITING && s->state != GB_SYNC_RUNNING) return;
    if (connected && !s->connected) {
        // A partner (again): what the last one said no longer counts.
        s->met = true;
        s->heard_at = now;
        if (s->state == GB_SYNC_WAITING) {
            // Another partner, perhaps: the player is asked again.
            s->why = GB_SYNC_WHY_NONE;
            s->partner_ready = s->accepted = s->partner_accepted = false;
            s->partner_title[0] = 0;
        }
    }
    s->connected = connected;
    if (!s->caps_ok) {
        uint32_t caps;
        if (!rd(s, LINK_SYNC_CAPS, &caps)) return;
        if (caps != (s->gba ? GBA_SYNC_CAPS : GB_SYNC_CAPS)) { fault(s, GB_SYNC_WHY_CORE, "sync: REFUSED core has no second core"); return; }
        s->caps_ok = true;
        s->ready_at = now;
    }
    if (!s->quiet && (int32_t)(now - s->state_at) >= 0) {
        s->state_at = now + 1000;
        uint32_t status = 0xffffffffu, wait = 0xffffffffu, cable[3] = {0}, writes = 0, rejects = 0, last = 0;
        rd(s, LINK_SYNC_STATUS, &status);
        rd(s, LINK_SYNC_WAIT, &wait);
        rd(s, LINK_SYNC_SERIAL_V, &cable[0]);
        rd(s, LINK_SYNC_SERIAL_H, &cable[1]);
        rd(s, LINK_SYNC_SERIAL_X, &cable[2]);
        rd(s, LINK_SYNC_WRITES, &writes);
        rd(s, LINK_SYNC_REJECTS, &rejects);
        rd(s, LINK_SYNC_LAST_REJECT, &last);
        char line[256];
        // cable: visible, hidden (done<<16 | master starts), cross (ext done h, v, both master).
        // fpga writes: accepted<<16 | seen; rejects: late<<16 | far; last: module frame<<16 | written.
        snprintf(line, sizeof(line), "sync: cable v=%08x h=%08x x=%08x writes=%08x rejects=%08x last=%08x",
                 (unsigned)cable[0], (unsigned)cable[1], (unsigned)cable[2], (unsigned)writes,
                 (unsigned)rejects, (unsigned)last);
        say(s, line);
        if (s->gba) {
            // Stall counters, as the change since the last line (one second).
            uint32_t d[GBA_SYNC_PROBES];
            for (int i = 0; i < GBA_SYNC_PROBES; i++) {
                uint32_t v = s->probe[i];
                rd(s, LINK_SYNC_PROBE + 4u * (uint32_t)i, &v);
                d[i] = v - s->probe[i];
                s->probe[i] = v;
            }
            snprintf(line, sizeof(line), "sync: perf stall ewram1=%u cart1=%u ewram2=%u cart2=%u sync=%u phase=%u",
                     (unsigned)d[0], (unsigned)d[1], (unsigned)d[2], (unsigned)d[3], (unsigned)d[4],
                     (unsigned)GBA_SYNC_PHASE);
            say(s, line);
        } else {
        // Probes, as the change since the last line (one second).
        uint32_t d[GB_SYNC_PROBES];
        for (int i = 0; i < GB_SYNC_PROBES; i++) {
            uint32_t v = s->probe[i];
            rd(s, LINK_SYNC_PROBE + 4u * (uint32_t)i, &v);
            d[i] = i == 11 ? ((v >> 16) - (s->probe[i] >> 16)) << 16 | ((v - s->probe[i]) & 0xffffu) : v - s->probe[i];
            s->probe[i] = v;
        }
        snprintf(line, sizeof(line),
                 "sync: perf ticks=%u stall v=%u h=%u both=%u sync=%u reads=%u/%u pending=%u/%u hits=%u/%u "
                 "pref=%u/%u cache=%u prefetch=%u phase=%u",
                 (unsigned)d[0], (unsigned)d[1], (unsigned)d[2], (unsigned)d[3], (unsigned)d[4], (unsigned)d[5],
                 (unsigned)d[6], (unsigned)d[7], (unsigned)d[8], (unsigned)d[9], (unsigned)d[10],
                 (unsigned)(d[11] & 0xffffu), (unsigned)(d[11] >> 16), (unsigned)(s->control >> 8 & 1),
                 (unsigned)(s->control >> 9 & 1),
                 (unsigned)(GB_SYNC_PHASE_BASE + s->phase_idx));
        say(s, line);
        }
        snprintf(line, sizeof(line),
                 "sync: state=%d conn=%d frames=%u newest=%u expected=%u gaps=%u werr=%u rx=%u tx=%u "
                 "in=%u/%u ready=%d/%d fpga=%08x wait=%u rewrites=%u",
                 (int)s->state, (int)connected, (unsigned)s->frames, (unsigned)s->newest, (unsigned)s->expected,
                 (unsigned)s->gaps, (unsigned)s->write_errors, (unsigned)s->received, (unsigned)s->sent,
                 (unsigned)s->last_newest, (unsigned)s->last_count, (int)s->partner_ready, (int)s->partner_input,
                 (unsigned)status, (unsigned)wait, (unsigned)s->rewrites);
        say(s, line);
    }
    if (!connected) return;
    // READY repeats until the partner's buttons show that it runs too.
    if (!s->partner_input && (int32_t)(now - s->ready_at) >= 0) {
        send_ready(s);
        s->ready_at = now + GB_SYNC_READY_MS;
    }
    if (s->state == GB_SYNC_WAITING) {
        if (!s->partner_ready) {
            if (s->why != GB_SYNC_WHY_SILENT && (uint32_t)(now - s->heard_at) >= GB_SYNC_SILENT_MS) {
                s->why = GB_SYNC_WHY_SILENT;
                s->partner_title[0] = 0;
                say(s, "sync: the partner is not in a link game");
            }
            return;
        }
        if (!s->accepted || !s->partner_accepted) return;
        const uint32_t player2 = s->io.player2 && s->io.player2(s->io.ctx);
        // Bits 8 and 9: kept ROM words and next-word prefetch. Measured on SMB Deluxe:
        // 84% of full speed without, 99.4% with four kept words, 99.65% with the prefetch too.
        s->control = 3u | (uint32_t)s->delay << 4 | player2 << 7 | (s->gba ? 0u : 0x300u);
        const uint32_t phase = s->gba ? GBA_SYNC_PHASE : GB_SYNC_PHASE_BASE + s->phase_idx;
        if (!wr(s, LINK_SYNC_PHASE, phase) ||
            !wr(s, LINK_SYNC_CONTROL, s->control)) return;
        s->state = GB_SYNC_RUNNING;
        s->why = GB_SYNC_WHY_NONE;
        s->player = (uint8_t)(player2 + 1);
        s->resend_at = s->cache_at = now;
        char go[48];
        snprintf(go, sizeof(go), "sync: GO player=%u phase=%u", (unsigned)player2 + 1, (unsigned)phase);
        say(s, go);
    }
    drain(s);
    if (s->state != GB_SYNC_RUNNING) return;
    recover(s, now);
    if (!s->any_local) return;
    if (s->fresh || (int32_t)(now - s->resend_at) >= 0) {
        send_input(s);
        s->fresh = false;
        s->resend_at = now + GB_SYNC_RESEND_MS;
    }
}

void gb_sync_receive(GbSync *s, const uint8_t *u, size_t n, uint32_t now)
{
    if (s->state != GB_SYNC_WAITING && s->state != GB_SYNC_RUNNING) return;
    if (n < 3 || u[0] != 'L' || n != 3u + u[2]) return;
    if (u[1] == GB_SYNC_UNIT_BYE) {
        if (s->state == GB_SYNC_RUNNING) {
            fault(s, GB_SYNC_WHY_LEFT, "sync: the partner left the game");
        } else {
            s->why = GB_SYNC_WHY_SILENT;
            s->partner_ready = s->partner_accepted = false;
            s->partner_title[0] = 0;
        }
        return;
    }
    if (u[1] == GB_SYNC_UNIT_READY && u[2] >= 13) {
        const uint32_t rom = get32(u + 6), save = get32(u + 10);
        s->heard_at = now;
        // A version 3 READY is 13 bytes long; a longer one than ours only adds what we ignore.
        const bool known = u[3] == GB_SYNC_VERSION && u[2] >= GB_SYNC_READY_LEN && u[2] >= GB_SYNC_READY_LEN + u[17];
        const GbSyncWhy why =
            !known ? GB_SYNC_WHY_FIRMWARE :
            (u[15] >> 7) != (s->gba ? 1 : 0) ? GB_SYNC_WHY_SYSTEM :
            rom != s->rom_crc ? GB_SYNC_WHY_GAME :
            save != s->save_crc ? GB_SYNC_WHY_SAVE :
            u[4] != s->delay ? GB_SYNC_WHY_SETTINGS : GB_SYNC_WHY_NONE;
        if (known) {
            // What the partner plays, also when it is another game. Printable ASCII only.
            const size_t len = u[17] < GB_SYNC_TITLE ? u[17] : GB_SYNC_TITLE;
            for (size_t i = 0; i < len; i++) s->partner_title[i] = u[18 + i] >= 0x20 && u[18 + i] < 0x7f ? (char)u[18 + i] : '?';
            s->partner_title[len] = 0;
        }
        if (why != GB_SYNC_WHY_NONE) {
            if (why != s->why || s->state == GB_SYNC_RUNNING) {
                char line[112];
                snprintf(line, sizeof(line), "sync: REFUSED version=%u/%u delay=%u/%u rom=%08x/%08x save=%08x/%08x",
                         GB_SYNC_VERSION, u[3], s->delay, u[4], (unsigned)s->rom_crc, (unsigned)rom,
                         (unsigned)s->save_crc, (unsigned)save);
                say(s, line);
            }
            // While waiting the partner may still pick the matching game; a running game is over.
            if (s->state == GB_SYNC_RUNNING) s->state = GB_SYNC_FAULT;
            s->why = why;
            s->partner_ready = s->partner_accepted = false;
            if (why != GB_SYNC_WHY_SAVE) s->accept_on_match = false;
            return;
        }
        if (s->state == GB_SYNC_WAITING) s->why = GB_SYNC_WHY_NONE;
        s->partner_accepted = (u[5] & GB_SYNC_READY_ACCEPT) != 0;
        s->partner_caps = u[16];
        // One channel for both: player 2 moves to player 1's.
        if (u[14] && s->io.channel && s->io.set_channel && s->io.player2 && s->io.player2(s->io.ctx) &&
            s->io.channel(s->io.ctx) != u[14]) {
            char line[64];
            snprintf(line, sizeof(line), "sync: channel %u -> %u", (unsigned)s->io.channel(s->io.ctx), (unsigned)u[14]);
            say(s, line);
            s->io.set_channel(s->io.ctx, u[14]);
        }
        // One start phase for both: player 1's.
        if (s->io.player2 && s->io.player2(s->io.ctx)) s->phase_idx = u[15] & 3;
        s->partner_ready = true;
        if (s->accept_on_match && s->state == GB_SYNC_WAITING && !s->accepted) {
            s->accepted = true;
            s->ready_at = now;
        }
        return;
    }
    if (u[1] != GB_SYNC_UNIT_INPUT || !s->partner_ready || u[2] < 3 + 10) return;
    const uint16_t newest = u[3] | u[4] << 8;
    const uint8_t count = u[5], width = s->gba ? 2 : 1;
    if (count < 1 || count > GB_SYNC_HISTORY || u[2] != 3 + count * width + 10) return;
    s->partner_input = true;
    s->received++;
    s->last_newest = newest;
    s->last_count = count;
    // Only the next frames in order; a hole waits for the partner's resend.
    for (uint8_t i = 0; i < count; i++) {
        const uint16_t apply = newest - (count - 1 - i);
        const int16_t ahead = (int16_t)(apply - s->expected);
        if (ahead < 0) continue;
        if (ahead > 0) { s->gaps++; break; }
        const uint16_t b = s->gba ? (uint16_t)(u[6 + 2 * i] | (u[7 + 2 * i] & 3) << 8) : u[6 + i];
        if (!wr(s, LINK_SYNC_PARTNER, (uint32_t)apply << (s->gba ? 10 : 8) | b)) { s->write_errors++; break; }
        s->partner_buttons[apply % GB_SYNC_RING] = b;
        s->expected++;
    }
    const uint8_t *p = u + 6 + count * width;
    const uint16_t frame = p[0] | p[1] << 8;
    GbSyncDigest *d = &s->partner[frame % GB_SYNC_RING];
    d->frame = frame; d->used = true; d->visible = get32(p + 2); d->hidden = get32(p + 6);
    compare(s, frame);
}

GbSyncPhase gb_sync_phase(const GbSync *s)
{
    if (s->state == GB_SYNC_OFF) return GB_SYNC_PHASE_OFF;
    if (s->state == GB_SYNC_FAULT) return GB_SYNC_PHASE_FAILED;
    if (s->diverged) return GB_SYNC_PHASE_DIVERGED;
    if (!s->connected) return s->met ? GB_SYNC_PHASE_LOST : GB_SYNC_PHASE_SEARCHING;
    if (s->state == GB_SYNC_RUNNING) return GB_SYNC_PHASE_RUNNING;
    if (s->why != GB_SYNC_WHY_NONE) return GB_SYNC_PHASE_REFUSED;
    if (!s->partner_ready) return GB_SYNC_PHASE_WAITING;
    return s->accepted ? GB_SYNC_PHASE_ACCEPTED : GB_SYNC_PHASE_FOUND;
}
