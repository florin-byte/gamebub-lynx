package net.gamebub.core.gba

import chisel3._
import chisel3.util._
import gba.link.WirelessSioPort
import lib.mem.{MemoryInterface, RegisterMap}

/** Shared outer register routing: keep the full bridge ABI reachable from the host. */
object GbaLinkHostBridge {
  def route(host: MemoryInterface, regular: MemoryInterface, link: MemoryInterface): Unit = {
    val selected = host.address >= 0x60.U && host.address <= 0x9c.U
    for ((port, enabled) <- Seq(regular -> !selected, link -> selected)) {
      port.address := host.address
      port.enable := host.enable && enabled
      port.write := host.write
      port.writeStrobe := host.writeStrobe
      port.dataWrite := host.dataWrite
    }
    host.dataRead := Mux(selected, link.dataRead, regular.dataRead)
    host.done := Mux(selected, link.done, regular.done)
  }
}

/** Dedicated GBA register block, 0x60..0x9c. FIFO peek metadata and data are
  * stable until POP. Staged command data/id commit atomically at COMMAND.
  */
class GbaLinkHostBridge extends Module {
  val io = IO(new Bundle {
    val mem = new MemoryInterface(16, 32)
    val link = Flipped(new WirelessSioPort)
    val channelReset = Input(Bool())
    val coreRequest = Output(Bool())
  })
  val enabled = RegInit(false.B)
  val multiConfig = RegInit(0.U(6.W))
  val generation = RegInit(0.U(8.W))
  val overflow = RegInit(false.B)
  val fifo = Reg(Vec(16, UInt(96.W)))
  val head = RegInit(0.U(4.W))
  val tail = RegInit(0.U(4.W))
  val count = RegInit(0.U(5.W))
  val data = RegInit(0.U(32.W))
  val cycles = RegInit(0.U(32.W))
  val anchor = RegInit(0.U(28.W))
  val snapshotMeta = RegInit(0.U(32.W))
  val snapshotCycles = RegInit(0.U(32.W))
  val id = RegInit(0.U(29.W))
  val clear = WireDefault(io.channelReset)
  val pop = WireDefault(false.B)
  val completion = RegInit(false.B)
  when (io.mem.enable && io.mem.write) { completion := !completion }
    .otherwise { completion := false.B }
  def launch(en: Bool): Bool = en && !completion
  def write(fn: (Bool, UInt) => Unit): RegisterMap.Entry =
    RegisterMap.Entry(32, RegisterMap.ReadFn(), RegisterMap.WriteFn((en, d) => fn(launch(en), d)))
  io.link.enabled := enabled
  io.link.clear := clear
  io.link.command.bits.multiEnabled := multiConfig(0)
  io.link.command.bits.multiParent := multiConfig(1)
  io.link.command.bits.multiReady := multiConfig(2)
  io.link.command.bits.syncEnabled := multiConfig(3)
  io.link.command.bits.grantEnabled := multiConfig(4)
  io.link.command.bits.pairFromHost := multiConfig(5)
  io.link.command.bits.grant := false.B
  io.link.command.bits.anchor := anchor
  io.link.command.bits.cycles := cycles
  io.link.command.bits.sync := false.B
  io.link.command.valid := false.B
  io.link.command.bits.id := id
  io.link.command.bits.data := data
  io.link.command.bits.error := false.B
  io.link.command.bits.begin := false.B
  io.coreRequest := count =/= 0.U
  val status = (generation << 16) | (io.link.multiMode.asUInt << 11) |
    (io.link.busy.asUInt << 10) | (overflow.asUInt << 9) | count
  io.mem <> RegisterMap(16, 32, Seq(
    // CONFIG read bit 28 (GB-117): SAMPLED at 0x9c and CONFIG bit 6 (pair from host).
    0x60 -> RegisterMap.Entry.r("h57470101".U(32.W)),
    0x64 -> RegisterMap.Entry(32, RegisterMap.ReadFn(Cat(15.U(4.W), 0.U(21.W), multiConfig, enabled)),
      RegisterMap.WriteFn((en, d) => {
        when (launch(en)) {
          enabled := d(0)
          multiConfig := d(6, 1)
          when (enabled =/= d(0)) { clear := true.B }
        }
      })),
    0x68 -> RegisterMap.Entry.r(status),
    0x6c -> RegisterMap.Entry.r(Mux(count =/= 0.U, fifo(head)(63, 32), 0.U)),
    0x70 -> RegisterMap.Entry.r(Mux(count =/= 0.U, fifo(head)(31, 0), 0.U)),
    0x74 -> write((en, d) => { pop := en && d(0) }),
    0x78 -> write((en, d) => { when (en) { data := d } }),
    0x7c -> write((en, d) => { when (en) { id := d(28, 0) } }),
    0x80 -> write((en, d) => {
      io.link.command.valid := en && d(0) && enabled && !clear
      io.link.command.bits.error := d(1)
      io.link.command.bits.begin := d(2)
      io.link.command.bits.sync := d(3)
      io.link.command.bits.grant := d(4)
    }),
    0x88 -> RegisterMap.Entry.r(Mux(count =/= 0.U, fifo(head)(95, 64), 0.U)),
    0x8c -> write((en, d) => { when (en) { cycles := d } }),
    // Explicit snapshot command freezes all fields on one FPGA edge.
    0x90 -> RegisterMap.Entry(32, RegisterMap.ReadFn(snapshotMeta),
      RegisterMap.WriteFn((en, d) => {
        when (launch(en) && d(0)) {
          snapshotMeta := Cat(io.link.progress.valid, io.link.progress.running,
            0.U(2.W), io.link.progress.anchor)
          snapshotCycles := io.link.progress.cycles
        }
      })),
    0x94 -> RegisterMap.Entry.r(snapshotCycles),
    0x98 -> write((en, d) => { when (en) { anchor := d(27, 0) } }),
    0x9c -> RegisterMap.Entry.r(io.link.sampled),
    0x84 -> write((en, d) => { when (en && d(0)) { clear := true.B; enabled := false.B; multiConfig := 0.U } }),
  ))
  val remove = pop && count =/= 0.U
  val insert = io.link.event.valid && (count < 16.U || remove)
  when (remove) { head := head + 1.U }
  when (insert) {
    fifo(tail) := Cat(io.link.event.bits.cycles, io.link.event.bits.meta, io.link.event.bits.data)
    tail := tail + 1.U
  }
  count := count + insert.asUInt - remove.asUInt
  when (io.link.event.valid && !insert) { overflow := true.B }
  when (clear) {
    head := 0.U; tail := 0.U; count := 0.U; overflow := false.B
    generation := generation + 1.U
    io.link.command.valid := false.B
  }
  when (io.channelReset) { enabled := false.B; multiConfig := 0.U }
}
