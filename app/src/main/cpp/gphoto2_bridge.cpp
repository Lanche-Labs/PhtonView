#include <jni.h>
#include <android/log.h>
#include <string>
#include <cstdlib>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "PhtonView", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "PhtonView", __VA_ARGS__)

#ifdef HAVE_GPHOTO2
#include <gphoto2/gphoto2.h>
#include <gphoto2/gphoto2-port.h>
#endif

static bool gphoto2_initialized = false;

#ifdef HAVE_GPHOTO2
static std::string g_basePath;
static GPContext* g_context = nullptr;
static Camera* g_camera = nullptr;

static bool setGphotoPaths(const std::string& basePath) {
    std::string camlibs = basePath + "/libgphoto2/2.5.31";
    std::string iolibs = basePath + "/libgphoto2_port/0.12.2";
    int r1 = setenv("CAMLIBS", camlibs.c_str(), 1);
    int r2 = setenv("IOLIBS", iolibs.c_str(), 1);
    LOGI("CAMLIBS=%s (rc=%d)", camlibs.c_str(), r1);
    LOGI("IOLIBS=%s (rc=%d)", iolibs.c_str(), r2);
    return r1 == 0 && r2 == 0;
}
#endif

extern "C" JNIEXPORT jboolean JNICALL
Java_com_phtontools_phtonview_ndk_Gphoto2Bridge_nativeInit(
        JNIEnv *env, jobject /*thiz*/, jstring modulesBasePath) {
    const char *path = env->GetStringUTFChars(modulesBasePath, nullptr);
    LOGI("GPhoto2 bridge init called, modules base path: %s", path);
#ifdef HAVE_GPHOTO2
    std::string basePath(path ? path : "");
    env->ReleaseStringUTFChars(modulesBasePath, path);
    if (basePath.empty()) {
        LOGE("modules base path is empty");
        return JNI_FALSE;
    }
    setGphotoPaths(basePath);

    g_context = gp_context_new();
    if (!g_context) {
        LOGE("gp_context_new failed");
        return JNI_FALSE;
    }

    CameraList *list = nullptr;
    gp_list_new(&list);
    int ret = gp_camera_autodetect(list, g_context);
    int count = (ret >= GP_OK && list) ? gp_list_count(list) : 0;
    LOGI("gp_camera_autodetect returned %d, cameras found: %d", ret, count);
    if (list) {
        gp_list_free(list);
    }
    gphoto2_initialized = true;
    return JNI_TRUE;
#else
    env->ReleaseStringUTFChars(modulesBasePath, path);
    LOGI("libgphoto2 not linked yet");
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_phtontools_phtonview_ndk_Gphoto2Bridge_nativeListCameras(JNIEnv *env, jobject /*thiz*/) {
    std::string result = "[]";
#ifdef HAVE_GPHOTO2
    if (!gphoto2_initialized || !g_context) {
        LOGE("libgphoto2 not initialized");
        return env->NewStringUTF(result.c_str());
    }
    CameraList *list = nullptr;
    gp_list_new(&list);
    int ret = gp_camera_autodetect(list, g_context);
    if (ret >= GP_OK) {
        int count = gp_list_count(list);
        result = "[";
        for (int i = 0; i < count; i++) {
            const char *name = nullptr;
            const char *value = nullptr;
            gp_list_get_name(list, i, &name);
            gp_list_get_value(list, i, &value);
            if (i > 0) result += ",";
            result += "{\"index\":" + std::to_string(i) +
                      ",\"model\":\"" + (name ? name : "") + "\"" +
                      ",\"port\":\"" + (value ? value : "") + "\"}";
        }
        result += "]";
    } else {
        LOGE("gp_camera_autodetect failed: %d", ret);
    }
    if (list) gp_list_free(list);
#else
    LOGI("libgphoto2 not linked yet");
#endif
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_phtontools_phtonview_ndk_Gphoto2Bridge_nativeConnect(
        JNIEnv *env, jobject /*thiz*/, jint index) {
    LOGI("GPhoto2 connect called for camera index %d", index);
#ifdef HAVE_GPHOTO2
    if (!gphoto2_initialized || !g_context) {
        LOGE("libgphoto2 not initialized");
        return JNI_FALSE;
    }
    if (g_camera) {
        gp_camera_exit(g_camera, g_context);
        gp_camera_free(g_camera);
        g_camera = nullptr;
    }

    CameraList *list = nullptr;
    gp_list_new(&list);
    int ret = gp_camera_autodetect(list, g_context);
    if (ret < GP_OK) {
        LOGE("autodetect failed: %d", ret);
        gp_list_free(list);
        return JNI_FALSE;
    }
    int count = gp_list_count(list);
    if (index < 0 || index >= count) {
        LOGE("camera index %d out of range (count=%d)", index, count);
        gp_list_free(list);
        return JNI_FALSE;
    }

    const char *model = nullptr;
    const char *port = nullptr;
    gp_list_get_name(list, index, &model);
    gp_list_get_value(list, index, &port);

    CameraAbilitiesList *abilities = nullptr;
    GPPortInfoList *portinfo = nullptr;
    gp_abilities_list_new(&abilities);
    gp_abilities_list_load(abilities, g_context);
    gp_port_info_list_new(&portinfo);
    gp_port_info_list_load(portinfo);

    ret = gp_camera_new(&g_camera);
    if (ret >= GP_OK) {
        int m = gp_abilities_list_lookup_model(abilities, model);
        CameraAbilities a;
        gp_abilities_list_get_abilities(abilities, m, &a);
        gp_camera_set_abilities(g_camera, a);

        int p = gp_port_info_list_lookup_path(portinfo, port);
        GPPortInfo info;
        gp_port_info_list_get_info(portinfo, p, &info);
        gp_camera_set_port_info(g_camera, info);

        ret = gp_camera_init(g_camera, g_context);
        LOGI("gp_camera_init returned %d", ret);
    }

    gp_port_info_list_free(portinfo);
    gp_abilities_list_free(abilities);
    gp_list_free(list);

    return ret >= GP_OK ? JNI_TRUE : JNI_FALSE;
#else
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_phtontools_phtonview_ndk_Gphoto2Bridge_nativeCapture(JNIEnv *env, jobject /*thiz*/) {
    LOGI("GPhoto2 capture called");
#ifdef HAVE_GPHOTO2
    if (!g_camera || !g_context) {
        LOGE("camera not connected");
        return JNI_FALSE;
    }
    CameraFilePath path;
    int ret = gp_camera_capture(g_camera, GP_CAPTURE_IMAGE, &path, g_context);
    LOGI("gp_camera_capture returned %d", ret);
    return ret >= GP_OK ? JNI_TRUE : JNI_FALSE;
#else
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_phtontools_phtonview_ndk_Gphoto2Bridge_nativeExecuteCommand(
        JNIEnv *env, jobject /*thiz*/, jstring command) {
    const char *cmd = env->GetStringUTFChars(command, nullptr);
    LOGI("GPhoto2 execute command: %s", cmd);
    std::string result;
#ifdef HAVE_GPHOTO2
    if (std::string(cmd) == "summary") {
        CameraText text;
        int ret = gp_camera_get_summary(g_camera, &text, g_context);
        result = (ret >= GP_OK) ? text.text : ("ERR: " + std::to_string(ret));
    } else if (std::string(cmd) == "trigger") {
        int ret = gp_camera_trigger_capture(g_camera, g_context);
        result = (ret >= GP_OK) ? "OK: triggered" : ("ERR: " + std::to_string(ret));
    } else {
        result = "ERR: unknown command: " + std::string(cmd);
    }
#else
    result = "ERR: libgphoto2 not linked yet. Command: " + std::string(cmd);
#endif
    env->ReleaseStringUTFChars(command, cmd);
    return env->NewStringUTF(result.c_str());
}
