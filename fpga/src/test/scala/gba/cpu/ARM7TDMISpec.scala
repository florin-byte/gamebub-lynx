package gba.cpu

import gba.mem.BusAccessWidth
import lib.util.EphemeralSimulator._
import org.scalatest.funsuite.AnyFunSuite

class ARM7TDMISpec extends AnyFunSuite {
  /// CPU harness providing memory accesses
  class CpuHarness(dut: ARM7TDMI) {
    // Array of 32-bit integers for memory
    private val mem = Array.fill(1 * 1024 * 1024)(0)

    def reset(reloadPipeline: Boolean = true): Unit = {
      dut.io.enable.poke(true)
      dut.io.mem.CLKEN.poke(true)
      dut.io.mem.RDATA.poke(0xFFFFFFFF)
      dut.reset.poke(true)
      dut.clock.step()
      dut.reset.poke(false)

      if (reloadPipeline) {
        step(4)
      }
    }

    def copyMem(data: Array[Int], address: Int = 0): Unit = {
      data.copyToArray(mem, address / 4)
    }

    def getMem(address: Int = 0): Int = {
      mem.lift(address >> 2).getOrElse(0xffffffff)
    }

    def step(): Unit = {
      val memAddress = dut.io.mem.ADDR.peek().litValue
      val memWrite = dut.io.mem.WRITE.peek().litToBoolean
      val memSize = 1 << dut.io.mem.SIZE.peekValue().asBigInt.toInt
      val memRequest = dut.io.mem.MREQ.peek().litToBoolean
      val memSequential = dut.io.mem.SEQ.peek().litToBoolean

      // Test that 'enable' works
//      dut.io.enable.poke(false)
//      dut.clock.step()
//      dut.io.enable.poke(true)
      dut.clock.step()

      // TODO verify bursts are valid
      // TODO support stores (8, 16, or 32 bit)
      // Note: addresses are not necessarily aligned, they are aligned by memory controller.

      if (memRequest) {
        val seq = if (memSequential) "   Seq" else "NonSeq"
        if (memWrite) {
          val memDataWrite = dut.io.mem.WDATA.peek().litValue
          System.err.println(f"Mem Write $seq: [0x$memAddress%X] <- 0x$memDataWrite%X | size=$memSize\n")
          // TODO!
        } else {
          val readData = getMem(memAddress.toInt)
          dut.io.mem.RDATA.poke(readData)
          System.err.println(f"Mem  Read $seq: [0x$memAddress%X] -> 0x$readData%X | size=$memSize\n")
        }
      } else {
        System.err.println(f"Mem          Int: [0x$memAddress%X]\n")
        dut.io.mem.RDATA.poke(0xffffffff)
      }
    }

    def step(cycles: Int): Unit = {
      for (_ <- 0 until cycles) {
        step()
      }
    }

    def assertMemRead(address: Int, sequential: Boolean, size: BusAccessWidth.Type = BusAccessWidth.Word, internal: Boolean = false): Unit = {
      assert(dut.io.mem.MREQ.peek().litToBoolean == !internal, "wrong mreq")
      assert(dut.io.mem.ADDR.peek().litValue == address, "read address")
      assert(!dut.io.mem.WRITE.peek().litToBoolean, "not read")
      assert(dut.io.mem.SEQ.peek().litToBoolean == sequential, "wrong sequential")
      assert(dut.io.mem.SIZE.peekValue().asBigInt == size.litValue, "wrong size")
    }

    def assertMemWrite(address: Int, sequential: Boolean, size: BusAccessWidth.Type = BusAccessWidth.Word, internal: Boolean = false): Unit = {
      assert(dut.io.mem.MREQ.peek().litToBoolean == !internal, "wrong mreq")
      assert(dut.io.mem.ADDR.peek().litValue == address, "write address")
      assert(dut.io.mem.WRITE.peek().litToBoolean, "not write")
      assert(dut.io.mem.SEQ.peek().litToBoolean == sequential, "wrong sequential")
      assert(dut.io.mem.SIZE.peekValue().asBigInt == size.litValue, "wrong size")
    }

    def memAddress(): Int = {
      dut.io.mem.ADDR.peek().litValue.toInt
    }

    def memWriteData(): Int = {
      dut.io.mem.WDATA.peek().litValue.toInt
    }

    def reg(index: Int): Int = {
      dut.io.debug.registers.getElements(index).peek().litValue.toInt
    }

    def cpsr(): Int = {
      dut.io.debug.cpsr.peek().litValue.toInt
    }

    def cpsr_flags(): Int = {
      (cpsr() >> 28) & 0xF
    }
  }

  test("reset") {
    simulate(new ARM7TDMI) { dut =>
      val cpu = new CpuHarness(dut)
      cpu.copyMem(Array(
        0xe3a00001, // 0x0000: mov r0, 1
        0xe3a01002, // 0x0004: mov r1, 2
        0xe3a02003, // 0x0008: mov r2, 3
      ))
      cpu.reset(reloadPipeline = false)
      cpu.step()

      cpu.assertMemRead(0x00, sequential = false)
      cpu.step()

      cpu.assertMemRead(0x04, sequential = true)
      cpu.step()

      cpu.assertMemRead(0x08, sequential = true)
      cpu.step()
      assert(cpu.reg(15) == 0x8)
      assert(cpu.reg(0) == 0x0)

      cpu.assertMemRead(0x0C, sequential = true)
      cpu.step()
      assert(cpu.reg(15) == 0xC)
      assert(cpu.reg(0) == 0x1)
    }
  }

