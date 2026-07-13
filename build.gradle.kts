// Root project: no build logic of its own, only aggregates subproject plugin application.
// Kotlin support is AGP 9's built-in Kotlin (no org.jetbrains.kotlin.android plugin needed
// or allowed — applying it alongside AGP 9 is a hard error); only the Compose compiler
// plugin still needs explicit application. See docs/ARCHITECTURE.md 前提・判断ログ.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
