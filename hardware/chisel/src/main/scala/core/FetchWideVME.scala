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

/** Fetch.
 *
 * The fetch unit reads instructions (tasks) from memory (i.e. DRAM), using the
 * VTA Memory Engine (VME), and push them into an instruction queue called
 * inst_q. Once the instruction queue is full, instructions are dispatched to
 * the Load, Compute and Store module queues based on the instruction opcode.
 * After draining the queue, the fetch unit checks if there are more instructions
 * via the ins_count register which is written by the host.
 *
 * Additionally, instructions are read into two chunks (see sReadLSB and sReadMSB)
 * because we are using a DRAM payload of 8-bytes or half of a VTA instruction.
 * This should be configurable for larger payloads, i.e. 64-bytes, which can load
 * more than one instruction at the time. Finally, the instruction queue is
 * sized (entries_q), depending on the maximum burst allowed in the memory.
 */
class FetchWideVME(debug: Boolean = false)(implicit p: Parameters) extends Module {
  val vp = p(ShellKey).vcrParams
  val mp = p(ShellKey).memParams
  val io = IO(new Bundle {
    val launch = Input(Bool())
    val ins_baddr = Input(UInt(mp.addrBits.W))
    val ins_count = Input(UInt(vp.regBits.W))
    val vme_rd = new VMEReadMaster
    val inst = new Bundle {
      val ld = Decoupled(UInt(INST_BITS.W))
      val co = Decoupled(UInt(INST_BITS.W))
      val st = Decoupled(UInt(INST_BITS.W))
    }
  })

  val tp = new TensorParams("fetch")
  val tensorsInClNb = tp.clSizeRatio
  val tensorsInClNbWidth = log2Ceil(tensorsInClNb)
  val inst_q = Seq.fill(tensorsInClNb) {
    require((tp.memDepth/tensorsInClNb) * tensorsInClNb == tp.memDepth,
      "-F- Unexpected queue depth to instructions in cacheline ratio")
    SyncReadMem(tp.memDepth/tensorsInClNb, UInt(tp.tensorSizeBits.W))
  }

  // sample start
  val s1_launch = RegNext(io.launch, init = false.B)
  val start = io.launch & ~s1_launch


  val xrem = Reg(chiselTypeOf(io.ins_count))
  // fit instruction into 64bit chunks
  val elemsInInstr = INST_BITS/64
  val xsize = io.ins_count << log2Ceil(elemsInInstr)
  // max size of transfer is limited by a buffer size
  val xmax = (((1 << mp.lenBits) << log2Ceil(tp.clSizeRatio)).min(tp.memDepth)).U
  val elemNb = Reg(xsize.cloneType)

  val sIdle :: sRead :: sDrain :: Nil = Enum(3)
  val state = RegInit(sIdle)
  val isBusy = state === sRead

  val vmeStart = start || (state === sRead && RegNext(state, init = sIdle) === sDrain)
  val dramOffset  = RegInit(UInt(mp.addrBits.W), init = 0.U)
  val vmeCmd = Module (new GenVMECmdWideFetch(debug))
  vmeCmd.io.start := vmeStart
  vmeCmd.io.isBusy := isBusy & ~vmeStart
  vmeCmd.io.ins_baddr := Mux(start, io.ins_baddr, io.ins_baddr + (dramOffset << log2Ceil(tp.tensorSizeBits / 8)))
  vmeCmd.io.vmeCmd <> io.vme_rd.cmd
  val readLen = vmeCmd.io.readLen
  val vmeCmdDone = vmeCmd.io.done & ~vmeStart

  vmeCmd.io.xsize := elemNb
  vmeCmd.io.sram_offset := 0.U // this is a queue we reload

  io.vme_rd.data.ready := true.B
  val pipeDelayQueueDeqV = RegNext(io.vme_rd.data.valid, init = false.B)
  val pipeDelayQueueDeqF = pipeDelayQueueDeqV // fire()
  val pipeDelayQueueDeqB = RegNext(io.vme_rd.data.bits)

  // Nb of CLs requestd, not received.
  val clCntIdxWdth = log2Ceil(tp.memDepth/tensorsInClNb) + 1
  val clInFlight = Reg(UInt(clCntIdxWdth.W))
  when(start) {
    clInFlight := 0.U
  }.elsewhen(isBusy && io.vme_rd.cmd.fire() && !pipeDelayQueueDeqF) {
    clInFlight := clInFlight + readLen
  }.elsewhen(isBusy && io.vme_rd.cmd.fire() && pipeDelayQueueDeqF) {
    clInFlight := clInFlight + readLen - 1.U
  }.elsewhen(isBusy && !io.vme_rd.cmd.fire() && pipeDelayQueueDeqF) {
    assert(clInFlight > 0.U)
    clInFlight := clInFlight - 1.U
  }.otherwise {
    clInFlight := clInFlight
  }

