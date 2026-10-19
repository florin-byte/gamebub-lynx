#pragma once

#include <filesystem>
#include <string>
#include <vector>

#include "cartridge.hpp"
#include "framebuffer.hpp"
#include "input.hpp"
#include "VSimGameboy.h"

class Simulator {
public:
    Simulator(std::filesystem::path rom_path, std::filesystem::path bios_path);
    ~Simulator();

    void set_joypad_state(JoypadState state);
    void simulate_cycles(uint64_t cycles);
    void simulate_frame();
    void reset();
    Framebuffer& getFramebuffer() { return framebuffer; }
    std::vector<int16_t>& getAudioSampleBuffer();

    const std::string& serialLog() const { return serial_log; }
    bool mooneyePassed() const {
        return top->io_cpuDebug_regB == 3 && top->io_cpuDebug_regC == 5 &&
               top->io_cpuDebug_regD == 8 && top->io_cpuDebug_regE == 13 &&
               top->io_cpuDebug_regH == 21 && top->io_cpuDebug_regL == 34;
    }
    bool mooneyeFailed() const {
        return top->io_cpuDebug_regB == 0x42 && top->io_cpuDebug_regC == 0x42 &&
               top->io_cpuDebug_regD == 0x42 && top->io_cpuDebug_regE == 0x42 &&
               top->io_cpuDebug_regH == 0x42 && top->io_cpuDebug_regL == 0x42;
    }
    // blargg tests that report through cartridge RAM: $A001-3 = DE B0 61, $A000 = status.
    int blarggSramResult() const {
        if (!(blargg_sram[1] == 0xDE && blargg_sram[2] == 0xB0 && blargg_sram[3] == 0x61)) return -1;
        if (blargg_sram[0] == 0x80) return -1;
        return blargg_sram[0] == 0 ? 0 : 1;
    }

    /// Runs a save (load=false) or a load (load=true) through the save-state walker, with the
    /// core paused as on the handheld. Returns false if the walker reports a failure.
    bool walkerOp(bool load);
    /// Contents of the save-state region of SDRAM (one word per 4 bytes).
    std::vector<uint32_t>& stateMemory() { return ss_sdram; }

    static int width() { return 160; }
    static int height() { return 144; }
    static int clockHz() { return 4 * 1024 * 1024; }
    static int audioSampleHz() { return 256 * 1024; }
    static float videoFramerate() { return 59.7275f; }

private:
    void stepFramebuffer();
    void stepAudio();

    Framebuffer framebuffer;

    std::unique_ptr<Cartridge> cart;
    uint64_t cycles = 0;
    VSimGameboy* top = nullptr;
    bool prev_lcd_enabled = false;
    std::vector<int16_t> audioSampleBuffer;
    int audioTimer = 0;
    std::string serial_log;
    bool prev_serial_enable = false;
    uint8_t blargg_sram[256] = {};
    std::vector<uint32_t> ss_sdram;
};
