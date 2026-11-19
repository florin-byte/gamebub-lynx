// SPDX-License-Identifier: GPL-3.0-only
// Two production Game Bub session/word adapters over a deterministic radio.
#include "gamebub_cwl_logic.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
typedef struct {
    GbCwlLogic s;
    uint8_t mac[6], gen;
    bool enabled, busy, master, overflow;
    bool multi_capable, multi_mode, multi_started;
    bool sync_capable, sync_pending, grant_capable;
    uint32_t anchor, cmd_anchor, snapshot_meta, snapshot_cycles, grant_limit;
    unsigned grants;
    bool anchor_valid;
    unsigned capture_at;
    uint32_t cycles, cmd_cycles, capture_word;
    unsigned config;
    uint32_t id, word, received, cmd_data, cmd_id;
    uint32_t meta[16], data[16], event_cycles[16];
    unsigned head,count,completed,commands;
    unsigned baud; // SIOCNT baud reported in a multi START (data[17:16])
    bool pair_capable;          // GB-117 bitstream: SAMPLED + CONFIG pair bit
    uint32_t sampled;           // SEND sampled at START / child BEGIN
    bool done_pending; unsigned done_at; // child DONE after the emulated wire time
} End;
typedef struct { unsigned to, due, n; bool unicast; uint8_t data[48]; } Packet;
static End ends[2];
static Packet packets[4096];
static unsigned pending,now,seed=13;
static bool loss,offline,drop_ack;
// GB-116: like Mario Kart, the child arms its next transfer right after DONE.
static bool rearm_child;
static unsigned sync_capture_ms=40; // mock FPGA wait for a synced child sample
static unsigned wire_ms;            // GB-117: unsynced child DONE this long after COMPLETE
static unsigned rnd(void) { seed=seed*1664525+1013904223; return seed; }
static void event(End *e,unsigned op)
{
    assert(e->count<16);
    unsigned at=(e->head+e->count++)%16;
    e->meta[at]=(op<<30)|(e->master?1u<<29:0)|
                (e->multi_mode?1u<<28:0)|e->id;
    // Like the RTL: a multi START carries the 16-bit SEND and the baud above it.
    e->data[at]=(op==GBA_CWL_START && e->multi_mode) ? (e->baud&3)<<16|(e->word&0xffffu) : e->word;
    e->event_cycles[at]=e->cycles;
    e->s.irq_us=now*1000u; // GB-115: the FPGA IRQ time, as the task records it (now>0 here)
}
static void rearm(End *e)
{
    if (!rearm_child || e->master || !e->multi_mode) return;
    e->id++; e->sampled=e->word; e->busy=true; event(e,GBA_CWL_START); // DONE and START in the same poll
}
static bool read_reg(void *ctx,uint32_t a,uint32_t *v)
{
    End *e=ctx;
    switch(a) {
    case 0x64: *v=e->config|(e->multi_capable?1u<<31:0)|(e->sync_capable?1u<<30:0)|(e->grant_capable?1u<<29:0)|
                     (e->pair_capable?1u<<28:0); break;
    case 0x9c: if (!e->pair_capable) return false; *v=e->sampled; break;
    case 0x68: *v=(e->gen<<16)|(e->multi_mode?1u<<11:0)|(e->overflow?512:0)|e->count; break;
    case 0x6c: *v=e->count?e->meta[e->head]:0; break;
    case 0x70: *v=e->count?e->data[e->head]:0; break;
    case 0x90: *v=e->snapshot_meta; break;
    case 0x94: *v=e->snapshot_cycles; break;
    case 0x88: *v=e->count?e->event_cycles[e->head]:0; break;
    default: return false;
    }
    return true;
}
static bool write_reg(void *ctx,uint32_t a,uint32_t v)
{
    End *e=ctx;
    switch(a) {
    case 0x84:
        if(e->busy) { e->busy=false; e->received=UINT32_MAX; e->completed++; }
        e->enabled=false; e->sync_pending=false; e->anchor_valid=false; e->grant_limit=0; e->head=e->count=0; e->gen++; break;
    case 0x64:
        if (e->enabled!=(bool)(v&1)) { e->head=e->count=0; e->gen++; }
        e->enabled=v&1; e->config=v; break;
    case 0x74: assert(e->count); e->head=(e->head+1)%16; e->count--; break;
    case 0x78: e->cmd_data=v; break;
    case 0x7c: e->cmd_id=v; break;
    case 0x8c: e->cmd_cycles=v; break;
    case 0x90:
        e->snapshot_meta=(e->anchor_valid?0x80000000u:0) |
            (e->anchor_valid && !e->busy && (e->config&4)?0x40000000u:0) | e->anchor;
        e->snapshot_cycles=e->cycles; break;
    case 0x98: e->cmd_anchor=v; break;
    case 0x80:
        if (v==17) {
            if (e->anchor_valid && e->cmd_anchor==e->anchor && e->cmd_cycles>e->grant_limit) {
                e->grant_limit=e->cmd_cycles; e->grants++;
            }
            break;
        }
        e->commands++;
        if(e->enabled && e->busy && e->cmd_id==e->id) {
            if (v==9) {
                assert(e->sync_capable && e->multi_mode && !e->master && !e->sync_pending);
                e->sync_pending=true; e->capture_at=now+sync_capture_ms;
                break;
            }
            if (v==5) { assert(e->multi_mode && !e->master); e->multi_started=true; e->sampled=e->word; break; }
            if (e->multi_mode && !e->master) assert(e->multi_started);
            // GB-117: with CONFIG bit 6 both slots come from the COMPLETE word.
            e->received=e->multi_mode && !e->master && !(e->config&64) ? (e->word<<16)|(uint16_t)e->cmd_data : e->cmd_data;
            if (e->multi_mode && !e->master && wire_ms) {
                // DONE (with the sampled word) after the emulated wire time.
                e->done_pending=true; e->done_at=now+wire_ms; e->busy=false;
                e->anchor=e->id; e->anchor_valid=(v!=3); e->grant_limit=0;
                break;
            }
            event(e,GBA_CWL_DONE);
            e->busy=false; e->completed++; e->multi_started=false;
            e->anchor=e->id; e->anchor_valid=(v!=3); e->grant_limit=0;
            rearm(e);
        }
        break;
    default: return false;
    }
    return true;
}
// Batched FPGA access (GB-114): the same register model, in order, stopping
// at the first refused access like the Rust driver.
static unsigned batches;
static size_t batch_regs(void *ctx,GbLinkOp *ops,size_t n)
{
    batches++;
    size_t done=0;
    for(;done<n;done++) {
        bool ok=ops[done].write ? write_reg(ctx,ops[done].address,ops[done].value)
                                : read_reg(ctx,ops[done].address,&ops[done].value);
        if(!ok) break;
    }
    return done;
}
static void send_frame(void *ctx,const uint8_t dest[6],const uint8_t *data,size_t n)
{
    End *e=ctx;
    if(offline || (drop_ack && n==27 && (data[4]==GBA_CWL_ACK || data[4]==GBA_CWL_RESULT16)) || (loss && rnd()%100<20)) return;
    unsigned copies=loss && rnd()%5==0?2:1;
    while(copies--) {
        assert(pending<4096 && n<=48);
        Packet *p=&packets[pending++];
        *p=(Packet){.to=e==&ends[0]?1:0,.due=now+1+(loss?rnd()%15:0),.n=n,
            .unicast=!CwlLx_MacIsBroadcast(dest)};
        memcpy(p->data,data,n);
    }
}
static void channel(void *ctx,uint8_t ch) { (void)ctx;(void)ch; }
static uint32_t clock_us(void *ctx) { (void)ctx; return now*1000u; }
static void tick(unsigned n)
{
    while(n--) {
        for(unsigned i=0;i<pending;) {
            if(packets[i].due<=now) {
                Packet p=packets[i]; packets[i]=packets[--pending];
                ends[p.to].s.received_us=now*1000u; // GB-115: radio callback time
                if(!offline) gb_cwl_logic_receive(&ends[p.to].s,ends[1-p.to].mac,p.unicast,-40,p.data,p.n,now);
            } else i++;
        }
        for(unsigned i=0;i<2;i++) {
            End *e=&ends[i];
            if (e->sync_pending && now>=e->capture_at) {
                assert(e->busy);
                e->word=e->capture_word;
                e->received=(e->word<<16)|(uint16_t)e->cmd_data;
                e->sync_pending=false; e->busy=false; e->completed++;
                e->anchor=e->id; e->anchor_valid=true; e->grant_limit=0;
                event(e,GBA_CWL_DONE);
                rearm(e);
            }
            if (e->done_pending && now>=e->done_at) {
                uint32_t live=e->word; e->word=e->sampled; event(e,GBA_CWL_DONE); e->word=live;
                e->done_pending=false; e->completed++; e->multi_started=false;
                rearm(e);
            }
            gb_cwl_bridge_poll(&ends[i].s,now);
            gb_cwl_logic_poll(&ends[i].s,now);
        }
        now++;
    }
}
static void init(unsigned i,unsigned session,bool multi)
{
    End *e=&ends[i];
    e->mac[0]=2; e->mac[5]=i+1; e->multi_capable=multi;
    GbCwlIo io={.ctx=e,.read=read_reg,.write=write_reg,.send=send_frame,.channel=channel,.clock_us=clock_us,
                .batch=(i%2) ? batch_regs : NULL}; // one end batched, one without (both paths)
    gb_cwl_logic_init(&e->s,&io,e->mac,session,now);
    e->s.caps=GBA_CWL_CAPS; e->s.core_running=e->s.caps_read=true;
}
static void reset_pair(void)
{
    memset(ends,0,sizeof(ends));pending=0;loss=offline=drop_ack=false;
    init(0,100,false);init(1,200,false);tick(1200);
    assert(ends[0].s.gba.on && ends[1].s.gba.on);
    assert(ends[0].s.gba.remote_epoch && ends[1].s.gba.remote_epoch);
}
static void start(End *e,bool master,uint32_t word)
{
    assert(e->enabled && !e->busy);
    e->id++; e->master=master;e->word=word;e->sampled=word;e->busy=true;event(e,GBA_CWL_START);
}
static void exchange(unsigned role,unsigned i,bool late)
{
    End *m=&ends[role],*s=&ends[1-role];
    unsigned mn=m->completed,sn=s->completed;
    uint32_t mw=0x87654321u+i*0x10203u,sw=0xfedcba98u-i*0x20304u;
    if(!late) start(s,false,sw);
    start(m,true,mw);
    if(late) { tick(50);start(s,false,sw); }
    unsigned deadline=now+1600;
    while((m->busy||s->busy) && now<deadline) tick(1);
    assert(!m->busy && !s->busy);
    assert(m->completed==mn+1 && s->completed==sn+1);
    assert(m->received==sw && s->received==mw);
    tick(30);
}
static void inject_ready(End *parent, bool multi, uint32_t token)
{
    uint8_t p[27]={'L',0x10,24,1,multi?GBA_CWL_READY16:GBA_CWL_READY,1,0};
    uint32_t fields[]={parent->s.gba.remote_epoch,parent->s.gba.epoch,token,0,0x3333};
    for(unsigned i=0;i<5;i++) for(unsigned j=0;j<4;j++) p[7+4*i+j]=fields[i]>>(8*j);
    gba_cwl_receive(&parent->s,p,sizeof(p));
}
// A next-round READY can arrive after RESULT but before the parent START.
static void ready_between_rounds(bool multi,bool sync)
{
    memset(ends,0,sizeof(ends));pending=0;loss=offline=drop_ack=false;
    init(0,100,multi);init(1,200,multi);
    ends[0].sync_capable=ends[1].sync_capable=sync;
    tick(1200);
    ends[0].multi_mode=ends[1].multi_mode=multi;tick(250);
    ends[1].capture_word=0x1111;
    start(&ends[1],false,0x1111); start(&ends[0],true,0x2222);
    tick(100);
    assert(ends[0].completed==1 && ends[1].completed==1);
    assert(ends[0].s.gba.trace.round_n==1);
    assert(!ends[0].s.gba.requesting);
    ends[1].capture_word=0x3333;
    start(&ends[1],false,0x3333);
    tick(3); // First READY delivered; its 20 ms retransmission has not fired.
    assert(ends[0].s.gba.remote_ready);
    assert(ends[0].s.gba.remote_token==ends[1].id);
    unsigned stale=ends[0].s.gba.trace.ready_stale;
    inject_ready(&ends[0],multi,ends[1].id-1);
    assert(ends[0].s.gba.trace.ready_stale==stale+1);
    assert(ends[0].s.gba.remote_token==ends[1].id);
    inject_ready(&ends[0],multi,ends[1].id); // Current duplicate remains usable.
    assert(ends[0].s.gba.remote_ready);
    unsigned op=multi?GBA_CWL_ROUND16:GBA_CWL_REQUEST;
    unsigned requests=ends[0].s.gba.trace.tx[op];
    start(&ends[0],true,0x4444);
    tick(1);
    assert(ends[0].s.gba.trace.tx[op]==requests+1); // No retry-period wait.
    tick(100);
    assert(ends[0].completed==2 && ends[1].completed==2);
    assert(ends[0].received==(multi?0x33334444:0x3333));
    assert(ends[1].received==(multi?0x33334444:0x4444));
    assert(ends[0].s.gba.trace.round_n==2);
    inject_ready(&ends[0],multi,ends[1].id); // Completed READY cannot resurrect.
    assert(!ends[0].s.gba.remote_ready);
    const uint32_t mask=multi?0x0fffffff:0x1fffffff;
    ends[0].s.gba.remote_token=mask;
    inject_ready(&ends[0],multi,0); // Serial-number rollover is forward progress.
    assert(ends[0].s.gba.remote_ready && ends[0].s.gba.remote_token==0);
    inject_ready(&ends[0],multi,mask);
    assert(ends[0].s.gba.remote_token==0);
    printf("gba: next READY retained, stale/duplicate/wrap safe, immediate request multi=%u sync=%u PASS\n",multi,sync);
}

