package com.luachitim.util

data class HebrewDate(val year: Int, val month: Int, val day: Int)

data class WeekInfo(
    val parashaName: String,
    val parashaIndex: Int,
    val pdfPageStart: Int,
    val pdfPageEnd: Int,
    val weekStartJd: Long,
    val weekEndJd: Long
)

data class HebrewDateDisplay(
    val hebrewDate: HebrewDate,
    val dayOfWeek: Int,
    val dayOfWeekName: String,
    val hebrewDateString: String,
    val parashaName: String,
    val holidayName: String = ""
)

// ── expect declarations ───────────────────────────────────────────────────

expect fun buildLuachSchedule(luachYear: Int, inIsrael: Boolean): List<Pair<Long, String>>
expect fun getTodayHebrewDisplay(): HebrewDate
expect fun getTodayDayOfWeek(): Int
expect fun currentJulianDay(): Long
expect fun saveFilePath(key: String, value: String)
expect fun loadFilePath(key: String): String?
expect fun clearFilePath(key: String)

/** True on the Desktop target, false on Android - used to show mouse-friendly
 *  controls (like on-screen zoom buttons) that touch users don't need. */
expect fun isDesktopPlatform(): Boolean

/** Opens a URL in the system's default browser. */
expect fun openUrl(url: String)

/** Name of the Jewish holiday/fast falling on this day ("" if it's a plain weekday). */
expect fun holidayName(jd: Long, inIsrael: Boolean): String

// ── שמות ─────────────────────────────────────────────────────────────────

private val MONTH_NAMES = mapOf(
    1 to "ניסן",  2 to "אייר",  3 to "סיוון", 4 to "תמוז",
    5 to "אב",    6 to "אלול",  7 to "תשרי",  8 to "חשוון",
    9 to "כסלו", 10 to "טבת",  11 to "שבט",  12 to "אדר", 13 to "אדר ב"
)
private val DAY_NAMES = listOf("", "ראשון", "שני", "שלישי", "רביעי", "חמישי", "שישי", "שבת")
private val DAY_NUM   = listOf(
    "", "א","ב","ג","ד","ה","ו","ז","ח","ט","י",
    "יא","יב","יג","יד","טו","טז","יז","יח","יט","כ",
    "כא","כב","כג","כד","כה","כו","כז","כח","כט","ל"
)
private val HUNDREDS = listOf("", "ק", "ר", "ש", "ת")
private val TENS     = listOf("", "י", "כ", "ל", "מ", "נ", "ס", "ע", "פ", "צ")
private val ONES     = listOf("", "א", "ב", "ג", "ד", "ה", "ו", "ז", "ח", "ט")

/**
 * Converts a Hebrew year (e.g. 5786) into its gematria representation
 * (e.g. תשפ"ו). Works for any year, not just a fixed lookup table -
 * only the last 3 digits are spelled out (the thousands digit, ה' for
 * the 5000s, is dropped by convention, same as on a standard luach).
 * 15/16 are special-cased to ט"ו/ט"ז to avoid spelling out God's name.
 */
fun hebrewYearGematria(year: Int): String {
    var remainder = year % 1000
    val letters = StringBuilder()

    // Hundreds: ת (400) can repeat (e.g. 900 = תת)
    while (remainder >= 400) { letters.append("ת"); remainder -= 400 }
    if (remainder >= 100) {
        letters.append(HUNDREDS[remainder / 100])
        remainder %= 100
    }
    when (remainder) {
        15 -> letters.append("טו")
        16 -> letters.append("טז")
        else -> {
            if (remainder >= 10) {
                letters.append(TENS[remainder / 10])
                remainder %= 10
            }
            if (remainder > 0) letters.append(ONES[remainder])
        }
    }

    if (letters.isEmpty()) return year.toString()
    // Punctuation: גרשיים (") before the last letter, or גרש (') if it's a single letter
    return if (letters.length == 1) {
        "${letters}'"
    } else {
        "${letters.substring(0, letters.length - 1)}\"${letters.last()}"
    }
}

fun formatHebrewDate(hd: HebrewDate): String {
    val d = DAY_NUM.getOrElse(hd.day) { hd.day.toString() }
    val m = MONTH_NAMES[hd.month] ?: ""
    val y = hebrewYearGematria(hd.year)
    return "$d $m $y"
}
fun dayOfWeekName(dow: Int): String = DAY_NAMES.getOrElse(dow) { "" }

fun gregorianToJd(year: Int, month: Int, day: Int): Long {
    val a = (14 - month) / 12
    val y = year + 4800 - a
    val m = month + 12 * a - 3
    return day + (153 * m + 2) / 5 + 365L * y + y / 4 - y / 100 + y / 400 - 32045
}
fun jdDayOfWeek(jd: Long): Int = ((jd + 1) % 7).toInt()
fun weekSunday(jd: Long): Long  = jd - jdDayOfWeek(jd)

/** Convert Julian Day to HebrewDate */
expect fun jdToHebrew(jd: Long): HebrewDate

/** Convert a HebrewDate to Julian Day (inverse of jdToHebrew) */
expect fun hebrewToJd(hd: HebrewDate): Long

/** Number of days in a given Hebrew month for a given Hebrew year (29 or 30) */
expect fun daysInHebrewMonth(year: Int, month: Int): Int

/** Is the given Hebrew year a leap year (has Adar I + Adar II)? */
expect fun isHebrewLeapYear(year: Int): Boolean
