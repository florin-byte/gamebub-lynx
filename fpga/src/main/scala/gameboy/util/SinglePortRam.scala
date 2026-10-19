package gameboy.util

import chisel3._
import chisel3.util._

/**
 * N10 save-state access port: a second read/write port on an internal memory,
 * driven by the save-state walker WHILE THE CORE IS FROZEN. Artix-7 BRAM is
 * true dual-port, so this port is essentially free in hardware. Directions are
 * from the memory's perspective (inputs driven by the walker).
 */
class SaveStatePort(size: Int) extends Bundle {
  val address   = Input(UInt(log2Ceil(size).W))
  val enable    = Input(Bool())
  val write     = Input(Bool())
  val dataWrite = Input(UInt(8.W))
  /** Synchronous read: valid the cycle after `enable`. */
  val dataRead  = Output(UInt(8.W))
}

/** Synchronous read/write single port (one read *or* one write) RAM.
 *  Plus an independent N10 save-state read/write port (true dual-port BRAM). */
class SinglePortRam(size: Int) extends Module {
  val io = IO(new Bundle {
    /** Address select */
    val address = Input(UInt(log2Ceil(size).W))
    /** Whether there's an access attempt */
    val enabled = Input(Bool())
    /** Whether the access is a write */
    val write = Input(Bool())
    /** The data being written */
    val dataWrite = Input(UInt(8.W))
    /** The data being read */
    val dataRead = Output(UInt(8.W))
    /** N10: save-state port (used only under freeze). */
    val ss = new SaveStatePort(size)
  })

  val ram = SRAM(size, UInt(8.W), numReadPorts = 0, numWritePorts = 0, numReadwritePorts = 2)
  val port = ram.readwritePorts(0)

  port.enable := io.enabled
  port.address := io.address
  port.isWrite := io.write
  port.writeData := io.dataWrite
  io.dataRead := port.readData

  // N10 save-state port (independent — core is frozen when this is used).
  val ssPort = ram.readwritePorts(1)
  ssPort.enable := io.ss.enable
  ssPort.address := io.ss.address
  ssPort.isWrite := io.ss.write
  ssPort.writeData := io.ss.dataWrite
  io.ss.dataRead := ssPort.readData
}
