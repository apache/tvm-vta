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
import vta.util._

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
class Fetch(debug: Boolean = false)(implicit p: Parameters) extends Module {
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

  val forceSimpleFetch = false // Force use original implementation of fetch

  if (forceSimpleFetch) {
    require (mp.dataBits <= 128, "-F- Simple VME data transfer doesnt support fetch data wider than instruction.")
  }

  if (mp.dataBits >= 128 && !forceSimpleFetch) {
    // wide cacheline
    val fetch = Module(new FetchWideVME(debug))
    io <> fetch.io
  } else {
    require(mp.dataBits == 64, "-F- Cannot make simple Fetch for more than 64 bit data read")
    val fetch = Module(new Fetch64Bit(debug)) // Simple
    io <> fetch.io
  }

}
