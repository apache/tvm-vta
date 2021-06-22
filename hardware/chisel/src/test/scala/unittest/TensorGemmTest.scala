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

package unittest

import chisel3._
import chisel3.util._
import chisel3.iotesters.PeekPokeTester
import unittest.util._
import vta.core._
import vta.util.config._

class TensorGemmTester(c: TensorGemmSimple) extends PeekPokeTester(c) {
  poke(c.io.start, 0)
  poke(c.io.dec.reset, 0)
  poke(c.io.dec.uop_begin, 0)
  poke(c.io.dec.uop_end, 1)
  poke(c.io.dec.lp_0, 1)
  poke(c.io.dec.lp_1, 1)
  poke(c.io.dec.acc_0, 1)
  poke(c.io.dec.acc_1, 1)
  poke(c.io.dec.inp_0, 1)
  poke(c.io.dec.inp_1, 1)
  poke(c.io.dec.wgt_0, 1)
  poke(c.io.dec.wgt_1, 1)
  // Don't need empty_0, {push, pop}_{next, prev}, op

  poke(c.io.uop.data.bits.u0, 0)
  poke(c.io.uop.data.bits.u1, 0)
  poke(c.io.uop.data.bits.u2, 0)

  val inp = IndexedSeq.fill(c.io.inp.rd(0).data.bits(0).size){BigInt(1)}
  for {lhs <- c.io.inp.rd(0).data.bits} {
    poke(lhs, inp.reverse)
  }

  val wgt = IndexedSeq.fill(c.io.wgt.rd(0).data.bits(0).size){BigInt(1)}
  for {lhs <- c.io.wgt.rd(0).data.bits} {
    poke(lhs, wgt.reverse)
  }

  val acc = IndexedSeq.fill(c.io.acc.rd(0).data.bits(0).size){BigInt(1)}
  for {lhs <- c.io.acc.rd(0).data.bits} {
    poke(lhs, acc.reverse)
  }

  class TensorMasterMock(tm: TensorMaster) {
    poke(tm.rd(0).data.valid, 0)
    var valid = peek(tm.rd(0).idx.valid)

    def logical_step(v: BigInt) {
      poke(tm.rd(0).data.valid, valid)
      valid = peek(tm.rd(0).idx.valid)
      expect(tm.rd(0).idx.valid, v)
    }
  }

  class UopMasterMock(um: UopMaster) {
    poke(um.data.valid, 0)
    var valid = peek(um.idx.valid)

    def logical_step(v: BigInt) {
      poke(um.data.valid, valid)
      valid = peek(um.idx.valid)
      expect(um.idx.valid, v)
    }
  }

  class Mocks {
    val uop_mock = new UopMasterMock(c.io.uop)
    val inp_mock = new TensorMasterMock(c.io.inp)
    val wgt_mock = new TensorMasterMock(c.io.wgt)
    val acc_mock = new TensorMasterMock(c.io.acc)

    def logical_step(sram_valid: BigInt, uop_valid: BigInt) {
      step(1)
      uop_mock.logical_step(uop_valid)
      inp_mock.logical_step(sram_valid)
      wgt_mock.logical_step(sram_valid)
      acc_mock.logical_step(sram_valid)
    }
  }

  val mocks = new Mocks
  poke(c.io.start, 0)

  step(1)

  expect(c.io.state, c.sIdle)

  poke(c.io.start, 1)
  mocks.logical_step(0, 1)
  expect(c.io.state, c.sReadUop)

  expect(c.io.out.wr(0).valid, 0)
  expect(c.io.acc.wr(0).valid, 0)

  poke(c.io.start, 0)

  mocks.logical_step(0, 0)
  expect(c.io.state, c.sComputeIdx)
  expect(c.io.out.wr(0).valid, 0)
  expect(c.io.acc.wr(0).valid, 0)

  mocks.logical_step(1, 0)
  expect(c.io.state, c.sReadTensor)
  expect(c.io.out.wr(0).valid, 0)
  expect(c.io.acc.wr(0).valid, 0)

  mocks.logical_step(0, 0)
  expect(c.io.state, c.sExe)
  expect(c.io.out.wr(0).valid, 0)
  expect(c.io.acc.wr(0).valid, 0)
  expect(c.io.done, 0)

