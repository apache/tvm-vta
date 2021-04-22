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
import scala.math.pow

/** Pipelined multiply and accumulate */
class MAC(aBits: Int = 8, bBits: Int = 8, cBits: Int = 16, flopIn: Boolean = false) extends Module {
  val outBits = Math.max(aBits + bBits, cBits) + 1
  val io = IO(new Bundle {
    val a = Input(SInt(aBits.W))
    val b = Input(SInt(bBits.W))
    val c = Input(SInt(cBits.W))
    val y = Output(SInt(outBits.W))
  })

  val mult = Wire(SInt((aBits + bBits).W))
  val rA = if (flopIn) RegNext(io.a) else io.a
  val rB = if (flopIn) RegNext(io.b) else io.b
  val rC = if (flopIn) RegNext(io.c) else io.c

  mult := rA * rB
  val addV = if (flopIn) {rC +& mult} else {RegNext(rC +& mult)}
  io.y := addV
}

class AdderIO(val aBits: Int, val bBits: Int) extends Bundle {
  val outBits = Math.max(aBits, bBits) + 1
  val a = Input(SInt(aBits.W))
  val b = Input(SInt(bBits.W))
  val y = Output(SInt(outBits.W))
}

trait IsAdder { val io: AdderIO }

/** PipeAdder
 *
 * This unit loads input bits into register and performs addition in the next cycle
 */
class PipeAdder(aBits: Int = 8, bBits: Int = 8) extends Module with IsAdder {
  val io = IO(new AdderIO(aBits, bBits))
  val add = Wire(chiselTypeOf(io.y))
  val rA = RegNext(io.a)
  val rB = RegNext(io.b)
  add := rA +& rB
  io.y := add
}

/** Adder
 *
 * This unit wires input bits to an adder directly.
 * The output comes out of combinational logic without waiting for another cycle.
 */
class Adder(aBits: Int = 8, bBits: Int = 8) extends Module with IsAdder {
  val io = IO(new AdderIO(aBits, bBits))
  val add = Wire(chiselTypeOf(io.y))
  val rA = Wire(SInt(aBits.W))
  val rB = Wire(SInt(bBits.W))
  rA := io.a
  rB := io.b
  add := rA +& rB
  io.y := add
}

/** Pipelined DotProduct based on MAC and PipeAdder */
class DotProduct(aBits: Int = 8, bBits: Int = 8, blockIn: Int = 16) extends Module {
  val errorMsg =
    s"\n\n[VTA] [DotProduct] size must be greater than 4 and a power of 2\n\n"
  require(blockIn >= 2 && isPow2(blockIn), errorMsg)
  val b = aBits + bBits
  val outBits = b + log2Ceil(blockIn) + 1
  val io = IO(new Bundle {
    val a = Input(Vec(blockIn, SInt(aBits.W)))
    val b = Input(Vec(blockIn, SInt(bBits.W)))
    val y = Output(SInt(outBits.W))
  })
  val s = Seq.tabulate(log2Ceil(blockIn + 1))(i =>
    pow(2, log2Ceil(blockIn) - i).toInt) // # of total layers
  val p = log2Ceil(blockIn / 2) + 1 // # of adder layers
  val m = Seq.fill(s(0))(Module(new MAC(aBits, bBits, cBits = 1, flopIn = p < 6))) // # of total vector pairs
  val a = Seq.tabulate(p)(
    i =>
      Seq.fill(s(i + 1))(
        if ((i == 0 && p < 4) || (i == p - 2 && p >= 4)) {
          Module(new PipeAdder(aBits = (b + i + 1), bBits = (b + i + 1)))
        }
        else {
          Module(new Adder(aBits = (b + i + 1), bBits = (b + i + 1)))
        }
      )) // # adders within each layer

  // Vector MACs
  for (i <- 0 until s(0)) {
    m(i).io.a := io.a(i)
    m(i).io.b := io.b(i)
    m(i).io.c := 0.S
  }

