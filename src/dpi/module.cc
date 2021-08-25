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
#include <assert.h>

#include <tvm/runtime/module.h>
#include <tvm/runtime/packed_func.h>
#include <tvm/runtime/registry.h>

#include <vta/dpi/module.h>
#include <vta/dpi/tsim.h>
#if defined(_WIN32)
#include <windows.h>
#else
#include <dlfcn.h>
#endif

#include <mutex>
#include <queue>
#include <thread>
#include <condition_variable>
#include <fstream>

#include "../vmem/virtual_memory.h"

// Include verilator array access functions code
#include "verilated.cpp"
#include "verilated_dpi.cpp"

namespace vta {
namespace dpi {

using namespace tvm::runtime;

typedef void* DeviceHandle;

struct HostRequest {
  uint8_t opcode;
  uint8_t addr;
  uint32_t value;
};

struct HostResponse {
  uint32_t value;
};

struct MemResponse {
  uint8_t valid;
  uint8_t id;
  uint64_t* value;
};

template <typename T>
class ThreadSafeQueue {
 public:
  void Push(const T item) {
    std::lock_guard<std::mutex> lock(mutex_);
    queue_.push(std::move(item));
    cond_.notify_one();
  }

  void WaitPop(T* item) {
    std::unique_lock<std::mutex> lock(mutex_);
    cond_.wait(lock, [this]{return !queue_.empty();});
    *item = std::move(queue_.front());
    queue_.pop();
  }

  bool TryPop(T* item, bool pop) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (queue_.empty()) return false;
    *item = std::move(queue_.front());
    if (pop) queue_.pop();
    return true;
  }

 private:
  mutable std::mutex mutex_;
  std::queue<T> queue_;
  std::condition_variable cond_;
};

class SimDevice {
 public:
  void Wait();
  void Resume();
  void Exit();
  bool GetWaitStatus();
  bool GetExitStatus();

 private:
  bool wait_{false};
  bool exit_{false};
  mutable std::mutex mutex_;
};

class HostDevice {
 public:
  void PushRequest(uint8_t opcode, uint8_t addr, uint32_t value);
  bool TryPopRequest(HostRequest* r, bool pop);
  void PushResponse(uint32_t value);
  void WaitPopResponse(HostResponse* r);

 private:
  mutable std::mutex mutex_;
  ThreadSafeQueue<HostRequest> req_;
  ThreadSafeQueue<HostResponse> resp_;
};

class MemDevice {
 public:
  void  SetRequest(
    uint8_t  rd_req_valid,
    uint64_t rd_req_addr,
    uint32_t rd_req_len,
    uint32_t rd_req_id,
    uint64_t wr_req_addr,
    uint32_t wr_req_len,
    uint8_t  wr_req_valid);
  MemResponse ReadData(uint8_t ready, int blkNb);
  void WriteData(svOpenArrayHandle value, uint64_t wr_strb);

