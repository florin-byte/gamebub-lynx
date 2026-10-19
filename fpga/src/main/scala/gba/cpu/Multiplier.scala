package gba.cpu

import chisel3._
import chisel3.util._

class Multiplier extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())

    val a = Input(UInt(32.W))
    val b = Input(UInt(32.W))

    val start = Input(Bool())
    val loadAccumulator = Input(Bool())
    val accumulate = Input(Bool())
    val signed = Input(Bool())
    val long = Input(Bool())

    val outLo = Output(UInt(32.W))
    val outHi = Output(UInt(32.W))
    val done = Output(Bool())
    val outFlagZ = Output(Bool())
    val outFlagN = Output(Bool())
  })

  val accumulator = Reg(UInt(64.W))
  val output = Reg(UInt(64.W))
  val counter = Reg(UInt(2.W))

  // Determine cycle length.
  // Early termination based on the number of leading 0s or 1s.
  // For unsigned long multiply, it's only leading 0s.
  val prefixZeroes = VecInit(!io.b(31, 24).orR, !io.b(23, 16).orR, !io.b(15, 8).orR, !io.b(7, 0).orR).asUInt
  val prefixOnes = VecInit(io.b(31, 24).andR, io.b(23, 16).andR, io.b(15, 8).andR, io.b(7, 0).andR).asUInt
  val termOnes = !(io.long && !io.signed)
  val numCycles = MuxCase(3.U, Seq(
    (prefixZeroes(2, 0) === "b111".U || (prefixOnes(2, 0) === "b111".U && termOnes)) -> 0.U,
    (prefixZeroes(1, 0) === "b11".U || (prefixOnes(1, 0) === "b11".U && termOnes)) -> 1.U,
    (prefixZeroes(0, 0) === "b1".U || (prefixOnes(0, 0) === "b1".U && termOnes)) -> 2.U,
  ))

  when (io.enable) {
    when (io.loadAccumulator) {
      accumulator := Cat(io.a, io.b)
    }
    when (io.start) {
      val augend = Mux(io.accumulate, accumulator, 0.U)

      // TODO: decompose this into repeated 32x8 multiplies to improve timing?
      when (io.signed) {
        output := (io.a.asSInt * io.b.asSInt).asUInt + augend
      } .otherwise {
        output := (io.a * io.b) + augend
      }
      counter := numCycles
    }
    when (counter > 0.U) {
      counter := counter - 1.U
    }
  }

  val zeroLo = output(31, 0) === 0.U
  val zeroHi = output(63, 32) === 0.U

  io.done := counter === 0.U
  io.outLo := output(31, 0)
  io.outHi := output(63, 32)
  io.outFlagZ := Mux(io.long, zeroLo && zeroHi, zeroLo)
  io.outFlagN := Mux(io.long, output(63), output(31))
}
