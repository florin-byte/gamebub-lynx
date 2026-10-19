package net.gamebub.core.gameboy

import chisel3._
import chisel3.util._
import gameboy.Gameboy
import gameboy.cart.emu.EmuCartridge
import lib.mem.MemoryInterface

/**
 * N10 save-state walker for HandheldGameboy.
 *
 * Sequences the Gameboy core's save-state window (memories + IO registers + CGB
 * palette + CPU/sysctrl/PPU/mapper blobs) to/from a region in SDRAM, so the host
 * only has to stream that SDRAM region <-> SD, like any other core file (see the
 * file commands in HandheldGameboy). The byte order/sequence mirrors the earlier
 * Verilator reference model of the save state (round-trip 14/14 PASS).
 * Originally written for the rev2 fork of the Game Bub firmware (HW-tested there).
 *
 * Layout of one slot (contiguous bytes, internal to the walker — the MCU treats
 * it as opaque):
 *   [0            .. N_MEM-1 ]  memories, flat & CONTIGUOUS:
 *                               WRAM 0x0..0x7FFF, VRAM 0x8000..0xBFFF,
 *                               OAM 0xC000..0xC09F, HRAM 0xC0A0..0xC11E
 *   [N_MEM        .. +0x80   ]  IO registers 0x00..0x7F
 *   [N_MEM+0x80   .. +0x80   ]  CGB palette 0..127 (BG 0..63, OBJ 0..63)
 *   [tail                    ]  CPU || misc(sysctrl+ppu) || mapper, byte-packed
 *
 * Save aligns the capture point to a PPU vblank rising edge (pipeline idle), then
 * freezes; load restores under freeze then pulses the one-shot load strobes.
 *
 * @param cpuBits  width of Cpu.SaveState (passed from HandheldGameboy)
 * @param miscBits width of MiscSaveState
 * @param vblTimeoutLog2 abort the vblank wait after 2^n cycles (B1: with the LCD
 *                       off the PPU never raises vblank — without this the walker
 *                       would sit in sVbl forever with the core running under the
 *                       menu). Default 2^21 @ 8.39 MHz ≈ 250 ms (≫ 2 frames, and
 *                       < the firmware's 500 ms poll timeout so the abort is what
 *                       the firmware observes). Overridden small in tests.
 */
