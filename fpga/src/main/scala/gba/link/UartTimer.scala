package gba.link

import chisel3._

class UartTimer(counterWidth: Int = 17) extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())
    val baudrate = Input(UInt(2.W))

    /// Pulses high at the beginning of each period
    /// Doesn't pulse high for the first period after reset.
    val pulse = Output(Bool())
    /// Pulses high in the middle of each period
    val pulseMid = Output(Bool())
  })

  // The actual timing can't be gated by enable, because UART is very time sensitive.
  // So running a percent or two slow (because of stalls) would break communication.
  // Instead, always count the timer (even when not enabled), and latch the pulse bits
  // until the next enabled cycle.
  val latchedPulse = RegInit(false.B)
  val latchedPulseMid = RegInit(false.B)
  io.pulse := false.B
  io.pulseMid := false.B

  val counter = RegInit(0.U(counterWidth.W))
  val increment = VecInit(counterIncrements)(io.baudrate)
  val nextCounter = counter +& increment
  counter := nextCounter

  val pulseMid = nextCounter(counterWidth - 1) && !counter(counterWidth - 1)
  val pulse = nextCounter(counterWidth)
  when (io.enable) {
    io.pulse := latchedPulse || pulse
    io.pulseMid := latchedPulseMid || pulseMid
    latchedPulse := false.B
    latchedPulseMid := false.B
  } .otherwise {
    latchedPulse := latchedPulse || pulse
    latchedPulseMid := latchedPulseMid || pulseMid
  }

  private def counterIncrements: Seq[UInt] = {
    UartTimer.baudrates.map(baudrate => {
      val clockHz = 16 * 1024 * 1024
      val counterMax = 1 << counterWidth
      (counterMax.toDouble * baudrate.toDouble / clockHz.toDouble).round.toInt.U
    })
  }
}

object UartTimer {
  val baudrates: Seq[Int] = Seq(9600, 38400, 57600, 115200)
}