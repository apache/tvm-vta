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

`ifndef LANES
`define LANES 1
`endif

module driver (
    input  logic          clock,
    input  logic          reset,
    input  logic [32-1:0] opcode,
    input  logic [32-1:0] id,
    input  logic [32-1:0] in,
    input  logic [32-1:0] addr,
    output logic [32-1:0] out
);

    function void write_cc;
        input int value;
        input int addr;
        begin
            driver.dut.cc[32*addr+:32] = value;
        end
    endfunction

    function int read_cc;
        input int addr;
        begin
            return driver.dut.cc[32*addr+:32];
        end
    endfunction

    function void write_reg_a;
        input int value;
        input int addr;
        begin
            driver.dut.ra[32*addr+:32] = value;
        end
    endfunction

    function int read_reg_a;
        input int addr;
        begin
            return driver.dut.ra[32*addr+:32];
        end
    endfunction

    function void write_reg_b;
        input int value;
        input int addr;
        begin
            driver.dut.rb[32*addr+:32] = value;
        end
    endfunction

    function int read_reg_b;
        input int addr;
        begin
            return driver.dut.rb[32*addr+:32];
        end
    endfunction

    function void write_reg_y;
        input int value;
        input int addr;
        begin
            driver.dut.ry[32*addr+:32] = value;
        end
    endfunction

    function int read_reg_y;
        input int addr;
        begin
            return driver.dut.ry[32*addr+:32];
        end
    endfunction

    always_comb begin
        case(opcode)
            32'd0 : out = 32'hdeadbeef;
            32'd1 : begin
                case(id)
                    32'd0 : write_cc(in, addr);
                    32'd1 : write_reg_a(in, addr);
                    32'd2 : write_reg_b(in, addr);
                    32'd3 : write_reg_y(in, addr);
                endcase
            end
            32'd2 : begin
                case(id)
                    32'd0 : out = read_cc(addr);
                    32'd1 : out = read_reg_a(addr);
                    32'd2 : out = read_reg_b(addr);
                    32'd3 : out = read_reg_y(addr);
                endcase
            end
        endcase
    end

    add #(.LANES(`LANES)) dut (.clock(clock), .reset(reset));

endmodule
