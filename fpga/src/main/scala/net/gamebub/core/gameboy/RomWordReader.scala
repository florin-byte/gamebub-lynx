package net.gamebub.core.gameboy

import chisel3._
import chisel3.util._

/** GB-150: ROM words for one core, between its emulated cartridge and the SDRAM.
  *
  * The cartridge reads ROM a byte at a time, each as a 32-bit SDRAM word.
  * With two cores on one SDRAM a read that waits for the other core's read
  * stops both cores. This module keeps the last `slots` words (`cache`) and,
  * in idle time, fetches the word after the one just used (`prefetch`), so
  * straight-line code finds its next word already there.
  *
  * It changes how long a read takes, never what it returns: ROM does not
  * change while a core runs, and `flush` (core reset) forgets the kept words;
  * a read already under way still answers its request, but is not kept.
  * With `cache` low it is a wire: the request starts the SDRAM read in the
  * same cycle and the answer is the SDRAM's.
  *
  * Request: `reqValid` and `reqAddress` are held until a cycle with
  * `respValid`. Memory: `memEnable` and `memAddress` are held until `memDone`.
  */
class RomWordReader(addressWidth: Int = 23, slots: Int = 4) extends Module {
  require(isPow2(slots))
  val io = IO(new Bundle {
    val cache = Input(Bool())
    val prefetch = Input(Bool())
    val flush = Input(Bool())

    val reqValid = Input(Bool())
    val reqAddress = Input(UInt(addressWidth.W))
    val respValid = Output(Bool())
    val respData = Output(UInt(32.W))

    val memEnable = Output(Bool())
    val memAddress = Output(UInt(addressWidth.W))
    val memDone = Input(Bool())
    val memData = Input(UInt(32.W))

    /** For counters: the request is answered from a kept word; a memory read starts. */
    val hit = Output(Bool())
    val startDemand = Output(Bool())
    val startPrefetch = Output(Bool())
  })

  val slotValid = RegInit(VecInit(Seq.fill(slots)(false.B)))
  val slotAddress = Reg(Vec(slots, UInt(addressWidth.W)))
  val slotData = Reg(Vec(slots, UInt(32.W)))
  val fill = RegInit(0.U(log2Ceil(slots).W))

  val inFlight = RegInit(false.B)
  val flightAddress = Reg(UInt(addressWidth.W))
  val wantPrefetch = RegInit(false.B)
  val prefetchAddress = Reg(UInt(addressWidth.W))

  def kept(address: UInt): Vec[Bool] =
    VecInit((0 until slots).map(i => slotValid(i) && slotAddress(i) === address))

  val reqKept = kept(io.reqAddress)
  val hit = io.cache && io.reqValid && reqKept.asUInt.orR
  val miss = io.reqValid && !hit

  val startDemand = miss && !inFlight
  val startPrefetch = io.cache && io.prefetch && wantPrefetch && !inFlight && !miss &&
    !kept(prefetchAddress).asUInt.orR
  val start = startDemand || startPrefetch

  io.memEnable := inFlight || start
  io.memAddress := Mux(inFlight, flightAddress, Mux(startDemand, io.reqAddress, prefetchAddress))
  val done = io.memEnable && io.memDone

  when (start && !io.memDone) {
    inFlight := true.B
    flightAddress := io.memAddress
  }
  when (startPrefetch) { wantPrefetch := false.B }
  when (done) {
    inFlight := false.B
    when (io.cache && !io.flush) {
      slotValid(fill) := true.B
      slotAddress(fill) := io.memAddress
      slotData(fill) := io.memData
      fill := fill + 1.U
    }
  }

  // The read that just finished answers the request when it is the word asked for,
  // whether it was started for it or as a prefetch.
  val fromMemory = done && miss && io.memAddress === io.reqAddress
  io.respValid := hit || fromMemory
  io.respData := Mux(hit, Mux1H(reqKept, slotData), io.memData)
  when (io.respValid && io.cache && io.prefetch) {
    wantPrefetch := true.B
    prefetchAddress := io.reqAddress + 1.U
  }

  when (io.flush || !io.cache) {
    slotValid.foreach(_ := false.B)
    wantPrefetch := false.B
  }

  io.hit := hit
  io.startDemand := startDemand
  io.startPrefetch := startPrefetch
}