  test("data processing: immediate") {
    simulate(new ARM7TDMI) { dut =>
      val cpu = new CpuHarness(dut)
      cpu.copyMem(Array(
        0xe3b00001, // 0x0000: movs r0, 1
        0xe2801004, // 0x0004: add r1, r0, 4
        0xe2412005, // 0x0008: sub r2, r1, 5
        0xe2512005, // 0x000c: subs r2, r1, 5
      ))
      cpu.reset()

      cpu.step()
      assert(cpu.reg(0) == 1)
      assert((cpu.cpsr_flags() & 4) == 0) // Z flag

      cpu.step()
      assert(cpu.reg(1) == 5)

      cpu.step()
      assert(cpu.reg(2) == 0)
      assert((cpu.cpsr_flags() & 4) == 0) // Z flag

      cpu.step()
      assert((cpu.cpsr_flags() & 4) == 4) // Z flag
    }
  }

  test("data processing: branch") {
    simulate(new ARM7TDMI) { dut =>
      val cpu = new CpuHarness(dut)
      cpu.copyMem(Array(
        0xe3a0f014, // 0x0000: mov pc, 20
        0xe3a01001, // 0x0004: mov r1, 1
        0xe3a01002, // 0x0008: mov r1, 2
        0xe3a01003, // 0x000C: mov r1, 3
        0xe3a01004, // 0x0010: mov r1, 4
        0xe3a02005, // 0x0014: mov r2, 5
        0xe3a02006, // 0x0018: mov r2, 6
        0xe3a02007, // 0x001C: mov r2, 7
      ))
      cpu.reset()

      cpu.assertMemRead(20, sequential = false)
      cpu.step()
      assert(cpu.reg(15) == 20)

      cpu.assertMemRead(24, sequential = true)
      cpu.step()
      assert(cpu.reg(15) == 24)

      cpu.assertMemRead(28, sequential = true)
      cpu.step()
      assert(cpu.reg(15) == 28)
      assert(cpu.reg(2) == 0)

      cpu.assertMemRead(32, sequential = true)
      cpu.step()
      assert(cpu.reg(1) == 0)
      assert(cpu.reg(2) == 5)

      cpu.assertMemRead(36, sequential = true)
      cpu.step()
      assert(cpu.reg(2) == 6)

      cpu.step()
      assert(cpu.reg(2) == 7)
    }
  }

  test("data processing: shift by register") {
    simulate(new ARM7TDMI) { dut =>
      val cpu = new CpuHarness(dut)
      cpu.copyMem(Array(
        0xe3a0000c, // 0x0000: mov r0, 12
        0xe3a01cff, // 0x0004: mov r1, 0xFF00
        0xe0812011, // 0x0008: add r2, r1, r1, LSL r0
      ))
      cpu.reset()

      // mov r0, 12
      cpu.step()
      assert(cpu.reg(0) == 12)
      // mov r1, 0xFF00
      cpu.step()

      // add ...
      cpu.assertMemRead(20, internal = true, sequential = false)
      cpu.step()
      cpu.assertMemRead(20, sequential = true)
      cpu.step()
      assert(cpu.reg(2) == 0xFF0FF00)

      // next
      cpu.assertMemRead(24, sequential = true)
    }
  }

  def testLoad(
                dut: ARM7TDMI,
                instruction: Int,
                address: Option[Int] = None,
                size: BusAccessWidth.Type = BusAccessWidth.Word,
                data: Int,
                base: Option[Int] = None
              ): Unit = {
    val cpu = new CpuHarness(dut)
    cpu.copyMem(Array(
      0xe3a00ffa, // 0x0000: mov r0, #1000
      0xe3a04004, // 0x0004: mov r4, #4
      instruction,
      0xe3a02001, // 0x000C: mov r2, #1
      0xe3a02002, // 0x0010: mov r2, #2
      0xe3a02003, // 0x0014: mov r2, #3
    ))
    cpu.copyMem(Array(0xAABBCCDD, 0x11223344, 0x55667788), 996)
    cpu.reset()
    cpu.step()
    assert(cpu.reg(0) == 1000)
    cpu.step()

    // Load: compute address
    cpu.assertMemRead(address.getOrElse(cpu.memAddress()), sequential = false, size)
    // TODO: assert prot0 is 1(?) for data
    cpu.step()

    // Load: register writeback
    cpu.assertMemRead(20, internal = true, sequential = false)
    cpu.step()
    if (base.isDefined) {
      assert(cpu.reg(0) == base.get)
    }

    // Load: save the memory
    cpu.assertMemRead(20, sequential = true)
    cpu.step()
    assert(cpu.reg(1) == data)

    cpu.step()
    assert(cpu.reg(2) == 1)

    cpu.step()
    assert(cpu.reg(2) == 2)

    cpu.step()
    assert(cpu.reg(2) == 3)
  }

