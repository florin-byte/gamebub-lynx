package gba.ppu

import chisel3._
import chisel3.util.RegEnable
import gba.mem.TargetInterface

class Vram extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())

    val forceBlank = Input(Bool())

    /// Display mode
    val displayMode = Input(UInt(3.W))

    /// Target interface for main CPU memory bus
    val cpuTarget = new TargetInterface(16.W)

    /// PPU read interface for Backgrounds (address width = 16)
    val portBG = new PpuMemoryInterface(80 * 1024 / 2, 16.W)

    /// PPU read interface for Objects (address width = 14)
    val portOBJ = new PpuMemoryInterface(32 * 1024 / 2, 16.W)
  })

  val isTileMode = io.displayMode < 3.U
  val currBgAddr = io.portBG.address(15, 14)
  val currObjAddr = io.portOBJ.address(13)
  val currCpuAddr = io.cpuTarget.address(16, 14)
  val lastBgAddr = RegEnable(currBgAddr, io.enable && io.portBG.read)
  val lastObjAddr = RegEnable(currObjAddr, io.enable && io.portOBJ.read)
  val lastCpuAddr = RegEnable(currCpuAddr, io.enable && io.cpuTarget.request)

  /*
   * VRAM is a total of 96 KiB: 16-bit words, without byte strobe.
   *
   * Lower 64KiB is exclusively BG. The next 16KiB is BG in bitmap modes, OBJ otherwise,
   * and the top 16KiB is exclusively OBJ.
   */
  val memBg = Module(new PpuMem("vramBG", 64 * 1024, 16.W)) //  address width = 15
  val memObjLo = Module(new PpuMem("vramObjLo", 16 * 1024, 16.W)) //  address width = 13
  val memObjHi = Module(new PpuMem("vramObjHi", 16 * 1024, 16.W)) //  address width = 13
  memBg.io.enable := io.enable
  memObjLo.io.enable := io.enable
  memObjHi.io.enable := io.enable
  memBg.io.forceBlank := io.forceBlank
  memObjLo.io.forceBlank := io.forceBlank
  memObjHi.io.forceBlank := io.forceBlank

  when (isTileMode) {
    memBg.io.ignoreByteWrites := false.B
    memObjLo.io.ignoreByteWrites := true.B
    memObjHi.io.ignoreByteWrites := true.B

    memBg.io.ppuTarget.address := io.portBG.address
    memObjLo.io.ppuTarget.address := io.portOBJ.address
    memObjHi.io.ppuTarget.address := io.portOBJ.address
    memBg.io.ppuTarget.read := false.B
    memObjLo.io.ppuTarget.read := false.B
    memObjHi.io.ppuTarget.read := false.B

    // BG read
    when (!currBgAddr(1)) {
      memBg.io.ppuTarget.read := io.portBG.read
    }
    when (!lastBgAddr(1)) {
      io.portBG.readData := memBg.io.ppuTarget.readData
    } .otherwise {
      io.portBG.readData := 0.U
    }

    // OBJ read
    when (!currObjAddr) {
      memObjLo.io.ppuTarget.read := io.portOBJ.read
    } .otherwise {
      memObjHi.io.ppuTarget.read := io.portOBJ.read
    }
    when (!lastObjAddr) {
      io.portOBJ.readData := memObjLo.io.ppuTarget.readData
    } .otherwise {
      io.portOBJ.readData := memObjHi.io.ppuTarget.readData
    }
  } .otherwise {
    memBg.io.ignoreByteWrites := false.B
    memObjLo.io.ignoreByteWrites := false.B
    memObjHi.io.ignoreByteWrites := true.B

    memBg.io.ppuTarget.address := io.portBG.address
    memObjLo.io.ppuTarget.address := io.portBG.address
    memObjHi.io.ppuTarget.address := io.portOBJ.address
    memBg.io.ppuTarget.read := false.B
    memObjLo.io.ppuTarget.read := false.B
    memObjHi.io.ppuTarget.read := false.B

    // BG read
    when (!currBgAddr(1)) {
      memBg.io.ppuTarget.read := io.portBG.read
    } .otherwise {
      when (!currBgAddr(0)) {
        memObjLo.io.ppuTarget.read := io.portBG.read
      }
    }
    when (!lastBgAddr(1)) {
      io.portBG.readData := memBg.io.ppuTarget.readData
    } .otherwise {
      when (!lastBgAddr(0)) {
        io.portBG.readData := memObjLo.io.ppuTarget.readData
      } .otherwise {
        io.portBG.readData := 0.U
      }
    }

    // OBJ read
    when (currObjAddr) {
      memObjHi.io.ppuTarget.read := io.portOBJ.read
    }
    when (!lastObjAddr) {
      io.portOBJ.readData := 0.U
    } .otherwise {
      io.portOBJ.readData := memObjHi.io.ppuTarget.readData
    }
  }

  // CPU access
  memBg.io.cpuTarget <> io.cpuTarget
  memObjLo.io.cpuTarget <> io.cpuTarget
  memObjHi.io.cpuTarget <> io.cpuTarget
  memBg.io.cpuTarget.request := false.B
  memObjLo.io.cpuTarget.request := false.B
  memObjHi.io.cpuTarget.request := false.B

  when (!currCpuAddr(2)) {
    memBg.io.cpuTarget.request := io.cpuTarget.request
  } .otherwise {
    when (!currCpuAddr(0)) {
      memObjLo.io.cpuTarget.request := io.cpuTarget.request
    } .otherwise {
      memObjHi.io.cpuTarget.request := io.cpuTarget.request
    }
  }
  when (!lastCpuAddr(2)) {
    io.cpuTarget.dataRead := memBg.io.cpuTarget.dataRead
    io.cpuTarget.done := memBg.io.cpuTarget.done
  } .otherwise {
    when (!lastCpuAddr(0)) {
      io.cpuTarget.dataRead := memObjLo.io.cpuTarget.dataRead
      io.cpuTarget.done := memObjLo.io.cpuTarget.done
    } .otherwise {
      io.cpuTarget.dataRead := memObjHi.io.cpuTarget.dataRead
      io.cpuTarget.done := memObjHi.io.cpuTarget.done
    }
  }
}
