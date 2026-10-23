package net.gamebub.core

import chisel3._
import chisel3.util._
import lib.mem.MemoryInterface

/** Stock firmware actions, sharing the existing engine and SDRAM with on-demand file 2.
  * Only loads probe memory; an empty/invalid slot never reaches the emulation core.
  */
class StockSaveStateSlots(base: BigInt, stride: Int, sms: Boolean, timeoutCycles: Int) extends Module {
  val io = IO(new Bundle {
    val request = Input(Bool())
    val load = Input(Bool())
    val slot = Input(UInt(2.W))
    val available = Input(Bool())
    val blocked = Input(Bool())
    val gameId = Input(UInt(32.W))
    val start = Output(Bool())
    val isLoad = Output(Bool())
    val activeSlot = Output(UInt(2.W))
    val engineDone = Input(Bool())
    val engineOk = Input(Bool())
    val busy = Output(Bool())
    // bit 0 busy, 1 success, 2 error, 3 empty/invalid, 4 request rejected, 9:8 slot
    val status = Output(UInt(32.W))
    val mem = Flipped(new MemoryInterface(25, 32))
  })
  object S extends ChiselEnum { val idle, probe, validate, launch, waitDone = Value }
  val state = RegInit(S.idle)
  val selected = RegInit(0.U(2.W))
  val load = RegInit(false.B)
  val word = RegInit(0.U(2.W))
  val header = Reg(Vec(4, UInt(32.W)))
  val timer = RegInit(0.U(log2Ceil(timeoutCycles + 1).W))
  val success = RegInit(false.B)
  val error = RegInit(false.B)
  val empty = RegInit(false.B)
  val rejected = RegInit(false.B)
  io.busy := state =/= S.idle
  io.activeSlot := selected
  io.isLoad := load
  io.start := state === S.launch
  io.status := Cat(0.U(22.W), selected, 0.U(3.W), rejected, empty, error, success, io.busy)
  io.mem.enable := state === S.probe && !io.mem.done
  io.mem.write := false.B
  io.mem.address := (base.U(25.W) + selected * stride.U + (word << 2))(24, 0)
  io.mem.dataWrite := 0.U
  io.mem.writeStrobe := 0.U

  when (io.request) {
    when (io.busy) {
      rejected := true.B
    } .otherwise {
      selected := io.slot
      load := io.load
      success := false.B
      error := false.B
      empty := false.B
      rejected := false.B
      timer := 0.U
      word := 0.U
      when (!io.available || io.blocked) {
        error := true.B
        rejected := true.B
      } .otherwise {
        state := Mux(io.load, S.probe, S.launch)
      }
    }
  }
  when (io.busy) {
    timer := timer + 1.U
    when (timer === (timeoutCycles - 1).U) {
      state := S.idle
      success := false.B
      error := true.B
    } .otherwise {
      switch (state) {
        is (S.probe) {
          when (io.mem.done) {
            header(word) := io.mem.dataRead
            when (word === 3.U) { state := S.validate }
              .otherwise { word := word + 1.U }
          }
        }
        is (S.validate) {
          val valid = if (sms) {
            val old = header(1) === 16382.U
            (old || header(1) === 24574.U) &&
              header(2) === "h534D5331".U && (old || header(3) === io.gameId)
          } else { header(1) === 16640.U }
          when (valid) { state := S.launch } .otherwise {
            state := S.idle
            error := true.B
            empty := true.B
          }
        }
        is (S.launch) { state := S.waitDone }
        is (S.waitDone) {
          when (io.engineDone) {
            state := S.idle
            success := io.engineOk
            error := !io.engineOk
          }
        }
      }
    }
  }
}

/** Small standalone target for the Verilator protocol tests (no emulation engine). */
object SimStockSaveStateSlots extends App {
  val sms = args.contains("--sms")
  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(
    new StockSaveStateSlots(if (sms) 0x1000000 else 0x200000,
      if (sms) 0x18000 else 0x20000, sms, timeoutCycles = 1024),
    args.filterNot(_ == "--sms"), firtoolOpts = Array("--preserve-aggregate=1d-vec"))
}
