package gameboy

import chisel3._

class BootRomAccess extends Bundle {
  val read = Output(Bool())
  val address = Output(UInt(12.W))
  val data = Input(UInt(8.W))
}

class BootRom(config: Gameboy.Configuration) extends Module {
  val io = IO(new Bundle {
    val isCgb = Input(Bool())

    val address = Input(UInt(16.W))
    val valid = Output(Bool())
    val dataRead = Output(UInt(8.W))

    /** True if the bootrom is mapped. */
    val mapped = Input(Bool())

    /** Access to externally stored bootrom */
    val access = new BootRomAccess
  })

  io.valid := false.B
  io.dataRead := io.access.data
  io.access.address := io.address
  io.access.read := io.valid
  when (io.mapped) {
    when (io.address < 0x100.U) {
      io.valid := true.B
    }

    when (io.isCgb) {
      when (io.address >= 0x200.U && io.address < 0x900.U) {
        io.valid := true.B
      }
    }
  }
}