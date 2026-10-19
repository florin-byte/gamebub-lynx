package gba.cart.emu

import chisel3._
import chisel3.util._
import gba.cart.emu.Rtc.State
import lib.log.Logger

object Rtc {
  object State extends ChiselEnum {
    /// Initial state: read the prefix, command, and read/write bit
    val Command = Value
    /// Done state: do nothing until the next activation.
    val Done = Value
    /// Reading a register
    val Read = Value
    /// Writing a register
    val Write = Value
  }

  object Register extends ChiselEnum {
    val Status = Value
    val DateTime = Value
    val Time = Value
  }

  class Date extends Bundle {
    val _padding3 = UInt(5.W)
    val dayOfWeek = UInt(3.W)
    val _padding2 = UInt(2.W)
    val dayHi = UInt(2.W)
    val dayLo = UInt(4.W)
    val _padding1 = UInt(3.W)
    val monthHi = UInt(1.W)
    val monthLo = UInt(4.W)
    val yearHi = UInt(4.W)
    val yearLo = UInt(4.W)
  }

  class Time extends Bundle {
    val _padding3 = UInt(1.W)
    val secondHi = UInt(3.W)
    val secondLo = UInt(4.W)
    val _padding2 = UInt(1.W)
    val minuteHi = UInt(3.W)
    val minuteLo = UInt(4.W)
    val amPm = UInt(1.W)
    val _padding1 = UInt(1.W)
    val hourHi = UInt(2.W)
    val hourLo = UInt(4.W)
  }

  class Status extends Bundle {
    val _padding4 = UInt(1.W)
    val hour24 = Bool()
    val intAe = Bool()
    val _padding3 = UInt(1.W)
    val intMe = Bool()
    val _padding2 = UInt(1.W)
    val intFe = Bool()
    val _padding1 = UInt(1.W)
  }
}

/// RTC chip: Seiko S-3511A
class Rtc extends Module {
  val clockRate = 16 * 1024 * 1024
  val io = IO(new Bundle {
    /// Access for emulator
    /// Select first 32-bits or second 32-bits of data.
    val emuDataSelect = Input(UInt(1.W))
    val emuDataIn = Input(UInt(32.W))
    val emuDataOut = Output(UInt(32.W))
    val emuDataWrite = Input(Bool())

    /// Serial clock: data sampled on rising edge, set on falling edge. Idles high.
    val serialClock = Input(Bool())
    /// Active high chip-select
    val serialSelect = Input(Bool())
    val serialIn = Input(UInt(1.W))
    val serialOut = Output(UInt(1.W))

    /// Active high interrupt output (probably won't be implemented, seems to be unused).
    val irq = Output(Bool())
  })
  val logger = Logger("cart.emu.rtc")

  /// Tick counter (subsecond)
  val tickCounter = new Counter(clockRate)
  /// Current date
  val regDate = Reg(new Rtc.Date)
  /// Current time
  val regTime = Reg(new Rtc.Time)
  /// Current status register
  val regStatus = Reg(new Rtc.Status)
  /// Current register that's being written
  val regWriteRegister = RegInit(Rtc.Register.Status)
  /// Current serial state
  val regState = RegInit(State.Done)
  /// Serial counter
  val regCounter = Reg(UInt(6.W))
  /// Serial buffer
  val regBuffer = Reg(UInt(56.W))
  /// Serial output bit
  val regOut = RegInit(1.U(1.W))

//  when (reset.asBool) {
//    regDate.yearHi := 5.U
//    regDate.yearLo := 2.U
//    regDate.monthHi := 0.U
//    regDate.monthLo := 2.U
//    regDate.dayHi := 2.U
//    regDate.dayLo := 8.U
//    regDate.dayOfWeek := 2.U
//    regTime.hourHi := 2.U
//    regTime.hourLo := 3.U
//    regTime.minuteHi := 5.U
//    regTime.minuteLo := 9.U
//    regTime.secondHi := 5.U
//    regTime.secondLo := 8.U
//  }

  io.irq := false.B
  io.serialOut := regOut

  // Days in the month
  val maxDayLo = WireDefault(0.U(4.W))
  val maxDayHi = WireDefault(3.U(2.W))
  val bcdMonth = Cat(regDate.monthHi, regDate.monthLo)
  // Leap year in BCD: for the range 2000-2099, we only care if the last 2 digits are divisible by 4.
  // divisible = ((lo + (hi << 1)) & 0b11) == 0
  val isLeapYear = {
    val sum = regDate.yearLo + (regDate.yearHi << 1).asUInt
    sum(1, 0) === 0.U
  }
  when (VecInit(Seq("h1", "h3", "h5", "h7", "h8", "h10", "h12").map(x => bcdMonth === x.U)).asUInt.orR) {
    maxDayLo := 1.U
  }
  when (bcdMonth === "h2".U) {
    maxDayHi := 2.U
    maxDayLo := Mux(isLeapYear, 9.U, 8.U)
  }

