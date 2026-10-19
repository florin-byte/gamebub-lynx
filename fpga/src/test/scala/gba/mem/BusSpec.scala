package gba.mem

import chisel3._
import gba.mem.BusAccessWidth
import lib.util.EphemeralSimulator._
import org.scalatest.funsuite.AnyFunSuite

case class TargetAccess(
  address: BigInt,
  size: BusAccessWidth.Type = BusAccessWidth.Word,
  write: Boolean = false,
  sequential: Boolean = false,
)

class BusSpec extends AnyFunSuite {
  private def makeBus(): Bus = {
    new Bus(Seq(
      BusTarget("TargetA", 1.U(4.W), BusAccessWidth.Word),
      BusTarget("TargetB", 2.U(4.W), BusAccessWidth.Word),
      BusTarget("TargetC", 3.U(4.W), BusAccessWidth.Halfword),
    ))
  }

  private class BusHarness(dut: Bus) {
    val targets = 3

    dut.io.enable.poke(true)
    dut.io.initiatorPort.MREQ.poke(false)
    dut.io.initiatorPort.SEQ.poke(false)
    for (i <- 0 until targets) {
      dut.io.targetPort(i).done.poke(false)
    }

    def setAccess(
      address: Int,
      size: BusAccessWidth.Type = BusAccessWidth.Word,
      write: Boolean = false,
      sequential: Boolean = false): Unit = {
      dut.io.initiatorPort.ADDR.poke(address)
      dut.io.initiatorPort.WRITE.poke(write)
      dut.io.initiatorPort.SIZE.poke(size)
      dut.io.initiatorPort.MREQ.poke(true)
      dut.io.initiatorPort.SEQ.poke(sequential)
    }

    def getReadData(): BigInt = {
      dut.io.initiatorPort.RDATA.peek().litValue
    }

    def getTargetAccess(i: Int): Option[TargetAccess] = {
      if (dut.io.targetPort(i).request.peek().litToBoolean) {
        val address = dut.io.targetPort(i).address.peek().litValue & 0xFFFFFF
        val write = dut.io.targetPort(i).write.peek().litToBoolean
        val sequential = dut.io.targetPort(i).sequential.peek().litToBoolean
        val size = dut.io.targetPort(i).size.peekValue().asBigInt.toInt match {
          case 0 => BusAccessWidth.Byte
          case 1 => BusAccessWidth.Halfword
          case 2 => BusAccessWidth.Word
          case _ => throw new Exception("invalid value")
        }
        Some(TargetAccess(address, size, write, sequential))
      } else {
        None
      }
    }

    def setWriteData(data: BigInt) = {
      dut.io.initiatorPort.WDATA.poke(data)
    }

    def getTargetDataWrite(i: Int): BigInt = {
      dut.io.targetPort(i).dataWrite.peek().litValue
    }

    def setTargetDone(i: Int, dataRead: BigInt = 0): Unit = {
      dut.io.targetPort(i).dataRead.poke(dataRead)
      dut.io.targetPort(i).done.poke(true)
    }

    def getClockEn(): Boolean = {
      dut.io.initiatorPort.CLKEN.peek().litToBoolean
    }

    def step(resetAccess: Boolean = true): Unit = {
      dut.clock.step()

      if (resetAccess) {
        // Reset some state so accesses don't continue by default
        dut.io.initiatorPort.MREQ.poke(false)
        dut.io.initiatorPort.SEQ.poke(false)
        for (i <- 0 until targets) {
          dut.io.targetPort(i).done.poke(false)
        }
      }
    }
  }

