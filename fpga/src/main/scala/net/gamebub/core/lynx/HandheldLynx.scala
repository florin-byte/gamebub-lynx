package net.gamebub.core.lynx

import chisel3._
import chisel3.util._
import lib.mem.{MemoryInterface, MemoryMap, PipelineInterfaceBridge, PipelineMemoryArbiter, PipelineMemoryInterface, RegisterMap}
import lib.mem.sdram.BurstSdramController
import lib.video.ColorRGB
import net.gamebub.framework.Core
import net.gamebub.framework.interface._
import xilinx.MMCM

object HandheldLynx {
  /** MMCM VCO: 50 MHz * 21.75 = 1087.5 MHz. System = VCO / 17 = 63.97 MHz (LynxTop runs on 64 MHz and
   *  divides it by 4 for the 16 MHz Lynx clock), also used for the SDRAM; host SPI = VCO / 6. */
  val mmcmMultiply = 21.75
  val mmcmVcoHz: Double = 50_000_000.toDouble * mmcmMultiply
  val systemDivide = 17
  val spiDivide = 6
  val clockSystemHz: Int = (mmcmVcoHz / systemDivide).toInt

  /** Scan-out period: 60 Hz (the Lynx frames themselves come at the rate the game sets). */
  val periodClocks: Int = math.round(clockSystemHz / 60.0).toInt
  val framePeriod: Double = periodClocks.toDouble / clockSystemHz

  object CommandState extends ChiselEnum {
    val idle, busy, error, done = Value
  }

  /** Register 0x0000, written by the core settings (settings.json). */
  class Config extends Bundle {
    val reserved = UInt(24.W)
    /** Bit 7: sync the Lynx to the 60 Hz scan-out (it waits after each frame) */
    val sync = Bool()
    /** Bits 6-5: fast-forward speed (LynxTop speedselect: 0 = 400 %, 1 = 133 %, 2 = 160 %, 3 = 200 %) */
    val speed = UInt(2.W)
    /** Bit 4: fast forward while R is held */
    val fastForward = Bool()
    /** Bits 3-2: flickerblend (LynxVideo.BlendOff, BlendTwo, BlendThree) */
    val blend = UInt(2.W)
    /** Bits 1-0: rotation (LynxVideo.RotateNone, RotateLeft, RotateRight) */
    val rotate = UInt(2.W)
  }

  /** Video output: 160x160 (the Lynx screen upright or rotated, centered), 5 bits per channel (blending
   *  adds one bit to the Lynx's 4). */
  def videoInterface: VideoV0 = new VideoV0(
    videoWidth = LynxVideo.Size,
    videoHeight = LynxVideo.Size,
    colorDepthR = 5,
    colorDepthG = 5,
    colorDepthB = 5,
    framePeriod = framePeriod,
  )

  /** Display modes for the platform's aspect scaler: the Lynx screen, upright or rotated. On the rev2
   *  panel (480x320) they come out at exactly 3x and 2x. */
  def scalerInterface: VideoScalerV0 = new VideoScalerV0(Seq(
    VideoScalerV0.Mode(0, LynxVideo.Margin, LynxVideo.ScreenWidth, LynxVideo.ScreenHeight),
    VideoScalerV0.Mode(LynxVideo.Margin, 0, LynxVideo.ScreenHeight, LynxVideo.ScreenWidth),
  ))

  /** Core file IDs (files.json). */
  val FileRom = 0
  val FileBios = 1
  val FileState = 2

  /** Save state (file 2): LynxTop's save-state slot 0, at SDRAM byte StateSdramBase (host address
   *  0x3000_0000 + StateSdramBase): a header, the internal registers and the 64 KiB RAM, the same
   *  format as the gamebub-0.1 fork's `.ssN` files. */
  val StateSdramBase = 0x0020_0000
  val StateBytes = 66_560

  /** Boot ROM size. */
  val BiosBytes = 512
}

