package lib.fifo

import chisel3._
import chisel3.util._

/// Simple synchronous FIFO.
///
/// Implements "first word fall through": read data is available without being popped
///
/// Designed to use registers/distributed RAM rather than block RAM
class SimpleFwftFifo[T <: Data](gen: T, entries: Int) extends Module {
  if (!isPow2(entries)) {
    throw new AssertionError()
  }

  val io = IO(new Bundle {
    val read = new Bundle {
      val data = Output(gen)
      val empty = Output(Bool())
      val pop = Input(Bool())
    }
    val write = new Bundle {
      val data = Input(gen)
      val full = Output(Bool())
      val push = Input(Bool())
    }

    val count = Output(UInt(log2Ceil(entries).W))
  })

  val regReadPtr = RegInit(0.U(log2Ceil(entries).W))
  val regWritePtr = RegInit(0.U(log2Ceil(entries).W))
  val regData = Reg(Vec(entries, gen))

  io.read.data := regData(regReadPtr)
  io.read.empty := regReadPtr === regWritePtr
  io.write.full := regReadPtr === (regWritePtr + 1.U)

  io.count := (regWritePtr +& entries.U -& regReadPtr)

  when (io.read.pop && !io.read.empty) {
    regReadPtr := regReadPtr + 1.U
  }
  when (io.write.push && (!io.write.full || io.read.pop)) {
    regData(regWritePtr) := io.write.data
    regWritePtr := regWritePtr + 1.U
  }
}