  // number of entries in a queue
  val queueCount = Reg(UInt((tp.memAddrBits + 1).W))
  val queueHead  = Wire(UInt(tp.memAddrBits.W))
  val queueHeadNext  = Reg(UInt(tp.memAddrBits.W))
  val forceRead  = Wire(Bool())
  forceRead := false.B
  // control
  switch(state) {
    is(sIdle) {
      when(start) {
        state := sRead
        dramOffset := 0.U
        when(xsize < xmax) {
          elemNb := xsize
          xrem := 0.U
        }.otherwise {
          elemNb := xmax
          xrem := xsize - xmax
        }
      }
    }
    is(sRead) {
      when(vmeCmdDone && clInFlight === 0.U) {
        forceRead := true.B
        state := sDrain
      }
    }
    is(sDrain) {
      when(queueCount === 0.U) {
        dramOffset := dramOffset + elemNb
        when(xrem === 0.U) {
          state := sIdle
        }.elsewhen(xrem < xmax) {
          state := sRead
          elemNb := xrem
          xrem := 0.U
        }.otherwise {
          state := sRead
          elemNb := xmax
          xrem := xrem - xmax
        }
      }
    }
  }


  //---------------------
  //--- Read VME data ---
  //---------------------

  val readData = Module(new ReadVMEDataWide("fetch", debug))
  readData.io.start := vmeStart
  readData.io.vmeData.valid := pipeDelayQueueDeqV
  readData.io.vmeData.bits := pipeDelayQueueDeqB
  assert(readData.io.vmeData.ready === true.B)

  //--------------------
  //--- Write memory ---
  //--------------------

  val wmask = readData.io.destMask
  val wdata = readData.io.destData
  val widx  = readData.io.destIdx

  for (i <- 0 until tensorsInClNb) {
    when(wmask(i) && pipeDelayQueueDeqF) {
      inst_q(i).write(widx(i), wdata(i))
    }
  }
  if (debug) {
    when (io.vme_rd.data.fire()) {
      printf(s"[TensorLoad] fetch data rdDataDestIdx:%x rdDataDestMask:%b\n",
        widx.asUInt,
        wmask.asUInt)
    }
  }

  // read-from-sram
  // queue head points to the first elem of instruction
  val rIdx = queueHead >> tensorsInClNbWidth // SP idx
  // rMask selects the first elem of instruction
  val rMask = if (tensorsInClNbWidth > 0) {
    UIntToOH(queueHead(tensorsInClNbWidth - 1, 0))
    } else {
      1.U
    }

  val deqElem = Wire(Bool())
  val rdataVec =   for (i <- 0 until tensorsInClNb) yield {
    // expand mask to select all elems of instruction
    val maskShift = i%elemsInInstr
    inst_q(i).read(rIdx, VecInit((rMask<<maskShift).asTypeOf(rMask).asBools)(i) && (deqElem || forceRead))

  }

  // instruction is a elemsInInstr number of elements
  // combine them into one instruction
  val rdata = Wire(Vec(elemsInInstr, UInt((tp.tensorSizeBits).W)))
  for (i <- 0 until elemsInInstr) {
    // expand mask to select all elems of instruction
    rdata(i) := Mux1H(RegNext((rMask << i).asTypeOf(rMask)), rdataVec)
  }


  val canRead = queueCount >= elemsInInstr.U && state === sDrain
  // instruction queues

  // use 2-enty queue to create one pipe stage for valid-ready interface
  val readInstrPipe = Module(new Queue(UInt(INST_BITS.W), 2))

  // decode
  val dec = Module(new FetchDecode)
  dec.io.inst := readInstrPipe.io.deq.bits
  readInstrPipe.io.enq.valid := canRead
  readInstrPipe.io.enq.bits := rdata.asTypeOf(UInt(INST_BITS.W))
  deqElem := readInstrPipe.io.enq.fire()
  readInstrPipe.io.deq.ready := (
    (dec.io.isLoad & io.inst.ld.ready) ||
    (dec.io.isCompute & io.inst.co.ready) ||
    (dec.io.isStore & io.inst.st.ready))
  io.inst.ld.valid := dec.io.isLoad & readInstrPipe.io.deq.valid
  io.inst.co.valid := dec.io.isCompute & readInstrPipe.io.deq.valid
  io.inst.st.valid := dec.io.isStore & readInstrPipe.io.deq.valid

