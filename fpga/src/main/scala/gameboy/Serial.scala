package gameboy

import chisel3._
import chisel3.util._
import gameboy.Timer.RegisterControl

class SerialDebug extends Bundle {
  val regData = UInt(8.W)
  val regBitsLeft = UInt(3.W)
  val regEnable = Bool()
  val regClockMode = Bool()
  val clockIn = Bool()
  val clockOut = Bool()
  val dataOut = Bool()
  val dataIn = Bool()
}

class SerialIo extends Bundle {
  /** Output bit */
  val out = Output(Bool())
  /** Input bit */
  val in = Input(Bool())

  /** True if clockOut is being used, False if clockIn */
  val clockEnable = Output(Bool())
  /** Output clock signal (if clockEnable) */
  val clockOut = Output(Bool())
  /** Input clock signal (if !clockEnable) */
  val clockIn = Input(Bool())
}

/**
 * Serial data transfer
 *
 * We shift out a new bit on falling clock edge, shift in a bit on the rising edge
 */
class Serial(config: Gameboy.Configuration) extends Module {
  private val guardCycles = config.wirelessGuardCycles
  val io = IO(new PeripheralAccess {
    val clocker = Input(new Clocker)
    val cgbMode = Input(Bool())

    val interruptRequest = Output(Bool())
    /**
     * 16384 Hz timer (x2 in double speed)
     * This is the frequency of edges, not changes. Each edge we invert the internal clock signal,
     * because we want a 8192 Hz clock.
     */
    val divSerial = Input(Bool())
    /** 524,288 Hz timer (x2 in double speed), for fast serial mode. */
    val divSerialFast = Input(Bool())

    val serial = new SerialIo()
    val wireless = new WirelessLinkIo

    /** Debug output */
    val debug = Output(new SerialDebug)
  })

  val regData = RegInit(0.U(8.W))
  val regEnable = RegInit(false.B)
  /** false: external clock, true: internal clock */
  val regClockMode = RegInit(false.B)
  /** In CGB mode, whether to use the fast internal clock */
  val regClockFast = RegInit(false.B)
  val regBitsLeft = RegInit(0.U(3.W))

  // Wireless state uses the module clock, independently of CPU speed/stall.
  import WirelessLink._
  require(guardCycles > 0)
  val wireless = io.wireless.config(0)
  val barrier = io.wireless.config(2)
  val standing = io.wireless.config(3)
  val held = RegInit(false.B)
  val parked = RegInit(false.B)
  val parkedAddress = Reg(UInt(8.W))
  val parkedData = Reg(UInt(8.W))
  val applyParked = RegInit(false.B)
  val offer = RegInit(false.B)
  val offerData = Reg(UInt(8.W))
  val guard = RegInit(0.U(log2Ceil(guardCycles + 1).W))
  val fastStall = held && regClockFast && io.cgbMode && barrier
  io.wireless.stall := wireless && (parked || applyParked || fastStall)
  io.wireless.held := held
  io.wireless.armed := wireless && regEnable && !regClockMode && !held
  val writeEvent = WireDefault(0.U.asTypeOf(Valid(UInt(11.W))))
  val slaveEvent = WireDefault(0.U.asTypeOf(Valid(UInt(11.W))))
  def emit(kind: Int, data: UInt): Unit = {
    writeEvent.valid := true.B
    writeEvent.bits := Cat(kind.U(3.W), data)
  }
  def emitSlave(kind: Int, data: UInt): Unit = {
    slaveEvent.valid := true.B
    slaveEvent.bits := Cat(kind.U(3.W), data)
  }
  io.wireless.event := Mux(writeEvent.valid, writeEvent, slaveEvent)
  io.wireless.eventNext.valid := writeEvent.valid && slaveEvent.valid
  io.wireless.eventNext.bits := slaveEvent.bits

  val writeAddress = Mux(applyParked, parkedAddress, io.address)
  val writeData = Mux(applyParked, parkedData, io.dataWrite)
  val writeRequest = applyParked || (io.enabled && io.write && !parked)
  val serialWrite = writeAddress === 1.U || writeAddress === 2.U
  val absorb = writeAddress === 2.U && writeData(0)
  val parkWrite = wireless && held && barrier && serialWrite && !absorb
  val doWrite = writeRequest && !parkWrite && !(wireless && held && absorb)
  applyParked := false.B
  when (writeRequest && parkWrite) {
    parked := true.B
    parkedAddress := writeAddress
    parkedData := writeData
    emit(Abandon, writeData)
  }

  // Register memory mapping; a parked write lands one cycle after the verdict,
  // so its SB update cannot hide the master's received byte from the IRQ edge.
  when (doWrite) {
    switch (writeAddress) {
      is (0x01.U) {
        regData := writeData
        when (io.wireless.armed) { emit(Armed, writeData) }
      }
      is (0x02.U) {
        val enabled = writeData(7)
        val clockMode = writeData(0)
        regEnable := enabled
        regClockMode := clockMode
        when (io.cgbMode) { regClockFast := writeData(1) }
        when (enabled) {
          regBitsLeft := 0.U
          if (config.optimizeForSimulation) { printf(cf"${regData}%c") }
        }
        when (wireless) {
          when (enabled && clockMode) {
            held := true.B
            guard := 0.U
            emit(Master, regData)
          } .elsewhen (enabled) {
            emit(Armed, regData)
          } .elsewhen (io.wireless.armed) {
            emit(Disarm, regData)
          }
        }
      }
    }
  }
  io.valid := (io.address === 0x01.U) || (io.address === 0x02.U)
  io.dataRead := 0xFF.U
  when (io.enabled && !io.write) {
    switch (io.address) {
      is (0x01.U) { io.dataRead := regData }
      is (0x02.U) {
        io.dataRead := Cat(regEnable, "b11111".U(5.W), !io.cgbMode || regClockFast, regClockMode)
      }
    }
  }

