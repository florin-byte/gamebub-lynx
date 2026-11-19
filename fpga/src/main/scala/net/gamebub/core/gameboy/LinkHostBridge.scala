package net.gamebub.core.gameboy

import chisel3._
import chisel3.util._
import gameboy.{WirelessLink, WirelessLinkIo}
import lib.mem.{MemoryInterface, RegisterMap}

/** System-clock bridge: the platform's existing SPI memory CDC precedes this map.
  * ABI 2: opcode[10:8], byte[7:0]; no command mailbox (accepted in one cycle).
  */
class LinkHostBridge extends Module {
  val io = IO(new Bundle {
    val mem = new MemoryInterface(16, 32)
    val link = Flipped(new WirelessLinkIo)
    val coreRequest = Output(Bool())
    val channelReset = Input(Bool())
  })
  val config = RegInit(0.U(4.W))
  val generation = RegInit(0.U(8.W))
  val transferId = RegInit(0.U(8.W))
  val masterStart = io.link.event.valid && io.link.event.bits(10, 8) === WirelessLink.Master.U
  val advance = masterStart || io.link.guardExpired
  val eventId = transferId + advance.asUInt
  when (advance) { transferId := eventId }
  val overflow = RegInit(false.B)
  val events = Reg(Vec(16, UInt(32.W)))
  val head = RegInit(0.U(4.W))
  val tail = RegInit(0.U(4.W))
  val count = RegInit(0.U(5.W))
  val pop = WireDefault(false.B)
  val clear = WireDefault(false.B)
  val clearError = WireDefault(false.B)
  val setConfig = WireDefault(false.B)
  val newConfig = WireDefault(config)
  io.link.config := config
  io.link.clear := clear || io.channelReset
  io.link.command.valid := false.B
  io.link.command.bits := 0.U
  io.coreRequest := count =/= 0.U

  // CAPS: 'WL' identifier[31:16], ABI[15:8]=2,
  // caps[7:0]: byte exchange, barrier, standing, fast stall, guard = 0x1f.
  val caps = "h574c021f".U(32.W)
  // STATUS: count[4:0], busy[8]=0, overflow[9], held[10], armed[11], generation[23:16], transfer id[31:24].
  val status = WireDefault(0.U(32.W))
  status := (transferId << 24) | (generation << 16) | (io.link.armed.asUInt << 11) |
    (io.link.held.asUInt << 10) | (overflow.asUInt << 9) | count
  val peek = Mux(count =/= 0.U, events(head), 0.U)
  def writeOnly(fn: (Bool, UInt) => Unit): RegisterMap.Entry =
    RegisterMap.Entry(32, RegisterMap.ReadFn(), RegisterMap.WriteFn(fn))
  // SpiReceiverFifo holds a request for launch and completion, even when
  // done is combinational. Accept each write only on its launch cycle.
  // Consecutive FIFO words may have no idle cycle between them.
  val writeCompletion = RegInit(false.B)
  when (io.mem.enable && io.mem.write) {
    writeCompletion := !writeCompletion
  } .otherwise {
    writeCompletion := false.B
  }
  def launch(en: Bool): Bool = en && !writeCompletion
  val entries: Seq[(Int, RegisterMap.Entry)] = Seq(
    0x40 -> RegisterMap.Entry.r(caps),
    0x44 -> RegisterMap.Entry(32, RegisterMap.ReadFn(config), RegisterMap.WriteFn((en, data) => {
      when (launch(en)) { setConfig := true.B; newConfig := data(3, 0) & "b1101".U }
    })),
    0x48 -> RegisterMap.Entry.r(status),
    0x4C -> RegisterMap.Entry.r(peek),
    0x50 -> writeOnly((en, data) => { pop := launch(en) && data(0) }),
    0x54 -> writeOnly((en, data) => {
      when (launch(en)) {
        when (data(10, 8) === WirelessLink.Config.U) {
          setConfig := true.B
          newConfig := data(3, 0) & "b1101".U
        } .otherwise {
          val verdict = data(10, 8) === WirelessLink.MasterReply.U ||
            data(10, 8) === WirelessLink.MasterDrop.U
          io.link.command.valid := !verdict ||
            data(31, 24) === transferId
          io.link.command.bits := data(10, 0)
        }
      }
    }),
    // CONTROL bit 0 resets channel/config/FIFO and advances generation;
    // bit 1 clears the sticky overflow. Channel reset also clears overflow.
    0x58 -> writeOnly((en, data) => {
      clear := launch(en) && data(0)
      clearError := launch(en) && data(1)
    }),
  )

  io.mem <> RegisterMap(16, 32, entries)

  val remove = pop && count =/= 0.U
  // Admit each lane against the space remaining after the earlier lane.
  // A full FIFO can accept one event when popped, but never overwrite a second.
  val lanes = Seq(io.link.event, io.link.eventNext)
  val inserted = Wire(Vec(3, UInt(2.W)))
  inserted(0) := 0.U
  val rejected = Wire(Vec(2, Bool()))
  for (i <- lanes.indices) {
    val lane = lanes(i)
    val occupancy = count +& inserted(i)
    val hint = lane.bits(10, 8) === WirelessLink.Armed.U ||
      lane.bits(10, 8) === WirelessLink.Disarm.U
    val wanted = lane.valid && !(hint && occupancy >= 12.U)
    val insert = wanted && occupancy < (16.U(6.W) + remove.asUInt)
    val index = (tail + inserted(i))(3, 0)
    when (insert) { events(index) := (eventId << 24) | lane.bits }
    inserted(i + 1) := inserted(i) + insert.asUInt
    rejected(i) := wanted && !insert
  }
  when (remove) { head := head + 1.U }
  tail := tail + inserted(2)
  count := count + inserted(2) - remove.asUInt
  when (clearError) { overflow := false.B }
  when (rejected.asUInt.orR) { overflow := true.B }
  when (setConfig) { config := newConfig }
  // Changing enable starts a new session; L3 must discard its old CWL verdicts.
  when (clear || io.channelReset || (setConfig && config(0) =/= newConfig(0))) {
    io.link.clear := true.B
    head := 0.U
    tail := 0.U
    count := 0.U
    overflow := false.B
    generation := generation + 1.U
    when (clear || io.channelReset) { config := 0.U }
    io.link.command.valid := false.B
  }
}