  mocks.logical_step(0, 0)
  expect(c.io.state, c.sWait)
  expect(c.io.inflight, 1)

  expect(c.io.out.wr(0).valid, 0)
  expect(c.io.acc.wr(0).valid, 0)

  mocks.logical_step(0, 0)
  expect(c.io.state, c.sWait)
  expect(c.io.inflight, 1)

  expect(c.io.out.wr(0).valid, 1)
  expect(c.io.acc.wr(0).valid, 1)

  mocks.logical_step(0, 0)
  expect(c.io.state, c.sWait)
  expect(c.io.inflight, 0)

  expect(c.io.out.wr(0).valid, 0)
  expect(c.io.acc.wr(0).valid, 0)

  mocks.logical_step(0, 0)
  expect(c.io.state, c.sIdle)
  expect(c.io.inflight, 0)

  expect(c.io.out.wr(0).valid, 0)
  expect(c.io.acc.wr(0).valid, 0)

}

class TensorGemmTest extends GenericTest("TensorGemm", (p:Parameters) => new TensorGemmSimple()(p),
  (c:TensorGemmSimple) => new TensorGemmTester(c))

class TensorGemmIdxTester(c: TensorGemmSimple) extends PeekPokeTester(c) {

  poke(c.io.start, 0)

  val uop_begin = 0
  val uop_end = 2
  assert(uop_begin < uop_end)
  val lp_0 = 2
  val lp_1 = 3
  val acc_0 = 1*lp_1
  val inp_0 = 2*lp_1
  val wgt_0 = 4*lp_1
  val acc_1 = 1
  val inp_1 = 2
  val wgt_1 = 4
  val u0 = BigInt("000", 16)
  val u1 = BigInt("100", 16)
  val u2 = BigInt("200", 16)

  poke(c.io.dec.reset, 0)
  poke(c.io.dec.uop_begin, uop_begin)
  poke(c.io.dec.uop_end, uop_end)
  poke(c.io.dec.lp_0, lp_0)
  poke(c.io.dec.lp_1, lp_1)
  poke(c.io.dec.acc_0, acc_0)
  poke(c.io.dec.acc_1, acc_1)
  poke(c.io.dec.inp_0, inp_0)
  poke(c.io.dec.inp_1, inp_1)
  poke(c.io.dec.wgt_0, wgt_0)
  poke(c.io.dec.wgt_1, wgt_1)
  // Don't need empty_0,{push,pop}_{next,prev},op

  poke(c.io.uop.data.bits.u0, u0)
  poke(c.io.uop.data.bits.u1, u1)
  poke(c.io.uop.data.bits.u2, u2)

  val inp = IndexedSeq.fill(c.io.inp.rd(0).data.bits(0).size){BigInt(1)}
  for {lhs <- c.io.inp.rd(0).data.bits} {
    poke(lhs, inp.reverse)
  }

  val wgt = IndexedSeq.fill(c.io.wgt.rd(0).data.bits(0).size){BigInt(1)}
  for {lhs <- c.io.wgt.rd(0).data.bits} {
    poke(lhs, wgt.reverse)
  }

  val acc = IndexedSeq.fill(c.io.acc.rd(0).data.bits(0).size){BigInt(1)}
  for {lhs <- c.io.acc.rd(0).data.bits} {
    poke(lhs, acc.reverse)
  }

  class TensorMasterMock(tm: TensorMaster) {
    poke(tm.rd(0).data.valid, 0)
    var valid = peek(tm.rd(0).idx.valid)
    def logical_step(v: BigInt) {
      poke(tm.rd(0).data.valid, valid)
      valid = peek(tm.rd(0).idx.valid)
      expect(tm.rd(0).idx.valid, v)
    }
  }

  class UopMasterMock(um: UopMaster) {
    poke(um.data.valid, 0)
    var valid = peek(um.idx.valid)
    def logical_step(v: BigInt) {
      poke(um.data.valid, valid)
      valid = peek(um.idx.valid)
      expect(um.idx.valid, v)
    }
  }

  class Mocks {
    val uop_mock = new UopMasterMock(c.io.uop)
    val inp_mock = new TensorMasterMock(c.io.inp)
    val wgt_mock = new TensorMasterMock(c.io.wgt)
    val acc_mock = new TensorMasterMock(c.io.acc)

