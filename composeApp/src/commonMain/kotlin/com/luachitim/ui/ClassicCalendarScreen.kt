package com.luachitim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luachitim.data.LuachRepository
import com.luachitim.util.*

/**
 * "Classic calendar" mode: a full month grid (Hebrew, with a Gregorian
 * toggle), showing every day's Hebrew/Gregorian date, holiday name, and a
 * diary-event dot. Tapping a day opens the same day-context-menu used
 * elsewhere in the app (see DayContextMenu in LuachApp.kt), so adding/
 * viewing events works identically to the PDF view.
 */
@Composable
fun ClassicCalendarScreen(
    repo: LuachRepository,
    initialJd: Long,
    inIsrael: Boolean,
    eventsVersion: Int,
    bg: Color, surf: Color, gold: Color, txt: Color, sub: Color,
    onDaySelected: (Long) -> Unit,
    onClose: () -> Unit
) {
    var mode by remember { mutableStateOf(0) } // 0 = Hebrew month, 1 = Gregorian month
    val todayJd = currentJulianDay()
    val (initGy, initGm, _) = remember(initialJd) { repo.jdToGregorian(initialJd) }
    val initHd = remember(initialJd) { jdToHebrew(initialJd) }

    var hYear  by remember { mutableStateOf(initHd.year) }
    var hMonth by remember { mutableStateOf(initHd.month) }
    var gYear  by remember { mutableStateOf(initGy) }
    var gMonth by remember { mutableStateOf(initGm) }

    var tappedDayForMenu by remember { mutableStateOf<Long?>(null) }

    val leap = isHebrewLeapYear(hYear)
    val hMonthNames = if (leap) HEBREW_MONTH_NAMES_LEAP else HEBREW_MONTH_NAMES_REGULAR
    LaunchedEffect(leap) { if (hMonthNames.none { it.first == hMonth }) hMonth = 7 }

    fun shiftHebrewMonth(delta: Int) {
        val names = if (isHebrewLeapYear(hYear)) HEBREW_MONTH_NAMES_LEAP else HEBREW_MONTH_NAMES_REGULAR
        val idx = names.indexOfFirst { it.first == hMonth }
        val newIdx = idx + delta
        when {
            newIdx < 0 -> {
                hYear -= 1
                val prevNames = if (isHebrewLeapYear(hYear)) HEBREW_MONTH_NAMES_LEAP else HEBREW_MONTH_NAMES_REGULAR
                hMonth = prevNames.last().first
            }
            newIdx >= names.size -> { hYear += 1; hMonth = 7 }
            else -> hMonth = names[newIdx].first
        }
    }
    fun shiftGregorianMonth(delta: Int) {
        var m = gMonth + delta
        var y = gYear
        if (m < 1) { m = 12; y -= 1 }
        if (m > 12) { m = 1; y += 1 }
        gMonth = m; gYear = y
    }

    tappedDayForMenu?.let { jd ->
        DayContextMenu(
            jd = jd, repo = repo,
            surf = surf, gold = gold, txt = txt, sub = sub,
            onAddEvent  = { onDaySelected(jd); tappedDayForMenu = null; onClose() },
            onOpenEvent = { onDaySelected(jd); tappedDayForMenu = null; onClose() },
            onDismiss   = { tappedDayForMenu = null }
        )
    }

    Box(Modifier.fillMaxSize().background(bg)) {
        Column(Modifier.fillMaxSize()) {
            // ── Header ──────────────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth().background(surf).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onClose) { Text("סגור", color = sub) }
                Spacer(Modifier.weight(1f))
                Text("לוח רגיל", style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold, color = gold))
                Spacer(Modifier.weight(1f))
                Row(Modifier.background(Color.Black.copy(alpha = 0.18f), RoundedCornerShape(10.dp)).padding(3.dp)) {
                    listOf("עברי" to 0, "לועזי" to 1).forEach { (label, idx) ->
                        val sel = mode == idx
                        Box(
                            Modifier
                                .background(if (sel) gold else Color.Transparent, RoundedCornerShape(8.dp))
                                .clickable { mode = idx }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(label, style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                color = if (sel) contrastOn(gold) else sub))
                        }
                    }
                }
            }
            Divider(color = gold.copy(alpha = 0.1f))

            // ── Month navigator ─────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { if (mode == 0) shiftHebrewMonth(-1) else shiftGregorianMonth(-1) }) {
                    IconChevronRight(gold, 20.dp)
                }
                Text(
                    if (mode == 0)
                        "${hMonthNames.find { it.first == hMonth }?.second ?: ""} ${hebrewYearGematria(hYear)}"
                    else
                        "${GREGORIAN_MONTH_NAMES.find { it.first == gMonth }?.second ?: ""} $gYear",
                    style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = gold)
                )
                IconButton(onClick = { if (mode == 0) shiftHebrewMonth(1) else shiftGregorianMonth(1) }) {
                    IconChevronLeft(gold, 20.dp)
                }
            }

            // ── Grid ─────────────────────────────────────────────────────
            if (mode == 0) {
                val daysInMonth = remember(hYear, hMonth) { daysInHebrewMonth(hYear, hMonth) }
                val dayJds = remember(hYear, hMonth, daysInMonth) {
                    (1..daysInMonth).map { d -> hebrewToJd(HebrewDate(hYear, hMonth, d)) }
                }
                val labels = remember(daysInMonth) { (1..daysInMonth).map { HEBREW_DAY_NUM.getOrElse(it) { it.toString() } } }
                ClassicMonthGrid(
                    repo = repo, dayJds = dayJds, primaryLabels = labels,
                    secondaryLabel = { jd -> val (_, _, d) = repo.jdToGregorian(jd); d.toString() },
                    todayJd = todayJd, inIsrael = inIsrael, eventsVersion = eventsVersion,
                    gold = gold, sub = sub, txt = txt,
                    onDayTap = { jd -> tappedDayForMenu = jd }
                )
            } else {
                val daysInMonth = remember(gYear, gMonth) { daysInGregorianMonth(gYear, gMonth) }
                val dayJds = remember(gYear, gMonth, daysInMonth) {
                    (1..daysInMonth).map { d -> repo.gregorianToJdLocal(gYear, gMonth, d) }
                }
                val labels = remember(daysInMonth) { (1..daysInMonth).map { it.toString() } }
                ClassicMonthGrid(
                    repo = repo, dayJds = dayJds, primaryLabels = labels,
                    secondaryLabel = { jd -> val hd = jdToHebrew(jd); HEBREW_DAY_NUM.getOrElse(hd.day) { hd.day.toString() } },
                    todayJd = todayJd, inIsrael = inIsrael, eventsVersion = eventsVersion,
                    gold = gold, sub = sub, txt = txt,
                    onDayTap = { jd -> tappedDayForMenu = jd }
                )
            }
        }
    }
}

