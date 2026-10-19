package lib.debug

import chisel3._
import chisel3.util._
import lib.debug.EmbeddedLogicAnalyzer.SignalConfigRegister
import lib.mem.{MemoryInterface, MemoryMap, RegisterMap}
import xilinx.{XpmCdcHandshake, XpmCdcPulse, XpmCdcSingle}

import java.io.{BufferedOutputStream, File, FileOutputStream}

object EmbeddedLogicAnalyzer {
  private case class Entry(name: String, offset: Int, width: Int, variants: Seq[(Int, String)] = Seq())

  private def getEntries(element: Data, prefix: String = "", offset: Int = 0): Seq[Entry] = {
    element match {
      case r: Record => {
        var off = offset
        r.elements.flatMap(entry => {
          val (name, data) = entry
          val prevOffset = off
          off += data.getWidth
          val key = if (prefix.nonEmpty) s"$prefix.$name" else name
          getEntries(data, key, prevOffset)
        }).toSeq
      }
      case v: Vec[_] => {
        v.getElements.zipWithIndex.flatMap(entry => {
          val (data, i) = entry
          getEntries(data, s"$prefix.$i", offset + (i * data.getWidth))
        })
      }
      case e: EnumType => {
        // Hack to get the outer ChiselEnum so that we can list all variants.
        // Is there a better way to do this?
        val chiselEnum = e.getClass.getField("$outer").get(e).asInstanceOf[ChiselEnum]
        val variants = chiselEnum.all.zip(chiselEnum.allNames).map(variant => {
          (variant._1.litValue.toInt, variant._2)
        })
        Seq(Entry(prefix, offset, element.getWidth, variants))
      }
      case _ => Seq(Entry(prefix, offset, element.getWidth))
    }
  }

  private class SignalConfigRegister(width: Int) extends Bundle {
    val trigger = new Bundle {
      /// Whether the matcher for the second latest value is used.
      val match1 = Bool()
      /// Whether the matcher for the latest value is used.
      val match0 = Bool()
      /// Whether the trigger for this signal is enabled.
      val enable = Bool()
    }

    /// Latest value match register
    val match0 = UInt(width.W)
    /// Second latest value match register
    val match1 = UInt(width.W)
  }
}

class EmbeddedLogicAnalyzer[T <: Bundle](gen: T, depth: Int = 1024, hasSignalClock: Boolean = false) extends Module  {
  val io = IO(new Bundle {
    val interface = new MemoryInterface(addressWidth = 24, dataWidth = 32)

    /// Clock used to latch signal values.
    val signalClock = Input(Clock())

    /// Input signals (and trigger values)
    /// Used from the 'signalClock' domain.
    val signals = Input(gen)

    /// Whether the signals should be sampled at this clock cycle
    /// Tie to true if no subsampling should occur.
    /// Used from the 'signalClock' domain.
    val sample = Input(Bool())

  })
  if (!isPow2(depth)) {
    throw new IllegalArgumentException(s"depth must be a power of two")
  }

  val width = gen.getWidth
  val clockSignal = io.signalClock

  private val entries = EmbeddedLogicAnalyzer.getEntries(io.signals)
  for (entry <- entries) {
    println(s"${entry.name}: offset=${entry.offset} width=${entry.width}")
  }

  // Written by outer config domain
  val armPulse = WireDefault(false.B)
  val forceTriggerPulse = WireDefault(false.B)
  private val signalConfigRegisters = entries.map(e => Reg(new SignalConfigRegister(e.width)))
  val regPostTriggerSamples = RegInit(0.U(24.W))

  // Written by inner signal domain
  val log = SRAM(depth, UInt(width.W), readPortClocks = Seq(clock), writePortClocks = Seq(clockSignal), readwritePortClocks = Seq())
  val isRecording = Wire(Bool())
  val isTriggered = Wire(Bool())
  val writeIndex = Wire(UInt(24.W))
  val writeWrapped = Wire(Bool())

