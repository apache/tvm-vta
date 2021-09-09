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

package vta.util

import chisel3._
import chisel3.util._

import vta.util.config._

//! Queue with SRAM one port or 1r1W
class SyncQueue[T <: Data](
    gen: T,
    val entries: Int,
    pipe: Boolean = false,
    flow: Boolean = false)
    extends Module() {

  val genType = gen
  val forceSimpleQueue = false // Force usage of Queue

  val io = IO(new QueueIO(genType, entries))

  require (!pipe, "-F- Not supported")
  require (!flow, "-F- Not supported")

  if (forceSimpleQueue) {
    val queue = Module(new Queue(genType.asUInt, entries))
    io <> queue.io
  } else {
    val queue = Module(new SyncQueue2PortMem(genType.asUInt, entries))
    io <> queue.io
  }


}

// Implement a Queue on a single-port memory
// pipe/flow not supported
// combine DoubleQueue with a 3-entry Queue
// Queue is required to buffer DoubleQueue latency
class SyncQueue1PortMem[T <: Data](
    gen: T,
    val entries: Int,
    pipe: Boolean = false,
    flow: Boolean = false)
    extends Module() {

  val genType = gen

  val io = IO(new QueueIO(genType, entries))

  require (!pipe, "-F- Not supported")
  require (!flow, "-F- Not supported")

  if (entries < 4 ) {
    val queue =  Module(new Queue(genType.asUInt, entries))
    io <> queue.io
  } else {
    val queue = Module(new SyncQueue1PortMemImpl(genType.asUInt, entries))
    io <> queue.io
  }


}
class SyncQueue1PortMemImpl[T <: Data](
    gen: T,
    val entries: Int,
    pipe: Boolean = false,
    flow: Boolean = false)
    extends Module() {

  require (!pipe, "-F- Not supported")
  require (!flow, "-F- Not supported")

  val genType = gen

  val io = IO(new QueueIO(genType, entries))

  require (entries > 3, "-F- TODO: small queue implemetation")
  val doubleQueue = Module(new DoubleQueue(genType.asUInt, entries))
  val buffer = Module(new Queue(genType.asUInt, 3))

  val doubleQueueHasValues = doubleQueue.io.count =/= 0.U
  val bufferInValid = Mux(doubleQueueHasValues, doubleQueue.io.deq.valid, io.enq.valid)
  val bufferInBits = Mux(doubleQueueHasValues, doubleQueue.io.deq.bits, io.enq.bits)
  buffer.io.enq.valid := bufferInValid
  buffer.io.enq.bits := bufferInBits

  io.deq <> buffer.io.deq
  doubleQueue.io.enq.bits := io.enq.bits
  doubleQueue.io.enq.valid := io.enq.fire() && (!buffer.io.enq.ready || doubleQueueHasValues)
  doubleQueue.io.deq.ready := buffer.io.enq.ready

  val count = Wire(UInt(log2Up(entries + 1).W))
  val countNext = RegEnable(
    next = count,
    init = 0.U,
    enable = io.enq.fire() || io.deq.fire())
  when (io.enq.fire() && !io.deq.fire()) {
    assert(countNext < entries.U)
    count := countNext + 1.U
  }.elsewhen (!io.enq.fire() && io.deq.fire()) {
    assert(countNext > 0.U)
    count := countNext - 1.U
  }.otherwise {
    count := countNext
  }

  io.count := countNext
  io.enq.ready :=  countNext =/= entries.U
  io.deq.valid :=  countNext =/= 0.U
  assert(io.deq.valid === buffer.io.deq.valid)
  assert(io.enq.ready === buffer.io.enq.ready || doubleQueue.io.enq.ready)
}

class SyncQueue2PortMem[T <: Data](
    gen: T,
    val entries: Int,
    pipe: Boolean = false,
    flow: Boolean = false)
    extends Module() {

  val genType = gen

  val io = IO(new QueueIO(genType, entries))

  require (!pipe, "-F- Not supported")
  require (!flow, "-F- Not supported")

  if (entries < 4 ) {
    val queue = Module(new Queue(genType.asUInt, entries))
    io <> queue.io
  } else {
    val queue = Module(new SyncQueue2PortMemImpl(genType.asUInt, entries))
    io <> queue.io
  }

}

