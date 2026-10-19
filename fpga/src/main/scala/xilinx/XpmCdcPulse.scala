package xilinx

import chisel3._

object XpmCdcPulse {
  /**
   * Synchronize a pulse from a source clock domain to the current clock domain.
   */
  def apply(sourceClock: Clock, sourcePulse: Bool): Bool = {
    val cdc = Module(new XpmCdcPulse)
    cdc.io.sourceClock := sourceClock
    cdc.io.sourcePulse := sourcePulse
    cdc.io.destPulse
  }
}

class XpmCdcPulse extends Module {
  val io = IO(new Bundle {
    /** Source clock */
    val sourceClock = Input(Clock())
    /** Source input pulse */
    val sourcePulse = Input(Bool())

    /** Destination output pulse */
    val destPulse = Output(Bool())
  })

  val cdc = Module(new xpm_cdc_pulse())
  cdc.io.dest_clk := clock.asBool
  io.destPulse := cdc.io.dest_pulse
  cdc.io.dest_rst := reset.asBool
  cdc.io.src_clk := io.sourceClock.asBool
  cdc.io.src_pulse := io.sourcePulse
  cdc.io.src_rst := reset.asBool
}

class xpm_cdc_pulse(
) extends ExtModule(Map(
  "DEST_SYNC_FF" -> 4,
  "INIT_SYNC_FF" -> 0,
  "REG_OUTPUT" -> 1,
  "RST_USED" -> 1,
  "SIM_ASSERT_CHK" -> 0,
)) {
  val io = FlatIO(new Bundle {
    val dest_clk = Input(Bool())
    val dest_pulse = Output(Bool())
    val dest_rst = Input(Bool())
    val src_clk = Input(Bool())
    val src_pulse = Input(Bool())
    val src_rst = Input(Bool())
  })
}

