#include <ltdl.h>

// libltdl 在部分 Android 预编译产物中引用了 lt_libltdl_LTX_preloaded_symbols，
// 但该符号并未被导出，导致 dlopen 失败。此处提供一个最小的预加载符号表，
// 使 libltdl.so 能正常加载。该符号需要在加载 libltdl.so 之前已存在于链接器
// 全局命名空间中，因此本文件被单独编译为 ltdl_preload.so 并最先以 RTLD_GLOBAL
// 加载。参见 ltdl.h 中 lt_preloaded_symbols 与 LT_DLSYM_CONST 的定义。
__attribute__((visibility("default")))
extern "C" const lt_dlsymlist lt_libltdl_LTX_preloaded_symbols[] = {
    { 0, 0 }
};
