package com.luachitim.data

import com.luachitim.util.*
import kotlin.random.Random

// ── Storage keys ──────────────────────────────────────────────────────────
const val CURRENT_WEEK_KEY       = "cur_week"
const val CURRENT_LUACH_KEY      = "cur_luach_key"
const val LUACH_KEYS_KEY         = "luach_keys"
const val SETTINGS_ISRAEL_KEY    = "in_israel"
const val SETTINGS_DARK_KEY      = "dark_mode"
const val SETTINGS_COMPACT_KEY   = "compact_view"
const val SETTINGS_COMPACT_CUT_KEY = "compact_cut"
const val SETTINGS_AUTOSYNC_KEY  = "autosync_calendar"
const val ZOOM_KEY_PREFIX        = "zoom_"
const val OFFSET_X_PREFIX        = "ox_"
const val OFFSET_Y_PREFIX        = "oy_"

// ── Models ────────────────────────────────────────────────────────────────
data class LuachEntry(
    val key: String,
    val name: String,
    val pdfPath: String,
    val hebrewYear: Int = 5786,      // ← year per luach
    val inIsrael: Boolean = true     // ← Israel/Diaspora schedule this file was made for
)

data class AppSettings(
    val inIsrael: Boolean = true,
    val darkMode: Boolean = false,
    val compactView: Boolean = false,
    val compactCutPercent: Float = 0.33f,   // default 33%
    // On by default: diary events mirror automatically into the phone's own
    // local calendar (no account, no network) so they show up in whichever
    // calendar app the person already uses - the same way any other calendar
    // app's events do.
    val autoSyncCalendar: Boolean = true
)

// ── Repository ────────────────────────────────────────────────────────────
class LuachRepository {

    // Schedule cache — keyed by (year, inIsrael)
    private val scheduleCache = mutableMapOf<Pair<Int,Boolean>, List<Pair<Long,String>>>()

    fun getSchedule(hebrewYear: Int, inIsrael: Boolean): List<Pair<Long, String>> {
        val key = hebrewYear to inIsrael
        return scheduleCache.getOrPut(key) {
            buildLuachSchedule(hebrewYear, inIsrael)
        }
    }

    // ── Settings ──────────────────────────────────────────────────────────
    fun loadSettings(): AppSettings = AppSettings(
        inIsrael          = loadFilePath(SETTINGS_ISRAEL_KEY) != "false",
        darkMode          = loadFilePath(SETTINGS_DARK_KEY)   == "true",
        compactView       = loadFilePath(SETTINGS_COMPACT_KEY) == "true",
        compactCutPercent = loadFilePath(SETTINGS_COMPACT_CUT_KEY)?.toFloatOrNull() ?: 0.33f,
        autoSyncCalendar  = loadFilePath(SETTINGS_AUTOSYNC_KEY) != "false"
    )
    fun saveSettings(s: AppSettings) {
        saveFilePath(SETTINGS_ISRAEL_KEY,     s.inIsrael.toString())
        saveFilePath(SETTINGS_DARK_KEY,       s.darkMode.toString())
        saveFilePath(SETTINGS_COMPACT_KEY,    s.compactView.toString())
        saveFilePath(SETTINGS_COMPACT_CUT_KEY, s.compactCutPercent.toString())
        saveFilePath(SETTINGS_AUTOSYNC_KEY,   s.autoSyncCalendar.toString())
    }

    // ── Multi-luach flat storage ───────────────────────────────────────────
    fun loadLuachList(): List<LuachEntry> {
        val keys = loadFilePath(LUACH_KEYS_KEY)
            ?.split(",")?.filter { it.isNotEmpty() }
            ?: return emptyList()
        return keys.mapNotNull { key ->
            val name = loadFilePath("luach_name_$key") ?: return@mapNotNull null
            val path = loadFilePath("luach_path_$key") ?: return@mapNotNull null
            val year = loadFilePath("luach_year_$key")?.toIntOrNull() ?: 5786
            val israel = loadFilePath("luach_israel_$key") != "false"
            LuachEntry(key, name, path, year, israel)
        }
    }

    private fun saveLuachList(list: List<LuachEntry>) {
        saveFilePath(LUACH_KEYS_KEY, list.joinToString(",") { it.key })
        for (e in list) {
            saveFilePath("luach_name_${e.key}", e.name)
            saveFilePath("luach_path_${e.key}", e.pdfPath)
            saveFilePath("luach_year_${e.key}", e.hebrewYear.toString())
            saveFilePath("luach_israel_${e.key}", e.inIsrael.toString())
        }
    }