  // PipeAdder Reduction
  for (i <- 0 until p) {
    for (j <- 0 until s(i + 1)) {
      if (i == 0) {
        // First layer of PipeAdders
        a(i)(j).io.a := m(2 * j).io.y
        a(i)(j).io.b := m(2 * j + 1).io.y
      } else {
        a(i)(j).io.a := a(i - 1)(2 * j).io.y
        a(i)(j).io.b := a(i - 1)(2 * j + 1).io.y
      }
    }
  }

  // last adder
  io.y := a(p - 1)(0).io.y
}

/** Perform matrix-vector-multiplication based on DotProduct */
class MatrixVectorMultiplication(implicit p: Parameters) extends Module {
  val accBits = p(CoreKey).accBits
  val size = p(CoreKey).blockOut / p(CoreKey).blockOutFactor
  val batch = p(CoreKey).batch
  val inpBits = p(CoreKey).inpBits
  val wgtBits = p(CoreKey).wgtBits
  val outBits = p(CoreKey).outBits
  val io = IO(new Bundle {
    val reset = Input(Bool()) // FIXME: reset should be replaced by a load-acc instr
    val inp = new TensorMasterData(tensorType = "inp")
    val wgt = new TensorMasterData(tensorType = "wgt")
    val acc_i = new TensorMasterData(tensorType = "acc")
    val acc_o = new TensorClientData(tensorType = "acc")
    val out = new TensorClientData(tensorType = "out")
  })
  val dot = Seq.fill(batch)(Seq.fill(size)(
    Module(new DotProduct(aBits = inpBits, bBits = wgtBits, size))))
  // Latency is defined as two in the following, because there is one cycle in the MAC module,
  // and another cycle in the pipelined adders as the first layer of the accumulator
  val acc = Seq.fill(batch)(Seq.fill(size)(Module(new Pipe(UInt(accBits.W), latency = 2))))
  val add = Seq.fill(batch)(Seq.fill(size)(Wire(SInt(accBits.W))))
  val vld = Wire(Vec(batch, Vec(size, Bool())))

  for (b <- 0 until batch) {
    for (i <- 0 until size) {
      acc(b)(i).io.enq.valid := io.inp.data.valid & io.wgt.data.valid & io.acc_i.data.valid & ~io.reset
      acc(b)(i).io.enq.bits := io.acc_i.data.bits(b)(i)
      for (j <- 0 until size) {
        dot(b)(i).io.a(j) := io.inp.data.bits(b)(j).asSInt
        dot(b)(i).io.b(j) := io.wgt.data.bits(i)(j).asSInt // all batches get the same weight - reuse
      }
      add(b)(i) := acc(b)(i).io.deq.bits.asSInt + dot(b)(i).io.y
      io.acc_o.data.bits(b)(i) := Mux(io.reset, 0.U, add(b)(i).asUInt)
      io.out.data.bits(b)(i) := add(b)(i).asUInt
      vld(b)(i) := acc(b)(i).io.deq.valid
    }
    io.acc_o.data.valid := vld.asUInt.andR | io.reset
    io.out.data.valid := vld.asUInt.andR
  }
}

/** Perform matrix-vector-multiplication based on DotProduct */
class MatrixVectorMultiplicationBypass(implicit p: Parameters) extends Module {
  val accBits = p(CoreKey).accBits
  val blockOut = p(CoreKey).blockOut / p(CoreKey).blockOutFactor
  val blockIn = p(CoreKey).blockIn
  val batch   = p(CoreKey).batch
  val inpBits = p(CoreKey).inpBits
  val wgtBits = p(CoreKey).wgtBits
  val outBits = p(CoreKey).outBits
  val io = IO(new Bundle {
    val valid_reset = Input(Bool())
    val inp = new TensorMasterData(tensorType = "inp")
    val wgt = new TensorMasterData(tensorType = "wgt")
    val acc_i = new TensorMasterData(tensorType = "acc")
    val acc_o = new TensorClientData(tensorType = "acc")
    val out = new TensorClientData(tensorType = "out")
    val bypass_cond = Input(Bool())
  })
  val dot = Seq.fill(batch)(Seq.fill(blockOut)(
    Module(new DotProduct(aBits = inpBits, bBits = wgtBits, blockIn))))
  val add = Seq.fill(batch)(Seq.fill(blockOut)(Wire(SInt(accBits.W))))
  val last_acc_write = Seq.fill(batch)(Seq.fill(blockOut){Reg(SInt(accBits.W))})
  io.out.data.bits := DontCare // out is not fully initialized by a single module
  for (b <- 0 until batch) {
    for (i <- 0 until blockOut) {
      for (j <- 0 until blockIn) {
        dot(b)(i).io.a(j) := io.inp.data.bits(b)(j).asSInt
        dot(b)(i).io.b(j) := io.wgt.data.bits(i)(j).asSInt
      }
      val byp = Mux(io.bypass_cond, last_acc_write(b)(i), io.acc_i.data.bits(b)(i).asSInt)
      add(b)(i) := byp + dot(b)(i).io.y
      val tmp = Mux(io.valid_reset, 0.S, add(b)(i))
      io.acc_o.data.bits(b)(i) := tmp.asUInt
      last_acc_write(b)(i) := tmp
      io.out.data.bits(b)(i) := add(b)(i).asUInt
    }
  }
  io.acc_o.data.valid := io.acc_i.data.valid | io.valid_reset
  io.out.data.valid := io.acc_i.data.valid & ~io.valid_reset
}

