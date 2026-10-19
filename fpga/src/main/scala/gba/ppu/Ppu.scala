package gba.ppu

import chisel3._
import chisel3.util._
import gba.{MMIO, MmioMap, MmioTarget}
import gba.mem.TargetInterface
import lib.log.Logger

class PpuOutput extends Bundle {
  /** Output pixel value (B G R) */
  val pixel = UInt(15.W)
  /** Whether the pixel this clock is valid */
  val valid = Bool()
  /** Whether the PPU is in hblank */
  val hblank = Bool()
  /** Whether the PPU is in vblank */
  val vblank = Bool()
}

class Ppu extends Module {
  val io = IO(new Bundle {
    /// Global enable
    val enable = Input(Bool())

    /// PPU output
    val output = Output(new PpuOutput)

    /// VRAM memory target for CPU
    val vramTarget = new TargetInterface(16.W)
    /// Palette memory target for CPU
    val paletteRamTarget = new TargetInterface(16.W)
    /// OAM memory target for CPU
    val oamTarget = new TargetInterface(32.W)

    /// MMIO access
    val mmio = new MmioTarget()

    /// Interrupts
    val irqVblank = Output(Bool())
    val irqHblank = Output(Bool())
    val irqVcount = Output(Bool())

    /// DMA triggers
    val dmaTriggerVblank = Output(Bool())
    val dmaTriggerHblank = Output(Bool())
    val dmaTriggerVideo = Output(Bool())
    val dmaStopVideo = Output(Bool())
  })
  val logger = Logger("ppu", enable = io.enable)

  val regDisplayControl = RegInit(0.U.asTypeOf(new PpuRegisters.DisplayControl))
  val regIrqEnableVblank = RegInit(false.B)
  val regIrqEnableHblank = RegInit(false.B)
  val regIrqEnableVcount = RegInit(false.B)
  val regVcount = RegInit(0.U(8.W))
  val regBgControl = RegInit(VecInit.fill(4)(0.U.asTypeOf(new PpuRegisters.BackgroundControl)))
  val regBgOffX = RegInit(VecInit.fill(4)(0.U(16.W)))
  val regBgOffY = RegInit(VecInit.fill(4)(0.U(16.W)))
  val regBgAff = RegInit(VecInit.fill(2)("h0100_0000_0000_0100".U.asTypeOf(new PpuRegisters.AffineParams)))
  val regBgAffX = RegInit(VecInit.fill(2)(0.U.asTypeOf(new PpuRegisters.AffineReferencePoint)))
  val regBgAffY = RegInit(VecInit.fill(2)(0.U.asTypeOf(new PpuRegisters.AffineReferencePoint)))
  val regWin0Bounds = RegInit(0.U.asTypeOf(new PpuRegisters.WindowBounds))
  val regWin1Bounds = RegInit(0.U.asTypeOf(new PpuRegisters.WindowBounds))
  val regWin0Control = RegInit(0.U.asTypeOf(new PpuRegisters.WindowControl))
  val regWin1Control = RegInit(0.U.asTypeOf(new PpuRegisters.WindowControl))
  val regWinOutControl = RegInit(0.U.asTypeOf(new PpuRegisters.WindowControl))
  val regWinObjControl = RegInit(0.U.asTypeOf(new PpuRegisters.WindowControl))
  val regBlendControl = RegInit(0.U.asTypeOf(new PpuRegisters.BlendControl))
  val regBlendA = RegInit(0.U(5.W))
  val regBlendB = RegInit(0.U(5.W))
  val regBlendFade = RegInit(0.U(5.W))
  val regMosaic = RegInit(0.U.asTypeOf(new PpuRegisters.MosaicSize))

  /// VRAM: 96KiB, 16-bit access without byte strobe (internally split into multiple banks for bg/obj)
  val vram = Module(new Vram)
  vram.io.enable := io.enable
  vram.io.forceBlank := regDisplayControl.forceBlank
  vram.io.displayMode := regDisplayControl.mode
  vram.io.cpuTarget <> io.vramTarget

  val paletteRam = Module(new PpuMem("pal", 1024, 16.W))
  paletteRam.io.enable := io.enable
  paletteRam.io.forceBlank := regDisplayControl.forceBlank
  paletteRam.io.ignoreByteWrites := false.B
  paletteRam.io.cpuTarget <> io.paletteRamTarget

