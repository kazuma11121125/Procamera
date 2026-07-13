package com.procamera.recorder

import android.app.Application

/**
 * Composition root. Dependency wiring uses manual DI via [AppContainer] rather than
 * Hilt/Dagger — see docs/ARCHITECTURE.md §DI方式の明確化 for the rationale
 * (single :app module, moderate graph size, avoiding KSP/KAPT build overhead on top of
 * an already NDK-heavy build).
 */
class ProCameraApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