 private:
  uint64_t* raddr_{0};
  uint64_t* waddr_{0};
  uint32_t rlen_{0};
  uint32_t rid_{0};
  uint32_t wlen_{0};
  std::mutex mutex_;
  uint64_t dead_beef_ [8] = {0xdeadbeefdeadbeef,0xdeadbeefdeadbeef,
                              0xdeadbeefdeadbeef,0xdeadbeefdeadbeef,
                              0xdeadbeefdeadbeef,0xdeadbeefdeadbeef,
                              0xdeadbeefdeadbeef,0xdeadbeefdeadbeef };
  
};

void SimDevice::Wait() {
  std::unique_lock<std::mutex> lock(mutex_);
  wait_ = true;
}

void SimDevice::Resume() {
  std::unique_lock<std::mutex> lock(mutex_);
  wait_ = false;
}

void SimDevice::Exit() {
  std::unique_lock<std::mutex> lock(mutex_);
  exit_ = true;
}

bool SimDevice::GetWaitStatus() {
  std::unique_lock<std::mutex> lock(mutex_);
  return wait_;
}

bool SimDevice::GetExitStatus() {
  std::unique_lock<std::mutex> lock(mutex_);
  return exit_;
}

void HostDevice::PushRequest(uint8_t opcode, uint8_t addr, uint32_t value) {
  HostRequest r;
  r.opcode = opcode;
  r.addr = addr;
  r.value = value;
  req_.Push(r);
}

bool HostDevice::TryPopRequest(HostRequest* r, bool pop) {
  r->opcode = 0xad;
  r->addr = 0xad;
  r->value = 0xbad;
  return req_.TryPop(r, pop);
}

void HostDevice::PushResponse(uint32_t value) {
  HostResponse r;
  r.value = value;
  resp_.Push(r);
}

void HostDevice::WaitPopResponse(HostResponse* r) {
  resp_.WaitPop(r);
}

void MemDevice::SetRequest(
  uint8_t  rd_req_valid,
  uint64_t rd_req_addr,
  uint32_t rd_req_len,
  uint32_t rd_req_id,
  uint64_t wr_req_addr,
  uint32_t wr_req_len,
  uint8_t  wr_req_valid) {

  std::lock_guard<std::mutex> lock(mutex_);
  if(rd_req_addr !=0 ){
    void * rd_vaddr = vta::vmem::VirtualMemoryManager::Global()->GetAddr(rd_req_addr);
    if(rd_req_valid == 1) {
      rlen_ = rd_req_len + 1;
      rid_  = rd_req_id;
      raddr_ = reinterpret_cast<uint64_t*>(rd_vaddr);
    }
  }

  if(wr_req_addr != 0){
    void * wr_vaddr = vta::vmem::VirtualMemoryManager::Global()->GetAddr(wr_req_addr);
    if (wr_req_valid == 1) {
      wlen_ = wr_req_len + 1;
      waddr_ = reinterpret_cast<uint64_t*>(wr_vaddr);
    } 
  }
}

MemResponse MemDevice::ReadData(uint8_t ready, int blkNb) {
  std::lock_guard<std::mutex> lock(mutex_);
  MemResponse r;
  r.valid = rlen_ > 0;
  r.value = rlen_ > 0 ? raddr_ : dead_beef_;
  r.id    = rid_;
  if (ready == 1 && rlen_ > 0) {
    raddr_ += blkNb;
    rlen_ -= 1;
  }
  return r;
}

void MemDevice::WriteData(svOpenArrayHandle value, uint64_t wr_strb) {

  int lftIdx = svLeft(value, 1);
  int rgtIdx = svRight(value, 1);
  int blkNb  = lftIdx - rgtIdx + 1;
  assert(lftIdx >= 0);
  assert(rgtIdx >= 0);
  assert(lftIdx >= rgtIdx);
  assert(blkNb > 0);
  // supported up to 64bit strb
  assert(blkNb <= 8);

  std::lock_guard<std::mutex> lock(mutex_);
  int strbMask = 0xff;
  if (wlen_ > 0) {
    for (int idx = 0 ; idx < blkNb; ++idx) {
      int strbFlags = (wr_strb >> (idx * 8)) & strbMask;
      if (!(strbFlags == 0 || strbFlags == strbMask)) {
        LOG(FATAL) << "Unexpected strb data " << (void*)wr_strb;
      }
      if (strbFlags != 0) {
        uint64_t* elemPtr = (uint64_t*)svGetArrElemPtr1(value, rgtIdx + idx);
        assert(elemPtr != NULL);
        waddr_[idx] = (*elemPtr);
      }
    }
    waddr_ += blkNb;
    wlen_ -= 1;
  }
}

class DPIModule final : public DPIModuleNode {
 public:
  ~DPIModule() {
    if (lib_handle_) Unload();
  }

  const char* type_key() const final {
    return "vta-tsim";
  }

