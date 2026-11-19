// SPDX-License-Identifier: GPL-3.0-only
// ESP-IDF 5.4.3 owner of an unassociated STA radio. No network credentials.
#define GAMEBUB_CWL_BENCH_INTERNAL
#include "gamebub_cwl.h"
#include "gamebub_cwl_logic.h"
#include "gamebub_cwl_peers.h"
#include "gamebub_cwl_sync.h"
#include "esp_event.h"
#include "esp_freertos_hooks.h"
#include "esp_heap_caps.h"
#include "esp_log.h"
#include "esp_now.h"
#include "esp_random.h"
#include "esp_timer.h"
#include "esp_wifi.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/task.h"
#include <string.h>
#include <stdio.h>
#include <stdlib.h>

static const char *TAG = "link";
static bool diagnostics;
// GB-162: the pause history as it was when the pause was noticed, printed a few entries a second.
static struct { uint32_t at_us, value, last_us, repeats; char kind; } trace_dump[GB_CWL_TRACE_CAPACITY];
static unsigned trace_dump_n, trace_dump_at;
static uint32_t trace_dump_ms;
static bool trace_selftest;
static bool benchmark;
void gamebub_cwl_set_benchmark(bool enabled) { benchmark=enabled; }
// Owner-task-only, fixed storage; no allocation or text output per transfer.
typedef struct { uint32_t n, max, bins[33]; } PerfSample;
static PerfSample spi_us, queue_us, service_us;
static uint32_t rx_serviced, rx_backlog, rx_highwater;
static void sample(PerfSample *s, uint64_t duration)
{
    uint32_t v=duration>UINT32_MAX ? UINT32_MAX : (uint32_t)duration;
    unsigned b=0; for(uint32_t x=v;x;x>>=1) b++;
    s->bins[b]++; s->n++; if(v>s->max) s->max=v;
}
static uint32_t upper_percentile(const PerfSample *s,unsigned percent)
{
    if(!s->n) return 0;
    uint64_t rank=((uint64_t)s->n*percent+99)/100;
    uint32_t count=0;
    for(unsigned i=0;i<33;i++) {
        count+=s->bins[i];
        if(count>=rank) return i==32 ? UINT32_MAX : (uint32_t)((1ull<<i)-1);
    }
    return s->max;
}
void gamebub_cwl_set_diagnostics(bool enabled) { diagnostics = enabled; }
static void memory_step(const char *point)
{
    if (!diagnostics) return;
    uint32_t caps = MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT;
    ESP_LOGI(TAG, "mem: %s free=%u min=%u largest=%u", point,
             (unsigned)heap_caps_get_free_size(caps),
             (unsigned)heap_caps_get_minimum_free_size(caps),
             (unsigned)heap_caps_get_largest_free_block(caps));
}
// IDF runs these hooks after reclaiming self-deleted tasks. No allocation or
// blocking operation is allowed here; the owner waits with its stack asleep.
static unsigned idle_passes[CONFIG_FREERTOS_NUMBER_OF_CORES];
static bool idle_seen(void)
{
    __atomic_fetch_add(&idle_passes[xPortGetCoreID()], 1, __ATOMIC_RELEASE);
    return true;
}
static esp_err_t await_idle_cleanup(void)
{
    for (unsigned cpu = 0; cpu < CONFIG_FREERTOS_NUMBER_OF_CORES; cpu++)
        __atomic_store_n(&idle_passes[cpu], 0, __ATOMIC_RELEASE);
    esp_err_t err = ESP_OK;
    unsigned registered = 0;
    for (; registered < CONFIG_FREERTOS_NUMBER_OF_CORES; registered++) {
        err = esp_register_freertos_idle_hook_for_cpu(idle_seen, registered);
        if (err != ESP_OK) break;
    }
    TickType_t start = xTaskGetTickCount();
    while (err == ESP_OK) {
        bool complete = true;
        // Two passes also cover a hook invocation already in flight when the
        // previous session deregistered it.
        for (unsigned cpu = 0; cpu < CONFIG_FREERTOS_NUMBER_OF_CORES; cpu++)
            if (__atomic_load_n(&idle_passes[cpu], __ATOMIC_ACQUIRE) < 2) complete = false;
        if (complete) break;
        if (xTaskGetTickCount() - start >= pdMS_TO_TICKS(1000)) {
            err = ESP_ERR_TIMEOUT;
            break;
        }
        vTaskDelay(1);
    }
    for (unsigned cpu = 0; cpu < registered; cpu++)
        esp_deregister_freertos_idle_hook_for_cpu(idle_seen, cpu);
    if (err != ESP_OK) ESP_LOGE(TAG, "idle cleanup: %s", esp_err_to_name(err));
    return err;
}
typedef struct {
    uint32_t at_us;
    uint8_t src[6], len;
    bool unicast;
    int8_t rssi;
    uint8_t data[ESP_NOW_MAX_DATA_LEN];
    int64_t queued_us;
} Rx;
// The callback and teardown serialize queue access with mux.
// Session buffers are allocated only while the radio is on.
static QueueHandle_t rx_queue;
static TaskHandle_t task;
static portMUX_TYPE mux = portMUX_INITIALIZER_UNLOCKED;
static gamebub_cwl_status_t snapshot;
static uint32_t drops, failures, irq_us;
static GbCwlLogic *logic;
// Configuration and candidate snapshots cross tasks only under mux. The radio
// task alone mutates logic; no SPI work or allocation occurs in the critical section.
static CwlLxCands_t candidate_snapshot;
static uint8_t fixed_mac[6];
static bool fixed_set, partner_pending;
static bool core_running, core_caps_read, core_paused;
static uint32_t core_caps;
void gamebub_cwl_set_core(bool running, bool caps_read, uint32_t caps)
{
    portENTER_CRITICAL(&mux);
    core_running = running;
    core_caps_read = caps_read;
    core_caps = caps;
    portEXIT_CRITICAL(&mux);
}
void gamebub_cwl_set_paused(bool paused)
{
    portENTER_CRITICAL(&mux);
    core_paused = paused;
    portEXIT_CRITICAL(&mux);
}
static char own_name[16], remembered_name[16];
void gamebub_cwl_set_partner(const uint8_t *mac, const char *name)
{
    portENTER_CRITICAL(&mux);
    fixed_set = mac != NULL;
    if (mac) memcpy(fixed_mac, mac, 6);
    snprintf(remembered_name, sizeof(remembered_name), "%s", name ? name : "");
    partner_pending = true;
    portEXIT_CRITICAL(&mux);
}
void gamebub_cwl_set_name(const char *name)
{
    portENTER_CRITICAL(&mux);
    snprintf(own_name, sizeof(own_name), "%s", name);
    portEXIT_CRITICAL(&mux);
}
static void apply_configuration(void)
{
    uint8_t mac[6];
    char name[16];
    bool fixed, pending;
    portENTER_CRITICAL(&mux);
    logic->core_running = core_running && !benchmark;
    logic->caps_read = core_caps_read;
    logic->caps = core_caps;
    logic->paused = core_paused;
    fixed = fixed_set;
    pending = partner_pending;
    partner_pending = false;
    memcpy(mac, fixed_mac, 6);
    memcpy(name, own_name, sizeof(name));
    portEXIT_CRITICAL(&mux);
    if (pending) gb_cwl_apply_partner(logic, fixed ? mac : NULL);
    if (name[0]) memcpy(logic->name, name, sizeof(name));
}
static bool own_event_loop;
static bool wifi_initialized, wifi_started, now_started;
static uint8_t last_peer[6];
static bool have_peer;
extern bool gamebub_link_event_pop(uint32_t *);
extern bool gamebub_link_read(uint32_t, uint32_t *);
extern bool gamebub_link_write(uint32_t, uint32_t);
static bool bridge_read(void *ctx, uint32_t a, uint32_t *v)
{
    (void)ctx;
    int64_t start=diagnostics ? esp_timer_get_time() : 0;
    bool ok=gamebub_link_read(a,v);
    if(start) sample(&spi_us,esp_timer_get_time()-start);
    return ok;
}
static bool bridge_write(void *ctx, uint32_t a, uint32_t v)
{
    (void)ctx;
    int64_t start=diagnostics ? esp_timer_get_time() : 0;
    bool ok=gamebub_link_write(a,v);
    if(start) sample(&spi_us,esp_timer_get_time()-start);
    return ok;
}
static bool bridge_event_pop(void *ctx, uint32_t *v)
{ (void)ctx; return gamebub_link_event_pop(v); }
extern size_t gamebub_link_batch(GbLinkOp *, size_t);
static size_t bridge_batch(void *ctx, GbLinkOp *ops, size_t n)
{
    (void)ctx;
    int64_t start=diagnostics ? esp_timer_get_time() : 0;
    size_t done=gamebub_link_batch(ops,n);
    if(start) sample(&spi_us,esp_timer_get_time()-start); // one sample per batch
    return done;
}
static uint32_t clock_us(void *ctx)
{ (void)ctx; return (uint32_t)esp_timer_get_time(); }
void gamebub_cwl_notify_bridge(void)
{
    portENTER_CRITICAL(&mux);
    if (task) {
        if (!irq_us) irq_us = (uint32_t)esp_timer_get_time();
        xTaskNotify(task, 2, eSetBits);
    }
    portEXIT_CRITICAL(&mux);
}
static uint32_t now_ms(void) { return (uint32_t)(esp_timer_get_time() / 1000); }

