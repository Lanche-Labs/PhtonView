#include <jni.h>
#include <android/log.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "PhtonView", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "PhtonView", __VA_ARGS__)

extern "C" JNIEXPORT jstring JNICALL
Java_com_phtontools_phtonview_ndk_NativeBridge_stringFromJNI(JNIEnv *env, jobject /*thiz*/) {
    return env->NewStringUTF("PhtonView NDK ready");
}
