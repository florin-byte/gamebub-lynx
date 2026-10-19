package net.gamebub.core.boot

import chisel3._
import chisel3.util._
import lib.mem.{MemoryInterface, RegisterMap}
import lib.video.ColorRGB
import net.gamebub.framework.interface.VideoV0

import javax.imageio.ImageIO

class LogoAnimationState extends Bundle {
  val time = UInt(8.W)
  val speed = UInt(4.W) // duration = (4 seconds / "speed")
  val loop = Bool()
  val running = Bool()
}

class Logo(video: VideoV0) extends Module {
  val io = IO(new Bundle {
    val registers = new MemoryInterface(addressWidth = 16, dataWidth = 32)

    val video_ = video.cloneType
  })

  val regX = RegInit(0.U(log2Ceil(video.videoWidth + 1).W))
  val regY = RegInit(0.U(log2Ceil(video.videoHeight + 1).W))

  val regAnimation = RegInit(0.U.asTypeOf(new LogoAnimationState))
  val regLogoStartY = RegInit(26.U(8.W))
  // Colors as 0xRRGGBB, set by the firmware to match the UI theme: background, logo face, logo
  // shadow, and the brightest color of the glow that sweeps across the face.
  val regBgColor = RegInit(0xE8E8E8.U(24.W))
  val regFaceColor = RegInit(0x810F97.U(24.W))
  val regShadowColor = RegInit(0x5B0B6A.U(24.W))
  val regGlowColor = RegInit(0xDA19FF.U(24.W))
  val (_, frame) = Counter(true.B, 16 * 1024 * 1024 / 60)

  io.registers <> RegisterMap(
    addressWidth = 8,
    dataWidth = 32,
    entries = Seq(
      0x0 -> RegisterMap.Entry.rw(regAnimation),
      0x4 -> RegisterMap.Entry.rw(regLogoStartY),
      0x8 -> RegisterMap.Entry.rw(regBgColor),
      0xC -> RegisterMap.Entry.rw(regFaceColor),
      0x10 -> RegisterMap.Entry.rw(regShadowColor),
      0x14 -> RegisterMap.Entry.rw(regGlowColor),
    )
  )

  // Load and process logo
  val (logoW, logoH, logoData) = loadLogo()
  val logo = VecInit(logoData.map(x => x.U(2.W)))
  val bgColor = toColor(regBgColor)
  val faceColor = toColor(regFaceColor)
  val shadowColor = toColor(regShadowColor)
  val peakColor = toColor(regGlowColor)
  val logoStartX = (video.videoWidth - logoW) / 2
  val logoEndX = (video.videoWidth + logoW) / 2
  val glowTable = makeGlowTable(logoW)
  val colorOffX = RegInit(0.U(log2Ceil(3 * logoW).W))

  io.video_.dataEnable := false.B
  io.video_.hblank := false.B
  io.video_.vblank := false.B

  when (regY === video.videoHeight.U) {
    io.video_.vblank := true.B
  } .elsewhen (regX === video.videoWidth.U) {
    io.video_.hblank := true.B
    regX := 0.U
    regY := regY + 1.U
  } .otherwise {
    io.video_.dataEnable := true.B
    regX := regX + 1.U
  }

  when (frame) {
    regX := 0.U
    regY := 0.U

    when (regAnimation.running) {
      val nextTime = regAnimation.time + regAnimation.speed
      when ((nextTime < regAnimation.time) && !regAnimation.loop) {
        regAnimation.time := 0.U
        regAnimation.running := false.B
      } .otherwise {
        regAnimation.time := nextTime
      }
    }

    // TODO calculate colorOffX with the curve
    colorOffX := ((regAnimation.time * (logoW * 2).U) >> 8).asUInt
  }

  // Glow amount, looked up one pixel early (for the pixel at regX + 1) to keep the table lookup
  // out of the path through the color blend.
  val regGlowAlpha = RegInit(0.U(6.W))
  val nextColorX = colorOffX +& (regX +& 1.U) - logoStartX.U
  regGlowAlpha := 0.U
  when (nextColorX >= logoW.U && nextColorX < (logoW * 2).U) {
    regGlowAlpha := glowTable((nextColorX - logoW.U)(7, 0))
  }
  val glowColor = Wire(ColorRGB(5))
  glowColor.r := blend(peakColor.r, faceColor.r, regGlowAlpha)
  glowColor.g := blend(peakColor.g, faceColor.g, regGlowAlpha)
  glowColor.b := blend(peakColor.b, faceColor.b, regGlowAlpha)

  io.video_.data := bgColor
  when (regX >= logoStartX.U && regX < logoEndX.U && regY >= regLogoStartY && regY < (regLogoStartY + logoH.U)) {
    val x = regX - logoStartX.U
    val y = regY - regLogoStartY
    val index = logo((x * logoH.U + y)(log2Ceil(logoData.length) - 1, 0))
    // index: 0 = transparent, 2 = shadow, 3 = color

    when (index === 2.U) {
      io.video_.data := shadowColor
    } .elsewhen (index === 3.U) {
      io.video_.data := glowColor
    }
  }

  /// alpha / 32 of a plus the rest of b (alpha from 0 to 32).
  private def blend(a: UInt, b: UInt, alpha: UInt): UInt = {
    (((a * alpha) +& ((0x20.U(6.W) - alpha) * b)) >> 5)(4, 0)
  }

  /// 0xRRGGBB to a 5-bit color.
  private def toColor(rgb: UInt): ColorRGB = {
    val color = Wire(ColorRGB(5))
    color.r := rgb(23, 19)
    color.g := rgb(15, 11)
    color.b := rgb(7, 3)
    color
  }

  private def loadLogo(): (Int, Int, Seq[Int]) = {
    val logo = ImageIO.read(getClass.getClassLoader.getResource("logo.png"))
    val data = (0 until (logo.getWidth * logo.getHeight)).map(i => {
      val x = i / logo.getHeight
      val y = i % logo.getHeight
      val g = (logo.getRGB(x, y)) & 0xFF
      g >> 6 // convert to 2 bit
    })
    (logo.getWidth, logo.getHeight, data)
  }

  /// Glow amount across the logo (0 to 32): the same curve as the original HSB brightness boost,
  /// highest in the middle of the sweep.
  private def makeGlowTable(width: Int) = {
    VecInit((0 until width).map(x => {
      val t = x.toDouble / (width - 1)
      val delta = -4.0 * t * (t - 1.0)
      math.round(delta * 32).toInt.U(6.W)
    }))
  }
}
