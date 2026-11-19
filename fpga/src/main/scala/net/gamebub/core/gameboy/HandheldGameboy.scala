package net.gamebub.core.gameboy

import chisel3._
import chisel3.util._
import gameboy.Gameboy
import gameboy.cart.emu.{EmuCartConfig, EmuCartridge, Mbc3RtcAccess, RtcState}
import HandheldGameboy.{CommandState, SaveStateCommand}
import lib.audio.AudioFilterChain
import lib.mem.{MemoryInterface, MemoryMap, PipelineInterfaceBridge, RegisterMap}
import lib.mem.MemoryArbiter
import lib.mem.PipelineMemoryArbiter
import lib.mem.PipelineMemoryBurstCdc
import lib.mem.HandshakeMemoryCdc
import lib.mem.sdram.BurstSdramController
import lib.mem.sram.AsyncSramController
import lib.util.ButtonFilter
import lib.video.ColorCorrection
import lib.video.ColorRGB
import net.gamebub.framework.interface._
import net.gamebub.framework.Core
import xilinx.MMCM

object HandheldGameboy {
  class Config extends Bundle {
    val isCgb = Bool()
  }

  object CommandState extends ChiselEnum {
    val idle, busy, error, done = Value
  }

  /** Save-state file command in progress (see "Save states" below). */
  object SaveStateCommand extends ChiselEnum {
    val idle, reset, start, run = Value
  }

  /** Core file ID of the save state (the ROM, save and BIOS files are 0-3). */
  val SaveStateFileId = 4
  /** SDRAM byte address of the save state: just past the largest ROM (8 MiB). */
  val SaveStateSdramBase = 0x0080_0000

  val mmcmVcoHz = 50_000_000.toDouble / 3 * 56.375

  /** GB-143 synthesis trial: build with GB_DUAL_CORE=1 to add a second, hidden
    * core joined to the first by a local link cable. Off by default; without it
    * the generated design is unchanged.
    */
  val DualCoreTrial: Boolean = sys.env.get("GB_DUAL_CORE").contains("1")
}

class HandheldGameboy extends Module with Core {
  val displayDivider = (HandheldGameboy.mmcmVcoHz / ClocksV0.getClockDisplayHz(1.0 / 60.0)._1).floor.toInt
  val io = IO(new Bundle {
    val clocks = new ClocksV0(
      // ~ 8.3886 MHz
      clockSystemHz = (HandheldGameboy.mmcmVcoHz / 112).toInt,
      clockDisplayHz = (HandheldGameboy.mmcmVcoHz / displayDivider).toInt,
      clockSpiHz = (HandheldGameboy.mmcmVcoHz / 5).toInt,
    )
    val video = new VideoV0(
      videoWidth = 160,
      videoHeight = 144,
      colorDepthR = 5,
      colorDepthG = 5,
      colorDepthB = 5,
      framePeriod = (456 * 154).toDouble / (4 * 1024 * 1024),
    )
    val videoFilter = new VideoFilterBasicV0(
      colorInDepthR = 5,
      colorInDepthG = 5,
      colorInDepthB = 5,
      latency = 3,
    )
    val audio = new AudioV0()
    val host = new HostV0()
    val pmod = new PmodV0()
    val input = new InputBasicV0()
    val vibrate = new VibrateV0()
    val cartridge = new CartridgePortV0()
    val link = new LinkPortV0()
    val sram = new SramV0()
    val sdram = new SdramV0()
  })

  // Main MMCM
  val mmcm = Module(new MMCM(
      clockInHz = 50_000_000,
      divide = 3,
      multiply = 56.375,
      clockOutConfig = Seq(
          MMCM.ClockOut(112), // System
          MMCM.ClockOut(28),  // SDRAM (4x)
          MMCM.ClockOut(displayDivider),  // Display
          MMCM.ClockOut(5),   // Host SPI
      )
  ))
  mmcm.io.clockIn := io.clocks.clockIn50M
  mmcm.io.powerDown := false.B
  io.clocks.clockOutSystem := mmcm.io.clockOuts(0)
  val clockSdram = mmcm.io.clockOuts(1)
  val clockSdramHz = io.clocks.clockSystemHz * 4
  io.clocks.clockOutDisplay := mmcm.io.clockOuts(2)
  io.clocks.clockOutSpi := mmcm.io.clockOuts(3)
  io.clocks.locked := mmcm.io.locked

  val regCoreSetup = RegInit(false.B)
  val regCoreReset = RegInit(true.B)
  val regCoreFocus = RegInit(false.B)
  val regCoreResetOnce = RegInit(false.B)
  regCoreResetOnce := false.B

  // Config
  val configRegSystem = RegInit(0.U.asTypeOf(new HandheldGameboy.Config))
  val configRegEmuCart = RegInit(0.U.asTypeOf(new EmuCartConfig))
  val configRegRomAddress = RegInit(0.U(19.W))
  val configRegRomMask = RegInit(0.U(23.W))
  val configRegRamAddress = RegInit(0.U(19.W))
  val configRegRamMask = RegInit(0.U(17.W))
  val configRegImuAccelX = RegInit(0.U(16.W))
  val configRegImuAccelY = RegInit(0.U(16.W))
  val configRegDmgOffColor = RegInit(0.U(16.W))
  val statRegStalls = RegInit(0.U(32.W))
  val statRegCycles = RegInit(0.U(32.W))
  // GB-145 second core: its cartridge RAM base and the frame-locked button exchange.
  // CONTROL: enable[0], run[1], delay[6:4], local core is player 2[7], ROM word cache[8], ROM prefetch[9]. With enable low the build behaves as one core.
  private val dual = HandheldGameboy.DualCoreTrial
  val dualRegRamAddress = Option.when(dual)(RegInit(0.U(19.W)))
  val dualRegControl = Option.when(dual)(RegInit(0.U(10.W)))
  val dualProbe = Option.when(dual)(RegInit(VecInit(Seq.fill(12)(0.U(32.W)))))
  val dualRegPhase = Option.when(dual)(RegInit(0.U(18.W)))
  val dualSync = Option.when(dual) {
    val sync = Module(new DualCoreSync)
    sync.io.partnerWrite := false.B
    sync.io.partnerData := DontCare
    sync.io.pop := false.B
    sync
  }

