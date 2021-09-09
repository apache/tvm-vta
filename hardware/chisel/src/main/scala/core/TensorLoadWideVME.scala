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

import chisel3._
import chisel3.util._
import vta.util.config._
import vta.shell._


/** TensorLoad.
 *
 * Load Cachelines from main memory (DRAM) into SRAM
 * Mux Cachelines to tensor size memory blocks in
 * scratchpads (SRAM). Also, there is support for zero padding, while
 * doing the load. Zero-padding works on the y and x axis, and it is
 * managed by ZeroPadding.
 * Read tensors from SRAM.

 * banks number (BN) = CachLineSize (CS) / Tensor bit size (TS)
 * the number of banks is pow of 2
 * Scratchpad: Seq(BN) {Mem(TensorsNb/BN, TS)}
 * Cacheline: Vec(BN,CS/BN)

 * Load:
 *          Scratchpad
 *       bank1      bank2
 *         |          |
 *        ---        ---
 * wmask-/   \     -/   \
 *       -----      -----
 *        | |        | |
 *  c     | |        | |
 *  a  -----|--------  |
 *  c       |          |
 *  h       |          |
 *  e       |          |
 *  l       |          |
 *  i ------------------
 *  n
 *  e




 */
class TensorLoadWideVME(tensorType: String = "none", debug: Boolean = false)(
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
  // the delay cycles of write pipe. Needed to deliver singal over physical distance
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

  val readVMEDataLatency = tp.readVMEDataLatency
  val vmeDataBitsPipe = ShiftRegister(io.vme_rd.data.bits, readVMEDataLatency, en = true.B)
  val vmeDataValidPipe = ShiftRegister(io.vme_rd.data.valid, readVMEDataLatency, resetData = false.B, en = true.B)
  val vmeDataReadyPipe = ShiftRegister(io.vme_rd.data.ready, readVMEDataLatency, resetData = true.B, en = true.B)
  val vmeDataFirePipe = vmeDataValidPipe & vmeDataReadyPipe

  //--------------------------------------
  //--- Generate data load VME command ---
  //--------------------------------------
  val vmeCmd = Module (new GenVMECmdWideTL(tensorType, debug))
  vmeCmd.io.start := io.start
  vmeCmd.io.isBusy := isBusy
  vmeCmd.io.inst := io.inst
  vmeCmd.io.baddr := io.baddr
  vmeCmd.io.vmeCmd <> io.vme_rd.cmd
  val readLen = vmeCmd.io.readLen
  val commandsDone = vmeCmd.io.done

  require (mp.dataBits >= tp.tensorSizeBits,
    "-F- Chacheline width must be larger than tensor bit size")
  require(pow(2, log2Ceil(mp.dataBits)) == mp.dataBits,
    "-F- Chacheline width must be pow of 2")
  require(pow(2, log2Ceil(tp.tensorSizeBits)) == tp.tensorSizeBits,
    "-F- Tensor size bits must be pow of 2")

  // me mux puts tensors in a single memory line of Cacheline (CL) bits
  val tensorsInClNb = tp.clSizeRatio
  val tensorsInClNbWidth = log2Ceil(tensorsInClNb)

  //--------------------------------------
  //--- count how many CLs not receved ---
  //--------------------------------------

  // the address size of scratchpad memory
  val clCntIdxWdth = log2Ceil(tp.memDepth/tensorsInClNb) + 1
  // Nb of CLs requestd, not received.
  val clInFlight = Reg(UInt(clCntIdxWdth.W))
  when(io.start) {
    clInFlight := 0.U
  }.elsewhen(isBusy && io.vme_rd.cmd.fire() && !vmeDataFirePipe) {
    clInFlight := clInFlight + readLen
  }.elsewhen(isBusy && io.vme_rd.cmd.fire() && vmeDataFirePipe) {
    clInFlight := clInFlight + readLen - 1.U
  }.elsewhen(isBusy && !io.vme_rd.cmd.fire() && vmeDataFirePipe) {
    assert(clInFlight > 0.U)
    clInFlight := clInFlight - 1.U
  }.otherwise {
    clInFlight := clInFlight
  }

  //---------------------
  //--- Read VME data ---
  //---------------------

  val readData = Module(new ReadVMEDataWide(tensorType, debug))
  readData.io.start := io.start
  readData.io.vmeData.valid := vmeDataValidPipe
  readData.io.vmeData.bits := vmeDataBitsPipe
  assert(!readData.io.vmeData.valid || readData.io.vmeData.ready,
    "-F- Expecting const ready. Fix ReadVMEData to receive data piped after ready")
  io.vme_rd.data.ready := readData.io.vmeData.ready
  // write mask defined number of elems strating with offset in SRAM line
  val rdDataWrIdx  = readData.io.destIdx // SP index vector
  val rdDataWrData = readData.io.destData // SP data vector
  val rdDataWrEn   = readData.io.destMask // write enable vector

  //-------------------------
  //--- Fill zero padding ---
  //-------------------------

  val fillPadding = Module(new ZeroPadding(tensorType, debug))
  fillPadding.io.canWriteMem := !vmeDataFirePipe
  fillPadding.io.inst := io.inst
  fillPadding.io.start := io.start

  val isZeroPadWrite = fillPadding.io.tensorIdx.valid // Store zero filled tensor, zpDestIdx is valid
  val zpDestIdx = fillPadding.io.tensorIdx.bits >>  tensorsInClNbWidth // SP idx
  val zpDestMask =
    if (tensorsInClNb == 1) 1.U
    else  UIntToOH(fillPadding.io.tensorIdx.bits (tensorsInClNbWidth - 1, 0)) // tensor in a memory line
  val paddingDone = fillPadding.io.done

  //--------------------
  //--- Write memory ---
  //--------------------

  // depth is reduced by dataBlock/tensorSize ratio
  // width is dataBlock bits split into tensor bits
  // each tensor is split into group bits
  // group bits can be read/written independently


  val splitDataFactor = tp.splitWidth * tp.splitLength
  val splitMemFactor = tp.splitMemsFactor
  val groupSizeBits = tp.tensorSizeBits/splitDataFactor
  val memSizeBits = groupSizeBits/splitMemFactor
  val tensorFile = Seq.fill(tensorsInClNb * splitDataFactor*splitMemFactor) {
    SyncReadMem(tp.memDepth/tensorsInClNb, UInt(memSizeBits.W))
  }

  // direct write
  val directWrIdx = for (grpIdx <- 0 until splitDataFactor) yield {
    io.tensor.wr(grpIdx).bits.idx >> tensorsInClNbWidth // SP idx
  }
  val directWrMask = for (grpIdx <- 0 until splitDataFactor) yield {
    Mux(
      io.tensor.wr(grpIdx).valid,
      if(tensorsInClNb == 1) 1.U
      else UIntToOH(io.tensor.wr(grpIdx).bits.idx(tensorsInClNbWidth - 1, 0)),// tensor in a memory line
      0.U)
  }

  // THIS directWrData writes continous scratchpad data space
  // It is WRONG for ACC is batch is > 1
  // maps group data bits to continous sequence of mem blocks
  // but wr(x).bits.data is a window in a tensor
  val directWrData = VecInit(for (grpIdx <- 0 until splitDataFactor) yield {
    io.tensor.wr(grpIdx).bits.data
  }).asTypeOf(UInt(tp.tensorSizeBits.W))


  val wmask = Wire(Vec(tensorsInClNb*splitDataFactor*splitMemFactor, Bool()))
  for (i <- 0 until tensorsInClNb) {
    for (grpIdx <- 0 until splitDataFactor) {
      for (memIdx <- 0 until splitMemFactor) { // duplicate control
        wmask(i*splitDataFactor*splitMemFactor + grpIdx * splitMemFactor + memIdx) :=
          Mux(
            ShiftRegister(state === sIdle, writePipeLatency, resetData = true.B, en = true.B),
            directWrMask(grpIdx)(i),
            Mux(
              ShiftRegister(isZeroPadWrite, writePipeLatency, resetData = false.B, en = true.B),
              ShiftRegister(zpDestMask(i), writePipeLatency),
              Mux(
                ShiftRegister(vmeDataFirePipe, writePipeLatency, resetData = false.B, en = true.B),
                ShiftRegister(rdDataWrEn(i), writePipeLatency),
                false.B)))
      }
    }
  }

  val wdata = Wire(Vec(tensorsInClNb*splitDataFactor, UInt(groupSizeBits.W)))
  for (i <- 0 until tensorsInClNb){
    for (grpIdx <- 0 until splitDataFactor) {
      val zpDestData = 0.U
      wdata(i*splitDataFactor + grpIdx) := Mux(
        ShiftRegister(state === sIdle, writePipeLatency, resetData = true.B, en = true.B),
        io.tensor.wr(grpIdx).bits.data.asTypeOf(UInt(groupSizeBits.W)),
        Mux(
          ShiftRegister(isZeroPadWrite, writePipeLatency, resetData = false.B, en = true.B),
          ShiftRegister(zpDestData /* group size zero */, writePipeLatency),
          ShiftRegister(
            (rdDataWrData(i).asTypeOf(Vec(splitDataFactor, UInt(groupSizeBits.W))))(grpIdx), writePipeLatency)))
    }
  }

  val widx = Wire(Vec(tensorsInClNb*splitDataFactor*splitMemFactor, UInt(tp.memAddrBits.W)))
  for (i <- 0 until tensorsInClNb) {
    for (grpIdx <- 0 until splitDataFactor) {
      for (memIdx <- 0 until splitMemFactor) { // duplicate control
        widx(i*splitDataFactor*splitMemFactor + grpIdx * splitMemFactor + memIdx) :=
          Mux(
            ShiftRegister(state === sIdle, writePipeLatency, resetData = true.B, en = true.B),
            directWrIdx(grpIdx),
            Mux(
              ShiftRegister(isZeroPadWrite, writePipeLatency, resetData = false.B, en = true.B),
              ShiftRegister(zpDestIdx, writePipeLatency),
              ShiftRegister(rdDataWrIdx(i), writePipeLatency)))
      }
    }
  }

  for (i <- 0 until tensorsInClNb) {
    for (grpIdx <- 0 until splitDataFactor) {
      for (memIdx <- 0 until splitMemFactor) { // duplicate control
        when(wmask(i*splitDataFactor*splitMemFactor + grpIdx * splitMemFactor + memIdx)) {
          tensorFile(i*splitDataFactor*splitMemFactor + grpIdx * splitMemFactor + memIdx).write(
            widx(i*splitDataFactor*splitMemFactor + grpIdx * splitMemFactor + memIdx),
            wdata(i*splitDataFactor + grpIdx).asTypeOf(
              Vec(splitMemFactor, UInt(memSizeBits.W)))(memIdx))
        }
      }
    }
  }
  if (debug) {
    when(isZeroPadWrite) {
      printf(s"[TensorLoad] $tensorType isZeroPadWrite data zpDestIdx:%d\n",
        zpDestIdx)
    }
  }

  // read-from-sram
  for (grpIdx <- 0 until splitDataFactor) {
    val rIdx = io.tensor.rd(grpIdx).idx.bits >> tensorsInClNbWidth // SP idx
    val rMask =
      Mux(
        io.tensor.rd(grpIdx).idx.valid,
        if(tensorsInClNb == 1) 1.U
        else UIntToOH(io.tensor.rd(grpIdx).idx.bits(tensorsInClNbWidth - 1, 0)),// tensor in a memory line
        0.U)

    val rdataVec =   for (i <- 0 until tensorsInClNb) yield {
      VecInit(for (memIdx <- 0 until splitMemFactor) yield {
        tensorFile(
          i*splitDataFactor*splitMemFactor + grpIdx * splitMemFactor + memIdx).read(
            ShiftRegister(rIdx, tp.readTensorLatency),
            ShiftRegister(VecInit(rMask.asBools)(i), tp.readTensorLatency, resetData = false.B, en = true.B))
      }).asUInt
    }

    val rdata = Wire(UInt(tp.tensorSizeBits.W))
    rdata := Mux1H(ShiftRegister(rMask, tp.readTensorLatency + 1), rdataVec)
    io.tensor.rd(grpIdx).data.bits := rdata.asTypeOf(io.tensor.rd(grpIdx).data.bits.cloneType)

    val rvalid = ShiftRegister(
      io.tensor.rd(grpIdx).idx.valid, tp.readTensorLatency + 1, resetData = false.B, en = true.B)
    io.tensor.rd(grpIdx).data.valid := rvalid
  }

  // done
  val loadDone = clInFlight === 0.U && commandsDone && state === sBusy
  localDone := loadDone && paddingDone
  io.done := ShiftRegister(localDone, writePipeLatency, resetData = false.B, en = true.B)
}

