package gba.cart.emu

import chisel3._
import chisel3.util._
import gba.cart.emu.EepromBackup.State
import lib.log.Logger
import lib.mem.MemoryInterface

object EepromBackup {
  object State extends ChiselEnum {
    val Idle = Value
    val GetCommand = Value
    val GetRead = Value
    val DoRead = Value
    val GetWriteAddress = Value
    val GetWriteData = Value
    val DoWrite = Value
  }
}

class EepromBackup extends Module {
  val io = IO(new Bundle {
    val configSize = Input(UInt(1.W))
    val configAutodetect = Input(Bool())

    /// Whether the EEPROM chip is selected
    val selected = Input(Bool())
    val readPulse = Input(Bool())
    val writePulse = Input(Bool())
    val dataRead = Output(UInt(1.W))
    val dataWrite = Input(UInt(1.W))
    val reqEnd = Input(Bool())

    val stall = Output(Bool())

    val backup = Flipped(new MemoryInterface(addressWidth = 13, dataWidth = 8))
  })
  val logger = Logger("cart.emu.eeprom")

  io.stall := false.B
  io.backup.enable := false.B
  io.backup.address := DontCare
  io.backup.dataWrite := DontCare
  io.backup.write := DontCare
  io.backup.writeStrobe := 1.U
  io.backup.dataRead := DontCare
  val regOut = RegInit(1.U(1.W))
  io.dataRead := regOut

  // Size autodetection, based on number of bits transferred in the first read
  val eepromSize = WireDefault(io.configSize)
  val regDetectDone = RegInit(false.B)
  val regDetectSize = Reg(Bool())
  when (io.configAutodetect && regDetectDone) {
    eepromSize := regDetectSize
  }

  val regState = RegInit(State.Idle)
  val regData = Reg(UInt(64.W))
  val regCounter = Reg(UInt(6.W))
  /// Current address, in 64-bit blocks.
  val regAddress = Reg(UInt(14.W))
  /// Number of dummy reads left
  val regDummy = Reg(UInt(3.W))

  // Handle writes
  when (io.selected && io.writePulse) {
//    logger.debug(cf"EEPROM write: ${io.dataWrite}")
    val bit = io.dataWrite
    switch (regState) {
      is (State.Idle) {
        when (bit === 1.U) {
          regState := State.GetCommand
        }
      }
      is (State.GetCommand) {
        regCounter := 0.U
        when (bit === 1.U) {
          regData := 0.U
          regState := State.GetRead
        } .otherwise {
          logger.info("EEPROM command write")
          regState := State.GetWriteAddress
        }
      }
      is (State.GetRead) {
        regData := Cat(regData, bit)
        regCounter := regCounter + 1.U
      }
      is (State.GetWriteAddress) {
        val nextData = Cat(regData, bit)
        val nextCounter = regCounter + 1.U
        regData := nextData
        regCounter := nextCounter
        val addressSize = Mux(eepromSize === 0.U, 6.U, 14.U)
        when (nextCounter === addressSize) {
          val address = Mux(eepromSize === 0.U, nextData(5, 0), nextData(13, 0))

          regAddress := address
          regCounter := 0.U
          regState := State.GetWriteData
        }
      }
      is (State.GetWriteData) {
        val nextData = Cat(regData, bit)
        regData := nextData
        regCounter := regCounter + 1.U
        when (regCounter === 63.U) {
          // Finished getting write data
          regState := State.DoWrite
          logger.debug(cf"EEPROM write: addr=${regAddress}%x data=${nextData(63, 0)}%x")
        }
      }
    }
  }

  when (!io.selected) {
    regState := State.Idle

    // Finish getting read address, start read.
    when (regState === State.GetRead) {
      when (io.configAutodetect && !regDetectDone) {
        regDetectDone := true.B
        eepromSize := regCounter > 7.U
        regDetectSize := regCounter > 7.U // 6 bit address, 1 bit end
        logger.warn(cf"Detected EEPROM size: ${eepromSize}")
      }

      val address = Mux(eepromSize === 0.U, regData(6, 1), regData(14, 1))
      regAddress := address
      regState := State.DoRead
      regCounter := 0.U
      logger.info(cf"EEPROM read: addr=${address}%x")
    }
  }

  when (io.selected && io.readPulse) {
    when (regDummy > 0.U) {
      regDummy := regDummy - 1.U
      regOut := 0.U
    } .otherwise {
      regOut := regData(63)
      // Shift in 1 so that a read will eventually return 1 (needed for write completion).
      regData := Cat(regData, 1.U(1.W))
    }
  }

  // Actually do the read or write from memory. Stall until it's done, for simplicity.
  io.backup.address := Cat(regAddress, regCounter(2, 0))
  when (regState === State.DoRead) {
    io.stall := true.B
    io.backup.enable := true.B
    io.backup.write := false.B
    regState := State.DoRead
    when (io.backup.done) {
      logger.debug(cf"- read data ${io.backup.address}%x: ${io.backup.dataRead}%x")
      regCounter := regCounter + 1.U
      val nextData = Cat(regData, io.backup.dataRead)
      regData := nextData
      when (regCounter === 7.U) {
        regDummy := 4.U
        regState := State.Idle
        logger.debug(cf"- read complete: ${nextData(63, 0)}%x")
      }
    }
  }
  when (regState === State.DoWrite) {
    io.stall := true.B
    io.backup.enable := true.B
    io.backup.write := true.B
    io.backup.dataWrite := regData(63, 56)
    when (io.backup.done) {
      logger.debug(cf"- write data ${io.backup.address}%x: ${io.backup.dataWrite}%x")
      // Shift data.
      regData := regData << 8
      regCounter := regCounter + 1.U
      when (regCounter === 7.U) {
        regState := State.Idle
        regOut := 1.U
      }
    }
  }
}
