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
import vta.util._
import vta.shell._

/** Compute.
 *
 * The compute unit is in charge of the following:
 * - Loading micro-ops from memory (loadUop module)
 * - Loading biases (acc) from memory (tensorAcc module)
 * - Compute ALU instructions (tensorAlu module)
 * - Compute GEMM instructions (tensorGemm module)
 */
class Compute(debug: Boolean = false)(implicit val p: Parameters) extends Module {
  val mp = p(ShellKey).memParams
  val io = IO(new Bundle {
    val i_post = Vec(2, Input(Bool()))
    val o_post = Vec(2, Output(Bool()))
    val inst = Flipped(Decoupled(UInt(INST_BITS.W)))
    val uop_baddr = Input(UInt(mp.addrBits.W))
    val acc_baddr = Input(UInt(mp.addrBits.W))
    val vme_rd = Vec(2, new VMEReadMaster)
    val inp = new TensorMaster(tensorType = "inp")
    val wgt = new TensorMaster(tensorType = "wgt")
    val out = new TensorMaster(tensorType = "out")
    val finish = Output(Bool())
    val acc_wr_event = Output(Bool())
  })
  val sIdle :: sSync :: sExe :: Nil = Enum(3)
  val state = RegInit(sIdle)

  val s = Seq.tabulate(2)(_ =>
    Module(new Semaphore(counterBits = 8, counterInitValue = 0)))


  val loadUop = Module(new LoadUopTop)
  val tensorAcc = Module(new TensorLoad(tensorType = "acc"))
  val tensorGemm = Module(new TensorGemm)
  val tensorAlu = Module(new TensorAlu)

  //try to use the acc closest to top IO
  val topAccGrpIdx = tensorGemm.io.acc.closestIOGrpIdx

  val inst_q = Module(new SyncQueue(UInt(INST_BITS.W), p(CoreKey).instQueueEntries))

  // decode
  val dec = Module(new ComputeDecode)
  dec.io.inst := inst_q.io.deq.bits

  val inst_type =
    Cat(dec.io.isFinish,
      dec.io.isAlu,
      dec.io.isGemm,
      dec.io.isLoadAcc,
      dec.io.isLoadUop).asUInt

  val sprev = inst_q.io.deq.valid & Mux(dec.io.pop_prev, s(0).io.sready, true.B)
  val snext = inst_q.io.deq.valid & Mux(dec.io.pop_next, s(1).io.sready, true.B)
  val start = snext & sprev
  val done =
    MuxLookup(
      inst_type,
      false.B, // default
      Array(
        "h_01".U -> loadUop.io.done,
        "h_02".U -> tensorAcc.io.done,
        "h_04".U -> tensorGemm.io.done,
        "h_08".U -> tensorAlu.io.done,
        "h_10".U -> true.B // Finish
      )
    )

  // control
  switch(state) {
    is(sIdle) {
      when(start) {
        when(dec.io.isSync) {
          state := sSync
        }.elsewhen(inst_type.orR) {
          state := sExe
        }
      }
    }
    is(sSync) {
      state := sIdle
    }
    is(sExe) {
      when(done) {
        state := sIdle
      }
    }
  }

  // instructions
  inst_q.io.enq <> io.inst
  inst_q.io.deq.ready := (state === sExe & done) | (state === sSync)

  // uop
  loadUop.io.start := state === sIdle & start & dec.io.isLoadUop
  loadUop.io.inst := inst_q.io.deq.bits
  loadUop.io.baddr := io.uop_baddr
  io.vme_rd(0) <> loadUop.io.vme_rd
  loadUop.io.uop.idx <> Mux(dec.io.isGemm, tensorGemm.io.uop.idx, tensorAlu.io.uop.idx)
  assert(!tensorGemm.io.uop.idx.valid || !tensorAlu.io.uop.idx.valid)

  // acc
  tensorAcc.io.start := state === sIdle & start & dec.io.isLoadAcc
  tensorAcc.io.inst := inst_q.io.deq.bits
  tensorAcc.io.baddr := io.acc_baddr
  require(tensorAcc.io.tensor.lenSplit ==
    tensorAcc.io.tensor.tensorLength, "-F- Expecting a whole batch in acc group")

