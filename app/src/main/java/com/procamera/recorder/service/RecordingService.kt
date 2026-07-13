package com.procamera.recorder.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Foreground Service (camera|microphone) hosting the recording pipeline so recording
 * survives screen-off (§4.6). Full lifecycle (notification, wake lock, thermal listener,
 * crash-safe finalize) lands in Phase 4; this is a build-verification stub for Phase 1.
 */
class RecordingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