class TensorGemmIndexGenerator(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val last = Output(Bool())

    val dec = Input(new GemmDecode)

    val acc_i = Output(UInt(new TensorParams(tensorType="acc").memAddrBits.W))
    val inp_i = Output(UInt(new TensorParams(tensorType="inp").memAddrBits.W))
    val wgt_i = Output(UInt(new TensorParams(tensorType="wgt").memAddrBits.W))

    val uop_idx = Output(UInt(log2Ceil(p(CoreKey).uopMemDepth).W))

    val valid = Output(Bool())
  })

  io.last := false.B

  val running = RegInit(false.B)
  when(!running && io.start) {
    running := true.B
  }.elsewhen(io.last) {
    running := false.B
  }

  val cnt_i = Reg(chiselTypeOf(io.dec.lp_1))
  val acc_i = Reg(chiselTypeOf(io.acc_i))
  val inp_i = Reg(chiselTypeOf(io.inp_i))
  val wgt_i = Reg(chiselTypeOf(io.wgt_i))

  val cnt_o = Reg(chiselTypeOf(io.dec.lp_0))
  val acc_o = Reg(chiselTypeOf(io.acc_i))
  val inp_o = Reg(chiselTypeOf(io.inp_i))
  val wgt_o = Reg(chiselTypeOf(io.wgt_i))

  val uop_idx = Reg(chiselTypeOf(io.dec.uop_end))

  io.valid := running
  io.acc_i := acc_i
  io.inp_i := inp_i
  io.wgt_i := wgt_i
  io.uop_idx := uop_idx

  when(!running) {
    cnt_i := 0.U; acc_i := 0.U; inp_i := 0.U; wgt_i := 0.U
    cnt_o := 0.U; acc_o := 0.U; inp_o := 0.U; wgt_o := 0.U
    uop_idx := io.dec.uop_begin
  } .otherwise {
    when (uop_idx =/= io.dec.uop_end - 1.U) {
      uop_idx := uop_idx + 1.U
    }.otherwise {
      uop_idx := io.dec.uop_begin
      when (cnt_i =/= io.dec.lp_1 - 1.U) {
        cnt_i := cnt_i + 1.U
        acc_i := acc_i + io.dec.acc_1
        inp_i := inp_i + io.dec.inp_1
        wgt_i := wgt_i + io.dec.wgt_1
      }.otherwise {
        when (cnt_o =/= io.dec.lp_0 - 1.U) {
          val acc_tmp = acc_o + io.dec.acc_0
          val inp_tmp = inp_o + io.dec.inp_0
          val wgt_tmp = wgt_o + io.dec.wgt_0
          cnt_o := cnt_o + 1.U
          acc_o := acc_tmp
          inp_o := inp_tmp
          wgt_o := wgt_tmp
          cnt_i := 0.U
          acc_i := acc_tmp
          inp_i := inp_tmp
          wgt_i := wgt_tmp
        } .otherwise {
          io.last := true.B
        }
      }
    }
  }
}

