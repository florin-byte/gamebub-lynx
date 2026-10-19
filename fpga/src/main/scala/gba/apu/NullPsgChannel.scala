package gba.apu

import chisel3._
import gameboy.apu.ChannelIO

class NullPsgChannel extends Module {
  val io = IO(new ChannelIO)
  io.out := 0.U
  io.channelDisable := false.B
  io.dacEnabled := false.B
}
