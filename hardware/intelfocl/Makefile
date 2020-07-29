# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

# Directories
ROOTDIR = $(CURDIR)
VTA_HW_DIR = $(CURDIR)/../..
BUILD_DIR = $(VTA_HW_DIR)/build/hardware/intelfocl
SRC_DIR = $(CURDIR)/src

# Executables
INTEL_FPGA_AOC = aoc

# Process VTA JSON config
VTA_CONFIG := $(VTA_HW_DIR)/config/vta_config.py

# Retrieve VTA definitions
DEFNS := $(shell python ${VTA_CONFIG} --cflags)

# Derive config name
CONF := $(shell python ${VTA_CONFIG} --cfg-str)
HW_BUILD_PATH := $(BUILD_DIR)/$(CONF)

# Bitstream file path
BIT_PATH := $(BUILD_DIR)/$(CONF)/vta_opencl.aocx
EMULATOR_PATH := $(BUILD_DIR)/$(CONF)/vta_opencl_emu.aocx

.PHONY: all bit emu cleanall

all: bit
bit: $(BIT_PATH)
emulator: $(EMULATOR_PATH)

$(BIT_PATH): $(SRC_DIR)/*
	mkdir -p $(HW_BUILD_PATH)
	cd $(HW_BUILD_PATH) && \
		$(INTEL_FPGA_AOC) -v \
		$(SRC_DIR)/vta.cl \
		$(DEFNS) \
		-o $@

$(EMULATOR_PATH): $(SRC_DIR)/*
	mkdir -p $(HW_BUILD_PATH)
	cd $(HW_BUILD_PATH) && \
		$(INTEL_FPGA_AOC) -v \
		-march=emulator -legacy-emulator \
		$(SRC_DIR)/vta.cl \
		$(DEFNS) \
		-o $@

cleanall:
	rm -rf $(BUILD_DIR)
