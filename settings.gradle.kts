pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
    plugins {
        id("com.android.application") version "9.0.0"
        id("org.jetbrains.kotlin.android") version "1.9.0"
        id("org.jetbrains.kotlin.kapt") version "1.9.0"
    }
}

rootProject.name = "dpad-messaging"
include(":app")
