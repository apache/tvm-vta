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

import chisel3.util._
import chisel3.iotesters.PeekPokeTester
import scala.util.Random
import unittest.util._
import vta.core._
import vta.util.config._

object Alu_ref {
  /* alu_ref
   *
   * This is a software function used as a reference for the hardware
   */
  def alu(opcode: Int, a: Array[Int], b: Array[Int], width: Int) : Array[Int] = {
    val size = a.length
    val mask = Helper.getMask(log2Ceil(width))
    val res = Array.fill(size) {0}

    if (opcode == 0) {
      for (i <- 0 until size) { // min
        res(i) = if (a(i) < b(i)) a(i) else b(i)
      }
    } else if (opcode == 1) { // max
      for (i <- 0 until size) {
        res(i) = if (a(i) < b(i)) b(i) else a(i)
      }
    } else if (opcode == 2) { // add
      for (i <- 0 until size) {
        res(i) = a(i) + b(i)
      }
    } else if (opcode == 3) { // right shift
      for (i <- 0 until size) {
        res(i) = a(i) >> (b(i) & mask).toInt
      }
    } else if (opcode == 4) { // left shift
      // HLS shift left by >> negative number
      // b always < 0 when opcode == 4
      for (i <- 0 until size) {
        res(i) = a(i) << ((-1*b(i)) & mask).toInt
      }
    } else { // default
      for (i <- 0 until size) {
        res(i) = 0
      }
    }
    res
  }
}

class AluVectorTester(c: AluVector, seed: Int = 47) extends PeekPokeTester(c) {
  val r = new Random(seed)

  val num_ops = ALU_OP_NUM
  for (op <- 0 until num_ops) {
    // generate data based on bits
    val bits = c.io.acc_a.tensorElemBits
    val dataGen = new RandomArray(c.blockOut, bits, r)
    val in_a = dataGen.any
    val in_b = if (op != 4) dataGen.any else dataGen.negative
    val mask = Helper.getMask(bits)
    val res = Alu_ref.alu(op, in_a, in_b, bits)

    for (i <- 0 until c.blockOut) {
      poke(c.io.acc_a.data.bits(0)(i), in_a(i) & mask)
      poke(c.io.acc_b.data.bits(0)(i), in_b(i) & mask)
    }
    poke(c.io.opcode, op)

    poke(c.io.acc_a.data.valid, 1)
    poke(c.io.acc_b.data.valid, 1)

    step(1)

    poke(c.io.acc_a.data.valid, 0)
    poke(c.io.acc_b.data.valid, 0)

    // wait for valid signal
    while (peek(c.io.acc_y.data.valid) == BigInt(0)) {
      step(1) // advance clock
    }
    if (peek(c.io.acc_y.data.valid) == BigInt(1)) {
      for (i <- 0 until c.blockOut) {
        expect(c.io.acc_y.data.bits(0)(i), res(i) & mask)
      }
    }
  }
}

class AluTest extends GenericTest("AluTest", (p:Parameters) =>
  new AluVector()(p), (c:AluVector) => new AluVectorTester(c, 48))