    fun addLuach(name: String, path: String, hebrewYear: Int, inIsrael: Boolean = true): List<LuachEntry> {
        val list = loadLuachList().toMutableList()
        val existingKeys = list.map { it.key }.toHashSet()
        // julianDay_size alone can collide once an entry has been removed
        // (list.size repeats), so we mix in a random suffix and verify
        // uniqueness against the current list before committing.
        var key: String
        do {
            key = "k${currentJulianDay()}_${Random.nextInt(0, 1_000_000)}"
        } while (key in existingKeys)
        list.add(LuachEntry(key, name, path, hebrewYear, inIsrael))
        saveLuachList(list)
        return list
    }

    fun renameLuach(key: String, newName: String): List<LuachEntry> {
        val list = loadLuachList().map {
            if (it.key == key) it.copy(name = newName) else it
        }
        saveLuachList(list)
        return list
    }

    fun removeLuach(key: String): List<LuachEntry> {
        val list = loadLuachList().filter { it.key != key }
        saveLuachList(list)
        clearFilePath("luach_name_$key")
        clearFilePath("luach_path_$key")
        clearFilePath("luach_year_$key")
        clearFilePath("luach_israel_$key")
        if (loadActiveLuachKey() == key)
            saveFilePath(CURRENT_LUACH_KEY, list.firstOrNull()?.key ?: "")
        return list
    }

    fun loadActiveLuachKey(): String = loadFilePath(CURRENT_LUACH_KEY) ?: ""
    fun saveActiveLuachKey(k: String) = saveFilePath(CURRENT_LUACH_KEY, k)
    fun getActiveLuach(): LuachEntry? {
        val key = loadActiveLuachKey()
        return loadLuachList().find { it.key == key }
    }

    // ── Week navigation ───────────────────────────────────────────────────
    fun saveCurrentWeek(i: Int) = saveFilePath(CURRENT_WEEK_KEY, i.toString())
    fun loadCurrentWeek(): Int  = loadFilePath(CURRENT_WEEK_KEY)?.toIntOrNull()
                                  ?: getCurrentWeekIndex(loadSettings().inIsrael,
                                                         getActiveLuach()?.hebrewYear ?: 5786)

    fun getCurrentWeekIndex(inIsrael: Boolean, hebrewYear: Int): Int {
        val schedule = getSchedule(hebrewYear, inIsrael)
        val todaySun = weekSunday(currentJulianDay())
        val idx = schedule.indexOfFirst { it.first == todaySun }
        return if (idx >= 0) idx else 0
    }

    fun getWeekInfoForIndex(idx: Int, inIsrael: Boolean, hebrewYear: Int): WeekInfo {
        val schedule = getSchedule(hebrewYear, inIsrael)
        val safeIdx  = idx.coerceIn(0, schedule.size - 1)
        val (sunJd, parasha) = schedule[safeIdx]
        return WeekInfo(
            parashaName  = parasha,
            parashaIndex = safeIdx,
            pdfPageStart = 4 + safeIdx * 2,
            pdfPageEnd   = 5 + safeIdx * 2,
            weekStartJd  = sunJd,
            weekEndJd    = sunJd + 6
        )
    }

    fun getTodayDisplayInfo(inIsrael: Boolean, hebrewYear: Int): HebrewDateDisplay {
        val hd      = getTodayHebrewDisplay()
        val dow     = getTodayDayOfWeek()
        val weekIdx = getCurrentWeekIndex(inIsrael, hebrewYear)
        val parasha = getSchedule(hebrewYear, inIsrael).getOrNull(weekIdx)?.second ?: ""
        return HebrewDateDisplay(
            hebrewDate       = hd,
            dayOfWeek        = dow,
            dayOfWeekName    = dayOfWeekName(dow),
            hebrewDateString = formatHebrewDate(hd),
            parashaName      = parasha,
            holidayName      = holidayName(currentJulianDay(), inIsrael)
        )
    }

    fun getAllParashot(inIsrael: Boolean, hebrewYear: Int): List<Pair<String, Int>> =
        getSchedule(hebrewYear, inIsrael).mapIndexed { i, (_, p) ->
            Pair(if (p.isEmpty()) "(יו\"ט)" else p, i)
        }

    fun getTotalWeeks(inIsrael: Boolean, hebrewYear: Int): Int =
        getSchedule(hebrewYear, inIsrael).size

