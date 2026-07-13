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
import chiseltest._
import chiseltest.iotesters._
import scala.collection.mutable.ArrayBuffer
import vta.util.config._
import vta.core.TensorStoreNarrowVME

/** Regression test for the TensorStoreNarrowVME sub-block-select (`tag`) wrap.
 *
 * The store engine streams each output element to DRAM as `numMemBlock` 64-bit
 * sub-blocks selected by `tag`.  If the `tag` wrap is not gated on the write
 * handshake `io.vme_wr.data.fire`, then when the AXI slave de-asserts `WREADY`
 * at the high-half (last sub-block) beat, `tag` rolls back to the low half while
 * the FSM still holds `sWriteData` (so `WVALID` stays high).  That
 *   (P1) changes `WDATA` while `WVALID && !WREADY` -- an AXI4 write-data channel
 *        stability violation (AMBA AXI, ARM IHI 0022, handshake process); and
 *   (P2) re-presents / re-accepts the low half, shifting the accepted write
 *        stream by one 64-bit beat.
 *
 * This test drives a real 1D store of two 128-bit elements, de-asserts `WREADY`
 * for a few cycles exactly at the high-half beat, and checks both properties.
 * It is written so that the UNFIXED RTL fails (WDATA changes under back-pressure
 * and the low half is accepted twice) while the fixed RTL passes.
 */
class TestTensorStoreNarrowVMEWReadyStall(c: TensorStoreNarrowVME)
    extends PeekPokeTester(c) {

  // One MemDecode word: ysize=1, xsize=4, xstride=4, all offsets 0.
  // (bits: op/dep/id/sram_offset/dram_offset in [0..63], ysize[79:64]=1,
  //  xsize[95:80]=4, xstride[111:96]=4.)
  val inst: BigInt = (BigInt(0x400040001L) << 64)

  // Two elements of 16 distinct bytes each; low/high 64-bit halves are the
  // sub-blocks streamed at tag=0 / tag=1.
  val elem0Base = 0x10
  val elem1Base = 0x40
  def halfOf(base: Int, hi: Boolean): BigInt = {
    var v = BigInt(0)
    val lo = if (hi) 8 else 0
    for (k <- 0 until 8) v |= BigInt(base + lo + k) << (8 * k)
    v
  }
  val LOW0 = halfOf(elem0Base, hi = false)
  val HIGH0 = halfOf(elem0Base, hi = true)

  def tieInputs(): Unit = {
    poke(c.io.start, 0)
    poke(c.io.inst, 0)
    poke(c.io.baddr, 0)
    poke(c.io.vme_wr.cmd.ready, 0)
    poke(c.io.vme_wr.data.ready, 0)
    poke(c.io.vme_wr.ack, 0)
    poke(c.io.tensor.wr(0).valid, 0)
  }

  def loadElem(idx: Int, base: Int): Unit = {
    poke(c.io.tensor.wr(0).valid, 1)
    poke(c.io.tensor.wr(0).bits.idx, idx)
    for (k <- 0 until 16) poke(c.io.tensor.wr(0).bits.data(0)(k), base + k)
    step(1)
    poke(c.io.tensor.wr(0).valid, 0)
  }

  // --- Setup: fill the out-scratchpad with two elements, then issue the store.
  tieInputs()
  step(1)
  loadElem(0, elem0Base)
  loadElem(1, elem1Base)
  for (_ <- 0 until 4) step(1) // let the write pipeline drain before start

  poke(c.io.inst, inst)
  poke(c.io.baddr, 0)
  poke(c.io.start, 1)
  step(1)
  poke(c.io.start, 0)

  // --- Run the write burst.  Accept every beat except a 3-cycle WREADY stall
  // injected on the beat right after the first accepted beat (= the high half
  // of element 0).  cmd.ready and ack are held high so the FSM progresses.
  val accepted = ArrayBuffer[BigInt]()
  var stabilityViolations = 0
  var stallArmed = true
  var stalling = false
  var stallLeft = 0
  var stallHappened = false
  var prevValid = false
  var prevReady = false
  var prevData = BigInt(0)

  for (_ <- 0 until 60) {
    val valid = peek(c.io.vme_wr.data.valid) != 0
    val data = peek(c.io.vme_wr.data.bits.data)

    // P1: if the previous cycle presented a beat that was NOT accepted
    // (valid && !ready), WDATA must be unchanged this cycle while still valid.
    if (prevValid && !prevReady && valid && data != prevData) {
      stabilityViolations += 1
    }

    // Decide WREADY for this cycle: stall the beat right after the 1st accept.
    var ready = true
    if (stallArmed && valid && accepted.length == 1 && !stalling) {
      stalling = true; stallLeft = 3; stallArmed = false; stallHappened = true
    }
    if (stalling) {
      ready = false
      stallLeft -= 1
      if (stallLeft == 0) stalling = false
    }
    poke(c.io.vme_wr.data.ready, if (ready) 1 else 0)
    poke(c.io.vme_wr.cmd.ready, 1)
    poke(c.io.vme_wr.ack, 1)

    val fired = valid && ready
    prevValid = valid; prevReady = ready; prevData = data
    step(1)
    if (fired) accepted += data
  }

  // --- Non-vacuity: the stall must have actually landed, and a real multi-beat
  // store must have happened.
  require(stallHappened, "-F- test vacuous: WREADY stall never triggered")
  require(accepted.length >= 4,
    s"-F- test vacuous: only ${accepted.length} beats accepted, expected a real burst")

  println(s"accepted beats: ${accepted.map(b => f"0x$b%016x").mkString(", ")}")
  println(s"AXI W-stability violations: $stabilityViolations")

  // P1: no WDATA change under WVALID && !WREADY.
  expect(stabilityViolations == 0,
    "AXI4 W-channel stability: WDATA must hold while WVALID && !WREADY")
  // P2: the low half must not be accepted twice in a row (high-half stutter).
  //     On the fix, the first two accepted beats are LOW0 then HIGH0.
  expect(!(accepted(0) == LOW0 && accepted(1) == LOW0),
    "high-half stutter: low half accepted twice in a row")
  expect(accepted(0) == LOW0 && accepted(1) == HIGH0,
    "accepted stream must be LOW0 then HIGH0 (no stutter)")
}

class TensorStoreNarrowVMEWReadyStallTest extends GenericTest(
  "TensorStoreNarrowVME",
  (p: Parameters) => new TensorStoreNarrowVME("out")(p),
  (c: TensorStoreNarrowVME) => new TestTensorStoreNarrowVMEWReadyStall(c))
