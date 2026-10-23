package platform.handheld

import chisel3._
import lib.util.EphemeralSimulator._
import lib.video.PolyphaseScaler
import net.gamebub.framework.interface.VideoScalerV0
import org.scalatest.funsuite.AnyFunSuite

/**
 * The aspect scaler on a simulated rev2 LCD scan: a 480x320 picture on a rotated 320x480 panel, with
 * the ILI9488 driver's counters (sync + back porch before each line, counters that wrap outside the
 * active area) and a framebuffer read latency of 3 cycles. Every pixel inside the scaled picture is
 * compared with a reference computed in the same fixed point (both axes: the source pattern differs
 * along x and y in every channel). The rev4 tests use the 800x480 panel (rotated 480x800 ILI9806E) with
 * the picture centered on column 372.
 */
class LcdAspectScalerSpec extends AnyFunSuite {
  import PolyphaseScaler._

  // ILI9488 (rev2): 320 x 480 panel, rotated.
  val panelWidth = 320 // fast axis = picture rows
  val panelHeight = 480 // slow axis = picture columns
  val hBlankBefore = 6 // hSync + hBackPorch
  val vBlankBefore = 3 // vSync + vBackPorch
  val totalWidth = 420
  val totalHeight = 488
  val screenWidth = 480
  val screenHeight = 320

  /** A rotated LCD: the scan counters and the screen the picture is placed on. */
  case class Panel(
    panelWidth: Int, // fast axis = picture rows
    panelHeight: Int, // slow axis = picture columns
    hBlankBefore: Int,
    vBlankBefore: Int,
    totalWidth: Int,
    totalHeight: Int,
    displayOffsetX: Int,
    dpiWidthX: Int,
    dpiWidthY: Int,
  ) {
    def screenWidth: Int = panelHeight
    def screenHeight: Int = panelWidth
  }
  val rev2 = Panel(panelWidth, panelHeight, hBlankBefore, vBlankBefore, totalWidth, totalHeight, 0, 9, 9)
  // ILI9806E (rev4): 480 x 800 panel, rotated; HandheldTop's displayOffsetX = -28. The counters are 10 bits
  // (slow axis) and 9 bits (fast axis, wraps during the horizontal blanking).
  val rev4 = Panel(480, 800, hBlankBefore = 8, vBlankBefore = 6, totalWidth = 540, totalHeight = 810,
    displayOffsetX = -28, dpiWidthX = 10, dpiWidthY = 9)

  val depth = 4

  /** Source pattern: every channel changes along both axes. */
  def source(x: Int, y: Int): (Int, Int, Int) = ((x * 3 + y) & 15, (y * 5 + x / 3) & 15, ((x ^ y) * 7) & 15)

  def expected(g: LcdAspectScaler.Geometry, filter: Int, dx: Int, dy: Int): (Int, Int, Int) = {
    val m = g.mode
    val fx = LcdAspectScaler.sourcePosition(dx - g.offsetX, g.inverseScaleX) & 0x1FFFF
    val fy = LcdAspectScaler.sourcePosition(dy - g.offsetY, g.inverseScaleY) & 0x1FFFF
    val (xi, yi) = (fx >> 8, fy >> 8)
    val (phX, phY) = ((fx >> 2) & 63, (fy >> 2) & 63)
    val slopeX = sharpSlopeFor(g.scaleX)
    val slopeY = sharpSlopeFor(g.scaleY)
    val wx = taps2(filter, phX.toDouble / NumPhases, slopeX)
    val wy = taps2(filter, phY.toDouble / NumPhases, slopeY)
    def cx(h: Int) = math.min(xi + h, m.width - 1)
    def cy(t: Int) = math.min(yi + t, m.height - 1)
    def chan(c: ((Int, Int, Int)) => Int): Int = {
      val acc = (0 until Taps).map { h =>
        (0 until Taps).map(t => c(source(m.x + cx(h), m.y + cy(t))) * wy(t)).sum * wx(h)
      }.sum
      math.min(15, (acc + (1 << 13)) >> 14)
    }
    (chan(_._1), chan(_._2), chan(_._3))
  }

