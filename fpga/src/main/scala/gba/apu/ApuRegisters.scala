package gba.apu

import chisel3._

object ApuRegisters {
  /// SOUNDCNT_L lower - PSG volume
  class PsgVolume extends Bundle {
    val volumeLeft = UInt(3.W)
    val _padding1 = UInt(1.W)
    val volumeRight = UInt(3.W)
  }

  /// SOUNDCNT_L upper - PSG L/R Panning (per-channel)
  class PsgPanning extends Bundle {
    val left = UInt(4.W)
    val right = UInt(4.W)
  }

  /// SOUNDCNT_H lower - Mix volume
  class MixControl extends Bundle {
    val directBVolume = UInt(1.W)
    val directAVolume = UInt(1.W)
    val psgVolume = UInt(2.W)
  }

  /// SOUNDCNT_H upper - Direct sound control
  class DirectControl extends Bundle {
    val resetB = Bool()
    val timerB = UInt(1.W)
    val enableLeftB = Bool()
    val enableRightB = Bool()
    val resetA = Bool()
    val timerA = UInt(1.W)
    val enableLeftA = Bool()
    val enableRightA = Bool()
  }

  /// SOUNDBIAS
  class SoundBias extends Bundle {
    val resolution = UInt(2.W)
    val _padding1 = UInt(4.W)
    val bias = UInt(9.W)
    val _padding2 = UInt(1.W)
  }
}