/**
 * Atari Lynx core around AtariLynx_MiSTer (`fpga/vendor/lynx_mister/`).
 *
 * The parts of the MiSTer top level (AtariLynx.sv) that this replaces:
 *  - clocks: 64 MHz system clock (LynxTop makes its own clock enables);
 *  - cartridge: the .lnx/.lyx file loaded by the host into SDRAM (file 0) and read back for the cart port;
 *    the 16-bit words of the file are replayed into LynxTop's download port, which detects the header (and
 *    loads BS93 homebrew), as the MiSTer download stream does;
 *  - boot ROM: file 1 (/sdcard/system/lynxboot.img), written into LynxTop's boot ROM;
 *  - video: the frame stores and the 60 Hz scan-out (LynxVideo), with rotation and flickerblend;
 *  - save states: LynxTop's own save-state machine, its memory in SDRAM, transferred as file 2.
 */
class HandheldLynx extends Module with Core {
  import HandheldLynx._

  val displayDivider = (mmcmVcoHz / ClocksV0.getClockDisplayHz(framePeriod)._1).floor.toInt
  val io = IO(new Bundle {
    val clocks = new ClocksV0(
      clockSystemHz = clockSystemHz,
      clockDisplayHz = (mmcmVcoHz / displayDivider).toInt,
      clockSpiHz = (mmcmVcoHz / spiDivide).toInt,
    )
    val video = videoInterface
    val videoScaler = scalerInterface
    val audio = new AudioV0()
    val host = new HostV0()
    val input = new InputV0()
    val sdram = new SdramV0()
  })

  // Clocks
  val mmcm = Module(new MMCM(
    clockInHz = 50_000_000,
    divide = 1,
    multiply = mmcmMultiply,
    clockOutConfig = Seq(
      MMCM.ClockOut(systemDivide),   // System (also the SDRAM clock)
      MMCM.ClockOut(displayDivider), // Display
      MMCM.ClockOut(spiDivide),      // Host SPI
    )
  ))
  mmcm.io.clockIn := io.clocks.clockIn50M
  mmcm.io.powerDown := false.B
  io.clocks.clockOutSystem := mmcm.io.clockOuts(0)
  io.clocks.clockOutDisplay := mmcm.io.clockOuts(1)
  io.clocks.clockOutSpi := mmcm.io.clockOuts(2)
  io.clocks.locked := mmcm.io.locked

  val core = Module(new LynxCore)
  io.host <> core.io.host
  io.video <> core.io.video
  io.videoScaler <> core.io.videoScaler
  io.audio <> core.io.audio
  core.io.buttons := io.input.buttons

  //////////////////////////////////
  // SDRAM controller
  //////////////////////////////////
  // On the system clock (64 MHz), without a clock crossing, as the SMS core: the board's SDRAM pins have no
  // I/O timing constraints, and reads at 107 MHz were corrupted on the console (13 Oct 2026); the
  // gamebub-0.1 Lynx core ran its SDRAM at the same 64 MHz.
  {
    val config = BurstSdramController.Config(
      clockFrequency = clockSystemHz,
      accessLength = 2,
      timeRsc = (2 * 1_000_000_000) / clockSystemHz, /* 2 clocks */
      timeWr = (2 * 1_000_000_000) / clockSystemHz, /* 2 clocks */
      enableBurst = false,
    )
    val controller = Module(new BurstSdramController(config))
    controller.io.mem <> core.io.sdram

    io.sdram.clock := clock
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
}

/**
 * The Lynx core logic, without the clock generation and the SDRAM controller (so that it can be
 * simulated). Runs on the system clock; SDRAM through a pipelined memory interface.
 */
class LynxCore extends Module {
  import HandheldLynx._

  val io = IO(new Bundle {
    val host = new HostV0()
    val video = videoInterface
    val videoScaler = scalerInterface
    val audio = new AudioV0()
    val buttons = Input(new InputV0.Buttons)
    val sdram = Flipped(new PipelineMemoryInterface(addressWidth = 25, dataWidth = 32))
  })

  // Core state
  val regCoreSetup = RegInit(false.B)
  val regCoreReset = RegInit(true.B)
  val regCoreFocus = RegInit(false.B)
  val regCoreResetOnce = RegInit(false.B)
  regCoreResetOnce := false.B
  val configReg = RegInit(0.U.asTypeOf(new Config))

  // Cartridge: set when file 0 has been loaded; its size
  val regRomLoaded = RegInit(false.B)
  val regRomSize = RegInit(0.U(20.W))

