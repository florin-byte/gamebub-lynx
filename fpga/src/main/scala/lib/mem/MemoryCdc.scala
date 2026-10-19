package lib.mem

import chisel3._
import chisel3.util._

/**
 * Utility to allow a target memory interface running in a
 * fast clock domain to be accessed by a slower clock domain.
 * e.g. a system interfacing with a faster SDRAM controller.
 *
 * Must be instantiated with the faster clock, and the fast clock must be
 * an integer multiple of the slow clock.
 */
class MemoryCdc(addressWidth: Int, dataWidth: Int) extends Module {
  val io = IO(new Bundle {
    val slowClock = Input(Clock())
    val initiator = new MemoryInterface(addressWidth, dataWidth)
    val target = Flipped(new MemoryInterface(addressWidth, dataWidth))
  })
  // Generate a one-cycle pulse in the fast clock domain after
  // each rising edge of the slow (so we know when we can stop holding
  // a signal).
  val regSlowWave = withClock(io.slowClock) {
    val reg = RegInit(false.B)
    reg := !reg
    reg
  }
  val slowEdge = regSlowWave =/= RegNext(regSlowWave)

  // Hold the done signal until it's been seen by one slow clock edge.
  val regDone = RegInit(false.B)
  when (io.target.done) {
    regDone := true.B
    // TODO should we register dataRead too?
  } .elsewhen (slowEdge) {
    // The slow domain has seen the "done" signal.
    regDone := false.B
  }
  val slowDone = regDone || io.target.done

  io.target.enable := io.initiator.enable && !slowDone
  io.target.write := io.initiator.write && !slowDone
  io.target.address := io.initiator.address
  io.target.dataWrite := io.initiator.dataWrite
  io.target.writeStrobe := io.initiator.writeStrobe
  io.initiator.dataRead := io.target.dataRead
  io.initiator.done := slowDone
}
