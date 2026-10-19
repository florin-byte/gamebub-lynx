package gba.cpu

import chisel3._
import chisel3.util._

object ShiftKind extends ChiselEnum {
  val LogicalShiftLeft = Value
  val LogicalShiftRight = Value
  val ArithmeticShiftRight = Value
  val RotateRight = Value
  val RotateRightWithExtend = Value
}

class Shifter extends Module {
  val io = IO(new Bundle {
    val in = Input(UInt(32.W))
    val shiftKind = Input(ShiftKind())
    val shiftAmount = Input(UInt(6.W))

    val out = Output(UInt(32.W))

    val carryIn = Input(Bool())
    val carryOut = Output(Bool())

    /// Used for data processing with register shift. If set, will latch the bottom 8-bits
    /// of the operand, allowing it to be used as the shiftAmount in another cycle.
    val latchShift = Input(Bool())
    /// Used for data processing with register shift. If set, uses the latched shift amount.
    val useLatchedShift = Input(Bool())
  })

  // Shift latching for shift-by-register
  val latchedShift = Reg(UInt(8.W))
  when (io.latchShift) {
    latchedShift := io.in
  }
  val operand = io.in
  val shiftAmount = Mux(io.useLatchedShift, latchedShift, io.shiftAmount)

  io.out := DontCare
  io.carryOut := DontCare
  switch (io.shiftKind) {
    is (ShiftKind.LogicalShiftLeft) {
      when (shiftAmount === 0.U) {
        io.out := operand
        io.carryOut := io.carryIn
      } .elsewhen (shiftAmount < 32.U) {
        io.out := operand << shiftAmount
        io.carryOut := operand((32.U - shiftAmount)(4, 0))
      } .elsewhen (shiftAmount === 32.U) {
        io.out := 0.U
        io.carryOut := operand(0)
      } .otherwise {
        io.out := 0.U
        io.carryOut := 0.U
      }
    }
    is (ShiftKind.LogicalShiftRight) {
      when (shiftAmount === 32.U) {
        io.out := 0.U
        io.carryOut := operand(31)
      } .elsewhen (shiftAmount === 0.U) {
        io.out := operand
        io.carryOut := io.carryIn
      } .elsewhen (shiftAmount < 32.U) {
        io.out := operand >> shiftAmount
        io.carryOut := operand((shiftAmount - 1.U)(4, 0))
      } .otherwise {
        io.out := 0.U
        io.carryOut := 0.U
      }
    }
    is (ShiftKind.ArithmeticShiftRight) {
      // With immediate, right shift of 0 is actually shift of 32
      when (shiftAmount >= 32.U) {
        when (operand(31) === 0.U) {
          io.out := 0.U
        } .otherwise {
          io.out := "hFFFFFFFF".U(32.W)
        }
        io.carryOut := operand(31)
      } .elsewhen (shiftAmount === 0.U) {
        io.out := operand
        io.carryOut := io.carryIn
      } .otherwise {
        // TODO: verify that making this sint makes it arithmetic shift
        io.out := (operand.asSInt >> shiftAmount).asUInt
        io.carryOut := operand((shiftAmount - 1.U)(4, 0))
      }
    }
    is (ShiftKind.RotateRight) {
      val amount = shiftAmount(4, 0)
      when (shiftAmount === 0.U) {
        io.out := operand
        io.carryOut := io.carryIn
      } .elsewhen (amount === 0.U) {
        io.out := operand
        io.carryOut := operand(31)
      } .otherwise {
        io.out := operand.rotateRight(amount)
        io.carryOut := operand(amount - 1.U)
      }
    }
    is (ShiftKind.RotateRightWithExtend) {
      // With immediate, shift of 0 is actually "rotate right with extend"
      io.out := Cat(io.carryIn, operand >> 1)
      io.carryOut := operand(0)
    }
  }
}
