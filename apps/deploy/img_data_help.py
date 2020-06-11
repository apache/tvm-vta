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
"""Resize Image To Match MXNET Model."""
import sys
import os
from PIL import Image
import numpy as np

if __name__ == "__main__":
    assert len(sys.argv) == 2, "usage: <image path>"
    IMG_PATH = sys.argv[1]
    assert os.path.isfile(IMG_PATH), "file " + IMG_PATH + "  not exist"
    IMAGE = Image.open(IMG_PATH).resize((224, 224))
    np.array(IMAGE).astype('float32').tofile("./img_data")
