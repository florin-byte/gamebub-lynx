package gameboy

import chisel3._
import chisel3.util._

/** Byte-level CWL2 interface. Events and commands encode opcode[10:8], byte[7:0].
  * Configuration bits: enable=0, write barrier/fast stall=2, standing offers=3.
  * Event order within a cycle: CPU write first, slave response second. The
  * compact lanes are event, then eventNext (valid only when both sources fire).
  * Possible pairs (the byte belongs to each source, not the final SB value):
  *   ABANDON -> SLAVE_NAK / SLAVE_NAK_STALLED
  *   MASTER -> SLAVE_NAK
  *   ARMED -> SLAVE_DONE / SLAVE_NAK
  *   DISARM -> SLAVE_NAK
  * ARMED includes SB updates while armed and SC external-clock starts. A standing
  * offer can produce the slave response without a new command. CPU write events
  * are mutually exclusive; slave responses are mutually exclusive. No other
  * source emits events. Guard expiry has a separate pulse, so it cannot lose
  * either event lane. ABI 2 exposes a transfer id in STATUS/EVENT/COMMAND[31:24].
  * The bridge advances it on MASTER and guard expiry; queued MASTER events carry
  * their new id. Reply/drop commands must match the live id atomically. Firmware
  * invalidates its master transaction on an id change, including guard expiry,
  * before starting the next MASTER. Channel generation remains independent.
  * Clear/disable suppress both lanes; session reset discards the entire FIFO.
  * Clear completes a normal held master with FF + IRQ, drops a parked master
  * silently, and discards its parked write. The core retains a clear-time IRQ
  * until the CPU can sample it (including fast-clock stalls).
  * The bridge admits lanes in order, retaining its 16 entries and sticky decisive
  * overflow. ARMED/DISARM hints are discarded at occupancy >= 12 (including an
  * earlier accepted lane, before the concurrent pop); a pop frees one slot for
  * decisive events. Rejected decisive lanes each set overflow, never overwrite.
  * No send-ahead or physical-clock CDC is implemented here.
  */
object WirelessLink {
  val MasterReply = 1
  val SlaveXfer = 2
  val Config = 3
  val MasterDrop = 4
  val SlaveWithdraw = 6
  val Armed = 1
  val Disarm = 2
  val Master = 3
  val SlaveDone = 4
  val SlaveNak = 5
  val Abandon = 6
  val SlaveNakStalled = 7
}

class WirelessLinkIo extends Bundle {
  val config = Input(UInt(4.W))
  val clear = Input(Bool())
  val command = Flipped(Valid(UInt(11.W)))
  val event = Valid(UInt(11.W))
  val eventNext = Valid(UInt(11.W))
  val stall = Output(Bool())
  val guardExpired = Output(Bool())
  val held = Output(Bool())
  val armed = Output(Bool())
}
