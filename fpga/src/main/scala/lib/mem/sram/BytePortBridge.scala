package lib.mem.sram

import chisel3._
import chisel3.util._
import lib.mem.MemoryInterface

// From Jeremy O'Brien's SNES port for the Game Bub (HandheldSnes.scala, 2026-09-13), unchanged, as a library
// file so that cores built without the SNES glue (the NES port) compile alone. The SNES glue keeps its own copy.
/**
 * Bridge from a level-sensitive async-SRAM-style 8-bit port (as driven by the
 * core: CE_N / OE_N / WE_N / address / data) to a 16-bit Game Bub
 * [[MemoryInterface]] with byte strobes.
 *
 * A read is issued when the port becomes active or its address changes; a
 * write is issued when the port becomes write-active or its address/data
 * changes. Requests go through a small in-order queue, so writes are
 * posted: `pendingRead` is high while a read is outstanding, `pending`
 * additionally while the queue is nearly full (the core must be stalled
 * then, since it can issue one more request before it notices). Reads
 * ordered behind posted writes see their data.
 *
 * Latency matters here: the core only has a few cycles between asserting a
 * read strobe and latching the data (see the stall control in
 * [[HandheldSnes]]), so a request is put on the memory interface in the
 * same cycle it is detected (when idle) and the read data is forwarded to
 * `dataRead` in the cycle it arrives, with `pendingRead` dropping in that
 * same cycle. With the [[AsyncSramController]] (done 2 cycles after the
 * request) read data is valid 2 cycles after the strobe.
 *
 * With `bufferWords > 0`, a 2-way cache of that many 16-bit words
 * (distributed RAM, read asynchronously, with a byte-valid pair per word
 * in registers) sits in front of the memory: a read of a byte it holds is
 * answered in the strobe cycle (no `pendingRead`); a write updates or
 * allocates its byte there (write-through, write-allocate: a
 * decompressor reading back what it just wrote hits) before going to the
 * memory; a completed read installs its word; and within a sequential
 * run the next word is prefetched when the queue is idle, so a byte
 * stream misses at most once per word. `invalidate` drops the contents
 * (needed when something else writes the memory, e.g. the MCU loading a
 * save while the core is in reset).
 */
class BytePortBridge(addressWidth: Int, wordBase: Int, queueDepth: Int = 4, bufferWords: Int = 0) extends Module {
  val io = IO(new Bundle {
    val address = Input(UInt(addressWidth.W))
    val dataWrite = Input(UInt(8.W))
    val dataRead = Output(UInt(8.W))
    val ceN = Input(Bool())
    val oeN = Input(Bool())
    val weN = Input(Bool())
    /** Optional: only sample the port when high (edge-based ports). */
    val sample = Input(Bool())

    val mem = Flipped(new MemoryInterface(addressWidth = 18, dataWidth = 16))
    /** A read is outstanding, or the queue is nearly full: the core must not continue. */
    val pending = Output(Bool())
    /** A read is outstanding. */
    val pendingRead = Output(Bool())
    /** The queue is nearly full: the core must not continue, whatever the stall policy. */
    val full = Output(Bool())
    /** Pulses: a read / a write was detected on the port (statistics). */
    val readIssued = Output(Bool())
    val writeIssued = Output(Bool())
    /** Pulses: a read was answered by the word buffer (statistics). */
    val bufferHit = Output(Bool())
    /** Pulses: a read missed although its entry held another (valid) word — a conflict miss (statistics). */
    val bufferConflict = Output(Bool())
    /** What the memory is busy with, if anything (statistics). */
    val busyWrite = Output(Bool())
    val busyPrefetch = Output(Bool())
    val busyDemand = Output(Bool())
    /** Drop the word buffer's contents. */
    val invalidate = Input(Bool())
    /** Debug: never answer from or fill the word buffer (all misses). */
    val bufferOff = Input(Bool())
    /** Debug: use way 0 only (a direct-mapped cache of half the size). */
    val oneWay = Input(Bool())
  })

  class Request extends Bundle {
    val isWrite = Bool()
    /** A background read to fill the word buffer: nobody waits for it. */
    val isPrefetch = Bool()
    /** A demand read continuing a sequential run (worth prefetching after). */
    val isSequential = Bool()
    /** The cache way a read's word goes to when it completes. */
    val way = UInt(1.W)
    val address = UInt(addressWidth.W)
    val data = UInt(8.W)
  }

  val readActive = !io.ceN && !io.oeN
  val writeActive = !io.ceN && !io.weN
  val regReadActive = RegNext(readActive, false.B)
  val regWriteActive = RegNext(writeActive, false.B)
  val regAddress = RegNext(io.address)
  val regDataWrite = RegNext(io.dataWrite)
  val addressChanged = regAddress =/= io.address
  val dataChanged = regDataWrite =/= io.dataWrite

