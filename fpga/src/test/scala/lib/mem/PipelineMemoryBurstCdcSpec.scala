package lib.mem

import chisel3._
import chisel3.util.log2Ceil
import lib.log.Log
import lib.mem.PipelineMemoryBurstCdcSpec.Wrapper
import lib.util.EphemeralSimulator._
import org.scalatest.funsuite.AnyFunSuite


object PipelineMemoryBurstCdcSpec {
  /// Wrapper around PipelineMemoryInterface that handles the clock divider
  class Wrapper(val multiplier: Int = 4, registered: Boolean = false) extends Module {
    val addressWidth = 32
    val dataWidth = 32
    val io = IO(new Bundle {
      val initiator = new PipelineMemoryInterface(addressWidth, dataWidth)
      val target = Flipped(new PipelineMemoryInterface(addressWidth, dataWidth))
    })
    val module = Module(new PipelineMemoryBurstCdc(addressWidth, dataWidth, enablePrefetch = !registered, registeredResponses = registered))

    val counter = RegInit((multiplier / 2).U(log2Ceil(multiplier).W)) // Start high
    counter := counter + 1.U
    module.io.slowClock := counter(log2Ceil(multiplier) - 1).asClock
    module.io.initiator <> io.initiator
    module.io.target <> io.target
  }
}

class PipelineMemoryBurstCdcSpec extends AnyFunSuite {
  private class Harness(dut: Wrapper) {
    val clockMultiplier = 4
    val io = dut.io

    private var numCycles = 0

    def step(cycles: Int = 1): Unit = {
      dut.clock.step(cycles)
      numCycles += cycles
    }

    def isInitiatorReady: Boolean = dut.io.initiator.ready.peek().litToBoolean
    def isTargetEnable: Boolean = dut.io.target.enable.peek().litToBoolean
    def isTargetWrite: Boolean = dut.io.target.isWrite.peek().litToBoolean
    def getInitiatorReadData: Option[BigInt] = {
      if (isInitiatorReady) {
        Some(dut.io.initiator.dataRead.peek().litValue)
      } else {
        None
      }
    }
    def setInitiatorIdle(): Unit = {
      dut.io.initiator.enable.poke(false)
    }
    def setInitiatorRead(address: BigInt): Unit = {
      dut.io.initiator.enable.poke(true)
      dut.io.initiator.address.poke(address)
      dut.io.initiator.isWrite.poke(false)
    }
    def setInitiatorWrite(address: BigInt): Unit = {
      dut.io.initiator.enable.poke(true)
      dut.io.initiator.address.poke(address)
      dut.io.initiator.isWrite.poke(true)
    }
    def setInitiatorWriteData(data: BigInt): Unit = {
      dut.io.initiator.dataWrite.poke(data)
    }
    def getTargetReadAddress: Option[BigInt] = {
      if (isTargetEnable && !isTargetWrite) {
        Some(dut.io.target.address.peek().litValue)
      } else {
        None
      }
    }

    // Initial state and reset
    dut.io.initiator.enable.poke(false)
    dut.io.target.ready.poke(true)
    dut.reset.poke(true)
    step(clockMultiplier)
    dut.reset.poke(false)
  }

  private def go()(body: Harness => Unit): Unit = {
    Log.setDefaultLevel(Log.Level.Info)
    simulate(new Wrapper) { dut =>
      val harness = new Harness(dut)
      body(harness)
    }
  }

  test("idle") {
    go() { dut =>
      for (_ <- 0 until 40) {
        assert(dut.isInitiatorReady)
        assert(!dut.isTargetEnable)
        dut.step()
      }
    }
  }

