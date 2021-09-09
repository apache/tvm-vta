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

/** TensorStore.
 *
 * Store 1D and 2D tensors from out-scratchpad (SRAM) to main memory (DRAM).
 */
class TensorStoreWideVME(tensorType: String = "none", debug: Boolean = false)(
    implicit p: Parameters)
    extends Module {
  val tp = new TensorParams(tensorType)
  val mp = p(ShellKey).memParams
  val io = IO(new Bundle {
    val start = Input(Bool())
    val done = Output(Bool())
    val inst = Input(UInt(INST_BITS.W))
    val baddr = Input(UInt(mp.addrBits.W))
    val vme_wr = new VMEWriteMaster
    val tensor = new TensorClient(tensorType)
  })
  val writePipeLatency = tp.writePipeLatency
  // Store write is delayed by writePipeLatency
  // postpone start by the same number of cycles
  // expects instr and baddr are valid from start till done
  val localStart = ShiftRegister(io.start, writePipeLatency, resetData = false.B, en = true.B)

  val dec = io.inst.asTypeOf(new MemDecode)

  val sIdle :: sWriteCmd :: sWriteData :: sWriteAck :: Nil = Enum(4)
  val state = RegInit(sIdle)

  val cmdGen = Module (new GenVMECmdWide(tensorType, debug))

  cmdGen.io.ysize := dec.ysize
  cmdGen.io.xsize := dec.xsize
  cmdGen.io.xstride := dec.xstride
  cmdGen.io.dram_offset := dec.dram_offset
  cmdGen.io.sram_offset := dec.sram_offset
  cmdGen.io.xpad_0 := 0.U
  cmdGen.io.xpad_1 := 0.U
  cmdGen.io.ypad_0 := 0.U

  cmdGen.io.start := localStart
  cmdGen.io.isBusy := state =/= sIdle
  cmdGen.io.baddr := io.baddr
  cmdGen.io.updateState := state === sWriteCmd
  cmdGen.io.canSendCmd := cmdGen.io.updateState
  io.vme_wr.cmd <> cmdGen.io.vmeCmd
  val commandsDone =  cmdGen.io.done

  // latch cmd parameters
  val readLenReg = Reg(cmdGen.io.readLen.cloneType)
  val readLen = Wire(readLenReg.cloneType)
  val fstPulseDataStartReg = Reg(cmdGen.io.fstPulseDataStart.cloneType)
  val fstPulseDataStart = Wire(fstPulseDataStartReg.cloneType)
  val lstPulseDataEndReg = Reg(cmdGen.io.lstPulseDataEnd.cloneType)
  val lstPulseDataEnd = Wire(lstPulseDataEndReg.cloneType)
  val spElemIdxReg = Reg(cmdGen.io.spElemIdx.cloneType)
  val spElemIdx = Wire(spElemIdxReg.cloneType)
  when (cmdGen.io.updateState) {
    readLen    := cmdGen.io.readLen
    readLenReg := readLen
    fstPulseDataStart    := cmdGen.io.fstPulseDataStart
    fstPulseDataStartReg := fstPulseDataStart
    lstPulseDataEnd    := cmdGen.io.lstPulseDataEnd
    lstPulseDataEndReg := lstPulseDataEnd
    spElemIdx    := cmdGen.io.spElemIdx
    spElemIdxReg := spElemIdx
  }.otherwise {
    readLenReg := readLenReg
    readLen    := readLenReg
    fstPulseDataStartReg := fstPulseDataStartReg
    fstPulseDataStart    := fstPulseDataStartReg
    lstPulseDataEndReg := lstPulseDataEndReg
    lstPulseDataEnd    := lstPulseDataEndReg
    spElemIdxReg := spElemIdxReg
    spElemIdx    := spElemIdxReg
  }

  val xcnt = Reg(chiselTypeOf(io.vme_wr.cmd.bits.len))
  xcnt := xcnt
  // control
  val updateState = Wire(Bool())
  updateState := false.B
  switch(state) {
    is(sIdle) {
      when (localStart) {
        state := sWriteCmd
      }
    }
    is(sWriteCmd) {
      when(io.vme_wr.cmd.fire()) {
        state := sWriteData
        updateState := true.B
        xcnt := 0.U
      }
    }
    is(sWriteData) {
      when(io.vme_wr.data.fire()) {
        when(xcnt === readLen - 1.U) {
          state := sWriteAck
        }.otherwise {
          xcnt := xcnt + 1.U
        }
      }
    }
    is(sWriteAck) {
      when(io.vme_wr.ack) {
        when(commandsDone) {
          state := sIdle
        }.otherwise { // stride
          state := sWriteCmd
        }
      }
    }
  }


  //--------------------
  //--- Write memory ---
  //--------------------

  val splitDataFactor = tp.splitWidth * tp.splitLength
  val groupSizeBits = tp.tensorSizeBits/splitDataFactor
  val tensorFile = Seq.fill(tp.clSizeRatio * splitDataFactor) {
    SyncReadMem(tp.memDepth/tp.clSizeRatio, UInt(groupSizeBits.W))
  }

  // direct write
  for (grpIdx <- 0 until splitDataFactor) {
    val directWrIdx = io.tensor.wr(grpIdx).bits.idx >> log2Ceil(tp.clSizeRatio) // SP idx
    val directWrTensorIdx =
      if(tp.clSizeRatio == 1) 0.U
      else io.tensor.wr(grpIdx).bits.idx(log2Ceil(tp.clSizeRatio) - 1, 0)
    for (i <- 0 until tp.clSizeRatio) {
      when(ShiftRegister(io.tensor.wr(grpIdx).valid && directWrTensorIdx === i.U, writePipeLatency,
        resetData = false.B, en = true.B)) {

        tensorFile(i*splitDataFactor + grpIdx).write(ShiftRegister(directWrIdx, writePipeLatency),
          ShiftRegister(io.tensor.wr(grpIdx).bits.data.asUInt, writePipeLatency))
      }
    }
  }


  //--------------------
  //--- Read memory ---
  //--------------------
  // first pulse doesnt reead whole data size, it is bounded by DRAM data alignment
  // ! - data pulse boundary
  // . - tenzor boundary
  // tz - not used
  // TZ - tensor to store
  // =TZ= - first pulse tensor
  //  DRAM !-tz-.-tz-.=TZ=!-TZ-.-TZ-.-TZ-!
  //
  //  SRAM !-tz-.=TZ=.-TZ-!-TZ-.-TZ-.-tz-!

  val isFirstPulse = io.vme_wr.data.fire() && xcnt === 0.U
  assert(state =/= sWriteData || readLen > 0.U)
  val firstPulseTenzorsNb = tp.clSizeRatio.U - fstPulseDataStart
  val isLastPulse = io.vme_wr.data.fire() && xcnt === readLen - 1.U
  val spReadAddrReg = Reg(UInt(M_SRAM_OFFSET_BITS.W))
  val spReadAddr = Wire(spReadAddrReg.cloneType)
  val srcElemOffsetReg = Reg(UInt(log2Ceil(tp.clSizeRatio).W))
  val srcElemOffset = Wire(srcElemOffsetReg.cloneType)
  val incrFstIdx = Mux((spElemIdx % tp.clSizeRatio.U) < fstPulseDataStart.asTypeOf(UInt(width = 8.W)), 0.U , 1.U)
  spReadAddr := DontCare
  when(state === sWriteCmd) {
    // init by data block index
    spReadAddr := spElemIdx >> log2Ceil(tp.clSizeRatio)
    spReadAddrReg := spReadAddr + incrFstIdx
    srcElemOffset := spElemIdx % tp.clSizeRatio.U
    srcElemOffsetReg := (spElemIdx + firstPulseTenzorsNb) % tp.clSizeRatio.U
  }.elsewhen(io.vme_wr.data.fire()) {
    spReadAddrReg := spReadAddrReg + 1.U
    spReadAddr := spReadAddrReg
    srcElemOffset := (spElemIdx + firstPulseTenzorsNb) % tp.clSizeRatio.U
    srcElemOffsetReg := srcElemOffset
  }.otherwise {
    spReadAddrReg := spReadAddrReg
    srcElemOffsetReg := srcElemOffsetReg
    srcElemOffset := srcElemOffsetReg
  }


  val dstData   = Wire(Vec(tp.clSizeRatio, UInt(tp.tensorSizeBits.W)))
  val srcData   = Wire(Vec(tp.clSizeRatio, UInt(tp.tensorSizeBits.W)))
  val srcMemIdx = Wire(Vec(tp.clSizeRatio, spReadAddr.cloneType))
  val dstOffset = Wire(Vec(tp.clSizeRatio, UInt((log2Ceil(tp.clSizeRatio) + 1).W)))
  val dstIdx    = Wire(Vec(tp.clSizeRatio, UInt(log2Ceil(tp.clSizeRatio).W)))


  // D(j+d) = S(j+s)  replace i=j+d --> D(i) = S(i-d+s)
  for (i <- 0 until tp.clSizeRatio) {

    //if src offset overflow, incr that dest idx, read next memory row
    val incrIdx = if (tp.clSizeRatio == 1 ) {
      0.U
    } else {
      Mux(i.U >= srcElemOffset, 0.U, 1.U)
    }
    srcMemIdx(i) := spReadAddr + incrIdx

    //read memory
    srcData(i) := VecInit(for (grpIdx <- 0 until splitDataFactor) yield {
      tensorFile(i*splitDataFactor + grpIdx).read(
        srcMemIdx(i),
        state === sWriteCmd | (state === sWriteData && io.vme_wr.data.fire()))
    }).asTypeOf(UInt(tp.tensorSizeBits.W))

    // crossbar src to dst
    dstOffset(i) := i.U + spElemIdx % tp.clSizeRatio.U
    dstIdx(i) := dstOffset(i)  -% fstPulseDataStart
    dstData(i) := Mux1H(UIntToOH(dstIdx(i)), srcData)

  }

  // build valid bytes strb
  val tensorSizeBytes = tp.tensorSizeBits/8
  val validBytes = Wire(Vec(tp.clSizeRatio, UInt(tensorSizeBytes.W)))
  val tensorBytesOnes = (BigInt(1) << tensorSizeBytes) - 1
  when(isFirstPulse && !isLastPulse) {
    for (i <- 0 until tp.clSizeRatio) {
      validBytes(i) := Mux(i.U < fstPulseDataStart && fstPulseDataStart =/= 0.U, 0.U, tensorBytesOnes.U)
    }
  }.elsewhen (!isFirstPulse && isLastPulse) {
    for (i <- 0 until tp.clSizeRatio) {
      validBytes(i) := Mux(i.U >= lstPulseDataEnd && lstPulseDataEnd =/= 0.U, 0.U, tensorBytesOnes.U)
    }
  }.elsewhen (isFirstPulse && isLastPulse) {
    for (i <- 0 until tp.clSizeRatio) {
      validBytes(i) := Mux((i.U < fstPulseDataStart  && fstPulseDataStart =/= 0.U)
        || (i.U >= lstPulseDataEnd && lstPulseDataEnd =/= 0.U), 0.U, tensorBytesOnes.U)
    }
  }.otherwise {
    for (i <- 0 until tp.clSizeRatio) {
      validBytes(i) := tensorBytesOnes.U
    }
  }


  io.vme_wr.data.valid := state === sWriteData
  io.vme_wr.data.bits.data := dstData.asUInt
  io.vme_wr.data.bits.strb := validBytes.asUInt


  // disable external read-from-sram requests
  io.tensor.tieoffRead()

  // done
  io.done := state === sWriteAck & commandsDone & io.vme_wr.ack

  // debug
  if (debug) {
    when(io.vme_wr.data.fire()) {
      printf("[TensorStore] data:%x\n", io.vme_wr.data.bits.data)
      printf("[TensorStore] strb:%x\n", io.vme_wr.data.bits.strb)
    }
    when(io.vme_wr.ack) {
      printf("[TensorStore] ack\n")
    }
  }
}
