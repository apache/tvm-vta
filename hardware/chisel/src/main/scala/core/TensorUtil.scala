/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package vta.core

import chisel3._
import chisel3.util._
import vta.util.config._
import vta.shell._

/** TensorParams.
 *
 * This Bundle derives parameters for each tensorType, including inputs (inp),
 * weights (wgt), biases (acc), and outputs (out). This is used to avoid
 * doing the same boring calculations over and over again.
 */
class TensorParams(tensorType: String = "none")(implicit p: Parameters) extends Bundle {
  val errorMsg =
    s"\n\n[VTA] [TensorParams] only inp, wgt, acc, and out supported\n\n"

  require(tensorType == "inp" || tensorType == "wgt"
    || tensorType == "acc" || tensorType == "out" || tensorType == "fetch" || tensorType == "uop",
    errorMsg)

  val (tensorLength, tensorWidth, tensorElemBits) =
    if (tensorType == "inp")
      (p(CoreKey).batch, p(CoreKey).blockIn, p(CoreKey).inpBits)
    else if (tensorType == "wgt")
      (p(CoreKey).blockOut, p(CoreKey).blockIn, p(CoreKey).wgtBits)
    else if (tensorType == "acc")
      (p(CoreKey).batch, p(CoreKey).blockOut, p(CoreKey).accBits)
    else if (tensorType == "fetch") {
      // make fetch a 64 bit data to be able to read
      // 64 bit aligned address. It works for wide cacheline
      // fetch tensorload is not used for narrow data load
      require(p(ShellKey).memParams.dataBits >= INST_BITS,
        "-F- Cannot make fetch tensor narrower than data pulse. TODO: narrow fetch with tensors")
      (1, 1, 64)
    }
    else if (tensorType == "uop")
      (1, 1, p(CoreKey).uopBits)
    else
      (p(CoreKey).batch, p(CoreKey).blockOut, p(CoreKey).outBits)

  val memBlockBits = p(ShellKey).memParams.dataBits
  val numMemBlock = (tensorWidth * tensorElemBits) / memBlockBits

  val memDepth =
    if (tensorType == "inp")
      p(CoreKey).inpMemDepth
    else if (tensorType == "wgt")
      p(CoreKey).wgtMemDepth
    else if (tensorType == "acc")
      p(CoreKey).accMemDepth
    else if (tensorType == "fetch") {
      require(p(ShellKey).memParams.dataBits >= INST_BITS,
        "-F- Cannot make fetch tensor narrower than data pulse. TODO: narrow fetch with tensors")
      // still should be one data line
      (1 << p(ShellKey).memParams.lenBits)*(INST_BITS / 64)
    }
    else if (tensorType == "uop") {
      p(CoreKey).uopMemDepth
    }
    else
      p(CoreKey).outMemDepth

  // the number of cycles Instruction write is delayed
  // Idle state writes are not delayed
  // inserted regs are used to physically deliver signal to memories
  val writePipeLatency =
    if (tensorType == "inp") {
      0 // VME data load cmd write (per group)
    } else if (tensorType == "wgt") {
      0 // VME data load cmd write (per group)
    } else if (tensorType == "acc") {
      0 // VME data load cmd write (per group)
    } else if (tensorType == "fetch") {
      0
    } else if (tensorType == "uop") {
      0
    } else if (tensorType == "out") {
      0 // direct write from core
    } else {
      0
    }

  // the number of cycles Idle state data read is delayed
  // inserted regs are used to physically deliver signal to memories
  val readTensorLatency =
    if (tensorType == "inp") {
      0 // GEMM inp data read (per memsplit)
    } else if (tensorType == "wgt") {
      0
    } else if (tensorType == "acc") {
      0
    } else if (tensorType == "fetch") {
      0
    } else if (tensorType == "uop") {
      0
    } else if (tensorType == "out") {
      0
    } else {
      0
    }
  // the number of cycles vme data signals are delayed
  // This is a global delay of VME data signals. One for all groups
  val readVMEDataLatency =
    if (tensorType == "inp") {
      0 // VME data signals delay
    } else if (tensorType == "wgt") {
      0 // VME data signals delay
    } else if (tensorType == "acc") {
      0  // VME data signals delay
    } else if (tensorType == "fetch") {
      0
    } else if (tensorType == "uop") {
      0 // VME data signals delay
    } else if (tensorType == "out") {
      0
    } else {
      0
    }


