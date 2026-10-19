package gameboy

import chisel3._
import chisel3.util._
import gameboy.apu.{Apu, ApuOutput}
import gameboy.cart.{CartridgeController, CartridgeInterface}
import gameboy.cpu.Cpu
import gameboy.ppu.{Ppu, PpuOutput}
import gameboy.util.SinglePortRam

/**
 * N10 save-state memory window. A flat address space over the GB internal
 * memories, accessed by the save-state walker WHILE THE CORE IS FROZEN
 * (independent BRAM port, so no contention with the running core). CGB widths
 * (DMG uses a subset). Synchronous read: `dataRead` valid the cycle after access.
 *
 * Layout: WRAM 32K [0x00000..0x07FFF] | VRAM 16K [0x08000..0x0BFFF] |
 *         OAM 160  [0x0C000..0x0C09F] | HRAM 127 [0x0C0A0..0x0C11E].
 */
class SaveStateMemAccess extends Bundle {
  // Internal memory window (WRAM/VRAM/OAM/HRAM), via the second BRAM port.
  val address   = Input(UInt(17.W))
  val enable    = Input(Bool())
  val write     = Input(Bool())
  val dataWrite = Input(UInt(8.W))
  val dataRead  = Output(UInt(8.W))

  // IO register window (0xFFxx peripheral page): the walker drives the existing
  // peripheral bus under freeze (combinational read; write NOT phi-gated so it
  // latches while the clocker is paused). Covers PPU/timer/APU/serial/joypad/
  // sysctrl/DMA regs + CGB palette RAM (via BCPS/BCPD auto-increment). The
  // walker chooses which 0xFF addresses to touch (skips trigger-on-write regs
  // like 0xFF46 OAM-DMA / 0xFF55 HDMA). CPU regs + IE/IF/IME are NOT here.
  val regAddress   = Input(UInt(8.W))
  val regEnable    = Input(Bool())
  val regWrite     = Input(Bool())
  val regDataWrite = Input(UInt(8.W))
  val regDataRead  = Output(UInt(8.W))

  // CGB palette RAM window (0..63 BG, 64..127 OBJ). Combinational read.
  val palAddr      = Input(UInt(7.W))
  val palWrite     = Input(Bool())
  val palDataWrite = Input(UInt(8.W))
  val palDataRead  = Output(UInt(8.W))

  // CPU state (datapath + IE/IF/IME + microcode). Not memory-mapped, so a
  // dedicated blob. `cpuLoad` is a one-shot restore strobe (driven under freeze).
  val cpuLoad = Input(Bool())
  val cpuIn   = Input(new gameboy.cpu.Cpu.SaveState)
  val cpuOut  = Output(new gameboy.cpu.Cpu.SaveState)

  // Misc internal state that is NOT (fully) IO-restorable (sysctrl double-speed,
  // etc.). Grows as the round-trip test reveals more missing state.
  val miscLoad = Input(Bool())
  val miscIn   = Input(new MiscSaveState)
  val miscOut  = Output(new MiscSaveState)
}

/** N10: aggregated misc internal state needing dedicated save/load. */
class MiscSaveState extends Bundle {
  val sysctrl = new SystemControl.SaveState
  val ppu = new gameboy.ppu.Ppu.SaveState
}

class JoypadState extends Bundle {
  val start = Bool()
  val select = Bool()
  val b = Bool()
  val a = Bool()
  val down = Bool()
  val up = Bool()
  val left = Bool()
  val right = Bool()
}

/**
 * The main Gameboy module.
 *
 * External interfaces: cartridge, PPU output
 *
 * The "external bus" contains the cartridge (rom and ram) and work ram.
 * Video ram is on a separate bus (OAM DMA with it doesn't block wram).
 * The peripherals (0xFF**) are separate, and the OAM is also special.
 */
