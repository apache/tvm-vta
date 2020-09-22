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

#ifndef _INTELFOCL_VTA_H_
#define _INTELFOCL_VTA_H_

#include <vta/hw_spec_const.h>

#define BITS(x, o, w) ((x) >> (o) & ((1ULL << (w)) - 1))

#if VTA_LOG_INP_WIDTH != 3
#error Only 8-bit inputs are supported
#endif
#if VTA_LOG_WGT_WIDTH != 3
#error Only 8-bit weights are supported
#endif
#if VTA_LOG_ACC_WIDTH != 5
#error Only 32-bit accumulators are supported
#endif

typedef unsigned int    uop_T;
typedef char            inp_T;
typedef char            wgt_T;
typedef int             acc_T;
typedef int             sum_T;
typedef int             mul_T;
typedef char            out_T;

typedef struct
{
  ulong w[2];
} insn_T;

#define OPCODE_OFFSET           (0)
#define OPCODE_WIDTH            (VTA_OPCODE_BIT_WIDTH)
#define DEP_FLAGS_OFFSET        (OPCODE_OFFSET + OPCODE_WIDTH)
#define DEP_FLAGS_WIDTH         (4)
#define MEMORY_TYPE_OFFSET      (DEP_FLAGS_OFFSET + DEP_FLAGS_WIDTH)
#define MEMORY_TYPE_WIDTH       (VTA_MEMOP_ID_BIT_WIDTH)
#define SRAM_BASE_OFFSET        (MEMORY_TYPE_OFFSET + MEMORY_TYPE_WIDTH)
#define SRAM_BASE_WIDTH         (VTA_MEMOP_SRAM_ADDR_BIT_WIDTH)
#define DRAM_BASE_OFFSET        (SRAM_BASE_OFFSET + SRAM_BASE_WIDTH)
#define DRAM_BASE_WIDTH         (VTA_MEMOP_DRAM_ADDR_BIT_WIDTH)
#define Y_SIZE_OFFSET           (0)
#define Y_SIZE_WIDTH            (VTA_MEMOP_SIZE_BIT_WIDTH)
#define X_SIZE_OFFSET           (Y_SIZE_OFFSET + Y_SIZE_WIDTH)
#define X_SIZE_WIDTH            (VTA_MEMOP_SIZE_BIT_WIDTH)
#define X_STRIDE_OFFSET         (X_SIZE_OFFSET + X_SIZE_WIDTH)
#define X_STRIDE_WIDTH          (VTA_MEMOP_STRIDE_BIT_WIDTH)
#define Y_PAD_0_OFFSET          (X_STRIDE_OFFSET + X_STRIDE_WIDTH)
#define Y_PAD_0_WIDTH           (VTA_MEMOP_PAD_BIT_WIDTH)
#define Y_PAD_1_OFFSET          (Y_PAD_0_OFFSET + Y_PAD_0_WIDTH)
#define Y_PAD_1_WIDTH           (VTA_MEMOP_PAD_BIT_WIDTH)
#define X_PAD_0_OFFSET          (Y_PAD_1_OFFSET + Y_PAD_1_WIDTH)
#define X_PAD_0_WIDTH           (VTA_MEMOP_PAD_BIT_WIDTH)
#define X_PAD_1_OFFSET          (X_PAD_0_OFFSET + X_PAD_0_WIDTH)
#define X_PAD_1_WIDTH           (VTA_MEMOP_PAD_BIT_WIDTH)
#define RESET_OFFSET            (DEP_FLAGS_OFFSET + DEP_FLAGS_WIDTH)
#define RESET_WIDTH             (1)
#define UOP_BGN_OFFSET          (RESET_OFFSET + RESET_WIDTH)
#define UOP_BGN_WIDTH           (VTA_LOG_UOP_BUFF_DEPTH)
#define UOP_END_OFFSET          (UOP_BGN_OFFSET + UOP_BGN_WIDTH)
#define UOP_END_WIDTH           (VTA_LOG_UOP_BUFF_DEPTH + 1)
#define ITER_OUT_OFFSET         (UOP_END_OFFSET + UOP_END_WIDTH)
#define ITER_OUT_WIDTH          (VTA_LOOP_ITER_WIDTH)
#define ITER_IN_OFFSET          (ITER_OUT_OFFSET + ITER_OUT_WIDTH)
#define ITER_IN_WIDTH           (VTA_LOOP_ITER_WIDTH)
#define DST_FAC_OUT_OFFSET      (0)
#define DST_FAC_OUT_WIDTH       (VTA_LOG_ACC_BUFF_DEPTH)
#define DST_FAC_IN_OFFSET       (DST_FAC_OUT_OFFSET + DST_FAC_OUT_WIDTH)
#define DST_FAC_IN_WIDTH        (VTA_LOG_ACC_BUFF_DEPTH)
#define GSRC_FAC_OUT_OFFSET     (DST_FAC_IN_OFFSET + DST_FAC_IN_WIDTH)
#define GSRC_FAC_OUT_WIDTH      (VTA_LOG_INP_BUFF_DEPTH)
#define GSRC_FAC_IN_OFFSET      (GSRC_FAC_OUT_OFFSET + GSRC_FAC_OUT_WIDTH)
#define GSRC_FAC_IN_WIDTH       (VTA_LOG_INP_BUFF_DEPTH)
#define ASRC_FAC_OUT_OFFSET     (DST_FAC_IN_OFFSET + DST_FAC_IN_WIDTH)
#define ASRC_FAC_OUT_WIDTH      (VTA_LOG_ACC_BUFF_DEPTH)
#define ASRC_FAC_IN_OFFSET      (ASRC_FAC_OUT_OFFSET + ASRC_FAC_OUT_WIDTH)
#define ASRC_FAC_IN_WIDTH       (VTA_LOG_ACC_BUFF_DEPTH)
#define WGT_FAC_OUT_OFFSET      (GSRC_FAC_IN_OFFSET + GSRC_FAC_IN_WIDTH)
#define WGT_FAC_OUT_WIDTH       (VTA_LOG_WGT_BUFF_DEPTH)
#define WGT_FAC_IN_OFFSET       (WGT_FAC_OUT_OFFSET + WGT_FAC_OUT_WIDTH)
#define WGT_FAC_IN_WIDTH        (VTA_LOG_WGT_BUFF_DEPTH)
#define ALU_OPCODE_OFFSET       (ASRC_FAC_IN_OFFSET + ASRC_FAC_IN_WIDTH)
#define ALU_OPCODE_WIDTH        (VTA_ALU_OPCODE_BIT_WIDTH)
#define USE_IMM_OFFSET          (ALU_OPCODE_OFFSET + ALU_OPCODE_WIDTH)
#define USE_IMM_WIDTH           (1)
#define IMM_OFFSET              (USE_IMM_OFFSET + USE_IMM_WIDTH)
#define IMM_WIDTH               (VTA_ALUOP_IMM_BIT_WIDTH)

#define UOP_DST_OFFSET          (0)
#define UOP_DST_WIDTH           (VTA_LOG_ACC_BUFF_DEPTH)
#define UOP_SRC_OFFSET          (UOP_DST_OFFSET + UOP_DST_WIDTH)
#define UOP_SRC_WIDTH           (VTA_LOG_ACC_BUFF_DEPTH)
#define UOP_WGT_OFFSET          (UOP_SRC_OFFSET + UOP_SRC_WIDTH)
#define UOP_WGT_WIDTH           (VTA_LOG_WGT_BUFF_DEPTH)

#endif /* _INTELFOCL_VTA_H_ */
