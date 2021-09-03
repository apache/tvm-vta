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
import vta.util._
import vta.util.config._

class TestOnePortMem(c: OnePortMem[UInt]) extends PeekPokeTester(c) {

  // write a:0 d:24
  println("-----------------------------")
  println("Cycle 0 write 24 to address 0")
  poke (c.io.wr_en, 1)
  poke (c.io.wr_data, 24)
  poke (c.io.ch_en, 1)
  poke (c.io.addr, 0)
  step(1)
  println("-----------------------------")
  // read a:0
  println("Cycle 1 read address 0")
  poke (c.io.wr_en, 0)
  poke (c.io.addr, 0)
  poke (c.io.ch_en, 1)
  step(1)
  println("-----------------------------")
  // write a:1 d:99
  println("Cycle 2 write 99 to address 1")
  poke (c.io.wr_en, 1)
  poke (c.io.wr_data, 99)
  poke (c.io.ch_en, 1)
  poke (c.io.addr, 1)
  // read d:24
  println("Cycle 2 read expect data 24")
  expect (c.io.rd_data, 24)
  step(1)
  println("-----------------------------")
  println("Cycle 3 should still read data 24")
  poke (c.io.ch_en, 0)
  // read d:24
  expect (c.io.rd_data, 24)
  step(1)
  println("-----------------------------")
  println("Cycle 4 read address 0")
  poke (c.io.wr_en, 0)
  poke (c.io.addr, 0)
  poke (c.io.ch_en, 1)
  step(1)
  println("-----------------------------")
  // write a:1 d:99
  poke (c.io.wr_en, 0)
  poke (c.io.wr_data, 99)
  poke (c.io.ch_en, 0)
  poke (c.io.addr, 1)
  // read d:24
  println("Cycle 5 read expect data 24")
  expect (c.io.rd_data, 24)
  step(1)
  println("-----------------------------")
}
class Checker(c: SyncQueueTestWrapper[UInt], t: PeekPokeTester[SyncQueueTestWrapper[UInt]]) {

  def bits (bits: Int) = {
    t.expect(c.io.tq.deq.bits, bits)
    t.expect(c.io.rq.deq.bits, bits)

  }
  def ready (bits: Int) = {
    t.expect(c.io.tq.enq.ready, bits)
    t.expect(c.io.rq.enq.ready, bits)

  }
  def valid (bits: Int) = {
    t.expect(c.io.tq.deq.valid, bits)
    t.expect(c.io.rq.deq.valid, bits)

  }
  def status () = {
    val rv = t.peek(c.io.rq.enq.ready)
    t.expect(c.io.tq.enq.ready, rv)
    val rc = t.peek(c.io.rq.count)
    t.expect(c.io.tq.count, rc)
    val vv = t.peek(c.io.rq.deq.valid)
    t.expect(c.io.tq.deq.valid, vv)
    if (vv != 0) {
      val bv = t.peek(c.io.rq.deq.bits)
      t.expect(c.io.tq.deq.bits, bv)
    }
    t.peek(c.io.rq.count)
    t.peek(c.io.tq.count)
  }
}
class TestSyncQueueLongRead(c: SyncQueueTestWrapper[UInt]) extends PeekPokeTester(c) {

  val chr = new Checker (c, this)

  def testFillRW(depth: Int) = {
    val qsize = peek(c.io.tq.count)
    require(qsize == 0, s"-F- An empty queue is expected ${qsize}")

    poke (c.io.tq.deq.ready, 0)
    poke (c.io.tq.enq.valid, 0)
    chr.ready(1)
    step(1)

    // fill up to depth
    for (i <- 10 until 10 + depth) {
      poke (c.io.tq.enq.bits, i)
      poke (c.io.tq.enq.valid, 1)
      chr.status()
      step(1)

    }
    // read and write same cycle
    for (i <- 30 + depth until 30 + depth * 2) {
      poke (c.io.tq.enq.valid, 1)
      poke (c.io.tq.deq.ready, 1)
      poke (c.io.tq.enq.bits, i)
      chr.status()
      step(1)
    }
    // read out
    for (i <- 0 until depth + 1) {
      poke (c.io.tq.enq.valid, 0)
      poke (c.io.tq.deq.ready, 1)
      poke (c.io.tq.enq.bits, 99)
      chr.status()
      step(1)
    }
  }
  for (i <- 1 until 28) {
    testFillRW(i)
  }
}
class TestSyncQueueWaveRead(c: SyncQueueTestWrapper[UInt]) extends PeekPokeTester(c) {

