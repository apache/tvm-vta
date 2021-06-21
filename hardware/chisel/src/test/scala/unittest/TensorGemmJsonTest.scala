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

import scala.io._
import scala.language.postfixOps
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper

class TensorGemmJsonTester(c: TensorGemmPipelinedSplit, fn : String = "/x.json")
  extends PeekPokeTester(c) {

  val bufferedSource = Source.fromURL(getClass.getResource(fn))
  val mapper = new ObjectMapper() with ScalaObjectMapper
  mapper.registerModule(DefaultScalaModule)
  val archState = mapper.readValue[Map[String, Object]](bufferedSource.reader())
  bufferedSource.close

  val inst = archState("inst").asInstanceOf[Map[String,String]]

  def build_scratchpad(tag: String) : Array[Array[BigInt]] = {
    val arr = archState(tag).asInstanceOf[Seq[Map[String,Object]]]
    (
      for {(m,i) <- arr zipWithIndex} yield {
        val idx = BigInt(m("idx").asInstanceOf[String], 16)
        assert(BigInt(i) == idx)
        val vec = m("vec").asInstanceOf[Seq[String]]
        (
          for {v <- vec} yield {
            BigInt(v, 16)
          }
        ).toArray
      }
    ).toArray
  }

  val inp_scratchpad = build_scratchpad("inp")
  val wgt_scratchpad = build_scratchpad("wgt")
  val uop_scratchpad = build_scratchpad("uop")
  val acc_scratchpad = build_scratchpad("acc_i")
  val acc_o_scratchpad = build_scratchpad("acc_o")

  poke(c.io.start, 0)

  val dec_reset = BigInt(inst("reset"), 16)
  val uop_begin = BigInt(inst("uop_begin"), 16)
  val uop_end = BigInt(inst("uop_end"), 16)
  assert(uop_begin < uop_end)
  val lp_0 = BigInt(inst("lp_0"), 16)
  val lp_1 = BigInt(inst("lp_1"), 16)
  val acc_0 = BigInt(inst("acc_0"), 16)
  val inp_0 = BigInt(inst("inp_0"), 16)
  val wgt_0 = BigInt(inst("wgt_0"), 16)
  val acc_1 = BigInt(inst("acc_1"), 16)
  val inp_1 = BigInt(inst("inp_1"), 16)
  val wgt_1 = BigInt(inst("wgt_1"), 16)

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

  class TensorMasterMock(tm: TensorMaster, scratchpad : Array[Array[BigInt]]) {
    poke(tm.rd(0).data.valid, 0)
    var valid = peek(tm.rd(0).idx.valid)
    var idx : Int = 0
    def logical_step() {
      if (valid == 1) {
        poke(tm.rd(0).data.valid, 1)
        val cols = tm.rd(0).data.bits(0).size
        for {i <- 0 until tm.rd(0).data.bits.size
          j <- 0 until cols
        } {
          poke(tm.rd(0).data.bits(i)(j), scratchpad(idx)(i*cols + j))
        }
      } else {
        poke(tm.rd(0).data.valid, 0)
      }
      valid = peek(tm.rd(0).idx.valid)
      idx = peek(tm.rd(0).idx.bits).toInt
    }
  }

  class TensorMasterMockWr(tm: TensorMaster, scratchpad : Array[Array[BigInt]]) {
    def logical_step() {
      if (peek(tm.wr(0).valid) == 1) {
        val idx = peek(tm.wr(0).bits.idx).toInt
        val cols = tm.wr(0).bits.data(0).size
        for {
          i <- 0 until tm.wr(0).bits.data.size
          j <- 0 until cols
        } {
          scratchpad(idx)(i*cols + j) = peek(tm.wr(0).bits.data(i)(j))
        }
      }
    }
  }

  class UopMasterMock(um: UopMaster, scratchpad: Array[Array[BigInt]]) {
    poke(um.data.valid, 0)
    var valid = peek(um.idx.valid)
    var idx : Int = 0
    def logical_step() {
      if (valid == 1) {
        poke(um.data.valid, 1)
        poke(um.data.bits.u0, scratchpad(idx)(0))
        poke(um.data.bits.u1, scratchpad(idx)(1))
        poke(um.data.bits.u2, scratchpad(idx)(2))
      } else {
        poke(um.data.valid, 0)
      }
      valid = peek(um.idx.valid)
      idx = peek(um.idx.bits).toInt
    }
  }

  class Mocks {
    val uop_mock = new UopMasterMock(c.io.uop, uop_scratchpad)
    val inp_mock = new TensorMasterMock(c.io.inp, inp_scratchpad)
    val wgt_mock = new TensorMasterMock(c.io.wgt, wgt_scratchpad)
    val acc_mock = new TensorMasterMock(c.io.acc, acc_scratchpad)
    val acc_mock_wr = new TensorMasterMockWr(c.io.acc, acc_scratchpad)

    val uop_indices = new scala.collection.mutable.Queue[BigInt]
    val acc_indices = new scala.collection.mutable.Queue[BigInt]
    val inp_indices = new scala.collection.mutable.Queue[BigInt]
    val wgt_indices = new scala.collection.mutable.Queue[BigInt]
    val accout_indices = new scala.collection.mutable.Queue[BigInt]
    val out_indices = new scala.collection.mutable.Queue[BigInt]

    def logical_step() {
      step(1)
      uop_mock.logical_step()
      inp_mock.logical_step()
      wgt_mock.logical_step()
      acc_mock.logical_step()
      acc_mock_wr.logical_step()

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
      println(s"uop_indices should be empty ${uop_indices.size}")
      println(s"acc_indices should be empty ${acc_indices.size}")
      println(s"inp_indices should be empty ${inp_indices.size}")
      println(s"wgt_indices should be empty ${wgt_indices.size}")
      println(s"accout_indices should be empty ${accout_indices.size}")
      println(s"out_indices should be empty ${out_indices.size}")
    }

    def check() = {
      val result = for {
        ((x,y),idx) <- (acc_scratchpad, acc_o_scratchpad).zipped.toList.zipWithIndex
      } yield {
        (for {((xx,yy),jdx) <- (x,y).zipped.toList.zipWithIndex} yield {
          if (xx != yy) {
            println(s"Value mismatch at $idx $jdx: $xx (actual) != $yy (expected)")
          }
          xx == yy
        }).reduce((x,y) => x&&y)
      }
      val result2 = result.reduce((x,y) => x&&y)
      result2
    }
  }

  val mocks = new Mocks

  for {
    cnt_o <- BigInt(0) until lp_0
    cnt_i <- BigInt(0) until lp_1
    uop_idx <- uop_begin until uop_end
  } {
    val u0 = uop_scratchpad(uop_idx.toInt)(0)
    val u1 = uop_scratchpad(uop_idx.toInt)(1)
    val u2 = uop_scratchpad(uop_idx.toInt)(2)

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
  mocks.logical_step()
  expect(c.io.state, c.sIdle)
  poke(c.io.start, 1)

  val total_steps = (uop_end-uop_begin)*lp_0*lp_1

  val max_count = 100 + 4*total_steps
  var count = 0
  while (peek(c.io.done) == 0 && count < max_count) {
    if (count % 100 == 0) {
      println(s"logical_step $count")
    }
    mocks.logical_step()
    if (count == 0) {
      poke(c.io.start, 0)
    }
    count += 1
  }

  assert(peek(c.io.done) == 1, s"Signal done never high even after $count steps.")
  println(s"Signal done high after $count steps.")

  mocks.logical_step()
  expect(c.io.done, 0)

  val cc = mocks.check()
  println(s"Checking acc with acc_o ${cc}")
  assert(cc)

  println(s"Total active steps: ${total_steps}")
  mocks.test_if_done()
}

class TensorGemmJsonTestSingleUopOverflowOffset extends GenericTest("TensorGemmJson", (p:Parameters) =>
  new TensorGemmPipelinedSplit()(p),
  (c:TensorGemmPipelinedSplit) => new TensorGemmJsonTester(c, "/gemm_1uop_overflow_offset.json"))

class TensorGemmJsonTestDoubleUopOverflowCascaded extends GenericTest("TensorGemmJson", (p:Parameters) =>
  new TensorGemmPipelinedSplit()(p),
  (c:TensorGemmPipelinedSplit) => new TensorGemmJsonTester(c, "/gemm_2uop_overflow_cascaded.json"))