    fun findWeekIndexForDate(year: Int, month: Int, day: Int,
                              inIsrael: Boolean, hebrewYear: Int): Int {
        val jd  = gregorianToJd(year, month, day)
        val sun = weekSunday(jd)
        val schedule = getSchedule(hebrewYear, inIsrael)
        val idx = schedule.indexOfFirst { it.first == sun }
        if (idx >= 0) return idx
        return when {
            schedule.isEmpty()           -> 0
            sun < schedule.first().first -> 0
            else                         -> schedule.size - 1
        }
    }

    // ── Zoom / pan ────────────────────────────────────────────────────────
    fun saveZoom(zoom: Float, ox: Float, oy: Float) {
        saveFilePath("${ZOOM_KEY_PREFIX}global", zoom.toString())
        saveFilePath("${OFFSET_X_PREFIX}global", ox.toString())
        saveFilePath("${OFFSET_Y_PREFIX}global", oy.toString())
    }
    fun loadZoom(): Triple<Float, Float, Float> {
        val z  = loadFilePath("${ZOOM_KEY_PREFIX}global")?.toFloatOrNull() ?: 1f
        val ox = loadFilePath("${OFFSET_X_PREFIX}global")?.toFloatOrNull() ?: 0f
        val oy = loadFilePath("${OFFSET_Y_PREFIX}global")?.toFloatOrNull() ?: 0f
        return Triple(z, ox, oy)
    }

    // ── Date conversion ───────────────────────────────────────────────────
    fun jdToGregorian(jd: Long): Triple<Int, Int, Int> {
        val a = jd + 32044; val b = (4*a+3)/146097; val c = a-(146097*b)/4
        val d = (4*c+3)/1461; val e = c-(1461*d)/4; val m = (5*e+2)/153
        return Triple((100*b+d-4800+m/10).toInt(), (m+3-12*(m/10)).toInt(),
                      (e-(153*m+2)/5+1).toInt())
    }
    fun gregorianToJdLocal(year: Int, month: Int, day: Int) =
        gregorianToJd(year, month, day)

    // ── Diary / Events ────────────────────────────────────────────────────
    // title/note are stored on one line as "<title>|<note>". A literal '|' or
    // newline typed by the user would otherwise corrupt that split, so both
    // are escaped/unescaped going in and out of storage.
    private fun escapeField(s: String): String =
        s.replace("\\", "\\\\").replace("|", "\\p").replace("\n", "\\n")

