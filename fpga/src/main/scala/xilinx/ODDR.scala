package xilinx

import chisel3._

class ODDRWrapper(
    initial: Boolean,
) extends Module {
    val io = IO(new Bundle {
        val Q = Output(Bool())
        val D1 = Input(Bool())
        val D2 = Input(Bool())
    })

    val inner = Module(new ODDR(
        initial = if (initial) 1 else 0,
    ))
    io.Q := inner.io.Q
    inner.io.C := clock
    inner.io.CE := true.B
    inner.io.D1 := io.D1
    inner.io.D2 := io.D2
    if (initial) {
        inner.io.R := false.B
        inner.io.S := reset.asBool
    } else {
        inner.io.R := reset.asBool
        inner.io.S := false.B
    }
}

class ODDR(
    initial: Int,
) extends ExtModule(Map(
    "DDR_CLK_EDGE" -> "SAME_EDGE",
    "INIT" -> initial,
)) {
    val io = FlatIO(new Bundle {
        val Q = Output(Bool())
        val C = Input(Clock())
        val CE = Input(Bool())
        val D1 = Input(Bool())
        val D2 = Input(Bool())
        val R = Input(Bool())
        val S = Input(Bool())
    })
}