abstract class TensorGemmIfc(implicit p: Parameters) extends Module {
  val stateBits = 3
  val inflightBits = 4
  val io = IO(new Bundle {
    val start = Input(Bool())
    val done = Output(Bool())
    val dec = Input(new GemmDecode)
    val uop = new UopMaster
    val inp = new TensorMaster(tensorType = "inp")
    val wgt = new TensorMaster(tensorType = "wgt")
    val acc = new TensorMaster(tensorType = "acc")
    val out = new TensorMaster(tensorType = "out")
    val state = Output(UInt(stateBits.W))
    val inflight = Output(UInt(inflightBits.W))
  })
}

/** TensorGemmSimple
 *
 * This unit instantiate the MatrixVectorMultiplication and go over the
 * micro-ops (uops) which are used to read inputs, weights and biases,
 * and writes results back to the acc and out scratchpads.
 *
 * Also, TensorGemmSimple uses the reset field in the Gemm instruction to
 * clear or zero-out the acc-scratchpad locations based on the micro-ops.
 */
class TensorGemmSimple(debug: Boolean = false)(implicit p: Parameters) extends TensorGemmIfc {

  require(p(CoreKey).blockOutFactor == 1,
    "-F- Split GEMM not supported. Use TensorGemmPipelinedSplit or set blockOutFactor to 1")
  val sIdle :: sReadUop :: sComputeIdx :: sReadTensor :: sExe :: sWait :: Nil = Enum(6)
  val state = RegInit(sIdle)
  io.state := state
  val mvc = Module(new MatrixVectorMultiplication)
  val dec = io.dec
  val uop_idx = Reg(chiselTypeOf(dec.uop_end))
  val uop_end = dec.uop_end
  val uop_acc = Reg(chiselTypeOf(dec.uop_end))
  val uop_inp = Reg(chiselTypeOf(dec.uop_end))
  val uop_wgt = Reg(chiselTypeOf(dec.uop_end))
  val cnt_o = Reg(chiselTypeOf(dec.lp_0))
  val acc_o = Reg(chiselTypeOf(dec.uop_end))
  val inp_o = Reg(chiselTypeOf(dec.uop_end))
  val wgt_o = Reg(chiselTypeOf(dec.uop_end))
  val cnt_i = Reg(chiselTypeOf(dec.lp_1))
  val acc_i = Reg(chiselTypeOf(dec.uop_end))
  val inp_i = Reg(chiselTypeOf(dec.uop_end))
  val wgt_i = Reg(chiselTypeOf(dec.uop_end))

  val inflight = Reg(UInt(inflightBits.W))
  io.inflight := inflight
  // Latency is defined as two in the following, because there is one cycle in the MAC module,
  // and another cycle in the pipelined adders as the first layer of the accumulator
  val wrpipe = Module(new Pipe(chiselTypeOf(dec.uop_end), latency = 2))
  val cond = cnt_o === dec.lp_0 - 1.U &
    cnt_i === dec.lp_1 - 1.U &
    uop_idx === uop_end - 1.U

  val done = inflight === 0.U &
    ((state === sExe) & cond | state === sWait)

  switch(state) {
    is(sIdle) {
      when(io.start) {
        state := sReadUop
      }
    }
    is(sReadUop) {
      state := sComputeIdx
    }
    is(sComputeIdx) {
      state := sReadTensor
    }
    is(sReadTensor) {
      state := sExe
    }
    is(sExe) {
      when(cond) {
        when(inflight =/= 0.U) {
          state := sWait
        }.otherwise {
          state := sIdle
        }
      }.otherwise {
        state := sReadUop
      }
    }
    is(sWait) {
      when(inflight === 0.U) {
        state := sIdle
      }
    }
  }

  when(state === sIdle) {
    inflight := 0.U
  }.elsewhen(!dec.reset) {
    when((state === sReadTensor) && mvc.io.acc_o.data.valid) { // issue & commit
    }.elsewhen(state === sReadTensor) { // issue a tensor
      assert(inflight =/= ((1<<inflightBits)-1).U)
      inflight := inflight + 1.U
    }.elsewhen(mvc.io.acc_o.data.valid) { // commit a tensor
      assert(inflight =/= 0.U)
      inflight := inflight - 1.U
    }
  }