class SyncQueue2PortMemImpl[T <: Data](
    gen: T,
    val entries: Int,
    pipe: Boolean = false,
    flow: Boolean = false)
    extends Module() {

  require (!pipe, "-F- Not supported")
  require (!flow, "-F- Not supported")

  val genType = gen

  val io = IO(new QueueIO(genType, entries))

  require (entries > 3, "-F- TODO: small queue implemetation")
  val memoryQueue = Module(new OneCycleQueue(genType.asUInt, entries, ""))
  val buffer = Module(new Queue(genType.asUInt, 3))

  val memoryQueueHasValues = memoryQueue.io.count =/= 0.U
  val bufferInValid = Mux(memoryQueueHasValues, memoryQueue.io.deq.valid, io.enq.valid)
  val bufferInBits = Mux(memoryQueueHasValues, memoryQueue.io.deq.bits, io.enq.bits)
  buffer.io.enq.valid := bufferInValid
  buffer.io.enq.bits := bufferInBits

  io.deq <> buffer.io.deq
  memoryQueue.io.enq.bits := io.enq.bits
  memoryQueue.io.enq.valid := io.enq.fire() && (!buffer.io.enq.ready || memoryQueueHasValues)
  memoryQueue.io.deq.ready := buffer.io.enq.ready

  val count = Wire(UInt(log2Up(entries + 1).W))
  val countNext = RegEnable(
    next = count,
    init = 0.U,
    enable = io.enq.fire() || io.deq.fire())
  when (io.enq.fire() && !io.deq.fire()) {
    assert(countNext < entries.U)
    count := countNext + 1.U
  }.elsewhen (!io.enq.fire() && io.deq.fire()) {
    assert(countNext > 0.U)
    count := countNext - 1.U
  }.otherwise {
    count := countNext
  }

  io.count := countNext
  io.enq.ready :=  countNext =/= entries.U
  io.deq.valid :=  countNext =/= 0.U
  assert(io.deq.valid === buffer.io.deq.valid)
  assert(io.enq.ready === buffer.io.enq.ready || memoryQueue.io.enq.ready)
}

//combines two TwoCycle one-port memory queues into a queue
// with a latency 3
class DoubleQueue[T <: Data](
    gen: T,
    val entries: Int)
    extends Module() {

  val genType = gen

  val io = IO(new QueueIO(genType, entries))

  require(entries > 1, "Zero size not tested")
  val entriesRam0 = entries/2
  val entriesRam1 = entries - entriesRam0
  val queue0 = Module(new TwoCycleQueue(genType.asUInt, entriesRam0, "q0"))
  val queue1 = Module(new TwoCycleQueue(genType.asUInt, entriesRam1, "q1"))

  val enqRR = Wire(Bool())
  enqRR := RegEnable(
    next = ~enqRR,
    init = 1.U,
    enable = io.enq.fire())
  val deqRR = Wire(Bool())
  deqRR := RegEnable(
    next = ~deqRR,
    init = 1.U,
    enable = io.deq.fire())


  val do_enq0 = WireInit(io.enq.fire() && enqRR)
  val do_enq1 = WireInit(io.enq.fire() && ~enqRR)
  val deq0 = WireInit(io.deq.fire() && deqRR)
  val deq1 = WireInit(io.deq.fire() && ~deqRR)
  val do_deq0_next = RegNext(deq0 && do_enq0)
  val do_deq1_next = RegNext(deq1 && do_enq1)
  val do_deq0 = (deq0 && ~do_enq0) || do_deq0_next
  val do_deq1 = (deq1 && ~do_enq1) || do_deq1_next

  val do_deq = WireInit(io.deq.fire())
  val full  = !queue0.io.enq.ready && !queue1.io.enq.ready
  val empty = !queue0.io.deq.valid && !queue1.io.deq.valid

  queue0.io.enq.bits := io.enq.bits
  queue0.io.enq.valid := do_enq0
  queue1.io.enq.bits := io.enq.bits
  queue1.io.enq.valid := do_enq1
  queue0.io.deq.ready := do_deq0
  queue1.io.deq.ready := do_deq1

  io.deq.valid := !empty
  io.enq.ready := !full


  when(do_deq0) {
    assert(queue0.io.deq.valid, "-F- Deq empty queue 0")
  }
  when(do_deq1) {
    assert(queue1.io.deq.valid, "-F- Deq empty queue 1")
  }

  when(do_enq0) {
    assert(queue0.io.enq.ready, "-F- Enq full queue 0")
  }
  when(do_enq1) {
    assert(queue1.io.enq.ready, "-F- Enq full queue 1")
  }


  io.deq.bits := Mux(deqRR, queue0.io.deq.bits, queue1.io.deq.bits)
  io.count := queue0.io.count +& queue1.io.count
}

