package com.luachitim.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.luachitim.MainActivity
import com.luachitim.R
import com.luachitim.data.LuachRepository
import com.luachitim.util.clearFilePath
import com.luachitim.util.currentJulianDay
import com.luachitim.util.initAndroidPlatform
import com.luachitim.util.jdToHebrew
import com.luachitim.util.loadFilePath

/** Per-widget preference keys, stored via the app's existing saveFilePath/loadFilePath
 *  key-value store (same "luach_prefs" the rest of the app already uses) - one instance's
 *  worth of settings per home-screen placement (keyed by that placement's appWidgetId). */
fun widgetThemeKey(appWidgetId: Int) = "widget_theme_$appWidgetId"
fun widgetTextSizeKey(appWidgetId: Int) = "widget_textsize_$appWidgetId"

enum class WidgetTheme(val storageKey: String, val label: String) {
    DARK("dark", "כהה"), LIGHT("light", "בהיר"), APP("app", "לפי האפליקציה");
    companion object {
        fun fromStorage(v: String?): WidgetTheme = values().firstOrNull { it.storageKey == v } ?: DARK
    }
}

enum class WidgetTextSize(val storageKey: String, val label: String, val parashaSp: Float, val dateSp: Float) {
    SMALL("small", "קטן", 13f, 10f),
    MEDIUM("medium", "רגיל", 16f, 12.5f),
    LARGE("large", "גדול", 19f, 15f);
    companion object {
        fun fromStorage(v: String?): WidgetTextSize = values().firstOrNull { it.storageKey == v } ?: MEDIUM
    }
}

/**
 * Home-screen widget - shows the current week's פרשת השבוע and today's
 * Hebrew date. Refreshes on its own periodic schedule (see
 * updatePeriodMillis in luach_widget_info.xml) and also immediately at
 * midnight, since ACTION_DATE_CHANGED / ACTION_TIME_CHANGED /
 * ACTION_TIMEZONE_CHANGED are exempt from Android's implicit-broadcast
 * restrictions and are still delivered to a manifest-registered receiver
 * (see the matching <receiver> intent-filters in AndroidManifest.xml).
 */
class LuachWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { id -> updateWidgetView(context, appWidgetManager, id) }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        // Drop that placement's saved theme/text-size choices so they don't
        // linger forever under a widget id that no longer exists.
        appWidgetIds.forEach { id ->
            clearFilePath(widgetThemeKey(id))
            clearFilePath(widgetTextSizeKey(id))
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent) // handles ACTION_APPWIDGET_UPDATE itself

        when (intent.action) {
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(ComponentName(context, LuachWidgetProvider::class.java))
                ids.forEach { id -> updateWidgetView(context, manager, id) }
            }
        }
    }

    companion object {
        /**
         * Builds and pushes the RemoteViews for one widget placement, applying
         * that placement's own theme + text-size settings (set via
         * WidgetConfigActivity, defaulting to dark/medium for a freshly-added
         * widget that hasn't been configured yet).
         */
        fun updateWidgetView(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            // Idempotent - LuachApplication.onCreate() already calls this on process
            // start, but a defensive call here costs nothing and protects against
            // any edge case where the widget's receiver runs before that.
            initAndroidPlatform(context)

            val repo = LuachRepository()
            val settings = repo.loadSettings()
            val hebrewYear = repo.getActiveLuach()?.hebrewYear
                ?: jdToHebrew(currentJulianDay()).year
            val info = repo.getTodayDisplayInfo(settings.inIsrael, hebrewYear)

            val dateLine = "יום ${info.dayOfWeekName}, ${info.hebrewDateString}"
            val parashaLine = when {
                info.holidayName.isNotEmpty() -> info.holidayName
                info.parashaName.isNotEmpty() -> "פרשת ${info.parashaName}"
                else -> ""
            }

            val theme = WidgetTheme.fromStorage(loadFilePath(widgetThemeKey(appWidgetId)))
            val isDark = when (theme) {
                WidgetTheme.DARK -> true
                WidgetTheme.LIGHT -> false
                WidgetTheme.APP -> settings.darkMode
            }
            val textSize = WidgetTextSize.fromStorage(loadFilePath(widgetTextSizeKey(appWidgetId)))

            val views = RemoteViews(context.packageName, R.layout.widget_luach)

            views.setInt(
                R.id.widget_root, "setBackgroundResource",
                if (isDark) R.drawable.widget_background_dark else R.drawable.widget_background_light
            )
            val accentColor = context.getColor(if (isDark) R.color.widget_accent else R.color.widget_accent_light)
            val subColor = context.getColor(if (isDark) R.color.widget_sub else R.color.widget_sub_light)

            views.setTextColor(R.id.widget_parasha_line, accentColor)
            views.setTextColor(R.id.widget_date_line, subColor)
            views.setTextViewTextSize(R.id.widget_parasha_line, TypedValue.COMPLEX_UNIT_SP, textSize.parashaSp)
            views.setTextViewTextSize(R.id.widget_date_line, TypedValue.COMPLEX_UNIT_SP, textSize.dateSp)

            views.setTextViewText(R.id.widget_date_line, dateLine)
            if (parashaLine.isEmpty()) {
                views.setViewVisibility(R.id.widget_parasha_line, View.GONE)
            } else {
                views.setViewVisibility(R.id.widget_parasha_line, View.VISIBLE)
                views.setTextViewText(R.id.widget_parasha_line, parashaLine)
            }

            val openAppIntent = Intent(context, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                context, appWidgetId, openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