class Gameboy(config: Gameboy.Configuration) extends Module {
  val io = IO(new Bundle {
    // Control signals
    val clockConfig = new ClockConfig
    val isCgb = Input(new Bool)

    /// Boot ROM access
    val bootRom = new BootRomAccess

    // Regular I/O signals
    val cartridge = new CartridgeInterface()
    val ppu = new PpuOutput()
    val joypad = Input(new JoypadState)
    val apu = new ApuOutput
    val serial = new SerialIo

    // Internal state signals
    val tCycle = Output(UInt(2.W))
    val cpuDebug = Output(new Cpu.DebugState)
    val serialDebug = Output(new SerialDebug)

    /** N10: save-state memory window (driven under freeze). */
    val saveState = new SaveStateMemAccess

  })

  // Clock control module
  val clockControl = Module(new ClockControl)
  io.clockConfig <> clockControl.io.clockConfig
  io.tCycle := clockControl.io.clocker.tCycle

  // Module: System control
  val systemControl = Module(new SystemControl(config))
  systemControl.io.isCgb := io.isCgb
  systemControl.io.clocker := clockControl.io.clocker
  clockControl.io.doubleSpeed := systemControl.io.doubleSpeed
  // N10: sysctrl save-state (double-speed etc. not IO-restorable).
  systemControl.io.ssLoad := io.saveState.miscLoad
  systemControl.io.ssIn := io.saveState.miscIn.sysctrl
  io.saveState.miscOut.sysctrl := systemControl.io.ssOut

  // Module: CPU
  val cpu = Module(new Cpu(config))
  cpu.io.clocker := clockControl.io.clocker
  cpu.io.interruptRequest := 0.U.asTypeOf(new Cpu.InterruptFlags)
  systemControl.io.cpuStopState := cpu.io.stopState
  cpu.io.stopStateExit := systemControl.io.cpuStopExit
  io.cpuDebug := cpu.io.debugState
  // N10: CPU save-state blob ↔ core save-state window.
  cpu.io.saveState.load := io.saveState.cpuLoad
  cpu.io.saveState.in := io.saveState.cpuIn
  io.saveState.cpuOut := cpu.io.saveState.out

  // Module: PPU
  val ppu = Module(new Ppu(config))
  ppu.io.clocker := clockControl.io.clocker
  ppu.io.isCgb := io.isCgb
  ppu.io.cgbMode := systemControl.io.cgbMode
  io.ppu := ppu.io.output
  cpu.io.interruptRequest.vblank := ppu.io.vblankIrq
  cpu.io.interruptRequest.lcdStat := ppu.io.statIrq
  // N10: PPU frame-position save-state (LY/tick/LX not IO-restorable).
  ppu.io.ssLoad := io.saveState.miscLoad
  ppu.io.ssIn := io.saveState.miscIn.ppu
  io.saveState.miscOut.ppu := ppu.io.ssOut
  ppu.io.ssPalAddr := io.saveState.palAddr
  ppu.io.ssPalWrite := io.saveState.palWrite
  ppu.io.ssPalDataIn := io.saveState.palDataWrite
  io.saveState.palDataRead := ppu.io.ssPalDataOut

  // Module: OAM DMA
  val oamDma = Module(new OamDma)
  val oamDmaData = WireDefault(0xFF.U(8.W))
  oamDma.io.clocker := clockControl.io.clocker
  val oamDmaSelectExternal = oamDma.io.dmaAddress(15, 8) < 0x80.U || oamDma.io.dmaAddress(15, 8) >= 0xA0.U
  val oamDmaSelectVram = !oamDmaSelectExternal

  // Module: APU
  val apu = Module(new Apu(config))
  apu.io.clocker := clockControl.io.clocker
  apu.io.cgbMode := systemControl.io.cgbMode
  io.apu := apu.io.output

  // Memories
  val workRamSize = 32 * 1024  // 32 KiB in CGB, 8 KiB in DMG
  val workRam = Module(new SinglePortRam(workRamSize)) // DMG: 0xC000 to 0xDFFF
  val videoRamSize = 16 * 1024  // 16 KiB in CGB, 8 KiB in DMG
  val videoRam = Module(new SinglePortRam(videoRamSize)) // 0x8000 to 0x9FFF
  val oam = Module(new SinglePortRam(160)) // 0xFE00 to 0xFE9F
  val highRam = Module(new HighRam)

