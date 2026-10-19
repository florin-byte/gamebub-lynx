package gba

import chisel3._
import chisel3.util._
import lib.log.Logger

class TimerControl extends Bundle {
  val enable = Bool()
  val irq = Bool()
  val _padding = UInt(3.W)
  val cascade = Bool()
  val freq = UInt(2.W)
}

class Timer extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())

    val mmio = new MmioTarget()

    val irq = Output(Vec(4, Bool()))

    /// Overflow signal for timers 0 and 1 (for audio)
    val timerOverflow = Output(Vec(2, Bool()))
  })
  private val logger = Logger("timer", enable = io.enable)

  io.irq := VecInit.fill(4)(false.B)

  val regControl = Seq.fill(4)(RegInit(0.U.asTypeOf(new TimerControl)))
  val regCounterReload = Seq.fill(4)(RegInit(0.U(16.W)))
  val regCounter = Seq.fill(4)(RegInit(0.U(16.W)))

  io.mmio <> MmioMap.fromSeq(
    (0 until 4).map(i => 0x100 + (4 * i) -> MmioMap.Entry(
      MmioMap.ReadFn(regCounter(i), regControl(i)),
      MmioMap.WriteFn(regCounterReload(i), regControl(i)),
    ))
  )

  // Prescaler / clock divider
  val prescalerTick = Wire(Vec(4, Bool()))
  val prescalerCounter = RegInit(0.U(11.W))
  val nextPrescalerCounter = prescalerCounter + 1.U
  when (io.enable) {
    prescalerCounter := nextPrescalerCounter
  }
  prescalerTick(0) := nextPrescalerCounter(0) ^ prescalerCounter(0) // by 1
  prescalerTick(1) := nextPrescalerCounter(6) ^ prescalerCounter(6) // by 64
  prescalerTick(2) := nextPrescalerCounter(8) ^ prescalerCounter(8) // by 256
  prescalerTick(3) := nextPrescalerCounter(10) ^ prescalerCounter(10) // by 1024

  val overflow = WireDefault(VecInit.fill(4)(false.B))
  io.timerOverflow(0) := overflow(0)
  io.timerOverflow(1) := overflow(1)
  for (i <- 0 until 4) {
    val control = regControl(i)
    val counter = regCounter(i)
    val counterReload = regCounterReload(i)

    val justEnabled = control.enable && !RegEnable(control.enable, io.enable)
    val tick = Wire(Bool())
    if (i == 0) {
      tick := prescalerTick(control.freq)
    } else {
      tick := Mux(control.cascade, overflow(i - 1), prescalerTick(control.freq))
    }

    when (io.enable) {
      when (justEnabled) {
        logger.info(cf"$i: enable $control")
        counter := counterReload
      } .elsewhen (control.enable && tick) {
        val next = counter + 1.U
        when (next === 0.U) {
          logger.info(cf"$i: overflow, reload=${counterReload}")
          overflow(i) := true.B
          when (control.irq) {
            io.irq(i) := true.B
          }
          counter := counterReload
        } .otherwise {
          counter := next
        }
      }
    }
  }
}