//---------------------
//--- Read VME data ---
//---------------------
//----------------------------------------------------------------------------
// Read VME data. Generate Memory index and data
// transaction TAG is a data block offset in scratchpad
// Different transactions are identified by atag change
// SAME DESTINATION SUBSEQUENT REQUESTS IN ONE INSTRUCTION LEADS TO UNDEFINED BEHAVIOR
//----------------------------------------------------------------------------
class ReadVMEDataWide(tensorType: String = "none", debug: Boolean = false)(
    implicit p: Parameters)
    extends Module {
  val tp = new TensorParams(tensorType)
  val mp = p(ShellKey).memParams
  val wmaskWidth = mp.dataBits/tp.tensorSizeBits
  val io = IO(new Bundle {
    val start = Input(Bool())
    val vmeData = Flipped(Decoupled(new VMEData))

    val destIdx  = Output(Vec(tp.clSizeRatio, UInt(tp.memAddrBits.W)))
    val destData = Output(Vec(tp.clSizeRatio, UInt(tp.tensorSizeBits.W)))
    val destMask = Output(Vec(tp.clSizeRatio, Bool()))
  })

  io.vmeData.ready := true.B // always ready to read VME data

  require(pow(2, log2Ceil(tp.tensorLength)) == tp.tensorLength,
    "-F- Tensor length must be 2^. Using shift and bits to divide.")
  val blkIdxWdth = log2Ceil(tp.memDepth) // the size of scratchpad in cache lines

  //decode data destination
  val vmeTagDecode = io.vmeData.bits.tag
  val vmeTagDecodeLast = Reg(vmeTagDecode.cloneType) // store tag to identify a new burst
  val clBytes = mp.dataBits / 8 // cacheline bytes
  val elemBytes = tp.tensorLength * tp.tensorWidth * tp.tensorElemBits / 8 // bytes in tensor
  val rdDataMaskDecodeWidth = if (wmaskWidth == 1) 1 else (log2Ceil(wmaskWidth) + 1)
  val rdDataElemIdx = vmeTagDecode(vmeTagDecode.getWidth - 1, 2 * rdDataMaskDecodeWidth)
  val rdFstOffsetNb = if (rdDataMaskDecodeWidth == 0) {
    0.U
  } else {
    val readOffset  = vmeTagDecode(2 * rdDataMaskDecodeWidth - 1, rdDataMaskDecodeWidth)
    readOffset
  }
  val rdLstNb = if (rdDataMaskDecodeWidth == 0) {
    1.U
  } else {
    val readNb  = vmeTagDecode(rdDataMaskDecodeWidth - 1, 0)
    assert(!io.vmeData.valid || readNb > 0.U,"-F- Expecting some elements to read")
    readNb
  }
  val wrMask1st = if (rdDataMaskDecodeWidth == 0) {
    1.U
  } else {
    Reverse(VecInit(for(idx <- 0 until wmaskWidth) yield {
      idx.U < tp.clSizeRatio.U - rdFstOffsetNb
    }).asUInt)
  }
  val wrMaskLast = if (rdDataMaskDecodeWidth == 0) {
    1.U
  } else {
    VecInit(for(idx <- 0 until wmaskWidth) yield {
      idx.U < rdLstNb
    }).asUInt
  }
  val rdDataElemDestIdx = Wire(UInt(tp.memAddrBits.W)) // this is an idx  of a tensor
  val rdDataElemDestIdxNext = Reg(UInt(tp.memAddrBits.W))
  val rdDataClDestIdx = rdDataElemDestIdx >> log2Ceil(tp.clSizeRatio)
  val rdDataDestElemOffset = rdDataElemDestIdx % tp.clSizeRatio.U

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

  val isFirstPulse = Wire(Bool())
  val isLastPulse = io.vmeData.bits.last
  val wmaskSel =
    Mux(
      isFirstPulse && isLastPulse,
      wrMask1st & wrMaskLast,
      Mux(
        isFirstPulse,
        wrMask1st,
        Mux(
          isLastPulse,
          wrMaskLast,
          ((1 << wmaskWidth) - 1).U)))
  val wmask = Mux(io.vmeData.fire(), wmaskSel, 0.U)
  rdDataElemDestIdx := DontCare
  isFirstPulse := false.B
  when(io.vmeData.fire()) {
    when (
      !vmeTagDecodeLastValidNext ||
      (vmeTagDecodeLastValidNext &&
        vmeTagDecode.asUInt =/= vmeTagDecodeLast.asUInt)) {

      vmeTagDecodeLast := vmeTagDecode // a new burst
      isFirstPulse := true.B
      rdDataElemDestIdx := rdDataElemIdx
      // dont incrememt first partial read pulse
      rdDataElemDestIdxNext := rdDataElemIdx + PopCount(wmask)
    }.otherwise {
      rdDataElemDestIdxNext := rdDataElemDestIdxNext + PopCount(wmask)
      rdDataElemDestIdx := rdDataElemDestIdxNext
    }
  }


  val srcData  = io.vmeData.bits.data.asTypeOf(Vec(tp.clSizeRatio, UInt(tp.tensorSizeBits.W)))
  val srcOffset = Wire(Vec(tp.clSizeRatio, UInt((log2Ceil(tp.clSizeRatio) + 1).W)))
  val srcIdx = Wire(Vec(tp.clSizeRatio, UInt(log2Ceil(tp.clSizeRatio).W)))

  // D(j+d) = S(j+s)  replace i=j+d --> D(i) = S(i-d+s)
  for (i <- 0 until tp.clSizeRatio) {
    srcOffset(i) := i.U + Mux(isFirstPulse, rdFstOffsetNb, 0.U)
    srcIdx(i) := srcOffset(i) -% rdDataDestElemOffset
    val srcIdxOH = UIntToOH(srcIdx(i))
    io.destData(i) := Mux1H(srcIdxOH,srcData)
    io.destMask(i) := Mux1H(srcIdxOH, wmask)

    //if dest offset overflow, incr that dest idx
    val incrIdx = if (tp.clSizeRatio == 1 ) {
      0.U
    } else {
      Mux(srcOffset(i) >= rdDataDestElemOffset, 0.U, 1.U)
    }
    io.destIdx(i) := rdDataClDestIdx + incrIdx


  }


}

