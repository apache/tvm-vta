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

import scala.math.pow
import scala.math.sqrt

import chisel3._
import chisel3.util._
import vta.util.config._
import vta.shell._


/** TensorLoad.
 *
 * Load 1D and 2D tensors from main memory (DRAM) to input/weight
 * scratchpads (SRAM). Also, there is support for zero padding, while
 * doing the load.
 */
class TensorLoadNarrowVME(tensorType: String = "none", debug: Boolean = false)(
    implicit p: Parameters)
    extends Module {
  val tp = new TensorParams(tensorType)
  val mp = p(ShellKey).memParams
  val io = IO(new Bundle {
    val start = Input(Bool())
    val done = Output(Bool())
    val inst = Input(UInt(INST_BITS.W))
    val baddr = Input(UInt(mp.addrBits.W))
    val vme_rd = new VMEReadMaster
    val tensor = new TensorClient(tensorType)
  })
  val writePipeLatency = tp.writePipeLatency

  val sIdle :: sBusy :: Nil =
    Enum(2)
  val state = RegInit(sIdle)

  val isBusy = state === sBusy

  val localDone = Wire(Bool())
  when(io.start) {
    state := sBusy
  }.elsewhen(localDone) {
    state := sIdle
  }

  val dec = io.inst.asTypeOf(new MemDecode)

  val vmeDataBitsPipe = RegNext(io.vme_rd.data.bits)
  val vmeDataValidPipe = RegNext(io.vme_rd.data.valid, init = false.B)
  val vmeDataReadyPipe = RegNext(io.vme_rd.data.ready, init = false.B)
  val vmeDataFirePipe = vmeDataValidPipe & vmeDataReadyPipe

  //--------------------------------------
  //--- Generate data load VME command ---
  //--------------------------------------
  val vmeCmd = Module (new GenVMECmd(tensorType, debug))
  vmeCmd.io.start := io.start
  vmeCmd.io.isBusy := isBusy
  vmeCmd.io.inst := io.inst
  vmeCmd.io.baddr := io.baddr
  vmeCmd.io.vmeCmd <> io.vme_rd.cmd
  val readLen = vmeCmd.io.readLen
  val commandsDone = vmeCmd.io.done

  // count how many blocks not received
  val blkIdxWdth = log2Ceil(tp.tsSizeRatio * tp.memDepth) // the size of scratchpad in blocks
  // Nb of data blocks requestd, not received. TODO: smaller width parameter
  val blocksInFlight = Reg(UInt(blkIdxWdth.W))
  when(io.start) {
    blocksInFlight := 0.U
  }.elsewhen(isBusy && io.vme_rd.cmd.fire() && !vmeDataFirePipe) {
    blocksInFlight := blocksInFlight + readLen
  }.elsewhen(isBusy && io.vme_rd.cmd.fire() && vmeDataFirePipe) {
    blocksInFlight := blocksInFlight + readLen - 1.U
  }.elsewhen(isBusy && !io.vme_rd.cmd.fire() && vmeDataFirePipe) {
    assert(blocksInFlight > 0.U)
    blocksInFlight := blocksInFlight - 1.U
  }.otherwise {
    blocksInFlight := blocksInFlight
  }

  //---------------------
  //--- Read VME data ---
  //---------------------

  val readData = Module(new ReadVMEData(tensorType, debug))
  readData.io.start := io.start
  readData.io.vmeData.valid := vmeDataValidPipe
  readData.io.vmeData.bits := vmeDataBitsPipe
  assert(!readData.io.vmeData.valid || readData.io.vmeData.ready,
    "-F- Expecting const ready. Fix ReadVMEData to receive data 1 cyce after ready")
  io.vme_rd.data.ready := readData.io.vmeData.ready
  val rdDataDestCol = readData.io.col // this is an index of a col in tensor
  val rdDataDestIdx = readData.io.idx // this is an index of a tensor

  //-------------------------
  //--- Fill zero padding ---
  //-------------------------

  val fillPadding = Module(new ZeroPadding(tensorType, debug))
  fillPadding.io.canWriteMem := !vmeDataFirePipe
  fillPadding.io.inst := RegNext(io.inst) // stage it to move from instr queue
  fillPadding.io.start := RegNext(io.start, init = false.B)// stage it to move from instr que

  val isZeroPadWrite = fillPadding.io.tensorIdx.valid // Store zero filled tensor, zpDestIdx is valid
  val zpDestIdx = fillPadding.io.tensorIdx.bits // Tensor index
  val paddingDone = fillPadding.io.done

  //--------------------
  //--- Write memory ---
  //--------------------

  val memSizeRatio = tp.tsSizeRatio
  val splitDataFactor = tp.splitWidth * tp.splitLength
  val splitMemBlockFactor = if (splitDataFactor > memSizeRatio) {
    require((splitDataFactor/memSizeRatio) * memSizeRatio == splitDataFactor,
      "-F- Cannot split tensor data memBlockBits further.")
    splitDataFactor/memSizeRatio
  }else {
    1
  }
  val groupMemBlockFactor = if (splitDataFactor > memSizeRatio) {
    1
  }else {
    require((memSizeRatio/splitDataFactor) * splitDataFactor == memSizeRatio,
      "-F- Cannot group tensor data memBlockBits into groups.")
    memSizeRatio/splitDataFactor
  }
  // one macro has a VME memory read bit width or read/write group bit width
  //different groups can read/write scratchpad separately
  val tensorFile = Seq.fill(memSizeRatio * splitMemBlockFactor
  ) {
    SyncReadMem(tp.memDepth, UInt((tp.memBlockBits/splitMemBlockFactor).W))
  }


  require(splitDataFactor * groupMemBlockFactor == memSizeRatio * splitMemBlockFactor,
    "-F- Wrong split of data")
  //-------------------------------
  //--- Write address vector ------
  //-------------------------------
  // split data to build pipe tree
  val splitFactorL0 = pow(2,log2Ceil(memSizeRatio) / 2).toInt
  val splitFactorL1 = pow(2,log2Ceil(memSizeRatio) - log2Ceil(memSizeRatio) / 2).toInt
  require(splitFactorL0 * splitFactorL1 == memSizeRatio)
  // tensor load instruction writes a VME data block or a whole tensor
  val waddrTensInstrTmp = Mux(isZeroPadWrite, zpDestIdx, rdDataDestIdx)
  val waddrTensInstrPipe = VecInit((for (j <- 0 until splitFactorL1) yield {
    ShiftRegister(waddrTensInstrTmp, if (writePipeLatency > 0) 1 else 0)
  }).flatMap(elem => for (k <- 0 until splitFactorL0) yield {
    elem
  }).flatMap(elem => for (k <- 0 until splitMemBlockFactor) yield {
    ShiftRegister(elem, if (writePipeLatency < 2) 0 else writePipeLatency - 1)
  }))
  require(waddrTensInstrPipe.size == memSizeRatio * splitMemBlockFactor)

  val waddrDirect = (VecInit((for (grIdx <- 0 until splitDataFactor) yield {
    io.tensor.wr(grIdx).bits.idx
  }).flatMap(elem => for (k <- 0 until groupMemBlockFactor) yield {elem}))).asTypeOf(
    Vec(memSizeRatio * splitMemBlockFactor, io.tensor.wr(0).bits.idx.cloneType)
  )


  val waddr = Wire(Vec(memSizeRatio * splitMemBlockFactor, waddrTensInstrTmp.cloneType))
  for (j <- 0 until memSizeRatio * splitMemBlockFactor) {
    waddr(j) := Mux(
      ShiftRegister(state === sIdle, writePipeLatency, resetData = true.B, en = true.B),
      waddrDirect(j),
      waddrTensInstrPipe(j))
  }

  //-------------------------------
  //--- Write enable vector -------
  //-------------------------------
  val dataOffset = rdDataDestCol
  // get en sygnal and duplicate
  val wenTensInstr = VecInit((for (j <- 0 until memSizeRatio) yield {
    Mux(isZeroPadWrite, true.B, dataOffset === j.U && vmeDataFirePipe)
  }).flatMap(elem => for (k <- 0 until splitMemBlockFactor) yield {elem}))

  val wenDirect = VecInit((for (grIdx <- 0 until splitDataFactor) yield {
    io.tensor.wr(grIdx).valid
  }).flatMap(elem => for (k <- 0 until groupMemBlockFactor) yield {elem}))

  val wen = Wire(Vec(memSizeRatio * splitMemBlockFactor, Bool()))
  for (j <- 0 until memSizeRatio * splitMemBlockFactor) {
    wen(j) := Mux(
      ShiftRegister(state === sIdle, writePipeLatency, resetData = true.B, en = true.B),
      wenDirect(j),
      ShiftRegister(wenTensInstr(j), writePipeLatency))
  }

  require(tp.memBlockBits % tp.tensorElemBits == 0)


  //-------------------------------
  //--- Write data vector ---------
  //-------------------------------
  val wdataTensInstrDataPipe = VecInit((for (j <- 0 until splitFactorL0) yield {
    ShiftRegister(vmeDataBitsPipe.data, if (writePipeLatency > 0) 1 else 0)
  }).flatMap(elem => for (k <- 0 until splitFactorL1) yield {
    elem
  }).flatMap(elem => for (k <- 0 until splitMemBlockFactor) yield {
    require(elem.getWidth == tp.memBlockBits)
    ShiftRegister(
      elem.asTypeOf(Vec(splitMemBlockFactor, UInt((tp.memBlockBits/splitMemBlockFactor).W)))(k),
      if (writePipeLatency < 2) 0 else writePipeLatency - 1)
  }))
  require(wdataTensInstrDataPipe.size == memSizeRatio * splitMemBlockFactor)
  val wdataTensInstr = Wire(Vec(memSizeRatio * splitMemBlockFactor, UInt((tp.memBlockBits/splitMemBlockFactor).W)))
  for (j <- 0 until memSizeRatio * splitMemBlockFactor) {
    // pipe 1 stage paddingControl per group
    val padValue = 0.U

    wdataTensInstr(j) := Mux(
      ShiftRegister(isZeroPadWrite, writePipeLatency, resetData = false.B, en = true.B),
      ShiftRegister(padValue /* a single group total data bits */, writePipeLatency),
      wdataTensInstrDataPipe(j))
  }

  // THIS wdataDirect writes continous scratchpad data space
  // It is WRONG for ACC batch > 1
  // maps group data bits to continous sequence of mem blocks
  // but wr(x).bits.data is a window in a tensor
  val wdataDirect = VecInit((for (grIdx <- 0 until splitDataFactor) yield {
    io.tensor.wr(grIdx).bits.data
  }).flatMap(elem => for (k <- 0 until groupMemBlockFactor) yield {
    elem.asTypeOf(Vec(groupMemBlockFactor, UInt((tp.memBlockBits/splitMemBlockFactor).W)))(k)
  }))
  val wdata = Wire(Vec(memSizeRatio * splitMemBlockFactor, UInt((tp.memBlockBits/splitMemBlockFactor).W)))
  for (j <- 0 until memSizeRatio * splitMemBlockFactor) {
    wdata(j) := Mux(
      ShiftRegister(state === sIdle, writePipeLatency, resetData = true.B, en = true.B),
      wdataDirect(j),
      wdataTensInstr(j))
  }

  for (j <- 0 until memSizeRatio * splitMemBlockFactor) {
    when(wen(j)) {
      tensorFile(j).write(waddr(j), wdata(j))
    }
  }
  if (debug) {
    when(isZeroPadWrite) {
      printf(s"[TensorLoad] $tensorType isZeroPadWrite data zpDestIdx:%d\n",
        zpDestIdx)
    }
    when (vmeDataFirePipe) {
      printf(s"[TensorLoad] $tensorType data rdDataDestCol:%d rdDataDestIdx:%d\n",
        rdDataDestCol,
        rdDataDestIdx)
    }
  }

  // read-from-sram
  for (grIdx <- 0 until splitDataFactor) {
    val rvalid = ShiftRegister(
      io.tensor.rd(grIdx).idx.valid, tp.readTensorLatency + 1, resetData = false.B, en = true.B)
    io.tensor.rd(grIdx).data.valid := rvalid
  }

  val memsInGroup = memSizeRatio * splitMemBlockFactor / splitDataFactor
  for (grIdx <- 0 until splitDataFactor) {
    io.tensor.rd(grIdx).data.bits :=
      VecInit(for (memBlkIdx <- 0 until memsInGroup) yield {
        tensorFile(grIdx * memsInGroup + memBlkIdx).read(
          ShiftRegister(io.tensor.rd(grIdx).idx.bits, tp.readTensorLatency),
          ShiftRegister(io.tensor.rd(grIdx).idx.valid, tp.readTensorLatency, resetData = false.B, en = true.B))
      }).asTypeOf(io.tensor.rd(grIdx).data.bits)
  }

  // done
  val loadDone = blocksInFlight === 0.U && commandsDone && state === sBusy
  localDone := loadDone && paddingDone
  io.done := ShiftRegister(localDone, writePipeLatency, resetData = false.B, en = true.B)

}

