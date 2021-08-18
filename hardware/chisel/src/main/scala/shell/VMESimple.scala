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

package vta.shell
import chisel3._
import chisel3.util._
import vta.util.config._
import vta.util.genericbundle._
import vta.interface.axi._


/** VTA Memory Engine (VME).
 *
 * This unit multiplexes the memory controller interface for the Core. Currently,
 * it supports single-writer and multiple-reader mode and it is also based on AXI.
 */
class VMESimple(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val mem = new AXIMaster(p(ShellKey).memParams)
    val vme = new VMEClient
  })

  val nReadClients = p(ShellKey).vmeParams.nReadClients
  val rd_arb = Module(new Arbiter(new VMECmd, nReadClients))
  val rd_arb_chosen = RegEnable(rd_arb.io.chosen, rd_arb.io.out.fire())

  for (i <- 0 until nReadClients) { rd_arb.io.in(i) <> io.vme.rd(i).cmd }

  val sReadIdle :: sReadAddr :: sReadData :: Nil = Enum(3)
  val rstate = RegInit(sReadIdle)

  switch(rstate) {
    is(sReadIdle) {
      when(rd_arb.io.out.valid) {
        rstate := sReadAddr
      }
    }
    is(sReadAddr) {
      when(io.mem.ar.ready) {
        rstate := sReadData
      }
    }
    is(sReadData) {
      when(io.mem.r.fire() && io.mem.r.bits.last) {
        rstate := sReadIdle
      }
    }
  }

  val sWriteIdle :: sWriteAddr :: sWriteData :: sWriteResp :: Nil = Enum(4)
  val wstate = RegInit(sWriteIdle)
  val addrBits = p(ShellKey).memParams.addrBits
  val lenBits = p(ShellKey).memParams.lenBits
  val wr_cnt = RegInit(0.U(lenBits.W))

  when(wstate === sWriteIdle) {
    wr_cnt := 0.U
  }.elsewhen(io.mem.w.fire()) {
    wr_cnt := wr_cnt + 1.U
  }

  switch(wstate) {
    is(sWriteIdle) {
      when(io.vme.wr(0).cmd.valid) {
        wstate := sWriteAddr
      }
    }
    is(sWriteAddr) {
      when(io.mem.aw.ready) {
        wstate := sWriteData
      }
    }
    is(sWriteData) {
      when(
        io.vme
          .wr(0)
          .data
          .valid && io.mem.w.ready && wr_cnt === io.vme.wr(0).cmd.bits.len) {
        wstate := sWriteResp
      }
    }
    is(sWriteResp) {
      when(io.mem.b.valid) {
        wstate := sWriteIdle
      }
    }
  }

  // registers storing read/write cmds

  val rd_len = RegInit(0.U(lenBits.W))
  val wr_len = RegInit(0.U(lenBits.W))
  val rd_addr = RegInit(0.U(addrBits.W))
  val wr_addr = RegInit(0.U(addrBits.W))

  when(rd_arb.io.out.fire()) {
    rd_len := rd_arb.io.out.bits.len
    rd_addr := rd_arb.io.out.bits.addr
  }

  when(io.vme.wr(0).cmd.fire()) {
    wr_len := io.vme.wr(0).cmd.bits.len
    wr_addr := io.vme.wr(0).cmd.bits.addr
  }

  // rd arb
  rd_arb.io.out.ready := rstate === sReadIdle

  val localTag = Reg(Vec(nReadClients, UInt(p(ShellKey).vmeParams.clientTagBitWidth.W)))
  // vme
  for (i <- 0 until nReadClients) {
    io.vme.rd(i).data.valid := rd_arb_chosen === i.asUInt & io.mem.r.valid
    io.vme.rd(i).data.bits.data := io.mem.r.bits.data
    io.vme.rd(i).data.bits.last := io.mem.r.bits.last
    io.vme.rd(i).data.bits.tag := localTag(i)

    when (io.vme.rd(i).cmd.fire()) {
      localTag(i) := io.vme.rd(i).cmd.bits.tag
    }
  }

  io.vme.wr(0).cmd.ready := wstate === sWriteIdle
  io.vme.wr(0).ack := io.mem.b.fire()
  io.vme.wr(0).data.ready := wstate === sWriteData & io.mem.w.ready

  // mem
  io.mem.aw.valid := wstate === sWriteAddr
  io.mem.aw.bits.addr := wr_addr
  io.mem.aw.bits.len := wr_len

  io.mem.w.valid := wstate === sWriteData & io.vme.wr(0).data.valid
  io.mem.w.bits.data := io.vme.wr(0).data.bits.data
  io.mem.w.bits.last := wr_cnt === io.vme.wr(0).cmd.bits.len
  io.mem.w.bits.strb := Fill(p(ShellKey).memParams.strbBits, true.B)

  io.mem.b.ready := wstate === sWriteResp

  io.mem.ar.valid := rstate === sReadAddr
  io.mem.ar.bits.addr := rd_addr
  io.mem.ar.bits.len := rd_len
  io.mem.ar.bits.id  := 0.U

  io.mem.r.ready := rstate === sReadData & io.vme.rd(rd_arb_chosen).data.ready

  // AXI constants - statically defined
  io.mem.setConst()
}
