#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "PhtonView", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "PhtonView", __VA_ARGS__)

// 无依赖的原生引导库。
// Android 的 System.load() 默认以 RTLD_LOCAL 加载 so，导致 ltdl_preload 导出的
// lt_libltdl_LTX_preloaded_symbols 对后续加载的 libltdl.so 不可见；
// 而已经以 RTLD_LOCAL 打开的库在部分 Android 版本上无法通过再次 dlopen(RTLD_GLOBAL)
// 提升为全局可见。因此使用本库作为“引导器”：它本身没有任何依赖，先被 System.load
// 加载后，通过 JNI 以 dlopen(RTLD_GLOBAL | RTLD_NOW) 按正确顺序打开所有依赖库，
// 使 ltdl_preload 的符号在 libltdl.so 加载前进入全局命名空间。
extern "C" JNIEXPORT jboolean JNICALL
Java_com_phtontools_phtonview_ndk_NativeLibraryLoader_loadLibrariesGlobally(
        JNIEnv *env, jobject /*thiz*/, jobjectArray paths) {
    jsize len = env->GetArrayLength(paths);
    for (jsize i = 0; i < len; ++i) {
        jstring pathStr = reinterpret_cast<jstring>(env->GetObjectArrayElement(paths, i));
        if (!pathStr) {
            LOGE("NativeBootstrap: null path at index %d", i);
            return JNI_FALSE;
        }
        const char *path = env->GetStringUTFChars(pathStr, nullptr);
        if (!path) {
            LOGE("NativeBootstrap: failed to get UTF chars for path at index %d", i);
            return JNI_FALSE;
        }
        void *handle = dlopen(path, RTLD_GLOBAL | RTLD_NOW);
        if (!handle) {
            LOGE("NativeBootstrap: failed to load %s: %s", path, dlerror());
            env->ReleaseStringUTFChars(pathStr, path);
            return JNI_FALSE;
        }
        LOGI("NativeBootstrap: loaded %s into global namespace", path);
        env->ReleaseStringUTFChars(pathStr, path);
    }
    return JNI_TRUE;
}