//-------------------------
//--- Fill zero padding ---
//-------------------------

//----------------------------------------------------------------------------
// Fill tensors with zeros if padding is defined
// stride must be used (xstride and ysize) if xpad_0 or xpad_1
// are not zero and matrix has more than one row of tensors
// zp states enumerate different types of padding blocks
// TOP - width = dec.xpad_0 + dec.xstride + dec.xpad_1; height = dec.ypad_0
// LEFT - width = dec.xpad_0; height = dec.ysize
// RIGHT - width = dec.xpad_1; height = dec.ysize
// BOT - width = dec.xpad_0 + dec.xstride + dec.xpad_1; height = dec.ypad_1
// BOTH - LEFT+RIGHT
// SKIP - dec.xpad_0 == 0 && dec.xpad_1

//Fill algorithm fills row by row from TOP then sides, then BOT
//----------------------------------------------------------------------------
class ZeroPadding(tensorType: String = "none", debug: Boolean = false)(
    implicit p: Parameters)
    extends Module {
  val tp = new TensorParams(tensorType)
  val mp = p(ShellKey).memParams
  val io = IO(new Bundle {
    val canWriteMem  = Input(Bool())
    val inst = Input(UInt(INST_BITS.W))
    val tensorIdx = Output(ValidIO(UInt(tp.memAddrBits.W)))
    val start  = Input(Bool())
    val done   = Output(Bool())
  })

  val dec = io.inst.asTypeOf(new MemDecode)

  val isZeroPadWrite = Wire(Bool()) // Store zero filled tensor, zpDestIdx is valid
  val zpDestIdx = Wire(dec.sram_offset.cloneType) // Tensor index
  val sZpIdle :: sZpTop :: sZpSideLeft :: sZpSideRight :: sZpSideBoth :: sZpSideSkip :: sZpBot :: Nil =
    Enum(7)
  val zpState = RegInit(sZpIdle)
  val paddingDone = zpState === sZpIdle // Done filling zero tensors
  val zpColIdx = Reg(UInt((dec.xpad_0.getWidth + dec.xsize.getWidth + dec.xpad_1.getWidth).W))
  val zpNewFillBlock = Wire(Bool()) // separate new fill block <-> inside block  row change and column idx calculation
  // Define padding area iterators
  val zpRowIdx = Reg(UInt((dec.ypad_0.getWidth + dec.ysize.getWidth + dec.ypad_1.getWidth).W)) // current padding row
  // current padding column
  val zpDestRowOffset = Reg(dec.sram_offset.cloneType) // one-dimentional offset for zpRowIdx
  zpRowIdx := zpRowIdx
  zpColIdx := zpColIdx
  zpDestRowOffset := zpDestRowOffset
  zpNewFillBlock := false.B

  //state change val
  val zpLastDataRow = zpRowIdx === dec.ypad_0 + dec.ysize - 1.U
  val zpTopLastIdx = dec.xpad_0 + dec.xsize + dec.xpad_1 - 1.U // last index of total width
  val zpWideLineEnd = (zpState === sZpSideBoth || zpState === sZpSideRight) && zpColIdx === zpTopLastIdx
  val zpNarwLineEnd = zpState === sZpSideLeft && zpColIdx === dec.xpad_0 - 1.U
  val zpFillLineEnd = zpWideLineEnd || zpNarwLineEnd

  when(io.start) {
    zpRowIdx := 0.U
    zpDestRowOffset := dec.sram_offset

    zpColIdx := 0.U
    when(dec.xpad_0 === 0.U && dec.xpad_1 =/= 0.U && dec.ypad_0 === 0.U) {
      zpColIdx := dec.xpad_0 + dec.xsize
    }
    when(dec.ypad_0 =/= 0.U) {
      zpState := sZpTop
    }.elsewhen(dec.xpad_0 =/= 0.U && dec.xpad_1 === 0.U) {
      zpState := sZpSideLeft
    }.elsewhen(dec.xpad_0 === 0.U && dec.xpad_1 =/= 0.U) {
      zpState := sZpSideRight
    }.elsewhen(dec.xpad_0 =/= 0.U && dec.xpad_1 =/= 0.U) {
      zpState := sZpSideBoth
    }.elsewhen(dec.ypad_1 =/= 0.U) {
      zpState := sZpSideSkip
    }.otherwise {
      zpState := sZpIdle // nothing to fill
    }
  }.elsewhen(
    io.canWriteMem &&
    zpState  === sZpTop &&
    zpRowIdx === dec.ypad_0 - 1.U &&  /*we know ypad_0 > 0 */
    zpColIdx === zpTopLastIdx) {
    zpNewFillBlock := true.B

    zpColIdx := 0.U
    when(dec.xpad_0 === 0.U && dec.xpad_1 =/= 0.U) {
      zpColIdx := dec.xpad_0 + dec.xsize
    }
    when(dec.xpad_0 =/= 0.U && dec.xpad_1 === 0.U) {
      zpState := sZpSideLeft
    }.elsewhen(dec.xpad_0 === 0.U && dec.xpad_1 =/= 0.U) {
      zpState := sZpSideRight
    }.elsewhen(dec.xpad_0 =/= 0.U && dec.xpad_1 =/= 0.U) {
      zpState := sZpSideBoth
    }.elsewhen(dec.ypad_1 =/= 0.U) {
      zpState := sZpSideSkip
    }.otherwise {
      zpState := sZpIdle // nothing to fill
    }
  }.elsewhen(
    zpLastDataRow &&  // last row before ypad_1
    ((zpFillLineEnd && io.canWriteMem) || // last zero tensor in xpad_0 or xpad_1
      zpState === sZpSideSkip)) /* no padding in data rows */ {

    zpNewFillBlock := true.B

    when(dec.ypad_1 =/= 0.U) { // also no dec.xpad_1 no xpad_0
      zpColIdx := 0.U // first index for ypad_1 area
      zpState := sZpBot // if more padding is needed go to count data rows
    }.otherwise {
      zpState := sZpIdle // nothing to fill
    }
  }.elsewhen(
    io.canWriteMem &&
    zpState  === sZpBot &&
    zpRowIdx === dec.ypad_0 + dec.ysize + dec.ypad_1 - 1.U &&  /*we know ypad_1 > 0 */
    zpColIdx === zpTopLastIdx) {
    zpNewFillBlock := true.B

    zpColIdx := 0.U
    zpState := sZpIdle
  }.otherwise {
    zpState := zpState
  }
  // allowed to  write memory when data reader is inactive
  isZeroPadWrite := zpState =/= sZpIdle && zpState =/= sZpSideSkip && io.canWriteMem
  zpDestIdx := zpDestRowOffset + zpColIdx

  //increment row
  // and set zpColIdx on a row change
  val incrementRow = Wire(Bool())
  incrementRow := false.B
  when(
    ((((zpState === sZpTop || zpState === sZpSideBoth || zpState === sZpSideRight || zpState === sZpBot) &&
      zpColIdx === zpTopLastIdx) ||
    (zpState === sZpSideLeft  && zpColIdx === dec.xpad_0 - 1.U))&& io.canWriteMem) ||
    zpState === sZpSideSkip) {

    zpDestRowOffset := zpDestRowOffset + zpTopLastIdx + 1.U // count rows in one-dimentional destination matrix
    zpRowIdx := zpRowIdx + 1.U
    incrementRow := true.B
    when(!zpNewFillBlock) { // column may be reset on block type change
      when(zpState === sZpSideRight) {
        zpColIdx := dec.xpad_0 + dec.xsize
      }.otherwise {
        zpColIdx := 0.U
      }
    }
  }

  //increment column if it is not done on block change or row in block change
  when(isZeroPadWrite && !zpNewFillBlock && !incrementRow) {
    when(zpState === sZpSideBoth && zpColIdx === dec.xpad_0 - 1.U) {
      zpColIdx := zpColIdx + dec.xsize + 1.U// skip data tensors

    }.otherwise {
      zpColIdx := zpColIdx + 1.U
    }
  }
  io.done := zpState === sZpIdle
  io.tensorIdx.valid := isZeroPadWrite
  io.tensorIdx.bits := zpDestIdx
}