  //////////////////////////////////
  // Host memory map
  //////////////////////////////////
  val registerInterface = Wire(new MemoryInterface(addressWidth = 16, dataWidth = 32))
  val sdramHost = Wire(new MemoryInterface(addressWidth = 25, dataWidth = 32))
  val biosInterface = Wire(new MemoryInterface(addressWidth = 16, dataWidth = 32))
  val commandInterface = Wire(new MemoryInterface(addressWidth = 16, dataWidth = 32))
  val memoryMap = MemoryMap(
    addressWidth = 32,
    dataWidth = 32,
    entries = Seq(
      0x0.U(4.W) -> registerInterface,
      0x3.U(4.W) -> sdramHost,
      0x5.U(4.W) -> biosInterface,
      0xF0.U(8.W) -> commandInterface,
    ))
  io.host.mem.unsafe :<>= memoryMap.unsafe
  memoryMap.writeStrobe := "b1111".U

  // Diagnostics: save-state operations started and failed, Lynx frames completed, cartridge reads.
  val statRegStateOps = RegInit(0.U(16.W))
  val statRegStateFails = RegInit(0.U(16.W))
  val statRegLynxFrames = RegInit(0.U(32.W))
  val statRegRomReads = RegInit(0.U(32.W))
  registerInterface <> RegisterMap(
    addressWidth = 16,
    dataWidth = 32,
    entries = Seq(
      0x0000 -> RegisterMap.Entry.rw(configReg),
      0x1000 -> RegisterMap.Entry.r(Cat(statRegStateFails, statRegStateOps)),
      0x1004 -> RegisterMap.Entry.r(statRegLynxFrames),
      0x1008 -> RegisterMap.Entry.r(statRegRomReads),
      0x2000 -> RegisterMap.Entry.w(regCoreResetOnce),
    )
  )

  //////////////////////////////////
  // Cartridge download replay
  //////////////////////////////////
  // While file 0 is written, each 32-bit word the host writes to SDRAM is also fed to LynxTop's download
  // port as two 16-bit words (header detection; BS93 files are copied into RAM from it). The halves go two
  // clocks apart (header.vhd takes the second byte of a BS93 word one clock later); the next host write
  // waits until both have been replayed.
  val regReplayActive = RegInit(false.B)
  val regReplayStep = RegInit(0.U(3.W))
  val regReplayWord = Reg(UInt(32.W))
  val regReplayAddress = Reg(UInt(20.W))
  val replayEnable = regReplayStep === 4.U || regReplayStep === 2.U
  val replayAddress = Mux(regReplayStep === 4.U, regReplayAddress, regReplayAddress + 2.U)
  val replayData = Mux(regReplayStep === 4.U, regReplayWord(15, 0), regReplayWord(31, 16))
  when (regReplayStep =/= 0.U) {
    regReplayStep := regReplayStep - 1.U
  }

  // SDRAM: host (with the replay tap), cartridge reads, save states
  val sdramArbiter = Module(new PipelineMemoryArbiter(addressWidth = 25, dataWidth = 32, n = 3))
  val holdHostWrite = regReplayActive && regReplayStep =/= 0.U
  locally {
    val bridge = Module(new PipelineInterfaceBridge(addressWidth = 25, dataWidth = 32))
    bridge.io.dest <> sdramArbiter.io.initiator(0)
    val source = bridge.io.source
    source.address := sdramHost.address
    source.write := sdramHost.write
    source.dataWrite := sdramHost.dataWrite
    source.writeStrobe := sdramHost.writeStrobe
    source.enable := sdramHost.enable && !holdHostWrite
    sdramHost.dataRead := source.dataRead
    sdramHost.done := source.done
    // The file is at SDRAM address 0: the host address is the offset in the file.
    when (regReplayActive && source.done && sdramHost.write && sdramHost.address < (1 << 20).U) {
      regReplayWord := sdramHost.dataWrite
      regReplayAddress := sdramHost.address(19, 0)
      regReplayStep := 4.U
    }
  }

