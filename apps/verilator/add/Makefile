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

VERILATOR_BIN := $(shell which verilator)
VERILATOR_INC_DIR := $(abspath $(dir $(VERILATOR_BIN))/../share/verilator/include)
TOP_NAME = "Top"
LIB_NAME = "libverilator"
VERILOG_DIR = $(abspath .)/verilog
SRC_DIR = $(abspath .)/src
ROOT_DIR = $(abspath .)
TVM_DIR = $(abspath ../../../../..)
OUT_DIR = $(abspath .)/out
LANES = 1
LIB_PATH = $(ROOT_DIR)/$(LIB_NAME).so

default: $(LIB_PATH)

$(LIB_PATH): $(OUT_DIR)/$(TOP_NAME).cpp
	g++ \
	-std=c++14 \
	-O2 \
	-shared \
	-fPIC \
	-DLANES=$(LANES) \
	-I$(TVM_DIR)/src/runtime/contrib/verilator \
	-I$(TVM_DIR)/include \
	-I$(TVM_DIR)/3rdparty/dlpack/include \
	-I$(VERILATOR_INC_DIR) \
	-I$(OUT_DIR) \
	$(VERILATOR_INC_DIR)/verilated.cpp \
	$(SRC_DIR)/*.cc \
	$(OUT_DIR)/*.cpp \
	-o $@

$(OUT_DIR)/$(TOP_NAME).cpp: $(VERILOG_DIR)/*.v
	$(VERILATOR_BIN) \
	-Wno-BLKANDNBLK \
	-Wno-PINMISSING \
	-Wno-STMTDLY \
	-Wno-WIDTH \
	-Wno-UNOPTFLAT \
	-DLANES=$(LANES) \
	--cc \
	--prefix $(TOP_NAME) \
	--top-module "driver" \
	--Mdir $(OUT_DIR) \
	$^

clean:
	-rm -rf $(OUT_DIR) *.so