  test("load") {
    simulate(new ARM7TDMI) { dut =>
      // Load word with various addressing modes.
      testLoad(dut,
        instruction = 0xe5901000, // ldr r1, [r0]
        address = Some(1000),
        data = 0x11223344,
        base = Some(1000),
      )
      testLoad(dut,
        instruction = 0xe5901004, // ldr r1, [r0, #4]
        address = Some(1004),
        data = 0x55667788,
        base = Some(1000),
      )
      testLoad(dut,
        instruction = 0xe5b01004, // ldr r1, [r0, #4]!
        address = Some(1004),
        data = 0x55667788,
        base = Some(1004),
      )
      testLoad(dut,
        instruction = 0xe5301004, // ldr r1, [r0, #-4]!
        address = Some(996),
        data = 0xAABBCCDD,
        base = Some(996),
      )
      testLoad(dut,
        instruction = 0xe4901004, // ldr r1, [r0], #4
        address = Some(1000),
        data = 0x11223344,
        base = Some(1004),
      )
      testLoad(dut,
        instruction = 0xe7901184, // ldr r1, [r0, r4, LSL #3]
        address = Some(1032),
        data = 0x0,
        base = Some(1000),
      )

      // Load byte unsigned
      testLoad(dut,
        instruction = 0xe5d01000, // ldrb r1, [r0, #0]
        data = 0x44,
        size = BusAccessWidth.Byte)
      testLoad(dut,
        instruction = 0xe5d01001, // ldrb r1, [r0, #1]
        data = 0x33,
        size = BusAccessWidth.Byte)
      testLoad(dut,
        instruction = 0xe5d01002, // ldrb r1, [r0, #2]
        data = 0x22,
        size = BusAccessWidth.Byte)
      testLoad(dut,
        instruction = 0xe5d01003, // ldrb r1, [r0, #3]
        data = 0x11,
        size = BusAccessWidth.Byte)

      // Load byte signed
      testLoad(dut,
        instruction = 0xe1d010d0, // ldrsb r1, [r0, #0]
        data = 0x44,
        size = BusAccessWidth.Byte)
      testLoad(dut,
        instruction = 0xe1d010d1, // ldrsb r1, [r0, #1]
        data = 0x33,
        size = BusAccessWidth.Byte)
      testLoad(dut,
        instruction = 0xe1d010d2, // ldrsb r1, [r0, #2]
        data = 0x22,
        size = BusAccessWidth.Byte)
      testLoad(dut,
        instruction = 0xe1d010d3, // ldrsb r1, [r0, #3]
        data = 0x11,
        size = BusAccessWidth.Byte)
      testLoad(dut,
        instruction = 0xe15010d4, // ldrsb r1, [r0, #-4]
        data = 0xFFFFFFDD,
        size = BusAccessWidth.Byte)
      testLoad(dut,
        instruction = 0xe15010d3, // ldrsb r1, [r0, #-3]
        data = 0xFFFFFFCC,
        size = BusAccessWidth.Byte)
      testLoad(dut,
        instruction = 0xe15010d2, // ldrsb r1, [r0, #-2]
        data = 0xFFFFFFBB,
        size = BusAccessWidth.Byte)
      testLoad(dut,
        instruction = 0xe15010d1, // ldrsb r1, [r0, #-1]
        data = 0xFFFFFFAA,
        size = BusAccessWidth.Byte)

      // Load halfword unsigned
      testLoad(dut,
        instruction = 0xe1d010b0, // ldrh r1, [r0, #0]
        data = 0x3344,
        size = BusAccessWidth.Halfword)
      testLoad(dut,
        instruction = 0xe1d010b1, // ldrh r1, [r0, #0]
        data = 0x44000033,
        size = BusAccessWidth.Halfword)
      testLoad(dut,
        instruction = 0xe1d010b2, // ldrh r1, [r0, #2]
        data = 0x1122,
        size = BusAccessWidth.Halfword)
      testLoad(dut,
        instruction = 0xe1d010b3, // ldrh r1, [r0, #0]
        data = 0x22000011,
        size = BusAccessWidth.Halfword)

      // Load halfword signed
      testLoad(dut,
        instruction = 0xe1d010f0, // ldrsh r1, [r0, #0]
        data = 0x3344,
        size = BusAccessWidth.Halfword)
      testLoad(dut,
        instruction = 0xe1d010f1, // ldrsh r1, [r0, #1]
        data = 0x33,
        size = BusAccessWidth.Halfword)
      testLoad(dut,
        instruction = 0xe1d010f2, // ldrsh r1, [r0, #2]
        data = 0x1122,
        size = BusAccessWidth.Halfword)
      testLoad(dut,
        instruction = 0xe1d010f3, // ldrsh r1, [r0, #3]
        data = 0x11,
        size = BusAccessWidth.Halfword)
      testLoad(dut,
        instruction = 0xe15010f4, // ldrsh r1, [r0, #-4]
        data = 0xFFFFCCDD,
        size = BusAccessWidth.Halfword)
      testLoad(dut,
        instruction = 0xe15010f3, // ldrsh r1, [r0, #-3]
        data = 0xFFFFFFCC,
        size = BusAccessWidth.Halfword)
      testLoad(dut,
        instruction = 0xe15010f2, // ldrsh r1, [r0, #-2]
        data = 0xFFFFAABB,
        size = BusAccessWidth.Halfword)
      testLoad(dut,
        instruction = 0xe15010f1, // ldrsh r1, [r0, #-1]
        data = 0xFFFFFFAA,
        size = BusAccessWidth.Halfword)
    }
  }

