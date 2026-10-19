package gba.cart.emu

import chisel3._
import chisel3.util._
import lib.log.Logger

/// 2-axis (X-Y) accelerometer, mapped into SRAM at 0xE008x00
///
/// Limitations *not* implemented:
/// * Supposed to require 8-clock SRAM wait, and PHI at 4 MHz.
/// * ADC conversion takes no time
class TiltSensor extends Module {
  val io = IO(new Bundle {
    /// Samples, centered at 0x400 (?), with 1g ~= 0xE0
    val sampleX = Input(UInt(12.W))
    val sampleY = Input(UInt(12.W))

    val ramEnable = Input(Bool())
    val ramAddress = Input(UInt(4.W))
    val ramIsWrite = Input(Bool())
    val ramDataRead = Output(UInt(8.W))
    val ramDataWrite = Input(UInt(8.W))
  })
  val logger = Logger("cart.emu.tilt")

  val regLatchX = Reg(UInt(12.W))
  val regLatchY = Reg(UInt(12.W))
  /// Write 0x55 to reg 0 to reset, write 0xAA to reg1 to latch new data
  val regLatched = RegInit(false.B)

  when (io.ramEnable && io.ramIsWrite) {
    logger.debug(cf"write: addr=${io.ramAddress} data=${io.ramDataWrite}%x")
    when (io.ramAddress === 0.U && io.ramDataWrite === 0x55.U) {
      regLatched := false.B
    }
    when (io.ramAddress === 1.U && io.ramDataWrite === 0xAA.U) {
      regLatched := true.B
      regLatchX := io.sampleX
      regLatchY := io.sampleY
    }
  }

  val regDataRead = Reg(UInt(8.W))
  io.ramDataRead := regDataRead
  when (io.ramEnable && !io.ramIsWrite) {
    val data = WireDefault(0xFF.U)
    regDataRead := data
    logger.debug(cf"read : addr=${io.ramAddress} data=${data}%x")
    switch (io.ramAddress) {
      is (2.U) {
        // Lower 8 bits of X axis
        data := regLatchX(7, 0)
      }
      is (3.U) {
        // Upper 4 bits of X axis, and bit 7 indicates active-high ready
        data := Cat(regLatched, 0.U(3.W), regLatchX(11, 8))
      }
      is (4.U) {
        // Lower 8 bits of Y axis
        data := regLatchY(7, 0)
      }
      is (5.U) {
        // Upper 8 bits of Y axis
        data := regLatchY(11, 8)
      }
    }
  }
}