//---------------------
//--- Read VME data ---
//---------------------
//----------------------------------------------------------------------------
// Read VME data. Generate Memory index and data
// transaction TAG is a data block offset in scratchpad
// Different transactions are identified by tag change
// SAME DESTINATION SUBSEQUENT REQUESTS IN ONE INSTRUCTION LEADS TO UNDEFINED BEHAVIOR
//----------------------------------------------------------------------------
class ReadVMEData(tensorType: String = "none", debug: Boolean = false)(
    implicit p: Parameters)
    extends Module {
  val tp = new TensorParams(tensorType)
  val mp = p(ShellKey).memParams
  val io = IO(new Bundle {
    val start = Input(Bool())
    val vmeData = Flipped(Decoupled(new VMEData))
    val idx = Output(UInt(tp.memAddrBits.W))
    val col = Output(UInt(log2Ceil(tp.tsSizeRatio).W))
  })

  io.vmeData.ready := true.B // always ready to read VME data

  require(pow(2, log2Ceil(tp.tensorSizeBits)) == tp.tensorSizeBits,
    "-F- Tensor bit size must be 2^. Using shift and bits to divide.")
  require(pow(2, log2Ceil(tp.memBlockBits)) == tp.memBlockBits,
    "-F- Tensor bit size must be 2^. Using shift and bits to divide.")
  require(tp.tsSizeRatio >= 1,
    "-F- Tensor bit size must equal or greater than read puls width.")

  val blkOffsetWidth = log2Ceil(tp.tsSizeRatio)


  val rdDataDestCol = Wire(UInt(blkOffsetWidth.W)) // this is an index of a cl in a tensor
  val rdDataDestIdx = Wire(UInt(M_SRAM_OFFSET_BITS.W)) // this is an index of a tensor
  io.vmeData.ready := true.B // always ready to read VME data

  //decode data destination
  val vmeTagDecode = io.vmeData.bits.tag
  val vmeTagDecodeLast = Reg(vmeTagDecode.cloneType) // store tag to identify a new burst
  val rdDataIdx = vmeTagDecode(vmeTagDecode.getWidth - 1, blkOffsetWidth)
  val rdDataCol = if (tp.tsSizeRatio == 1) 0.U else vmeTagDecode(blkOffsetWidth - 1, 0)
  val rdDataDestColNext = Reg(rdDataDestCol.cloneType) // this is an index in a col in tensor
  val rdDataDestIdxNext = Reg(UInt(M_SRAM_OFFSET_BITS.W)) // this is an index of a tensor

  val vmeTagDecodeLastValid = Wire(Bool())
  val vmeTagDecodeLastValidNext = RegNext(
    next = vmeTagDecodeLastValid,
    init = false.B)
  when(io.start) {
    vmeTagDecodeLastValid :=false.B // reset tag valid
  }.elsewhen(io.vmeData.fire()) {
    vmeTagDecodeLastValid := true.B // set tag valid on a new read
  }.otherwise {
    vmeTagDecodeLastValid := vmeTagDecodeLastValidNext // keep value
  }
  rdDataDestCol := DontCare
  rdDataDestIdx := DontCare
  when(io.vmeData.fire()) {
    when (
      !vmeTagDecodeLastValidNext ||
      (vmeTagDecodeLastValidNext &&
        vmeTagDecode.asUInt =/= vmeTagDecodeLast.asUInt)) {

      vmeTagDecodeLast := vmeTagDecode // a new burst
      rdDataDestCol := rdDataCol
      rdDataDestIdx := rdDataIdx
      rdDataDestColNext := rdDataCol + 1.U //increment col in tensor
      rdDataDestIdxNext := rdDataIdx
    }.otherwise {
      rdDataDestCol := rdDataDestColNext //continue burst read
      rdDataDestColNext := rdDataDestColNext + 1.U //increment col in tensor
      rdDataDestIdx := rdDataDestIdxNext
      when(rdDataDestCol === (tp.tsSizeRatio - 1).U) {
        rdDataDestIdxNext := rdDataDestIdxNext + 1.U //increment tensor index
      }
    }
  }

  io.idx := rdDataDestIdx
  io.col := rdDataDestCol
}

