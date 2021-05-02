/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

module add #(parameter LANES = 1)(input clock, input reset);

    reg [32-1:0] cc;
    reg [32*LANES-1:0] ra;
    reg [32*LANES-1:0] rb;
    reg [32*LANES-1:0] ry;

    always @(posedge clock) begin
        if (reset) begin
            cc <= 0;
        end
        else begin
            cc <= cc + 1'b1;
        end
    end

    genvar i;
    for (i = 0; i < LANES; i++) begin

        always @(posedge clock) begin
            if (reset) begin
                ra[32*i+:32] <= 0;
                rb[32*i+:32] <= 0;
                ry[32*i+:32] <= 0;
            end
            else begin
                ry[32*i+:32] <= ra[32*i+:32] + rb[32*i+:32];
            end
        end

    end

endmodule
