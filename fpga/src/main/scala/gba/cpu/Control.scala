package gba.cpu

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import gba.mem.{BusAccessWidth, BusProtectionType}
import lib.log.Logger

object PcNext extends ChiselEnum {
  val Same = Value
  val Incrementer = Value
}

object AddressSource extends ChiselEnum {
  val Same = Value
  val Incrementer = Value
  val Pc = Value
  val Alu = Value
  val Immediate = Value
}

object BusBValue extends ChiselEnum {
  val RegisterB = Value
  val Immediate = Value
  val MemReadData = Value
  val Cpsr = Value
  val Spsr = Value
  val MultiplyLo = Value
  val MultiplyHi = Value
}

object ExceptionKind extends ChiselEnum {
  val None = Value
  val Reset = Value
  val UndefinedInstruction = Value
  val SoftwareInterrupt = Value
  val PrefetchAbort = Value
  val DataAbort = Value
  val Irq = Value
  val Fiq = Value
}

class ControlSignals extends Bundle {
  /// True to start advance the fetch/decode stages of the pipeline.
  val advancePipeline = Bool()
  val flushPipeline = Bool()
  val startException = Bool()

  val pcNext = PcNext()
  val addressSource = AddressSource()

  val regBankMode = CpuMode()
  val regReadA = UInt(4.W)
  val regReadB = UInt(4.W)
  val regReadC = UInt(4.W)
  val regWriteIndex = UInt(4.W)
  val regWriteEnable = Bool()
  val regUserReadC = Bool()
  val regUserWrite = Bool()
  val cpsrUpdateCond = Bool()
  val cpsrUpdateThumb = Bool()
  val cpsrUpdateFields = UInt(2.W)
  val spsrUpdateFields = UInt(2.W)
  val cpsrRestore = Bool()
  val cpsrFromMultiply = Bool()

  val busB = BusBValue()
  val immediate = UInt(32.W)

  val aluOpcode = AluOpcode()
  val aluOutAlign4 = Bool()
  val aluInAAlign4 = Bool()
  val shiftKind = ShiftKind()
  val shiftImmediate = UInt(6.W)
  val shiftDoLatch = Bool()
  val shiftUseLatched = Bool()
  val shiftByAddressAlign = Bool()

  val multiplyEnable = Bool()
  val multiplySigned = Bool()
  val multiplyLoadAccumulator = Bool()
  val multiplyAccumulate = Bool()
  val multiplyLong = Bool()

  val memRequest = Bool()
  val memSequential = Bool()
  val memWrite = Bool()
  val memWidth = BusAccessWidth()
  val memProt = new BusProtectionType
  val memLock = Bool()
  val latchMemReadData = Bool()
  val latchMemWriteData = Bool()
  val memReadDataSigned = Bool()
  val incrementerForceWord = Bool()
}

/// Control unit
class Control extends Module {
  val io = IO(new Bundle {
    /// Global enable
    val enable = Input(Bool())

    /// Control signals
    val signals = Output(new ControlSignals)
    /// Next instruction from the decoder
    val nextInstruction = Input(new DecodedInstruction)
    /// Current program status register
    val currentStatus = Input(new ProgramStatusRegister)
    /// Next program status register
    val nextStatus = Input(new ProgramStatusRegister)
    /// Whether the multiplier is finished
    val multiplierDone = Input(Bool())

    /// Active-high fast interrupt request
    val fiq = Input(Bool())
    /// Active-high interrupt request
    val irq = Input(Bool())
  })
  val logger = Logger("cpu", enable = io.enable)
  val control = io.signals