  test("single-cycle read") {
    simulate(makeBus()) { dut =>
      val bus = new BusHarness(dut)

      // No requests at the start
      assert(bus.getTargetAccess(0).isEmpty)
      assert(bus.getTargetAccess(1).isEmpty)
      assert(bus.getTargetAccess(2).isEmpty)

      // Start a read from TargetA
      bus.setAccess(address = 0x01_00ABC0)

      // Check that TargetA has a request
      assert(bus.getTargetAccess(0).contains(TargetAccess(0xABC0)))
      assert(bus.getTargetAccess(1).isEmpty)
      assert(bus.getTargetAccess(2).isEmpty)

      // Clock, check that the request is not satisfied.
      bus.step()
      assert(!bus.getClockEn())

      // Mark the request as done:
      bus.setTargetDone(0, 0xABCD1234)
      assert(bus.getClockEn())
      assert(bus.getReadData() === 0xABCD1234L)

      // Bus should be free next cycle.
      bus.step()
      assert(dut.io.initiatorPort.CLKEN.peek().litToBoolean)
    }
  }

  test("multi-cycle read") {
    simulate(makeBus()) { dut =>
      val bus = new BusHarness(dut)

      // Start a read from TargetA
      bus.setAccess(address = 0x01_000000)
      assert(bus.getTargetAccess(0).contains(TargetAccess(0x0)))

      // Clock a few times, check that the request is not satisfied.
      for (_ <- 0 until 4) {
        bus.step()
        bus.setAccess(address = 0x02_000000) // Set a new access somewhere else
        assert(bus.getTargetAccess(0).contains(TargetAccess(0x0)))
        assert(!bus.getClockEn())
      }

      // Mark the request as done:
      bus.setTargetDone(0, 0xABCD1234)
      assert(bus.getClockEn())
      assert(bus.getReadData() === 0xABCD1234L)
    }
  }

  /// Repeated, pipelined single-cycle accesses on a single target
  test("repeated accesses") {
    simulate(makeBus()) { dut =>
      val bus = new BusHarness(dut)

      // Start Read 1 from TargetB
      bus.setAccess(address = 0x02_000FF0)
      assert(bus.getTargetAccess(1).contains(TargetAccess(0xFF0)))
      assert(bus.getClockEn())
      bus.step()

      // Read 1, Start read 2
      bus.setTargetDone(1, 0x0001)
      assert(bus.getClockEn())
      assert(bus.getReadData() === 0x0001)
      bus.setAccess(address = 0x02_000FF4)
      assert(bus.getTargetAccess(1).contains(TargetAccess(0xFF4)))
      bus.step()

      // Read 2, Start read 3
      bus.setTargetDone(1, 0x0002)
      assert(bus.getClockEn())
      assert(bus.getReadData() === 0x0002)
      bus.setAccess(address = 0x02_000FF8)
      assert(bus.getTargetAccess(1).contains(TargetAccess(0xFF8)))
      bus.step()

      // Read 3
      bus.setTargetDone(1, 0x0003)
      assert(bus.getClockEn())
      assert(bus.getReadData() === 0x0003)
      bus.step()
    }
  }

