package lib.mem.cache

import chisel3._
import chisel3.util._
import lib.mem.{MemoryInterface, PipelineMemoryInterface}
import lib.mem.cache.DirectReadCache._

object DirectReadCache {
  object State extends ChiselEnum {
    val init, idle, waitCache, waitMem, forwardMem = Value
  }

  class Entry(tagWidth: Int, dataWidth: Int) extends Bundle {
    val valid = Bool()
    val tag = UInt(tagWidth.W)
    val data = UInt(dataWidth.W)
  }
}


/*
 * A simple, direct-mapped, read-only cache.
 *
 * TODO: implement writes: should be passed through, but not cached
 */
class DirectReadCache(addressWidth: Int, dataWidth: Int, numEntries: Int) extends Module {
  val io = IO(new Bundle {
    val in = new PipelineMemoryInterface(addressWidth, dataWidth)
    val out = Flipped(new PipelineMemoryInterface(addressWidth, dataWidth))
  })
  assert(isPow2(numEntries))
  val indexWidth = log2Ceil(numEntries)
  val tagWidth = addressWidth - indexWidth

  val entryType = new Entry(tagWidth, dataWidth)
  val cache = SRAM(numEntries, UInt(entryType.getWidth.W), numReadPorts = 1, numWritePorts = 1, numReadwritePorts = 0)
  val state = RegInit(State.init)
  val initIndex = RegInit(0.U(indexWidth.W))

  val cacheReadPort = cache.readPorts(0)
  cacheReadPort.enable := false.B
  cacheReadPort.address := DontCare
  val cacheWritePort = cache.writePorts(0)
  cacheWritePort.enable := false.B
  cacheWritePort.address := DontCare
  cacheWritePort.data := DontCare

  /// Whether a request is pending (received in State.init).
  val regRequestPending = RegInit(false.B)

  val regAddress = Reg(UInt(addressWidth.W))
  val regIsWrite = Reg(Bool())
  val regLastDataRead = Reg(UInt(dataWidth.W))
  io.in.ready := !regRequestPending
  io.in.dataRead := DontCare
  io.out.enable := false.B
  io.out.address := DontCare
  io.out.isWrite := false.B
  io.out.writeStrobe := DontCare
  io.out.dataWrite := DontCare

  private def getIndex(address: UInt): UInt = address(indexWidth - 1, 0)
  private def getTag(address: UInt): UInt = address(addressWidth - 1, indexWidth)

  switch (state) {
    // Initialization: upon reset, iterate through the cache and invalidate each entry.
    is (State.init) {
      val nextInitIndex = initIndex + 1.U
      cacheWritePort.enable := true.B
      cacheWritePort.address := initIndex
      cacheWritePort.data := 0.U
      initIndex := nextInitIndex
      when (nextInitIndex === 0.U) {
        state := State.idle
      }
    }

    is (State.idle) {
      when (regRequestPending) {
        // Got a request during init that we need to handle. Don't bother checking cache, it's not there.
        io.out.enable := true.B
        io.out.address := regAddress
        state := State.waitMem
        regRequestPending := false.B
      }
    }

    is (State.waitCache) {
      io.in.ready := false.B
      val entry = cacheReadPort.data.asTypeOf(entryType)
      when (entry.valid && entry.tag === getTag(regAddress)) {
        // Cache hit!
        io.in.ready := true.B
        io.in.dataRead := entry.data
        state := State.idle
      } .otherwise {
        // Cache miss, begin the read of main memory.
        io.out.enable := true.B
        io.out.address := regAddress
        // Note: assumes that io.out.enable is true, as it should be, because we have no request pending.
        state := State.waitMem
      }
    }

    is (State.waitMem) {
      io.in.ready := false.B

      when (io.out.ready) {
        // Insert into cache.
        cacheWritePort.enable := true.B
        cacheWritePort.address := getIndex(regAddress)
        val wire = Wire(entryType)
        wire.valid := true.B
        wire.data := io.out.dataRead
        wire.tag := getTag(regAddress)
        cacheWritePort.data := wire.asUInt
        regLastDataRead := io.out.dataRead

        // Pass data onwards
        io.in.ready := true.B
        io.in.dataRead := io.out.dataRead
        state := State.idle
      }
    }

    is (State.forwardMem) {
      io.in.ready := true.B
      io.in.dataRead := regLastDataRead
      state := State.idle
    }
  }

  when (io.in.ready && io.in.enable) {
    regAddress := io.in.address

    when (state === State.init) {
      // Save the request for when we're ready.
      regRequestPending := true.B
    } .elsewhen (state === State.waitMem &&
        getIndex(regAddress) === getIndex(io.in.address) &&
        getTag(regAddress) === getTag(io.in.address)) {
      // The cache write completing this cycle matches this request, forward the data.
      // This is required because generally in a block RAM (e.g. on Xilinx Series 7),
      // a read will return the old data being written.
      state := State.forwardMem
    } .otherwise {
      // Check the cache for the data.
      // TODO: invalidate on writes (when supported)
      cacheReadPort.enable := true.B
      cacheReadPort.address := getIndex(io.in.address)
      state := State.waitCache
    }
  }
}
