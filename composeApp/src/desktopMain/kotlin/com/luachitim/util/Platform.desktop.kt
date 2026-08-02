package com.luachitim.util

import com.kosherjava.zmanim.hebrewcalendar.JewishCalendar
import com.kosherjava.zmanim.hebrewcalendar.JewishDate
import java.util.Calendar
import java.util.prefs.Preferences

private val prefs: Preferences = Preferences.userRoot().node("com/luachitim")

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

actual fun getTodayDayOfWeek(): Int = JewishCalendar().dayOfWeek

actual fun saveFilePath(key: String, value: String) {
    prefs.put(key, value); prefs.flush()
}

actual fun loadFilePath(key: String): String? {
    val v = prefs.get(key, "")
    return v.ifEmpty { null }
}

actual fun clearFilePath(key: String) {
    prefs.remove(key); prefs.flush()
}

actual fun buildLuachSchedule(luachYear: Int, inIsrael: Boolean): List<Pair<Long, String>> =
    buildLuachScheduleJvm(luachYear, inIsrael)

actual fun jdToHebrew(jd: Long): HebrewDate = jdToHebrewJvm(jd)

actual fun hebrewToJd(hd: HebrewDate): Long = hebrewToJdJvm(hd.year, hd.month, hd.day)
actual fun daysInHebrewMonth(year: Int, month: Int): Int = daysInHebrewMonthJvm(year, month)
actual fun isHebrewLeapYear(year: Int): Boolean = isHebrewLeapYearJvm(year)

actual fun holidayName(jd: Long, inIsrael: Boolean): String = holidayNameJvm(jd, inIsrael)

actual fun isDesktopPlatform(): Boolean = true

actual fun openUrl(url: String) {
    try {
        java.awt.Desktop.getDesktop().browse(java.net.URI(url))
    } catch (e: Exception) {
        System.err.println("openUrl failed for $url: ${e.message}")
    }
}