  // acc/wgt parts are grouped to form
  // a physically compact compute entity
  val (splitLength, splitWidth) =
    if (tensorType == "inp") {
      (1, 1)
    } else if (tensorType == "wgt") {
      (p(CoreKey).blockOutFactor, 1)
    } else if (tensorType == "acc") {
      // acc scratchpad is batch rows of blockout columns
      // GEMM/ALU operation group is based on wgt tiling of blockout
      // means acc out of a group if batch > 1 is not
      // continous data and may be placed into different memory
      // modules. But the whole idea of a group to localize
      // piece of wgt to piece of acc data transformation
      (1, p(CoreKey).blockOutFactor)
    } else if (tensorType == "fetch") {
      (1, 1)
    } else if (tensorType == "uop") {
      (1, 1)
    } else if (tensorType == "out") {
      (1, 1) // narrow store doesnt support split
    } else {
      (1, 1)
    }
  require (splitLength == 1 || splitWidth == 1, "-F- Can split only one dimension.")

  // provide index of a group closes to IO
  // expect 2 columns of groups io on top and indexing from bottom
  val closestIOGrpIdx =
    if (tensorType == "inp") {
      splitLength - 1
    } else if (tensorType == "wgt") {
      if (splitLength < 2) 0 else splitLength / 2 - 1
    } else if (tensorType == "acc") {
      if (splitWidth < 2) 0 else splitWidth / 2 - 1
    } else if (tensorType == "fetch") {
      0
    } else if (tensorType == "uop") {
      0
    } else if (tensorType == "out") {
      0
    } else {
      0
    }

  // this split doesnt change tensorLoad interface, but
  // allows pipe VME write control signals per group of memory modules
  val splitMemsFactor =
    if (tensorType == "inp") {
      1
    } else {
      1
    }

  val memAddrBits = log2Ceil(memDepth)

  val tensorSizeBits = tensorLength * tensorWidth * tensorElemBits
  val tsSizeRatio = tensorSizeBits / memBlockBits
  val clSizeRatio = memBlockBits / tensorSizeBits

  val lenSplit = tensorLength / splitLength // tensor rows in a group
  val widthSplit = tensorWidth / splitWidth // tensor colums in a group
  require(lenSplit > 0 && widthSplit > 0, "-F- wrong split")

  // tensor condsiders groups as a continous data, gemm generates a data window
  // Map data index from a window index to a continous groups index
  def reindexDataFromGroup (grpIdx : Int, lenIdx: Int, wdtIdx: Int) = {

    val grpLen = lenSplit // tensor rows in a group
    val grpWdt = widthSplit // tensor colums in a group
    val srcGrpRow = grpIdx / splitWidth // group row
    val srcGrpCol = grpIdx % splitWidth // group column
    val tnzRow = srcGrpRow * grpLen
    val tnzCol = srcGrpCol * grpWdt
    val flatIdx = (tnzRow + lenIdx) * tensorWidth + tnzCol + wdtIdx

    val outGroupIdx = flatIdx / (grpLen * grpWdt)
    val outGroupOffset = flatIdx % (grpLen * grpWdt)
    val outGroupLenIdx = outGroupOffset / grpWdt
    val outGroupWdthIdx = outGroupOffset % grpWdt
    (outGroupIdx, outGroupLenIdx, outGroupWdthIdx)
  }
  // map data index form a continous to a window index
  def reindexDataToGroup (grpIdx : Int, lenIdx: Int, wdtIdx: Int) = {
    val outGrpLen = tensorLength / splitLength // data rows in a group
    val outGrpWdt = tensorWidth / splitWidth // data colums in a group

    val outIdx = grpIdx * outGrpLen * outGrpWdt + lenIdx * outGrpWdt + wdtIdx
    val outRow = outIdx / tensorWidth
    val outCol = outIdx % tensorWidth


    val outGrpRow = outRow / outGrpLen
    val outLenIdx = outRow % outGrpLen

    val outGrpCol = outCol / outGrpWdt
    val outColIdx = outCol % outGrpWdt

    val outGrpIdx = outGrpRow * splitWidth + outGrpCol

    (outGrpIdx, outLenIdx, outColIdx)

  }
  def paramsStr () = {
    s" ${tensorType} ${tensorSizeBits*memDepth/8} Byte. length:${tensorLength} width:${tensorWidth}" +
    s" data bits:${tensorElemBits} mem depth:${memDepth} groups split length:${splitLength}" +
    s" split width:${splitWidth} pipe write:${writePipeLatency}"
  }
}

