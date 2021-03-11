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
#include <cstdio>
#include <dlpack/dlpack.h>
#include <tvm/runtime/module.h>
#include <tvm/runtime/registry.h>
#include <tvm/runtime/packed_func.h>
#include <fstream>
#include <iterator>
#include <algorithm>
#include <vta/runtime/runtime.h>


void graph_test(std::string img,
                std::string model_path,
                std::string lib,
                std::string graph,
                std::string params) {
  tvm::runtime::Module mod_dylib =
        tvm::runtime::Module::LoadFromFile((model_path+lib).c_str()) ;
  std::ifstream json_in((model_path + graph).c_str());
  if(json_in.fail())
  {
    throw std::runtime_error("could not open json file");
  }

  std::ifstream params_in((model_path + params).c_str(), std::ios::binary);
  if(params_in.fail())
  {
    throw std::runtime_error("could not open json file");
  }

  const std::string json_data((std::istreambuf_iterator<char>(json_in)),
                               std::istreambuf_iterator<char>());
  json_in.close();
  const std::string params_data((std::istreambuf_iterator<char>(params_in)),
                                 std::istreambuf_iterator<char>());
  params_in.close();

  TVMByteArray params_arr;
  params_arr.data = params_data.c_str();
  params_arr.size = params_data.length();

  int dtype_code = kDLFloat;
  int dtype_bits = 32;
  int dtype_lanes = 1;
  int device_type = kDLExtDev;
  int device_id = 0;

  // get global function module for graph runtime
  tvm::runtime::Module mod = 
    (*tvm::runtime::Registry::Get("tvm.graph_runtime.create"))(json_data,
                                                                mod_dylib,
                                                                device_type,
                                                                device_id);
  DLTensor* x;
  tvm::runtime::PackedFunc get_input = mod.GetFunction("get_input");
  x = get_input(0);
  VTACommandHandle cmd;

  int in_ndim = 4;
  int64_t in_shape[4] = {1, 3, 224, 224};
  size_t data_len = 3 * 224 * 224 * 4;
  // load image data saved in binary
  std::ifstream data_fin(img.c_str(), std::ios::binary);
  char * data = (char *) malloc(data_len);
  data_fin.read(data, data_len);
  TVMArrayCopyFromBytes(x, data, data_len);
  free(data);
  // get the function from the module(load parameters)
  tvm::runtime::PackedFunc load_params = mod.GetFunction("load_params");
  load_params(params_arr);
  tvm::runtime::PackedFunc run = mod.GetFunction("run");
  run();

  DLTensor* y;
  int out_ndim = 2;
  int64_t out_shape[2] = {1, 1000};
  TVMArrayAlloc(out_shape, out_ndim, dtype_code, dtype_bits, dtype_lanes,
                  kDLCPU, device_id, &y);

  // get the function from the module(get output data)
  tvm::runtime::PackedFunc get_output = mod.GetFunction("get_output");
  get_output(0, y);

    // get the maximum position in output vector
  auto y_iter = static_cast<float*>(y->data);
  auto max_iter = std::max_element(y_iter, y_iter + 1000);
  auto max_index = std::distance(y_iter, max_iter);
  std::cout << "The maximum position in output vector is: " << max_index << std::endl;

  TVMArrayFree(x);
  TVMArrayFree(y);
}

int main(int argc, char *argv[]) {
  if (argc <= 1) {
  	printf("deploy <file name>\n");
	return 0;
  }
  graph_test(argv[1],
             "./model/",
             "lib.so",
             "graph.json",
             "params.params");
  return 0;

}

