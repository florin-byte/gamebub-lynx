// Simulation of LynxCore (the Lynx core without clocks and SDRAM controller), driven like the firmware
// drives a core: the boot ROM (file 1) and the cartridge (file 0) are written through the host memory
// interface between FILE_WRITE_START and FILE_WRITE_END, then the settings register, SETUP_COMPLETE,
// CORE_RUN and NOTIFY_FOCUS. The SDRAM is a model of the pipelined memory interface with a configurable
// latency; the video stream is written to a 160x160 framebuffer the way the platform does (rows advance on
// hblank edges, restart on vblank). Screens are saved as PPM.
//
// Usage: lynx_core <boot rom> <cartridge> [out prefix]
// Environment: FRAMES (300), EVERY (60), LATENCY (SDRAM wait cycles, default 6), CONFIG (register 0),
//              START / PRESS_A (frames at which to press Pause / A, e.g. 200,300), HOLD_UP=A,B (hold up
//              from frame A to frame B),
//              STATE_TEST=A,B: save a state (file 2) at frame A with the menu open, load it back at frame B,
//              and compare the screen 30 frames after each (shot_state_saved/loaded.ppm); STATE_NO_LOAD=1
//              skips the load (control: the screens must then differ).

#include "VLynxCore.h"
#include "verilated.h"
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <set>
#include <string>
#include <vector>

static VLynxCore* top;
static std::vector<uint32_t> sdram(8 * 1024 * 1024, 0);  // 32 MiB, 32-bit words
static int latency = 6;
static bool pending = false, pendingWrite = false;
static uint32_t pendingAddress = 0;
static int waitCount = 0;
static uint64_t cycles = 0;

// Core outputs sampled before the clock edge of the last tick (with that cycle's inputs).
static struct {
    bool hostDone, commandDone, commandError;
    uint32_t hostDataRead;
    bool dataEnable, hblank, vblank;
    uint8_t r, g, b;
} sampled;

static void tick() {
    bool ready = !pending || waitCount == 0;
    top->io_sdram_ready = ready;
    top->io_sdram_dataRead = (pending && !pendingWrite) ? sdram[(pendingAddress >> 2) % sdram.size()] : 0;
    top->clock = 0;
    top->eval();
    bool enable = top->io_sdram_enable;
    uint32_t address = top->io_sdram_address;
    bool isWrite = top->io_sdram_isWrite;
    uint32_t dataWrite = top->io_sdram_dataWrite;
    uint32_t strobe = top->io_sdram_writeStrobe;
    sampled.hostDone = top->io_host_mem_done;
    sampled.hostDataRead = top->io_host_mem_dataRead;
    sampled.commandDone = top->io_host_commandHost_done;
    sampled.commandError = top->io_host_commandHost_error;
    sampled.dataEnable = top->io_video_dataEnable;
    sampled.hblank = top->io_video_hblank;
    sampled.vblank = top->io_video_vblank;
    sampled.r = top->io_video_data_r;
    sampled.g = top->io_video_data_g;
    sampled.b = top->io_video_data_b;
    top->clock = 1;
    top->eval();
    cycles++;
    static int debugState = getenv("DEBUG_STATE") ? atoi(getenv("DEBUG_STATE")) : 0;
    if (debugState > 0 && ready && enable && address >= 0x200000 && address < 0x300000) {
        debugState--;
        fprintf(stderr, "[%llu] state %s %06x data %08x strobe %x\n", (unsigned long long)cycles,
                isWrite ? "write" : "read ", address, dataWrite, strobe);
    }
    // Pipelined interface (AHB-Lite style): address, isWrite and strobe in the request cycle, the write
    // data in the following (data) cycle, held until ready.
    static uint32_t pendingStrobe = 0;
    if (ready) {
        if (pending && pendingWrite) {
            uint32_t& word = sdram[(pendingAddress >> 2) % sdram.size()];
            for (int i = 0; i < 4; i++)
                if (pendingStrobe & (1 << i)) word = (word & ~(0xFFu << (8 * i))) | (dataWrite & (0xFFu << (8 * i)));
        }
        pending = false;
        if (enable) {
            pending = true;
            pendingAddress = address;
            pendingWrite = isWrite;
            pendingStrobe = strobe;
            waitCount = latency;
        }
    } else {
        waitCount--;
    }
}

static void hostWrite(uint32_t address, uint32_t data) {
    top->io_host_mem_enable = 1;
    top->io_host_mem_write = 1;
    top->io_host_mem_address = address;
    top->io_host_mem_dataWrite = data;
    int guard = 0;
    do { tick(); } while (!sampled.hostDone && ++guard < 100000);
    top->io_host_mem_enable = 0;
    if (guard >= 100000) { fprintf(stderr, "host write 0x%08x timed out\n", address); exit(1); }
}