  when(
    state === sIdle ||
      (state === sExe &&
        uop_idx === uop_end - 1.U)) {
    uop_idx := dec.uop_begin
  }.elsewhen(state === sExe && dec.uop_begin =/= uop_end) {
    uop_idx := uop_idx + 1.U
  }

  when(state === sIdle) {
    cnt_o := 0.U
    acc_o := 0.U
    inp_o := 0.U
    wgt_o := 0.U
  }.elsewhen(
    state === sExe &&
      uop_idx === uop_end - 1.U &&
      cnt_i === dec.lp_1 - 1.U) {
    cnt_o := cnt_o + 1.U
    acc_o := acc_o + dec.acc_0
    inp_o := inp_o + dec.inp_0
    wgt_o := wgt_o + dec.wgt_0
  }

  when(state === sIdle) {
    cnt_i := 0.U
    acc_i := 0.U
    inp_i := 0.U
    wgt_i := 0.U
  }.elsewhen(state === sReadUop && cnt_i === dec.lp_1) {
    cnt_i := 0.U
    acc_i := acc_o
    inp_i := inp_o
    wgt_i := wgt_o
  }.elsewhen(state === sExe && uop_idx === uop_end - 1.U) {
    cnt_i := cnt_i + 1.U
    acc_i := acc_i + dec.acc_1
    inp_i := inp_i + dec.inp_1
    wgt_i := wgt_i + dec.wgt_1
  }

  when(state === sComputeIdx && io.uop.data.valid) {
    uop_acc := io.uop.data.bits.u0 + acc_i
    uop_inp := io.uop.data.bits.u1 + inp_i
    uop_wgt := io.uop.data.bits.u2 + wgt_i
  }

  wrpipe.io.enq.valid := state === sExe & ~dec.reset
  wrpipe.io.enq.bits := uop_acc

  // uop
  io.uop.idx.valid := state === sReadUop
  io.uop.idx.bits := uop_idx

  // inp
  io.inp.rd(0).idx.valid := state === sReadTensor
  io.inp.rd(0).idx.bits := uop_inp
  io.inp.tieoffWrite() // read-only

  // wgt
  io.wgt.rd(0).idx.valid := state === sReadTensor
  io.wgt.rd(0).idx.bits := uop_wgt
  io.wgt.tieoffWrite() // read-only

  // acc_i
  io.acc.rd(0).idx.valid := state === sReadTensor
  io.acc.rd(0).idx.bits := uop_acc

  // mvc
  mvc.io.reset := dec.reset & state === sExe
  mvc.io.inp.data <> io.inp.rd(0).data
  mvc.io.wgt.data <> io.wgt.rd(0).data
  mvc.io.acc_i.data <> io.acc.rd(0).data

  // acc_o
  io.acc.wr(0).valid := mvc.io.acc_o.data.valid &
    Mux(dec.reset, true.B, wrpipe.io.deq.valid)
  io.acc.wr(0).bits.idx := Mux(dec.reset, uop_acc, wrpipe.io.deq.bits)
  io.acc.wr(0).bits.data <> mvc.io.acc_o.data.bits

  // out
  io.out.wr(0).valid := mvc.io.out.data.valid & wrpipe.io.deq.valid
  io.out.wr(0).bits.idx := wrpipe.io.deq.bits
  io.out.wr(0).bits.data <> mvc.io.out.data.bits
  io.out.tieoffRead() // write-only

  io.done := done