  // Peripheral: Timer
  val timer = Module(new Timer)
  timer.io.clocker := clockControl.io.clocker
  cpu.io.interruptRequest.timer := timer.io.interruptRequest
  apu.io.divApu := timer.io.divApu
  systemControl.io.divOverflow := timer.io.divOverflow

  // Peripheral: Joypad
  val joypad = Module(new Joypad)
  joypad.io.clocker := clockControl.io.clocker
  joypad.io.state := io.joypad
  cpu.io.interruptRequest.joypad := joypad.io.interruptRequest

  // Peripheral: Serial
  val serial = Module(new Serial(config))
  serial.io.clocker := clockControl.io.clocker
  serial.io.cgbMode := systemControl.io.cgbMode
  serial.io.divSerial := timer.io.divSerial
  serial.io.divSerialFast := timer.io.divSerialFast
  cpu.io.interruptRequest.serial := serial.io.interruptRequest
  io.serial <> serial.io.serial
  io.serialDebug := serial.io.debug

  // Boot rom
  val bootRom = Module(new BootRom(config))
  bootRom.io.isCgb := io.isCgb
  bootRom.io.address := cpu.io.memAddress
  bootRom.io.mapped := systemControl.io.bootRomMapped
  io.bootRom <> bootRom.io.access

  // Module: VRAM DMA (HDMA) - CGB only
  val vramDma = Module(new VramDma)
  val vramDmaData = WireDefault(0xFF.U(8.W))
  vramDma.io.clocker := systemControl.io.clocker
  vramDma.io.cgbMode := systemControl.io.cgbMode
  vramDma.io.hblank := ppu.io.output.hblank
  clockControl.io.vramDmaActive := vramDma.io.active
  when (io.isCgb) {
    // VRAM DMA suspends the CPU while it's active.
    cpu.io.clocker.enable := systemControl.io.clocker.enable && !vramDma.io.active
    cpu.io.clocker.phiPulse := systemControl.io.clocker.phiPulse && !vramDma.io.active
    cpu.io.clocker.pulse4Mhz := systemControl.io.clocker.pulse4Mhz && !vramDma.io.active
  } .otherwise {
    vramDma.io.enabled := false.B
    vramDma.io.address := DontCare
    vramDma.io.dataWrite := DontCare
    vramDma.io.write := DontCare
  }

  // Module: Cartridge controller
  // External bus read/write logic
  // TODO: on CGB wram and cartridge are on separate busses.
  val cart = Module(new CartridgeController)
  io.cartridge <> cart.io.cartridge
  cart.io.clocker := systemControl.io.clocker
  cart.io.busRequester := CartridgeController.BusRequester.cpu
  cart.io.lastClockCycle := clockControl.io.lastClockCycle
  val busAddress = WireDefault(cpu.io.memAddress)
  val busDataWrite = WireDefault(cpu.io.memDataOut)
  val busMemEnable = WireDefault(cpu.io.memEnable)
  val busMemWrite = WireDefault(cpu.io.memWrite)
  val cartRomSelect = busAddress >= 0x0000.U && busAddress < 0x8000.U
  val cartRamSelect = busAddress >= 0xA000.U && busAddress < 0xC000.U
  val workRamSelect = busAddress >= 0xC000.U && busAddress < 0xFE00.U
  val busDataRead = Mux1H(Seq(
    (cartRomSelect || cartRamSelect, cart.io.busDataRead),
    (workRamSelect, workRam.io.dataRead),
  ))
  when (oamDma.io.active && oamDmaSelectExternal) {
    cart.io.busRequester := CartridgeController.BusRequester.oamDma
    busAddress := oamDma.io.dmaAddress
    busMemEnable := true.B
    busMemWrite := false.B
    busDataWrite := DontCare
    oamDmaData := busDataRead
  }
  when (io.isCgb && vramDma.io.active) {
    cart.io.busRequester := CartridgeController.BusRequester.vramDma
    busAddress := vramDma.io.addressSource
    busMemEnable := true.B
    busMemWrite := false.B
    busDataWrite := DontCare
    vramDmaData := busDataRead
  }
  val cpuExternalBusSelect =
    cpu.io.memAddress < 0x8000.U ||
    (cpu.io.memAddress >= 0xA000.U && cpu.io.memAddress < 0xFE00.U)
  cart.io.busEnable := busMemEnable && (cartRomSelect || cartRamSelect)
  cart.io.busWrite := busMemWrite
  cart.io.busIsRom := cartRomSelect
  cart.io.busDataWrite := busDataWrite
  cart.io.busAddress := busAddress

