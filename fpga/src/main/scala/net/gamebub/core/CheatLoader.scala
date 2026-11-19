package net.gamebub.core

import chisel3._
import chisel3.util._
import lib.mem.MemoryInterface

/**
 * Feeds a MiSTer cheat engine (`CODES`, `cheatcodes.sv`) with the codes the firmware writes to its window from the
 * game's `.cht` when a cheat is switched in the menu (after clearing the engine). Every 16 bytes are one code in
 * MiSTer's layout (SMS.sv / NES.sv code loaders): four little-endian 32-bit words, flags (bit 0: compare), CPU
 * address, compare value, replacement value. (The NES core's NesCheatLoader, shared.)
 *
 * CODES must run on the same clock as this module and takes a code at a rising edge of `code(128)`: after a
 * code's fourth word the strobe is high for one cycle, then low for one, before the host's write is answered.
 */
class CheatLoader extends Module {
  import CheatLoader._

  val io = IO(new Bundle {
    val host = new MemoryInterface(addressWidth = AddressWidth, dataWidth = 32)
    /** To CODES: {strobe, flags, address, compare, replacement}. */
    val code = Output(UInt(129.W))
  })

  object State extends ChiselEnum {
    val idle, strobeHigh, strobeLow, done = Value
  }
  val state = RegInit(State.idle)
  val words = Reg(Vec(4, UInt(32.W)))

  switch (state) {
    is (State.idle) {
      when (io.host.enable) {
        val field = io.host.address(3, 2)
        when (io.host.write) {
          words(field) := io.host.dataWrite
        }
        state := Mux(io.host.write && field === 3.U, State.strobeHigh, State.done)
      }
    }
    is (State.strobeHigh) {
      state := State.strobeLow
    }
    is (State.strobeLow) {
      state := State.done
    }
    is (State.done) {
      state := State.idle
    }
  }
  io.host.done := state === State.done
  io.host.dataRead := 0.U
  io.code := Cat(state === State.strobeHigh, words(0), words(1), words(2), words(3))
}

object CheatLoader {
  /** CODES' capacity (`MAX_CODES`); a longer file loads, the codes past it are ignored. */
  val MaxCodes = 32
  val FileBytes = 16 * MaxCodes
  val AddressWidth = 16
}
