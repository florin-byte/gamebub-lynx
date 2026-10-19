package gameboy.cart.emu

import chisel3._
import chisel3.util._

/**
 * IMU state.
 *
 * All accelerometer values are centered around 0x81D0,
 * +/- the acceleration value in 'g's (9.81m/s**2).
 * 1 g is an offset of ~0x70.
 */
class Mbc7ImuState extends Bundle {
  /** Acceleration along X axis */
  val x = UInt(16.W)
  /** Acceleration along Y axis */
  val y = UInt(16.W)
}

class Mbc7DirectRamAccess extends Bundle {
  /// High when an access should happen.
  /// Will be held high as long as the access hasn't completed.
  /// A new access starts only when this is high *and* valid is low
  /// (that is, the completing cycle does not allow a new access to start)
  val enable = Output(Bool())
  /// High if this access is a write
  val write = Output(Bool())
  /// Address of the access
  val address = Output(UInt(10.W))
  /// Data to be written, if a write
  val dataWrite = Output(UInt(8.W))
  /// Data read, only valid when "valid" is 1.
  val dataRead = Input(UInt(8.W))
  /// Done signal, high for one cycle when the current access is completed.
  val done = Input(Bool())
}

class Mbc7 extends Module {
  val io = IO(new MbcIo {
    val imu = Input(new Mbc7ImuState)

    val directRam = new Mbc7DirectRamAccess
  })


  // N10: TODO mapper save state for MBC7 (only Kirby Tilt 'n' Tumble), still open.
  // Stub for now (io.ssOut = 0). To do: romBank/ramEnable/ramEnable2 + the EEPROM state
  // (Mbc7Eeprom: command/counter/address/data/state/writeEnable).
  // accelX/accelY are live sensor values and need no capture.
  io.ssOut := 0.U
  val ramEnable = RegInit(false.B)
  val ramEnable2 = RegInit(false.B)
  val romBank = RegInit(0.U(7.W))

  val accelX = RegInit(0x8000.U(16.W))
  val accelY = RegInit(0x8000.U(16.W))
  // Whether the accel. values have been erased, and can be latched
  val accelErased = RegInit(true.B)

  val eepromClk = RegInit(false.B)
  val eepromCs = RegInit(false.B)
  val eepromDataIn = RegInit(false.B)
  val eepromDataOut = WireDefault(false.B)
  val eeprom = Module(new Mbc7Eeprom)
  eeprom.io.clk := eepromClk
  eeprom.io.cs := eepromCs
  eeprom.io.dataIn := eepromDataIn
  eepromDataOut := eeprom.io.dataOut
  eeprom.io.directRam <> io.directRam

  // ROM region writes (ram enable and rom banking)
  when (io.memEnable && io.memWrite && io.selectRom) {
    switch(io.memAddress(14, 13)) {
      is(0.U) {
        ramEnable := io.memDataWrite(3, 0) === "b1010".U
      }
      is(1.U) {
        romBank := io.memDataWrite
      }
      is(2.U) {
        ramEnable2 := io.memDataWrite === 0x40.U
      }
    }
  }
  io.bankRom1 := 0.U
  io.bankRom2 := romBank
  io.bankRam := DontCare

  // RAM region accesses (EEPROM and IMU)
  val dataRead = WireDefault(0xFF.U(8.W))
  when (!io.selectRom && ramEnable && ramEnable2) {
    val index = io.memAddress(7, 4)
    switch (index) {
      is (0.U) {
        // Erase accelerometer latch
        when (io.memEnable && io.memWrite && io.memDataWrite === 0x55.U) {
          accelErased := true.B
          accelX := 0x8000.U
          accelY := 0x8000.U
        }
      }
      is (1.U) {
        // Latch accelerometer
        when (io.memEnable && io.memWrite && io.memDataWrite === 0xAA.U && accelErased) {
          accelErased := false.B
          accelX := io.imu.x
          accelY := io.imu.y
        }
      }
      is (2.U) { dataRead := accelX(7, 0) }
      is (3.U) { dataRead := accelX(15, 8) }
      is (4.U) { dataRead := accelY(7, 0) }
      is (5.U) { dataRead := accelY(15, 8) }
      is (6.U) { dataRead := 0x00.U }
      is (7.U) { dataRead := 0xFF.U }
      is (8.U) {
        dataRead := Cat(
          eepromCs, eepromClk, 0.U(4.W), eepromDataIn, eepromDataOut
        )
        when (io.memEnable && io.memWrite) {
          eepromDataIn := io.memDataWrite(1)
          eepromClk := io.memDataWrite(6)
          eepromCs := io.memDataWrite(7)
          eeprom.io.dataIn := io.memDataWrite(1)
          eeprom.io.clk := io.memDataWrite(6)
          eeprom.io.cs := io.memDataWrite(7)
        }
      }
    }
  }
  io.memDataRead := dataRead
  io.ramReadMbc := true.B
}

