package com.luachitim.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.luachitim.util.initAndroidPlatform

/**
 * Reacts to the same midnight-ish system broadcasts LuachWidgetProvider
 * already listens to (see its own kdoc for why these are exempt from
 * Android's implicit-broadcast restrictions), and uses them to refresh:
 *  - the daily-icon pinned shortcut (if the person turned it on), and
 *  - the "page" home-screen widget (which shows the actual PDF page and
 *    otherwise only refreshes on its own multi-hour schedule).
 */
class MidnightIconReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                initAndroidPlatform(context)
                if (isDailyIconEnabled()) {
                    updateDailyIconShortcut(context)
                }
                LuachPageWidgetProvider.requestUpdate(context)
            }
        }
    }
}
