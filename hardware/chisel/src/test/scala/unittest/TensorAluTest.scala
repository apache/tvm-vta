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
import chisel3.iotesters.{ChiselFlatSpec, Driver, PeekPokeTester}
import scala.util.Random
import unittest.util._
import vta.core._
import vta.util.config._

class TensorAluIndexGeneratorTester(c: TensorAluIndexGenerator, alu_use_imm : Int = 0) extends PeekPokeTester(c) {


  val uop_begin = 0
  val uop_end = 2
  assert(uop_begin < uop_end)

  val lp_0 = 2
  val lp_1 = 3
  val dst_0 = 1*lp_1
  val src_0 = 2*lp_1
  val dst_1 = 1
  val src_1 = 2

  poke(c.io.dec.reset, 0)
  poke(c.io.dec.alu_use_imm, alu_use_imm)
  poke(c.io.dec.uop_begin, uop_begin)
  poke(c.io.dec.uop_end, uop_end)
  poke(c.io.dec.lp_0, lp_0)
  poke(c.io.dec.lp_1, lp_1)
  poke(c.io.dec.dst_0, dst_0)
  poke(c.io.dec.dst_1, dst_1)
  poke(c.io.dec.src_0, src_0)
  poke(c.io.dec.src_1, src_1)
  // Don't need empty_0,{push,pop}_{next,prev},op


  class Mocks {
    val uop_indices = new scala.collection.mutable.Queue[BigInt]
    val dst_indices = new scala.collection.mutable.Queue[BigInt]
    val src_indices = new scala.collection.mutable.Queue[BigInt]

    def logical_step() {
      step(1)
      if (peek(c.io.valid) == 1) {
        expect(c.io.uop_idx, uop_indices.dequeue())
        expect(c.io.dst_idx, dst_indices.dequeue())
      }
      if (peek(c.io.src_valid) == 1) {
        expect(c.io.src_idx, src_indices.dequeue())
      }
    }

    def test_if_done() {
      println(s"uop_indices remaining: ${uop_indices.size}")
      println(s"dst_indices remaining: ${dst_indices.size}")
      println(s"src_indices remaining: ${src_indices.size}")
      assert(uop_indices.isEmpty)
      assert(dst_indices.isEmpty)
      assert(src_indices.isEmpty)
    }
  }

  val mocks = new Mocks
  for {
    cnt_o <- 0 until lp_0
    cnt_i <- 0 until lp_1
    uop_idx <- uop_begin until uop_end
  } {
    mocks.uop_indices.enqueue(uop_idx)
    mocks.dst_indices.enqueue(dst_0*cnt_o + dst_1*cnt_i)
    if (alu_use_imm == 0) {
      mocks.src_indices.enqueue(src_0*cnt_o + src_1*cnt_i)
    }
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
  step(1)
}

class TensorAluIndexGenerator_0_Test extends GenericTest("TensorAluIndexGenerator_0", (p:Parameters) =>
  new TensorAluIndexGenerator()(p), (c:TensorAluIndexGenerator) => new TensorAluIndexGeneratorTester(c, 0))

class TensorAluIndexGenerator_1_Test extends GenericTest("TensorAluIndexGenerator_1", (p:Parameters) =>
  new TensorAluIndexGenerator()(p), (c:TensorAluIndexGenerator) => new TensorAluIndexGeneratorTester(c, 1))

class TensorAluPipelinedTester(c: TensorAlu) extends PeekPokeTester(c) {
  poke(c.io.start, 0)

  val uop_begin = 0
  val uop_end = 1
  assert(uop_begin < uop_end)
  val alu_use_imm = 1
  val lp_0 = 2
  val lp_1 = 3
  val dst_0 = 1*lp_1
  val src_0 = 2*lp_1
  val dst_1 = 1
  val src_1 = 2

  val dst_offset = BigInt("000", 16)
  val src_offset = BigInt("100", 16)

  val u0 = dst_offset
  val u1 = src_offset
  val u2 = 0 // if src_offset is big, some bits go here

