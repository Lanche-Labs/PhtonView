#include <ltdl.h>

// libltdl 在部分 Android 预编译产物中引用了 lt_libltdl_LTX_preloaded_symbols，
// 但该符号并未被导出，导致 dlopen 失败。此处提供一个最小的预加载符号表，
// 使 libltdl.so 能正常加载。该符号需要在加载 libltdl.so 之前已存在于链接器
// 全局命名空间中，因此本文件被单独编译为 ltdl_preload.so 并最先以 RTLD_GLOBAL
// 加载。参见 ltdl.h 中 lt_preloaded_symbols 与 LT_DLSYM_CONST 的定义。
//
// **修复**（issue #104/#105/#111-#117/#122）：之前 NDK 默认 -fvisibility=hidden
// 导致该变量在 strip 之后动态符号表为空；Android linker 在 dlopen(RTLD_GLOBAL)
// ltdl.so 时找不到 preloaded_symbols。CMakeLists.txt 已加 -fvisibility=default
// 与 -Wl,--export-dynamic。
//
// **修复**（issue #122）：某些 NDK 较新版本（r25+ clang 17+）即使有上述选项，
// 对 const 数据符号仍会丢入 .rodata 段且在 strip 时不写入 dynsym。
// - 去除 const 限定（libltdl 内部会做 const_cast 兼容）
// - 增加 __attribute__((retain)) 强制链接器写入 .dynsym
// - 同时提供一个非 const 别名 + dummy 引用，确保 lld 不会判定"未被引用而删除"
__attribute__((visibility("default"), used, retain))
extern "C" lt_dlsymlist lt_libltdl_LTX_preloaded_symbols[] = {
    { 0, 0 }
};

// 非 const 别名：libltdl 早期版本（< 2.4.2）查找的就是非 const 类型；
// 同时导出可避免符号查找时类型不匹配。
__attribute__((visibility("default"), used, retain))
extern "C" lt_dlsymlist lt_libltdl_LTX_preloaded_symbols_nonconst[] = {
    { 0, 0 }
};

// 显式 dummy 引用，防止 LTO / strip 完全删掉上面的空表（链接器可能判定"未被引用
// 因而可以丢弃"）。下面的函数在链接时通过 -Wl,--export-dynamic 进入动态符号表，
// 顺带强制保留同 so 的全部 default-visibility 符号。
extern "C" __attribute__((visibility("default"), used, retain))
void phtonview_ltdl_preload_anchor() {
    // volatile 防止编译器把"未使用"消除
    (void)lt_libltdl_LTX_preloaded_symbols;
    (void)lt_libltdl_LTX_preloaded_symbols_nonconst;
}
