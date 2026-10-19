# Atari Lynx core for the Game Bub

An unofficial Atari Lynx core for the [Game Bub](https://github.com/elipsitz/gamebub), loaded from the SD card:
MiSTer's [AtariLynx_MiSTer](https://github.com/MiSTer-devel/AtariLynx_MiSTer) by Robert Peip (commit `3708e45`) on
Eli Lipsitz's Game Bub framework (1.1.0-beta2), ported by Florin. Not affiliated with the Game Bub project.

This repository is Florin's fork of the Game Bub repository, reduced to what the Lynx core needs: the FPGA framework
and platform, the Lynx core (`net.gamebub.core.lynx.HandheldLynx`), the firmware and the build scripts.

A ready-to-use rev4 bitstream is attached to the [releases](../../releases).

## Status

| | |
|---|---|
| Game Bub rev2 | built and tested on the console: games, rotation, the 60 Hz scan-out with three frame stores, save states (with the save-state firmware, below) |
| Game Bub rev4 | **works on rev4 hardware**: a rev4 tester built these sources with Vivado 2018.2 on Windows and ran them (October 2026). The released bitstream: Vivado 2025.2, WNS +0.586 ns, 0 critical warnings, 69.5 / 135 BRAM |

rev4 needed one fix in the LCD aspect scaler: it fitted the picture to the panel's 800 columns and then moved it by
rev4's `displayOffsetX` (-28, the picture centered on column 372), so the Lynx picture at the full height (752
columns) started at column -4 and the core did not elaborate. The scale now fits the width that stays on the panel
around that center (744 columns); see `LcdAspectScaler.geometry` and the rev4 test in `LcdAspectScalerSpec`.

## Where the core lives

- `fpga/src/main/scala/net/gamebub/core/lynx/`: the Chisel glue (`HandheldLynx.scala`) and the video buffering of the
  MiSTer top level reimplemented in Chisel (`LynxVideo.scala`: three frame stores, 60 Hz scan-out, rotation,
  flickerblend);
- `fpga/vendor/lynx_mister/`: the vendored MiSTer RTL (`LICENSE`: GPL-2.0); its `README.md` lists every local change;
- `fpga/src/main/vhdl/lynx/`: `dpram.vhd` (a Vivado-inferable replacement for the Altera RAM wrapper) and
  `lynxTopWrap.vhd`;
- `fpga/src/main/scala/platform/handheld/LcdAspectScaler.scala`, `lib/video/PolyphaseScaler.scala`,
  `net/gamebub/framework/interface/VideoScalerV0.scala`: the platform's aspect scaler, which the core uses for the LCD;
- `sdcard/`: the core's files for the SD card (see `sdcard/README.md`).

## Building the bitstream