  val instruction = RegInit((new DecodedInstruction).Lit(
    _.condition -> Condition.Al,
    _.kind -> InstructionKind.Exception,
    _.opcode -> ExceptionKind.Reset.litValue.U,
  ))
  val stage = RegInit(0.U(3.W))
  val nextStage = WireDefault(stage)
  val counter = Reg(UInt(5.W)) // Counter used for LDM/STM
  val nextCounter = WireDefault(counter)
  val dispatch = WireDefault(false.B)
  when (io.enable) {
    stage := nextStage
    counter := nextCounter
    when (dispatch) {
      stage := 0.U
      when (io.nextInstruction.condition === Condition.Nv) {
        // The intention is to allow the pipeline refill to complete before allowing
        // a FIQ or IRQ to occur. However, an instruction could also (illegally)
        // have a "Nv" condition code, which would delay IRQ.
        // TODO: should this use a separate signal to determine whether to handle IRQ?
        logger.debug("dispatch Nv")
        instruction := io.nextInstruction
      } .elsewhen (io.fiq && !io.currentStatus.fiqDisable) {
        instruction.kind := InstructionKind.Exception
        instruction.opcode := ExceptionKind.Fiq.asUInt
        instruction.condition := Condition.Al
      } .elsewhen (io.irq && !io.currentStatus.irqDisable) {
        instruction.kind := InstructionKind.Exception
        instruction.opcode := ExceptionKind.Irq.asUInt
        instruction.condition := Condition.Al
      } .otherwise {
        instruction := io.nextInstruction

        // Debug output of each instruction executed
        when (io.nextInstruction.debugThumb) {
          logger.info(cf"${io.nextInstruction.debugAddress}%x:  ${io.nextInstruction.debugRaw(15, 0)}%x")
        } .otherwise {
          logger.info(cf"${io.nextInstruction.debugAddress}%x:  ${io.nextInstruction.debugRaw}%x")
        }
      }
    }
  }
  val execute = Control.evaluateCondition(instruction.condition, io.currentStatus.cond)
  val nextThumb = io.nextStatus.thumb

  control.advancePipeline := false.B
  control.flushPipeline := false.B
  control.startException := false.B
  control.pcNext := PcNext.Same
  control.addressSource := AddressSource.Same
  control.regBankMode := io.currentStatus.mode
  control.regReadA := DontCare
  control.regReadB := DontCare
  control.regReadC := DontCare
  control.regWriteIndex := DontCare
  control.regWriteEnable := false.B
  control.regUserReadC := false.B
  control.regUserWrite := false.B
  control.cpsrUpdateCond := false.B
  control.cpsrUpdateThumb := false.B
  control.cpsrUpdateFields := 0.U
  control.spsrUpdateFields := 0.U
  control.cpsrRestore := false.B
  control.cpsrFromMultiply := false.B
  control.busB := DontCare
  control.immediate := DontCare
  control.aluOpcode := DontCare
  control.aluOutAlign4 := false.B
  control.aluInAAlign4 := false.B
  control.shiftKind := ShiftKind.LogicalShiftLeft
  control.shiftImmediate := 0.U
  control.shiftDoLatch := false.B
  control.shiftUseLatched := false.B
  control.shiftByAddressAlign := false.B
  control.multiplyEnable := false.B
  control.multiplySigned := DontCare
  control.multiplyLoadAccumulator := false.B
  control.multiplyAccumulate := DontCare
  control.multiplyLong := DontCare
  control.memWrite := false.B
  control.memWidth := DontCare
  control.memRequest := false.B
  control.memSequential := false.B
  control.memProt.privileged := false.B // TODO
  control.memProt.data := false.B
  control.memLock := false.B
  control.latchMemReadData := false.B
  control.latchMemWriteData := false.B
  control.memReadDataSigned := false.B
  control.incrementerForceWord := false.B

