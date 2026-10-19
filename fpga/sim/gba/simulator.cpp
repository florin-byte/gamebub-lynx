#include <iostream>
#include <cstdio>
#include <stdexcept>
#include <cstdlib>

#include "audio.hpp"
#include "common.hpp"
#include "simulator.hpp"

#include "VSimGba___024root.h"

Simulator::Simulator(std::filesystem::path rom_path, std::filesystem::path bios_path)
    : framebuffer(width(), height()), cartridge(rom_path)
{
    this->top = new VSimGba;

    if (bios_path.empty()) {
        std::cerr << "ERROR: must specify bios path\n";
        std::exit(1);
    }
    auto bios = read_file(bios_path);
    if (bios.size() != 16 * 1024) {
        std::cerr << "ERROR: incorrect bios size: " << bios.size() << "\n";
        std::exit(1);
    }
    // Note: assumes little-endian
    memcpy(
        &this->top->rootp->SimGba__DOT__biosRom_ext__DOT__Memory,
        bios.data(),
        16 * 1024
    );

    // Note assumes little-endian
    memcpy(
      &this->top->rootp->SimGba__DOT__cartRom_ext__DOT__Memory,
      cartridge.rom.data(),
      cartridge.rom.size()
    );
}

Simulator::~Simulator()
{
    top->final();
    delete top;
}

void Simulator::reset()
{
    top->io_enable = true;
    top->io_configGBPlayer = false;
    top->io_emuCartConfig_enabled = true;
    top->io_emuCartConfig_backupType = cartridge.config_backup_type;
    top->io_emuCartConfig_backupSize = cartridge.config_backup_size;
    top->io_emuCartConfig_backupAutodetect = cartridge.config_backup_autodetect;
    top->io_emuCartConfig_hasGpio = cartridge.config_has_gpio;
    top->io_emuCartRomSize = cartridge.rom_size - 1;

    top->reset = 1;
    simulate_cycles(1);
    top->reset = 0;
}

void Simulator::set_joypad_state(JoypadState state)
{
    top->io_keypad_start = state.start;
    top->io_keypad_select = state.select;
    top->io_keypad_b = state.b;
    top->io_keypad_a = state.a;
    top->io_keypad_down = state.down;
    top->io_keypad_up = state.up;
    top->io_keypad_left = state.left;
    top->io_keypad_right = state.right;
    top->io_keypad_l = state.l;
    top->io_keypad_r = state.r;
}

void Simulator::simulate_cycles(uint64_t num_cycles)
{
    for (uint64_t i = 0; i < num_cycles; i++) {
        this->stepFramebuffer();
        this->stepAudio();

        // Testing for io.enable
//        top->io_enable = false;
//        top->clock = 0;
//        top->eval();
//        top->clock = 1;
//        top->eval();

        top->io_enable = true;
        top->clock = 0;
        top->eval();
        top->io_enable = !top->io_emuCartStall;
        top->clock = 1;
        top->eval();

        if (top->io_emuCartBackup_enable) {
            int backup_address = top->io_emuCartBackup_address;
            if (backup_address < cartridge.backup.size()) {
                if (top->io_emuCartBackup_write) {
                    cartridge.backup[backup_address] = top->io_emuCartBackup_dataWrite;
//                    fprintf(stderr, "[%llu] ram write addr=0x%x data=0x%x\n", this->cycles, backup_address, top->io_emuCartBackup_dataWrite);
                } else {
                    top->io_emuCartBackup_dataRead = cartridge.backup[backup_address];
//                    fprintf(stderr, "[%llu] ram read addr=0x%x data=0x%x\n", this->cycles, backup_address, top->io_emuCartBackup_dataRead);
                }
            }
            top->io_emuCartBackup_done = 1;
        }

        this->cycles++;

//        fprintf(stderr, "cycle=%llu", this->cycles);
//        if (cycles == 473799) {
//            size_t size = 256 * 1024;
//            std::vector<uint8_t> dump;
//            dump.resize(size);
//            memcpy(
//                dump.data(),
//                reinterpret_cast<uint8_t*>(&this->top->rootp->SimGba__DOT__gba__DOT__ewram__DOT__mem_ext__DOT__Memory),
//                size
//            );
//            write_file("/tmp/dump.bin", dump);
//            exit(0);
//        }
    }
}

void Simulator::stepFramebuffer()
{
    framebuffer.update(top->io_ppu_hblank, top->io_ppu_vblank);

    if (top->io_ppu_valid && !top->io_ppu_hblank && !top->io_ppu_vblank) {
      framebuffer.pushBGR(top->io_ppu_pixel);
    }
}

void Simulator::simulate_frame()
{
    simulate_cycles(280896);

// Testing: save OBJ vram
//    size_t size = 16 * 1024;
//    std::vector<uint8_t> dump;
//    dump.resize(size * 2);
//    memcpy(
//        dump.data(),
//        reinterpret_cast<uint8_t*>(&this->top->rootp->SimGba__DOT__gba__DOT__ppu__DOT__vram__DOT__memObjLo__DOT__mem_mem_0_ext__DOT__Memory),
//        size
//    );
//    memcpy(
//            dump.data() + size,
//            reinterpret_cast<uint8_t*>(&this->top->rootp->SimGba__DOT__gba__DOT__ppu__DOT__vram__DOT__memObjHi__DOT__mem_mem_0_ext__DOT__Memory),
//            size
//        );
//    write_file("/tmp/dump_obj.bin", dump);
}

void Simulator::stepAudio()
{
    audioTimer++;
    if (audioTimer == (clockHz() / audioSampleHz())) {
        int16_t mask = 1U << (10 - 1);
        int16_t left = (top->io_apu_left ^ mask) - mask;
        int16_t right = (top->io_apu_right ^ mask) - mask;

        audioTimer = 0;
        audioSampleBuffer.push_back(left * 8);
        audioSampleBuffer.push_back(right * 8);
    }
}

std::vector<int16_t>& Simulator::getAudioSampleBuffer()
{
    return audioSampleBuffer;
}
