// SPDX-License-Identifier: GPL-3.0-only
#define GAMEBUB_CWL_BENCH_INTERNAL
#include "../gamebub_cwl.h"
#include <assert.h>
#include <stdio.h>
int main(void) {
    uint8_t wire[31];CbPacket a={.op=CB_REQUEST,.stage=9,.session=0x12345678,.seq=127,.sent=UINT32_MAX-10},b;
    assert(cb_pack(wire,&a)==31);assert(cb_unpack(&b,wire,31));
    assert(b.session==a.session && b.seq==a.seq && b.sent==a.sent);
    for(size_t n=0;n<31;n++)assert(!cb_unpack(&b,wire,n));
    wire[30]=1;assert(!cb_unpack(&b,wire,31));wire[30]=0;
    wire[3]=1;assert(!cb_unpack(&b,wire,31));wire[3]=2;
    wire[5]=CB_STAGES;assert(!cb_unpack(&b,wire,31));wire[5]=9;
    wire[6]=1;assert(!cb_unpack(&b,wire,31));wire[6]=0;
    cb_put(wire+7,0);assert(!cb_unpack(&b,wire,31));
    a.op=CB_REPLY;assert(cb_pack(wire,&a)==27);
    assert(cb_unpack(&b,wire,27));assert(!cb_unpack(&b,wire,31));
    CbSlot slots[CB_WINDOW]={0};uint32_t rtt;
    CbSlot *s=cb_reserve(slots,a.seq,a.sent);assert(s);
    assert(!cb_reserve(slots,a.seq+CB_WINDOW,5));
    assert(cb_complete(slots,&a,9,&rtt)&&rtt==20);
    assert(!cb_complete(slots,&a,10,&rtt));
    s=cb_reserve(slots,a.seq,100);assert(s);
    assert(!cb_complete(slots,&a,110,&rtt)); // old echo after slot reuse
    a.sent=100;assert(cb_complete(slots,&a,120,&rtt)&&rtt==20);
    s=cb_reserve(slots,1,UINT32_MAX-500);assert(s);
    assert(!cb_expired(s,100));assert(cb_expired(s,100000));
    s->sent=s->last=0;s->attempts=1;
    assert(!cb_retry(s,999,1000));
    for(unsigned i=1;i<4;i++){assert(cb_retry(s,i*1000,1000));s->last=i*1000;s->attempts++;}
    assert(!cb_retry(s,4000,1000));assert(cb_expired(s,CB_TIMEOUT_US));
    CbHist h={0};for(unsigned i=1;i<=100;i++)cb_sample(&h,i*100);
    assert(h.n==100&&h.min==100&&h.max==10000&&h.sum==505000);
    assert(cb_quantile(&h,50)>=5000&&cb_quantile(&h,50)<5032);
    assert(cb_quantile(&h,95)>=9500&&cb_quantile(&h,95)<10500);
    assert(cb_quantile(&h,99)>=9900&&cb_quantile(&h,99)<10900);
    assert(cb_bucket(UINT32_MAX)==CB_BINS-1);
    assert(cb_upper(CB_BINS-1)==UINT32_MAX);
    CbTxQueue q={0};CbTx v={.priority=3,.len=1},out;
    for(unsigned i=0;i<12;i++){v.data[0]=i;assert(cb_tx_push(&q,&v));}
    assert(!cb_tx_push(&q,&v));
    v.priority=1;v.data[0]=55;assert(cb_tx_push(&q,&v));
    v.priority=0;v.data[0]=66;assert(cb_tx_push(&q,&v));
    assert(cb_tx_pop(&q,&out)&&out.data[0]==66);
    assert(cb_tx_pop(&q,&out)&&out.data[0]==55);
    for(unsigned i=0;i<12;i++)assert(cb_tx_pop(&q,&out)&&out.data[0]==i);
    assert(!cb_tx_pop(&q,&out));
    for(unsigned i=0;i<16;i++)assert(cb_tx_push(&q,&v));
    assert(!cb_tx_push(&q,&v)&&q.n==16);
    for(unsigned i=0;i<CB_STAGES;i++){
        assert(cb_phy_mbps(i)==(i%6<2?1:i%6<4?6:12));
        assert(cb_paced(i)==(i%2==1));
        assert(cb_rate(i)==(i<12?100:i<18?500:1000));
    }
    puts("transport bench protocol/window/retry/rollover/histogram PASS");
}
