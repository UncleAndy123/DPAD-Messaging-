# Proguard rules for DPAD Messaging

# Keep Compose runtime & material
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Keep Room entities and DAOs accessed by reflection
-keepclassmembers class * {
    @androidx.room.* <methods>;
}

# Keep Klinker SMS/MMS classes used via reflection or JNI
-keep class com.klinkerapps.** { *; }
-dontwarn com.klinkerapps.**

# Keep classes referenced in AndroidManifest (activities, receivers)
-keep class * extends android.app.Activity { *; }
-keep class * extends android.app.Service { *; }
-keep class * extends android.content.BroadcastReceiver { *; }

# Keep entry points with @JvmStatic or used by reflection
-keepclassmembers class ** {
    @kotlin.jvm.JvmStatic *;
}