  if (debug) {
    printf("[TensorGemm] [state]:%d [inflight]:%d\n", state, inflight)

    when(state === sReadUop && ~dec.reset) {
      printf("[TensorGemm] [uop] idx:%x\n", uop_idx)
    }

    when(state === sReadTensor && ~dec.reset) {
      printf("[TensorGemm] [uop] acc:%x inp:%x wgt:%x\n", uop_acc, uop_inp, uop_wgt)
    }

    io.inp.rd(0).data.bits.zipWithIndex.foreach {
      case (r, i) =>
        when(io.inp.rd(0).data.valid && ~dec.reset) {
          printf("[TensorGemm] [inp] i:%x val:%x\n", i.U, r.asUInt)
        }
    }

    io.wgt.rd(0).data.bits.zipWithIndex.foreach {
      case (r, i) =>
        when(io.wgt.rd(0).data.valid && ~dec.reset) {
          printf("[TensorGemm] [wgt] i:%x val:%x\n", i.U, r.asUInt)
        }
    }

    io.acc.rd(0).data.bits.foreach { tensor =>
      tensor.zipWithIndex.foreach {
        case (elem, i) =>
          when(io.acc.rd(0).data.valid && ~dec.reset) {
            printf("[TensorGemm] [acc_i] i:%x val:%x\n", i.U, elem)
          }
      }
    }

    mvc.io.acc_o.data.bits.foreach { tensor =>
      tensor.zipWithIndex.foreach {
        case (elem, i) =>
          when(mvc.io.acc_o.data.valid && ~dec.reset) {
            printf("[TensorGemm] [acc_o] i:%x val:%x\n", i.U, elem)
          }
      }
    }

    mvc.io.out.data.bits.foreach { tensor =>
      tensor.zipWithIndex.foreach {
        case (elem, i) =>
          when(mvc.io.out.data.valid && ~dec.reset) {
            printf("[TensorGemm] [out] i:%x val:%x\n", i.U, elem)
          }
      }
    }
  }
}

class TensorGemmPipelinedSplit (implicit p: Parameters) extends TensorGemmIfc {
  val sIdle::sRun::sWait::Nil = Enum(3);
  val numMVMs = p(CoreKey).blockOutFactor
  val numOuts = p(CoreKey).blockOut / numMVMs
  require (numOuts > 0, "-F- Cannot factor more groups than blockOut")
  val batch = p(CoreKey).batch

  val m = Module(new TensorGemmIndexGenerator)

  // additional pipe latency of wgt/inp read if needed
  val scratchpadReadLatency = 0
  val inpReadIdxLatency = 0
  val uopReadLatency = 0

  val delayed_valid = ShiftRegister(m.io.valid, uopReadLatency + 1, resetData = false.B, en = true.B)
  val delayed_acc_i = ShiftRegister(m.io.acc_i, uopReadLatency + 1)
  val delayed_inp_i = ShiftRegister(m.io.inp_i, uopReadLatency + 1)
  val delayed_wgt_i = ShiftRegister(m.io.wgt_i, uopReadLatency + 1)

  val state = RegInit(sIdle)
  val inflight = RegInit(0.U(inflightBits.W))

  val capture_dec = Reg(chiselTypeOf(io.dec))

  io.done := false.B
  when(state === sIdle && io.start) {
    state := sRun
    capture_dec := io.dec
    // if (io.dec.empty_0 != None) assert(io.dec.empty_0.get === 0.U)
    // if (io.dec.empty_1 != None) assert(io.dec.empty_1.get === 0.U)
  }.elsewhen(state === sRun && m.io.last) {
    state := sWait
  }.elsewhen(state === sWait && inflight === 0.U) {
    state := sIdle
    io.done := true.B
  }
  io.state := state

  assert(state =/= sRun  || capture_dec.asUInt === io.dec.asUInt)
  assert(state =/= sWait || capture_dec.asUInt === io.dec.asUInt)

  m.io.start := io.start

  m.io.dec := io.dec
  io.uop.idx.bits := m.io.uop_idx
  io.uop.idx.valid := m.io.valid

  val delayedUopData = ShiftRegister(io.uop.data, uopReadLatency)

  assert(delayedUopData.valid === delayed_valid)

  val uop_valid = ShiftRegister(delayed_valid, inpReadIdxLatency, resetData = false.B, en = true.B)
  val uop_acc = ShiftRegister(delayedUopData.bits.u0 + delayed_acc_i, inpReadIdxLatency)
  val uop_inp =  delayedUopData.bits.u1 + delayed_inp_i // it is piped in inp tensor read
  val uop_wgt = ShiftRegister(delayedUopData.bits.u2 + delayed_wgt_i, inpReadIdxLatency)

