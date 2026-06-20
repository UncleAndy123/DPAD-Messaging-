pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "DPADMessaging"
include(":app")

// F-Droid: when vendor/mmslib exists, use it instead of jitpack dependency
val vendorMmsLib = file("vendor/mmslib/build.gradle.kts")
if (vendorMmsLib.exists()) {
    include(":vendor:mmslib")
    project(":vendor:mmslib").projectDir = file("vendor/mmslib")
}