    val uop_indices = new scala.collection.mutable.Queue[BigInt]
    val acc_indices = new scala.collection.mutable.Queue[BigInt]
    val inp_indices = new scala.collection.mutable.Queue[BigInt]
    val wgt_indices = new scala.collection.mutable.Queue[BigInt]
    val accout_indices = new scala.collection.mutable.Queue[BigInt]
    val out_indices = new scala.collection.mutable.Queue[BigInt]

    def logical_step(sram_valid: BigInt, uop_valid: BigInt) {
      step(1)
      uop_mock.logical_step(uop_valid)
      inp_mock.logical_step(sram_valid)
      wgt_mock.logical_step(sram_valid)
      acc_mock.logical_step(sram_valid)
      if (peek(c.io.uop.idx.valid) == 1) {
        expect(c.io.uop.idx.bits, uop_indices.dequeue())
      }
      if (peek(c.io.acc.rd(0).idx.valid) == 1) {
        expect(c.io.acc.rd(0).idx.bits, acc_indices.dequeue())
      }
      if (peek(c.io.inp.rd(0).idx.valid) == 1) {
        expect(c.io.inp.rd(0).idx.bits, inp_indices.dequeue())
      }
      if (peek(c.io.wgt.rd(0).idx.valid) == 1) {
        expect(c.io.wgt.rd(0).idx.bits, wgt_indices.dequeue())
      }
      if (peek(c.io.acc.wr(0).valid) == 1) {
        expect(c.io.acc.wr(0).bits.idx, accout_indices.dequeue())
      }
      if (peek(c.io.out.wr(0).valid) == 1) {
        expect(c.io.out.wr(0).bits.idx, out_indices.dequeue())
      }
    }

    def test_if_done() {
      assert(uop_indices.isEmpty)
      assert(acc_indices.isEmpty)
      assert(inp_indices.isEmpty)
      assert(wgt_indices.isEmpty)
    }
  }

  val mocks = new Mocks
  for {
    cnt_o <- 0 until lp_0
    cnt_i <- 0 until lp_1
    uop_idx <- uop_begin until uop_end
  } {
    mocks.uop_indices.enqueue(uop_idx)
    mocks.acc_indices.enqueue(u0 + acc_0*cnt_o + acc_1*cnt_i)
    mocks.inp_indices.enqueue(u1 + inp_0*cnt_o + inp_1*cnt_i)
    mocks.wgt_indices.enqueue(u2 + wgt_0*cnt_o + wgt_1*cnt_i)
    mocks.accout_indices.enqueue(u0 + acc_0*cnt_o + acc_1*cnt_i)
    mocks.out_indices.enqueue(u0 + acc_0*cnt_o + acc_1*cnt_i)
  }

  poke(c.io.start, 0)
  step(1)
  expect(c.io.state, c.sIdle)

  poke(c.io.start, 1)

  for {q <- 0 until (uop_end-uop_begin)*lp_0*lp_1} {
    mocks.logical_step(0, 1)
    expect(c.io.out.wr(0).valid, 0)
    expect(c.io.acc.wr(0).valid, 0)

    poke(c.io.start, 0)

    mocks.logical_step(0, 0)
    expect(c.io.out.wr(0).valid, if (q > 0) 1 else 0)
    expect(c.io.acc.wr(0).valid, if (q > 0) 1 else 0)

    mocks.logical_step(1, 0)
    expect(c.io.out.wr(0).valid, 0)
    expect(c.io.acc.wr(0).valid, 0)

    mocks.logical_step(0, 0)
    expect(c.io.out.wr(0).valid, 0)
    expect(c.io.acc.wr(0).valid, 0)
    expect(c.io.done, 0)
  }

  mocks.logical_step(0, 0)
  expect(c.io.inflight, 1)

  expect(c.io.out.wr(0).valid, 0)
  expect(c.io.acc.wr(0).valid, 0)

  mocks.logical_step(0, 0)
  expect(c.io.inflight, 1)

  expect(c.io.out.wr(0).valid, 1)
  expect(c.io.acc.wr(0).valid, 1)

  mocks.logical_step(0, 0)
  expect(c.io.inflight, 0)

  expect(c.io.out.wr(0).valid, 0)
  expect(c.io.acc.wr(0).valid, 0)

  mocks.logical_step(0, 0)
  expect(c.io.inflight, 0)

