package gba.mem

import chisel3._
import chisel3.util._
import lib.log.Logger

class TargetInterface(maxWidth: Width) extends Bundle {
  /// Byte-wise access address
  val address = Input(UInt(25.W))
  /// Whether an access is requested
  val request = Input(Bool())
  /// Whether the access is a sequential request
  val sequential = Input(Bool())
  /// Whether the access is a write
  val write = Input(Bool())
  /// The width of the access
  val size = Input(BusAccessWidth())
  /// Byte mask strobe (if the access were aligned to 32-bits)
  val mask = Input(UInt((maxWidth.get / 8).W))
  /// Whether the access is a data access (as opposed to instruction)
  val isData = Input(Bool())
  /// Data write
  val dataWrite = Input(UInt(maxWidth))
  /// Data read
  val dataRead = Output(UInt(maxWidth))
  /// True when the access started in the previous cycle has completed
  val done = Output(Bool())

  /// Whether the next bus cycle will be a request to this target.
  val nextRequest = Input(Bool())
  /// Whether the next bus cycle will be a sequential request (to any target).
  val nextSeq = Input(Bool())
}

case class BusTarget(
  name: String,
  prefix: UInt,
  dataWidth: BusAccessWidth.Type,
)

class Bus(
  targets: Seq[BusTarget],
) extends Module {
  val io = IO(new Bundle {
    /// Global enable signal
    val enable = Input(Bool())

    /// CPU initiator port
    val initiatorPort = Flipped(new BusInterface)

    /// Target ports
    val targetPort = MixedVec(targets.map(t => Flipped(new TargetInterface(BusAccessWidth.toWidth(t.dataWidth)))))
  })
  val logger = Logger("bus", enable = io.enable)

  val requestEnable = WireDefault(false.B)
  val requestAddress = Wire(UInt(32.W))
  val requestSequential = Wire(Bool())
  val requestWrite = Wire(Bool())
  val requestSize = Wire(BusAccessWidth())
  val requestIsData = Wire(Bool())
  val requestDataWrite = Wire(UInt(32.W))
  val requestDataRead = Wire(UInt(32.W))
  val (requestAddressAligned, requestMask) = alignAddress(requestAddress, requestSize)
  val selectedTargetHalfword = WireDefault(false.B)
  val anySelectedNow = WireDefault(false.B)
  val requestNextIsSequential = Wire(Bool())
  val splitPhase0Busy = WireDefault(false.B)

  val regAccessBusy = RegInit(false.B)
  val regAccessAddress = Reg(UInt(32.W))
  val regAccessWrite = Reg(Bool())
  val regAccessSplit = RegInit(false.B)
  val regAccessSplitPhase = Reg(UInt(1.W))
  val regAccessSequential = Reg(Bool())
  val regAccessSize = Reg(BusAccessWidth())
  val regAccessIsData = Reg(Bool())
  /// Whether the active request is completing.
  val accessDone = WireDefault(false.B)
  val regSplitBuffer = Reg(UInt(16.W))
  val regLastDataRead = Reg(UInt(32.W))

  requestDataRead := regLastDataRead

  for ((target, i) <- io.targetPort.zipWithIndex) {
    val metadata = targets(i)
    val selectedNext = prefixMatches(requestAddress, metadata.prefix)
    val selectedNow = prefixMatches(regAccessAddress, metadata.prefix)
    when (selectedNow) {
      anySelectedNow := true.B
    }

    target.address := requestAddressAligned
    target.request := requestEnable && selectedNext
    target.sequential := requestSequential
    target.write := requestWrite
    target.size := requestSize
    target.nextSeq := requestNextIsSequential
    target.isData := requestIsData
    target.nextRequest :=
      (prefixMatches(io.initiatorPort.ADDR, metadata.prefix) && io.initiatorPort.MREQ) ||
        (splitPhase0Busy && selectedNow)

    metadata.dataWidth match {
      case BusAccessWidth.Byte => {
        target.address := requestAddress
        target.dataWrite := VecInit((0 until 4).map(i => requestDataWrite(i * 8 + 7, i * 8)))(requestAddress(1, 0))
        target.mask := 1.U
        when (selectedNow) {
          requestDataRead := Fill(4, target.dataRead)
        }
      }
      case BusAccessWidth.Halfword => {
        target.dataWrite := Mux(regAccessAddress(1), requestDataWrite(31, 16), requestDataWrite(15, 0))
        target.mask := Mux(requestAddress(1), requestMask(3, 2), requestMask(1, 0))
        when (selectedNow) {
          requestDataRead := Fill(2, target.dataRead)
        }
        when (selectedNext) {
          selectedTargetHalfword := true.B
        }
      }
      case BusAccessWidth.Word => {
        target.dataWrite := requestDataWrite
        target.mask := requestMask
        when (selectedNow) {
          requestDataRead := target.dataRead
        }
      }
    }

    when (selectedNow && regAccessBusy) {
      accessDone := target.done
    }
  }

  // Open bus implementation.
  // TODO: improve with proper halfword/byte read handling.
  when (!anySelectedNow) {
    accessDone := true.B
    logger.info(cf"Open bus access: addr=0x${regAccessAddress}%x wr=${regAccessWrite} rdata=0x${requestDataRead}%x")
  }
  when (accessDone && !regAccessWrite) {
    regLastDataRead := requestDataRead
  }

  /// Whether there is an incoming request.
  val initiatorRequested = io.initiatorPort.MREQ
  /// Whether we can accept a new request.
  val isAvailable = (!regAccessBusy || accessDone) && (!regAccessSplit || regAccessSplitPhase === 1.U)

  requestAddress := io.initiatorPort.ADDR
  requestSequential := io.initiatorPort.SEQ
  requestWrite := io.initiatorPort.WRITE
  requestSize := io.initiatorPort.SIZE
  requestDataWrite := io.initiatorPort.WDATA
  requestIsData := io.initiatorPort.PROT.data
  io.initiatorPort.RDATA := requestDataRead
  io.initiatorPort.CLKEN := isAvailable
  io.initiatorPort.ABORT := false.B

  // During a long access, propagate the current request.
  when (regAccessBusy && !regAccessSplit && !accessDone) {
    requestEnable := true.B
    requestAddress := regAccessAddress
    requestSequential := regAccessSequential
    requestSize := regAccessSize
    requestWrite := regAccessWrite
    requestIsData := regAccessIsData
  }

  // Determine whether the next request is sequential.
  requestNextIsSequential := (io.initiatorPort.MREQ && io.initiatorPort.SEQ) || splitPhase0Busy

  when (io.enable) {
    when (accessDone) {
      when (regAccessWrite) {
        logger.debug(cf"Done. split=${regAccessSplit} wdata=0x${io.initiatorPort.WDATA}%x")
      } .otherwise {
        logger.debug(cf"Done. split=${regAccessSplit} rdata=0x${io.initiatorPort.RDATA}%x")
      }
      regAccessBusy := false.B

      when (regAccessSplit) {
        when (regAccessSplitPhase === 0.U) {
          // Start the second half.
          requestEnable := true.B
          requestAddress := regAccessAddress | 2.U
          requestSequential := true.B
          requestWrite := regAccessWrite
          requestIsData := regAccessIsData
          requestSize := BusAccessWidth.Halfword
          requestMask := "b1111".U(4.W)
          regAccessBusy := true.B
          regAccessSplitPhase := 1.U
          regAccessAddress := requestAddress

          when (!regAccessWrite) {
            regSplitBuffer := requestDataRead
          }
        } .otherwise {
          io.initiatorPort.RDATA := Cat(requestDataRead(15, 0), regSplitBuffer)
          // io.initiatorPort.CLKEN is set above, because isAvailable is true.
        }
      }
    } .otherwise {
      when (regAccessSplit && regAccessBusy) {
        // Over a multi-cycle split access (e.g. multiple wait states within each access),
        // ensure that the access is continuously put on the target bus.
        when (regAccessSplitPhase === 0.U) {
          requestEnable := true.B
          requestSize := BusAccessWidth.Halfword
          // Ensure the initial request is aligned to the word (to handle misaligned word requests).
          requestMask := "b1111".U(4.W)
          requestAddress := regAccessAddress
          requestAddressAligned := requestAddress & "hFFFFFFFC".U(32.W)
          requestWrite := regAccessWrite
          requestIsData := regAccessIsData
          splitPhase0Busy := true.B
        } .otherwise {
          requestEnable := true.B
          requestAddress := regAccessAddress | 2.U
          requestSequential := true.B
          requestWrite := regAccessWrite
          requestIsData := regAccessIsData
          requestSize := BusAccessWidth.Halfword
          requestMask := "b1111".U(4.W)
        }
      }
    }
    when (initiatorRequested && isAvailable) {
      logger.debug(cf"Request addr=0x${requestAddressAligned}%x wr=${requestWrite} seq=${requestSequential} size=${io.initiatorPort.SIZE}")
      requestEnable := true.B
      regAccessBusy := true.B
      regAccessAddress := requestAddress
      regAccessSplit := false.B
      regAccessWrite := requestWrite
      regAccessSequential := requestSequential
      regAccessSize := requestSize
      regAccessIsData := requestIsData

      when (selectedTargetHalfword && io.initiatorPort.SIZE === BusAccessWidth.Word) {
        regAccessSplit := true.B
        regAccessSplitPhase := 0.U
        requestSize := BusAccessWidth.Halfword
        // Ensure the initial request is aligned to the word (to handle misaligned word requests).
        requestMask := "b1111".U(4.W)
        requestAddressAligned := requestAddress & "hFFFFFFFC".U(32.W)
      }
    }
  }

  def alignAddress(address: UInt, width: BusAccessWidth.Type): (UInt, UInt) = {
    val aligned = Wire(UInt(address.getWidth.W))
    when (width === BusAccessWidth.Word) {
      aligned := Cat(address(address.getWidth - 1, 2), 0.U(2.W))
    } .elsewhen (width === BusAccessWidth.Halfword) {
      aligned := Cat(address(address.getWidth - 1, 1), 0.U(1.W))
    } .otherwise {
      aligned := address
    }
    import BusAccessWidth._
    val mask = WireDefault(Cat(
      (width === Word) || (width === Halfword && address(1) === 1.U) || (width === Byte && address(1, 0) === 3.U),
      (width === Word) || (width === Halfword && address(1) === 1.U) || (width === Byte && address(1, 0) === 2.U),
      (width === Word) || (width === Halfword && address(1) === 0.U) || (width === Byte && address(1, 0) === 1.U),
      (width === Word) || (width === Halfword && address(1) === 0.U) || (width === Byte && address(1, 0) === 0.U),
    ))
    (aligned, mask)
  }

  def getMSB(input: UInt, width: Int): UInt = {
    input(input.getWidth - 1, input.getWidth - width)
  }

  def prefixMatches(address: UInt, prefix: UInt): Bool = {
    address(27, 27 - prefix.getWidth + 1) === prefix && address(31, 28) === 0.U
  }
}
