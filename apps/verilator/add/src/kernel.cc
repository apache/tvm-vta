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

#include <stdint.h>
#include <iostream>

#include "verilator_device.h"
#include "verilator_kernel.h"

#ifndef LANES
#define LANES 1
#endif

namespace tvm {
namespace runtime {
namespace contrib {

extern "C" void verilator_add(VerilatorHandle handle, int* left, int* right, int* out, int p_h_,
                              int p_w_) {
  for (int64_t i = 0; i < (p_h_ * p_w_ / LANES); ++i) {
    for (int64_t j = 0; j < LANES; ++j) {
      int64_t k = i * LANES + j;
      VerilatorWrite(handle, 1, j, left[k]);
      VerilatorWrite(handle, 2, j, right[k]);
    }
    VerilatorRun(handle, 1);
    for (int64_t j = 0; j < LANES; ++j) {
      int64_t k = i * LANES + j;
      out[k] = VerilatorRead(handle, 3, j);
    }
  }
}

extern "C" void verilator_bias_add(VerilatorHandle handle, int* data, int* bias, int* out, int p_n_,
                                   int p_c_, int p_h_, int p_w_) {
  int64_t round = p_c_ / LANES;
  if (p_c_ % LANES != 0) {
    round++;
  }
  for (int64_t i = 0; i < (p_n_ * p_h_ * p_w_); ++i) {
    for (int64_t j = 0; j < round; ++j) {
      for (int64_t k = 0; k < LANES; ++k) {
        int64_t l = j * LANES + k;
        int64_t m = i * p_c_ + l;
        if (l < p_c_) {
          VerilatorWrite(handle, 1, k, data[m]);
          VerilatorWrite(handle, 2, k, bias[l]);
        }
      }
      VerilatorRun(handle, 1);
      for (int64_t k = 0; k < LANES; ++k) {
        int64_t l = j * LANES + k;
        int64_t m = i * p_c_ + l;
        if (l < p_c_) {
          out[m] = VerilatorRead(handle, 3, k);
        }
      }
    }
  }
}

}  // namespace contrib
}  // namespace runtime
}  // namespace tvm
