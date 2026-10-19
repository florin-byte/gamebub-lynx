#pragma once

#include <filesystem>

#include "framebuffer.hpp"
#include "input.hpp"
#include "VSimGba.h"
#include "cartridge.hpp"

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

    static int width() { return 240; }
    static int height() { return 160; }
    static int clockHz() { return 16 * 1024 * 1024; }
    static int audioSampleHz() { return 32 * 1024; }
    static float videoFramerate() { return 59.7275f; }

private:
    void stepFramebuffer();
    void stepAudio();

    VSimGba* top = nullptr;

    uint64_t cycles = 0;
    Framebuffer framebuffer;
    std::vector<int16_t> audioSampleBuffer;
    int audioTimer = 0;

    Cartridge cartridge;
};