  test("read") {
    go() { dut =>
      // Start a read request
      dut.setInitiatorRead(0xA000)
      dut.step(4)
      dut.setInitiatorIdle()
      assert(!dut.isInitiatorReady) // not ready

      // Target should have gotten that request
      assert(dut.isTargetEnable)
      assert(dut.io.target.address.peek().litValue == 0xA000)
      assert(!dut.io.target.isWrite.peek().litToBoolean)
      dut.step(1)
      assert(!dut.isTargetEnable)
      dut.io.target.ready.poke(false)
      dut.step(3)

      // Wait another few cycles before fulfilling (partway through the slow cycle)
      dut.step(6)
      dut.io.target.dataRead.poke(0xD000)
      dut.io.target.ready.poke(true)
      assert(!dut.isInitiatorReady) // Not ready yet...
      dut.step(2)

      // Initiator receives data now
      assert(dut.getInitiatorReadData.contains(0xD000))

      // Fast should prefetch the next word in the burst
      assert(dut.isTargetEnable)
      assert(dut.io.target.address.peek().litValue == 0xA001)
      dut.step(1)
      assert(!dut.isTargetEnable)
      dut.io.target.ready.poke(false)
      dut.step(3 + 4 + 2)
      assert(!dut.isTargetEnable)
      dut.io.target.ready.poke(true)
      dut.io.target.dataRead.poke(0xD001)
      dut.step(2)

      // Bunch of idle time
      for (_ <- 0 until 20) {
        assert(dut.isInitiatorReady)
        assert(!dut.isTargetEnable)
        dut.step(1)
      }
      System.err.println("--- end of idle --- ")

      // Start the next read request
      dut.setInitiatorRead(0xA001)
      dut.step(4)

      // And do several sequential reads (should all be single cycle)
      for (i <- 1 until 4) {
        System.err.println("---- " + i)
        // It should complete in the next cycle (since already prefetched)
        assert(dut.getInitiatorReadData.contains(0xD000 + i))
        // And start the next read
        dut.setInitiatorRead(0xA000 + i + 1)
        // Fast will do the next prefetch
        assert(dut.isTargetEnable)
        assert(dut.io.target.address.peek().litValue == (0xA000 + i + 1))
        dut.step(1)
        assert(!dut.isTargetEnable)
        dut.io.target.ready.poke(false)
        dut.step(1)
        assert(!dut.isTargetEnable)
        dut.io.target.ready.poke(true)
        dut.io.target.dataRead.poke(0xD000 + i + 1)
        dut.step(1)
        dut.io.target.dataRead.poke(0xDEAD0000)
        dut.step(1)
      }

      // Last one finished from the loop.
      System.err.println("* end of sequential read loop")
      assert(dut.getInitiatorReadData.contains(0xD004))

      // Do a non-sequential read now.
      dut.setInitiatorRead(0xA100)
      dut.step(4)
      assert(!dut.isInitiatorReady)
      // Target should have gotten that request
      assert(dut.isTargetEnable)
      assert(dut.io.target.address.peek().litValue == 0xA100)
      assert(!dut.io.target.isWrite.peek().litToBoolean)
      dut.step(1)
      assert(!dut.isTargetEnable)
      dut.io.target.ready.poke(false)
      dut.step(3)
      assert(!dut.isInitiatorReady)

      // Fulfill request at the end of the cycle
      dut.step(4 + 3)
      dut.io.target.dataRead.poke(0xD100)
      dut.io.target.ready.poke(true)
      assert(!dut.isInitiatorReady)
      dut.step(1)
      assert(dut.getInitiatorReadData.contains(0xD100))
    }
  }