  val readRequest = io.sample && readActive && (!regReadActive || addressChanged)
  val writeRequest = io.sample && writeActive && (!regWriteActive || addressChanged || dataChanged)

  //////////////////////////////////
  // Word buffer
  //////////////////////////////////
  require(bufferWords == 0 || isPow2(bufferWords))
  val wordBits = addressWidth - 1
  // Two ways with an LRU bit per set (92 % of the misses of a 4 KiB
  // direct-mapped cache landed on occupied entries with SA-1 games).
  val bufWays = if (bufferWords == 0) 1 else 2
  val bufSets = (bufferWords / bufWays).max(1)
  val indexBits = log2Ceil(bufSets.max(2))
  val tagBits = (wordBits - indexBits).max(1)
  /** Byte-valid bits per way and set (registers, so that `invalidate` clears them at once). */
  val bufValid = RegInit(VecInit(Seq.fill(bufSets)(VecInit(Seq.fill(bufWays)(0.U(2.W))))))
  /** The way used most recently per set (the other one is the victim). */
  val bufLru = RegInit(VecInit(Seq.fill(bufSets)(false.B)))
  val bufTag = Seq.fill(bufWays)(Mem(bufSets, UInt(tagBits.W)))
  val bufData = Seq.fill(bufWays)(Mem(bufSets, Vec(2, UInt(8.W))))
  private def indexOf(word: UInt): UInt = word(indexBits - 1, 0)
  private def tagOf(word: UInt): UInt = word(wordBits - 1, indexBits)
  // One asynchronous read port per memory (the address on the port): more
  // would turn the distributed RAM into mux trees.
  val portWord = io.address(addressWidth - 1, 1)
  val portSet = indexOf(portWord)
  /** Per way: the entry holds the word on the port (its tag, and at least one valid byte). */
  val portTagMatchWay = VecInit((0 until bufWays).map(w =>
    if (bufferWords == 0) false.B else bufValid(portSet)(w).orR && bufTag(w).read(portSet) === tagOf(portWord)))
  val portTagMatch = portTagMatchWay.asUInt.orR
  val portDataWay = (0 until bufWays).map(w => if (bufferWords == 0) VecInit(Seq.fill(2)(0.U(8.W))) else bufData(w).read(portSet))
  /** The way that holds (or would hold) the word on the port: the way holding it, else the LRU victim. */
  val portWay = Mux(portTagMatch, OHToUInt(portTagMatchWay), Mux(bufLru(portSet) || io.oneWay, 0.U, 1.U))
  def bufLookup(address: UInt): (Bool, UInt) = if (bufferWords == 0) (false.B, 0.U) else {
    val hitWay = VecInit((0 until bufWays).map(w => bufValid(portSet)(w)(address(0)) && portTagMatchWay(w)))
    (hitWay.asUInt.orR, Mux1H(hitWay, portDataWay.map(_(address(0)))))
  }
  /** The word of the last demand read: a request for the word after it continues a run. */
  val bufLastWord = RegInit(0.U(wordBits.W))
  val sequential = portWord === bufLastWord + 1.U
  // A single write port (an install and a core write never happen in the
  // same cycle): a second one would keep Vivado from inferring distributed
  // RAM and turn the cache into flip-flops and mux trees.
  val bufWrite = WireDefault(false.B)
  val bufWriteWay = Wire(UInt(1.W))
  val bufWriteIndex = Wire(UInt(indexBits.W))
  val bufWriteTag = Wire(UInt(tagBits.W))
  val bufWriteData = Wire(Vec(2, UInt(8.W)))
  val bufWriteMask = Wire(Vec(2, Bool()))
  bufWriteWay := DontCare; bufWriteIndex := DontCare; bufWriteTag := DontCare; bufWriteData := DontCare; bufWriteMask := DontCare
  if (bufferWords > 0) {
    for (w <- 0 until bufWays) {
      when (bufWrite && bufWriteWay === w.U) {
        bufTag(w).write(bufWriteIndex, bufWriteTag)
        bufData(w).write(bufWriteIndex, bufWriteData, bufWriteMask)
      }
    }
  }
  /**
   * Install a whole word (from a memory read). It goes to the way that
   * already holds the word if there is one (a second tag read port, for
   * the completing address: a prefetch and a demand read of the same word
   * can be in flight together, and the LRU may have moved between their
   * requests), else to `way`, chosen when the read was requested.
   */
  def bufInstall(word: UInt, way: UInt, data: UInt): Unit = if (bufferWords > 0) {
    val set = indexOf(word)
    val heldWay = VecInit((0 until bufWays).map(w => bufValid(set)(w).orR && bufTag(w).read(set) === tagOf(word)))
    bufWrite := true.B
    bufWriteWay := Mux(heldWay.asUInt.orR, OHToUInt(heldWay), way)
    bufWriteIndex := indexOf(word)
    bufWriteTag := tagOf(word)
    bufWriteData := data.asTypeOf(Vec(2, UInt(8.W)))
    bufWriteMask := VecInit(true.B, true.B)
    bufValid(set)(bufWriteWay) := 3.U
    bufLru(set) := bufWriteWay(0)
  }
  /** A byte written by the core: update its word if present, else allocate it (that byte only). */
  def bufUpdate(address: UInt, data: UInt): Unit = if (bufferWords > 0) {
    val word = address(addressWidth - 1, 1)
    val index = indexOf(word)
    bufWrite := true.B
    bufWriteWay := portWay
    bufWriteIndex := index
    bufWriteTag := tagOf(word)
    bufWriteData := VecInit(Seq.fill(2)(data))
    bufWriteMask := VecInit(!address(0), address(0))
    when (portTagMatch) {
      bufValid(index)(portWay) := bufValid(index)(portWay) | Mux(address(0), 2.U, 1.U)
    } .otherwise {
      bufValid(index)(portWay) := Mux(address(0), 2.U, 1.U)
    }
    bufLru(index) := portWay(0)
  }
  val (bufHit0, bufByte) = bufLookup(io.address)
  val bufHit = bufHit0 && !io.bufferOff
  val readHit = readRequest && bufHit
  io.bufferHit := readHit
  io.bufferConflict := readRequest && !bufHit && (if (bufferWords == 0) false.B else bufValid(portSet).asUInt.orR && !portTagMatch)
  when (readHit) { bufLru(portSet) := portWay(0) }
  when (io.invalidate) {
    bufValid.foreach(_.foreach(_ := 0.U))
  }
  // A read that was in flight when a write came in may return data older
  // than the write: don't install anything until the port drains.
  val bufDirty = RegInit(false.B)