  /** `exact` (integer scales): also check that each pixel is exactly the source pixel under the center of the
    * screen pixel, so every source pixel is `scale` screen pixels wide, the first and last ones included. */
  /** `nearest`: also check that each pixel is exactly the source pixel nearest to the sample position. */
  def run(modes: Seq[VideoScalerV0.Mode], mode: Int, filter: Int, fbWidth: Int = 256, fbHeight: Int = 240,
          exact: Boolean = false, panel: Panel = rev2, nearest: Boolean = false): Unit = {
    val maskX = (1 << panel.dpiWidthX) - 1
    val maskY = (1 << panel.dpiWidthY) - 1
    simulate(new LcdAspectScaler(modes, panel.screenWidth, panel.screenHeight, panel.displayOffsetX, fbWidth,
      fbHeight, depth, depth, depth, dpiWidthX = panel.dpiWidthX, dpiWidthY = panel.dpiWidthY, delay = 0)) { dut =>
      val g = dut.geometries(mode)
      dut.io.mode.poke(mode)
      dut.io.filter.poke(filter)
      dut.reset.poke(true); dut.clock.step(2); dut.reset.poke(false)
      val history = scala.collection.mutable.Queue.fill(FramebufferReadLatency)(0)
      var checked = 0
      var mismatches = List.empty[String]
      for (frame <- 0 until 2; y <- 0 until panel.totalHeight; x <- 0 until panel.totalWidth) {
        val dpiX = (y - panel.vBlankBefore) & maskX
        val dpiY = (x - panel.hBlankBefore) & maskY
        // The display only shows the active area (the fast-axis counter wraps in the blanking on rev4).
        val active = x - panel.hBlankBefore >= 0 && x - panel.hBlankBefore < panel.panelWidth &&
          y - panel.vBlankBefore >= 0 && y - panel.vBlankBefore < panel.panelHeight
        dut.io.dpiX.poke(dpiX)
        dut.io.dpiY.poke(dpiY)
        val (r, gg, b) = { val a = history.dequeue(); source(a % fbWidth, a / fbWidth) }
        dut.io.fbReadData.r.poke(r); dut.io.fbReadData.g.poke(gg); dut.io.fbReadData.b.poke(b)
        // Frame 0 latches the mode at its start; check frame 1.
        if (frame == 1 && active && dut.io.inBounds.peek().litToBoolean) {
          val got = (dut.io.pixel.r.peek().litValue.toInt, dut.io.pixel.g.peek().litValue.toInt,
            dut.io.pixel.b.peek().litValue.toInt)
          val want = expected(g, filter, dpiX, dpiY)
          if (got != want && mismatches.size < 10) mismatches ::= s"($dpiX,$dpiY): got $got, want $want"
          if (exact) {
            val pure = source(g.mode.x + ((dpiX - g.offsetX) / g.scaleX.round.toInt),
              g.mode.y + ((dpiY - g.offsetY) / g.scaleY.round.toInt))
            if (got != pure && mismatches.size < 10) mismatches ::= s"($dpiX,$dpiY): got $got, source pixel $pure"
          }
          if (nearest) {
            // The source pixel whose center is nearest to the sample position (9.8 fixed point; a tie goes right).
            def nearestIndex(fix: Int, size: Int) = math.min(((fix & 0x1FFFF) + 128) >> 8, size - 1)
            val fx = LcdAspectScaler.sourcePosition(dpiX - g.offsetX, g.inverseScaleX)
            val fy = LcdAspectScaler.sourcePosition(dpiY - g.offsetY, g.inverseScaleY)
            val pure = source(g.mode.x + nearestIndex(fx, g.mode.width), g.mode.y + nearestIndex(fy, g.mode.height))
            if (got != pure && mismatches.size < 10) mismatches ::= s"($dpiX,$dpiY): got $got, nearest pixel $pure"
          }
          checked += 1
        }
        history.enqueue(dut.io.fbReadAddress.peek().litValue.toInt)
        dut.clock.step()
      }
      assert(dut.io.active.peek().litToBoolean)
      assert(checked == g.width * g.height, s"checked $checked of ${g.width}x${g.height}")
      assert(mismatches.isEmpty, mismatches.reverse.mkString("\n"))
    }
  }

  val smsModes = Seq(
    VideoScalerV0.Mode(0, 24, 256, 192),
    VideoScalerV0.Mode(0, 8, 256, 224),
    VideoScalerV0.Mode(0, 0, 256, 240),
  )