  val oam = Module(new PpuMem("oam", 1024, 32.W))
  oam.io.enable := io.enable
  oam.io.forceBlank := regDisplayControl.forceBlank
  oam.io.ignoreByteWrites := true.B
  oam.io.cpuTarget <> io.oamTarget

  val scanline = RegInit(0.U(8.W))
  val tick = RegInit(0.U(11.W))
  val vcountHit = scanline === regVcount
  val isHblank = tick >= 1006.U
  val isVblank = scanline >= 160.U && scanline < 227.U

  when (io.enable) {
    when (tick < (1232 - 1).U) {
      tick := tick + 1.U
    } .otherwise {
      tick := 0.U
      when (scanline < (228 - 1).U) {
        scanline := scanline + 1.U
      } .otherwise {
        scanline := 0.U
      }
    }
  }

  // Background renderer
  val bgRender = Module(new BackgroundRenderer)
  bgRender.io.enable := io.enable
  bgRender.io.displayControl := regDisplayControl
  bgRender.io.bgControl := regBgControl
  bgRender.io.bgOffX := regBgOffX
  bgRender.io.bgOffY := regBgOffY
  bgRender.io.bgAff := regBgAff
  bgRender.io.bgAffX := regBgAffX
  bgRender.io.bgAffY := regBgAffY
  bgRender.io.writeAffX := VecInit.fill(2)(false.B)
  bgRender.io.writeAffY := VecInit.fill(2)(false.B)
  bgRender.io.mosaicY := regMosaic.bgY
  bgRender.io.tick := tick
  bgRender.io.scanline := scanline
  bgRender.io.vram <> vram.io.portBG

  // I/O registers
  io.mmio <> MmioMap(
    0x0 -> MmioMap.Entry.rw(regDisplayControl),
    0x4 -> MmioMap.Entry(
      // DISPSTAT and VCOUNT
      MmioMap.ReadFn(_ => {
        val status = Wire(new PpuRegisters.DisplayStatus)
        status.vblank := isVblank
        status.hblank := isHblank
        status.vcountHit := vcountHit
        status.irqVblank := regIrqEnableVblank
        status.irqHblank := regIrqEnableHblank
        status.irqVcount := regIrqEnableVcount
        val data = Cat(
          0.U(8.W),
          scanline,
          regVcount,
          status.asUInt.pad(8),
        )
        (data, true.B)
      }),
      MmioMap.WriteFn((enable, data, mask) => {
        when (enable) {
          when (mask(0)) {
            val newData = data(7, 0).asTypeOf(new PpuRegisters.DisplayStatus)
            regIrqEnableVblank := newData.irqVblank
            regIrqEnableHblank := newData.irqHblank
            regIrqEnableVcount := newData.irqVcount
          }
          when (mask(1)) {
            regVcount := data(15, 8)
          }
        }
      })
    ),
    0x8 -> MmioMap.Entry.rw16(regBgControl(0), regBgControl(1)),
    0xC -> MmioMap.Entry.rw16(regBgControl(2), regBgControl(3)),
    0x10 -> MmioMap.Entry.w16(regBgOffX(0), regBgOffY(0)),
    0x14 -> MmioMap.Entry.w16(regBgOffX(1), regBgOffY(1)),
    0x18 -> MmioMap.Entry.w16(regBgOffX(2), regBgOffY(2)),
    0x1C -> MmioMap.Entry.w16(regBgOffX(3), regBgOffY(3)),
    0x20 -> MmioMap.Entry.w16(regBgAff(0).pa, regBgAff(0).pb),
    0x24 -> MmioMap.Entry.w16(regBgAff(0).pc, regBgAff(0).pd),
    0x28 -> makeAffBgReferencePointMmio(regBgAffX(0), bgRender.io.bgAffX(0), bgRender.io.writeAffX(0)),
    0x2C -> makeAffBgReferencePointMmio(regBgAffY(0), bgRender.io.bgAffY(0), bgRender.io.writeAffY(0)),
    0x30 -> MmioMap.Entry.w16(regBgAff(1).pa, regBgAff(1).pb),
    0x34 -> MmioMap.Entry.w16(regBgAff(1).pc, regBgAff(1).pd),
    0x38 -> makeAffBgReferencePointMmio(regBgAffX(1), bgRender.io.bgAffX(1), bgRender.io.writeAffX(1)),
    0x3C -> makeAffBgReferencePointMmio(regBgAffY(1), bgRender.io.bgAffY(1), bgRender.io.writeAffY(1)),
    0x40 -> MmioMap.Entry.w8(regWin0Bounds.xEnd, regWin0Bounds.xStart, regWin1Bounds.xEnd, regWin1Bounds.xStart),
    0x44 -> MmioMap.Entry.w8(regWin0Bounds.yEnd, regWin0Bounds.yStart, regWin1Bounds.yEnd, regWin1Bounds.yStart),
    0x48 -> MmioMap.Entry.rw8(regWin0Control, regWin1Control, regWinOutControl, regWinObjControl),
    0x4C -> MmioMap.Entry.w(regMosaic),
    0x50 -> MmioMap.Entry(
      MmioMap.ReadFn(_ => {
        val data = Cat(
          regBlendB.asUInt.pad(8),
          regBlendA.asUInt.pad(8),
          regBlendControl.asUInt.pad(16),
        )
        (data, true.B)
      }),
      MmioMap.WriteFn((enable, data, mask) => {
        when (enable) {
          regBlendControl := MMIO.mask(regBlendControl, data(15, 0), mask(1, 0))
          regBlendA := MMIO.mask(regBlendA, data(23, 16), mask(2))
          regBlendB := MMIO.mask(regBlendB, data(31, 24), mask(3))
        }
      })
    ),
    0x54 -> MmioMap.Entry.w(regBlendFade),
  )

