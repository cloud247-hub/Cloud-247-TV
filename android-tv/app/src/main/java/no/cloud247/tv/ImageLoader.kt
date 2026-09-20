package no.cloud247.tv

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import java.util.concurrent.Executors

object ImageLoader {
    private val executor = Executors.newFixedThreadPool(4)
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun load(activity: Activity, url: String, image: ImageView, initials: TextView) {
        if (url.isBlank()) {
            image.visibility = View.GONE
            initials.visibility = View.VISIBLE
            return
        }

        image.tag = url
        cache.get(url)?.let { bitmap ->
            image.setImageBitmap(bitmap)
            image.visibility = View.VISIBLE
            initials.visibility = View.GONE
            return
        }

        image.visibility = View.GONE
        initials.visibility = View.VISIBLE
        executor.execute {
            try {
                val bytes = NetworkClient.fetchBytes(url, 2 * 1024 * 1024)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@execute
                cache.put(url, bitmap)
                activity.runOnUiThread {
                    if (image.tag == url) {
                        image.setImageBitmap(bitmap)
                        image.visibility = View.VISIBLE
                        initials.visibility = View.GONE
                    }
                }
            } catch (_: Exception) {
                // A missing/broken channel logo must never affect playback.
            }
        }
    }
}
