package net.gamebub.core.gameboy

import chisel3._
import chisel3.util._
import gameboy.{Gameboy, SerialDebug}
import gameboy.apu.ApuOutput
import gameboy.cpu.Cpu
import gameboy.ppu.PpuOutput

/** GB-145: frame-locked buttons for two GB cores in one console.
  *
  * Both consoles run the same pair of cores (own game visible, partner's game
  * hidden) and exchange only buttons. A "frame" is `frameCycles` enabled core
  * clocks, not a PPU frame: the two cores have separate PPUs and a game may
  * switch its LCD off.
  *
  * At the boundary of frame F the module
  *  - applies the local buttons sampled at F-delay to the visible core,
  *  - applies the partner's buttons for F to the hidden core, stalling both
  *    cores until the host has written them (frames below `delay` use none),
  *  - samples the local buttons for F+delay and queues, for the host,
  *    {F+delay, buttons, trace digests of both cores at F}.
  *
  * State is cleared while `enable` is low; `run` low holds the cores stopped
  * but keeps accepting partner buttons.
  */
object DualCoreSync {
  val FrameCycles = 140448
  /** 'SY' identifier[31:16], ABI[15:0]. */
  val Caps = 0x53590004L
  val FifoDepth = 8
  val Window = 16

  /** What one core contributes to its trace digest on an enabled clock. */
  def traceSample(gb: Gameboy): UInt =
    traceSample(gb.io.ppu, gb.io.cpuDebug, gb.io.serialDebug, gb.io.apu)

  def traceSample(ppu: PpuOutput, cpu: Cpu.DebugState, serial: SerialDebug, apu: ApuOutput): UInt = {
    val pixel = Mux(ppu.valid, Cat(1.U(1.W), ppu.pixel), 0.U(16.W))
    Cat(
      cpu.regPc,
      pixel ^ Cat(serial.regData, serial.regEnable, 0.U(7.W)),
    ) ^ Cat(apu.left.asUInt, apu.right.asUInt)
  }
}

/** `buttonBits`: 8 for GB (default), 10 for GBA (GB-156). The frame number sits above the buttons
  * in `partnerData` and `local`. */
class DualCoreSync(frameCycles: Int = DualCoreSync.FrameCycles, buttonBits: Int = 8) extends Module {
  require(buttonBits >= 1 && buttonBits <= 15)
  import DualCoreSync.{FifoDepth, Window}
  val io = IO(new Bundle {
    val enable = Input(Bool())
    val run = Input(Bool())
    val delay = Input(UInt(3.W))
    /** Both cores advance on this clock. Must be low while `stall` is high. */
    val tick = Input(Bool())
    val localButtons = Input(UInt(buttonBits.W))
    val sampleVisible = Input(UInt(32.W))
    val sampleHidden = Input(UInt(32.W))
    /** One-cycle strobes from the host. partnerData: frame[buttonBits+15:buttonBits], buttons[buttonBits-1:0]. */
    val partnerWrite = Input(Bool())
    val partnerData = Input(UInt((16 + buttonBits).W))
    val pop = Input(Bool())
    /** Core clocks by which player 2's core starts after player 1's (0: together). Two real
      * consoles are never in phase; some games never find each other when they are. */
    val phase = Input(UInt(18.W))
    /** The local (visible) core is player 2, so it is the one that starts late. */
    val visibleLate = Input(Bool())
    /** Serial port state of each core: transfer active[1], this core drives the clock[0]. */
    val serialVisible = Input(UInt(2.W))
    val serialHidden = Input(UInt(2.W))

    val stall = Output(Bool())
    /** Keep this core's clock off although `tick` is high (start phase). */
    val holdVisible = Output(Bool())
    val holdHidden = Output(Bool())
    val visibleButtons = Output(UInt(buttonBits.W))
    val hiddenButtons = Output(UInt(buttonBits.W))
    /** Level: a local sample waits for the host. */
    val request = Output(Bool())
    /** Head of the local queue: valid[31], apply frame[buttonBits+15:buttonBits], buttons[buttonBits-1:0]. */
    val local = Output(UInt(32.W))
    /** Digests at frame (apply frame - delay) of the queue head. */
    val digestVisible = Output(UInt(32.W))
    val digestHidden = Output(UInt(32.W))
    /** frame[15:0], queue count[19:16], at boundary[20], waiting for partner[21], queue full[22], run[24]. */
    val status = Output(UInt(32.W))
    /** System clocks spent stopped at a frame boundary while running. */
    val waitCycles = Output(UInt(32.W))
    /** Link cable activity per core: transfers completed[31:16], started as master[15:0]. */
    val serialStatVisible = Output(UInt(32.W))
    val serialStatHidden = Output(UInt(32.W))
    /** Completed on the external clock: hidden[31:24], visible[23:16]; a master start while the other core is master[15:0]. */
    val serialStatCross = Output(UInt(32.W))
    /** Partner writes: accepted[31:16], strobes seen[15:0]. */
    val writeStat = Output(UInt(32.W))
    /** Partner writes refused: frame already running[31:16], frame outside the window[15:0]. */
    val rejectStat = Output(UInt(32.W))
    /** Last refused write: module frame[31:16], written frame[15:0]. */
    val lastReject = Output(UInt(32.W))
  })

