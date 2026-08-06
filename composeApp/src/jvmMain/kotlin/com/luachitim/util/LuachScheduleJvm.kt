package com.luachitim.util

import com.kosherjava.zmanim.hebrewcalendar.JewishCalendar
import com.kosherjava.zmanim.hebrewcalendar.JewishDate
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.logging.Level
import java.util.logging.Logger

private val log = Logger.getLogger("LuachSchedule")

fun buildLuachScheduleJvm(luachYear: Int, inIsrael: Boolean): List<Pair<Long, String>> {
    val result = mutableListOf<Pair<Long, String>>()

    val elul28 = JewishDate()
    elul28.setJewishDate(luachYear - 1, JewishDate.ELUL, 28)
    val elul28Greg = elul28.gregorianCalendar
    val daysBack   = elul28Greg.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY
    val week1 = elul28Greg.clone() as GregorianCalendar
    week1.add(Calendar.DAY_OF_MONTH, -daysBack)

    val saNext = JewishDate()
    saNext.setJewishDate(luachYear + 1, JewishDate.TISHREI, 22)
    val saNextGreg = saNext.gregorianCalendar
    val daysBackSa = saNextGreg.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY
    val lastSun = saNextGreg.clone() as GregorianCalendar
    lastSun.add(Calendar.DAY_OF_MONTH, -daysBackSa)

    val current = week1.clone() as GregorianCalendar
    while (!current.after(lastSun)) {
        val sunJd = gregorianToJd(
            current.get(Calendar.YEAR),
            current.get(Calendar.MONTH) + 1,
            current.get(Calendar.DAY_OF_MONTH)
        )
        val shabGreg = current.clone() as GregorianCalendar
        shabGreg.add(Calendar.DAY_OF_MONTH, 6)

        val parasha = try {
            val jc = JewishCalendar(shabGreg)
            jc.inIsrael = inIsrael
            parashaToHebrew(jc.parshah)
        } catch (e: Exception) {
            log.log(Level.WARNING, "Failed to resolve parasha for $shabGreg", e)
            ""
        }

        result.add(Pair(sunJd, parasha))
        current.add(Calendar.DAY_OF_MONTH, 7)
    }
    return result
}

