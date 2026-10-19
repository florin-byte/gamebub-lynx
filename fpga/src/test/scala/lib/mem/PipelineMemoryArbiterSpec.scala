package lib.mem

import chisel3._
import lib.log.Log
import lib.util.EphemeralSimulator._
import org.scalatest.funsuite.AnyFunSuite


class PipelineMemoryArbiterSpec extends AnyFunSuite {
  private class Harness(dut: PipelineMemoryArbiter) {
    val io = dut.io
    def step(n: Int = 1) = dut.clock.step(n)

    def isInitiatorReady(i: Int): Boolean = dut.io.initiator(i).ready.peek().litToBoolean
    def setInitiatorIdle(i: Int): Unit = dut.io.initiator(i).enable.poke(false)
    def setInitiatorRead(i: Int, address: BigInt): Unit = {
      dut.io.initiator(i).enable.poke(true)
      dut.io.initiator(i).address.poke(address)
      dut.io.initiator(i).isWrite.poke(false)
    }
    def setInitiatorWrite(i: Int, address: BigInt): Unit = {
      dut.io.initiator(i).enable.poke(true)
      dut.io.initiator(i).address.poke(address)
      dut.io.initiator(i).isWrite.poke(true)
    }
    def getInitiatorReadData(i: Int): Option[BigInt] = {
      if (isInitiatorReady(i)) {
        Some(dut.io.initiator(i).dataRead.peek().litValue)
      } else {
        None
      }
    }
    def setInitiatorWriteData(i: Int, data: BigInt): Unit = dut.io.initiator(i).dataWrite.poke(data)
    def getTargetRequest: Option[(BigInt, Boolean)] = {
      if (dut.io.target.enable.peek().litToBoolean) {
        Some((dut.io.target.address.peek().litValue, dut.io.target.isWrite.peek().litToBoolean))
      } else {
        None
      }
    }
    def setTargetReady(ready: Boolean): Unit = dut.io.target.ready.poke(ready)
    def setTargetReadData(data: BigInt): Unit = dut.io.target.dataRead.poke(data)
    def getTargetWriteData(): BigInt = dut.io.target.dataWrite.peek().litValue

    // Initial state and reset
    for (initiator <- dut.io.initiator) {
      initiator.enable.poke(false)
    }
    dut.io.target.ready.poke(true)
    dut.reset.poke(true)
    dut.clock.step()
    dut.reset.poke(false)
  }

  private def go()(body: Harness => Unit): Unit = {
    Log.setDefaultLevel(Log.Level.Debug)
    simulate(new PipelineMemoryArbiter(addressWidth = 32, dataWidth = 32, n = 3)) { dut =>
      val harness = new Harness(dut)
      body(harness)
    }
  }

  test("idle") {
    go() { dut =>
      for (_ <- 0 until 10) {
        for (initiator <- dut.io.initiator) {
          assert(initiator.ready.peek().litToBoolean)
        }
        assert(!dut.io.target.enable.peek().litToBoolean)
        dut.step()
      }
    }
  }

  test("simple read") {
    go() { dut =>
      // Initiator 0 reads from 0xA000
      dut.setInitiatorRead(0, 0xA000)
      assert(dut.isInitiatorReady(0))
      assert(dut.isInitiatorReady(1))
      assert(dut.getTargetRequest.contains((0xA000, false)))
      dut.step()
      // It completes immediately
      dut.setTargetReady(true)
      dut.setTargetReadData(0xD000)
      dut.setInitiatorIdle(0)
      assert(dut.isInitiatorReady(0))
      assert(dut.getInitiatorReadData(0).contains(0xD000))
      dut.step()
      // Everyone is ready after
      assert(dut.isInitiatorReady(0))
      assert(dut.isInitiatorReady(1))
    }
  }

  test("read delayed") {
    go() { dut =>
      // Initiator 0 reads from 0xA000
      dut.setInitiatorRead(0, 0xA000)
      assert(dut.isInitiatorReady(0))
      assert(dut.isInitiatorReady(1))
      assert(dut.getTargetRequest.contains((0xA000, false)))
      dut.step()

      // It takes a few cycles to complete
      dut.setTargetReady(false)
      dut.setInitiatorIdle(0)
      for (_ <- 0 until 3) {
        assert(!dut.isInitiatorReady(0))
        assert(dut.isInitiatorReady(1))
        dut.step()
      }

      // Then it completes
      dut.setTargetReady(true)
      dut.setTargetReadData(0xD000)
      assert(dut.getInitiatorReadData(0).contains(0xD000))
      dut.step()
      assert(dut.isInitiatorReady(0))
      assert(dut.isInitiatorReady(1))
    }
  }