  // Work ram
  workRam.io.address := Cat(Mux(busAddress(12), systemControl.io.wramBank, 0.U), busAddress(11, 0))
  workRam.io.enabled := busMemEnable && workRamSelect
  workRam.io.write := busMemWrite && clockControl.io.clocker.phiPulse
  workRam.io.dataWrite := busDataWrite

  // Video ram
  // PPU locks it during pixel fetch mode (not hblank, vblank, oam search)
  // OAM DMA locks it if reading from this region
  // Priority: OAM DMA > PPU > CPU
  val cpuVideoRamSelect = cpu.io.memAddress >= 0x8000.U && cpu.io.memAddress < 0xA000.U
  when (vramDma.io.active) {
    videoRam.io.address := Cat(systemControl.io.vramBank, vramDma.io.addressDest)
    videoRam.io.enabled := true.B
    videoRam.io.write := clockControl.io.clocker.pulseVramDma
    videoRam.io.dataWrite := vramDmaData
  } .elsewhen (oamDma.io.active && oamDmaSelectVram) {
    videoRam.io.address := Cat(systemControl.io.vramBank, oamDma.io.dmaAddress(12, 0))
    videoRam.io.enabled := true.B
    videoRam.io.write := false.B
    videoRam.io.dataWrite := DontCare
    oamDmaData := videoRam.io.dataRead
  } .elsewhen (ppu.io.vramEnabled) {
    // PPU has control of VRAM bus
    videoRam.io.address := ppu.io.vramAddress
    videoRam.io.enabled := true.B
    videoRam.io.write := false.B
    videoRam.io.dataWrite := DontCare
  } .otherwise {
    // CPU is controlling VRAM bus
    videoRam.io.address := Cat(systemControl.io.vramBank, cpu.io.memAddress(12, 0))
    videoRam.io.enabled := cpu.io.memEnable && cpuVideoRamSelect
    videoRam.io.write := cpu.io.memWrite && clockControl.io.clocker.phiPulse
    videoRam.io.dataWrite := cpu.io.memDataOut
  }
  ppu.io.vramDataRead := videoRam.io.dataRead

  // Oam ram
  // PPU locks it during oam dma and pixel fetch mode (not hblank, vblank)
  // OAM DMA locks it if writing
  // Priority: OAM DMA > PPU > CPU
  val cpuOamSelect = cpu.io.memAddress >= 0xFE00.U && cpu.io.memAddress < 0xFEA0.U
  when (oamDma.io.active) {
    // OAM DMA is writing
    oam.io.address := oamDma.io.dmaAddress(7, 0)
    oam.io.enabled := true.B
    oam.io.write := clockControl.io.clocker.phiPulse
    oam.io.dataWrite := oamDmaData
  } .elsewhen (ppu.io.oamEnabled) {
    // PPU has control of OAM bus
    oam.io.address := ppu.io.oamAddress
    oam.io.enabled := true.B
    oam.io.write := false.B
    oam.io.dataWrite := DontCare
  } .otherwise {
    // CPU is controlling OAM bus
    oam.io.address := cpu.io.memAddress(7, 0)
    oam.io.enabled := cpu.io.memEnable && cpuOamSelect
    oam.io.write := cpu.io.memWrite && clockControl.io.clocker.phiPulse
    oam.io.dataWrite := cpu.io.memDataOut
  }
  ppu.io.oamDataRead := oam.io.dataRead

  // ── N10: save-state memory window → internal memories' second port ────────
  // Driven only under freeze (core idle), so the second BRAM port never races
  // the core's port. Flat map decoded to WRAM/VRAM/OAM/HRAM. Read latency = 1
  // cycle, so the region select is registered for the read mux.
  val ssAddr = io.saveState.address
  val ssWram = ssAddr < 0x8000.U
  val ssVram = ssAddr >= 0x8000.U && ssAddr < 0xC000.U
  val ssOam  = ssAddr >= 0xC000.U && ssAddr < 0xC0A0.U
  val ssHram = ssAddr >= 0xC0A0.U && ssAddr < 0xC11F.U

