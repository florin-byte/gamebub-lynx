#include "VStockSaveStateSlots.h"
#include "verilated.h"
#include <cassert>
#include <cstdint>
#include <map>
#include <cstdio>
static VStockSaveStateSlots t;
static std::map<uint32_t,uint32_t> memory;
static bool pending=false;
static uint32_t address;
static int launches=0, launchedSlot=-1;
static bool launchedLoad;
static void tick() {
    t.clock=0;t.io_mem_done=pending;t.io_mem_dataRead=memory.count(address)?memory[address]:0xffffffffu;t.eval();
    bool next=t.io_mem_enable;uint32_t nextAddr=t.io_mem_address;
    if(t.io_start){launches++;launchedSlot=t.io_activeSlot;launchedLoad=t.io_isLoad;}
    t.clock=1;t.eval();pending=next;if(next)address=nextAddr;
}
static void request(int slot,bool load){t.io_slot=slot;t.io_load=load;t.io_request=1;tick();t.io_request=0;}
static void waitLaunch(int previous){for(int n=0;n<50&&launches==previous;n++)tick();assert(launches==previous+1);}
static void complete(bool ok=true){t.io_engineOk=ok;t.io_engineDone=1;tick();t.io_engineDone=0;tick();assert(!t.io_busy);assert((t.io_status&6)==(ok?2:4));}
int main(int argc,char**argv){
    bool sms=argc>1;uint32_t base=sms?0x1000000:0x200000,stride=sms?0x18000:0x20000;
    t.reset=1;tick();tick();t.reset=0;t.io_available=1;t.io_blocked=0;t.io_gameId=0x12345678;
    for(int slot=0;slot<4;slot++){
        int old=launches;request(slot,true);for(int n=0;n<30;n++)tick();assert(launches==old);assert((t.io_status&0xc)==0xc);
        auto b=base+slot*stride;memory[b]=0x80000000u+slot;memory[b+4]=sms?24574:16640;memory[b+8]=0x534d5331;memory[b+12]=t.io_gameId;
        request(slot,true);waitLaunch(old);assert(launchedSlot==slot&&launchedLoad);
        t.io_slot=(slot+1)%4;request((slot+2)%4,false);assert(t.io_status&16);assert(t.io_activeSlot==slot);complete();
        old=launches;request(slot,false);waitLaunch(old);assert(launchedSlot==slot&&!launchedLoad);complete();
    }
    int old=launches;t.io_blocked=1;request(0,false);tick();assert(launches==old&&(t.io_status&0x14)==0x14);t.io_blocked=0;
    t.io_available=0;request(0,false);tick();assert(launches==old);t.io_available=1;
    request(0,false);waitLaunch(old);for(int n=0;n<1100;n++)tick();assert(!t.io_busy&&(t.io_status&4));
    if(sms){memory[base+12]^=1;old=launches;request(0,true);for(int n=0;n<30;n++)tick();assert(launches==old&&(t.io_status&8));memory[base+4]=16382;request(0,true);waitLaunch(old);complete(false);}
    printf("PASS %s: four slot addresses, empty/header checks, request latching/rejection, completion/error/timeout\n",sms?"GG":"Lynx");
}