  val emuCartRtcAccess = Wire(new Mbc3RtcAccess)
  emuCartRtcAccess.writeEnable := false.B
  emuCartRtcAccess.writeState := DontCare
  emuCartRtcAccess.latchSelect := DontCare
  private def makeRtcAccess(latched: Boolean): RegisterMap.Entry = {
    RegisterMap.Entry(
      (new RtcState).getWidth,
      read = RegisterMap.ReadFn((read: Bool) => {
        when (read) { emuCartRtcAccess.latchSelect := latched.B }
        emuCartRtcAccess.readState.asUInt
      }),
      write = RegisterMap.WriteFn((write: Bool, data: UInt) =>
        when (write) {
          emuCartRtcAccess.latchSelect := latched.B
          emuCartRtcAccess.writeState := data.asTypeOf(new RtcState)
          emuCartRtcAccess.writeEnable := true.B
        }
      ),
    )
  }

  val sramArbiter = Module(new MemoryArbiter(addressWidth = 18, dataWidth = 16, n = if (dual) 3 else 2))
  val sramHost = sramArbiter.io.initiator(0)
  val sramEmuCart = sramArbiter.io.initiator(1)

  // SDRAM: host, emulated cartridge, save-state walker
  val sdramArbiter = Module(new PipelineMemoryArbiter(addressWidth = 25, dataWidth = 32, n = if (dual) 4 else 3))
  val sdramHost = Wire(new MemoryInterface(addressWidth = 25, dataWidth = 32))
  val sdramEmuCart = sdramArbiter.io.initiator(1)

  {
    val bridge = Module(new PipelineInterfaceBridge(addressWidth = 25, dataWidth = 32))
    bridge.io.source <> sdramHost
    bridge.io.dest <> sdramArbiter.io.initiator(0)
  }

  val registerInterface = Wire(new MemoryInterface(addressWidth = 16, dataWidth = 32))
  val biosInterface = Wire(new MemoryInterface(addressWidth = 12, dataWidth = 8)) // 4 KiB
  val dmgPaletteInterface = Wire(new MemoryInterface(addressWidth = 5, dataWidth = 16))
  val colorCorrectInterface = Wire(new MemoryInterface(addressWidth = 16, dataWidth = 16))
  val commandInterface = Wire(new MemoryInterface(addressWidth = 16, dataWidth = 32))
  val memoryMap = MemoryMap(
    addressWidth = 32,
    dataWidth = 32,
    entries = Seq(
      0x0.U(4.W) -> registerInterface,
      0x1.U(4.W) -> biosInterface,
      0x2.U(4.W) -> dmgPaletteInterface,
      0x3.U(4.W) -> sdramHost,
      0x4.U(4.W) -> sramHost,
      0x5.U(4.W) -> colorCorrectInterface,
      0xF0.U(8.W) -> commandInterface,
    ))
  io.host.mem.unsafe :<>= memoryMap.unsafe
  memoryMap.writeStrobe := "b1111".U

  val linkBridge = Module(new LinkHostBridge)

  val regularRegisters = Wire(new MemoryInterface(16, 32))
  LinkRegisterRouter.connect(registerInterface, regularRegisters, linkBridge.io.mem)

  suppressEnumCastWarning {
    regularRegisters <> RegisterMap(
      addressWidth = 16,
      dataWidth = 32,
      entries = Seq(
        0x0000 -> RegisterMap.Entry.rw(configRegSystem),
        0x0004 -> RegisterMap.Entry.rw(configRegEmuCart), // Suppressing mbcType enum cast
        0x0008 -> RegisterMap.Entry.rw(configRegRomAddress),
        0x000C -> RegisterMap.Entry.rw(configRegRomMask),
        0x0010 -> RegisterMap.Entry.rw(configRegRamAddress),
        0x0014 -> RegisterMap.Entry.rw(configRegRamMask),
        0x0018 -> makeRtcAccess(latched = false),
        0x001C -> makeRtcAccess(latched = true),
        0x0020 -> RegisterMap.Entry.rw(configRegImuAccelX),
        0x0024 -> RegisterMap.Entry.rw(configRegImuAccelY),
        0x0030 -> RegisterMap.Entry.w(configRegDmgOffColor),

        0x1000 -> RegisterMap.Entry.rw(statRegStalls),
        0x1004 -> RegisterMap.Entry.rw(statRegCycles),

        0x2000 -> RegisterMap.Entry.w(regCoreResetOnce),
      ) ++ (if (dual) {
        // 0x40..0x58 belong to the link bridge (LinkRegisterRouter).
        val sync = dualSync.get.io
        // SpiReceiverFifo holds a write for two cycles: act on the first (as LinkHostBridge).
        val writeCompletion = RegInit(false.B)
        writeCompletion := regularRegisters.enable && regularRegisters.write && !writeCompletion
        def strobe(fn: UInt => Unit): RegisterMap.Entry =
          RegisterMap.Entry(32, RegisterMap.ReadFn(), RegisterMap.WriteFn((en, data) =>
            when (en && !writeCompletion) { fn(data) }))
        Seq(
          0x00A0 -> RegisterMap.Entry.r(DualCoreSync.Caps.U(32.W)),
          0x00A4 -> RegisterMap.Entry.rw(dualRegControl.get),
          0x00A8 -> RegisterMap.Entry.rw(dualRegRamAddress.get),
          0x00AC -> strobe(data => { sync.partnerWrite := true.B; sync.partnerData := data(23, 0) }),
          0x00B0 -> RegisterMap.Entry.r(sync.local),
          0x00B4 -> RegisterMap.Entry.r(sync.digestVisible),
          0x00B8 -> RegisterMap.Entry.r(sync.digestHidden),
          0x00BC -> strobe(data => { sync.pop := data(0) }),
          0x00C0 -> RegisterMap.Entry.r(sync.status),
          0x00C4 -> RegisterMap.Entry.r(sync.waitCycles),
          0x00C8 -> RegisterMap.Entry.rw(dualRegPhase.get),
          0x00CC -> RegisterMap.Entry.r(sync.serialStatVisible),
          0x00D0 -> RegisterMap.Entry.r(sync.serialStatHidden),
          0x00D4 -> RegisterMap.Entry.r(sync.serialStatCross),
          0x00D8 -> RegisterMap.Entry.r(sync.writeStat),
          0x00DC -> RegisterMap.Entry.r(sync.rejectStat),
          0x00E0 -> RegisterMap.Entry.r(sync.lastReject),
        ) ++ (0 until 12).map(i => (0x00E4 + 4 * i) -> RegisterMap.Entry.r(dualProbe.get(i)))
      } else Nil)
    )
  }

