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
import vta.util.config._
import chiseltest._
import chiseltest.iotesters._
import org.scalatest.flatspec.AnyFlatSpec
import vta.DefaultPynqConfig

class GenericTest[T <: Module, P <: PeekPokeTester[T], C <: Parameters](
    tag : String, dutFactory : (Parameters) => T, testerFactory : (T) => P
  ) extends AnyFlatSpec with ChiselScalatestTester {

  implicit val p: Parameters = new DefaultPynqConfig
  val defaultOpts = Seq(TreadleBackendAnnotation)

  behavior of tag
  it should "not have expect violations" in {
    test(dutFactory(p)).withAnnotations(defaultOpts).runPeekPoke(testerFactory)
  }
}