  //////////////////////////////////
  // Boot ROM writes (file 1)
  //////////////////////////////////
  // Each 32-bit word the host writes becomes four byte writes into LynxTop's boot ROM, one per clock.
  val regBiosBytes = RegInit(0.U(3.W))
  val regBiosWord = Reg(UInt(32.W))
  val regBiosAddress = Reg(UInt(9.W))
  val regBiosDone = RegInit(false.B)
  biosInterface.dataRead := 0.U
  biosInterface.done := regBiosDone
  regBiosDone := false.B
  when (regBiosBytes =/= 0.U) {
    regBiosWord := regBiosWord >> 8
    regBiosAddress := regBiosAddress + 1.U
    regBiosBytes := regBiosBytes - 1.U
    when (regBiosBytes === 1.U) {
      regBiosDone := true.B
    }
  } .elsewhen (biosInterface.enable && !regBiosDone) {
    when (biosInterface.write) {
      regBiosWord := biosInterface.dataWrite
      regBiosAddress := Cat(biosInterface.address(8, 2), 0.U(2.W))
      regBiosBytes := 4.U
    } .otherwise {
      regBiosDone := true.B
    }
  }

  //////////////////////////////////
  // Commands
  //////////////////////////////////
  val commandHostState = RegInit(CommandState.idle)
  val regCommandHost = Reg(Vec(4, UInt(32.W)))
  val regRomEndPending = RegInit(false.B)
  // Save state requested by the host (started by the state machine below).
  val regStateSaveRequest = RegInit(false.B)
  val regStateLoadRequest = RegInit(false.B)
  commandInterface <> RegisterMap(
    addressWidth = 16,
    dataWidth = 32,
    entries =
      regCommandHost.zipWithIndex.map { case (reg, i) => (0x0000 + (4 * i) -> RegisterMap.Entry.rw(reg)) }
  )
  io.host.commandHost.busy := commandHostState === CommandState.busy
  io.host.commandHost.done := commandHostState === CommandState.done
  io.host.commandHost.error := commandHostState === CommandState.error
  when (io.host.commandHost.request) {
    when (commandHostState === CommandState.idle) {
      val command = regCommandHost(0)(15, 0)
      val fileId = regCommandHost(1)(15, 0)
      for (reg <- regCommandHost) {
        reg := 0.U
      }
      commandHostState := CommandState.done

      when (command === HostV0.CommandGetStatus.U) {
        when (regCoreSetup) {
          regCommandHost(0) := Mux(regCoreReset, HostV0.StatusCoreHalt.U, HostV0.StatusCoreRun.U)
        } .otherwise {
          regCommandHost(0) := HostV0.StatusSetup.U
        }
      } .elsewhen (command === HostV0.CommandSetupComplete.U) {
        regCoreSetup := true.B
      } .elsewhen (command === HostV0.CommandCoreRun.U) {
        regCoreReset := false.B
      } .elsewhen (command === HostV0.CommandCoreHalt.U) {
        regCoreReset := true.B
      } .elsewhen (command === HostV0.CommandNotifyFocus.U) {
        regCoreFocus := regCommandHost(1)(0)
      } .elsewhen (command === HostV0.CommandFileWriteStart.U) {
        when (fileId === FileRom.U) {
          regReplayActive := true.B
          regRomLoaded := false.B
        }
      } .elsewhen (command === HostV0.CommandFileWriteEnd.U) {
        when (fileId === FileRom.U) {
          // Finish once the last word has been replayed.
          regRomSize := regCommandHost(2)(19, 0)
          regRomEndPending := true.B
          commandHostState := CommandState.busy
        } .elsewhen (fileId === FileState.U) {
          // The host has written a state into SDRAM: restore it.
          regStateLoadRequest := regRomLoaded
          commandHostState := Mux(regRomLoaded, CommandState.busy, CommandState.error)
        }
      } .elsewhen (command === HostV0.CommandFileReadStart.U) {
        when (fileId === FileState.U) {
          // Save the state into SDRAM; the host reads it once the command is done.
          regStateSaveRequest := regRomLoaded
          commandHostState := Mux(regRomLoaded, CommandState.busy, CommandState.error)
        }
      } .elsewhen (command(15, 8) === 0x03.U) {
        // Other file commands: nothing to do.
      } .otherwise {
        commandHostState := CommandState.error
      }
    }
  } .otherwise {
    commandHostState := CommandState.idle
  }
  when (regRomEndPending && regReplayStep === 0.U) {
    regRomEndPending := false.B
    regReplayActive := false.B
    regRomLoaded := true.B
    commandHostState := CommandState.done
  }
  io.host.commandCore.request := false.B