  // Save states: the GbSaveStateWalker copies the core state to/from SDRAM.
  val ssCommand = RegInit(SaveStateCommand.idle)
  val ssCommandIsLoad = Reg(Bool())
  val ssSavePulse = WireDefault(false.B)
  val ssLoadPulse = WireDefault(false.B)
  val ssResetCore = WireDefault(false.B)
  val ssWalkerBusy = Wire(Bool())
  val ssWalkerLoaded = Wire(Bool())
  val ssWalkerAborted = Wire(Bool())
  val ssStateBytes = Wire(UInt(32.W))

  // Command interface
  val commandHostState = RegInit(CommandState.idle)
  val regCommandHost = Reg(Vec(2, UInt(32.W)))
  commandInterface <> RegisterMap(
    addressWidth = 16,
    dataWidth = 32,
    entries =
      regCommandHost.zipWithIndex.map { case (reg, i) => (0x0000 + (4 * i) -> RegisterMap.Entry.rw(reg)) }
  )
  // Host -> Core commands
  io.host.commandHost.busy := commandHostState === CommandState.busy
  io.host.commandHost.done := commandHostState === CommandState.done
  io.host.commandHost.error := commandHostState === CommandState.error
  when (io.host.commandHost.request) {
    when (commandHostState === CommandState.idle) {
      val command = regCommandHost(0)(15, 0)
      for (reg <- regCommandHost) {
        reg := 0.U
      }
      commandHostState := CommandState.done
      
      when (command === HostV0.CommandGetStatus.U) {
        when (regCoreSetup) {
          regCommandHost(0) := Mux(regCoreReset, HostV0.StatusCoreHalt.U, HostV0.StatusCoreRun.U)
        } .otherwise {
          // No pre-setup initialization to do.
          regCommandHost(0) := HostV0.StatusSetup.U
        }
      } .elsewhen (command === HostV0.CommandSetupComplete.U) {
        // No post-setup initialization to do.
        regCoreSetup := true.B
      } .elsewhen (command === HostV0.CommandCoreRun.U) {
        regCoreReset := false.B
      } .elsewhen (command === HostV0.CommandCoreHalt.U) {
        regCoreReset := true.B
      } .elsewhen (command === HostV0.CommandNotifyFocus.U) {
        regCoreFocus := regCommandHost(1)(0)
      } .elsewhen (command(15, 8) === 0x03.U) {
        // File command. The save state is captured into SDRAM when the host starts reading it,
        // and applied after the host has written it; the command stays busy until then.
        when (regCommandHost(1)(15, 0) === HandheldGameboy.SaveStateFileId.U) {
          when (command === HostV0.CommandFileReadStart.U) {
            ssCommand := SaveStateCommand.start
            ssCommandIsLoad := false.B
            commandHostState := CommandState.busy
          } .elsewhen (command === HostV0.CommandFileWriteEnd.U) {
            ssCommand := SaveStateCommand.reset
            ssCommandIsLoad := true.B
            commandHostState := CommandState.busy
          }
        }
      } .otherwise {
        // Unknown command
        commandHostState := CommandState.error
      }
    }
  } .otherwise {
    commandHostState := CommandState.idle
  }
  // Core -> Host commands
  io.host.commandCore.request := linkBridge.io.coreRequest

  // Save-state command sequencing (declarations above)
  switch (ssCommand) {
    is (SaveStateCommand.reset) {
      // Reset the Gameboy (not the cartridge, which keeps its RTC) before applying a state.
      ssResetCore := true.B
      ssCommand := SaveStateCommand.start
    }
    is (SaveStateCommand.start) {
      ssSavePulse := !ssCommandIsLoad
      ssLoadPulse := ssCommandIsLoad
      ssCommand := SaveStateCommand.run
    }
    is (SaveStateCommand.run) {
      when (!ssWalkerBusy) {
        ssCommand := SaveStateCommand.idle
        val failed = Mux(ssCommandIsLoad, !ssWalkerLoaded, ssWalkerAborted)
        commandHostState := Mux(failed, CommandState.error, CommandState.done)
        // FILE_READ_START returns the file size.
        regCommandHost(0) := Mux(ssCommandIsLoad, 0.U, ssStateBytes)
      }
    }
  }

  val dmgPalette = Reg(Vec(16, UInt(15.W)))
  when (dmgPaletteInterface.enable && dmgPaletteInterface.write) {
    dmgPalette(dmgPaletteInterface.address(4, 1)) := dmgPaletteInterface.dataWrite
  }
  dmgPaletteInterface.dataRead := 0.U
  dmgPaletteInterface.done := RegNext(dmgPaletteInterface.enable)