static void receive(const esp_now_recv_info_t *info, const uint8_t *data, int len)
{
    if (!info || !data || len <= 0 || len > ESP_NOW_MAX_DATA_LEN) return;
    int64_t queued_us = esp_timer_get_time();
    Rx r = {.at_us = (uint32_t)queued_us, .len = (uint8_t)len, .queued_us = queued_us};
    memcpy(r.src, info->src_addr, 6);
    r.unicast = info->des_addr && !CwlLx_MacIsBroadcast(info->des_addr);
    r.rssi = info->rx_ctrl ? info->rx_ctrl->rssi : 0;
    memcpy(r.data, data, len);
    portENTER_CRITICAL(&mux);
    if (rx_queue && xQueueSend(rx_queue, &r, 0) != pdTRUE) drops++;
    if (task) xTaskNotify(task, 2, eSetBits);
    portEXIT_CRITICAL(&mux);
}
static esp_err_t add_peer(const uint8_t *mac)
{
    if (esp_now_is_peer_exist(mac)) return ESP_OK;
    esp_now_peer_info_t p = {.channel = 0, .ifidx = WIFI_IF_STA, .encrypt = false};
    memcpy(p.peer_addr, mac, 6);
    return esp_now_add_peer(&p);
}
static void game_radio_forget(void);
static bool game_radio_sync(const GbCwlLogic *s,const uint8_t mac[6]);
static bool cb_io(const uint8_t *dest,const uint8_t *data,size_t n,unsigned priority);
static void send_frame(void *ctx, const uint8_t dest[6], const uint8_t *data, size_t n)
{
    (void)ctx;
    if (!CwlLx_MacIsBroadcast(dest) && (!have_peer || memcmp(last_peer, dest, 6))) {
        if (have_peer) esp_now_del_peer(last_peer);
        have_peer = false;
        game_radio_forget();
        if (add_peer(dest) == ESP_OK) { memcpy(last_peer, dest, 6); have_peer = true; }
    }
    // Configure only this validated unicast peer; broadcasts stay at default.
    if(!benchmark && !CwlLx_MacIsBroadcast(dest) && have_peer &&
       !game_radio_sync(logic,dest)) {
        failures++;return; // Never leak a retained 12 Mbps rate into GB/other peers.
    }
    if (benchmark ? !cb_io(dest,data,n,0) : esp_now_send(dest, data, n) != ESP_OK) {
        failures++;
        if (logic && (n == 27 || n == 31) && data[0] == 'L' && data[1] == 0x10)
            logic->gba.trace.send_error++;
    }
}
static void set_channel(void *ctx, uint8_t channel)
{
    (void)ctx;
    esp_err_t err = esp_wifi_set_channel(channel, WIFI_SECOND_CHAN_NONE);
    if (err != ESP_OK) ESP_LOGW(TAG, "set channel %u: %s", channel, esp_err_to_name(err));
}
static void publish(void)
{
    uint8_t channel = 0;
    wifi_second_chan_t secondary;
    esp_wifi_get_channel(&channel, &secondary);
    gamebub_cwl_status_t s = {
        .state = gb_cwl_logic_connected(logic, now_ms()) ? GB_CWL_CONNECTED : GB_CWL_SEARCHING,
        .channel = channel, .rx = logic->rx, .tx = logic->tx, .rtt_ms = logic->rtt_ms,
        .tx_fail = failures, .malformed = logic->bad, .pongs = logic->pongs,
    };
    snprintf(s.partner, sizeof(s.partner), "%s", gb_cwl_logic_partner(logic));
    portENTER_CRITICAL(&mux);
    s.rx_drop = drops;
    snapshot = s;
    candidate_snapshot = logic->candidates;
    portEXIT_CRITICAL(&mux);
}
static int compare_rtt(const void *a, const void *b)
{
    uint32_t x = *(const uint32_t *)a, y = *(const uint32_t *)b;
    return (x > y) - (x < y);
}
static void log_gba_trace(const GbaCwlTrace *t)
{
    ESP_LOGI(TAG, "gba trace role=%u id=%lu tx=%08lx rx=%08lx cmd=%08lx start=%lu/%lu done=%lu cancel=%lu timeout=%lu stop=%lu reason=%u status=%08lx config_error=%lu cmd_ok=%lu cmd_error=%lu",
        t->role, (unsigned long)t->token, (unsigned long)t->outgoing,
        (unsigned long)t->incoming, (unsigned long)t->command_word,
        (unsigned long)t->start[0], (unsigned long)t->start[1],
        (unsigned long)t->done, (unsigned long)t->cancel, (unsigned long)t->timeout,
        (unsigned long)t->stop, t->stop_reason, (unsigned long)t->status,
        (unsigned long)t->config_error, (unsigned long)t->command_ok,
        (unsigned long)t->command_error);
    ESP_LOGI(TAG, "gba radio hello/ready/req/ack tx=%lu/%lu/%lu/%lu rx=%lu/%lu/%lu/%lu send_error=%lu local=%06lx remote=%06lx seen=%06lx reject parse/route/shape/session/epoch/dest/token=%lu/%lu/%lu/%lu/%lu/%lu/%lu",
        (unsigned long)t->tx[0], (unsigned long)t->tx[1],
        (unsigned long)t->tx[2], (unsigned long)t->tx[3],
        (unsigned long)t->rx[0], (unsigned long)t->rx[1],
        (unsigned long)t->rx[2], (unsigned long)t->rx[3], (unsigned long)t->send_error,
        (unsigned long)t->epoch, (unsigned long)t->remote_epoch,
        (unsigned long)t->seen_epoch, (unsigned long)t->reject[GBA_RX_PARSE],
        (unsigned long)t->reject[GBA_RX_ROUTE], (unsigned long)t->reject[GBA_RX_SHAPE],
        (unsigned long)t->reject[GBA_RX_SESSION], (unsigned long)t->reject[GBA_RX_EPOCH],
        (unsigned long)t->reject[GBA_RX_DEST], (unsigned long)t->reject[GBA_RX_TOKEN]);
    ESP_LOGI(TAG, "gba grant tx=%lu rx=%lu",
        (unsigned long)t->tx[GBA_CWL_GRANT16], (unsigned long)t->rx[GBA_CWL_GRANT16]);
    ESP_LOGI(TAG, "gba multi ready/round/result tx=%lu/%lu/%lu rx=%lu/%lu/%lu ready_idle=%lu ready_stale=%lu",
        (unsigned long)t->tx[GBA_CWL_READY16], (unsigned long)t->tx[GBA_CWL_ROUND16],
        (unsigned long)t->tx[GBA_CWL_RESULT16], (unsigned long)t->rx[GBA_CWL_READY16],
        (unsigned long)t->rx[GBA_CWL_ROUND16], (unsigned long)t->rx[GBA_CWL_RESULT16],
        (unsigned long)t->ready_idle, (unsigned long)t->ready_stale);
    // Cumulative since the link task started (measurement, GB-113).
    ESP_LOGI(TAG, "gba measure batches=%lu unsynced=%u switches=%lu early=%lu early_mismatch=%lu buffered=%lu buffer_miss=%lu",
        (unsigned long)t->spi_batches, (unsigned)t->unsynced, (unsigned long)t->unsync_switches,
        (unsigned long)t->early, (unsigned long)t->early_mismatch,
        (unsigned long)t->buffered, (unsigned long)t->buffer_miss);
    // GB-115: cumulative per step (n, average, max in microseconds).
    static const char *const hop_name[GBA_HOP_COUNT]={"p_detect","p_rtt","p_rxq","p_done",
        "c_rxq","c_cmd","c_run","c_detect","c_send","c_grant"};
    char line[400]; size_t used=0;
    for (unsigned i=0;i<GBA_HOP_COUNT && used<sizeof(line);i++) {
        const GbaHop *h=&t->hop[i];
        int w=snprintf(line+used,sizeof(line)-used," %s=%lu/%lu/%lu",hop_name[i],(unsigned long)h->n,
                       (unsigned long)(h->n ? h->sum/h->n : 0),(unsigned long)h->max);
        if (w<0) break;
        used+=(size_t)w;
    }
    ESP_LOGI(TAG, "gba hop_us n/avg/max%s", line);
    ESP_LOGI(TAG, "gba measure baud 9600/38400/57600/115200=%lu/%lu/%lu/%lu spi ops=%lu us=%lu crit_parent n=%lu ops=%lu spi_us=%lu crit_child n=%lu ops=%lu spi_us=%lu timeout n=%lu requesting=%u ready=%u ready_age_ms=%lu round_tx=%lu",
        (unsigned long)t->baud[0], (unsigned long)t->baud[1], (unsigned long)t->baud[2],
        (unsigned long)t->baud[3], (unsigned long)t->spi_ops, (unsigned long)t->spi_us,
        (unsigned long)t->crit_parent_n, (unsigned long)t->crit_parent_ops,
        (unsigned long)t->crit_parent_spi_us, (unsigned long)t->crit_child_n,
        (unsigned long)t->crit_child_ops, (unsigned long)t->crit_child_spi_us,
        (unsigned long)t->to_n, (unsigned)t->to_requesting, (unsigned)t->to_ready,
        (unsigned long)t->to_ready_age_ms, (unsigned long)t->to_round_tx);
}
static unsigned round_percentile(const GbaCwlTrace *t,unsigned percent)
{
    if(!t->round_n) return 0;
    uint64_t rank=((uint64_t)t->round_n*percent+99)/100;
    uint32_t count=0;
    for(unsigned i=0;i<65;i++) { count+=t->round_ms[i]; if(count>=rank) return i; }
    return 64;
}
static void report_performance(GbaCwlTrace *t)
{
    ESP_LOGI(TAG, "gba perf round_n=%lu p50_ms=%u p95_ms=%u max_ms=%lu (64=overflow) rx=%lu backlog=%lu highwater=%lu",
        (unsigned long)t->round_n,round_percentile(t,50),round_percentile(t,95),
        (unsigned long)t->round_max_ms,(unsigned long)rx_serviced,(unsigned long)rx_backlog,(unsigned long)rx_highwater);
    ESP_LOGI(TAG, "gba perf us_upper spi=%lu/%lu/%lu n=%lu queue=%lu/%lu/%lu n=%lu service=%lu/%lu/%lu n=%lu",
        (unsigned long)upper_percentile(&spi_us,50),(unsigned long)upper_percentile(&spi_us,95),
        (unsigned long)spi_us.max,(unsigned long)spi_us.n,
        (unsigned long)upper_percentile(&queue_us,50),(unsigned long)upper_percentile(&queue_us,95),
        (unsigned long)queue_us.max,(unsigned long)queue_us.n,
        (unsigned long)upper_percentile(&service_us,50),(unsigned long)upper_percentile(&service_us,95),
        (unsigned long)service_us.max,(unsigned long)service_us.n);
    static const char *const split_name[GBA_SPLIT_COUNT]={"wait","remote","child_wait","child_run","child_behind"};
    for(unsigned i=0;i<GBA_SPLIT_COUNT;i++) {
        const GbaSplit *h=&t->split[i];
        if(!h->n) continue;
        ESP_LOGI(TAG, "gba split_us %s n=%lu avg=%lu p50_upper=%lu p95_upper=%lu max=%lu",
            split_name[i],(unsigned long)h->n,(unsigned long)(h->sum/h->n),
            (unsigned long)gba_split_percentile(h,50),(unsigned long)gba_split_percentile(h,95),
            (unsigned long)h->max);
    }
    memset(t->split,0,sizeof(t->split));
    memset(t->round_ms,0,sizeof(t->round_ms));t->round_n=t->round_max_ms=0;
    memset(&spi_us,0,sizeof(spi_us));memset(&queue_us,0,sizeof(queue_us));memset(&service_us,0,sizeof(service_us));
    rx_serviced=rx_backlog=rx_highwater=0;
}