@Composable
private fun ClassicMonthGrid(
    repo: LuachRepository, dayJds: List<Long>, primaryLabels: List<String>,
    secondaryLabel: (Long) -> String,
    todayJd: Long, inIsrael: Boolean, eventsVersion: Int,
    gold: Color, sub: Color, txt: Color,
    onDayTap: (Long) -> Unit
) {
    val leadingBlanks = remember(dayJds.firstOrNull()) {
        dayJds.firstOrNull()?.let { jdDayOfWeek(it) } ?: 0
    }
    val weekdayShort = listOf("ראשון", "שני", "שלישי", "רביעי", "חמישי", "שישי", "שבת")

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 8.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(Modifier.fillMaxWidth()) {
                weekdayShort.forEach { name ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(name.take(1), style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = sub))
                    }
                }
            }
            val padded: List<Long?> = List(leadingBlanks) { null } + dayJds
            padded.chunked(7).forEachIndexed { rowIdx, row ->
                Row(
                    Modifier.fillMaxWidth().height(72.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    row.forEachIndexed { colIdx, jd ->
                        if (jd == null) {
                            Spacer(Modifier.weight(1f))
                        } else {
                            val globalIdx = rowIdx * 7 + colIdx - leadingBlanks
                            val holiday = remember(jd, inIsrael) { holidayName(jd, inIsrael) }
                            val hasEvents = remember(jd, eventsVersion) { repo.hasEvents(jd) }
                            val isToday = jd == todayJd
                            val isShabbat = jdDayOfWeek(jd) == 6
                            Box(
                                Modifier.weight(1f).fillMaxHeight()
                                    .background(
                                        when {
                                            holiday.isNotEmpty() -> Color(0xFFFFB74D).copy(alpha = 0.20f)
                                            isShabbat -> gold.copy(alpha = 0.10f)
                                            else -> Color.Transparent
                                        },
                                        RoundedCornerShape(8.dp)
                                    )
                                    .border(
                                        if (isToday) 1.6.dp else 0.5.dp,
                                        if (isToday) gold else gold.copy(alpha = 0.15f),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable { onDayTap(jd) }
                                    .padding(3.dp)
                            ) {
                                Column(Modifier.fillMaxSize()) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(primaryLabels.getOrElse(globalIdx) { "" },
                                            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                                color = if (isToday) gold else txt))
                                        Text(secondaryLabel(jd),
                                            style = TextStyle(fontSize = 9.sp, color = sub))
                                    }
                                    if (holiday.isNotEmpty())
                                        Text(holiday, style = TextStyle(fontSize = 8.5.sp, fontWeight = FontWeight.Bold,
                                            color = Color(0xFFC77A00)), maxLines = 1)
                                    if (hasEvents)
                                        Box(Modifier.padding(top = 2.dp).size(5.dp)
                                            .background(gold, RoundedCornerShape(50)))
                                }
                            }
                        }
                    }
                    repeat(7 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