  test("read at the same time") {
    go() { dut =>
      // Initiator 0 and 1 both read
      dut.setInitiatorRead(0, 0xA000)
      dut.setInitiatorRead(1, 0xA100)
      assert(dut.isInitiatorReady(0))
      assert(dut.isInitiatorReady(1))
      assert(dut.getTargetRequest.contains((0xA000, false)))
      dut.step()

      // It completes, and arbiter sends the second one
      dut.setTargetReady(true)
      dut.setTargetReadData(0xD000)
      dut.setInitiatorIdle(0)
      dut.setInitiatorIdle(1)
      assert(dut.getInitiatorReadData(0).contains(0xD000))
      assert(!dut.isInitiatorReady(1))
      assert(dut.getTargetRequest.contains((0xA100, false)))
      dut.step()

      // Then it completes
      dut.setTargetReady(true)
      dut.setTargetReadData(0xD100)
      assert(dut.isInitiatorReady(0))
      assert(dut.getInitiatorReadData(1).contains(0xD100))
      dut.step()
      assert(dut.isInitiatorReady(0))
      assert(dut.isInitiatorReady(1))
    }
  }

  test("read P0 when P1 busy") {
    go() { dut =>
      // Initiator 1 starts a read
      dut.setInitiatorRead(1, 0xA100)
      assert(dut.isInitiatorReady(0))
      assert(dut.isInitiatorReady(1))
      assert(dut.getTargetRequest.contains((0xA100, false)))
      dut.step()

      // It takes a few cycles to complete
      dut.setTargetReady(false)
      dut.setInitiatorIdle(1)
      for (_ <- 0 until 3) {
        assert(dut.isInitiatorReady(0))
        assert(!dut.isInitiatorReady(1))
        dut.step()
      }
      // Initiator 0 starts a read too, arbiter sends it
      dut.setInitiatorRead(0, 0xA000)
      assert(dut.isInitiatorReady(0))
      assert(dut.getTargetRequest.contains((0xA000, false)))
      dut.step()
      dut.setInitiatorIdle(0)

      // Initiator 1 starts a second read (while first is going)
      dut.setInitiatorRead(1, 0xA101)
      for (_ <- 0 until 2) {
        assert(!dut.isInitiatorReady(0))
        assert(!dut.isInitiatorReady(1))
        dut.step()
      }

      // Initiator 1's first request completes
      // ... and its second request starts because ready=true
      dut.setTargetReady(true)
      dut.setTargetReadData(0xD100)
      assert(dut.getTargetRequest.contains((0xA000, false)))
      assert(!dut.isInitiatorReady(0))
      assert(dut.getInitiatorReadData(1).contains(0xD100))
      dut.step()

      // Initiator 0's request completes
      dut.setTargetReady(true)
      dut.setTargetReadData(0xD000)
      assert(dut.getInitiatorReadData(0).contains(0xD000))
      assert(!dut.isInitiatorReady(1))
      assert(dut.getTargetRequest.contains((0xA101, false)))
      dut.setInitiatorIdle(1)
      dut.step()

      // Then initiator 1's second one completes
      dut.setTargetReady(true)
      dut.setTargetReadData(0xD101)
      assert(dut.getInitiatorReadData(1).contains(0xD101))
      assert(dut.isInitiatorReady(0))
      assert(dut.isInitiatorReady(1))
      dut.step()

      assert(dut.isInitiatorReady(0))
      assert(dut.isInitiatorReady(1))
    }
  }

  test("simple write") {
    go() { dut =>
      // Initiator 0 writes to 0xA000
      dut.setInitiatorWrite(0, 0xA000)
      assert(dut.isInitiatorReady(0))
      assert(dut.isInitiatorReady(1))
      assert(dut.getTargetRequest.contains((0xA000, true)))
      dut.step()
      // It completes immediately
      dut.setInitiatorWriteData(0, 0xD000)
      dut.setTargetReady(true)
      assert(dut.getTargetWriteData() === 0xD000)
      dut.setInitiatorIdle(0)
      assert(dut.isInitiatorReady(0))
      dut.step()
      // Everyone is ready after
      assert(dut.isInitiatorReady(0))
      assert(dut.isInitiatorReady(1))
    }
  }

  test("write at the same time") {
    go() { dut =>
      // Initiator 0 and 1 both write
      dut.setInitiatorWrite(0, 0xA000)
      dut.setInitiatorWrite(1, 0xA100)
      assert(dut.isInitiatorReady(0))
      assert(dut.isInitiatorReady(1))
      assert(dut.getTargetRequest.contains((0xA000, true)))
      dut.step()

      // It completes, and arbiter sends the second one
      dut.setTargetReady(true)
      dut.setInitiatorIdle(0)
      dut.setInitiatorWriteData(0, 0xD000)
      dut.setInitiatorIdle(1)
      dut.setInitiatorWriteData(1, 0xD100)
      assert(!dut.isInitiatorReady(1))
      assert(dut.getTargetWriteData() === 0xD000)
      dut.step()

      // Then it completes
      dut.setTargetReady(true)
      assert(dut.isInitiatorReady(1))
      assert(dut.getTargetWriteData() === 0xD100)
      dut.step()

      assert(dut.isInitiatorReady(0))
      assert(dut.isInitiatorReady(1))
    }
  }
}
