// SPDX-License-Identifier: GPL-3.0-only
#pragma once
#include <stdint.h>
#include <stdbool.h>
// Calls must be serialized by the owner (Rust link module).
enum { GB_CWL_OFF, GB_CWL_SEARCHING, GB_CWL_CONNECTED };
typedef struct {
    uint32_t state, channel, rx, tx, rtt_ms;
    uint32_t rx_drop, tx_fail, malformed, pongs;
    char partner[16];
} gamebub_cwl_status_t;
// Cached Rust core probe; retained across radio startup and copied under mux.
void gamebub_cwl_set_core(bool running, bool caps_read, uint32_t caps);
// The emulator is paused (menu open). A GBA link returns to cycle sync so the
// peer game waits instead of declaring the partner lost (GB-117).
void gamebub_cwl_set_paused(bool paused);
void gamebub_cwl_notify_bridge(void);
void gamebub_cwl_set_diagnostics(bool enabled);
// Must be set while stopped; only the dedicated link-bench image enables this.
void gamebub_cwl_set_benchmark(bool enabled);
// GB/GBC game traffic to the connected partner at 12 Mbps instead of 1 Mbps
// (user setting, off by default). Only our transmit rate changes.
void gamebub_cwl_set_fast_gb(bool enabled);
// GB-147: two GB cores per console with frame-locked buttons instead of the
// byte bridge. Set before the core runs; rom_id and save_crc must match the
// partner's. The caller has written the FPGA CONTROL register (enable, delay).
// gba (GB-156): the running core is the GBA one (ten buttons).
// title (GB-162): what the partner's screen calls this game; at most 24 characters are used.
void gamebub_cwl_set_sync(bool enabled, bool gba, uint32_t delay, uint32_t rom_id, uint32_t save_crc,
                          const char *title);
// GB-162: the player chose to play with the partner the waiting room shows.
void gamebub_cwl_sync_accept(void);
// The save in both cores was replaced while the waiting room is shown: its new id.
void gamebub_cwl_sync_set_save(uint32_t save_crc);
// GB-162: the two-core link session, for the waiting room. phase is a GbSyncPhase and why a
// GbSyncWhy (gamebub_cwl_sync.h); all zero while no such game is armed or the radio is off.
// player is 1 or 2 once the game runs. partner_title is the game the partner is in, if known.
typedef struct {
    uint32_t phase, why, player, frames, partner_accepted;
    char partner_title[25];
} gamebub_cwl_session_t;
void gamebub_cwl_session(gamebub_cwl_session_t *out);
int32_t gamebub_cwl_start(void);
int32_t gamebub_cwl_stop(void);
void gamebub_cwl_status(gamebub_cwl_status_t *out);

// Read ESP-IDF panic metadata without duplicating its private struct layout in Rust.
const char *gamebub_crash_reason(const void *info);

// Page states: Off, Searching, Connected, Partner lost, Not enough memory.
uint32_t gamebub_cwl_page_state(uint32_t state, uint32_t connected_before, uint32_t no_memory);

// Shared pure benchmark protocol/window code, excluded from generated bindings.
#ifdef GAMEBUB_CWL_BENCH_INTERNAL
#include <stddef.h>
#include <string.h>
enum { CB_HELLO=1, CB_START, CB_ACK, CB_STOP, CB_REQUEST, CB_REPLY, CB_LOAD,
       CB_WINDOW=64, CB_STAGES=24, CB_TIMEOUT_US=100000, CB_BINS=350 };