  val frame = RegInit(0.U(16.W))
  val cycle = RegInit(0.U(log2Ceil(frameCycles).W))
  val atBoundary = RegInit(true.B)
  val warm = RegInit(false.B) // frame counter has passed `delay` at least once
  val waitCycles = RegInit(0.U(32.W))

  val localRing = RegInit(VecInit(Seq.fill(Window)(0.U(buttonBits.W))))
  val partnerRing = Reg(Vec(Window, UInt(buttonBits.W)))
  val partnerValid = RegInit(VecInit(Seq.fill(Window)(false.B)))

  val fifoFrame = Reg(Vec(FifoDepth, UInt(16.W)))
  val fifoButtons = Reg(Vec(FifoDepth, UInt(buttonBits.W)))
  val fifoVisible = Reg(Vec(FifoDepth, UInt(32.W)))
  val fifoHidden = Reg(Vec(FifoDepth, UInt(32.W)))
  val head = RegInit(0.U(log2Ceil(FifoDepth).W))
  val tail = RegInit(0.U(log2Ceil(FifoDepth).W))
  val count = RegInit(0.U(log2Ceil(FifoDepth + 1).W))

  val digestVisible = RegInit(0.U(32.W))
  val digestHidden = RegInit(0.U(32.W))
  val visibleButtons = RegInit(0.U(buttonBits.W))
  val hiddenButtons = RegInit(0.U(buttonBits.W))

  val slot = frame(3, 0)
  val needPartner = warm || frame >= io.delay
  val partnerReady = !needPartner || partnerValid(slot)
  val full = count === FifoDepth.U
  val fire = io.enable && io.run && atBoundary && partnerReady && !full
  val remove = io.pop && count =/= 0.U

  // Partner buttons: accept the current frame (until it is consumed) and the
  // next Window-1 frames. Older frames are resends of what was already used.
  val writeFrame = io.partnerData(buttonBits + 15, buttonBits)
  val ahead = writeFrame - frame
  val writeFar = ahead >= Window.U
  val writeLate = ahead === 0.U && !atBoundary
  val writeStrobes = RegInit(0.U(16.W))
  val writeAccepted = RegInit(0.U(16.W))
  val rejectFar = RegInit(0.U(16.W))
  val rejectLate = RegInit(0.U(16.W))
  val lastReject = RegInit(0.U(32.W))
  when (io.partnerWrite) {
    writeStrobes := writeStrobes + 1.U
    when (writeFar) { rejectFar := rejectFar + 1.U }
      .elsewhen (writeLate) { rejectLate := rejectLate + 1.U }
      .otherwise { writeAccepted := writeAccepted + 1.U }
    when (writeFar || writeLate) { lastReject := Cat(frame, writeFrame) }
  }
  when (io.partnerWrite && !writeFar && !writeLate) {
    partnerRing(writeFrame(3, 0)) := io.partnerData(buttonBits - 1, 0)
    partnerValid(writeFrame(3, 0)) := true.B
  }

  when (fire) {
    val applyFrame = frame + io.delay
    visibleButtons := localRing(slot)
    hiddenButtons := Mux(needPartner, partnerRing(slot), 0.U)
    // After the write above: a resend of this frame must not leave the slot valid.
    partnerValid(slot) := false.B
    localRing(applyFrame(3, 0)) := io.localButtons
    fifoFrame(tail) := applyFrame
    fifoButtons(tail) := io.localButtons
    fifoVisible(tail) := digestVisible
    fifoHidden(tail) := digestHidden
    tail := tail + 1.U
    atBoundary := false.B
    when (frame >= io.delay) { warm := true.B }
  }
  when (remove) { head := head + 1.U }
  count := count + fire.asUInt - remove.asUInt

