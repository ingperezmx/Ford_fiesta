// Top-level build file where you can add configuration options common to all sub-projects/modules.
// API 36 (Android 16) es obligatorio para Google Play desde el 31-ago-2026:
// AGP 8.10.0 es el minimo que soporta compileSdk 36 (requiere Gradle 8.11.1
// y JDK 17) y Kotlin 2.1.0 es el que soporta Gradle 8.11. El plugin de
// Compose (org.jetbrains.kotlin.plugin.compose) viene empaquetado con Kotlin
// 2.x y sustituye a kotlinCompilerExtensionVersion.
plugins {
    id("com.android.application") version "8.10.0" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
}