// transaction TAG is a data block offset in scratchpad
// Different transactions are identified by tag change
// SAME DESTINATION SUBSEQUENT REQUESTS IN ONE INSTRUCTION LEADS TO UNDEFINED BEHAVIOR
class GenVMECmd(tensorType: String = "none", debug: Boolean = false)(
    implicit p: Parameters)
    extends Module {
  val tp = new TensorParams(tensorType)
  val mp = p(ShellKey).memParams
  val io = IO(new Bundle {
    val start = Input(Bool())
    val isBusy = Input(Bool())
    val inst = Input(UInt(INST_BITS.W))
    val baddr = Input(UInt(mp.addrBits.W))
    val vmeCmd = Decoupled(new VMECmd)
    val readLen = Output(UInt((mp.lenBits + 1).W))
    val done = Output(Bool())
  })
  val sizeFactor = tp.tsSizeRatio


  val dec = io.inst.asTypeOf(new MemDecode)

  val rdCmdExtAddr = Reg(UInt(mp.addrBits.W)) // current address in the row
  val maxTransfer = (1 << mp.lenBits).U // max number of blocks in transfer
  // from old data ctrl
  val elemBytes = tp.tensorLength * tp.tensorWidth * tp.tensorElemBits / 8 // bytes in tensor
  val maskOffset = VecInit(Seq.fill(M_DRAM_OFFSET_BITS)(true.B)).asUInt
  val xfer_init_addr = io.baddr | (maskOffset & (dec.dram_offset << log2Ceil(elemBytes)))
  val maxTrBytes = maxTransfer << (log2Ceil(mp.dataBits) - 3)
  //Align first transfer to maxTrBytes boundary. It occures on every dec.xsize transfer
  //all other transfers in the row will end at maxTrBytes boundary
  val firstMaxTransfer = (maxTrBytes - rdCmdExtAddr % maxTrBytes) >> (log2Ceil(mp.dataBits) - 3)


  //--------------------------------------
  //--- Generate data load VME command ---
  //--------------------------------------

  val rdCmdStartIdxValid = Wire(Bool()) // Command is valid
  val startIssueCmdRead = Wire(Bool()) // First transaction in dec.xsize transfer
  val rdCmdStartIdx = Reg(UInt(log2Ceil(tp.memDepth).W)) // Scratchpad data block index for the first transaction
  val readLen = Wire(UInt((mp.lenBits + 1).W)) // read cmd transaction length. It is <= maxTransfer
  val commandsDone = RegInit(true.B) // Done generating VME commands
  val stride = Wire(Bool()) // flags change to the next row to read
  val blocksReadSize = (dec.xsize << log2Ceil(sizeFactor)) // how many blocks to read in a singl src row
  val blocksReadNb = Reg(blocksReadSize.cloneType)
  val rdCmdExtAddrRowBegin = Reg(UInt(mp.addrBits.W)) // starting address in the row
  val newReadRow = Reg(Bool()) // flags the first read of dec.xsize

  // set which source row of data to read. dec.ysize defines the number of rows
  val srcRowIdx = Reg(UInt(dec.ysize.getWidth.W)) // current row of stride read
  when (io.start) {
    srcRowIdx := 0.U // 1st row
  }.elsewhen (stride) {
    srcRowIdx := srcRowIdx + 1.U // increment row
  }.otherwise {
    srcRowIdx := srcRowIdx // stay in the row
  }

  // set how many blocks of data being loaded
  commandsDone := commandsDone
  when (io.start || stride) {
    blocksReadNb := 0.U
    commandsDone := false.B
  }.elsewhen (io.vmeCmd.fire()) {
    val nextBlRNb = blocksReadNb + readLen
    blocksReadNb := nextBlRNb // THIS IS WHEN A NEW VME CMD HAPPENS
    when (nextBlRNb === blocksReadSize && srcRowIdx === dec.ysize - 1.U) {
      commandsDone := true.B
    }
  }.otherwise {
    blocksReadNb := blocksReadNb
  }

  //when the whole xsize row read commands send, go for the next src row
  when((blocksReadNb === blocksReadSize - readLen) && (srcRowIdx =/= dec.ysize - 1.U) && io.vmeCmd.fire()) {
    stride := true.B
  }.otherwise {
    stride := false.B
  }

  assert(!io.isBusy || blocksReadSize >= blocksReadNb)// define how many block to read at this cycle
  val blocksRemained = blocksReadSize - blocksReadNb
  when (newReadRow) {
    when(blocksRemained < firstMaxTransfer) {
      readLen := blocksRemained
    }.otherwise {
      readLen := firstMaxTransfer
    }
  }.otherwise {
    when(blocksRemained < maxTransfer) {
      readLen := blocksRemained
    }.otherwise {
      readLen := maxTransfer
    }
  }
  // block index of the read data row (xsize). Modified by zero padding
  val totalWidth = dec.xsize + dec.xpad_0 + dec.xpad_1 // width of scratchpad matrix in tensors
  // instead of multiplying total width by ypad_0 do incremental addition.
  //Should cost ypad_0 cycles to issue 1st read cmd
  // counts src matrix with y padding rows of tensors
  val currentRowIdx = Reg(UInt((dec.ysize.getWidth + dec.ypad_0.getWidth).W))
  // start to issue read cmd
  rdCmdStartIdxValid := currentRowIdx >= dec.ypad_0 &&
    currentRowIdx < (dec.ysize + dec.ypad_0) &&
    io.isBusy &&
    !commandsDone
  when (io.start) {
    currentRowIdx := 0.U
    rdCmdStartIdx := dec.sram_offset + dec.xpad_0 // this index is in tensors
  }.elsewhen (io.isBusy && (currentRowIdx < dec.ypad_0 || stride)) {
    rdCmdStartIdx := rdCmdStartIdx + totalWidth
    currentRowIdx := currentRowIdx + 1.U
  }
  startIssueCmdRead := false.B
  when(blocksReadNb === 0.U && rdCmdStartIdxValid) {
    startIssueCmdRead := true.B
  }
  rdCmdExtAddrRowBegin := rdCmdExtAddrRowBegin

  when (io.start) {
    rdCmdExtAddr := xfer_init_addr
    rdCmdExtAddrRowBegin := xfer_init_addr
    newReadRow := true.B
  }.elsewhen (io.vmeCmd.fire()) {
    when(stride) {
      val memRow = rdCmdExtAddrRowBegin + (dec.xstride << log2Ceil(elemBytes))
      rdCmdExtAddr := memRow //  go to the next source matrix row with xstride tensors offset
      rdCmdExtAddrRowBegin := memRow
      newReadRow := true.B
    }.otherwise {
      newReadRow := false.B
      // go to the next tranaction same continous data block
      rdCmdExtAddr := rdCmdExtAddr + (readLen << (log2Ceil(mp.dataBits) - 3))
    }
  }.otherwise {
    rdCmdExtAddr := rdCmdExtAddr
    newReadRow := newReadRow
  }

  //-------------------------------------
  //--- execute VME data load command ---
  //-------------------------------------

  require(pow(2, log2Ceil(tp.tensorSizeBits)) == tp.tensorSizeBits,
    "-F- Tensor size must be 2^. Using shift and bits to divide.")
  require(pow(2, log2Ceil(tp.memBlockBits)) == tp.memBlockBits,
    "-F- Read pulsewidth must be 2^ . Using shift and bits to divide.")
  //first log2Ceil(tp.numMemBlock) bits encode block offset in a row,
  //then log2Ceil(tp.tensorLength) bits for a row in a tensor, then tensor index
  val blkOffset = log2Ceil(tp.tsSizeRatio)
  val blkIdxWdth = log2Ceil(tp.tsSizeRatio * tp.memDepth) // the size of scratchpad in blocks

  val rdCmdDestBlockIdx = Wire(UInt(blkIdxWdth.W)) // dataBits size block index in a scratchpad
  val rdCmdDestBlockIdxNext = Reg(rdCmdDestBlockIdx.cloneType) // dataBits size block index in a scratchpad
  rdCmdDestBlockIdxNext := rdCmdDestBlockIdxNext
  rdCmdDestBlockIdx := rdCmdDestBlockIdxNext

  // block position in a scratchpad
  val rdCmdValid = Wire(Bool())
  //increment scratch pad destination index
  when(rdCmdStartIdxValid) {
    rdCmdValid := true.B
    when(startIssueCmdRead) {
      rdCmdDestBlockIdx := rdCmdStartIdx << blkOffset // it is aligned by tensor size
      rdCmdDestBlockIdxNext:= rdCmdDestBlockIdx + readLen
    }.elsewhen (io.vmeCmd.fire()) {
      // increment block position by transaction length
      rdCmdDestBlockIdxNext:= rdCmdDestBlockIdxNext + readLen
    }
  }.otherwise {
    rdCmdValid := false.B
  }
  if(debug) {
    when (io.vmeCmd.fire()) {
      printf(s"[GenVMECmd] $tensorType cmd data rdCmdDestBlockIdx:%b " +
        s" length:%d \n",
        rdCmdDestBlockIdx,
        readLen)
    }
  }
  // read-from-dram
  require(io.vmeCmd.bits.tag.getWidth >= rdCmdDestBlockIdx.getWidth,
    "-F- Not enough VME tag bits to store transaction tag.")
  io.vmeCmd.valid := rdCmdValid
  io.vmeCmd.bits.addr := rdCmdExtAddr
  io.vmeCmd.bits.len := readLen - 1.U
  assert(!io.vmeCmd.valid || ((readLen << log2Ceil(mp.dataBits/8)) <= (maxTrBytes - rdCmdExtAddr % maxTrBytes)),
    s"-F- ${tensorType} DRAM page alignment failure. DRAM " +
    s"address + len overlaps mp.lenBits*memBlockSize alignment %x %x",
    rdCmdExtAddr, readLen)
  io.vmeCmd.bits.tag := rdCmdDestBlockIdx
  io.readLen := readLen
  io.done := commandsDone
}
