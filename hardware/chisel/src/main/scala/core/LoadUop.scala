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

/** UopMaster.
 *
 * Uop interface used by a master module, i.e. TensorAlu or TensorGemm,
 * to request a micro-op (uop) from the uop-scratchpad. The index (idx) is
 * used as an address to find the uop in the uop-scratchpad.
 */
class UopMaster(implicit p: Parameters) extends Bundle {
  val addrBits = log2Ceil(p(CoreKey).uopMemDepth)
  val idx = ValidIO(UInt(addrBits.W))
  val data = Flipped(ValidIO(new UopDecode))
  override def cloneType = new UopMaster().asInstanceOf[this.type]
}

/** UopClient.
 *
 * Uop interface used by a client module, i.e. LoadUop, to receive
 * a request from a master module, i.e. TensorAlu or TensorGemm.
 * The index (idx) is used as an address to find the uop in the uop-scratchpad.
 */
class UopClient(implicit p: Parameters) extends Bundle {
  val addrBits = log2Ceil(p(CoreKey).uopMemDepth)
  val idx = Flipped(ValidIO(UInt(addrBits.W)))
  val data = ValidIO(new UopDecode)
  override def cloneType = new UopClient().asInstanceOf[this.type]
}

/** LoadUopTop.
 *
 * Top wrapper of load uop implementations.
 */
class LoadUopTop(debug: Boolean = false)(implicit val p: Parameters) extends Module {
  val mp = p(ShellKey).memParams
  val io = IO(new Bundle {
    val start = Input(Bool())
    val done = Output(Bool())
    val inst = Input(UInt(INST_BITS.W))
    val baddr = Input(UInt(mp.addrBits.W))
    val vme_rd = new VMEReadMaster
    val uop = new UopClient
  })

  // force simple load uop implementation be careful if
  // define simple tensor load
  val forceSimpleLoadUop = false;

  if (forceSimpleLoadUop) {
    require(mp.dataBits == 64, "-F- Original LoadUop supports only 64 bit memory data transfer")

    val loadUop = Module(new LoadUopSimple(debug))

    loadUop.io.start := io.start
    io.done := loadUop.io.done
    loadUop.io.baddr := io.baddr
    loadUop.io.vme_rd <> io.vme_rd

    loadUop.io.dec := io.inst.asTypeOf(new MemDecode)
    loadUop.io.uop.idx <> io.uop.idx
    io.uop <> loadUop.io.uop

  } else {
    val loadUop = Module(new TensorLoad(tensorType = "uop"))
    loadUop.io.tensor.tieoffWrite()

    loadUop.io.start := io.start
    io.done := loadUop.io.done
    loadUop.io.baddr := io.baddr
    loadUop.io.vme_rd <> io.vme_rd

    loadUop.io.inst := io.inst
    require(loadUop.tp.splitWidth == 1 && loadUop.tp.splitLength == 1, "-F- UOP tensor split read is not expected")
    loadUop.io.tensor.rd(0).idx <> io.uop.idx
    io.uop.data.valid := loadUop.io.tensor.rd(0).data.valid
    io.uop.data.bits <> loadUop.io.tensor.rd(0).data.bits.asTypeOf(new UopDecode)

  }
}

