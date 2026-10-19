package gba

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import gba.cart.{CartridgeController, CartridgeInterface, CartridgePrefetch}
import gba.apu.{Apu, ApuOutput}
import gba.cpu.ARM7TDMI
import gba.link.Link
import gba.mem.{BusAccessWidth, BusArbiter, BusTarget, EwramController, SimpleRam}
import gba.ppu.{Ppu, PpuOutput}
import lib.mem.MemoryInterface

object GBA extends App {
  ChiselStage.emitSystemVerilogFile(new GBA, args)
}

class GBA extends Module {
  val io = IO(new Bundle {
    /// Global enable signal
    val enable = Input(Bool())
    /// Whether to enable Game Boy Player functionality
    val configGBPlayer = Input(Bool())

    /// Cartridge interface
    val cartridge = new CartridgeInterface

    /// PPU video output
    val ppu = Output(new PpuOutput)

    /// APU audio output
    val apu = Output(new ApuOutput)

    /// Keypad state
    val keypad = Input(new Keypad.State)

    /// BIOS ROM access
    val biosRom = new BiosRomAccess

    /// EWRAM access. Outside of the module to allow the use of
    /// device-specific storage (e.g. an external SRAM chip).
    val ewram = Flipped(new MemoryInterface(addressWidth = 17, dataWidth = 16))
    val ewramStall = Output(Bool())

    /// Link port
    val link = new Link.Interface

    /// Game Boy Player Rumble
    val gbpRumble = Output(Bool())
  })

  val bus = Module(new mem.Bus(Seq(
    BusTarget("BIOS", 0x0.U(14.W), BusAccessWidth.Word),
    BusTarget("EWRAM", 0x2.U(4.W), BusAccessWidth.Halfword),
    BusTarget("IWRAM", 0x3.U(4.W), BusAccessWidth.Word),
    BusTarget("I/O", 0x4.U(4.W), BusAccessWidth.Word),
    BusTarget("Palette Ram", 0x5.U(4.W), BusAccessWidth.Halfword),
    BusTarget("Video Ram", 0x6.U(4.W), BusAccessWidth.Halfword),
    BusTarget("OAM", 0x7.U(4.W), BusAccessWidth.Word),
    BusTarget("Cart ROM 0", (0x8 >> 1).U(3.W), BusAccessWidth.Halfword),
    BusTarget("Cart ROM 1", (0xA >> 1).U(3.W), BusAccessWidth.Halfword),
    BusTarget("Cart ROM 2", (0xC >> 1).U(3.W), BusAccessWidth.Halfword),
    BusTarget("Cart RAM", (0xE >> 1).U(3.W), BusAccessWidth.Byte),
  )))
  bus.io.enable := io.enable
  val busArbiter = Module(new BusArbiter(numInputs = 5))
  busArbiter.io.enable := io.enable
  bus.io.initiatorPort <> busArbiter.io.outputPort
  val busPortCpu = busArbiter.io.inputPorts(4)

  // MMIO Bus
  val mmio = Module(new MMIO(numTargets = 9))
  mmio.io.enable := io.enable
  bus.io.targetPort(3) <> mmio.io.mem

  // BIOS
  val bios = Module(new Bios)
  bios.io.enable := io.enable
  bios.io.access <> io.biosRom
  bus.io.targetPort(0) <> bios.io.target
  bios.io.busRequest := bus.io.initiatorPort.MREQ
  bios.io.busAddress := bus.io.initiatorPort.ADDR
  bios.io.busIsData := bus.io.initiatorPort.PROT.data

  // IWRAM
  val iwram = Module(new SimpleRam("IWRAM", 32 * 1024, 32.W))
  iwram.io.enable := io.enable
  bus.io.targetPort(2) <> iwram.io.target

  // EWRAM (proxy)
  val ewram = Module(new EwramController)
  ewram.io.enable := io.enable
  ewram.io.numWaits := 2.U
  ewram.io.mem <> io.ewram
  io.ewramStall := ewram.io.stall
  bus.io.targetPort(1) <> ewram.io.target

  // CPU
  val cpu = Module(new ARM7TDMI)
  cpu.io.enable := io.enable
  cpu.io.FIQ := false.B
  busPortCpu <> cpu.io.mem

  // Interrupt manager
  val interrupt = Module(new Interrupt)
  interrupt.io.enable := io.enable
  mmio.targets(0) <> interrupt.io.mmio
  cpu.io.IRQ := interrupt.io.irq
  interrupt.io.peripheralIrq := 0.U.asTypeOf(new Interrupt.Flags)
  // Implement halting by blocking CPU transactions on the bus.
  busArbiter.io.blockInitiators := Cat(interrupt.io.cpuHalt, 0.U(4.W))
  interrupt.io.biosUnlocked := bios.io.unlocked
  // TODO implement cartridge interrupt / DMA request

