package gba.cart.emu

import chisel3._
import chisel3.util._
import lib.log.Logger

class Gpio extends Module {
  val io = IO(new Bundle {
    val reqAddress = Input(UInt(2.W))
    val reqWrite = Input(Bool())
    val dataRead = Output(UInt(4.W))
    val dataWrite = Input(UInt(4.W))
    val isReadable = Output(Bool())

    // Pin output values
    val pinOut = Output(UInt(4.W))
    // Pin input values
    val pinIn = Input(UInt(4.W))
    // Pin directions, 0 for input, 1 for output
    val pinDir = Output(UInt(4.W))
  })
  val logger = Logger("cart.emu.gpio")

  val regOut = RegInit(0.U(4.W))
  val regDir = RegInit(0.U(4.W))
  val regReadable = RegInit(false.B)

  io.dataRead := DontCare
  io.pinOut := regOut
  io.pinDir := regDir
  io.isReadable := regReadable

  switch (io.reqAddress) {
    is (((0xC4 >> 1) & 3).U) {
      io.dataRead := io.pinIn//(io.pinIn & (~regDir).asUInt) | (regOut & regDir)
    }
    is (((0xC6 >> 1) & 3).U) {
      io.dataRead := regDir
    }
    is (((0xC8 >> 1) & 3).U) {
      io.dataRead := regReadable
    }
  }

  when (io.reqWrite) {
    logger.info(cf"write reg=${0xC4.U + ((io.reqAddress - 2.U) << 1)}%x data=${io.dataWrite}%b")
    switch (io.reqAddress) {
      is (((0xC4 >> 1) & 3).U) {
        regOut := io.dataWrite
      }
      is (((0xC6 >> 1) & 3).U) {
        regDir := io.dataWrite
      }
      is (((0xC8 >> 1) & 3).U) {
        regReadable := io.dataWrite(0)
      }
    }
  }
}
