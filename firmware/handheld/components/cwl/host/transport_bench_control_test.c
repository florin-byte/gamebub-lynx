// SPDX-License-Identifier: GPL-3.0-only
// Compile the actual owner-state machine with deterministic radio/clock stubs.
#define GAMEBUB_CWL_BENCH_INTERNAL
#include "../gamebub_cwl.h"
#include "../gamebub_cwl_logic.h"
#include <assert.h>
#include <stdio.h>
typedef int esp_err_t;
typedef void *esp_timer_handle_t;
#define ESP_OK 0
typedef int esp_now_send_status_t;
enum {ESP_NOW_SEND_SUCCESS,ESP_NOW_SEND_FAIL,WIFI_PHY_MODE_11B,WIFI_PHY_MODE_11G,
      WIFI_PHY_RATE_1M_L,WIFI_PHY_RATE_6M,WIFI_PHY_RATE_12M};
typedef struct {int phymode,rate;bool ersu,dcm;} esp_now_rate_config_t;
static bool auto_complete=true;
static int send_error,rate_error,configured_rate,rate_calls;
static void cb_sent(const uint8_t *,esp_now_send_status_t);
static int esp_now_set_peer_rate_config(const uint8_t *mac,esp_now_rate_config_t *rate){
    (void)mac;rate_calls++;configured_rate=rate->rate;return rate_error;
}
#define ESP_LOGI(tag,...) do { if(0) printf(__VA_ARGS__); } while(0)
#define ESP_LOGE ESP_LOGI
#define ESP_LOGW ESP_LOGI
static uint32_t tick,drops;
static bool connected=true;
static unsigned queued,sends,timer_stops;
static CbPacket last_sent;
static void *rx_queue;
typedef GbCwlLogic FakeLogic;
static FakeLogic instance,*logic=&instance;
typedef struct { uint8_t src[6],len;bool unicast;int8_t rssi;uint8_t data[250];int64_t queued_us; } Rx;
static int64_t esp_timer_get_time(void){return tick;}
static uint32_t now_ms(void){return tick/1000;}
void gamebub_cwl_notify_bridge(void){}
static int esp_timer_stop(void *timer){(void)timer;timer_stops++;return 0;}
bool gb_cwl_logic_connected(const GbCwlLogic *s,uint32_t now){(void)s;(void)now;return connected;}
static unsigned uxQueueMessagesWaiting(void *queue){(void)queue;return queued;}
static int add_peer(const uint8_t *mac){(void)mac;return 0;}
static int esp_now_send(const uint8_t *mac,const uint8_t *data,size_t n){
    (void)mac;assert(cb_unpack(&last_sent,data,n));sends++;
    if(send_error)return send_error;
    if(auto_complete)cb_sent(mac,ESP_NOW_SEND_SUCCESS);
    return 0;
}
#include "transport_bench_runtime.inc"
static CbState state;
static void setup(bool leader){
    memset(&state,0,sizeof(state));bench=&state;bench_timer=(void*)1;
    tick=1000;drops=queued=sends=timer_stops=0;connected=true;
    cb_pending=cb_done_ok=cb_done_fail=cb_stray=cb_progress=0;
    auto_complete=true;send_error=rate_error=0;
    state.session=11;state.remote=22;state.peer_ack=true;state.peer_bound=true;
    state.leader=leader;state.bound_at=tick;memset(logic->peer.mac,0,6);
}
static void deliver(unsigned op,unsigned stage,unsigned seq){
    CbPacket p={.op=op,.stage=stage,.session=22,.seq=seq};
    Rx r={.unicast=true,.queued_us=tick};r.len=cb_pack(r.data,&p);cb_receive(&r);cb_pump();
}
int main(void){
    setup(true);cb_poll();assert(state.waiting&&last_sent.op==CB_START);
    tick+=100000;cb_poll();assert(state.waiting&&last_sent.op==CB_START);
    deliver(CB_ACK,0,1);assert(state.waiting&&!state.running);
    deliver(CB_ACK,0,0);assert(state.running&&!state.waiting);
    tick+=2100000;cb_poll();assert(state.stopping&&!state.running&&last_sent.op==CB_STOP);
    tick+=100000;cb_poll();assert(state.stopping&&last_sent.op==CB_STOP);
    deliver(CB_ACK,0,0);assert(state.stopping);
    deliver(CB_ACK,0,1);assert(!state.stopping&&state.expected==1);
    tick+=500000;cb_poll();assert(state.waiting&&state.stage==1);
    setup(false);deliver(CB_START,1,0);assert(!state.running);
    deliver(CB_START,0,0);assert(state.running&&last_sent.op==CB_ACK);
    uint32_t start=state.started;tick+=100000;
    deliver(CB_START,0,0);assert(state.started==start); // lost ACK must not restart test
    deliver(CB_STOP,0,0);assert(!state.running&&state.expected==1&&last_sent.seq==1);
    deliver(CB_STOP,0,0);assert(state.expected==1&&last_sent.op==CB_ACK);
    deliver(CB_START,0,0);assert(!state.running); // reordered old START
    deliver(CB_START,1,0);assert(state.running&&state.stage==1);
    setup(true);cb_begin(0);queued=12;cb_poll();assert(state.halted&&!state.running);
    cb_poll();assert(timer_stops==1);
    setup(false);tick+=120000000;cb_poll();assert(state.halted);
    setup(false);state.expected=23;deliver(CB_START,23,0);deliver(CB_STOP,23,0);
    assert(state.finished_at&&!state.halted);
    tick+=100000;deliver(CB_STOP,23,0);assert(last_sent.op==CB_ACK);
    tick+=3000000;cb_poll();assert(state.halted);
    setup(true);cb_begin(0);connected=false;cb_poll();assert(state.halted);
    setup(true);assert(cb_begin(3));assert(configured_rate==WIFI_PHY_RATE_6M&&state.paced);
    auto_complete=false;
    CbPacket packet={.op=CB_LOAD,.stage=3,.session=11};assert(cb_send_packet(&packet));
    cb_pump();assert(cb_pending==1&&last_sent.op==CB_LOAD);
    packet.op=CB_LOAD;assert(cb_send_packet(&packet));
    packet.op=CB_REPLY;assert(cb_send_packet(&packet));
    packet.op=CB_ACK;assert(cb_send_packet(&packet));
    unsigned before=sends;cb_pump();assert(sends==before); // only one outstanding
    cb_sent(state.peer_mac,ESP_NOW_SEND_SUCCESS);cb_pump();assert(last_sent.op==CB_ACK&&cb_pending==1);
    cb_sent(state.peer_mac,ESP_NOW_SEND_FAIL);cb_pump();assert(last_sent.op==CB_REPLY&&cb_done_fail==1);
    cb_sent(state.peer_mac,ESP_NOW_SEND_SUCCESS);cb_pump();assert(last_sent.op==CB_LOAD);
    cb_sent(state.peer_mac,ESP_NOW_SEND_SUCCESS);
    packet.op=CB_REQUEST;assert(cb_send_packet(&packet));tick+=100001;cb_pump();
    assert(state.txq.stale==1&&cb_pending==0);
    send_error=0x3067;packet.op=CB_LOAD;assert(cb_send_packet(&packet));cb_pump();
    assert(state.tx_error==1&&state.api_error[0]==0x3067&&cb_pending==0);
    send_error=0;assert(cb_send_packet(&packet));cb_pump();tick+=500001;cb_poll();
    assert(state.halted); // missing callback cannot permanently stall the owner
    setup(true);rate_error=123;assert(!cb_begin(4)&&state.halted);
    setup(true);assert(cb_begin(4));assert(configured_rate==WIFI_PHY_RATE_12M&&!state.paced);
    auto_complete=false;assert(cb_send_packet(&packet));assert(cb_send_packet(&packet));assert(cb_pending==2);
    assert(!cb_begin(5)); // no PHY/mode transition while old driver packets remain
    cb_sent(state.peer_mac,ESP_NOW_SEND_SUCCESS);cb_sent(state.peer_mac,ESP_NOW_SEND_SUCCESS);
    assert(cb_begin(5));
    // Real game policy: only a connected current-session GBA peer upgrades.
    setup(true);memset(logic,0,sizeof(*logic));game_radio_forget();rate_calls=0;
    uint8_t peer[6]={1,2,3,4,5,6};memcpy(logic->peer.mac,peer,6);
    assert(game_radio_sync(logic,peer)&&rate_calls==0);
    logic->core_running=true;logic->caps_read=true;logic->caps=GBA_CWL_CAPS;
    logic->gba.on=true;logic->core.peer_session_known=true;logic->core.peer_session=42;
    logic->gba.remote_epoch=41<<8;assert(game_radio_sync(logic,peer)&&rate_calls==0);
    logic->gba.remote_epoch=42<<8;
    assert(game_radio_sync(logic,peer)&&game_radio.applied==12&&rate_calls==1);
    assert(game_radio_sync(logic,peer)&&rate_calls==1); // no per-packet reconfiguration
    logic->caps=0x574c021f;assert(game_radio_sync(logic,peer)&&game_radio.applied==1);
    logic->caps=GBA_CWL_CAPS;
    rate_error=123;int prior=rate_calls;
    assert(game_radio_sync(logic,peer)&&game_radio.applied==1&&rate_calls==prior+1);
    assert(game_radio_sync(logic,peer)&&rate_calls==prior+1); // throttled safe fallback
    tick+=1000000;rate_error=0;assert(game_radio_sync(logic,peer)&&game_radio.applied==12);
    logic->core_running=false;rate_error=123;
    assert(!game_radio_sync(logic,peer)&&game_radio.applied==12); // block unsafe downgrade failure
    prior=rate_calls;assert(!game_radio_sync(logic,peer)&&rate_calls==prior);
    tick+=1000000;rate_error=0;assert(game_radio_sync(logic,peer)&&game_radio.applied==1);
    logic->core_running=true;assert(game_radio_sync(logic,peer)&&game_radio.applied==12);
    connected=false;assert(game_radio_sync(logic,peer)&&game_radio.applied==1);
    connected=true;logic->gba.fault=true;assert(game_radio_sync(logic,peer)&&game_radio.applied==1);
    logic->gba.fault=false;assert(game_radio_sync(logic,peer)&&game_radio.applied==12);
    uint8_t other[6]={6,5,4,3,2,1}; // newly registered different peer starts at SDK default
    assert(game_radio_sync(logic,other)&&game_radio.applied==1);
    memcpy(logic->peer.mac,other,6);logic->gba.remote_epoch=0;
    assert(game_radio_sync(logic,other)&&game_radio.applied==1);
    game_radio_forget();assert(!game_radio.valid);
    // GB/GBC opt-in: only with the setting, a healthy bridge and the connected partner.
    memset(logic,0,sizeof(*logic));memcpy(logic->peer.mac,peer,6);
    logic->core_running=true;logic->caps_read=true;logic->caps=0x574c021f;logic->bridge_on=true;
    assert(game_radio_sync(logic,peer)&&game_radio.applied==1); // setting off: unchanged
    gamebub_cwl_set_fast_gb(true);
    assert(game_radio_sync(logic,peer)&&game_radio.applied==12&&configured_rate==WIFI_PHY_RATE_12M);
    logic->bridge_fault=true;assert(game_radio_sync(logic,peer)&&game_radio.applied==1);
    logic->bridge_fault=false;logic->bridge_on=false;assert(game_radio_sync(logic,peer)&&game_radio.applied==1);
    logic->bridge_on=true;assert(game_radio_sync(logic,peer)&&game_radio.applied==12);
    connected=false;assert(game_radio_sync(logic,peer)&&game_radio.applied==1);
    connected=true;assert(game_radio_sync(logic,other)&&game_radio.applied==1); // not the partner
    assert(game_radio_sync(logic,peer)&&game_radio.applied==12);
    gamebub_cwl_set_fast_gb(false);assert(game_radio_sync(logic,peer)&&game_radio.applied==1);
    game_radio_forget();
    cb_tick(NULL);
    puts("game radio 12Mbps policy: eligibility/session/peer reset, GB restore, error fallback and retry PASS");
    puts("transport bench actual control: lost START/STOP ACK, reorder, bounds, queue and disconnect PASS");
}