  // PPU
  val ppu = Module(new Ppu)
  ppu.io.enable := io.enable
  io.ppu := ppu.io.output
  bus.io.targetPort(4) <> ppu.io.paletteRamTarget
  bus.io.targetPort(5) <> ppu.io.vramTarget
  bus.io.targetPort(6) <> ppu.io.oamTarget
  mmio.targets(1) <> ppu.io.mmio
  interrupt.io.peripheralIrq.vblank := ppu.io.irqVblank
  interrupt.io.peripheralIrq.hblank := ppu.io.irqHblank
  interrupt.io.peripheralIrq.vcount := ppu.io.irqVcount

  // Keypad input
  val keypad = Module(new Keypad)
  keypad.io.enable := io.enable
  keypad.io.state := io.keypad
  mmio.targets(2) <> keypad.io.mmio
  interrupt.io.peripheralIrq.keypad := keypad.io.irq

  // DMA
  val dma = Module(new Dma)
  dma.io.enable := io.enable
  dma.io.triggerHblank := ppu.io.dmaTriggerHblank
  dma.io.triggerVblank := ppu.io.dmaTriggerVblank
  dma.io.triggerVideo := ppu.io.dmaTriggerVideo
  dma.io.stopVideo := ppu.io.dmaStopVideo
  mmio.targets(3) <> dma.io.mmio
  interrupt.io.peripheralIrq.dma := dma.io.irq.asUInt
  for (i <- 0 until 4) {
    busArbiter.io.inputPorts(i) <> dma.io.busInitiator(i)
  }

  // Timer
  val timer = Module(new Timer)
  timer.io.enable := io.enable
  mmio.targets(4) <> timer.io.mmio
  interrupt.io.peripheralIrq.timer := timer.io.irq.asUInt

  // APU
  val apu = Module(new Apu)
  apu.io.enable := io.enable
  io.apu := apu.io.output
  mmio.targets(5) <> apu.io.mmio
  mmio.targets(6) <> apu.io.mmio2
  dma.io.triggerFifo := apu.io.dmaTrigger
  apu.io.timerOverflow := timer.io.timerOverflow

  // Cartridge controller
  val cart = Module(new CartridgeController)
  cart.io.enable := io.enable
  cart.io.cartridge <> io.cartridge
  mmio.targets(7) <> cart.io.mmio
  bus.io.targetPort(10) <> cart.io.busTargetRam

  val cartPrefetch = Module(new CartridgePrefetch)
  cartPrefetch.io.enable := io.enable
  cartPrefetch.io.prefetchEnabled := cart.io.prefetchEnabled
  cartPrefetch.io.busTargetRamRequest := cart.io.busTargetRam.request
  cartPrefetch.io.busTargetRamNextRequest := cart.io.busTargetRam.nextRequest
  bus.io.targetPort(7) <> cartPrefetch.io.busTargetRom0
  bus.io.targetPort(8) <> cartPrefetch.io.busTargetRom1
  bus.io.targetPort(9) <> cartPrefetch.io.busTargetRom2
  cart.io.busTargetRom <> cartPrefetch.io.cartInitiatorRom
  cart.io.busTargetRomRegion := cartPrefetch.io.cartInitiatorRomRegion
  cart.io.abortRequest := cartPrefetch.io.cartInitiatorAbortRequest

  // Link port controller
  val link = Module(new Link)
  link.io.enable := io.enable
  io.link <> link.io.port
  mmio.targets(8) <> link.io.mmio
  interrupt.io.peripheralIrq.link := link.io.irq

  // Game Boy Player
  val gameBoyPlayer = Module(new GameBoyPlayer)
  gameBoyPlayer.io.enable := io.enable && io.configGBPlayer
  gameBoyPlayer.io.ppu := ppu.io.output
  gameBoyPlayer.io.link.in := link.io.port.out
  io.gbpRumble := gameBoyPlayer.io.rumble
  when (io.configGBPlayer) {
    when (gameBoyPlayer.io.doKeypadOverride) {
      keypad.io.state := gameBoyPlayer.io.keypadOverride.asTypeOf(new Keypad.State)
    }
    when (gameBoyPlayer.io.doLinkOverride) {
      link.io.port.in := gameBoyPlayer.io.link.out
    }
  }
}