  def testStore(
                dut: ARM7TDMI,
                instruction: Int,
                address: Option[Int] = None,
                size: BusAccessWidth.Type = BusAccessWidth.Word,
                data: Int,
                base: Option[Int] = None
              ): Unit = {
    val cpu = new CpuHarness(dut)
    cpu.copyMem(Array(
      0xe3a00ffa, // 0x0000: mov r0, #1000
      0xe3a01011, // 0x0004: mov r1, #0x11
      0xe3811c22, // 0x0008: orr r1, r1, #0x2200
      0xe3811833, // 0x000c: orr r1, r1, #0x330000
      0xe3811311, // 0x0010: orr r1, r1, #0x44000000
      0xe3a04004, // 0x0014: mov r4, #4
      instruction,
      0xe3a02001, // 0x001c: mov r2, #1
      0xe3a02002, // 0x0020: mov r2, #2
      0xe3a02003, // 0x0024: mov r2, #3
    ))
    cpu.copyMem(Array(0xAABBCCDD, 0x99887766, 0x55667788), 996)
    cpu.reset()
    cpu.step(5)
    assert(cpu.reg(1) == 0x44332211)
    cpu.step()

    // Store: compute address
    cpu.assertMemWrite(address.getOrElse(cpu.memAddress()), sequential = false, size)
    // TODO: assert prot0 is 1(?) for data
    cpu.step()
    assert(cpu.memWriteData() == data)

    // Store: base modification
    cpu.assertMemRead(36, sequential = false)
    cpu.step()
    if (base.isDefined) {
      assert(cpu.reg(0) == base.get)
    }

    cpu.step()
    assert(cpu.reg(2) == 1)

    cpu.step()
    assert(cpu.reg(2) == 2)

    cpu.step()
    assert(cpu.reg(2) == 3)
  }

  test("store") {
    simulate(new ARM7TDMI) { dut =>
      testStore(dut,
        instruction = 0xe5801000, // str r1, [r0]
        address = Some(1000),
        data = 0x44332211,
        base = Some(1000),
      )

      testStore(dut,
        instruction = 0xE5A00004, // str r0, [r0, #4]!
        address = Some(1004),
        data = 1000,
        base = Some(1004),
      )

      testStore(dut,
        instruction = 0xe5c01000, // strb r1, [r0]
        size = BusAccessWidth.Byte,
        data = 0x11111111,
      )

      testStore(dut,
        instruction = 0xe1c010b0, // strh r1, [r0]
        size = BusAccessWidth.Halfword,
        data = 0x22112211,
      )
    }
  }

  test("swap") {
    simulate(new ARM7TDMI) { dut =>
      def testSwap(instruction: Int, size: BusAccessWidth.Type, rd: Int, storeData: Int, loadData: Int): Unit = {
        val cpu = new CpuHarness(dut)
        cpu.copyMem(Array(
          0xe3a00ffa, // 0x0000: mov r0, #1000
          0xe3a01011, // 0x0004: mov r1, #0x11
          0xe3811c22, // 0x0008: orr r1, r1, #0x2200
          0xe3811833, // 0x000c: orr r1, r1, #0x330000
          0xe3811311, // 0x0010: orr r1, r1, #0x44000000
          instruction,
          0xe3a02001, // 0x0018: mov r2, #1
          0xe3a02002, // 0x001c: mov r2, #2
          0xe3a02003, // 0x0020: mov r2, #3
        ))
        cpu.copyMem(Array(0xAABBCCDD), 1000)
        cpu.reset()
        cpu.step(5)

        // Swap: load
        // TODO assert "LOCK" is set (and PROT is data)
        cpu.assertMemRead(1000, sequential = false, size)
        cpu.step()

        // Swap: store
        // TODO assert "LOCK" is set (and PROT is data)
        cpu.assertMemWrite(1000, sequential = false, size)
        cpu.step()
        assert(cpu.memWriteData() == storeData)

        // Swap: write-back to register
        cpu.assertMemRead(0x20 /* pc + 12 */ , internal = true, sequential = false)
        cpu.step()

        // Swap: prefetch?
        cpu.assertMemRead(0x20 /* pc + 12 */ , sequential = true)
        cpu.step()
        assert(cpu.reg(rd) == loadData)

        cpu.step()
        assert(cpu.reg(2) == 1)
        cpu.step()
        assert(cpu.reg(2) == 2)
        cpu.step()
        assert(cpu.reg(2) == 3)
      }

      testSwap(
        0xe1002091, // swp r2, r1, [r0]
        size = BusAccessWidth.Word,
        rd = 2,
        storeData = 0x44332211,
        loadData = 0xAABBCCDD,
      )

      testSwap(
        0xe1001091, // swp r1, r1, [r0]
        size = BusAccessWidth.Word,
        rd = 1,
        storeData = 0x44332211,
        loadData = 0xAABBCCDD,
      )

      testSwap(
        0xe1402091, // swpb r2, r1, [r0]
        size = BusAccessWidth.Byte,
        rd = 2,
        storeData = 0x11111111,
        loadData = 0xDD,
      )
    }
  }

