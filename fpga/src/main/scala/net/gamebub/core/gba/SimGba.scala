package net.gamebub.core.gba

import chisel3._
import _root_.circt.stage.ChiselStage
import chisel3.util.SRAM
import gba._
import gba.apu.ApuOutput
import gba.cart.emu.{EmulatedCartridge, VerificationEmulatedCartridge}
import gba.ppu.PpuOutput
import lib.log.Log
import lib.mem.{MemoryInterface, PipelineMemoryInterface}

object SimGba extends App {
  Log.setDefaultLevel(Log.Level.Warning)
  sys.env.get("LOG_LEVELS") match {
    case Some(value) => Log.setLevelsFromString(value)
    case None =>
  }

  ChiselStage.emitSystemVerilogFile(
    new SimGba,
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

class SimGba extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())
    val ppu = Output(new PpuOutput)
    val keypad = Input(new Keypad.State)
    val apu = Output(new ApuOutput)

    val configGBPlayer = Input(Bool())

    val emuCartConfig = Input(new EmulatedCartridge.Config)
    val emuCartRomSize = Input(UInt(25.W))
    val emuCartBackup = Flipped(new MemoryInterface(addressWidth = 17, dataWidth = 8))
    val emuCartStall = Output(Bool())
  })

  val gba = Module(new GBA())
  gba.io.enable <> io.enable
  gba.io.configGBPlayer := io.configGBPlayer
  gba.io.ppu <> io.ppu
  gba.io.keypad <> io.keypad
  gba.io.apu <> io.apu

  // BIOS, to be filled in by verilator simulator
  val biosRom = {
    val rom = SyncReadMem(16 * 1024 / 4, UInt(32.W))
    // dontTouch: hack to ensure Chisel doesn't optimize the mem out
    val temp = dontTouch(WireDefault(false.B))
    when (temp) {
      rom.write(0.U, 0.U)
    }
    rom
  }
  gba.io.biosRom.data := biosRom.read(gba.io.biosRom.address, gba.io.biosRom.read)

  // EWRAM
  val ewram = SRAM.masked(128 * 1024, Vec(2, UInt(8.W)), numReadPorts = 0, numWritePorts = 0, numReadwritePorts = 1)
  val ewramPort = ewram.readwritePorts(0)
  ewramPort.address := gba.io.ewram.address
  ewramPort.enable := gba.io.ewram.enable
  ewramPort.isWrite := gba.io.ewram.write
  ewramPort.mask.get := gba.io.ewram.writeStrobe.asBools
  ewramPort.writeData := gba.io.ewram.dataWrite.asTypeOf(ewramPort.writeData)
  gba.io.ewram.dataRead := ewramPort.readData.asUInt
  gba.io.ewram.done := true.B

  // Emulated cartridge
  val emuCart = Module(new EmulatedCartridge)
  emuCart.io.config := io.emuCartConfig
  emuCart.io.romSize := io.emuCartRomSize
  emuCart.io.imuAccelX := 0x3A0.U
  emuCart.io.imuAccelY := 0x3A0.U
  emuCart.io.imuGyroZ := DontCare  // No gyro support in simulator
  emuCart.io.rtcDataWrite := false.B
  emuCart.io.rtcDataIn := DontCare
  emuCart.io.rtcDataSelect := DontCare
  io.emuCartStall := emuCart.io.stall
  gba.io.cartridge <> emuCart.io.interface
  io.emuCartBackup <> emuCart.io.backup
  emuCart.io.interfaceEnable := gba.io.enable

  // Cartridge ROM, to be filled in by verilator simulation
  val cartRom = {
    val rom = SyncReadMem(32 * 1024 * 1024 / 2, UInt(16.W))
    // dontTouch: hack to ensure Chisel doesn't optimize the mem out
    val temp = dontTouch(WireDefault(false.B))
    when (temp) {
      rom.write(0.U, 0.U)
    }
    rom
  }
  // Simulate ROM access
  val romBusy = RegInit(false.B)
  val romBusyAddress = Reg(UInt(24.W))
  val romCounter = Reg(UInt(4.W))
  val romAccessWaitCycles = 1 // minimum 1
  emuCart.io.rom.ready := true.B
  when (romBusy) {
    emuCart.io.rom.ready := false.B
    romCounter := romCounter - 1.U
    when (romCounter === 0.U) {
      romBusy := false.B
      emuCart.io.rom.ready := true.B
    }
  }
  when (emuCart.io.rom.enable && emuCart.io.rom.ready) {
    romCounter := romAccessWaitCycles.U
    romBusy := true.B
    romBusyAddress := emuCart.io.rom.address
  }
  emuCart.io.rom.dataRead := cartRom.read(romBusyAddress, romBusy && romCounter === 1.U)


  // Link
  gba.io.link.in.si := false.B
  gba.io.link.in.so := true.B
  gba.io.link.in.sc := true.B
  gba.io.link.in.sd := true.B
}