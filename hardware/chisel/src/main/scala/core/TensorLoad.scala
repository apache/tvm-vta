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

import chisel3._
import chisel3.util._
import vta.util.config._
import vta.shell._



/** TensorLoad.
 *
 * Load 1D and 2D tensors from main memory (DRAM) to input/weight
 * scratchpads (SRAM). Also, there is support for zero padding, while
 * doing the load. Zero-padding works on the y and x axis, and it is
 * managed by TensorPadCtrl. The TensorDataCtrl is in charge of
 * handling the way tensors are stored on the scratchpads.
 */
class TensorLoad(tensorType: String = "none", debug: Boolean = false)(
    implicit p: Parameters)
    extends Module {
  val tp = new TensorParams(tensorType)
  val mp = p(ShellKey).memParams
  val io = IO(new Bundle {
    val start = Input(Bool())
    val done = Output(Bool())
    val inst = Input(UInt(INST_BITS.W))
    val baddr = Input(UInt(mp.addrBits.W))
    val vme_rd = new VMEReadMaster
    val tensor = new TensorClient(tensorType)
  })

  override def desiredName = "TensorLoad" + tensorType.capitalize

  val forceSimpleTensorLoad = false // force a simple implemetation of TL

  if (forceSimpleTensorLoad) {
    // use
    val tensorLoad = Module(new TensorLoadSimple(tensorType, debug))
    io <> tensorLoad.io
  } else if (mp.dataBits >= tp.tensorSizeBits) {
    // cacheline is wider than tensor size,
    // macro memory bitwidth by cache size
    // bank by tansor size
    val tensorLoad = Module(new TensorLoadWideVME(tensorType, debug))
    io <> tensorLoad.io
  } else {
    // tensor is wider than cacheline, bank by
    // macro memory bitwidth by tansor size
    // bank by cacheline size
    val tensorLoad = Module(new TensorLoadNarrowVME(tensorType, debug))
    io <> tensorLoad.io
  }
}

