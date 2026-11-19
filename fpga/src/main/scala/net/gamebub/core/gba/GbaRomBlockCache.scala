package net.gamebub.core.gba

import chisel3._
import chisel3.util._
import lib.mem.PipelineMemoryInterface
import lib.mem.cache.DirectReadCache

/** GB-159: ROM words for one GBA core, between its emulated cartridge and the SDRAM arbiter.
  *
  * With two cores on one SDRAM a ROM read that waits for the other core's read stops both
  * cores. A direct-mapped cache of `entries` 32-bit words in block RAM answers the words of
  * a loop without going to the SDRAM. A cache of four words with read-ahead (as for the
  * Game Boy cores) was tried first and saved only 3% of the stalls: a GBA core reads ROM
  * almost all the time, so the SDRAM has no idle time for reading ahead.
  *
  * It changes how long a read takes, never what it returns: the ROM does not change while a
  * core runs, and `flush` (core reset, when the host may load another ROM) empties it.
  *
  * Addresses are byte addresses of 32-bit words (low two bits zero), as on the SDRAM port.
  */
class GbaRomBlockCache(addressWidth: Int, dataWidth: Int, entries: Int = 2048) extends Module {
  val io = IO(new Bundle {
    val flush = Input(Bool())
    val in = new PipelineMemoryInterface(addressWidth, dataWidth)
    val out = Flipped(new PipelineMemoryInterface(addressWidth, dataWidth))
  })
  val cache = withReset(reset.asBool || io.flush) {
    Module(new DirectReadCache(addressWidth - 2, dataWidth, entries))
  }
  cache.io.in <> io.in
  cache.io.in.address := io.in.address >> 2
  io.out <> cache.io.out
  io.out.address := cache.io.out.address << 2
}