object Mbc7Eeprom {
  object State extends ChiselEnum {
    /// Initial state: waiting for start condition
    val init = Value
    /// Reading 10 bit command
    val command = Value
    /// Command: read
    val doRead = Value
    /// Command: write (waiting for data)
    val writeData = Value
    /// Command: write (execute)
    val writeExecute = Value
    /// Command: write all (execute)
    val writeAllExecute = Value
    /// Command: erase (wait for falling CS)
    val eraseWait = Value
    /// Command: erase (execute)
    val eraseExecute = Value
    /// Command: erase all (execute)
    val eraseAllExecute = Value
    /// Command done, waiting to reset to initial state
    val done = Value
  }
}

/// Microchip 93LC56
///
/// This is 16-bit words, but our interface for SRAM is 8-bits,
/// so we do some conversions.
class Mbc7Eeprom extends Module {
  import Mbc7Eeprom.State
  val MEM_SIZE = 256 // 256 bytes in words of 16 bits
  val COUNTER_DELAY = 8192 // at 8MHz, about 1ms

  val io = IO(new Bundle {
    val clk = Input(Bool())
    val cs = Input(Bool())
    val dataIn = Input(Bool())
    val dataOut = Output(Bool())

    val directRam = new Mbc7DirectRamAccess
  })
  val clockPosEdge = io.clk && !RegNext(io.clk)
  val csNegEdge = !io.cs && RegNext(io.cs)
  val writeEnable = RegInit(false.B)
  val command = Reg(UInt(10.W))
  val commandIsAll = command(9, 8) === 0.U // Whether an erase or write command applies to all.
  val counter = Reg(UInt(16.W))
  val address = Reg(UInt(8.W)) // Byte oriented address
  val data = Reg(UInt(16.W))

  // Whether we're waiting to read a byte from directRam.
  val regReadBusy = RegInit(false.B)

  io.dataOut := 1.U
  io.directRam.enable := false.B
  io.directRam.address := address
  io.directRam.write := DontCare
  io.directRam.dataWrite := DontCare

