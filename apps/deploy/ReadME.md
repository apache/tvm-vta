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
=========================
This folder contains an example on how to deploy TVM-VTA modules.
It also contains an example code to deploy with C++.

1. In tvm enable vta fsim or FPGA and compile tvm.

2. run resnet_export.py, this script would compile mxnet resnet18 into vta library, and
   compute graph, parameter and save into ./build/model folder.

```bash
  python3 ./resnet_export.py
```

3. compile cpp execute file

   3.1 Deploy with vta simulator

       Run "make" command, the script would build "lib.so" and cop libtvm_runtime.so
       and libvta*.so into ./build folder and compile execute file ./deploy
       ```bash
       make
       ```

   3.2 Deploy with FPGA

       3.2.1 copy './build/' folder(generate by #2) into target FPGA board folder "tvm/3rdparty/vta-hw/apps/deploy/"

       3.2.1 in target FPGA board, enable FPGA in config file and run following command

             to build libvta.so and libtvm_runtime.so
       ```bash
       make runtime vta
       ```

       3.2.2 copy './build/' folder into target FPGA board folder "tvm/3rdparty/vta-hw/apps/deploy/"

       3.2.3 goto "tvm/3rdparty/vta-hw/apps/deploy/"
       ```bash
       cd tvm/3rdparty/vta-hw/apps/deploy/
       ```

       3.2.4 Run "make" command, the script would build "lib.so" and cop libtvm_runtime.so
             and "libvta*.so" into "./build" folder and compile execute file "./deploy"
      ```bash
      make
      ```
  
      3.2.5. use following command to convert a image into correct image size that match mxnet resnet18 requirement.
      ```bash
      ./img_data_help.py <image path>
      ```
      the said command would output a file name 'img_data'

      3.2.6. run following command to get the image type
      ```bash
      ./deploy img_data
      ```
4. python deploy

      4.1 run python_deploy.py

      ```bash
      python3 ./python_deploy.py
      ```