  expect(c.io.out.wr(0).valid, 0)
  expect(c.io.acc.wr(0).valid, 0)

  mocks.test_if_done()
}

class TensorGemmIdxTest extends GenericTest("TensorGemmIdx", (p:Parameters) => new TensorGemmSimple()(p),
  (c:TensorGemmSimple) => new TensorGemmIdxTester(c))

class TensorGemmIndexGeneratorTester(c: TensorGemmIndexGenerator) extends PeekPokeTester(c) {
  val uop_begin = 0
  val uop_end = 2
  assert(uop_begin < uop_end)
  val lp_0 = 2
  val lp_1 = 3
  val acc_0 = 1*lp_1
  val inp_0 = 2*lp_1
  val wgt_0 = 4*lp_1
  val acc_1 = 1
  val inp_1 = 2
  val wgt_1 = 4

  poke(c.io.dec.reset, 0)
  poke(c.io.dec.uop_begin, uop_begin)
  poke(c.io.dec.uop_end, uop_end)
  poke(c.io.dec.lp_0, lp_0)
  poke(c.io.dec.lp_1, lp_1)
  poke(c.io.dec.acc_0, acc_0)
  poke(c.io.dec.acc_1, acc_1)
  poke(c.io.dec.inp_0, inp_0)
  poke(c.io.dec.inp_1, inp_1)
  poke(c.io.dec.wgt_0, wgt_0)
  poke(c.io.dec.wgt_1, wgt_1)
  // Don't need empty_0,{push,pop}_{next,prev},op

  class Mocks {
    val uop_indices = new scala.collection.mutable.Queue[BigInt]
    val acc_indices = new scala.collection.mutable.Queue[BigInt]
    val inp_indices = new scala.collection.mutable.Queue[BigInt]
    val wgt_indices = new scala.collection.mutable.Queue[BigInt]

    def logical_step() {
      step(1)
      if (peek(c.io.valid) == 1) {
        expect(c.io.uop_idx, uop_indices.dequeue())
        expect(c.io.acc_i, acc_indices.dequeue())
        expect(c.io.inp_i, inp_indices.dequeue())
        expect(c.io.wgt_i, wgt_indices.dequeue())
      }
    }

    def test_if_done() {
      println(s"uop_indices remaining: ${uop_indices.size}")
      println(s"acc_indices remaining: ${acc_indices.size}")
      println(s"inp_indices remaining: ${inp_indices.size}")
      println(s"wgt_indices remaining: ${wgt_indices.size}")
      assert(uop_indices.isEmpty)
      assert(acc_indices.isEmpty)
      assert(inp_indices.isEmpty)
      assert(wgt_indices.isEmpty)
    }
  }

  val mocks = new Mocks
  for {
    cnt_o <- 0 until lp_0
    cnt_i <- 0 until lp_1
    uop_idx <- uop_begin until uop_end
  } {
    mocks.uop_indices.enqueue(uop_idx)
    mocks.acc_indices.enqueue(acc_0*cnt_o + acc_1*cnt_i)
    mocks.inp_indices.enqueue(inp_0*cnt_o + inp_1*cnt_i)
    mocks.wgt_indices.enqueue(wgt_0*cnt_o + wgt_1*cnt_i)
  }

  poke(c.io.start, 1)
  mocks.logical_step()
  poke(c.io.start, 0)

  val end = (uop_end-uop_begin)*lp_0*lp_1
  var count = 0
  while(peek(c.io.last) == 0 && count < 10*end + 100) {
    mocks.logical_step()
    count += 1
  }
  mocks.test_if_done()
}

class TensorGemmIndexGeneratorTest extends GenericTest("TensorGemmIndexGenerator",
  (p:Parameters) => new TensorGemmIndexGenerator()(p),
  (c:TensorGemmIndexGenerator) => new TensorGemmIndexGeneratorTester(c))

class TensorGemmPipelinedTester(c: TensorGemmPipelinedSplit) extends PeekPokeTester(c) {
  poke(c.io.start, 0)

  val uop_begin = 0
  val uop_end = 2
  assert(uop_begin < uop_end)
  val lp_0 = 2
  val lp_1 = 3
  val acc_0 = 1*lp_1
  val inp_0 = 2*lp_1
  val wgt_0 = 4*lp_1
  val acc_1 = 1
  val inp_1 = 2
  val wgt_1 = 4
  val u0 = BigInt("000", 16)
  val u1 = BigInt("100", 16)
  val u2 = BigInt("200", 16)

