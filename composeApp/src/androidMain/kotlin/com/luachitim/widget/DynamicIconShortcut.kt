package com.luachitim.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.luachitim.MainActivity
import com.luachitim.util.dayOfWeekName
import com.luachitim.util.getTodayDayOfWeek
import com.luachitim.util.getTodayHebrewDisplay
import com.luachitim.util.loadFilePath
import com.luachitim.util.saveFilePath

private const val SHORTCUT_ID = "luach_daily_icon"
private const val DAILY_ICON_ENABLED_KEY = "daily_icon_shortcut_enabled"

private val HEBREW_MONTH_SHORT = mapOf(
    1 to "ניסן", 2 to "אייר", 3 to "סיוון", 4 to "תמוז", 5 to "אב", 6 to "אלול",
    7 to "תשרי", 8 to "חשוון", 9 to "כסלו", 10 to "טבת", 11 to "שבט", 12 to "אדר", 13 to "אדר ב"
)
private val DAY_GEMATRIA = listOf(
    "", "א","ב","ג","ד","ה","ו","ז","ח","ט","י",
    "יא","יב","יג","יד","טו","טז","יז","יח","יט","כ",
    "כא","כב","כג","כד","כה","כו","כז","כח","כט","ל"
)

/** True if the person has switched the daily-icon shortcut on in Settings. */
fun isDailyIconEnabled(): Boolean = loadFilePath(DAILY_ICON_ENABLED_KEY) == "true"

/**
 * Persists the enabled flag and, when turning it on, immediately requests
 * pinning the shortcut (the actual placement is still confirmed by the
 * person via the OS's own pin-request UI - this only makes the request).
 * Turning it off just stops future daily refreshes; Android has no API to
 * force-remove a shortcut the person pinned themselves.
 */
fun setDailyIconEnabled(context: Context, enabled: Boolean) {
    saveFilePath(DAILY_ICON_ENABLED_KEY, enabled.toString())
    if (enabled) {
        requestPinDailyIconShortcut(context)
    }
}

/** Renders today's "day of week / big Hebrew day / month" as a square icon bitmap. */
private fun renderDailyIconBitmap(size: Int = 192): Bitmap {
    val dow = getTodayDayOfWeek()      // 1=Sun..7=Sat
    val hd  = getTodayHebrewDisplay()
    val dowName = dayOfWeekName(dow)
    val dayLetters = DAY_GEMATRIA.getOrElse(hd.day) { hd.day.toString() }
    val monthName = HEBREW_MONTH_SHORT[hd.month] ?: ""

    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)

    // Background - same navy used elsewhere in the app's dark theme.
    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1B2A3B") }
    canvas.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), size * 0.22f, size * 0.22f, bgPaint)

    val accent = Color.parseColor("#D8E6FF")
    val sub = Color.parseColor("#8A94AA")

    fun textPaint(sizePx: Float, col: Int, bold: Boolean) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = col
        textSize = sizePx
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    val cx = size / 2f

    val dowPaint = textPaint(size * 0.15f, sub, bold = true)
    canvas.drawText(dowName, cx, size * 0.30f, dowPaint)

    val dayPaint = textPaint(size * 0.40f, accent, bold = true)
    canvas.drawText(dayLetters, cx, size * 0.62f, dayPaint)

    val monthPaint = textPaint(size * 0.14f, sub, bold = false)
    canvas.drawText(monthName, cx, size * 0.88f, monthPaint)

    return bmp
}

private fun buildShortcut(context: Context): ShortcutInfoCompat {
    val bmp = renderDailyIconBitmap()
    val icon = IconCompat.createWithBitmap(bmp)
    val intent = Intent(context, MainActivity::class.java).apply { action = Intent.ACTION_VIEW }

    return ShortcutInfoCompat.Builder(context, SHORTCUT_ID)
        .setShortLabel("לוח עתים לבינה")
        .setLongLabel("לוח עתים לבינה - היום")
        .setIcon(icon)
        .setIntent(intent)
        .build()
}

/**
 * Requests pinning the "daily icon" shortcut to the home screen. Safe to
 * call repeatedly - if it's already pinned, updateDailyIconShortcut() below
 * is what should be used to just refresh its icon without re-prompting.
 */
fun requestPinDailyIconShortcut(context: Context) {
    if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return
    ShortcutManagerCompat.requestPinShortcut(context, buildShortcut(context), null)
}

/**
 * Refreshes the already-pinned shortcut's icon/label to today's date. Only
 * has an effect on a shortcut that's already pinned/published - a harmless
 * no-op otherwise, so it's safe to call unconditionally at app startup and
 * once a day from MidnightIconReceiver.
 */
fun updateDailyIconShortcut(context: Context) {
    ShortcutManagerCompat.updateShortcuts(context, listOf(buildShortcut(context)))
}