  // Reset
  val regCoreResetCombined = RegInit(true.B)
  regCoreResetCombined := false.B
  // A save-state load resets the core only; the physical cartridge reset below is separate.
  when (regCoreReset || regCoreResetOnce || ssResetCore) {
    regCoreResetCombined := true.B
  }

  // Gameboy
  val gameboyConfig = Gameboy.Configuration(
    skipBootrom = false,
    optimizeForSimulation = false,
  )
  val gameboy = Module(new Gameboy(gameboyConfig.copy(
    wirelessGuardCycles = 4 * io.clocks.clockSystemHz,
  )))
  gameboy.io.wireless <> linkBridge.io.link
  // Use a pulse: a held reset must not advance the session every clock.
  linkBridge.io.channelReset := regCoreResetCombined && !RegNext(regCoreResetCombined, false.B)
  gameboy.reset := regCoreResetCombined
  gameboy.io.isCgb := configRegSystem.isCgb

  // Save-state walker, between the core's save-state window and SDRAM.
  val ssWalker = Module(new GbSaveStateWalker(
    cpuBits = gameboy.io.saveState.cpuOut.getWidth,
    miscBits = gameboy.io.saveState.miscOut.getWidth,
  ))
  ssWalker.io.savePulse := ssSavePulse
  ssWalker.io.loadPulse := ssLoadPulse
  ssWalker.io.slotBase := HandheldGameboy.SaveStateSdramBase.U
  ssWalkerBusy := ssWalker.io.busy
  ssWalkerLoaded := ssWalker.io.loaded
  ssWalkerAborted := ssWalker.io.aborted
  ssStateBytes := ssWalker.STATE_BYTES.U

  {
    val bridge = Module(new PipelineInterfaceBridge(addressWidth = 25, dataWidth = 32))
    bridge.io.source <> ssWalker.io.sdram
    bridge.io.dest <> sdramArbiter.io.initiator(2)
  }

  // Gameboy clock control
  val doStall = WireDefault(false.B)
  gameboy.io.clockConfig.enable := false.B
  // The save-state walker runs the core up to the next vblank before capturing a state.
  when (regCoreFocus || ssWalker.io.coreClockEnable) {
    when (doStall) {
      statRegStalls := statRegStalls + 1.U
    }.otherwise {
      gameboy.io.clockConfig.enable := true.B
      statRegCycles := statRegCycles + 1.U
    }
  }
  gameboy.io.clockConfig.provide8Mhz := true.B

  gameboy.io.joypad.a := io.input.buttons.a
  gameboy.io.joypad.b := io.input.buttons.b
  gameboy.io.joypad.up := io.input.buttons.up
  gameboy.io.joypad.down := io.input.buttons.down
  gameboy.io.joypad.left := io.input.buttons.left
  gameboy.io.joypad.right := io.input.buttons.right
  gameboy.io.joypad.start := io.input.buttons.start
  gameboy.io.joypad.select := io.input.buttons.select

  // Vibration unused by default.
  io.vibrate.mode := VibrateV0.Mode.Off

  // PMOD unused
  io.pmod.out := DontCare
  io.pmod.dir := 0.U(4.W)

  // Audio output and filtering
  val audioFilter = Module(new AudioFilterChain(
    inputRateHz = io.clocks.clockSystemHz,
    cicR = (io.clocks.clockSystemHz.toFloat / (4 * 48000)).ceil.toInt,
    dataBits = 16,
    outputRateHz = 48000,
  ))
  val audioShift = audioFilter.dataBits - 10
  audioFilter.io.in(0) := gameboy.io.apu.left << audioShift
  audioFilter.io.in(1) := gameboy.io.apu.right << audioShift
  io.audio.left := audioFilter.io.out(0)
  io.audio.right := audioFilter.io.out(1)

  // Link port
  io.link.soOut := gameboy.io.serial.out
  io.link.soDir := true.B
  gameboy.io.serial.in := RegNext(RegNext(io.link.siIn))
  io.link.siOut := DontCare
  io.link.siDir := false.B
  io.link.sdOut := DontCare
  io.link.sdDir := false.B
  gameboy.io.serial.clockIn := RegNext(RegNext(io.link.scIn))
  io.link.scOut := gameboy.io.serial.clockOut
  io.link.scDir := gameboy.io.serial.clockEnable

  // Video output
  val videoX = RegInit(0.U(8.W))
  val videoY = RegInit(0.U(8.W))
  io.video.dataEnable := false.B
  io.video.data.r := DontCare
  io.video.data.g := DontCare
  io.video.data.b := DontCare
  val regDisplayOff = RegInit(false.B)

  when (regDisplayOff) {
    // Render a frame of "lcd off" color
    // When the display is turned off, it remains off until the next vblank when
    // the LCD is on. This ensures that the entire screen is blanked, and matches
    // Game Boy behavior.

    io.video.vblank := false.B
    io.video.hblank := false.B
    when (configRegSystem.isCgb) {
      io.video.data.r := 0x1F.U(5.W)
      io.video.data.g := 0x1F.U(5.W)
      io.video.data.b := 0x1F.U(5.W)
    } .otherwise {
      io.video.data := configRegDmgOffColor.asTypeOf(ColorRGB(5, 5, 5))
    }

    when (videoY === 144.U) {
      io.video.vblank := true.B
    } .elsewhen (videoX === 160.U) {
      io.video.hblank := true.B
      videoX := 0.U
      videoY := videoY + 1.U
    } .otherwise {
      io.video.dataEnable := true.B
      videoX := videoX + 1.U
    }

    // Only end blanking after the *next* vblank when the LCD is on
    when (gameboy.io.ppu.lcdEnable && gameboy.io.ppu.vblank) {
      regDisplayOff := false.B
    }
  } .otherwise {
    io.video.vblank := gameboy.io.ppu.vblank
    io.video.hblank := gameboy.io.ppu.hblank

    when (!gameboy.io.clockConfig.enable) {
      // Do nothing.
    } .elsewhen (!gameboy.io.ppu.lcdEnable) {
      // Blank for at least a whole frame.
      regDisplayOff := true.B
      videoX := 0.U
      videoY := 0.U
    } .elsewhen (gameboy.io.ppu.valid) {
      io.video.dataEnable := true.B

      when (configRegSystem.isCgb) {
        io.video.data.r := gameboy.io.ppu.pixel(4, 0)
        io.video.data.g := gameboy.io.ppu.pixel(9, 5)
        io.video.data.b := gameboy.io.ppu.pixel(14, 10)
      } .otherwise {
        val index = gameboy.io.ppu.dmgColor.asUInt
        io.video.data := dmgPalette(index).asTypeOf(ColorRGB(5, 5, 5))
      }
    }
  }