private fun parashaToHebrew(p: JewishCalendar.Parsha): String = when (p) {
    JewishCalendar.Parsha.NONE                -> ""
    JewishCalendar.Parsha.BERESHIS            -> "בראשית"
    JewishCalendar.Parsha.NOACH               -> "נח"
    JewishCalendar.Parsha.LECH_LECHA          -> "לך לך"
    JewishCalendar.Parsha.VAYERA              -> "וירא"
    JewishCalendar.Parsha.CHAYEI_SARA         -> "חיי שרה"
    JewishCalendar.Parsha.TOLDOS              -> "תולדות"
    JewishCalendar.Parsha.VAYETZEI            -> "ויצא"
    JewishCalendar.Parsha.VAYISHLACH          -> "וישלח"
    JewishCalendar.Parsha.VAYESHEV            -> "וישב"
    JewishCalendar.Parsha.MIKETZ              -> "מקץ"
    JewishCalendar.Parsha.VAYIGASH            -> "ויגש"
    JewishCalendar.Parsha.VAYECHI             -> "ויחי"
    JewishCalendar.Parsha.SHEMOS              -> "שמות"
    JewishCalendar.Parsha.VAERA               -> "וארא"
    JewishCalendar.Parsha.BO                  -> "בא"
    JewishCalendar.Parsha.BESHALACH           -> "בשלח"
    JewishCalendar.Parsha.YISRO               -> "יתרו"
    JewishCalendar.Parsha.MISHPATIM           -> "משפטים"
    JewishCalendar.Parsha.TERUMAH             -> "תרומה"
    JewishCalendar.Parsha.TETZAVEH            -> "תצוה"
    JewishCalendar.Parsha.KI_SISA             -> "כי תשא"
    JewishCalendar.Parsha.VAYAKHEL            -> "ויקהל"
    JewishCalendar.Parsha.PEKUDEI             -> "פקודי"
    JewishCalendar.Parsha.VAYAKHEL_PEKUDEI    -> "ויקהל-פקודי"
    JewishCalendar.Parsha.VAYIKRA             -> "ויקרא"
    JewishCalendar.Parsha.TZAV                -> "צו"
    JewishCalendar.Parsha.SHMINI              -> "שמיני"
    JewishCalendar.Parsha.TAZRIA              -> "תזריע"
    JewishCalendar.Parsha.METZORA             -> "מצורע"
    JewishCalendar.Parsha.TAZRIA_METZORA      -> "תזריע-מצורע"
    JewishCalendar.Parsha.ACHREI_MOS          -> "אחרי מות"
    JewishCalendar.Parsha.KEDOSHIM            -> "קדושים"
    JewishCalendar.Parsha.ACHREI_MOS_KEDOSHIM -> "אחרי מות-קדושים"
    JewishCalendar.Parsha.EMOR                -> "אמור"
    JewishCalendar.Parsha.BEHAR               -> "בהר"
    JewishCalendar.Parsha.BECHUKOSAI          -> "בחוקותי"
    JewishCalendar.Parsha.BEHAR_BECHUKOSAI    -> "בהר-בחוקותי"
    JewishCalendar.Parsha.BAMIDBAR            -> "במדבר"
    JewishCalendar.Parsha.NASSO               -> "נשא"
    JewishCalendar.Parsha.BEHAALOSCHA         -> "בהעלותך"
    JewishCalendar.Parsha.SHLACH              -> "שלח"
    JewishCalendar.Parsha.KORACH              -> "קרח"
    JewishCalendar.Parsha.CHUKAS              -> "חוקת"
    JewishCalendar.Parsha.BALAK               -> "בלק"
    JewishCalendar.Parsha.CHUKAS_BALAK        -> "חוקת-בלק"
    JewishCalendar.Parsha.PINCHAS             -> "פינחס"
    JewishCalendar.Parsha.MATOS               -> "מטות"
    JewishCalendar.Parsha.MASEI               -> "מסעי"
    JewishCalendar.Parsha.MATOS_MASEI         -> "מטות-מסעי"
    JewishCalendar.Parsha.DEVARIM             -> "דברים"
    JewishCalendar.Parsha.VAESCHANAN          -> "ואתחנן"
    JewishCalendar.Parsha.EIKEV               -> "עקב"
    JewishCalendar.Parsha.REEH                -> "ראה"
    JewishCalendar.Parsha.SHOFTIM             -> "שופטים"
    JewishCalendar.Parsha.KI_SEITZEI          -> "כי תצא"
    JewishCalendar.Parsha.KI_SAVO             -> "כי תבוא"
    JewishCalendar.Parsha.NITZAVIM            -> "נצבים"
    JewishCalendar.Parsha.VAYEILECH           -> "וילך"
    JewishCalendar.Parsha.NITZAVIM_VAYEILECH  -> "נצבים-וילך"
    JewishCalendar.Parsha.HAAZINU             -> "האזינו"
    JewishCalendar.Parsha.VZOS_HABERACHA      -> "וזאת הברכה"
    else                                       -> ""
}

/**
 * Converts a Julian Day to a HebrewDate using KosherJava.
 */
fun jdToHebrewJvm(jd: Long): HebrewDate {
    // Clamp to a reasonable Hebrew-calendar range (JD of 1 Jan 1000 CE ≈ 2086308)
    val safeJd = if (jd < 2000000L) {
        // Fallback to today if JD looks invalid
        val cal = GregorianCalendar(); gregorianToJd(
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH))
    } else jd
    val (yr, mo, dy) = jdToGregorianLocal(safeJd)
    return try {
        val jd2 = JewishDate(GregorianCalendar(yr, mo - 1, dy))
        HebrewDate(jd2.jewishYear, jd2.jewishMonth, jd2.jewishDayOfMonth)
    } catch (e: Exception) {
        log.log(Level.WARNING, "jdToHebrewJvm failed for jd=$jd, falling back to today", e)
        val today = JewishDate()
        HebrewDate(today.jewishYear, today.jewishMonth, today.jewishDayOfMonth)
    }
}

private fun jdToGregorianLocal(jd: Long): Triple<Int, Int, Int> {
    val a = jd + 32044; val b = (4*a+3)/146097; val c = a-(146097*b)/4
    val d = (4*c+3)/1461; val e = c-(1461*d)/4; val m = (5*e+2)/153
    return Triple((100*b+d-4800+m/10).toInt(), (m+3-12*(m/10)).toInt(), (e-(153*m+2)/5+1).toInt())
}

