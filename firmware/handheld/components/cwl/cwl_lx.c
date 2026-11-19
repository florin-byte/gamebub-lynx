// cwl_lx.c: CWL2 over local radios, portable part. See cwl_lx.h.
#include "cwl_lx.h"

#include <string.h>

// ---------------------------------------------------------------- framing

void CwlLx_BatchInit(CwlLxBatch_t *b, unsigned limit)
{
    b->len = 0;
    b->limit = (uint8_t)(limit > kCwlLx_MaxFrame ? kCwlLx_MaxFrame : limit);
}

bool CwlLx_BatchFits(const CwlLxBatch_t *b, unsigned n) { return (unsigned)b->len + n <= b->limit; }

bool CwlLx_BatchAddCwl(CwlLxBatch_t *b, const uint8_t pkt[kCwlLx_CwlLen])
{
    if (!CwlLx_BatchFits(b, kCwlLx_CwlLen)) return false;
    memcpy(b->buf + b->len, pkt, kCwlLx_CwlLen);
    b->len += kCwlLx_CwlLen;
    return true;
}

bool CwlLx_BatchAddPing(CwlLxBatch_t *b, bool pong, uint8_t id, uint32_t t_ms)
{
    if (!CwlLx_BatchFits(b, kCwlLx_PingLen)) return false;
    uint8_t *p = b->buf + b->len;
    p[0] = kCwlLx_UnitL;
    p[1] = pong ? kCwlLx_LPong : kCwlLx_LPing;
    p[2] = 5;
    p[3] = id;
    p[4] = (uint8_t)t_ms;
    p[5] = (uint8_t)(t_ms >> 8);
    p[6] = (uint8_t)(t_ms >> 16);
    p[7] = (uint8_t)(t_ms >> 24);
    b->len += kCwlLx_PingLen;
    return true;
}

bool CwlLx_BatchAddName(CwlLxBatch_t *b, const char *name)
{
    size_t n = strlen(name);
    if (n == 0) return true;
    if (n > kCwlLx_NameMax) n = kCwlLx_NameMax;
    if (!CwlLx_BatchFits(b, 3 + (unsigned)n)) return false;
    uint8_t *p = b->buf + b->len;
    p[0] = kCwlLx_UnitL;
    p[1] = kCwlLx_LName;
    p[2] = (uint8_t)n;
    memcpy(p + 3, name, n);
    b->len += (uint8_t)(3 + n);
    return true;
}

// Length of the unit at p (<= left), 0 if malformed.
static size_t UnitLen(const uint8_t *p, size_t left)
{
    if (p[0] == 'C') {
        if (left < kCwlLx_CwlLen || p[1] != 'W' || p[2] != 0x02) return 0;
        return kCwlLx_CwlLen;
    }
    if (p[0] == kCwlLx_UnitL) {
        if (left < 3 || (size_t)3 + p[2] > left) return 0;
        return (size_t)3 + p[2];
    }
    return 0;
}

int CwlLx_Split(const uint8_t *frame, size_t len, const CwlLxSink_t *sink)
{
    if (frame == NULL || len == 0) return -1;
    // Validate every unit first: a damaged frame delivers nothing.
    for (size_t at = 0; at < len;) {
        const size_t u = UnitLen(frame + at, len - at);
        if (u == 0) return -1;
        at += u;
    }
    int n = 0;
    for (size_t at = 0; at < len;) {
        const uint8_t *p = frame + at;
        const size_t u = UnitLen(p, len - at);
        if (p[0] == 'C') {
            if (sink->cwl) sink->cwl(sink->ctx, p);
            n++;
        } else if ((p[1] == kCwlLx_LPing || p[1] == kCwlLx_LPong) && p[2] == 5 && sink->ping) {
            const uint32_t t = (uint32_t)p[4] | (uint32_t)p[5] << 8 | (uint32_t)p[6] << 16 | (uint32_t)p[7] << 24;
            sink->ping(sink->ctx, p[1] == kCwlLx_LPong, p[3], t);
        } else if (p[1] == kCwlLx_LName && p[2] >= 1 && p[2] <= kCwlLx_NameMax && sink->name) {
            sink->name(sink->ctx, (const char *)p + 3, p[2]);
        }   // unknown 'L' kinds are skipped (forward compatible)
        at += u;
    }
    return n;
}

// ---------------------------------------------------------------- peer lock

static const uint8_t kBroadcast[6] = {0xff, 0xff, 0xff, 0xff, 0xff, 0xff};

bool CwlLx_MacIsBroadcast(const uint8_t mac[6]) { return memcmp(mac, kBroadcast, 6) == 0; }

void CwlLx_PeerInit(CwlLxPeer_t *p, const uint8_t *fixed_mac)
{
    memset(p, 0, sizeof(*p));
    if (fixed_mac != NULL && !CwlLx_MacIsBroadcast(fixed_mac)) {
        static const uint8_t zero[6] = {0};
        if (memcmp(fixed_mac, zero, 6) != 0) {
            memcpy(p->mac, fixed_mac, 6);
            p->fixed = p->locked = p->confirmed = true;
        }
    }
}