  // Requests wait here; `flow` lets one issue in the cycle it arrives.
  val queue = Module(new Queue(new Request, queueDepth, flow = true))
  val prefetchRequest = WireDefault(false.B)
  val prefetchAddress = Wire(UInt(addressWidth.W))
  prefetchAddress := DontCare
  // A prefetch is only ever raised in a cycle without a demand enqueue (a
  // miss's done cycle without a new request, or a hit).
  queue.io.enq.valid := (readRequest && !readHit) || writeRequest || prefetchRequest
  queue.io.enq.bits.isWrite := writeRequest
  queue.io.enq.bits.isPrefetch := prefetchRequest
  queue.io.enq.bits.isSequential := sequential
  // A prefetch is for the next word: its way is chosen by the LRU of that set (an approximation).
  queue.io.enq.bits.way := Mux(prefetchRequest, Mux(bufLru(indexOf(prefetchAddress(addressWidth - 1, 1))) || io.oneWay, 0.U, 1.U), portWay)
  queue.io.enq.bits.address := Mux(prefetchRequest, prefetchAddress, io.address)
  queue.io.enq.bits.data := io.dataWrite
  assert(!(prefetchRequest && ((readRequest && !readHit) || writeRequest)), "prefetch raised with a demand request")
  assert(!(queue.io.enq.valid && !queue.io.enq.ready), "BytePortBridge queue overflow")
  when (writeRequest && !io.bufferOff) {
    bufUpdate(io.address, io.dataWrite)
  }

  /** The request at the memory. */
  val busy = RegInit(false.B)
  val busyIsWrite = Reg(Bool())
  val busyIsPrefetch = Reg(Bool())
  val busyIsSequential = Reg(Bool())
  val busyWay = Reg(UInt(1.W))
  val busyAddress = Reg(UInt(addressWidth.W))
  val busyData = Reg(UInt(8.W))
  val regDataRead = RegInit(0.U(8.W))

