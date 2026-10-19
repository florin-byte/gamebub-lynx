package net.gamebub.core.lynx

import chisel3._
import chisel3.util.HasBlackBoxPath

/** The Atari Lynx machine: AtariLynx_MiSTer's `LynxTop` behind the `lynxTopWrap` shim
  * (`fpga/src/main/vhdl/lynx/lynxTopWrap.vhd`), which converts its integer ports to vectors (the
  * Verilog/VHDL boundary takes only std_logic types in Vivado) and ties off cheats, ComLynx, rewind and
  * the FPS overlay. Names and widths must match the VHDL entity exactly.
  *
  * Save-state memory: slot `savestate_number` at 32-bit word 524288 + 0x8000 * slot (the shim's
  * `Softmap_SaveState_ADDR`), i.e. byte 0x0020_0000 for slot 0. */
class LynxSystem extends BlackBox with HasBlackBoxPath {
  override def desiredName = "lynxTopWrap"
  LynxSources.files.foreach(addPath)
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val reset_in = Input(Bool())
    val pause_in = Input(Bool())

    // Cartridge ROM: `rom_req` pulses with `rom_addr` (an offset in the whole .lnx file; the core adds
    // the 64-byte header itself); answer with a one-clock `rom_ack` and `rom_byte` in the same clock.
    val rom_addr = Output(UInt(20.W))
    val rom_byte = Input(UInt(8.W))
    val rom_req = Output(Bool())
    val rom_ack = Input(Bool())

    // File size, and the download port the header detection watches (16-bit words, byte address).
    val romsize = Input(UInt(20.W))
    val romwrite_data = Input(UInt(16.W))
    val romwrite_addr = Input(UInt(20.W))
    val romwrite_wren = Input(Bool())

    // Boot ROM (512 bytes), written while in reset.
    val bios_wraddr = Input(UInt(9.W))
    val bios_wrdata = Input(UInt(8.W))
    val bios_wr = Input(Bool())

    // Frame buffer writes: address y * 160 + x, RGB444 ([11:8] red), one-clock write enable.
    val pixel_out_addr = Output(UInt(14.W))
    val pixel_out_data = Output(UInt(12.W))
    val pixel_out_we = Output(Bool())

    val audio_l = Output(UInt(16.W))
    val audio_r = Output(UInt(16.W))

    // Fast forward (held) and its speed: 0 = 400 %, 1 = 133 %, 2 = 160 %, 3 = 200 %.
    val fastforward = Input(Bool())
    val speedselect = Input(UInt(2.W))

    // Save states: request pulses, the slot, status, and the 64-bit memory bus (one access at a time:
    // `SAVE_out_ena` pulses with the address in 32-bit words, answered by a `SAVE_out_done` pulse).
    val save_state = Input(Bool())
    val load_state = Input(Bool())
    val savestate_number = Input(UInt(2.W))
    val state_loaded = Output(Bool())
    val ss_busy = Output(Bool())
    val SAVE_out_Din = Output(UInt(64.W))
    val SAVE_out_Dout = Input(UInt(64.W))
    val SAVE_out_Adr = Output(UInt(26.W))
    val SAVE_out_rnw = Output(Bool())
    val SAVE_out_ena = Output(Bool())
    val SAVE_out_be = Output(UInt(8.W))
    val SAVE_out_done = Input(Bool())

    // Controls, active high.
    val JoyUP = Input(Bool())
    val JoyDown = Input(Bool())
    val JoyLeft = Input(Bool())
    val JoyRight = Input(Bool())
    val Option1 = Input(Bool())
    val Option2 = Input(Bool())
    val KeyB = Input(Bool())
    val KeyA = Input(Bool())
    val KeyPause = Input(Bool())
  })
}

/** HDL sources of the Lynx core (relative to fpga/, where elaboration runs), added to the build. */
object LynxSources {
  private val vendor = "vendor/lynx_mister/rtl"
  val files: Seq[String] =
    Seq("registerpackage", "export", "bus_savestates", "reg_savestates", "reg_suzy", "reg_mikey")
      .map(f => s"$vendor/$f.vhd") ++
    Seq("src/main/vhdl/lynx/dpram.vhd") ++
    Seq("SyncRamDual", "SyncFifoFallThrough", "lynxboot", "header", "joypad", "serial", "timer_module",
        "timer", "sound_module", "sound", "display_dma", "math", "gpu", "cpu", "memorymux", "cart",
        "dummyregs", "overlay", "fpsoverlay", "lynx_cheats", "savestates", "statemanager", "lynxTop")
      .map(f => s"$vendor/$f.vhd") ++
    Seq("src/main/vhdl/lynx/lynxTopWrap.vhd")
}
