package gba.ppu

import chisel3._

object PpuRegisters {
  class DisplayControl extends Bundle {
    val greenSwap = Bool()
    val objWindow = Bool()
    val displayWindow = UInt(2.W)
    val enableObj = Bool()
    val enableBg = UInt(4.W)
    val forceBlank = Bool() // TODO
    val objMapping = UInt(1.W)
    val hblankFree = Bool()
    val frame = UInt(1.W)
    val cgbMode = Bool()  // TODO only settable in BIOS
    val mode = UInt(3.W)
  }

  /// Lower byte of DISPSTAT
  class DisplayStatus extends Bundle {
    val irqVcount = Bool()
    val irqHblank = Bool()
    val irqVblank = Bool()
    val vcountHit = Bool()
    val hblank = Bool()
    val vblank = Bool()
  }

  class BackgroundControl extends Bundle {
    val size = UInt(2.W)
    val affineWrap = Bool()
    val screenBase = UInt(5.W)
    val bpp8 = Bool()
    val mosaic = Bool()
    val _padding = UInt(2.W)
    val charBase = UInt(2.W)
    val priority = UInt(2.W)
  }

  class FixedPoint(intWidth: Int) extends Bundle {
    val sign = UInt(1.W)
    val int = UInt(intWidth.W)
    val frac = UInt(8.W)
  }

  class AffineReferencePoint extends FixedPoint(19) {}

  class AffineParams extends Bundle {
    val pa = new FixedPoint(7)
    val pb = new FixedPoint(7)
    val pc = new FixedPoint(7)
    val pd = new FixedPoint(7)
  }

  class WindowBounds extends Bundle {
    val xStart = UInt(8.W)
    val xEnd = UInt(8.W)
    val yStart = UInt(8.W)
    val yEnd = UInt(8.W)
  }

  class WindowControl extends Bundle {
    val blend = Bool()
    val obj = Bool()
    val bg = UInt(4.W)
  }

  object BlendEffect extends ChiselEnum {
    val none = Value
    val alpha = Value
    val white = Value
    val black = Value
  }

  class BlendControl extends Bundle {
    val bottomBackdrop = Bool()
    val bottomObj = Bool()
    val bottomBg = UInt(4.W)
    val effect = BlendEffect()
    val topBackdrop = Bool()
    val topObj = Bool()
    val topBg = UInt(4.W)
  }

  class MosaicSize extends Bundle {
    /// Object mosaic vertical size (minus 1)
    val objY = UInt(4.W)
    /// Object mosaic horizontal size (minus 1)
    val objX = UInt(4.W)
    /// Background mosaic vertical size (minus 1)
    val bgY = UInt(4.W)
    /// Background mosaic horizontal size (minus 1)
    val bgX = UInt(4.W)
  }
}
