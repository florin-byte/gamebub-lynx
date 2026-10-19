// Headless test runner: mooneye (Fibonacci registers) and blargg (serial or cartridge RAM).
#include <cstdlib>
#include <iostream>
#include <string>
#include <vector>
#include "simulator.hpp"

// Save-state round trip through the walker. Reference: run N frames, save (which runs the
// core to the next vblank), run M frames. Test: run N frames, save, run K more frames so the
// machine state moves on, load, run M frames. Both framebuffers must be identical.
static int run_state_test(const char* bios, const char* rom, int n, int k, int m) {
    std::string name = std::filesystem::path(rom).filename().string();
    auto reference = [&]() {
        Simulator sim(rom, bios);
        sim.reset();
        for (int f = 0; f < n; f++) sim.simulate_frame();
        sim.walkerOp(false);
        for (int f = 0; f < m; f++) sim.simulate_frame();
        return sim.getFramebuffer().renderBuffer();
    };
    std::vector<uint8_t> ref = reference();
    if (reference() != ref) {
        std::cout << "SKIP  " << name << "  (two identical runs differ: not deterministic)\n";
        return 2;
    }

    Simulator sim(rom, bios);
    sim.reset();
    for (int f = 0; f < n; f++) sim.simulate_frame();
    if (!sim.walkerOp(false)) {
        std::cout << "FAIL  " << name << "  (save failed)\n";
        return 1;
    }
    size_t words = sim.stateMemory().size();
    for (int f = 0; f < k; f++) sim.simulate_frame();
    // Negative control: without the load, the result must differ (else the test proves nothing).
    bool skip_load = std::getenv("STATE_TEST_SKIP_LOAD") != nullptr;
    if (!skip_load && !sim.walkerOp(true)) {
        std::cout << "FAIL  " << name << "  (load failed)\n";
        return 1;
    }
    for (int f = 0; f < m; f++) sim.simulate_frame();
    const std::vector<uint8_t>& test = sim.getFramebuffer().renderBuffer();
    size_t diff = 0;
    for (size_t i = 0; i < ref.size() && i < test.size(); i++) diff += ref[i] != test[i];
    if (diff == 0 && ref.size() == test.size()) {
        std::cout << "PASS  " << name << "  (state " << words * 4 << " bytes, N=" << n << " K=" << k
                  << " M=" << m << ")\n";
        return 0;
    }
    std::cout << "FAIL  " << name << "  (" << diff << " of " << ref.size() << " framebuffer bytes differ)\n";
    return 1;
}

int main(int argc, char** argv) {
    if (argc >= 4 && std::string(argv[3]) == "--state") {
        int n = argc > 4 ? std::stoi(argv[4]) : 120;
        int k = argc > 5 ? std::stoi(argv[5]) : 30;
        int m = argc > 6 ? std::stoi(argv[6]) : 60;
        return run_state_test(argv[1], argv[2], n, k, m);
    }
    if (argc < 3) { std::cerr << "usage: test_sim <bios> <rom> [frames | --state [N K M]]\n"; return 2; }
    int frames = argc > 3 ? std::stoi(argv[3]) : 6000;
    Simulator sim(argv[2], argv[1]);
    sim.reset();
    std::string name = std::filesystem::path(argv[2]).filename().string();
    int f = 0;
    for (; f < frames; f++) {
        sim.simulate_frame();
        sim.getAudioSampleBuffer().clear();
        const std::string& s = sim.serialLog();
        if (s.find("Passed") != std::string::npos || s.find("Failed") != std::string::npos) break;
        if (sim.mooneyePassed() || sim.mooneyeFailed() || sim.blarggSramResult() >= 0) break;
    }
    const std::string& s = sim.serialLog();
    int sram = sim.blarggSramResult();
    bool pass = sim.mooneyePassed() || s.find("Passed") != std::string::npos || sram == 0;
    const char* why = sim.mooneyePassed() ? "mooneye regs" : sim.mooneyeFailed() ? "mooneye fail regs"
        : sram >= 0 ? "blargg ram" : !s.empty() ? "blargg serial" : "timeout";
    std::cout << (pass ? "PASS  " : "FAIL  ") << name << "  (" << why << ", frame " << f << ")\n";
    return pass ? 0 : 1;
}