// GB-147: frame-locked buttons for two GB cores per console. The radio task
// owns `sync`; the request crosses tasks under mux like the core probe.
static bool sync_wanted, sync_active, sync_gba;
static uint32_t sync_delay, sync_rom, sync_save, sync_request, sync_applied;
static GbSync sync;
static gamebub_cwl_session_t session_snapshot;
static void session_publish(void)
{
    gamebub_cwl_session_t s = {0};
    if (sync_active)
    {
        s = (gamebub_cwl_session_t){.phase = gb_sync_phase(&sync), .why = sync.why, .player = sync.player,
                                    .frames = sync.frames, .partner_accepted = sync.partner_accepted};
        snprintf(s.partner_title, sizeof(s.partner_title), "%s", sync.partner_title);
    }
    portENTER_CRITICAL(&mux);
    session_snapshot = s;
    portEXIT_CRITICAL(&mux);
}
void gamebub_cwl_session(gamebub_cwl_session_t *out)
{
    portENTER_CRITICAL(&mux); *out = session_snapshot; portEXIT_CRITICAL(&mux);
}
static char sync_title[GB_SYNC_TITLE + 1];
static bool sync_accept;
void gamebub_cwl_sync_accept(void)
{
    portENTER_CRITICAL(&mux);
    sync_accept = true;
    portEXIT_CRITICAL(&mux);
}
static bool sync_save_changed;
void gamebub_cwl_sync_set_save(uint32_t save_crc)
{
    portENTER_CRITICAL(&mux);
    sync_save = save_crc;
    sync_save_changed = true;
    portEXIT_CRITICAL(&mux);
}
void gamebub_cwl_set_sync(bool enabled, bool gba, uint32_t delay, uint32_t rom_id, uint32_t save_crc,
                          const char *title)
{
    portENTER_CRITICAL(&mux);
    sync_save_changed = false;
    snprintf(sync_title, sizeof(sync_title), "%s", title ? title : "");
    sync_accept = false;
    sync_wanted = enabled;
    sync_gba = gba;
    sync_delay = delay;
    sync_rom = rom_id;
    sync_save = save_crc;
    sync_request++;
    portEXIT_CRITICAL(&mux);
}
static bool sync_read(void *ctx, uint32_t a, uint32_t *v) { (void)ctx; return gamebub_link_read(a, v); }
// Through the batch path: it uses the read-clock SPI device. Single writes go out on the fast
// device, and about one in six never reached the FPGA module (GB-150: 5912 seen of ~6900 sent).
static bool sync_write(void *ctx, uint32_t a, uint32_t v)
{
    (void)ctx;
    GbLinkOp op = {a, v, 1};
    return gamebub_link_batch(&op, 1) == 1;
}
static uint8_t sync_channel(void *ctx) { (void)ctx; return logic->channel; }
static void sync_set_channel(void *ctx, uint8_t channel)
{
    logic->channel = channel;
    set_channel(ctx, channel);
}
static void sync_send(void *ctx, const uint8_t *unit, size_t n) { send_frame(ctx, logic->peer.mac, unit, n); }
static void sync_log(void *ctx, const char *line) { (void)ctx; ESP_LOGI(TAG, "%s", line); }
// The console with the higher address is player 2; the two consoles always disagree.
static bool sync_player2(void *ctx)
{
    (void)ctx;
    uint8_t own[6] = {0};
    esp_wifi_get_mac(WIFI_IF_STA, own);
    return memcmp(own, logic->peer.mac, 6) > 0;
}
static void sync_configure(void)
{
    portENTER_CRITICAL(&mux);
    const bool wanted = sync_wanted && core_running;
    const uint32_t request = sync_request, delay = sync_delay, rom = sync_rom, save = sync_save;
    const bool gba = sync_gba;
    char title[GB_SYNC_TITLE + 1];
    memcpy(title, sync_title, sizeof(title));
    portEXIT_CRITICAL(&mux);
    if (request == sync_applied && wanted == sync_active) return;
    sync_applied = request;
    sync_active = wanted;
    if (!wanted) { gb_sync_stop(&sync); session_publish(); return; }
    const GbSyncIo io = {.read = sync_read, .write = sync_write, .send = sync_send, .log = sync_log,
                         .player2 = sync_player2, .channel = sync_channel,
                         .set_channel = sync_set_channel};
    gb_sync_init(&sync, &io, (uint8_t)delay, rom, save);
    // 35002: measured 97.4% with the cache (35000: 96%, 35001: 97.3%; 35003 not tried).
    sync.phase_idx = 2;
    // GB-156: the GBA core has ten buttons and its own register layout.
    sync.gba = gba;
    sync.quiet = !diagnostics;
    memcpy(sync.title, title, sizeof(sync.title));
    ESP_LOGI(TAG, "sync: armed core=%s delay=%lu rom=%08lx save=%08lx", gba ? "gba" : "gb", (unsigned long)delay,
             (unsigned long)rom, (unsigned long)save);
}
static void sync_poll(void)
{
    portENTER_CRITICAL(&mux);
    const bool accept = sync_accept, save_changed = sync_save_changed;
    const uint32_t save = sync_save;
    sync_accept = false;
    sync_save_changed = false;
    portEXIT_CRITICAL(&mux);
    if (save_changed) {
        ESP_LOGI(TAG, "sync: save replaced, id=%08lx", (unsigned long)save);
        gb_sync_set_save(&sync, save);
    }
    if (accept) gb_sync_accept(&sync);
    gb_sync_poll(&sync, now_ms(), gb_cwl_logic_connected(logic, now_ms()));
    session_publish();
}
// SYNC units of a frame from the locked partner; the frame layout is checked here too.
static void sync_receive(const uint8_t src[6], bool unicast, const uint8_t *data, size_t len)
{
    if (!unicast || !logic->peer.locked || memcmp(logic->peer.mac, src, 6)) return;
    for (size_t at = 0; at < len;) {
        if (data[at] != 'C' && (data[at] != 'L' || at + 3 > len)) return;
        const size_t unit = data[at] == 'C' ? 6 : 3 + (size_t)data[at + 2];
        if (at + unit > len) return;
        if (data[at] == 'L' && (data[at + 1] == GB_SYNC_UNIT_READY || data[at + 1] == GB_SYNC_UNIT_INPUT ||
                                data[at + 1] == GB_SYNC_UNIT_BYE)) {
            // Button traffic is proof of life too: HELLO alone (every 500 ms) declared the
            // partner lost under packet loss, and the game stood still meanwhile.
            logic->core.peer_seen = true;
            logic->core.peer_last_seen_ms = now_ms();
            logic->peer.last_ms = now_ms();
            gb_sync_receive(&sync, data + at, unit, now_ms());
        }
        at += unit;
    }
}

