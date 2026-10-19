package gba.cart.emu

import chisel3._
import chisel3.util._
import lib.log.Logger
import lib.mem.MemoryInterface

class SramBackup extends Module {
  val io = IO(new Bundle {
    val ramEnable = Input(Bool())
    val ramAddress = Input(UInt(16.W))
    val ramIsWrite = Input(Bool())
    val ramDataRead = Output(UInt(8.W))
    val ramDataWrite = Input(UInt(8.W))
    val ramReqEnd = Input(Bool())

    val stall = Output(Bool())

    val backup = Flipped(new MemoryInterface(addressWidth = 15, dataWidth = 8))
  })
  val logger = Logger("cart.emu.sram")

  val regAddress = Reg(UInt(16.W))
  val regBusy = Reg(Bool())
  val regIsWrite = Reg(Bool())

  io.stall := false.B

  io.backup.enable := false.B
  io.backup.address := DontCare
  io.backup.write := DontCare
  io.backup.writeStrobe := 1.U
  io.ramDataRead := io.backup.dataRead
  io.backup.dataWrite := io.ramDataWrite

  when (io.ramEnable) {
    regAddress := io.ramAddress
    regBusy := true.B
    regIsWrite := io.ramIsWrite

    io.backup.enable := true.B
    io.backup.address := io.ramAddress
    io.backup.write := io.ramIsWrite
  }
  when (regBusy) {
    io.backup.enable := true.B
    io.backup.address := regAddress
    io.backup.write := regIsWrite

    when (io.backup.done) {
      regBusy := false.B
      when (regIsWrite) {
        logger.debug(cf"SRAM write done: addr=0x${regAddress}%x data=0x${io.backup.dataWrite}%x")
      } .otherwise {
        logger.debug(cf"SRAM read done:  addr=0x${regAddress}%x data=0x${io.backup.dataRead}%x")
      }
    } .elsewhen (io.ramReqEnd) {
      logger.warn("SRAM request stall")
      io.stall := true.B
    }
  }
}