static uint32_t hostRead(uint32_t address) {
    top->io_host_mem_enable = 1;
    top->io_host_mem_write = 0;
    top->io_host_mem_address = address;
    int guard = 0;
    do { tick(); } while (!sampled.hostDone && ++guard < 100000);
    top->io_host_mem_enable = 0;
    return sampled.hostDataRead;
}

static uint32_t command(std::vector<uint32_t> words, long limit = 1000000, bool allowError = false) {
    for (size_t i = 0; i < words.size(); i++) hostWrite(0xF0000000u + 4 * i, words[i]);
    top->io_host_commandHost_request = 1;
    long guard = 0;
    do { tick(); } while (!sampled.commandDone && !sampled.commandError && ++guard < limit);
    bool error = sampled.commandError;
    top->io_host_commandHost_request = 0;
    tick();
    uint32_t result = hostRead(0xF0000000u);
    if ((error && !allowError) || guard >= limit) { fprintf(stderr, "command 0x%04x failed\n", words[0]); exit(1); }
    if (limit > 1000000) fprintf(stderr, "command 0x%04x %u: %ld cycles%s\n", words[0], words.size() > 1 ? words[1] : 0, guard, error ? " (error)" : "");
    return error ? 0xFFFFFFFFu : result;
}

static std::vector<uint8_t> readFile(const char* path) {
    FILE* f = fopen(path, "rb");
    if (!f) { perror(path); exit(1); }
    std::vector<uint8_t> data;
    for (int c; (c = fgetc(f)) != EOF;) data.push_back(c);
    fclose(f);
    while (data.size() % 4) data.push_back(0xFF);
    return data;
}

static uint32_t word(const std::vector<uint8_t>& d, size_t i) {
    return d[i] | (d[i + 1] << 8) | (d[i + 2] << 16) | (uint32_t(d[i + 3]) << 24);
}

static std::set<int> frameList(const char* name) {
    std::set<int> s;
    if (!getenv(name)) return s;
    std::string v = getenv(name);
    for (size_t p = 0; p < v.size();) { s.insert(atoi(v.c_str() + p)); p = v.find(',', p); if (p == std::string::npos) break; p++; }
    return s;
}

#include "../stock_savestates/protocol.hpp"

