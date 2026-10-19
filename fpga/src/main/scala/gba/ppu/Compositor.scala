package gba.ppu

import chisel3._
import chisel3.util._
import gba.ppu.PpuRegisters.BlendEffect
import lib.log.Logger

object Compositor {
  class Layer extends Bundle {
    // Whether this layer is the backdrop (transparent)
    val isBackdrop = Bool()
    // Whether this layer is an object
    val isObj = Bool()
    // Whether this layer is one of the backgrounds (1-hot)
    val isBg = UInt(4.W)
    val color = UInt(15.W)
    val priority = UInt(2.W)
  }

  class BgMosaicBuffer extends Bundle {
    val opaque = Bool()
    val color = UInt(15.W)
  }
}

/// PPU compositor
///
/// 1) Pull data from object render buffer and background render FIFOs
/// 2) Apply windowing to layers
/// 3) Sort by priority
/// 4) Fetch palette entries for top two layers
/// 5) Apply blend effects
/// 6) Output final pixel
class Compositor extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())

    val displayControl = Input(new PpuRegisters.DisplayControl)
    val bgControl = Input(Vec(4, new PpuRegisters.BackgroundControl))
    val win0Bounds = Input(new PpuRegisters.WindowBounds)
    val win1Bounds = Input(new PpuRegisters.WindowBounds)
    val win0Control = Input(new PpuRegisters.WindowControl)
    val win1Control = Input(new PpuRegisters.WindowControl)
    val winOutControl = Input(new PpuRegisters.WindowControl)
    val winObjControl = Input(new PpuRegisters.WindowControl)
    val blendControl = Input(new PpuRegisters.BlendControl)
    val blendAlphaA = Input(UInt(5.W))
    val blendAlphaB = Input(UInt(5.W))
    val blendFade = Input(UInt(5.W))
    val mosaic = Input(new PpuRegisters.MosaicSize)

    val tick = Input(UInt(11.W))
    val scanline = Input(UInt(8.W))

    val paletteRam = Flipped(new PpuMemoryInterface(1024 / 2, 16.W))

    val valid = Output(Bool())
    val pixel = Output(UInt(15.W))

    val bgFifo = Vec(4, Flipped(DecoupledIO(new BackgroundPixel)))

    val objectIndex = Output(UInt(8.W))
    val objectRead = Output(Bool())
    val objectData = Input(new ObjectBufferEntry)
  })
  val logger = Logger("ppu.comp", enable = io.enable)

  val isBitmap16bpp = io.displayControl.mode === 3.U || io.displayControl.mode === 5.U
  val isForceBlank = io.displayControl.forceBlank

  io.paletteRam.read := false.B
  io.paletteRam.address := DontCare
  io.objectRead := false.B
  io.objectIndex := DontCare
  for (i <- 0 until 4) {
    io.bgFifo(i).ready := false.B
  }

  val win0ActiveX = Reg(Bool())
  val win1ActiveX = Reg(Bool())
  val win0ActiveY = Reg(Bool())
  val win1ActiveY = Reg(Bool())

  val mosaicObjCounter = Reg(UInt(4.W))
  val mosaicBgCounter = Reg(UInt(4.W))

  val fetchX = Reg(UInt(8.W))
  val active = Reg(Bool())
  when (io.enable) {
    when (io.tick === 0.U) {
      fetchX := 0.U
      mosaicObjCounter := 0.U
      // We start the BG mosaic counter at the maximum value, so that when it's incremented
      // for the first time, it gets reset to zero. This because the first increment happens
      // in the first subCycle = 3 when active, which is *before* the first BG pixel.
      // If we didn't skip the first increment, BG mosaic would be off by a pixel.
      mosaicBgCounter := io.mosaic.bgX

      // Window Y activation / deactivation
      when (io.scanline === io.win0Bounds.yStart) {
        win0ActiveY := true.B
      }
      when (io.scanline === io.win0Bounds.yEnd) {
        win0ActiveY := false.B
      }
      when (io.scanline === io.win1Bounds.yStart) {
        win1ActiveY := true.B
      }
      when (io.scanline === io.win1Bounds.yEnd) {
        win1ActiveY := false.B
      }
    }
    when (io.tick === 40.U && io.scanline < 160.U) {
      active := true.B
    }
    when (io.tick === 1006.U) {
      active := false.B
    }

    // Window X activation / deactivation. Happens even during vblank.
    when (io.tick >= 40.U && io.tick(1, 0) === 0.U) {
      val x = (io.tick - 40.U) >> 2
      when (x === io.win0Bounds.xStart) {
        win0ActiveX := true.B
      }
      when (x === io.win0Bounds.xEnd) {
        win0ActiveX := false.B
      }
      when (x === io.win1Bounds.xStart) {
        win1ActiveX := true.B
      }
      when (x === io.win1Bounds.xEnd) {
        win1ActiveX := false.B
      }
    }
  }
  val subCycle = (io.tick - 2.U)(1, 0)

  val regBlendFirst = Reg(new Compositor.Layer)
  val regBlendSecond = Reg(new Compositor.Layer)
  val regBlendWindow = Reg(Bool())
  val regBlendObj = Reg(Bool())
  io.valid := false.B
  io.pixel := DontCare
  when (io.enable && active && io.tick >= 50.U && subCycle === 0.U) {
    io.valid := true.B

    // If the top layer is an obj with the blend mode, do special behavior.
    val isObjBlend = regBlendFirst.isObj && regBlendObj

    val blendFirst = (
      Cat(regBlendFirst.isBackdrop, regBlendFirst.isObj, regBlendFirst.isBg) &
        Cat(io.blendControl.topBackdrop, io.blendControl.topObj, io.blendControl.topBg)).orR
    val blendSecond = (
      Cat(regBlendSecond.isBackdrop, regBlendSecond.isObj, regBlendSecond.isBg) &
        Cat(io.blendControl.bottomBackdrop, io.blendControl.bottomObj, io.blendControl.bottomBg)).orR
    val blendEffect = Mux(isObjBlend && blendSecond, BlendEffect.alpha, io.blendControl.effect)

    val blendEnabled =
      (regBlendWindow || isObjBlend) &&
        (blendEffect =/= BlendEffect.none) &&
        (blendFirst || (isObjBlend && blendSecond)) &&
        !(blendEffect === BlendEffect.alpha && !blendSecond)

    io.pixel := regBlendFirst.color

    when (isForceBlank) {
      // Force blank outputs white
      io.pixel := 0x7FFF.U(15.W)
    } .elsewhen (blendEnabled) {
      val colorA = regBlendFirst.color
      val colorB = Wire(UInt(15.W))
      val weightA = Wire(UInt(5.W))
      val weightB = Wire(UInt(5.W))
      // Pick blend colors based on mode.
      when (blendEffect === BlendEffect.white) {
        colorB := 0x7FFF.U(15.W)
      } .elsewhen (blendEffect === BlendEffect.black) {
        colorB := 0.U(15.W)
      } .otherwise {
        colorB := regBlendSecond.color
      }
      // Pick blend weights based on mode. Clamp each at 16.
      when (blendEffect === BlendEffect.alpha) {
        weightA := Mux(io.blendAlphaA(4), 16.U, io.blendAlphaA)
        weightB := Mux(io.blendAlphaB(4), 16.U, io.blendAlphaB)
      } .otherwise {
        val fade = Mux(io.blendFade(4), 16.U, io.blendFade)
        weightA := 16.U - fade
        weightB := fade
      }
      io.pixel := DoBlend(colorA, colorB, weightA, weightB)
    }
  }

  val regLayerFirst = Reg(new Compositor.Layer)
  val regLayerSecond = Reg(new Compositor.Layer)
  val regLayerWindowBlend = Reg(Bool())
  val regLayerObjBlend = Reg(Bool())
  when (io.enable && active && io.tick >= 46.U) {
    switch (subCycle) {
      // Start top layer palette entry fetch
      is (0.U) {
        when (regLayerFirst.isBackdrop) {
          // No valid layer, use the backdrop (palette index 0)
          io.paletteRam.read := true.B
          io.paletteRam.address := 0.U
        } .elsewhen (regLayerFirst.isBg(2) && isBitmap16bpp) {
          // Special case: 16bpp bitmap, don't query palette ram.
        } .otherwise {
          io.paletteRam.read := true.B
          io.paletteRam.address := Cat(regLayerFirst.isObj, regLayerFirst.color(7, 0))
        }
      }
      // Store top layer palette entry
      is (1.U) {
        when (regLayerFirst.isBackdrop || !(regLayerFirst.isBg(2) && isBitmap16bpp)) {
          regLayerFirst.color := io.paletteRam.readData
        }
      }
      // Start fetch of bottom layer palette entry
      is (2.U) {
        // TODO only fetch if blending is enabled
        val fetchBottom = true.B

        when (!fetchBottom) {
          // Nothing to fetch, blending isn't enabled.
        } .elsewhen (regLayerSecond.isBackdrop) {
          // No valid layer, use the backdrop (palette index 0)
          io.paletteRam.read := true.B
          io.paletteRam.address := 0.U
        } .elsewhen (regLayerSecond.isBg(2) && isBitmap16bpp) {
          // Special case: 16bpp bitmap, don't query palette ram.
        } .otherwise {
          io.paletteRam.read := true.B
          io.paletteRam.address := Cat(regLayerSecond.isObj, regLayerSecond.color(7, 0))
        }
      }
      // Store second layer palette entry, pass to blend stage.
      is (3.U) {
        regBlendFirst := regLayerFirst
        regBlendSecond := regLayerSecond
        when (regLayerSecond.isBackdrop || !(regLayerSecond.isBg(2) && isBitmap16bpp)) {
          regBlendSecond.color := io.paletteRam.readData
        }
        regBlendWindow := regLayerWindowBlend
        regBlendObj := regLayerObjBlend
      }
    }
  }

  // First stage: priority sorting: should start on cycle 41 (subCycle = 3)
  val regSortFirst = Reg(new Compositor.Layer)
  val regSortSecond = Reg(new Compositor.Layer)
  val regSortWindow = Reg(new PpuRegisters.WindowControl)
  val regSortObjBlend = Reg(Bool())
  /// Object latch for horizontal mosaic (previous object data)
  val regSortObjMosaic = Reg(new ObjectBufferEntry)
  /// Background latch for horizontal mosaic
  val regSortBgMosaic = Reg(Vec(4, new Compositor.BgMosaicBuffer))
  when (io.enable && active) {
    val nextFirstLayer = WireDefault(regSortFirst)
    val nextSecondLayer = WireDefault(regSortSecond)

    val bgControl = io.bgControl(subCycle)
    val bgFifo = io.bgFifo(subCycle)
    val bgPriority = bgControl.priority
    when (io.displayControl.enableBg(subCycle) && !(isBitmap16bpp && subCycle === 3.U)) {
      // Pull from BG fifo if background is enabled.
      // *Don't* pull from BG3 fifo if we're in a 16bpp bitmap mode: BG3 is never enabled,
      // and we re-use the FIFO for the upper bits of the color.

      when (io.tick >= 42.U) {
        // Hack, maybe?
        // This first stage starts on cycle 41 (subcycle 3) in order to set up the
        // object fetch for the next full cycle (on cycle 42).
        // If we fetched here on cycle 41, we'd pop from BG3 fifo and discard it,
        // (since stage 2 doesn't start until cycle 46), effectively shifting everything in BG3
        // by one pixel.
        bgFifo.ready := true.B
      }
    }

    val color = Wire(UInt(15.W))
    color := bgFifo.bits.color
    when (isBitmap16bpp && subCycle === 2.U) {
      // Special case: 16-bit bitmap, pull from bg fifo 2 and 3
      io.bgFifo(3).ready := true.B
      color := Cat(io.bgFifo(3).bits.color, bgFifo.bits.color)
    }

    // Handle horizontal mosaic
    val opaque = WireDefault(bgFifo.bits.opaque)
    when (bgControl.mosaic && mosaicBgCounter =/= 0.U) {
      color := regSortBgMosaic(subCycle).color
      opaque := regSortBgMosaic(subCycle).opaque
    } .otherwise {
      regSortBgMosaic(subCycle).color := color
      regSortBgMosaic(subCycle).opaque := opaque
    }

    when (bgFifo.valid && opaque && io.displayControl.enableBg(subCycle) && regSortWindow.bg(subCycle)) {
      when (regSortFirst.isBackdrop || bgPriority < regSortFirst.priority) {
        nextFirstLayer.isBackdrop := false.B
        nextFirstLayer.isObj := false.B
        nextFirstLayer.isBg := UIntToOH(subCycle)
        nextFirstLayer.color := color
        nextFirstLayer.priority := bgPriority
        nextSecondLayer := regSortFirst
      } .elsewhen (regSortSecond.isBackdrop || bgPriority < regSortSecond.priority) {
        nextSecondLayer.isBackdrop := false.B
        nextSecondLayer.isObj := false.B
        nextSecondLayer.isBg := UIntToOH(subCycle)
        nextSecondLayer.color := color
        nextSecondLayer.priority := bgPriority
      }
    }

    // Last cycle: set up palette fetch and set up next priority sorting.
    when (subCycle === 3.U) {
      // Palette fetch
      regLayerFirst := nextFirstLayer
      regLayerSecond := nextSecondLayer
      regLayerWindowBlend := regSortWindow.blend
      regLayerObjBlend := regSortObjBlend

      // Evaluate windows
      val windowsEnabled = io.displayControl.displayWindow.orR || io.displayControl.objWindow
      val windowControl = Wire(new PpuRegisters.WindowControl)
      when (!windowsEnabled) {
        windowControl.blend := true.B
        windowControl.obj := true.B
        windowControl.bg := "b1111".U(4.W)
      } .elsewhen (io.displayControl.displayWindow(0) && win0ActiveX && win0ActiveY) {
        windowControl := io.win0Control
      } .elsewhen (io.displayControl.displayWindow(1) && win1ActiveX && win1ActiveY) {
        windowControl := io.win1Control
      } .elsewhen (io.displayControl.objWindow && io.objectData.window) {
        // Note that object window never uses the mosaic data.
        windowControl := io.winObjControl
      } .otherwise {
        windowControl := io.winOutControl
      }
      regSortWindow := windowControl

      // Set up the next set by fetching an object.
      io.objectRead := true.B
      io.objectIndex := fetchX

      // Handle horizontal object mosaic:
      // Use new object data (rather than mosaic) if the new object doesn't have mosaic bit,
      // or the old data doesn't have mosaic bit, or the mosaic counter is at 0.
      val mosaicUpdate = !io.objectData.mosaic || !regSortObjMosaic.mosaic || mosaicObjCounter === 0.U
      val objectData = WireDefault(regSortObjMosaic)
      when (mosaicUpdate) {
        regSortObjMosaic := io.objectData
        objectData := io.objectData
      }
      // Update mosaic counters.
      mosaicObjCounter := mosaicObjCounter + 1.U
      when (mosaicObjCounter === io.mosaic.objX) {
        mosaicObjCounter := 0.U
      }
      mosaicBgCounter := mosaicBgCounter + 1.U
      when (mosaicBgCounter === io.mosaic.bgX) {
        mosaicBgCounter := 0.U
      }

      val objOpaque = objectData.opaque && windowControl.obj && io.displayControl.enableObj
      regSortObjBlend := objOpaque && objectData.blend
      regSortFirst.isBackdrop := !objOpaque
      regSortFirst.isObj := objOpaque
      regSortFirst.isBg := 0.U
      regSortFirst.color := objectData.color
      regSortFirst.priority := objectData.priority
      regSortSecond.isBackdrop := true.B
      regSortSecond.isObj := false.B
      regSortSecond.isBg := 0.U
      fetchX := fetchX + 1.U
    } .otherwise {
      regSortFirst := nextFirstLayer
      regSortSecond := nextSecondLayer
    }
  }

  // Blend colorA with colorB. weights must already be capped at 16.
  private def DoBlend(colorA: UInt, colorB: UInt, weightA: UInt, weightB: UInt): UInt = {
    assert(colorA.getWidth == 15)
    assert(colorB.getWidth == 15)
    assert(weightA.getWidth == 5)
    assert(weightB.getWidth == 5)

    // Alpha-blend each component separately.
    Cat((0 until 3).reverse.map(i => {
      val a = colorA(5 * i + 4, 5 * i)
      val b = colorB(5 * i + 4, 5 * i)
      val out = (((a * weightA) +& (b * weightB)) >> 4).asUInt
      Mux(out > 31.U, 31.U(5.W), out(4, 0))
    }))
  }
}