  val done = busy && io.mem.done
  val doneRead = done && !busyIsWrite && !busyIsPrefetch
  val doneData = io.mem.dataRead.asTypeOf(Vec(2, UInt(8.W)))(busyAddress(0))
  // Demand reads answered by the memory in order; a hit answers a newer
  // request at once, so the data of demand reads still outstanding at that
  // moment (e.g. the GSU's speculative read before a store) must not
  // replace the hit's data when they complete later.
  val demandReads = RegInit(0.U(log2Ceil(queueDepth + 2).W))
  val staleReads = RegInit(0.U(log2Ceil(queueDepth + 2).W))
  val doneStale = doneRead && (readHit || staleReads =/= 0.U)
  // The data follows the address on the port whenever the buffer has it,
  // not only in the request cycle: two consumers share the port (the
  // S-CPU and the SA-1 on BW-RAM), and one's hit between the other's
  // strobe and sample must not replace what the other samples — its own
  // read is installed when it completes, and its address is on the port
  // when it samples.
  io.dataRead := Mux(bufHit, bufByte, Mux(doneRead && !doneStale, doneData, regDataRead))
  when (readHit) {
    regDataRead := bufByte
    // Keep a sequential stream ahead: fetch the following word too — but
    // only when this access continues a run (it reads the word after the
    // previous demand read): a coprocessor reading all over its RAM would
    // otherwise spend half the SRAM's time on prefetches it never uses.
    if (bufferWords > 0) {
      val nextWord = portWord + 1.U
      when (queue.io.count === 0.U && sequential) {
        prefetchRequest := true.B
        prefetchAddress := Cat(nextWord, 0.U(1.W))
      }
    }
  }
  // (A write arriving in this very cycle makes the data stale too.)
  when (done && !busyIsWrite && !bufDirty && !writeRequest && !io.bufferOff) {
    bufInstall(busyAddress(addressWidth - 1, 1), busyWay, io.mem.dataRead)
    // After a demand read that continues a run, fetch the following word
    // if the port is otherwise idle.
    if (bufferWords > 0) {
      val nextWord = busyAddress(addressWidth - 1, 1) + 1.U
      when (!busyIsPrefetch && busyIsSequential && queue.io.count === 0.U && !readRequest && !writeRequest) {
        prefetchRequest := true.B
        prefetchAddress := Cat(nextWord, 0.U(1.W))
      }
    }
  }

  // Issue the queue head when the memory is free (or completing this cycle).
  // A read may issue in the cycle it arrives (`flow`); a write arriving
  // this cycle waits for the next one. Writes are posted, so the cycle
  // costs nothing, and it keeps the core's write strobe off the
  // combinational path into the SRAM controller's write-enable, which
  // otherwise runs the whole 46 ns cycle from the SA-1's address generator.
  val arrivingWrite = queue.io.count === 0.U && queue.io.deq.bits.isWrite
  val issue = queue.io.deq.valid && (!busy || done) && !arrivingWrite
  queue.io.deq.ready := issue
  val head = queue.io.deq.bits
  val memIsWrite = Mux(busy, busyIsWrite, head.isWrite)
  val memAddress = Mux(busy, busyAddress, head.address)
  val memData = Mux(busy, busyData, head.data)
  // The target only accepts a request while it is idle and then ignores
  // `enable` until `done`, so holding the request from the issue cycle
  // until `done` is safe whether or not it was accepted immediately.
  io.mem.enable := busy || issue
  io.mem.write := memIsWrite
  io.mem.address := (memAddress >> 1).asUInt + wordBase.U
  io.mem.dataWrite := Fill(2, memData)
  io.mem.writeStrobe := Mux(memAddress(0), "b10".U(2.W), "b01".U(2.W))

  when (done) {
    when (doneRead && !doneStale) {
      regDataRead := doneData
    }
    busy := false.B
  }
  val enqDemandRead = readRequest && !readHit
  when (readRequest) { bufLastWord := portWord }
  val demandReadsNext = demandReads + enqDemandRead - doneRead
  val staleReadsNext = Mux(readHit, demandReads - doneRead,
    Mux(doneRead && staleReads =/= 0.U, staleReads - 1.U, staleReads))
  demandReads := demandReadsNext
  staleReads := staleReadsNext
  when (issue) {
    busy := true.B
    busyIsWrite := head.isWrite
    busyIsPrefetch := head.isPrefetch
    busyIsSequential := head.isSequential
    busyWay := head.way
    busyAddress := head.address
    busyData := head.data
  }

  // Demand reads somebody may still be waiting for at the end of this
  // cycle: enqueued (or in flight) and neither completing now nor made
  // stale by a newer hit. Posted writes and prefetches ahead of them in
  // the queue delay them but don't count by themselves: a burst of
  // stores (the SA-1 decompressing into BW-RAM) used to hold this high
  // and stall the coprocessor at every sample instant for nothing.
  val readOutstanding = demandReadsNext =/= staleReadsNext
  val nearlyFull = queue.io.count >= (queueDepth - 1).U
  when (writeRequest && (busy || queue.io.deq.valid)) {
    bufDirty := true.B
  } .elsewhen (!busy && !queue.io.deq.valid) {
    bufDirty := false.B
  }
  io.pendingRead := readOutstanding
  io.full := nearlyFull
  io.readIssued := readRequest
  io.busyWrite := busy && busyIsWrite
  io.busyPrefetch := busy && !busyIsWrite && busyIsPrefetch
  io.busyDemand := busy && !busyIsWrite && !busyIsPrefetch
  io.writeIssued := writeRequest
  io.pending := readOutstanding || nearlyFull
}
