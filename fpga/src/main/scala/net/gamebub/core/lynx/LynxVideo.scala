package net.gamebub.core.lynx

import chisel3._
import chisel3.util._
import lib.video.ColorRGB

object LynxVideo {
  /** The Lynx screen. */
  val ScreenWidth = 160
  val ScreenHeight = 102
  val ScreenPixels = ScreenWidth * ScreenHeight

  /** The framebuffer sent to the platform: square, so the picture fits upright or rotated. */
  val Size = 160
  /** Offset of the picture across its short side, centered. */
  val Margin = (Size - ScreenHeight) / 2

  /** Rotation setting. */
  val RotateNone = 0
  /** Turned left: the top of the Lynx screen is on the left of the display. */
  val RotateLeft = 1
  /** Turned right: the top of the Lynx screen is on the right of the display. */
  val RotateRight = 2

  /** Flickerblend setting: off, the last 2 frames, the last 3 frames. */
  val BlendOff = 0
  val BlendTwo = 1
  val BlendThree = 2

  /** Clocks between a scan-out pixel position and its color on the output. */
  val Latency = 2
}

/**
 * The Lynx video path of MiSTer's `AtariLynx.sv`, for the platform's raster video interface.
 *
 * The Lynx writes its frames (`pixel_out_*`: an address and a color, at its own refresh rate, which games
 * set between about 50 and 75 Hz) into one of three frame stores, in turn ("Buffer video" in MiSTer). A
 * scan-out with a fixed period (`periodClocks`) sends the last completed frame to the platform as a
 * 160x160 raster, so the platform's display can lock to a steady frame rate: Lynx frames are dropped or
 * repeated, never torn. The whole raster is sent in one burst at the start of the period (one pixel per
 * clock), then the output is in vertical blanking until the next period.
 *
 * Also as in `AtariLynx.sv`: the rotation is done by the order in which the scan-out reads the store, and
 * the flickerblend (for games that alternate two or three frames to show more colors) averages the frame
 * on display with the previous one, or the three stores.
 *
 * On reset the stores are cleared to black, so that the previous game's picture is not shown.
 */
class LynxVideo(periodClocks: Int) extends Module {
  import LynxVideo._

  val io = IO(new Bundle {
    /** Frame buffer writes from the Lynx. */
    val pixelWrite = Input(Bool())
    val pixelAddress = Input(UInt(14.W))
    val pixelData = Input(UInt(12.W))
    /** Clear the stores (core in reset). */
    val clear = Input(Bool())

    /** Settings, applied at the start of a scan-out frame. */
    val rotate = Input(UInt(2.W))
    val blend = Input(UInt(2.W))

    /** A Lynx frame is complete (its last pixel was written). */
    val lynxFrameDone = Output(Bool())
    /** A scan-out frame starts (once every `periodClocks`). */
    val frameStart = Output(Bool())
    /** The rotation of the frame being sent (for the scaler mode). */
    val rotated = Output(Bool())

    val data = Output(ColorRGB(5, 5, 5))
    val dataEnable = Output(Bool())
    val hblank = Output(Bool())
    val vblank = Output(Bool())
  })

  val stores = Seq.fill(3)(SyncReadMem(ScreenPixels, UInt(12.W)))

  // Write side: the store being written, and the last one completed.
  val regWrite = RegInit(0.U(2.W))
  val regReadNext = RegInit(0.U(2.W))
  val frameDone = io.pixelWrite && io.pixelAddress === (ScreenPixels - 1).U
  io.lynxFrameDone := frameDone && !io.clear
  when (frameDone) {
    regReadNext := regWrite
    regWrite := Mux(regWrite === 2.U, 0.U, regWrite + 1.U)
  }

  // Clear: black into every address of the three stores.
  val regClearAddress = RegInit(0.U(14.W))
  val clearing = io.clear
  when (clearing) {
    regClearAddress := Mux(regClearAddress === (ScreenPixels - 1).U, 0.U, regClearAddress + 1.U)
    regWrite := 0.U
    regReadNext := 0.U
  }
  // One write port per store (two `write` calls would make two ports, which Vivado cannot map to block RAM).
  val writeAddress = Mux(clearing, regClearAddress, io.pixelAddress)
  val writeData = Mux(clearing, 0.U, io.pixelData)
  for ((store, i) <- stores.zipWithIndex) {
    when (clearing || (io.pixelWrite && regWrite === i.U)) {
      store.write(writeAddress, writeData)
    }
  }