  when (io.run && atBoundary && !fire) { waitCycles := waitCycles + 1.U }

  // Start phase: the late core stays stopped for the first `phase` core clocks.
  val phaseLeft = RegInit(0.U(18.W))
  val holding = phaseLeft =/= 0.U
  io.holdVisible := holding && io.visibleLate
  io.holdHidden := holding && !io.visibleLate
  when (!io.run) { phaseLeft := io.phase } .elsewhen (io.tick && holding) { phaseLeft := phaseLeft - 1.U }

  // Link cable activity, as each core's serial port shows it on the core clock.
  val serial = Seq(io.serialVisible, io.serialHidden)
  val serialPrev = Seq.fill(2)(RegInit(0.U(2.W)))
  val masterStarts = Seq.fill(2)(RegInit(0.U(16.W)))
  val completions = Seq.fill(2)(RegInit(0.U(16.W)))
  val slaveDone = Seq.fill(2)(RegInit(0.U(8.W)))
  val bothMaster = RegInit(0.U(16.W))
  val masterStart = Seq.tabulate(2)(i => serial(i) === 3.U && serialPrev(i) =/= 3.U)
  when (io.tick) {
    for (i <- 0 until 2) {
      serialPrev(i) := serial(i)
      when (masterStart(i)) { masterStarts(i) := masterStarts(i) + 1.U }
      when (serialPrev(i)(1) && !serial(i)(1)) {
        completions(i) := completions(i) + 1.U
        when (!serialPrev(i)(0)) { slaveDone(i) := slaveDone(i) + 1.U }
      }
    }
    when ((masterStart(0) && serial(1) === 3.U) || (masterStart(1) && serial(0) === 3.U)) {
      bothMaster := bothMaster + 1.U
    }
  }

  when (io.tick) {
    when (!io.holdVisible) {
      digestVisible := Cat(digestVisible(26, 0), digestVisible(31, 27)) ^ io.sampleVisible
    }
    when (!io.holdHidden) {
      digestHidden := Cat(digestHidden(26, 0), digestHidden(31, 27)) ^ io.sampleHidden
    }
    when (cycle === (frameCycles - 1).U) {
      cycle := 0.U
      frame := frame + 1.U
      atBoundary := true.B
    } .otherwise {
      cycle := cycle + 1.U
    }
  }

  when (!io.enable) {
    frame := 0.U
    cycle := 0.U
    atBoundary := true.B
    warm := false.B
    waitCycles := 0.U
    localRing.foreach(_ := 0.U)
    partnerValid.foreach(_ := false.B)
    head := 0.U
    tail := 0.U
    count := 0.U
    digestVisible := 0.U
    digestHidden := 0.U
    visibleButtons := 0.U
    hiddenButtons := 0.U
    writeStrobes := 0.U
    writeAccepted := 0.U
    rejectFar := 0.U
    rejectLate := 0.U
    lastReject := 0.U
    bothMaster := 0.U
    for (i <- 0 until 2) {
      serialPrev(i) := 0.U
      masterStarts(i) := 0.U
      completions(i) := 0.U
      slaveDone(i) := 0.U
    }
  }

  io.stall := io.enable && (!io.run || atBoundary)
  io.visibleButtons := visibleButtons
  io.hiddenButtons := hiddenButtons
  io.request := io.enable && count =/= 0.U
  io.local := Cat(count =/= 0.U, 0.U((15 - buttonBits).W), fifoFrame(head), fifoButtons(head))
  io.digestVisible := fifoVisible(head)
  io.digestHidden := fifoHidden(head)
  io.status := Cat(io.run, 0.U(1.W), full, atBoundary && !partnerReady, atBoundary, count.pad(4), frame)
  io.waitCycles := waitCycles
  io.serialStatVisible := Cat(completions(0), masterStarts(0))
  io.serialStatHidden := Cat(completions(1), masterStarts(1))
  io.serialStatCross := Cat(slaveDone(1), slaveDone(0), bothMaster)
  io.writeStat := Cat(writeAccepted, writeStrobes)
  io.rejectStat := Cat(rejectLate, rejectFar)
  io.lastReject := lastReject
}
