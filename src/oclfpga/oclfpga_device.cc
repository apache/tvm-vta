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
#include <dmlc/logging.h>
#include <vta/hw_spec.h>
#include <cstring>
#include <numeric>

#define CL_STATUS_SUCCESS(x) ((x) == CL_SUCCESS)

static const char *kernel_names[] = {"vta_core"};

static cl_platform_id *find_platform(std::vector<cl_platform_id> *platforms,
                                     const std::vector<std::string> &supported_platforms) {
  cl_int status;
  size_t size;
  std::vector<char> name;
  for (auto &id : *platforms) {
    status = clGetPlatformInfo(id, CL_PLATFORM_NAME, 0, NULL, &size);
    if (!CL_STATUS_SUCCESS(status)) continue;
    name.resize(size);
    status = clGetPlatformInfo(id, CL_PLATFORM_NAME, name.size(), name.data(), NULL);
    if (!CL_STATUS_SUCCESS(status)) continue;
    for (auto &p : supported_platforms) {
      if (strstr(name.data(), p.c_str()) != NULL) {
        return &id;
      }
    }
  }
  return NULL;
}

OCLFPGADevice::OCLFPGADevice() {
  std::vector<std::string> supported_platforms = {"Intel(R) FPGA SDK for OpenCL(TM)", "Xilinx"};
  init(supported_platforms);
}

void OCLFPGADevice::init(const std::vector<std::string> &supported_platforms) {
  cl_int status;
  cl_device_id *device;
  cl_platform_id *platform;
  cl_uint n;
  size_t size;
  std::vector<char> name;
  std::vector<cl_platform_id> platforms;
  std::vector<cl_device_id> devices;

  status = clGetPlatformIDs(0, NULL, &n);
  CHECK(CL_STATUS_SUCCESS(status)) << "Failed to query number of OpenCL platforms";
  platforms.resize(n);
  CHECK(platforms.size() > 0) << "No OpenCL platform available";
  status = clGetPlatformIDs(platforms.size(), platforms.data(), NULL);
  CHECK(CL_STATUS_SUCCESS(status)) << "Failed to query OpenCL platform IDs";

  platform = find_platform(&platforms, supported_platforms);
  CHECK(platform) << "Unable to find supported OpenCL platform";

  status = clGetDeviceIDs(*platform, CL_DEVICE_TYPE_ALL, 0, NULL, &n);
  CHECK(CL_STATUS_SUCCESS(status)) << "Failed to query number of OpenCL devices";
  devices.resize(n);
  CHECK(devices.size() > 0) << "No OpenCL device found";
  status = clGetDeviceIDs(*platform, CL_DEVICE_TYPE_ALL, devices.size(), devices.data(), NULL);
  CHECK(CL_STATUS_SUCCESS(status)) << "Failed to query OpenCL devices IDs";

  device = NULL;
  for (auto &id : devices) {
    _context = clCreateContext(NULL, 1, &id, NULL, NULL, &status);
    if (CL_STATUS_SUCCESS(status)) {
      status = clGetDeviceInfo(id, CL_DEVICE_NAME, 0, NULL, &size);
      CHECK(CL_STATUS_SUCCESS(status)) << "Failed to query OpenCL device info";
      name.resize(size);
      status = clGetDeviceInfo(id, CL_DEVICE_NAME, name.size(), name.data(), NULL);
      CHECK(CL_STATUS_SUCCESS(status)) << "Failed to query OpenCL device name";
      LOG(INFO) << "Using FPGA device: " << name.data();
      device = &id;
      break;
    } else {
      LOG(INFO) << "This FPGA Device is not available. Skipped.";
    }
  }
  CHECK(device) << "No FPGA device available";
  _device = *device;
}

int OCLFPGADevice::setup(size_t mem_size, std::string bitstream_file) {
  cl_int status;
  unsigned int argi;
  size_t size;
  FILE *binary_file;
  unsigned char *binary;

  LOG(INFO) << "Using Bitstream: " << bitstream_file;
  binary_file = std::fopen(bitstream_file.c_str(), "rb");
  CHECK(binary_file) << "Could not open bitstream file for reading";

  std::fseek(binary_file, 0, SEEK_END);
  size = std::ftell(binary_file);
  std::fseek(binary_file, 0, SEEK_SET);
  binary = new unsigned char[size];
  std::fread(binary, 1, size, binary_file);
  std::fclose(binary_file);

  _program = clCreateProgramWithBinary(_context, 1, &_device, &size,
                                       const_cast<const unsigned char **>(&binary), NULL, &status);
  delete binary;
  CHECK(CL_STATUS_SUCCESS(status)) << "Failed to build program";

  for (unsigned int i = 0; i < NUM_OCL_KERNELS; i++) {
    _kernels[i] = clCreateKernel(_program, kernel_names[i], &status);
    CHECK(CL_STATUS_SUCCESS(status)) << "Failed to create kernel";
    _queues[i] = clCreateCommandQueue(_context, _device, 0, &status);
    CHECK(CL_STATUS_SUCCESS(status)) << "Failed to create command queue";
  }

  _mem = clCreateBuffer(_context, CL_MEM_READ_WRITE, mem_size, NULL, &status);
  CHECK(CL_STATUS_SUCCESS(status)) << "Failed to create buffer mem";
  mem_chunk_t init_chunk = {.offset = 0, .size = mem_size, .occupied = false};
  _mem_chunks.push_back(init_chunk);

  _alignment = std::lcm(VTA_BLOCK_IN * VTA_BLOCK_OUT,
                        std::lcm(VTA_BLOCK_IN, VTA_BLOCK_OUT * sizeof(int)) * VTA_BATCH);

  argi = 2;
  status = clSetKernelArg(_kernels[KERNEL_VTA_CORE], argi++, sizeof(cl_mem), &_mem);
  CHECK(CL_STATUS_SUCCESS(status)) << "Failed to set argument " << argi;
  status = clSetKernelArg(_kernels[KERNEL_VTA_CORE], argi++, sizeof(cl_mem), &_mem);
  CHECK(CL_STATUS_SUCCESS(status)) << "Failed to set argument " << argi;
  status = clSetKernelArg(_kernels[KERNEL_VTA_CORE], argi++, sizeof(cl_mem), &_mem);
  CHECK(CL_STATUS_SUCCESS(status)) << "Failed to set argument " << argi;
  status = clSetKernelArg(_kernels[KERNEL_VTA_CORE], argi++, sizeof(cl_mem), &_mem);
  CHECK(CL_STATUS_SUCCESS(status)) << "Failed to set argument " << argi;
  status = clSetKernelArg(_kernels[KERNEL_VTA_CORE], argi++, sizeof(cl_mem), &_mem);
  CHECK(CL_STATUS_SUCCESS(status)) << "Failed to set argument " << argi;
  status = clSetKernelArg(_kernels[KERNEL_VTA_CORE], argi++, sizeof(cl_mem), &_mem);
  CHECK(CL_STATUS_SUCCESS(status)) << "Failed to set argument " << argi;

  return 0;
}

