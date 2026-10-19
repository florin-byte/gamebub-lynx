package gba.mem

import chisel3._
import chisel3.util._
import lib.log.Logger
import lib.mem.MemoryInterface

/// Bridge between EWRAM interface and an external (to GBA) memory with MemoryInterface
///
/// TODO: rework to improve speed! takes too many cycles, too many things are registered
class EwramController extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())

    /// Internal memory target
    val target = new TargetInterface(16.W)
    /// Number of wait states (nominally 2, minimum 1)
    val numWaits = Input(UInt(4.W))

    /// External memory interface, assumed synchronous
    val mem = Flipped(new MemoryInterface(addressWidth = 17, dataWidth = 16))
    /// Whether the previous memory request has not yet completed by the time the GBA needs it to.
    val stall = Output(Bool())
  })
  val logger = Logger("ewram", enable = io.enable)
  val widthBytes = 2
  val numWords = 256 * 1024 / widthBytes
  val addrShift = log2Ceil(widthBytes)

  val busy = RegInit(false.B)
  val waitCounter = Reg(UInt(4.W))
  val queuedIsWrite = RegInit(false.B)
  val queuedAddress = Reg(UInt(log2Ceil(numWords).W))
  val queuedWriteMask = Reg(UInt(widthBytes.W))
  val readDataLatch = Reg(UInt(16.W))
  /// Whether the external access has been submitted.
  val externalRequested = Reg(Bool())
  /// Whether the external access has completed.
  val externalComplete = Reg(Bool())

  io.target.done := false.B
  io.target.dataRead := readDataLatch
  io.mem.address := queuedAddress
  io.mem.enable := busy && !externalComplete && (io.enable || externalRequested)
  io.mem.write := queuedIsWrite
  io.mem.dataWrite := io.target.dataWrite
  io.mem.writeStrobe := queuedWriteMask
  io.stall := busy && waitCounter === 0.U && !externalComplete

  // Latch data upon external memory completion
  when (busy && io.mem.done && externalRequested && !externalComplete) {
    externalComplete := true.B
    readDataLatch := io.mem.dataRead
    io.target.dataRead := io.mem.dataRead
  }
  io.stall := busy && (waitCounter === 0.U) && !(externalComplete || io.mem.done)

  // Complete access
  when (io.enable && busy) {
    waitCounter := waitCounter - 1.U
    externalRequested := true.B

    when (waitCounter === 0.U) {
      when (!(externalComplete || io.mem.done)) {
        logger.error("Memory wait expired without access being complete!")
      }

      busy := false.B
      io.target.done := true.B
    }
  }

  // Accept a new access.
  when (io.enable && io.target.request && (!busy || waitCounter === 0.U)) {
    busy := true.B
    externalRequested := false.B
    externalComplete := false.B
    waitCounter := io.numWaits
    queuedAddress := io.target.address >> addrShift
    queuedIsWrite := io.target.write
    queuedWriteMask := io.target.mask
  }
}