  test("write") {
    go() { dut =>
      // Start a write to 0xA000
      dut.setInitiatorWrite(0xA000)
      assert(dut.isInitiatorReady)
      dut.step(4)

      // Next write
      dut.setInitiatorWriteData(0xD000)
      dut.setInitiatorWrite(0xA100)
      assert(dut.isInitiatorReady)
      dut.step(4)

      // Next write
      dut.setInitiatorWriteData(0xD100)
      dut.setInitiatorWrite(0xA200)
      // Fast should now have started the write
      assert(dut.isTargetEnable)
      assert(dut.io.target.address.peek().litValue == 0xA000)
      assert(dut.io.target.isWrite.peek().litToBoolean)
      // Slow is still going because request fifo isn't full yet (3 entries)
      assert(dut.isInitiatorReady)
      dut.step(1)
      dut.io.target.ready.poke(false)
      assert(dut.io.target.dataWrite.peek().litValue == 0xD000)
      dut.step(3)

      // Next write
      dut.setInitiatorWriteData(0xD200)
      dut.setInitiatorWrite(0xA300)
      assert(dut.isInitiatorReady)
      dut.step(4)

      // Next write -- FIFO is now filled up (with A000, A100, A200)
      dut.setInitiatorWriteData(0xD300)
      dut.setInitiatorWrite(0xA400)
      assert(!dut.isInitiatorReady)
      dut.step(4)

      // Wait a while before allowing fast to go through
      System.err.println("** waiting a while")
      for (_ <- 0 until 12) {
        assert(!dut.isInitiatorReady)
        assert(!dut.isTargetEnable)
        assert(dut.io.target.dataWrite.peek().litValue == 0xD000)
        dut.step(1)
      }

      // Complete the write right before the next slow cycle.
      dut.step(2)
      dut.io.target.ready.poke(true)
      // Initiator ready won't go true until the fifo can clear next slow cycle
      assert(!dut.isInitiatorReady)
      dut.step(1)
      assert(!dut.isInitiatorReady)
      dut.step(1)

      System.err.println("begin loop writes")

      // Do a series of writes, should be all single cycle
      // And then drain the FIFO
      for (i <- 0 until 8) {
        assert(dut.isInitiatorReady)
        assert(dut.io.target.enable.peek().litToBoolean)
        assert(dut.io.target.address.peek().litValue == (0xA100 + (i * 0x0100)))
        dut.step(1)
        dut.io.target.ready.poke(false)
        assert(dut.io.target.dataWrite.peek().litValue == (0xD100 + (i * 0x0100)))
        dut.step(1)
        dut.io.target.ready.poke(true)
        dut.step(2)
        dut.setInitiatorIdle()
        if (i < 5) {
          dut.setInitiatorWriteData(0xD400 + (i * 0x0100))
        }
        if (i < 4) {
          // Only do 4 writes this loop
          dut.setInitiatorWrite(0xA500 + (i * 0x0100))
        }
      }
      System.err.println("end loop writes")

      // Everything should be idle now.
      for (_ <- 0 until 20) {
        assert(dut.isInitiatorReady)
        assert(!dut.io.target.enable.peek().litToBoolean)
        dut.step()
      }
    }
  }

  test("read then write") {
    go() { dut =>
      // Start a read request
      dut.setInitiatorRead(0xA000)
      dut.step(4)
      dut.setInitiatorIdle()
      assert(!dut.isInitiatorReady)

      // Handle that request
      assert(dut.isTargetEnable)
      assert(dut.io.target.address.peek().litValue == 0xA000)
      assert(!dut.io.target.isWrite.peek().litToBoolean)
      dut.step(1)
      dut.io.target.ready.poke(true)
      dut.io.target.dataRead.poke(0xD000)
      dut.step(3)

      // Read is returned
      assert(dut.getInitiatorReadData.contains(0xD000))
      // Fast prefetches the next read
      assert(dut.isTargetEnable)
      assert(dut.io.target.address.peek().litValue == 0xA001)
      assert(!dut.io.target.isWrite.peek().litToBoolean)
      dut.step(20)
      System.err.println("done with reads")

      // Now, do a write to a different address
      dut.setInitiatorWrite(0xA100)
      assert(dut.isInitiatorReady)
      dut.step(4)
      dut.setInitiatorWriteData(0xD100)
      dut.setInitiatorIdle()
      assert(dut.isInitiatorReady)
      dut.step(4)

      // Fast should do the write
      assert(dut.isTargetEnable)
      assert(dut.io.target.address.peek().litValue == 0xA100)
      assert(dut.io.target.isWrite.peek().litToBoolean)
      dut.step(1)
      assert(dut.io.target.dataWrite.peek().litValue == 0xD100)
      dut.step(3)
    }
  }

