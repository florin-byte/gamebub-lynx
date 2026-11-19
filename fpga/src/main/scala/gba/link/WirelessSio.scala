package gba.link

import chisel3._
import chisel3.util._

/** Independent of the emulated CPU enable, so SPI replies and the wall-clock
  * guard remain live while cartridge/EWRAM accesses pause the GBA.
  */
class WirelessSioPort extends Bundle {
  val enabled = Input(Bool())
  val clear = Input(Bool())
  val multiMode = Output(Bool())
  // Local completed-transfer token identifies the cycle origin. Host snapshots
  // this tuple atomically; it must not combine cycles from another origin.
  val progress = Output(new Bundle {
    val anchor = UInt(28.W)
    val cycles = UInt(32.W)
    val valid = Bool()
    val running = Bool()
  })
  val command = Flipped(Valid(new Bundle {
    val id = UInt(29.W)
    val data = UInt(32.W)
    val error = Bool()
    val begin = Bool()
    val sync = Bool()
    val cycles = UInt(32.W)
    val syncEnabled = Bool()
    val grantEnabled = Bool()
    val grant = Bool()
    val anchor = UInt(28.W)
    // Configuration is a level, independent of the command valid pulse.
    val multiEnabled = Bool()
    val multiParent = Bool()
    val multiReady = Bool()
    // GB-117: both SIOMULTI slots come from the host COMPLETE word (one-round buffer).
    val pairFromHost = Bool()
  }))
  val event = Valid(new Bundle {
    val cycles = UInt(32.W)
    val meta = UInt(32.W)
    val data = UInt(32.W)
  })
  val busy = Output(Bool())
  val multiWait = Output(Bool())
  // GB-117: the word this side sends, sampled at START or child BEGIN.
  val sampled = Output(UInt(32.W))
}

