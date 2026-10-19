package gba.apu

import chisel3._
import gba.MMIO
import lib.log.Logger

class DirectChannel(name: String) extends Module {
  val io = IO(new Bundle {
    val writeEnable = Input(Bool())
    val writeData = Input(UInt(32.W))
    val writeMask = Input(UInt(4.W))

    val timerTrigger = Input(Bool())
    val dmaTrigger = Output(Bool())

    val sample = Output(SInt(8.W))
  })
  val logger = Logger(s"apu.dma-$name")

  val buffer = RegInit(VecInit.fill(8)(0.U(32.W)))
  /// Word-based write index
  val regWriteIndex = RegInit(0.U(3.W))
  /// *Byte*-based read index
  val regReadIndex = RegInit(0.U(5.W))
  val readIndexWord = regReadIndex(4, 2)
  val regSample = RegInit(0.U(8.W))

  val isEmpty = regWriteIndex === readIndexWord
  val isAlmostEmpty = !(regWriteIndex - readIndexWord)(2)

  io.dmaTrigger := false.B
  io.sample := regSample.asTypeOf(SInt(8.W))

  when (io.writeEnable) {
    logger.debug(cf"write data=${io.writeData}%x mask=${io.writeMask}%b")
    buffer(regWriteIndex) := MMIO.mask(buffer(regWriteIndex), io.writeData, io.writeMask)
    regWriteIndex := regWriteIndex + 1.U
  }
  when (io.timerTrigger) {
    logger.debug(cf"timer")
    when (!isEmpty) {
      regSample := buffer(readIndexWord).asTypeOf(Vec(4, UInt(8.W)))(regReadIndex(1, 0))
      regReadIndex := regReadIndex + 1.U
    }
    when (isAlmostEmpty) {
      io.dmaTrigger := true.B
    }
  }
}
