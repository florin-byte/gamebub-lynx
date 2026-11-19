// SPDX-License-Identifier: GPL-3.0-only
#include "cwl_lx.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
static int packets, names, pings;
static void packet(void *ctx, const uint8_t *p) { (void)ctx; assert(p[3] == 5); packets++; }
static void name(void *ctx, const char *p, size_t n) { (void)ctx; assert(n == 12 && !memcmp(p,"GAMEBUB 1234",12)); names++; }
static void ping(void *ctx, bool pong, uint8_t id, uint32_t t) { (void)ctx; assert(!pong && id == 255 && t == 0xfffffffe); pings++; }
int main(void)
{
    CwlLxBatch_t b;
    CwlLx_BatchInit(&b, 48);
    uint8_t p[] = {'C','W',2,5,1,2};
    assert(CwlLx_BatchAddCwl(&b,p));
    assert(CwlLx_BatchAddName(&b,"GAMEBUB 1234"));
    assert(CwlLx_BatchAddPing(&b,false,255,0xfffffffe));
    CwlLxSink_t sink = {.cwl=packet,.name=name,.ping=ping};
    assert(CwlLx_Split(b.buf,b.len,&sink) == 1);
    assert(packets == 1 && names == 1 && pings == 1);
    puts("lx: mixed frame PASS");
    b.buf[b.len++]='C';
    assert(CwlLx_Split(b.buf,b.len,&sink) == -1 && packets == 1 && names == 1);
    puts("lx: malformed frame atomic drop PASS");
    CwlLx_BatchInit(&b,6);
    assert(CwlLx_BatchAddCwl(&b,p) && !CwlLx_BatchAddCwl(&b,p));
    puts("lx: batch boundary PASS");
    CwlLxPeer_t peer;
    const uint8_t a[6]={1}, other[6]={2};
    CwlLx_PeerInit(&peer,NULL);
    assert(CwlLx_PeerAccept(&peer,a,false,100));
    assert(!peer.confirmed);
    assert(CwlLx_PeerAccept(&peer,a,true,200) && peer.confirmed);
    assert(!CwlLx_PeerAccept(&peer,other,true,300));
    puts("lx: mutual pairing and foreign rejection PASS");
    CwlLx_PeerInit(&peer,NULL);
    assert(CwlLx_PeerAccept(&peer,a,false,0xfffffff0));
    assert(CwlLx_PeerPoll(&peer,0xfffffff0u+2000));
    assert(!CwlLx_PeerAccept(&peer,a,false,0xfffffff0u+2001));
    assert(CwlLx_PeerAccept(&peer,other,true,0xfffffff0u+2002));
    puts("lx: confirmation timeout, snub and clock wrap PASS");
    CwlLx_PeerFix(&peer,other);
    assert(!CwlLx_PeerPoll(&peer,20000));
    assert(!CwlLx_PeerAccept(&peer,a,true,20001));
    puts("lx: fixed partner retained PASS");
    puts("lx_test: 6/6 PASS");
    return 0;
}
