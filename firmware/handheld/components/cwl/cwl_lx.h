// cwl_lx.h: CWL2 over local radios (Bluetooth LE, ESP-NOW): framing, peer
// lock, BLE role tie-break, transport ping, and the SLIP codec of the ESP-NOW
// USB bridge. Portable C, no OS calls (host tests: tools/cwl_lx_test.c).
//
// A local-radio FRAME (one GATT write / notification, one ESP-NOW frame) is a
// concatenation of UNITS:
//   'C' 'W' 0x02 type seq byte    a CWL2 packet, byte for byte as on UDP 47820
//   'L' kind len payload[len]     a transport control unit (never reaches the
//                                 protocol core): PING / PONG (air round trip),
//                                 NAME (the side's display name, for pairing)
// so the CWL2 messages travel unchanged and cwl_proto.c stays the one core. A
// frame with a malformed unit is dropped whole (counted). See PROTOCOL.md
// "Local radios".
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

enum {
    kCwlLx_CwlLen = 6,          // one CWL2 packet
    kCwlLx_MaxFrame = 48,       // largest frame we send (BLE ATT MTU 23 carries 20: 3 packets)
    kCwlLx_UnitL = 'L',
    kCwlLx_LPing = 1,           // payload: id u8, t_ms u32 LE (sender's clock)
    kCwlLx_LPong = 2,           // payload: the PING's payload, echoed
    kCwlLx_PingLen = 3 + 5,
    kCwlLx_LName = 3,           // payload: name, 1..kCwlLx_NameMax bytes (A-Z 0-9 space -)
    kCwlLx_NameMax = 15,

    kCwlLx_PeerLost_ms = 5000,  // an unfixed peer silent this long is forgotten (ESP-NOW)
    kCwlLx_Confirm_ms = 2000,   // a new peer must unicast to us within this, else it is snubbed
    kCwlLx_Snub_ms = 10000,     // ... and ignored this long (it is not looking for us)
};

// ---- framing

// Collects units into one frame (the host flushes it once per link-task pass,
// or when the next unit would not fit `limit`).
typedef struct {
    uint8_t buf[kCwlLx_MaxFrame];
    uint8_t len;
    uint8_t limit;              // usable bytes (BLE: ATT MTU - 3, at most kCwlLx_MaxFrame)
} CwlLxBatch_t;

void CwlLx_BatchInit(CwlLxBatch_t *b, unsigned limit);
// Room for n more bytes?
bool CwlLx_BatchFits(const CwlLxBatch_t *b, unsigned n);
// Appends a CWL2 packet / a control unit. False if it does not fit (flush first).
bool CwlLx_BatchAddCwl(CwlLxBatch_t *b, const uint8_t pkt[kCwlLx_CwlLen]);
bool CwlLx_BatchAddPing(CwlLxBatch_t *b, bool pong, uint8_t id, uint32_t t_ms);
bool CwlLx_BatchAddName(CwlLxBatch_t *b, const char *name);
static inline void CwlLx_BatchClear(CwlLxBatch_t *b) { b->len = 0; }

typedef struct {
    void *ctx;
    void (*cwl)(void *ctx, const uint8_t pkt[kCwlLx_CwlLen]);       // a CWL2 packet
    void (*ping)(void *ctx, bool pong, uint8_t id, uint32_t t_ms);  // optional
    void (*name)(void *ctx, const char *name, size_t n);            // optional (not 0-terminated)
} CwlLxSink_t;

// Splits a received frame. Returns the number of CWL2 packets delivered, or
// -1 if the frame is malformed (nothing delivered: units are checked first).
int CwlLx_Split(const uint8_t *frame, size_t len, const CwlLxSink_t *sink);

// ---- peer lock (ESP-NOW: who we talk to)
//
// Unlocked, a side broadcasts (its CWL2 HELLOs are the discovery beacons) and
// locks onto the first device it hears; from then on it unicasts to it. The
// lock is CONFIRMED when that device unicasts to us (it locked onto us too). A
// lock not confirmed within kCwlLx_Confirm_ms is dropped and that device
// snubbed for kCwlLx_Snub_ms: it is busy elsewhere or not a CWL2 side (with
// first-come locking alone, a stray beacon heard first captured a side for
// good). An unconfirmed lock also yields at once to a device that unicasts to
// us. A configured (fixed) peer is the only one ever accepted.
typedef struct {
    bool locked;
    bool fixed;                 // configured: never forgotten, others never accepted
    bool confirmed;             // the peer unicasts to us
    uint8_t mac[6];
    uint32_t last_ms, lock_ms;
    uint8_t snub_mac[6];
    uint32_t snub_until;
    bool snubbing;
    uint32_t locks, losses, foreign, snubs, switches;
} CwlLxPeer_t;

