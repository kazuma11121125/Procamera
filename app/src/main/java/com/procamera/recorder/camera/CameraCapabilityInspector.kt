package com.procamera.recorder.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build

/**
 * §1.1's device-capability gating: selects the default rear standard lens, checks HW
 * level, and verifies (via MediaCodecList, not just INFO_SUPPORTED_HARDWARE_LEVEL) that
 * a given resolution/fps/bitrate combination is actually encodable before committing to
 * it — FULL hardware level does not guarantee a specific codec configuration is
 * supported.
 */
class CameraCapabilityInspector(private val cameraManager: CameraManager) {

    data class LensInfo(
        val cameraId: String,
        val focalLengthMm: Float,
        val isStandardRearLens: Boolean,
    )

    private data class RearCandidate(
        val cameraId: String,
        val focalLengthMm: Float,
        val equivalentFocalLengthMm: Float,
        val isLogicalMultiCamera: Boolean,
        val hardwareLevel: Int,
        val sensorDiagonalMm: Float,
    )

    /**
     * Enumerates back-facing lenses and identifies the "standard" one — i.e. not the
     * ultra-wide, telephoto, or an auxiliary macro/depth sensor on multi-lens devices —
     * rather than assuming array index 0 (§1.1's explicit requirement, since HAL lens
     * ordering is not a documented contract).
     *
     * **実機で修正済み(確信度の教訓)**: 当初は「35mm換算焦点距離が28mmに最も近いレンズ」
     * という素朴なヒューリスティックのみで選定していたが、実機(Sony SO-51C)で
     * **誤って超小型センサーの補助レンズ(センサー対角線3.0mm、焦点距離2.14mm)を
     * 「標準」として選んでしまうバグを実際に確認した** — 極小センサー×極短焦点距離の
     * 組み合わせが偶然28mm付近の換算値を生んだため。この失敗を受け、
     * `REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA`(Android公式が「アプリは
     * これをデフォルトのメインカメラとして使うべき」と意図して用意している、より
     * 直接的なシグナル)を最優先の判定基準とし、極小センサー(対角線5mm未満、
     * コンパクトデジカメの1/2.3型センサーの対角線ですら約7.7mmある水準)を候補から
     * 除外したうえで、焦点距離ヒューリスティックとHWレベルを副次的なタイブレークに
     * 降格した。それでも複数OEMでの網羅的な実機検証(Phase5)なしに全機種での
     * 正しさを断定はできない。
     */
    fun findStandardRearLens(): LensInfo? {
        val candidates = cameraManager.cameraIdList.mapNotNull { id ->
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing != CameraCharacteristics.LENS_FACING_BACK) return@mapNotNull null

            val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val focalLength = focalLengths?.minOrNull()
            if (focalLength == null || sensorSize == null) return@mapNotNull null

            val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            val isLogicalMultiCamera = capabilities?.contains(
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA,
            ) == true

            // 35mm-equivalent focal length: physical_focal_length * (43.27mm / sensor_diagonal_mm),
            // 43.27mm being the diagonal of a full-frame (36x24mm) sensor.
            val sensorDiagonalMm = kotlin.math.hypot(sensorSize.width, sensorSize.height)
            val equivalentFocalLengthMm = focalLength * (FULL_FRAME_DIAGONAL_MM / sensorDiagonalMm)

            RearCandidate(
                cameraId = id,
                focalLengthMm = focalLength,
                equivalentFocalLengthMm = equivalentFocalLengthMm,
                isLogicalMultiCamera = isLogicalMultiCamera,
                hardwareLevel = hardwareLevel(id),
                sensorDiagonalMm = sensorDiagonalMm,
            )
        }.filter { it.sensorDiagonalMm >= MIN_PLAUSIBLE_MAIN_SENSOR_DIAGONAL_MM }

        if (candidates.isEmpty()) return null

        val standard = candidates
            .sortedWith(
                compareByDescending<RearCandidate> { it.isLogicalMultiCamera }
                    .thenByDescending { it.hardwareLevel == CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL }
                    .thenBy { kotlin.math.abs(it.equivalentFocalLengthMm - NORMAL_LENS_EQUIVALENT_MM) },
            )
            .first()

