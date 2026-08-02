package com.luachitim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.luachitim.data.AVAILABLE_YEARS
import com.luachitim.data.LuachRepository
import com.luachitim.util.HebrewDate
import com.luachitim.util.daysInHebrewMonth
import com.luachitim.util.hebrewToJd
import com.luachitim.util.holidayName
import com.luachitim.util.isHebrewLeapYear
import com.luachitim.util.jdDayOfWeek
import com.luachitim.util.jdToHebrew

/**
 * Sun..Sat header initials for the day grid — the single starting letter of
 * each Hebrew weekday name (א for ראשון, ..., ש for שבת), the conventional
 * short form used on printed Hebrew calendars. Index 0 = Sunday, matching
 * jdDayOfWeek()'s 0..6 range, so both the header and the day cells below it
 * line up using the same index.
 */
private val WEEKDAY_INITIALS = listOf("א", "ב", "ג", "ד", "ה", "ו", "ש")

private val HEBREW_MONTH_NAMES_REGULAR = listOf(
    1 to "ניסן", 2 to "אייר", 3 to "סיוון", 4 to "תמוז", 5 to "אב", 6 to "אלול",
    7 to "תשרי", 8 to "חשוון", 9 to "כסלו", 10 to "טבת", 11 to "שבט", 12 to "אדר"
)
private val HEBREW_MONTH_NAMES_LEAP = listOf(
    1 to "ניסן", 2 to "אייר", 3 to "סיוון", 4 to "תמוז", 5 to "אב", 6 to "אלול",
    7 to "תשרי", 8 to "חשוון", 9 to "כסלו", 10 to "טבת", 11 to "שבט",
    12 to "אדר א", 13 to "אדר ב"
)
private val HEBREW_DAY_NUM = listOf(
    "", "א","ב","ג","ד","ה","ו","ז","ח","ט","י",
    "יא","יב","יג","יד","טו","טז","יז","יח","יט","כ",
    "כא","כב","כג","כד","כה","כו","כז","כח","כט","ל"
)
private val GREGORIAN_MONTH_NAMES = listOf(
    1 to "ינואר", 2 to "פברואר", 3 to "מרץ", 4 to "אפריל", 5 to "מאי", 6 to "יוני",
    7 to "יולי", 8 to "אוגוסט", 9 to "ספטמבר", 10 to "אוקטובר", 11 to "נובמבר", 12 to "דצמבר"
)

private fun isGregorianLeap(year: Int) = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
private fun daysInGregorianMonth(year: Int, month: Int): Int = when (month) {
    1, 3, 5, 7, 8, 10, 12 -> 31
    4, 6, 9, 11            -> 30
    2                       -> if (isGregorianLeap(year)) 29 else 28
    else                    -> 30
}

/**
 * A single themed dialog for picking either a Hebrew or a Gregorian date,
 * replacing the old flow of "which kind of date?" chooser → OS-native
 * Gregorian dialog (unthemed) → third-party Hebrew dialog (also unthemed).
 * Both tabs stay in sync with each other and with a "today" shortcut.
 */
