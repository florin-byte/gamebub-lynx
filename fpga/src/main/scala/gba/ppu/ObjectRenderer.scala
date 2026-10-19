package gba.ppu

import chisel3._
import chisel3.util._
import gba.ppu.PpuRegisters.AffineReferencePoint
import lib.log.Logger

object ObjectEffectKind extends ChiselEnum {
  val Normal = Value
  val Alpha = Value
  val Window = Value
  val Forbidden = Value
}

class ObjectAttribute0 extends Bundle {
  val shape = UInt(2.W)
  val bpp8 = Bool()
  val mosaic = Bool()
  val effect = ObjectEffectKind()
  val double = Bool()
  val affine = Bool()
  val y = UInt(8.W)
}

class ObjectAttribute1 extends Bundle {
  val size = UInt(2.W)
  val flipY = Bool()
  val flipX = Bool()
  val affineIndexLo = UInt(3.W)
  val x = UInt(9.W)
}

class ObjectAttribute2 extends Bundle {
  val paletteBank = UInt(4.W)
  val priority = UInt(2.W)
  val tile = UInt(10.W)
}

class ObjectBufferEntry extends Bundle {
  val opaque = Bool()
  val color = UInt(8.W)
  val priority = UInt(2.W)
  val window = Bool()
  val blend = Bool()
  val mosaic = Bool()
}

/// Combined and calculated object attributes from the OAM fetch stage.
class ObjectAttributeFull extends Bundle {
  val x = UInt(9.W)
  /// Pixel row within the object
  val row = UInt(7.W)
  /// Sprite width in tiles (8 pixels)
  val w = UInt(5.W)
  /// Sprite height in tiles (8 pixels)
  val h = UInt(5.W)
  /// Texture width in tiles
  val texW = UInt(4.W)
  /// Texture height in tiles
  val texH = UInt(4.W)
  /// Base tile index
  val tile = UInt(10.W)
  val paletteBank = UInt(4.W)
  val bpp8 = Bool()
  val priority = UInt(2.W)
  val flipX = Bool()
  val affine = Bool()
  val window = Bool()
  val blend = Bool()
  val mosaic = Bool()
}

class ObjectBuffer extends Module {
  val io = IO(new Bundle {
    val writeEnable = Input(Bool())
    val writeIndex = Input(UInt(8.W))
    val writeData = Input(UInt((new ObjectBufferEntry).getWidth.W))
    val writeReadback = Output(UInt((new ObjectBufferEntry).getWidth.W))
    val readIndex = Input(UInt(8.W))
    val readData = Output(UInt((new ObjectBufferEntry).getWidth.W))
  })

  val buffer = RegInit(VecInit.fill(240)(0.U((new ObjectBufferEntry).getWidth.W)))
  when (io.writeEnable) {
    buffer(io.writeIndex) := io.writeData
  }
  io.writeReadback := buffer(io.writeIndex)
  io.readData := buffer(io.readIndex)
}