  //////////////////////////////////
  // Save states
  //////////////////////////////////
  // LynxTop's save-state machine needs the core running to reach an idle point, so the core runs during
  // the operation even though the menu is open (no controls then: they need focus). A request is held for
  // a few clocks; the operation is over when `ss_busy` has risen and fallen again. Results: a save always
  // succeeds once busy has been seen; a load only if LynxTop reports `state_loaded` (it checks the
  // header of the slot).
  object StateOp extends ChiselEnum {
    val idle, request, waitBusy, waitIdle = Value
  }
  val regStateOp = RegInit(StateOp.idle)
  val regStateIsLoad = RegInit(false.B)
  val regStateLoaded = RegInit(false.B)
  val regStateTimer = RegInit(0.U(27.W))
  val stateRunning = regStateOp =/= StateOp.idle
  val stateSave = WireDefault(false.B)
  val stateLoad = WireDefault(false.B)
  // Rise of busy: within 50 ms of the request; the whole operation: 1 s.
  val busyRiseTimeout = (clockSystemHz / 20).U
  val operationTimeout = clockSystemHz.U

  def finishState(ok: Bool): Unit = {
    regStateOp := StateOp.idle
    when (!ok) {
      statRegStateFails := statRegStateFails + 1.U
    }
    when (commandHostState === CommandState.busy) {
      commandHostState := Mux(ok, CommandState.done, CommandState.error)
      // Read start: size of the state file.
      regCommandHost(0) := Mux(ok && !regStateIsLoad, StateBytes.U, 0.U)
    }
  }

  val ssBusy = Wire(Bool())
  val ssStateLoaded = Wire(Bool())
  when (ssStateLoaded) {
    regStateLoaded := true.B
  }
  switch (regStateOp) {
    is (StateOp.idle) {
      when (regStateSaveRequest || regStateLoadRequest) {
        regStateIsLoad := regStateLoadRequest
        regStateSaveRequest := false.B
        regStateLoadRequest := false.B
        regStateLoaded := false.B
        regStateTimer := 0.U
        regStateOp := StateOp.request
        statRegStateOps := statRegStateOps + 1.U
      }
    }
    is (StateOp.request) {
      stateSave := !regStateIsLoad
      stateLoad := regStateIsLoad
      regStateTimer := regStateTimer + 1.U
      when (regStateTimer === 3.U) {
        regStateTimer := 0.U
        regStateOp := StateOp.waitBusy
      }
    }
    is (StateOp.waitBusy) {
      regStateTimer := regStateTimer + 1.U
      when (ssBusy) {
        regStateOp := StateOp.waitIdle
      } .elsewhen (regStateTimer === busyRiseTimeout) {
        finishState(false.B)
      }
    }
    is (StateOp.waitIdle) {
      regStateTimer := regStateTimer + 1.U
      when (!ssBusy) {
        finishState(!regStateIsLoad || regStateLoaded || ssStateLoaded)
      } .elsewhen (regStateTimer === operationTimeout) {
        finishState(false.B)
      }
    }
  }

  //////////////////////////////////
  // Lynx core
  //////////////////////////////////
  val lynx = Module(new LynxSystem)
  val video = Module(new LynxVideo(periodClocks))
  val coreReset = regCoreReset || regCoreResetOnce || !regRomLoaded

  // Fast forward while R is held (not during a save state); the core's sound is muted meanwhile, as its
  // pitch would be wrong.
  val buttons = io.buttons
  val fastForward = configReg.fastForward && buttons.r && regCoreFocus && !stateRunning

  // "Sync core to 60 Hz" (AtariLynx.sv): after each frame the core waits for the next scan-out frame.
  val regSyncWait = RegInit(false.B)
  when (video.io.frameStart || !configReg.sync || fastForward || stateRunning) {
    regSyncWait := false.B
  } .elsewhen (video.io.lynxFrameDone) {
    regSyncWait := true.B
  }