  when (io.enable) {
    logger.debug(cf"Execute [${instruction.condition} -> ${execute}] ${instruction.kind} ${stage}")
  }
  when (execute) {
    switch (instruction.kind) {
      is (InstructionKind.Exception) {
        val kind = suppressEnumCastWarning { instruction.opcode(2, 0).asTypeOf(ExceptionKind()) }
        val newAddress = MuxLookup(kind, 0.U)(Seq(
          ExceptionKind.Reset -> 0.U,
          ExceptionKind.UndefinedInstruction -> 0x4.U,
          ExceptionKind.SoftwareInterrupt -> 0x8.U,
          ExceptionKind.PrefetchAbort -> 0xC.U,
          ExceptionKind.DataAbort -> 0x10.U,
          ExceptionKind.Irq -> 0x18.U,
          ExceptionKind.Fiq -> 0x1C.U,
        ))
        val newMode = MuxLookup(kind, CpuMode.Supervisor)(Seq(
          ExceptionKind.Reset -> CpuMode.Supervisor,
          ExceptionKind.UndefinedInstruction -> CpuMode.Undefined,
          ExceptionKind.SoftwareInterrupt -> CpuMode.Supervisor,
          ExceptionKind.PrefetchAbort -> CpuMode.Abort,
          ExceptionKind.DataAbort -> CpuMode.Abort,
          ExceptionKind.Irq -> CpuMode.Irq,
          ExceptionKind.Fiq -> CpuMode.Fiq,
        ))
        val entryThumb = Reg(Bool())

        switch (stage) {
          is (0.U) {
            when (io.enable) {
              logger.info(cf"Exception! ${kind}")
              entryThumb := io.currentStatus.thumb
            }
            flushPipeline()
            dispatch := false.B

            // Construct forced address
            control.immediate := newAddress
            control.addressSource := AddressSource.Immediate

            // Change mode, set ARM mode, set I high.
            // In Reset and Fiq, set F high too.
            // Move CPSR -> (new) SPSR
            control.regBankMode := newMode
            control.startException := true.B

            // Move PC -> (new) LR
            control.regReadB := 15.U
            control.busB := BusBValue.RegisterB
            control.aluOpcode := AluOpcode.mov
            control.regWriteIndex := 14.U
            control.regWriteEnable := true.B

            advanceStage()
          }
          is (1.U) {
            // Modify return address (to facilitate return):
            // r14 is currently set to (next instruction to be executed + 2i)
            // IRQ: set it to next instruction + 4  (-2i + 4)
            // SWI/undef: set it to next instruction after SWI (+ i)
            control.aluOpcode := AluOpcode.sub
            control.regReadA := 14.U
            when (kind === ExceptionKind.SoftwareInterrupt || kind === ExceptionKind.UndefinedInstruction) {
              control.immediate := Mux(entryThumb, 2.U, 4.U)
            } .otherwise {
              control.immediate := Mux(entryThumb, 0.U, 4.U)
            }
            control.busB := BusBValue.Immediate
            control.regWriteIndex := 14.U
            control.regWriteEnable := true.B

            nextInstruction()
            dispatch := false.B
            advanceStage()
          }
          is (2.U) {
            // Refill instruction pipeline.
            nextInstruction()
          }
        }
      }
      is (InstructionKind.DataProcessingImm) {
        // Rd := Alu(Rn, Imm)
        when (instruction.flags(1) === 0.U) {
          control.shiftKind := ShiftKind.RotateRight
          control.shiftImmediate := instruction.immediate(11, 8) << 1
          control.immediate := instruction.immediate(7, 0)
        } .otherwise {
          // Special 11-bit signed immediate, used for Thumb BL (long)
          control.immediate := Cat(Fill(9, instruction.immediate(10)), instruction.immediate(10, 0), 0.U(12.W))
        }
        control.busB := BusBValue.Immediate
        finishDataProcessing()
      }
      is (InstructionKind.DataProcessingImmShift) {
        // Rd := Alu(Rn, Rm shift Imm)
        val shiftImmediate = instruction.immediate(6, 2)
        val shiftKind = suppressEnumCastWarning { instruction.immediate(1, 0).asTypeOf(ShiftKind()) }
        control.regReadB := instruction.regM
        control.busB := BusBValue.RegisterB
        control.shiftKind := shiftKind
        control.shiftImmediate := shiftImmediate
        when (shiftImmediate === 0.U) {
          switch(shiftKind) {
            // Right shift [both] of 0 is actually shift of 32
            is (ShiftKind.LogicalShiftRight, ShiftKind.ArithmeticShiftRight) {
              control.shiftImmediate := 32.U
            }
            // Rotate right of 0 is actually rotate right with extend
            is (ShiftKind.RotateRight) {
                control.shiftKind := ShiftKind.RotateRightWithExtend
            }
          }
        }
        finishDataProcessing()
      }
      is (InstructionKind.DataProcessingRegShift) {
        switch (stage) {
          is (0.U) {
            control.regReadB := instruction.regS
            control.shiftDoLatch := true.B
            beginPrefetch()
            advanceStage()
          }
          is (1.U) {
            // Rd := Alu(Rn, Rm shift Imm)
            val shiftKind = suppressEnumCastWarning { instruction.immediate(1, 0).asTypeOf(ShiftKind()) }
            control.regReadB := instruction.regM
            control.busB := BusBValue.RegisterB
            control.shiftKind := shiftKind
            control.shiftUseLatched := true.B
            finishDataProcessing(didPrefetch = true)
          }
        }
      }
      is (InstructionKind.Load) {
        val width = suppressEnumCastWarning { instruction.opcode(1, 0).asTypeOf(BusAccessWidth()) }
        val flag_user = instruction.flags(5) // TODO "LDRT"
        val flag_signed = instruction.flags(4)
        val flag_immediate = instruction.flags(3)
        val flag_preindex = instruction.flags(2)
        val flag_add = instruction.flags(1)
        val flag_writeback = instruction.flags(0)

        switch (stage) {
          is (0.U) {
            // Calculate address, initiate access
            when (flag_preindex) {
              setAluLoadStoreAddress()
            } .otherwise {
              control.regReadB := instruction.regN
              control.busB := BusBValue.RegisterB
              control.aluOpcode := AluOpcode.mov
            }
            control.addressSource := AddressSource.Alu

            control.memRequest := true.B
            control.memSequential := false.B
            control.memWrite := false.B
            control.memWidth := width
            control.memProt.data := true.B
            control.pcNext := PcNext.Incrementer
            advanceStage()
          }
          is (1.U) {
            // Wait for access, perform address writeback
            when (flag_writeback) {
              setAluLoadStoreAddress()
              control.regWriteIndex := instruction.regN
              control.regWriteEnable := true.B
            }

            control.latchMemReadData := true.B
            beginPrefetch()
            control.addressSource := AddressSource.Pc
            control.pcNext := PcNext.Same
            advanceStage()
          }
          is (2.U) {
            // Write the loaded data to the register.
            control.busB := BusBValue.MemReadData
            control.memReadDataSigned := flag_signed
            control.aluOpcode := AluOpcode.mov
            control.shiftKind := Mux(flag_signed, ShiftKind.ArithmeticShiftRight, ShiftKind.RotateRight)
            control.shiftByAddressAlign := true.B
            control.regWriteIndex := instruction.regD
            control.regWriteEnable := true.B
            when (instruction.regD === 15.U) {
              flushPipeline()
            } .otherwise {
              completePrefetch()
            }
          }
        }
      }
      is (InstructionKind.Store) {
        val width = suppressEnumCastWarning { instruction.opcode(1, 0).asTypeOf(BusAccessWidth()) }
        val flag_user = instruction.flags(5) // TODO "STRT"
        val flag_immediate = instruction.flags(3)
        val flag_preindex = instruction.flags(2)
        val flag_add = instruction.flags(1)
        val flag_writeback = instruction.flags(0)

        switch (stage) {
          is (0.U) {
            // Calculate address, initiate access
            // Note: if base addr regN is the same as store regD, the stored data is *pre* writeback
            when (flag_preindex) {
              setAluLoadStoreAddress()
            } .otherwise {
              control.regReadB := instruction.regN
              control.busB := BusBValue.RegisterB
              control.aluOpcode := AluOpcode.mov
            }
            control.addressSource := AddressSource.Alu
            control.latchMemWriteData := true.B

            control.memRequest := true.B
            control.memSequential := false.B
            control.memWrite := true.B
            control.memWidth := width
            control.memProt.data := true.B
            control.pcNext := PcNext.Incrementer
            advanceStage()
          }
          is (1.U) {
            // Base modification
            when (flag_writeback) {
              setAluLoadStoreAddress()
              control.regWriteIndex := instruction.regN
              control.regWriteEnable := true.B
            }

            // XXX: is there a way to do this without adding a third register read port?
            control.regReadC := instruction.regD

            nextInstruction()
            control.memRequest := true.B
            control.memSequential := false.B
            control.pcNext := PcNext.Same
            control.addressSource := AddressSource.Pc
          }
        }
      }
      is (InstructionKind.Swap) {
        val width = suppressEnumCastWarning { instruction.opcode(1, 0).asTypeOf(BusAccessWidth()) }

        switch (stage) {
          is (0.U) {
            // Start load from Rn
            control.regReadB := instruction.regN
            control.busB := BusBValue.RegisterB
            control.aluOpcode := AluOpcode.mov
            control.addressSource := AddressSource.Alu
            control.memRequest := true.B
            control.memSequential := false.B
            control.memWrite := false.B
            control.memWidth := width
            control.memProt.data := true.B
            control.pcNext := PcNext.Incrementer
            advanceStage()
          }
          is (1.U) {
            // Latch loaded data, start store to Rn (with Rm)
            control.latchMemReadData := true.B
            // XXX: this *could* go over bus B
            control.regReadC := instruction.regM
            control.latchMemWriteData := true.B

            control.addressSource := AddressSource.Same
            control.memRequest := true.B
            control.memSequential := false.B
            control.memWrite := true.B
            control.memWidth := width
            control.memProt.data := true.B
            control.memLock := true.B
            advanceStage()
          }
          is (2.U) {
            // Wait for the store... start merged I-S cycle
            beginPrefetch()
            control.addressSource := AddressSource.Pc
            control.pcNext := PcNext.Same
            control.memLock := true.B
            advanceStage()
          }
          is (3.U) {
            // Write the loaded data to the register.
            control.busB := BusBValue.MemReadData
            control.aluOpcode := AluOpcode.mov
            control.shiftKind := ShiftKind.RotateRight
            control.shiftByAddressAlign := true.B
            control.regWriteIndex := instruction.regD
            control.regWriteEnable := true.B
            completePrefetch()
          }
        }
      }
      is (InstructionKind.ArmBranch) {
        val flag_link = instruction.flags(0)
        val flag_exchange = instruction.flags(1)
        val flag_thumb_imm = instruction.flags(2)

        switch (stage) {
          is (0.U) {
            when (flag_exchange) {
              control.regReadA := instruction.regM
              control.busB := BusBValue.Immediate
              control.immediate := 1.U
              control.aluOpcode := AluOpcode.bic
              control.cpsrUpdateThumb := true.B
            } .otherwise {
              control.regReadA := 15.U // PC
              control.busB := BusBValue.Immediate
              when (!flag_thumb_imm) {
                control.immediate := Cat(
                  Fill(6, instruction.immediate(23)),
                  instruction.immediate(23, 0),
                  "b00".U(2.W)
                )
              } .otherwise {
                control.immediate := Cat(
                  Fill(20, instruction.immediate(10)),
                  instruction.immediate(10, 0),
                  "b0".U(1.W)
                )
              }
              control.aluOpcode := AluOpcode.add
            }
            flushPipeline()
            dispatch := false.B
            advanceStage()
          }
          is (1.U) {
            when (flag_link) {
              // If link, save LR := PC - 4 (to point to the instruction after the branch)
              // Note: always called from ARM mode.
              control.regWriteEnable := true.B
              control.regWriteIndex := 14.U // LR
              control.regReadA := 15.U // PC
              control.busB := BusBValue.Immediate
              control.immediate := 4.U
              control.aluOpcode := AluOpcode.sub
            }
            nextInstruction()
            dispatch := false.B
            advanceStage()
          }
          is (2.U) {
            // And update the PC.
            nextInstruction()
          }
        }
      }
      is (InstructionKind.ThumbBranch) {
        switch (stage) {
          is (0.U) {
            control.regReadA := 14.U // LR
            control.busB := BusBValue.Immediate
            control.immediate := instruction.immediate(10, 0) << 1
            control.aluOpcode := AluOpcode.add

            flushPipeline()
            dispatch := false.B
            advanceStage()
          }
          is (1.U) {
            // Link, save LR := PC - 2 (to point to the instruction after the branch)
            control.regWriteEnable := true.B
            control.regWriteIndex := 14.U // LR
            control.regReadA := 15.U // PC
            control.busB := BusBValue.Immediate
            // Actually subtract 1 -- previous instruction, with bit 0 set to 1 to allow for BX to return here.
            // This works because PC is always aligned to 2.
            control.immediate := 1.U
            control.aluOpcode := AluOpcode.sub

            nextInstruction()
            dispatch := false.B
            advanceStage()
          }
          is (2.U) {
            // And update the PC.
            nextInstruction()
          }
        }
      }
      is (InstructionKind.MoveFromStatusRegister) {
        val flag_spsr = instruction.flags(0)
        control.busB := Mux(flag_spsr, BusBValue.Spsr, BusBValue.Cpsr)
        control.aluOpcode := AluOpcode.mov
        control.regWriteIndex := instruction.regD
        control.regWriteEnable := true.B
        nextInstruction()
        // XXX: if target is R15, is the pipeline flushed or not?
      }
      is (InstructionKind.MoveToStatusRegister) {
        val flag_spsr = instruction.flags(0)
        val flag_immediate = instruction.flags(1)
        when (flag_immediate) {
          control.shiftKind := ShiftKind.RotateRight
          control.shiftImmediate := instruction.immediate(11, 8) << 1
          control.immediate := instruction.immediate(7, 0)
          control.busB := BusBValue.Immediate
        } .otherwise {
          control.busB := BusBValue.RegisterB
          control.regReadB := instruction.regM
        }
        control.aluOpcode := AluOpcode.mov
        when (flag_spsr) {
          control.spsrUpdateFields := Cat(instruction.opcode(3), instruction.opcode(0))
        } .otherwise {
          control.cpsrUpdateFields := Cat(instruction.opcode(3), instruction.opcode(0))
        }
        nextInstruction()
      }
      is (InstructionKind.LoadMultiple) {
        val flag_writeback = instruction.flags(0)
        val flag_s = instruction.flags(1)
        val flag_up = instruction.flags(2)
        val flag_preindex = instruction.flags(3)

        // Special handling for empty list: transfer R15 only, but increment/decrement base by full 64 bytes.
        val regList = instruction.immediate(15, 0)
        val regListEmpty = regList === 0.U
        val regCount = PopCount(regList)
        val regNextIndex = Mux(regListEmpty, 15.U, PriorityEncoder(regList))

        when (stage === 0.U) {
          // Calculate start address
          // Note: address is force aligned, which is fine: memory system will align,
          // and we don't rotate upon read.
          control.regReadA := instruction.regN
          control.immediate := Mux(flag_up,
            flag_preindex,
            Mux(regListEmpty, 16.U, regCount) - (!flag_preindex).asUInt
          )
          control.busB := BusBValue.Immediate
          control.aluOpcode := Mux(flag_up, AluOpcode.add, AluOpcode.sub)
          control.shiftKind := ShiftKind.LogicalShiftLeft
          control.shiftImmediate := 2.U
          control.addressSource := AddressSource.Alu
          control.memRequest := true.B
          control.memSequential := false.B
          control.memWrite := false.B
          control.memWidth := BusAccessWidth.Word
          control.memProt.data := true.B
          nextCounter := Mux(regListEmpty, 1.U, regCount) - 1.U
          control.pcNext := PcNext.Incrementer
          advanceStage()
        }

        when (stage === 1.U) {
          // Update base (if writeback)
          control.regReadA := instruction.regN
          control.regWriteEnable := flag_writeback
          control.regWriteIndex := instruction.regN
          control.immediate := Mux(regListEmpty, 16.U, regCount)
          control.busB := BusBValue.Immediate
          control.aluOpcode := Mux(flag_up, AluOpcode.add, AluOpcode.sub)
          control.shiftKind := ShiftKind.LogicalShiftLeft
          control.shiftImmediate := 2.U
          control.addressSource := AddressSource.Alu

          advanceStage()
        }

        when (stage === 1.U || stage === 2.U) {
          // Sequential memory accesses after the first
          control.addressSource := AddressSource.Incrementer
          control.incrementerForceWord := true.B
          control.memRequest := true.B
          control.memSequential := true.B
          control.memWrite := false.B
          control.memWidth := BusAccessWidth.Word
          control.memProt.data := true.B
          control.latchMemReadData := true.B
          nextCounter := counter - 1.U
          when (counter === 0.U) {
            // Begin I-S prefetch cycle
            beginPrefetch()
            control.addressSource := AddressSource.Pc
            control.pcNext := PcNext.Same
            // Skip stage 2 for single register load
            advanceStage(Mux(regCount > 1.U && !regListEmpty, 1.U, 2.U))
          }
        }

        when (stage === 2.U && io.enable) {
          // Unset the next bit (unless we're on the last cycle, to not corrupt next instruction).
          instruction.immediate := regList & (~(1.U << regNextIndex)).asUInt
        }

        when (stage >= 2.U) {
          // Write loaded RDATA to the next register in the list.
          control.busB := BusBValue.MemReadData
          control.aluOpcode := AluOpcode.mov
          control.regWriteIndex := regNextIndex
          control.regWriteEnable := true.B
          control.regUserWrite := flag_s
        }

        when (stage === 3.U) {
          // Complete fetch, next cycle
          when (control.regWriteIndex === 15.U) {
            // If writing PC, flush the pipeline
            flushPipeline()
            control.cpsrRestore := flag_s
          } .otherwise {
            completePrefetch()
          }
        }
      }
      is (InstructionKind.StoreMultiple) {
        val flag_writeback = instruction.flags(0)
        val flag_s = instruction.flags(1)
        val flag_up = instruction.flags(2)
        val flag_preindex = instruction.flags(3)

        // Special handling for empty list: transfer R15 only, but increment/decrement base by full 64 bytes.
        val regList = instruction.immediate(15, 0)
        val regListEmpty = regList === 0.U
        val regCount = PopCount(regList)
        val regNextIndex = Mux(regListEmpty, 15.U, PriorityEncoder(regList))

        when (stage === 0.U) {
          // Calculate start address
          control.regReadA := instruction.regN
          control.immediate := Mux(flag_up,
            flag_preindex,
            Mux(regListEmpty, 16.U, regCount) - (!flag_preindex).asUInt
          )
          control.busB := BusBValue.Immediate
          control.aluOpcode := Mux(flag_up, AluOpcode.add, AluOpcode.sub)
          control.shiftKind := ShiftKind.LogicalShiftLeft
          control.shiftImmediate := 2.U
          control.addressSource := AddressSource.Alu
          control.memRequest := true.B
          control.memSequential := false.B
          control.memWrite := true.B
          control.memWidth := BusAccessWidth.Word
          control.memProt.data := true.B
          nextCounter := Mux(regListEmpty, 1.U, regCount) - 1.U
          control.pcNext := PcNext.Incrementer
          advanceStage()
        }

        when (stage === 1.U) {
          // Update base (if writeback)
          control.regReadA := instruction.regN
          control.regWriteEnable := flag_writeback
          control.regWriteIndex := instruction.regN
          control.immediate := Mux(regListEmpty, 16.U, regCount)
          control.busB := BusBValue.Immediate
          control.aluOpcode := Mux(flag_up, AluOpcode.add, AluOpcode.sub)
          control.shiftKind := ShiftKind.LogicalShiftLeft
          control.shiftImmediate := 2.U
          advanceStage()
        }

        when (stage >= 1.U) {
          // Store registers
          control.addressSource := AddressSource.Incrementer
          control.incrementerForceWord := true.B
          control.memRequest := true.B
          control.memSequential := true.B
          control.memWrite := true.B
          control.memWidth := BusAccessWidth.Word
          control.memProt.data := true.B
          control.regReadC := regNextIndex
          control.regUserReadC := flag_s

          nextCounter := counter - 1.U
          when (counter === 0.U) {
            // Begin next instruction fetch
            nextInstruction()
            control.memSequential := false.B
            control.pcNext := PcNext.Same
            control.addressSource := AddressSource.Pc
          } .elsewhen (io.enable) {
            // Unset the next bit (unless we're on the last cycle, to not corrupt next instruction).
            instruction.immediate := regList & (~(1.U << regNextIndex)).asUInt
          }
        }
      }
      is (InstructionKind.Multiply) {
        // Multiply (Long) (Accumulate)
        // 1) if accumulate, load registers to accumulate into multiplier
        // 2) Spend "i" cycles (depending on RS's prefix) multiplying
        // 3) Write back
        // 4) if long, write back the second register.
        val flag_setcond = instruction.flags(0)
        val flag_accumulate = instruction.flags(1)
        val flag_signed = instruction.flags(2)
        val flag_long = instruction.flags(3)
        val regD_Hi = instruction.regD
        val regD_Lo = instruction.regN
        control.multiplyAccumulate := flag_accumulate
        control.multiplySigned := flag_signed
        control.multiplyLong := flag_long

        // Execution: (1 if accumulate) + mI + (1 if long) + S
        when (stage === 0.U && flag_accumulate) {
          // Load accumulator register
          control.regReadA := regD_Hi
          control.regReadB := regD_Lo
          control.multiplyLoadAccumulator := true.B
          beginPrefetch()
          control.pcNext := PcNext.Same
          control.addressSource := AddressSource.Same
          advanceStage()
        }

        when ((stage === 0.U && !flag_accumulate) || stage === 1.U) {
          // Perform the multiply
          control.regReadA := instruction.regM
          control.regReadB := instruction.regS
          control.busB := BusBValue.RegisterB
          control.multiplyEnable := true.B
          nextStage := 2.U
          beginPrefetch()
        }

        when (stage === 2.U && io.multiplierDone) {
          // Writeback low register
          control.aluOpcode := AluOpcode.mov
          control.regWriteEnable := true.B
          control.busB := BusBValue.MultiplyLo

          when (flag_setcond) {
            control.cpsrFromMultiply := true.B
          }

          when (flag_long) {
            control.regWriteIndex := regD_Lo
            advanceStage()
          } .otherwise {
            control.regWriteIndex := instruction.regD
            completePrefetch()
          }
        }

        when (stage === 3.U) {
          // Writeback high register.
          control.aluOpcode := AluOpcode.mov
          control.regWriteEnable := true.B
          control.busB := BusBValue.MultiplyHi
          control.regWriteIndex := regD_Hi
          completePrefetch()
        }
      }
    }
  } .otherwise {
    // Unexecuted instruction
    nextInstruction()
  }