@Composable
fun UnifiedDatePicker(
    initialJd: Long,
    todayJd: Long,
    repo: LuachRepository,
    gold: Color, surf: Color, txt: Color, sub: Color,
    onPicked: (Long) -> Unit, onDismiss: () -> Unit
) {
    var activeTab by remember { mutableStateOf(0) }   // 0 = Hebrew, 1 = Gregorian

    val initHd = remember(initialJd) { jdToHebrew(initialJd) }
    var hYear  by remember { mutableStateOf(initHd.year) }
    var hMonth by remember { mutableStateOf(initHd.month) }
    var hDay   by remember { mutableStateOf(initHd.day) }

    val initGd = remember(initialJd) { repo.jdToGregorian(initialJd) }
    var gYear  by remember { mutableStateOf(initGd.first) }
    var gMonth by remember { mutableStateOf(initGd.second) }
    var gDay   by remember { mutableStateOf(initGd.third) }

    fun syncFromHebrew() {
        val jd = hebrewToJd(HebrewDate(hYear, hMonth, hDay))
        val (y, m, d) = repo.jdToGregorian(jd)
        gYear = y; gMonth = m; gDay = d
    }
    fun syncFromGregorian() {
        val jd = repo.gregorianToJdLocal(gYear, gMonth, gDay)
        val hd = jdToHebrew(jd)
        hYear = hd.year; hMonth = hd.month; hDay = hd.day
    }
    fun jumpToToday() {
        val hd = jdToHebrew(todayJd)
        hYear = hd.year; hMonth = hd.month; hDay = hd.day
        val (y, m, d) = repo.jdToGregorian(todayJd)
        gYear = y; gMonth = m; gDay = d
    }

    val leap       = isHebrewLeapYear(hYear)
    val monthNames = if (leap) HEBREW_MONTH_NAMES_LEAP else HEBREW_MONTH_NAMES_REGULAR
    LaunchedEffect(leap) { if (monthNames.none { it.first == hMonth }) hMonth = 7 }
    val hDaysInMonth = remember(hYear, hMonth) { daysInHebrewMonth(hYear, hMonth) }
    LaunchedEffect(hDaysInMonth) { if (hDay > hDaysInMonth) hDay = hDaysInMonth }

    val gDaysInMonth = remember(gYear, gMonth) { daysInGregorianMonth(gYear, gMonth) }
    LaunchedEffect(gDaysInMonth) { if (gDay > gDaysInMonth) gDay = gDaysInMonth }

    val inIsrael = remember { repo.loadSettings().inIsrael }
    val todayHd  = remember(todayJd) { jdToHebrew(todayJd) }
    val todayGd  = remember(todayJd) { repo.jdToGregorian(todayJd) }

    val (todayGY, _, _) = todayGd
    val gregorianYears  = remember(todayGY) { (todayGY - 1..todayGY + 6).toList() }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onDismiss() },
        contentAlignment = Alignment.Center) {
        Card(Modifier.fillMaxWidth(0.88f).heightIn(max = 560.dp).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {},
            shape  = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = surf),
            elevation = CardDefaults.cardElevation(16.dp)) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {

                // ── Tabs ──────────────────────────────────────────────────
                Row(Modifier.fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.18f), RoundedCornerShape(10.dp))
                    .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    listOf("עברי" to 0, "לועזי" to 1).forEach { (label, idx) ->
                        val sel = activeTab == idx
                        Box(Modifier.weight(1f)
                            .background(if (sel) gold else Color.Transparent, RoundedCornerShape(8.dp))
                            .clickable {
                                if (idx == 1 && activeTab == 0) syncFromHebrew()
                                if (idx == 0 && activeTab == 1) syncFromGregorian()
                                activeTab = idx
                            }
                            .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center) {
                            Text(label, style = TextStyle(fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (sel) contrastOn(gold) else sub))
                        }
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (activeTab == 0)
                            "${HEBREW_DAY_NUM.getOrElse(hDay){hDay.toString()}} " +
                            "${monthNames.find{it.first==hMonth}?.second ?: ""} " +
                            (AVAILABLE_YEARS.find { it.first == hYear }?.second ?: hYear.toString())
                        else
                            "$gDay ב${GREGORIAN_MONTH_NAMES.find{it.first==gMonth}?.second ?: ""} $gYear",
                        style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = gold)
                    )
                    TextButton(onClick = { jumpToToday() }) {
                        Text("היום", color = gold, fontSize = 13.sp)
                    }
                }

                if (activeTab == 0) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ScrollPicker(items = monthNames, selected = hMonth, gold = gold, sub = sub,
                            modifier = Modifier.weight(1f)) { hMonth = it }
                        ScrollPicker(items = AVAILABLE_YEARS, selected = hYear, gold = gold, sub = sub,
                            modifier = Modifier.weight(1f)) { hYear = it }
                    }

                    // Day-of-week of the 1st of this Hebrew month, so the grid below
                    // can start each month in its true weekday column.
                    val hFirstDow = remember(hYear, hMonth) {
                        jdDayOfWeek(hebrewToJd(HebrewDate(hYear, hMonth, 1)))
                    }
                    val hTodayDay = if (todayHd.year == hYear && todayHd.month == hMonth) todayHd.day else null
                    Text("יום", style = TextStyle(fontSize = 12.sp, color = sub))
                    MonthChipGrid(
                        items = (1..hDaysInMonth).map { it to HEBREW_DAY_NUM.getOrElse(it) { it.toString() } },
                        selected = hDay, leadingBlanks = hFirstDow, todayDay = hTodayDay,
                        dayJd = { d -> hebrewToJd(HebrewDate(hYear, hMonth, d)) },
                        inIsrael = inIsrael, gold = gold, sub = sub
                    ) { hDay = it }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ScrollPicker(items = GREGORIAN_MONTH_NAMES, selected = gMonth, gold = gold, sub = sub,
                            modifier = Modifier.weight(1f)) { gMonth = it }
                        ScrollPicker(items = gregorianYears.map { it to it.toString() }, selected = gYear,
                            gold = gold, sub = sub, modifier = Modifier.weight(1f)) { gYear = it }
                    }

                    // Day-of-week of the 1st of this Gregorian month.
                    val gFirstDow = remember(gYear, gMonth) {
                        jdDayOfWeek(repo.gregorianToJdLocal(gYear, gMonth, 1))
                    }
                    val gTodayDay = if (todayGd.first == gYear && todayGd.second == gMonth) todayGd.third else null
                    Text("יום", style = TextStyle(fontSize = 12.sp, color = sub))
                    MonthChipGrid(
                        items = (1..gDaysInMonth).map { it to it.toString() },
                        selected = gDay, leadingBlanks = gFirstDow, todayDay = gTodayDay,
                        dayJd = { d -> repo.gregorianToJdLocal(gYear, gMonth, d) },
                        inIsrael = inIsrael, gold = gold, sub = sub
                    ) { gDay = it }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text("ביטול", color = sub)
                    }
                    Button(
                        onClick = {
                            val jd = if (activeTab == 0) hebrewToJd(HebrewDate(hYear, hMonth, hDay))
                                     else repo.gregorianToJdLocal(gYear, gMonth, gDay)
                            onPicked(jd)
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = gold, contentColor = contrastOn(gold))
                    ) {
                        Text("אישור", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * A compact "scroll frame" for month/year: side arrows step one at a time,
 * and tapping the label opens a small dropdown to jump straight to any
 * option. Deliberately looks nothing like the day grid below it (bordered
 * pill vs. grid of chips) so the two kinds of choice read as different at
 * a glance, instead of three identical-looking grids stacked on top of
 * each other.
 */
@Composable
private fun <T> ScrollPicker(
    items: List<Pair<T, String>>, selected: T, gold: Color, sub: Color,
    modifier: Modifier = Modifier, onSelect: (T) -> Unit
) {
    val idx = items.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    var expanded by remember { mutableStateOf(false) }

    Row(modifier
        .background(Color.Black.copy(alpha = 0.18f), RoundedCornerShape(10.dp))
        .border(1.dp, gold.copy(alpha = 0.35f), RoundedCornerShape(10.dp)),
        verticalAlignment = Alignment.CenterVertically) {

        ScrollArrow("‹", enabled = idx > 0, gold = gold, sub = sub) {
            if (idx > 0) onSelect(items[idx - 1].first)
        }

        Box(Modifier.weight(1f)
            .clickable { expanded = true }
            .padding(vertical = 9.dp),
            contentAlignment = Alignment.Center) {
            Text(items.getOrNull(idx)?.second ?: "", style = TextStyle(
                fontSize = 14.sp, fontWeight = FontWeight.Bold, color = gold))
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                items.forEach { (value, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = { onSelect(value); expanded = false }
                    )
                }
            }
        }

        ScrollArrow("›", enabled = idx < items.size - 1, gold = gold, sub = sub) {
            if (idx < items.size - 1) onSelect(items[idx + 1].first)
        }
    }
}

@Composable
private fun ScrollArrow(glyph: String, enabled: Boolean, gold: Color, sub: Color, onClick: () -> Unit) {
    Box(Modifier
        .clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 10.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center) {
        Text(glyph, style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold,
            color = if (enabled) gold else sub.copy(alpha = 0.3f)))
    }
}

