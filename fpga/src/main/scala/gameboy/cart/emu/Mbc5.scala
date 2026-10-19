package gameboy.cart.emu

import chisel3._
import chisel3.util._

/** MBC5 */
class Mbc5 extends Module {
  val io = IO(new MbcIo {
    val hasRumble = Input(Bool())
    val rumble = Output(Bool())
  })

  val ramEnable = RegInit(false.B)
  val bankRomLo = RegInit(1.U(8.W))
  val bankRomHi = RegInit(0.U(1.W))
  val bankRam = RegInit(0.U(4.W))
  val regClockLatch = RegInit(false.B)
  val clockSelect = bankRam(3)

  when (io.memEnable && io.memWrite && io.selectRom) {
    switch (io.memAddress(14, 13)) {
      is (0.U) { ramEnable := io.memDataWrite === "b00001010".U }
      is (1.U) {
        when (!io.memAddress(12)) {
          bankRomLo := io.memDataWrite
        } .otherwise {
          bankRomHi := io.memDataWrite(0)
        }
      }
      is (2.U) { bankRam := io.memDataWrite(3, 0) }
    }
  }

  io.rumble := io.hasRumble && bankRam(3)

  io.bankRom1 := 0.U
  io.bankRom2 := Cat(bankRomHi, bankRomLo)
  io.bankRam := bankRam

  // If RAM is disabled, just read 0xFF
  io.memDataRead := 0xFF.U
  io.ramReadMbc := !ramEnable

  // N10 mapper save-state: ramEnable[0], bankRomLo[8:1], bankRomHi[9], bankRam[13:10],
  // regClockLatch[14]. Load (one-shot, under freeze) is connected last, so it wins.
  io.ssOut := Cat(0.U(17.W), regClockLatch, bankRam, bankRomHi, bankRomLo, ramEnable)
  when (io.ssLoad) {
    ramEnable := io.ssIn(0)
    bankRomLo := io.ssIn(8, 1)
    bankRomHi := io.ssIn(9)
    bankRam := io.ssIn(13, 10)
    regClockLatch := io.ssIn(14)
  }
}