  test("branch") {
    simulate(new ARM7TDMI) { dut =>
      val cpu = new CpuHarness(dut)
      cpu.copyMem(Array(
        0xEB0003FE, // 0x0000: bl +0x1000
        0xe3a01001, // 0x0004: mov r1, #1
      ))
      cpu.copyMem(Array(
        0xe3a02001, // 0x1000: mov r2, #1
        0xe3a02002, // 0x1004: mov r2, #2
        0xe3a02003, // 0x1008: mov r2, #3
      ), 0x1000)
      cpu.reset()

      // Branch 1: load from branch target
      cpu.assertMemRead(0x1000, sequential = false)
      cpu.step()

      // Branch 2: refill pipeline
      cpu.assertMemRead(0x1004, sequential = true)
      cpu.step()

      // Branch 3: refill pipeline
      cpu.assertMemRead(0x1008, sequential = true)
      cpu.step()

      // Check that link flag and PC were set correctly.
      assert(cpu.reg(14) == 0x4)
      assert(cpu.reg(15) == 0x1008)

      cpu.step()
      assert(cpu.reg(2) == 1)
      cpu.step()
      assert(cpu.reg(2) == 2)
      cpu.step()
      assert(cpu.reg(2) == 3)
    }
  }

  test("branch exchange") {
    simulate(new ARM7TDMI) { dut =>
      val cpu = new CpuHarness(dut)
      cpu.copyMem(Array(
        0xe3a0000c, // 0x0000: mov r0, #0xC
        0xe12fff10, // 0x0004: bx r0
        0xe3a01001, // 0x0008: mov r1, #1
        0xe3a00019, // 0x000c: mov r0, #0x19
        0xe12fff10, // 0x0010: bx r0
        0xe3a01002, // 0x0014: mov r1, #2
        //             0x0018: movs r2, #1   (thumb)
        //             0x001A: movs r2, #2   (thumb)
        0x22022201,
        //             0x001C: movs r2, #3   (thumb)
        //             0x001E: movs r2, #4   (thumb)
        0x22042203,
      ))
      cpu.reset()
      cpu.step()

      // Branch 1
      cpu.assertMemRead(0xC, sequential = false)
      cpu.step(3)
      assert((cpu.cpsr() & 0x20) == 0)

      cpu.step(1)
      assert(cpu.reg(1) == 0)

      // Branch 2
      cpu.assertMemRead(0x18, sequential = false, size = BusAccessWidth.Halfword)
      cpu.step()
      cpu.assertMemRead(0x1A, sequential = true, size = BusAccessWidth.Halfword)
      cpu.step()
      cpu.assertMemRead(0x1C, sequential = true, size = BusAccessWidth.Halfword)
      cpu.step()
      assert((cpu.cpsr() & 0x20) != 0)

      // Execute thumb instructions
      cpu.assertMemRead(0x1E, sequential = true, size = BusAccessWidth.Halfword)
      cpu.step()
      assert(cpu.reg(2) == 1)

      cpu.assertMemRead(0x20, sequential = true, size = BusAccessWidth.Halfword)
      cpu.step()
      assert(cpu.reg(2) == 2)

      cpu.assertMemRead(0x22, sequential = true, size = BusAccessWidth.Halfword)
      cpu.step()
      assert(cpu.reg(2) == 3)

      cpu.assertMemRead(0x24, sequential = true, size = BusAccessWidth.Halfword)
      cpu.step()
      assert(cpu.reg(2) == 4)
    }
  }

  test("move to/from cpsr") {
    simulate(new ARM7TDMI) { dut =>
      val cpu = new CpuHarness(dut)
      cpu.copyMem(Array(
        0xe1500000, // 0x0000: cmp r0, r0
        0xe10f1000, // 0x0004: mrs r1, cpsr
        0xe328f203, // 0x0008: msr cpsr_f, #0x30000000
        0x63822001, // 0x000c: orrvs r2, #1
        0x23822002, // 0x0010: orrcs r2, #2
        0x03822004, // 0x0014: orreq r2, #4
        0x43822008, // 0x0018: orrmi r2, #8
        0xe3a03209, // 0x001c: mov r3, #0x90000000
        0xe128f003, // 0x0020: msr cpsr_f, r3
        0x63844001, // 0x0024: orrvs r4, #1
        0x23844002, // 0x0028: orrcs r4, #2
        0x03844004, // 0x002c: orreq r4, #4
        0x43844008, // 0x0030: orrmi r4, #8
      ))
      cpu.reset()
      cpu.step()

      // MRS
      cpu.step()
      assert(cpu.reg(1) == 0x600000D3) // Note: last '3' means supervisor mode

      // MSR (immediate)
      cpu.step(5)
      assert(cpu.reg(2) == 3)

      // MSR (reg)
      cpu.step(6)
      assert(cpu.reg(4) == 9)
    }
  }