  poke(c.io.dec.reset, 0)
  poke(c.io.dec.uop_begin, uop_begin)
  poke(c.io.dec.uop_end, uop_end)
  poke(c.io.dec.lp_0, lp_0)
  poke(c.io.dec.lp_1, lp_1)
  poke(c.io.dec.acc_0, acc_0)
  poke(c.io.dec.acc_1, acc_1)
  poke(c.io.dec.inp_0, inp_0)
  poke(c.io.dec.inp_1, inp_1)
  poke(c.io.dec.wgt_0, wgt_0)
  poke(c.io.dec.wgt_1, wgt_1)
  // Don't need empty_0,{push,pop}_{next,prev},op

  poke(c.io.uop.data.bits.u0, u0)
  poke(c.io.uop.data.bits.u1, u1)
  poke(c.io.uop.data.bits.u2, u2)

  val inp = IndexedSeq.fill(c.io.inp.rd(0).data.bits(0).size){BigInt(1)}
  for {lhs <- c.io.inp.rd(0).data.bits} {
    poke(lhs, inp.reverse)
  }

  val wgt = IndexedSeq.fill(c.io.wgt.rd(0).data.bits(0).size){BigInt(1)}
  for {lhs <- c.io.wgt.rd(0).data.bits} {
    poke(lhs, wgt.reverse)
  }

  val acc = IndexedSeq.fill(c.io.acc.rd(0).data.bits(0).size){BigInt(1)}
  for {lhs <- c.io.acc.rd(0).data.bits} {
    poke(lhs, acc.reverse)
  }

  class TensorMasterMock(tm: TensorMaster) {
    poke(tm.rd(0).data.valid, 0)
    var valid = peek(tm.rd(0).idx.valid)
    def logical_step(v: Option[BigInt]) {
      poke(tm.rd(0).data.valid, valid)
      valid = peek(tm.rd(0).idx.valid)
      for {x <- v} expect(tm.rd(0).idx.valid, x)
    }
  }

  class UopMasterMock(um: UopMaster) {
    poke(um.data.valid, 0)
    var valid = peek(um.idx.valid)
    def logical_step(v: Option[BigInt]) {
      poke(um.data.valid, valid)
      valid = peek(um.idx.valid)
      for {x <- v} expect(um.idx.valid, x)
    }
  }

  class Mocks {
    val uop_mock = new UopMasterMock(c.io.uop)
    val inp_mock = new TensorMasterMock(c.io.inp)
    val wgt_mock = new TensorMasterMock(c.io.wgt)
    val acc_mock = new TensorMasterMock(c.io.acc)

    val uop_indices = new scala.collection.mutable.Queue[BigInt]
    val acc_indices = new scala.collection.mutable.Queue[BigInt]
    val inp_indices = new scala.collection.mutable.Queue[BigInt]
    val wgt_indices = new scala.collection.mutable.Queue[BigInt]
    val accout_indices = new scala.collection.mutable.Queue[BigInt]
    val out_indices = new scala.collection.mutable.Queue[BigInt]

    def logical_step() {
      step(1)
      uop_mock.logical_step(None)
      inp_mock.logical_step(None)
      wgt_mock.logical_step(None)
      acc_mock.logical_step(None)
      if (peek(c.io.uop.idx.valid) == 1) {
        expect(c.io.uop.idx.bits, uop_indices.dequeue())
      }
      if (peek(c.io.acc.rd(0).idx.valid) == 1) {
        expect(c.io.acc.rd(0).idx.bits, acc_indices.dequeue())
      }
      if (peek(c.io.inp.rd(0).idx.valid) == 1) {
        expect(c.io.inp.rd(0).idx.bits, inp_indices.dequeue())
      }
      if (peek(c.io.wgt.rd(0).idx.valid) == 1) {
        expect(c.io.wgt.rd(0).idx.bits, wgt_indices.dequeue())
      }
      if (peek(c.io.acc.wr(0).valid) == 1) {
        expect(c.io.acc.wr(0).bits.idx, accout_indices.dequeue())
      }
      if (peek(c.io.out.wr(0).valid) == 1) {
        expect(c.io.out.wr(0).bits.idx, out_indices.dequeue())
      }
    }

