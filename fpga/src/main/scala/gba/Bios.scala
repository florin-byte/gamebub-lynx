package gba

import chisel3._
import gba.mem.TargetInterface
import lib.log.Logger

class BiosRomAccess extends Bundle {
  val read = Output(Bool())
  val address = Output(UInt(12.W))
  val data = Input(UInt(32.W))
}

class Bios extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())
    val target = new TargetInterface(32.W)
    val access = new BiosRomAccess
    val unlocked = Output(Bool())

    // Bus request signals to lock/unlock
    val busRequest = Input(Bool())
    val busIsData = Input(Bool())
    val busAddress = Input(UInt(32.W))
  })
  val logger = Logger("bios", enable = io.enable)

  // Lock/unlock: unlocked when an opcode fetch from the bios region happens.
  val regUnlocked = RegInit(true.B)
  val requestIsAllowed = regUnlocked || !io.busIsData
  io.unlocked := regUnlocked
  when (io.enable) {
    when (io.busRequest && !io.busIsData) {
      val inBios = io.busAddress(31, 14) === 0.U
      when (inBios =/= regUnlocked) {
        logger.info(cf"unlocked=${inBios}")
        regUnlocked := inBios
      }
    }
  }

  val readEnable = io.enable && io.target.request
  val readBusy = RegInit(false.B)
  io.target.done := false.B
  when (io.enable && readBusy) {
    io.target.done := true.B
    readBusy := false.B
  }
  when (readEnable) {
    readBusy := true.B
  }
  io.access.read := readEnable
  io.access.address := io.target.address >> 2
  io.target.dataRead := io.access.data

  // Latch the read data, in case enable goes to false.
  // Also used in the case of a bios data read from non-bios code ("bios open bus").
  val readLatch = Reg(UInt(32.W))
  val lastRead = RegNext(readEnable && requestIsAllowed)
  when (lastRead) {
    readLatch := io.access.data
  } .otherwise {
    io.target.dataRead := readLatch
  }
}