  // Emulated Cartridge
  val emuCart = Module(new EmuCartridge(8 * 1024 * 1024))
  when (regCoreReset || regCoreResetOnce) {
    emuCart.reset := true.B
  }
  emuCart.io.config := configRegEmuCart
  emuCart.io.tCycle := gameboy.io.tCycle
  emuCart.io.rtcAccess <> emuCartRtcAccess
  emuCart.io.imu.x := configRegImuAccelX
  emuCart.io.imu.y := configRegImuAccelY
  GbSaveStateWalker.connect(ssWalker, gameboy, emuCart)

  val sdramBridge = Module(new PipelineInterfaceBridge(addressWidth = 25, dataWidth = 32))
  sdramBridge.io.dest <> sdramEmuCart
  val sdram = sdramBridge.io.source
  sdram.enable := false.B
  sdram.write := false.B
  sdram.address := DontCare
  sdram.dataWrite := DontCare
  sdram.writeStrobe := DontCare

  sramEmuCart.enable := false.B
  sramEmuCart.write := false.B
  sramEmuCart.address := DontCare
  sramEmuCart.dataWrite := DontCare
  sramEmuCart.writeStrobe := DontCare

  val regEmuCartBusy = RegInit(false.B)
  val regEmuCartDataRead = Reg(UInt(8.W))
  val regEmuCartDataWrite = Reg(UInt(8.W))
  val regEmuCartAddress = Reg(UInt(23.W))
  val regEmuCartIsWrite = Reg(Bool())
  val regEmuCartSelectRom = Reg(Bool())
  val emuCartDataWrite = WireDefault(regEmuCartDataWrite)
  val emuCartIsWrite = WireDefault(regEmuCartIsWrite)
  val emuCartAddress = WireDefault(regEmuCartAddress)
  val emuCartSelectRom = WireDefault(regEmuCartSelectRom)
  val emuCartAccessStart = emuCart.io.dataAccess.enable && !emuCart.reset.asBool
  when (emuCartAccessStart) {
    regEmuCartBusy := true.B

    regEmuCartDataWrite := emuCart.io.dataAccess.dataWrite
    regEmuCartAddress := emuCart.io.dataAccess.address
    regEmuCartIsWrite := emuCart.io.dataAccess.write
    regEmuCartSelectRom := emuCart.io.dataAccess.selectRom

    emuCartDataWrite := emuCart.io.dataAccess.dataWrite
    emuCartAddress := emuCart.io.dataAccess.address
    emuCartIsWrite := emuCart.io.dataAccess.write
    emuCartSelectRom := emuCart.io.dataAccess.selectRom
  }
  emuCart.io.dataAccess.valid := false.B
  emuCart.io.dataAccess.dataRead := regEmuCartDataRead
  when (emuCartAccessStart || regEmuCartBusy) {
    when (emuCartSelectRom) {
      when (emuCartIsWrite) {
        // Don't handle ROM writes.
        emuCart.io.dataAccess.valid := true.B
      } .otherwise {
        sdram.enable := true.B
        sdram.write := false.B
        sdram.address := configRegRomAddress + (Cat(emuCartAddress(22, 2), "b00".U(2.W)) & configRegRomMask)
        emuCart.io.dataAccess.dataRead := sdram.dataRead
          .asTypeOf(Vec(4, UInt(8.W)))(
            emuCartAddress(1, 0)
          )
        emuCart.io.dataAccess.valid := sdram.done
      }
    } .otherwise {
      sramEmuCart.enable := true.B
      sramEmuCart.write := emuCartIsWrite
      sramEmuCart.address := (configRegRamAddress + (Cat(emuCartAddress(16, 1), "b0".U(1.W)) & configRegRamMask))
      sramEmuCart.dataWrite := Fill(2, emuCartDataWrite)
      sramEmuCart.writeStrobe := Mux(emuCartAddress(0), "b10".U(2.W), "b01".U(2.W))
      emuCart.io.dataAccess.valid := sramEmuCart.done
      emuCart.io.dataAccess.dataRead := Mux(
        emuCartAddress(0),
        sramEmuCart.dataRead(15, 8),
        sramEmuCart.dataRead(7, 0)
      )
    }
  }
  when (regEmuCartBusy && emuCart.io.dataAccess.valid) {
    regEmuCartBusy := false.B
    regEmuCartDataRead := emuCart.io.dataAccess.dataRead
  }