focl_mem_off_t OCLFPGADevice::alloc(size_t size) {
  auto iter = _mem_chunks.begin();
  size_t aligned_size = ((size + _alignment - 1) / _alignment) * _alignment;

  while (iter != _mem_chunks.end() && (iter->occupied || (iter->size < aligned_size))) {
    iter++;
  }

  if (iter == _mem_chunks.end()) return FOCL_MEM_OFF_ERR;

  iter->occupied = true;
  if (iter->size != aligned_size) {
    mem_chunk_t rem = {iter->offset + aligned_size, iter->size - aligned_size, false};
    iter->size = aligned_size;
    _mem_chunks.insert(std::next(iter), rem);
  }

  return iter->offset;
}

void OCLFPGADevice::free(focl_mem_off_t offset) {
  auto iter = _mem_chunks.begin();
  while (iter != _mem_chunks.end() && iter->offset < offset) iter++;

  if (iter == _mem_chunks.end() || iter->offset != offset || !iter->occupied) {
    return;
  }

  iter->occupied = false;
  if (iter != _mem_chunks.begin() && !std::prev(iter)->occupied) iter--;

  while (std::next(iter) != _mem_chunks.end() && !std::next(iter)->occupied) {
    iter->size += std::next(iter)->size;
    _mem_chunks.erase(std::next(iter));
  }
}

void OCLFPGADevice::writeMem(focl_mem_off_t offset, const void *buf, size_t nbyte) {
  cl_int status =
      clEnqueueWriteBuffer(_queues[0], _mem, CL_TRUE, offset, nbyte, buf, 0, NULL, NULL);
  CHECK(CL_STATUS_SUCCESS(status)) << "Failed to enqueue write buffer";
}

void OCLFPGADevice::readMem(focl_mem_off_t offset, void *buf, size_t nbyte) {
  cl_int status = clEnqueueReadBuffer(_queues[0], _mem, CL_TRUE, offset, nbyte, buf, 0, NULL, NULL);
  CHECK(CL_STATUS_SUCCESS(status)) << "Failed to enqueue read buffer";
}

int OCLFPGADevice::executeInstructions(focl_mem_off_t offset, size_t count) {
  cl_int status;
  unsigned int argi;
  unsigned int insn_offset = offset / VTA_INS_ELEM_BYTES;
  unsigned int insn_count = count;
  const size_t global_work_size = 1;
  const size_t local_work_size = 1;

  argi = 0;
  status = clSetKernelArg(_kernels[KERNEL_VTA_CORE], argi++, sizeof(unsigned int), &insn_count);
  CHECK(CL_STATUS_SUCCESS(status)) << "Failed to set argument " << argi;
  status = clSetKernelArg(_kernels[KERNEL_VTA_CORE], argi++, sizeof(unsigned int), &insn_offset);
  CHECK(CL_STATUS_SUCCESS(status)) << "Failed to set argument " << argi;

  for (unsigned int i = 0; i < NUM_OCL_KERNELS; i++) {
    status = clEnqueueNDRangeKernel(_queues[i], _kernels[i], 1, NULL, &global_work_size,
                                    &local_work_size, 0, NULL, NULL);
    CHECK(CL_STATUS_SUCCESS(status)) << "Failed to enqueue kernel";
  }

  for (unsigned int i = 0; i < NUM_OCL_KERNELS; i++) {
    status = clFinish(_queues[i]);
    CHECK(CL_STATUS_SUCCESS(status)) << "Failed to clFinish";
  }

  return 0;
}

void OCLFPGADevice::deinit() {
  for (unsigned int i = 0; i < NUM_OCL_KERNELS; i++) {
    if (_kernels[i]) clReleaseKernel(_kernels[i]);
    _kernels[i] = NULL;
    if (_queues[i]) clReleaseCommandQueue(_queues[i]);
    _queues[i] = NULL;
  }

  if (_mem) clReleaseMemObject(_mem);
  _mem = NULL;

  if (_program) clReleaseProgram(_program);
  _program = NULL;

  if (_context) clReleaseContext(_context);
  _context = NULL;
}

OCLFPGADevice::~OCLFPGADevice() { deinit(); }
