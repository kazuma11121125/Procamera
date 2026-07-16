package com.procamera.recorder.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ──────────────────────────────────────────────────────────────────────────────
// ProCamera colour palette — professional dark camera UI
// ──────────────────────────────────────────────────────────────────────────────

/** Almost-black surface matching pro camera app conventions. */
val SurfaceBlack = Color(0xFF080A0C)

/** Slightly raised card/panel surface. */
val SurfaceDark = Color(0xFF12161A)

/** Subtle border / divider between panels. */
val SurfaceVariant = Color(0xFF1E2328)

/** Primary text on dark background. */
val OnSurfacePrimary = Color(0xFFE8ECF0)

/** Secondary / label text. */
val OnSurfaceSecondary = Color(0xFF8A9099)

/** Amber accent — REC indicator, active controls, highlights. */
val Amber = Color(0xFFFF9500)
val AmberDim = Color(0xFFB86A00)

/** Red for REC state. */
val RecRed = Color(0xFFE53935)
val RecRedDim = Color(0xFF7F1C1C)

// ── Audio meter colours ───────────────────────────────────────────────────────
val MeterGreen = Color(0xFF4CAF50)
val MeterYellow = Color(0xFFFFEB3B)
val MeterOrange = Color(0xFFFF9800)
val MeterRed = Color(0xFFE53935)

// ── Slider track ──────────────────────────────────────────────────────────────
val SliderTrackInactive = Color(0xFF2A2F35)
val SliderTrackActive = Amber

private val ProCameraColorScheme = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF1A0E00),
    primaryContainer = AmberDim,
    onPrimaryContainer = Color(0xFFFFDDB3),
    secondary = Color(0xFF6EAACF),
    onSecondary = Color(0xFF003548),
    background = SurfaceBlack,
    onBackground = OnSurfacePrimary,
    surface = SurfaceDark,
    onSurface = OnSurfacePrimary,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceSecondary,
    error = RecRed,
    onError = Color.White,
    outline = Color(0xFF3A4048),
)

/**
 * App-wide theme for ProCamera. Every composable inside this theme has access to
 * the professional dark colour scheme and Material 3 typography via [MaterialTheme].
 */
@Composable
fun ProCameraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ProCameraColorScheme,
        content = content,
    )
}