/** Hebrew month name constants used by KosherJava's JewishDate (1-based, Nisan=1..Adar=12/13) */
fun hebrewToJdJvm(year: Int, month: Int, day: Int): Long {
    return try {
        val jd = JewishDate()
        jd.setJewishDate(year, month, day)
        val g = jd.gregorianCalendar
        gregorianToJd(
            g.get(java.util.Calendar.YEAR),
            g.get(java.util.Calendar.MONTH) + 1,
            g.get(java.util.Calendar.DAY_OF_MONTH)
        )
    } catch (e: Exception) {
        log.log(Level.WARNING, "hebrewToJdJvm failed for $year-$month-$day, using today as fallback", e)
        currentJulianDayFallback()
    }
}

fun daysInHebrewMonthJvm(year: Int, month: Int): Int {
    return try {
        val jd = JewishDate()
        jd.setJewishDate(year, month, 1)
        jd.daysInJewishMonth   // instance property/getter — safe against API drift
    } catch (e: Exception) {
        log.log(Level.WARNING, "daysInHebrewMonthJvm failed for $year-$month, defaulting to 30", e)
        30
    }
}

fun isHebrewLeapYearJvm(year: Int): Boolean {
    return try {
        val jd = JewishDate()
        jd.setJewishDate(year, 7, 1)   // Tishrei 1 of that year
        jd.isJewishLeapYear
    } catch (e: Exception) {
        log.log(Level.WARNING, "isHebrewLeapYearJvm failed for year=$year, defaulting to false", e)
        false
    }
}

fun holidayNameJvm(jd: Long, inIsrael: Boolean): String {
    return try {
        val (yr, mo, dy) = jdToGregorianLocal(jd)
        val jc = JewishCalendar(GregorianCalendar(yr, mo - 1, dy))
        jc.inIsrael = inIsrael
        jc.isUseModernHolidays = true
        yomTovIndexToHebrew(jc.yomTovIndex)
    } catch (e: Exception) {
        log.log(Level.WARNING, "holidayNameJvm failed for jd=$jd", e)
        ""
    }
}

// Erev days (day before a holiday) intentionally map to "" - the holiday
// itself is only shown to the person once it has actually started.
private fun yomTovIndexToHebrew(idx: Int): String = when (idx) {
    JewishCalendar.PESACH              -> "פסח"
    JewishCalendar.CHOL_HAMOED_PESACH   -> "חול המועד פסח"
    JewishCalendar.PESACH_SHENI         -> "פסח שני"
    JewishCalendar.SHAVUOS              -> "שבועות"
    JewishCalendar.SEVENTEEN_OF_TAMMUZ  -> "י\"ז בתמוז"
    JewishCalendar.TISHA_BEAV           -> "תשעה באב"
    JewishCalendar.TU_BEAV              -> "ט\"ו באב"
    JewishCalendar.ROSH_HASHANA         -> "ראש השנה"
    JewishCalendar.FAST_OF_GEDALYAH     -> "צום גדליה"
    JewishCalendar.YOM_KIPPUR           -> "יום כיפור"
    JewishCalendar.SUCCOS               -> "סוכות"
    JewishCalendar.CHOL_HAMOED_SUCCOS   -> "חול המועד סוכות"
    JewishCalendar.HOSHANA_RABBA        -> "הושענא רבה"
    JewishCalendar.SHEMINI_ATZERES      -> "שמיני עצרת"
    JewishCalendar.SIMCHAS_TORAH        -> "שמחת תורה"
    JewishCalendar.CHANUKAH             -> "חנוכה"
    JewishCalendar.TENTH_OF_TEVES       -> "עשרה בטבת"
    JewishCalendar.TU_BESHVAT           -> "ט\"ו בשבט"
    JewishCalendar.FAST_OF_ESTHER       -> "תענית אסתר"
    JewishCalendar.PURIM                -> "פורים"
    JewishCalendar.SHUSHAN_PURIM        -> "שושן פורים"
    JewishCalendar.PURIM_KATAN          -> "פורים קטן"
    JewishCalendar.ROSH_CHODESH         -> "ראש חודש"
    JewishCalendar.YOM_HASHOAH          -> "יום השואה"
    JewishCalendar.YOM_HAZIKARON        -> "יום הזיכרון"
    JewishCalendar.YOM_HAATZMAUT        -> "יום העצמאות"
    JewishCalendar.YOM_YERUSHALAYIM     -> "יום ירושלים"
    JewishCalendar.LAG_BAOMER           -> "ל\"ג בעומר"
    else                                -> ""
}

private fun currentJulianDayFallback(): Long {
    val c = java.util.Calendar.getInstance()
    return gregorianToJd(c.get(java.util.Calendar.YEAR),
        c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH))
}
