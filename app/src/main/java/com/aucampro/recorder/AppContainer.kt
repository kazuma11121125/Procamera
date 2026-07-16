package com.procamera.recorder

import android.content.Context

/**
 * Manual dependency container (see [ProCameraApplication]). Populated incrementally as
 * camera/audio/encoder/muxer subsystems are implemented (Phase 3–4); intentionally holds
 * no members yet so this compiles cleanly ahead of those phases.
 */
class AppContainer(private val appContext: Context)