// Radio-only benchmark. One owner task; callbacks only enqueue and timestamp.
// HELLO capability negotiation is mandatory before traffic; normal peers never
// activate a benchmark. User explicitly starts it via Wireless on this image.
typedef struct {
    CbTxQueue txq;
    uint32_t api_ok[4],api_error[8],api_error_count[8],error_overflow;
    uint32_t callback_ok_base,callback_fail_base,callback_stray_base,queue_discard;
    CbHist local_tx_queue;
    bool paced;
    CbSlot slots[CB_WINDOW];
    CbHist rtt,remote_queue,remote_service;
    uint32_t session,remote,seq,load_seq,last_hello,control_at,started,next_due,next_load,next_stage,request_at,bound_at,finished_at;
    uint32_t drop_mark[CB_WINDOW],tx_ok,tx_error,completed,expired,retries,late,invalid;
    uint32_t rx_requests,rx_load,tx_load,injected,window_full,slip,drop_base,highwater;
    uint8_t own_mac[6],peer_mac[6],stage,expected;
    bool peer_seen,peer_ack,leader,running,waiting,stopping,halted,peer_bound;
} CbState;
static CbState *bench;
static esp_timer_handle_t bench_timer;
static uint32_t cb_now(void) { return (uint32_t)esp_timer_get_time(); }
static bool cb_initiator(void) { return bench->leader; }
static void cb_tick(void *arg) { (void)arg;gamebub_cwl_notify_bridge(); }
// Callbacks never dereference session memory. All driver sends, including
// discovery, use this accounting while benchmark is enabled.
static uint32_t cb_pending,cb_done_ok,cb_done_fail,cb_stray,cb_progress;
static void cb_sent(const uint8_t *mac,esp_now_send_status_t status) {
    (void)mac;
    if(__atomic_load_n(&cb_pending,__ATOMIC_ACQUIRE)) {
        __atomic_fetch_add(status==ESP_NOW_SEND_SUCCESS?&cb_done_ok:&cb_done_fail,1,__ATOMIC_RELAXED);
        __atomic_store_n(&cb_progress,cb_now(),__ATOMIC_RELEASE);
        __atomic_fetch_sub(&cb_pending,1,__ATOMIC_RELEASE);
    } else __atomic_fetch_add(&cb_stray,1,__ATOMIC_RELAXED);
    gamebub_cwl_notify_bridge();
}
static void cb_error(esp_err_t err) {
    bench->tx_error++;
    for(unsigned i=0;i<8;i++) {
        if(!bench->api_error_count[i]||bench->api_error[i]==(uint32_t)err) {
            bench->api_error[i]=(uint32_t)err;bench->api_error_count[i]++;return;
        }
    }
    bench->error_overflow++;
}
static bool cb_submit(const uint8_t *dest,const uint8_t *data,size_t n,unsigned priority) {
    if(__atomic_fetch_add(&cb_pending,1,__ATOMIC_ACQ_REL)==0)
        __atomic_store_n(&cb_progress,cb_now(),__ATOMIC_RELEASE);
    esp_err_t err=esp_now_send(dest,data,n);
    if(err!=ESP_OK) {
        __atomic_fetch_sub(&cb_pending,1,__ATOMIC_RELEASE);cb_error(err);return false;
    }
    bench->api_ok[priority]++;return true;
}
static bool cb_io(const uint8_t *dest,const uint8_t *data,size_t n,unsigned priority) {
    if(!bench||n>250||priority>3)return false;
    if(bench->halted)return false;
    if(!bench->paced)return cb_submit(dest,data,n,priority);
    CbTx v={.len=(uint8_t)n,.priority=(uint8_t)priority,.queued=cb_now()};
    memcpy(v.mac,dest,6);memcpy(v.data,data,n);
    return cb_tx_push(&bench->txq,&v);
}
static void cb_pump(void) {
    if(!bench->paced||__atomic_load_n(&cb_pending,__ATOMIC_ACQUIRE))return;
    CbTx v;
    while(cb_tx_pop(&bench->txq,&v)) {
        if((uint32_t)(cb_now()-v.queued)>=CB_TIMEOUT_US) {bench->txq.stale++;continue;}
        cb_sample(&bench->local_tx_queue,cb_now()-v.queued);
        cb_submit(v.mac,v.data,v.len,v.priority);
        return; // At most one driver call per owner pass, even on API failure.
    }
}
static bool cb_send_packet(CbPacket *p) {
    uint8_t data[31];size_t n=cb_pack(data,p);
    unsigned priority=p->op==CB_REPLY?1:p->op==CB_REQUEST?2:p->op==CB_LOAD?3:0;
    return cb_io(bench->peer_mac,data,n,priority);
}
static void cb_control(unsigned op,unsigned stage,uint32_t seq) {
    CbPacket p={.op=op,.stage=stage,.session=bench->session,.seq=seq};cb_send_packet(&p);
}
static void cb_print_hist(const char *name,const CbHist *h) {
    ESP_LOGI(TAG,"bench %s n=%lu min_us=%lu avg_us=%lu p50_upper_us=%lu p95_upper_us=%lu p99_upper_us=%lu max_us=%lu",
        name,(unsigned long)h->n,(unsigned long)h->min,(unsigned long)(h->n?h->sum/h->n:0),
        (unsigned long)cb_quantile(h,50),(unsigned long)cb_quantile(h,95),(unsigned long)cb_quantile(h,99),(unsigned long)h->max);
}
static void cb_report(unsigned reason) {
    if(!bench->running)return;
    bench->running=false;
    unsigned pending=0;for(unsigned i=0;i<CB_WINDOW;i++)if(bench->slots[i].active){pending++;bench->slots[i].active=false;}
    uint32_t now=cb_now();
    ESP_LOGI(TAG,"bench result stage=%u leader=%u initiator=%u reason=%u rate=%u window_us=%lu tx_ok=%lu tx_error=%lu completed=%lu expired=%lu pending=%u retries=%lu late=%lu invalid=%lu req_rx=%lu load_tx=%lu load_rx=%lu injected=%lu window_full=%lu scheduler_slip=%lu queue_high=%lu queue_drop=%lu",
        bench->stage,bench->leader,cb_initiator(),reason,cb_rate(bench->stage),(unsigned long)(now-bench->started),
        (unsigned long)bench->tx_ok,(unsigned long)bench->tx_error,(unsigned long)bench->completed,(unsigned long)bench->expired,pending,
        (unsigned long)bench->retries,(unsigned long)bench->late,(unsigned long)bench->invalid,(unsigned long)bench->rx_requests,
        (unsigned long)bench->tx_load,(unsigned long)bench->rx_load,(unsigned long)bench->injected,(unsigned long)bench->window_full,
        (unsigned long)bench->slip,(unsigned long)bench->highwater,(unsigned long)(drops-bench->drop_base));
    ESP_LOGI(TAG,"bench transport stage=%u phy_mbps=%u paced=%u api_control=%lu api_reply=%lu api_request=%lu api_load=%lu callback_ok=%lu callback_fail=%lu callback_stray=%lu in_flight=%lu txq_high=%u txq_reject=%u txq_stale=%u txq_pending=%u",
        bench->stage,cb_phy_mbps(bench->stage),bench->paced,
        (unsigned long)bench->api_ok[0],(unsigned long)bench->api_ok[1],(unsigned long)bench->api_ok[2],(unsigned long)bench->api_ok[3],
        (unsigned long)(__atomic_load_n(&cb_done_ok,__ATOMIC_ACQUIRE)-bench->callback_ok_base),
        (unsigned long)(__atomic_load_n(&cb_done_fail,__ATOMIC_ACQUIRE)-bench->callback_fail_base),
        (unsigned long)(__atomic_load_n(&cb_stray,__ATOMIC_ACQUIRE)-bench->callback_stray_base),
        (unsigned long)__atomic_load_n(&cb_pending,__ATOMIC_ACQUIRE),
        bench->txq.high,bench->txq.rejected,bench->txq.stale,bench->txq.n);
    for(unsigned i=0;i<8;i++)if(bench->api_error_count[i])
        ESP_LOGI(TAG,"bench api_error code=0x%lx count=%lu",(unsigned long)bench->api_error[i],(unsigned long)bench->api_error_count[i]);
    if(bench->error_overflow)ESP_LOGE(TAG,"bench error-code overflow=%lu",(unsigned long)bench->error_overflow);
    cb_print_hist("local_tx_queue",&bench->local_tx_queue);
    // Data pending after the drain cannot contaminate the next profile.
    for(unsigned i=bench->txq.n;i>0;i--)if(bench->txq.items[i-1].priority) {
        memmove(&bench->txq.items[i-1],&bench->txq.items[i],(bench->txq.n-i)*sizeof(CbTx));bench->txq.n--;
    }
    cb_print_hist("rtt",&bench->rtt);cb_print_hist("remote_queue",&bench->remote_queue);cb_print_hist("remote_service",&bench->remote_service);
    if(reason)bench->halted=true;
}
static bool cb_begin(unsigned stage) {
    if(__atomic_load_n(&cb_pending,__ATOMIC_ACQUIRE)||bench->txq.n)return false;
    esp_now_rate_config_t rate={.phymode=cb_phy_mbps(stage)==1?WIFI_PHY_MODE_11B:WIFI_PHY_MODE_11G,
        .rate=cb_phy_mbps(stage)==1?WIFI_PHY_RATE_1M_L:cb_phy_mbps(stage)==6?WIFI_PHY_RATE_6M:WIFI_PHY_RATE_12M};
    esp_err_t err=esp_now_set_peer_rate_config(bench->peer_mac,&rate);
    if(err!=ESP_OK){ESP_LOGE(TAG,"bench PHY configuration failed: %d",(int)err);bench->halted=true;return false;}
    bench->paced=cb_paced(stage);
    memset(&bench->txq,0,sizeof(bench->txq));memset(bench->api_ok,0,sizeof(bench->api_ok));
    memset(bench->api_error_count,0,sizeof(bench->api_error_count));bench->error_overflow=0;
    memset(&bench->local_tx_queue,0,sizeof(bench->local_tx_queue));
    bench->callback_ok_base=__atomic_load_n(&cb_done_ok,__ATOMIC_ACQUIRE);
    bench->callback_fail_base=__atomic_load_n(&cb_done_fail,__ATOMIC_ACQUIRE);
    bench->callback_stray_base=__atomic_load_n(&cb_stray,__ATOMIC_ACQUIRE);
    memset(bench->slots,0,sizeof(bench->slots));memset(bench->drop_mark,0,sizeof(bench->drop_mark));
    memset(&bench->rtt,0,sizeof(bench->rtt));memset(&bench->remote_queue,0,sizeof(bench->remote_queue));memset(&bench->remote_service,0,sizeof(bench->remote_service));
    bench->seq=bench->load_seq=bench->tx_ok=bench->tx_error=bench->completed=bench->expired=bench->retries=bench->late=bench->invalid=0;
    bench->rx_requests=bench->rx_load=bench->tx_load=bench->injected=bench->window_full=bench->slip=bench->highwater=0;
    bench->stage=stage;bench->running=true;bench->waiting=false;bench->drop_base=drops;
    bench->started=bench->next_due=bench->next_load=cb_now();
    ESP_LOGI(TAG,"bench begin stage=%u phy_mbps=%u paced=%u leader=%u initiator=%u rate=%u duration_us=2000000 retries=off",
        stage,cb_phy_mbps(stage),bench->paced,bench->leader,cb_initiator(),cb_rate(stage));
    return true;
}
static void cb_receive(const Rx *r) {
    if(!bench||!bench->peer_bound||!r->unicast||memcmp(r->src,bench->peer_mac,6))return;
    CbPacket p;if(!cb_unpack(&p,r->data,r->len)){bench->invalid++;return;}
    uint32_t now=cb_now();
    if(p.op==CB_HELLO) {
        if(bench->remote && bench->remote!=p.session){cb_report(2);bench->halted=true;return;}
        bench->remote=p.session;bench->peer_seen=true;
        if(p.seq==bench->session)bench->peer_ack=true;
        return;
    }
    if(!bench->peer_ack||bench->halted)return;
    if(p.op==CB_REPLY) {
        if(p.session!=bench->session||!bench->running||p.stage!=bench->stage){bench->late++;return;}
        uint32_t rtt;
        if(!cb_complete(bench->slots,&p,now,&rtt)){bench->late++;return;}
        bench->completed++;cb_sample(&bench->rtt,rtt);cb_sample(&bench->remote_queue,p.queue);cb_sample(&bench->remote_service,p.service);
        return;
    }
    if(p.session!=bench->remote){bench->invalid++;return;}
    if(p.op==CB_START && !bench->leader) {
        if(p.stage!=bench->expected)return;
        if(!bench->running && !cb_begin(p.stage))return;
        cb_control(CB_ACK,p.stage,0);return;
    }
    if(p.op==CB_ACK && bench->leader && p.stage==bench->stage) {
        if(p.seq==0 && bench->waiting)cb_begin(p.stage);
        else if(p.seq==1 && bench->stopping) {
            bench->stopping=false;bench->expected=bench->stage+1;bench->next_stage=now+500000;
        }
        return;
    }
    if(p.op==CB_STOP && p.stage==bench->stage) {
        cb_report(p.seq);bench->waiting=false;
        if(p.seq)bench->halted=true;
        else if(!bench->leader){
            bench->expected=p.stage+1;
            if(!bench->finished_at && bench->expected>=CB_STAGES)bench->finished_at=now;
            cb_control(CB_ACK,p.stage,1);
        }
        return;
    }
    if(!bench->running||p.stage!=bench->stage)return;
    if(p.op==CB_LOAD){bench->rx_load++;return;}
    if(p.op==CB_REQUEST) {
        bench->rx_requests++;
        p.op=CB_REPLY;p.queue=now-(uint32_t)r->queued_us;
        // The queue timestamp is taken in the receive callback, not at RF arrival.
        p.service=cb_now()-now;
        cb_send_packet(&p);
    }
}
static void cb_poll(void) {
    if(!bench)return;
    if(bench->halted){bench->txq.n=0;if(bench_timer)esp_timer_stop(bench_timer);return;}
    uint32_t now=cb_now();
    if(__atomic_load_n(&cb_pending,__ATOMIC_ACQUIRE) &&
       (uint32_t)(now-__atomic_load_n(&cb_progress,__ATOMIC_ACQUIRE))>=500000) {
        cb_report(4);bench->halted=true;ESP_LOGE(TAG,"bench TX callback watchdog");return;
    }
    cb_pump();
    // Bound every paired session, including a silent peer between stages.
    if(bench->peer_bound && (uint32_t)(now-bench->bound_at)>=120000000) {
        cb_report(3);bench->halted=true;ESP_LOGE(TAG,"bench suite watchdog");return;
    }
    // Leave a grace period to re-ACK a lost final STOP acknowledgement.
    if(bench->finished_at && (uint32_t)(now-bench->finished_at)>=3000000) {
        bench->halted=true;ESP_LOGI(TAG,"bench follower suite complete");return;
    }
    if(!gb_cwl_logic_connected(logic,now_ms())) {
        if(bench->running||bench->waiting||bench->stopping){cb_report(2);bench->halted=true;}
        return;
    }
    if(!bench->peer_bound) {
        memcpy(bench->peer_mac,logic->peer.mac,6);bench->peer_bound=true;
        bench->leader=memcmp(bench->own_mac,bench->peer_mac,6)<0;
        if(add_peer(bench->peer_mac)!=ESP_OK){bench->halted=true;ESP_LOGE(TAG,"bench peer registration failed");return;}
        bench->last_hello=now-100000;bench->next_stage=now+2000000;bench->bound_at=now;
    } else if(memcmp(bench->peer_mac,logic->peer.mac,6)){cb_report(2);bench->halted=true;return;}
    if((uint32_t)(now-bench->last_hello)>=100000) {
        cb_control(CB_HELLO,bench->stage,bench->remote);bench->last_hello=now;
    }
    if(!bench->peer_ack) {
        if((int32_t)(now-bench->next_stage)>=1000000) {
            bench->halted=true;ESP_LOGE(TAG,"bench capability negotiation timeout");
        }
        return;
    }
    if(bench->running) {
        unsigned q=uxQueueMessagesWaiting(rx_queue);if(q>bench->highwater)bench->highwater=q;
        if(bench->highwater>=12||drops!=bench->drop_base){cb_control(CB_STOP,bench->stage,1);cb_report(1);bench->halted=true;return;}
        for(unsigned i=0;i<CB_WINDOW;i++) {
            CbSlot *slot=&bench->slots[i];
            if(cb_expired(slot,now)){slot->active=false;bench->expired++;}

        }
        uint32_t elapsed=now-bench->started;
        if(elapsed<2000000 && cb_initiator() && (int32_t)(now-bench->next_due)>=0) {
            unsigned interval=1000000/cb_rate(bench->stage);
            if((uint32_t)(now-bench->next_due)>=interval)bench->slip++;
            bench->next_due=now+interval; // Never issue a catch-up burst.
            bool idle=true;if(bench->stage<6)for(unsigned i=0;i<CB_WINDOW;i++)if(bench->slots[i].active)idle=false;
            if(idle) {
                uint32_t seq=bench->seq+1;CbSlot *slot=cb_reserve(bench->slots,seq,now);
                if(!slot)bench->window_full++;
                else {
                    bench->seq=seq;CbPacket p={.op=CB_REQUEST,.stage=bench->stage,.session=bench->session,.seq=seq,.sent=now};
                    if(cb_send_packet(&p))bench->tx_ok++;else slot->active=false;
                }
            }
        }
        // Reverse unacknowledged 31-byte traffic models concurrent GRANT load.
        if(elapsed<2000000 && bench->stage>=6 && !cb_initiator() && (int32_t)(now-bench->next_load)>=0) {
            bench->next_load=now+1000000/cb_rate(bench->stage);
            CbPacket p={.op=CB_LOAD,.stage=bench->stage,.session=bench->session,.seq=++bench->load_seq};
            if(cb_send_packet(&p))bench->tx_load++;
        }
        if(elapsed>=2100000 && bench->leader) {
            cb_control(CB_STOP,bench->stage,0);cb_report(0);bench->stopping=true;
            bench->request_at=bench->control_at=now;
        } else if(elapsed>=2800000) { cb_report(3);bench->halted=true; }
    } else if(bench->leader) {
        if(bench->expected>=CB_STAGES){bench->halted=true;ESP_LOGI(TAG,"bench suite complete");return;}
        if(bench->stopping) {
            if((uint32_t)(now-bench->request_at)>=3000000){bench->halted=true;ESP_LOGE(TAG,"bench STOP acknowledgement timeout");return;}
            if((uint32_t)(now-bench->control_at)>=100000){cb_control(CB_STOP,bench->stage,0);bench->control_at=now;}
        } else if(bench->waiting) {
            if((uint32_t)(now-bench->request_at)>=3000000){bench->halted=true;ESP_LOGE(TAG,"bench START acknowledgement timeout");return;}
            if((uint32_t)(now-bench->control_at)>=100000){cb_control(CB_START,bench->stage,0);bench->control_at=now;}
        } else if((int32_t)(now-bench->next_stage)>=0) {
            bench->stage=bench->expected;bench->waiting=true;bench->request_at=bench->control_at=now;
            cb_control(CB_START,bench->stage,0);
        }
    }
}