/** TensorMaster.
 *
 * This interface issue read and write tensor-requests to scratchpads. For example,
 * The TensorGemm unit uses this interface for managing the inputs (inp), weights (wgt),
 * biases (acc), and outputs (out).
 *
 */
class TensorMaster(tensorType: String = "none")
  (implicit p: Parameters) extends TensorParams(tensorType) {
  val rd = Vec(splitLength * splitWidth, new Bundle {
    val idx = ValidIO(UInt(memAddrBits.W))
    val data = Flipped(
      ValidIO(Vec(lenSplit, Vec(widthSplit, UInt(tensorElemBits.W)))))
  })
  val wr = Vec(splitLength * splitWidth, ValidIO(new Bundle {
    val idx = UInt(memAddrBits.W)
    val data = Vec(lenSplit, Vec(widthSplit, UInt(tensorElemBits.W)))
  }))
  def tieoffRead() {
    for (idx <- 0 until splitLength * splitWidth) {
      rd(idx).idx.valid := false.B
      rd(idx).idx.bits := 0.U
    }
  }
  def tieoffWrite() {
    for (idx <- 0 until splitLength * splitWidth) {
      wr(idx).valid := false.B
      wr(idx).bits.idx := 0.U
      wr(idx).bits.data.foreach { b =>
        b.foreach { c =>
          c := 0.U
        }
      }
    }
  }
  override def cloneType =
    new TensorMaster(tensorType).asInstanceOf[this.type]
}

/** TensorClient.
 *
 * This interface receives read and write tensor-requests to scratchpads. For example,
 * The TensorLoad unit uses this interface for receiving read and write requests from
 * the TensorGemm unit.
 */
class TensorClient(tensorType: String = "none")
  (implicit p: Parameters) extends TensorParams(tensorType) {
  val rd = Vec(splitLength * splitWidth, new Bundle {
    val idx = Flipped(ValidIO(UInt(memAddrBits.W)))
    val data = ValidIO(
      Vec(lenSplit, Vec(widthSplit, UInt(tensorElemBits.W))))
  })
  val wr = Vec(splitLength * splitWidth, Flipped(ValidIO(new Bundle {
    val idx = UInt(memAddrBits.W)
    val data = Vec(lenSplit, Vec(widthSplit, UInt(tensorElemBits.W)))
  })))
  def tieoffRead() {
    for (idx <- 0 until splitLength * splitWidth) {
      rd(idx).data.valid := false.B
      rd(idx).data.bits.foreach { b =>
        b.foreach { c =>
          c := 0.U
        }
      }
    }
  }
  def tieoffWrite() {
    for (idx <- 0 until splitLength * splitWidth) {
      wr(idx).valid := false.B
      wr(idx).bits.idx := 0.U
      wr(idx).bits.data.foreach { b =>
        b.foreach { c =>
          c := 0.U
        }
      }
    }
  }
  override def cloneType =
    new TensorClient(tensorType).asInstanceOf[this.type]
}

/** TensorMasterData.
 *
 * This interface is only used for datapath only purposes and the direction convention
 * is based on the TensorMaster interface, which means this is an input. This interface
 * is used on datapath only module such MatrixVectorCore or AluVector.
 */
class TensorMasterData(tensorType: String = "none")
  (implicit p: Parameters) extends TensorParams(tensorType) {
  val data = Flipped(
    ValidIO(Vec(lenSplit, Vec(widthSplit, UInt(tensorElemBits.W)))))
  override def cloneType =
    new TensorMasterData(tensorType).asInstanceOf[this.type]
}

