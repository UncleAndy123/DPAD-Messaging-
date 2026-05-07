package com.dpad.messaging.activities

import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.dpad.messaging.R

class ImageViewerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_viewer)

        val imageView = findViewById<ImageView>(R.id.iv_full_image)
        val uriString = intent.getStringExtra(EXTRA_IMAGE_URI)

        if (uriString.isNullOrBlank()) {
            finish()
            return
        }

        Glide.with(this)
            .load(Uri.parse(uriString))
            .into(imageView)

        imageView.setOnClickListener { finish() }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_STAR) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        const val EXTRA_IMAGE_URI = "extra_image_uri"
    }
}