  val state = RegInit(State.init)
  switch (state) {
    is (State.init) {
      when (clockPosEdge && io.cs && io.dataIn) {
        // Start condition detected
        state := State.command
        counter := 9.U // 10 bits, minus one
      }
    }
    is (State.command) {
      when (clockPosEdge) {
        val nextCommand = Cat(command, io.dataIn.asUInt)
        command := nextCommand
        when (counter === 0.U) {
          // Process command
          address := nextCommand(6, 0) << 1
          switch (nextCommand(9, 8)) {
            is (0.U) {
              switch (nextCommand(7, 6)) {
                is (0.U) {
                  // erase/write disable
                  writeEnable := false.B
                  state := State.done
                }
                is (1.U) {
                  // write all
                  state := State.writeData
                }
                is (2.U) {
                  // erase all
                  state := State.eraseWait
                }
                is (3.U) {
                  // erase/write enable
                  writeEnable := true.B
                  state := State.done
                }
              }

            }
            is (1.U) {
              // Write
              when(writeEnable) {
                state := State.writeData
                counter := 7.U
              } .otherwise {
                state := State.done
              }
            }
            is (2.U) {
              // Read: prepare to read on the next clock
              counter := 0.U
              data := 0.U
              state := State.doRead
            }
            is (3.U) {
              // Erase
              when (writeEnable) {
                state := State.eraseWait
              } .otherwise {
                state := State.done
              }
            }
          }
        } .otherwise {
          counter := counter - 1.U
        }
      }
    }
    is (State.doRead) {
      io.dataOut := data(7)

      when (regReadBusy) {
        io.directRam.enable := true.B
        io.directRam.write := false.B

        when (io.directRam.done) {
          regReadBusy := false.B
          // Increment the address for the next byte
          address := address + 1.U
          // Latch data
          data := io.directRam.dataRead
        }
      }

      when (clockPosEdge) {
        data := data << 1
        counter := counter - 1.U

        when (counter === 0.U) {
          // Read the next byte.
          assert(!regReadBusy)
          io.directRam.enable := true.B
          io.directRam.write := false.B
          regReadBusy := true.B
          counter := 7.U
          // XXX: this should stall while we're waiting for the read to complete,
          // because we start clocking out the data immediately after.
        }
      }

      when (!io.cs) {
        state := State.init
      }
    }
    is (State.writeData) {
      when (clockPosEdge) {
        data := Cat(data, io.dataIn.asUInt)
        counter := counter - 1.U
      }
      when (csNegEdge) {
        counter := 0.U
        when (commandIsAll) {
          state := State.writeAllExecute
          address := 0.U
        } .otherwise {
          state := State.writeExecute
        }
      }
    }
    is (State.writeExecute) {
      // "Write takes 4ms per word typical"
      io.dataOut := 0.U

      when (counter < 2.U) {
        // Two bus cycles of byte writes for the 16-bit word.
        io.directRam.enable := true.B
        io.directRam.write := true.B
        io.directRam.dataWrite := Mux(
          address(0),
          data(7, 0),
          data(15, 8),
        )
        when (io.directRam.done) {
          counter := counter + 1.U
          address := address + 1.U
        }
      } .otherwise {
        counter := counter + 1.U
      }
      when (counter === COUNTER_DELAY.U) {
        state := State.done
      }
    }
    is (State.writeAllExecute) {
      // "Write all takes 16ms per word typical"
      io.dataOut := 0.U
      when (counter < MEM_SIZE.U) {
        io.directRam.enable := true.B
        io.directRam.write := true.B
        io.directRam.dataWrite := Mux(
          address(0),
          data(7, 0),
          data(15, 8),
        )
        when(io.directRam.done) {
          counter := counter + 1.U
          address := address + 1.U
        }
      } .otherwise {
        counter := counter + 1.U
      }
      when(counter === COUNTER_DELAY.U) {
        state := State.done
      }
    }
    is (State.eraseWait) {
      when(csNegEdge) {
        counter := 0.U
        when (commandIsAll) {
          state := State.eraseAllExecute
          address := 0.U
        }.otherwise {
          state := State.eraseExecute
        }
      }
    }
    is (State.eraseExecute) {
      // "Erase takes 4ms per word typical"
      io.dataOut := 0.U
      when (counter < 2.U) {
        io.directRam.enable := true.B
        io.directRam.write := true.B
        io.directRam.dataWrite := 0xFF.U(8.W)
        when (io.directRam.done) {
          counter := counter + 1.U
          address := address + 1.U
        }
      } .otherwise {
        counter := counter + 1.U
      }
      when (counter === COUNTER_DELAY.U) {
        state := State.done
      }
    }
    is(State.eraseAllExecute) {
      // "Erase all takes 8ms per word typical"
      io.dataOut := 0.U
      when (counter < MEM_SIZE.U) {
        io.directRam.enable := true.B
        io.directRam.write := true.B
        io.directRam.dataWrite := 0xFF.U(8.W)
        when (io.directRam.done) {
          counter := counter + 1.U
          address := address + 1.U
        }
      }.otherwise {
        counter := counter + 1.U
      }
      when (counter === COUNTER_DELAY.U) {
        state := State.done
      }
    }
    is (State.done) {
      when (!io.cs) {
        state := State.init
      }
    }
  }
}