package net.gamebub.core.lynx

import _root_.circt.stage.ChiselStage

/** SystemVerilog of LynxCore for the Verilator simulation (scripts/rev2/lynx_test_sim.sh core). */
object SimLynx extends App {
  ChiselStage.emitSystemVerilogFile(
    new LynxCore,
    args,
    firtoolOpts = Array("--preserve-aggregate=1d-vec"),
  )
}