  io.inst.ld.bits := readInstrPipe.io.deq.bits
  io.inst.co.bits := readInstrPipe.io.deq.bits
  io.inst.st.bits := readInstrPipe.io.deq.bits

  when(start) {
    queueCount := 0.U
  }.elsewhen(deqElem && pipeDelayQueueDeqF) {
    assert(queueCount > 0.U, "-F- Decrement zero counter")
    val readCount = PopCount(wmask)
    assert(readCount > 0.U, "-F- Must push something")
    queueCount := queueCount + readCount - elemsInInstr.U
  }.elsewhen(deqElem) {
    assert(queueCount > 0.U, "-F- Decrement zero counter")
    queueCount := queueCount - elemsInInstr.U
  }.elsewhen (pipeDelayQueueDeqF) {
    val numLoaded = PopCount(wmask)
    assert(tp.memDepth.U - numLoaded >= queueCount, "-F- Counter overflow")
    queueCount := queueCount + PopCount(wmask)
  }.otherwise {
    queueCount := queueCount
  }
  when(start) {
    queueHead := 0.U
    queueHeadNext := 0.U
  }.elsewhen(deqElem) {
    queueHead := queueHeadNext + elemsInInstr.U // read ahead
    when (queueCount - elemsInInstr.U === 0.U) {
      queueHeadNext := 0.U
    }.otherwise {
      queueHeadNext := queueHeadNext + elemsInInstr.U
    }
  }.otherwise {
    assert(reset.asBool || state === sIdle || queueCount =/= 0.U ||
      (queueCount === 0.U && queueHeadNext === 0.U))
    queueHead := queueHeadNext
    queueHeadNext := queueHeadNext
  }

  // check if selected queue is ready
  val deq_sel = Cat(dec.io.isCompute, dec.io.isStore, dec.io.isLoad).asUInt
  val deq_ready =
    MuxLookup(deq_sel,
      false.B, // default
      Array(
        "h_01".U -> io.inst.ld.ready,
        "h_02".U -> io.inst.st.ready,
        "h_04".U -> io.inst.co.ready
      ))


  // debug
  if (debug) {
    when(start) {
      printf("[Fetch] Launch\n")
    }
    when(io.inst.ld.fire()) {
      printf("[Fetch] [instruction decode] [L] %x\n", dec.io.inst)
    }
    when(io.inst.co.fire()) {
      printf("[Fetch] [instruction decode] [C] %x\n", dec.io.inst)
    }
    when(io.inst.st.fire()) {
      printf("[Fetch] [instruction decode] [S] %x\n", dec.io.inst)
    }
  }
}
class GenVMECmdWideFetch(debug: Boolean = false)(
    implicit p: Parameters)
    extends Module {
  val mp = p(ShellKey).memParams
  val io = IO(new Bundle {
    val start = Input(Bool())
    val isBusy = Input(Bool())
    val ins_baddr = Input(UInt(mp.addrBits.W))
    val vmeCmd = Decoupled(new VMECmd)
    val readLen = Output(UInt((mp.lenBits + 1).W))
    val done = Output(Bool())

    val xsize = Input(UInt(M_SIZE_BITS.W))
    val sram_offset = Input(UInt(M_SRAM_OFFSET_BITS.W))

  })


  val cmdGen = Module (new GenVMECmdWide(tensorType = "fetch", debug))

  cmdGen.io.start := io.start
  cmdGen.io.isBusy := io.isBusy
  cmdGen.io.baddr := io.ins_baddr
  io.vmeCmd <> cmdGen.io.vmeCmd
  io.readLen :=  cmdGen.io.readLen
  io.done :=  cmdGen.io.done

  cmdGen.io.ysize := 1.U
  cmdGen.io.xsize := io.xsize
  cmdGen.io.xstride := io.xsize
  cmdGen.io.dram_offset := 0.U
  cmdGen.io.sram_offset := io.sram_offset
  cmdGen.io.xpad_0 := 0.U
  cmdGen.io.xpad_1 := 0.U
  cmdGen.io.ypad_0 := 0.U
  cmdGen.io.updateState := io.vmeCmd.fire()
  cmdGen.io.canSendCmd := true.B

  when(io.start) {
    val tp = new TensorParams("fetch")
    assert(io.ins_baddr%(tp.tensorSizeBits/8).U === 0.U, "-F- Fetch DRAM address expected to be tensor size aligned.")
  }

}

