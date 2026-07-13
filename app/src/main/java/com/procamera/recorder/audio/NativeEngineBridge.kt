package com.procamera.recorder.audio

/**
 * JNI bridge to the C++ Oboe engine (`app/src/main/cpp/jni/native-lib.cpp`). Phase 1 only
 * exposes a version probe to verify the native toolchain links against Oboe; the real
 * start/stop/EQ/meter surface is added in Phase 2–3.
 */
object NativeEngineBridge {
    init {
        System.loadLibrary("procamera_native")
    }

    external fun nativeOboeVersion(): String
}
