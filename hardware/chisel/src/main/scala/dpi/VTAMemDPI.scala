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

package vta.dpi

import chisel3._
import chisel3.util._
import chisel3.experimental.IntParam
import vta.util.config._
import vta.interface.axi._
import vta.shell._

/** Memory DPI parameters */
case class VTAMemDPIParams(
    dpiDelay  : Int,
    dpiLenBits: Int,
    dpiAddrBits: Int,
    dpiDataBits: Int,
    dpiTagBits: Int
) {}
case object DpiKey extends Field[VTAMemDPIParams]

/** Memory master interface.
 *
 * This interface is tipically used by the Accelerator
 */

class MemRequest(implicit val p: Parameters) extends Bundle {
  val len =   (UInt(p(ShellKey).memParams.dataBits.W))
  val addr = (UInt(p(ShellKey).memParams.addrBits.W))
  val id = (UInt(p(ShellKey).memParams.idBits.W))
}

class VTAMemDPIData(implicit val p: Parameters) extends Bundle {
  val data = UInt(p(ShellKey).memParams.dataBits.W)
  val id   = UInt(p(ShellKey).memParams.idBits.W)
  override def cloneType =
  new VTAMemDPIData().asInstanceOf[this.type]
}

class VTAMemDPIWrData(implicit val p: Parameters) extends Bundle {
  val data = UInt(p(ShellKey).memParams.dataBits.W)
  val strb = UInt((p(ShellKey).memParams.dataBits/8).W)
  override def cloneType =
  new VTAMemDPIWrData().asInstanceOf[this.type]
}


class VTAMemDPIMaster(implicit val p: Parameters) extends Bundle {
  val req = new Bundle {
    val ar_valid = Output(Bool())
    val ar_len =   Output(UInt(p(ShellKey).memParams.lenBits.W))
    val ar_addr = Output(UInt(p(ShellKey).memParams.addrBits.W))
    val ar_id   = Output(UInt(p(ShellKey).memParams.idBits.W))
    val aw_valid = Output(Bool())
    val aw_addr = Output(UInt(p(ShellKey).memParams.addrBits.W))
    val aw_len  = Output(UInt(p(ShellKey).memParams.lenBits.W))
  }
  val wr = ValidIO(new VTAMemDPIWrData)
  val rd = Flipped(Decoupled(new VTAMemDPIData))
}

/** Memory client interface.
 *
 * This interface is tipically used by the Host
 */
class VTAMemDPIClient(implicit val p: Parameters) extends Bundle {
  val req = new Bundle {
    val ar_valid = Input(Bool())
    val ar_len =   Input(UInt(p(ShellKey).memParams.lenBits.W))
    val ar_addr = Input(UInt(p(ShellKey).memParams.addrBits.W))
    val ar_id   = Input(UInt(p(ShellKey).memParams.idBits.W))
    val aw_valid = Input(Bool())
    val aw_addr = Input(UInt(p(ShellKey).memParams.addrBits.W))
    val aw_len  = Input(UInt(p(ShellKey).memParams.lenBits.W))
  }
  val wr = Flipped(ValidIO(new VTAMemDPIWrData))
  val rd = (Decoupled(new VTAMemDPIData))
}

/** Memory DPI module.
 *
 * Wrapper for Memory Verilog DPI module.
 */
class VTAMemDPI(implicit val p: Parameters) extends BlackBox(
  Map(
    "LEN_BITS" -> IntParam(p(ShellKey).memParams.lenBits),
    "ADDR_BITS" -> IntParam(p(ShellKey).memParams.addrBits),
    "DATA_BITS" -> IntParam(p(ShellKey).memParams.dataBits))) with HasBlackBoxResource {

  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Reset())
    val dpi = new VTAMemDPIClient
  })
  addResource("/verilog/VTAMemDPI.v")
}

class VTAMemDPIToAXI(debug: Boolean = true)(implicit val p: Parameters) extends Module {
  val io = IO(new Bundle {
    val dpi = new VTAMemDPIMaster
    val axi = new AXIClient(p(ShellKey).memParams)
  })
  //Read request interface for sw memory manager
  val ar_valid = RegInit(false.B)
  val ar_len = RegInit(0.U.asTypeOf(chiselTypeOf(io.dpi.req.ar_len)))
  val ar_addr = RegInit(0.U.asTypeOf(chiselTypeOf(io.dpi.req.ar_addr)))
  val ar_id   = RegInit(0.U.asTypeOf(chiselTypeOf(io.dpi.req.ar_len)))
  val rd_data = RegInit(0.U.asTypeOf(chiselTypeOf(io.dpi.rd.bits.data)))
  val rIdle :: readData :: Nil = Enum(2)
  val rstate = RegInit(rIdle)
  //Write request interface for sw memomry manager
  val aw_valid = RegInit(false.B)
  val aw_len = RegInit(0.U.asTypeOf(chiselTypeOf(io.dpi.req.aw_len)))
  val aw_addr = RegInit(0.U.asTypeOf(chiselTypeOf(io.dpi.req.aw_addr)))
  val wIdle :: writeAddress :: writeData :: writeResponse :: Nil = Enum(4)
  val wstate = RegInit(wIdle)
  //Read Interface to Memory Manager
  val counter = RegInit(0.U(32.W))
  val dpiDelay = 16.U
  val dpiReqQueue = Module(new Queue(new MemRequest, 256))
  dpiReqQueue.io.enq.valid     := io.axi.ar.valid  & dpiReqQueue.io.enq.ready
  dpiReqQueue.io.enq.bits.addr := io.axi.ar.bits.addr
  dpiReqQueue.io.enq.bits.len  := io.axi.ar.bits.len
  dpiReqQueue.io.enq.bits.id   := io.axi.ar.bits.id

