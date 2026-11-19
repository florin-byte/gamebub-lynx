package net.gamebub.core.gameboy

import chisel3._
import lib.mem.MemoryInterface

/** Register decode shared by HandheldGameboy and its host-path integration test. */
object LinkRegisterRouter {
  def connect(host: MemoryInterface, regular: MemoryInterface, link: MemoryInterface): Unit = {
    val linkSelect = host.address >= 0x40.U && host.address <= 0x58.U
    for ((port, selected) <- Seq(regular -> !linkSelect, link -> linkSelect)) {
      port.address := host.address
      port.enable := host.enable && selected
      port.write := host.write
      port.dataWrite := host.dataWrite
      port.writeStrobe := host.writeStrobe
    }
    host.dataRead := Mux(linkSelect, link.dataRead, regular.dataRead)
    host.done := Mux(linkSelect, link.done, regular.done)
  }
}
