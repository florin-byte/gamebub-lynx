// SPDX-License-Identifier: GPL-3.0-only
// ABI 2 adapter. No OS calls: host tests run this exact event/command path.
#include "gamebub_cwl_logic.h"

enum { CONFIG=0x44, STATUS=0x48, EVENT=0x4c, POP=0x50,
       COMMAND=0x54, CONTROL=0x58 };
void gb_cwl_trace(GbCwlLogic *s, char kind, uint32_t value)
{
    if (!s->trace_enabled) return;
    unsigned i = s->trace_head;
    uint32_t us = s->io.clock_us ? s->io.clock_us(s->io.ctx) : 0;
    if (kind == 'E' && ((value >> 8) & 7) == 3) {
        for (unsigned j = 0; j < 6; j++) s->hops_us[j] = 0;
        // A notification covers a FIFO batch, not each transfer. Consume it
        // once; keep the event-to-command sample even when it is unknown.
        s->hops_us[0] = s->irq_us;
        s->irq_us = 0;
        s->hops_us[1] = us;
        s->timing_master = true;
    }
    if (s->timing_master) {
        if (kind == 'T' && !s->hops_us[2]) s->hops_us[2] = us;
        if (kind == 'R') s->hops_us[3] = s->received_us;
        if (kind == 'V') s->hops_us[4] = us;
        if (kind == 'C' && (((value >> 8) & 255) == 1 || ((value >> 8) & 255) == 4)) {
            s->hops_us[5] = us;
            bool valid = true;
            if (s->hops_us[0] && (int32_t)(s->hops_us[1] - s->hops_us[0]) < 0)
                s->hops_us[0] = 0;
            for (unsigned j = 1; j < 5; j++)
                if (!s->hops_us[j] || (int32_t)(s->hops_us[j+1] - s->hops_us[j]) < 0)
                    valid = false;
            if (valid) {
                unsigned row = s->latency_n++ % 64;
                for (unsigned j = 0; j < 6; j++) s->latency[row][j] = s->hops_us[j];
                for (unsigned j = 0; j < 5; j++) {
                    if (j == 0 && !s->hops_us[0]) continue;
                    uint32_t d = s->hops_us[j+1] - s->hops_us[j];
                    if (d > s->hop_max[j]) s->hop_max[j] = d;
                }
            }
            s->timing_master = false;
        }
        if (kind == 'S' || kind == 'X' || kind == 'I') s->timing_master = false;
    }
    // Count consecutive identical packet retries without evicting the state
    // transitions that preceded them. Timing above still sees every packet.
    if ((kind == 'R' || kind == 'T') && s->trace_count) {
        unsigned last = (i + GB_CWL_TRACE_CAPACITY - 1) % GB_CWL_TRACE_CAPACITY;
        if (s->trace[last].kind == kind && s->trace[last].value == value) {
            s->trace[last].repeats++;
            s->trace[last].last_us = us;
            return;
        }
    }
    s->trace[i].at = s->now;
    s->trace[i].at_us = us;
    s->trace[i].kind = kind;
    s->trace[i].value = value;
    s->trace[i].last_us = us;
    s->trace[i].repeats = 0;
    s->trace_head = (i + 1) % GB_CWL_TRACE_CAPACITY;
    if (s->trace_count < GB_CWL_TRACE_CAPACITY) s->trace_count++;
}
static bool read_reg(GbCwlLogic *s, uint32_t a, uint32_t *v)
{
    return s->io.read && s->io.read(s->io.ctx, a, v);
}
static bool write_reg(GbCwlLogic *s, uint32_t a, uint32_t v)
{
    return s->io.write && s->io.write(s->io.ctx, a, v);
}
static void command(GbCwlLogic *s, unsigned op, uint8_t byte)
{
    gb_cwl_trace(s, 'V', (op << 8) | byte);
    uint32_t status;
    if (!s->bridge_on || s->bridge_fault) return;
    if (!read_reg(s, STATUS, &status) || (status & 512) ||
        (uint8_t)(status >> 16) != s->generation) { s->bridge_fault = true; return; }
    s->bridge_status = status;
    if ((op == 1 || op == 4) && (uint8_t)(status >> 24) != s->transfer_id) {
        gb_cwl_trace(s, 'S', ((uint32_t)s->transfer_id << 24) | (op << 8) | byte);
        return;
    }
    // Hardware repeats this check on the command edge (guard/read/write race).
    if (!write_reg(s, COMMAND, ((uint32_t)s->transfer_id << 24) | (op << 8) | byte))
        s->bridge_fault = true;
    else gb_cwl_trace(s, 'C', ((uint32_t)s->transfer_id << 24) | (op << 8) | byte);
}
static void first_byte(GbCwlLogic *s, uint8_t tx, uint8_t rx)
{
    if (s->first_n == 16) return;
    s->first_tx[s->first_n] = tx;
    s->first_rx[s->first_n++] = rx;
}
void gb_cwl_bridge_reply(void *ctx, uint8_t byte)
{
    GbCwlLogic *s = ctx;
    if (s->bridge_on) {
        uint32_t rtt = s->now - s->master_at;
        first_byte(s, s->master_byte, byte);
        s->xmaster++;
        s->rtt[s->rtt_n++ % 256] = rtt;
        if (rtt > s->rtt_max) s->rtt_max = rtt;
    }
    command(s, 1, byte);
}
void gb_cwl_bridge_drop(void *ctx)
{
    GbCwlLogic *s = ctx;
    s->xdrop++;
    command(s, 4, 0);
}
void gb_cwl_bridge_offer(void *ctx, uint8_t byte)
{
    GbCwlLogic *s = ctx;
    s->slave_byte = byte;
    command(s, 2, byte);
}
void gb_cwl_bridge_withdraw(void *ctx) { command(ctx, 6, 0); }
void gb_cwl_bridge_stop(GbCwlLogic *s)
{
    if (s->caps == GBA_CWL_CAPS) { gba_cwl_stop(s); return; }
    // ABI 2 CONTROL atomically completes a normal held master (FF + IRQ),
    // or silently releases an abandoned master, before clearing the channel.
    s->first_n = s->first_reported = 0;
    gb_cwl_trace(s, 'X', s->bridge_status);
    s->timing_master = false;
    s->bridge_on = false; // Suppress every callback before invalidating state.
    write_reg(s, CONTROL, 1);
    CwlHost_t host = s->core.host;
    uint16_t session = s->core.session + 1;
    uint8_t seq = s->core.m_seq + 1;
    Cwl_Init(&s->core, &host, s->now);
    Cwl_SetSession(&s->core, session ? session : 1);
    s->core.m_seq = seq;
    s->core.master_timeout_ms = 1500;
    Cwl_SetSendAhead(&s->core, false, false);
    Cwl_SetStandingOffers(&s->core, true);
    s->bridge_fault = false;
}
void gb_cwl_bridge_event(GbCwlLogic *s, uint32_t event)
{
    uint8_t byte = event;
    switch ((event >> 8) & 7) {
    case 1: Cwl_OnLocalArmed(&s->core, s->now); break;
    case 2: Cwl_OnLocalDisarm(&s->core, s->now); break;
    case 3:
        // A new FPGA id supersedes any request still awaiting a CWL verdict.
        s->core.m_state = kCwlM_Idle;
        s->core.m_abandoned = false;
        s->transfer_id = event >> 24;
        s->master_byte = byte;
        s->master_at = s->now;
        Cwl_OnLocalMaster(&s->core, byte, s->now); break;
    case 4:
        if (s->core.offer_in_flight) first_byte(s, byte, s->slave_byte);
        s->xslave++;
        Cwl_OnLocalSlaveResult(&s->core, true, byte, s->now); break;
    case 5: Cwl_OnLocalSlaveResult(&s->core, false, 0, s->now); break;
    case 6: Cwl_OnLocalMasterAbandonB(&s->core, byte, s->now); break;
    case 7:
        // With standing offers this is a collision hint, not a verdict.
        if (!s->core.standing) Cwl_OnLocalSlaveResult(&s->core, false, 0, s->now);
        Cwl_OnLocalMasterCollision(&s->core, s->now); break;
    default: s->bridge_fault = true; break;
    }
}
const char *gb_cwl_bridge_config_reason(const GbCwlLogic *s, uint32_t now)
{
    if (!s->core_running) return "blocked: core stopped";
    if (!s->caps_read) return "blocked: caps read failed";
    if (s->caps == GBA_CWL_CAPS) {
        if (!gb_cwl_logic_connected(s, now)) return "blocked: partner unavailable";
        return s->gba.on ? "GBA written" : "GBA awaiting config write";
    }
    if (s->caps != 0x574c021f) return "blocked: incompatible caps";
    if (!gb_cwl_logic_connected(s, now)) return "blocked: partner unavailable";
    if (s->bridge_fault) return "blocked: bridge I/O fault";
    return s->bridge_on ? "written" : "blocked: awaiting config write";
}
void gb_cwl_bridge_poll(GbCwlLogic *s, uint32_t now)
{
    if (s->caps == GBA_CWL_CAPS || s->gba.on) { gba_cwl_poll(s, now); return; }
    s->now = now;
    uint32_t status;
    bool available = s->core_running && s->caps_read && s->caps == 0x574c021f;
    bool wanted = available && gb_cwl_logic_connected(s, now);
    if (s->bridge_fault || (s->bridge_on && !available)) {
        s->xdrop++;
        gb_cwl_bridge_stop(s);
        return;
    }
    if (!wanted && !s->bridge_on) return;
    if (!s->bridge_on) {
        // Enable toggles generation and clears FIFO; sample only afterwards.
        if (!write_reg(s, CONTROL, 1) || !write_reg(s, CONFIG, 13) ||
            !read_reg(s, STATUS, &status)) { s->bridge_fault = true; return; }
        s->generation = status >> 16;
        Cwl_SetStandingOffers(&s->core, true);
        s->bridge_on = true;
    }
    // Re-read STATUS even after the last POP: IRQ is edge triggered. Bound
    // work per pass, but the task rechecks before sleeping and every 5 ms.
    for (unsigned n = 0; n < 64; n++) {
        if (!read_reg(s, STATUS, &status) || (status & 512) ||
            (uint8_t)(status >> 16) != s->generation) {
            s->xdrop++;
            gb_cwl_bridge_stop(s);
            return;
        }
        s->bridge_status = status;
        if (!(status & 31)) {
            if ((uint8_t)(status >> 24) != s->transfer_id) {
                gb_cwl_trace(s, 'I', status);
                // Guard ended the held master. Late ACK/CANCELLED is now inert.
                s->core.m_state = kCwlM_Idle;
                s->core.m_abandoned = false;
                s->transfer_id = status >> 24;
            }
            // Let CWL decide normal (FF + IRQ) versus abandoned (silent drop)
            // while callbacks can still reach the FPGA, then stop the channel.
            Cwl_Poll(&s->core, now);
            if (!wanted && s->core.m_state == kCwlM_Idle) gb_cwl_bridge_stop(s);
            return;
        }
        uint32_t event;
        if (!(s->io.event_pop ? s->io.event_pop(s->io.ctx, &event) :
              (read_reg(s, EVENT, &event) && write_reg(s, POP, 1)))) {
            s->bridge_fault = true; return;
        }
        gb_cwl_trace(s, 'E', event);
        // A reset between EVENT and POP must not acknowledge a stale slave byte.
        if (!read_reg(s, STATUS, &status) || (status & 512) ||
            (uint8_t)(status >> 16) != s->generation) {
            s->xdrop++;
            gb_cwl_bridge_stop(s);
            return;
        }
        unsigned op = (event >> 8) & 7;
        if ((op == 3 || op == 6) && (uint8_t)(event >> 24) != (uint8_t)(status >> 24)) {
            gb_cwl_trace(s, 'S', event);
            continue; // Superseded by guard/new master while queued.
        }
        gb_cwl_bridge_event(s, event);
        if (s->bridge_fault) { gb_cwl_bridge_stop(s); return; }
    }
}