typedef struct { uint8_t op,stage,flags; uint32_t session,seq,sent,queue,service; } CbPacket;
static inline uint32_t cb_get(const uint8_t *p) {
    return (uint32_t)p[0]|(uint32_t)p[1]<<8|(uint32_t)p[2]<<16|(uint32_t)p[3]<<24;
}
static inline void cb_put(uint8_t *p,uint32_t v) {
    for(unsigned i=0;i<4;i++) p[i]=(uint8_t)(v>>(8*i));
}
static inline size_t cb_pack(uint8_t p[31],const CbPacket *v) {
    memset(p,0,31);p[0]='G';p[1]='B';p[2]='B';p[3]=2;
    p[4]=v->op;p[5]=v->stage;p[6]=v->flags;
    cb_put(p+7,v->session);cb_put(p+11,v->seq);cb_put(p+15,v->sent);
    cb_put(p+19,v->queue);cb_put(p+23,v->service);
    return v->op==CB_REPLY ? 27 : 31;
}
static inline bool cb_unpack(CbPacket *v,const uint8_t *p,size_t n) {
    if(n<7 || p[0]!='G'||p[1]!='B'||p[2]!='B'||p[3]!=2 ||
       p[4]<CB_HELLO||p[4]>CB_LOAD ||p[5]>=CB_STAGES ||p[6]) return false;
    if(n!=(p[4]==CB_REPLY?27u:31u)) return false;
    if(n==31 && (p[27]||p[28]||p[29]||p[30])) return false;
    *v=(CbPacket){p[4],p[5],p[6],cb_get(p+7),cb_get(p+11),cb_get(p+15),cb_get(p+19),cb_get(p+23)};
    return v->session!=0;
}
// Bounded owner-task FIFO with stable priority: control, reply, request, load.
// Four entries remain available to control/reply when background traffic fills it.
enum { CB_TX_CAP=16 };
typedef struct { uint8_t mac[6],data[250],len,priority; uint32_t queued; } CbTx;
typedef struct { CbTx items[CB_TX_CAP]; unsigned n,high,rejected,stale; } CbTxQueue;
static inline bool cb_tx_push(CbTxQueue *q,const CbTx *v) {
    if(q->n>=CB_TX_CAP || (v->priority>=2 && q->n>=CB_TX_CAP-4)) {
        q->rejected++;return false;
    }
    q->items[q->n++]=*v;if(q->n>q->high)q->high=q->n;return true;
}
static inline bool cb_tx_pop(CbTxQueue *q,CbTx *v) {
    if(!q->n)return false;
    unsigned best=0;
    for(unsigned i=1;i<q->n;i++)if(q->items[i].priority<q->items[best].priority)best=i;
    *v=q->items[best];
    memmove(&q->items[best],&q->items[best+1],(--q->n-best)*sizeof(*v));
    return true;
}
static inline unsigned cb_phy_mbps(unsigned stage) {
    static const unsigned mbps[6]={1,1,6,6,12,12};return mbps[stage%6];
}
static inline bool cb_paced(unsigned stage) {return (stage%6)%2!=0;}
static inline unsigned cb_rate(unsigned stage) {
    static const unsigned rates[4]={100,100,500,1000};return rates[stage/6];
}
typedef struct { uint32_t seq,sent,last; uint8_t attempts; bool active; } CbSlot;
static inline CbSlot *cb_reserve(CbSlot slots[CB_WINDOW],uint32_t seq,uint32_t now) {
    CbSlot *p=&slots[seq%CB_WINDOW];if(p->active) return NULL;
    *p=(CbSlot){.seq=seq,.sent=now,.last=now,.attempts=1,.active=true};return p;
}
static inline bool cb_expired(const CbSlot *p,uint32_t now) {
    return p->active && (uint32_t)(now-p->sent)>=CB_TIMEOUT_US;
}
static inline bool cb_retry(const CbSlot *p,uint32_t now,uint32_t interval) {
    return p->active&&!cb_expired(p,now)&&p->attempts<4&&(uint32_t)(now-p->last)>=interval;
}
static inline bool cb_complete(CbSlot slots[CB_WINDOW],const CbPacket *p,uint32_t now,uint32_t *rtt) {
    CbSlot *s=&slots[p->seq%CB_WINDOW];
    if(!s->active||s->seq!=p->seq||s->sent!=p->sent||cb_expired(s,now)) return false;
    *rtt=now-s->sent;s->active=false;return true;
}
typedef struct { uint32_t n,min,max,bins[CB_BINS]; uint64_t sum; } CbHist;
static inline unsigned cb_bucket(uint32_t us) {
    if(us<=8192) return (us+31)/32;
    uint64_t b=256+((uint64_t)us-8192+999)/1000;return b<CB_BINS-1?b:CB_BINS-1;
}
static inline uint32_t cb_upper(unsigned b) {
    return b<=256?b*32:b==CB_BINS-1?UINT32_MAX:8192+(b-256)*1000;
}
static inline void cb_sample(CbHist *h,uint32_t us) {
    if(!h->n||us<h->min) h->min=us;
    if(us>h->max) h->max=us;
    h->n++;h->sum+=us;h->bins[cb_bucket(us)]++;
}
static inline uint32_t cb_quantile(const CbHist *h,unsigned percent) {
    if(!h->n)return 0;
    uint64_t rank=((uint64_t)h->n*percent+99)/100,count=0;
    for(unsigned i=0;i<CB_BINS;i++){count+=h->bins[i];if(count>=rank)return cb_upper(i);}
    return h->max;
}
#endif
