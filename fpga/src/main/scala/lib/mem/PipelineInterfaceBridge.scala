package lib.mem

import chisel3._
import chisel3.util._
import lib.mem.PipelineInterfaceBridge.State

object PipelineInterfaceBridge {
  private object State extends ChiselEnum {
    val idle = Value
    val waiting = Value
  }
}

class PipelineInterfaceBridge(addressWidth: Int, dataWidth: Int) extends Module {
  val io = IO(new Bundle {
    val source = new MemoryInterface(addressWidth, dataWidth)
    val dest = Flipped(new PipelineMemoryInterface(addressWidth, dataWidth))
  })

  io.source.dataRead := io.dest.dataRead
  io.source.done := false.B
  io.dest.enable := false.B
  io.dest.address := io.source.address
  io.dest.isWrite := io.source.write
  io.dest.writeStrobe := io.source.writeStrobe
  io.dest.dataWrite := io.source.dataWrite

  private val regState = RegInit(State.idle)

  switch (regState) {
    is (State.idle) {
      when (io.source.enable) {
        io.dest.enable := true.B

        when (io.dest.ready) {
          regState := State.waiting
        }
      }
    }
    is (State.waiting) {
      when (io.dest.ready) {
        io.source.done := true.B
        regState := State.idle
      }
    }
  }
}