package lib.mem

import chisel3._
import chisel3.util._
import lib.log.Logger


/**
 * Allows multiple initiator PipelineMemoryInterfaces to map to one target PipelineMemoryInterface.
 * Priority is given to the lowest initiator.
 */
class PipelineMemoryArbiter(addressWidth: Int, dataWidth: Int, n: Int) extends Module {
  val io = IO(new Bundle {
    val target = Flipped(new PipelineMemoryInterface(addressWidth, dataWidth))
    val initiator = Vec(n, new PipelineMemoryInterface(addressWidth, dataWidth))
  })
  private val logger = Logger("arbiter")

  class RequestParams extends Bundle {
    val address = UInt(addressWidth.W)
    val isWrite = Bool()
    val writeStrobe = UInt((dataWidth / 8).W)
  }

  /// Whether we're waiting for a request to complete.
  val regBusy = RegInit(false.B)
  /// The source of the current request (the one we're waiting for data)
  val regCurrentSource = RegInit(VecInit.fill(n)(false.B))
  /// Whether a request is already queued up to be sent on the next bus cycle.
  val regNextPending = RegInit(false.B)
  /// The source of the next request (the one we're sending the address for)
  val regNextSource = RegInit(VecInit.fill(n)(false.B))
  /// Params for the pending next request
  val regNextParams = Reg(new RequestParams)
  /// Initiator ports that are trying to submit a request
  val requestPendingVec = Wire(Vec(n, Bool()))
  /// Request parameters from each initiator port
  val requestParamsVec = Wire(Vec(n, new RequestParams))

  // Arbiter logic
  io.target.enable := false.B
  io.target.address := DontCare
  io.target.isWrite := DontCare
  io.target.writeStrobe := DontCare
  io.target.dataWrite := DontCare
  when (regBusy) {
    when (io.target.ready) {
      logger.info("Request complete")
      regBusy := false.B
    } .otherwise {
      logger.debug("Busy...")
    }
  }
  // Propagate the next request
  when (regNextPending) {
    // Keep propagating the same request.
    io.target.enable := true.B
    io.target.address := regNextParams.address
    io.target.isWrite := regNextParams.isWrite
    io.target.writeStrobe := regNextParams.writeStrobe

    when (io.target.ready) {
      // The request is being accepted.
      regBusy := true.B
      regCurrentSource := regNextSource
      regNextPending := false.B
    }
  } .otherwise {
    // Choose a new request to propagate
    val isRequestPending = requestPendingVec.asUInt.orR
    val requestSource = PriorityEncoderOH(requestPendingVec)
    val requestParams = Mux1H(requestSource, requestParamsVec)
    when (isRequestPending) {
      logger.info(cf"Accepted request: submitted=${io.target.ready}")
      io.target.enable := true.B
      io.target.address := requestParams.address
      io.target.isWrite := requestParams.isWrite
      io.target.writeStrobe := requestParams.writeStrobe

      when (io.target.ready) {
        // Sending the request now
        regBusy := true.B
        regCurrentSource := requestSource
      } .otherwise {
        // The target won't be accepting the request immediately, so pend it.
        regNextPending := true.B
        regNextSource := requestSource
        regNextParams := requestParams
      }
    }
  }


  // Per-initiator logic
  for ((initiator, i) <- io.initiator.zipWithIndex) {
    // Default state of an initiator is ready.
    // If a request comes in, hold it until we can actually submit and complete it.
    requestPendingVec(i) := false.B
    initiator.dataRead := io.target.dataRead
    initiator.ready := true.B

    /// Whether we're currently busy with this initiator's request
    val regInitiatorBusy = RegInit(false.B)
    val regInitiatorParams = Reg(new RequestParams)
    requestParamsVec(i) := regInitiatorParams

    when (regInitiatorBusy) {
      // Whether the arbiter is busy with our request
      val requestActive = regBusy && regCurrentSource(i)

      // Ready if the arbiter is busy with our request and we're completing this cycle.
      initiator.ready := requestActive && io.target.ready

      when (requestActive) {
        when (io.target.ready) {
          regInitiatorBusy := false.B
          logger.info(cf"$i: done")
        }
        io.target.dataWrite := initiator.dataWrite
      } .otherwise {
        // Keep trying to submit our request
        requestPendingVec(i) := true.B
      }
      when (!initiator.ready) {
        logger.debug(cf"$i: busy")
      }
    }

    when (initiator.ready && initiator.enable) {
      // Accept the incoming request.
      val params = Wire(new RequestParams)
      params.address := initiator.address
      params.isWrite := initiator.isWrite
      params.writeStrobe := initiator.writeStrobe
      regInitiatorBusy := true.B
      regInitiatorParams := params
      logger.info(cf"$i: accepted addr=${params.address}%x write=${params.isWrite}")

      // And try to submit it.
      requestPendingVec(i) := true.B
      requestParamsVec(i) := params
    }
  }
}