/**
 * Lays the days of a month out as a real calendar grid: a Sun..Sat header
 * row on top, and each day chip placed in the column matching its actual
 * weekday - not just wrapped every 7 items regardless of where the month
 * starts. `leadingBlanks` (0..6, from jdDayOfWeek() of the 1st) pads the
 * first row with empty cells so day 1 lands under its true weekday.
 *
 * The whole grid is forced to RTL regardless of the system/app locale, so
 * "Sunday" is always the rightmost column and "Saturday" the leftmost -
 * matching how a Hebrew calendar reads - instead of silently flipping on
 * a device whose locale Compose doesn't consider RTL.
 */
/** Warm, festive tint for a day that has a Jewish holiday/fast - distinct from the
 *  cooler Shabbat tint below so the two never look like the same thing. */
private val HOLIDAY_TINT = Color(0xFFFFB74D)

@Composable
private fun MonthChipGrid(
    items: List<Pair<Int, String>>, selected: Int, leadingBlanks: Int, todayDay: Int?,
    dayJd: (Int) -> Long, inIsrael: Boolean, gold: Color, sub: Color,
    onSelect: (Int) -> Unit
) {
    val columns = 7
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            WeekdayHeaderRow(sub)

            val blanksInFirstRow = leadingBlanks.coerceIn(0, columns - 1)
            val padded: List<Pair<Int, String>?> =
                List(blanksInFirstRow) { null } + items.map { it }

            padded.chunked(columns).forEach { rowItems ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    rowItems.forEach { pair ->
                        if (pair == null) {
                            Spacer(Modifier.weight(1f))
                        } else {
                            val (dayNum, label) = pair
                            val jd = dayJd(dayNum)
                            val isShabbat = jdDayOfWeek(jd) == 6
                            val holiday = holidayName(jd, inIsrael)
                            val tint = when {
                                holiday.isNotEmpty() -> HOLIDAY_TINT.copy(alpha = 0.28f)
                                isShabbat -> gold.copy(alpha = 0.14f)
                                else -> Color.Transparent
                            }
                            PickerChip(
                                label = label, selected = dayNum == selected,
                                isToday = dayNum == todayDay, tint = tint, gold = gold,
                                modifier = Modifier.weight(1f)
                            ) { onSelect(dayNum) }
                        }
                    }
                    // Keep the last, possibly-partial row from stretching its chips
                    repeat(columns - rowItems.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** Sun..Sat header labels sitting above the day grid, aligned to the same 7 columns. */
@Composable
private fun WeekdayHeaderRow(sub: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        WEEKDAY_INITIALS.forEach { label ->
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(label, style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = sub))
            }
        }
    }
}

/**
 * A single day cell. `tint` paints a soft background for Shabbat/holidays even
 * when the day isn't selected. `isToday` draws a full-opacity ring (without a
 * fill) so "today" reads clearly even though it's a different signal from
 * "selected" - the two can both be true at once, in which case the gold fill
 * (selected) simply wins visually since it's the stronger of the two states.
 */
@Composable
private fun PickerChip(
    label: String, selected: Boolean, isToday: Boolean, tint: Color, gold: Color,
    modifier: Modifier = Modifier, onClick: () -> Unit
) {
    val borderWidth = if (selected) 1.dp else if (isToday) 1.6.dp else 1.dp
    val borderColor = when {
        selected -> gold
        isToday  -> gold
        else     -> gold.copy(alpha = 0.3f)
    }
    Box(modifier
        .background(if (selected) gold else tint, RoundedCornerShape(8.dp))
        .border(borderWidth, borderColor, RoundedCornerShape(8.dp))
        .clickable(onClick = onClick)
        .padding(horizontal = 2.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center) {
        Text(label, style = TextStyle(fontSize = 12.sp,
            fontWeight = if (isToday && !selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) contrastOn(gold) else gold),
            maxLines = 1)
    }
}
