package gba.cart

import chisel3._
import chisel3.util._
import gba.cart.CartridgePrefetch.State
import gba.mem.TargetInterface
import lib.log.Logger

object CartridgePrefetch {
  object State extends ChiselEnum {
    /// No ROM request is happening.
    val Idle = Value
    /// A ROM request (directly requested) is happening
    val Passthrough = Value
    /// A prefetch ROM request is happening
    val PrefetchActive = Value
  }

  /// The actual 8-entry buffer holding fetched data.
  class Buffer extends Module {
    val io = IO(new Bundle {
      val empty = Output(Bool())
      val almostEmpty = Output(Bool())
      val full = Output(Bool())

      val writeEnable = Input(Bool())
      val writeData = Input(UInt(16.W))

      val readEnable = Input(Bool())
      val readData = Output(UInt(16.W))

      val flush = Input(Bool())


      val debugReadIndex = Output(UInt(3.W))
      val debugWriteIndex = Output(UInt(3.W))
    })

    val buffer = Reg(Vec(8, UInt(16.W)))
    val regReadIndex = RegInit(0.U(3.W))
    val regWriteIndex = RegInit(0.U(3.W))
    io.debugReadIndex := regReadIndex
    io.debugWriteIndex := regWriteIndex

    // TODO allow all 8 places to be used
    io.empty := regReadIndex === regWriteIndex
    io.almostEmpty := (regReadIndex + 1.U) === regWriteIndex
    io.full := (regWriteIndex + 1.U) === regReadIndex
    io.readData := buffer(regReadIndex)

    when (io.writeEnable) {
      buffer(regWriteIndex) := io.writeData
      regWriteIndex := regWriteIndex + 1.U
    }
    when (io.readEnable) {
      regReadIndex := regReadIndex + 1.U
    }
    when (io.flush) {
      // It's valid to flush *and* put a new entry in.
      // That should be the only entry after this cycle.
      // If no write happened, write and read are the same so it's empty.
      // If a write happened, read now points one before, at the correct data.
      regReadIndex := regWriteIndex
    }
  }
}

/**
 * Cartridge prefetch controller
 *
 * The purpose of prefetch is to keep ROM bursts going when non-cartridge
 * memory requests are occurring. Then, prefetched data is fed back to the bus
 * with zero wait states.
 *
 * Prefetch only activates for code (not data) requests from ROM. Any request
 * to the cartridge that isn't a code request for the next fetched address
 * in the buffer aborts prefetch.
 */