// one-port memory queue
// enq and deq should not overlap
// two subsequent enq should be cycle separated
// two subsequent deq can be next cycle
class TwoCycleQueue[T <: Data](
    gen: T,
    val entries: Int,
    val qname: String)
    extends Module() {

  val genType = gen

  val io = IO(new QueueIO(genType, entries))

  val ram0 = Module(new OnePortMem(genType.asUInt, entries, qname))
  val enq_ptr = Counter(entries)
  val deq_ptr = Counter(entries)
  val maybe_full = RegInit(false.B)


  val ptr_match = enq_ptr.value === deq_ptr.value
  val empty = ptr_match && !maybe_full
  val full = ptr_match && maybe_full

  val do_enq = WireInit(io.enq.fire())
  val do_deq = WireInit(io.deq.fire())

  // check protocol
  val enq_next = RegNext(do_enq)
  assert(!(enq_next && do_enq), "-F- Expecting two cycle behavior on enq")
  assert(!do_enq || !do_deq, "-F- No simultaneous R/W")

  when(do_deq) {
    deq_ptr.inc()
  }

  when(do_enq =/= do_deq) {
    maybe_full := do_enq
  }

  val firstRead = RegEnable(next = do_enq && io.count === 0.U, init = false.B, enable = true.B)
  io.deq.valid := !empty && !firstRead
  io.enq.ready := !full

  when (do_enq) {
    enq_ptr.inc()
  }

  val memAddr = Wire(enq_ptr.value.cloneType)
  memAddr := enq_ptr.value
  when(!do_enq) {
    when(firstRead) {// output the 1st written data
      memAddr := deq_ptr.value
    }.elsewhen (do_deq) {
      val wrap = deq_ptr.value === (entries - 1).U
      when (wrap) {
        memAddr := 0.U // initiate read of the next entry
      }.otherwise {
        memAddr := (deq_ptr.value + 1.U) // initiate read of the next entry
      }
    }.otherwise {
      memAddr := deq_ptr.value
    }
  }
  ram0.io.wr_en  := do_enq
  ram0.io.wr_data := io.enq.bits.asUInt
  ram0.io.ch_en := do_deq || firstRead || do_enq
  io.deq.bits := ram0.io.rd_data
  ram0.io.addr :=  memAddr



  val ptr_diff = enq_ptr.value - deq_ptr.value
  if (isPow2(entries)) {
    io.count := Mux(maybe_full && ptr_match, entries.U, 0.U) | ptr_diff
  } else {
    io.count := Mux(
      ptr_match,
      Mux(
        maybe_full,
        entries.asUInt, 0.U),
        Mux(
          deq_ptr.value > enq_ptr.value,
          entries.asUInt + ptr_diff, ptr_diff))
  }
}