  // Clock
  val clockOut = RegInit(true.B)
  io.serial.clockOut := Mux(wireless, true.B, clockOut)
  io.serial.clockEnable := regClockMode && !wireless
  val clockSignal = Mux(regClockMode, clockOut, io.serial.clockIn)
  val prevClockSignal = RegEnable(clockSignal, io.clocker.enable)
  val clockRising = !prevClockSignal && clockSignal
  val clockFalling = prevClockSignal && !clockSignal

  // Internal clock
  val divider = Mux(regClockFast, io.divSerialFast, io.divSerial)
  val dividerEdge = RegEnable(divider, io.clocker.enable) && !divider
  when (!wireless && io.clocker.enable && regEnable && regClockMode && dividerEdge) {
    clockOut := !clockOut
  }

  // Serial operation
  io.interruptRequest := false.B
  val regOut = RegInit(1.U(1.W))
  io.serial.out := Mux(wireless, true.B, regOut)
  when (!wireless && io.clocker.enable && regEnable) {
    when (clockFalling) {
      regOut := regData(7)
      regData := regData << 1
    }
    when (clockRising) {
      regData := Cat(regData(7, 1), io.serial.in)

      regBitsLeft := regBitsLeft - 1.U
      when (regBitsLeft === 1.U) {
        // Transfer complete, we're going down to 0
        regEnable := false.B
        io.interruptRequest := true.B
      }
    }
  }

  val command = io.wireless.command
  val opcode = command.bits(10, 8)
  val commandData = command.bits(7, 0)
  val reply = command.valid && opcode === MasterReply.U
  val drop = command.valid && opcode === MasterDrop.U
  val expired = guard === (guardCycles - 1).U
  io.wireless.guardExpired := wireless && held && expired && !reply && !drop && !io.wireless.clear
  when (wireless && held) {
    guard := guard + 1.U
    when (reply || drop || expired) {
      held := false.B
      regEnable := false.B
      when (!drop) {
        regData := Mux(reply, commandData, 0xFF.U)
        io.interruptRequest := true.B
      }
      when (parked || (writeRequest && parkWrite)) {
        parked := false.B
        applyParked := true.B
      }
    }
  }

  // Use the post-write slave state, accepting even a one-cycle arm. A CPU SB
  // write on that edge supplies the actual outgoing byte.
  val scWrite = doWrite && writeAddress === 2.U
  val sbWrite = doWrite && writeAddress === 1.U
  val slaveReady = !held && !parked && !applyParked &&
    Mux(scWrite, writeData(7) && !writeData(0), regEnable && !regClockMode)
  val slaveByte = Mux(sbWrite, writeData, regData)
  val incoming = command.valid && opcode === SlaveXfer.U
  val withdraw = command.valid && opcode === SlaveWithdraw.U
  when (wireless) {
    when (withdraw) {
      when (offer) { emitSlave(SlaveNak, regData) }
      offer := false.B
    } .elsewhen (incoming || offer) {
      when (slaveReady) {
        regData := Mux(incoming, commandData, offerData)
        regEnable := false.B
        offer := false.B
        io.interruptRequest := true.B
        emitSlave(SlaveDone, slaveByte)
      } .elsewhen (incoming) {
        when (standing) {
          offer := true.B
          offerData := commandData
          when (fastStall || parked) { emitSlave(SlaveNakStalled, regData) }
        } .otherwise {
          when (fastStall || parked) { emitSlave(SlaveNakStalled, regData) }
            .otherwise { emitSlave(SlaveNak, regData) }
        }
      }
    }
  }
  when (!wireless || io.wireless.clear) {
    held := false.B
    parked := false.B
    applyParked := false.B
    offer := false.B
    guard := 0.U
    when (held || io.wireless.clear) { regEnable := false.B }
    io.wireless.event.valid := false.B
    io.wireless.eventNext.valid := false.B
    when (io.wireless.clear) {
      // Clear also completes an SC master start accepted on this same edge. A
      // normal held master still needs its unplugged completion; a parked
      // (abandoned) master is silently released, without replaying its write.
      val completeMaster = (held && !parked && !(writeRequest && parkWrite)) ||
        (wireless && scWrite && writeData(7) && writeData(0))
      io.interruptRequest := completeMaster
      when (completeMaster) { regData := 0xFF.U }
    }
  }

  // Debug output
  io.debug.regData := regData
  io.debug.regBitsLeft := regBitsLeft
  io.debug.regEnable := regEnable
  io.debug.regClockMode := regClockMode
  io.debug.clockIn := io.serial.clockIn
  io.debug.clockOut := clockOut
  io.debug.dataOut := regData
  io.debug.dataIn := io.serial.in
}
