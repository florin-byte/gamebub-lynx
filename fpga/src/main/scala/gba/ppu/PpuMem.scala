package gba.ppu

import chisel3._
import chisel3.util._
import gba.mem.TargetInterface
import lib.log.Logger

class PpuMemoryInterface(size: Int, width: Width) extends Bundle {
  /// Word address
  val address = Input(UInt(log2Ceil(size).W))
  val read = Input(Bool())
  val readData = Output(UInt(width))
}

/// Module for PPU-owned memory that is also exposed to the CPU:
/// VRAM, OAM, and Palette RAM
///
/// PPU port is always read-only and takes priority over CPU.
/// Byte strobe is not supported, but halfword writes are
class PpuMem(name: String, size: Int, width: Width) extends Module {
  override val desiredName = s"PpuMem_$name"
  val io = IO(new Bundle {
    val enable = Input(Bool())

    /// Whether the PPU is in force blank (in which PPU reads are blocked)
    val forceBlank = Input(Bool())

    /// Whether to ignore 8-bit writes
    val ignoreByteWrites = Input(Bool())

    /// Target interface for main CPU memory bus
    val cpuTarget = new TargetInterface(width)

    /// Target interface for the PPU
    val ppuTarget = new PpuMemoryInterface(size / (width.get / 8), width)
  })

  val logger = Logger(s"ppu.mem.${name}", enable = io.enable)
  val widthBytes = width.get / 8
  val widthHalfwords = width.get / 16
  val numWords = size / widthBytes
  val addrShift = log2Ceil(widthBytes)

  val cpuBusy = RegInit(false.B)
  val cpuBlocked = RegInit(false.B)
  val queuedWrite = RegInit(false.B)
  val queuedAddress = Reg(UInt(log2Ceil(numWords).W))
  val queuedWriteMask = Reg(UInt(widthBytes.W))

  // Note that, even though we ostensibly do *either* only one read *or* one write each cycle,
  // due to the pipelined bus, a read access must be able to start when we're *finishing* (i.e.
  // actually performing) the previous write access. Thus, we need two separate read and
  // write ports.
  val mem = SRAM.masked(numWords, Vec(widthHalfwords, UInt(16.W)), numReadPorts = 1, numWritePorts = 1, numReadwritePorts = 0)
  val memReadPort = mem.readPorts(0)
  val memWritePort = mem.writePorts(0)
  memReadPort.enable := false.B
  memReadPort.address := DontCare
  memWritePort.enable := false.B
  memWritePort.address := DontCare
  memWritePort.mask.get := DontCare
  memWritePort.data := DontCare
  io.cpuTarget.done := false.B

  // Read / write conflict handling (on different ports).
  // The write is supposed to have occurred before the read, so forward the write data.
  val conflictWrite = RegInit(false.B)
  val conflictWriteData = Reg(UInt(width))
  val conflictWriteMask = Reg(UInt(widthBytes.W))
  when (memReadPort.enable) {
    conflictWrite := false.B
  }
  val memReadData = WireDefault(memReadPort.data.asUInt)
  when (conflictWrite) {
    memReadData := gba.MMIO.mask(memReadPort.data.asUInt, conflictWriteData, conflictWriteMask);
  }

  // Latch read data, in case io.enable goes to false.
  val lastRead = RegNext(memReadPort.enable)
  val readDataLatch = Reg(UInt(32.W))
  when (lastRead) {
    readDataLatch := memReadData.asUInt
  }
  val readData = Mux(lastRead, memReadData.asUInt, readDataLatch)
  io.cpuTarget.dataRead := readData
  io.ppuTarget.readData := readData

  when (io.enable) {
    when (cpuBusy) {
      cpuBusy := false.B
      io.cpuTarget.done := true.B

      when (queuedWrite) {
        val mask = if (width == 32.W) {
          // OAM ignore 8 bit writes
          val isNonByteWrite = (queuedWriteMask(0) && queuedWriteMask(1)) || (queuedWriteMask(2) && queuedWriteMask(3))
          Seq(queuedWriteMask(0) && isNonByteWrite, queuedWriteMask(2) && isNonByteWrite)
        } else {
          val isHalfwordWrite = queuedWriteMask(0) && queuedWriteMask(1)
          Seq(isHalfwordWrite || !io.ignoreByteWrites)
        }

        memWritePort.enable := true.B
        memWritePort.address := queuedAddress
        memWritePort.mask.get := mask
        memWritePort.data := io.cpuTarget.dataWrite.asTypeOf(Vec(widthHalfwords, UInt(16.W)))

        when (memReadPort.enable && memReadPort.address === memWritePort.address) {
          // Write conflict!
          conflictWriteData := io.cpuTarget.dataWrite
          conflictWrite := true.B
          conflictWriteMask := queuedWriteMask
        }
      }
    }

    val ppuAccess = io.ppuTarget.read && !io.forceBlank
    when (ppuAccess) {
      memReadPort.enable := true.B
      memReadPort.address := io.ppuTarget.address
    }
    when (io.cpuTarget.request || cpuBlocked) {
      // If the PPU is currently accessing the memory, the CPU is blocked.
      when (ppuAccess) {
        cpuBlocked := true.B
      } .otherwise {
        cpuBusy := true.B
        cpuBlocked := false.B
      }

      // It's possible that we just "done'd" a previous request, so the CPU thinks that we accepted it.
      // Thus, when the CPU is getting blocked, we need to store the access information (just like BusArbiter).
      when (!cpuBlocked) {
        queuedAddress := io.cpuTarget.address >> addrShift
        queuedWrite := io.cpuTarget.write
        queuedWriteMask := io.cpuTarget.mask
      }

      // The request has actually gone through -- start the read (if applicable).
      when (!ppuAccess && !io.cpuTarget.write) {
        memReadPort.enable := true.B
        memReadPort.address := Mux(cpuBlocked, queuedAddress, io.cpuTarget.address >> addrShift)
      }
    }
  }
}