  when (emuCart.io.config.enabled) {
    io.cartridge.enabled := false.B

    // Connect emulated cartridge
    emuCart.io.cartridge <> gameboy.io.cartridge
    io.vibrate.mode := Mux(emuCart.io.rumble, VibrateV0.Mode.On, VibrateV0.Mode.Off)
    doStall := emuCart.io.stall

    // Disconnect physical cartridge
    io.cartridge.bank0Out := DontCare
    io.cartridge.bank1Out := DontCare
    io.cartridge.bank2Out := DontCare
    io.cartridge.bank3Out := DontCare
    io.cartridge.pin30Out := DontCare
    io.cartridge.pin31Out := DontCare
    io.cartridge.bank0Dir := false.B
    io.cartridge.bank1Dir := false.B
    io.cartridge.bank2Dir := false.B
    io.cartridge.bank3Dir := false.B
    io.cartridge.pin30Dir := false.B
    io.cartridge.pin31Dir := false.B
  } .otherwise {
    // Cartridge I/O
    io.cartridge.enabled := true.B

    // Bank 0: Data bus
    gameboy.io.cartridge.dataIn := io.cartridge.bank0In
    io.cartridge.bank0Out := gameboy.io.cartridge.dataOut
    io.cartridge.bank0Dir := gameboy.io.cartridge.dataDir

    // Bank 1: Address High
    io.cartridge.bank1Out := gameboy.io.cartridge.address(15, 8)
    io.cartridge.bank1Dir := true.B

    // Bank 2: Address Low
    io.cartridge.bank2Out := gameboy.io.cartridge.address(7, 0)
    io.cartridge.bank2Dir := true.B

    // Bank 3: Control signals (0: nCS, 1: nRD, 2: nWR, 3: PHI)
    io.cartridge.bank3Dir := true.B
    io.cartridge.bank3Out := Cat(
      gameboy.io.cartridge.phi,
      gameboy.io.cartridge.nWR,
      gameboy.io.cartridge.nRD,
      gameboy.io.cartridge.nCS,
    )

    // Pin 30: nRST
    // *Bidirectional* open-drain active-low reset
    // 1) Held low by the device during power up
    // 2) The cartridge can *also* hold it low to reset the device
    val resetDir = RegInit(true.B)
    io.cartridge.pin30Dir := resetDir
    io.cartridge.pin30Out := false.B
    // When the core is resetting, hold the pin low.
    resetDir := regCoreReset || regCoreResetOnce
    // When the pin is low... reset the core.
    when (RegNext(RegNext(io.cartridge.pin30In)) === 0.U) {
      regCoreResetCombined := true.B
    }

    // Pin 31: VIN
    io.cartridge.pin31Dir := false.B
    io.cartridge.pin31Out := DontCare

    // Disconnect emulated cartridge
    emuCart.io.cartridge := DontCare
    emuCart.io.cartridge.reqStart := false.B
  }

  // Boot ROM
  // The DMG one is 256 bytes, and starts at 0
  // The CGB one is 2304 bytes (2048 with 256 bytes padding), starts at 256
  val bios = SRAM(2048 + 256 + 256, UInt(8.W), numReadPorts = if (dual) 2 else 1, numWritePorts = 1, numReadwritePorts = 0)
  bios.writePorts(0).enable := biosInterface.enable && biosInterface.write
  bios.writePorts(0).address := biosInterface.address
  bios.writePorts(0).data := biosInterface.dataWrite
  biosInterface.dataRead := 0.U
  biosInterface.done := RegNext(bios.writePorts(0).enable || bios.readPorts(0).enable)
  bios.readPorts(0).enable := gameboy.io.bootRom.read
  when (gameboy.io.isCgb) {
    bios.readPorts(0).address := gameboy.io.bootRom.address +& 256.U
  } .otherwise {
    bios.readPorts(0).address := gameboy.io.bootRom.address
  }
  gameboy.io.bootRom.data := bios.readPorts(0).data

