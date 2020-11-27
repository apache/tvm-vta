# Verilator examples

These are examples of hardware designs for testing Verilator backend integration to TVM

## Requirements

This repository is meant to be used together with TVM (3rdparty folder) and not standalone,
because it depends on certain headers (device and runtime) available in TVM.

Install Verilator (4.100 or above)

## Build

1. Build Verilator hardware library by running `make`
2. Enable Verilator backend by setting `USE_VERILATOR_HW ON` in TVM cmake configuration file (`config.cmake`)
3. Build and install TVM