class WirelessSio(guardCycles: Int = 67108864) extends Module {
  require(guardCycles > 1)
  val io = IO(new Bundle {
    val port = new WirelessSioPort
    val enable = Input(Bool())
    val start = Input(Bool())
    val cancel = Input(Bool())
    val master = Input(Bool())
    val multi = Input(Bool())
    val multiMode = Input(Bool())
    val multiCycles = Input(UInt(17.W))
    val multiWordCycles = Input(UInt(17.W))
    // Measurement: the game's SIOCNT baud, reported in a multi START event (data[17:16]).
    val multiBaud = Input(UInt(2.W))
    val multiLost = Input(Bool())
    val data = Input(UInt(32.W))
    val done = Output(Bool())
    val received = Output(UInt(32.W))
    val completedMulti = Output(Bool())
    val failed = Output(Bool())
    val multiBusy = Output(Bool())
    val multiSlot0 = Valid(UInt(16.W))
    val multiSlot1 = Valid(UInt(16.W))
    val multiChildTurn = Output(Bool())
  })
  val busy = RegInit(false.B)
  val id = RegInit(0.U(28.W))
  val master = RegInit(false.B)
  val multi = RegInit(false.B)
  val childStarted = RegInit(false.B)
  val failed = RegInit(false.B)
  val outgoing = Reg(UInt(32.W))
  val received = Reg(UInt(32.W))
  val pending = RegInit(false.B)
  val guard = RegInit(0.U(log2Ceil(guardCycles).W))
  val multiElapsed = RegInit(0.U(17.W))
  val multiEnd = Reg(UInt(17.W))
  val multiWordEnd = Reg(UInt(17.W))
  // Anchors are local guest cycles, not wall clocks or radio receive times.
  val syncMode = io.port.enabled && io.port.command.bits.syncEnabled && io.multiMode
  val wasSyncMode = RegNext(syncMode, false.B)
  val syncElapsed = RegInit(0.U(32.W))
  val syncQueued = RegInit(false.B)
  val syncTarget = Reg(UInt(32.W))
  val syncReply = Reg(UInt(32.W))
  val syncFault = RegInit(false.B)
  val anchor = RegInit(0.U(28.W))
  val anchorValid = RegInit(false.B)
  val grantLimit = RegInit(0.U(32.W))
  val grantMode = syncMode && io.port.command.bits.grantEnabled
  io.port.progress.anchor := anchor
  io.port.progress.cycles := syncElapsed
  io.port.progress.valid := anchorValid && syncMode && !syncFault
  io.port.progress.running := io.port.progress.valid && !busy && !pending &&
    io.port.command.bits.multiParent
  // Compare in RTL at commit, not in firmware before a later SPI write.
  when (io.port.command.valid && io.port.command.bits.grant && grantMode &&
    anchorValid && !syncFault && !io.port.command.bits.multiParent &&
    io.port.command.bits.anchor === anchor &&
    io.port.command.bits.cycles > grantLimit) {
    grantLimit := io.port.command.bits.cycles
  }
  when (io.enable && syncMode && !syncFault && syncElapsed =/= "hffffffff".U) {
    syncElapsed := syncElapsed + 1.U
  }
  when (!syncMode || !wasSyncMode || io.port.clear) {
    syncElapsed := 0.U
    syncQueued := false.B
    syncFault := false.B
    anchorValid := false.B
    grantLimit := 0.U
  }
  // A ROUND is the final upper bound, even if a later GRANT is reordered.
  val childLimit = Mux(syncQueued, syncTarget, grantLimit)
  val childCredit = syncQueued || (grantMode && anchorValid)
  val syncWaiting = syncMode && !syncFault && busy && multi &&
    (master || (!childStarted && (!childCredit || syncElapsed >= childLimit)))
  val multiTransferActive = busy && multi && (master || childStarted)
  val multiResultPending = multi && pending
  val multiLinkLost = io.port.clear || !io.port.enabled || io.multiLost
  val discardMultiResult = multiResultPending && io.cancel
  val invalidateMultiResult = multiResultPending && !failed && multiLinkLost
  val multiClockRunning = multiTransferActive || multiResultPending
  // Cable order: parent word, child takes its turn, child word, then IRQ.
  val nextCycle = multiElapsed + 1.U
  val firstWord = nextCycle === multiWordEnd
  val childTurn = multiElapsed === multiWordEnd
  val secondWord = nextCycle === ((multiWordEnd << 1) + Mux(master, 3.U, 1.U))
  val pairFromHost = io.port.command.bits.pairFromHost
  // With the pair from the host, the parent's own slot also waits for COMPLETE.
  val needsPeerWord = Mux(master && !pairFromHost, secondWord, firstWord)
  io.port.multiWait := syncWaiting || (multiTransferActive && (needsPeerWord || multiElapsed === multiEnd))
  val advanceMulti = io.enable && multiClockRunning && !io.port.multiWait &&
    multiElapsed =/= multiEnd && !io.cancel && !multiLinkLost && !(pending && failed)
  when (advanceMulti) { multiElapsed := nextCycle }
  io.multiSlot0.valid := advanceMulti && firstWord
  io.multiSlot0.bits := Mux(master && !pairFromHost, io.data(15, 0), received(15, 0))
  io.multiSlot1.valid := advanceMulti && secondWord
  io.multiSlot1.bits := Mux(master || pairFromHost, received(31, 16), io.data(15, 0))
  io.multiChildTurn := advanceMulti && !master && childTurn
  io.port.busy := busy
  io.port.sampled := outgoing
  io.port.multiMode := io.multiMode
  io.port.event.valid := false.B
  io.port.event.bits.meta := 0.U
  io.port.event.bits.data := outgoing
  io.port.event.bits.cycles := syncElapsed
  io.done := pending && io.enable && !discardMultiResult && !invalidateMultiResult &&
    (!multi || failed || multiElapsed === multiEnd)
  io.received := received
  io.completedMulti := multi
  io.failed := failed
  // Keep SIOCNT busy until Link commits the pending SIOMULTI result.
  io.multiBusy := multiTransferActive || multiResultPending
  when (io.done || discardMultiResult) {
    pending := false.B
    when (multi) {
      syncElapsed := 0.U
      grantLimit := 0.U
      anchor := id
      anchorValid := io.done && !failed && syncMode && !syncFault
    }
  }
  when (invalidateMultiResult) {
    received := "hffffffff".U
    failed := true.B
    anchorValid := false.B
  }
  def event(op: Int, token: UInt, role: Bool, multiplayer: Bool, data: UInt): Unit = {
    io.port.event.valid := true.B
    io.port.event.bits.meta := Cat(op.U(2.W), role, multiplayer, token)
    io.port.event.bits.data := data
  }
  when (io.port.enabled && io.start && !busy && !pending && !io.port.clear) {
    id := id + 1.U
    master := io.master
    multi := io.multi
    childStarted := false.B
    multiElapsed := 0.U
    multiEnd := io.multiCycles
    multiWordEnd := io.multiWordCycles
    outgoing := io.data
    busy := true.B
    guard := 0.U
    event(1, id + 1.U, io.master, io.multi,
      Mux(io.multi, Cat(0.U(14.W), io.multiBaud, io.data(15, 0)), io.data))
  }
  when (busy) {
    guard := guard + 1.U
    when (io.port.command.valid && !io.port.command.bits.grant && io.port.command.bits.id === id) {
      when (io.port.command.bits.sync && syncMode && multi && !master && !childStarted && !syncQueued && !syncFault) {
        syncTarget := io.port.command.bits.cycles
        syncReply := io.port.command.bits.data
        syncQueued := true.B
        guard := 0.U
      } .elsewhen (io.port.command.bits.begin) {
        when (multi && !master && !childStarted) {
          childStarted := true.B
          // Like cable TX, sample SEND when the child starts shifting.
          outgoing := io.data
          multiElapsed := 0.U
          multiEnd := io.multiCycles
          multiWordEnd := io.multiWordCycles
          guard := 0.U
        }
      } .elsewhen (!io.port.command.bits.sync && (!multi || master || childStarted)) {
        val child = multi && !master
        val reply = io.port.command.bits.data
        val childSlots = Cat(io.data(15, 0), reply(15, 0))
        failed := io.port.command.bits.error
        received := Mux(child && !io.port.command.bits.error && !pairFromHost, childSlots, reply)
        pending := true.B
        busy := false.B
        // DONE acknowledges the transport immediately, even with guest BUSY set.
        event(2, id, master, multi, outgoing)
      }
    }
    when (syncMode && syncQueued && !master && !childStarted && syncElapsed >= syncTarget) {
      // An already-passed target is an error, not permission to sample late.
      val late = syncElapsed =/= syncTarget
      outgoing := io.data
      received := Mux(late, "hffffffff".U, Cat(io.data(15, 0), syncReply(15, 0)))
      failed := late
      syncFault := late
      syncQueued := false.B
      childStarted := true.B
      multiElapsed := 0.U
      busy := false.B
      pending := true.B
      event(2, id, master, multi, io.data)
      io.port.event.bits.meta := Cat(Mux(late, 3.U(2.W), 2.U(2.W)), master, multi, id)
    }
    // A cancelled transfer cannot consume a coincident reply or raise an IRQ.
    when (io.cancel) {
      syncQueued := false.B
      anchorValid := false.B
      grantLimit := 0.U
      busy := false.B
      pending := false.B
      event(3, id, master, multi, outgoing)
    }
    val idleChild = multi && !master && !childStarted
    val transportFailed = ((!idleChild || syncMode) && guard === (guardCycles - 1).U) ||
      io.port.clear || !io.port.enabled || (multi && io.multiLost)
    when (transportFailed && !(multi && io.cancel)) {
      syncQueued := false.B
      anchorValid := false.B
      grantLimit := 0.U
      when (syncMode) { syncFault := true.B }
      received := "hffffffff".U
      failed := true.B
      pending := !idleChild
      busy := false.B
      event(3, id, master, multi, outgoing)
    }
  }
  when (io.port.clear) { io.port.event.valid := false.B }
}
