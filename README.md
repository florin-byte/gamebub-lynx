# Atari Lynx core for the Game Bub

This is the Atari Lynx core for the [Game Bub](https://github.com/elipsitz/gamebub), loaded from the SD card. It is
the MiSTer core ([AtariLynx_MiSTer](https://github.com/MiSTer-devel/AtariLynx_MiSTer) by Robert Peip) glued to Eli's
Game Bub framework (1.1.0-beta2). It is not an official Game Bub core, I just ported it.

The repo is my fork of the Game Bub code, cut down to what the Lynx needs: the FPGA framework, the Lynx core, the
firmware and the scripts to build it.

If you only want to play, take the zip from the [releases](../../releases), you don't need to build anything.

## Does it work?

- rev2: yes, this is what I use myself. Games, rotation and save states (with my firmware) work.
- rev4: yes, tested by other users, but not by me, because I don't own a rev4 Game Bub, only a rev2 one. The
  bitstream in the release is built on my machine with Vivado 2025.2 (timing is met, no critical warnings).

For rev4 I had to fix the LCD scaler. On rev4 the picture is centered on column 372 of the 800 wide panel, not on
400 (`displayOffsetX = -28` in Eli's code), and the scaler was making the Lynx picture 752 wide, so it started off
the screen and the core did not even build. Now the scaler keeps the picture inside 744 columns around that center.
If you want to look at it: `LcdAspectScaler.geometry` and the rev4 test in `LcdAspectScalerSpec`.

## Install

1. Copy `sdcard/cores/Retro-Bub.Lynx` to the `cores/` folder on your SD card and put the bitstream next to the
   json files (`lynx-gamebub_rev4.bit` for rev4, `lynx-gamebub_rev2.bit` for rev2).
2. You need your own Lynx boot rom: `system/lynxboot.img`, 512 bytes. It is not here and it will not be, it is
   Atari's code, so dump it from your own Lynx. Without it the core will not start.
3. Games are `.lnx` or `.lyx`.

## Settings

- Rotation: None / Left / Right. You set it yourself for the games played with the Lynx on the side.
- Flickerblend: Off / 2 Frames / 3 Frames.
- Fast Forward (R): how fast it goes while you hold R (400 %, 200 %, 160 %, 133 % or Off). No sound during it.
- Sync to 60 Hz: the core waits for the screen after every frame, like "Sync core to 60 Hz" on MiSTer. The Lynx
  games run between about 50 and 75 Hz.
- Reset.

## Save states

The save states are in the bitstream (the MiSTer save state engine), but they need fw changes as well, to move the
state between the core and the SD card while the game runs. Eli's firmware does not have that, so on the stock
firmware there are no save states.

- Stock firmware: use `sdcard/cores/Retro-Bub.Lynx`. Everything works except save states. Don't use the other
  folder with it, the State file will give an error when you exit the game.
- My firmware (`firmware/handheld/`): use `sdcard/savestate-firmware/cores/Retro-Bub.Lynx`. Then you have State
  Slot, Save State and Load State (`.ss0` to `.ss3` next to the rom). I tested it on rev2. For rev4 it compiles
  (`cargo check --features rev4`) but nobody ran it on a rev4 yet. The part that matters is
  `firmware/handheld/src/core/on_demand.rs`, plus the `on_demand`, `file` and `file_slots` keys in `core/info.rs`
  and `core/settings.rs`. Build it like the stock one (ESP-IDF 5.4.3, `cargo build --release --features rev4`).

## Building the bitstream

What you need: Java 17, Python 3 with `edalize==0.6.7` (see `fpga/framework/requirements.txt`) and Vivado (I use
2025.2). On an Apple Silicon Mac the `firtool` that Chisel downloads needs Rosetta 2.
On Windows the scripts use `fpga/mill.bat` by themselves.

1. Generate the Vivado project, from the root of the repo:

   ```bash
   python3 scripts/rev2/make_vivado_bundle.py --core-class net.gamebub.core.lynx.HandheldLynx --name lynx --target gamebub_rev4
   ```

   You get `dist/vivado/lynx-gamebub_rev4.zip` (and the same folder unpacked next to it). The script is in
   `scripts/rev2/` only because it started there, `--target` is the board (`gamebub_rev1` to `gamebub_rev4`).

2. Run Vivado in that folder: `make` on Linux, `build.bat` on Windows (set `VIVADO` to your `vivado.bat` if you have
   more than one). At the end you have `lynx-gamebub_rev4.bit`.

Build from a git clone, the firmware build needs the commit hash.

I use `make_vivado_bundle.py` and not Eli's `fpga/scripts/build_core.py` because it tells Vivado the right language
for every file. The Lynx VHDL has to be read as VHDL-93 (`dpram.vhd` has a shared variable for the dual port RAM).
I did not try the Lynx with `build_core.py`.

## Where things are

- `fpga/src/main/scala/net/gamebub/core/lynx/`: my glue for the core. `LynxVideo.scala` is the video part of the
  MiSTer top, done again in Chisel (3 frame buffers, 60 Hz out, rotation, flickerblend).
- `fpga/vendor/lynx_mister/`: the MiSTer code. Its `README.md` says what I changed and why.
- `fpga/src/main/vhdl/lynx/`: `dpram.vhd` (a RAM Vivado understands, instead of the Altera one) and `lynxTopWrap.vhd`.
- `fpga/src/main/scala/platform/handheld/LcdAspectScaler.scala`, `lib/video/PolyphaseScaler.scala`,
  `net/gamebub/framework/interface/VideoScalerV0.scala`: the scaler.
- `sdcard/`: the files for the SD card.

## License and credits

- My code here (the Lynx glue, the scaler, the scripts, what I added in the firmware; the list is in
  `THIRD-PARTY.md`) is GPL-2.0-or-later (`LICENSE-GPL-V2`, `LICENSE-GPL-V3`). The firmware as a whole stays
  GPL-3.0-only, because it is Eli's GPL-3.0-only program.
- Eli's framework and firmware, also with my changes in his files, keep his licenses: CERN-OHL-S-2.0 for `fpga/`
  (`LICENSE-CERN-OHL-S`), GPL-3.0-only for the firmware and the scripts (`LICENSE-GPL-V3`).
- The Lynx core is GPL-2.0 (`fpga/vendor/lynx_mister/LICENSE`).

Everybody whose code is in here, and under which license: `THIRD-PARTY.md`. Thanks to Robert Peip for the Lynx core,
to Eli for the Game Bub, to minuitfranck and Jeremy O'Brien for the parts I took from their ports, and to Toady M for
testing on rev4.

"Game Bub" is Eli's project and trademark, this is a community port. "Atari" and "Lynx" belong to their owners.
No bios and no games in here.
