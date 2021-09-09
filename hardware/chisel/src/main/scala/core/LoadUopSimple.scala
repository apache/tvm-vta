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

class LoadUopSimple(debug: Boolean = false)(implicit val p: Parameters) extends Module {
  val mp = p(ShellKey).memParams
  val io = IO(new Bundle {
    val start = Input(Bool())
    val done = Output(Bool())
    val dec = Input(new MemDecode)
    val baddr = Input(UInt(mp.addrBits.W))
    val vme_rd = new VMEReadMaster
    val uop = new UopClient
  })
  val uopsPerMemXfer = p(ShellKey).memParams.dataBits / p(CoreKey).uopBits
  require(p(ShellKey).memParams.dataBits % p(CoreKey).uopBits == 0)

  val uopBits = p(CoreKey).uopBits
  val uopBytes = uopBits / 8
  val uopDepth = p(CoreKey).uopMemDepth / uopsPerMemXfer
  val dataBytes = mp.dataBits / 8

  val dec = io.dec
  val raddr = Reg(chiselTypeOf(io.vme_rd.cmd.bits.addr))
  val xcnt = Reg(chiselTypeOf(io.vme_rd.cmd.bits.len))
  val xlen = Reg(chiselTypeOf(io.vme_rd.cmd.bits.len))
  val xrem = Reg(chiselTypeOf(dec.xsize))
  val xmax = (1 << mp.lenBits).U
  val xmax_bytes = ((1 << mp.lenBits) * dataBytes).U
  // Align DRAM address to data and do not cross page boundary.
  val data_align_bits = WireInit(UInt(raddr.getWidth.W), dataBytes.U - 1.U)
  val beat_bytes_bits = log2Ceil(mp.dataBits >> 3)
  val xfer_bytes = Reg(chiselTypeOf(xmax_bytes))
  // DRAM address width must be the same as AXI araddr since anything above will
  // be silently truncated, enforce this here.
  val dram_byte_addr = WireInit(UInt(raddr.getWidth.W), dec.dram_offset << log2Ceil(uopBytes))
  // Here we are assuming io.baddr | dram_byte_addr === io.baddr + dram_byte_addr.
  val unaligned_addr = io.baddr | dram_byte_addr
  val xfer_init_addr = unaligned_addr & ~data_align_bits
  val xfer_next_addr = raddr + xfer_bytes
  val xfer_init_bytes = xmax_bytes - xfer_init_addr % xmax_bytes
  val xfer_init_beats = xfer_init_bytes >> beat_bytes_bits
  val xfer_next_bytes = xmax_bytes - xfer_next_addr % xmax_bytes
  val xfer_next_beats = xfer_next_bytes >> beat_bytes_bits

  val dram_even = (dec.dram_offset % 2.U) === 0.U
  val sram_even = (dec.sram_offset % 2.U) === 0.U
  val sizeIsEven = (dec.xsize % 2.U) === 0.U

  val sIdle :: sReadCmd :: sReadData :: Nil = Enum(3)
  val state = RegInit(sIdle)
  val first = RegInit(init=false.B)

  // control
  switch(state) {
    is(sIdle) {
      xfer_bytes := xfer_init_bytes
      when(io.start) {
        state := sReadCmd
        first := true.B
        raddr := xfer_init_addr
        // Number of total beats in the load transfer.
        val xsize = if (uopsPerMemXfer == 1) {
          dec.xsize
        } else {
          ((dec.xsize +& 1.U + dec.dram_offset(0)) >> 1)
        }

        when(xsize <= xfer_init_beats) {
          xlen := xsize - 1.U
          xrem := 0.U
        }.otherwise {
          xlen := xfer_init_beats - 1.U
          xrem := xsize - xfer_init_beats
        }
      }
    }
    is(sReadCmd) {
      when(io.vme_rd.cmd.ready) {
        state := sReadData
      }
    }
    is(sReadData) {
      when(io.vme_rd.data.valid) {
        when(xcnt === xlen) {
          when(xrem === 0.U) {
            state := sIdle
          }.otherwise {
            state := sReadCmd
            raddr := xfer_next_addr
            xfer_bytes := xfer_next_bytes
            when(xrem <= xfer_next_beats) {
              xlen := xrem - 1.U
              xrem := 0.U
            }.otherwise {
              xlen := xfer_next_beats - 1.U
              xrem := xrem - xfer_next_beats
            }
          }
        }
      }
    }
  }

