package net.gamebub.core.boot

import chisel3._

class picorv32(
) extends ExtModule(Map(
  "BARREL_SHIFTER" -> 1,
  "ENABLE_MUL" -> 1,
  "ENABLE_DIV" -> 1,
  "COMPRESSED_ISA" -> 1,
)) {
  val io = FlatIO(new Bundle {
    val clk = Input(Clock())
    val resetn = Input(Reset())
    val trap = Output(Bool())

    val mem_valid = Output(Bool())
    val mem_instr = Output(Bool())
    val mem_ready = Input(Bool())

    val mem_addr = Output(UInt(32.W))
    val mem_wdata = Output(UInt(32.W))
    val mem_wstrb = Output(UInt(4.W))
    val mem_rdata = Input(UInt(32.W))

    val mem_la_read = Output(Bool())
    val mem_la_write = Output(Bool())
    val mem_la_addr = Output(UInt(32.W))
    val mem_la_wdata = Output(UInt(32.W))
    val mem_la_wstrb = Output(UInt(4.W))

    // Excludes PCPI

    // Excludes IRQ

    // Excludes Trace
  })
}
