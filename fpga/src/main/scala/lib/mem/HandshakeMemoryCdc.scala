package lib.mem

import chisel3._
import chisel3.util._
import xilinx.XpmCdcHandshake

/**
 * Utility to allow a target memory interface in current clock domain
 * to be accessed by a memory interface in another clock domain.
 * The clock domains can be entirely asynchronous.
 *
 * Note: has a very high latency, and only supports writes.
 */
class HandshakeMemoryCdc(addressWidth: Int, dataWidth: Int) extends Module {
  val io = IO(new Bundle {
    val sourceClock = Input(Clock())
    val sourceReset = Input(Reset())
    val initiator = new MemoryInterface(addressWidth, dataWidth)
    val target = Flipped(new MemoryInterface(addressWidth, dataWidth))
  })

  class Request extends Bundle {
    val isWrite = Bool()
    val address = UInt(addressWidth.W)
    val dataWrite = UInt(dataWidth.W)
    val writeStrobe = Input(UInt((dataWidth / 8).W))
  }

  val handshake = Module(new XpmCdcHandshake(new Request, externalHandshake = true))
  handshake.io.sourceClock := io.sourceClock
  handshake.io.sourceInput.isWrite := io.initiator.write
  handshake.io.sourceInput.address := io.initiator.address
  handshake.io.sourceInput.dataWrite := io.initiator.dataWrite
  handshake.io.sourceInput.writeStrobe := io.initiator.writeStrobe
  handshake.io.sourceSend := false.B

  io.initiator.done := false.B
  io.initiator.dataRead := 0.U // TODO

  withClockAndReset (io.sourceClock, io.sourceReset) {
    val regSourceBusy = RegInit(false.B)
    val regSent = RegInit(false.B)

    when (regSourceBusy) {
      when (!handshake.io.sourceReceived) {
        handshake.io.sourceSend := true.B
        regSent := true.B
      } .elsewhen (regSent) {
        regSourceBusy := false.B
        io.initiator.done := true.B
      }
    } .otherwise {
      when (io.initiator.enable) {
        regSourceBusy := true.B
        regSent := false.B
      }
    }
  }

  private object State extends ChiselEnum {
    val idle = Value
    val request = Value
    val ack = Value
  }
  private val regState = RegInit(State.idle)

  io.target.address := handshake.io.destOutput.address
  io.target.write := handshake.io.destOutput.isWrite
  io.target.dataWrite := handshake.io.destOutput.dataWrite
  io.target.writeStrobe := handshake.io.destOutput.writeStrobe

  io.target.enable := false.B
  handshake.io.destAck := false.B

  switch (regState) {
    is (State.idle) {
      when (handshake.io.destRequest) {
        regState := State.request
        io.target.enable := true.B
      }
    }
    is (State.request) {
      io.target.enable := true.B

      when (io.target.done) {
        regState := State.ack
        handshake.io.destAck := true.B
      }
    }
    is (State.ack) {
      handshake.io.destAck := true.B
      when (!handshake.io.destRequest) {
        regState := State.idle
      }
    }
  }
}