class GbSaveStateWalker(cpuBits: Int, miscBits: Int,
                        vblTimeoutLog2: Int = 21) extends Module {
  // ── Blob geometry ────────────────────────────────────────────────────────
  val N_MEM  = 0xC11F            // 49439 contiguous mem bytes (0x0..0xC11E)
  val N_IO   = 0x80
  val N_PAL  = 0x80
  val MAP_BITS = 32
  val tailBits  = cpuBits + miscBits + MAP_BITS
  val tailBytes = (tailBits + 7) / 8
  val MEM_BASE  = 0
  val IO_BASE   = N_MEM
  val PAL_BASE  = N_MEM + N_IO
  val TAIL_BASE = N_MEM + N_IO + N_PAL
  val N_TOTAL   = TAIL_BASE + tailBytes
  val N_WORDS   = (N_TOTAL + 3) / 4
  val STATE_BYTES = N_WORDS * 4   // what the firmware streams (== SS_STATE_BYTES)
  // B4: tailVec is indexed with tailOff(4,0) — 5 bits — in sSaveReq/sLoadByte;
  // a tail beyond 32 bytes would alias silently (savestate corruption with no
  // elaboration error). Turn that future bug into a build failure.
  require(tailBytes <= 32,
    s"tailBytes=$tailBytes exceeds the 5-bit tailOff index (widen tailOff slices)")
  // Surface the computed size at elaboration so it can be cross-checked vs the
  // firmware constant.
  println(s"[GbSaveStateWalker] tailBytes=$tailBytes N_TOTAL=$N_TOTAL " +
          s"N_WORDS=$N_WORDS STATE_BYTES=$STATE_BYTES")

  val io = IO(new Bundle {
    // ── Control (from MMIO registers in HandheldGameboy) ──
    val savePulse = Input(Bool())
    val loadPulse = Input(Bool())
    val slotBase  = Input(UInt(25.W))   // byte base = SS_SDRAM_BASE + slot*stride
    val busy      = Output(Bool())
    val loaded    = Output(Bool())      // sticky: a load completed
    val aborted   = Output(Bool())      // sticky: save gave up waiting for vblank (B1)

    // ── Core save-state window (walker drives inputs, reads outputs) ──
    val ssAddress   = Output(UInt(17.W))
    val ssEnable    = Output(Bool())
    val ssWrite     = Output(Bool())
    val ssDataWrite = Output(UInt(8.W))
    val ssDataRead  = Input(UInt(8.W))
    val regAddress   = Output(UInt(8.W))
    val regEnable    = Output(Bool())
    val regWrite     = Output(Bool())
    val regDataWrite = Output(UInt(8.W))
    val regDataRead  = Input(UInt(8.W))
    val palAddr      = Output(UInt(7.W))
    val palWrite     = Output(Bool())
    val palDataWrite = Output(UInt(8.W))
    val palDataRead  = Input(UInt(8.W))
    val cpuLoad = Output(Bool())
    val cpuIn   = Output(UInt(cpuBits.W))
    val cpuOut  = Input(UInt(cpuBits.W))
    val miscLoad = Output(Bool())
    val miscIn   = Output(UInt(miscBits.W))
    val miscOut  = Input(UInt(miscBits.W))
    val mbcLoad = Output(Bool())
    val mbcIn   = Output(UInt(MAP_BITS.W))
    val mbcOut  = Input(UInt(MAP_BITS.W))

    // ── Vblank alignment + clock request for the run-to-vblank phase ──
    val ppuVblank      = Input(Bool())
    val coreClockEnable = Output(Bool())  // OR'd into clockConfig.enable upstream

    // ── SDRAM initiator (muxed onto io.sdram while owned, see ownsSdram) ──
    val sdram     = Flipped(new MemoryInterface(addressWidth = 25, dataWidth = 32))
    val ownsSdram = Output(Bool())        // walker is driving the SDRAM port
  })

  // ── State ────────────────────────────────────────────────────────────────
  object S extends ChiselEnum {
    val sIdle, sVbl, sCapture, sSaveReq, sSaveWait, sSaveCollect, sSaveWrite,
        sLoadRead, sLoadByte, sLoadFinish, sLoadLatch, sDone = Value
  }
  val state = RegInit(S.sIdle)
  val isLoad = RegInit(false.B)

  val pos      = RegInit(0.U(log2Ceil(N_TOTAL + 1).W))
  val wordIdx  = RegInit(0.U(log2Ceil(N_WORDS + 1).W))
  val wordByte = RegInit(0.U(2.W))
  val wordVec  = Reg(Vec(4, UInt(8.W)))
  val byteVal  = Reg(UInt(8.W))
  val tailVec  = Reg(Vec(tailBytes, UInt(8.W)))
  val prevVbl  = RegInit(false.B)
  val loadedSticky = RegInit(false.B)
  val abortedSticky = RegInit(false.B)
  val vblWait = RegInit(0.U((vblTimeoutLog2 + 1).W))

  // Position classification (shared by save & load routing).
  val inMem = pos < N_MEM.U
  val inIo  = (pos >= IO_BASE.U)  && (pos < PAL_BASE.U)
  val inPal = (pos >= PAL_BASE.U) && (pos < TAIL_BASE.U)
  val inTail = pos >= TAIL_BASE.U
  val ioOff   = (pos - IO_BASE.U)(7, 0)
  val palOff  = (pos - PAL_BASE.U)(6, 0)
  val tailOff = pos - TAIL_BASE.U
  // IO regs that must NOT be replayed on load (DIV reset / LY read-only /
  // OAM-DMA + HDMA triggers) — mirror of simulator.cpp loadState.
  val ioSkip = (ioOff === 0x04.U) || (ioOff === 0x44.U) ||
               (ioOff === 0x46.U) || (ioOff === 0x55.U)

  // ── Defaults (deasserted) ──
  io.ssAddress := pos
  io.ssEnable := false.B
  io.ssWrite := false.B
  io.ssDataWrite := byteVal
  io.regAddress := ioOff
  io.regEnable := false.B
  io.regWrite := false.B
  io.regDataWrite := byteVal
  io.palAddr := palOff
  io.palWrite := false.B
  io.palDataWrite := byteVal
  io.cpuLoad := false.B
  io.miscLoad := false.B
  io.mbcLoad := false.B
  // Load targets, derived combinationally from the accumulated tail bytes.
  val tailWide = Cat(tailVec.reverse)            // tailVec(0) = LSB
  io.mbcIn  := tailWide(MAP_BITS - 1, 0)
  io.miscIn := tailWide(MAP_BITS + miscBits - 1, MAP_BITS)
  io.cpuIn  := tailWide(MAP_BITS + miscBits + cpuBits - 1, MAP_BITS + miscBits)

  io.sdram.enable := false.B
  io.sdram.write := false.B
  io.sdram.address := io.slotBase + Cat(wordIdx, 0.U(2.W))
  io.sdram.dataWrite := Cat(wordVec.reverse)     // wordVec(0) = LSB byte
  io.sdram.writeStrobe := "b1111".U
  io.ownsSdram := (state === S.sSaveWrite) || (state === S.sLoadRead)
  io.coreClockEnable := (state === S.sVbl)
  io.busy := state =/= S.sIdle
  io.loaded := loadedSticky
  io.aborted := abortedSticky

  val vblRising = io.ppuVblank && !prevVbl
  prevVbl := io.ppuVblank

  switch (state) {
    is (S.sIdle) {
      when (io.savePulse) {
        isLoad := false.B; loadedSticky := false.B; abortedSticky := false.B
        prevVbl := io.ppuVblank
        vblWait := 0.U
        state := S.sVbl
      } .elsewhen (io.loadPulse) {
        isLoad := true.B; loadedSticky := false.B; abortedSticky := false.B
        pos := 0.U; wordIdx := 0.U; wordByte := 0.U
        tailVec.foreach(_ := 0.U)
        state := S.sLoadRead
      }
    }

    // ── SAVE: run the core to a vblank rising edge, then freeze & capture ──
    is (S.sVbl) {
      vblWait := vblWait + 1.U
      when (vblRising) { state := S.sCapture }
      .elsewhen (vblWait(vblTimeoutLog2)) {
        // B1: no vblank in ~250 ms → the LCD is off (PPU idle) or the game is
        // wedged. Abort so busy falls and the core stops free-running; the
        // firmware surfaces the sticky aborted bit as a save error.
        abortedSticky := true.B
        state := S.sIdle
      }
    }
    is (S.sCapture) {
      // Core frozen one cycle: latch the combinational CPU/misc/mapper blobs.
      val cap = Cat(io.cpuOut, io.miscOut, io.mbcOut)   // [cpu | misc | mapper]
      tailVec := cap.asTypeOf(Vec(tailBytes, UInt(8.W)))
      pos := 0.U; wordIdx := 0.U; wordByte := 0.U
      state := S.sSaveReq
    }
    is (S.sSaveReq) {
      when (inMem)      { io.ssEnable := true.B; state := S.sSaveWait }
      .elsewhen (inIo)  { io.regEnable := true.B; state := S.sSaveWait }
      .elsewhen (inPal) { state := S.sSaveWait }     // palette read is combinational
      .otherwise        {                            // tail: from captured bytes
        byteVal := tailVec(tailOff(4, 0))
        state := S.sSaveCollect
      }
    }
    is (S.sSaveWait) {
      when (inMem)      { byteVal := io.ssDataRead }
      .elsewhen (inIo)  { io.regEnable := true.B; byteVal := io.regDataRead }
      .otherwise        { byteVal := io.palDataRead }
      state := S.sSaveCollect
    }
    is (S.sSaveCollect) {
      wordVec(wordByte) := byteVal
      val posNext = pos + 1.U
      val wbNext  = wordByte + 1.U
      pos := posNext
      // wordByte is 2-bit: compare to 3 (the 4th/last byte just stored), NOT
      // wbNext===4 (wbNext wraps 3->0 in 2 bits, so ===4 is never true).
      when ((wordByte === 3.U) || (posNext === N_TOTAL.U)) {
        state := S.sSaveWrite
      } .otherwise {
        wordByte := wbNext
        state := S.sSaveReq
      }
    }
    is (S.sSaveWrite) {
      io.sdram.enable := true.B
      io.sdram.write := true.B
      // dataWrite uses wordVec; lanes not yet filled (tail of a short final word)
      // are stale but harmless — firmware reads back exactly STATE_BYTES.
      when (io.sdram.done) {
        wordIdx := wordIdx + 1.U
        wordByte := 0.U
        when (pos === N_TOTAL.U) { state := S.sDone }
          .otherwise { state := S.sSaveReq }
      }
    }

    // ── LOAD: read words from SDRAM, distribute bytes, then latch blobs ──
    is (S.sLoadRead) {
      io.sdram.enable := true.B
      io.sdram.write := false.B
      when (io.sdram.done) {
        wordVec := io.sdram.dataRead.asTypeOf(Vec(4, UInt(8.W)))
        wordByte := 0.U
        wordIdx := wordIdx + 1.U
        state := S.sLoadByte
      }
    }
    is (S.sLoadByte) {
      val b = wordVec(wordByte)
      when (inMem) {
        io.ssDataWrite := b; io.ssWrite := true.B; io.ssEnable := true.B
      } .elsewhen (inIo) {
        when (!ioSkip) {
          io.regDataWrite := b; io.regWrite := true.B; io.regEnable := true.B
        }
      } .elsewhen (inPal) {
        io.palDataWrite := b; io.palWrite := true.B
      } .otherwise {
        tailVec(tailOff(4, 0)) := b
      }
      val posNext = pos + 1.U
      val wbNext  = wordByte + 1.U
      pos := posNext
      when (posNext === N_TOTAL.U) {
        state := S.sLoadFinish
      } .elsewhen (wordByte === 3.U) { // 4th byte consumed (wbNext wraps in 2 bits)
        wordByte := 0.U
        state := S.sLoadRead
      } .otherwise {
        wordByte := wbNext
        state := S.sLoadByte
      }
    }
    is (S.sLoadFinish) {
      // Boot ROM is always unmapped post-boot (save is taken post-boot).
      io.regAddress := 0x50.U; io.regDataWrite := 0x01.U
      io.regWrite := true.B; io.regEnable := true.B
      state := S.sLoadLatch
    }
    is (S.sLoadLatch) {
      io.cpuLoad := true.B; io.miscLoad := true.B; io.mbcLoad := true.B
      loadedSticky := true.B
      state := S.sDone
    }

    is (S.sDone) { state := S.sIdle }
  }
}

