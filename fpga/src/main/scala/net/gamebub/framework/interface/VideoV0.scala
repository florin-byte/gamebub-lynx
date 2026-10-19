package net.gamebub.framework.interface

import chisel3._
import chisel3.util._
import lib.video.ColorRGB

class VideoV0(
  /// Width of the video output, in pixels
  val videoWidth: Int,
  /// Height of the video output, in pixels
  val videoHeight: Int,
  /// Bits per pixel for the R channel.
  val colorDepthR: Int,
  /// Bits per pixel for the G channel.
  val colorDepthG: Int,
  /// Bits per pixel for the B channel.
  val colorDepthB: Int,
  /// Target frame period, in seconds.
  val framePeriod: Double,
  /// A second frame period the core can run at (a 50 Hz machine), in seconds; the rev2 LCD driver
  /// keeps a longer scan line for it and locks to whichever rate the core produces (2026-10-09).
  val framePeriodSlow: Option[Double] = None,
) extends Bundle {
  val data = Output(ColorRGB.apply(colorDepthR, colorDepthG, colorDepthB))
  val dataEnable = Output(Bool())
  val vblank = Output(Bool())
  val hblank = Output(Bool())
}