  test("write then read pipelined") {
    go() { dut =>
      // Start a write
      dut.setInitiatorWrite(0xA100)
      assert(dut.isInitiatorReady)
      dut.step(4)

      // And do a read
      dut.setInitiatorWriteData(0xD100)
      dut.setInitiatorRead(0xA200)
      assert(dut.isInitiatorReady)
      dut.step(4)

      // The read was accepted immediately, but not pushed to FIFO last cycle
      // so initiator is blocked for this cycle.
      assert(!dut.isInitiatorReady)
      // Fast should start the write
      assert(dut.isTargetEnable)
      assert(dut.io.target.address.peek().litValue == 0xA100)
      assert(dut.io.target.isWrite.peek().litToBoolean)
      dut.step(1)
      assert(dut.io.target.dataWrite.peek().litValue == 0xD100)
      dut.step(3)

      // Read is not done yet
      assert(!dut.isInitiatorReady)
      assert(dut.isTargetEnable)
      assert(dut.io.target.address.peek().litValue == 0xA200)
      assert(!dut.io.target.isWrite.peek().litToBoolean)
      dut.step(1)
      dut.io.target.dataRead.poke(0xD200)
      dut.step(1)
      dut.io.target.dataRead.poke(0xDEAD)
      dut.step(2)

      // Read is back
      assert(dut.getInitiatorReadData.contains(0xD200))
      dut.step(4)
    }
  }

  test("read then write pipelined") {
    go() { dut =>
      // Start a read
      dut.setInitiatorRead(0xA100)
      assert(dut.isInitiatorReady)
      dut.step(4)

      // Start a write too (blocked)
      dut.setInitiatorWrite(0xA200)
      assert(!dut.isInitiatorReady)
      // Fast started the read
      assert(dut.isTargetEnable)
      assert(dut.io.target.address.peek().litValue == 0xA100)
      assert(!dut.io.target.isWrite.peek().litToBoolean)
      dut.step(1)
      dut.io.target.dataRead.poke(0xD100)
      dut.step(3)

      // Read is done, write can progress
      assert(dut.isInitiatorReady)
      assert(dut.getInitiatorReadData.contains(0xD100))
      dut.step(4)

      // Write data
      assert(dut.isInitiatorReady)
      dut.setInitiatorWriteData(0xD200)
      dut.setInitiatorIdle()
      dut.step(4)

      assert(dut.isInitiatorReady)
      // Fast started the write
      assert(dut.isTargetEnable)
      assert(dut.io.target.address.peek().litValue == 0xA200)
      assert(dut.io.target.isWrite.peek().litToBoolean)
      dut.step(4)
    }
  }

