package com.dpad.messaging

import android.app.Application
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import okhttp3.OkHttpClient

class DpadMessagingApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .memoryCache {
                    MemoryCache.Builder(this)
                        .maxSizeBytes(12 * 1024 * 1024) // 12 MB — conservative for low-RAM device
                        .build()
                }
                .diskCache {
                    DiskCache.Builder()
                        .directory(cacheDir.resolve("coil_image_cache"))
                        .maxSizeBytes(32 * 1024 * 1024) // 32 MB on disk
                        .build()
                }
                .respectCacheHeaders(false) // MMS content:// URIs have no cache headers
                .crossfade(false)           // no animation — saves CPU on low-RAM device
                .build()
        )
    }
}