/** TensorClientData.
 *
 * This interface is only used for datapath only purposes and the direction convention
 * is based on the TensorClient interface, which means this is an output. This interface
 * is used on datapath only module such MatrixVectorCore or AluVector.
 */
class TensorClientData(tensorType: String = "none")
  (implicit p: Parameters) extends TensorParams(tensorType) {
  val data = ValidIO(
    Vec(lenSplit, Vec(widthSplit, UInt(tensorElemBits.W))))
  override def cloneType =
    new TensorClientData(tensorType).asInstanceOf[this.type]
}

/** TensorPadCtrl. Zero-padding controller for TensorLoad. */
class TensorPadCtrl(padType: String = "none", sizeFactor: Int = 1) extends Module {
  val errorMsg =
    s"\n\n\n[VTA-ERROR] only YPad0, YPad1, XPad0, or XPad1 supported\n\n\n"
  require(padType == "YPad0" || padType == "YPad1"
    || padType == "XPad0" || padType == "XPad1",
    errorMsg)

  val io = IO(new Bundle {
    val start = Input(Bool())
    val done = Output(Bool())
    val inst = Input(UInt(INST_BITS.W))
  })

  val dec = io.inst.asTypeOf(new MemDecode)

  val xmax = Reg(chiselTypeOf(dec.xsize))
  val ymax = Reg(chiselTypeOf(dec.ypad_0))
  val xcnt = Reg(chiselTypeOf(dec.xsize))
  val ycnt = Reg(chiselTypeOf(dec.ypad_0))

  val xval =
    if (padType == "YPad0" || padType == "YPad1")
      ((dec.xpad_0 + dec.xsize + dec.xpad_1) << log2Ceil(sizeFactor)) - 1.U
    else if (padType == "XPad0")
      (dec.xpad_0 << log2Ceil(sizeFactor)) - 1.U
    else
      (dec.xpad_1 << log2Ceil(sizeFactor)) - 1.U

  val yval =
    if (padType == "YPad0")
      Mux(dec.ypad_0 =/= 0.U, dec.ypad_0 - 1.U, 0.U)
    else if (padType == "YPad1")
      Mux(dec.ypad_1 =/= 0.U, dec.ypad_1 - 1.U, 0.U)
    else
      0.U

  val sIdle :: sActive :: Nil = Enum(2)
  val state = RegInit(sIdle)

  switch(state) {
    is(sIdle) {
      when(io.start) {
        state := sActive
      }
    }
    is(sActive) {
      when(ycnt === ymax && xcnt === xmax) {
        state := sIdle
      }
    }
  }

  when(state === sIdle) {
    xmax := xval
    ymax := yval
  }

  when(state === sIdle || xcnt === xmax) {
    xcnt := 0.U
  }.elsewhen(state === sActive) {
    xcnt := xcnt + 1.U
  }

  when(state === sIdle || ymax === 0.U) {
    ycnt := 0.U
  }.elsewhen(state === sActive && xcnt === xmax) {
    ycnt := ycnt + 1.U
  }

  io.done := state === sActive & ycnt === ymax & xcnt === xmax
}