static void inject_grant(End *child,uint32_t tx,uint32_t rx,uint32_t cycles)
{
    uint8_t p[27]={'L',0x10,24,1,GBA_CWL_GRANT16,0,0};
    uint32_t fields[]={child->s.gba.remote_epoch,child->s.gba.epoch,tx,rx,cycles};
    for(unsigned i=0;i<5;i++) for(unsigned j=0;j<4;j++) p[7+4*i+j]=fields[i]>>(8*j);
    gba_cwl_receive(&child->s,p,sizeof(p));
}
static void grants(void)
{
    memset(ends,0,sizeof(ends)); pending=0; loss=offline=drop_ack=false;
    init(0,100,true); init(1,200,true);
    for(unsigned i=0;i<2;i++) ends[i].sync_capable=ends[i].grant_capable=true;
    tick(1200);
    ends[0].multi_mode=ends[1].multi_mode=true; tick(250);
    assert(ends[0].config==63 && ends[1].config==59);
    End *p=&ends[0],*c=&ends[1];
    p->cycles=100; c->capture_word=0xabcd;
    drop_ack=true;
    start(c,false,0x1111); start(p,true,0x2222); tick(100);
    assert(c->completed==1 && !p->completed);
    assert(!p->s.gba.grant_confirmed && !p->s.gba.trace.tx[GBA_CWL_GRANT16]);
    drop_ack=false; tick(40);
    assert(p->completed==1 && p->s.gba.grant_confirmed);
    assert(p->s.gba.trace.tx[GBA_CWL_GRANT16]>0 && c->grants>0);
    assert(c->grant_limit==100 && c->completed==1);
    // Coalesce duplicates/reordering before the next poll; no backward budget.
    unsigned applied=c->grants;
    inject_grant(c,p->id,c->id,200);
    inject_grant(c,p->id,c->id,150);
    inject_grant(c,p->id,c->id,200);
    tick(1);
    assert(c->grants==applied+1 && c->grant_limit==200);
    // A received GRANT applies in the same pass, even twice in one millisecond.
    inject_grant(c,p->id,c->id,250); gb_cwl_bridge_poll(&c->s,now);
    assert(c->grant_limit==250);
    inject_grant(c,p->id,c->id,300); gb_cwl_bridge_poll(&c->s,now);
    assert(c->grant_limit==300);
    // Parent between rounds: one GRANT per 1 ms tick, no skipped ticks.
    unsigned sent=p->s.gba.trace.tx[GBA_CWL_GRANT16];
    tick(100);
    sent=p->s.gba.trace.tx[GBA_CWL_GRANT16]-sent;
    assert(sent>=95 && sent<=101);
    inject_grant(c,p->id-1,c->id-1,100000);
    tick(1); assert(c->grant_limit==300); // stale anchor rejected
    // A new ROUND invalidates pending credit; only cached RESULT is retried.
    p->cycles=300; c->capture_word=0x5678;
    start(c,false,0x3333); start(p,true,0x4444); tick(100);
    assert(c->completed==2 && p->completed==2);
    assert(p->received==0x56784444 && c->received==p->received);
    assert(c->grant_limit==300);
    inject_grant(c,p->id-1,c->id-1,100000);
    tick(2); assert(c->grant_limit==300);
    // Actual RTL checks the commit/IRQ race; the adapter rejects old cache keys.
    p->cycles=400;
    drop_ack=true; start(c,false,0x5555); start(p,true,0x6666);
    tick(1600);
    assert(p->s.gba.trace.timeout==1);
    assert(!p->s.gba.grant_confirmed);
    tick(500);
    assert(p->s.gba.on && c->s.gba.on);
    assert(!p->s.gba.grant_confirmed && !c->s.gba.cached);
    unsigned pg=p->gen,cg=c->gen;
    tick(500);
    assert(p->gen==pg && c->gen==cg); // no reset ping-pong
    p->cycles=100; c->capture_word=0xaaaa; drop_ack=false;
    p->baud=3; c->baud=1; // measurement: baud reaches the trace, word stays 16-bit
    start(c,false,0xbbbb); start(p,true,0x1234); tick(100);
    assert(p->received==0xaaaa1234 && c->received==p->received);
    assert(p->s.gba.grant_confirmed);
    const GbaCwlTrace *pt=&p->s.gba.trace,*ct=&c->s.gba.trace;
    assert(pt->baud[3]==1 && ct->baud[1]==1 && p->s.gba.word==0x1234);
    assert(pt->crit_parent_n>0 && pt->crit_parent_ops>0 && pt->to_n==1 && pt->to_requesting);
    assert(ct->crit_child_n>0 && ct->crit_child_ops>0 && ct->split[GBA_SPLIT_CHILD_BEHIND].n>0);
    assert(pt->spi_ops>0 && ct->spi_ops>0);
    // GB-114: end 1 batches (one lock per group), end 0 runs the same groups one by one.
    assert(batches>0 && ct->spi_batches>0 && ct->spi_ops>ct->spi_batches);
    // GB-115: every step has samples, and the parent's steps add up to its REMOTE wait.
    for (unsigned k=GBA_HOP_P_RTT;k<=GBA_HOP_P_DONE;k++) assert(pt->hop[k].n>0);
    for (unsigned k=GBA_HOP_C_RXQ;k<=GBA_HOP_C_GRANT;k++) {
        if(!ct->hop[k].n) fprintf(stderr,"missing child hop %u\n",k);
        assert(ct->hop[k].n>0);
    }
    {
        const GbaHop *h=pt->hop;
        uint32_t steps=h[GBA_HOP_P_RTT].sum/h[GBA_HOP_P_RTT].n+h[GBA_HOP_P_RXQ].sum/h[GBA_HOP_P_RXQ].n+
                       h[GBA_HOP_P_DONE].sum/h[GBA_HOP_P_DONE].n;
        const GbaSplit *r=&pt->split[GBA_SPLIT_REMOTE];
        uint32_t remote=r->sum/r->n;
        assert(steps+1000>=remote && remote+1000>=steps);
        uint32_t child=0;
        for (unsigned k=GBA_HOP_C_RXQ;k<=GBA_HOP_C_SEND;k++) child+=ct->hop[k].sum/ct->hop[k].n;
        assert(h[GBA_HOP_P_RTT].sum/h[GBA_HOP_P_RTT].n+1000>=child); // child work happens inside the RTT
    }
    puts("gba: GRANT negotiation, lost RESULT bootstrap, coalescing, stale anchors and timeout PASS");
}