  test("geometry: SMS and Game Gear modes on 480x320") {
    val sizes = smsModes.map(LcdAspectScaler.geometry(_, screenWidth, screenHeight, 0)).map(g => (g.width, g.height))
    assert(sizes == Seq((427, 320), (366, 320), (342, 320)))
    val gg = LcdAspectScaler.geometry(VideoScalerV0.Mode(0, 0, 160, 144), screenWidth, screenHeight, 0)
    assert((gg.width, gg.height) == (356, 320))
  }

  test("SMS 192 lines, Sharp Bilinear") { run(smsModes, 0, VideoScalerV0.FilterSharpBilinear) }
  test("SMS 224 lines, Bilinear") { run(smsModes, 1, VideoScalerV0.FilterBilinear) }
  test("SMS 240 lines, Sharp Bilinear") { run(smsModes, 2, VideoScalerV0.FilterSharpBilinear) }
  test("Game Gear 160x144, Sharp Bilinear") {
    run(Seq(VideoScalerV0.Mode(0, 0, 160, 144)), 0, VideoScalerV0.FilterSharpBilinear, fbWidth = 160, fbHeight = 144)
  }

  // Lynx (net.gamebub.core.lynx.HandheldLynx): the screen upright or rotated in a 160x160 framebuffer.
  val lynxModes = Seq(VideoScalerV0.Mode(0, 29, 160, 102), VideoScalerV0.Mode(29, 0, 102, 160))

  test("geometry: Lynx modes on 480x320 at exactly 3x and 2x") {
    val g = lynxModes.map(LcdAspectScaler.geometry(_, screenWidth, screenHeight, 0))
    assert(g.map(x => (x.width, x.height, x.scaleX)) == Seq((480, 306, 3.0), (204, 320, 2.0)))
  }
  test("Lynx 160x102, 3x: every pixel a source pixel") {
    run(lynxModes, 0, VideoScalerV0.FilterSharpBilinear, fbWidth = 160, fbHeight = 160, exact = true)
  }
  test("Lynx rotated 102x160, 2x: every pixel a source pixel") {
    run(lynxModes, 1, VideoScalerV0.FilterSharpBilinear, fbWidth = 160, fbHeight = 160, exact = true)
  }

  // rev4: an 800x480 panel with the picture centered on column 372 (HandheldTop's displayOffsetX = -28). Every
  // mode must stay on the panel (the Lynx at the full height was 752 wide and started at column -4).
  test("geometry: every mode on the rev4 panel (800x480, offset -28)") {
    val all = smsModes ++ Seq(VideoScalerV0.Mode(0, 0, 160, 144)) ++ lynxModes
    for (m <- all) {
      val g = LcdAspectScaler.geometry(m, 800, 480, -28)
      assert(g.offsetX >= 0 && g.offsetX + g.width <= 800 && g.offsetY >= 0 && g.offsetY + g.height <= 480, s"$m: $g")
      assert(g.offsetX + g.width / 2 - 372 <= 1 && 372 - (g.offsetX + g.width / 2) <= 1, s"$m not centered on 372: $g")
    }
  }

  // Nearest (filter 3): no blending, every screen pixel is the source pixel nearest to its sample position.
  test("taps: Nearest is a single tap") {
    for (p <- 0 until NumPhases) {
      val t = taps2(VideoScalerV0.FilterNearest, p.toDouble / NumPhases, 2)
      assert(t.toSeq == (if (p < NumPhases / 2) Seq(128, 0) else Seq(0, 128)), s"phase $p")
    }
  }
  test("geometry: Game Gear on the rev4 panel, full height") {
    val g = LcdAspectScaler.geometry(VideoScalerV0.Mode(0, 0, 160, 144), 800, 480, -28)
    assert((g.width, g.height, g.offsetX, g.offsetY) == (534, 480, 105, 0))
  }
  test("rev4: Game Gear 160x144, Nearest") {
    run(Seq(VideoScalerV0.Mode(0, 0, 160, 144)), 0, VideoScalerV0.FilterNearest, fbWidth = 160, fbHeight = 144,
      panel = rev4, nearest = true)
  }
  test("rev4: SMS 224 lines, Nearest") {
    run(smsModes, 1, VideoScalerV0.FilterNearest, panel = rev4, nearest = true)
  }
  test("Lynx 160x102, 3x, Nearest: every pixel a source pixel") {
    run(lynxModes, 0, VideoScalerV0.FilterNearest, fbWidth = 160, fbHeight = 160, exact = true, nearest = true)
  }
}