int main(int argc, char** argv) {
    Verilated::commandArgs(argc, argv);
    if (argc < 3) { fprintf(stderr, "usage: lynx_core <boot rom> <cartridge> [out prefix]\n"); return 2; }
    std::string out = argc > 3 ? argv[3] : "shot";
    int frames = atoi(getenv("FRAMES") ? getenv("FRAMES") : "300");
    int every = atoi(getenv("EVERY") ? getenv("EVERY") : "60");
    latency = atoi(getenv("LATENCY") ? getenv("LATENCY") : "6");
    uint32_t config = strtoul(getenv("CONFIG") ? getenv("CONFIG") : "0", nullptr, 0);
    std::set<int> starts = frameList("START"), pressA = frameList("PRESS_A");

    std::vector<uint8_t> bios = readFile(argv[1]);
    std::vector<uint8_t> rom = readFile(argv[2]);
    size_t romSize = rom.size();

    top = new VLynxCore;
    top->reset = 1;
    for (int i = 0; i < 10; i++) tick();
    top->reset = 0;
    tick();

    // Files as CoreManager::load_files does: boot ROM (file 1), cartridge (file 0).
    command({0x0300, 1});
    for (size_t i = 0; i < bios.size(); i += 4) hostWrite(0x50000000u + i, word(bios, i));
    command({0x0301, 1, (uint32_t)bios.size(), 0});
    command({0x0300, 0});
    for (size_t i = 0; i < rom.size(); i += 4) hostWrite(0x30000000u + i, word(rom, i));
    command({0x0301, 0, (uint32_t)romSize, 0});
    for (size_t i = 0; i < rom.size(); i += 4) {
        if (sdram[i >> 2] != word(rom, i)) { fprintf(stderr, "SDRAM mismatch at 0x%zx\n", i); return 1; }
    }
    hostWrite(0x00000000u, config);
    command({0x0102});           // setup complete
    command({0x0100});           // core run
    command({0x0200, 1});        // focus
    fprintf(stderr, "files loaded (cartridge %zu bytes) in %llu cycles\n", romSize, (unsigned long long)cycles);
    if (getenv("STOCK_FILE_TEST")) { runStockFileProtocol(false); delete top; return 0; }
    if (getenv("STOCK_TEST")) { runStockProtocol(false); delete top; return 0; }

    const int size = 160;
    std::vector<uint32_t> fb(size * size, 0);
    int fx = 0, fy = 0, frame = 0, heldStart = 0, heldA = 0;
    bool prevVblank = false, prevHblank = false;
    int stateSaveAt = -1, stateLoadAt = -1, compareAt = -1;
    std::string compareName;
    std::vector<uint32_t> state, savedShot;
    if (getenv("STATE_TEST")) sscanf(getenv("STATE_TEST"), "%d,%d", &stateSaveAt, &stateLoadAt);
    int holdUpFrom = -1, holdUpTo = -1;
    if (getenv("HOLD_UP")) sscanf(getenv("HOLD_UP"), "%d,%d", &holdUpFrom, &holdUpTo);
    auto dumpFrame = [&](const std::string& name) {
        FILE* o = fopen(name.c_str(), "wb");
        fprintf(o, "P6 %d %d 255\n", size, size);
        for (auto px : fb) { uint8_t rgb[3] = {uint8_t(px >> 16), uint8_t(px >> 8), uint8_t(px)}; fwrite(rgb, 1, 3, o); }
        fclose(o);
    };
    auto c8 = [](uint32_t c) { return (c << 3) | (c >> 2); };
    uint32_t lastLynxFrames = 0;
    while (frame < frames) {
        tick();
        bool vblank = sampled.vblank, hblank = sampled.hblank;
        if (vblank) {
            fx = 0; fy = 0;
        } else if (hblank) {
            if (!prevHblank) { fx = 0; fy++; }
        } else if (sampled.dataEnable) {
            if (fx < size && fy < size) fb[fy * size + fx] = (c8(sampled.r) << 16) | (c8(sampled.g) << 8) | c8(sampled.b);
            fx++;
        }
        if (vblank && !prevVblank) {
            frame++;
            if (frame == compareAt) {
                dumpFrame(out + "_" + compareName + ".ppm");
                if (compareName == "state_saved") {
                    savedShot = fb;
                } else {
                    int diff = 0;
                    for (size_t i = 0; i < fb.size(); i++) diff += fb[i] != savedShot[i];
                    fprintf(stderr, "state test: %d of %zu pixels differ between 30 frames after save and after load\n",
                            diff, fb.size());
                }
            }
            if (frame == stateSaveAt || frame == stateLoadAt) {
                // As the firmware does: menu open (no focus), then the on-demand transfer of file 2.
                command({0x0200, 0});
                const uint32_t base = 0x30200000u, words = 66560 / 4;
                if (frame == stateSaveAt) {
                    uint32_t sizeReported = command({0x0302, 2}, 200000000);
                    state.resize(words);
                    for (uint32_t i = 0; i < words; i++) state[i] = hostRead(base + 4 * i);
                    command({0x0303, 2});
                    fprintf(stderr, "state saved: size %u, words %08x %08x %08x %08x (SDRAM model: %08x %08x %08x %08x)\n",
                            sizeReported, state[0], state[1], state[2], state[3], sdram[0x80000], sdram[0x80001],
                            sdram[0x80002], sdram[0x80003]);
                    if (getenv("DUMP_STATE")) {
                        FILE* o = fopen(getenv("DUMP_STATE"), "wb");
                        fwrite(state.data(), 4, state.size(), o);
                        fclose(o);
                    }
                    compareName = "state_saved";
                } else if (getenv("STATE_NO_LOAD")) {
                    compareName = "state_not_loaded";
                } else {
                    command({0x0300, 2});
                    for (uint32_t i = 0; i < words; i++) hostWrite(base + 4 * i, state[i]);
                    command({0x0301, 2, 66560, 0}, 200000000);
                    compareName = "state_loaded";
                }
                command({0x0200, 1});
                compareAt = frame + 30;
            }
            if (starts.count(frame)) heldStart = 6;
            if (pressA.count(frame)) heldA = 6;
            top->io_buttons_start = heldStart > 0;
            top->io_buttons_a = heldA > 0;
            top->io_buttons_up = frame >= holdUpFrom && frame < holdUpTo;
            if (heldStart > 0) heldStart--;
            if (heldA > 0) heldA--;
            if (frame % every == 0) {
                char name[512];
                snprintf(name, sizeof name, "%s_%d.ppm", out.c_str(), frame);
                dumpFrame(name);
                uint32_t lynxFrames = hostRead(0x00001004u);
                fprintf(stderr, "frame %d (%llu cycles): Lynx frames %u (+%u), cartridge reads %u\n", frame,
                        (unsigned long long)cycles, lynxFrames, lynxFrames - lastLynxFrames, hostRead(0x00001008u));
                lastLynxFrames = lynxFrames;
            }
        }
        prevVblank = vblank;
        prevHblank = hblank;
    }
    uint32_t ops = hostRead(0x00001000u);
    fprintf(stderr, "save-state operations: %u, failed: %u\n", ops & 0xFFFF, ops >> 16);
    delete top;
    return 0;
}