enum { UNSYNC_ROUNDS_FOR_TEST = 300 }; // = UNSYNC_AFTER_ROUNDS in gamebub_cwl_gba.c
// GB-116: cycle sync while the games pair, unsynced afterwards, synced again
// after a restart. Every round must deliver both words in either mode.
static void multi_round_spaced(End *p,End *c,uint32_t pw,uint32_t cw,unsigned spacing)
{
    unsigned pn=p->completed,cn=c->completed,t0=now;
    c->capture_word=cw;
    if (c->busy) c->word=cw; else start(c,false,cw); // already armed after the last DONE
    start(p,true,pw);
    unsigned deadline=now+1600;
    while((p->completed==pn || c->completed==cn) && now<deadline) tick(1);
    assert(p->completed==pn+1 && c->completed==cn+1);
    assert(p->received==((cw<<16)|pw) && c->received==p->received);
    tick(1);
    while(now<t0+spacing) tick(1);
}
// Paired Mario Kart rounds come ~21 ms apart; pairing rounds back to back.
static void multi_round(End *p,End *c,uint32_t pw,uint32_t cw) { multi_round_spaced(p,c,pw,cw,21); }
static void fast_round(End *p,End *c,uint32_t pw,uint32_t cw) { multi_round_spaced(p,c,pw,cw,0); }
static void unsync(void)
{
    memset(ends,0,sizeof(ends)); pending=0; loss=offline=drop_ack=false;
    init(0,100,true); init(1,200,true);
    for(unsigned i=0;i<2;i++) ends[i].sync_capable=ends[i].grant_capable=true;
    tick(1200);
    ends[0].multi_mode=ends[1].multi_mode=true; tick(250);
    End *p=&ends[0],*c=&ends[1];
    assert(p->config==63 && c->config==59);
    p->cycles=100; rearm_child=true;
    // Pairing: many rounds back to back never switch (Mario Kart cannot pair unsynced).
    sync_capture_ms=2;
    for(unsigned i=0;i<2*UNSYNC_ROUNDS_FOR_TEST;i++) fast_round(p,c,0x0100+i,0x0200+i);
    assert(!p->s.gba.unsync && !c->s.gba.unsynced && (p->config&16));
    sync_capture_ms=4;
    // The first slow START follows a fast one, so N spaced gaps need N+1 STARTs.
    for(unsigned i=0;i<UNSYNC_ROUNDS_FOR_TEST;i++) multi_round(p,c,0x1000+i,0x2000+i);
    assert(!p->s.gba.unsync && !c->s.gba.unsynced);
    multi_round(p,c,0x1111,0x2222);
    // The parent asks, the child echoes and switches first, then the parent.
    tick(400);
    assert(p->s.gba.unsync && c->s.gba.unsynced && p->s.gba.unsynced);
    assert(!(p->config&16) && !(c->config&16) && !(p->config&32) && !(c->config&32));
    for(unsigned i=0;i<20;i++) multi_round(p,c,0x3000+i,0x4000+i);
    assert(!p->s.gba.trace.timeout && !c->s.gba.trace.timeout);
    assert(c->s.gba.trace.hop[GBA_HOP_C_CMD].n>0);
    // The game pairs again (rounds back to back): both return to sync, no restart.
    for(unsigned i=0;i<40;i++) fast_round(p,c,0x7000+i,0x7100+i);
    tick(400);
    assert(!p->s.gba.unsync && !p->s.gba.unsynced && !c->s.gba.unsynced);
    assert((p->config&16) && (c->config&16));
    for(unsigned i=0;i<5;i++) fast_round(p,c,0x7200+i,0x7300+i);
    assert(!p->s.gba.trace.timeout && !c->s.gba.trace.timeout && !p->s.gba.fault && !c->s.gba.fault);
    // Paired again: unsynced again after the slow streak.
    for(unsigned i=0;i<UNSYNC_ROUNDS_FOR_TEST+1;i++) multi_round(p,c,0x7400+i,0x7500+i);
    tick(400);
    assert(p->s.gba.unsynced && c->s.gba.unsynced);
    // A lost RESULT in unsynced mode restarts the link, which pairs synced again.
    rearm_child=false; drop_ack=true; c->capture_word=0x5555;
    if (c->busy) c->word=0x5555; else start(c,false,0x5555);
    start(p,true,0x6666);
    tick(1700); drop_ack=false; tick(1500);
    assert(p->s.gba.trace.timeout==1 && !p->s.gba.unsynced && (p->config&16));
    sync_capture_ms=40;
    printf("gba: pairing stays synced, %u slow rounds -> unsynced (child first, re-armed child), back to sync on fast rounds, resync after a timeout PASS\n",
           UNSYNC_ROUNDS_FOR_TEST);
}

