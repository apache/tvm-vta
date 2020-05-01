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


How to Deploy TVM-VTA Modules
=============================
This folder contains an example on how to deploy TVM-VTA modules.
It also contains an example code to deploy with C++ and Python.

1. In host machine tvm project enable vta fsim or FPGA and compile tvm successfully.

2. In target FPGA machine, flash bitstream into FPGA, following are example on pynq board

   'cd' into vta-hw/app/deploy, run following command,
    "/home/xilinx/vta.bit" is the bitstream file

	```bash
        sudo python3 ./bitstream.py /home/xilinx/vta.bit
	```

3. Compile and Deploy with C++

   3.1 Deploy with FPGA

       3.1.1 in host machine change ./vta-hw/config/vta_config.json TARGET into FPGA type
             for example "ultra96"

       3.1.2 in host machine run resnet_export.py, this script would compile mxnet resnet18
             into vta library, and compute graph, parameter and save into ./build/model folder.
       
	```bash
  	python3 ./resnet_export.py
	```

       3.1.3 from host machine, copy './build/' folder(generate by #2) into target FPGA board folder 
             "tvm/3rdparty/vta-hw/apps/deploy/"

       3.1.4 in target FPGA board, enable FPGA in config file and run following command

             to build libvta.so and libtvm_runtime.so
       ```bash
       make runtime vta
       ```

       3.1.5 in target FPGA board goto "tvm/3rdparty/vta-hw/apps/deploy/"
       ```bash
       cd tvm/3rdparty/vta-hw/apps/deploy/
       ```

       3.1.6 int FPGA board Run "make" command, the script would build "lib.so" and cop libtvm_runtime.so
             and "libvta*.so" into "./build" folder and compile execute file "./deploy"
      ```bash
      make
      ```
  
      3.1.7. in FPGA board use following command to convert a image into correct image size that match 
             mxnet resnet18 requirement.
      ```bash
      ./img_data_help.py <image path>
      ```
      the said command would output a file name 'img_data'

      3.1.8. in FPGA board run following command to get the image type
      ```bash
      ./deploy img_data
      ```

   3.2 Deploy with vta simulator(all steps happen in host machine)

       3.2.1 change ./vta-hw/config/vta_config.json TARGET into "sim"

       3.2.2 run resnet_export.py, this script would compile mxnet resnet18 into vta library, 
            and compute graph, parameter and save into ./build/model folder.

	```bash
  	python3 ./resnet_export.py
	```
       
       3.2.3 Run "make" command, the script would build "lib.so" and copy libtvm_runtime.so
             and libvta*.so into ./build folder and compile execute file ./deploy
       ```bash
       make
       ```

4. Python deploy

      4.1 Deploy with FPGA.

          4.1.1 From host machine Copy "./vta-hw/apps/deploy/build" folder into 
                target FPGA board "vta-hw/apps/deploy/" folder

          4.1.2 on FPGA board build libtvmruntime.so and libvta.so

          ```bash
          make runtime vta
          ```

          4.1.3 in ./vta-hw/apps/deploy run make to compile ./build/model/lib.so
          ```
          make
          ```

          4.1.4 run python_deploy.py by "run_python_deploy.sh"
          ```bash
          sudo ./run_python_deploy.sh
          ```
