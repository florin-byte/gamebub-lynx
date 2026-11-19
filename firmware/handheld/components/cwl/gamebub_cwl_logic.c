// SPDX-License-Identifier: GPL-3.0-only
#include "gamebub_cwl_logic.h"
#include "gamebub_cwl.h"
#include <stdio.h>
#include <string.h>

static void frame(GbCwlLogic *s, const CwlLxBatch_t *b, bool broadcast)
{
    uint8_t dest[6];
    CwlLx_PeerDest(&s->peer, dest);
    if (broadcast) memset(dest, 0xff, 6);
    s->io.send(s->io.ctx, dest, b->buf, b->len);
    s->tx++;
}
static void send_cwl(void *ctx, const uint8_t *pkt)
{
    GbCwlLogic *s = ctx;
    CwlLxBatch_t b;
    CwlLx_BatchInit(&b, kCwlLx_MaxFrame);
    CwlLx_BatchAddCwl(&b, pkt);
    frame(s, &b, false);
    if (pkt[3] != kCwlType_Hello && pkt[3] != kCwlType_HelloProbe)
        gb_cwl_trace(s, 'T', ((uint32_t)pkt[3] << 16) | (pkt[4] << 8) | pkt[5]);
}
static void beacon(GbCwlLogic *s, bool probe)
{
    CwlLxBatch_t b;
    CwlLx_BatchInit(&b, kCwlLx_MaxFrame);
    if (probe) {
        const uint8_t p[] = {'C', 'W', kCwlVersion, kCwlType_HelloProbe,
                            s->core.session >> 8, s->core.session & 255};
        CwlLx_BatchAddCwl(&b, p);
    }
    CwlLx_BatchAddName(&b, s->name);
    frame(s, &b, true);
}
void gb_cwl_logic_init(GbCwlLogic *s, const GbCwlIo *io, const uint8_t mac[6], uint16_t session, uint32_t now)
{
    memset(s, 0, sizeof(*s));
    s->io = *io;
    s->now = now;
    s->channel = 1;
    s->sweep_at = now + 2000;
    s->name_at = s->ping_at = now;
    snprintf(s->name, sizeof(s->name), "GAMEBUB %02X%02X", mac[4], mac[5]);
    CwlHost_t host = {.ctx = s, .send = send_cwl, .master_reply = gb_cwl_bridge_reply,
        .master_dropped = gb_cwl_bridge_drop, .slave_offer = gb_cwl_bridge_offer,
        .slave_withdraw = gb_cwl_bridge_withdraw};
    Cwl_Init(&s->core, &host, now);
    Cwl_SetSession(&s->core, session ? session : 1);
    s->core.master_timeout_ms = 1500;
    Cwl_SetSendAhead(&s->core, false, false);
    CwlLx_PeerInit(&s->peer, NULL);
}
typedef struct { GbCwlLogic *s; const uint8_t *src; } RxContext;
static void on_name(void *ctx, const char *name, size_t n)
{
    RxContext *r = ctx;
    CwlLx_CandName(&r->s->candidates, r->src, name, n);
}
static void on_cwl(void *ctx, const uint8_t *pkt)
{
    RxContext *r = ctx;
    GbCwlLogic *s = r->s;
    bool hello = pkt[3] == kCwlType_Hello || pkt[3] == kCwlType_HelloProbe;
    uint16_t session = (uint16_t)(pkt[4] << 8 | pkt[5]);
    if (hello && s->bridge_on && s->core.peer_session_known && s->core.peer_session != session) {
        uint16_t local_session = s->core.session;
        gb_cwl_bridge_stop(s);
        // A peer restart must not trigger alternating local HELLO restarts.
        Cwl_SetSession(&s->core, local_session);
    }
    // Discovery remains live with the game port disabled. Game traffic does not.
    if (hello || s->bridge_on) {
        if (!hello)
            gb_cwl_trace(s, 'R', ((uint32_t)pkt[3] << 16) | (pkt[4] << 8) | pkt[5]);
        Cwl_OnPacket(&s->core, pkt, kCwlPacketLen, s->now);
    }
}
static void on_ping(void *ctx, bool pong, uint8_t id, uint32_t t)
{
    GbCwlLogic *s = ((RxContext *)ctx)->s;
    if (pong) {
        if (s->ping_pending && id == s->ping_id && t == s->ping_time) {
            s->rtt_ms = s->now - t;
            s->pongs++;
            s->ping_pending = false;
        }
    } else {
        CwlLxBatch_t b;
        CwlLx_BatchInit(&b, kCwlLx_MaxFrame);
        CwlLx_BatchAddPing(&b, true, id, t);
        frame(s, &b, false);
    }
}
void gb_cwl_logic_receive(GbCwlLogic *s, const uint8_t src[6], bool unicast,
                          int8_t rssi, const uint8_t *data, size_t len, uint32_t now)
{
    s->now = now;
    s->rx++;
    RxContext r = {s, src};
    CwlLxSink_t sink = {.ctx = &r};
    int n = CwlLx_Split(data, len, &sink);
    const bool gba_frame = len >= 2 && data[0] == 'L' && data[1] == 0x10;
    if (n < 0) {
        s->bad++;
        if (gba_frame) s->gba.trace.reject[GBA_RX_PARSE]++;
        return;
    }
    CwlLx_CandSeen(&s->candidates, src, rssi, now);
    sink.name = on_name;
    CwlLx_Split(data, len, &sink);
    if (n > 0) {
        if (!CwlLx_PeerAccept(&s->peer, src, unicast, now)) return;
        // Any keeps an expiring discovery lock; only user selection fixes it.
    } else if (!s->peer.locked || memcmp(s->peer.mac, src, 6)) {
        if (gba_frame) s->gba.trace.reject[GBA_RX_ROUTE]++;
        return;
    }
    sink.cwl = on_cwl;
    sink.ping = on_ping;
    sink.name = NULL;
    CwlLx_Split(data, len, &sink);
    // The splitter validated the whole frame before any extension is delivered.
    if (unicast && gb_cwl_logic_connected(s, now)) {
        for (size_t at=0; at<len;) {
            size_t unit=data[at]=='C' ? 6 : 3+(size_t)data[at+2];
            if (data[at]=='L' && data[at+1]==0x10) gba_cwl_receive(s,data+at,unit);
            at+=unit;
        }
    } else if (gba_frame) {
        s->gba.trace.reject[GBA_RX_ROUTE]++;
    }
}
bool gb_cwl_logic_connected(const GbCwlLogic *s, uint32_t now)
{
    return s->peer.confirmed && Cwl_PeerAlive(&s->core, now);
}
const char *gb_cwl_logic_partner(const GbCwlLogic *s)
{
    if (s->peer.locked) {
        for (int i = 0; i < kCwlLx_MaxCand; ++i)
            if (s->candidates.c[i].used && !memcmp(s->candidates.c[i].mac, s->peer.mac, 6))
                return s->candidates.c[i].name;
    }
    return "";
}
void gb_cwl_logic_poll(GbCwlLogic *s, uint32_t now)
{
    s->now = now;
    CwlLx_PeerPoll(&s->peer, now);
    Cwl_Poll(&s->core, now);
    if ((int32_t)(now - s->name_at) >= 0) {
        beacon(s, false);
        s->name_at = now + (s->peer.confirmed ? 5000 : 1000);
    }
    if (gb_cwl_logic_connected(s, now) && (int32_t)(now - s->ping_at) >= 0) {
        CwlLxBatch_t b;
        CwlLx_BatchInit(&b, kCwlLx_MaxFrame);
        s->ping_id++;
        s->ping_time = now;
        s->ping_pending = true;
        CwlLx_BatchAddPing(&b, false, s->ping_id, now);
        frame(s, &b, false);
        s->ping_at = now + 1000;
    }
    if ((s->peer.locked && (uint32_t)(now - s->peer.last_ms) < kCwlLx_PeerLost_ms) || (int32_t)(now - s->sweep_at) < 0) return;
    static const uint8_t order[] = {1, 6, 11, 2, 3, 4, 5, 7, 8, 9, 10, 12, 13};
    if (s->sweep_idx >= sizeof(order)) {
        s->sweep_idx = 0;
        s->channel = 1;
        s->sweep_at = now + 2000;
    } else {
        if (order[s->sweep_idx] == 1) s->sweep_idx++;
        s->channel = order[s->sweep_idx++];
        s->sweep_at = now + 120;
    }
    s->io.channel(s->io.ctx, s->channel);
    beacon(s, true);
}

uint32_t gamebub_cwl_page_state(uint32_t state, uint32_t connected_before, uint32_t no_memory)
{
    if (state == GB_CWL_OFF) return no_memory ? 4 : 0;
    if (state == GB_CWL_CONNECTED) return 2;
    return connected_before ? 3 : 1;
}
