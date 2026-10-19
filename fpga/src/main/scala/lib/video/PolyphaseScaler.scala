package lib.video

import chisel3._
import chisel3.util._

/**
 * Separable 2-tap polyphase upscaler (Sharp Bilinear / Bilinear) for non-integer scales on a display
 * that scans the source image column by column (the rotated LCD panels: one panel line = one output
 * column, rows on the fast axis).
 *
 * Ported from the gamebub-0.1 fork (N13/N14 scaler, hardware-tested there with SMS, SNES and
 * Genesis). Changes: any color depth, and a source window (x, y, width, height) inside the
 * framebuffer, selected at run time.
 *
 * The window brackets the interpolation point on both axes ({n, n+1} around src = n + frac):
 *  - horizontal: a ring of 3 source columns (2 in the window + 1 prefetched), slot = column mod 3
 *    (an explicit mod: a bit slice computes mod 4 and disagrees with the read side);
 *  - vertical: each slot is one column with two read ports, so rows {n, n+1} are read in one cycle;
 *  - the vertical partials stay at full precision; one round-to-nearest after the horizontal stage.
 *
 * The module owns the framebuffer read port while a filter is active: a prefetch state machine streams
 * whole source columns into the ring. The output follows the coordinates by [[PolyphaseScaler.Latency]]
 * cycles. After frameStart the ring is empty: the first columns are prefetched while the display draws
 * the left border (one column takes about height + 4 cycles, a display line about 340), so the output
 * must not start at the left edge of the screen.
 */
object PolyphaseScaler {
  val Taps = 2          // bracketing window {n, n+1}
  val NumPhases = 64    // phase resolution (top 6 of the 8 fractional bits)
  val CoeffBits = 8     // unsigned Q1.7 coefficients; each phase sums to 128
  val NumBuffers = 3    // ring: 2 in-window columns + 1 prefetched
  val NumFilters = 2    // 1 = Sharp Bilinear, 2 = Bilinear

  /** Framebuffer read latency (address to data) the module expects. */
  val FramebufferReadLatency = 3

  /** Cycles from (srcXfix, srcYfix) to pixelOut. */
  val Latency = 2

  /** Sharp Bilinear ramp slope for an axis scale: "prescale by round(scale), then bilinear", clamped to
    * at least 2 so that scales below 2 stay sharp (slope 1 is plain bilinear). At an integer scale the
    * sample positions fall exactly on the ends of a ramp of slope `scale`, where any rounding gives a small
    * blend; one more makes every pixel a single source pixel with a margin of about 1 / (2 * scale^2)
    * source pixels. */
  def sharpSlopeFor(scale: Double): Int = {
    val n = math.round(scale).toInt
    if (math.abs(scale - n) < 1e-9) n + 1 else math.max(2, n)
  }

  private def clampUnit(x: Double): Double = math.max(0.0, math.min(1.0, x))

  /** 2-tap weights for (filter, frac), order [n, n+1], sum = 128. */
  def taps2(filter: Int, frac: Double, sharpSlope: Int): Array[Int] = {
    val q = filter match {
      case 1 => // Sharp Bilinear: steep ramp around the half phase
        val fw = clampUnit((frac - 0.5) * sharpSlope + 0.5)
        Array(math.round((1 - fw) * 128).toInt, math.round(fw * 128).toInt)
      case _ => // Bilinear
        Array(math.round((1 - frac) * 128).toInt, math.round(frac * 128).toInt)
    }
    q(0) += 128 - q.sum // rounding fix on the leading tap: exact unity gain
    q
  }

  /** The 2 weights of one (filter, phase), packed. */
  def packedCoeff(filter: Int, phase: Int, sharpSlope: Int): BigInt = {
    val c = taps2(filter, phase.toDouble / NumPhases, sharpSlope)
    (0 until Taps).map(t => BigInt(c(t) & ((1 << CoeffBits) - 1)) << (t * CoeffBits)).reduce(_ | _)
  }
}

/**
 * @param fbWidth  framebuffer width (row stride of the read address)
 * @param fbHeight framebuffer height (maximum source window height)
 */