  // Object renderer
  val objRender = Module(new ObjectRenderer)
  objRender.io.enable := io.enable
  objRender.io.displayControl := regDisplayControl
  objRender.io.mosaicY := regMosaic.objY
  objRender.io.tick := tick
  objRender.io.scanline := scanline
  objRender.io.vram <> vram.io.portOBJ
  objRender.io.oam <> oam.io.ppuTarget

  // Compositor
  val compositor = Module(new Compositor)
  compositor.io.enable := io.enable
  compositor.io.displayControl := regDisplayControl
  compositor.io.bgControl := regBgControl
  compositor.io.win0Bounds := regWin0Bounds
  compositor.io.win1Bounds := regWin1Bounds
  compositor.io.win0Control := regWin0Control
  compositor.io.win1Control := regWin1Control
  compositor.io.winOutControl := regWinOutControl
  compositor.io.winObjControl := regWinObjControl
  compositor.io.blendControl := regBlendControl
  compositor.io.blendAlphaA := regBlendA
  compositor.io.blendAlphaB := regBlendB
  compositor.io.blendFade := regBlendFade
  compositor.io.mosaic := regMosaic
  compositor.io.tick := tick
  compositor.io.scanline := scanline
  compositor.io.paletteRam <> paletteRam.io.ppuTarget
  compositor.io.bgFifo <> bgRender.io.pixels
  objRender.io.bufferRead := compositor.io.objectRead
  objRender.io.bufferIndex := compositor.io.objectIndex
  compositor.io.objectData := objRender.io.bufferData
  io.output.valid := compositor.io.valid
  io.output.pixel := compositor.io.pixel
  io.output.hblank := tick > 1006.U // Output video signal actually happens a cycle later, after last pixel output
  io.output.vblank := scanline >= 160.U

  // IRQs and DMA trigger
  {
    val lastVblank = RegInit(false.B)
    val lastHblank = RegInit(false.B)
    val lastVcountHit = RegInit(false.B)
    when (io.enable) {
      lastHblank := isHblank
      lastVblank := isVblank
      lastVcountHit := vcountHit
    }
    io.irqHblank := regIrqEnableHblank && isHblank && !lastHblank
    io.irqVblank := regIrqEnableVblank && isVblank && !lastVblank
    io.irqVcount := regIrqEnableVcount && vcountHit && !lastVcountHit
    io.dmaTriggerHblank := (tick === 1006.U) && (scanline < 160.U)
    io.dmaTriggerVblank := (tick === 0.U) && (scanline === 160.U)
    io.dmaTriggerVideo := (tick === 1006.U) && (scanline >= 2.U && scanline < 162.U)
    io.dmaStopVideo := (tick === 1006.U) && (scanline === 162.U)
  }

  private def makeAffBgReferencePointMmio(
    reg: PpuRegisters.AffineReferencePoint,
    input: PpuRegisters.AffineReferencePoint,
    flag: Bool
  ): MmioMap.Entry = {
    MmioMap.Entry(MmioMap.ReadFn(), MmioMap.WriteFn((enable, data, mask) => {
      val value = MMIO.mask(reg, data, mask)
      when(enable) {
        reg := value
        input := value
        flag := true.B
      }
    }))
  }
}
