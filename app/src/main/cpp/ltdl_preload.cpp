#include <ltdl.h>

// libltdl 在部分 Android 预编译产物中引用了 lt_libltdl_LTX_preloaded_symbols，
// 但该符号并未被导出，导致 dlopen 失败。此处提供一个最小的预加载符号表，
// 使 libltdl.so 能正常加载。该符号需要在加载 libltdl.so 之前已存在于链接器
// 全局命名空间中，因此本文件被单独编译为 ltdl_preload.so 并最先以 RTLD_GLOBAL
// 加载。参见 ltdl.h 中 lt_preloaded_symbols 与 LT_DLSYM_CONST 的定义。
//
// **修复**（issue #104/#105/#111-#117）：之前 NDK 默认 -fvisibility=hidden 导致
// 该变量在 strip 之后动态符号表为空；Android linker 在 dlopen(RTLD_GLOBAL) ltdl.so
// 时找不到 preloaded_symbols。CMakeLists.txt 已加 -fvisibility=default 与
// -Wl,--export-dynamic，此处补 __attribute__((used)) 防止 LTO 删除变量，
// extern "C" 防止 C++ name mangling 让符号名 lt_libltdl_LTX_preloaded_symbols
// 真正出现在 ELF 符号表。
__attribute__((visibility("default"), used))
extern "C" const lt_dlsymlist lt_libltdl_LTX_preloaded_symbols[] = {
    { 0, 0 }
};
// 显式 dummy 引用，防止 LTO / strip 完全删掉上面的空表（链接器可能判定"未被引用
// 因而可以丢弃"）。下面的函数在链接时通过 -Wl,--export-dynamic 进入动态符号表，
// 顺带强制保留同 so 的全部 default-visibility 符号。
extern "C" __attribute__((visibility("default"), used))
const void* phtonview_ltdl_preload_anchor =
    (const void*)&lt_libltdl_LTX_preloaded_symbols;