// GB-117: unsynced child answers at BEGIN (A); with the buffer (B) both sides
// deliver the previous round's pair and the parent completes at START.
static void pair_buffer(bool buffer)
{
    memset(ends,0,sizeof(ends)); pending=0; loss=offline=drop_ack=false;
    gba_cwl_buffer=buffer; wire_ms=2;
    init(0,100,true); init(1,200,true);
    for(unsigned i=0;i<2;i++) ends[i].sync_capable=ends[i].grant_capable=ends[i].pair_capable=true;
    tick(1200);
    ends[0].multi_mode=ends[1].multi_mode=true; tick(250);
    End *p=&ends[0],*c=&ends[1];
    p->cycles=100; rearm_child=true; sync_capture_ms=2;
    for(unsigned i=0;i<100;i++) fast_round(p,c,0x0100+i,0x0200+i);
    sync_capture_ms=4;
    for(unsigned i=0;i<UNSYNC_ROUNDS_FOR_TEST+1;i++) multi_round(p,c,0x1000+i,0x2000+i);
    tick(400);
    assert(p->s.gba.unsynced && c->s.gba.unsynced && (p->config&64) && (c->config&64));
    uint32_t last=0; unsigned parent_ms=0;
    for(unsigned i=0;i<200;i++) {
        if (i==100) loss=true;   // 20% loss, duplicates and reorder on every frame
        uint32_t pw=0x3000+i, cw=0x4000+i, fresh=(cw<<16)|pw;
        unsigned pn=p->completed,cn=c->completed,t0=now;
        c->capture_word=cw; if (c->busy) c->word=cw; else start(c,false,cw);
        start(p,true,pw);
        unsigned deadline=now+1600;
        while(p->completed==pn && now<deadline) tick(1);
        if (i<100) parent_ms+=now-t0;
        while(c->completed==cn && now<deadline) tick(1);
        assert(p->completed==pn+1 && c->completed==cn+1);
        // Both consoles always see the same pair.
        assert(p->received==c->received);
        if (!buffer) assert(p->received==fresh);
        else assert(p->received==fresh || p->received==last); // previous pair, or fresh after a miss
        last=fresh;
        tick(1); while(now<t0+21) tick(1);
    }
    loss=false; tick(200);
    GbaCwlTrace *pt=&p->s.gba.trace,*ct=&c->s.gba.trace;
    assert(!pt->timeout && !ct->timeout && !p->s.gba.fault && !c->s.gba.fault);
    assert(ct->early>=200 && !ct->early_mismatch);
    if (buffer) {
        assert(pt->buffered>=150 && pt->buffered+pt->buffer_miss>=200);
        assert(parent_ms<=100*2); // completed within the START poll, no radio wait
    } else {
        assert(!pt->buffered && !pt->buffer_miss && parent_ms>=100*3);
    }
    printf("gba: GB-117 early child answer%s: parent %u ms/100 rounds, early=%lu buffered=%lu miss=%lu held=%lu, same pair on both under loss PASS\n",
           buffer ? " + one-round buffer" : "", parent_ms, (unsigned long)ct->early,
           (unsigned long)pt->buffered, (unsigned long)pt->buffer_miss, (unsigned long)pt->buffer_wait);
    gba_cwl_buffer=false; wire_ms=0; rearm_child=false; sync_capture_ms=40;
}