  // GB-145: the partner's core, hidden (no video, no audio). Local link cable
  // to the first core, one shared clock enable, the same ROM in SDRAM, its own
  // cartridge RAM in SRAM. DualCoreSync applies both cores' buttons on frame
  // boundaries and stops both cores while the partner's buttons are missing.
  // With CONTROL.enable low the hidden core stays in reset and nothing changes.
  if (dual) {
    val sync = dualSync.get
    val syncEnable = dualRegControl.get(0)
    val gameboy2 = Module(new Gameboy(gameboyConfig.copy(
      wirelessGuardCycles = 4 * io.clocks.clockSystemHz,
    )))
    gameboy2.reset := regCoreResetCombined || !syncEnable
    gameboy2.io.isCgb := configRegSystem.isCgb
    // One enable for both cores, except the start phase, when the late core stays stopped.
    val coreTick = (regCoreFocus || ssWalker.io.coreClockEnable) && !doStall
    gameboy.io.clockConfig.enable := coreTick && !sync.io.holdVisible
    gameboy2.io.clockConfig.enable := coreTick && !sync.io.holdHidden
    gameboy2.io.clockConfig.provide8Mhz := true.B
    gameboy2.io.wireless.config := 0.U
    gameboy2.io.wireless.clear := false.B
    gameboy2.io.wireless.command.valid := false.B
    gameboy2.io.wireless.command.bits := 0.U
    gameboy2.io.joypad := sync.io.hiddenButtons.asTypeOf(gameboy2.io.joypad)
    when (syncEnable) {
      gameboy.io.joypad := sync.io.visibleButtons.asTypeOf(gameboy.io.joypad)
    }
    sync.io.enable := syncEnable
    sync.io.run := dualRegControl.get(1)
    sync.io.delay := dualRegControl.get(6, 4)
    sync.io.tick := coreTick
    sync.io.phase := dualRegPhase.get
    sync.io.visibleLate := dualRegControl.get(7)
    sync.io.serialVisible := Cat(gameboy.io.serialDebug.regEnable, gameboy.io.serialDebug.regClockMode)
    sync.io.serialHidden := Cat(gameboy2.io.serialDebug.regEnable, gameboy2.io.serialDebug.regClockMode)
    // Same bit order as JoypadState.
    sync.io.localButtons := Cat(
      io.input.buttons.start, io.input.buttons.select, io.input.buttons.b, io.input.buttons.a,
      io.input.buttons.down, io.input.buttons.up, io.input.buttons.left, io.input.buttons.right)
    sync.io.sampleVisible := DualCoreSync.traceSample(gameboy)
    sync.io.sampleHidden := DualCoreSync.traceSample(gameboy2)
    when (sync.io.request) {
      io.host.commandCore.request := true.B
    }
    // No save-state walker on the hidden core in this trial.
    gameboy2.io.saveState.elements.values.foreach { field =>
      if (chisel3.reflect.DataMirror.directionOf(field) == ActualDirection.Input) {
        field := 0.U.asTypeOf(field)
      }
    }

    // Local link cable (overrides the external link port wiring above).
    when (syncEnable) {
      gameboy.io.serial.in := gameboy2.io.serial.out
      gameboy.io.serial.clockIn := !gameboy2.io.serial.clockEnable || gameboy2.io.serial.clockOut
    }
    gameboy2.io.serial.in := gameboy.io.serial.out
    gameboy2.io.serial.clockIn := !gameboy.io.serial.clockEnable || gameboy.io.serial.clockOut

    bios.readPorts(1).enable := gameboy2.io.bootRom.read
    bios.readPorts(1).address := gameboy2.io.bootRom.address +& Mux(gameboy2.io.isCgb, 256.U, 0.U)
    gameboy2.io.bootRom.data := bios.readPorts(1).data

    val emuCart2 = Module(new EmuCartridge(8 * 1024 * 1024))
    when (regCoreReset || regCoreResetOnce || !syncEnable) {
      emuCart2.reset := true.B
    }
    emuCart2.io.config := configRegEmuCart
    emuCart2.io.tCycle := gameboy2.io.tCycle
    emuCart2.io.rtcAccess.writeEnable := false.B
    emuCart2.io.rtcAccess.writeState := DontCare
    emuCart2.io.rtcAccess.latchSelect := false.B
    emuCart2.io.imu.x := configRegImuAccelX
    emuCart2.io.imu.y := configRegImuAccelY
    emuCart2.io.ssIn := 0.U
    emuCart2.io.ssLoad := false.B
    emuCart2.io.cartridge <> gameboy2.io.cartridge
    // Both cores stop together, so the cable between them keeps its timing.
    when ((emuCart.io.config.enabled && emuCart2.io.stall && syncEnable) || sync.io.stall) {
      doStall := true.B
    }

    val sdramBridge2 = Module(new PipelineInterfaceBridge(addressWidth = 25, dataWidth = 32))
    sdramBridge2.io.dest <> sdramArbiter.io.initiator(3)
    val sdram2 = sdramBridge2.io.source
    sdram2.enable := false.B
    sdram2.write := false.B
    sdram2.address := DontCare
    sdram2.dataWrite := DontCare
    sdram2.writeStrobe := DontCare
    val sram2 = sramArbiter.io.initiator(2)
    sram2.enable := false.B
    sram2.write := false.B
    sram2.address := DontCare
    sram2.dataWrite := DontCare
    sram2.writeStrobe := DontCare

    // Same access path as the first emulated cartridge.
    val access = emuCart2.io.dataAccess
    val regBusy = RegInit(false.B)
    val regDataRead = Reg(UInt(8.W))
    val regDataWrite = Reg(UInt(8.W))
    val regAddress = Reg(UInt(23.W))
    val regIsWrite = Reg(Bool())
    val regSelectRom = Reg(Bool())
    val accessStart = access.enable && !emuCart2.reset.asBool
    val dataWrite = Mux(accessStart, access.dataWrite, regDataWrite)
    val address = Mux(accessStart, access.address, regAddress)
    val isWrite = Mux(accessStart, access.write, regIsWrite)
    val selectRom = Mux(accessStart, access.selectRom, regSelectRom)
    when (accessStart) {
      regBusy := true.B
      regDataWrite := access.dataWrite
      regAddress := access.address
      regIsWrite := access.write
      regSelectRom := access.selectRom
    }
    access.valid := false.B
    access.dataRead := regDataRead
    when (accessStart || regBusy) {
      when (selectRom) {
        when (isWrite) {
          access.valid := true.B
        } .otherwise {
          sdram2.enable := true.B
          sdram2.address := configRegRomAddress + (Cat(address(22, 2), "b00".U(2.W)) & configRegRomMask)
          access.dataRead := sdram2.dataRead.asTypeOf(Vec(4, UInt(8.W)))(address(1, 0))
          access.valid := sdram2.done
        }
      } .otherwise {
        sram2.enable := true.B
        sram2.write := isWrite
        sram2.address := dualRegRamAddress.get + (Cat(address(16, 1), "b0".U(1.W)) & configRegRamMask)
        sram2.dataWrite := Fill(2, dataWrite)
        sram2.writeStrobe := Mux(address(0), "b10".U(2.W), "b01".U(2.W))
        access.valid := sram2.done
        access.dataRead := Mux(address(0), sram2.dataRead(15, 8), sram2.dataRead(7, 0))
      }
    }
    when (regBusy && access.valid) {
      regBusy := false.B
      regDataRead := access.dataRead
    }
    // GB-150: ROM words per core through RomWordReader: the last four words are kept
    // (CONTROL bit 8) and the next word is fetched in idle time (bit 9), so two cores ask
    // the shared SDRAM less often and rarely at the moment they need the answer.
    // It changes how long a read takes, never what it returns. Only in this mode: with the
    // mode off the first core's ROM path above is untouched.
    val readers = Seq.fill(2)(Module(new RomWordReader(23, 4)))
    for (reader <- readers) {
      reader.io.cache := dualRegControl.get(8)
      reader.io.prefetch := dualRegControl.get(9)
      reader.io.flush := regCoreResetCombined
    }
    val romAddrV = configRegRomAddress + (Cat(emuCartAddress(22, 2), 0.U(2.W)) & configRegRomMask)
    val romReadV = (emuCartAccessStart || regEmuCartBusy) && emuCartSelectRom && !emuCartIsWrite &&
      emuCart.io.config.enabled && syncEnable
    readers(0).io.reqValid := romReadV
    readers(0).io.reqAddress := romAddrV >> 2
    readers(0).io.memDone := sdram.done
    readers(0).io.memData := sdram.dataRead
    when (syncEnable) {
      sdram.enable := readers(0).io.memEnable
      sdram.write := false.B
      sdram.address := Cat(readers(0).io.memAddress, 0.U(2.W))
      when (romReadV) {
        emuCart.io.dataAccess.dataRead := readers(0).io.respData.asTypeOf(Vec(4, UInt(8.W)))(emuCartAddress(1, 0))
        emuCart.io.dataAccess.valid := readers(0).io.respValid
      }
    }
    val romAddrH = configRegRomAddress + (Cat(address(22, 2), 0.U(2.W)) & configRegRomMask)
    val romReadH = (accessStart || regBusy) && selectRom && !isWrite
    readers(1).io.reqValid := romReadH
    readers(1).io.reqAddress := romAddrH >> 2
    readers(1).io.memDone := sdram2.done
    readers(1).io.memData := sdram2.dataRead
    sdram2.enable := readers(1).io.memEnable
    sdram2.address := Cat(readers(1).io.memAddress, 0.U(2.W))
    when (romReadH) {
      access.dataRead := readers(1).io.respData.asTypeOf(Vec(4, UInt(8.W)))(address(1, 0))
      access.valid := readers(1).io.respValid
    }

    // GB-150 probes: who stops the core clock, and what the ROM reads cost.
    // 0 core clocks; stopped by 1 visible cartridge only, 2 hidden only, 3 both, 4 the frame sync;
    // SDRAM ROM reads started 5 visible, 6 hidden; clocks with a read pending 7 visible, 8 hidden;
    // requests answered from kept words 9 visible, 10 hidden; 11 prefetch reads started, hidden[31:16] visible[15:0].
    val probe = dualProbe.get
    def count(i: Int): Unit = { probe(i) := probe(i) + 1.U }
    val stallV = emuCart.io.config.enabled && emuCart.io.stall
    val stallH = emuCart.io.config.enabled && emuCart2.io.stall
    when (!syncEnable) {
      probe.foreach(_ := 0.U)
    } .otherwise {
      when (regCoreFocus) {
        when (coreTick) { count(0) }
        when (stallV && !stallH) { count(1) }
        when (!stallV && stallH) { count(2) }
        when (stallV && stallH) { count(3) }
        when (!stallV && !stallH && sync.io.stall) { count(4) }
      }
      when (emuCartAccessStart && romReadV && readers(0).io.hit) { count(9) }
      when (accessStart && romReadH && readers(1).io.hit) { count(10) }
      when (readers(0).io.startDemand) { count(5) }
      when (readers(1).io.startDemand) { count(6) }
      when (sdram.enable) { count(7) }
      when (sdram2.enable) { count(8) }
      val prefetchV = readers(0).io.startPrefetch
      val prefetchH = readers(1).io.startPrefetch
      when (prefetchV || prefetchH) {
        probe(11) := Cat(probe(11)(31, 16) + prefetchH.asUInt, probe(11)(15, 0) + prefetchV.asUInt)
      }
    }
  }

