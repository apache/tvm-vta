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

Intel OpenCL for FPGA Setup
---------------------------

To compile and run VTA on Intel® OpenCL for FPGA™ compatible devices, you need to first install and configure Intel® FPGA SDK for OpenCL™ environment on your system. Detailed installation instructions of the SDK can be found at `Intel® FPGA SDK for OpenCL™ Pro Edition: Getting Started Guide <https://www.intel.com/content/www/us/en/programmable/documentation/mwh1391807309901.html>`_.

If you have Intel® OpenCL for FPGA™ compatible hardware accelerator card(s) installed on your system, you could compile and run the VTA design on actual hardware. However, if you do not have any compatible card available, you may still try and test VTA in software emulation or cycle-accurate simulation modes, please jump to section 'Compile VTA kernel in Emulation Mode' for more details.

Verify hardware installation
^^^^^^^^^^^^^^^^^^^^^^^^^^^^

To verify your hardware installation, simple use the aoc utility provided by Intel.

.. code:: bash

    $ aocl list-devices
    --------------------------------------------------------------------
    Device Name:
    acl0

    BSP Install Location:
    /opt/intelFPGA_pro/18/hld/board/a10_ref

    Vendor: Intel(R) Corporation

    Phys Dev Name  Status   Information

    acla10_ref0   Passed   Arria 10 Reference Platform (acla10_ref0)
                           PCIe dev_id = 2494, bus:slot.func = 3b:00.00, Gen3 x8
                           FPGA temperature = 45.7383 degrees C.

    DIAGNOSTIC_PASSED

To perform a simple test on your installed acceleration cards, you could use the diagnose option of the aocl utility.

.. code:: bash

    $ aocl diagnose all
    ...
    --------------------------------------------------------------------
    ICD System Diagnostics
    --------------------------------------------------------------------
    ...

    Write top speed = 6024.05 MB/s
    Read top speed = 6083.50 MB/s
    Throughput = 6053.77 MB/s

    DIAGNOSTIC_PASSED

For detailed usage of aoc/aocl command, please refer to `Intel FPGA SDK for OpenCL Programming Guide <https://www.intel.com/content/www/us/en/programmable/documentation/mwh1391807965224.html>`_.

VTA Kernel Compilation
^^^^^^^^^^^^^^^^^^^^^^

To run TVM-VTA on Intel® OpenCL for FPGA™ compatible devices, firstly you need to configure the VTA target properly.

.. code:: bash

    $ cd <tvm root>/3rdparty/vta-hw/config
    $ cp intelfocl_sample.json vta_config.py

After updating vta_config, you need to re-compile the TVM:

.. code:: bash

    $ cd <tvm root>
    $ make

Before compiling your VTA kernel for Intel OpenCL for FPGA devices, you need to make sure all the required environment variables have been set correctly.

.. code:: bash

    $ echo $INTELFPGAOCLSDKROOT
    /opt/intelFPGA_pro/19/hld
    $ echo $QUARTUS_ROOTDIR_OVERRIDE
    /opt/intelFPGA_pro/18/quartus
    $ echo $AOCL_BOARD_PACKAGE_ROOT
    /opt/intelFPGA_pro/18/hld/board/a10_ref

Change your directory to hardware/intelfocl:

.. code:: bash

    $ cd <tvm root>/3rdparty/vta-hw/hardware/intelfocl

Simply enter ``make`` for hardware compilation and generate the VTA bitstream for your Intel OpenCL for FPGA device. Please note this process may take hours or even days to complete.

.. code:: bash

    $ make
    aoc: Running OpenCL parser....
    ...
    aoc: Compiling for FPGA. This process may take several hours to complete.
    
If the hardware compilation is successful, the generated bitstream can be found at <tvm root>/3rdparty/vta-hw/build/hardware/intelfocl/<config>/vta_opencl.aocx

Test your compiled VTA kernel
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

The intelfocl target uses a local RPC session and you need to program your FPGA acceleration card using the correct bitstream before any calculation. To configure that, make sure the following instructions are added to your python script.

.. code:: python

    if env.TARGET in ("intelfocl"):
      remote = rpc.LocalSession()
      vta.program_fpga(remote, bitstream="<your bitstream path>")

You can now run VTA tutorial test scripts to test your kernel on Intel® OpenCL for FPGA™ compatible devices!

.. code:: bash

    $ python vta/tutorials/vta_get_started.py
    oclfpga_device.cc:91: Using FPGA device: fa510q : Arria 10 Reference Platform (acla10_ref0)
    oclfpga/oclfpga_device.cc:109: Using Bitstream: vta_opencl.aocx
    ...
    Successful vector add test!

Compile VTA kernel in Emulation Mode
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

As hardware compilation takes hours or even days to compile, you can quickly verify your VTA design via software emulation mode. What's more, the running of emulation mode does not depend on actual hardware. That means you could try and test your design even without possession of an compatible Intel® OpenCL for FPGA™ acceleration card!

As we are using emulation mode provided by Intel® OpenCL for FPGA™ SDK, we will still need to configure the VTA target to "intelfocl".

.. code:: bash

    $ cd <tvm root>/3rdparty/vta-hw/config
    $ vim vta_config.py
    $ cd <tvm root>
    $ make

To compile you VTA design for emulation, instead of typing ``make``, you need to enter ``make emulator`` instead.

.. code:: bash

    $ cd <tvm root>/3rdparty/vta-hw/hardware/intelfocl
    $ make emulator
    Emulator flow is successful.
    To execute emulated kernel, invoke host with
            env CL_CONTEXT_EMULATOR_DEVICE_INTELFPGA=1 <host_program>
      For multi device emulations replace the 1 with the number of devices you wish to emulate

The compiled bitstream could be found at <tvm root>/3rdparty/vta-hw/build/hardware/intelfocl/<config>/vta_opencl_emu.aocx

As suggested by the compilation output, you should set environment variable CL_CONTEXT_EMULATOR_DEVICE_INTELFPGA before running your application.

.. code:: bash

    $ CL_CONTEXT_EMULATOR_DEVICE_INTELFPGA=1 python vta/tutorials/vta_get_started.py
    oclfpga_device.cc:91: Using FPGA device: fa510q : Arria 10 Reference Platform (acla10_ref0)
    oclfpga/oclfpga_device.cc:109: Using Bitstream: vta_opencl.aocx
    ...
    Successful vector add test!

Tested Boards
^^^^^^^^^^^^^

This version of VTA design has been successfully tested on the following Intel® OpenCL for FPGA™ compatible acceleration cards:

* Intel® Programmable Acceleration Card with Intel Arria® 10
* Intel® FPGA Programmable Acceleration Card (Intel FPGA PAC) D5005
* Intel Arria® 10 GX FPGA Development Kit
* Intel Stratix® 10 GX FPGA Development Kit
* 4Paradigm ATX800 Acceleration Card
* 4Paradigm ATX810 Acceleration Card
* 4Paradigm ATX900 Acceleration Card
* Flyslice FA510Q
* Flyslice FA728Q
