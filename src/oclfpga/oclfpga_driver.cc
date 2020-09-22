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

#include "oclfpga_device.h"
#include <vta/driver.h>
#include <tvm/runtime/registry.h>
#include <string>
#include <iostream>

/* This identifier is used to differentiate between offset and virtual
 * address, it will prevent zero been returned as a valid address */
#define MEM_ADDR_IDENTIFIER (0x18000000)

static OCLFPGADevice focl_device;

static inline void* mem_get_addr(focl_mem_off_t offset) {
  void* ret = reinterpret_cast<void*>(offset + MEM_ADDR_IDENTIFIER);
  return ret;
}

static inline focl_mem_off_t mem_get_offset(const void* addr) {
  focl_mem_off_t ret = (focl_mem_off_t)addr - MEM_ADDR_IDENTIFIER;
  return ret;
}

void* VTAMemAlloc(size_t size, int cached) {
  (void)cached;
  focl_mem_off_t offset = focl_device.alloc(size);
  if (offset == FOCL_MEM_OFF_ERR) return NULL;
  void* addr = mem_get_addr(offset);
  return addr;
}

void VTAMemFree(void* buf) {
  focl_mem_off_t offset = mem_get_offset(buf);
  focl_device.free(offset);
}

vta_phy_addr_t VTAMemGetPhyAddr(void* buf) {
  focl_mem_off_t offset = mem_get_offset(buf);
  return (vta_phy_addr_t)offset;
}

void VTAMemCopyFromHost(void* dst, const void* src, size_t size) {
  focl_mem_off_t dst_offset = mem_get_offset(dst);
  focl_device.writeMem(dst_offset, src, size);
}

void VTAMemCopyToHost(void* dst, const void* src, size_t size) {
  focl_mem_off_t src_offset = mem_get_offset(src);
  focl_device.readMem(src_offset, dst, size);
}

void VTAFlushCache(void* offset, vta_phy_addr_t buf, int size) {
  std::cout << "VTAFlushCache not implemented for Intel OpenCL for FPGA devices" << std::endl;
}

void VTAInvalidateCache(void* offset, vta_phy_addr_t buf, int size) {
  std::cout << "VTAInvalidateCache not implemented for Intel OpenCL for FPGA devices" << std::endl;
}

VTADeviceHandle VTADeviceAlloc() { return (VTADeviceHandle) & focl_device; }

void VTADeviceFree(VTADeviceHandle handle) { (void)handle; }

int VTADeviceRun(VTADeviceHandle handle, vta_phy_addr_t insn_phy_addr, uint32_t insn_count,
                 uint32_t wait_cycles) {
  (void)wait_cycles;
  focl_mem_off_t offset = (focl_mem_off_t)insn_phy_addr;
  return focl_device.executeInstructions(offset, insn_count);
}

using tvm::runtime::TVMRetValue;
using tvm::runtime::TVMArgs;

TVM_REGISTER_GLOBAL("vta.oclfpga.program").set_body([](TVMArgs args, TVMRetValue* rv) {
  std::string bitstream = args[0];
  int64_t mem_size = args[1];
  focl_device.setup(mem_size, bitstream);
});
