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

module VTAMemDPI #
  ( parameter LEN_BITS = 8,
    parameter ADDR_BITS = 64,
    parameter DATA_BITS = 64,
    parameter STRB_BITS = DATA_BITS/8
  )
  (
    input                        clock,
    input                        reset,
    input                        dpi_req_ar_valid,
    input [LEN_BITS-1:0]         dpi_req_ar_len,
    input [7:0]                  dpi_req_ar_id,
    input [ADDR_BITS-1:0]        dpi_req_ar_addr,
    input                        dpi_req_aw_valid,
    input [LEN_BITS-1:0]         dpi_req_aw_len,
    input [ADDR_BITS-1:0]        dpi_req_aw_addr,
    input                        dpi_wr_valid,
    input [DATA_BITS-1:0]        dpi_wr_bits_data,
    input [STRB_BITS-1:0]        dpi_wr_bits_strb,
    output logic                 dpi_rd_valid,
    output logic [7:0]           dpi_rd_bits_id,
    output logic [DATA_BITS-1:0] dpi_rd_bits_data,
    input                        dpi_rd_ready
  );

  import "DPI-C" function void VTAMemDPI
    (
      input byte     unsigned rd_req_valid,
      input byte     unsigned rd_req_len,
      input byte     unsigned rd_req_id,
      input longint  unsigned rd_req_addr,
      input byte     unsigned wr_req_valid,
      input byte     unsigned wr_req_len,
      input longint  unsigned wr_req_addr,
      input byte     unsigned wr_valid,
      input longint  unsigned wr_value[],
      input longint  unsigned wr_strb,
      output byte    unsigned rd_valid,
      output byte    unsigned rd_id,
      output longint unsigned rd_value[],
      input byte     unsigned rd_ready
    );
  parameter blockNb = DATA_BITS/64;

  generate
    if (blockNb*64 != DATA_BITS) begin
      $error("-F- 64 bit data blocks expected.");
    end
  endgenerate
  generate
    if (STRB_BITS > 64) begin
      $error("-F- Strb bits should not exceed 64. Fix strb transfer");
    end
  endgenerate

  typedef logic         dpi1_t;
  typedef logic [7:0]         dpi8_t;
  typedef logic [31:0]       dpi32_t;
  typedef logic [63:0]       dpi64_t;
  typedef longint        dpi_data_t [blockNb-1:0];

  dpi1_t  __reset;
  dpi8_t  __rd_req_valid;
  dpi8_t  __rd_req_len;
  dpi8_t  __rd_req_id;
  dpi64_t __rd_req_addr;
  dpi8_t  __wr_req_valid;
  dpi8_t  __wr_req_len;
  dpi64_t __wr_req_addr;
  dpi8_t  __wr_valid;
  dpi_data_t __wr_value;
  dpi64_t __wr_strb;
  dpi8_t  __rd_valid;
  dpi_data_t __rd_value;
  dpi8_t  __rd_id;
  dpi8_t  __rd_ready;

  always_ff @(posedge clock) begin
    __reset <= reset;
  end

  // delaying outputs by one-cycle
  // since verilator does not support delays
  integer i;
  always_ff @(posedge clock) begin
    dpi_rd_valid <= dpi1_t ' (__rd_valid);
    for (i = 0; i < blockNb; i = i +1) begin
      dpi_rd_bits_data[64 * i +: 64]  <= __rd_value[i];
    end
    dpi_rd_bits_id    <= __rd_id;
  end

  assign __rd_req_valid  = dpi8_t ' (dpi_req_ar_valid);
  assign __rd_req_len    = dpi8_t ' (dpi_req_ar_len);
  assign __rd_req_id     = dpi_req_ar_id;
  assign __rd_req_addr   = dpi64_t ' (dpi_req_ar_addr);
  assign __wr_req_valid  = dpi8_t ' (dpi_req_aw_valid);
  assign __wr_req_len    = dpi8_t ' (dpi_req_aw_len);
  assign __wr_req_addr   = dpi64_t ' (dpi_req_aw_addr);

  generate
    if (STRB_BITS != 64) begin
      localparam [63 - STRB_BITS:0] strbfill = 0;
      assign __wr_strb    = {strbfill, dpi_wr_bits_strb};
    end
    else begin
      assign __wr_strb    = dpi_wr_bits_strb;
    end
  endgenerate
  assign __wr_valid   = dpi8_t ' (dpi_wr_valid);
  genvar j;
  generate
  for (j = 0; j < blockNb; j = j +1) begin
    assign __wr_value[j] = dpi_wr_bits_data[64 * j +: 64];
  end
  endgenerate
  assign __rd_ready   = dpi8_t ' (dpi_rd_ready);

  // evaluate DPI function
  always_ff @(posedge clock) begin
    if(reset) begin
      dpi_rd_valid <= 0;
      dpi_rd_bits_data <= 0;
      dpi_rd_bits_id <= 0;
    end
    else begin
      VTAMemDPI(
        __rd_req_valid,
        __rd_req_len,
        __rd_req_id,           
        __rd_req_addr,
        __wr_req_valid,
        __wr_req_len,
        __wr_req_addr,
        __wr_valid,
        __wr_value,
        __wr_strb,
        __rd_valid,
        __rd_id,
        __rd_value,
        __rd_ready);
      dpi_rd_valid <= dpi1_t ' (__rd_valid);
      for (i = 0; i < blockNb; i = i +1) begin
        dpi_rd_bits_data[64 * i +: 64] <= __rd_value[i];
      end
      dpi_rd_bits_id <= __rd_id;
    end // else: !if(reset | __reset)
  end // always_ff @

endmodule