object GbSaveStateWalker {
  /** Connects a walker to the save-state ports of a Gameboy core and of its emulated cartridge. */
  def connect(walker: GbSaveStateWalker, gameboy: Gameboy, emuCart: EmuCartridge): Unit = {
    val window = gameboy.io.saveState
    window.address := walker.io.ssAddress
    window.enable := walker.io.ssEnable
    window.write := walker.io.ssWrite
    window.dataWrite := walker.io.ssDataWrite
    walker.io.ssDataRead := window.dataRead
    window.regAddress := walker.io.regAddress
    window.regEnable := walker.io.regEnable
    window.regWrite := walker.io.regWrite
    window.regDataWrite := walker.io.regDataWrite
    walker.io.regDataRead := window.regDataRead
    window.palAddr := walker.io.palAddr
    window.palWrite := walker.io.palWrite
    window.palDataWrite := walker.io.palDataWrite
    walker.io.palDataRead := window.palDataRead
    window.cpuLoad := walker.io.cpuLoad
    window.cpuIn := walker.io.cpuIn.asTypeOf(window.cpuIn)
    walker.io.cpuOut := window.cpuOut.asUInt
    window.miscLoad := walker.io.miscLoad
    window.miscIn := walker.io.miscIn.asTypeOf(window.miscIn)
    walker.io.miscOut := window.miscOut.asUInt
    walker.io.ppuVblank := gameboy.io.ppu.vblank

    emuCart.io.ssIn := walker.io.mbcIn
    emuCart.io.ssLoad := walker.io.mbcLoad
    walker.io.mbcOut := emuCart.io.ssOut
  }
}
