package gba.cpu

import chisel3._
import chisel3.util._

object AluOpcode extends ChiselEnum {
  /// Logical AND (Rd := Rn AND shifter_operand)
  val and = Value
  /// Logical XOR (Rd := Rn XOR shifter_operand)
  val eor = Value
  /// Subtract (Rd := Rn - shifter_operand)
  val sub = Value
  /// Reverse Subtract (Rd := shifter_operand - Rn)
  val rsb = Value
  /// Add (Rd := Rn + shifter_operand)
  val add = Value
  /// Add with Carry (Rd := Rn + shifter_operand + carry flag)
  val adc = Value
  /// Subtract with Carry (Rd := Rn - shifter_operand - NOT(carry flag))
  val sbc = Value
  /// Reverse subtract with Carry (Rd := shifter_operand - Rn - NOT(carry flag))
  val rsc = Value
  /// Test (update flags after (Rn AND shifter_operand))
  val tst = Value
  /// Test Equivalence (update flags after (Rn XOR shifter_operand))
  val teq = Value
  /// Compare (update flags after (Rn - shifter_operand))
  val cmp = Value
  /// Compare Negated (update flags after (Rn + shifter_operand))
  val cmn = Value
  /// Logical OR (Rd := Rn OR shifter_operand)
  val orr = Value
  /// Move (Rd := shifter_operand)
  val mov = Value
  /// Bit Clear (Rd := Rn AND NOT shifter_operand)
  val bic = Value
  /// Move Not (Rd := NOT shifter_operand)
  val mvn = Value
}

class Alu extends Module {
  val io = IO(new Bundle {
    /// Opcode
    val opcode = Input(AluOpcode())

    /// Operand A
    val a = Input(UInt(32.W))
    /// Operand B
    val b = Input(UInt(32.W))
    /// Flags in
    val flagIn = Input(new ConditionFlags)
    /// Shifter carry
    val shifterCarry = Input(Bool())

    /// Output
    val out = Output(UInt(32.W))
    /// Flags out
    val flagOut = Output(new ConditionFlags)
  })

  io.flagOut.n := io.out(31)
  io.flagOut.z := io.out === 0.U
  io.flagOut.c := io.shifterCarry
  io.flagOut.v := io.flagIn.v
  io.out := DontCare

  switch (io.opcode) {
    is (AluOpcode.mov) {
      io.out := io.b
    }
    is (AluOpcode.mvn) {
      io.out := ~io.b
    }
    is (AluOpcode.add, AluOpcode.cmn) {
      val temp = io.a +& io.b
      io.out := temp
      io.flagOut.c := temp(32)
      io.flagOut.v := !(io.a(31) ^ io.b(31)) && (io.a(31) ^ io.out(31))
    }
    is (AluOpcode.adc) {
      val temp = io.a +& io.b +& io.flagIn.c.asUInt
      io.out := temp
      io.flagOut.c := temp(32)
      io.flagOut.v := !(io.a(31) ^ io.b(31)) && (io.a(31) ^ io.out(31))
    }
    is (AluOpcode.sub, AluOpcode.cmp) {
      val temp = io.a -& io.b
      io.out := temp
      io.flagOut.c := !temp(32)
      io.flagOut.v := (io.a(31) ^ io.b(31)) && (io.a(31) ^ io.out(31))
    }
    is (AluOpcode.sbc) {
      val temp = io.a -& io.b -& (!io.flagIn.c).asUInt
      io.out := temp
      io.flagOut.c := !temp(32)
      io.flagOut.v := (io.a(31) ^ io.b(31)) && (io.a(31) ^ io.out(31))
    }
    is (AluOpcode.rsb) {
      val temp = io.b -& io.a
      io.out := temp
      io.flagOut.c := !temp(32)
      io.flagOut.v := (io.a(31) ^ io.b(31)) && (io.b(31) ^ io.out(31))
    }
    is (AluOpcode.rsc) {
      val temp = io.b -& io.a -& (!io.flagIn.c).asUInt
      io.out := temp
      io.flagOut.c := !temp(32)
      io.flagOut.v := (io.a(31) ^ io.b(31)) && (io.b(31) ^ io.out(31))
    }
    is (AluOpcode.and, AluOpcode.tst) {
      io.out := io.a & io.b
    }
    is (AluOpcode.eor, AluOpcode.teq) {
      io.out := io.a ^ io.b
    }
    is (AluOpcode.orr) {
      io.out := io.a | io.b
    }
    is (AluOpcode.bic) {
      io.out := io.a & (~io.b).asUInt
    }
  }
}