// transaction TAG is a data block offset in scratchpad
// Different transactions are identified by atag change
// SAME DESTINATION SUBSEQUENT REQUESTS IN ONE INSTRUCTION LEADS TO UNDEFINED BEHAVIOR
class GenVMECmdWide(tensorType: String = "none", debug: Boolean = false)(
    implicit p: Parameters)
    extends Module {
  val tp = new TensorParams(tensorType)
  val mp = p(ShellKey).memParams
  val io = IO(new Bundle {
    val start = Input(Bool())
    val isBusy = Input(Bool())
    val updateState = Input(Bool())
    val canSendCmd = Input(Bool())
    val baddr = Input(UInt(mp.addrBits.W))
    val vmeCmd = Decoupled(new VMECmd)
    val readLen = Output(UInt((mp.lenBits + 1).W))
    val done = Output(Bool())
    val fstPulseDataStart = Output(UInt((log2Ceil(tp.clSizeRatio) + 1).W))
    val lstPulseDataEnd = Output(UInt((log2Ceil(tp.clSizeRatio) + 1).W))
    val spElemIdx = Output(UInt(tp.memAddrBits.W))

    val ysize = Input(UInt(M_SIZE_BITS.W))
    val xsize = Input(UInt(M_SIZE_BITS.W))
    val xstride = Input(UInt(M_STRIDE_BITS.W))
    val dram_offset = Input(UInt(M_DRAM_OFFSET_BITS.W))
    val sram_offset = Input(UInt(M_SRAM_OFFSET_BITS.W))
    val xpad_0 = Input(UInt(M_PAD_BITS.W))
    val xpad_1 = Input(UInt(M_PAD_BITS.W))
    val ypad_0 = Input(UInt(M_PAD_BITS.W))
  })

  val clBytes = mp.dataBits / 8 // cacheline bytes
  val elemBytes = tp.tensorLength * tp.tensorWidth * tp.tensorElemBits / 8 // bytes in tensor
  val stride = Wire(Bool()) // flags change to the next row to read

  //----------------------------------------
  //--- Count lines of DRAM memory lines ---
  //----------------------------------------

  // set which source row of data to read. io.ysize defines the number of rows
  val dramLineIdx = Reg(UInt(io.ysize.getWidth.W)) // current row of stride read
  when (io.start) {
    dramLineIdx := 0.U // 1st row
  }.elsewhen (stride) {
    dramLineIdx := dramLineIdx + 1.U // increment row
  }.otherwise {
    dramLineIdx := dramLineIdx // stay in the row
  }

  // calculate address of DRAM memory line begin (initial/stride)
  val maskOffset = VecInit(Seq.fill(M_DRAM_OFFSET_BITS)(true.B)).asUInt
  val dramInitialAddr = (io.dram_offset << log2Ceil(elemBytes)).asTypeOf(UInt(mp.addrBits.W))
  val xferElemInitAddr = io.baddr | dramInitialAddr // SHOULD have + here?
  //aling address to CL size
  // lower bits - elem offset in a cachline
  val dramClAddrAlignNotMask = ((BigInt(1) << log2Ceil(clBytes)) - 1).U.asTypeOf(xferElemInitAddr)
  // upper bits - cacheline alinement
  val dramClAddrAlignMask = ~dramClAddrAlignNotMask
  val xferClInitAddr = xferElemInitAddr & dramClAddrAlignMask
  val rdLineElemBeginAddr = Reg(UInt(mp.addrBits.W)) // DRAM address of xsize tensors memory line
  val rdLineClBeginAddr = rdLineElemBeginAddr & dramClAddrAlignMask
  // begin of the next DRAM memory line
  val nextLineBeginElemAddr = rdLineElemBeginAddr + (io.xstride << log2Ceil(elemBytes))
  val nextLineBeginClAddr = nextLineBeginElemAddr & dramClAddrAlignMask
  when (io.start) {
    rdLineElemBeginAddr := xferElemInitAddr
  }.elsewhen (stride) {
    rdLineElemBeginAddr := nextLineBeginElemAddr
  }.otherwise {
    rdLineElemBeginAddr := rdLineElemBeginAddr
  }

  //-----------------------------------------------------
  //--- Calculate current DRAM address of transaction ---
  //-----------------------------------------------------

  val rdLen = Wire(UInt((mp.lenBits + 1).W)) // read cmd transaction length. It is <= maxTransfer
  val rdLineAddr = Reg(UInt(mp.addrBits.W)) // current DRAM address of command
  when (io.start) {
    rdLineAddr := xferClInitAddr
  }.elsewhen (io.updateState) {
    when(stride) {
      rdLineAddr := nextLineBeginClAddr
    }.otherwise {
      rdLineAddr := rdLineAddr + (rdLen << log2Ceil(clBytes))
    }
  }.otherwise {
    rdLineAddr := rdLineAddr
  }

  //total load length in cachelines
  val rdLineBytes = io.xsize << log2Ceil(elemBytes)

  //First transaction in a line length (1st or stride)
  val maxTransfer = (1 << mp.lenBits).U // max number of pulses in transfer
  val maxTrBytes = maxTransfer << log2Ceil(clBytes)
  val rdLen1stMaxTransBytes = maxTrBytes - rdLineClBeginAddr % maxTrBytes
  // get the number of cachelines till maxTrBytes aligned address
  val rdLen1stMaxTransClNb = rdLen1stMaxTransBytes >> log2Ceil(clBytes)

  //Transaction begin mask. Number of tensors to read from right
  val rd1stPulseOffsetBytes = rdLineElemBeginAddr % clBytes.U
  assert(rd1stPulseOffsetBytes >> log2Ceil(elemBytes) <= tp.clSizeRatio.U,
    "-F- Expecting the number of tensors to skip in CL")
  val rd1stPulseOffsetTensNb =  Wire(UInt((log2Ceil(tp.clSizeRatio) + 1).W))
  rd1stPulseOffsetTensNb := rd1stPulseOffsetBytes >> log2Ceil(elemBytes)

  val rdLineClNbTmp = (rdLineBytes + rd1stPulseOffsetBytes) >> log2Ceil(clBytes)
  val rdLineClNb =
    Mux((rdLineBytes + rd1stPulseOffsetBytes) % clBytes.U === 0.U, rdLineClNbTmp, rdLineClNbTmp + 1.U)

  //Transaction end mask. Number of tensors to read from left
  val rdLastPulseBytes =  (rdLineElemBeginAddr + rdLineBytes) % clBytes.U
  assert(rdLastPulseBytes >> log2Ceil(elemBytes) <= (clBytes/elemBytes).U,
    "-F- Expecting the number of active tensors in CL")
  val rdLastPulseTensNb =  Wire(UInt((log2Ceil(clBytes/elemBytes) + 1).W))
  val rdLastPulseTensNbTmp =  rdLastPulseBytes >> log2Ceil(elemBytes)
  rdLastPulseTensNb :=  Mux(rdLastPulseTensNbTmp === 0.U, (clBytes/elemBytes).U, rdLastPulseTensNbTmp)



  //--------------------------------------
  //--- Generate data load VME command ---
  //--------------------------------------

  val rdCmdStartIdxValid = Wire(Bool()) // Command is valid
  val startIssueCmdRead = Wire(Bool()) // First transaction in io.xsize transfer
  val rdCmdStartIdx = Reg(UInt(log2Ceil(tp.memDepth).W)) // Scratchpad data block index for the first transaction
  val commandsDone = RegInit(true.B) // Done generating VME commands
  // counts the number of CLs read in a xsize line
  val clReadIdx = Reg(UInt((io.xsize.getWidth + log2Ceil(elemBytes) - log2Ceil(clBytes)).W))
  val newReadRow = clReadIdx === 0.U // flags the first read of io.xsize

  // set how many blocks of data being loaded
  commandsDone := commandsDone
  when (io.start || stride) {
    clReadIdx := 0.U
    commandsDone := false.B
  }.elsewhen (io.updateState) {
    val nextClIdx = clReadIdx + rdLen
    clReadIdx := nextClIdx // THIS IS WHEN A NEW VME CMD HAPPENS
    when (nextClIdx === rdLineClNb && dramLineIdx === io.ysize - 1.U) {
      commandsDone := true.B
    }
  }.otherwise {
    clReadIdx := clReadIdx
  }

  //when the whole xsize row read commands are sent, go for the next src row
  when((clReadIdx === rdLineClNb - rdLen) && (dramLineIdx =/= io.ysize - 1.U) && io.updateState) {
    stride := true.B
  }.otherwise {
    stride := false.B
  }

  // current transaction tensors to read nb in 1st and last pulses
  val rdCmd1stPluseOffsetTensNb = Wire(rd1stPulseOffsetTensNb.cloneType)
  val rdCmdLastPluseTensNb = Wire(rdLastPulseTensNb.cloneType)
  when(newReadRow) {
    // first read in line
    rdCmd1stPluseOffsetTensNb := rd1stPulseOffsetTensNb
  }.otherwise {
    // any other read
    rdCmd1stPluseOffsetTensNb := 0.U
  }
  when (clReadIdx === rdLineClNb - rdLen) {
    // last read in line
    rdCmdLastPluseTensNb := rdLastPulseTensNb
  }.otherwise {
    // any other read
    rdCmdLastPluseTensNb := (clBytes/elemBytes).U
  }

  //when the whole xsize row read commands are sent, go for the next src row
  when((clReadIdx === rdLineClNb - rdLen) && (dramLineIdx =/= io.ysize - 1.U) && io.updateState) {
    stride := true.B
  }.otherwise {
    stride := false.B
  }

  assert(!io.isBusy || rdLineClNb >= clReadIdx)// define how many cachelines to read at this cycle
  val clRemained = rdLineClNb - clReadIdx
  when (newReadRow) {
    when(clRemained < rdLen1stMaxTransClNb) {
      rdLen := clRemained
    }.otherwise {
      rdLen := rdLen1stMaxTransClNb
    }
  }.otherwise {
    when(clRemained < maxTransfer) {
      rdLen := clRemained
    }.otherwise {
      rdLen := maxTransfer
    }
  }
  // block index of the read data row (xsize). Modified by zero padding
  val totalWidth = io.xsize + io.xpad_0 + io.xpad_1 // width of scratchpad matrix in tensors
  // instead of multiplying total width by ypad_0 do incremental addition.
  //Should cost ypad_0 cycles to issue 1st read cmd
  // counts src matrix with y padding rows of tensors
  val currentRowIdx = Reg(UInt((io.ysize.getWidth + io.ypad_0.getWidth).W))
  // start to issue read cmd
  rdCmdStartIdxValid := currentRowIdx >= io.ypad_0 &&
    currentRowIdx < (io.ysize + io.ypad_0) &&
    io.isBusy &&
    !commandsDone
  when (io.start) {
    currentRowIdx := 0.U
    rdCmdStartIdx := io.sram_offset + io.xpad_0 // this index is in tensors
  }.elsewhen (io.isBusy && (currentRowIdx < io.ypad_0 || stride)) {
    rdCmdStartIdx := rdCmdStartIdx + totalWidth
    currentRowIdx := currentRowIdx + 1.U
  }
  startIssueCmdRead := false.B
  when(newReadRow && rdCmdStartIdxValid) {
    startIssueCmdRead := true.B
  }

  //-------------------------------------
  //--- execute VME data load command ---
  //-------------------------------------

  require(pow(2, log2Ceil(tp.tensorLength)) == tp.tensorLength,
    "-F- Tensor length must be 2^. Using shift and bits to divide.")
  val blkIdxWdth = log2Ceil(tp.memDepth) // the size of scratchpad

  val rdCmdDestElemIdx = Wire(UInt(tp.memAddrBits.W)) // element(tensor) size block index in a scratchpad
  val rdCmdDestElemIdxNext = Reg(rdCmdDestElemIdx.cloneType)
  rdCmdDestElemIdxNext := rdCmdDestElemIdxNext
  rdCmdDestElemIdx := rdCmdDestElemIdxNext

  val rdCmdValid = Wire(Bool())
  // the number of tensors read in transaction
  val rdCmdTransactionTensNb = (rdLen << log2Ceil(clBytes/elemBytes)) - rdCmd1stPluseOffsetTensNb
  //increment scratch pad destination index
  when(rdCmdStartIdxValid) {
    rdCmdValid := true.B
    when(startIssueCmdRead) {
      rdCmdDestElemIdx := rdCmdStartIdx
      rdCmdDestElemIdxNext:= rdCmdStartIdx + rdCmdTransactionTensNb
    }.elsewhen (io.updateState) {
      // increment block position by transaction length
      rdCmdDestElemIdxNext:= rdCmdDestElemIdxNext + rdCmdTransactionTensNb
    }
  }.otherwise {
    rdCmdValid := false.B
  }

  // read-from-dram
  require(io.vmeCmd.bits.tag.getWidth >= rdCmdDestElemIdx.getWidth +
    rdCmdLastPluseTensNb.getWidth + rdCmd1stPluseOffsetTensNb.getWidth,
    s"-F- Tensor ${tensorType} Not enough VME tag bits to store transaction" +
    s" tag. need:${rdCmdDestElemIdx.getWidth + rdCmdLastPluseTensNb.getWidth + rdCmd1stPluseOffsetTensNb.getWidth}")
  io.vmeCmd.valid := rdCmdValid && io.canSendCmd
  io.vmeCmd.bits.addr := rdLineAddr
  io.vmeCmd.bits.len := rdLen - 1.U
  assert(!io.vmeCmd.valid || ((rdLen << log2Ceil(clBytes)) <= maxTrBytes - rdLineAddr % maxTrBytes),
    s"-F- ${tensorType} DRAM page alignment failure. DRAM " +
    s"address + len overlaps mp.lenBits*memBlockSize alignment %x %x",
    rdLineAddr, rdLen)
  io.vmeCmd.bits.tag := Cat(rdCmdDestElemIdx, Cat(rdCmd1stPluseOffsetTensNb, rdCmdLastPluseTensNb))
  io.readLen := rdLen
  io.spElemIdx := rdCmdDestElemIdx // scratchpad tensor idx
  io.fstPulseDataStart := rdCmd1stPluseOffsetTensNb // first pulse data start
  io.lstPulseDataEnd := rdCmdLastPluseTensNb // last pulse data end
  io.done := commandsDone
}

class GenVMECmdWideTL(tensorType: String = "none", debug: Boolean = false)(
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

  val dec = io.inst.asTypeOf(new MemDecode)

  val cmdGen = Module (new GenVMECmdWide(tensorType, debug))

  cmdGen.io.start := io.start
  cmdGen.io.isBusy := io.isBusy
  cmdGen.io.baddr := io.baddr
  io.vmeCmd <> cmdGen.io.vmeCmd
  io.readLen :=  cmdGen.io.readLen
  io.done :=  cmdGen.io.done

  cmdGen.io.ysize := dec.ysize
  cmdGen.io.xsize := dec.xsize
  cmdGen.io.xstride := dec.xstride
  cmdGen.io.dram_offset := dec.dram_offset
  cmdGen.io.sram_offset := dec.sram_offset
  cmdGen.io.xpad_0 := dec.xpad_0
  cmdGen.io.xpad_1 := dec.xpad_1
  cmdGen.io.ypad_0 := dec.ypad_0
  cmdGen.io.updateState := io.vmeCmd.fire()
  cmdGen.io.canSendCmd := true.B
}
