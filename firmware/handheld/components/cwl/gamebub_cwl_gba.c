// SPDX-License-Identifier: GPL-3.0-only
#include "gamebub_cwl_logic.h"
#include <string.h>

enum { CONFIG=0x64, STATUS=0x68, EVENT=0x6c, DATA=0x70, POP=0x74,
       COMMAND_DATA=0x78, COMMAND_ID=0x7c, COMMAND=0x80, CONTROL=0x84, EVENT_CYCLES=0x88, COMMAND_CYCLES=0x8c, SNAPSHOT=0x90, SNAPSHOT_CYCLES=0x94, COMMAND_ANCHOR=0x98, SAMPLED=0x9c };
#ifndef GBA_CWL_BUFFER
#define GBA_CWL_BUFFER 1
#endif
bool gba_cwl_buffer = GBA_CWL_BUFFER;
// The slave advertises every 20 ms while armed. Keep several beacon periods
// for loss and jitter, but do not start a request from an old advertisement.
enum { READY_MAX_AGE_MS = 100, PEER_MODE_MAX_AGE_MS = 350 };
// Parent GRANT cadence, measured in microseconds. Each GRANT costs three SPI
// accesses, so it stays at about one per 1 ms task tick; 900 us lets every tick
// wake send one instead of the truncated millisecond counter skipping some.
enum { GRANT_PERIOD_US = 900 };
enum {
    CFG_ENABLE = 1, CFG_MULTI = 2, CFG_PARENT = 4, CFG_PEER_READY = 8, CFG_SYNC = 16, CFG_GRANT = 32,
    STATUS_MULTI = 1 << 11, HELLO_NORMAL32 = 1, HELLO_MULTI16 = 2,
    HELLO_IN_MULTI16 = 4, HELLO_SYNC16 = 8, HELLO_GRANT16 = 16, CMD_GRANT = 17, CMD_SYNC_CHILD = 9, CMD_COMPLETE = 1, CMD_ERROR = 3, CMD_BEGIN_CHILD = 5,
    // GB-116: cycle sync only while the games pair (rounds back to back). After
    // UNSYNC_AFTER_ROUNDS parent STARTs >= SLOW_ROUND_MS apart the parent asks for
    // unsynced rounds; the child switches first, then the parent. UNSYNC_FAST_BACK
    // closer STARTs in a row (the game pairs again) bring both back to sync.
    // A parent timeout while unsynced restarts the link.
    HELLO_UNSYNC16 = 32, UNSYNC_AFTER_ROUNDS = 300, SLOW_ROUND_MS = 12, UNSYNC_FAST_BACK = 20,
    // GB-117: unsynced rounds take both SIOMULTI slots from COMPLETE (CFG_PAIR).
    // The child reads its BEGIN-sampled SEND and answers at once. With the buffer,
    // a parent ROUND marked PAIR carries the pair both sides deliver this round
    // (the previous exchange), so the parent completes at START.
    CFG_PAIR = 64, HELLO_PAIR16 = 64, ROUND_FLAG_PAIR = 1,
    // A paused emulator says so in HELLO; while either side is paused both use
    // cycle sync (the waiting game freezes), the parent does not time out, and
    // unsynced rounds need a new slow streak after the pause.
    HELLO_PAUSED16 = 128
};