  /// Addresses are force-aligned
  test("align addresses") {
    simulate(makeBus()) { dut =>
      val bus = new BusHarness(dut)

      // Word
      bus.setAccess(address = 0x01_000000, size = BusAccessWidth.Word)
      assert(bus.getTargetAccess(0).contains(TargetAccess(0x0, BusAccessWidth.Word)))
      bus.setAccess(address = 0x01_000001, size = BusAccessWidth.Word)
      assert(bus.getTargetAccess(0).contains(TargetAccess(0x0, BusAccessWidth.Word)))
      bus.setAccess(address = 0x01_000002, size = BusAccessWidth.Word)
      assert(bus.getTargetAccess(0).contains(TargetAccess(0x0, BusAccessWidth.Word)))
      bus.setAccess(address = 0x01_000003, size = BusAccessWidth.Word)
      assert(bus.getTargetAccess(0).contains(TargetAccess(0x0, BusAccessWidth.Word)))
      bus.setAccess(address = 0x01_000004, size = BusAccessWidth.Word)
      assert(bus.getTargetAccess(0).contains(TargetAccess(0x4, BusAccessWidth.Word)))

      // Halfword
      bus.setAccess(address = 0x01_000000, size = BusAccessWidth.Halfword)
      assert(bus.getTargetAccess(0).contains(TargetAccess(0x0, BusAccessWidth.Halfword)))
      bus.setAccess(address = 0x01_000001, size = BusAccessWidth.Halfword)
      assert(bus.getTargetAccess(0).contains(TargetAccess(0x0, BusAccessWidth.Halfword)))
      bus.setAccess(address = 0x01_000002, size = BusAccessWidth.Halfword)
      assert(bus.getTargetAccess(0).contains(TargetAccess(0x2, BusAccessWidth.Halfword)))
      bus.setAccess(address = 0x01_000003, size = BusAccessWidth.Halfword)
      assert(bus.getTargetAccess(0).contains(TargetAccess(0x2, BusAccessWidth.Halfword)))
      bus.setAccess(address = 0x01_000004, size = BusAccessWidth.Halfword)
      assert(bus.getTargetAccess(0).contains(TargetAccess(0x4, BusAccessWidth.Halfword)))

      // Byte
      bus.setAccess(address = 0x01_000000, size = BusAccessWidth.Byte)
      assert(bus.getTargetAccess(0).contains(TargetAccess(0x0, BusAccessWidth.Byte)))
      bus.setAccess(address = 0x01_000001, size = BusAccessWidth.Byte)
      assert(bus.getTargetAccess(0).contains(TargetAccess(0x1, BusAccessWidth.Byte)))
      bus.setAccess(address = 0x01_000002, size = BusAccessWidth.Byte)
      assert(bus.getTargetAccess(0).contains(TargetAccess(0x2, BusAccessWidth.Byte)))
      bus.setAccess(address = 0x01_000003, size = BusAccessWidth.Byte)
      assert(bus.getTargetAccess(0).contains(TargetAccess(0x3, BusAccessWidth.Byte)))
      bus.setAccess(address = 0x01_000004, size = BusAccessWidth.Byte)
      assert(bus.getTargetAccess(0).contains(TargetAccess(0x4, BusAccessWidth.Byte)))
    }
  }

  test("multiple targets") {
    simulate(makeBus()) { dut =>
      val bus = new BusHarness(dut)

      // Start Read 1 from TargetA
      bus.setAccess(address = 0x01_123000)
      assert(bus.getTargetAccess(0).contains(TargetAccess(0x123000)))
      assert(bus.getTargetAccess(1).isEmpty)
      assert(bus.getTargetAccess(2).isEmpty)
      assert(bus.getClockEn())
      bus.step()

      // Read 1, Start read 2
      assert(!bus.getClockEn())
      bus.setTargetDone(0, 0x0001)
      assert(bus.getClockEn())
      assert(bus.getReadData() == 0x0001)
      bus.setAccess(address = 0x02_ABC000)
      assert(bus.getTargetAccess(0).isEmpty)
      assert(bus.getTargetAccess(1).contains(TargetAccess(0xABC000)))
      assert(bus.getTargetAccess(2).isEmpty)
      bus.step()

      // Read 2, Start read 3
      bus.setTargetDone(1, 0x0002)
      assert(bus.getClockEn())
      assert(bus.getReadData() == 0x0002)
      bus.setAccess(address = 0x03_DEF000, size = BusAccessWidth.Halfword)
      assert(bus.getTargetAccess(0).isEmpty)
      assert(bus.getTargetAccess(1).isEmpty)
      assert(bus.getTargetAccess(2).contains(TargetAccess(0xDEF000, size = BusAccessWidth.Halfword)))
      bus.step()

      // Read 3
      bus.setTargetDone(2, 0x0003)
      assert(bus.getClockEn())
      assert(bus.getReadData() == 0x0003_0003)
      bus.step()
    }
  }