  poke(c.io.dec.reset, 0)
  poke(c.io.dec.alu_op, 2) // ADD or ADDI 1
  poke(c.io.dec.alu_imm, 1)
  poke(c.io.dec.alu_use_imm, alu_use_imm)
  poke(c.io.dec.uop_begin, uop_begin)
  poke(c.io.dec.uop_end, uop_end)
  poke(c.io.dec.lp_0, lp_0)
  poke(c.io.dec.lp_1, lp_1)
  poke(c.io.dec.dst_0, dst_0)
  poke(c.io.dec.dst_1, dst_1)
  poke(c.io.dec.src_0, src_0)
  poke(c.io.dec.src_1, src_1)

  // Don't need empty_0,{push,pop}_{next,prev},op

  poke(c.io.uop.data.bits.u0, u0)
  poke(c.io.uop.data.bits.u1, u1)
  poke(c.io.uop.data.bits.u2, u2)

  require(c.io.acc.splitWidth == 1, "-F- Test doesnt support acc data access split")
  require(c.io.acc.splitLength == 1, "-F- Test doesnt support acc data access split")

  val acc = IndexedSeq.tabulate(c.io.acc.rd(0).data.bits(0).size){ i => BigInt(i) }
  for { lhs <- c.io.acc.rd(0).data.bits} {
    poke(lhs, acc.reverse)
  }

  class TensorMasterMock(tm: TensorMaster) {
    poke(tm.rd(0).data.valid, 0)
    var valid = peek(tm.rd(0).idx.valid)
    def logical_step(v: Option[BigInt]) {
      poke(tm.rd(0).data.valid, valid)
      valid = peek(tm.rd(0).idx.valid)
      for { x <- v} expect(tm.rd(0).idx.valid, x)
    }
  }

  class UopMasterMock(um: UopMaster) {
    poke(um.data.valid, 0)
    var valid = peek(um.idx.valid)
    def logical_step(v: Option[BigInt]) {
      poke(um.data.valid, valid)
      valid = peek(um.idx.valid)
      for { x <- v} expect(um.idx.valid, x)
    }
  }

  class Mocks {
    val uop_mock = new UopMasterMock(c.io.uop)
    val acc_mock = new TensorMasterMock(c.io.acc)

    val uop_indices = new scala.collection.mutable.Queue[BigInt]
    val acc_indices = new scala.collection.mutable.Queue[BigInt]
    val accout_indices = new scala.collection.mutable.Queue[BigInt]
    val out_indices = new scala.collection.mutable.Queue[BigInt]

    def logical_step() {
      step(1)
      uop_mock.logical_step(None)
      acc_mock.logical_step(None)
      if (peek(c.io.uop.idx.valid) == 1) {
        expect(c.io.uop.idx.bits, uop_indices.dequeue())
      }
      if (peek(c.io.acc.rd(0).idx.valid) == 1) {
        expect(c.io.acc.rd(0).idx.bits, acc_indices.dequeue())
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
      println(s"accout_indices remaining: ${accout_indices.size}")
      println(s"out_indices remaining: ${out_indices.size}")
      assert(uop_indices.isEmpty)
      assert(acc_indices.isEmpty)
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
    mocks.acc_indices.enqueue(src_offset + src_0*cnt_o + src_1*cnt_i)
    mocks.accout_indices.enqueue(dst_offset + dst_0*cnt_o + dst_1*cnt_i)
    mocks.out_indices.enqueue(dst_offset + dst_0*cnt_o + dst_1*cnt_i)
  }

  poke(c.io.start, 0)
  step(1)
  poke(c.io.start, 1)

  var count = 0
  val end = (uop_end-uop_begin)*lp_0*lp_1

  while (peek(c.io.done) == 0 && count < 10*end + 100) {
    mocks.logical_step()
    poke(c.io.start, 0)
    count += 1
  }
  expect(c.io.done, 1)
  mocks.test_if_done()
}

class TensorAluPipelinedTest extends GenericTest("TensorAluPipelined", (p:Parameters) =>
  new TensorAlu()(p), (c:TensorAlu) => new TensorAluPipelinedTester(c))