  val running = regRomLoaded && !regCoreReset && (regCoreFocus || stateRunning) && !regSyncWait
  lynx.io.clk := clock
  lynx.io.reset_in := coreReset
  lynx.io.pause_in := !running
  lynx.io.fastforward := fastForward
  lynx.io.speedselect := configReg.speed
  lynx.io.romsize := regRomSize
  lynx.io.romwrite_data := replayData
  lynx.io.romwrite_addr := replayAddress
  lynx.io.romwrite_wren := replayEnable
  lynx.io.bios_wraddr := regBiosAddress
  lynx.io.bios_wrdata := regBiosWord(7, 0)
  lynx.io.bios_wr := regBiosBytes =/= 0.U
  lynx.io.save_state := stateSave
  lynx.io.load_state := stateLoad
  lynx.io.savestate_number := 0.U
  ssBusy := lynx.io.ss_busy
  ssStateLoaded := lynx.io.state_loaded

  // Controls (the platform clears them without focus). Option 1 on L or X, Option 2 on Y, Pause on Start.
  // When the picture is rotated, the d-pad is turned with it, so that up is up on the display.
  val rotate = configReg.rotate
  val up = buttons.up
  val down = buttons.down
  val left = buttons.left
  val right = buttons.right
  lynx.io.JoyUP := MuxLookup(rotate, up)(Seq(LynxVideo.RotateLeft.U -> left, LynxVideo.RotateRight.U -> right))
  lynx.io.JoyDown := MuxLookup(rotate, down)(Seq(LynxVideo.RotateLeft.U -> right, LynxVideo.RotateRight.U -> left))
  lynx.io.JoyLeft := MuxLookup(rotate, left)(Seq(LynxVideo.RotateLeft.U -> down, LynxVideo.RotateRight.U -> up))
  lynx.io.JoyRight := MuxLookup(rotate, right)(Seq(LynxVideo.RotateLeft.U -> up, LynxVideo.RotateRight.U -> down))
  lynx.io.KeyA := buttons.a
  lynx.io.KeyB := buttons.b
  lynx.io.Option1 := buttons.l || buttons.x
  lynx.io.Option2 := buttons.y
  lynx.io.KeyPause := buttons.start

  //////////////////////////////////
  // Cartridge reads from SDRAM
  //////////////////////////////////
  // `rom_req` pulses with the address; the cart port stalls until `rom_ack` (one request at a time).
  val regRomReading = RegInit(false.B)
  val regRomReadAddress = Reg(UInt(20.W))
  val regRomAck = RegInit(false.B)
  val regRomByte = RegInit(0xFF.U(8.W))
  regRomAck := false.B
  locally {
    val bridge = Module(new PipelineInterfaceBridge(addressWidth = 25, dataWidth = 32))
    bridge.io.dest <> sdramArbiter.io.initiator(1)
    val rom = bridge.io.source
    rom.enable := regRomReading
    rom.write := false.B
    rom.address := Cat(regRomReadAddress(19, 2), 0.U(2.W))
    rom.dataWrite := DontCare
    rom.writeStrobe := DontCare
    when (lynx.io.rom_req && !regRomReading) {
      regRomReading := true.B
      regRomReadAddress := lynx.io.rom_addr
    }
    when (regRomReading && rom.done) {
      statRegRomReads := statRegRomReads + 1.U
      regRomReading := false.B
      regRomAck := true.B
      regRomByte := rom.dataRead.asTypeOf(Vec(4, UInt(8.W)))(regRomReadAddress(1, 0))
    }
    when (coreReset) {
      regRomReading := false.B
    }
  }
  lynx.io.rom_ack := regRomAck
  lynx.io.rom_byte := regRomByte

  //////////////////////////////////
  // Save-state memory
  //////////////////////////////////
  locally {
    val memory = Module(new LynxSaveBridge(addressWidth = 25))
    memory.io.address := lynx.io.SAVE_out_Adr
    memory.io.dataWrite := lynx.io.SAVE_out_Din
    memory.io.byteEnable := lynx.io.SAVE_out_be
    memory.io.readNotWrite := lynx.io.SAVE_out_rnw
    memory.io.enable := lynx.io.SAVE_out_ena
    lynx.io.SAVE_out_Dout := memory.io.dataRead
    lynx.io.SAVE_out_done := memory.io.done
    val bridge = Module(new PipelineInterfaceBridge(addressWidth = 25, dataWidth = 32))
    bridge.io.dest <> sdramArbiter.io.initiator(2)
    bridge.io.source <> memory.io.mem
  }

