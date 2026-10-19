package lib.mem

import chisel3._

/// Pipelined memory interface (like AHB-Lite)
///
/// Request parameters (address, isWrite, writeStrobe) are sent on the first bus
/// cycle, and the read data / write data is sent on the next bus cycle.
///
/// When 'ready' is false, the request parameters must be held stable.
/// The exception is that 'request' can go from 0 to 1 to start a request.
class PipelineMemoryInterface(addressWidth: Int, dataWidth: Int) extends Bundle {
  /// Ready: high when a bus cycle is to proceed
  val ready = Output(Bool())

  /// True if a new request is being requested this bus cycle.
  val enable = Input(Bool())
  /// Request address
  val address = Input(UInt(addressWidth.W))
  /// Whether the access is a wrote
  val isWrite = Input(Bool())
  /// Write data byte strobe. Not supported by all targets.
  val writeStrobe = Input(UInt((dataWidth / 8).W))

  /// Read data
  val dataRead = Output(UInt(dataWidth.W))
  /// Write data. Held on the bus until
  val dataWrite = Input(UInt(dataWidth.W))
}
