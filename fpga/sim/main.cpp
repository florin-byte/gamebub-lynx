#include <iostream>
#include <format>
#include <vector>
#include <cstdio>
#include <cmath>
#include <getopt.h>
#include <optional>

#include <SDL2/SDL.h>

#include "audio.hpp"
#include "simulator.hpp"
#include "window.hpp"
#include "recording_writer.hpp"

JoypadState read_joypad_state() {
    const uint8_t* keyboard = SDL_GetKeyboardState(nullptr);
    JoypadState joypad = {};
    joypad.start = keyboard[SDL_SCANCODE_RETURN];
    joypad.select = keyboard[SDL_SCANCODE_RSHIFT];
    joypad.b = keyboard[SDL_SCANCODE_X];
    joypad.a = keyboard[SDL_SCANCODE_Z];
    joypad.down = keyboard[SDL_SCANCODE_DOWN];
    joypad.up = keyboard[SDL_SCANCODE_UP];
    joypad.left = keyboard[SDL_SCANCODE_LEFT];
    joypad.right = keyboard[SDL_SCANCODE_RIGHT];
    joypad.l = keyboard[SDL_SCANCODE_A];
    joypad.r = keyboard[SDL_SCANCODE_S];
    return joypad;
}

void print_help() {
    std::cout << "Usage: sim"
        << " --bios path/to/bios"
        << " [rom path]\n";
    std::exit(1);
}

int main(int argc, char** argv) {
    std::filesystem::path bios_path;
    std::filesystem::path rom_path;
    std::filesystem::path audio_save_path;
    std::filesystem::path record_path;

    const char* const short_opts = "b:";
    const option long_opts[] = {
        {"bios-path", required_argument, nullptr, 'b'},
        {"save-audio", required_argument, nullptr, 'a'},
        {"record", required_argument, nullptr, 'r'},
        {"help", no_argument, nullptr, 'h'},
        {nullptr, no_argument, nullptr, 0}
    };

    while (true) {
        const auto opt = getopt_long(argc, argv, short_opts, long_opts, nullptr);
        if (opt == -1) {
            break;
        }

        switch (opt) {
            case 'b':
                bios_path = optarg;
                break;
            case 'a':
                audio_save_path = optarg;
                break;
            case 'r':
                record_path = optarg;
                break;
            case 'h':
            case '?':
            default:
                print_help();
                break;
        }
    }
    if (optind < argc) {
        rom_path = argv[optind++];
    }
    if (rom_path.empty()) {
        print_help();
    }

    // Initialize SDL.
    SDL_Init(SDL_INIT_VIDEO | SDL_INIT_EVENTS | SDL_INIT_AUDIO);
    Window window(Simulator::width(), Simulator::height());
    Audio audio(audio_save_path);

    Simulator simulator(rom_path, bios_path);
    simulator.reset();

    std::optional<RecordingWriter> recording;
    if (!record_path.empty()) {
        recording.emplace(
            record_path,
            Simulator::width(),
            Simulator::height(),
            Simulator::videoFramerate(),
            Simulator::audioSampleHz()
        );
    }

    bool single_step = false;
    bool paused = false;
    uint64_t frame_timer = 0;
    int frame_counter = 0;
    while (true) {
        // Handle SDL events.
        SDL_Event event;
        if (SDL_PollEvent(&event)) {
            if (event.type == SDL_QUIT) {
                break;
            } else if (event.type == SDL_KEYDOWN) {
                auto key = event.key.keysym;
                bool command = (key.mod & KMOD_GUI) != 0;
                if (key.sym == SDLK_p && command) {
                    paused = !paused;
                } else if (key.sym == SDLK_n && command) {
                    paused = true;
                    single_step = true;
                } else if (key.sym == SDLK_r && command) {
                    std::cout << "Resetting..." << std::endl;
                    simulator.reset();
                }
            }
        }

        // Simulate for a frame.
        if (!paused || single_step) {
            // Simulate
            simulator.set_joypad_state(read_joypad_state());
            simulator.simulate_frame();
            frame_counter++;

            if (recording.has_value()) {
                recording->write_video(simulator.getFramebuffer());
                recording->write_audio(simulator.getAudioSampleBuffer());
            }

            // Audio
            std::vector<int16_t>& samples = simulator.getAudioSampleBuffer();
            audio.push(samples.data(), samples.size());
            samples.clear();
        }
        window.update(simulator.getFramebuffer());
        single_step = false;

        // Update title.
        if (SDL_GetTicks64() - frame_timer >= 1000) {
            char buffer[100];
            snprintf(buffer, 100, "Sim - FPS: %d", frame_counter);
            window.setTitle(buffer);
            frame_counter = 0;
            frame_timer = SDL_GetTicks64();
        }
    }

    SDL_Quit();
}

// Needed for Verilator with some linkers.
double sc_time_stamp() { return 0; }