  // Setup the ALU to calculate the offset address for a load/store instruction.
  private def setAluLoadStoreAddress(): Unit = {
    val flag_align_a = instruction.flags(6)
    val flag_immediate = instruction.flags(3)
    val flag_add = instruction.flags(1)

    // Calculate address, initiate access
    when (flag_immediate) {
      control.immediate := instruction.immediate
      control.busB := BusBValue.Immediate
    } .otherwise {
      control.regReadB := instruction.regM
      control.busB := BusBValue.RegisterB
      val shiftImmediate = instruction.immediate(6, 2)
      val shiftKind = suppressEnumCastWarning { instruction.immediate(1, 0).asTypeOf(ShiftKind()) }
      control.shiftKind := shiftKind
      control.shiftImmediate := shiftImmediate
      when (shiftImmediate === 0.U) {
        switch (shiftKind) {
          // Right shift [both] of 0 is actually shift of 32
          is (ShiftKind.LogicalShiftRight, ShiftKind.ArithmeticShiftRight) {
            control.shiftImmediate := 32.U
          }
          // Rotate right of 0 is actually rotate right with extend
          is (ShiftKind.RotateRight) {
            control.shiftKind := ShiftKind.RotateRightWithExtend
          }
        }
      }
    }
    control.aluInAAlign4 := flag_align_a
    control.regReadA := instruction.regN
    control.aluOpcode := Mux(flag_add, AluOpcode.add, AluOpcode.sub)
  }

