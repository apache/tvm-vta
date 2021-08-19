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
"""Python VTA Deploy."""
from __future__ import absolute_import, print_function

import os
from os.path import join
from io import BytesIO
from PIL import Image

import requests
import numpy as np

import tvm
from tvm.contrib import graph_executor, download


CTX = tvm.ext_dev(0)

def load_vta_library():
    """load vta lib"""
    curr_path = os.path.dirname(
        os.path.abspath(os.path.expanduser(__file__)))
    proj_root = os.path.abspath(os.path.join(curr_path, "../../../../"))
    vtadll = os.path.abspath(os.path.join(proj_root, "build/libvta.so"))
    return tvm.runtime.load_module(vtadll)


def load_model():
    """ Load VTA Model  """

    load_vta_library()

    with open("./build/model/graph.json", "r") as graphfile:
        graph = graphfile.read()

    lib = tvm.runtime.load_module("./build/model/lib.so")

    model = graph_executor.create(graph, lib, CTX)

    with open("./build/model/params.params", "rb") as paramfile:
        param_bytes = paramfile.read()

    categ_url = "https://github.com/uwsampl/web-data/raw/main/vta/models/"
    categ_fn = "synset.txt"
    download.download(join(categ_url, categ_fn), categ_fn)
    synset = eval(open(categ_fn).read())

    return model, param_bytes, synset

if __name__ == "__main__":
    MOD, PARAMS_BYTES, SYNSET = load_model()

    IMAGE_URL = 'https://homes.cs.washington.edu/~moreau/media/vta/cat.jpg'
    RESPONSE = requests.get(IMAGE_URL)

    # Prepare test image for inference
    IMAGE = Image.open(BytesIO(RESPONSE.content)).resize((224, 224))

    MOD.set_input('data', IMAGE)
    MOD.load_params(PARAMS_BYTES)
    MOD.run()

    TVM_OUTPUT = MOD.get_output(0, tvm.nd.empty((1, 1000), "float32", CTX))
    TOP_CATEGORIES = np.argsort(TVM_OUTPUT.asnumpy()[0])
    print("\t#1:", SYNSET[TOP_CATEGORIES[-1]])
