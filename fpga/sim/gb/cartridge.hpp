#pragma once

#include <memory>
#include <vector>
#include <filesystem>

class Cartridge {
public:
    Cartridge(std::filesystem::path rom_path);
    ~Cartridge();

    std::vector<uint8_t> rom;
    std::vector<uint8_t> ram;
    int mbc_type;
    bool has_ram;
    bool has_rumble;
    bool multicart = false;  // MBC1M, detected from the logo at 0x40104
    bool has_timer;

private:
    std::filesystem::path rom_path;
};