  // read-from-dram
  io.vme_rd.cmd.valid := state === sReadCmd
  io.vme_rd.cmd.bits.addr := raddr
  io.vme_rd.cmd.bits.len := xlen
  io.vme_rd.cmd.bits.tag := dec.sram_offset

  io.vme_rd.data.ready := state === sReadData

  when(state =/= sReadData) {
    xcnt := 0.U
  }.elsewhen(io.vme_rd.data.fire()) {
    xcnt := xcnt + 1.U
  }

  val waddr = IndexedSeq.fill(uopsPerMemXfer) { Reg(UInt(log2Ceil(uopDepth).W))}
  when(state === sIdle) {
    val so = dec.sram_offset >> log2Ceil(uopsPerMemXfer)
    if (uopsPerMemXfer == 1) {
      waddr(0) := so
    } else {
      when (!sram_even &&  dram_even) { // 10
        waddr(0) := so + 1.U
        waddr(1) := so
      }.elsewhen (sram_even && !dram_even) { // 01
        waddr(0) := so
        waddr(1) := so - 1.U
      }.otherwise {
        waddr(0) := so
        waddr(1) := so
      }
    }
  }.elsewhen(io.vme_rd.data.fire()) {
    for (i <- 0 until uopsPerMemXfer) {
      waddr(i) := waddr(i) + 1.U
    }
  }

  val mems = IndexedSeq.fill(uopsPerMemXfer) { SyncReadMem(uopDepth, UInt(uopBits.W))}
  val last = (xcnt === xlen) && (xrem === 0.U)

  val wmask = Wire(Vec(uopsPerMemXfer, Bool()))
  for (i <- 0 until uopsPerMemXfer) {
    wmask(i) := true.B
  }

  when (io.vme_rd.data.fire()) {
    when (first) {
      first := false.B

      if (uopsPerMemXfer == 2) {
        when(!sram_even && !dram_even) {
          wmask(0) := false.B
        }
      }
    }
    when(last) {
      if (uopsPerMemXfer == 2) {
        when(dram_even ^ sizeIsEven) {
          when (sram_even ^ sizeIsEven) {
            wmask(1) := false.B
          }.otherwise{
            wmask(0) := false.B
          }
        }
      }
    }
  }

  val wdata = Wire(Vec(uopsPerMemXfer, UInt(uopBits.W)))
  wdata := io.vme_rd.data.bits.data.asTypeOf(wdata)
  if (uopsPerMemXfer == 2) {
    when(dram_even =/= sram_even) { // swap
      wdata(0) := io.vme_rd.data.bits.data.asTypeOf(wdata)(1)
      wdata(1) := io.vme_rd.data.bits.data.asTypeOf(wdata)(0)
    }
  }

  when(io.vme_rd.data.fire()) {
    for { i <- 0 until mems.size} {
      when (wmask(i)) {
        mems(i).write(waddr(i), wdata(i))
      }
    }
  }

  io.done := io.vme_rd.data.fire() & last

  // ----------- read-from-sram -------------

  io.uop.data.valid := RegNext(io.uop.idx.valid)

  // delay LSB of idx by a cycle because of the one-cycle memory read latency
  val rIdx = io.uop.idx.bits >> log2Ceil(uopsPerMemXfer)
  val m0 = mems(0).read(rIdx, io.uop.idx.valid)

  if (uopsPerMemXfer == 2) {
    val m1 = mems(1).read(rIdx, io.uop.idx.valid)
    val sIdx = RegNext(io.uop.idx.bits % uopsPerMemXfer.U)
    io.uop.data.bits <> Mux(sIdx =/= 0.U, m1, m0).asTypeOf(io.uop.data.bits)
  } else {
    io.uop.data.bits <> m0.asTypeOf(io.uop.data.bits)
  }

  if (false) {
    // Report initial part of the uop state after
    //   the clock transition where io.done is high
    val memDumpGuard = RegNext(io.done,init=false.B)
    when (memDumpGuard) {
      for {
        idx <- 0 until scala.math.min(8,uopDepth)
        i <- 0 until uopsPerMemXfer} {
        val s = mems(i)(idx).asTypeOf(io.uop.data.bits)
        printf(s"uop: $idx $i u0: %x u1: %x u2: %x\n", s.u0, s.u1, s.u2)
      }
    }
  }

  // debug
  if (debug) {
    when(io.vme_rd.cmd.fire()) {
      printf("[LoadUop] cmd addr:%x len:%x rem:%x\n", raddr, xlen, xrem)
    }
  }
}