void CwlLx_PeerInit(CwlLxPeer_t *p, const uint8_t *fixed_mac /* NULL: learn */);
// A frame with at least one CWL2 packet arrived from mac (unicast: addressed
// to us, not broadcast). True: it is (now) our peer, deliver it. False: drop.
bool CwlLx_PeerAccept(CwlLxPeer_t *p, const uint8_t mac[6], bool unicast, uint32_t now_ms);
// Forgets an unfixed peer silent for kCwlLx_PeerLost_ms, or not confirmed in
// time. True if the lock was dropped (the host returns to broadcast).
bool CwlLx_PeerPoll(CwlLxPeer_t *p, uint32_t now_ms);
// Pins the lock to mac for good (pairing done / partner picked), or back to
// learning (NULL).
void CwlLx_PeerFix(CwlLxPeer_t *p, const uint8_t *mac);
// Where to send: the peer, or broadcast (all FF) while unlocked.
void CwlLx_PeerDest(const CwlLxPeer_t *p, uint8_t out[6]);
bool CwlLx_MacIsBroadcast(const uint8_t mac[6]);

// ---- nearby sides seen (the pick list): any device whose CWL2 frames we hear

enum { kCwlLx_MaxCand = 6, kCwlLx_CandExpire_ms = 10000 };
typedef struct {
    bool used;
    uint8_t mac[6];
    char name[kCwlLx_NameMax + 1];
    int8_t rssi;
    uint32_t last_ms;
} CwlLxCand_t;
typedef struct { CwlLxCand_t c[kCwlLx_MaxCand]; } CwlLxCands_t;
void CwlLx_CandSeen(CwlLxCands_t *t, const uint8_t mac[6], int8_t rssi, uint32_t now_ms);
void CwlLx_CandName(CwlLxCands_t *t, const uint8_t mac[6], const char *name, size_t n);
// Fresh candidates (seen in the last kCwlLx_CandExpire_ms), strongest first.
int CwlLx_CandList(const CwlLxCands_t *t, uint32_t now_ms, CwlLxCand_t *out, int max);
// Default display name from a MAC: "CHROMATIC 1A2B" (last two bytes).
void CwlLx_DefaultName(const uint8_t mac[6], char *out, size_t len);

// ---- Bluetooth LE role between two identical devices
//
// Both advertise the CWL2 service and scan for it. The side with the LOWER
// 48-bit address (compared as a number; NimBLE stores val[0] = LSB) initiates
// (central); the higher one only advertises and accepts. A device that does
// not advertise (the Mac central) just connects; we are then peripheral.
typedef enum { kCwlLxRole_Auto, kCwlLxRole_Central, kCwlLxRole_Peripheral } CwlLxRole_t;
// Should we connect to the advertiser `peer` (addresses LSB first)?
bool CwlLx_BleShouldInitiate(const uint8_t own[6], const uint8_t peer[6], CwlLxRole_t forced);

// ---- latency statistics (exchange REQ->ACK, transport ping)

enum { kCwlLx_RttRing = 64 };
typedef struct {
    uint16_t ring[kCwlLx_RttRing];   // ms, newest kCwlLx_RttRing samples
    uint32_t n;                      // samples ever
    uint32_t max_ms;
} CwlLxRtt_t;
void CwlLx_RttAdd(CwlLxRtt_t *r, uint32_t ms);
// Percentile (0..100) over the ring; 0 if empty.
uint32_t CwlLx_RttPct(const CwlLxRtt_t *r, unsigned pct);

// ---- ESP-NOW USB bridge serial link (tools/espnow_bridge <-> tools/espnow_shim)
//
// SLIP framing (END 0xC0, ESC 0xDB, ESC_END 0xDC, ESC_ESC 0xDD) of
// [type][body...][crc8 SAE J1850 over type+body]. Bootloader text on the
// same UART fails the CRC and is ignored.
enum {
    kBr_Tx = 0x01,        // host->bridge: dest[6] frame...          (all-FF dest = broadcast)
    kBr_Config = 0x02,    // host->bridge: channel u8, low_power u8 (0 full, 1 connectionless modem sleep)
    kBr_Ping = 0x03,      // host->bridge: id u8 -> kBr_Pong id (serial round trip)
    kBr_Hello = 0x04,     // host->bridge: -> kBr_Info
    kBr_Rx = 0x81,        // bridge->host: src[6] rssi i8 unicast u8 frame...
    kBr_TxDone = 0x82,    // bridge->host: dest[6] ok u8 (ESP-NOW send callback: MAC ACK for unicast)
    kBr_Pong = 0x83,      // bridge->host: id u8
    kBr_Info = 0x84,      // bridge->host: own_mac[6] channel u8 version u8 rx u32 tx u32 tx_fail u32 (LE)

    kBr_MaxBody = 6 + 1 + 250,
    kBr_MaxEncoded = 2 * (1 + kBr_MaxBody + 1) + 2,
};
uint8_t CwlLx_Crc8(const uint8_t *p, size_t n);
// Encodes one message; returns its length in out (>= kBr_MaxEncoded bytes).
size_t CwlLx_SlipEncode(uint8_t type, const uint8_t *body, size_t n, uint8_t *out);

typedef struct {
    uint8_t buf[1 + kBr_MaxBody + 1];
    size_t len;
    bool esc, overflow, ready;
    uint32_t good, bad;
} CwlLxSlip_t;
void CwlLx_SlipInit(CwlLxSlip_t *s);
// Feeds one byte. Returns true when a complete message with a good CRC is in
// s->buf (type = buf[0], body = buf + 1, body length = s->len - 2).
bool CwlLx_SlipFeed(CwlLxSlip_t *s, uint8_t c);

#ifdef __cplusplus
}
#endif