  test("read same address repeatedly") {
    go() { dut =>
      // Start a read
      dut.setInitiatorRead(0xA100)
      assert(dut.isInitiatorReady)
      dut.step(4)

      // Fast started the read
      assert(dut.isTargetEnable)
      assert(dut.io.target.address.peek().litValue == 0xA100)
      assert(!dut.io.target.isWrite.peek().litToBoolean)
      dut.step(1)
      dut.io.target.dataRead.poke(0xD100)
      dut.step(1)
      dut.io.target.ready.poke(false)
      dut.step(2)

      // Fast will start the prefetch
      assert(dut.isTargetEnable)
      assert(dut.io.target.address.peek().litValue == 0xA101)
      dut.step(1)
      dut.io.target.ready.poke(false)
      dut.step(3)

      // Read is done. Keep doing the read from the same address,
      // doesn't require any target reads to actually occur.
      for (_ <- 0 until 8) {
        assert(dut.isInitiatorReady)
        assert(dut.getInitiatorReadData.contains(0xD100))
        dut.setInitiatorRead(0xA100)
        dut.step(4)
      }
      System.err.println("-- done with loop")

      // Now, start a read to an unrelated address.
      assert(dut.isInitiatorReady)
      assert(dut.getInitiatorReadData.contains(0xD100))
      dut.setInitiatorRead(0xA200)
      dut.step(4)

      // And allow the prefetch from before to go through.
      dut.step(1)
      dut.io.target.dataRead.poke(0xD101)
      dut.io.target.ready.poke(true)
      dut.step(1)
      dut.io.target.dataRead.poke(0xBAAA)
      dut.step(2)

      // Make sure the initiator isn't stuck.
      //
      // If the module has a bug that causes the FIFO to start filling up
      // during prefetches from the same address, the request to 0xA200
      // won't have made it onto the FIFO, and so the fast domain will
      // never have actually done the request and so the initiator will be stuck.
      dut.setInitiatorIdle()
      dut.step(64)
      assert(dut.isInitiatorReady)
    }
  }

  /// Read an unrelated address while the fast domain is doing a prefetch that's taking a while
  test("read interrupting prefetch") {
    go() { dut =>
      // Start a read request
      dut.setInitiatorRead(0xA000)
      dut.step(4)
      dut.setInitiatorIdle()
      assert(!dut.isInitiatorReady) // not ready

      // Target should get and do the request
      assert(dut.isTargetEnable)
      assert(dut.io.target.address.peek().litValue == 0xA000)
      assert(!dut.io.target.isWrite.peek().litToBoolean)
      dut.step(1)
      dut.io.target.dataRead.poke(0xD000)
      assert(!dut.isTargetEnable)
      dut.step(1)
      dut.io.target.dataRead.poke(0xDEAD)
      dut.step(2)

      // Initiator receives data now
      assert(dut.getInitiatorReadData.contains(0xD000))

      // Fast prefetches next word
      assert(dut.isTargetEnable)
      assert(dut.io.target.address.peek().litValue == 0xA001)
      dut.step(1)
      assert(!dut.isTargetEnable)
      dut.io.target.ready.poke(false)
      dut.step(3)
      // Keep it waiting for a while
      dut.step(8)

      // Start another read request
      dut.setInitiatorRead(0xA100)
      dut.step(4)
      dut.setInitiatorIdle()
      assert(!dut.isInitiatorReady)

      // Fast still needs to prefetch the word before it can start another request.
      for (_ <- 0 until 12) {
        assert(!dut.isTargetEnable)
        assert(!dut.isInitiatorReady)
        dut.step(1)
      }

      // Complete the slow prefetch
      dut.step(2)
      dut.io.target.dataRead.poke(0xD001)
      assert(!dut.isTargetEnable)
      dut.step(1)
      dut.io.target.ready.poke(true)
      dut.io.target.dataRead.poke(0xDEAD)
      dut.step(1)
      System.err.println("- slow prefetch completed")

      // Slow needs to still be waiting, fast will start a new request
      assert(!dut.isInitiatorReady)
      assert(dut.isTargetEnable)
      assert(dut.io.target.address.peek().litValue == 0xA100)
      assert(!dut.io.target.isWrite.peek().litToBoolean)
      dut.step(1)
      dut.io.target.dataRead.poke(0xD100)
      dut.step(3)

      // Slow gets the response
      assert(dut.getInitiatorReadData.contains(0xD100))
    }
  }