// Game firmware policy. Kept in the host-extracted owner section so tests execute
// this exact implementation, independently of ESP-IDF hardware.
// GB/GBC opt-in to 12 Mbps (link page setting), read by game_radio_sync.
static volatile bool fast_gb;
void gamebub_cwl_set_fast_gb(bool enabled) { fast_gb=enabled; }
typedef struct {
    uint8_t mac[6];
    unsigned applied,wanted;
    uint32_t retry_at;
    bool valid,retry;
} GameRadioRate;
static GameRadioRate game_radio;
static void game_radio_forget(void) { memset(&game_radio,0,sizeof(game_radio)); }
static bool game_radio_sync(const GbCwlLogic *s,const uint8_t mac[6]) {
    uint32_t now=now_ms();
    bool gba=s && s->core_running && s->caps_read && s->caps==GBA_CWL_CAPS &&
        s->gba.on && !s->gba.fault && s->core.peer_session_known &&
        s->gba.remote_epoch && (s->gba.remote_epoch>>8)==s->core.peer_session &&
        gb_cwl_logic_connected(s,now) && !memcmp(s->peer.mac,mac,6);
    // GB/GBC: opt-in, only while the game bridge runs with the connected partner.
    bool gb=fast_gb && s && s->core_running && s->caps_read && s->caps==0x574c021f &&
        s->bridge_on && !s->bridge_fault &&
        gb_cwl_logic_connected(s,now) && !memcmp(s->peer.mac,mac,6);
    unsigned wanted=(gba||gb)?12:1;
    if(!game_radio.valid || memcmp(game_radio.mac,mac,6)) {
        game_radio_forget();memcpy(game_radio.mac,mac,6);
        game_radio.valid=true;game_radio.applied=1; // newly added peer's SDK default
    }
    if(game_radio.wanted!=wanted) {game_radio.wanted=wanted;game_radio.retry=false;}
    if(game_radio.applied==wanted)return true;
    if(game_radio.retry && (int32_t)(now-game_radio.retry_at)<0)
        return game_radio.applied==1; // Safe low-rate fallback after upgrade failure.
    esp_now_rate_config_t rate={
        .phymode=wanted==12?WIFI_PHY_MODE_11G:WIFI_PHY_MODE_11B,
        .rate=wanted==12?WIFI_PHY_RATE_12M:WIFI_PHY_RATE_1M_L
    };
    esp_err_t err=esp_now_set_peer_rate_config(mac,&rate);
    if(err!=ESP_OK) {
        game_radio.retry=true;game_radio.retry_at=now+1000;
        ESP_LOGW(TAG,"game radio kind=%s rate error=%d wanted_mbps=%u retained_mbps=%u retry_ms=1000",
            gb&&!gba?"gb":"gba",(int)err,wanted,game_radio.applied);
        return game_radio.applied==1;
    }
    game_radio.applied=wanted;game_radio.retry=false;
    ESP_LOGI(TAG,"game radio kind=%s phy_mbps=%u peer=%02x:%02x:%02x:%02x:%02x:%02x",
        gb&&!gba?"gb":"gba",wanted,mac[0],mac[1],mac[2],mac[3],mac[4],mac[5]);
    return true;
}