  // Scan-out timing: one period per frame; the raster goes out at its start.
  val regPeriod = RegInit(0.U(log2Ceil(periodClocks).W))
  val periodEnd = regPeriod === (periodClocks - 1).U
  regPeriod := Mux(periodEnd, 0.U, regPeriod + 1.U)
  val frameStart = regPeriod === 0.U
  io.frameStart := frameStart

  val regRead = RegInit(0.U(2.W))
  val regLast = RegInit(0.U(2.W))
  val regRotate = RegInit(RotateNone.U(2.W))
  val regBlend = RegInit(BlendOff.U(2.W))
  when (frameStart) {
    regRead := regReadNext
    regLast := regRead
    regRotate := io.rotate
    regBlend := io.blend
  }
  io.rotated := regRotate =/= RotateNone.U

  // Raster position: 160 lines of 160 pixels, one clock of horizontal blanking after each line.
  val regActive = RegInit(false.B)
  val regX = RegInit(0.U(8.W))
  val regY = RegInit(0.U(8.W))
  val inLine = regActive && regX < Size.U
  when (frameStart) {
    regActive := true.B
    regX := 0.U
    regY := 0.U
  } .elsewhen (regActive) {
    when (regX === Size.U) {
      regX := 0.U
      regY := regY + 1.U
      when (regY === (Size - 1).U) {
        regActive := false.B
      }
    } .otherwise {
      regX := regX + 1.U
    }
  }

  // Source pixel of the raster position, for the current rotation.
  val x = regX
  val y = regY
  val sourceX = Wire(UInt(8.W))
  val sourceY = Wire(UInt(8.W))
  val inPicture = Wire(Bool())
  when (regRotate === RotateLeft.U) {
    inPicture := x >= Margin.U && x < (Margin + ScreenHeight).U
    sourceY := x - Margin.U
    sourceX := (Size - 1).U - y
  } .elsewhen (regRotate === RotateRight.U) {
    inPicture := x >= Margin.U && x < (Margin + ScreenHeight).U
    sourceY := (Margin + ScreenHeight - 1).U - x
    sourceX := y
  } .otherwise {
    inPicture := y >= Margin.U && y < (Margin + ScreenHeight).U
    sourceY := y - Margin.U
    sourceX := x
  }
  val sourceAddress = (sourceY(6, 0) * ScreenWidth.U +& sourceX)(13, 0)

  // Read the three stores (one clock), then blend (one clock).
  val colors = stores.map(_.read(sourceAddress, inLine && inPicture))
  val pictureValid = RegNext(inLine && inPicture, false.B)
  def channel(color: UInt, i: Int): UInt = color(4 * i + 3, 4 * i)
  val now = VecInit(colors)(regRead)
  val last = VecInit(colors)(regLast)
  val blended = Wire(ColorRGB(5, 5, 5))
  for ((out, i) <- Seq((blended.b, 0), (blended.g, 1), (blended.r, 2))) {
    val sum2 = channel(now, i) +& channel(last, i)
    val sum3 = channel(colors(0), i) +& channel(colors(1), i) +& channel(colors(2), i)
    out := MuxLookup(regBlend, Cat(channel(now, i), channel(now, i)(3)))(Seq(
      BlendTwo.U -> sum2,
      // 0..45 to 0..31, rounded: sum * 31 / 45.
      BlendThree.U -> ((sum3 * 2822.U +& 2048.U) >> 12)(4, 0),
    ))
  }
  val black = 0.U.asTypeOf(ColorRGB(5, 5, 5))
  io.data := RegNext(Mux(pictureValid, blended, black))

  // Control signals delayed to match the color.
  io.dataEnable := ShiftRegister(inLine, Latency, false.B, true.B)
  io.hblank := ShiftRegister(regActive && !inLine, Latency, false.B, true.B)
  io.vblank := ShiftRegister(!regActive, Latency, true.B, true.B)
}