  // split factor of isGemm for many groups
  val splitFactorL0 = pow(2,log2Ceil(tensorAcc.io.tensor.splitWidth) / 2).toInt
  val splitFactorL1 = pow(2,log2Ceil(tensorAcc.io.tensor.splitWidth)
    - log2Ceil(tensorAcc.io.tensor.splitWidth) / 2).toInt
  require(splitFactorL0 * splitFactorL1 == tensorAcc.io.tensor.splitWidth)
  val accRdSelectL0 = for (idx <- 0 until splitFactorL1) yield {
    // can save 1 stage on small design
    if (splitFactorL1 > 1) RegNext(dec.io.isGemm, init = false.B) else dec.io.isGemm
  }

  for (idx <- 0 until tensorAcc.io.tensor.splitWidth) {
    tensorAcc.io.tensor.rd(idx).idx <> Mux(
      RegNext(accRdSelectL0(idx/splitFactorL0), init = false.B),
      tensorGemm.io.acc.rd(idx).idx,
      tensorAlu.io.acc.rd(idx).idx)
    tensorAcc.io.tensor.wr(idx) <> Mux(
      RegNext(accRdSelectL0(idx/splitFactorL0), init = false.B),
      tensorGemm.io.acc.wr(idx),
      tensorAlu.io.acc.wr(idx))
  }
  io.vme_rd(1) <> tensorAcc.io.vme_rd
  io.acc_wr_event := tensorAcc.io.tensor.wr(topAccGrpIdx).valid

  // gemm
  tensorGemm.io.start := RegNext(state === sIdle & start & dec.io.isGemm, init = false.B)
  tensorGemm.io.dec := inst_q.io.deq.bits.asTypeOf(new GemmDecode)
  tensorGemm.io.uop.data.valid := loadUop.io.uop.data.valid & dec.io.isGemm
  tensorGemm.io.uop.data.bits <> loadUop.io.uop.data.bits
  tensorGemm.io.inp <> io.inp
  tensorGemm.io.wgt <> io.wgt
  for (idx <- 0 until tensorGemm.io.acc.splitWidth) {
    tensorGemm.io.acc.rd(idx).data.valid :=
      tensorAcc.io.tensor.rd(idx).data.valid & RegNext(dec.io.isGemm, init = false.B)
    tensorGemm.io.acc.rd(idx).data.bits <> tensorAcc.io.tensor.rd(idx).data.bits
  }
  for (idx <- 0 until tensorGemm.io.out.splitWidth) {
    tensorGemm.io.out.rd(idx).data.valid :=
      io.out.rd(idx).data.valid & RegNext(dec.io.isGemm, init = false.B)
    tensorGemm.io.out.rd(idx).data.bits <> io.out.rd(idx).data.bits
  }

  // alu
  tensorAlu.io.start := RegNext(state === sIdle & start & dec.io.isAlu, init = false.B)
  tensorAlu.io.dec := inst_q.io.deq.bits.asTypeOf(new AluDecode)
  tensorAlu.io.uop.data.valid := loadUop.io.uop.data.valid & dec.io.isAlu
  tensorAlu.io.uop.data.bits <> loadUop.io.uop.data.bits
  for (idx <- 0 until tensorAlu.io.acc.splitWidth) {
    tensorAlu.io.acc.rd(idx).data.valid :=
      tensorAcc.io.tensor.rd(idx).data.valid & RegNext(dec.io.isAlu, init = false.B)
    tensorAlu.io.acc.rd(idx).data.bits <> tensorAcc.io.tensor.rd(idx).data.bits
  }
  for (idx <- 0 until tensorAlu.io.out.splitWidth) {
    tensorAlu.io.out.rd(idx).data.valid :=
      io.out.rd(idx).data.valid & RegNext(dec.io.isAlu, init = false.B)
    tensorAlu.io.out.rd(idx).data.bits <> io.out.rd(idx).data.bits
  }