    def test_if_done() {
      println(s"uop_indices remaining: ${uop_indices.size}")
      println(s"acc_indices remaining: ${acc_indices.size}")
      println(s"inp_indices remaining: ${inp_indices.size}")
      println(s"wgt_indices remaining: ${wgt_indices.size}")
      println(s"accout_indices remaining: ${accout_indices.size}")
      println(s"out_indices remaining: ${out_indices.size}")
    }
  }

  val mocks = new Mocks
  for {
    cnt_o <- 0 until lp_0
    cnt_i <- 0 until lp_1
    uop_idx <- uop_begin until uop_end
  } {
    mocks.uop_indices.enqueue(uop_idx)
    mocks.acc_indices.enqueue(u0 + acc_0*cnt_o + acc_1*cnt_i)
    mocks.inp_indices.enqueue(u1 + inp_0*cnt_o + inp_1*cnt_i)
    mocks.wgt_indices.enqueue(u2 + wgt_0*cnt_o + wgt_1*cnt_i)
    mocks.accout_indices.enqueue(u0 + acc_0*cnt_o + acc_1*cnt_i)
    mocks.out_indices.enqueue(u0 + acc_0*cnt_o + acc_1*cnt_i)
  }

  poke(c.io.start, 0)
  step(1)
  expect(c.io.state, c.sIdle)
  poke(c.io.start, 1)

  var count = 0
  val end = (uop_end-uop_begin)*lp_0*lp_1

  while (peek(c.io.done) == 0 && count < 10*end + 100) {
    mocks.logical_step()
    poke(c.io.start, 0)
  }

  expect(c.io.done, 1)
  mocks.test_if_done()
}

class TensorGemmPipelinedTest extends GenericTest("TensorGemmPipelined",
  (p:Parameters) => new TensorGemmPipelinedSplit()(p),
  (c:TensorGemmPipelinedSplit) => new TensorGemmPipelinedTester(c))

class TensorGemmResetTester(c: TensorGemm) extends PeekPokeTester(c) {
  poke(c.io.start, 0)

  val uop_begin = 0
  val uop_end = 2
  assert(uop_begin < uop_end)
  val lp_0 = 2
  val lp_1 = 3
  val acc_0 = 1*lp_1
  val inp_0 = 2*lp_1
  val wgt_0 = 4*lp_1
  val acc_1 = 1
  val inp_1 = 2
  val wgt_1 = 4
  val u0 = BigInt("000", 16)
  val u1 = BigInt("100", 16)
  val u2 = BigInt("200", 16)
  val dec_reset = 1

  poke(c.io.dec.reset, dec_reset)
  poke(c.io.dec.uop_begin, uop_begin)
  poke(c.io.dec.uop_end, uop_end)
  poke(c.io.dec.lp_0, lp_0)
  poke(c.io.dec.lp_1, lp_1)
  poke(c.io.dec.acc_0, acc_0)
  poke(c.io.dec.acc_1, acc_1)
  poke(c.io.dec.inp_0, inp_0)
  poke(c.io.dec.inp_1, inp_1)
  poke(c.io.dec.wgt_0, wgt_0)
  poke(c.io.dec.wgt_1, wgt_1)
  // Don't need empty_0,{push,pop}_{next,prev},op

  poke(c.io.uop.data.bits.u0, u0)
  poke(c.io.uop.data.bits.u1, u1)
  poke(c.io.uop.data.bits.u2, u2)

  val inp = IndexedSeq.fill(c.io.inp.rd(0).data.bits(0).size){BigInt(1)}
  for {lhs <- c.io.inp.rd(0).data.bits} {
    poke(lhs, inp.reverse)
  }

  val wgt = IndexedSeq.fill(c.io.wgt.rd(0).data.bits(0).size){BigInt(1)}
  for {lhs <- c.io.wgt.rd(0).data.bits} {
    poke(lhs, wgt.reverse)
  }

  val acc = IndexedSeq.fill(c.io.acc.rd(0).data.bits(0).size){BigInt(1)}
  for {lhs <- c.io.acc.rd(0).data.bits} {
    poke(lhs, acc.reverse)
  }