class OneCycleQueue[T <: Data](
    gen: T,
    val entries: Int,
    val qname: String)
    extends Module() {

  val genType = gen

  val io = IO(new QueueIO(genType, entries))

  val ram0 = Module(new TwoPortMem(genType.asUInt, entries, qname))
  val enq_ptr = Counter(entries)
  val deq_ptr = Counter(entries)
  val maybe_full = RegInit(false.B)


  val ptr_match = enq_ptr.value === deq_ptr.value
  val empty = ptr_match && !maybe_full
  val full = ptr_match && maybe_full

  val do_enq = WireInit(io.enq.fire())
  val do_deq = WireInit(io.deq.fire())


  when(do_deq) {
    deq_ptr.inc()
  }

  when(do_enq =/= do_deq) {
    maybe_full := do_enq
  }

  when (do_enq) {
    enq_ptr.inc()
  }

  val firstRead = RegEnable(next = do_enq && io.count === 0.U, init = false.B, enable = true.B)
  io.deq.valid := !empty && !firstRead
  io.enq.ready := !full
  assert(!firstRead || !do_deq, "-F- Cannot have deq with first read as queue output is not valid yet")

  val rdAddr = Wire(enq_ptr.value.cloneType)
  when(firstRead) {// output the 1st written data
    rdAddr := deq_ptr.value
  }.elsewhen (do_deq) {
    val wrap = deq_ptr.value === (entries - 1).U
    when (wrap) {
      rdAddr := 0.U // initiate read of the next entry
    }.otherwise {
      rdAddr := (deq_ptr.value + 1.U) // initiate read of the next entry
    }
  }.otherwise {
    rdAddr := deq_ptr.value
  }
  ram0.io.wr_en  := do_enq
  ram0.io.wr_data := io.enq.bits.asUInt
  ram0.io.wr_addr := enq_ptr.value
  ram0.io.rd_en := do_deq || firstRead
  ram0.io.rd_addr := rdAddr
  io.deq.bits := ram0.io.rd_data



  val ptr_diff = enq_ptr.value - deq_ptr.value
  if (isPow2(entries)) {
    io.count := Mux(maybe_full && ptr_match, entries.U, 0.U) | ptr_diff
  } else {
    io.count := Mux(
      ptr_match,
      Mux(
        maybe_full,
        entries.asUInt, 0.U),
        Mux(
          deq_ptr.value > enq_ptr.value,
          entries.asUInt + ptr_diff, ptr_diff))
  }
}

// one-port memory implementation
class MemIO[T <: Data](gen: T, entries: Int) extends Bundle
{
  val wr_en   = Input(Bool())
  val wr_data = Input(gen.cloneType)
  val ch_en   = Input(Bool())
  val rd_data = Output(gen.cloneType)
  val addr    = Input(UInt(16.W)) // i dont care
  override def cloneType: this.type = new MemIO(gen, entries).asInstanceOf[this.type]
}
class OnePortMem[T <: Data](
    gen: T,
    val entries: Int,
    val qname: String)
    extends Module() {

  val genType = gen

  val io = IO(new MemIO(genType, entries))

  val mem = SyncReadMem(entries, genType.asUInt)

  // detected as one-port sync mem interface
  io.rd_data := DontCare
  when(io.ch_en) {
    val rdwrPort = mem(io.addr)
    when (io.wr_en) { rdwrPort := io.wr_data }
      .otherwise    { io.rd_data := rdwrPort }
  }
}

// two-port memory implementation
class MemIO2P[T <: Data](gen: T, entries: Int) extends Bundle
{
  val wr_en   = Input(Bool())
  val wr_addr = Input(UInt(16.W)) // i dont care
  val wr_data = Input(gen.cloneType)
  val rd_en   = Input(Bool())
  val rd_addr = Input(UInt(16.W)) // i dont care
  val rd_data = Output(gen.cloneType)
  override def cloneType: this.type = new MemIO2P(gen, entries).asInstanceOf[this.type]
}

class TwoPortMem[T <: Data](
    gen: T,
    val entries: Int,
    val qname: String)
    extends Module() {

  val genType = gen

  val io = IO(new MemIO2P(genType, entries))

  val mem = SyncReadMem(entries, genType.asUInt)

  when (io.wr_en ) {
    mem.write(io.wr_addr, io.wr_data.asUInt)
  }
  io.rd_data := DontCare
  when (io.rd_en)  {
    io.rd_data := mem.read(io.rd_addr, io.rd_en)
  }

}
