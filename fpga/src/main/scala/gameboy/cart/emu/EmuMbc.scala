package gameboy.cart.emu

import chisel3._

class MbcIo extends Bundle {
  /** Memory accesses */
  val memEnable = Input(Bool())
  val memWrite = Input(Bool())
  val memAddress = Input(UInt(16.W))
  val memDataWrite = Input(UInt(8.W))
  val memDataRead = Output(UInt(8.W))
  val selectRom = Input(Bool())

  /** Bank index for ROM region 0x0000-0x3FFF */
  val bankRom1 = Output(UInt(9.W))
  /** Bank index for ROM region 0x4000-0x7FFF */
  val bankRom2 = Output(UInt(9.W))
  /** Bank index for RAM region 0xA000-0xBFFF */
  val bankRam = Output(UInt(4.W))
  /** If true, use the MBC's memDataRead (instead of reading from main RAM) */
  val ramReadMbc = Output(Bool())

  /** N10 save-state: packed mapper register state (bank selects, mode, ram-enable).
   *  32 bits covers all simple MBCs. `ssLoad` (one-shot, under freeze) restores. */
  val ssOut = Output(UInt(32.W))
  val ssIn = Input(UInt(32.W))
  val ssLoad = Input(Bool())
}

/**
 * An emulated memory bank controller (MBC)
 *
 * Controls bank switching. Observes writes to ROM and RAM regions
 * (potentially changing internal state). Can provide alternate data
 * for external RAM reads.
 *
 * Max ROM: 8 MiB (23 bits)
 * Max RAM: 128 KiB (17 bits)
 * Each ROM bank switches 16 KiB (14 bits)
 * Each RAM bank switches 8 KiB (13 bits)
 *    (MBC2 only does 512 addresses, 4 bits each)
 */
class EmuMbc(clockRate: Int) extends Module {
  val io = IO(new Bundle {
    val config = Input(new EmuCartConfig())
    val mbc = new MbcIo()
    /** Direct access to MBC3 RTC registers */
    val rtcAccess = new Mbc3RtcAccess
    /** Rumble activation signal */
    val rumble = Output(Bool())
    /** IMU state */
    val imu = Input(new Mbc7ImuState)

    // MBC7 (EEPROM backed) needs to directly access the underlying RAM,
    // so provide an interface for that.
    val directRam = new Mbc7DirectRamAccess

    /** N10 save-state: mapper register state of the active MBC. */
    val ssOut = Output(UInt(32.W))
    val ssIn = Input(UInt(32.W))
    val ssLoad = Input(Bool())
  })
  val mbcNone = Module(new MbcNone())
  val mbc1 = Module(new Mbc1())
  mbc1.io.multicart := io.config.multicart
  val mbc2 = Module(new Mbc2())
  val mbc3 = Module(new Mbc3(clockRate))
  mbc3.io.hasRtc := io.config.hasRtc
  io.rtcAccess <> mbc3.io.rtcAccess
  val mbc5 = Module(new Mbc5())
  mbc5.io.hasRumble := io.config.hasRumble
  val mbc7 = Module(new Mbc7())
  mbc7.io.imu := io.imu

  val mbcs = Seq(
    MbcType.None -> mbcNone.io,
    MbcType.Mbc1 -> mbc1.io,
    MbcType.Mbc2 -> mbc2.io,
    MbcType.Mbc3 -> mbc3.io,
    MbcType.Mbc5 -> mbc5.io,
    MbcType.Mbc7 -> mbc7.io,
  )

  io.mbc.memDataRead := DontCare
  io.mbc.bankRom1 := DontCare
  io.mbc.bankRom2 := DontCare
  io.mbc.bankRam := DontCare
  io.mbc.ramReadMbc := DontCare
  // N10: ss* lives in MbcIo (the ports of each MBC), but the external io.mbc bus does not use
  // it; the save state goes through the separate io.ssOut/ssIn/ssLoad.
  io.mbc.ssOut := DontCare
  io.ssOut := 0.U
  for ((id, mbc) <- mbcs) {
    mbc.memEnable := io.mbc.memEnable
    mbc.memWrite := io.mbc.memWrite
    mbc.memAddress := io.mbc.memAddress
    mbc.memDataWrite := io.mbc.memDataWrite
    mbc.selectRom := io.mbc.selectRom
    // N10: save-state — load only the active MBC; tap its state out.
    mbc.ssIn := io.ssIn
    mbc.ssLoad := io.ssLoad && (id === io.config.mbcType)

    when (id === io.config.mbcType) {
      io.mbc.memDataRead := mbc.memDataRead
      io.mbc.bankRom1 := mbc.bankRom1
      io.mbc.bankRom2 := mbc.bankRom2
      io.mbc.bankRam := mbc.bankRam
      io.mbc.ramReadMbc := mbc.ramReadMbc
      io.ssOut := mbc.ssOut
    }
  }

  io.rumble := false.B
  when (io.config.mbcType === MbcType.Mbc5) {
    io.rumble := mbc5.io.rumble
  }

  // MBC7 direct ram access
  when(io.config.mbcType === MbcType.Mbc7) {
    io.directRam <> mbc7.io.directRam
  } .otherwise {
    io.directRam.enable := false.B
    io.directRam.write := DontCare
    io.directRam.address := DontCare
    io.directRam.dataWrite := DontCare
    mbc7.io.directRam.dataRead := DontCare
    mbc7.io.directRam.done := false.B
  }
}
