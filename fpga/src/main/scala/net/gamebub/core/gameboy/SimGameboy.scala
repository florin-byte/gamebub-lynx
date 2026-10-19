package net.gamebub.core.gameboy

import chisel3._
import _root_.circt.stage.ChiselStage
import gameboy.apu.ApuOutput
import gameboy.cart.emu.{EmuCartConfig, EmuCartridge, EmuCartridgeDataAccess, EmuMbc}
import gameboy.{ClockConfig, Gameboy, JoypadState, SerialDebug}
import lib.mem.MemoryInterface
import gameboy.cpu.Cpu
import gameboy.ppu.PpuOutput

object SimGameboy extends App {
  ChiselStage.emitSystemVerilogFile(
    new SimGameboy,
    args,
    firtoolOpts = Array(
      "--preserve-aggregate=1d-vec",
      "-enable-layers=Verification",
      "-enable-layers=Verification.Assert",
      "-enable-layers=Verification.Assume",
      "-enable-layers=Verification.Cover",
    )
  )
}

class SimGameboy extends Module {
  val io = IO(new Bundle {
    val clockConfig = new ClockConfig

    val ppu = new PpuOutput
    val joypad = Input(new JoypadState)
    val apu = new ApuOutput

    // Emulated cartridge data access interface
    val cartConfig = Input(new EmuCartConfig())
    val dataAccess = new EmuCartridgeDataAccess()

    // Test taps for the headless test runner (mooneye registers, blargg serial output).
    val cpuDebug = Output(new Cpu.DebugState)
    val serialDebug = Output(new SerialDebug)

    // Save-state walker control, and its SDRAM port (modelled by the simulator).
    val saveState = new Bundle {
      val savePulse = Input(Bool())
      val loadPulse = Input(Bool())
      /** Resets the Gameboy (not the cartridge), as before a load on the handheld. */
      val resetCore = Input(Bool())
      val busy = Output(Bool())
      val loaded = Output(Bool())
      val aborted = Output(Bool())
      val sdram = Flipped(new MemoryInterface(addressWidth = 25, dataWidth = 32))
    }
  })

  // Gameboy
  val gameboyConfig = Gameboy.Configuration(
    skipBootrom = false,
    optimizeForSimulation = true,
  )
  val gameboy = Module(new Gameboy(gameboyConfig))
  gameboy.io.isCgb := true.B
  when (io.saveState.resetCore) {
    gameboy.reset := true.B
  }
  val ssWalker = Module(new GbSaveStateWalker(
    cpuBits = gameboy.io.saveState.cpuOut.getWidth,
    miscBits = gameboy.io.saveState.miscOut.getWidth,
  ))
  ssWalker.io.savePulse := io.saveState.savePulse
  ssWalker.io.loadPulse := io.saveState.loadPulse
  ssWalker.io.slotBase := HandheldGameboy.SaveStateSdramBase.U
  io.saveState.busy := ssWalker.io.busy
  io.saveState.loaded := ssWalker.io.loaded
  io.saveState.aborted := ssWalker.io.aborted
  io.saveState.sdram <> ssWalker.io.sdram
  // The walker runs the core up to the next vblank before capturing a state.
  gameboy.io.clockConfig.enable := io.clockConfig.enable || ssWalker.io.coreClockEnable
  gameboy.io.clockConfig.provide8Mhz := io.clockConfig.provide8Mhz
  io.clockConfig.need8Mhz := gameboy.io.clockConfig.need8Mhz
  io.ppu <> gameboy.io.ppu
  io.joypad <> gameboy.io.joypad
  io.apu <> gameboy.io.apu
  io.cpuDebug := gameboy.io.cpuDebug
  io.serialDebug := gameboy.io.serialDebug

  // Boot ROM, to be filled in by verilator simulator
  val bootRom = {
    val rom = SyncReadMem(2048 + 256, UInt(8.W))
    // dontTouch: hack to ensure Chisel doesn't optimize the mem out
    val temp = dontTouch(WireDefault(false.B))
    when (temp) {
      rom.write(0.U, 0.U)
    }
    rom
  }
  gameboy.io.bootRom.data := bootRom.read(gameboy.io.bootRom.address, gameboy.io.bootRom.read)

  // Disconnected serial
  gameboy.io.serial.clockIn := true.B
  gameboy.io.serial.in := true.B

  // Emulated cartridge
  val emuCart = Module(new EmuCartridge(4 * 1024 * 1024))
  gameboy.io.cartridge <> emuCart.io.cartridge
  emuCart.io.dataAccess <> io.dataAccess
  emuCart.io.config := io.cartConfig
  emuCart.io.tCycle := gameboy.io.tCycle
  emuCart.io.rtcAccess.writeEnable := false.B
  emuCart.io.rtcAccess.writeState := DontCare
  emuCart.io.rtcAccess.latchSelect := DontCare
  emuCart.io.imu := DontCare
  GbSaveStateWalker.connect(ssWalker, gameboy, emuCart)
}