  // SRAM controller
  val sramController = Module(new AsyncSramController(addressWidth = 18, dataWidth = 16))
  io.sram.ceN := false.B
  io.sram.weN := sramController.io.signals.weN
  io.sram.oeN := sramController.io.signals.oeN
  io.sram.writeMaskN := sramController.io.signals.writeMaskN
  io.sram.address := sramController.io.signals.address
  sramController.io.signals.dataIn := io.sram.dataIn
  io.sram.dataOut := sramController.io.signals.dataOut
  io.sram.dataDir := sramController.io.signals.dataDir
  sramController.io.mem <> sramArbiter.io.target

  // SDRAM controller
  withClock(clockSdram) {
    val config = BurstSdramController.Config(
      clockFrequency = clockSdramHz,
      accessLength = 2,
      timeRsc = (2 * 1_000_000_000) / clockSdramHz, /* 2 clocks */
      timeWr = (2 * 1_000_000_000) / clockSdramHz, /* 2 clocks */
      enableBurst = false,
    )
    val controller = Module(new BurstSdramController(config))
    val cdc = Module(new PipelineMemoryBurstCdc(
      addressWidth = 25,
      dataWidth = 32,
      addressBurstIncrement = 4,
      enablePrefetch = false,
    ))
    cdc.io.slowClock := clock
    cdc.io.initiator <> sdramArbiter.io.target
    cdc.io.target <> controller.io.mem
    
    io.sdram.clock := clockSdram
    io.sdram.cke := controller.io.signals.cke

    io.sdram.cs := controller.io.signals.cs
    io.sdram.ras := controller.io.signals.ras
    io.sdram.cas := controller.io.signals.cas
    io.sdram.we := controller.io.signals.we

    io.sdram.dqm := controller.io.signals.dqm
    io.sdram.bank := controller.io.signals.bank
    io.sdram.address := controller.io.signals.address
    controller.io.signals.dataIn := io.sdram.dataIn
    io.sdram.dataOut := controller.io.signals.dataOut
    io.sdram.dataDir := controller.io.signals.dataDir
  }

  // Video filter (color correction)
  ColorCorrection.setup(
    clock = clock,
    reset = reset,
    videoFilter = io.videoFilter,
    memInterface = colorCorrectInterface,
  )
}