static void run(void *arg)
{
    (void)arg;
    uint32_t previous = GB_CWL_SEARCHING;
    bool paired = false;
    uint32_t report_at = now_ms(), publish_at=report_at;
    unsigned backlog_passes=0;
    memset(&spi_us,0,sizeof(spi_us));memset(&queue_us,0,sizeof(queue_us));memset(&service_us,0,sizeof(service_us));
    rx_serviced=rx_backlog=rx_highwater=0;
    uint32_t progress_at = report_at, last_master = 0, last_slave = 0;
    bool trace_reported = false;
    uint32_t wake_n = 0, wake_max = 0;
    uint64_t wake_total = 0;
    for (;;) {
        uint32_t flags = 0;
        xTaskNotifyWait(0, UINT32_MAX, &flags, 0);
        if (flags & 1) break;
        int64_t service_start=esp_timer_get_time();
        apply_configuration();
        sync_configure();
        logic->trace_enabled = diagnostics;
        // GB-162: once per boot, a full-size test history through the same printing path, so the
        // capture can be checked (128 entries, twice, with the longest numbers) before a game runs.
        if (diagnostics && !trace_selftest) {
            trace_selftest = true;
            for (unsigned j = 0; j < GB_CWL_TRACE_CAPACITY; j++) {
                trace_dump[j].kind = 'Z';
                trace_dump[j].value = 0xfedc0000u | j;
                trace_dump[j].at_us = 4000000000u + j;
                trace_dump[j].repeats = 100000u + j;
                trace_dump[j].last_us = 4000000000u + j + 3999999u;
            }
            trace_dump_n = GB_CWL_TRACE_CAPACITY;
            trace_dump_at = 0;
            ESP_LOGI(TAG, "trace: self test n=%u", trace_dump_n);
        }
        portENTER_CRITICAL(&mux);
        logic->irq_us = irq_us;
        irq_us = 0;
        portEXIT_CRITICAL(&mux);
        if (diagnostics && logic->irq_us) {
            uint32_t delay = (uint32_t)esp_timer_get_time() - logic->irq_us;
            wake_n++;
            wake_total += delay;
            if (delay > wake_max) wake_max = delay;
        }
        if(!benchmark && have_peer)game_radio_sync(logic,last_peer);
        // GB-116: with GBA running, radio packets first; the FPGA is read before
        // them only when its IRQ fired (the poll after them covers the rest).
        if(!benchmark && sync_active) sync_poll();
        else if(!benchmark && (!logic->gba.on || logic->irq_us)) gb_cwl_bridge_poll(logic, now_ms());
        Rx r;
        unsigned queued=uxQueueMessagesWaiting(rx_queue);
        if(queued>rx_highwater) rx_highwater=queued;
        if(bench && bench->running && queued>bench->highwater)bench->highwater=queued;
        for(unsigned i=0;i<8 && xQueueReceive(rx_queue,&r,0)==pdTRUE;i++) {
            if(diagnostics) sample(&queue_us,esp_timer_get_time()-r.queued_us);
            rx_serviced++;
            logic->received_us = r.at_us;
            if(benchmark && r.len>=3 && r.data[0]=='G' && r.data[1]=='B' && r.data[2]=='B') cb_receive(&r);
            else {
                gb_cwl_logic_receive(logic,r.src,r.unicast,r.rssi,r.data,r.len,now_ms());
                if(sync_active) sync_receive(r.src,r.unicast,r.data,r.len);
            }
            if(esp_timer_get_time()-service_start>=2000) break;
        }
        // One FPGA poll before and one after the radio work. Each poll drains
        // the event FIFO, so a later event raises a new IRQ edge and wakes us;
        // polling per packet only repeated SPI STATUS reads (~140 us each).
        gb_cwl_logic_poll(logic, now_ms());
        if(benchmark)cb_poll();
        if(!benchmark && sync_active) sync_poll();
        else if(!benchmark) gb_cwl_bridge_poll(logic, now_ms());
        if(!benchmark && have_peer)game_radio_sync(logic,last_peer);
        while (diagnostics && logic->first_reported < logic->first_n) {
            unsigned i = logic->first_reported++;
            ESP_LOGI(TAG, "first tx=%02x rx=%02x", logic->first_tx[i], logic->first_rx[i]);
        }
        if(diagnostics) sample(&service_us,esp_timer_get_time()-service_start);
        // GB-162: the pause history, three entries on a short line every 60 ms (longer lines are
        // cut by the log, and lines printed together are lost), and the whole of it twice.
        if (diagnostics && trace_dump_at < 2 * trace_dump_n && now_ms() - trace_dump_ms >= 60) {
            trace_dump_ms = now_ms();
            char text[112];
            unsigned i = trace_dump_at % trace_dump_n;
            int at = snprintf(text, sizeof(text), "tr %03u", i);
            for (unsigned k = 0; k < 3 && i < trace_dump_n; k++, i++, trace_dump_at++)
                at += snprintf(text + at, sizeof(text) - (size_t)at, " %c:%08lx:%lu:%lu:%lu",
                    trace_dump[i].kind, (unsigned long)trace_dump[i].value,
                    (unsigned long)trace_dump[i].at_us, (unsigned long)trace_dump[i].repeats,
                    (unsigned long)(trace_dump[i].last_us - trace_dump[i].at_us));
            ESP_LOGI(TAG, "%s", text);
        }
        if (diagnostics && !benchmark && now_ms() - report_at >= 1000) {
            if(logic->gba.on || logic->gba.trace.config_error) {
                log_gba_trace(&logic->gba.trace);
                report_performance(&logic->gba.trace);
            }
            ESP_LOGI(TAG, "bridge: caps read=%d value=0x%08lx expected=0x574c021f partner=%d core=%d alive=%d -> config=%s",
                logic->caps_read, (unsigned long)logic->caps, logic->peer.confirmed,
                logic->core_running, Cwl_PeerAlive(&logic->core, now_ms()),
                gb_cwl_bridge_config_reason(logic, now_ms()));
            static const char *hop[] = {"notify_event", "event_send", "send_receive", "receive_verdict", "verdict_command"};
            unsigned samples = logic->latency_n < 64 ? logic->latency_n : 64;
            uint32_t values[64];
            for (unsigned h = 0; h < 5; h++) {
                unsigned valid_samples = 0;
                for (unsigned j = 0; j < samples; j++) {
                    if (h == 0 && !logic->latency[j][0]) continue;
                    values[valid_samples++] = logic->latency[j][h+1] - logic->latency[j][h];
                }
                qsort(values, valid_samples, sizeof(values[0]), compare_rtt);
                ESP_LOGI(TAG, "hop_us %s n=%lu sample=%u p50=%lu p95=%lu max=%lu", hop[h],
                    (unsigned long)logic->latency_n, valid_samples,
                    (unsigned long)(valid_samples ? values[(valid_samples-1)/2] : 0),
                    (unsigned long)(valid_samples ? values[(valid_samples*95+99)/100-1] : 0),
                    (unsigned long)logic->hop_max[h]);
            }
            if (samples) {
                unsigned row = (logic->latency_n-1) % 64;
                ESP_LOGI(TAG, "exchange_us notify=%lu event=%lu sent=%lu received=%lu verdict=%lu command=%lu",
                    (unsigned long)logic->latency[row][0], (unsigned long)logic->latency[row][1],
                    (unsigned long)logic->latency[row][2], (unsigned long)logic->latency[row][3],
                    (unsigned long)logic->latency[row][4], (unsigned long)logic->latency[row][5]);
            }
            ESP_LOGI(TAG, "handoff_us n=%lu notify_to_run_total=%llu max=%lu scope=notification_batch",
                (unsigned long)wake_n, (unsigned long long)wake_total, (unsigned long)wake_max);
            wake_n = wake_max = 0;
            wake_total = 0;
            logic->latency_n = 0;
            memset(logic->hop_max, 0, sizeof(logic->hop_max));
            // Exact median of the last <=256 completions in this second;
            // maximum covers every completion, even above the sample limit.
            unsigned n = logic->rtt_n < 256 ? logic->rtt_n : 256;
            qsort(logic->rtt, n, sizeof(logic->rtt[0]), compare_rtt);
            uint32_t p50 = n ? logic->rtt[(n-1)/2] : 0;
            ESP_LOGI(TAG, "xfer master=%lu slave=%lu drops=%lu rtt_ms=%lu/%lu",
                (unsigned long)logic->xmaster, (unsigned long)logic->xslave,
                (unsigned long)logic->xdrop, (unsigned long)p50,
                (unsigned long)logic->rtt_max);
            uint32_t now = now_ms();
            if (last_master != logic->xmaster || last_slave != logic->xslave) {
                last_master = logic->xmaster;
                last_slave = logic->xslave;
                progress_at = now;
                trace_reported = false;
            }
            CwlState_t *c = &logic->core;
            portENTER_CRITICAL(&mux);
            uint32_t rx_drops = drops;
            portEXIT_CRITICAL(&mux);
            ESP_LOGI(TAG, "cwl: status=%08lx gen=%u id=%u m=%u seq=%u byte=%02x abandoned=%d armed_hint=%d pending=%d/%u/%02x offer=%d withdraw=%d cancel_after=%d seen=%d/%u ack=%d/%u cancelled=%d/%u idle_ms=%lu",
                (unsigned long)logic->bridge_status, logic->generation, logic->transfer_id,
                (unsigned)c->m_state, c->m_seq, c->m_byte, c->m_abandoned, c->local_armed,
                c->pend_valid, c->pend_seq, c->pend_byte, c->offer_in_flight,
                c->withdraw_sent, c->cancel_after_offer, c->have_seen_seq, c->newest_seen_seq,
                c->have_acked, c->acked_seq, c->have_cancelled, c->cancelled_seq,
                (unsigned long)(now - progress_at));
            ESP_LOGI(TAG, "cwl: done=%lu timeout_ff=%lu collision=%lu abandon=%lu refused=%lu cancels=%lu peer_reset=%lu session=%04x/%04x peer_age_ms=%lu rx_drop=%lu tx_fail=%lu",
                (unsigned long)c->stats.master_done, (unsigned long)c->stats.master_timeouts,
                (unsigned long)c->stats.master_collisions, (unsigned long)c->stats.master_abandons,
                (unsigned long)c->stats.slave_offers_refused, (unsigned long)c->stats.cancels,
                (unsigned long)c->stats.peer_resets, c->session, c->peer_session,
                (unsigned long)(now - c->peer_last_seen_ms), (unsigned long)rx_drops,
                (unsigned long)failures);
            // Dump once per pause, after game traffic stops. Keep the final
            // game bytes even while HELLO/PING traffic continues indefinitely.
            // GB-162: 128 lines at once do not survive the USB console, so the history is
            // copied when the pause is noticed and printed twelve entries a second.
            if (logic->bridge_on && (last_master || last_slave) && !trace_reported &&
                now - progress_at >= 2000) {
                trace_dump_n = logic->trace_count;
                trace_dump_at = 0;
                for (unsigned j = 0; j < trace_dump_n; j++) {
                    unsigned i = (logic->trace_head + GB_CWL_TRACE_CAPACITY - logic->trace_count + j) % GB_CWL_TRACE_CAPACITY;
                    trace_dump[j].at_us = logic->trace[i].at_us;
                    trace_dump[j].value = logic->trace[i].value;
                    trace_dump[j].last_us = logic->trace[i].last_us;
                    trace_dump[j].repeats = logic->trace[i].repeats;
                    trace_dump[j].kind = logic->trace[i].kind;
                }
                ESP_LOGI(TAG, "trace: pause history n=%u (E event C command R/T packet I id S stale X reset; kind:value:at_us:repeats:last_us)",
                    trace_dump_n);
                trace_reported = true;
            }
            memset(logic->rtt, 0, sizeof(logic->rtt));
            logic->rtt_n = logic->rtt_max = 0;
            report_at = now_ms();
        }
        // UI snapshots are not part of a word exchange; bound their frequency.
        if((uint32_t)(now_ms()-publish_at)>=100) {
            publish(); publish_at=now_ms();
        }
        if (!paired && logic->peer.fixed) {
            paired = true;
            ESP_LOGI(TAG, "paired with %s (%02X:%02X:%02X:%02X:%02X:%02X)",
                     gb_cwl_logic_partner(logic), logic->peer.mac[0], logic->peer.mac[1],
                     logic->peer.mac[2], logic->peer.mac[3], logic->peer.mac[4], logic->peer.mac[5]);
        }
        uint32_t state = gb_cwl_logic_connected(logic, now_ms()) ? GB_CWL_CONNECTED : GB_CWL_SEARCHING;
        if (state != previous) {
            ESP_LOGI(TAG, "state=%s", state == GB_CWL_CONNECTED ? "Connected" : "Searching");
            previous = state;
        }
        // FIFO was rechecked above. Notifications persist across this wait;
        // the bounded poll also covers a missing/coalesced IRQ edge.
        // Do not sleep on queued packets after coalesced notifications. Give
        // lower-priority tasks one tick after four continuously busy batches.
        if(uxQueueMessagesWaiting(rx_queue)) {
            rx_backlog++;
            if(++backlog_passes>=4) { vTaskDelay(1); backlog_passes=0; }
            continue;
        }
        backlog_passes=0;
        flags = 0;
        // GRANT's producer must wake even when no ROUND/RX IRQ is pending.
        const bool granting = logic->gba.on && (logic->gba.config & 32);
        xTaskNotifyWait(0, UINT32_MAX, &flags, pdMS_TO_TICKS(granting ? 1 : 5));
        if (flags & 1) break;
    }
    if(bench_timer)esp_timer_stop(bench_timer);
    // The radio goes down with a link game armed: the partner is told now (GB-162).
    if(!benchmark && sync_active) { gb_sync_stop(&sync); sync_active = false; }
    if(!benchmark)gb_cwl_bridge_stop(logic);
    // Leave a valid handle for the owner to delete once this task is quiescent.
    // Self-deletion would defer the stack/TCB free until an idle task runs.
    vTaskSuspend(NULL);
}
static esp_err_t radio_down(void)
{
    esp_err_t result = ESP_OK, err;
    if (now_started) {
        esp_now_unregister_recv_cb();
        if(benchmark)esp_now_unregister_send_cb();
        err = esp_now_deinit();
        if (err != ESP_OK) result = err;
        now_started = false;
        memory_step("stop-after-esp-now-deinit");
    }
    if (wifi_started) {
        err = esp_wifi_stop();
        if (err != ESP_OK) result = err;
        wifi_started = false;
        memory_step("stop-after-wifi-stop");
    }
    if (wifi_initialized) {
        err = esp_wifi_deinit();
        if (err != ESP_OK) result = err;
        wifi_initialized = false;
        memory_step("stop-after-wifi-deinit");
    }
    if (own_event_loop) {
        err = esp_event_loop_delete_default();
        if (err != ESP_OK) result = err;
        own_event_loop = false;
        memory_step("stop-after-event-loop-delete");
    }
    ESP_LOGI(TAG, "radio down: %s", esp_err_to_name(result));
    return result;
}
static void release_session(void)
{
    // A dispatched callback can finish before detach or see NULL afterwards.
    portENTER_CRITICAL(&mux);
    QueueHandle_t queue = rx_queue;
    rx_queue = NULL;
    portEXIT_CRITICAL(&mux);
    if (queue) vQueueDelete(queue);
    memory_step("stop-after-rx-queue-delete");
    if(bench_timer){esp_timer_stop(bench_timer);esp_timer_delete(bench_timer);bench_timer=NULL;}
    heap_caps_free(bench);bench=NULL;
    heap_caps_free(logic);
    logic = NULL;
    memory_step("stop-after-session-free");
}
int32_t gamebub_cwl_start(void)
{
    if (task) return ESP_OK;
    memory_step("cwl-before-start");
    QueueHandle_t queue = xQueueCreate(16, sizeof(Rx));
    logic = heap_caps_calloc(1, sizeof(*logic), MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT);
    portENTER_CRITICAL(&mux);
    rx_queue = queue;
    portEXIT_CRITICAL(&mux);
    esp_err_t err = ESP_ERR_NO_MEM;
    if (!queue || !logic) goto fail;
    if(benchmark) {
        bench=heap_caps_calloc(1,sizeof(*bench),MALLOC_CAP_INTERNAL|MALLOC_CAP_8BIT);
        if(!bench)goto fail;
        bench->session=esp_random();if(!bench->session)bench->session=1;
    }
    drops = failures = irq_us = 0;
    have_peer = false;
    game_radio_forget();
    // Raw ESP-NOW needs Wi-Fi events, but no netif or permanent TCP/IP task.
    err = esp_event_loop_create_default();
    own_event_loop = err == ESP_OK;
    if (err != ESP_OK && err != ESP_ERR_INVALID_STATE) goto fail;
    wifi_init_config_t config = WIFI_INIT_CONFIG_DEFAULT();
    // RAM-only STA does not need the application's custom NVS partition.
    config.nvs_enable = 0;
#define TRY(call) do { err = (call); if (err != ESP_OK) goto fail; } while (0)
    TRY(esp_wifi_init(&config));
    wifi_initialized = true;
    TRY(esp_wifi_set_storage(WIFI_STORAGE_RAM));
    TRY(esp_wifi_set_mode(WIFI_MODE_STA));
    TRY(esp_wifi_start());
    wifi_started = true;
    TRY(esp_wifi_set_channel(1, WIFI_SECOND_CHAN_NONE));
    TRY(esp_wifi_set_ps(WIFI_PS_NONE));
    TRY(esp_now_init());
    now_started = true;
    TRY(esp_now_set_wake_window(65535));
    TRY(esp_wifi_connectionless_module_set_wake_interval(0));
    TRY(esp_now_register_recv_cb(receive));
    if(benchmark) {
        cb_pending=cb_done_ok=cb_done_fail=cb_stray=0;cb_progress=cb_now();
        TRY(esp_now_register_send_cb(cb_sent));
    }
    const uint8_t broadcast[6] = {255,255,255,255,255,255};
    TRY(add_peer(broadcast));
    uint8_t mac[6];
    TRY(esp_wifi_get_mac(WIFI_IF_STA, mac));
    GbCwlIo io = {.send = send_frame, .channel = set_channel,
                  .read = bridge_read, .write = bridge_write,
                  .event_pop = bridge_event_pop, .clock_us = clock_us,
                  .batch = bridge_batch};
    gb_cwl_logic_init(logic, &io, mac, (uint16_t)esp_random(), now_ms());
    if(!benchmark)gb_cwl_bridge_stop(logic);
    if(bench)memcpy(bench->own_mac,mac,6);
    portENTER_CRITICAL(&mux); partner_pending = true; portEXIT_CRITICAL(&mux);
    apply_configuration();
    publish();
    if(benchmark) {
        esp_timer_create_args_t timer_args={.callback=cb_tick,.name="link-bench",.skip_unhandled_events=true};
        TRY(esp_timer_create(&timer_args,&bench_timer));
        TRY(esp_timer_start_periodic(bench_timer,100));
        ESP_LOGW(TAG,"LINK-BENCH radio-only image: pairing explicitly arms bounded suite; game bridge disabled");
    }
    // Above the Interrupt thread (11) and the UI (10, core 0): a notified link
    // task must not wait for a UI time slice. It blocks between services.
    // GB-116: core 1, away from Wi-Fi (23), esp_timer (22) and the Rust main
    // task on core 0; priority 12 stays above the UI (10) and Interrupt (11).
    if (xTaskCreatePinnedToCore(run, "cwl", 6144, NULL, 12, &task, 1) != pdPASS) {
        task = NULL;
        err = ESP_ERR_NO_MEM;
        goto fail;
    }
    ESP_LOGI(TAG, "radio up: STA unassociated Full ch=1 name=\"%s\"", logic->name);
    return ESP_OK;
fail:
    radio_down();
    release_session();
    portENTER_CRITICAL(&mux); memset(&snapshot, 0, sizeof(snapshot)); memset(&candidate_snapshot, 0, sizeof(candidate_snapshot)); memset(&session_snapshot, 0, sizeof(session_snapshot)); portEXIT_CRITICAL(&mux);
    if (await_idle_cleanup() == ESP_OK) memory_step("start-failed-after-idle");
    return err;
#undef TRY
}
int32_t gamebub_cwl_stop(void)
{
    if (!task) return ESP_OK;
    xTaskNotify(task, 1, eSetBits);
    while (eTaskGetState(task) != eSuspended) vTaskDelay(1);
    // If its suspend is still switching out on the other CPU, IDF defers the
    // free; await_idle_cleanup below covers that case as well.
    portENTER_CRITICAL(&mux);
    TaskHandle_t stopped = task;
    task = NULL;
    portEXIT_CRITICAL(&mux);
    vTaskDelete(stopped);
    memory_step("stop-after-cwl-task-delete");
    esp_err_t err = radio_down();
    release_session();
    portENTER_CRITICAL(&mux); memset(&snapshot, 0, sizeof(snapshot)); memset(&candidate_snapshot, 0, sizeof(candidate_snapshot)); memset(&session_snapshot, 0, sizeof(session_snapshot)); portEXIT_CRITICAL(&mux);
    memory_step("stop-after-snapshot-clear");
    esp_err_t idle_err = await_idle_cleanup();
    if (idle_err != ESP_OK) return idle_err;
    memory_step("stop-after-idle");
    return err;
}
void gamebub_cwl_status(gamebub_cwl_status_t *out)
{
    portENTER_CRITICAL(&mux); *out = snapshot; portEXIT_CRITICAL(&mux);
}

uint32_t gamebub_cwl_partners(gamebub_cwl_partner_t out[6])
{
    CwlLxCands_t candidates;
    uint8_t mac[6];
    char name[16];
    bool fixed;
    portENTER_CRITICAL(&mux);
    candidates = candidate_snapshot;
    fixed = fixed_set;
    memcpy(mac, fixed_mac, 6);
    memcpy(name, remembered_name, sizeof(name));
    portEXIT_CRITICAL(&mux);
    return gb_cwl_partner_list(&candidates, now_ms(), fixed ? mac : NULL, name, out);
}