  when(dpiReqQueue.io.deq.valid && counter < dpiDelay){
    counter := counter + 1.U
  }.elsewhen(dpiReqQueue.io.deq.valid && counter === dpiDelay){
    counter := counter
  }.otherwise{
    counter := 0.U
  }

  switch(rstate){
    is(rIdle){
      when(dpiReqQueue.io.deq.valid && dpiReqQueue.io.deq.bits.len =/=0.U && counter === dpiDelay){
        rstate := readData
      }
    }
    is(readData) {
      when(io.axi.r.ready && io.dpi.rd.valid && ar_len === 1.U) {
        rstate := rIdle
      }
    }
  }
  when(rstate === rIdle) {
    when(dpiReqQueue.io.deq.ready){
      ar_len :=  dpiReqQueue.io.deq.bits.len
      ar_addr := dpiReqQueue.io.deq.bits.addr
      ar_id   := dpiReqQueue.io.deq.bits.id
    }
  }
  .elsewhen(rstate === readData){
    when(io.axi.r.ready && io.dpi.rd.valid && ar_len =/= 0.U){
      ar_len := ar_len - 1.U
    }
  }
dpiReqQueue.io.deq.ready :=  ((dpiReqQueue.io.deq.valid && (rstate === rIdle)) && (counter === dpiDelay))
when(rstate === rIdle && dpiReqQueue.io.deq.valid){
  io.dpi.req.ar_len  := dpiReqQueue.io.deq.bits.len
  io.dpi.req.ar_addr := dpiReqQueue.io.deq.bits.addr
  io.dpi.req.ar_id   := dpiReqQueue.io.deq.bits.id
  io.dpi.req.ar_valid := dpiReqQueue.io.deq.ready
  }.otherwise{
    io.dpi.req.ar_len  := ar_len
    io.dpi.req.ar_addr := ar_addr
    io.dpi.req.ar_id   := ar_id
    io.dpi.req.ar_valid  := (dpiReqQueue.io.deq.ready)
  }
  io.axi.ar.ready := dpiReqQueue.io.enq.ready
  io.axi.r.valid := io.dpi.rd.valid
  io.axi.r.bits.data := io.dpi.rd.bits.data
  io.axi.r.bits.last := (ar_len === 0.U && io.dpi.rd.valid)
  io.axi.r.bits.resp := 0.U
  io.axi.r.bits.user := 0.U
  io.axi.r.bits.id := io.dpi.rd.bits.id
  io.dpi.rd.ready  := io.axi.r.ready

  //Write Request
  switch(wstate){
    is(wIdle){
      when(io.axi.aw.valid){
        wstate := writeAddress
      }
    }
    is(writeAddress) {
      when(io.axi.aw.valid) {
        wstate := writeData
      }
    }
    is(writeData) {
      when(io.axi.w.valid && io.axi.w.bits.last) {
        wstate := writeResponse
      }
    }
    is(writeResponse) {
      when(io.axi.b.ready) {
        wstate := wIdle
      }
    }
  }
  when(wstate === wIdle){
    when(io.axi.aw.valid){
      aw_len := io.axi.aw.bits.len
      aw_addr := io.axi.aw.bits.addr
    }
  }
  io.dpi.req.aw_addr := aw_addr
  io.dpi.req.aw_len  := aw_len
  io.dpi.req.aw_valid := RegNext(io.axi.aw.valid) & (wstate === writeAddress)
  io.axi.aw.ready := wstate === writeAddress
  io.dpi.wr.valid := wstate === writeData & io.axi.w.valid
  io.dpi.wr.bits.data := io.axi.w.bits.data
  io.dpi.wr.bits.strb := io.axi.w.bits.strb
  io.axi.w.ready := wstate === writeData

  io.axi.b.valid := wstate === writeResponse
  io.axi.b.bits.resp := 0.U
  io.axi.b.bits.user := 0.U
  io.axi.b.bits.id := 0.U
}