static uint32_t get32(const uint8_t *p)
{ return (uint32_t)p[0] | (uint32_t)p[1]<<8 | (uint32_t)p[2]<<16 | (uint32_t)p[3]<<24; }
static void put32(uint8_t *p, uint32_t v)
{ for (unsigned i=0; i<4; i++) p[i] = v >> (8*i); }
static uint32_t now_us(GbCwlLogic *s)
{ return s->io.clock_us ? s->io.clock_us(s->io.ctx) : 0; }
// Every FPGA access is counted and timed (measurement, GB-113).
static bool rd(GbCwlLogic *s, unsigned a, uint32_t *v)
{
    uint32_t t=now_us(s);
    bool ok=s->io.read && s->io.read(s->io.ctx, a, v);
    s->gba.trace.spi_ops++; s->gba.trace.spi_us+=now_us(s)-t;
    return ok;
}
static bool wr(GbCwlLogic *s, unsigned a, uint32_t v)
{
    uint32_t t=now_us(s);
    bool ok=s->io.write && s->io.write(s->io.ctx, a, v);
    s->gba.trace.spi_ops++; s->gba.trace.spi_us+=now_us(s)-t;
    return ok;
}
// Several accesses in order, in one SPI batch when the platform offers it
// (GB-114). spi_ops counts accesses, spi_batches the lock/bus acquisitions.
#define OPR(a)   {(a),0,0}
#define OPW(a,v) {(a),(v),1}
static bool xfer(GbCwlLogic *s, GbLinkOp *ops, size_t n)
{
    uint32_t t=now_us(s);
    size_t done=0;
    if (s->io.batch) done=s->io.batch(s->io.ctx,ops,n);
    else for (; done<n; done++) {
        bool ok=ops[done].write ? (s->io.write && s->io.write(s->io.ctx,ops[done].address,ops[done].value))
                                : (s->io.read && s->io.read(s->io.ctx,ops[done].address,&ops[done].value));
        if (!ok) break;
    }
    s->gba.trace.spi_ops+=n; s->gba.trace.spi_batches++; s->gba.trace.spi_us+=now_us(s)-t;
    return done==n;
}
// GB-115: one step of a round; skipped without a clock or a start time.
static void hop(GbCwlLogic *s, unsigned kind, uint32_t from, uint32_t to)
{
    if (!s->io.clock_us || !from || (int32_t)(to-from)<0) return;
    GbaHop *h=&s->gba.trace.hop[kind];
    const uint32_t us=to-from;
    h->n++; h->sum+=us; if (us>h->max) h->max=us;
}
static void crit_begin(GbaCwl *g)
{ g->crit_ops0=g->trace.spi_ops; g->crit_us0=g->trace.spi_us; }
static void crit_end(GbaCwl *g, uint32_t *n, uint32_t *ops, uint32_t *us)
{ (*n)++; *ops+=g->trace.spi_ops-g->crit_ops0; *us+=g->trace.spi_us-g->crit_us0; }
void gba_split_add(GbaSplit *h, uint32_t us)
{
    unsigned b=us/250;
    if (h->bins[b<32 ? b : 32]<UINT16_MAX) h->bins[b<32 ? b : 32]++;
    h->n++; h->sum+=us; if (us>h->max) h->max=us;
}
// Upper bound of the bucket holding the percentile; 8000 means >=8 ms.
uint32_t gba_split_percentile(const GbaSplit *h, unsigned percent)
{
    if (!h->n) return 0;
    uint64_t rank=((uint64_t)h->n*percent+99)/100, count=0;
    for (unsigned i=0;i<33;i++) { count+=h->bins[i]; if (count>=rank) return i<32 ? (i+1)*250 : 8000; }
    return 8000;
}
static void split(GbCwlLogic *s, unsigned kind, uint32_t since)
{
    if (s->io.clock_us) gba_split_add(&s->gba.trace.split[kind], now_us(s)-since);
}
static void send_word(GbCwlLogic *s, unsigned op, uint32_t tx, uint32_t rx, uint32_t word)
{
    uint8_t p[31] = {'L',0x10,24,1,0,0,0};
    const bool sync = op == GBA_CWL_ROUND16 && s->gba.sync;
    const size_t size = sync ? 31 : 27;
    p[2] = size - 3;
    p[4] = op;
    p[5] = (op == GBA_CWL_REQUEST || op == GBA_CWL_ROUND16 || op == GBA_CWL_GRANT16) ? 0 : 1;
    if (op == GBA_CWL_ROUND16 && !sync && s->gba.pair_round) { p[6] = ROUND_FLAG_PAIR; word = s->gba.round_pair; }
    put32(p+7,s->gba.epoch); put32(p+11,s->gba.remote_epoch);
    put32(p+15,tx); put32(p+19,rx); put32(p+23,word);
    if (sync) put32(p+27,s->gba.cycles);
    s->gba.trace.tx[op]++;
    s->io.send(s->io.ctx,s->peer.mac,p,size); s->tx++;
}
// cycles: optional COMMAND_CYCLES written first in the same batch (sync child).
static bool command_cycles(GbCwlLogic *s, uint32_t id, uint32_t word, unsigned action,
                           const uint32_t *cycles)
{
    GbLinkOp ops[4]={OPW(COMMAND_CYCLES,cycles ? *cycles : 0),OPW(COMMAND_DATA,word),
                     OPW(COMMAND_ID,id),OPW(COMMAND,action)};
    bool ok = cycles ? xfer(s,ops,4) : xfer(s,ops+1,3);
    s->gba.trace.command_word = word;
    if (ok) {
        s->gba.trace.command_ok++;
    } else {
        s->gba.trace.command_error++;
        s->gba.fault = true;
    }
    return ok;
}
static bool command(GbCwlLogic *s, uint32_t id, uint32_t word, unsigned action)
{ return command_cycles(s,id,word,action,NULL); }
static void stop(GbCwlLogic *s, unsigned reason)
{
    GbaCwl *g = &s->gba;
    if (g->on) {
        g->trace.stop++;
        g->trace.stop_reason = reason;
        wr(s,CONTROL,1);
    }
    memset(g,0,offsetof(GbaCwl,trace));
}
void gba_cwl_stop(GbCwlLogic *s)
{
    stop(s,GBA_STOP_EXPLICIT);
}
// A local START: one transfer of this game. detect=false when it was held.
static void round_start(GbCwlLogic *s, uint32_t event, uint32_t id, uint32_t word,
                        uint32_t cycles, bool multi, uint32_t now, bool detect)
{
    GbaCwl *g=&s->gba;
    crit_begin(g);
    g->trace.role=(event&(1u<<29)) ? 0 : 1;
    g->trace.start[g->trace.role]++;
    g->trace.token=id;
    g->trace.outgoing=word;
    g->trace.incoming=0;
    g->trace.command_word=0;
    g->active=true; g->master=(event&(1u<<29))!=0; g->multi=multi;
    g->token=id; g->word=word; g->cycles=cycles;
    g->sync=multi && (g->config & CFG_SYNC)!=0;
    g->requesting=false; g->started_at=now; g->retry_at=now;
    g->started_us=now_us(s);
    if (g->master && multi && detect) hop(s,GBA_HOP_P_DETECT,s->irq_us,g->started_us);
    g->answered=false; g->pair_round=false;
    if (g->master && multi && (g->config & CFG_PAIR) && g->peer_pair && gba_cwl_buffer) {
        // GB-117 buffer: deliver the previous exchange now; this round's
        // pair is exchanged meanwhile and delivered next round.
        if (g->have_prev && command(s,id,g->prev_pair,CMD_COMPLETE)) {
            g->pair_round=true; g->round_pair=g->prev_pair; g->trace.buffered++;
            hop(s,GBA_HOP_P_DONE,g->started_us,now_us(s));
        } else g->trace.buffer_miss++;
    }
    g->have_prev=false;
    if (g->master && multi) {
        // GB-116: the game's round spacing tells pairing from play.
        if (g->last_start_known) {
            if ((uint32_t)(now-g->last_start_at)>=SLOW_ROUND_MS) {
                if (g->slow_streak<UINT32_MAX) g->slow_streak++;
                g->fast_streak=0;
            } else {
                g->slow_streak=0;
                if (g->fast_streak<UINT32_MAX) g->fast_streak++;
            }
        }
        g->last_start_at=now; g->last_start_known=true;
    }
    g->awaiting_done=false;
}
void gba_cwl_receive(GbCwlLogic *s, const uint8_t *p, size_t n)
{
    GbaCwl *g=&s->gba;
    if (!g->on || (n!=27 && n!=31) || p[0]!='L' || p[1]!=0x10 || p[2]!=n-3 ||
        p[3]!=1 || p[4]>=GBA_CWL_OP_COUNT || p[5]>1 ||
        (p[6] && !(p[6]==ROUND_FLAG_PAIR && n==27 && p[4]==GBA_CWL_ROUND16)) ||
        (n==31 && p[4]!=GBA_CWL_ROUND16)) {
        g->trace.reject[GBA_RX_SHAPE]++;
        return;
    }
    g->trace.rx[p[4]]++;
    uint32_t epoch=get32(p+7), dest=get32(p+11);
    // Discovery owns the peer session. Delayed frames cannot reopen an old one.
    g->trace.seen_epoch=epoch;
    if (!s->core.peer_session_known || (epoch>>8)!=s->core.peer_session) {
        g->trace.reject[GBA_RX_SESSION]++;
        return;
    }
    if (epoch!=g->remote_epoch) {
        if (g->remote_epoch && (epoch>>8)==(g->remote_epoch>>8) &&
            (uint8_t)(epoch-g->remote_epoch)>=128) {
            g->trace.reject[GBA_RX_EPOCH]++;
            return;
        }
        // Reset the local cycle origin once when a live GRANT peer restarts.
        // Fresh/bootstrap endpoints have no committed origin, so they adopt
        // the new epoch without bouncing resets indefinitely.
        if (g->remote_epoch && (g->config & CFG_GRANT) &&
            (g->grant_confirmed || g->cached)) {
            stop(s,GBA_STOP_UNAVAILABLE);
            return;
        }
        g->trace.remote_epoch=epoch;
        g->remote_epoch=epoch; g->remote_ready=false; g->remote_token_known=false; g->cached=false; g->awaiting_done=false;
        g->peer_multi_supported=false; g->peer_sync_supported=false;
        g->peer_grant_supported=false; g->grant_confirmed=false; g->grant_pending=false;
        g->peer_multi=false; g->peer_unsync=false;
    }
    if (p[4]==GBA_CWL_HELLO) {
        uint32_t capabilities=get32(p+23);
        if ((g->config & CFG_GRANT) && g->peer_multi &&
            !(capabilities & HELLO_IN_MULTI16) &&
            (g->grant_confirmed || g->cached)) {
            stop(s,GBA_STOP_UNAVAILABLE);
            return;
        }
        g->peer_multi_supported=(capabilities & HELLO_MULTI16)!=0;
        g->peer_sync_supported=g->peer_multi_supported && (capabilities & HELLO_SYNC16)!=0;
        g->peer_grant_supported=g->peer_sync_supported && (capabilities & HELLO_GRANT16)!=0;
        g->peer_multi=g->peer_multi_supported && (capabilities & HELLO_IN_MULTI16)!=0;
        g->peer_unsync=g->peer_sync_supported && (capabilities & HELLO_UNSYNC16)!=0;
        g->peer_pair=g->peer_multi_supported && (capabilities & HELLO_PAIR16)!=0;
        g->peer_paused=(capabilities & HELLO_PAUSED16)!=0;
        g->peer_multi_at=s->now;
        return;
    }
    if (dest!=g->epoch) {
        g->trace.reject[GBA_RX_DEST]++;
        return;
    }
    uint32_t tx=get32(p+15), rx=get32(p+19), word=get32(p+23);
    const unsigned op=p[4];
    // GB-116: an unsynced child also takes a synced ROUND (the parent switches
    // after it) and serves it unsynced, ignoring the cycle.
    const bool sync=n==31 && !(op==GBA_CWL_ROUND16 && g->unsynced && !(g->config & CFG_PARENT));
    const uint32_t cycles=sync ? get32(p+27) : 0;
    const bool pair=p[6]==ROUND_FLAG_PAIR;
    if (pair && !(g->config & CFG_PAIR)) {
        g->trace.reject[GBA_RX_SHAPE]++;
        return;
    }
    const bool multi=op>=GBA_CWL_READY16;
    if (op==GBA_CWL_GRANT16) {
        // Cache identifies the last captured pair of local/remote tokens.
        // FPGA repeats the local-anchor comparison atomically at commit.
        if ((g->config & CFG_GRANT) && !(g->config & CFG_PARENT) && p[5]==0 &&
            g->cached && g->cached_sync && g->request_token==tx && g->request_slave==rx) {
            if (g->grant_rx_anchor!=rx || word>g->grant_rx_cycles) {
                g->grant_rx_anchor=rx; g->grant_rx_cycles=word; g->grant_pending=true;
                g->grant_rx_us=s->received_us;
            }
        } else g->trace.reject[GBA_RX_TOKEN]++;
        return;
    }
    const unsigned ack=multi ? GBA_CWL_RESULT16 : GBA_CWL_ACK;
    if (multi && !(g->config & CFG_MULTI)) return;
    // The longer ROUND is only legal after both FPGA/firmware pairs negotiated it.
    if (op==GBA_CWL_ROUND16 && sync!=((g->config & CFG_SYNC)!=0)) {
        g->trace.reject[GBA_RX_SHAPE]++;
        return;
    }
    if ((op==GBA_CWL_READY || op==GBA_CWL_READY16) && p[5]==1 && !g->requesting) {
        // READY may lead the next local START, but old/reordered tokens must
        // never replace newer readiness or resurrect a completed transfer.
        const uint32_t mask=multi ? 0x0fffffffu : 0x1fffffffu;
        const uint32_t delta=(tx-g->remote_token)&mask;
        if (g->remote_token_known && multi==g->ready_multi &&
            ((!delta && !g->remote_ready) || delta>(mask>>1))) {
            g->trace.ready_stale++;
            g->trace.reject[GBA_RX_TOKEN]++;
            return;
        }
        if (!g->active) g->trace.ready_idle++;
        g->remote_token_known=true;
        g->remote_token=tx; g->remote_ready=true; g->ready_multi=multi;
        g->remote_ready_at=s->now;
        // A waiting parent can send its first round in the next poll.
        // The READY branch is inactive once a request has been sent.
        if (multi && g->active && g->master && g->multi)
            g->retry_at=s->now;
    } else if ((op==GBA_CWL_REQUEST || op==GBA_CWL_ROUND16) && p[5]==0) {
        if (g->cached && g->cached_multi==multi && g->cached_sync==sync &&
            g->request_token==tx && g->request_slave==rx && g->request_word==word &&
            (!sync || g->request_cycles==cycles)) {
            send_word(s,ack,tx,rx,g->cached_word);
        } else if (g->active && !g->master && g->multi==multi &&
                   rx==g->token && !g->awaiting_done) {
            split(s,GBA_SPLIT_CHILD_WAIT,g->started_us);
            g->request_us=now_us(s);
            hop(s,GBA_HOP_C_RXQ,s->received_us,g->request_us);
            g->cached=false; g->grant_pending=false;
            g->request_token=tx; g->request_slave=rx; g->request_word=word;
            g->request_cycles=cycles; g->sync=sync;
            g->trace.incoming=word;
            crit_begin(g);
            if (sync) {
                g->awaiting_done=command_cycles(s,rx,word,CMD_SYNC_CHILD,&cycles);
                g->cmd_done_us=now_us(s);
                hop(s,GBA_HOP_C_CMD,g->request_us,g->cmd_done_us);
                // Measurement: how far the child still is from the round cycle.
                // Taken after the command, so the round already runs; its
                // accesses are left out of the critical-path count.
                uint32_t ops=g->trace.spi_ops, us=g->trace.spi_us;
                GbLinkOp snap[2]={OPW(SNAPSHOT,1),OPR(SNAPSHOT_CYCLES)};
                if (g->awaiting_done && xfer(s,snap,2) && s->io.clock_us)
                    gba_split_add(&g->trace.split[GBA_SPLIT_CHILD_BEHIND],
                        snap[1].value<cycles ? (uint32_t)(((uint64_t)(cycles-snap[1].value)*1000u)/16777u) : 0);
                g->crit_ops0+=g->trace.spi_ops-ops; g->crit_us0+=g->trace.spi_us-us;
            } else if (multi && (g->config & CFG_PAIR)) {
                // GB-117: BEGIN samples SEND like the cable; read it and answer
                // now instead of after the emulated wire time (DONE).
                GbLinkOp begin[4]={OPW(COMMAND_DATA,0),OPW(COMMAND_ID,rx),
                                   OPW(COMMAND,CMD_BEGIN_CHILD),OPR(SAMPLED)};
                if (!xfer(s,begin,4)) { g->trace.command_error++; g->fault=true; return; }
                g->trace.command_ok++;
                const uint32_t mine=begin[3].value&0xffffu;
                // Both slots from the host: this round's pair, or the buffered one.
                g->awaiting_done=command(s,rx,pair ? word : (mine<<16)|(uint16_t)word,CMD_COMPLETE);
                g->cmd_done_us=now_us(s);
                hop(s,GBA_HOP_C_CMD,g->request_us,g->cmd_done_us);
                if (g->awaiting_done) {
                    g->cached=true; g->cached_multi=true; g->cached_sync=false;
                    g->cached_word=(mine<<16)|(uint16_t)word;
                    send_word(s,GBA_CWL_RESULT16,tx,rx,g->cached_word);
                    g->answered=true; g->trace.early++;
                    crit_end(g,&g->trace.crit_child_n,&g->trace.crit_child_ops,
                             &g->trace.crit_child_spi_us);
                    hop(s,GBA_HOP_C_SEND,g->cmd_done_us,now_us(s));
                }
            } else {
                if (!multi || command(s,rx,0,CMD_BEGIN_CHILD))
                    g->awaiting_done=command(s,rx,word,CMD_COMPLETE);
                g->cmd_done_us=now_us(s);
                if (multi) hop(s,GBA_HOP_C_CMD,g->request_us,g->cmd_done_us);
            }
        } else {
            g->trace.reject[GBA_RX_TOKEN]++;
        }
    } else if (op==ack && p[5]==1 && g->active && g->master &&
               g->multi==multi && tx==g->token && rx==g->request_slave && g->remote_ready &&
               (!multi || (uint16_t)word==(uint16_t)(g->pair_round ? g->round_pair : g->word))) {
        g->trace.incoming=word;
        const uint32_t handled_us=now_us(s);
        if (multi) {
            hop(s,GBA_HOP_P_RTT,g->request_us,s->received_us);
            hop(s,GBA_HOP_P_RXQ,s->received_us,handled_us);
        }
        // A buffered round was completed at START; this RESULT only brings the
        // child's word for the next round.
        if (g->pair_round || command(s,tx,word,CMD_COMPLETE)) {
            if (multi && (g->config & CFG_PAIR)) {
                g->prev_pair=(word&0xffff0000u)|(uint16_t)g->word; g->have_prev=true;
            }
            if (multi && !g->pair_round) {
                hop(s,GBA_HOP_P_DONE,handled_us,now_us(s));
                crit_end(g,&g->trace.crit_parent_n,&g->trace.crit_parent_ops,
                         &g->trace.crit_parent_spi_us);
            }
            split(s,GBA_SPLIT_REMOTE,g->request_us);
            uint32_t elapsed=s->now-g->started_at;
            g->trace.round_ms[elapsed<64 ? elapsed : 64]++;
            g->trace.round_n++;
            if (elapsed>g->trace.round_max_ms) g->trace.round_max_ms=elapsed;
            g->active=false; g->remote_ready=false; g->requesting=false;
            if (g->queued) {
                g->queued=false;
                round_start(s,g->q_event,g->q_id,g->q_word,g->q_cycles,true,s->now,false);
                return;
            }
            if (multi && g->sync && (g->config & CFG_GRANT)) {
                g->grant_parent=tx; g->grant_child=rx; g->grant_confirmed=true;
            }
        }
    } else {
        g->trace.reject[GBA_RX_TOKEN]++;
    }
}
void gba_cwl_poll(GbCwlLogic *s, uint32_t now)
{
    s->now=now;
    GbaCwl *g=&s->gba;
    bool wanted=s->core_running && s->caps_read && s->caps==GBA_CWL_CAPS &&
                gb_cwl_logic_connected(s,now);
    if (!wanted || g->fault) {
        stop(s,g->fault ? GBA_STOP_COMMAND : GBA_STOP_UNAVAILABLE);
        return;
    }
    uint32_t status = 0;
    if (!g->on) {
        uint32_t caps=0;
        g->multi_supported=rd(s,CONFIG,&caps) && (caps & (1u<<31));
        g->sync_supported=g->multi_supported && (caps & (1u<<30));
        g->grant_supported=g->sync_supported && (caps & (1u<<29));
        g->sampled_supported=g->multi_supported && (caps & (1u<<28));
        if (!wr(s,CONTROL,1) || !wr(s,CONFIG,1) || !rd(s,STATUS,&status)) {
            g->trace.config_error++;
            return;
        }
        g->on=true; g->generation=status>>16; g->config=CFG_ENABLE;
        g->epoch=((uint32_t)s->core.session<<8)|g->generation;
        g->trace.epoch=g->epoch;
        g->trace.remote_epoch=0;
        g->beacon_at=now;
    }
    // One STATUS read, then one batch per event: EVENT, DATA, [CYCLES], POP and
    // the post-POP STATUS, which is also the next iteration's STATUS (GB-114).
    // GB-116: the FPGA is read when its IRQ fired, or at least every 1 ms in
    // case an edge was coalesced, not on every wake-up (~2 000 STATUS reads/s).
    const uint32_t poll_us=now_us(s);
    const bool fpga_due=!s->io.clock_us || s->irq_us || (uint32_t)(poll_us-g->status_read_us)>=1000;
    if (fpga_due) g->status_read_us=poll_us;
    bool status_ok=fpga_due && rd(s,STATUS,&status);
    for (unsigned i=0;fpga_due && i<64;i++) {
        if (!status_ok || (status&512) || (uint8_t)(status>>16)!=g->generation) {
            g->trace.status=status;
            stop(s,GBA_STOP_STATUS);
            return;
        }
        g->trace.status=status;
        g->local_multi=(status & STATUS_MULTI)!=0;
        if (!g->local_multi) g->grant_confirmed=false;
        if (!(status&31)) break;
        GbLinkOp evt[5]={OPR(EVENT),OPR(DATA),OPR(EVENT_CYCLES),OPW(POP,1),OPR(STATUS)};
        const bool with_cycles=g->sync_supported;
        if (!with_cycles) { evt[2]=evt[3]; evt[3]=evt[4]; }
        const size_t n=with_cycles ? 5 : 4;
        // Any failed access in the batch stops cleanly; POP is never repeated.
        if (!xfer(s,evt,n)) { stop(s,GBA_STOP_FIFO); return; }
        uint32_t event=evt[0].value, word=evt[1].value, cycles=with_cycles ? evt[2].value : 0;
        status=evt[n-1].value;
        if ((status&512) || (uint8_t)(status>>16)!=g->generation) {
            g->trace.status=status;
            stop(s,GBA_STOP_STATUS);
            return;
        }
        // New bridge: opcode[31:30], parent[29], multi[28], token[27:0].
        bool multi=g->multi_supported && (event & (1u<<28))!=0;
        uint32_t id=event & (g->multi_supported ? 0x0fffffffu : 0x1fffffffu);
        uint32_t op=event>>30;
        if (op==GBA_CWL_DONE) g->trace.done++;
        if (op==GBA_CWL_CANCEL) g->trace.cancel++;
        if (op==GBA_CWL_START) {
            // Multi START data[17:16] carries the game's SIOCNT baud (measurement).
            if (multi) { g->trace.baud[(word>>16)&3]++; word&=0xffffu; }
            if ((event&(1u<<29)) && multi && g->active && g->master && g->pair_round) {
                // GB-117: the previous buffered exchange has not ended (lost RESULT).
                // Hold this START (the game waits, as without the buffer).
                g->queued=true; g->q_event=event; g->q_id=id; g->q_word=word; g->q_cycles=cycles;
                g->trace.buffer_wait++;
            } else round_start(s,event,id,word,cycles,multi,now,true);
        } else if (op==GBA_CWL_DONE && g->awaiting_done && id==g->request_slave) {
            const uint32_t done_us=now_us(s);
            if (multi && s->irq_us && (int32_t)(s->irq_us-g->cmd_done_us)>=0) {
                hop(s,GBA_HOP_C_RUN,g->cmd_done_us,s->irq_us);
                hop(s,GBA_HOP_C_DETECT,s->irq_us,done_us);
            }
            split(s,GBA_SPLIT_CHILD_RUN,g->request_us);
            g->active=false; g->awaiting_done=false;
            if (g->answered) {
                // GB-117: RESULT already left at BEGIN; DONE must carry the same word.
                g->answered=false;
                if ((uint16_t)word!=(uint16_t)(g->cached_word>>16)) g->trace.early_mismatch++;
            } else {
                g->cached=true; g->cached_multi=multi; g->cached_sync=g->sync;
                g->cached_word=multi ? (word<<16)|(uint16_t)g->request_word : word;
                send_word(s,multi ? GBA_CWL_RESULT16 : GBA_CWL_ACK,
                          g->request_token,g->request_slave,g->cached_word);
                if (multi) crit_end(g,&g->trace.crit_child_n,&g->trace.crit_child_ops,
                                    &g->trace.crit_child_spi_us);
                if (multi) hop(s,GBA_HOP_C_SEND,done_us,now_us(s));
            }
        } else if (op==GBA_CWL_CANCEL && id==g->token) {
            g->active=false; g->awaiting_done=false;
            g->grant_confirmed=false;
            // A failed synchronization cannot reuse a completed-round origin.
            if (g->config & CFG_GRANT) { g->fault=true; return; }
        }
    }
    if (fpga_due) s->irq_us=0; // this IRQ is handled; the next poll waits for a new one or 1 ms
    // A total order on the paired epochs assigns exactly one parent. If they
    // happen to match, wait for a new pairing rather than electing two parents.
    unsigned config=CFG_ENABLE;
    // GB-116: the parent asks for unsynced rounds once the games have paired;
    // the child follows the parent's request; each applies it between rounds,
    // the parent only after the child's echo.
    const bool parent_role=g->epoch<g->remote_epoch;
    const bool hold=s->paused || g->peer_paused;
    if (hold && g->active) g->started_at=now; // a paused game is not a lost peer
    if (parent_role) {
        if (hold) { g->unsync=false; g->slow_streak=0; }
        else if (!g->unsync && g->slow_streak>=UNSYNC_AFTER_ROUNDS) g->unsync=true;
        else if (g->unsync && g->fast_streak>=UNSYNC_FAST_BACK) g->unsync=false;
    } else g->unsync=g->peer_unsync && !hold;
    const bool unsynced=parent_role ? (g->unsync && g->peer_unsync) : g->unsync;
    // A child re-arms right after DONE (Mario Kart), so it is almost never
    // inactive: it switches while armed but before taking a ROUND. Dropping
    // CFG_SYNC there only releases the waiting guest (no FPGA timeout).
    if (unsynced!=g->unsynced && !g->awaiting_done && (!parent_role || !g->active)) {
        g->unsynced=unsynced; g->trace.unsynced=unsynced; g->trace.unsync_switches++;
    }
    if (g->multi_supported && g->peer_multi_supported && g->epoch!=g->remote_epoch) {
        config|=CFG_MULTI;
        if (g->sync_supported && g->peer_sync_supported && !g->unsynced) config|=CFG_SYNC;
        if ((config & CFG_SYNC) && g->grant_supported && g->peer_grant_supported) config|=CFG_GRANT;
        if (g->epoch<g->remote_epoch) config|=CFG_PARENT;
        if (g->unsynced && g->sampled_supported) config|=CFG_PAIR;
        if (g->peer_multi && (uint32_t)(now-g->peer_multi_at)<PEER_MODE_MAX_AGE_MS)
            config|=CFG_PEER_READY;
    }
    if (!(config & CFG_PAIR)) g->have_prev=false;
    if (config!=g->config) {
        if (!wr(s,CONFIG,config)) { g->fault=true; return; }
        g->config=config;
    }
    if (s->paused!=g->sent_paused) g->beacon_at=now; // tell the peer at once
    if ((int32_t)(now-g->beacon_at)>=0) {
        unsigned caps=HELLO_NORMAL32;
        if (g->multi_supported) caps|=HELLO_MULTI16;
        if (g->sync_supported) caps|=HELLO_SYNC16;
        if (g->grant_supported) caps|=HELLO_GRANT16;
        if (g->local_multi) caps|=HELLO_IN_MULTI16;
        // The child echoes only an applied switch, so the parent never sends
        // unsynced ROUNDs to a child that still requires the synced shape.
        if (parent_role ? g->unsync : g->unsynced) caps|=HELLO_UNSYNC16;
        if (g->sampled_supported) caps|=HELLO_PAIR16;
        if (s->paused) caps|=HELLO_PAUSED16;
        g->sent_paused=s->paused;
        send_word(s,GBA_CWL_HELLO,0,0,caps);
        g->beacon_at=now+100;
    }
    if (g->active && (!g->multi || g->master) && (uint32_t)(now-g->started_at)>=1500) {
        g->grant_confirmed=false;
        g->trace.timeout++;
        // Measurement: what the parent was waiting for when it gave up.
        g->trace.to_n++; g->trace.to_requesting=g->requesting; g->trace.to_ready=g->remote_ready;
        g->trace.to_ready_age_ms=now-g->remote_ready_at; g->trace.to_round_tx=g->trace.tx[GBA_CWL_ROUND16];
        command(s,g->token,UINT32_MAX,g->multi ? CMD_ERROR : CMD_COMPLETE);
        g->active=false; g->awaiting_done=false; g->queued=false;
        // GB-116: an unsynced timeout restarts the link, which pairs synced again.
        if ((g->config & CFG_GRANT) || g->unsync || g->unsynced) { g->fault=true; return; }
        if (g->master) {
            g->requesting=false;
            g->remote_ready=false;
        }
    }
    if (g->active && g->remote_epoch && (int32_t)(now-g->retry_at)>=0) {
        // A request already sent must keep its token so a lost ACK can be
        // recovered from the slave cache without delivering a second word.
        bool ready = g->remote_ready && g->ready_multi==g->multi &&
                     (g->requesting || (uint32_t)(now-g->remote_ready_at)<READY_MAX_AGE_MS);
        if (g->master && ready) {
            if (!g->requesting) {
                g->request_slave=g->remote_token;
                split(s,GBA_SPLIT_WAIT,g->started_us);
                g->request_us=now_us(s);
            }
            g->requesting=true;
            send_word(s,g->multi ? GBA_CWL_ROUND16 : GBA_CWL_REQUEST,
                      g->token,g->request_slave,g->word);
        } else if (!g->master) {
            send_word(s,g->multi ? GBA_CWL_READY16 : GBA_CWL_READY,g->token,0,g->word);
        }
        g->retry_at=now+20;
    }
    // Apply a received GRANT in the same pass: every millisecond of delay here
    // is emulation time the child spends stalled before the next ROUND.
    if (g->grant_pending) {
        g->grant_pending=false; g->grant_applied_at=now;
        if ((g->config & CFG_GRANT) && g->cached && g->local_multi) {
            GbLinkOp grant[3]={OPW(COMMAND_ANCHOR,g->grant_rx_anchor),
                               OPW(COMMAND_CYCLES,g->grant_rx_cycles),OPW(COMMAND,CMD_GRANT)};
            bool ok=xfer(s,grant,3);
            if (ok) hop(s,GBA_HOP_C_GRANT,g->grant_rx_us,now_us(s));
            if (ok) g->trace.command_ok++;
            else { g->trace.command_error++; g->fault=true; return; }
        }
    }
    if ((g->config & (CFG_GRANT|CFG_PARENT))==(CFG_GRANT|CFG_PARENT) &&
        g->local_multi && g->grant_confirmed && !g->active &&
        (s->io.clock_us ? (uint32_t)(now_us(s)-g->grant_at_us)>=GRANT_PERIOD_US
                        : (uint32_t)(now-g->grant_at)>=1)) {
        g->grant_at=now; g->grant_at_us=now_us(s);
        GbLinkOp snap[3]={OPW(SNAPSHOT,1),OPR(SNAPSHOT),OPR(SNAPSHOT_CYCLES)};
        if (!xfer(s,snap,3)) {
            g->fault=true;
            return;
        }
        const uint32_t meta=snap[1].value, progress=snap[2].value;
        // A successful RESULT is necessary but not sufficient: local IRQ/end
        // must also have committed and the parent must still be between rounds.
        if ((meta & 0xc0000000u)==0xc0000000u &&
            (meta & 0x0fffffffu)==g->grant_parent)
            send_word(s,GBA_CWL_GRANT16,g->grant_parent,g->grant_child,progress);
    }

}
