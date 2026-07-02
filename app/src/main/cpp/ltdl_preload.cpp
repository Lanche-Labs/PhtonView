#include <jni.h>
#include <dlfcn.h>
#include <ltdl.h>
#include <android/log.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "PhtonView", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "PhtonView", __VA_ARGS__)

// libltdl 在部分 Android 预编译产物中引用了 lt_libltdl_LTX_preloaded_symbols，
// 但该符号并未被导出，导致 dlopen 失败。此处提供一个最小的预加载符号表，
// 使 libltdl.so 能正常加载。该符号需要在加载 libltdl.so 之前已存在于链接器
// 命名空间中，因此本文件被单独编译为 ltdl_preload.so 并最先加载。
// 参见 ltdl.h 中 lt_preloaded_symbols 与 LT_DLSYM_CONST 的定义。
__attribute__((visibility("default")))
extern "C" const lt_dlsymlist lt_libltdl_LTX_preloaded_symbols[] = {
    { 0, 0 }
};

// System.load 默认使用 RTLD_LOCAL，导致本 so 导出的符号对后续通过 DT_NEEDED
// 加载的库不可见。通过 JNI 以 RTLD_GLOBAL 重新打开自身，可将符号提升到全局
// 命名空间，供 libltdl.so 解析缺失符号。
__attribute__((visibility("default")))
extern "C" JNIEXPORT jboolean JNICALL
Java_com_phtontools_phtonview_ndk_NativeLibraryLoader_loadLibrariesGlobally(
        JNIEnv *env, jobject /*thiz*/, jobjectArray paths) {
    jsize len = env->GetArrayLength(paths);
    for (jsize i = 0; i < len; ++i) {
        jstring pathStr = reinterpret_cast<jstring>(env->GetObjectArrayElement(paths, i));
        if (!pathStr) {
            LOGE("Null path at index %d", i);
            return JNI_FALSE;
        }
        const char *path = env->GetStringUTFChars(pathStr, nullptr);
        if (!path) {
            LOGE("Failed to get UTF chars for path at index %d", i);
            return JNI_FALSE;
        }
        void *handle = dlopen(path, RTLD_GLOBAL | RTLD_NOW);
        if (!handle) {
            LOGE("Failed to load %s: %s", path, dlerror());
            env->ReleaseStringUTFChars(pathStr, path);
            return JNI_FALSE;
        }
        LOGI("Loaded %s into global namespace", path);
        env->ReleaseStringUTFChars(pathStr, path);
    }
    return JNI_TRUE;
}
