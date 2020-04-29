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
"""Python VTA Deploy."""
from __future__ import absolute_import, print_function

from io import BytesIO
from PIL import Image

import requests
import numpy as np

import tvm
from tvm import relay
from tvm.contrib import graph_runtime, cc

import vta
from vta.testing import simulator

CTX = tvm.ext_dev(0)

def load_model():
    """ Load VTA Model  """

    with open("./build/model/graph.json", "r") as graphfile:
        graph = graphfile.read()

    cc.create_shared("./graph_lib.so", ["./build/model/lib.o"])
    lib = tvm.runtime.load_module("./graph_lib.so")

    model = graph_runtime.create(graph, lib, CTX)

    with open("./build/model/params.params", "rb") as paramfile:
        param_bytes = paramfile.read()
    params = relay.load_param_dict(param_bytes)

    return model, params

MOD, PARAMS = load_model()

IMAGE_URL = 'https://homes.cs.washington.edu/~moreau/media/vta/cat.jpg'
RESPONSE = requests.get(IMAGE_URL)

# Prepare test image for inference
IMAGE = Image.open(BytesIO(RESPONSE.content)).resize((224, 224))

MOD.set_input('data', IMAGE)
MOD.set_input(**PARAMS)
MOD.run()

TVM_OUTPUT = MOD.get_output(0, tvm.nd.empty((1, 1000), "float32", CTX))
TOP_CATEGORIES = np.argsort(TVM_OUTPUT.asnumpy()[0])
print("\t#1:", TOP_CATEGORIES[-1])
