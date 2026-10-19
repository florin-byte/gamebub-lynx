package platform.handheld.spi

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.freespec.AnyFreeSpec

import scala.collection.mutable
import platform.handheld.spi.SpiReceiver

class SpiReceiverSpec extends AnyFreeSpec with ChiselScalatestTester {
  private val CommandWrite = 0 | (1 << 1) | (1 << 4)
  private val CommandRead = 1 | (1 << 1) | (1 << 4)

  /** Do a SPI exchange, returning the read data. */
  private def spiExchange(dut: SpiReceiver, writeData: Int, bits: Int): Int = {
    var readData = 0
    for (i <- (0 until bits).reverse) {
      dut.io.signals.serialIn.poke((writeData >> i) & 1)
      dut.clock.step(4)
      dut.io.signals.serialClock.poke(1.U)
      readData = (readData << 1) | dut.io.signals.serialOut.peekInt().toInt
      dut.clock.step(4)
      dut.io.signals.serialClock.poke(0.U)
    }
    readData
  }

  private def spiTransaction(dut: SpiReceiver, command: Int, address: Int, writeData: Seq[Int]): Seq[Int] = {
    dut.io.signals.chipSelect.poke(0.U)
    dut.clock.step(4)

    spiExchange(dut, command, bits = 8)
    spiExchange(dut, address, bits = 16) // Address
    val readData = writeData.map(x => spiExchange(dut, x, bits = 16))

    dut.io.signals.chipSelect.poke(1.U)
    dut.clock.step(4)

    readData
  }

  private def setupDut(dut: SpiReceiver): (mutable.Queue[(BigInt, BigInt)], mutable.Queue[(BigInt, BigInt)]) = {
    dut.io.signals.chipSelect.poke(1.U)
    dut.io.signals.serialClock.poke(0.U)
    dut.clock.step(4)

    val receiveQueue = mutable.Queue[(BigInt, BigInt)]()
    fork {
      while (true) {
        if (dut.io.writeValid.peekBoolean()) {
          receiveQueue.enqueue((
            dut.io.address.peekInt(),
            dut.io.dataWrite.peekInt()
          ))
          println(f"Received write: 0x${dut.io.address.peekInt()}%04X <= 0x${dut.io.dataWrite.peekInt()}%04X")
        }
        dut.clock.step()
      }
    }

    val sendQueue = mutable.Queue[(BigInt, BigInt)]()
    fork {
      while (true) {
        if (dut.io.readValid.peekBoolean()) {
          assert(sendQueue.nonEmpty)
          val (address, data) = sendQueue.dequeue()
          assert(dut.io.address.peekInt() == address)
          dut.io.dataRead.poke(data)
          println(f"Sent data 0x${address}%04X <= 0x${data}%04X")
        }
        dut.clock.step()
        dut.io.dataRead.poke(0)
      }
    }

    (sendQueue, receiveQueue)
  }

  "write register" in {
    test(new SpiReceiver) { dut =>
      val (_, receiveQueue) = setupDut(dut)

      val _ = spiTransaction(dut, CommandWrite, 0xABCD, Seq(0x12EF))

      assert(receiveQueue.length == 1)
      val (address, data) = receiveQueue.dequeue()
      assert(address == 0xABCD)
      assert(data == 0x12EF)
    }
  }

  "write multiple registers" in {
    test(new SpiReceiver) { dut =>
      val (_, receiveQueue) = setupDut(dut)

      val _ = spiTransaction(dut, CommandWrite, 0x4080, Seq(0xAABB, 0xCCDD, 0x1122))

      assert(receiveQueue.length == 3)
      assert(receiveQueue.dequeue() == (0x4080, 0xAABB))
      assert(receiveQueue.dequeue() == (0x4082, 0xCCDD))
      assert(receiveQueue.dequeue() == (0x4084, 0x1122))
    }
  }

  "read register" in {
    test(new SpiReceiver) { dut =>
      val (sendQueue, receiveQueue) = setupDut(dut)
      sendQueue.enqueue((0xABCD, 0xDE12))
      sendQueue.enqueue((0xABCE, 0x0000)) // Extra read at the end.

      val data = spiTransaction(dut, CommandRead, 0xABCD, Seq.fill(1)(0x0))

      assert(data == Seq(0xDE12))
      assert(receiveQueue.isEmpty)
    }
  }

  "read multiple registers" in {
    test(new SpiReceiver) { dut =>
      val (sendQueue, receiveQueue) = setupDut(dut)
      sendQueue.enqueue((0xFF02, 0x3344))
      sendQueue.enqueue((0xFF04, 0x5566))
      sendQueue.enqueue((0xFF06, 0x7788))
      sendQueue.enqueue((0xFF08, 0x0000)) // Extra read at the end.

      val data = spiTransaction(dut, CommandRead, 0xFF02, Seq.fill(3)(0x0))

      assert(data == Seq(0x3344, 0x5566, 0x7788))
      assert(receiveQueue.isEmpty)
    }
  }

  "write then read" in {
    test(new SpiReceiver) { dut =>
      val (sendQueue, receiveQueue) = setupDut(dut)
      sendQueue.enqueue((0xAA04, 0xABCD))
      sendQueue.enqueue((0xAA06, 0x1234))
      sendQueue.enqueue((0xAA08, 0x0000)) // Extra read at the end.

      val _ = spiTransaction(dut, CommandWrite, 0xA0B0, Seq(0x9876))
      assert(receiveQueue.length == 1)
      assert(receiveQueue.dequeue() == (0xA0B0, 0x9876))

      val data = spiTransaction(dut, CommandRead, 0xAA04, Seq.fill(2)(0x0))
      assert(data == Seq(0xABCD, 0x1234))
      assert(receiveQueue.isEmpty)
    }
  }
}