    private fun unescapeField(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'p' -> { out.append('|'); i += 2 }
                    'n' -> { out.append('\n'); i += 2 }
                    '\\' -> { out.append('\\'); i += 2 }
                    else -> { out.append(c); i += 1 }
                }
            } else {
                out.append(c); i += 1
            }
        }
        return out.toString()
    }

    // A registry of which days have at least one event, so "export everything"
    // doesn't have to scan every possible day - just the days that matter.
    private fun loadEventDaySet(): MutableSet<Long> =
        loadFilePath("event_days")
            ?.split(",")?.mapNotNull { it.toLongOrNull() }?.toMutableSet()
            ?: mutableSetOf()

    private fun saveEventDaySet(days: Set<Long>) =
        saveFilePath("event_days", days.sorted().joinToString(","))

    // Line format: title|note|calEventId|importedEventId
    //  - calEventId: the row ID of our own local calendar's mirror of this
    //    entry, once outbound sync has created it there (empty = not synced out yet).
    //  - importedEventId: if this entry originated from another calendar app's
    //    event (inbound sync), the source event's own row ID there - used to
    //    recognize it on later scans (avoid re-importing) and to update it in
    //    place if the source event's title/note changes. Empty for entries the
    //    person created directly in this app.
    // Older entries saved before a given field existed simply parse with that
    // field null - same as "not linked that way".
    private fun parseEventLine(jd: Long, idx: Int, raw: String): DiaryEvent {
        val parts = raw.split("|", limit = 4)
        val title      = unescapeField(parts.getOrNull(0) ?: "")
        val note       = unescapeField(parts.getOrNull(1) ?: "")
        val calId      = parts.getOrNull(2)?.toLongOrNull()
        val importedId = parts.getOrNull(3)?.toLongOrNull()
        return DiaryEvent(jd, idx, title, note, calId, importedId)
    }

    private fun eventLine(title: String, note: String, calEventId: Long?, importedEventId: Long?): String =
        "${escapeField(title)}|${escapeField(note)}|${calEventId ?: ""}|${importedEventId ?: ""}"

    fun loadEventsForDay(jd: Long): List<DiaryEvent> {
        val keys = loadFilePath("event_keys_$jd")
            ?.split(",")?.filter { it.isNotEmpty() }
            ?: return emptyList()
        return keys.mapNotNull { idxStr ->
            val idx = idxStr.toIntOrNull() ?: return@mapNotNull null
            val raw = loadFilePath("event_${jd}_$idx") ?: return@mapNotNull null
            parseEventLine(jd, idx, raw)
        }
    }

    fun addEvent(jd: Long, title: String, note: String): List<DiaryEvent> {
        val existing = loadEventsForDay(jd)
        val newIdx   = (existing.maxOfOrNull { it.idx } ?: -1) + 1
        saveFilePath("event_${jd}_$newIdx", eventLine(title, note, null, null))
        val allIdxs  = (existing.map { it.idx } + newIdx).joinToString(",")
        saveFilePath("event_keys_$jd", allIdxs)
        val days = loadEventDaySet()
        if (days.add(jd)) saveEventDaySet(days)
        return loadEventsForDay(jd)
    }

    /** Creates a diary entry that mirrors an event from another calendar app
     *  (inbound sync) - tagged with [importedEventId] so a later scan can
     *  recognize it and update it in place instead of duplicating it. */
    fun addImportedEvent(jd: Long, title: String, note: String, importedEventId: Long): DiaryEvent {
        val existing = loadEventsForDay(jd)
        val newIdx   = (existing.maxOfOrNull { it.idx } ?: -1) + 1
        saveFilePath("event_${jd}_$newIdx", eventLine(title, note, null, importedEventId))
        val allIdxs  = (existing.map { it.idx } + newIdx).joinToString(",")
        saveFilePath("event_keys_$jd", allIdxs)
        val days = loadEventDaySet()
        if (days.add(jd)) saveEventDaySet(days)
        return loadEventsForDay(jd).first { it.idx == newIdx }
    }

    fun deleteEvent(jd: Long, idx: Int): List<DiaryEvent> {
        clearFilePath("event_${jd}_$idx")
        val remaining = loadEventsForDay(jd).filter { it.idx != idx }
        saveFilePath("event_keys_$jd", remaining.joinToString(",") { it.idx.toString() })
        if (remaining.isEmpty()) {
            val days = loadEventDaySet()
            if (days.remove(jd)) saveEventDaySet(days)
        }
        return remaining
    }

    /** Preserves the event's existing device-calendar links (both outbound
     *  [DiaryEvent.calEventId] and inbound [DiaryEvent.importedEventId], if
     *  any) - editing title/note should update those same rows, not orphan them. */
    fun editEvent(jd: Long, idx: Int, title: String, note: String): List<DiaryEvent> {
        val existing = loadEventsForDay(jd).find { it.idx == idx }
        saveFilePath("event_${jd}_$idx", eventLine(title, note, existing?.calEventId, existing?.importedEventId))
        return loadEventsForDay(jd)
    }

    /** Records (or clears, on failure/removal) which device-calendar event ID
     *  a diary entry is mirrored to (outbound) - called after a background sync op. */
    fun setEventCalendarId(jd: Long, idx: Int, calEventId: Long?) {
        val ev = loadEventsForDay(jd).find { it.idx == idx } ?: return
        saveFilePath("event_${jd}_$idx", eventLine(ev.title, ev.note, calEventId, ev.importedEventId))
    }

    fun hasEvents(jd: Long): Boolean =
        loadFilePath("event_keys_$jd")?.isNotEmpty() == true

    /** All diary events across every day, ordered by date - used for ICS export
     *  and for reconciling both sync directions against the device calendar. */
    fun getAllEvents(): List<DiaryEvent> =
        loadEventDaySet().sorted().flatMap { loadEventsForDay(it) }
}

// ── Diary event data class ─────────────────────────────────────────────────
// calEventId: the row ID of this event in the device's own local calendar,
// once outbound auto-sync has mirrored it there - null means "not synced out yet".
// importedEventId: if this entry was brought in from another calendar app
// (inbound sync), the source event's own row ID there - null for entries the
// person created directly in this app's diary.
data class DiaryEvent(
    val jd: Long, val idx: Int, val title: String, val note: String,
    val calEventId: Long? = null,
    val importedEventId: Long? = null
)

// ── Available Hebrew years for the picker ─────────────────────────────────
// Generated dynamically (this year -1 .. +5) instead of a static list that
// would silently stop offering new years and start showing a raw integer
// once the app runs past the last hardcoded entry.
val AVAILABLE_YEARS: List<Pair<Int, String>> by lazy {
    val todayYear = jdToHebrew(currentJulianDay()).year
    (todayYear - 1..todayYear + 5).map { it to hebrewYearGematria(it) }
}

