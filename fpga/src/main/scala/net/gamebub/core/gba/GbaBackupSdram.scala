package net.gamebub.core.gba

import chisel3._
import chisel3.util._
import lib.mem.{MemoryInterface, PipelineMemoryInterface}

/** GB-156: a cartridge backup (save) kept in SDRAM instead of the external SRAM, which two
  * GBA cores fill with their two EWRAMs.
  *
  * The backup side is the 8-bit interface of EmulatedCartridge: the request is held until
  * `done`, read data stays valid until the next request. The SDRAM path has no byte strobes,
  * so a write is a read of the 32-bit word followed by a write of the merged word.
  * `base` is the byte address of the backup in SDRAM (a multiple of 4).
  */
class GbaBackupSdram(base: Long) extends Module {
  require(base % 4 == 0)
  val io = IO(new Bundle {
    val backup = new MemoryInterface(addressWidth = 17, dataWidth = 8)
    val sdram = Flipped(new PipelineMemoryInterface(addressWidth = 25, dataWidth = 32))
  })

  object State extends ChiselEnum {
    val idle, read, writeRequest, write = Value
  }
  val state = RegInit(State.idle)
  val regByte = Reg(UInt(2.W))
  val regIsWrite = Reg(Bool())
  val regWord = Reg(UInt(32.W))
  val regLast = Reg(UInt(8.W))

  val wordAddress = base.U(25.W) + Cat(io.backup.address(16, 2), 0.U(2.W))
  val readBytes = io.sdram.dataRead.asTypeOf(Vec(4, UInt(8.W)))

  io.sdram.enable := false.B
  io.sdram.address := wordAddress
  io.sdram.isWrite := false.B
  io.sdram.writeStrobe := "b1111".U
  io.sdram.dataWrite := regWord
  io.backup.done := false.B
  io.backup.dataRead := regLast

  switch (state) {
    is (State.idle) {
      when (io.backup.enable && io.sdram.ready) {
        io.sdram.enable := true.B
        regByte := io.backup.address(1, 0)
        regIsWrite := io.backup.write
        state := State.read
      }
    }
    is (State.read) {
      when (io.sdram.ready) {
        when (regIsWrite) {
          val merged = Wire(Vec(4, UInt(8.W)))
          merged := readBytes
          merged(regByte) := io.backup.dataWrite
          regWord := merged.asUInt
          state := State.writeRequest
        } .otherwise {
          regLast := readBytes(regByte)
          io.backup.dataRead := readBytes(regByte)
          io.backup.done := true.B
          state := State.idle
        }
      }
    }
    is (State.writeRequest) {
      when (io.sdram.ready) {
        io.sdram.enable := true.B
        io.sdram.isWrite := true.B
        state := State.write
      }
    }
    is (State.write) {
      when (io.sdram.ready) {
        io.backup.done := true.B
        state := State.idle
      }
    }
  }
}