  test("load multiple") {
    simulate(new ARM7TDMI) { dut =>
      def testLDM(instruction: Int, start: Int, newBase: Int = 1000): Unit = {
        val cpu = new CpuHarness(dut)
        cpu.copyMem(Array(
          0xe3a00ffa, // 0x0000: mov r0, #1000
          instruction,
          0xe3a00001, // 0x0008: mov r0, #1
          0xe3a00002, // 0x000C: mov r0, #2
          0xe3a00003, // 0x0010: mov r0, #3
          0xe1a00000, // 0x0014: nop
          0xe1a00000, // 0x0018: nop
          0xe1a00000, // 0x001C: nop
          0xe3a00004, // 0x0020: mov r0, #4
          0xe3a00005, // 0x0024: mov r0, #5
          0xe3a00006, // 0x0028: mov r0, #6
        ))
        cpu.copyMem((0 until 16).map(0xA000 + _).toArray, 936)
        cpu.copyMem((0 until 16).map(0xB000 + _).toArray, 968)
        cpu.copyMem((0 until 16).map(0xC000 + _).toArray, 1000)
        cpu.copyMem((0 until 16).map(0xD000 + _).toArray, 1032)
        cpu.reset()
        cpu.step()

        var registerField = instruction & 0xFFFF
        if (registerField == 0) {
          // Special behavior with empty list: PC only (but writeback of +/- 64)
          registerField = 0x8000
        }
        val numRegisters = registerField.toBinaryString.count(_ == '1')

        val isBranch = (registerField & 0x8000) != 0
        val branchTarget = 0x20
        if (isBranch) {
          // Ensure that the memory PC will be loaded from is a valid target.
          cpu.copyMem(Array(branchTarget), start + ((numRegisters - 1) * 4))
        }

        // LDM #1: Calculate start address
        cpu.assertMemRead(start, sequential = false)
        cpu.step()

        // LDM #2: Writeback base, start fetch
        for (i <- 1 until numRegisters) {
          cpu.assertMemRead(start + (i * 4), sequential = true)
          cpu.step()

          if (i == 0) {
            // Base writeback
            assert(cpu.reg(0) == newBase)
          }
        }

        // Second-to-last: start I-S prefetch
        cpu.assertMemRead(0x10, internal = true, sequential = false)
        cpu.step()

        if (numRegisters == 1) {
          // Base writeback, not in the loop before.
          assert(cpu.reg(0) == newBase)
        }

        if (!isBranch) {
          // Last: finish prefetch, moving to next instruction
          cpu.assertMemRead(0x10, sequential = true)
          cpu.step()
        } else {
          // Flushing the pipeline -- new branch target
          cpu.assertMemRead(branchTarget, sequential = false)
          cpu.step()
          cpu.assertMemRead(branchTarget + 4, sequential = true)
          cpu.step()
          cpu.assertMemRead(branchTarget + 8, sequential = true)
          cpu.step()
        }

        // Check loaded registers.
        {
          var address = start
          for (i <- 0 until 16) {
            if ((registerField & (1 << i)) != 0 && (i != 15)) {
              assert(cpu.reg(i) == cpu.getMem(address), f"(reg $i)")
              address += 4
            }
          }
        }
        if ((registerField & 1) == 0) {
          assert(cpu.reg(0) == newBase)
        }

        // Check that instructions after work.
        val base = if (isBranch) { 3 } else { 0 }
        cpu.step()
        assert(cpu.reg(0) == base + 1)
        cpu.step()
        assert(cpu.reg(0) == base + 2)
        cpu.step()
        assert(cpu.reg(0) == base + 3)
      }

      // Test the four addressing modes
      // ldmia r0!, {r1, r2, r3, r4}
      testLDM(instruction = 0xe8b0001e, start = 1000, newBase = 1016)
      // ldmib r0!, {r1, r2, r3, r4}
      testLDM(instruction = 0xe9b0001e, start = 1004, newBase = 1016)
      // ldmda r0!, {r1, r2, r3, r4}
      testLDM(instruction = 0xe830001e, start = 988, newBase = 984)
      // ldmdb r0!, {r1, r2, r3, r4}
      testLDM(instruction = 0xe930001e, start = 984, newBase = 984)

      // Test with only 1 register
      // ldmia r0!, {r1}
      testLDM(instruction = 0xe8b00002, start = 1000, newBase = 1004)

      // Test writeback to a register that's loaded (load happens after writeback)
      // ldmia r0!, {r0}
      testLDM(instruction = 0xe8b00001, start = 1000, newBase = 1004)

      // Test loading PC
      // ldmia r0!, {r1, r2, r3, pc}
      testLDM(instruction = 0xe8b0800e, start = 1000, newBase = 1016)

      // Test loading only PC
      // ldmia r0!, {pc}
      testLDM(instruction = 0xe8b08000, start = 1000, newBase = 1004)

      // Test empty list (transfer PC, adjust registers by 64 bytes)
      // ldmia r0!, {}
      testLDM(instruction = 0xe8b00000, start = 1000, newBase = 1064)
      // ldmib r0!, {}
      testLDM(instruction = 0xe9b00000, start = 1004, newBase = 1064)
      // ldmda r0!, {}
      testLDM(instruction = 0xe8300000, start = 940, newBase = 936)
      // ldmdb r0!, {}
      testLDM(instruction = 0xe9300000, start = 936, newBase = 936)
    }
  }

