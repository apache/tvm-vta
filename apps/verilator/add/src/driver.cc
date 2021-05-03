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

#include "Top.h"
#include "verilator_device.h"

vluint64_t main_time = 0;
double sc_time_stamp() { return main_time; }

namespace tvm {
namespace runtime {
namespace contrib {

extern "C" VerilatorHandle VerilatorAlloc() {
  Top* top = new Top;
  return static_cast<VerilatorHandle>(top);
}

extern "C" void VerilatorDealloc(VerilatorHandle handle) { delete static_cast<Top*>(handle); }

extern "C" int VerilatorRead(VerilatorHandle handle, int id, int addr) {
  Top* top = static_cast<Top*>(handle);
  top->opcode = 2;
  top->id = id;
  top->addr = addr;
  top->eval();
  return top->out;
}

extern "C" void VerilatorWrite(VerilatorHandle handle, int id, int addr, int value) {
  Top* top = static_cast<Top*>(handle);
  top->opcode = 1;
  top->id = id;
  top->addr = addr;
  top->in = value;
  top->eval();
}

extern "C" void VerilatorReset(VerilatorHandle handle, int n) {
  Top* top = static_cast<Top*>(handle);
  top->opcode = 0;
  top->clock = 0;
  top->reset = 1;
  main_time = 0;
  while (!Verilated::gotFinish() && main_time < static_cast<vluint64_t>(n * 10)) {
    if ((main_time % 10) == 1) {
      top->clock = 1;
    }
    if ((main_time % 10) == 6) {
      top->reset = 0;
    }
    top->eval();
    main_time++;
  }
  top->reset = 0;
}

extern "C" void VerilatorRun(VerilatorHandle handle, int n) {
  Top* top = static_cast<Top*>(handle);
  top->opcode = 0;
  top->clock = 0;
  main_time = 0;
  while (!Verilated::gotFinish() && main_time < static_cast<vluint64_t>(n * 10)) {
    if ((main_time % 10) == 1) {
      top->clock = 1;
    }
    if ((main_time % 10) == 6) {
      top->clock = 0;
    }
    top->eval();
    main_time++;
  }
}

}  // namespace contrib
}  // namespace runtime
}  // namespace tvm