  withClock (clockSignal) {
    /// Whether the log has wrapped around.
    val regWriteWrapped = RegInit(false.B)
    /// Index of the next log write.
    val regWriteIndex = RegInit(0.U(log2Ceil(depth).W))
    /// Whether the log is being recorded.
    val regRecording = RegInit(true.B)
    /// Whether a trigger has occured.
    val regTriggered = RegInit(false.B)
    /// Samples left to collect after trigger.
    val regSamplesLeft = Reg(UInt(24.W))

    val doArm = XpmCdcPulse(clock, armPulse)
    val doForceTrigger = XpmCdcPulse(clock, forceTriggerPulse)
    val numPostTriggerSamples = XpmCdcHandshake.continuous(clock, regPostTriggerSamples)

    val doSample = WireDefault(regRecording && io.sample)

    // Record into the log
    val logPort = log.writePorts(0)
    logPort.enable := doSample
    logPort.address := regWriteIndex
    logPort.data := io.signals.asUInt
    when (doSample) {
      val nextWriteIndex = regWriteIndex + 1.U
      regWriteIndex := nextWriteIndex
      when (nextWriteIndex === 0.U) {
        regWriteWrapped := true.B
      }
    }

    // Handle triggers
    val triggered = WireDefault(doForceTrigger)
    for ((entry, i) <- entries.zipWithIndex) {
      val value0 = Reg(UInt(entry.width.W))
      val value1 = Reg(UInt(entry.width.W))
      value1 := value0
      value0 := io.signals.asUInt(entry.offset + entry.width - 1, entry.offset)

      val config = XpmCdcHandshake.continuous(clock, signalConfigRegisters(i))
      when (config.trigger.enable) {
        val match0 = config.match0 === value0
        val match1 = config.match1 === value1
        when ((match0 || !config.trigger.match0) && (match1 || !config.trigger.match1)) {
          triggered := true.B
        }
      }
    }
    when (triggered && !regTriggered) {
      regTriggered := true.B
      regSamplesLeft := numPostTriggerSamples
      when (numPostTriggerSamples === 0.U) {
        regRecording := false.B
        doSample := false.B
      }
    }
    when (regTriggered && regRecording) {
      val nextTriggerSamples = regSamplesLeft - 1.U
      regSamplesLeft := nextTriggerSamples
      when (nextTriggerSamples === 0.U) {
        regRecording := false.B
        doSample := false.B
      }
    }

    when (doArm) {
      // Arm: start recording, and reset state
      regRecording := true.B
      regTriggered := false.B
      regWriteIndex := 0.U
      regWriteWrapped := false.B
    }

    isRecording := withClock (clock) { XpmCdcSingle(clockSignal, regRecording) }
    isTriggered := withClock (clock) { XpmCdcSingle(clockSignal, regTriggered) }
    writeIndex := withClock (clock) { XpmCdcHandshake.continuous(clockSignal, regWriteIndex) }
    writeWrapped := withClock (clock) { XpmCdcHandshake.continuous(clockSignal, regWriteWrapped) }
  }


  // Control interface
  val controlRegisterMap = RegisterMap(
    addressWidth = 16,
    dataWidth = 32,
    entries = Seq(
      // Sample width
      0x0 -> RegisterMap.Entry.r(width.U),
      // Sample depth
      0x4 -> RegisterMap.Entry.r(depth.U),
      // Log info
      0x8 -> RegisterMap.Entry.r(Cat(isTriggered, isRecording, writeWrapped, writeIndex)),
      // Number of samples post-trigger to collect
      0xC -> RegisterMap.Entry.rw(regPostTriggerSamples),
      // Arm
      0x10 -> RegisterMap.Entry(1,
        read = RegisterMap.ReadFn(),
        write = RegisterMap.WriteFn((write: Bool, data: UInt) =>
          armPulse := write && data(0)
        ),
      ),
      // Force trigger
      0x14 -> RegisterMap.Entry(1,
        read = RegisterMap.ReadFn(),
        write = RegisterMap.WriteFn((write: Bool, data: UInt) =>
          forceTriggerPulse := write && data(0)
        ),
      ),
    )
  )

  // Signal config interface
  val signalRegisterMap = RegisterMap(
    addressWidth = 22,
    dataWidth = 32,
    entries = entries.indices.flatMap(i => Seq(
      (0x10 * i) + 0x0 -> RegisterMap.Entry.rw(signalConfigRegisters(i).trigger),
      (0x10 * i) + 0x4 -> RegisterMap.Entry.rw(signalConfigRegisters(i).match0),
      (0x10 * i) + 0x8 -> RegisterMap.Entry.rw(signalConfigRegisters(i).match1),
    ))
  )

  // Log read interface
  val logReadInterface = Wire(new MemoryInterface(addressWidth = 23, dataWidth = 32))
  val logNumWords = math.ceil(width / 32.0).toInt
  val logWordBits = log2Ceil(logNumWords)
  val logReadPort = log.readPorts(0)
  val logReadAddress = logReadInterface.address >> 2
  val logReadWordAddress = RegNext(logReadAddress(logWordBits, 0))
  logReadPort.enable := logReadInterface.enable
  logReadPort.address := logReadAddress(20, logWordBits)
  logReadInterface.dataRead := logReadPort.data
    .pad(logNumWords * 32)
    .asTypeOf(Vec(logNumWords, UInt(32.W)))(logReadWordAddress)
  logReadInterface.done := RegNext(logReadPort.enable)

  io.interface <> MemoryMap(
    addressWidth = 24,
    dataWidth = 32,
    entries = Seq(
      "b00".U(2.W) -> controlRegisterMap,
      "b01".U(2.W) -> signalRegisterMap,
      "b1".U(1.W) -> logReadInterface,
    ))

  writeMetadata(new File("ela-metadata.json"))

  def writeMetadata(file: File): Unit = {
    val metadata = ujson.Obj(
      "signals" -> entries.map(e =>
        ujson.Obj(
          "name" -> e.name,
          "offset" -> e.offset,
          "width" -> e.width,
          "variants" -> e.variants.map(v => ujson.Arr(v._1, v._2)),
        )
      )
    )
    val output = new BufferedOutputStream(new FileOutputStream(file))
    ujson.writeToOutputStream(metadata, output, indent = 2)
    output.close();
  }
}