  PackedFunc GetFunction(
      const std::string& name,
      const ObjectPtr<Object>& sptr_to_self) final {
    if (name == "WriteReg") {
      return TypedPackedFunc<void(int, int)>(
        [this](int addr, int value){
           this->WriteReg(addr, value);
        });
    } else {
      LOG(FATAL) << "Member " << name << "does not exists";
      return nullptr;
    }
  }

  void Init(const std::string& name) {
    Load(name);
    VTADPIInitFunc finit =  reinterpret_cast<VTADPIInitFunc>(
      GetSymbol("VTADPIInit"));
    CHECK(finit != nullptr);
    finit(this, VTASimDPI, VTAHostDPI, VTAMemDPI);
    ftsim_ = reinterpret_cast<VTADPISimFunc>(GetSymbol("VTADPISim"));
    CHECK(ftsim_ != nullptr);
  }

  void SimLaunch() {
    auto frun = [this]() {
      (*ftsim_)();
    };
    tsim_thread_ = std::thread(frun);
  }

  void SimWait() {
    sim_device_.Wait();
  }

  void SimResume() {
    sim_device_.Resume();
  }

  void SimFinish() {
    sim_device_.Exit();
    tsim_thread_.join();
  }

  void WriteReg(int addr, uint32_t value) {
    host_device_.PushRequest(1, addr, value);
  }

  uint32_t ReadReg(int addr) {
    uint32_t value;
    HostResponse* r = new HostResponse;
    host_device_.PushRequest(0, addr, 0);
    host_device_.WaitPopResponse(r);
    value = r->value;
    delete r;
    return value;
  }

 protected:
  VTADPISimFunc ftsim_;
  SimDevice sim_device_;
  HostDevice host_device_;
  MemDevice mem_device_;
  std::thread tsim_thread_;

  void SimDPI(dpi8_t* wait,
              dpi8_t* exit) {
    *wait = sim_device_.GetWaitStatus();
    *exit = sim_device_.GetExitStatus();
  }

  void HostDPI(dpi8_t* req_valid,
               dpi8_t* req_opcode,
               dpi8_t* req_addr,
               dpi32_t* req_value,
               dpi8_t req_deq,
               dpi8_t resp_valid,
               dpi32_t resp_value) {
    HostRequest* r = new HostRequest;
    *req_valid = host_device_.TryPopRequest(r, req_deq);
    *req_opcode = r->opcode;
    *req_addr = r->addr;
    *req_value = r->value;
    if (resp_valid) {
      host_device_.PushResponse(resp_value);
    }
    delete r;
  }

  void MemDPI(
      dpi8_t rd_req_valid,
      dpi8_t rd_req_len,
      dpi8_t rd_req_id,
      dpi64_t rd_req_addr,
      dpi8_t wr_req_valid,
      dpi8_t wr_req_len,
      dpi64_t wr_req_addr,
      dpi8_t wr_valid,
      const svOpenArrayHandle wr_value,
      dpi64_t wr_strb,
      dpi8_t* rd_valid,
      dpi8_t*  rd_id,
      const svOpenArrayHandle rd_value,
      dpi8_t rd_ready) {
    
    // check data pointers
    // data is expected to come in 64bit chunks
    // up to 512 bits total
    // more bits require wider strb data
    assert(wr_value != NULL);
    assert(svDimensions(wr_value) == 1);
    assert(svSize(wr_value, 1) <= 8);
    assert(svSize(wr_value, 0) == 64);
    assert(rd_value != NULL);
    assert(svDimensions(rd_value) == 1);
    assert(svSize(rd_value, 1) <= 8);
    assert(svSize(rd_value, 0) == 64);
    
    int lftIdx = svLeft(rd_value, 1);
    int rgtIdx = svRight(rd_value, 1);
    int blkNb  = lftIdx - rgtIdx + 1;
    assert(lftIdx >= 0);
    assert(rgtIdx >= 0);
    assert(lftIdx >= rgtIdx);
    assert(blkNb > 0);

    if (wr_valid) {
      mem_device_.WriteData(wr_value, wr_strb);
    }
    if (rd_req_valid || wr_req_valid) {
     mem_device_.SetRequest(
       rd_req_valid,
       rd_req_addr,
       rd_req_len,
       rd_req_id,
       wr_req_addr,
       wr_req_len,
       wr_req_valid);
    }

    
    MemResponse r = mem_device_.ReadData(rd_ready, blkNb);
    *rd_valid = r.valid;
    for (int idx = 0; idx < blkNb; idx ++) {
      uint64_t* dataPtr = (uint64_t*)svGetArrElemPtr1(rd_value, rgtIdx + idx);
      assert(dataPtr != NULL);
      (*dataPtr) = r.value[idx];
    }
    *rd_id     = r.id;
  }

