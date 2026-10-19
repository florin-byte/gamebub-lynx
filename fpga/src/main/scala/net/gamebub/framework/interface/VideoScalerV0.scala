package net.gamebub.framework.interface

import chisel3._
import chisel3.util._

/**
 * Optional interface: lets the platform scale the core's video to fill the display while keeping its
 * aspect ratio (non-integer scale with a Sharp Bilinear or Bilinear filter), instead of the default
 * integer scale.
 *
 * The core declares its display modes at elaboration: each mode is a window of the framebuffer (the
 * part of the video output that holds the game picture) and the shape of its pixels. At run time it
 * selects the current mode and the filter. The platform computes the scale for each mode from the
 * size of its display, and applies a change of mode or filter at the start of a display frame.
 *
 * Similar to MiSTer's VIDEO_ARX/VIDEO_ARY (aspect ratio reported by the core) and to the Analogue
 * Pocket's scaler modes (a list of modes declared by the core, one selected at run time).
 *
 * Signals are in the core's clock domain.
 */
class VideoScalerV0(
  /// Display modes; the index of a mode is the value of `mode` that selects it.
  val modes: Seq[VideoScalerV0.Mode],
) extends Bundle {
  require(modes.nonEmpty, "at least one mode")

  /// Current mode (index in `modes`).
  val mode = Output(UInt(log2Ceil(modes.size.max(2)).W))
  /// Scaling: VideoScalerV0.FilterInteger, FilterSharpBilinear or FilterBilinear.
  val filter = Output(UInt(2.W))
}

object VideoScalerV0 {
  /**
   * A display mode.
   *
   * @param x, y, width, height the game picture in the framebuffer (pixels)
   * @param pixelAspect width / height of one pixel as displayed (1.0 = square pixels)
   */
  case class Mode(x: Int, y: Int, width: Int, height: Int, pixelAspect: Double = 1.0) {
    require(x >= 0 && y >= 0 && width > 0 && height > 0 && pixelAspect > 0)
  }

  /// The platform's integer scale of the whole framebuffer (the behavior without this interface).
  val FilterInteger = 0
  /// Aspect-correct scale, Sharp Bilinear filter (sharp pixels, smooth edges).
  val FilterSharpBilinear = 1
  /// Aspect-correct scale, Bilinear filter.
  val FilterBilinear = 2
}