  // out
  for (idx <- 0 until tensorGemm.io.out.splitWidth) {
    io.out.rd(idx).idx <> Mux(dec.io.isGemm,
      tensorGemm.io.out.rd(idx).idx,
      tensorAlu.io.out.rd(idx).idx)
    assert(!tensorGemm.io.out.rd(idx).idx.valid || !tensorAlu.io.out.rd(idx).idx.valid)
    assert(!tensorGemm.io.out.rd(idx).data.valid || !tensorAlu.io.out.rd(idx).data.valid)

    assert(!tensorGemm.io.out.wr(idx).valid || !tensorAlu.io.out.wr(idx).valid)
  }
  require (tensorGemm.io.out.splitWidth == 1)
  require (tensorAlu.io.out.splitWidth == 1)
  io.out.wr(0).valid := Mux(
    RegNext(dec.io.isGemm, init = false.B), tensorGemm.io.out.wr(0).valid, tensorAlu.io.out.wr(0).valid)
  io.out.wr(0).bits.idx := Mux(
    RegNext(dec.io.isGemm, init = false.B), tensorGemm.io.out.wr(0).bits.idx, tensorAlu.io.out.wr(0).bits.idx)
  //put mux/Reg into every gemm group to build pipe (for Mux select) tree over distance
  val chunkWidth = io.out.wr(0).bits.data.getWidth / tensorGemm.io.acc.splitWidth
  val outDataBits = Wire(Vec(tensorGemm.io.acc.splitWidth, UInt(chunkWidth.W)))
  io.out.wr(0).bits.data := outDataBits.asTypeOf(io.out.wr(0).bits.data)
  for (idx <- 0 until tensorGemm.io.acc.splitWidth) {
    val lowBitIdx = idx * chunkWidth
    val highBitIdx = lowBitIdx + chunkWidth - 1
    val srcAluFlat = tensorAlu.io.out.wr(0).bits.data.asUInt
    val srcGemFlat = tensorGemm.io.out.wr(0).bits.data.asUInt
    outDataBits(idx) := Mux(
      RegNext(dec.io.isGemm, init = false.B),
      srcGemFlat(highBitIdx, lowBitIdx),
      srcAluFlat(highBitIdx, lowBitIdx))
  }
  // semaphore
  s(0).io.spost := io.i_post(0)
  s(1).io.spost := io.i_post(1)
  s(0).io.swait := dec.io.pop_prev & (state === sIdle & start)
  s(1).io.swait := dec.io.pop_next & (state === sIdle & start)
  io.o_post(0) := dec.io.push_prev & ((state === sExe & done) | (state === sSync))
  io.o_post(1) := dec.io.push_next & ((state === sExe & done) | (state === sSync))

  // finish
  io.finish := state === sExe & done & dec.io.isFinish

  // debug
  if (debug) {
    // start
    when(state === sIdle && start) {
      when(dec.io.isSync) {
        printf("[Compute] start sync\n")
      }.elsewhen(dec.io.isLoadUop) {
        printf("[Compute] start load uop\n")
      }.elsewhen(dec.io.isLoadAcc) {
        printf("[Compute] start load acc\n")
      }.elsewhen(dec.io.isGemm) {
        printf("[Compute] start gemm\n")
      }.elsewhen(dec.io.isAlu) {
        printf("[Compute] start alu\n")
      }.elsewhen(dec.io.isFinish) {
        printf("[Compute] start finish\n")
      }
    }
    // done
    when(state === sSync) {
      printf("[Compute] done sync\n")
    }
    when(state === sExe) {
      when(done) {
        when(dec.io.isLoadUop) {
          printf("[Compute] done load uop\n")
        }.elsewhen(dec.io.isLoadAcc) {
          printf("[Compute] done load acc\n")
        }.elsewhen(dec.io.isGemm) {
          printf("[Compute] done gemm\n")
        }.elsewhen(dec.io.isAlu) {
          printf("[Compute] done alu\n")
        }.elsewhen(dec.io.isFinish) {
          printf("[Compute] done finish\n")
        }
      }
    }
  }
}