  workRam.io.ss.address   := ssAddr(14, 0)
  workRam.io.ss.enable    := io.saveState.enable && ssWram
  workRam.io.ss.write     := io.saveState.write && ssWram
  workRam.io.ss.dataWrite := io.saveState.dataWrite

  videoRam.io.ss.address   := (ssAddr - 0x8000.U)(13, 0)
  videoRam.io.ss.enable    := io.saveState.enable && ssVram
  videoRam.io.ss.write     := io.saveState.write && ssVram
  videoRam.io.ss.dataWrite := io.saveState.dataWrite

  oam.io.ss.address   := (ssAddr - 0xC000.U)(7, 0)
  oam.io.ss.enable    := io.saveState.enable && ssOam
  oam.io.ss.write     := io.saveState.write && ssOam
  oam.io.ss.dataWrite := io.saveState.dataWrite

  highRam.io.ss.address   := (ssAddr - 0xC0A0.U)(6, 0)
  highRam.io.ss.enable    := io.saveState.enable && ssHram
  highRam.io.ss.write     := io.saveState.write && ssHram
  highRam.io.ss.dataWrite := io.saveState.dataWrite

  val ssRegionReg = RegNext(Cat(ssHram, ssOam, ssVram, ssWram), 0.U(4.W))
  io.saveState.dataRead := Mux1H(Seq(
    ssRegionReg(0) -> workRam.io.ss.dataRead,
    ssRegionReg(1) -> videoRam.io.ss.dataRead,
    ssRegionReg(2) -> oam.io.ss.dataRead,
    ssRegionReg(3) -> highRam.io.ss.dataRead,
  ))

  // Peripheral bus
  val peripherals = Seq(
    serial.io,
    highRam.io,
    timer.io,
    ppu.io.registers,
    oamDma.io,
    joypad.io,
    systemControl.io,
    apu.io.reg,
    vramDma.io,
  )
  val peripheralSelect = cpu.io.memAddress(15, 8) === 0xFF.U
  for (peripheral <- peripherals) {
    when (io.saveState.regEnable) {
      // N10: save-state walker drives the IO bus (under freeze). Write is NOT
      // phi-gated so the register latches while the clocker is paused.
      peripheral.address := io.saveState.regAddress
      peripheral.enabled := true.B
      peripheral.write := io.saveState.regWrite
      peripheral.dataWrite := io.saveState.regDataWrite
    } .otherwise {
      peripheral.address := cpu.io.memAddress(7, 0)
      peripheral.enabled := peripheralSelect && cpu.io.memEnable
      peripheral.write := cpu.io.memWrite && clockControl.io.clocker.phiPulse
      peripheral.dataWrite := cpu.io.memDataOut
    }
  }
  val peripheralValid = peripheralSelect && VecInit(peripherals.map(_.valid)).asUInt.orR
  val peripheralDataRead = Mux1H(peripherals.map(p => (p.valid, p.dataRead)))
  io.saveState.regDataRead := peripheralDataRead

  // CPU connection to the busses
  val cpuInputs = Seq(
    (bootRom.io.valid, bootRom.io.dataRead),
    (cpuExternalBusSelect && !bootRom.io.valid, busDataRead),
    (peripheralValid, peripheralDataRead),
    (cpuVideoRamSelect, videoRam.io.dataRead),
    (cpuOamSelect, oam.io.dataRead),
  )
  val cpuInputsValid = VecInit(cpuInputs.map(_._1)).asUInt.orR
  cpu.io.memDataIn := Mux1H(cpuInputs ++ Seq((!cpuInputsValid, 0xFF.U)))
}

object Gameboy {
  case class Configuration(
    /** Whether to skip the boot rom */
    skipBootrom: Boolean,
    /** Whether to optimize for simulation, at the cost of potentially breaking synthesis. */
    optimizeForSimulation: Boolean = false,
  )
}