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
import chisel3.iotesters.PeekPokeTester
import vta.core._
import vta.util.config._

class MACTester(c: MAC) extends PeekPokeTester(c) {
  poke(c.io.a, -1)
  poke(c.io.b,  7)
  poke(c.io.c, 10)
  step(1)
  expect(c.io.y, 3)
  poke(c.io.a, -2)
  poke(c.io.b,  7)
  poke(c.io.c, 11)
  step(1)
  expect(c.io.y, -3)
}

class MACTest extends GenericTest("MACTest", (p:Parameters) => new MAC(),
  (c:MAC) => new MACTester(c))

class PipeAdderTester(c: PipeAdder) extends PeekPokeTester(c) {
  poke(c.io.a, -1)
  poke(c.io.b,  7)
  step(1)
  expect(c.io.y, 6)
  poke(c.io.a, -2)
  poke(c.io.b,  7)
  step(1)
  expect(c.io.y, 5)
}

class PipeAdderTest extends GenericTest("PipeAdderTest", (p:Parameters) => new PipeAdder(),
  (c:PipeAdder) => new PipeAdderTester(c))

class AdderTester(c: Adder) extends PeekPokeTester(c) {
  poke(c.io.a, -1)
  poke(c.io.b,  7)
  expect(c.io.y, 6)
  step(1)

  poke(c.io.a, -2)
  poke(c.io.b,  7)
  expect(c.io.y, 5)
  step(1)
}

class AdderTest extends GenericTest("AdderTest", (p:Parameters) => new Adder(),
  (c:Adder) => new AdderTester(c))

class DotProductTester(c: DotProduct) extends PeekPokeTester(c) {
  for {i<- 0 until 16} {
    poke(c.io.a(i), if (i %2 == 0) 1 else -1)
    poke(c.io.b(i), i)
  }
  step(1)
  for {i<- 0 until 16} {
    poke(c.io.a(i), if (i %2 == 1) 1 else -1)
    poke(c.io.b(i), i)
  }
  step(1)
  expect(c.io.y, -8)
  step(1)
  expect(c.io.y,  8)
}

class DotProductTest extends GenericTest("DotProductTest", (p:Parameters) => new DotProduct(),
  (c:DotProduct) => new DotProductTester(c))
