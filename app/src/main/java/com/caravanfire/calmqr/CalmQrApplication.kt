package com.caravanfire.calmqr

import android.app.Application
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraXConfig

/**
 * Custom [Application] that provides a [CameraXConfig] restricted to the
 * back-facing camera only.  This prevents CameraX from probing for a
 * front-facing camera during initialization — avoiding the repeated
 * "LENS_FACING_FRONT verification failed" warnings on devices that
 * only have a rear camera (e.g. Mudita Kompakt).
 */
class CalmQrApplication : Application(), CameraXConfig.Provider {
    override fun getCameraXConfig(): CameraXConfig =
        CameraXConfig.Builder.fromConfig(Camera2Config.defaultConfig())
            .setAvailableCamerasLimiter(CameraSelector.DEFAULT_BACK_CAMERA)
            .build()
}