  test("store multiple") {
    simulate(new ARM7TDMI) { dut =>
      def testSTM(instruction: Int, start: Int, newBase: Int = 1000): Unit = {
        val cpu = new CpuHarness(dut)
        cpu.copyMem(Array(
          0xe3a000a0, // 0x0000: mov r0, #0xA0
          0xe3a01ffa, // 0x0004: mov r1, #1000
          0xe3a020a2, // 0x0008: mov r2, #0xA2
          0xe3a030a3, // 0x000c: mov r3, #0xA3
          0xe3a040a4, // 0x0010: mov r4, #0xA4
          0xe3a050a5, // 0x0014: mov r5, #0xA5
          0xe3a060a6, // 0x0018: mov r6, #0xA6
          0xe3a070a7, // 0x001c: mov r7, #0xA7
          0xe3a080a8, // 0x0020: mov r8, #0xA8
          0xe3a090a9, // 0x0024: mov r9, #0xA9
          0xe3a0a0aa, // 0x0028: mov r10, #0xAA
          0xe3a0b0ab, // 0x002c: mov r11, #0xAB
          0xe3a0c0ac, // 0x0030: mov r12, #0xAC
          0xe3a0d0ad, // 0x0034: mov r13, #0xAD
          0xe3a0e0ae, // 0x0038: mov r14, #0xAE
          instruction,
          0xe3a00001, // 0x0040: mov r0, #1
          0xe3a00002, // 0x0044: mov r0, #2
          0xe3a00003, // 0x0048: mov r0, #3
        ))
        cpu.reset()
        cpu.step(15)

        var registerField = instruction & 0xFFFF
        if (registerField == 0) {
          // Special behavior with empty list: PC only (but writeback of +/- 64)
          registerField = 0x8000
        }

        // STM #1: Calculate start address
        cpu.assertMemWrite(start, sequential = false)
        cpu.step()

        var address = start + 4
        for (i <- 0 until 16) {
          if ((registerField & (1 << i)) != 0) {
            System.err.println(f"    --> register $i")
            val expected = i match {
              case 1 if ((registerField & 1) == 0) => 1000 // r1 is not the first in the list
              case 1 => newBase
              case 15 => 0x48  // PC + 12
              case _ => 0xA0 | i
            }
            assert(cpu.memWriteData() == expected)
            if (registerField >> (i + 1) == 0) {
              // There are no more registers to write, last cycle.
              cpu.assertMemRead(0x48 /* PC + 12 */, sequential = false)
            } else {
              cpu.assertMemWrite(address, sequential = true)
              address += 4
            }
            cpu.step()
          }
        }

        // Check the new base is correct after the entire STM.
        assert(cpu.reg(1) == newBase)

        // Check that instructions after work.
        cpu.step()
        assert(cpu.reg(0) == 1)
        cpu.step()
        assert(cpu.reg(0) == 2)
        cpu.step()
        assert(cpu.reg(0) == 3)
      }

      // Test the four addressing modes
      // stmia r1!, {r2, r3, r4, r5}
      testSTM(instruction = 0xe8a1003c, start = 1000, newBase = 1016)
      // stmib r1!, {r2, r3, r4, r5}
      testSTM(instruction = 0xe9a1003c, start = 1004, newBase = 1016)
      // stmda r1!, {r2, r3, r4, r5}
      testSTM(instruction = 0xe821003c, start = 988, newBase = 984)
      // stmdb r1!, {r2, r3, r4, r5}
      testSTM(instruction = 0xe921003c, start = 984, newBase = 984)

      // Test writeback with the register in the list
      // stmia r1!, {r0, r1, r2, r3}
      testSTM(instruction = 0xe8a1000f, start = 1000, newBase = 1016)
      // stmib r1!, {r1, r2, r3}
      testSTM(instruction = 0xe8a1000e, start = 1000, newBase = 1012)

      // Test single register
      // stmia r1!, {r2}
      testSTM(instruction = 0xe8a10004, start = 1000, newBase = 1004)

      // Test PC writeback
      // stmia r1!, {r2, pc}
      testSTM(instruction = 0xe8a18004, start = 1000, newBase = 1008)
      // stmia r1!, {pc}
      testSTM(instruction = 0xe8a18000, start = 1000, newBase = 1004)

      // Test empty register list
      // stmia r1!, {}
      testSTM(instruction = 0xe8a10000, start = 1000, newBase = 1064)
    }
  }

  test("exception") {
    simulate(new ARM7TDMI) { dut =>
      val cpu = new CpuHarness(dut)
      cpu.copyMem(Array(
        0xea0000f8, // 0x0000: b 1000   (Reset vector)
        0xe1a00000, // 0x0004: mov r0, r0
        0xea0001f0, // 0x0008: b 2000   (SWI vector)
      ))
      cpu.copyMem(Array(
        0xe329f010, // 0x0000: msr cpsr, #0x10
        0xe3a0d0ab, // 0x0004: mov r13, #0xAB
        0xe3a06001, // 0x0008: mov r6, #1
        0xef000001, // 0x000C: swi #1
        0xe3a07002, // 0x0010: mov r7, #2
      ), 1000)
      cpu.copyMem(Array(
        0xe3a00001, // 0x0000: mov r0, #1
        0xe3a00002, // 0x0004: mov r0, #2
        0xe3a00003, // 0x0008: mov r0, #3
        0xe10f1000, // 0x000c: mrs r1, cpsr
        0xe14f2000, // 0x0010: mrs r2, spsr
        0xe1a0300d, // 0x0014: mov r3, r13
        0xe1a0400e, // 0x0018: mov r4, r14
      ), 2000)
      cpu.reset()

      cpu.step(5) // b, msr, mov 1
      assert(cpu.reg(13) == 0xAB)
      cpu.step()
      assert(cpu.reg(6) == 1)

      // First cycle: make sure PC is updated.
      assert(cpu.reg(15) == 1000 + 0x14)
      cpu.assertMemRead(0x8, sequential = false)
      cpu.step()
      cpu.assertMemRead(0xC, sequential = true)
      cpu.step()
      cpu.assertMemRead(0x10, sequential = true)
      cpu.step()

      // Do the branch
      assert(cpu.reg(15) == 8 + 8) // PC at SWI vector (+ 8)
      // It is important that the PC is at the vector area, because
      // the GBA only allows reading BIOS memory when the PC is in the
      // BIOS area (perhaps also if it's an instruction fetch? e.g.
      // for the first few prefetch cycles after a branch/exception?)
      cpu.step(3)

      cpu.step()
      assert(cpu.reg(0) == 1)
      cpu.step()
      assert(cpu.reg(0) == 2)
      cpu.step()
      assert(cpu.reg(0) == 3)

      // Check new CPSR: Supervisor, IRQ disabled, FIQ not disabled, ARM mode.
      cpu.step()
      assert(cpu.reg(1) == 0x93)

      // Check saved SPSR
      cpu.step()
      assert(cpu.reg(2) == 0x10)

      // Check that r13 is banked properly
      cpu.step()
      assert(cpu.reg(3) != 0xAB)

      // Check that r14 (LR) was set properly
      cpu.step()
      assert(cpu.reg(4) == 1016) // Instruction after SWI
    }
  }

