package gameboy.cart.emu

import chisel3._

/** No mapper. 32 KiB ROM only. */
class MbcNone extends Module {
  val io = IO(new MbcIo())
  io.memDataRead := DontCare
  io.bankRom1 := 0.U
  io.bankRom2 := 1.U
  io.bankRam := 0.U
  io.ramReadMbc := false.B
  io.ssOut := 0.U  // N10: no mapper state
}
