#ifndef VTA_INTEL_FOCL_DEVICE_H_
#define VTA_INTEL_FOCL_DEVICE_H_

#include <list>
#include <string>
#include "CL/opencl.h"

#define IFOCL_MEM_OFF_ERR (SIZE_MAX)

enum kernel_index {KERNEL_VTA_CORE, NUM_OCL_KERNELS};
static std::string kernel_names[] = {"vta_core"};

typedef size_t ifocl_mem_off_t;

typedef struct
{
    ifocl_mem_off_t offset;
    size_t size;
    bool occupied;
} mem_chunk_t;

class IntelFOCLDevice {
    private:
        cl_context _context;
        cl_program _program;
        cl_mem _mem;
        cl_kernel _kernels[NUM_OCL_KERNELS];
        cl_command_queue _queues[NUM_OCL_KERNELS];
        std::list<mem_chunk_t> _mem_chunks;
        size_t _alignment;

    public:
        IntelFOCLDevice() { init(4*1024*1024*1024ULL, "vta_opencl.aocx"); }

        int init(size_t mem_size, std::string aocx_file);

        ifocl_mem_off_t alloc(size_t size);

        void free(ifocl_mem_off_t offset);

        void write_mem(ifocl_mem_off_t offset, const void *buf, size_t nbyte);

        void read_mem(ifocl_mem_off_t offset, void *buf, size_t nbyte);

        int execute_instructions(ifocl_mem_off_t offset, size_t count);

        void deinit();

        ~IntelFOCLDevice();
};

#endif  // VTA_INTEL_FOCL_DEVICE_H_