class PolyphaseScaler(
  fbWidth: Int,
  fbHeight: Int,
  colorDepthR: Int,
  colorDepthG: Int,
  colorDepthB: Int,
  /** Sharp Bilinear slopes the scaler can use (`slopeSelectX/Y` picks one at run time). */
  sharpSlopesX: Seq[Int] = Seq(2),
  sharpSlopesY: Seq[Int] = Seq(2),
) extends Module {
  import PolyphaseScaler._

  private val color = ColorRGB(colorDepthR, colorDepthG, colorDepthB)
  private val colorBits = colorDepthR + colorDepthG + colorDepthB

  val io = IO(new Bundle {
    /** Framebuffer read, with [[PolyphaseScaler.FramebufferReadLatency]] cycles of latency. */
    val fbReadAddress = Output(UInt(log2Ceil(fbWidth * fbHeight).W))
    val fbReadData = Input(color)

    /** Source window in the framebuffer; must stay constant during a frame. */
    val windowX = Input(UInt(log2Ceil(fbWidth).W))
    val windowY = Input(UInt(log2Ceil(fbHeight).W))
    val windowWidth = Input(UInt(log2Ceil(fbWidth + 1).W))
    val windowHeight = Input(UInt(log2Ceil(fbHeight + 1).W))

    /** Source coordinates relative to the window, 9.8 fixed point. */
    val srcXfix = Input(UInt(17.W))
    val srcYfix = Input(UInt(17.W))
    /** Restart the prefetch (start of a display frame). */
    val frameStart = Input(Bool())

    /** 1 = Sharp Bilinear, 2 = Bilinear (0 is not a filter: the caller reads the framebuffer itself). */
    val filter = Input(UInt(2.W))
    /** Index in sharpSlopesX / sharpSlopesY of the slope for each axis. */
    val slopeSelectX = Input(UInt(log2Ceil(sharpSlopesX.size.max(2)).W))
    val slopeSelectY = Input(UInt(log2Ceil(sharpSlopesY.size.max(2)).W))

    val pixelOut = Output(color)
  })

  val NB = NumBuffers
  val srcXint = io.srcXfix(16, 8)
  val srcYint = io.srcYfix(16, 8)
  val phaseX = io.srcXfix(7, 2)
  val phaseY = io.srcYfix(7, 2)

  // Coefficient ROMs, one per axis (shared when the slope lists are equal): per slope, per filter, per phase.
  private def mkCoeffRom(slopes: Seq[Int]) = VecInit(slopes.flatMap { slope =>
    (1 to NumFilters).flatMap { f =>
      (0 until NumPhases).map { p => packedCoeff(f, p, slope).U((Taps * CoeffBits).W) }
    }
  })
  val coeffRomY = mkCoeffRom(sharpSlopesY)
  val coeffRomX = if (sharpSlopesX == sharpSlopesY) coeffRomY else mkCoeffRom(sharpSlopesX)
  def weightsFor(rom: Vec[UInt], slopes: Int, slope: UInt, filter: UInt, phase: UInt): Vec[UInt] = {
    val f = Mux(filter === 0.U, 1.U, filter)
    val s = Mux(slope < slopes.U, slope, 0.U)
    val idx = ((s * NumFilters.U + (f - 1.U)) * NumPhases.U + phase)(log2Ceil(slopes * NumFilters * NumPhases) - 1, 0)
    val word = rom(idx)
    VecInit((0 until Taps).map(t => word(t * CoeffBits + CoeffBits - 1, t * CoeffBits)))
  }

  // Column ring: one source column per slot, two read ports (rows n and n+1).
  def mkBuf() = SRAM(fbHeight, UInt(colorBits.W),
    readPortClocks = Seq(clock, clock), writePortClocks = Seq(clock), readwritePortClocks = Seq())
  val col = Seq.fill(NB)(mkBuf())

  // Prefetch: keep columns [srcXint .. srcXint + 2] resident.
  val loadedCol = RegInit(-1.S((log2Ceil(fbWidth) + 2).W))
  val filling = RegInit(false.B)
  val fillCol = Reg(UInt(log2Ceil(fbWidth).W))
  val fillRow = Reg(UInt(log2Ceil(fbHeight + 1).W))
  val fillSlot = Reg(UInt(log2Ceil(NB).W))
  val readValid = RegInit(0.U(FramebufferReadLatency.W))
  val readRowPipe = Reg(Vec(FramebufferReadLatency, UInt(log2Ceil(fbHeight + 1).W)))

  val srcXintS = srcXint.zext
  val nextCol = loadedCol + 1.S
  val needed = nextCol <= (srcXintS + 2.S)   // up to srcXint + 2 (window + 1 ahead)
  val safe = (nextCol - NB.S) < srcXintS      // the slot's occupant is left of the window
  val inRange = nextCol < io.windowWidth.zext

  val wrEnable = WireDefault(false.B)
  val wrSlot = WireDefault(0.U(log2Ceil(NB).W))
  val wrAddr = WireDefault(0.U(log2Ceil(fbHeight).W))
  val wrData = WireDefault(0.U(colorBits.W))

  io.fbReadAddress := 0.U
  when (io.frameStart) {
    loadedCol := -1.S
    filling := false.B
    readValid := 0.U
  } .elsewhen (!filling) {
    when (needed && safe && inRange) {
      filling := true.B
      fillCol := nextCol.asUInt
      fillSlot := nextCol.asUInt % NB.U // explicit mod (NB is not a power of two)
      fillRow := 0.U
      readValid := 0.U
    }
  } .otherwise {
    val issue = fillRow < io.windowHeight
    io.fbReadAddress := ((io.windowY +& fillRow) * fbWidth.U) + (io.windowX +& fillCol)
    when (issue) { fillRow := fillRow + 1.U }
    readValid := Cat(readValid(FramebufferReadLatency - 2, 0), issue.asUInt)
    for (i <- FramebufferReadLatency - 1 to 1 by -1) { readRowPipe(i) := readRowPipe(i - 1) }
    readRowPipe(0) := fillRow
    val dataValid = readValid(FramebufferReadLatency - 1)
    when (dataValid) {
      wrEnable := true.B
      wrSlot := fillSlot
      wrAddr := readRowPipe(FramebufferReadLatency - 1)
      wrData := io.fbReadData.asUInt
    }
    when (!issue && !dataValid && readValid === 0.U) {
      filling := false.B
      loadedCol := fillCol.zext
    }
  }
  for (k <- 0 until NB) {
    col(k).writePorts(0).enable := wrEnable && (wrSlot === k.U)
    col(k).writePorts(0).address := wrAddr
    col(k).writePorts(0).data := wrData
  }

  // Read window. Both rows are clamped: the caller's coordinate can overshoot the window at the end of a
  // display line (latency compensation); unclamped, it would read a row in the middle of the image.
  val lastRow = io.windowHeight - 1.U
  def clampHi(y: UInt, hi: UInt): UInt = Mux(y < hi, y, hi)
  val y0 = clampHi(srcYint, lastRow)
  val y1 = clampHi(srcYint + 1.U, lastRow)
  for (k <- 0 until NB) {
    col(k).readPorts(0).enable := true.B; col(k).readPorts(0).address := y0
    col(k).readPorts(1).enable := true.B; col(k).readPorts(1).address := y1
  }
  val slotTaps = VecInit((0 until NB).map(k =>
    VecInit(Seq(col(k).readPorts(0).data, col(k).readPorts(1).data).map(_.asTypeOf(color)))))

  // Horizontal tap columns {srcXint, srcXint + 1}, clamped, as ring slots.
  val lastCol = (io.windowWidth - 1.U).zext
  val slotH = (0 until Taps).map { h =>
    val xm = srcXintS + h.S
    Mux(xm > lastCol, lastCol, xm).asUInt % NB.U
  }
  // The SRAM read data is registered: align the slots and weights with it.
  val slotHd = slotH.map(RegNext(_))
  val wyD = weightsFor(coeffRomY, sharpSlopesY.size, io.slopeSelectY, io.filter, phaseY).map(RegNext(_))
  val wxD = weightsFor(coeffRomX, sharpSlopesX.size, io.slopeSelectX, io.filter, phaseX).map(RegNext(_))

  def channel(select: ColorRGB => UInt, bits: Int): UInt = {
    // Vertical partials at full precision, one round-to-nearest after the horizontal stage.
    val vsamp = (0 until Taps).map { h =>
      val taps = slotTaps(slotHd(h))
      (0 until Taps).map(t => select(taps(t)) * wyD(t)).reduce(_ +& _)
    }
    val acc = (0 until Taps).map(h => vsamp(h) * wxD(h)).reduce(_ +& _)
    val out = (acc +& (1 << 13).U) >> 14
    val max = ((1 << bits) - 1).U
    Mux(out > max, max, out(bits - 1, 0))
  }

  val filtered = Wire(color)
  filtered.r := channel(_.r, colorDepthR)
  filtered.g := channel(_.g, colorDepthG)
  filtered.b := channel(_.b, colorDepthB)
  // Registered output: Latency = 2 (SRAM read + this register).
  io.pixelOut := RegNext(filtered)
}