class ObjectRenderer extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())

    val displayControl = Input(new PpuRegisters.DisplayControl)
    val mosaicY = Input(UInt(4.W))

    /// OBJ VRAM access
    val vram = Flipped(new PpuMemoryInterface(32 * 1024 / 2, 16.W))

    /// OAM access
    val oam = Flipped(new PpuMemoryInterface(1024 / 4, 32.W))

    /// Current cycle in the scanline
    val tick = Input(UInt(11.W))
    val scanline = Input(UInt(8.W))

    /// Compositor access of buffer
    val bufferIndex = Input(UInt(8.W))
    val bufferRead = Input(Bool())
    val bufferData = Output(new ObjectBufferEntry)
  })
  val logger = Logger("ppu.obj", enable = io.enable)

  val active = RegInit(false.B)
  val renderY = Reg(UInt(8.W))
  val renderYMosaic = Reg(UInt(8.W))
  val mosaicCounter = Reg(UInt(4.W))
  val evenTick = io.tick(0) === 0.U

  // Object scanline buffer. 240 entries, times two, rounded to power-of-two.
  val buffer0 = Module(new ObjectBuffer)
  val buffer1 = Module(new ObjectBuffer)
  val bufferWriteIndex = Wire(UInt(8.W))
  val bufferWriteData = Wire(new ObjectBufferEntry)
  val bufferWriteEnable = WireDefault(false.B)
  val bufferWriteReadback = Wire(new ObjectBufferEntry)
  val bufferPage = Reg(UInt(1.W))
  for (buffer <- Seq(buffer0, buffer1)) {
    buffer.io.writeEnable := false.B
    buffer.io.writeIndex := bufferWriteIndex
    buffer.io.writeData := bufferWriteData.asUInt
    buffer.io.readIndex := io.bufferIndex
  }
  bufferWriteIndex := DontCare
  bufferWriteData := DontCare
  io.bufferData := Mux(bufferPage === 1.U, buffer0.io.readData, buffer1.io.readData).asTypeOf(new ObjectBufferEntry)
  when (bufferWriteEnable && io.enable) {
    buffer0.io.writeEnable := bufferPage === 0.U
    buffer1.io.writeEnable := bufferPage === 1.U
  }
  bufferWriteReadback := Mux(bufferPage === 0.U, buffer0.io.writeReadback, buffer1.io.writeReadback).asTypeOf(new ObjectBufferEntry)

  // Pixel draw
  val drawX = Reg(UInt(9.W))
  val drawCount = RegInit(0.U(2.W))
  val drawData = Reg(Vec(2, new ObjectBufferEntry))
  when (io.enable && drawCount > 0.U) {
    val pixel = drawData(0)
    bufferWriteEnable := drawX < 240.U
    bufferWriteIndex := drawX
    bufferWriteData := bufferWriteReadback

    when (pixel.window && pixel.opaque) {
      bufferWriteData.window := true.B
    } .elsewhen (!bufferWriteReadback.opaque || pixel.priority < bufferWriteReadback.priority) {
      when (pixel.opaque) {
        bufferWriteData.opaque := true.B
        bufferWriteData.color := pixel.color
        bufferWriteData.blend := pixel.blend
      }

      // GBA compositing bug: a *transparent* pixel drawn over an opaque pixel of lower priority
      // overwrites the priority. Not present in DS or later.
      bufferWriteData.priority := pixel.priority
      bufferWriteData.mosaic := pixel.mosaic
    }

    drawData(0) := drawData(1)
    drawCount := drawCount - 1.U
    drawX := drawX + 1.U
  }

  // VRAM fetch
  io.vram.read := false.B
  io.vram.address := DontCare
  val fetchObj = Reg(new ObjectAttributeFull)
  val fetchCol = Reg(UInt(8.W))
  val fetchActive = RegInit(false.B)
  val allowOam = RegInit(true.B) // Whether the VRAM fetch is blocking OAM fetch
  val fetchAffX = Reg(new AffineReferencePoint)
  val fetchAffY = Reg(new AffineReferencePoint)
  val fetchAffineParams = Reg(new PpuRegisters.AffineParams)
  when (io.enable && fetchActive) {
    val col = Wire(UInt(16.W))
    val row = Wire(UInt(16.W))
    val tileX = Wire(UInt(4.W))
    val tileY = Wire(UInt(4.W))
    val subtileX = Wire(UInt(3.W))
    val subtileY = Wire(UInt(3.W))
    when (!fetchObj.affine) {
      col := Mux(fetchObj.flipX, fetchCol ^ ((fetchObj.w << 3).asUInt - 1.U), fetchCol)
      row := fetchObj.row
      tileX := col(6, 3)
      tileY := row(5, 3)
      subtileX := col(2, 0)
      subtileY := row(2, 0)
    } .otherwise {
      col := fetchAffX.int + (fetchObj.texW << 2).asUInt
      row := fetchAffY.int + (fetchObj.texH << 2).asUInt
      tileX := col(6, 3)
      tileY := row(6, 3)
      subtileX := col(2, 0)
      subtileY := row(2, 0)
    }

    when (evenTick) {
      // Fetch from VRAM
      // objMapping 1 is 1D, otherwise 2D
      val tileStride = Mux(io.displayControl.objMapping === 1.U, OHToUInt(fetchObj.texW), Mux(fetchObj.bpp8, 4.U, 5.U))
      val tileOffset = tileX + (tileY << tileStride)
      io.vram.read := true.B
      when (fetchObj.bpp8) {
        // 8BPP tiles are 0x40 bytes long, but fetchObj.tile is always in multiples of 0x20 bytes.
        val subtile = Cat(subtileY, subtileX(2, 1))
        io.vram.address := Cat(tileOffset, subtile) + Cat(fetchObj.tile, 0.U(4.W))
      } .otherwise {
        val tile = fetchObj.tile + tileOffset
        val subtile = Cat(subtileY, subtileX(2))
        io.vram.address := Cat(tile, subtile)
      }
    } .otherwise {
      // Move from VRAM to draw queue

      when (!fetchObj.affine) {
        drawX := fetchObj.x + fetchCol - 1.U
        drawCount := 2.U
        when (fetchObj.bpp8) {
          val tileData = io.vram.readData.asTypeOf(Vec(2, UInt(8.W)))
          for (i <- 0 until 2) {
            val color = tileData(i.U ^ fetchObj.flipX)
            drawData(i).opaque := color =/= 0.U
            drawData(i).color := color
            drawData(i).priority := fetchObj.priority
            drawData(i).window := fetchObj.window
            drawData(i).blend := fetchObj.blend
            drawData(i).mosaic := fetchObj.mosaic
          }
        } .otherwise {
          val tileData = io.vram.readData.asTypeOf(Vec(4, UInt(4.W)))
          for (i <- 0 until 2) {
            val subtileCol = Cat(fetchCol(1), i.U(1.W))
            val color = tileData(Mux(fetchObj.flipX, (~subtileCol).asUInt, subtileCol))
            drawData(i).opaque := color =/= 0.U
            drawData(i).color := Cat(fetchObj.paletteBank, color)
            drawData(i).priority := fetchObj.priority
            drawData(i).window := fetchObj.window
            drawData(i).blend := fetchObj.blend
            drawData(i).mosaic := fetchObj.mosaic
          }
        }
      } .otherwise {
        // Bounds check only needs to consider positive numbers, as negative numbers will be way out of bounds.
        val inBounds = (col < (fetchObj.texW << 3).asUInt) && (row < (fetchObj.texH << 3).asUInt)
        drawX := fetchObj.x + fetchCol
        drawCount := 1.U

        when (fetchObj.bpp8) {
          val tileData = io.vram.readData.asTypeOf(Vec(2, UInt(8.W)))
          val color = tileData(subtileX(0))
          drawData(0).opaque := inBounds && (color =/= 0.U)
          drawData(0).color := color
          drawData(0).priority := fetchObj.priority
          drawData(0).window := fetchObj.window
          drawData(0).blend := fetchObj.blend
          drawData(0).mosaic := fetchObj.mosaic
        } .otherwise {
          val tileData = io.vram.readData.asTypeOf(Vec(4, UInt(4.W)))
          val color = tileData(subtileX(1, 0))
          drawData(0).opaque := inBounds && (color =/= 0.U)
          drawData(0).color := Cat(fetchObj.paletteBank, color)
          drawData(0).priority := fetchObj.priority
          drawData(0).window := fetchObj.window
          drawData(0).blend := fetchObj.blend
          drawData(0).mosaic := fetchObj.mosaic
        }

        fetchAffX := (fetchAffX.asUInt.asSInt + fetchAffineParams.pa.asUInt.asSInt).asTypeOf(new AffineReferencePoint)
        fetchAffY := (fetchAffY.asUInt.asSInt + fetchAffineParams.pc.asUInt.asSInt).asTypeOf(new AffineReferencePoint)
      }

      // Allow OAM fetch at the last VRAM fetch cycle.
      when (fetchObj.affine) {
        allowOam := ((fetchCol + 2.U) >> 3) === fetchObj.w
      } .otherwise {
        allowOam := ((fetchCol + 3.U) >> 3) === fetchObj.w
      }
    }

    // Increment draw column or end stage.
    val nextCol = fetchCol + (!fetchObj.affine || !evenTick).asUInt
    when (nextCol >> 3 === fetchObj.w) {
      // Done drawing.
      fetchActive := false.B
    } .otherwise {
      fetchCol := nextCol
    }
  }

  // OAM Fetch
  val oamIndex = Reg(UInt(7.W))
  io.oam.read := false.B
  io.oam.address := DontCare
  val oamStage = Reg(UInt(3.W))
  val oamAttrs = Reg(new ObjectAttributeFull)
  val oamAffineIndex = Reg(UInt(5.W))
  when (io.enable && active && allowOam) {
    val advanceIndex = WireDefault(false.B)

    switch (oamStage) {
      is (0.U) {
        // Fetch OAM attribute 0 and 1
        when (evenTick) {
          io.oam.read := true.B
          io.oam.address := Cat(oamIndex, 0.U(1.W))
        } .otherwise {
          val attr0 = io.oam.readData(15, 0).asTypeOf(new ObjectAttribute0)
          val attr1 = io.oam.readData(31, 16).asTypeOf(new ObjectAttribute1)
          val (width, height) = getObjectSize(attr0, attr1)
          val objY = Mux(attr0.mosaic, renderYMosaic, renderY)
          val objRow = (objY -& attr0.y)(7, 0)

          oamAttrs.x := attr1.x
          oamAttrs.row := Mux(attr1.flipY && !attr0.affine, objRow ^ ((height << 3).asUInt - 1.U), objRow)
          oamAttrs.w := Mux(attr0.double, width << 1, width)
          oamAttrs.h := Mux(attr0.double, height << 1, height)
          oamAttrs.texW := width
          oamAttrs.texH := height
          oamAttrs.bpp8 := attr0.bpp8
          oamAttrs.flipX := attr1.flipX
          oamAttrs.affine := attr0.affine
          oamAttrs.window := attr0.effect === ObjectEffectKind.Window
          oamAttrs.blend := attr0.effect === ObjectEffectKind.Alpha
          oamAttrs.mosaic := attr0.mosaic
          oamAffineIndex := Cat(attr1.flipY, attr1.flipX, attr1.affineIndexLo)

          val boundingH = Mux(attr0.double, height << 4, height << 3).asUInt
          val yMax = attr0.y + boundingH
          val inRange = (renderY >= attr0.y || yMax < attr0.y) && renderY < yMax
          when (inRange && !(attr0.double && !attr0.affine)) {
            // This object is in range, and will be rendered.
            oamStage := 1.U
          } .otherwise {
            advanceIndex := true.B
          }
        }
      }
      is (1.U) {
        // Fetch OAM attribute 2
        when (evenTick) {
          io.oam.read := true.B
          io.oam.address := Cat(oamIndex, 1.U(1.W))
        } .otherwise {
          val attr2 = io.oam.readData(15, 0).asTypeOf(new ObjectAttribute2)

          // Set up draw stage state
          fetchObj := oamAttrs
          fetchObj.tile := attr2.tile
          fetchObj.paletteBank := attr2.paletteBank
          fetchObj.priority := attr2.priority

          when (oamAttrs.affine) {
            // Fetch matrix coefficients
            oamStage := 2.U
          } .otherwise {
            // Go to draw stage
            fetchActive := true.B
            advanceIndex := true.B

            // Handle left-side clipping of regular sprites.
            // If fetchObj.x + fetchCol > 240 (wrapping at 512), the pixel is invisible,
            // see logic for affine sprites below.
            fetchCol := 0.U
            when (oamAttrs.x >= 240.U) {
              val clipped = (~oamAttrs.x).asUInt + 1.U
              // fetchCol must be even
              fetchCol := Cat(clipped(7, 1), 0.U(1.W))
              when (clipped >= (oamAttrs.w << 3).asUInt) {
                fetchActive := false.B
              }
            }
          }
        }
      }
      is (2.U) {
        // Read affine parameter 0: PA
        when (evenTick) {
          io.oam.read := true.B
          io.oam.address := Cat(oamAffineIndex, 1.U(3.W))
        } .otherwise {
          val data = io.oam.readData(31, 16)
          fetchAffineParams.pa := data.asTypeOf(new PpuRegisters.FixedPoint(7))
          oamStage := 3.U
        }
      }
      is (3.U) {
        // Read affine parameter 1: PB
        when (evenTick) {
          io.oam.read := true.B
          io.oam.address := Cat(oamAffineIndex, 3.U(3.W))
        } .otherwise {
          val data = io.oam.readData(31, 16)
          fetchAffineParams.pb := data.asTypeOf(new PpuRegisters.FixedPoint(7))
          oamStage := 4.U
        }
      }
      is (4.U) {
        // Read affine parameter 2: PC
        when (evenTick) {
          io.oam.read := true.B
          io.oam.address := Cat(oamAffineIndex, 5.U(3.W))
        } .otherwise {
          val data = io.oam.readData(31, 16)
          fetchAffineParams.pc := data.asTypeOf(new PpuRegisters.FixedPoint(7))
          oamStage := 5.U
        }
      }
      is (5.U) {
        // Read affine parameter 3: PD
        when (evenTick) {
          io.oam.read := true.B
          io.oam.address := Cat(oamAffineIndex, 7.U(3.W))
        } .otherwise {
          val data = io.oam.readData(31, 16)
          fetchAffineParams.pd := data.asTypeOf(new PpuRegisters.FixedPoint(7))
          oamStage := 6.U
        }
      }
      is (6.U) {
        // Go to draw stage
        when (!evenTick) {
          fetchActive := true.B
          advanceIndex := true.B

          // Handle left-side clipping of affine sprites.
          // If fetchObj.x + fetchCol > 240 (wrapping at 512), the pixel is invisible.
          val clippedFetchCol = WireDefault(0.U(8.W))
          when (fetchObj.x >= 240.U) {
            // fetchObj.x + fetchCol = 512
            // fetchCol = 512 - fetchObj.x
            clippedFetchCol := (~fetchObj.x).asUInt + 1.U
            when (clippedFetchCol >= (fetchObj.w << 3).asUInt) {
              fetchActive := false.B
            }
          }
          fetchCol := clippedFetchCol

          // TODO, make more efficient? pipelineable?
          val halfwidth = (fetchObj.w << 2).asUInt.zext
          val halfheight = (fetchObj.h << 2).asUInt.zext
          val offsetX = clippedFetchCol.zext - halfwidth
          val offsetY = oamAttrs.row.zext - halfheight
          fetchAffX := (
            (fetchAffineParams.pb.asUInt.asSInt * offsetY) +&
              (fetchAffineParams.pa.asUInt.asSInt * offsetX)
          ).pad((new AffineReferencePoint).getWidth).asTypeOf(new AffineReferencePoint)
          fetchAffY := (
            (fetchAffineParams.pd.asUInt.asSInt * offsetY) +&
              (fetchAffineParams.pc.asUInt.asSInt * offsetX)
          ).pad((new AffineReferencePoint).getWidth).asTypeOf(new AffineReferencePoint)
        }
      }
    }

    when (advanceIndex) {
      val nextOamIndex = oamIndex + 1.U
      when (nextOamIndex === 0.U) {
        // End of scan
        oamStage := 7.U
      } .otherwise {
        oamStage := 0.U
        oamIndex := nextOamIndex
      }
    }
  }

  // Object render activation
  when (io.enable) {
    when (active && io.tick === Mux(io.displayControl.hblankFree, 1005.U, 39.U)) {
      active := false.B
    }
    when (io.displayControl.enableObj && (io.scanline < 160.U || io.scanline === 227.U) && io.tick === 39.U) {
      active := true.B
      renderY := io.scanline + 1.U

      mosaicCounter := mosaicCounter + 1.U
      when (mosaicCounter === io.mosaicY) {
        mosaicCounter := 0.U
        renderYMosaic := io.scanline + 1.U
      }

      when (io.scanline === 227.U) {
        renderY := 0.U
        renderYMosaic := 0.U
        mosaicCounter := 0.U
      }
      bufferPage := !bufferPage
      when (bufferPage === 0.U) {
        buffer1.reset := true.B
      } .otherwise {
        buffer0.reset := true.B
      }
      oamIndex := 0.U
      oamStage := 0.U
      allowOam := true.B
      fetchActive := false.B
      drawCount := 0.U
    }
  }

  def getObjectSize(attr0: ObjectAttribute0, attr1: ObjectAttribute1): (UInt, UInt) = {
    val w = WireDefault(1.U(4.W))
    val h = WireDefault(1.U(4.W))
    switch (attr0.shape) {
      is (0.U) {
        w := 1.U << attr1.size
        h := 1.U << attr1.size
      }
      is (1.U) {
        w := VecInit(2.U, 4.U, 4.U, 8.U)(attr1.size)
        h := VecInit(1.U, 1.U, 2.U, 4.U)(attr1.size)
      }
      is (2.U) {
        w := VecInit(1.U, 1.U, 2.U, 4.U)(attr1.size)
        h := VecInit(2.U, 4.U, 4.U, 8.U)(attr1.size)
      }
    }
    (w, h)
  }
}
