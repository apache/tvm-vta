#licensed to the Apache Software Foundation (ASF) under one
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
from __future__ import absolute_import, print_function

import argparse, json, os, requests, sys, time
from io import BytesIO
from os.path import join, isfile
from PIL import Image

import numpy as np

import tvm
from tvm import relay
from tvm.contrib import graph_runtime, util, download

from tvm.contrib import cc
from tvm.contrib import util
import vta
from vta.testing import simulator

ctx = tvm.ext_dev(0)

def load_model():
    with open("./build/model/graph.json", "r") as f:
        graph = f.read();

    cc.create_shared("./graph_lib.so", ["./build/model/lib.o"])
    lib = tvm.runtime.load_module("./graph_lib.so")

    m = graph_runtime.create(graph, lib, ctx)

    with open("./build/model/params.params", "rb") as f:
        param_bytes = f.read();
    params = relay.load_param_dict(param_bytes)

    return m, params

mod, params = load_model()

image_url = 'https://homes.cs.washington.edu/~moreau/media/vta/cat.jpg'
response = requests.get(image_url)
# Prepare test image for inference
image = Image.open(BytesIO(response.content)).resize((224, 224))

mod.set_input('data', image)
mod.set_input(**params)

mod.run()

tvm_output = mod.get_output(0, tvm.nd.empty((1, 1000), "float32", ctx))
top_categories = np.argsort(tvm_output.asnumpy()[0])
print("\t#1:", top_categories[-1])