  val reset_pipe = Module(
    new Pipe(
      Bool(),
      latency = 3 /* 1 stage is borrowed down here*/ + scratchpadReadLatency + inpReadIdxLatency + uopReadLatency))
  reset_pipe.io.enq.valid := m.io.valid
  reset_pipe.io.enq.bits := capture_dec.reset

  val acc_idx_pipe = Module(
    new Pipe(chiselTypeOf(io.acc.rd(0).idx.bits), latency= 1 /* borrow 1 stage to split*/ + scratchpadReadLatency))
  acc_idx_pipe.io.enq.valid := uop_valid
  acc_idx_pipe.io.enq.bits := uop_acc

  require(io.inp.splitWidth == 1 && io.inp.splitLength == 1, "-F- Input split read not supported")
  io.inp.rd(0).idx.valid := delayed_valid
  io.inp.rd(0).idx.bits := uop_inp
  val delayed_uop_valid = RegNext(uop_valid, init=false.B) // memdelay
  // asset fires on emulated tensorRead Direct GEMM test TODO: fix memoryManager sram read
  // it works only for VTA_CORE_GEMM_INP_IDX_PIPE 0
  assert(io.inp.rd(0).data.valid === delayed_uop_valid)
  for (idx <- 0 until numMVMs) {
    io.acc.rd(idx).idx.valid := RegNext(acc_idx_pipe.io.deq.valid, init = false.B)
    io.acc.rd(idx).idx.bits := RegNext(acc_idx_pipe.io.deq.bits)

    // delay wgt read by input result delay latency
    io.wgt.rd(idx).idx.valid := ShiftRegister(uop_valid, scratchpadReadLatency)
    io.wgt.rd(idx).idx.bits := ShiftRegister(uop_wgt, scratchpadReadLatency)

    assert(io.wgt.rd(idx).data.valid === ShiftRegister(delayed_uop_valid, scratchpadReadLatency))
  }
  io.wgt.tieoffWrite()
  io.inp.tieoffWrite()

  // create a pipe of 3+ delay with split by goup last stage
  // and a separate last stage for out and inflight
  val wrpipe0 = Module(new Pipe(chiselTypeOf(io.acc.wr(0).bits.idx), latency= 2 + scratchpadReadLatency))
  wrpipe0.io.enq.valid := uop_valid
  wrpipe0.io.enq.bits := uop_acc
  // write pipe not split
  val wrpipeNs = Module(new Pipe(chiselTypeOf(io.acc.wr(0).bits.idx), latency= 1))
  wrpipeNs.io.enq <> wrpipe0.io.deq
  // split the last pipe stage per group
  val wrpipe =  for (idx <- 0 until numMVMs) yield {
    val pipe = Module(new Pipe(chiselTypeOf(io.acc.wr(0).bits.idx), latency= 1))
    pipe.io.enq <> wrpipe0.io.deq
    pipe
  }

  for (idx <- 0 until numMVMs) {
    assert(io.acc.rd(idx).data.valid === wrpipe(idx).io.deq.valid)
  }

  when(m.io.valid && wrpipeNs.io.deq.valid) {
  }.elsewhen(m.io.valid) {
    assert(inflight =/= ((1<<inflightBits)-1).U)
    inflight := inflight + 1.U
  }.elsewhen(wrpipeNs.io.deq.valid) {
    assert(inflight =/= 0.U)
    inflight := inflight - 1.U
  }
  when(state === sIdle) {
    assert(inflight === 0.U)
    inflight := 0.U
  }

  io.inflight := inflight

  val mvmInpRdLatency = if (scratchpadReadLatency == 0) {
    0
  } else {
    scratchpadReadLatency - 1
  }
  // split factor of inp data for many groups
  val splitFactorL0 = pow(2,log2Ceil(numMVMs) / 2).toInt
  val splitFactorL1 = pow(2,log2Ceil(numMVMs)
    - log2Ceil(numMVMs) / 2).toInt
  require(splitFactorL0 * splitFactorL1 == numMVMs)
  val inpRdData0 = for (idx <- 0 until splitFactorL0) yield {
    if (scratchpadReadLatency > 0) RegNext(io.inp.rd(0).data) else io.inp.rd(0).data
  }