// GB-117: a menu (paused emulator) on either console returns both to cycle sync;
// the parent waits through a 5 s pause without a timeout; after it the buffer
// comes back only after a new slow streak.
static void pause_side(unsigned paused_end)
{
    memset(ends,0,sizeof(ends)); pending=0; loss=offline=drop_ack=false;
    gba_cwl_buffer=true; wire_ms=2;
    init(0,100,true); init(1,200,true);
    for(unsigned i=0;i<2;i++) ends[i].sync_capable=ends[i].grant_capable=ends[i].pair_capable=true;
    tick(1200);
    ends[0].multi_mode=ends[1].multi_mode=true; tick(250);
    End *p=&ends[0],*c=&ends[1];
    p->cycles=100; sync_capture_ms=4;
    for(unsigned i=0;i<UNSYNC_ROUNDS_FOR_TEST+2;i++) multi_round(p,c,0x1000+i,0x2000+i);
    tick(400);
    assert(p->s.gba.unsynced && c->s.gba.unsynced && (p->config&64));
    ends[paused_end].s.paused=true;
    tick(300);
    assert(!p->s.gba.unsynced && !c->s.gba.unsynced && (p->config&16) && (c->config&16));
    // The parent starts a transfer the paused child cannot serve for 5 s.
    if (paused_end==1) {
        start(p,true,0x5555);
        tick(5000);
        assert(!p->s.gba.trace.timeout && !p->s.gba.fault && p->busy);
        ends[1].s.paused=false;
        c->capture_word=0x6666; start(c,false,0x6666);
        unsigned deadline=now+1600;
        while((p->busy||c->busy) && now<deadline) tick(1);
        assert(!p->busy && !c->busy && p->received==c->received && p->received==0x66665555u);
    } else {
        tick(5000);
        ends[0].s.paused=false;
    }
    tick(300);
    assert(!p->s.gba.trace.timeout && !c->s.gba.trace.timeout && !p->s.gba.fault && !c->s.gba.fault);
    assert(!p->s.gba.unsynced);
    for(unsigned i=0;i<UNSYNC_ROUNDS_FOR_TEST+2;i++) multi_round(p,c,0x3000+i,0x4000+i);
    tick(400);
    assert(p->s.gba.unsynced && c->s.gba.unsynced);
    printf("gba: GB-117 %s paused 5 s: both back to cycle sync, no timeout, buffer again after a slow streak PASS\n",
           paused_end ? "child" : "parent");
    gba_cwl_buffer=false; wire_ms=0; sync_capture_ms=40;
}

