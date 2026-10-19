package lib.audio

import chisel3._
import chisel3.util._

/**
 * A decimating low-pass for a core's audio output (NES, NGPC, Mega Drive): a CIC (`stages` cascaded box
 * averages of `ratio` enabled clocks), integrating on `enable` (a delivered
 * core clock) and emitting `outValid` every `ratio` of them. Unity DC gain
 * (the integrators grow by ratio^stages, shifted back), wrap-around
 * arithmetic as CIC filters require.
 */
class AudioDecimator(inWidth: Int = 16, ratio: Int = 512, stages: Int = 3) extends Module {
  require(isPow2(ratio) && ratio >= 4)
  val io = IO(new Bundle {
    val enable = Input(Bool())
    val in = Input(SInt(inWidth.W))
    val out = Output(SInt(inWidth.W))
    val outValid = Output(Bool())
  })
  val growth = stages * log2Ceil(ratio)
  val width = inWidth + growth
  val integrators = Seq.fill(stages)(RegInit(0.S(width.W)))
  when (io.enable) {
    integrators.foldLeft(io.in) { (x, acc) =>
      acc := acc + x
      acc
    }
  }
  val counter = RegInit(0.U(log2Ceil(ratio).W))
  val strobe = io.enable && counter.andR
  when (io.enable) {
    counter := counter + 1.U
  }
  val delays = Seq.fill(stages)(RegInit(0.S(width.W)))
  val combOut = delays.foldLeft(integrators.last: SInt) { (x, d) =>
    when (strobe) { d := x }
    x - d
  }
  val out = RegInit(0.S(inWidth.W))
  when (strobe) {
    out := (combOut >> growth).asSInt
  }
  io.out := out
  io.outValid := RegNext(strobe, false.B)
}
