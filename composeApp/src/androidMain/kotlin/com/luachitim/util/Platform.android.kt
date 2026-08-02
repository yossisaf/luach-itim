package com.luachitim.util

import android.content.Context
import android.content.SharedPreferences
import com.kosherjava.zmanim.hebrewcalendar.JewishCalendar
import com.kosherjava.zmanim.hebrewcalendar.JewishDate
import java.util.Calendar

private var appContext: Context? = null

fun initAndroidPlatform(context: Context) {
    appContext = context.applicationContext
}

private fun prefs(): SharedPreferences {
    val ctx = appContext
        ?: throw IllegalStateException("initAndroidPlatform() was not called before accessing prefs")
    return ctx.getSharedPreferences("luach_prefs", Context.MODE_PRIVATE)
}

actual fun currentJulianDay(): Long {
    val c = Calendar.getInstance()
    return gregorianToJd(
        c.get(Calendar.YEAR),
        c.get(Calendar.MONTH) + 1,
        c.get(Calendar.DAY_OF_MONTH)
    )
}

actual fun getTodayHebrewDisplay(): HebrewDate {
    val jd = JewishDate()
    return HebrewDate(jd.jewishYear, jd.jewishMonth, jd.jewishDayOfMonth)
}

actual fun getTodayDayOfWeek(): Int = JewishCalendar().dayOfWeek  // 1=Sun..7=Sat

actual fun saveFilePath(key: String, value: String) {
    prefs().edit().putString(key, value).apply()
}

actual fun loadFilePath(key: String): String? {
    val v = prefs().getString(key, "") ?: ""
    return v.ifEmpty { null }
}

actual fun clearFilePath(key: String) {
    prefs().edit().remove(key).apply()
}

actual fun buildLuachSchedule(luachYear: Int, inIsrael: Boolean): List<Pair<Long, String>> =
    buildLuachScheduleJvm(luachYear, inIsrael)

actual fun jdToHebrew(jd: Long): HebrewDate = jdToHebrewJvm(jd)

actual fun hebrewToJd(hd: HebrewDate): Long = hebrewToJdJvm(hd.year, hd.month, hd.day)
actual fun daysInHebrewMonth(year: Int, month: Int): Int = daysInHebrewMonthJvm(year, month)
actual fun isHebrewLeapYear(year: Int): Boolean = isHebrewLeapYearJvm(year)

actual fun holidayName(jd: Long, inIsrael: Boolean): String = holidayNameJvm(jd, inIsrael)

actual fun isDesktopPlatform(): Boolean = false

actual fun openUrl(url: String) {
    val ctx = appContext ?: return
    try {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    } catch (e: Exception) {
        android.util.Log.w("LuachItim", "openUrl failed for $url", e)
    }
}