int main(void)
{
    pause_side(1);
    pause_side(0);
    pair_buffer(false);
    pair_buffer(true);
    grants();
    unsync();
    ready_between_rounds(false,false);
    ready_between_rounds(true,false);
    ready_between_rounds(true,true);
    reset_pair();
    for(unsigned i=0;i<20;i++) exchange(i%2,i,i%3==0);
    for(unsigned i=0;i<2;i++) {
        GbaCwlTrace *t=&ends[i].s.gba.trace;
        assert(t->start[0]==10 && t->start[1]==10 && t->done==20);
        assert(t->command_ok==20 && !t->timeout && !t->command_error);
        assert(t->tx[GBA_CWL_REQUEST] && t->rx[GBA_CWL_ACK]);
        // Each endpoint mastered 10 transfers and served 10 as child.
        assert(t->split[GBA_SPLIT_WAIT].n==10 && t->split[GBA_SPLIT_REMOTE].n==10);
        assert(t->split[GBA_SPLIT_CHILD_WAIT].n==10 && t->split[GBA_SPLIT_CHILD_RUN].n==10);
        // A REQUEST/ACK pair needs two 1 ms deliveries; the wait is bounded.
        assert(t->split[GBA_SPLIT_REMOTE].sum/10>=2000);
        assert(gba_split_percentile(&t->split[GBA_SPLIT_REMOTE],50)>=2000);
        assert(t->split[GBA_SPLIT_WAIT].max<=1600000);
    }
    GbaSplit h={0};
    gba_split_add(&h,100); gba_split_add(&h,900); gba_split_add(&h,20000);
    assert(h.n==3 && h.max==20000 && h.bins[0]==1 && h.bins[3]==1 && h.bins[32]==1);
    assert(gba_split_percentile(&h,50)==1000 && gba_split_percentile(&h,95)==8000);
    puts("gba: per-transfer microsecond split (wait/remote/child) PASS");
    puts("gba: mutual Game Bub pairing, full 32-bit words, either master, late slave PASS");
    loss=true;
    for(unsigned i=20;i<220;i++) exchange(i%2,i,i%3==0);
    puts("gba: 20% loss, duplicates, jitter, cached ACKs, exactly one completion PASS");
    reset_pair();
    start(&ends[0],true,1); start(&ends[1],true,2);tick(1600);
    assert(!ends[0].busy && !ends[1].busy);
    assert(ends[0].received==UINT32_MAX && ends[1].received==UINT32_MAX);
    assert(ends[0].s.gba.trace.timeout==1 && ends[1].s.gba.trace.timeout==1);
    assert(ends[0].s.gba.trace.command_word==UINT32_MAX);
    exchange(0,0,false);
    puts("gba: both masters time out and the next exchange succeeds PASS");
    reset_pair();
    start(&ends[0],true,3);tick(20);
    event(&ends[0],GBA_CWL_CANCEL); ends[0].busy=false;tick(30);
    start(&ends[1],false,4);tick(100);
    assert(ends[1].busy && !ends[1].completed);
    // Explicit stop flushes both ports and invalidates all old readiness tokens.
    gba_cwl_stop(&ends[0].s);gba_cwl_stop(&ends[1].s);tick(200);
    exchange(1,10,false);
    puts("gba: cancel before slave arm, stop/restart, no phantom completion PASS");
    reset_pair();
    start(&ends[1],false,0x11111111);
    tick(2000);
    assert(!ends[1].busy && ends[1].received==UINT32_MAX);
    unsigned requests=ends[0].s.gba.trace.tx[GBA_CWL_REQUEST];
    exchange(0,12,true);
    assert(ends[0].s.gba.trace.tx[GBA_CWL_REQUEST]==requests+1);
    puts("gba: expired slave READY does not pin the next master exchange PASS");

    reset_pair(); drop_ack=true;
    start(&ends[1],false,0x12345678);start(&ends[0],true,0x87654321);tick(300);
    assert(ends[1].completed==1 && ends[0].busy);
    assert(ends[1].s.gba.trace.tx[GBA_CWL_ACK] && !ends[0].s.gba.trace.rx[GBA_CWL_ACK]);
    drop_ack=false;tick(100);
    assert(ends[0].completed==1 && ends[0].received==0x12345678);
    assert(ends[1].completed==1);
    puts("gba: lost ACK retransmits cached slave word without replaying IRQ PASS");
    reset_pair(); start(&ends[0],true,42);tick(20);offline=true;tick(2500);
    assert(!ends[0].busy && !ends[0].s.gba.on);
    puts("gba: partner loss releases held port PASS");
    assert(ends[0].s.gba.trace.stop && ends[0].s.gba.trace.start[0]==1);
    reset_pair();
    uint8_t stale[27]={'L',0x10,24,1,GBA_CWL_HELLO};
    gb_cwl_logic_receive(&ends[0].s,ends[1].mac,true,-40,stale,sizeof(stale),now);
    assert(ends[0].s.gba.trace.rx[GBA_CWL_HELLO]>0);
    assert(ends[0].s.gba.trace.reject[GBA_RX_SESSION]==1);
    puts("gba: trace distinguishes FPGA events, dropped ACK, timeout and wrong session PASS");
    memset(ends,0,sizeof(ends));
    pending=0;
    init(0,100,true);
    init(1,200,true);
    tick(1200);
    assert(ends[0].config==7 && ends[1].config==3);
    // A console in Normal mode cannot advertise Multiplayer readiness.
    ends[1].multi_mode=true;
    tick(250);
    assert(ends[0].config==15 && ends[1].config==3);
    ends[0].multi_mode=true;
    tick(250);
    assert(ends[1].config==11);
    start(&ends[1],false,0x1111);
    tick(2000);
    assert(ends[1].busy && !ends[1].completed);
    // A child can change SEND after advertising readiness but before the round.
    ends[1].word=0xffff;
    drop_ack=true;
    start(&ends[0],true,0xffff);
    tick(150);
    assert(ends[1].completed==1 && ends[0].busy);
    start(&ends[1],false,0x2222);
    drop_ack=false;
    tick(150);
    assert(ends[0].completed==1 && ends[1].completed==1);
    assert(ends[0].received==UINT32_MAX && ends[1].received==UINT32_MAX);
    tick(50);
    start(&ends[0],true,0x3333);
    tick(100);
    assert(ends[0].received==0x22223333 && ends[1].received==0x22223333);
    assert(ends[0].completed==2 && ends[1].completed==2);
    assert(ends[0].s.gba.trace.rx[GBA_CWL_RESULT16]>=2);
    assert(!ends[0].s.gba.trace.timeout && !ends[1].s.gba.trace.timeout);
    puts("gba: Multiplayer roles, idle child, current SEND, identical slots and lost RESULT PASS");
    unsigned multi_requests=ends[0].s.gba.trace.tx[GBA_CWL_ROUND16];
    start(&ends[0],true,0x4444);
    tick(5);
    assert(ends[0].s.gba.trace.tx[GBA_CWL_ROUND16]==multi_requests);

    drop_ack=true;
    start(&ends[1],false,0x5555);
    tick(2);
    assert(ends[0].s.gba.trace.tx[GBA_CWL_ROUND16]==multi_requests+1);
    tick(19);
    assert(ends[0].s.gba.trace.tx[GBA_CWL_ROUND16]==multi_requests+1);
    assert(ends[0].busy && ends[1].completed==3);

    drop_ack=false;
    tick(1);
    assert(ends[0].s.gba.trace.tx[GBA_CWL_ROUND16]==multi_requests+2);
    tick(4);
    assert(ends[0].received==0x55554444 && ends[1].received==0x55554444);
    assert(ends[0].completed==3 && ends[1].completed==3);
    puts("gba: READY16 starts the first round immediately, retries at 20 ms and completes once PASS");
    // Same production adapters, now with the cycle-capable FPGA extension.
    memset(ends,0,sizeof(ends)); pending=0; loss=offline=drop_ack=false;
    init(0,100,true); init(1,200,true);
    ends[0].sync_capable=ends[1].sync_capable=true;
    tick(1200);
    assert(ends[0].config==23 && ends[1].config==19);
    ends[0].multi_mode=ends[1].multi_mode=true; tick(250);
    assert(ends[0].config==31 && ends[1].config==27);
    ends[0].cycles=123456; ends[1].capture_word=0xabcd;
    start(&ends[1],false,0x1111); start(&ends[0],true,0x2222);
    tick(15);
    assert(ends[1].sync_pending && ends[1].cmd_cycles==123456);
    assert(!ends[1].completed && !ends[0].completed);
    assert(ends[1].s.gba.trace.tx[GBA_CWL_RESULT16]==0);
    // Lost results replay the word sampled after catch-up, not the READY word.
    drop_ack=true; tick(120);
    assert(ends[1].completed==1 && ends[0].busy && ends[1].commands==1);
    assert(ends[1].received==0xabcd2222);
    drop_ack=false; tick(50);
    assert(ends[0].completed==1 && ends[0].received==0xabcd2222);
    assert(ends[1].completed==1 && ends[1].commands==1);
    puts("gba: negotiated cycle ROUND waits for FPGA capture and replays cached RESULT PASS");
    loss=true;
    for (unsigned i=0;i<80;i++) {
        unsigned count=ends[0].completed;
        ends[0].cycles=1000+i*77; ends[1].capture_word=0x8000+i;
        start(&ends[1],false,0xdead); start(&ends[0],true,0x4000+i);
        unsigned deadline=now+1600;
        while ((ends[0].busy || ends[1].busy) && now<deadline) tick(1);
        assert(ends[0].completed==count+1 && ends[1].completed==count+1);
        assert(ends[0].received==((0x8000+i)<<16)+(0x4000+i));
        assert(ends[1].received==ends[0].received);
        assert(ends[1].cmd_cycles==ends[0].cycles);
        tick(30);
    }
    assert(ends[1].commands==81);
    assert(!ends[0].s.gba.trace.timeout && !ends[1].s.gba.trace.timeout);
    puts("gba: cycle budgets survive 20% loss, duplication and reorder without recapture PASS");
    uint8_t malformed[31]={'L',0x10,28,1,GBA_CWL_HELLO};
    unsigned rejects=ends[0].s.gba.trace.reject[GBA_RX_SHAPE];
    gba_cwl_receive(&ends[0].s,malformed,sizeof(malformed));
    assert(ends[0].s.gba.trace.reject[GBA_RX_SHAPE]==rejects+1);
    memset(ends,0,sizeof(ends)); pending=0; loss=false;
    init(0,100,true); init(1,200,true); ends[0].sync_capable=true;
    tick(1200);
    assert(ends[0].config==7 && ends[1].config==3);
    puts("gba: malformed extended frames rejected and old FPGA negotiates legacy mode PASS");
    puts("gba_test: ALL PASS");
}
