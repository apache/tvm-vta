<!--- Licensed to the Apache Software Foundation (ASF) under one -->
<!--- or more contributor license agreements.  See the NOTICE file -->
<!--- distributed with this work for additional information -->
<!--- regarding copyright ownership.  The ASF licenses this file -->
<!--- to you under the Apache License, Version 2.0 (the -->
<!--- "License"); you may not use this file except in compliance -->
<!--- with the License.  You may obtain a copy of the License at -->

<!---   http://www.apache.org/licenses/LICENSE-2.0 -->

<!--- Unless required by applicable law or agreed to in writing, -->
<!--- software distributed under the License is distributed on an -->
<!--- "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY -->
<!--- KIND, either express or implied.  See the License for the -->
<!--- specific language governing permissions and limitations -->
<!--- under the License. -->

# Verilator examples

These are examples of hardware designs for testing Verilator backend integration to TVM

## Requirements

This repository is meant to be used together with TVM (3rdparty folder) and not standalone,
because it depends on certain headers (device and runtime) available in TVM.

Install Verilator (4.100 or above)

## Build

1. Build Verilator hardware library by running `make`
2. Enable Verilator backend by setting `USE_VERILATOR ON` in TVM cmake configuration file (`config.cmake`)
3. Build and install TVM