  val chr = new Checker (c, this)

  def testFillRW(depth: Int) = {
    val qsize = peek(c.io.tq.count)
    require(qsize == 0, s"-F- An empty queue is expected ${qsize}")

    poke (c.io.tq.deq.ready, 0)
    poke (c.io.tq.enq.valid, 0)
    chr.ready(1)
    step(1)

    // fill up to depth
    for (i <- 10 until 10 + depth) {
      poke (c.io.tq.enq.bits, i)
      poke (c.io.tq.enq.valid, 1)
      chr.status()
      step(1)

    }
    // read out, no write
    poke (c.io.tq.enq.valid, 0)
    poke (c.io.tq.deq.ready, 1)
    for (i <- 0 until 7) {
      chr.status()
      step(1)
    }
    // fill more
    poke (c.io.tq.deq.ready, 0)
    poke (c.io.tq.enq.valid, 1)
    for (i <- 0 until 13) {
      poke (c.io.tq.enq.bits, 99 + i)
      chr.status()
      step(1)
    }
    // read out, no write
    poke (c.io.tq.enq.valid, 0)
    poke (c.io.tq.deq.ready, 1)
    for (i <- 1 until 14 + depth) {
      chr.status()
      step(1)
    }
  }
  // read
  for (i <- 1 until 28) {
    testFillRW(i)
  }
}

class SyncQueueTestWrapper[T <: Data](
    gen: T,
    val entries: Int)
    extends Module() {


  val genType = gen

  val io = IO(new Bundle {
    val tq = new QueueIO(genType, entries)
    val rq = new QueueIO(genType, entries)

    })

  val tq = Module(new SyncQueue1PortMem(genType.asUInt, entries))
  val rq = Module(new Queue(genType.asUInt, entries))
  io.tq <> tq.io
  io.rq <> rq.io
  tq.io.enq.valid := RegNext(io.tq.enq.valid)
  tq.io.enq.bits := RegNext(io.tq.enq.bits)
  tq.io.deq.ready := RegNext(io.tq.deq.ready)
  // connect reference queue inport to test input
  rq.io.enq.valid := RegNext(io.tq.enq.valid)
  rq.io.enq.bits := RegNext(io.tq.enq.bits)
  rq.io.deq.ready := RegNext(io.tq.deq.ready)
}

class SyncQueueTestLongRead24 extends GenericTest(
  "Queue",
  (p:Parameters) => new SyncQueueTestWrapper(UInt(16.W), 24),
  (c:SyncQueueTestWrapper[UInt]) => new TestSyncQueueLongRead(c))
class SyncQueueTestLongRead13 extends GenericTest(
  "Queue",
  (p:Parameters) => new SyncQueueTestWrapper(UInt(16.W), 13),
  (c:SyncQueueTestWrapper[UInt]) => new TestSyncQueueLongRead(c))
class SyncQueueTestWaveRead24 extends GenericTest(
  "Queue",
  (p:Parameters) => new SyncQueueTestWrapper(UInt(16.W), 24),
  (c:SyncQueueTestWrapper[UInt]) => new TestSyncQueueWaveRead(c))
class OnePorMemTest extends GenericTest(
  "Queue",
  (p:Parameters) => new OnePortMem(UInt(16.W), 16, ""),
  (c:OnePortMem[UInt]) => new TestOnePortMem(c))
class SyncQueueTestWaveRead1 extends GenericTest(
  "Queue",
  (p:Parameters) => new SyncQueueTestWrapper(UInt(16.W), 1),
  (c:SyncQueueTestWrapper[UInt]) => new TestSyncQueueWaveRead(c))
class SyncQueueTestWaveRead2 extends GenericTest(
  "Queue",
  (p:Parameters) => new SyncQueueTestWrapper(UInt(16.W), 2),
  (c:SyncQueueTestWrapper[UInt]) => new TestSyncQueueWaveRead(c))
class SyncQueueTestWaveRead3 extends GenericTest(
  "Queue",
  (p:Parameters) => new SyncQueueTestWrapper(UInt(16.W), 3),
  (c:SyncQueueTestWrapper[UInt]) => new TestSyncQueueWaveRead(c))
class SyncQueueTestWaveRead4 extends GenericTest(
  "Queue",
  (p:Parameters) => new SyncQueueTestWrapper(UInt(16.W), 4),
  (c:SyncQueueTestWrapper[UInt]) => new TestSyncQueueWaveRead(c))