  static void VTASimDPI(
      VTAContextHandle self,
      dpi8_t* wait,
      dpi8_t* exit) {
    static_cast<DPIModule*>(self)->SimDPI(
        wait, exit);
  }

  static void VTAHostDPI(
      VTAContextHandle self,
      dpi8_t* req_valid,
      dpi8_t* req_opcode,
      dpi8_t* req_addr,
      dpi32_t* req_value,
      dpi8_t req_deq,
      dpi8_t resp_valid,
      dpi32_t resp_value) {
    static_cast<DPIModule*>(self)->HostDPI(
      req_valid, req_opcode, req_addr,
      req_value, req_deq, resp_valid, resp_value);
  }

  static void VTAMemDPI(
    VTAContextHandle self,
    dpi8_t rd_req_valid,
    dpi8_t rd_req_len,
    dpi8_t rd_req_id,
    dpi64_t rd_req_addr,
    dpi8_t wr_req_valid,
    dpi8_t wr_req_len,
    dpi64_t wr_req_addr,
    dpi8_t wr_valid,
    const svOpenArrayHandle wr_value,
    dpi64_t wr_strb,
    dpi8_t* rd_valid,
    dpi8_t*   rd_id,    
    const svOpenArrayHandle rd_value,
    dpi8_t rd_ready) {
    static_cast<DPIModule*>(self)->MemDPI(
      rd_req_valid, rd_req_len, rd_req_id,
      rd_req_addr, wr_req_valid, wr_req_len, wr_req_addr,
      wr_valid, wr_value, wr_strb,
      rd_valid, rd_id, rd_value, rd_ready);
  }

 private:
  // Platform dependent handling.
#if defined(_WIN32)
  // library handle
  HMODULE lib_handle_{nullptr};
  // Load the library
  void Load(const std::string& name) {
    // use wstring version that is needed by LLVM.
    std::wstring wname(name.begin(), name.end());
    lib_handle_ = LoadLibraryW(wname.c_str());
    CHECK(lib_handle_ != nullptr)
        << "Failed to load dynamic shared library " << name;
  }
  void* GetSymbol(const char* name) {
    return reinterpret_cast<void*>(
        GetProcAddress(lib_handle_, (LPCSTR)name)); // NOLINT(*)
  }
  void Unload() {
    FreeLibrary(lib_handle_);
  }
#else
  // Library handle
  void* lib_handle_{nullptr};
  // load the library
  void Load(const std::string& name) {
    lib_handle_ = dlopen(name.c_str(), RTLD_LAZY | RTLD_LOCAL);
    CHECK(lib_handle_ != nullptr)
        << "Failed to load dynamic shared library " << name
        << " " << dlerror();
  }
  void* GetSymbol(const char* name) {
    return dlsym(lib_handle_, name);
  }
  void Unload() {
    dlclose(lib_handle_);
  }
#endif
};

Module DPIModuleNode::Load(std::string dll_name) {
  auto n = make_object<DPIModule>();
  n->Init(dll_name);
  return Module(n);
}

TVM_REGISTER_GLOBAL("runtime.module.loadfile_vta-tsim")
.set_body([](TVMArgs args, TVMRetValue* rv) {
    *rv = DPIModuleNode::Load(args[0]);
  });
}  // namespace dpi
}  // namespace vta