  test("read prefetch race") {
    go() { dut =>
      // Start a read request
      dut.setInitiatorRead(0xA000)
      dut.step(4)
      dut.setInitiatorIdle()
      assert(!dut.isInitiatorReady)

      // Target should get and do the request
      assert(dut.getTargetReadAddress.contains(0xA000))
      dut.step(1)
      dut.io.target.dataRead.poke(0xD000)
      dut.step(3)

      // Initiator receives data now
      assert(dut.getInitiatorReadData.contains(0xD000))

      // Fast prefetches next word
      assert(dut.isTargetEnable)
      assert(dut.io.target.address.peek().litValue == 0xA001)
      dut.step(1)
      assert(!dut.isTargetEnable)
      dut.io.target.ready.poke(false)
      dut.step(3)

      // Start another read request, for the previous word
      dut.setInitiatorRead(0xA000)
      dut.step(2)
      dut.io.target.dataRead.poke(0xA000)
      dut.io.target.ready.poke(true)
      dut.step(2)

      // The data is not ready, because 0xD001 replaced 0xD000.
      assert(!dut.isInitiatorReady)
      dut.setInitiatorIdle()
      // Target should start fetching 0xA000 again
      assert(dut.getTargetReadAddress.contains(0xA000))
      dut.step(1)
      dut.io.target.dataRead.poke(0xD000)
      dut.step(3)

      // Initiator receives data now
      assert(dut.getInitiatorReadData.contains(0xD000))
    }
  }

  test("registered responses preserve 2x/4x reads and writes at all completion phases") {
    for (ratio <- Seq(2, 4)) {
      simulate(new Wrapper(ratio, registered = true)) { dut =>
        val memory = scala.collection.mutable.Map.empty[BigInt, BigInt]
        var pending: Option[(BigInt, Boolean, BigInt, Int)] = None
        var starts = 0
        var latency = 1
        dut.io.initiator.enable.poke(false)
        dut.io.initiator.isWrite.poke(false)
        dut.io.initiator.address.poke(0)
        dut.io.initiator.dataWrite.poke(0)
        dut.io.initiator.writeStrobe.poke(15)
        dut.io.target.dataRead.poke(0)
        dut.io.target.ready.poke(true)
        dut.reset.poke(true); dut.clock.step(8); dut.reset.poke(false)
        def tick(): Unit = {
          if (dut.io.target.enable.peek().litToBoolean && dut.io.target.ready.peek().litToBoolean) {
            assert(pending.isEmpty, "overlapping target request")
            pending = Some((dut.io.target.address.peek().litValue,
              dut.io.target.isWrite.peek().litToBoolean,
              dut.io.target.dataWrite.peek().litValue, latency + 1))
            starts += 1
          }
          dut.clock.step()
          pending match {
            case Some((a, w, d, left)) if left > 1 =>
              pending = Some((a, w, d, left - 1)); dut.io.target.ready.poke(false)
            case Some((a, w, d, _)) =>
              if (w) memory(a) = d
              dut.io.target.dataRead.poke(memory.getOrElse(a, BigInt(0)))
              dut.io.target.ready.poke(true); pending = None
            case None => ()
          }
        }
        def step(n: Int): Unit = (0 until n).foreach(_ => tick())
        step(ratio * 2)
        for (delay <- 1 to 9) {
          latency = delay
          val a = BigInt(0x100 + delay * 4)
          val data = BigInt(0x12340000L + delay)
          for (write <- Seq(true, false)) {
            val before = starts
            dut.io.initiator.address.poke(a); dut.io.initiator.isWrite.poke(write)
            dut.io.initiator.dataWrite.poke(data); dut.io.initiator.enable.poke(true)
            step(ratio); dut.io.initiator.enable.poke(false); step(ratio)
            var guard = 0
            while (!dut.io.initiator.ready.peek().litToBoolean && guard < 100) { tick(); guard += 1 }
            assert(guard < 100, "lost response")
            if (!write) assert(dut.io.initiator.dataRead.peek().litValue == data)
            step(40)
            assert(starts == before + 1, "lost or duplicated target transaction")
            assert(memory(a) == data)
          }
        }
      }
    }
  }
}