Requirements: Java 17 (Chisel, through `fpga/mill`), Python 3 with `edalize==0.6.7`
(`fpga/framework/requirements.txt`), Vivado (2025.2 and 2018.2 were used). On an Apple Silicon Mac the `firtool`
that Chisel downloads needs Rosetta 2. On Windows the scripts run `fpga/mill.bat` (Mill's Windows launcher) instead
of `fpga/mill`. The HDMI sources (`fpga/verilog/hdmi`, a submodule upstream) are included as files.

1. Elaborate and generate the Vivado project (any OS with Java and Python), from the repository root:

   ```bash
   python3 scripts/rev2/make_vivado_bundle.py --core-class net.gamebub.core.lynx.HandheldLynx --name lynx --target gamebub_rev4
   ```

   The result is `dist/vivado/lynx-gamebub_rev4.zip` (also unpacked next to it): the generated SystemVerilog, the
   MiSTer VHDL, the board's constraints and the Vivado Tcl scripts. The script lives in `scripts/rev2/` for
   historical reasons; `--target` selects the board (`gamebub_rev1` to `gamebub_rev4`).

2. Build with Vivado, in the unpacked bundle:
   - Linux: `make` (the Makefile Edalize generates);
   - Windows: `build.bat` (set `VIVADO` to `vivado.bat` to pick an installation).

   The result is `lynx-gamebub_rev4.bit` in the bundle folder.

`make_vivado_bundle.py` gives Vivado each file's language and VHDL standard explicitly: the Lynx VHDL is read as
VHDL-93 (`dpram.vhd` uses a shared variable for its true dual-port RAM, which VHDL-2008 only allows as a protected
type). The framework's own `fpga/scripts/build_core.py` hands Vivado every generated file as SystemVerilog; it was not
checked with the Lynx core.

Build from a git clone: the firmware's build reads the commit hash (`build.rs` runs `git rev-parse HEAD`).

## SD card

- Copy `sdcard/cores/Retro-Bub.Lynx/` (stock firmware) or `sdcard/savestate-firmware/cores/Retro-Bub.Lynx/` (see
  Firmware) to the card's `cores/` folder, and put the uncompressed bitstream in it, next to the three JSON files
  (`core.json` names `lynx-gamebub_rev4.bit` for rev4 and `lynx-gamebub_rev2.bit` for rev2).
- **The Lynx boot ROM is required and not included** (it is Atari's code): `system/lynxboot.img`, 512 bytes, dumped
  from your own Lynx. The firmware loads it into the core at start (`lynxboot.vhd` is an empty 512-byte RAM); without
  the file the core does not start.
- Games: `.lnx` (with the header) or `.lyx` (headerless). The Lynx has no battery saves.

Settings:

- Rotation: None / Left / Right, chosen by hand (a core setting, not read from the `.lnx` header);
- Flickerblend: Off / 2 Frames / 3 Frames (blends consecutive frames);
- Fast Forward (R): the speed while R is held (400 %, 200 %, 160 %, 133 %, Off); the sound is muted meanwhile;
- Sync to 60 Hz: the core waits for the display's 60 Hz frame after each of its frames (MiSTer's "Sync core to
  60 Hz"; the game sets the Lynx's own frame rate, between about 50 and 75 Hz);
- Reset; with the save-state firmware also State Slot (1-4), Save State, Load State (`<rom>.ss0`..`.ss3` next to
  the ROM).

## Firmware and save states

The core can save and load states (MiSTer's save-state engine is in the bitstream), but moving a state between the
core and the SD card while a game runs needs firmware support that the stock Game Bub firmware does not have.

- **Stock firmware (Game Bub 1.1): use `sdcard/cores/Retro-Bub.Lynx/`.** Everything but save states works.
  Do not use the save-state folder with it: that firmware ignores the save-state keys, treats the State file as an
  ordinary file and fails to save it when the game is left (an error in the log).
- **Save states: `sdcard/savestate-firmware/` with the firmware in `firmware/handheld/`.** It adds on-demand core
  files (transferred while the game runs: `core/on_demand.rs`, the `on_demand` file flag and the `file` /
  `file_slots` setting keys in `core/info.rs` and `core/settings.rs`). Tested on rev2. For rev4 it compiles
  (`cargo check --features rev4`) but has not been run on a rev4. Build it as the stock firmware (ESP-IDF 5.4.3,
  `cargo build --release --features rev4`).

## License

- Florin's own code in this repository (the Lynx glue, the aspect scaler, the build and simulation scripts, the
  firmware additions; listed in `THIRD-PARTY.md`): GPL-2.0-or-later (`LICENSE-GPL-V2`, `LICENSE-GPL-V3`). Files
  inside `firmware/` are part of a GPL-3.0-only program, so the firmware as a whole is GPL-3.0-only.
- Eli Lipsitz's Game Bub framework and firmware, and Florin's changes to those files: their own licenses (FPGA source
  in `fpga/`: CERN-OHL-S-2.0, `LICENSE-CERN-OHL-S`; firmware and scripts: GPL-3.0-only, `LICENSE-GPL-V3`).
- Atari Lynx core: GPL-2.0 (`fpga/vendor/lynx_mister/LICENSE`).

Every third-party component, its authors and its license: `THIRD-PARTY.md`.

"Game Bub" is Eli Lipsitz's project and trademark; this core is a community port and uses neither the name nor the
logo as its own. "Atari" and "Lynx" are trademarks of their owners. No BIOS and no games are included.