  test("multiply") {
    simulate(new ARM7TDMI) { dut =>
      def testMultiply(instruction: Int, cycles: Int, r1: Int = 0, r2: Int = 0, r3: Int = 0, r4: Int = 0): (Long, Long) = {
        val cpu = new CpuHarness(dut)
        cpu.copyMem(Array(
          0xe3a00a01, // 0x0000: mov r0, #0x1000
          0xe5901000, // 0x0004: ldr r1, [r0, #0]
          0xe5902004, // 0x0008: ldr r2, [r0, #4]
          0xe5903008, // 0x000c: ldr r3, [r0, #8]
          0xe590400c, // 0x0010: ldr r4, [r0, #12]
          0xe3a00001, // 0x0014: mov r0, #1
          instruction,
        ))
        cpu.copyMem(Array(
          r1,
          r2,
          r3,
          r4
        ), 0x1000)
        cpu.reset()
        cpu.step(14)
        assert(cpu.reg(0) == 1)

        val accumulate = (instruction & (1 << 21)) != 0;
        for (i <- 0 until cycles) {
          if (i == 0 && accumulate) {
            cpu.assertMemRead(0x18 + (2 * 4), internal = true, sequential = false)
          } else if (i == cycles - 1) {
            cpu.assertMemRead(0x18 + (3 * 4), sequential = true)
          } else {
            cpu.assertMemRead(0x18 + (3 * 4), internal = true, sequential = false)
          }
          cpu.step()
        }

        (cpu.reg(4), cpu.reg(3))
      }

      // mul r3, r1, r2
      assert(testMultiply(0xE0030291, cycles = 2, r1 = 0x01020304, r2 = 0xAB)._2 == 0xac5803ac)
      assert(testMultiply(0xE0030291, cycles = 3, r1 = 0x01020304, r2 = 0xABCD)._2 == 0x26a01634)
      assert(testMultiply(0xE0030291, cycles = 4, r1 = 0x01020304, r2 = 0xABCDEF)._2 == 0x90f704bc)
      assert(testMultiply(0xE0030291, cycles = 5, r1 = 0x01020304, r2 = 0xABCDEF12)._2 == 0x928f248)

      // mla r4, r1, r2, r3
      assert(testMultiply(0xE0243291, cycles = 3, r1 = 0x01020304, r2 = 0x8, r3 = 0x11223344)._1 == 0x19324b64)
      assert(testMultiply(0xE0243291, cycles = 4, r1 = 0x01020304, r2 = 0x108, r3 = 0x11223344)._1 == 0x1b354f64)
      assert(testMultiply(0xE0243291, cycles = 5, r1 = 0x01020304, r2 = 0x20108, r3 = 0x11223344)._1 == 0x213d4f64)
      assert(testMultiply(0xE0243291, cycles = 6, r1 = 0x01020304, r2 = 0x3020108, r3 = 0x11223344)._1 == 0x2d3d4f64)

      // umull r3, r4, r1, r2
      assert(testMultiply(0xE0843291, cycles = 3, r1 = 0x01020304, r2 = 0xd0) == (0, 0xd1a27340))
      assert(testMultiply(0xE0843291, cycles = 4, r1 = 0x01020304, r2 = 0xc0d0) == (0xc2, 0x53e57340))
      assert(testMultiply(0xE0843291, cycles = 5, r1 = 0x01020304, r2 = 0xb0c0d0) == (0xb224, 0x66a57340))
      assert(testMultiply(0xE0843291, cycles = 6, r1 = 0x01020304, r2 = 0xa0b0c0d0) == (0xa1f406, 0xe6a57340))

      // umlal r3, r4, r1, r2
      assert(testMultiply(0xE0A43291, cycles = 4, r1 = 0x01020304, r2 = 0xd0, r4 = 0x11111111, r3 = 0x22222222) == (0x11111111, 0xf3c49562))
      assert(testMultiply(0xE0A43291, cycles = 5, r1 = 0x01020304, r2 = 0xc0d0, r4 = 0x11111111, r3 = 0x22222222) == (0x111111d3, 0x76079562))
      assert(testMultiply(0xE0A43291, cycles = 6, r1 = 0x01020304, r2 = 0xb0c0d0, r4 = 0x11111111, r3 = 0x22222222) == (0x1111c335, 0x88c79562))
      assert(testMultiply(0xE0A43291, cycles = 7, r1 = 0x01020304, r2 = 0xa0b0c0d0, r4 = 0x11111111, r3 = 0x22222222) == (0x11b30518, 0x08c79562))
    }
  }
}
