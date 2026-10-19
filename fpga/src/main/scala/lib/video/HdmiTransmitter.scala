package lib.video

import chisel3._
import chisel3.util._

object HdmiTransmitter {
  case class Config(
    videoIdCode: Int,
    videoRefreshRate: Double,
    audioRate: Int,
    audioBitWidth: Int,
    dviOnly: Boolean = false,
  ) {
    val posXWidth: Int = 12
    val posYWidth: Int = 11
  }
}

/// HDMI transmitter, using https://github.com/hdl-util/hdmi
class HdmiTransmitter(config: HdmiTransmitter.Config) extends Module {
  val io = IO(new Bundle {
    val clockPixel = Input(Clock())
    val clockPixelX5 = Input(Clock())
    val clockAudio = Input(Clock())

    val color = Input(ColorRGB(8, 8, 8))
    val audio = Input(Vec(2, UInt(config.audioBitWidth.W)))

    val outClock = Output(Bool())
    val outData = Output(UInt(3.W))

    val videoX = Output(UInt(config.posXWidth.W))
    val videoY = Output(UInt(config.posYWidth.W))
  })

  private val hdmi = Module(new hdmi(config))
  hdmi.io.clk_pixel := io.clockPixel.asBool
  hdmi.io.clk_pixel_x5 := io.clockPixelX5.asBool
  hdmi.io.clk_audio := io.clockAudio.asBool
  hdmi.io.reset := reset

  hdmi.io.rgb := io.color.asUInt
  hdmi.io.audio_sample_word := Cat(io.audio(1), io.audio(0))

  io.outClock := hdmi.io.tmds_clock.asBool
  io.outData := hdmi.io.tmds
  io.videoX := hdmi.io.cx
  io.videoY := hdmi.io.cy
}

private class hdmi(
  config: HdmiTransmitter.Config
) extends ExtModule(Map(
  "VIDEO_ID_CODE" -> config.videoIdCode,
  "VIDEO_REFRESH_RATE" -> config.videoRefreshRate,
  "AUDIO_RATE" -> config.audioRate,
  "AUDIO_BIT_WIDTH" -> config.audioBitWidth,
  "DVI_OUTPUT" -> (if (config.dviOnly) 1 else 0),
)) {
  val io = FlatIO(new Bundle {
    val clk_pixel_x5 = Input(Bool())
    val clk_pixel = Input(Bool())
    val clk_audio = Input(Bool())
    val reset = Input(Reset())
    val rgb = Input(UInt(24.W))
    val audio_sample_word = Input(UInt((config.audioBitWidth * 2).W))

    val tmds = Output(UInt(3.W))
    val tmds_clock = Output(UInt(1.W))

    val cx = Output(UInt(config.posXWidth.W))
    val cy = Output(UInt(config.posYWidth.W))

    val frame_width = Output(UInt(config.posXWidth.W))
    val frame_height = Output(UInt(config.posYWidth.W))
    val screen_width = Output(UInt(config.posXWidth.W))
    val screen_height = Output(UInt(config.posYWidth.W))
  })
}