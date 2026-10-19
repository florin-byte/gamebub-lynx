package gba.cart.emu

import chisel3._
import chisel3.util._
import lib.log.Logger

class GyroSensor extends Module {
  val io = IO(new Bundle {
    /// Z-axis sample, centered at 0x700, roughly in degrees/second?
    val sampleZ = Input(UInt(12.W))

    /// GPIO 0: take a sample on rising edge
    val takeSample = Input(Bool())
    /// GPIO 1: serial clock
    val serialClock = Input(Bool())
    /// GPIO 2: serial data
    val serialData = Output(UInt(1.W))
  })
  val logger = Logger("cart.emu.gyro")

  val buffer = RegInit(0.U(16.W))
  when (io.takeSample) {
    // Put 4 dummy bytes, plus the 12-bit sample.
    val sample = io.sampleZ
    buffer := Cat(0.U(4.W), sample)
  } .elsewhen (!io.serialClock && RegNext(io.serialClock)) {
    buffer := buffer << 1
  }

  io.serialData := buffer(15)
}
