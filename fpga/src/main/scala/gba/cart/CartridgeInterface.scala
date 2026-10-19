package gba.cart

import chisel3._

/// Physical GBA cartridge interface, with some additional signals to help with emulated cartridges.
class CartridgeInterface extends Bundle {
  /// System clock (selectable)
  val phi = Output(Bool())
  /// Write select
  val nWR = Output(Bool())
  /// Read select
  val nRD = Output(Bool())
  /// ROM chip select
  val nCS = Output(Bool())
  /// AD0-15: Lower 16-bit of the address / 16-bit ROM data
  val ADLoIn = Input(UInt(16.W))
  val ADLoOut = Output(UInt(16.W))
  val ADLoDir = Output(Bool())  // 1 for output, 0 for input
  /// A16-23: Upper 8-bit of ROM address / 16-bit SRAM data
  val AHiIn = Input(UInt(8.W))
  val AHiOut = Output(UInt(8.W))
  val AHiDir = Output(Bool())  // 1 for output, 0 for input
  /// SRAM chip select
  val nCS2 = Output(Bool())
  /// Interrupt request or DMA request (active-high)
  val IRQ = Input(Bool())

  /// Non-physical signals to improve timing of emulated cartridge.

  /// Whether a new address has been placed on the bus.
  /// This is redundant information: a cycle later, nCS or nCS2 will drop low. However, this
  /// allows the emulated cartridge to start handling an access earlier.
  val reqStart = Output(Bool())
  /// Whether this request is for ROM.
  val reqRom = Output(Bool())
  /// Whether this request will be a read (0) or write (1)
  val reqWrite = Output(Bool())
  /// The address of the request
  val reqAddress = Output(UInt(24.W))
  /// Whether the current read request will be sampled at the next clock cycle.
  /// If this is high, and the read data is not yet valid, the whole system should be disabled until it is ready.
  val reqEnd = Output(Bool())
  /// Set to true if this is an emulated cartridge: read data will be sampled
  /// one cycle later allowing for improved timing.
  val isEmulated = Input(Bool())
}