  //////////////////////////////////
  // Video
  //////////////////////////////////
  when (video.io.lynxFrameDone) {
    statRegLynxFrames := statRegLynxFrames + 1.U
  }
  video.io.pixelWrite := lynx.io.pixel_out_we
  video.io.pixelAddress := lynx.io.pixel_out_addr
  video.io.pixelData := lynx.io.pixel_out_data
  video.io.clear := coreReset
  video.io.rotate := configReg.rotate
  video.io.blend := configReg.blend
  io.video.data := video.io.data
  io.video.dataEnable := video.io.dataEnable
  io.video.hblank := video.io.hblank
  io.video.vblank := video.io.vblank
  io.videoScaler.mode := video.io.rotated
  // Always the aspect scaler: the integer scale of the platform would scale the whole 160x160 framebuffer
  // (2x); the scaler draws the Lynx screen at 3x (2x rotated), exact scales, so no pixel is blended.
  io.videoScaler.filter := VideoScalerV0.FilterSharpBilinear.U

  //////////////////////////////////
  // Audio
  //////////////////////////////////
  val mute = fastForward || stateRunning || !running
  io.audio.left := Mux(mute, 0.S, lynx.io.audio_l.asSInt)
  io.audio.right := Mux(mute, 0.S, lynx.io.audio_r.asSInt)

  io.sdram <> sdramArbiter.io.target
}

/**
 * Connects LynxTop's save-state memory bus (64-bit; `enable` pulses with the address in 32-bit words, the
 * access ends with a `done` pulse, read data held until the next access) to a 32-bit memory interface: two
 * accesses per transfer, low word first, so the bytes are in the same order as in the gamebub-0.1 fork's
 * state files. Halves without enabled bytes are not written.
 */
class LynxSaveBridge(addressWidth: Int) extends Module {
  object State extends ChiselEnum {
    val idle, low, high = Value
  }

  val io = IO(new Bundle {
    val address = Input(UInt(26.W))
    val dataWrite = Input(UInt(64.W))
    val byteEnable = Input(UInt(8.W))
    val readNotWrite = Input(Bool())
    val enable = Input(Bool())
    val dataRead = Output(UInt(64.W))
    val done = Output(Bool())

    val mem = Flipped(new MemoryInterface(addressWidth, 32))
  })

  val regState = RegInit(State.idle)
  val regAddress = Reg(UInt(addressWidth.W))
  val regData = Reg(UInt(64.W))
  val regByteEnable = Reg(UInt(8.W))
  val regRead = Reg(Bool())
  val regDataRead = RegInit(0.U(64.W))
  val regDone = RegInit(false.B)
  regDone := false.B
  io.done := regDone
  io.dataRead := regDataRead

  val high = regState === State.high
  io.mem.enable := regState =/= State.idle && !io.mem.done
  io.mem.address := Mux(high, regAddress + 4.U, regAddress)
  io.mem.write := !regRead
  io.mem.dataWrite := Mux(high, regData(63, 32), regData(31, 0))
  io.mem.writeStrobe := Mux(high, regByteEnable(7, 4), regByteEnable(3, 0))

  switch (regState) {
    is (State.idle) {
      when (io.enable) {
        regAddress := Cat(io.address, 0.U(2.W))(addressWidth - 1, 0)
        regData := io.dataWrite
        regByteEnable := io.byteEnable
        regRead := io.readNotWrite
        regState := Mux(!io.readNotWrite && io.byteEnable(3, 0) === 0.U, State.high, State.low)
      }
    }
    is (State.low) {
      when (io.mem.done) {
        when (regRead) {
          regDataRead := Cat(regDataRead(63, 32), io.mem.dataRead)
        }
        when (!regRead && regByteEnable(7, 4) === 0.U) {
          regState := State.idle
          regDone := true.B
        } .otherwise {
          regState := State.high
        }
      }
    }
    is (State.high) {
      when (io.mem.done) {
        when (regRead) {
          regDataRead := Cat(io.mem.dataRead, regDataRead(31, 0))
        }
        regState := State.idle
        regDone := true.B
      }
    }
  }
}