  test("split write") {
    simulate(makeBus()) { dut =>
      val bus = new BusHarness(dut)

      // Do a 32-bit write to target C
      bus.setAccess(address = 0x03_ABC000, size = BusAccessWidth.Word, write = true, sequential = false)
      assert(bus.getTargetAccess(0).isEmpty)
      assert(bus.getTargetAccess(1).isEmpty)
      assert(bus.getTargetAccess(2).contains(TargetAccess(0xABC000, size = BusAccessWidth.Halfword, write = true, sequential = false)))
      assert(bus.getClockEn())
      bus.step(resetAccess = false)

      bus.setWriteData(0xABCD1234)
      assert(bus.getTargetDataWrite(2) == BigInt(0x1234))
      bus.setTargetDone(2)
      assert(!bus.getClockEn())
      assert(bus.getTargetAccess(2).contains(TargetAccess(0xABC002, size = BusAccessWidth.Halfword, write = true, sequential = true)))
      bus.step()

      assert(bus.getTargetDataWrite(2) == BigInt(0xABCD))
      bus.setTargetDone(2)
      assert(bus.getClockEn())
      bus.step()
    }
  }

  test("split read") {
    simulate(makeBus()) { dut =>
      val bus = new BusHarness(dut)

      // Do a 32-bit read from target C
      bus.setAccess(address = 0x03_DEF000, size = BusAccessWidth.Word, write = false, sequential = false)
      assert(bus.getTargetAccess(0).isEmpty)
      assert(bus.getTargetAccess(1).isEmpty)
      assert(bus.getTargetAccess(2).contains(TargetAccess(0xDEF000, size = BusAccessWidth.Halfword, write = false, sequential = false)))
      assert(bus.getClockEn())
      bus.step(resetAccess = false)

      bus.setTargetDone(2, 0x1234)
      assert(!bus.getClockEn())
      assert(bus.getTargetAccess(2).contains(TargetAccess(0xDEF002, size = BusAccessWidth.Halfword, write = false, sequential = true)))
      bus.step()

      bus.setTargetDone(2, 0xABCD)
      assert(bus.getReadData() == 0xABCD1234L)
      assert(bus.getClockEn())
      bus.step()
    }
  }

  test("split read with delay") {
    simulate(makeBus()) { dut =>
      val bus = new BusHarness(dut)

      // Do a 32-bit read from target C
      bus.setAccess(address = 0x03_DEF000, size = BusAccessWidth.Word, write = false, sequential = false)
      assert(bus.getTargetAccess(0).isEmpty)
      assert(bus.getTargetAccess(1).isEmpty)
      assert(bus.getTargetAccess(2).contains(TargetAccess(0xDEF000, size = BusAccessWidth.Halfword, write = false, sequential = false)))
      assert(bus.getClockEn())
      bus.step(resetAccess = false)

      // Step a few times without being complete
      dut.io.targetPort(2).done.poke(false)
      for (_ <- 0 until 3) {
        assert(!bus.getClockEn())
        assert(bus.getTargetAccess(2).contains(TargetAccess(0xDEF000, size = BusAccessWidth.Halfword, write = false, sequential = false)))
        bus.step(resetAccess = false)
      }

      // Complete the first access.
      bus.setTargetDone(2, 0x1234)
      assert(!bus.getClockEn())
      assert(bus.getTargetAccess(2).contains(TargetAccess(0xDEF002, size = BusAccessWidth.Halfword, write = false, sequential = true)))
      bus.step(resetAccess = false)

      // Step a few times without being complete
      dut.io.targetPort(2).done.poke(false)
      for (_ <- 0 until 3) {
        assert(!bus.getClockEn())
        assert(bus.getTargetAccess(2).contains(TargetAccess(0xDEF002, size = BusAccessWidth.Halfword, write = false, sequential = true)))
        bus.step(resetAccess = false)
      }

      // Complete and make sure next access works.
      bus.setTargetDone(2, 0xABCD)
      assert(bus.getReadData() == 0xABCD1234L)
      assert(bus.getClockEn())
      bus.setAccess(address = 0x03_444000, size = BusAccessWidth.Halfword, write = false, sequential = false)
      assert(bus.getTargetAccess(2).contains(TargetAccess(0x444000, size = BusAccessWidth.Halfword)))
      bus.step()
    }
  }
}
