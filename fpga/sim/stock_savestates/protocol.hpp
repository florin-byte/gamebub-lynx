// Included after each core harness's host helpers: tests the actual core/glue/engine together.
#include <cassert>
#include <algorithm>
static void runStockProtocol(bool sms) {
    const uint32_t base=sms?0x31000000u:0x30200000u;
    const uint32_t stride=sms?0x18000u:0x20000u;
    const uint32_t payload=sms?0x18000u:0x10400u, bytes=stride*4;
    auto idle=[&]() {
        for(uint64_t i=0;i<200000000;i++) {
            if(i%1024==0) { auto status=hostRead(0x1c);if(!(status&1))return status; }
            tick();
        }
        fprintf(stderr,"stock operation timeout\n");exit(1);return 0u;
    };
    command({0x0200,0});
    command({0x0300,3});
    for(uint32_t i=0;i<bytes;i+=4)hostWrite(base+i,0xffffffffu);
    command({0x0301,3,bytes,0});
    assert(hostRead(0x1c)==0); // Import did not invoke an engine operation.
    for(int slot=0;slot<4;slot++) {
        hostWrite(0x18,slot);hostWrite(0x14,2);auto status=idle();
        assert((status&0xe)==0xc);assert(((status>>8)&3)==unsigned(slot));
    }
    fprintf(stderr,"PASS empty slots/import without restore\n");
    std::vector<uint32_t> previous(bytes/4,0xffffffffu);
    for(int slot=0;slot<4;slot++) {
        for(int i=0;i<100000;i++)tick();
        hostWrite(0x18,slot);hostWrite(0x14,1);
        hostWrite(0x18,(slot+1)%4);hostWrite(0x14,2); // Rejected, must not replace active slot.
        auto status=idle();assert((status&6)==2);assert(status&16);assert(((status>>8)&3)==unsigned(slot));
        for(uint32_t i=0;i<bytes/4;i++) {
            auto value=hostRead(base+4*i);
            if(i<slot*stride/4||i>=(slot+1)*stride/4)assert(value==previous[i]);
            previous[i]=value;
        }
        assert(previous[(slot*stride+4)/4]==(sms?24574u:16640u));
        fprintf(stderr,"PASS save slot %d and isolation\n",slot);
    }
    // Exit may halt the core while an async stock save is still in flight. Export must wait for it.
    hostWrite(0x18,3);hostWrite(0x14,1);command({0x0101});
    auto size=command({0x0302,3},200000000);assert(size==bytes);
    assert(!(hostRead(0x1c)&1));assert((hostRead(0x1c)&6)==2);
    std::vector<uint32_t> exported(bytes/4);
    for(uint32_t i=0;i<bytes/4;i++)exported[i]=hostRead(base+4*i);
    command({0x0303,3});
    auto oldStatus=hostRead(0x1c);
    command({0x0300,3});
    for(uint32_t i=0;i<bytes/4;i++)hostWrite(base+4*i,exported[i]);
    command({0x0301,3,bytes,0});assert(hostRead(0x1c)==oldStatus);
    for(uint32_t i=0;i<bytes/4;i++)assert(hostRead(base+4*i)==exported[i]);
    command({0x0100});
    for(int slot=0;slot<4;slot++) {
        hostWrite(0x18,slot);hostWrite(0x14,2);auto status=idle();fprintf(stderr,"load slot %d status %08x\n",slot,status);assert((status&6)==2);
    }
    fprintf(stderr,"PASS bulk export/import, exit waits, four slot loads\n");
    // File 2 remains payload-only at slot 0, regardless of the stock selector.
    auto n=command({0x0302,2},200000000);assert(n==payload);
    std::vector<uint32_t> single(payload/4);
    for(uint32_t i=0;i<payload/4;i++)single[i]=hostRead(base+4*i);
    for(uint32_t i=stride/4;i<bytes/4;i++)assert(hostRead(base+4*i)==exported[i]);
    command({0x0303,2});command({0x0300,2});
    for(uint32_t i=0;i<payload/4;i++)hostWrite(base+4*i,single[i]);
    command({0x0301,2,payload,0},200000000);
    assert(command({0x0302,3})==bytes);command({0x0303,3});
    fprintf(stderr,"PASS %s stock protocol + file2 payload compatibility (%u/%u bytes)\n",sms?"GG":"Lynx",payload,bytes);
}

static void runStockFileProtocol(bool sms) {
    const uint32_t base=sms?0x31000000u:0x30200000u,bytes=sms?0x60000u:0x80000u;
    assert(command({0x0302,3},1000000,true)==0xffffffffu);
    assert(command({0x0301,3,bytes,0},1000000,true)==0xffffffffu);
    command({0x0300,3});
    hostWrite(0x18,1);hostWrite(0x14,1);assert((hostRead(0x1c)&0x14)==0x14);
    assert(command({0x0302,2},1000000,true)==0xffffffffu);
    assert(command({0x0301,3,bytes-4,0},1000000,true)==0xffffffffu);
    assert(command({0x0302,3},1000000,true)==0xffffffffu);
    command({0x0300,3});
    for(uint32_t i=0;i<bytes;i+=4)hostWrite(base+i,0xffffffffu);
    command({0x0301,3,bytes,0});assert(command({0x0302,3})==bytes);command({0x0303,3});
    hostWrite(0x18,2);hostWrite(0x14,1);
    assert(command({0x0302,2},1000000,true)==0xffffffffu); // In-flight stock request keeps ownership.
    uint32_t status=1;
    for(int i=0;i<200000000&&(status&1);i++){tick();if(i%1024==0)status=hostRead(0x1c);}
    assert((status&7)==2);assert(((status>>8)&3)==2);
    fprintf(stderr,"PASS %s file3 readiness/size/bulk-open guards and file2 busy rejection\n",sms?"GG":"Lynx");
}
