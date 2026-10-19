package gba.apu

import chisel3._
import chisel3.util._
import gba.{MMIO, MmioMap, MmioTarget}
import lib.log.Logger

class ApuOutput extends Bundle {
  /** Left sample value */
  val left = SInt(10.W)
  /** Right sample value */
  val right = SInt(10.W)
}

class Apu extends Module {
  val io = IO(new Bundle {
    /// Global enable
    val enable = Input(Bool())

    /// MMIO access
    val mmio = new MmioTarget()
    val mmio2 = new MmioTarget()

    /// Audio output
    val output = Output(new ApuOutput)

    /// Timer 0 and 1 overflow signal
    val timerOverflow = Input(Vec(2, Bool()))

    /// DMA request trigger for FIFOs A and B
    val dmaTrigger = Output(Vec(2, Bool()))
  })
  val logger = Logger("apu", enable = io.enable)

  // TODO: SOUNDBIAS is stubbed to allow BIOS to boot
  val regSoundbias = RegInit(0.U.asTypeOf(new ApuRegisters.SoundBias))
  val regPsgVolume = RegInit(0.U.asTypeOf(new ApuRegisters.PsgVolume))
  val regPsgPanning = RegInit(0.U.asTypeOf(new ApuRegisters.PsgPanning))
  val regMixControl = RegInit(0.U.asTypeOf(new ApuRegisters.MixControl))
  val regDirectControl = RegInit(0.U.asTypeOf(new ApuRegisters.DirectControl))
  val regMasterEnable = RegInit(false.B)

  // Direct channels
  val channelDirectA = Module(new DirectChannel("a"))
  val channelDirectB = Module(new DirectChannel("b"))
  channelDirectA.io.writeEnable := false.B
  channelDirectA.io.writeData := DontCare
  channelDirectA.io.writeMask := DontCare
  channelDirectA.io.timerTrigger := io.enable &&
    (io.timerOverflow(0) && regDirectControl.timerA === 0.U) || (io.timerOverflow(1) && regDirectControl.timerA === 1.U)
  io.dmaTrigger(0) := channelDirectA.io.dmaTrigger
  channelDirectB.io.writeEnable := false.B
  channelDirectB.io.writeData := DontCare
  channelDirectB.io.writeMask := DontCare
  channelDirectB.io.timerTrigger := io.enable &&
    (io.timerOverflow(0) && regDirectControl.timerB === 0.U) || (io.timerOverflow(1) && regDirectControl.timerB === 1.U)
  io.dmaTrigger(1) := channelDirectB.io.dmaTrigger

  // PSG
  val psg = Module(new Psg)
  psg.io.enable := io.enable
  psg.io.panning := regPsgPanning
  psg.io.volume := regPsgVolume
  io.mmio2 <> psg.io.mmio

  io.mmio <> MmioMap(
    // SOUNDCNT_L / H
    0x80 -> MmioMap.Entry(
      MmioMap.ReadFn(regPsgVolume, regPsgPanning, regMixControl, regDirectControl),
      MmioMap.WriteFn((enable, data, mask) => {
        MmioMap.WriteFn(regPsgVolume, regPsgPanning, regMixControl, regDirectControl).fn(enable, data, mask)
        val newDirectControl = data(31, 24).asTypeOf(new ApuRegisters.DirectControl)
        val resetDirectA = mask(3) && newDirectControl.resetA
        val resetDirectB = mask(3) && newDirectControl.resetB
        when (enable) {
          when (resetDirectA) {
            channelDirectA.reset := true.B
          }
          when (resetDirectB) {
            channelDirectB.reset := true.B
          }
        }
      })
    ),
    // SOUNDCNT_X
    0x84 -> MmioMap.Entry(
      MmioMap.ReadFn(_ => {
        val psgOn = psg.io.channelEnabled
        (Cat(regMasterEnable, 0.U(3.W), psgOn), true.B)
      }),
      MmioMap.WriteFn((enable, data, mask) => {
        when (enable && mask(0)) {
          val masterEnable = data(7)
          regMasterEnable := masterEnable
        }
      })
    ),
    // SOUNDBIAS
    0x88 -> MmioMap.Entry.rw(regSoundbias),
    // FIFO_A
    0xA0 -> MmioMap.Entry(MmioMap.ReadFn(), MmioMap.WriteFn((enable, data, mask) => {
      when (enable) {
        channelDirectA.io.writeEnable := true.B
        channelDirectA.io.writeData := data
        channelDirectA.io.writeMask := mask
      }
    })),
    // FIFO_B
    0xA4 -> MmioMap.Entry(MmioMap.ReadFn(), MmioMap.WriteFn((enable, data, mask) => {
      when (enable) {
        channelDirectB.io.writeEnable := true.B
        channelDirectB.io.writeData := data
        channelDirectB.io.writeMask := mask
      }
    })),
  )

  when (!regMasterEnable) {
    psg.reset := true.B
    regPsgVolume := 0.U.asTypeOf(new ApuRegisters.PsgVolume)
    regPsgPanning := 0.U.asTypeOf(new ApuRegisters.PsgPanning)
  }

  // Final mixing
  when (regMasterEnable) {
    val directA = Mux(regMixControl.directAVolume.asBool, channelDirectA.io.sample << 2, channelDirectA.io.sample << 1).asSInt
    val directB = Mux(regMixControl.directBVolume.asBool, channelDirectB.io.sample << 2, channelDirectB.io.sample << 1).asSInt
    val psgLeft = VecInit(psg.io.outputLeft >> 2, psg.io.outputLeft >> 1, psg.io.outputLeft, 0.S)(regMixControl.psgVolume).asSInt
    val psgRight = VecInit(psg.io.outputRight >> 2, psg.io.outputRight >> 1, psg.io.outputRight, 0.S)(regMixControl.psgVolume).asSInt

    val left = ((regSoundbias.bias << 1).asUInt.zext - 0x200.S(12.W)) +
      Mux(regDirectControl.enableLeftA, directA, 0.S(12.W)) +
      Mux(regDirectControl.enableLeftB, directB, 0.S(12.W)) +
      psgLeft
    val right = ((regSoundbias.bias << 1).asUInt.zext - 0x200.S(12.W)) +
      Mux(regDirectControl.enableRightA, directA, 0.S(12.W)) +
      Mux(regDirectControl.enableRightB, directB, 0.S(12.W)) +
      psgRight

    when (left < (-0x200).S) {
      io.output.left := (-0x200).S(10.W)
    } .elsewhen (left >= (0x200).S) {
      io.output.left := 0x1FF.S(10.W)
    } .otherwise {
      io.output.left := left
    }
    when (right < (-0x200).S) {
      io.output.right := (-0x200).S(10.W)
    } .elsewhen (right >= (0x200).S) {
      io.output.right := 0x1FF.S(10.W)
    } .otherwise {
      io.output.right := right
    }
  } .otherwise {
    io.output.left := 0.S
    io.output.right := 0.S
  }
}