  // Complete a data processing instruction
  private def finishDataProcessing(didPrefetch: Boolean = false): Unit = {
    val testOnly = instruction.opcode(3, 2) === "b10".U(2.W)

    control.regReadA := instruction.regN
    control.aluOpcode := instruction.opcode.asTypeOf(AluOpcode())
    control.regWriteIndex := instruction.regD
    control.regWriteEnable := !testOnly
    control.cpsrUpdateCond := instruction.flags(0)
    control.aluOutAlign4 := instruction.flags(2)

    when (instruction.regD === 15.U && control.cpsrUpdateCond) {
      // 'S' instructions restore (CPSR := SPSR) when Rd = PC
      control.cpsrRestore := true.B
    }
    
    when (instruction.regD === 15.U && !testOnly) {
      flushPipeline()
    } .otherwise {
      if (didPrefetch) {
        completePrefetch()
      } else {
        nextInstruction()
      }
    }
  }

  private def beginPrefetch(): Unit = {
    control.pcNext := PcNext.Incrementer
    control.addressSource := AddressSource.Incrementer
    control.memWrite := false.B
    control.memWidth := Mux(nextThumb, BusAccessWidth.Halfword, BusAccessWidth.Word)
    control.memRequest := false.B
    control.memSequential := false.B
  }

