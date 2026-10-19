package lib.mem.sdram

import chisel3._
import lib.util.EphemeralSimulator._
import org.scalatest.funsuite.AnyFunSuite

class BurstSdramControllerSpec extends AnyFunSuite {
  test("registered intake preserves initialization, reads, writes and reset") {
    for (registered <- Seq(false, true)) {
      simulate(new Module {
        val controller = Module(new BurstSdramController(BurstSdramController.Config(
        clockFrequency = 100000000, timeInit = 200, enableBurst = false,
        registeredRequest = registered)))
        val io = IO(chiselTypeOf(controller.io))
        io <> controller.io
      }) { dut =>
        dut.io.mem.enable.poke(false)
        dut.io.mem.address.poke(0)
        dut.io.mem.isWrite.poke(false)
        dut.io.mem.writeStrobe.poke(15)
        dut.io.mem.dataWrite.poke(BigInt("1234abcd", 16))
        dut.io.signals.dataIn.poke(0x5678)
        dut.reset.poke(true); dut.clock.step(4); dut.reset.poke(false)
        def command: Int =
          (if (dut.io.signals.cs.peek().litToBoolean) 8 else 0) |
          (if (dut.io.signals.ras.peek().litToBoolean) 4 else 0) |
          (if (dut.io.signals.cas.peek().litToBoolean) 2 else 0) |
          (if (dut.io.signals.we.peek().litToBoolean) 1 else 0)
        def transact(write: Boolean, address: Int): Unit = {
          var wait = 0
          while (!dut.io.mem.ready.peek().litToBoolean && wait < 300) {
            dut.clock.step(); wait += 1
          }
          assert(wait < 300)
          dut.io.mem.isWrite.poke(write); dut.io.mem.address.poke(address)
          dut.io.mem.enable.poke(true)
          dut.clock.step(); dut.io.mem.enable.poke(false)
          var active = 0; var operations = 0; var refreshes = 0; var cycles = 0
          var written = Vector.empty[BigInt]
          while ((!dut.io.mem.ready.peek().litToBoolean || operations == 0) && cycles < 300) {
            if (command == 3) active += 1
            if (command == (if (write) 4 else 5)) operations += 1
            if (command == 1) refreshes += 1
            if (dut.io.signals.dataDir.peek().litToBoolean) written :+= dut.io.signals.dataOut.peek().litValue
            dut.clock.step(); cycles += 1
          }
          assert(cycles < 300, "request did not complete")
          // The default flow may already emit ACTIVE on the acceptance edge.
          assert((if (registered) active == 1 else active <= 1) && operations == 1, "missing or duplicate SDRAM command")
          if (write) assert(written.take(2) == Vector(BigInt(0x1234), BigInt(0xabcd)))
          else assert(dut.io.mem.dataRead.peek().litValue == BigInt("56785678", 16))
          dut.clock.step(8)
        }
        def drainInitialRefresh(): Unit = {
          var refreshes = 0
          for (_ <- 0 until 160) {
            if (command == 1) refreshes += 1
            dut.clock.step()
          }
          assert(refreshes == 8, "initial refresh deficit did not drain during idle")
        }
        transact(false, 0x100) // Accepted during initialization; must not disappear.
        drainInitialRefresh()
        transact(true, 0x204)
        transact(false, 0x300)
        dut.reset.poke(true); dut.clock.step(4); dut.reset.poke(false)
        transact(false, 0x400)
        drainInitialRefresh()
      }
    }
  }

  test("2x CDC with registered controller and responses completes real SDRAM commands") {
    simulate(new Module {
      val controller = Module(new BurstSdramController(BurstSdramController.Config(
        clockFrequency = 100000000, timeInit = 200, enableBurst = false,
        registeredRequest = true)))
      val divider = RegInit(true.B)
      divider := !divider
      val cdc = Module(new lib.mem.PipelineMemoryBurstCdc(
        addressWidth = controller.io.mem.address.getWidth, dataWidth = 32,
        enablePrefetch = false, registeredResponses = true))
      cdc.io.slowClock := divider.asClock
      cdc.io.target <> controller.io.mem
      val io = IO(new Bundle {
        val mem = new lib.mem.PipelineMemoryInterface(controller.io.mem.address.getWidth, 32)
        val dataIn = Input(UInt(16.W))
        val command = Output(UInt(4.W))
      })
      cdc.io.initiator <> io.mem
      controller.io.signals.dataIn := io.dataIn
      io.command := chisel3.util.Cat(controller.io.signals.cs, controller.io.signals.ras,
        controller.io.signals.cas, controller.io.signals.we)
    }) { dut =>
      dut.io.mem.enable.poke(false); dut.io.mem.address.poke(0)
      dut.io.mem.isWrite.poke(false); dut.io.mem.writeStrobe.poke(15)
      dut.io.mem.dataWrite.poke(BigInt("1234abcd",16)); dut.io.dataIn.poke(0x5678)
      dut.reset.poke(true); dut.clock.step(4); dut.reset.poke(false)
      var reads = 0; var writes = 0
      def tick(): Unit = {
        val cmd = dut.io.command.peek().litValue
        if (cmd == 5) reads += 1
        if (cmd == 4) writes += 1
        dut.clock.step()
      }
      def step(n: Int): Unit = (0 until n).foreach(_ => tick())
      for ((write, address) <- Seq((false, 0x100), (true, 0x204), (false, 0x308))) {
        val beforeReads = reads; val beforeWrites = writes
        dut.io.mem.address.poke(address); dut.io.mem.isWrite.poke(write)
        dut.io.mem.enable.poke(true); step(2); dut.io.mem.enable.poke(false); step(2)
        var guard = 0
        while (!dut.io.mem.ready.peek().litToBoolean && guard < 400) { tick(); guard += 1 }
        assert(guard < 400, "integrated request timed out")
        if (!write) assert(dut.io.mem.dataRead.peek().litValue == BigInt("56785678",16))
        step(60)
        assert(reads == beforeReads + (if (write) 0 else 1))
        assert(writes == beforeWrites + (if (write) 1 else 0))
      }
    }
  }
}
