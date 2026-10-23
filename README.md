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

This branch prepares the JSON files for a new unified bitstream. That bitstream is not released yet and its
RTL has not been ported into this public repo yet. Use a released archive until the matching bitstream is
available. I have not tested the new stock save-state path on hardware yet.

- Stock firmware (1.1.0-beta3): use `sdcard/cores/Retro-Bub.Lynx` with the matching new bitstream. State Slot, Save State
  and Load State use four slots in `<rom>.lnxstate` (512 KiB), loaded when the game starts and written when you
  exit the game normally. **States saved during the session are lost if you power off or the game crashes
  before a normal exit.** An empty slot is refused without restoring it.
- My firmware with on-demand files: use `sdcard/savestate-firmware/cores/Retro-Bub.Lynx`. States are still
  written immediately and atomically to `<rom>.ss0` through `.ss3`. The payload format of these individual
  files is unchanged. The new stock container and the individual files are separate; there is no automatic
  conversion between them.

Both folders use the same new bitstream. The `.sav` battery-save configuration is unchanged. The notes below
explain the existing on-demand method; earlier hardware results apply to that method, not the new stock path.

## How the save states work in my firmware

Eli's firmware only moves files between the card and the core at the start (the rom, the `.sav`) and at the exit
(the `.sav` back). A save state has to move while the game runs, so I added "on demand" files to the firmware
(`firmware/handheld/src/core/on_demand.rs`). It is generic, any core on the SD card can use it, only with json.

This is what happens when you press Save State in the menu:

1. The firmware sends the core the normal file command `FILE_READ_START` for the State file (file 2 for the Lynx).
2. The game is already paused, because the menu is open. The core lets only its save state engine (the MiSTer
   one) run, which writes the whole machine into SDRAM, and it answers with the size of the state. If it answers 0, the core has no save states and you get "Not supported by
   the core".
3. The firmware reads the state from the core over SPI and writes it next to the rom as `<rom>.ss0` to `.ss3`,
   depending on the slot. It first writes a temporary file and renames it only at the end, so a cut in the middle
   does not break the old state.
4. `FILE_READ_END`, and the game goes on.

Load State is the same the other way: the firmware writes the `.ssN` file into the core's SDRAM between
`FILE_WRITE_START` and `FILE_WRITE_END`, and at `FILE_WRITE_END` the core restores the machine from it. The
firmware waits at most 3.5 seconds for the core at every step, and shows a message if something fails.

In the json files it looks like this (see `sdcard/savestate-firmware/`):

- `files.json`: a file with `"on_demand": true` and the core's address for it. The firmware does not load or save
  it at start and exit, only when you ask.
- `settings.json`: a list with `"file_slots": 2` (the State Slot, the slot is the number in `.ssN`, and the
  firmware puts a `*` next to the slots that already have a file), and two actions with `"file": 2`, one of them
  with `"load": true` (Save State and Load State).

If you want save states in your own firmware, these are the parts to take: `core/on_demand.rs`, the `on_demand`
flag and the `file` / `file_slots` keys in `core/info.rs`, and the few lines in `core/settings.rs` that send these
actions to `on_demand.rs` instead of writing them to the core.

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
- `scripts/rev2/`: my scripts. `make_vivado_bundle.py` makes the Vivado project for a core and a board,
  `pack_vivado_bundles.py` packs several of them to build at the same time on Windows, `finish_bitstream.py` adds the
  git revision to a bitstream and makes the compressed `.bit.hs`, `lynx_test_sim.sh` is a simulation of the Lynx
  core, `make_logo.py` makes the logo. The other scripts in `scripts/` are Eli's.

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
