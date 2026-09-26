package no.cloud247.tv

import android.app.Application
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.cast.Cast
import androidx.media3.common.util.UnstableApi

class Cloud247Application : Application() {
    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        try {
            Cast.getSingletonInstance(this).initialize()
        } catch (error: Throwable) {
            Log.w(
                "Cloud247Cast",
                "Cast initialization failed. Local playback remains available.",
                error
            )
        }
    }
}