  // Tick the time.
  // TODO: handle 12 hour mode and AM/PM
  val tick = tickCounter.inc()
  // Cascade of time values: (register, max value)
  val cascade = Seq(
    (regTime.secondLo, 9.U),
    (regTime.secondHi, 5.U),
    (regTime.minuteLo, 9.U),
    (regTime.minuteHi, 5.U),
    (regTime.hourLo, Mux(regTime.hourHi === 2.U, 3.U, 9.U)),
    (regTime.hourHi, 2.U),
    (regDate.dayLo, Mux(regDate.dayHi === maxDayHi, maxDayLo, 9.U)),
    (regDate.dayHi, maxDayHi),
    (regDate.monthLo, Mux(regDate.monthHi === 1.U, 2.U, 9.U)),
    (regDate.monthHi, 1.U),
    (regDate.yearLo, 9.U),
    (regDate.yearHi, 9.U),
  )
  val ticked = WireDefault(VecInit.fill(cascade.length)(false.B))
  ticked(0) := tick
  for (((reg, max), i) <- cascade.zipWithIndex) {
    when (ticked(i)) {
      reg := reg + 1.U
      when (reg === max) {
        if (i < cascade.length - 1) {
          ticked(i + 1) := true.B
        }
        reg := 0.U
      }
    }
  }
  when (ticked(6)) {
    // dayLo ticked, update day of week
    regDate.dayOfWeek := regDate.dayOfWeek + 1.U
    when (regDate.dayOfWeek === 6.U) {
      regDate.dayOfWeek := 0.U
    }
  }
  when (ticked(8)) {
    // monthLo ticked, so dayHi overflowed. Restart at 1.
    regDate.dayLo := 1.U
  }
  when (ticked(10)) {
    // yearLo ticked, so monthHi overflowed. Restart at 1.
    regDate.monthLo := 1.U
  }

  // Serial access
  val prevSelect = RegNext(io.serialSelect)
  val prevClock = RegNext(io.serialClock)
  when (io.serialSelect) {
    // Rising edge of chip select
    when (!prevSelect) {
      logger.info("Selected")
      regState := State.Command
      regCounter := 7.U
    }

    // Rising edge of clock: sample data
    when (io.serialClock && !prevClock) {
//      logger.debug(cf"serial  in: data=${io.serialIn} counter=${regCounter}")

      switch (regState) {
        is (State.Command) {
          regCounter := regCounter - 1.U
          regBuffer := Cat(regBuffer, io.serialIn)

          when (regCounter === 0.U) {
            val prefix = regBuffer(6, 3)
            val command = regBuffer(2, 0)
            val isRead = io.serialIn.asBool
            logger.info(cf"Got command. prefix=${prefix}%b command=${command}%b isRead=${isRead}")

            // Default state: ignore
            regState := State.Done
            regOut := 1.U
            when (prefix === "b0110".U) {
              switch (command) {
                is ("b000".U) {
                  logger.info("Reset")
                  regStatus := 0.U.asTypeOf(new Rtc.Status)
                  regDate.yearLo := 0.U
                  regDate.yearHi := 0.U
                  regDate.monthLo := 1.U
                  regDate.monthHi := 0.U
                  regDate.dayLo := 1.U
                  regDate.dayHi := 0.U
                  regDate.dayOfWeek := 0.U
                  regTime.hourLo := 0.U
                  regTime.hourHi := 0.U
                  regTime.amPm := 0.U
                  regTime.minuteLo := 0.U
                  regTime.minuteHi := 0.U
                  regTime.secondLo := 0.U
                  regTime.secondHi := 0.U
                }
                is ("b001".U) {
                  // Status register
                  logger.info("Status")
                  when (isRead) {
                    regState := State.Read
                    regBuffer := regStatus.asUInt
                  } .otherwise {
                    regState := State.Write
                    regWriteRegister := Rtc.Register.Status
                  }
                  regCounter := (8 - 1).U
                }
                is ("b010".U) {
                  // Date and time
                  logger.info("Date and time")
                  when (isRead) {
                    regState := State.Read
                    regBuffer := Cat(regTime.asUInt, regDate.asUInt)
                  } .otherwise {
                    regState := State.Write
                    regWriteRegister := Rtc.Register.DateTime
                  }
                  regCounter := (8 * 7 - 1).U
                }
                is ("b011".U) {
                  // Time only
                  logger.info("Time")
                  when (isRead) {
                    regState := State.Read
                    regBuffer := regTime.asUInt
                  } .otherwise {
                    regState := State.Write
                    regWriteRegister := Rtc.Register.Time
                  }
                  regCounter := (8 * 3 - 1).U
                }
                // Alarm and test mode unimplemented
              }
            }
          }
        }
        is (State.Write) {
          val buffer = Cat(io.serialIn, regBuffer >> 1)
          regBuffer := buffer

          regCounter := regCounter - 1.U
          when (regCounter === 0.U) {
            // TODO
            logger.info(cf"Done write: data=${buffer}%x")
            regState := State.Done
          }
        }
      }
    }

    // Falling edge of clock: set output
    when (!io.serialClock && prevClock) {
      when (regState === State.Read) {
        regOut := regBuffer(0)
        regBuffer := regBuffer >> 1

        regCounter := regCounter - 1.U
        when (regCounter === 0.U) {
          logger.info(cf"Done read")
          regState := State.Done
        }
      }
    }
  }

  // Emulator buffer access
  when (io.emuDataSelect === 0.U) {
    io.emuDataOut := regDate.asUInt
  } .otherwise {
    io.emuDataOut := Cat(regStatus.asUInt, regTime.asUInt)
  }
  when (io.emuDataWrite) {
    when (io.emuDataSelect === 0.U) {
      regDate := io.emuDataIn.asTypeOf(new Rtc.Date)
      regDate._padding1 := 0.U
      regDate._padding2 := 0.U
      regDate._padding3 := 0.U
    } .otherwise {
      regTime := io.emuDataIn(23, 0).asTypeOf(new Rtc.Time)
      regStatus := io.emuDataIn(31, 24).asTypeOf(new Rtc.Status)
    }
  }
}