  class TensorMasterMock(tm: TensorMaster) {
    poke(tm.rd(0).data.valid, 0)
    var valid = peek(tm.rd(0).idx.valid)
    def logical_step(v: Option[BigInt]) {
      poke(tm.rd(0).data.valid, valid)
      valid = peek(tm.rd(0).idx.valid)
      for {x <- v} expect(tm.rd(0).idx.valid, x)
    }
  }

  class UopMasterMock(um: UopMaster) {
    poke(um.data.valid, 0)
    var valid = peek(um.idx.valid)
    def logical_step(v: Option[BigInt]) {
      poke(um.data.valid, valid)
      valid = peek(um.idx.valid)
      for {x <- v} expect(um.idx.valid, x)
    }
  }

  class Mocks {
    val uop_mock = new UopMasterMock(c.io.uop)
    val inp_mock = new TensorMasterMock(c.io.inp)
    val wgt_mock = new TensorMasterMock(c.io.wgt)
    val acc_mock = new TensorMasterMock(c.io.acc)

    val uop_indices = new scala.collection.mutable.Queue[BigInt]
    val acc_indices = new scala.collection.mutable.Queue[BigInt]
    val inp_indices = new scala.collection.mutable.Queue[BigInt]
    val wgt_indices = new scala.collection.mutable.Queue[BigInt]
    val accout_indices = new scala.collection.mutable.Queue[BigInt]
    val out_indices = new scala.collection.mutable.Queue[BigInt]

    def logical_step(sram_valid: BigInt, uop_valid: BigInt) {
      step(1)
      uop_mock.logical_step(None)
      inp_mock.logical_step(None)
      wgt_mock.logical_step(None)
      acc_mock.logical_step(None)
      if (peek(c.io.uop.idx.valid) == 1) {
        expect(c.io.uop.idx.bits, uop_indices.dequeue())
      }
      if (peek(c.io.acc.rd(0).idx.valid) == 1) {
        expect(c.io.acc.rd(0).idx.bits, acc_indices.dequeue())
      }
      if (peek(c.io.inp.rd(0).idx.valid) == 1) {
        expect(c.io.inp.rd(0).idx.bits, inp_indices.dequeue())
      }
      if (peek(c.io.wgt.rd(0).idx.valid) == 1) {
        expect(c.io.wgt.rd(0).idx.bits, wgt_indices.dequeue())
      }
      if (peek(c.io.acc.wr(0).valid) == 1) {
        expect(c.io.acc.wr(0).bits.idx, accout_indices.dequeue())
      }
      if (peek(c.io.out.wr(0).valid) == 1) {
        expect(c.io.out.wr(0).bits.idx, out_indices.dequeue())
      }
    }

    def test_if_done() {
      assert(uop_indices.isEmpty)
      assert(acc_indices.isEmpty)
      assert(inp_indices.isEmpty)
      assert(wgt_indices.isEmpty)
      assert(accout_indices.isEmpty)
      assert(out_indices.isEmpty)
    }
  }

  val mocks = new Mocks
  for {
    cnt_o <- 0 until lp_0
    cnt_i <- 0 until lp_1
    uop_idx <- uop_begin until uop_end
  } {
    mocks.uop_indices.enqueue(uop_idx)
    mocks.acc_indices.enqueue(u0 + acc_0*cnt_o + acc_1*cnt_i)
    mocks.inp_indices.enqueue(u1 + inp_0*cnt_o + inp_1*cnt_i)
    mocks.wgt_indices.enqueue(u2 + wgt_0*cnt_o + wgt_1*cnt_i)
    mocks.accout_indices.enqueue(u0 + acc_0*cnt_o + acc_1*cnt_i)

    if (dec_reset == 0) {
      mocks.out_indices.enqueue(u0 + acc_0*cnt_o + acc_1*cnt_i)
    }
  }

  poke(c.io.start, 0)
  step(1)
  expect(c.io.state, c.sIdle)
  poke(c.io.start, 1)

  while(peek(c.io.done) == 0) {
    mocks.logical_step(0, 0)
    poke(c.io.start, 0)
  }

  mocks.test_if_done()
}

class TensorGemmResetTest extends GenericTest("TensorGemmReset", (p:Parameters) => new TensorGemm()(p),
  (c:TensorGemm) => new TensorGemmResetTester(c))
