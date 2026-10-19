package gameboy.cart.emu

import chisel3._
import chisel3.util._

/** MBC1 (+ MBC1M multicart) */
class Mbc1 extends Module {
  val io = IO(new MbcIo {
    val multicart = Input(Bool())
  })

  val ramEnable = RegInit(false.B)
  val bank1 = RegInit(1.U(5.W))
  val bank2 = RegInit(0.U(2.W))
  val mode = RegInit(false.B)

  when (io.memEnable && io.memWrite && io.selectRom) {
    switch (io.memAddress(14, 13)) {
      is (0.U) { ramEnable := io.memDataWrite(3, 0) === "b1010".U }
      is (1.U) {
        val bank = io.memDataWrite(4, 0)
        bank1 := Mux(bank === 0.U, 1.U, bank)
      }
      is (2.U) { bank2 := io.memDataWrite(1, 0) }
      is (3.U) { mode := io.memDataWrite(0) }
    }
  }

  when (io.multicart) {
    // MBC1M: 4-bit bank1, bank2 = the 256 KiB game (bits 4-5). No banked RAM.
    io.bankRom1 := Mux(mode, (bank2 << 4).asUInt, 0.U)
    io.bankRom2 := Cat(bank2, bank1(3, 0))
    io.bankRam := 0.U
  } .otherwise {
    // Regular MBC1 (as before MBC1M, including F-07).
    io.bankRom1 := Mux(mode, (bank2 << 5).asUInt, 0.U)
    io.bankRom2 := Cat(bank2, bank1)
    io.bankRam := Mux(mode, bank2, 0.U)
  }

  // If RAM is disabled, just read 0xFF
  io.memDataRead := 0xFF.U
  io.ramReadMbc := !ramEnable

  // N10 save-state: pack {bank1[8:4], bank2[3:2], mode[1], ramEnable[0]}.
  io.ssOut := Cat(0.U(23.W), bank1, bank2, mode, ramEnable)
  when (io.ssLoad) {
    bank1 := io.ssIn(8, 4)
    bank2 := io.ssIn(3, 2)
    mode := io.ssIn(1).asBool
    ramEnable := io.ssIn(0).asBool
  }
}
