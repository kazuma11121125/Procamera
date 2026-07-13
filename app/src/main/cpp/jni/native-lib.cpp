#include <jni.h>
#include <oboe/Oboe.h>
#include <string>

// Phase 1 build-verification stub: proves the CMake + NDK r27d + Oboe(prefab) toolchain
// resolves and links correctly. Real JNI bindings (engine start/stop, EQ param push,
// meter pull, ring buffer wiring) are added in Phase 2 (§0 output plan).
extern "C" JNIEXPORT jstring JNICALL
Java_com_procamera_recorder_audio_NativeEngineBridge_nativeOboeVersion(JNIEnv *env, jobject /* this */) {
    std::string version = "oboe " + std::string(oboe::Version::Text);
    return env->NewStringUTF(version.c_str());
}
