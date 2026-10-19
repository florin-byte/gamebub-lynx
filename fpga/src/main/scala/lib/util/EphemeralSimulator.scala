package lib.util

import chisel3.RawModule
import chisel3.simulator._
import chisel3.testing.HasTestingDirectory

/// Based off of chisel3.simulator.EphemeralSimulator, with additional Verilator options
object EphemeralSimulator extends PeekPokeAPI {
  implicit val verilator: HasSimulator = HasSimulator.simulators
    .verilator(verilatorSettings =
      svsim.verilator.Backend.CompilationSettings.default.withDisabledWarnings(Seq("WIDTHEXPAND"))
    )

  private val chiselSim = new ChiselSim {}

  def simulate[T <: RawModule](
    module: => T,
    layerControl: LayerControl.Type = LayerControl.EnableAll
  )(body: (T) => Unit): Unit = {
    implicit val temporary: HasTestingDirectory = HasTestingDirectory.temporary(deleteOnExit = true)
    chiselSim.simulateRaw(
      module,
      settings = Settings.defaultRaw[T].copy(verilogLayers = layerControl)
    )(body)
  }
}
