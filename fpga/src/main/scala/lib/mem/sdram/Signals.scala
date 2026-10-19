package lib.mem.sdram

import chisel3._

class Signals(
  /** Physical address width */
  addressWidth: Int = 13,
  /** Physical data width (word size) */
  dataWidth: Int = 16,
  /** Bank address width */
  bankWidth: Int = 2,
) extends Bundle {
  // clk is not included.

  /** Clock Enable */
  val cke = Output(Bool())
  /** Chip Select (active-low) */
  val cs = Output(Bool())
  /** Row Address Strobe (active-low) */
  val ras = Output(Bool())
  /** Column Address Strobe (active-low) */
  val cas = Output(Bool())
  /** Write Enable (active-low) */
  val we = Output(Bool())
  /** Data Mask (byte) */
  val dqm = Output(UInt((dataWidth / 8).W))
  /** Bank Select */
  val bank = Output(UInt(bankWidth.W))
  /** Address */
  val address = Output(UInt(addressWidth.W))
  /** Data Input */
  val dataIn = Input(UInt(dataWidth.W))
  /** Data Output */
  val dataOut = Output(UInt(dataWidth.W))
  /** Data Direction: true for output. */
  val dataDir = Output(Bool())
}
