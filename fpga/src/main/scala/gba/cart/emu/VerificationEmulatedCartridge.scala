package gba.cart.emu

import chisel3._
import chisel3.util._
import gba.cart.CartridgeInterface
import lib.log.Logger
import lib.mem.MemoryInterface

/**
 * A version of EmulatedCartridge that uses the physical cartridge bus signals, rather
 * than the virtual ones, to more accurately simulate an actual cartridge.
 *
 * Additionally, this has additional assertions, logging, and verification to make it easier
 * to check that the emulator is using the interface correctly.
 *
 * TODO: support the 'stall' output signal
 */
class VerificationEmulatedCartridge extends Module {
  val io = IO(new Bundle {
    val config = Input(new EmulatedCartridge.Config)
    val interface = Flipped(new CartridgeInterface)

    /// External ROM memory interface, assumed synchronous.
    /// Must keep read data on the bus until the next request.
    val rom = Flipped(new MemoryInterface(addressWidth = 24, dataWidth = 16))
    /// External backup (RAM) memory interface, assumed synchronous.
    /// Must keep read data on the bus until the next request.
    val backup = Flipped(new MemoryInterface(addressWidth = 17, dataWidth = 8))
    /// Whether the previous memory request has not yet completed by the time the GBA needs it to.
    val stall = Output(Bool())
  })
  val logger = Logger("cart.emu")

  io.rom.address := DontCare
  io.rom.enable := false.B
  io.rom.write := false.B
  io.rom.dataWrite := DontCare
  io.rom.writeStrobe := DontCare
  io.backup.address := DontCare
  io.backup.enable := false.B
  io.backup.write := DontCare
  io.backup.dataWrite := DontCare
  io.backup.writeStrobe := 1.U
  io.interface.IRQ := false.B
  io.interface.ADLoIn := "hFFFF".U
  io.interface.AHiIn := "hFF".U
  io.stall := false.B

  val regPreviousAddr = Reg(UInt(24.W))
  val regLatchedAddr = Reg(UInt(24.W))
  val prev_nCS = RegNext(io.interface.nCS)
  val prev_nRD = RegNext(io.interface.nRD)
  val prev_nWR = RegNext(io.interface.nWR)
  val prev_nCS2 = RegNext(io.interface.nCS2)

  // Keep track of the last address actually placed onto the bus.
  when (io.interface.AHiDir && io.interface.ADLoDir && io.interface.nCS && io.interface.nCS2) {
    regPreviousAddr := Cat(io.interface.AHiOut, io.interface.ADLoOut)
  } .otherwise {
    regPreviousAddr := "hFFFF".U
  }

  when (!io.interface.nCS && !io.interface.nCS2) {
    logger.error("nCS and nCS2 low at the same time!")
  }

  when (!io.interface.nCS) {
    when (!prev_nCS2) {
      logger.crit("nCS / nCS2 need a cycle high in between them")
    }
    when (prev_nCS) {
      when (io.interface.AHiDir && io.interface.ADLoDir) {
        regLatchedAddr := regPreviousAddr
      } .otherwise {
        logger.crit("BAD AHiDir/ADLoDir at nCS activate")
        regLatchedAddr := 0.U
      }
    }

    // nRD behavior
    when (io.interface.nRD && !prev_nRD) {
      // nRD rising edge -- increment latched address
      regLatchedAddr := regLatchedAddr + 1.U
    }
    when (!io.interface.nRD && prev_nRD) {
      // nRD falling edge -- start a read
      io.rom.enable := true.B
      io.rom.address := regLatchedAddr
      logger.info(cf"read addr=${regLatchedAddr}%x")
    }
    when (!io.interface.nRD) {
      when (io.interface.ADLoDir) {
        logger.crit("BAD ADLoDir when doing a read")
      } .otherwise {
        io.interface.ADLoIn := io.rom.dataRead
        logger.info(cf"     data=${io.rom.dataRead}%x")
      }
    }

    // nWR behavior
    when (io.interface.nWR && !prev_nWR) {
      // rising edge -- increment latched address
      regLatchedAddr := regLatchedAddr + 1.U
    }
    when (!io.interface.nWR && prev_nWR) {
      // falling edge
      when (!io.interface.ADLoDir) {
        logger.crit("BAD ADLoDir when doing a write")
      } .otherwise {
        val data = io.interface.ADLoOut
        logger.info(cf"write addr=${regLatchedAddr}%x data=${data}%x")
      }
    }
    when (!io.interface.nWR) {
    }
  }

  when (!io.interface.nCS2) {
    when (!prev_nCS) {
      logger.crit("nCS / nCS2 need a cycle high in between them")
    }
  }

  switch (io.config.backupType) {
    is (EmulatedCartridge.BackupType.None) {
    }
    is (EmulatedCartridge.BackupType.Eeprom) {
      // TODO: implement EEPROM
      val eepromSelected = RegInit(false.B)
      when (!io.interface.nCS && prev_nCS && regPreviousAddr(23) === 1.U) {
        logger.crit("Start EEPROM burst")
        eepromSelected := true.B
      }
      when (eepromSelected) {
        when (io.interface.nCS && !prev_nCS) {
          logger.crit("End EEPROM burst")
          eepromSelected := false.B
        }
        when (!io.interface.nWR && prev_nWR) {
          logger.crit("EEPROM write")
        }
        when (!io.interface.nRD && prev_nRD) {
          logger.crit("EEPROM read")
        }
      }
    }
    // TODO: implement SRAM
    // TODO: implement Flash
  }
}
