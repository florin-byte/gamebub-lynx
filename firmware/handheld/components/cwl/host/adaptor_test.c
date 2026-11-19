// SPDX-License-Identifier: GPL-3.0-only
// Deterministic two-endpoint radio simulator using production session policy.
#include "gamebub_cwl_logic.h"
#include "gamebub_cwl.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
typedef struct { GbCwlLogic s; int id; uint8_t mac[6]; } Endpoint;
typedef struct { uint32_t due; int to, from; bool unicast; size_t n; uint8_t data[48]; } Message;
static Endpoint ep[2];
static Message queue[2048];
static int count;
static uint32_t now, random_state;
static bool impair;
static uint32_t rng(void) { random_state = random_state*1664525u+1013904223u; return random_state; }
static void send_frame(void *ctx, const uint8_t *dest, const uint8_t *data, size_t n)
{
    Endpoint *e = ctx;
    int to = 1-e->id;
    if (e->s.channel != ep[to].s.channel) return;
    bool unicast = !CwlLx_MacIsBroadcast(dest);
    if (unicast && memcmp(dest,ep[to].mac,6)) return;
    if (impair && rng()%100 < 20) return;
    int copies = impair && rng()%100 < 20 ? 2 : 1;
    while (copies--) {
        assert(count < 2048);
        Message *m = &queue[count++];
        *m = (Message){.due=now+1+(impair?rng()%20:0),.from=e->id,.to=to,.unicast=unicast,.n=n};
        memcpy(m->data,data,n);
    }
}
static void channel(void *ctx, uint8_t ch) { (void)ctx; assert(ch >= 1 && ch <= 13); }
static void init(int i, uint16_t session)
{
    ep[i].id=i;
    ep[i].mac[0]=2; ep[i].mac[5]=i+1;
    GbCwlIo io = {.ctx=&ep[i],.send=send_frame,.channel=channel};
    gb_cwl_logic_init(&ep[i].s,&io,ep[i].mac,session,now);
}
static void tick(uint32_t duration)
{
    uint32_t start=now;
    while ((uint32_t)(now-start) < duration) {
        for (int i=0;i<count;) {
            if ((int32_t)(now-queue[i].due)>=0) {
                Message m=queue[i]; queue[i]=queue[--count];
                if(ep[m.from].s.channel==ep[m.to].s.channel)
                    gb_cwl_logic_receive(&ep[m.to].s,ep[m.from].mac,m.unicast,-40,m.data,m.n,now);
            } else i++;
        }
        for(int i=0;i<2;i++) gb_cwl_logic_poll(&ep[i].s,now);
        now++;
    }
}
static void paired(void)
{
    for(int i=0;i<2;i++) {
        assert(gb_cwl_logic_connected(&ep[i].s,now));
        assert(!ep[i].s.peer.fixed);
        assert(!strcmp(gb_cwl_logic_partner(&ep[i].s), i ? "GAMEBUB 0001" : "GAMEBUB 0002"));
        assert(ep[i].s.pongs>0);
    }
}
int main(void)
{
    assert(gamebub_cwl_page_state(GB_CWL_OFF, 0, 0) == 0);
    assert(gamebub_cwl_page_state(GB_CWL_SEARCHING, 0, 0) == 1);
    assert(gamebub_cwl_page_state(GB_CWL_CONNECTED, 0, 0) == 2);
    assert(gamebub_cwl_page_state(GB_CWL_SEARCHING, 1, 0) == 3);
    assert(gamebub_cwl_page_state(GB_CWL_OFF, 1, 1) == 4);
    puts("adaptor: page states PASS");
    random_state=42; now=1000; init(0,1); init(1,2);
    // NAME alone must never claim a live protocol partner.
    CwlLxBatch_t b; CwlLx_BatchInit(&b,48); CwlLx_BatchAddName(&b,"NAME ONLY");
    gb_cwl_logic_receive(&ep[0].s,ep[1].mac,false,-40,b.buf,b.len,now);
    assert(!gb_cwl_logic_connected(&ep[0].s,now) && !ep[0].s.peer.locked);
    puts("adaptor: NAME-only is not connected PASS");
    tick(4000); paired(); puts("adaptor: two endpoints pair, NAME and ping PASS");
    uint8_t paired_channel = ep[0].s.channel;
    tick(10000); paired();
    assert(ep[0].s.channel == paired_channel && ep[1].s.channel == paired_channel);
    puts("adaptor: fresh partner stops sweep PASS");
    impair=true; tick(600000); paired();
    assert(ep[0].s.pongs>256 && ep[1].s.pongs>256);
    puts("adaptor: 20% loss, duplicates, jitter and ping sequence wrap PASS");
    init(1,99); tick(8000); paired();
    assert(ep[0].s.core.stats.peer_resets>0);
    puts("adaptor: peer restart and new HELLO session PASS");
    // Lose all traffic without polling the peer; freshness expires in 2 s.
    now+=2100;
    assert(!gb_cwl_logic_connected(&ep[0].s,now));
    assert(!ep[0].s.peer.fixed && ep[0].s.peer.locked);
    puts("adaptor: lost peer retains temporary lock before expiry PASS");
    count=0; now=0xfffff800; init(0,101); init(1,102); impair=false;
    tick(6000); paired(); puts("adaptor: millisecond clock wrap PASS");
    count=0; now=1000; init(0,1);
    uint32_t start=now;
    // Poll only endpoint zero to inspect the exact discovery cadence.
    gb_cwl_logic_poll(&ep[0].s,start+1999); assert(ep[0].s.channel==1);
    gb_cwl_logic_poll(&ep[0].s,start+2000); assert(ep[0].s.channel==6);
    gb_cwl_logic_poll(&ep[0].s,start+2119); assert(ep[0].s.channel==6);
    gb_cwl_logic_poll(&ep[0].s,start+2120); assert(ep[0].s.channel==11);
    puts("adaptor: home 2 s and sweep 120 ms PASS");
    // A fresh accepted peer must hold the channel even before protocol liveness.
    ep[0].s.peer.locked = true;
    ep[0].s.peer.confirmed = true;
    ep[0].s.peer.fixed = true;
    ep[0].s.peer.last_ms = start + 2240;
    ep[0].s.core.peer_seen = false;
    gb_cwl_logic_poll(&ep[0].s, start + 2240);
    assert(ep[0].s.channel == 11);
    gb_cwl_logic_poll(&ep[0].s, start + 2240 + kCwlLx_PeerLost_ms);
    assert(ep[0].s.channel != 11);
    puts("adaptor: fresh peer holds channel until freshness expires PASS");
    puts("adaptor_test: ALL PASS");
    return 0;
}
