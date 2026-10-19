package gba

import lib.util.EphemeralSimulator._
import org.scalatest.funsuite.AnyFunSuite

class GbaSpec extends AnyFunSuite {
  test("run") {
    simulate(new GBA) { dut =>
      dut.io.enable.poke(true)
      dut.reset.poke(true)
      dut.clock.step()
      dut.reset.poke(false)

      for (_ <- 0 until 100) {
        dut.clock.step()
      }
    }
  }
}