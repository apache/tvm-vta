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

#ifndef _3RDPARTY_VTA_HW_SRC_INTELFOCL_INTELFOCL_DEVICE_H_
#define _3RDPARTY_VTA_HW_SRC_INTELFOCL_INTELFOCL_DEVICE_H_

#include <list>
#include <string>
#include "CL/opencl.h"

#define IFOCL_MEM_OFF_ERR (SIZE_MAX)

enum kernel_index {
  KERNEL_VTA_CORE,
  NUM_OCL_KERNELS
};

typedef size_t ifocl_mem_off_t;

typedef struct {
  ifocl_mem_off_t offset;
  size_t size;
  bool occupied;
} mem_chunk_t;

class IntelFOCLDevice {
 private:
  cl_context _context = NULL;
  cl_device_id _device = NULL;
  cl_program _program = NULL;
  cl_mem _mem = NULL;
  cl_kernel _kernels[NUM_OCL_KERNELS] = {NULL};
  cl_command_queue _queues[NUM_OCL_KERNELS] = {NULL};
  std::list<mem_chunk_t> _mem_chunks;
  size_t _alignment;

 public:
  IntelFOCLDevice();

  void init();

  int setup(size_t mem_size, std::string aocx_file);

  ifocl_mem_off_t alloc(size_t size);

  void free(ifocl_mem_off_t offset);

  void write_mem(ifocl_mem_off_t offset, const void *buf, size_t nbyte);

  void read_mem(ifocl_mem_off_t offset, void *buf, size_t nbyte);

  int execute_instructions(ifocl_mem_off_t offset, size_t count);

  void deinit();

  ~IntelFOCLDevice();
};

#endif  // _3RDPARTY_VTA_HW_SRC_INTELFOCL_INTELFOCL_DEVICE_H_
