#pragma once

#include <android/log.h>

// Thin wrapper so call sites don't repeat the tag. NEVER call these from the audio
// callback thread (§4.2 forbids logging from RT context) — only from UI/coroutine-thread
// code such as stream open/close/fallback-decision paths.
#define AUCAMPRO_TAG "AuCamPRONative"
#define AUCAMPRO_LOGI(...) __android_log_print(ANDROID_LOG_INFO, AUCAMPRO_TAG, __VA_ARGS__)
#define AUCAMPRO_LOGW(...) __android_log_print(ANDROID_LOG_WARN, AUCAMPRO_TAG, __VA_ARGS__)
#define AUCAMPRO_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, AUCAMPRO_TAG, __VA_ARGS__)