  /// Complete the prefetch of a merged I-S cycle, and go to the next instruction
  private def completePrefetch(): Unit = {
    control.memWrite := false.B
    control.memWidth := Mux(nextThumb, BusAccessWidth.Halfword, BusAccessWidth.Word)
    control.memRequest := true.B
    control.memSequential := true.B
    control.advancePipeline := true.B
    dispatch := true.B
  }

  private def nextInstruction(): Unit = {
    control.pcNext := PcNext.Incrementer
    control.addressSource := AddressSource.Incrementer
    control.memWrite := false.B
    control.memWidth := Mux(nextThumb, BusAccessWidth.Halfword, BusAccessWidth.Word)
    control.memRequest := true.B
    control.memSequential := true.B
    control.advancePipeline := true.B
    dispatch := true.B
  }

  /// After modifying PC, flush pipeline.
  private def flushPipeline(): Unit = {
    control.pcNext := PcNext.Same
    control.addressSource := AddressSource.Alu
    control.flushPipeline := true.B
    control.advancePipeline := true.B
    control.memWrite := false.B
    control.memWidth := Mux(nextThumb, BusAccessWidth.Halfword, BusAccessWidth.Word)
    control.memRequest := true.B
    control.memSequential := false.B
    dispatch := true.B
  }

  private def advanceStage(by: UInt = 1.U): Unit = {
    nextStage := stage + by
  }
}

object Control {
  private def evaluateCondition(condition: Condition.Type, flags: ConditionFlags): Bool = {
    MuxLookup(condition, false.B)(Seq(
      Condition.Eq -> flags.z,
      Condition.Ne -> !flags.z,
      Condition.Cs -> flags.c,
      Condition.Cc -> !flags.c,
      Condition.Mi -> flags.n,
      Condition.Pl -> !flags.n,
      Condition.Vs -> flags.v,
      Condition.Vc -> !flags.v,
      Condition.Hi -> (flags.c && !flags.z),
      Condition.Ls -> (!flags.c || flags.z),
      Condition.Ge -> !(flags.n ^ flags.v),
      Condition.Lt -> (flags.n ^ flags.v),
      Condition.Gt -> (!flags.z && !(flags.n ^ flags.v)),
      Condition.Le -> (flags.z || (flags.n ^ flags.v)),
      Condition.Al -> true.B,
      Condition.Nv -> false.B,
    ))
  }
}