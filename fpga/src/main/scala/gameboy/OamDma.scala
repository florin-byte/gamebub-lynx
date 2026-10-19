package gameboy

import chisel3._
import chisel3.util._

class OamDma extends Module {
  val io = IO(new PeripheralAccess {
    val clocker = Input(new Clocker)
    val active = Output(Bool())
    val dmaAddress = Output(UInt(16.W))
  })

  val sourceHigh = RegInit(0.U(8.W))
  val active = RegInit(false.B)
  // F-04: OAM DMA starts about 1 M-cycle after the write to $FF46 (real hardware).
  // `starting` arms the transfer; it becomes active on the next phiPulse.
  // Note: mooneye oam_dma_start still fails with this change (simulation, CGB boot ROM).
  val starting = RegInit(false.B)
  val counter = RegInit(0.U(8.W))
  io.active := active
  io.dmaAddress := Cat(sourceHigh, counter)

  io.valid := false.B
  io.dataRead := sourceHigh
  when (io.enabled && io.address === 0x46.U) {
    io.valid := true.B
    when (io.write) {
      // Begin DMA: arm it; see the start delay below (F-04).
      sourceHigh := io.dataWrite
      starting := true.B
    }
  }

  when (io.clocker.phiPulse) {
    when (starting) {
      // F-04: 1 M-cycle start delay; the transfer becomes active now.
      starting := false.B
      active := true.B
      counter := 0.U
    } .elsewhen (active) {
      when (counter === (160 - 1).U) {
        active := false.B
      } .otherwise {
        counter := counter + 1.U
      }
    }
  }
}