/** TensorDataCtrl. Data controller for TensorLoad. */
class TensorDataCtrl(tensorType: String = "none",
    sizeFactor: Int = 1, strideFactor: Int = 1)(implicit p: Parameters) extends Module {
  val mp = p(ShellKey).memParams
  val io = IO(new Bundle {
    val start = Input(Bool())
    val done = Output(Bool())
    val inst = Input(UInt(INST_BITS.W))
    val baddr = Input(UInt(mp.addrBits.W))
    val xinit = Input(Bool())
    val xupdate = Input(Bool())
    val yupdate = Input(Bool())
    val stride = Output(Bool())
    val split = Output(Bool())
    val commit = Output(Bool())
    val addr = Output(UInt(mp.addrBits.W))
    val len = Output(UInt(mp.lenBits.W))
  })

  val dec = io.inst.asTypeOf(new MemDecode)

  val caddr = Reg(UInt(mp.addrBits.W))
  val baddr = Reg(UInt(mp.addrBits.W))
  val len = Reg(UInt(mp.lenBits.W))
  val maskOffset = VecInit(Seq.fill(M_DRAM_OFFSET_BITS)(true.B)).asUInt
  val elemBytes =
    if (tensorType == "inp") {
      (p(CoreKey).batch * p(CoreKey).blockIn * p(CoreKey).inpBits) / 8
    } else if (tensorType == "wgt") {
      (p(CoreKey).blockOut * p(CoreKey).blockIn * p(CoreKey).wgtBits) / 8
    } else {
      (p(CoreKey).batch * p(CoreKey).blockOut * p(CoreKey).accBits) / 8
    }

  val xmax_bytes = ((1 << mp.lenBits) * mp.dataBits / 8).U
  val xcnt = Reg(UInt(mp.lenBits.W))
  val xrem = Reg(chiselTypeOf(dec.xsize))
  val xsize = (dec.xsize << log2Ceil(sizeFactor)) - 1.U
  val xmax = (1 << mp.lenBits).U
  val ycnt = Reg(chiselTypeOf(dec.ysize))

  val xfer_bytes = Reg(UInt(mp.addrBits.W))
  val pulse_bytes_bits = log2Ceil(mp.dataBits >> 3)
  val xstride_bytes = dec.xstride << log2Ceil(elemBytes)

  val xfer_init_addr = io.baddr | (maskOffset & (dec.dram_offset << log2Ceil(elemBytes)))
  val xfer_split_addr = caddr + xfer_bytes
  val xfer_stride_addr = baddr + xstride_bytes

  val xfer_init_bytes   = xmax_bytes - xfer_init_addr % xmax_bytes
  val xfer_init_pulses  = xfer_init_bytes >> pulse_bytes_bits
  val xfer_split_bytes  = xmax_bytes - xfer_split_addr % xmax_bytes
  val xfer_split_pulses = xfer_split_bytes >> pulse_bytes_bits
  val xfer_stride_bytes = xmax_bytes - xfer_stride_addr % xmax_bytes
  val xfer_stride_pulses= xfer_stride_bytes >> pulse_bytes_bits

  val stride = xcnt === len &
    xrem === 0.U &
    ycnt =/= dec.ysize - 1.U

  val split = xcnt === len & xrem =/= 0.U

  when(io.start) {
    xfer_bytes := xfer_init_bytes
    when(xsize < xfer_init_pulses) {
      len := xsize
      xrem := 0.U
    }.otherwise {
      len := xfer_init_pulses - 1.U
      xrem := xsize - xfer_init_pulses
    }
  }.elsewhen(io.xupdate && stride) {
    xfer_bytes := xfer_stride_bytes
    when(xsize < xfer_stride_pulses) {
      len := xsize
      xrem := 0.U
    }.otherwise {
      len := xfer_stride_pulses - 1.U
      xrem := xsize - xfer_stride_pulses
    }
  }.elsewhen(io.xupdate && split) {
    xfer_bytes := xfer_split_bytes
    when(xrem < xfer_split_pulses) {
      len := xrem
      xrem := 0.U
    }.otherwise {
      len := xfer_split_pulses - 1.U
      xrem := xrem - xfer_split_pulses
    }
  }

  when(io.xinit) {
    xcnt := 0.U
  }.elsewhen(io.xupdate) {
    xcnt := xcnt + 1.U
  }

  when(io.start) {
    ycnt := 0.U
  }.elsewhen(io.yupdate && stride) {
    ycnt := ycnt + 1.U
  }

  when(io.start) {
    caddr := xfer_init_addr
    baddr := xfer_init_addr
  }.elsewhen(io.yupdate) {
    when(split) {
      caddr := xfer_split_addr
    }.elsewhen(stride) {
      caddr := xfer_stride_addr
      baddr := xfer_stride_addr
    }
  }

  io.stride := stride
  io.split := split
  io.commit := xcnt === len
  io.addr := caddr
  io.len := len
  io.done := xcnt === len &
    xrem === 0.U &
    ycnt === dec.ysize - 1.U
}
