# AtariLynx_MiSTer (vendored)

Atari Lynx core from the MiSTer project by Robert Peip (FPGAzumSpass), used by the `lynx` core of this
repository (`fpga/src/main/scala/net/gamebub/core/lynx/`).

- Source: https://github.com/MiSTer-devel/AtariLynx_MiSTer
- Commit: `3708e45` (14 Jul 2026, "Merge pull request #28 from kconger/serial_fix")
- License: GPL-2.0 (`LICENSE`, copied from the upstream repository).
- Files: every `.vhd` of upstream `rtl/` except `dpram.vhd`. Not included: the MiSTer top level
  (`AtariLynx.sv`), the MiSTer framework (`sys/`), `sdram.sv`, `ddram.sv`, `savestate_ui.sv`, the PLL and
  the Altera RAM wrapper `dpram.vhd`, which this repository replaces with a Vivado-inferable entity of the
  same name and ports (`fpga/src/main/vhdl/lynx/dpram.vhd`).
- The video buffering of `AtariLynx.sv` (three frame stores, a fixed 60 Hz scan-out, rotation,
  flickerblend, "sync core to 60 Hz") is reimplemented in Chisel (`LynxVideo.scala`).

To update: copy the same files from a newer upstream checkout, reapply the local changes below and record
the new commit here.

## Local changes

The same changes the gamebub-0.1 fork made to upstream `aff48ebc` (built with Vivado and tested on the
console there), applied to `3708e45` (whose only differences from `aff48ebc` are in `serial.vhd`,
`timer.vhd` and `timer_module.vhd`, the ComLynx fixes):

- `rtl/lynxTop.vhd`: new generic `Softmap_SaveState_ADDR` (the save-state memory base, in 32-bit words;
  upstream hard-codes the MiSTer DDR3 address 58720256 in the `istatemanager` generic map) and new output
  `ss_busy` (`savestate_busy`).
- `rtl/statemanager.vhd`: `savestate_number : in integer range 0 to 3` (was an unconstrained integer; the
  formal/actual range mismatch is rejected by strict front ends).
- `rtl/fpsoverlay.vhd`: intermediate signals with the `overlay` entity's ranges (0..2047, 0..1023) for
  `pixel_x` / `pixel_y` (the 0..159 / 0..101 ports were passed directly).
