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
""" Compile And Export MXNET Resnet18 Model With VTA As Backend """
from __future__ import absolute_import, print_function

import os
from os.path import exists
import numpy as np
from mxnet.gluon.model_zoo import vision

import tvm
from tvm import autotvm, relay
from tvm.relay import op, transform

import vta
from vta.top import graph_pack
from vta.top.graphpack import run_opt_pass

# Load VTA parameters from the vta/config/vta_config.json file
ENV = vta.get_env()
assert ENV.target.device_name == "vta"
# Dictionary lookup for when to start/end bit packing
PACK_DICT = {"resnet18_v1": ["nn.max_pool2d", "nn.global_avg_pool2d", None, None],}

# Name of Gluon model to compile
MODEL = "resnet18_v1"
assert MODEL in PACK_DICT

def merge_transform_to_mxnet_model(mod):
    """ Add Image Transform Logic Into Model """
    svalue = np.array([123., 117., 104.])
    sub_data = relay.Constant(tvm.nd.array(svalue)).astype("float32")
    dvalue = np.array([58.395, 57.12, 57.37])
    divide_data = relay.Constant(tvm.nd.array(dvalue)).astype("float32")

    data_shape = (224, 224, 3)
    data = relay.var("data", relay.TensorType(data_shape, "float32"))

    simple_net = relay.expand_dims(data, axis=0, num_newaxis=1)
    # To do, relay not support dynamic shape now, future need to add resize logic
    # simple_net = relay.image.resize(simple_net, (224, 224), "NHWC", "bilinear", "align_corners")
    simple_net = relay.subtract(simple_net, sub_data)
    simple_net = relay.divide(simple_net, divide_data)
    simple_net = relay.transpose(simple_net, ((0, 3, 1, 2)))

    #merge tranform into pretrained model network
    entry = mod["main"]
    anf = run_opt_pass(entry.body, transform.ToANormalForm())
    call = anf.value
    call_data, weights = call.args
    first_op = op.nn.conv2d(
        simple_net,
        weights,
        strides=call.attrs.strides,
        padding=call.attrs.padding,
        dilation=call.attrs.dilation,
        groups=call.attrs.groups,
        channels=call.attrs.channels,
        kernel_size=call.attrs.kernel_size,
        out_dtype=call.attrs.out_dtype)
    net = relay.expr.Let(anf.var, first_op, anf.body)
    new_params = [data]
    for indx in range(len(entry.params)):
        '''
        By pass first parameter which is input data and get replace with
        new data format(from (1, 224, 224, 3) to (224,224,3))
        '''
        if (indx > 0):
            new_params.append(entry.params[indx])
    '''
    Add param information to fix free varaible found error
    '''
    func = tvm.relay.Function(new_params,
                              net,
                              entry.ret_type,
                              entry.type_params,
                              entry.attrs)
    func = run_opt_pass(func, transform.ToGraphNormalForm())

    mod['main'] = func
    return mod

def compile_mxnet_gulon_resnet(_env, _model):
    """ Compile Model """
    # Generate tvm IR from mxnet gluon model
    # Populate the shape and data type dictionary for ImageNet classifier input
    dtype_dict = {"data": 'float32'}
    shape_dict = {"data": (_env.BATCH, 3, 224, 224)}
    # Get off the shelf gluon model, and convert to relay
    gluon_model = vision.get_model(_model, pretrained=True)
    # Start front end compilation
    mod, params = relay.frontend.from_mxnet(gluon_model, shape_dict)
    mod = merge_transform_to_mxnet_model(mod)
    # Update shape and type dictionary
    shape_dict.update({k: v.shape for k, v in params.items()})
    dtype_dict.update({k: str(v.dtype) for k, v in params.items()})

    # Load pre-configured AutoTVM schedules
    with autotvm.tophub.context(_env.target):
        # Perform quantization in Relay
        # Note: We set opt_level to 3 in order to fold batch norm
        with relay.build_config(opt_level=3):
            with relay.quantize.qconfig(global_scale=8.0, skip_conv_layers=[0]):
                mod = relay.quantize.quantize(mod, params=params)
            # Perform graph packing and constant folding for VTA target
            relay_prog = graph_pack(
                mod["main"],
                _env.BATCH,
                _env.BLOCK_IN,
                _env.WGT_WIDTH,
                start_name=PACK_DICT[_model][0],
                stop_name=PACK_DICT[_model][1])

    # Compile Relay program with AlterOpLayout disabled
    with relay.build_config(opt_level=3, disabled_pass={"AlterOpLayout"}):
        with vta.build_config(debug_flag=0):
            graph, lib, params = relay.build(
                relay_prog, target=_env.target,
                params=params, target_host=_env.target_host)

    return graph, lib, params

def export_tvm_compile(graph, lib, params, path):
    """ Export Model"""
    if not exists(path):
        os.makedirs(path)
    lib.save(path+"/lib.o")
    with open(path+"/graph.json", "w") as graphfile:
        graphfile.write(graph)
    with open(path+"/params.params", "wb") as paramfile:
        paramfile.write(relay.save_param_dict(params))

GRAPH, LIB, PARAMS = compile_mxnet_gulon_resnet(ENV, MODEL)
export_tvm_compile(GRAPH, LIB, PARAMS, "./build/model")