class CartridgePrefetch extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())
    val prefetchEnabled = Input(Bool())

    val busTargetRom0 = new TargetInterface(16.W)
    val busTargetRom1 = new TargetInterface(16.W)
    val busTargetRom2 = new TargetInterface(16.W)
    val busTargetRamRequest = Input(Bool())
    val busTargetRamNextRequest = Input(Bool())

    val cartInitiatorRom = Flipped(new TargetInterface(16.W))
    val cartInitiatorRomRegion = Output(Vec(3, Bool()))
    /// Whether the cartridge controller should abort the current request
    val cartInitiatorAbortRequest = Output(Bool())
  })
  val logger = Logger("cart.prefetch", enable = io.enable)

  // Passing through the rom
  val romTargets = Seq(io.busTargetRom0, io.busTargetRom1, io.busTargetRom2)
  val romInitiator = io.cartInitiatorRom
  val romRequests = romTargets.map(_.request)
  val hasRomRequest = VecInit(romRequests).asUInt.orR
  val hasRamRequest = io.busTargetRamRequest
  val hasNextRomRequest = VecInit(romTargets.map(_.nextRequest)).asUInt.orR
  val romRequestAddress = Mux1H(romRequests, romTargets.map(_.address(24, 1)))
  val romRequestWrite = Mux1H(romRequests, romTargets.map(_.write))
  val romRequestSequential = Mux1H(romRequests, romTargets.map(_.sequential))
  val romRequestNextSeq = romTargets(0).nextSeq // nextSeq is the same for all targets
  val romRequestDataWrite = Mux1H(romRequests, romTargets.map(_.dataWrite))
  val romRequestIsData = Mux1H(romRequests, romTargets.map(_.isData))
  // Parameters set below during a request.
  romInitiator.request := false.B
  romInitiator.address := DontCare
  romInitiator.write := DontCare
  romInitiator.dataWrite := DontCare
  romInitiator.sequential := DontCare
  romInitiator.nextSeq := DontCare
  // Parameters that are never used.
  romInitiator.size := DontCare
  romInitiator.mask := DontCare
  romInitiator.isData := DontCare
  romInitiator.nextRequest := DontCare  // unused

  // Passing data back towards the bus.
  val romTargetDone = WireDefault(false.B)
  val romTargetDataRead = Wire(UInt(16.W))
  for (x <- romTargets) {
    x.done := romTargetDone
    x.dataRead := romTargetDataRead
  }
  romTargetDataRead := DontCare

  // State registers.
  val regState = RegInit(State.Idle)
  val regRequestAddress = Reg(UInt(24.W))
  val regRequestRegion = Reg(Vec(3, Bool()))
  val regRequestEligible = Reg(Bool())

  val abortRequest = WireDefault(false.B)
  io.cartInitiatorAbortRequest := abortRequest
  io.cartInitiatorRomRegion := regRequestRegion

  // Prefetch buffer itself
  val buffer = Module(new CartridgePrefetch.Buffer)
  buffer.io.flush := false.B
  buffer.io.readEnable := false.B
  buffer.io.writeEnable := false.B
  buffer.io.writeData := DontCare
  // The first address in the prefetch buffer
  val regBufferAddress = Reg(UInt(24.W))
  // Whether there's a pending request for the prefetch buffer.
  val regBufferRequest = RegInit(false.B)
  val bufferCanSatisfyRequest = Wire(Bool())
  // Whether there's a pending request for the halfword we're currently fetching.
  val regPrefetchRequest = RegInit(false.B)

  bufferCanSatisfyRequest := !buffer.io.empty && regBufferAddress === romRequestAddress && !romRequestIsData
  when (regBufferRequest) {
    logger.info(cf"serving from buffer: data=${buffer.io.readData}%x | rd=${buffer.io.debugReadIndex} wr=${buffer.io.debugWriteIndex} ")
    romTargetDone := true.B
    romTargetDataRead := buffer.io.readData
    when (io.enable) {
      buffer.io.readEnable := true.B
      regBufferRequest := false.B
    }

    when (buffer.io.almostEmpty) {
      // The buffer will be empty next cycle.
      // TODO: should this not apply if writeEnable = true? combinatorial cycle though
      bufferCanSatisfyRequest := false.B
    }
  }

  switch (regState) {
    is (State.Idle) {
      // Went from idle to receiving a ROM request, so start the request.
      when (hasRomRequest) {
        when (bufferCanSatisfyRequest) {
          // This request can come from the prefetch buffer!
          logger.debug(cf"idle: got buffer request: addr=${romRequestAddress << 1}%x")
          when (io.enable) {
            regBufferRequest := true.B
            regBufferAddress := regBufferAddress + 1.U
          }
        } .otherwise {
          // Start the request.
          logger.info(cf"Rom request: addr=${romRequestAddress << 1}%x (${VecInit(romRequests).asUInt}%b)")
          romInitiator.request := true.B
          romInitiator.address := romRequestAddress
          romInitiator.write := romRequestWrite
          romInitiator.sequential := romRequestSequential
          romInitiator.nextSeq := romRequestNextSeq
          romInitiator.dataWrite := romRequestDataWrite
          io.cartInitiatorRomRegion := VecInit(romRequests)

          when (io.enable) {
            regState := State.Passthrough
            regRequestAddress := romRequestAddress
            regRequestRegion := VecInit(romRequests)
            regRequestEligible := !romRequestIsData && io.prefetchEnabled

            // Anything left in the buffer needs to be discarded.
            buffer.io.flush := true.B
          }
        }
      }
    }
    is (State.Passthrough) {
      // Keep the request going.
      romInitiator.request := true.B
      romInitiator.address := romRequestAddress
      romInitiator.write := romRequestWrite
      romInitiator.sequential := romRequestSequential
      romInitiator.dataWrite := romRequestDataWrite

      // nextSeq indicates whether the *next* request will be sequential, used by the CartridgeController
      // to determine if it should keep a burst alive (nCS low). This only matters on the second-to-last cycle
      // (which will definitely not be the first cycle after a request, because the minimum number of wait
      // states is 1.
      // This is the case if either 1) nextSeq is set (e.g. by DMA), or if this request is prefetch eligible.
      romInitiator.nextSeq := romRequestNextSeq || (!hasNextRomRequest && (!io.busTargetRamNextRequest) && regRequestEligible)

      when (romInitiator.done) {
        // Finishing a direct cartridge rom request.
        romTargetDone := true.B
        romTargetDataRead := romInitiator.dataRead
        logger.debug(cf"Passthrough done. req=${hasRomRequest} addr=${regRequestAddress << 1}%x data=${romTargetDataRead}%x")
        romInitiator.request := false.B

        when (hasRomRequest) {
          romInitiator.request := true.B
          // Got another rom request, so pass it through.
          when (io.enable) {
            regRequestAddress := romRequestAddress
            regRequestRegion := VecInit(romRequests)
            regRequestEligible := !romRequestIsData && io.prefetchEnabled
          }
        } .elsewhen (hasRamRequest) {
          // Another cartridge access that we don't handle.
          when (io.enable) {
            regState := State.Idle
          }
        } .elsewhen (romRequestNextSeq) {
          // There's no request coming in, but there's still a sequential request on the bus.
          // This is probably due to a DMA to/from ROM -- we shouldn't end the current burst.
          // But we also shouldn't start the prefetch (?).
          // Go to Idle?
          logger.debug(cf"Passthrough done, hasRequest=0 but nextSeq=1")
          when (io.enable) {
            regState := State.Idle
          }
        } .elsewhen (regRequestEligible) {
          // This request was prefetch-eligible, so keep the burst going.
          val nextPrefetchAddress = regRequestAddress + 1.U
          logger.debug(cf"Start prefetch at addr=${nextPrefetchAddress << 1}%x")
          romInitiator.request := true.B
          romInitiator.address := nextPrefetchAddress
          romInitiator.write := false.B
          romInitiator.sequential := true.B
          romInitiator.nextSeq := true.B
          romInitiator.dataWrite := DontCare

          when (io.enable) {
            regState := State.PrefetchActive
            regRequestAddress := nextPrefetchAddress
            regBufferAddress := nextPrefetchAddress
          }
        } .otherwise {
          when (io.enable) {
            regState := State.Idle
          }
        }
      }
    }
    is (State.PrefetchActive) {
      romInitiator.request := true.B
      romInitiator.address := regRequestAddress
      romInitiator.write := false.B
      romInitiator.sequential := true.B
      romInitiator.nextSeq := true.B
      romInitiator.dataWrite := DontCare
      val canCompletePrefetch = WireDefault(false.B)

      when (regPrefetchRequest) {
        when ((hasNextRomRequest && !romRequestNextSeq) || io.busTargetRamNextRequest) {
          // Bus is waiting for the current rom request, and then making a different cartridge request.
          // Don't continue the prefetch after this.
          romInitiator.nextSeq := false.B
        }

        // Bus is waiting for the currently fetched halfword.
        when (romInitiator.done) {
          logger.debug(cf"Forwarding prefetched halfword, data=${romInitiator.dataRead}%x")
          // It's done, pass it back to bus.
          romTargetDone := true.B
          romTargetDataRead := romInitiator.dataRead

          when (romInitiator.nextSeq) {
            // And continue the prefetch.
            romInitiator.address := regRequestAddress + 1.U
            when (io.enable) {
              regRequestAddress := regRequestAddress + 1.U
            }
          } .otherwise {
            // Don't continue the prefetch, there's another request pending.
            when (io.enable) {
              when (hasRomRequest) {
                // Start the request.
                romInitiator.address := romRequestAddress
                romInitiator.write := romRequestWrite
                romInitiator.sequential := romRequestSequential
                romInitiator.dataWrite := romRequestDataWrite
                io.cartInitiatorRomRegion := VecInit(romRequests)

                regState := State.Passthrough
                regRequestAddress := romRequestAddress
                regRequestRegion := VecInit(romRequests)
                regRequestEligible := !romRequestIsData && io.prefetchEnabled
              } .otherwise {
                // TODO: see about starting the rom request(?) immediately
                regState := State.Idle
              }
            }
          }

          when (io.enable) {
            regPrefetchRequest := false.B

            // Flush the buffer, because this is the word at the *end*.
            buffer.io.flush := true.B
            regBufferAddress := regRequestAddress + 1.U
          }
        }
      } .elsewhen (hasRomRequest) {
        when (bufferCanSatisfyRequest) {
          // This request can come from the prefetch buffer!
          logger.debug(cf"active: got buffer request: addr=${romRequestAddress << 1}%x | x=${regBufferRequest}")
          when (io.enable) {
            regBufferRequest := true.B
            regBufferAddress := regBufferAddress + 1.U
          }
          when (romInitiator.done) {
            canCompletePrefetch := true.B
          }
        } .elsewhen (regRequestAddress === romRequestAddress && !romRequestIsData) {
          // Bus is requesting the halfword we're currently prefetching.
          logger.info(cf"Request for current fetch addr=${romRequestAddress << 1}%x. done=${romInitiator.done}")

          when (io.enable) {
            when (romInitiator.done) {
              // We're done this cycle, which means we won't be able to directly forward it next cycle.
              // So put it into the buffer, and mark it as being served next cycle.
              regBufferRequest := true.B
              buffer.io.writeEnable := true.B
              buffer.io.writeData := romInitiator.dataRead
              // And flush the buffer, because otherwise this would be the *last* entry.
              buffer.io.flush := true.B
              regBufferAddress := regRequestAddress + 1.U
              // And continue the prefetch
              romInitiator.address := regRequestAddress + 1.U
              regRequestAddress := regRequestAddress + 1.U
            } .otherwise {
              regPrefetchRequest := true.B
            }
          }
        } .otherwise {
          // Request for something else, abort current prefetch.
          logger.debug("Abort prefetch (ROM request)")
          abortRequest := true.B
          when (io.enable) {
            buffer.io.flush := true.B
            regState := State.Idle
          }
        }
      } .elsewhen (hasRamRequest) {
        // Cartridge ram request came in, abort current prefetch
        logger.debug("Abort prefetch (RAM request)")
        abortRequest := true.B
        when (io.enable) {
          buffer.io.flush := true.B
          regState := State.Idle
        }
      } .otherwise {
        canCompletePrefetch := true.B
      }


      when (canCompletePrefetch && romInitiator.done) {
        logger.debug(cf"Prefetched addr=${(regRequestAddress) << 1}%x data=${romInitiator.dataRead}%x, continuing")

        when (buffer.io.full) {
          // TODO: do we have to stop if at a page boundary?
          logger.debug(cf"buffer full, stopping")
          when (io.enable) {
            regState := State.Idle
          }
          // Make sure the burst ends.
          abortRequest := true.B
        } .otherwise {
          when (io.enable) {
            buffer.io.writeEnable := true.B
            buffer.io.writeData := romInitiator.dataRead
            // logger.debug(cf"     prefetched: addr=${regRequestAddress << 1}%x data=${romInitiator.dataRead}%x")
          }
        }

        romInitiator.address := regRequestAddress + 1.U
        when (io.enable) {
          regRequestAddress := regRequestAddress + 1.U
        }
      }
    }
  }
}
