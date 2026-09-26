package no.cloud247.tv

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration

object DeviceProfile {
    fun isTelevision(context: Context): Boolean {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }

    fun isTablet(context: Context): Boolean {
        return !isTelevision(context) &&
            context.resources.configuration.smallestScreenWidthDp >= 600
    }
}