static void Lock(CwlLxPeer_t *p, const uint8_t mac[6], bool unicast, uint32_t now_ms)
{
    memcpy(p->mac, mac, 6);
    p->locked = true;
    p->confirmed = unicast;
    p->last_ms = p->lock_ms = now_ms;
    p->locks++;
}

bool CwlLx_PeerAccept(CwlLxPeer_t *p, const uint8_t mac[6], bool unicast, uint32_t now_ms)
{
    if (CwlLx_MacIsBroadcast(mac)) return false;
    if (p->locked && memcmp(p->mac, mac, 6) == 0) {
        p->last_ms = now_ms;
        if (unicast) p->confirmed = true;
        return true;
    }
    if (p->fixed) {
        p->foreign++;
        return false;
    }
    if (p->locked) {
        // Someone else. It wants US (unicast) and ours never answered: switch.
        if (unicast && !p->confirmed) {
            p->switches++;
            Lock(p, mac, true, now_ms);
            return true;
        }
        p->foreign++;
        return false;
    }
    if (p->snubbing && (int32_t)(now_ms - p->snub_until) < 0 && memcmp(p->snub_mac, mac, 6) == 0 && !unicast) {
        p->foreign++;
        return false;
    }
    Lock(p, mac, unicast, now_ms);
    return true;
}

bool CwlLx_PeerPoll(CwlLxPeer_t *p, uint32_t now_ms)
{
    if (!p->locked || p->fixed) return false;
    if (!p->confirmed && (uint32_t)(now_ms - p->lock_ms) >= kCwlLx_Confirm_ms) {
        memcpy(p->snub_mac, p->mac, 6);
        p->snub_until = now_ms + kCwlLx_Snub_ms;
        p->snubbing = true;
        p->snubs++;
        p->locked = false;
        p->confirmed = false;
        return true;
    }
    if ((uint32_t)(now_ms - p->last_ms) < kCwlLx_PeerLost_ms) return false;
    p->locked = false;
    p->confirmed = false;
    p->losses++;
    return true;
}

void CwlLx_PeerFix(CwlLxPeer_t *p, const uint8_t *mac)
{
    if (mac == NULL) {
        p->fixed = false;   // keep the current lock; it may be forgotten / replaced again
        return;
    }
    memcpy(p->mac, mac, 6);
    p->fixed = p->locked = true;
    p->confirmed = true;
    p->snubbing = false;
}

// ---------------------------------------------------------------- candidates

void CwlLx_CandSeen(CwlLxCands_t *t, const uint8_t mac[6], int8_t rssi, uint32_t now_ms)
{
    if (CwlLx_MacIsBroadcast(mac)) return;
    CwlLxCand_t *slot = NULL, *oldest = &t->c[0];
    for (int i = 0; i < kCwlLx_MaxCand; i++) {
        CwlLxCand_t *c = &t->c[i];
        if (c->used && memcmp(c->mac, mac, 6) == 0) { slot = c; break; }
        if (!c->used && slot == NULL) slot = c;
        if (c->used && (int32_t)(c->last_ms - oldest->last_ms) < 0) oldest = c;
    }
    if (slot == NULL) slot = oldest;
    if (!slot->used || memcmp(slot->mac, mac, 6) != 0) {
        memset(slot, 0, sizeof(*slot));
        memcpy(slot->mac, mac, 6);
        CwlLx_DefaultName(mac, slot->name, sizeof(slot->name));
        slot->used = true;
    }
    slot->rssi = rssi;
    slot->last_ms = now_ms;
}