        return LensInfo(
            cameraId = standard.cameraId,
            focalLengthMm = standard.focalLengthMm,
            isStandardRearLens = true,
        )
    }

    fun hardwareLevel(cameraId: String): Int {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        return characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            ?: CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY
    }

    fun isFullOrBetter(cameraId: String): Boolean {
        return when (hardwareLevel(cameraId)) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL,
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3,
            -> true
            else -> {
                // LEVEL_EXTERNAL is a value >= FULL numerically on some API levels only
                // from API 30; guard explicitly rather than relying on numeric ordering.
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    hardwareLevel(cameraId) == CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL
            }
        }
    }

    fun timestampSource(cameraId: String): Int {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        return characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
            ?: CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN
    }

    /**
     * §1.1's explicit requirement: `INFO_SUPPORTED_HARDWARE_LEVEL=FULL` does not
     * guarantee a specific resolution/fps/bitrate/codec combination is actually
     * supported. Checks the real encoder capability via `MediaCodecList` rather than
     * inferring it from the camera's hardware level.
     */
    fun isVideoConfigSupported(
        mimeType: String,
        width: Int,
        height: Int,
        frameRate: Int,
        bitrate: Int,
    ): Boolean {
        val format = MediaFormat.createVideoFormat(mimeType, width, height)
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val codecName = codecList.findEncoderForFormat(format) ?: return false

        val codecInfo = codecList.codecInfos.firstOrNull { it.name == codecName } ?: return false
        val videoCapabilities = codecInfo.getCapabilitiesForType(mimeType).videoCapabilities ?: return false

        if (!videoCapabilities.isSizeSupported(width, height)) return false
        if (!videoCapabilities.getSupportedFrameRatesFor(width, height).contains(frameRate.toDouble())) return false
        if (!videoCapabilities.bitrateRange.contains(bitrate)) return false
        return true
    }

    /**
     * §1.2's primary target with the §1.2 fallback, expressed as an ordered list:
     * callers should try each in order and use the first one [isVideoConfigSupported]
     * accepts.
     */
    fun videoConfigCandidates(): List<VideoConfigCandidate> = listOf(
        VideoConfigCandidate(MediaFormat.MIMETYPE_VIDEO_HEVC, 3840, 2160, 30, 50_000_000),
        VideoConfigCandidate(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080, 60, 20_000_000),
    )

    data class VideoConfigCandidate(
        val mimeType: String,
        val width: Int,
        val height: Int,
        val frameRate: Int,
        val bitrate: Int,
    )

    /**
     * §4.1's `INFO_SUPPORTED_HARDWARE_LEVEL_FULL` gate: on LIMITED devices, manual WB
     * (COLOR_CORRECTION_MODE_TRANSFORM_MATRIX) support is not guaranteed and the UI
     * should grey it out. Checks the actual advertised capability rather than inferring
     * it purely from hardware level, since some LIMITED devices do advertise it.
     *
     * **実機で修正済み**: 当初 `CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_MODES` という
     * キーを使っていたが、これは実機(Sony SO-51C, Android 14 / API 34)で
     * `NoSuchFieldError` を起こしてクラッシュした — compileSdk 36 の android.jar には
     * このシンボルが存在しコンパイルは通ったが、実行時の端末側 framework.jar には
     * 存在しない(このキー自体が実在しないか、API34より新しいAPIでのみ有効という
     * ことを実機で確認)。API21から一貫して存在する
     * `REQUEST_AVAILABLE_CAPABILITIES` の `MANUAL_POST_PROCESSING` ケイパビリティで
     * 判定する、より安全な方式に置き換えた。
     */
    fun supportsManualWhiteBalance(cameraId: String): Boolean {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val availableAwbModes = characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
        val availableCapabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        val hasAwbOff = availableAwbModes?.contains(CameraMetadata.CONTROL_AWB_MODE_OFF) == true
        val hasManualPostProcessing = availableCapabilities?.contains(
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING,
        ) == true
        return hasAwbOff && hasManualPostProcessing
    }

    // -----------------------------------------------------------------------------------------
    // Multi-lens enumeration (added for lens-switcher UI)
    // -----------------------------------------------------------------------------------------

    /**
     * Represents a single rear-facing physical (or logical-multi) camera available on
     * this device, enriched with the metadata the lens-switcher UI and zoom controller
     * need at startup.
     *
     * [equivalentFocalLengthMm] is the 35mm-film-equivalent focal length computed via
     * the same formula used in [findStandardRearLens]: `physicalFocalLength × (43.27 /
     * sensorDiagonalMm)`.  [zoomLabel] is derived from `equivalentFocalLengthMm / 28`
     * and formatted as "0.6x", "1x", "2x", etc. (see [allRearLenses] for rounding
     * rules).  [maxDigitalZoom] is read directly from
     * `SCALER_AVAILABLE_MAX_DIGITAL_ZOOM` — defaulting to 1f if absent — and caps the
     * zoom range that the pinch-to-zoom gesture exposes for this lens.
     */
    data class AvailableLens(
        val cameraId: String,
        val equivalentFocalLengthMm: Float,
        val zoomLabel: String,         // e.g. "0.6x", "1x", "2x"
        val isStandardLens: Boolean,
        val hardwareLevel: Int,
        val maxDigitalZoom: Float,     // from SCALER_AVAILABLE_MAX_DIGITAL_ZOOM
        val sensorDiagonalMm: Float,
    )

    /**
     * Enumerates **all** rear-facing cameras whose sensor diagonal meets the plausibility
     * floor ([MIN_PLAUSIBLE_MAIN_SENSOR_DIAGONAL_MM]), sorted by 35mm-equivalent focal
     * length ascending (ultra-wide → standard → telephoto).
     *
     * This is the source of truth for the lens-switcher strip: the UI renders one button
     * per element, highlights [AvailableLens.isStandardLens], and uses
     * [AvailableLens.zoomLabel] as the button label.
     *
     * **zoom-label rounding rules**
     * - ratio < (1.0 − 0.15) → "%.1fx" (one decimal, e.g. "0.6x")
     * - |ratio − 1.0| ≤ 0.15 → "1x"  (exact label, no decimal)
     * - ratio > (1.0 + 0.15) → "%.0fx" (no decimal, e.g. "2x", "5x")
     *
     * where `ratio = equivalentFocalLengthMm / 28.0`.
     */
    fun allRearLenses(): List<AvailableLens> {
        val standardCameraId = findStandardRearLens()?.cameraId

        return cameraManager.cameraIdList.mapNotNull { id ->
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing != CameraCharacteristics.LENS_FACING_BACK) return@mapNotNull null

            val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val focalLength = focalLengths?.minOrNull()
            if (focalLength == null || sensorSize == null) return@mapNotNull null

            // Same formula as findStandardRearLens: 35mm-equivalent = focal × (43.27 / diagonal)
            val sensorDiagonalMm = kotlin.math.hypot(sensorSize.width, sensorSize.height)
            if (sensorDiagonalMm < MIN_PLAUSIBLE_MAIN_SENSOR_DIAGONAL_MM) return@mapNotNull null

            val equivalentFocalLengthMm = focalLength * (FULL_FRAME_DIAGONAL_MM / sensorDiagonalMm)

            val ratio = equivalentFocalLengthMm / NORMAL_LENS_EQUIVALENT_MM
            val zoomLabel = when {
                ratio < 0.85f -> "%.1fx".format(ratio)  // ultra-wide, e.g. "0.6x"
                ratio <= 1.15f -> "1x"                   // within ±15% of 28mm → "1x"
                else -> "%.0fx".format(ratio)            // telephoto, e.g. "2x", "5x"
            }

            AvailableLens(
                cameraId = id,
                equivalentFocalLengthMm = equivalentFocalLengthMm,
                zoomLabel = zoomLabel,
                isStandardLens = id == standardCameraId,
                hardwareLevel = hardwareLevel(id),
                maxDigitalZoom = maxDigitalZoom(id),
                sensorDiagonalMm = sensorDiagonalMm,
            )
        }.sortedBy { it.equivalentFocalLengthMm }
    }

    /**
     * Returns every [VideoConfigCandidate] from the full candidate list that
     * [isVideoConfigSupported] confirms is actually encodable on this device, sorted
     * by descending pixel count (4K before 1080p, 1080p60 before 1080p30, etc.).
     *
     * The full candidate set checked (superset of [videoConfigCandidates]):
     * - HEVC 4K 3840×2160 30fps 50 Mbps
     * - HEVC 4K 3840×2160 24fps 40 Mbps
     * - AVC 1080p 1920×1080 60fps 20 Mbps
     * - AVC 1080p 1920×1080 30fps 10 Mbps
     * - AVC 720p 1280×720  60fps  8 Mbps
     *
     * [cameraId] is accepted as a parameter for future use (e.g. per-camera codec
     * capability queries via `CameraCharacteristics`) but the current implementation
     * delegates entirely to the device-global [isVideoConfigSupported] check, which is
     * correct for MediaCodec availability.
     */
    @Suppress("UNUSED_PARAMETER") // cameraId reserved for future per-camera codec queries
    fun supportedVideoConfigs(cameraId: String): List<VideoConfigCandidate> {
        val allCandidates = listOf(
            VideoConfigCandidate(MediaFormat.MIMETYPE_VIDEO_HEVC, 3840, 2160, 30, 50_000_000), // 16:9
            VideoConfigCandidate(MediaFormat.MIMETYPE_VIDEO_HEVC, 3840, 2880, 30, 50_000_000), // 4:3
            VideoConfigCandidate(MediaFormat.MIMETYPE_VIDEO_AVC,  2560, 1080, 30, 20_000_000), // 21:9
            VideoConfigCandidate(MediaFormat.MIMETYPE_VIDEO_AVC,  1920, 1080, 60, 20_000_000), // 16:9
            VideoConfigCandidate(MediaFormat.MIMETYPE_VIDEO_AVC,  1920, 1080, 30, 10_000_000), // 16:9
            VideoConfigCandidate(MediaFormat.MIMETYPE_VIDEO_AVC,  1440, 1080, 30, 10_000_000), // 4:3
            VideoConfigCandidate(MediaFormat.MIMETYPE_VIDEO_AVC,  1080, 1080, 30,  8_000_000), // 1:1
            VideoConfigCandidate(MediaFormat.MIMETYPE_VIDEO_AVC,  1280,  720, 60,  8_000_000), // 16:9
        )
        return allCandidates
            .filter { c ->
                isVideoConfigSupported(c.mimeType, c.width, c.height, c.frameRate, c.bitrate)
            }
            .sortedByDescending { it.width * it.height }
    }

    /**
     * Reads `SCALER_AVAILABLE_MAX_DIGITAL_ZOOM` for [cameraId], returning 1f if the
     * characteristic is absent (meaning no digital zoom beyond 1× is advertised).
     *
     * This value caps the zoom ratio exposed by the pinch-to-zoom gesture for this
     * physical lens. The camera2 API contract guarantees the crop region covers the full
     * sensor at ratio 1× and is cropped down to `1 / maxDigitalZoom` of the sensor at
     * the maximum zoom level.
     */
    fun maxDigitalZoom(cameraId: String): Float {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        return characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
    }

    private companion object {
        const val FULL_FRAME_DIAGONAL_MM = 43.27f
        const val NORMAL_LENS_EQUIVALENT_MM = 28f // typical rear "main" lens equivalent on phones

        // A real 1/2.3" compact-camera sensor (common "small sensor" reference point) has
        // a ~7.7mm diagonal; this is set well below that specifically to exclude the tiny
        // auxiliary sensors (depth/macro, often ~3mm diagonal) that caused the real-device
        // misdetection documented above, without being so aggressive it could exclude a
        // legitimate small main sensor on a budget device.
        const val MIN_PLAUSIBLE_MAIN_SENSOR_DIAGONAL_MM = 5.0f
    }
}