  // define MVC groups operating on a subset of acc elements
  // each MVM generates only a part of acc bits while has whole inteface defined !!!
  // those bits are lower bits in acc/out interface
  val mvc = for (idx <- 0 until numMVMs) yield {Module(new MatrixVectorMultiplicationBypass)}

  require(io.out.splitWidth == 1 && io.out.splitLength == 1, "-F- Out split write is not supported")
  for (idx1 <- 0 until numMVMs) {

    val wrpipe2 = Module(new Pipe(chiselTypeOf(io.acc.wr(0).bits.idx), latency=1))
    wrpipe2.io.enq := wrpipe(idx1).io.deq

    mvc(idx1).io.bypass_cond :=
      wrpipe(idx1).io.deq.bits === wrpipe2.io.deq.bits && wrpipe(idx1).io.deq.valid && wrpipe2.io.deq.valid

    // borrow one stage from reset_pipe and split per group
    mvc(idx1).io.valid_reset := RegNext(reset_pipe.io.deq.bits & reset_pipe.io.deq.valid, init = false.B)
    // wire to each mvm
    mvc(idx1).io.inp.data :=
      ShiftRegister(inpRdData0(idx1/splitFactorL1), mvmInpRdLatency) // delay to deliver over distance
    mvc(idx1).io.wgt.data := io.wgt.rd(idx1).data // wgt read idx is delayed instead of data
    mvc(idx1).io.acc_i.data.valid := io.acc.rd(idx1).data.valid
    assert(mvc(idx1).io.acc_o.data.valid === (wrpipe(idx1).io.deq.valid | mvc(idx1).io.valid_reset))
    for(accLenIdx <- 0 until mvc(idx1).io.acc_o.lenSplit) {
      for(accWdtIdx <- 0 until mvc(idx1).io.acc_o.widthSplit) {
        val (gemmGrpIdx, gemmLenIdx, gemmWdtIdx) =
          mvc(idx1).io.acc_o.reindexDataToGroup(idx1, accLenIdx, accWdtIdx)
        mvc(gemmGrpIdx).io.acc_i.data.bits(gemmLenIdx)(gemmWdtIdx) :=
          io.acc.rd(idx1).data.bits(accLenIdx)(accWdtIdx)
      }
    }

    for(gemmLenIdx <- 0 until mvc(idx1).io.acc_o.lenSplit) {
      for(gemmWdtIdx <- 0 until mvc(idx1).io.acc_o.widthSplit) {
        val (accGrpIdx, accLenIdx, accWdtIdx) =
          mvc(idx1).io.acc_o.reindexDataFromGroup(idx1, gemmLenIdx, gemmWdtIdx)
        io.acc.wr(accGrpIdx).bits.data(accLenIdx)(accWdtIdx) :=
          mvc(idx1).io.acc_o.data.bits(gemmLenIdx)(gemmWdtIdx)
      }
    }

    io.acc.wr(idx1).valid := wrpipe(idx1).io.deq.valid
    io.acc.wr(idx1).bits.idx := wrpipe(idx1).io.deq.bits
  }
// comment to split write out
  if (numMVMs > 1) {
    for (idx1 <- 1 until numMVMs) {
      assert(mvc(idx1).io.out.data.valid === mvc(idx1 - 1).io.out.data.valid,
        "-F- Out split write is not supported")
    }
  }
  val outData = Wire(io.out.wr(0).bits.data.cloneType)
  for (idx3 <- 0 until numMVMs) {
    for (idx1 <- 0 until io.out.tensorLength) {
      for (idx2 <- 0 until io.out.tensorWidth/numMVMs) {
        outData(idx1)(idx3*io.out.tensorWidth/numMVMs + idx2) := mvc(idx3).io.out.data.bits(idx1)(idx2)
      }
    }
  }
  io.out.wr(0).bits.data := outData
  io.out.wr(0).valid := wrpipeNs.io.deq.valid && mvc(io.acc.closestIOGrpIdx).io.out.data.valid
  io.out.wr(0).bits.idx := wrpipeNs.io.deq.bits

  io.out.tieoffRead()
}

class TensorGemm(implicit val p: Parameters) extends TensorGemmPipelinedSplit
