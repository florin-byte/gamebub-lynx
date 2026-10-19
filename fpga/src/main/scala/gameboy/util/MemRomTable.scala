package gameboy.util

import chisel3._
import chisel3.util._
import gameboy.Gameboy

/** A table stored in a memory-based ROM */
class MemRomTable[T <: Data](config: Gameboy.Configuration, gen: T, contents: Seq[T]) extends Module {
  val addressWidth = log2Ceil(contents.length)
  val dataWidth = gen.getWidth
  val io = IO(new Bundle {
    val addr = Input(UInt(addressWidth.W))
    val data = Output(gen)
  })


  // TODO: generate output more optimized for Verilator.
  // similar to firrtl.annotations.MemoryArrayInitAnnotation in Chisel 3.6.0.
  val table = VecInit(contents)
  io.data := table(io.addr)
}