void CwlLx_CandName(CwlLxCands_t *t, const uint8_t mac[6], const char *name, size_t n)
{
    for (int i = 0; i < kCwlLx_MaxCand; i++) {
        CwlLxCand_t *c = &t->c[i];
        if (!c->used || memcmp(c->mac, mac, 6) != 0) continue;
        if (n > kCwlLx_NameMax) n = kCwlLx_NameMax;
        size_t k = 0;
        for (size_t j = 0; j < n; j++) {   // the OSD font: upper case, digits, space, '-'
            char ch = name[j];
            if (ch >= 'a' && ch <= 'z') ch = (char)(ch - 'a' + 'A');
            if ((ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || ch == ' ' || ch == '-') c->name[k++] = ch;
        }
        if (k > 0) c->name[k] = '\0';
        return;
    }
}

int CwlLx_CandList(const CwlLxCands_t *t, uint32_t now_ms, CwlLxCand_t *out, int max)
{
    int n = 0;
    for (int i = 0; i < kCwlLx_MaxCand && n < max; i++) {
        const CwlLxCand_t *c = &t->c[i];
        if (!c->used || (uint32_t)(now_ms - c->last_ms) >= kCwlLx_CandExpire_ms) continue;
        int j = n++;
        while (j > 0 && out[j - 1].rssi < c->rssi) { out[j] = out[j - 1]; j--; }
        out[j] = *c;
    }
    return n;
}

void CwlLx_DefaultName(const uint8_t mac[6], char *out, size_t len)
{
    static const char hx[] = "0123456789ABCDEF";
    char s[16] = "CHROMATIC ";
    s[10] = hx[mac[4] >> 4]; s[11] = hx[mac[4] & 15]; s[12] = hx[mac[5] >> 4]; s[13] = hx[mac[5] & 15]; s[14] = 0;
    strncpy(out, s, len - 1);
    out[len - 1] = '\0';
}

void CwlLx_PeerDest(const CwlLxPeer_t *p, uint8_t out[6])
{
    memcpy(out, p->locked ? p->mac : kBroadcast, 6);
}

// ---------------------------------------------------------------- BLE role

static uint64_t Addr48(const uint8_t a[6])
{
    uint64_t v = 0;
    for (int i = 5; i >= 0; i--) v = v << 8 | a[i];
    return v;
}

bool CwlLx_BleShouldInitiate(const uint8_t own[6], const uint8_t peer[6], CwlLxRole_t forced)
{
    if (forced == kCwlLxRole_Central) return true;
    if (forced == kCwlLxRole_Peripheral) return false;
    return Addr48(own) < Addr48(peer);
}

// ---------------------------------------------------------------- RTT

void CwlLx_RttAdd(CwlLxRtt_t *r, uint32_t ms)
{
    r->ring[r->n % kCwlLx_RttRing] = (uint16_t)(ms > 65535 ? 65535 : ms);
    r->n++;
    if (ms > r->max_ms) r->max_ms = ms;
}

uint32_t CwlLx_RttPct(const CwlLxRtt_t *r, unsigned pct)
{
    const unsigned n = r->n < kCwlLx_RttRing ? (unsigned)r->n : kCwlLx_RttRing;
    if (n == 0) return 0;
    uint16_t s[kCwlLx_RttRing];
    memcpy(s, r->ring, n * sizeof(s[0]));
    for (unsigned i = 1; i < n; i++) {   // insertion sort: n <= 128
        const uint16_t v = s[i];
        unsigned j = i;
        while (j > 0 && s[j - 1] > v) { s[j] = s[j - 1]; j--; }
        s[j] = v;
    }
    unsigned k = (pct * n) / 100;
    if (k >= n) k = n - 1;
    return s[k];
}

// ---------------------------------------------------------------- SLIP

enum { kEnd = 0xC0, kEsc = 0xDB, kEscEnd = 0xDC, kEscEsc = 0xDD };

uint8_t CwlLx_Crc8(const uint8_t *p, size_t n)
{
    uint8_t crc = 0xFF;   // CRC-8 SAE J1850: poly 0x1D, init 0xFF, xorout 0xFF
    for (size_t i = 0; i < n; i++) {
        crc ^= p[i];
        for (int b = 0; b < 8; b++) crc = (crc & 0x80) ? (uint8_t)((crc << 1) ^ 0x1D) : (uint8_t)(crc << 1);
    }
    return crc ^ 0xFF;
}

static size_t Put(uint8_t *out, size_t o, uint8_t c)
{
    if (c == kEnd) { out[o++] = kEsc; out[o++] = kEscEnd; }
    else if (c == kEsc) { out[o++] = kEsc; out[o++] = kEscEsc; }
    else out[o++] = c;
    return o;
}

size_t CwlLx_SlipEncode(uint8_t type, const uint8_t *body, size_t n, uint8_t *out)
{
    if (n > kBr_MaxBody) n = kBr_MaxBody;
    uint8_t crc;
    size_t o = 0;
    out[o++] = kEnd;   // a leading END flushes any line noise at the receiver
    uint8_t tmp[1 + kBr_MaxBody];
    tmp[0] = type;
    if (n) memcpy(tmp + 1, body, n);
    crc = CwlLx_Crc8(tmp, n + 1);
    for (size_t i = 0; i < n + 1; i++) o = Put(out, o, tmp[i]);
    o = Put(out, o, crc);
    out[o++] = kEnd;
    return o;
}

void CwlLx_SlipInit(CwlLxSlip_t *s) { memset(s, 0, sizeof(*s)); }

bool CwlLx_SlipFeed(CwlLxSlip_t *s, uint8_t c)
{
    if (s->ready) {          // the caller has read the previous message
        s->ready = false;
        s->len = 0;
    }
    if (c == kEnd) {
        bool ok = false;
        if (s->len >= 2 && !s->overflow) {
            ok = CwlLx_Crc8(s->buf, s->len - 1) == s->buf[s->len - 1];
            if (ok) s->good++;
            else s->bad++;
        } else if (s->len > 0 || s->overflow) {
            s->bad++;
        }
        s->esc = false;
        s->overflow = false;
        if (ok) s->ready = true;
        else s->len = 0;
        return ok;
    }
    if (s->overflow) return false;
    if (s->esc) {
        s->esc = false;
        if (c == kEscEnd) c = kEnd;
        else if (c == kEscEsc) c = kEsc;
        else { s->overflow = true; return false; }   // bad escape: drop the message at END
    } else if (c == kEsc) {
        s->esc = true;
        return false;
    }
    if (s->len >= sizeof(s->buf)) { s->overflow = true; return false; }
    s->buf[s->len++] = c;
    return false;
}
