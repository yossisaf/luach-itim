package com.luachitim.ui

import androidx.compose.animation.*
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.luachitim.data.AppSettings
import com.luachitim.data.DiaryEvent
import com.luachitim.data.AVAILABLE_YEARS
import com.luachitim.data.LuachEntry
import com.luachitim.data.LuachRepository
import com.luachitim.data.PdfRenderer
import com.luachitim.data.buildIcs
import com.luachitim.data.parseIcsEvents
import com.luachitim.generated.resources.Res
import com.luachitim.generated.resources.app_icon
import com.luachitim.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource

private val BgDark   = Color(0xFF0D1B2A)
private val BgDay    = Color(0xFFF5F0E8)
private val SurfDark = Color(0xFF1B2A3B)
private val SurfDay  = Color(0xFFE8E0D0)
private val AccentDark    = Color(0xFFD8E6FF)   // soft blue-white on dark bg
private val AccentDay     = Color(0xFF1A2744)   // deep navy on light bg
private val Gold          = AccentDark           // backward-compat alias
private val TxtDark       = Color(0xFFF0F4FF)   // near-white on dark
private val TxtDay        = Color(0xFF1A1A2E)   // near-black on light
private val SubDark       = Color(0xFF8A94AA)   // muted on dark
private val SubDay        = Color(0xFF5A6070)   // muted on light
// Contrasting text on top of accent colour
private val OnAccentDark  = Color(0xFF0D1B2A)   // dark on light-accent (dark mode)
private val OnAccentDay   = Color(0xFFFAFAFF)   // light on dark-accent (light mode)

// ── Contrast helper ──────────────────────────────────────────────────────
/** Returns a readable text colour (dark or light) for text on top of [bg]. */
fun contrastOn(bg: Color): Color {
    val lum = bg.red * 0.299f + bg.green * 0.587f + bg.blue * 0.114f
    return if (lum > 0.45f) Color(0xFF0D1B2A) else Color(0xFFF0F4FF)
}

// ── ColorMatrix for night-mode PDF inversion ─────────────────────────────
private val nightColorFilter = ColorFilter.colorMatrix(
    ColorMatrix(floatArrayOf(
        -1f,  0f,  0f, 0f, 255f,
         0f, -1f,  0f, 0f, 255f,
         0f,  0f, -1f, 0f, 255f,
         0f,  0f,  0f, 1f,   0f
    ))
)

// ── Root ──────────────────────────────────────────────────────────────────
@Composable
fun LuachApp() {
    val repo = remember { LuachRepository() }

    var isLoading   by remember { mutableStateOf(true) }
    var loadError   by remember { mutableStateOf<String?>(null) }
    var settings    by remember { mutableStateOf(AppSettings()) }
    var activeLuach by remember { mutableStateOf<LuachEntry?>(null) }
    var weekIndex      by remember { mutableStateOf(0) }
    var selectedDayJd  by remember { mutableStateOf(currentJulianDay()) }  // today

    // App-wide queue of changes still to mirror into the phone's own
    // calendar, drained one at a time by DeviceCalendarSync below. Owned
    // here (not inside the diary screen) so syncing runs automatically the
    // moment the app opens - the same way a real calendar app's sync isn't
    // gated behind opening any particular screen.
    var pendingCalOps by remember { mutableStateOf<List<CalSyncOp>>(emptyList()) }
    val enqueueCalOp: (CalSyncOp) -> Unit = { op -> pendingCalOps = pendingCalOps + op }

    // Bumped once outbound registration confirms calendar permission is in
    // place, to run the inbound scan right after - not before, so the two
    // directions never race each other for the same permission dialog.
    var importTrigger by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        try {
            withContext(Dispatchers.Default) {
                settings    = repo.loadSettings()
                activeLuach = repo.getActiveLuach()
                weekIndex   = repo.loadCurrentWeek()
                repo.getSchedule(activeLuach?.hebrewYear ?: 5786, settings.inIsrael)
            }
        } catch (e: Exception) {
            loadError = e.message ?: "שגיאה בטעינה"
        } finally {
            isLoading = false
        }

        // Kick off calendar sync right away on startup: register the app's
        // own on-device calendar immediately (so it's visible in Google
        // Calendar/Samsung Calendar etc. even with zero events yet), then
        // catch up any diary events that were added before auto-sync
        // existed or while it was switched off. Guarded on its own - a
        // problem here must never block the app from opening.
        try {
            if (loadError == null && settings.autoSyncCalendar) {
                val unsynced = repo.getAllEvents().filter { it.calEventId == null }
                val backfill = unsynced.map { ev ->
                    val (y, m, d) = repo.jdToGregorian(ev.jd)
                    CalSyncOp.Upsert(ev.jd, ev.idx, null, y, m, d, ev.title, ev.note)
                }
                pendingCalOps = pendingCalOps + listOf(CalSyncOp.EnsureRegistered) + backfill
            }
        } catch (_: Exception) {
            // Calendar sync couldn't be kicked off this time - the diary
            // itself still works fine locally either way.
        }
    }

    // Drains pendingCalOps one item at a time, entirely on-device - no
    // network involved at any point, including the one-time system
    // permission prompt.
    DeviceCalendarSync(
        queue = pendingCalOps,
        onOpDone = { op, resultId ->
            if (op is CalSyncOp.Upsert) repo.setEventCalendarId(op.jd, op.idx, resultId)
            // Registration succeeded (permission is granted) - safe to run
            // the inbound scan now, same launch, no extra permission prompt.
            if (op is CalSyncOp.EnsureRegistered && resultId != null) importTrigger++
            pendingCalOps = pendingCalOps.drop(1)
        }
    )

    // Inbound sync: brings events created in other calendar apps (Google
    // Calendar, Samsung Calendar, ...) into the diary automatically. Purely
    // local/offline like the outbound side - only reads what's already on
    // the device.
    DeviceCalendarImport(
        trigger = importTrigger,
        onResult = { external ->
            try {
                val existingByImportId = repo.getAllEvents()
                    .filter { it.importedEventId != null }
                    .associateBy { it.importedEventId }

                for (ext in external) {
                    val jd = repo.gregorianToJdLocal(ext.year, ext.month, ext.day)
                    val matched = existingByImportId[ext.eventId]
                    when {
                        matched == null ->
                            repo.addImportedEvent(jd, ext.title, ext.note, ext.eventId)
                        matched.jd != jd -> {
                            // Moved to a different day at the source - move it here too.
                            repo.deleteEvent(matched.jd, matched.idx)
                            repo.addImportedEvent(jd, ext.title, ext.note, ext.eventId)
                        }
                        matched.title != ext.title || matched.note != ext.note ->
                            repo.editEvent(matched.jd, matched.idx, ext.title, ext.note)
                        // else: unchanged, nothing to do
                    }
                }
            } catch (_: Exception) {
                // Import couldn't complete this time - nothing already in the
                // diary is affected either way.
            }
        }
    )

    val bg     = if (settings.darkMode) BgDark    else BgDay
    val surf   = if (settings.darkMode) SurfDark  else SurfDay
    val accent = if (settings.darkMode) AccentDark else AccentDay
    val txt    = if (settings.darkMode) TxtDark    else TxtDay
    val sub    = if (settings.darkMode) SubDark    else SubDay

    MaterialTheme(
        colorScheme = if (settings.darkMode)
            darkColorScheme(background=BgDark,  surface=SurfDark, primary=AccentDark,
                onPrimary=BgDark, onBackground=TxtDark, onSurface=TxtDark)
        else
            lightColorScheme(background=BgDay,  surface=SurfDay,  primary=AccentDay,
                onPrimary=Color.White, onBackground=TxtDay, onSurface=TxtDay)
    ) {
        when {
            isLoading  -> LoadingScreen(bg, accent, sub)
            loadError != null -> ErrorScreen(loadError!!, bg, accent, txt) {
                loadError = null; isLoading = true
            }
            activeLuach == null -> WelcomeScreen(
                repo=repo, bg=bg, surf=surf, gold=accent, txt=txt, sub=sub,
                onReady = { entry ->
                    activeLuach = entry
                    // The Israel/Diaspora schedule is chosen per-luach (in the
                    // setup wizard) - keep the app's effective setting in step
                    // with whichever luach is now active.
                    if (entry.inIsrael != settings.inIsrael) {
                        settings = settings.copy(inIsrael = entry.inIsrael)
                        repo.saveSettings(settings)
                    }
                    weekIndex = repo.getCurrentWeekIndex(settings.inIsrael, entry.hebrewYear)
                }
            )
            else -> MainScreen(
                pdfPath        = activeLuach!!.pdfPath,
                weekIndex      = weekIndex,
                selectedDayJd  = selectedDayJd,
                onDaySelected  = { jd -> selectedDayJd = jd },
                repo = repo, settings = settings,
                bg=bg, surf=surf, gold=accent, txt=txt, sub=sub,
                enqueueCalOp = enqueueCalOp,
                onWeekChange     = { i -> weekIndex = i; repo.saveCurrentWeek(i) },
                onToday          = {
                    weekIndex = repo.getCurrentWeekIndex(settings.inIsrael,
                        repo.getActiveLuach()?.hebrewYear ?: 5786)
                    repo.saveCurrentWeek(weekIndex)
                    selectedDayJd = currentJulianDay()
                },
                onSettingsChange = { s ->
                    val israelChanged = s.inIsrael != settings.inIsrael
                    settings = s; repo.saveSettings(s)
                    // Only re-sync to today's week if the Israel/Diaspora schedule
                    // actually changed — other settings (dark mode, compact view)
                    // must NOT move the user off the day/week they're viewing.
                    if (israelChanged) {
                        weekIndex = repo.getCurrentWeekIndex(s.inIsrael,
                            repo.getActiveLuach()?.hebrewYear ?: 5786)
                    }
                },
                onLuachChange = { entry ->
                    activeLuach = entry
                    // Switching luachs (via "ניהול לוחות") also switches to
                    // that luach's own Israel/Diaspora schedule.
                    if (entry.inIsrael != settings.inIsrael) {
                        settings = settings.copy(inIsrael = entry.inIsrael)
                        repo.saveSettings(settings)
                        weekIndex = repo.getCurrentWeekIndex(settings.inIsrael, entry.hebrewYear)
                    }
                }
            )
        }
    }
}

// ── Loading ───────────────────────────────────────────────────────────────
@Composable
fun LoadingScreen(bg: Color, gold: Color, sub: Color) {
    Box(Modifier.fillMaxSize().background(bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator(color = gold, strokeWidth = 3.dp)
            Text("לוח עתים לבינה",
                style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = gold))
            Text("טוען...", style = TextStyle(fontSize = 14.sp, color = sub))
        }
    }
}

// ── Error ─────────────────────────────────────────────────────────────────
@Composable
fun ErrorScreen(error: String, bg: Color, gold: Color, txt: Color, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().background(bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(32.dp)) {
            Text("שגיאה", style = TextStyle(fontSize = 22.sp, color = gold, fontWeight = FontWeight.Bold))
            Text(error,   style = TextStyle(fontSize = 13.sp, color = txt, textAlign = TextAlign.Center))
            Button(onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = gold, contentColor = bg)) {
                Text("נסה שוב")
            }
        }
    }
}

// ── Welcome ───────────────────────────────────────────────────────────────
@Composable
fun WelcomeScreen(repo: LuachRepository, bg: Color, surf: Color, gold: Color,
                  txt: Color, sub: Color, onReady: (LuachEntry) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf(false) }
    val currentHebrewYear = remember { jdToHebrew(currentJulianDay()).year }
    var luachName  by remember { mutableStateOf("לוח ${hebrewYearGematria(currentHebrewYear)}") }
    var luachYear  by remember { mutableStateOf(currentHebrewYear) }
    var luachInIsrael by remember { mutableStateOf(true) }
    var showName   by remember { mutableStateOf(false) }
    var pickedPath by remember { mutableStateOf<String?>(null) }
    val clipboard  = LocalClipboardManager.current
    var emailCopied by remember { mutableStateOf(false) }
    LaunchedEffect(emailCopied) { if (emailCopied) { delay(1500); emailCopied = false } }

    FilePicker(
        show = showPicker, fileExtensions = listOf("pdf"),
        onError = { showPicker = false; importError = true }
    ) { path ->
        showPicker = false
        if (path != null) { pickedPath = path; showName = true }
    }

    if (showName && pickedPath != null) {
        AlertDialog(
            onDismissRequest = { showName = false },
            title = { Text("פרטי הלוח", style = TextStyle(color = gold, fontWeight = FontWeight.Bold)) },
            text  = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = luachName, onValueChange = { luachName = it },
                        label = { Text("שם הלוח") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    Text("שנת הלוח:", style = TextStyle(fontSize = 13.sp, color = sub))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(AVAILABLE_YEARS.size) { i ->
                            val (yr, name) = AVAILABLE_YEARS[i]
                            val sel = yr == luachYear
                            Box(Modifier
                                .background(if (sel) gold else Color.Transparent, RoundedCornerShape(8.dp))
                                .border(1.dp, gold.copy(if (sel) 1f else .35f), RoundedCornerShape(8.dp))
                                .clickable { luachYear = yr }
                                .padding(horizontal = 10.dp, vertical = 6.dp)) {
                                Text(name, style = TextStyle(fontSize = 13.sp,
                                    color = if (sel) contrastOn(gold) else gold,
                                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal))
                            }
                        }
                    }
                    Text("הלוח מיועד ל:", style = TextStyle(fontSize = 13.sp, color = sub))
                    Surface(shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, gold.copy(.3f)),
                        color  = Color.Transparent,
                        modifier = Modifier.fillMaxWidth()) {
                        Row {
                            ToggleOption("ארץ ישראל", luachInIsrael,  gold, txt, surf) { luachInIsrael = true  }
                            ToggleOption("חוץ לארץ",  !luachInIsrael, gold, txt, surf) { luachInIsrael = false }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    showName = false
                    val list  = repo.addLuach(luachName, pickedPath!!, luachYear, luachInIsrael)
                    val entry = list.last()
                    repo.saveActiveLuachKey(entry.key)
                    onReady(entry)
                }, colors = ButtonDefaults.buttonColors(containerColor = gold, contentColor = bg)) {
                    Text("אישור")
                }
            },
            dismissButton = { TextButton(onClick = { showName = false }) { Text("ביטול") } }
        )
    }

    Box(Modifier.fillMaxSize().background(bg), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            repeat(12) { i ->
                drawCircle(color = if (bg == BgDark) SurfDark else SurfDay,
                    radius = i * 90f,
                    center = Offset(size.width * .85f, size.height * .15f),
                    style  = Stroke(1f))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(28.dp),
            modifier = Modifier.padding(40.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("לוח עתים לבינה", style = TextStyle(fontSize = 32.sp,
                    fontWeight = FontWeight.Bold, color = gold, textAlign = TextAlign.Center))
                Spacer(Modifier.height(6.dp))
                Text("לוח שנה עברי ופרשות השבוע", style = TextStyle(fontSize = 15.sp, color = sub, textAlign = TextAlign.Center))
            }
            Box(Modifier.width(100.dp).height(2.dp).background(
                Brush.horizontalGradient(listOf(Color.Transparent, gold, Color.Transparent))))
            Text("לתחילת השימוש, יש לבחור את קובץ הלוח (PDF)",
                style = TextStyle(fontSize = 15.sp, color = txt, textAlign = TextAlign.Center))
            if (importError)
                Text("לא הצלחנו לקרוא את הקובץ שנבחר. ודאו שזהו קובץ PDF תקין ונסו שוב.",
                    style = TextStyle(fontSize = 13.sp, color = Color(0xFFD9534F), textAlign = TextAlign.Center))
            Button(onClick = { importError = false; showPicker = true },
                colors   = ButtonDefaults.buttonColors(containerColor = gold, contentColor = bg),
                modifier = Modifier.height(54.dp).widthIn(min = 210.dp),
                shape    = RoundedCornerShape(12.dp)) {
                Text("📂  בחר קובץ PDF",
                    style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold))
            }

            // About content shown directly here, not behind a dialog/tap
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("גרסה ${VersionInfo.VERSION_NAME}",
                    style = TextStyle(fontSize = 11.sp, color = sub))
                Text(
                    if (emailCopied) "הכתובת הועתקה" else "ys10app@gmail.com",
                    style = TextStyle(fontSize = 11.sp, color = sub),
                    modifier = Modifier.clickable {
                        clipboard.setText(AnnotatedString("ys10app@gmail.com"))
                        emailCopied = true
                    }
                )
                Text("© ${hebrewYearGematria(currentHebrewYear)} לוח עתים לבינה · כל הזכויות שמורות",
                    style = TextStyle(fontSize = 9.5.sp, color = sub.copy(alpha = .7f)))
            }
        }
    }
}

// ── Main Screen ───────────────────────────────────────────────────────────
@Composable
fun MainScreen(
    pdfPath: String, weekIndex: Int,
    selectedDayJd: Long, onDaySelected: (Long) -> Unit,
    repo: LuachRepository, settings: AppSettings,
    bg: Color, surf: Color, gold: Color, txt: Color, sub: Color,
    enqueueCalOp: (CalSyncOp) -> Unit,
    onWeekChange: (Int) -> Unit, onToday: () -> Unit,
    onSettingsChange: (AppSettings) -> Unit,
    onLuachChange: (LuachEntry) -> Unit
) {
    val inIsrael    = settings.inIsrael
    val hebrewYear  = remember(pdfPath) { repo.getActiveLuach()?.hebrewYear ?: 5786 }

    // Without this, leaving the app open (foregrounded or just not killed)
    // across midnight leaves every "today" indicator - the top bar, the
    // today/selected-day highlight, "go to today" - silently pointing at
    // yesterday until the user does something that happens to recompute it.
    // A once-a-minute check is more than precise enough for a calendar.
    var dateRefreshTick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        var lastJd = currentJulianDay()
        while (true) {
            delay(60_000)
            val nowJd = currentJulianDay()
            if (nowJd != lastJd) { lastJd = nowJd; dateRefreshTick++ }
        }
    }

    val weekInfo    = remember(weekIndex, inIsrael, hebrewYear) { repo.getWeekInfoForIndex(weekIndex, inIsrael, hebrewYear) }
    val todayInfo   = remember(inIsrael, hebrewYear, dateRefreshTick) { repo.getTodayDisplayInfo(inIsrael, hebrewYear) }
    val allParashot = remember(inIsrael, hebrewYear) { repo.getAllParashot(inIsrael, hebrewYear) }
    val totalWeeks  = remember(inIsrael, hebrewYear) { repo.getTotalWeeks(inIsrael, hebrewYear) }
    val todayWeek   = remember(inIsrael, hebrewYear, dateRefreshTick) { repo.getCurrentWeekIndex(inIsrael, hebrewYear) }
    val isOnToday   = weekIndex == todayWeek

    var showControls  by remember { mutableStateOf(true) }
    var showPicker    by remember { mutableStateOf(false) }
    var showMenu      by remember { mutableStateOf(false) }
    var showSettings  by remember { mutableStateOf(false) }
    var showManage    by remember { mutableStateOf(false) }
    var showAbout     by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }  // unified Hebrew/Gregorian picker
    var showDiary        by remember { mutableStateOf(false) }
    var showDayMenu      by remember { mutableStateOf(false) }  // quick menu after tapping a day
    // "Classic calendar" full-month-grid view, toggled from the bottom nav.
    var showClassicCalendar by remember { mutableStateOf(false) }
    // Bumped whenever diary events change (diary closed, ICS import) so the
    // calendar's cached per-week event lookup knows it's stale - see
    // PdfPagesView's `remember(weekStartJd, eventsVersion)` below.
    var eventsVersion    by remember { mutableStateOf(0) }

    // The currently "active" day for the top bar and diary.
    // Initialised to the Sunday of the current week; updates on every week change.
    // Sync selectedDayJd to Sunday of new week when navigating
    LaunchedEffect(weekIndex) {
        val sun = weekInfo.weekStartJd
        val sat = weekInfo.weekEndJd
        if (selectedDayJd == 0L || selectedDayJd < sun || selectedDayJd > sat) {
            onDaySelected(sun)
        }
    }

    val renderer = remember(pdfPath) { PdfRenderer(pdfPath) }
    DisposableEffect(pdfPath) { onDispose { renderer.close() } }

    // Global zoom/pan — persists across weeks and restarts
    val (sz, sox, soy) = remember { repo.loadZoom() }
    var zoom    by remember { mutableStateOf(sz) }
    var offsetX by remember { mutableStateOf(sox) }
    var offsetY by remember { mutableStateOf(soy) }

    LaunchedEffect(zoom, offsetX, offsetY) { repo.saveZoom(zoom, offsetX, offsetY) }
    // Bars only toggle on tap — no auto-hide timer

    // ── Back button logic ─────────────────────────────────────────────────
    // Priority: close classic-calendar → close overlay → go to today → exit
    val anyOverlay = showPicker || showMenu || showSettings ||
                     showManage || showAbout || showDatePicker || showDiary || showDayMenu

    AppBackHandler(enabled = true) {
        when {
            showClassicCalendar -> showClassicCalendar = false
            anyOverlay -> {
                showPicker = false; showMenu   = false; showSettings  = false
                showManage = false; showAbout  = false; showDatePicker = false; showDiary = false; showDayMenu = false
            }
            !isOnToday -> onToday()
            else       -> exitApp()
        }
    }

    Box(Modifier.fillMaxSize().background(bg)) {
        // Slide animation — direction computed from initialState/targetState
        // so it's always correct at the moment the transition fires.
        AnimatedContent(
            targetState = weekIndex,
            transitionSpec = {
                val goingForward = targetState > initialState
                if (initialState == targetState) {
                    fadeIn() togetherWith fadeOut()
                } else if (goingForward) {
                    // Hebrew RTL: "next week" = newer = slides in from LEFT (←)
                    (slideInHorizontally { w -> -w } + fadeIn()) togetherWith
                    (slideOutHorizontally { w -> w } + fadeOut())
                } else {
                    // "prev week" = older = slides in from RIGHT (→)
                    (slideInHorizontally { w -> w } + fadeIn()) togetherWith
                    (slideOutHorizontally { w -> -w } + fadeOut())
                }
            },
            label = "weekSlide"
        ) { displayedWeek ->
            val displayedInfo = remember(displayedWeek, inIsrael, hebrewYear) {
                repo.getWeekInfoForIndex(displayedWeek, inIsrael, hebrewYear)
            }
            PdfPagesView(
                renderer = renderer,
                page1    = displayedInfo.pdfPageStart - 1,
                page2    = displayedInfo.pdfPageEnd   - 1,
                zoom = zoom, offsetX = offsetX, offsetY = offsetY,
                bg   = bg, gold = gold, surf = surf, nightMode = settings.darkMode,
                compactView = settings.compactView,
                compactCutPercent = settings.compactCutPercent,
                selectedDayJd = selectedDayJd,
                weekStartJd   = displayedInfo.weekStartJd,
                repo = repo,
                eventsVersion = eventsVersion,
                dateRefreshTick = dateRefreshTick,
                onGestureStart = { showControls = false },
                onToggleControls = { showControls = !showControls },
                onDayTapped    = { jd ->
                    if (jd == selectedDayJd) {
                        // Tapping the already-active day opens its menu
                        showDayMenu = true
                    } else {
                        // Tapping a different day just moves the selection
                        onDaySelected(jd)
                    }
                    showControls = true
                },
                onTransform = { z, ox, oy ->
                    zoom = z.coerceIn(0.4f, 6f); offsetX = ox; offsetY = oy
                },
                controlsVisible = showControls
            )
        }

        // Top bar
        AnimatedVisibility(visible = showControls,
            enter = fadeIn() + slideInVertically(),
            exit  = fadeOut() + slideOutVertically(),
            modifier = Modifier.align(Alignment.TopCenter)) {
            TopBar(
                todayInfo      = todayInfo,
                weekParasha    = weekInfo.parashaName,
                selectedDayJd  = selectedDayJd,
                repo           = repo,
                surf = surf, gold = gold, txt = txt, sub = sub,
                onPicker = { showPicker = true },
                onMenu   = { showMenu   = true },
                onDiaryOpen = { showDiary = true }
            )
        }

        // Bottom nav
        AnimatedVisibility(visible = showControls,
            enter = fadeIn() + slideInVertically { it },
            exit  = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter)) {
            BottomNav(
                onPrev  = { if (weekIndex > 0)             onWeekChange(weekIndex - 1) },
                onToday = onToday,
                onNext  = { if (weekIndex < totalWeeks - 1) onWeekChange(weekIndex + 1) },
                canPrev = weekIndex > 0,
                canNext = weekIndex < totalWeeks - 1,
                isOnToday = isOnToday,
                surf = surf, gold = gold
            )
        }

        // Overlays
        if (showPicker) ParashaPicker(
            parashot = allParashot, currentIndex = weekIndex,
            surf = surf, gold = gold, txt = txt, sub = sub,
            onSelected = { i -> onWeekChange(i); showPicker = false },
            onDismiss  = { showPicker = false },
            onOpenDayPicker = { showPicker = false; showDatePicker = true }
        )

        if (showDatePicker) {
            val initJd = if (selectedDayJd > 1000000L) selectedDayJd else currentJulianDay()
            UnifiedDatePicker(
                initialJd = initJd,
                todayJd   = currentJulianDay(),
                repo = repo,
                gold = gold, surf = surf, txt = txt, sub = sub,
                onPicked = { pickedJd ->
                    onDaySelected(pickedJd)
                    val (gy, gm, gd) = repo.jdToGregorian(pickedJd)
                    val idx = repo.findWeekIndexForDate(gy, gm, gd, inIsrael, hebrewYear)
                    onWeekChange(idx)
                    showDatePicker = false
                },
                onDismiss = { showDatePicker = false }
            )
        }

        if (showDiary) {
            DiaryScreen(
                repo = repo, jd = selectedDayJd,
                surf = surf, gold = gold, txt = txt, sub = sub,
                enqueueCalOp = enqueueCalOp,
                onJumpToDay = { jumpJd ->
                    onDaySelected(jumpJd)
                    val (jgy, jgm, jgd) = repo.jdToGregorian(jumpJd)
                    val idx = repo.findWeekIndexForDate(jgy, jgm, jgd, inIsrael, hebrewYear)
                    onWeekChange(idx)
                },
                onDismiss = { showDiary = false; eventsVersion++ }
            )
        }

        if (showDayMenu) {
            DayContextMenu(
                jd = selectedDayJd, repo = repo,
                surf = surf, gold = gold, txt = txt, sub = sub,
                onAddEvent  = { showDayMenu = false; showDiary = true },
                onOpenEvent = { showDayMenu = false; showDiary = true },
                onDismiss   = { showDayMenu = false }
            )
        }

        if (showMenu) MenuSheet(
            surf = surf, gold = gold, txt = txt, isDark = settings.darkMode,
            onSettings = { showMenu = false; showSettings = true },
            onManage   = { showMenu = false; showManage   = true },
            onNight    = { showMenu = false
                           onSettingsChange(settings.copy(darkMode = !settings.darkMode)) },
            onAbout    = { showMenu = false; showAbout    = true },
            onDismiss  = { showMenu = false }
        )

        if (showSettings) SettingsScreen(settings, repo, surf, gold, txt, sub,
            onSave    = { s -> onSettingsChange(s); showSettings = false },
            onDismiss = { showSettings = false },
            onEventsChanged = { eventsVersion++ })

        if (showManage) ManageLuachScreen(repo, surf, gold, txt, sub,
            onActivate = { entry -> onLuachChange(entry); showManage = false },
            onDismiss  = { showManage = false })

        if (showAbout) AboutDialog(surf, gold, txt, sub, onDismiss = { showAbout = false })

        // "Classic calendar" — full-month grid view (Hebrew/Gregorian toggle),
        // opened from the 4th bottom-nav button. Drawn last so it sits above
        // everything else, matching how the other full-screen overlays behave.
        if (showClassicCalendar) {
            ClassicCalendarScreen(
                repo = repo, initialJd = selectedDayJd, inIsrael = inIsrael,
                eventsVersion = eventsVersion,
                bg = bg, surf = surf, gold = gold, txt = txt, sub = sub,
                onDaySelected = { jd ->
                    onDaySelected(jd)
                    val (jgy, jgm, jgd) = repo.jdToGregorian(jd)
                    onWeekChange(repo.findWeekIndexForDate(jgy, jgm, jgd, inIsrael, hebrewYear))
                },
                onClose = { showClassicCalendar = false }
            )
        }

        // Standalone "classic calendar" toggle — deliberately NOT one of the
        // connected pill buttons in BottomNav anymore, and NOT gated by
        // showControls or drawn underneath the classic-calendar overlay: it
        // is the very last thing composed in this Box, so it always sits on
        // top and stays reachable even while the classic calendar itself is
        // open — tapping it there closes it and returns to the PDF view.
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(WindowInsets.navigationBars.asPaddingValues())
                .padding(start = 20.dp, bottom = 20.dp)
                .size(48.dp)
                .shadow(6.dp, CircleShape)
                .clip(CircleShape)
                .background(surf.copy(alpha = 0.94f))
                .border(1.dp, gold.copy(alpha = 0.18f), CircleShape)
                .clickable { showClassicCalendar = !showClassicCalendar }
                .semantics { contentDescription = if (showClassicCalendar) "חזרה ללוח" else "לוח רגיל" },
            contentAlignment = Alignment.Center
        ) {
            IconGrid(gold, 20.dp)
        }
    }
}

// ── PDF Pages View ────────────────────────────────────────────────────────
@Composable
fun PdfPagesView(
    renderer: PdfRenderer, page1: Int, page2: Int,
    zoom: Float, offsetX: Float, offsetY: Float,
    bg: Color, gold: Color, surf: Color, nightMode: Boolean,
    compactView: Boolean, compactCutPercent: Float,
    selectedDayJd: Long,
    weekStartJd: Long,                          // JD of Sunday of current week
    repo: LuachRepository,
    eventsVersion: Int = 0,                     // bump to invalidate the cached event lookup below
    dateRefreshTick: Int = 0,                   // bump (value unused) to force a recompose when the date rolls over
    onTransform: (Float, Float, Float) -> Unit,
    onGestureStart: () -> Unit = {},
    onToggleControls: () -> Unit = {},          // tap outside any day frame
    onDayTapped: (Long) -> Unit = {},            // called when user taps a day strip
    controlsVisible: Boolean = true             // whether the bottom nav bar is currently showing
) {
    var cZ  by remember { mutableStateOf(zoom) }
    var cOx by remember { mutableStateOf(offsetX) }
    var cOy by remember { mutableStateOf(offsetY) }
    LaunchedEffect(zoom, offsetX, offsetY) { cZ = zoom; cOx = offsetX; cOy = offsetY }

    val currentOnDayTapped      by rememberUpdatedState(onDayTapped)
    val currentOnToggleControls by rememberUpdatedState(onToggleControls)
    val currentOnGestureStart   by rememberUpdatedState(onGestureStart)

    // Day row bounds within page 2, as fractions of the page's total height.
    // The very first row (0.0000–0.2027) is the luach's own title/header bar
    // printed on the PDF - it is NOT a day and must not get a tap frame.
    // Sunday is the row right after it; Friday and Saturday used to share one
    // oversized trailing row - that's split below into a normal-sized Friday
    // row plus Saturday absorbing whatever remains to the bottom of the page.
    val dayRowBounds = listOf(
        0.2027f to 0.2975f,   // ראשון  (Sun)
        0.2975f to 0.3922f,   // שני    (Mon)
        0.3922f to 0.4865f,   // שלישי  (Tue)
        0.4865f to 0.5812f,   // רביעי  (Wed)
        0.5812f to 0.6759f,   // חמישי  (Thu)
        0.6759f to 0.7706f,   // שישי   (Fri) — same height as the other days
        0.7706f to 1.0000f    // שבת    (Sat) — remaining space to page bottom
    )

    // ── Single source of truth for the on-screen layout ─────────────────────
    // Both hit-testing (tap detection, below) and drawing (Canvas, further
    // down) MUST agree on where page 2 sits and how tall it is. Previously
    // each recomputed the same formula independently — any tiny divergence
    // between those two copies silently shifted every tap away from what
    // was actually drawn on screen (worse in compact-view mode, where the
    // aspect-ratio math has an extra step that's easy for two copies of the
    // same formula to disagree on). Now there is exactly one calculation,
    // published here each recomposition and *read* — never recomputed — by
    // the tap handler, so the two can never drift apart again.
    data class PageLayout(val rw: Int, val rh1: Int, val rh2: Int, val fx: Float, val y2: Float)
    var layout by remember { mutableStateOf(PageLayout(0, 0, 0, 0f, 0f)) }
    val currentLayout by rememberUpdatedState(layout)

    BoxWithConstraints(Modifier.fillMaxSize().background(bg)) {
        val cw = constraints.maxWidth.toFloat()
        val ch = constraints.maxHeight.toFloat()

        val (pw1, ph1) = remember(renderer, page1) { renderer.getPageSize(page1) }
        val (pw2, ph2) = remember(renderer, page2) { renderer.getPageSize(page2) }

        val cut     = compactCutPercent.coerceIn(0f, 0.9f)
        val asp1    = if (pw1 > 0f) ph1 / pw1 else 1.414f
        val asp2raw = if (pw2 > 0f) ph2 / pw2 else 1.414f
        val asp2    = if (compactView) asp2raw / (1f - cut) else asp2raw

        val gap = 16f

        // baseX/baseY re-center the page whenever it's smaller than the
        // viewport, and both depend on the CURRENT zoom (rw = cw*z, and the
        // page's rendered height with it). Any zoom-around-a-point gesture
        // (pinch, double-tap, the +/- buttons) needs to know baseX/baseY at
        // BOTH the old and the new zoom level to correctly re-anchor on the
        // touch/click point - using only cOx/cOy (as an earlier version of
        // this code did) silently ignores how much baseX/baseY themselves
        // shift between those two zoom levels, so the zoom center drifted
        // away from the actual pinch midpoint / double-tap point / button
        // click more and more as it moved away from baseX/baseY = 0.
        fun baseXFor(z: Float): Float = (cw - cw * z) / 2f
        fun baseYFor(z: Float): Float {
            val rwFor  = cw * z
            val hFor   = rwFor * asp1 + rwFor * asp2 + gap
            return if (hFor < ch) (ch - hFor) / 2f else (ch - hFor)
        }

        // ── Minimum zoom: the calendar may never render smaller than the
        // screen ─────────────────────────────────────────────────────────
        // rw (rendered width) is always exactly cw*z, so z=1 is precisely
        // the point below which the page would be narrower than the
        // viewport (blank margins left/right). Separately, the STACKED
        // page height (rw*asp1 + rw*asp2 + gap) also scales with z - on an
        // unusually tall/narrow viewport that height can dip below the
        // viewport's own height (ch) even at z=1, leaving blank space
        // above/below instead. Which of the two ever actually binds
        // depends on the device's screen shape (portrait vs landscape,
        // narrow phone vs tablet) - so the true floor is whichever of the
        // two needs MORE zoom. This never limits zooming IN, and panning
        // is never clamped, so the user can still scroll to reach every
        // edge of the document.
        // Wrapped in remember(cw, ch, asp1, asp2) — NOT recomputed on every
        // pinch/pan frame (those only change cZ/cOx/cOy, not these four),
        // so this adds zero per-frame cost during an active gesture.
        val minZoom = remember(cw, ch, asp1, asp2) {
            val heightMinZoom = if (cw > 0f) (ch - gap) / (cw * (asp1 + asp2)) else 1f
            if (heightMinZoom > 1f) heightMinZoom else 1f
        }

        // If the live zoom is currently below that floor - e.g. a zoom
        // restored from a previous, larger window/screen, or a live
        // resize/rotation that just raised the floor - snap it up right
        // now, anchored on the viewport center (same math as the +/-
        // buttons) so the page doesn't jump, rather than momentarily
        // showing it smaller than the screen.
        if (cZ < minZoom) {
            val centroid = Offset(cw / 2f, ch / 2f)
            val ratio    = minZoom / cZ
            val newOx = centroid.x - baseXFor(minZoom) - ratio * (centroid.x - baseXFor(cZ) - cOx)
            val newOy = centroid.y - baseYFor(minZoom) - ratio * (centroid.y - baseYFor(cZ) - cOy)
            cZ = minZoom; cOx = newOx; cOy = newOy
            onTransform(cZ, cOx, cOy)
        }

        val rw  = (cw * cZ).toInt().coerceAtLeast(100)
        val rh1 = (rw * asp1).toInt()
        val rh2 = (rw * asp2).toInt()

        // Render at a resolution based on the viewport width alone - NOT on
        // the live zoom level. drawImage() below already scales a bitmap to
        // whatever dstSize the current zoom calls for "for free" (cheap GPU
        // scaling), so tying renderW to rw (which changes on every frame of
        // an active pinch/pan gesture) meant re-rasterizing the whole PDF
        // page from scratch on nearly every frame - a genuinely expensive,
        // blocking call - which is what made zooming and panning feel slow.
        // 1.6x gives some headroom for a modest zoom-in to still look crisp
        // without paying that cost on every gesture frame; only re-renders
        // when the viewport itself actually changes (rotation, window resize).
        val renderW = (cw * 1.6f).toInt().coerceIn(400, 1600)
        val bmp1 = remember(renderer, page1, renderW) {
            renderer.renderPage(page1, renderW, (renderW * asp1).toInt())
        }
        val fullW2 = if (compactView) (renderW / (1f - cut)).toInt() else renderW
        val bmp2 = remember(renderer, page2, fullW2) {
            renderer.renderPage(page2, fullW2, (fullW2 * asp2raw).toInt())
        }

        // The PDF pages themselves are the priority - the day borders and the
        // diary-event overlay are pure extras on top of them, and on a slow
        // device even their modest cost (a week's worth of event storage
        // reads, plus the border draw calls) can visibly delay the first
        // frame that actually shows the calendar. Gating them behind one
        // extra frame means the PDF always paints first, then the extras
        // fill in right after - instead of everything blocking together.
        var auxReady by remember(page1, page2) { mutableStateOf(false) }
        LaunchedEffect(bmp1, bmp2) {
            auxReady = false
            if (bmp1 != null && bmp2 != null) {
                withFrameNanos {}   // let the PDF-only frame actually get presented first
                auxReady = true
            }
        }

        val totalH = rh1 + rh2 + gap
        val baseX  = (cw - rw) / 2f
        // When the pages are taller than the viewport (the normal case),
        // anchor to the BOTTOM instead of the top - page 2 (the actual daily
        // grid) is what matters here, not page 1, so the default/reset view
        // should start there rather than scrolled up to the top of page 1.
        val baseY  = if (totalH < ch) (ch - totalH) / 2f else (ch - totalH)

        val pdfFilter = if (nightMode) nightColorFilter else null
        val phColor   = if (nightMode) Color(0xFF1A1A1A) else Color(0xFFD8D0C0)

        // Which day strip is selected? (0=Sun..6=Sat within current week)
        val selectedStrip = (selectedDayJd - weekStartJd).toInt().coerceIn(-1, 6)
        // Which day is today within this week?
        val todayJd   = currentJulianDay()
        val todayStrip = (todayJd - weekStartJd).toInt().coerceIn(-1, 6)

        val fx = baseX + cOx
        val fy = baseY + cOy
        val y2 = fy + rh1 + gap

        // Publish this recomposition's layout for the tap handler to read.
        layout = PageLayout(rw, rh1, rh2, fx, y2)

        // Cached per (week, eventsVersion) - NOT recomputed on every
        // recomposition. Without this, panning/zooming (which changes
        // cOx/cOy every frame) would re-read storage for all 7 days on every
        // single frame of the gesture; now it only re-reads when the visible
        // week actually changes, or when the diary/ICS-import bumps eventsVersion.
        val weekEvents = if (weekStartJd > 1000000L) {
            remember(weekStartJd, eventsVersion) { (0..6).map { repo.loadEventsForDay(weekStartJd + it) } }
        } else emptyList()
        val textMeasurer = rememberTextMeasurer()

        Canvas(Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, gestureZoom, _ ->
                    onGestureStart()
                    val newZ = (cZ * gestureZoom).coerceIn(minZoom, 6f)
                    val ratio = newZ / cZ
                    cOx = centroid.x - baseXFor(newZ) - ratio * (centroid.x - baseXFor(cZ) - cOx) + pan.x
                    cOy = centroid.y - baseYFor(newZ) - ratio * (centroid.y - baseYFor(cZ) - cOy) + pan.y
                    cZ  = newZ
                    onTransform(cZ, cOx, cOy)
                }
            }
            .pointerInput(weekStartJd) {
                detectTapGestures(
                    onTap = { tapOffset ->
                        // Read the SAME layout the drawing code just published — no
                        // independent recalculation, so this can never disagree with
                        // what's actually on screen (including compact-view mode).
                        val L = currentLayout
                        var hitDay = false
                        if (L.rh2 > 0 &&
                            tapOffset.x >= L.fx && tapOffset.x <= L.fx + L.rw &&
                            tapOffset.y >= L.y2 && tapOffset.y <= L.y2 + L.rh2) {
                            val relY = (tapOffset.y - L.y2) / L.rh2   // 0..1 within page 2
                            val tappedDay = dayRowBounds.indexOfFirst { (top, bot) ->
                                relY >= top && relY < bot
                            }
                            if (tappedDay >= 0) {
                                currentOnDayTapped(weekStartJd + tappedDay)
                                hitDay = true
                            }
                        }
                        // Tapped empty canvas / the title row / outside a day frame:
                        // show or hide the toolbars instead of doing nothing.
                        if (!hitDay) currentOnToggleControls()
                    },
                    onDoubleTap = { tapOffset ->
                        currentOnGestureStart()
                        if (cZ >= 2f) {
                            // Already zoomed in — reset to fit (the computed
                            // floor, not a hardcoded 1f, so this never lands
                            // below the "can't be smaller than the screen" limit)
                            cZ = minZoom; cOx = 0f; cOy = 0f
                        } else {
                            // Zoom into the exact point that was double-tapped
                            val newZoom = 2.5f
                            val ratio   = newZoom / cZ
                            cOx = tapOffset.x - baseXFor(newZoom) - ratio * (tapOffset.x - baseXFor(cZ) - cOx)
                            cOy = tapOffset.y - baseYFor(newZoom) - ratio * (tapOffset.y - baseYFor(cZ) - cOy)
                            cZ  = newZoom
                        }
                        onTransform(cZ, cOx, cOy)
                    }
                )
            }
        ) {
            fun placeholder(x: Float, y: Float, w: Int, h: Int) =
                drawRect(phColor, Offset(x, y), Size(w.toFloat(), h.toFloat()))

            // ── Page 1 ────────────────────────────────────────────────────
            if (bmp1 != null)
                drawImage(bmp1,
                    dstOffset     = IntOffset(fx.toInt(), fy.toInt()),
                    dstSize       = IntSize(rw, rh1),
                    colorFilter   = pdfFilter,
                    filterQuality = FilterQuality.High)
            else placeholder(fx, fy, rw, rh1)

            // ── Page 2 ────────────────────────────────────────────────────
            if (bmp2 != null) {
                if (!compactView) {
                    drawImage(bmp2,
                        dstOffset     = IntOffset(fx.toInt(), y2.toInt()),
                        dstSize       = IntSize(rw, rh2),
                        colorFilter   = pdfFilter,
                        filterQuality = FilterQuality.High)
                } else {
                    val srcW          = bmp2.width
                    val srcH          = bmp2.height
                    val stripFracEach = (1f - cut) / 2f
                    val stripSrcW     = (srcW * stripFracEach).toInt().coerceAtLeast(1)
                    val dstStripW     = (rw / 2f).toInt()
                    drawImage(image = bmp2,
                        srcOffset = IntOffset(0, 0), srcSize = IntSize(stripSrcW, srcH),
                        dstOffset = IntOffset(fx.toInt(), y2.toInt()),
                        dstSize   = IntSize(dstStripW, rh2),
                        colorFilter = pdfFilter, filterQuality = FilterQuality.High)
                    drawImage(
                        image = bmp2,
                        srcOffset = IntOffset(srcW - stripSrcW, 0),
                        srcSize   = IntSize(stripSrcW, srcH),
                        dstOffset = IntOffset(fx.toInt() + dstStripW, y2.toInt()),
                        dstSize   = IntSize(rw - dstStripW, rh2),
                        colorFilter = pdfFilter,
                        filterQuality = FilterQuality.High
                    )
                }
            } else placeholder(fx, y2, rw, rh2)

            // ── Day strip overlays on page 2 — exact PDF row positions ──────
            // Today-tint, selected-day border, AND the diary-event chip are
            // all drawn together in this one pass, using the exact same
            // fx/y2/rw/rh2 this same frame already used to place the PDF
            // pages themselves above. Earlier attempts drew the event chip as
            // a separately-laid-out Composable positioned to match this Canvas
            // via manual pixel math - that fought Compose's own layout system
            // (density scaling, RTL-mirrored offsets, constraint clamping at
            // high zoom) every step of the way and never fully stopped
            // drifting during an active zoom gesture. Drawing everything in
            // one DrawScope removes the second coordinate system entirely:
            // there is nothing left for it to disagree with.
            // Deferred behind auxReady - see the comment where that's set above.
            if (auxReady) {
                val accentCol = Color(0xFFC98A12)
                val strokeW   = (rw * 0.0026f).coerceAtLeast(1.5f)
                val chipBg  = if (nightMode) Color(0xFF0D1B2A).copy(alpha = 0.62f)
                              else Color(0xFFFFFFFF).copy(alpha = 0.72f)
                val chipTxt = if (nightMode) Color(0xFFD8E6FF) else Color(0xFF1A2744)
                val chipPad = 5.dp.toPx()

                dayRowBounds.forEachIndexed { dayIdx, (topFrac, botFrac) ->
                    val ty = y2 + rh2 * topFrac
                    val bh = rh2 * (botFrac - topFrac)

                    // Today: subtle tint
                    if (dayIdx == todayStrip) {
                        drawRect(
                            color   = Color(0x22FFFFFF),
                            topLeft = Offset(fx, ty),
                            size    = Size(rw.toFloat(), bh)
                        )
                    }

                    // Selected day: border
                    if (dayIdx == selectedStrip) {
                        drawRect(
                            color   = accentCol.copy(alpha = 0.9f),
                            topLeft = Offset(fx + strokeW / 2f, ty + strokeW / 2f),
                            size    = Size(rw - strokeW, bh - strokeW),
                            style   = Stroke(width = strokeW)
                        )
                    }

                    // Diary events for this day, centered in this exact row rect
                    val evts = weekEvents.getOrNull(dayIdx) ?: emptyList()
                    if (evts.isNotEmpty()) {
                        val text = evts.take(2).joinToString("\n") { "• ${it.title}" } +
                            if (evts.size > 2) "\n+${evts.size - 2}" else ""
                        val measured = textMeasurer.measure(
                            text = text,
                            style = TextStyle(
                                fontSize = (bh * 0.16f).coerceIn(8f, 12f).sp,
                                color = chipTxt,
                                fontWeight = FontWeight.Medium
                            ),
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 3,
                            constraints = Constraints(maxWidth = (rw * 0.62f).toInt().coerceAtLeast(1))
                        )
                        val chipW = measured.size.width + chipPad * 2
                        val chipH = measured.size.height + chipPad * 2
                        val chipX = fx + (rw - chipW) / 2f
                        val chipY = ty + (bh - chipH) / 2f
                        drawRoundRect(
                            color = chipBg,
                            topLeft = Offset(chipX, chipY),
                            size = Size(chipW, chipH),
                            cornerRadius = CornerRadius(6.dp.toPx())
                        )
                        drawText(measured, topLeft = Offset(chipX + chipPad, chipY + chipPad))
                    }
                }
            }
        }

        // ── Desktop-only zoom controls ───────────────────────────────────
        // Touch users already have pinch-to-zoom and double-tap; mouse users
        // on Windows have no equivalent gesture at all without these.
        if (isDesktopPlatform()) {
            fun zoomBy(factor: Float) {
                val centroid = Offset(cw / 2f, ch / 2f)
                val newZ  = (cZ * factor).coerceIn(minZoom, 6f)
                val ratio = newZ / cZ
                cOx = centroid.x - baseXFor(newZ) - ratio * (centroid.x - baseXFor(cZ) - cOx)
                cOy = centroid.y - baseYFor(newZ) - ratio * (centroid.y - baseYFor(cZ) - cOy)
                cZ  = newZ
                onTransform(cZ, cOx, cOy)
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = if (controlsVisible) 84.dp else 20.dp)
                    .width(44.dp)
                    .shadow(6.dp, RoundedCornerShape(16.dp))
                    .clip(RoundedCornerShape(16.dp))
                    .background(surf.copy(alpha = 0.94f))
                    .border(1.dp, gold.copy(alpha = 0.18f), RoundedCornerShape(16.dp))
            ) {
                ZoomButton(label = "+", onClick = { zoomBy(1.25f) }, gold = gold)
                Box(Modifier.width(44.dp).height(1.dp).background(gold.copy(alpha = 0.15f)))
                ZoomButton(label = "−", onClick = { zoomBy(0.8f) }, gold = gold)
            }
        }
    }
}

/** One button in the floating desktop zoom control - a plain Surface (not
 *  IconButton) so pressing anywhere in its 44dp square shows feedback, not
 *  just a small inscribed ripple circle that doesn't match the button's
 *  own visible bounds. */
@Composable
private fun ZoomButton(label: String, onClick: () -> Unit, gold: Color) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        modifier = Modifier.size(44.dp).semantics {
            contentDescription = if (label == "+") "הגדלה" else "הקטנה"
        }
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, color = gold))
        }
    }
}

// ── Icon drawing helpers ───────────────────────────────────────────────────

/** Calendar icon — modern style with ring-binders and grid */
@Composable
fun IconCalendar(color: Color, size: Dp = 22.dp) {
    Canvas(Modifier.size(size)) {
        val s     = this.size.width
        val st    = s * 0.09f
        val r     = s * 0.14f
        val cap   = StrokeCap.Round

        // Body with rounded corners
        drawRoundRect(color, Offset(s*.06f, s*.18f), Size(s*.88f, s*.76f),
            CornerRadius(r), style = Stroke(st))

        // Header bar fill
        drawRoundRect(color.copy(alpha = .15f), Offset(s*.06f, s*.18f),
            Size(s*.88f, s*.28f), CornerRadius(r))

        // Ring binders (two rounded caps on top)
        drawLine(color, Offset(s*.3f, s*.06f), Offset(s*.3f, s*.3f), st * 1.3f, cap = cap)
        drawLine(color, Offset(s*.7f, s*.06f), Offset(s*.7f, s*.3f), st * 1.3f, cap = cap)

        // Header divider
        drawLine(color, Offset(s*.06f, s*.46f), Offset(s*.94f, s*.46f), st * .7f)

        // 3×2 dot grid in body
        val dotR = st * .75f
        listOf(.28f to .62f, .5f to .62f, .72f to .62f,
               .28f to .80f, .5f to .80f, .72f to .80f).forEach { (x, y) ->
            drawCircle(color, dotR, Offset(s * x, s * y))
        }
    }
}

/** Hamburger menu icon */
@Composable
fun IconMenu(color: Color, size: Dp = 22.dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.width
        val stroke = s * 0.1f
        val cap = StrokeCap.Round
        listOf(.25f, .5f, .75f).forEach { y ->
            drawLine(color, Offset(s*.15f, s*y), Offset(s*.85f, s*y), stroke, cap = cap)
        }
    }
}

/** Chevron right (→ in LTR, previous week in RTL) */
@Composable
fun IconChevronRight(color: Color, size: Dp = 22.dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.width
        val stroke = s * 0.12f
        val path = Path().apply {
            moveTo(s*.35f, s*.25f)
            lineTo(s*.65f, s*.5f)
            lineTo(s*.35f, s*.75f)
        }
        drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Chevron left (← in LTR, next week in RTL) */
@Composable
fun IconChevronLeft(color: Color, size: Dp = 22.dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.width
        val stroke = s * 0.12f
        val path = Path().apply {
            moveTo(s*.65f, s*.25f)
            lineTo(s*.35f, s*.5f)
            lineTo(s*.65f, s*.75f)
        }
        drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Home / today icon */
@Composable
fun IconHome(color: Color, size: Dp = 20.dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.width
        val stroke = s * 0.1f
        // roof
        val roof = Path().apply {
            moveTo(s*.1f, s*.55f)
            lineTo(s*.5f, s*.15f)
            lineTo(s*.9f, s*.55f)
        }
        drawPath(roof, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        // walls
        drawRoundRect(color, Offset(s*.22f, s*.52f), Size(s*.56f, s*.38f),
            CornerRadius(s*.05f), style = Stroke(stroke))
        // door
        drawRect(color, Offset(s*.38f, s*.68f), Size(s*.24f, s*.22f))
    }
}

/** Settings gear icon */
@Composable
fun IconSettings(color: Color, size: Dp = 20.dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.width
        val stroke = s * 0.1f
        drawCircle(color, s*.18f, Offset(s*.5f, s*.5f), style = Stroke(stroke))
        val outerR = s*.38f
        for (i in 0 until 8) {
            val angle = Math.toRadians(i * 45.0)
            val x1 = s*.5f + (outerR - s*.1f) * Math.cos(angle).toFloat()
            val y1 = s*.5f + (outerR - s*.1f) * Math.sin(angle).toFloat()
            val x2 = s*.5f + outerR * Math.cos(angle).toFloat()
            val y2 = s*.5f + outerR * Math.sin(angle).toFloat()
            drawLine(color, Offset(x1, y1), Offset(x2, y2), stroke * 1.5f, cap = StrokeCap.Round)
        }
    }
}

/** Books / luach library icon */
@Composable
fun IconLibrary(color: Color, size: Dp = 20.dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.width
        val stroke = s * 0.09f
        val cap = StrokeCap.Round
        // 3 book spines
        listOf(.15f to .28f, .38f to .24f, .62f to .26f).forEachIndexed { idx, (x, w) ->
            drawRoundRect(color, Offset(s*x, s*.15f), Size(s*w, s*.7f),
                CornerRadius(s*.04f), style = Stroke(stroke))
        }
        // shelf line
        drawLine(color, Offset(s*.08f, s*.85f), Offset(s*.92f, s*.85f), stroke * 1.2f, cap = cap)
    }
}

/** Moon icon for night mode */
@Composable
fun IconMoon(color: Color, size: Dp = 20.dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.width
        // crescent moon via arc stroke
        drawArc(color, -110f, 220f, false,
            Offset(s*.08f, s*.08f), Size(s*.84f, s*.84f),
            style = Stroke(s*.12f, cap = StrokeCap.Round))
    }
}

/** Sun icon for day mode */
@Composable
fun IconSun(color: Color, size: Dp = 20.dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.width
        val stroke = s * 0.1f
        drawCircle(color, s*.2f, Offset(s*.5f, s*.5f), style = Stroke(stroke))
        for (i in 0 until 8) {
            val angle = Math.toRadians(i * 45.0)
            val x1 = s*.5f + s*.27f * Math.cos(angle).toFloat()
            val y1 = s*.5f + s*.27f * Math.sin(angle).toFloat()
            val x2 = s*.5f + s*.42f * Math.cos(angle).toFloat()
            val y2 = s*.5f + s*.42f * Math.sin(angle).toFloat()
            drawLine(color, Offset(x1,y1), Offset(x2,y2), stroke, cap = StrokeCap.Round)
        }
    }
}

/** Info icon */
@Composable
fun IconInfo(color: Color, size: Dp = 20.dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.width
        val stroke = s * 0.1f
        drawCircle(color, s*.42f, Offset(s*.5f, s*.5f), style = Stroke(stroke))
        drawCircle(color, stroke*.6f, Offset(s*.5f, s*.33f))
        drawLine(color, Offset(s*.5f, s*.45f), Offset(s*.5f, s*.7f),
            stroke * 1.1f, cap = StrokeCap.Round)
    }
}

/** Pencil/edit icon */
@Composable
fun IconEdit(color: Color, size: Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        val s  = this.size.width
        val st = s * 0.1f
        val cap = StrokeCap.Round
        val join = StrokeJoin.Round
        // pencil body diagonal
        val path = Path().apply {
            moveTo(s*.2f, s*.75f)
            lineTo(s*.55f, s*.2f)
            lineTo(s*.78f, s*.42f)
            lineTo(s*.42f, s*.88f)
            close()
        }
        drawPath(path, color, style = Stroke(st, cap = cap, join = join))
        // tip
        drawLine(color, Offset(s*.2f, s*.75f), Offset(s*.14f, s*.88f), st, cap = cap)
        drawLine(color, Offset(s*.42f, s*.88f), Offset(s*.14f, s*.88f), st, cap = cap)
        // eraser end
        drawLine(color, Offset(s*.55f, s*.2f), Offset(s*.66f, s*.1f), st * 1.2f, cap = cap)
    }
}

/** Trash/delete icon */
@Composable
fun IconTrash(color: Color, size: Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.width
        val st = s * 0.11f
        val cap = StrokeCap.Round
        // lid
        drawLine(color, Offset(s*.18f, s*.28f), Offset(s*.82f, s*.28f), st, cap = cap)
        drawLine(color, Offset(s*.38f, s*.16f), Offset(s*.62f, s*.16f), st * .9f, cap = cap)
        // body
        drawRoundRect(color, Offset(s*.22f, s*.32f), Size(s*.56f, s*.56f),
            CornerRadius(s*.08f), style = Stroke(st))
        // lines inside
        drawLine(color, Offset(s*.38f, s*.44f), Offset(s*.38f, s*.74f), st * .8f, cap = cap)
        drawLine(color, Offset(s*.62f, s*.44f), Offset(s*.62f, s*.74f), st * .8f, cap = cap)
    }
}

/** Plus / add icon */
@Composable
fun IconPlus(color: Color, size: Dp = 20.dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.width
        val st = s * 0.11f
        val cap = StrokeCap.Round
        drawLine(color, Offset(s*.5f, s*.2f), Offset(s*.5f, s*.8f), st, cap = cap)
        drawLine(color, Offset(s*.2f, s*.5f), Offset(s*.8f, s*.5f), st, cap = cap)
    }
}

/** Grid / classic-calendar icon - a 2×2 rounded-square grid, used for the
 *  "לוח רגיל" (classic month-grid view) bottom-nav button. Deliberately a
 *  different shape from IconCalendar (which already denotes the diary
 *  button) so the two never get confused for the same action. */
@Composable
fun IconGrid(color: Color, size: Dp = 20.dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.width
        val st = s * 0.09f
        val gap = s * 0.14f
        val cell = (s - gap) / 2f - st
        for (r in 0..1) for (c in 0..1) {
            val x = c * (cell + gap + st)
            val y = r * (cell + gap + st)
            drawRoundRect(color, Offset(x, y), Size(cell, cell), CornerRadius(s * 0.06f), style = Stroke(st))
        }
    }
}

// ── Top Bar ───────────────────────────────────────────────────────────────
@Composable
fun TopBar(
    todayInfo: HebrewDateDisplay, weekParasha: String,
    selectedDayJd: Long, repo: LuachRepository,
    surf: Color, gold: Color, txt: Color, sub: Color,
    onPicker: () -> Unit, onMenu: () -> Unit,
    onDiaryOpen: () -> Unit
) {
    val statusBarPad = WindowInsets.statusBars.asPaddingValues()
    Column(
        Modifier.fillMaxWidth()
            .background(surf)
            .padding(statusBarPad)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ── Left-most: diary button (RTL layout puts it visually first) ──
            // Surface(onClick=...), not IconButton - IconButton's ripple is a
            // plain circle that doesn't match this rounded-square background,
            // so pressing near a corner looked like nothing was happening
            // there. Surface clips its own click ripple to the same shape as
            // what's actually drawn, so the whole visible button now reacts.
            Surface(
                onClick = onDiaryOpen,
                shape = RoundedCornerShape(11.dp),
                color = gold.copy(.10f),
                modifier = Modifier.size(42.dp).semantics { contentDescription = "פתיחת היומן" }
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    IconCalendar(gold, 19.dp)
                }
            }

            Spacer(Modifier.width(10.dp))

            // ── Centre: selected day card - opens the date picker ──────────
            val safeJd  = if (selectedDayJd < 1000000L) currentJulianDay() else selectedDayJd
            val hd      = jdToHebrew(safeJd)
            val dowIdx  = ((selectedDayJd + 1) % 7).toInt()
            val dowName = dayOfWeekName(dowIdx + 1)
            val inIsrael = remember { repo.loadSettings().inIsrael }
            val selectedHoliday = remember(safeJd) { holidayName(safeJd, inIsrael) }

            Surface(
                onClick = onPicker,
                shape = RoundedCornerShape(14.dp),
                color = gold.copy(.08f),
                modifier = Modifier.weight(1.6f)
            ) {
                Column(
                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("יום $dowName",
                        style = TextStyle(fontSize = 9.5.sp, color = sub), maxLines = 1)
                    Text(formatHebrewDate(hd),
                        style = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.Bold,
                            color = gold), maxLines = 1)
                    if (selectedHoliday.isNotEmpty())
                        Text(selectedHoliday,
                            style = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Bold,
                                color = gold), maxLines = 1)
                    else if (weekParasha.isNotEmpty())
                        Text("פרשת $weekParasha",
                            style = TextStyle(fontSize = 9.sp, color = gold.copy(.65f)),
                            maxLines = 1)
                }
            }

            Spacer(Modifier.width(10.dp))

            // ── Right: today's real date (compact) ────────────────────────
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.weight(1f)) {
                Text(todayInfo.dayOfWeekName,
                    style = TextStyle(fontSize = 9.sp, color = sub, textAlign = TextAlign.End),
                    maxLines = 1)
                Text(todayInfo.hebrewDateString,
                    style = TextStyle(fontSize = 9.5.sp, color = sub, textAlign = TextAlign.End),
                    maxLines = 1)
                if (todayInfo.holidayName.isNotEmpty())
                    Text(todayInfo.holidayName,
                        style = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Bold,
                            color = gold.copy(.8f), textAlign = TextAlign.End), maxLines = 1)
                else if (todayInfo.parashaName.isNotEmpty())
                    Text(todayInfo.parashaName,
                        style = TextStyle(fontSize = 9.sp, color = gold.copy(.55f),
                            textAlign = TextAlign.End), maxLines = 1)
            }

            Spacer(Modifier.width(10.dp))

            // ── Far end: menu button - moved away from the diary button so the
            // two aren't crowded together, a more standard spot for an
            // overflow/settings menu (opposite end from the primary nav icon).
            Surface(
                onClick = onMenu,
                shape = RoundedCornerShape(11.dp),
                color = gold.copy(.06f),
                modifier = Modifier.size(42.dp).semantics { contentDescription = "תפריט" }
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    IconMenu(gold, 19.dp)
                }
            }
        }
        Divider(color = gold.copy(.08f), thickness = 1.dp)
    }
}

// ── Bottom Nav — compact, centred ────────────────────────────────────────
@Composable
fun BottomNav(
    onPrev: () -> Unit, onToday: () -> Unit, onNext: () -> Unit,
    canPrev: Boolean, canNext: Boolean, isOnToday: Boolean,
    surf: Color, gold: Color
) {
    val navPad = WindowInsets.navigationBars.asPaddingValues()
    Box(Modifier.fillMaxWidth()
        .background(surf.copy(alpha = 0.92f))
        .padding(navPad)
        .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .background(surf, RoundedCornerShape(14.dp))
                .border(1.dp, gold.copy(.18f), RoundedCornerShape(14.dp))
        ) {
            // Previous (RTL: right chevron = previous week)
            NavPillButton(
                onClick = onPrev, enabled = canPrev,
                isFirst = true, isLast = false,
                gold = gold
            ) { IconChevronRight(if (canPrev) gold else gold.copy(.22f), 20.dp) }

            // Divider
            Box(Modifier.width(1.dp).height(32.dp).background(gold.copy(.15f)))

            // Today / home
            NavPillButton(
                onClick = onToday, enabled = true,
                isFirst = false, isLast = false,
                gold = gold,
                highlight = !isOnToday
            ) { IconHome(if (!isOnToday) gold else gold.copy(.35f), 18.dp) }

            // Divider
            Box(Modifier.width(1.dp).height(32.dp).background(gold.copy(.15f)))

            // Next (RTL: left chevron = next week)
            NavPillButton(
                onClick = onNext, enabled = canNext,
                isFirst = false, isLast = true,
                gold = gold
            ) { IconChevronLeft(if (canNext) gold else gold.copy(.22f), 20.dp) }
        }
    }
}

@Composable
fun NavPillButton(
    onClick: () -> Unit, enabled: Boolean,
    isFirst: Boolean, isLast: Boolean,
    gold: Color, highlight: Boolean = false,
    content: @Composable () -> Unit
) {
    val shape = when {
        isFirst -> RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp)
        isLast  -> RoundedCornerShape(topEnd   = 14.dp, bottomEnd   = 14.dp)
        else    -> RoundedCornerShape(0.dp)
    }
    Box(
        Modifier
            .size(width = 56.dp, height = 46.dp)
            .background(
                if (highlight) gold.copy(.12f) else Color.Transparent,
                shape
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}

// ── Parasha Picker ────────────────────────────────────────────────────────
@Composable
fun ParashaPicker(
    parashot: List<Pair<String, Int>>, currentIndex: Int,
    surf: Color, gold: Color, txt: Color, sub: Color,
    onSelected: (Int) -> Unit, onDismiss: () -> Unit,
    onOpenDayPicker: () -> Unit
) {
    val listState = rememberLazyListState((currentIndex - 2).coerceAtLeast(0))
    Box(Modifier.fillMaxSize().background(Color.Black.copy(.65f)).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onDismiss() },
        contentAlignment = Alignment.Center) {
        Card(Modifier.fillMaxWidth(.68f).fillMaxHeight(.68f).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {},
            shape  = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = surf),
            elevation = CardDefaults.cardElevation(18.dp)) {
            Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 16.dp)) {

                // Header
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()) {
                    Box(Modifier.width(36.dp).height(3.dp)
                        .background(gold.copy(.35f), RoundedCornerShape(2.dp)))
                    Spacer(Modifier.height(10.dp))
                    Text("בחר פרשה", style = TextStyle(fontSize = 16.sp,
                        fontWeight = FontWeight.Bold, color = gold))
                }

                Spacer(Modifier.height(10.dp))

                // Day-picker quick action — pill button, centred
                Surface(
                    onClick = onOpenDayPicker,
                    shape  = RoundedCornerShape(20.dp),
                    color  = gold.copy(.10f),
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        IconCalendar(gold.copy(.85f), 13.dp)
                        Text("לפי תאריך מדויק",
                            style = TextStyle(fontSize = 11.5.sp, color = gold.copy(.85f)))
                    }
                }

                Spacer(Modifier.height(10.dp))
                Divider(color = gold.copy(.12f))
                Spacer(Modifier.height(6.dp))

                LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(parashot.size) { i ->
                        val (name, _) = parashot[i]; val sel = i == currentIndex
                        Box(Modifier.fillMaxWidth()
                            .background(if (sel) gold.copy(.14f) else Color.Transparent,
                                RoundedCornerShape(10.dp))
                            .border(if (sel) 1.dp else 0.dp,
                                if (sel) gold.copy(.4f) else Color.Transparent,
                                RoundedCornerShape(10.dp))
                            .clickable { onSelected(i) }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                            contentAlignment = Alignment.Center) {
                            Text(if (name.startsWith("(")) name else "פרשת $name",
                                style = TextStyle(fontSize = 13.5.sp,
                                    color = if (sel) gold else txt,
                                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                                    textAlign = TextAlign.Center))
                        }
                    }
                }
            }
        }
    }
}

// ── Menu Sheet ────────────────────────────────────────────────────────────
@Composable
fun MenuSheet(surf: Color, gold: Color, txt: Color, isDark: Boolean,
              onSettings: () -> Unit, onManage: () -> Unit,
              onNight: () -> Unit, onAbout: () -> Unit, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(.5f)).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onDismiss() },
        contentAlignment = Alignment.TopEnd) {
        Card(Modifier.padding(top = 70.dp, end = 12.dp).width(210.dp),
            shape  = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = surf),
            elevation = CardDefaults.cardElevation(12.dp)) {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                MenuRow(icon = { IconSettings(gold, 18.dp) }, label = "הגדרות",    txt, onSettings)
                MenuRow(icon = { IconLibrary(gold, 18.dp)  }, label = "ניהול לוחות", txt, onManage)
                MenuRow(
                    icon  = { if (isDark) IconSun(gold, 18.dp) else IconMoon(gold, 18.dp) },
                    label = if (isDark) "מצב יום" else "מצב לילה",
                    txt = txt, onClick = onNight)
                Divider(color = gold.copy(.2f), modifier = Modifier.padding(vertical = 4.dp))
                MenuRow(icon = { IconInfo(gold, 18.dp) }, label = "אודות", txt, onAbout)
            }
        }
    }
}

@Composable
fun MenuRow(icon: @Composable () -> Unit, label: String, txt: Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        icon()
        Text(label, style = TextStyle(fontSize = 14.sp, color = txt))
    }
}

// ── Settings Screen ───────────────────────────────────────────────────────
@Composable
fun SettingsScreen(settings: AppSettings, repo: LuachRepository, surf: Color, gold: Color, txt: Color, sub: Color,
                   onSave: (AppSettings) -> Unit, onDismiss: () -> Unit,
                   onEventsChanged: () -> Unit = {}) {
    var compactView by remember { mutableStateOf(settings.compactView) }
    var cutPercent  by remember { mutableStateOf(settings.compactCutPercent) }
    var autoSyncCalendar by remember { mutableStateOf(settings.autoSyncCalendar) }

    var showIcsExport  by remember { mutableStateOf(false) }
    var icsExportText  by remember { mutableStateOf("") }
    var showIcsImport  by remember { mutableStateOf(false) }
    var icsStatusMsg   by remember { mutableStateOf<String?>(null) }

    ExportIcsFile(show = showIcsExport, fileName = "luach_itim_events.ics",
        icsContent = icsExportText, onDone = { showIcsExport = false })

    ImportIcsFile(show = showIcsImport) { text ->
        showIcsImport = false
        if (text == null) {
            icsStatusMsg = null
        } else {
            val parsed = parseIcsEvents(text)
            parsed.forEach { e ->
                val jd = repo.gregorianToJdLocal(e.year, e.month, e.day)
                repo.addEvent(jd, e.title, e.note)
            }
            icsStatusMsg = "יובאו ${parsed.size} אירועים ליומן"
            onEventsChanged()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(.7f)).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onDismiss() },
        contentAlignment = Alignment.Center) {
        Card(Modifier.fillMaxWidth(.88f).fillMaxHeight(.7f).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {},
            shape  = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = surf),
            elevation = CardDefaults.cardElevation(16.dp)) {
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())
                    .padding(24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {

                    Text("הגדרות", style = TextStyle(fontSize = 20.sp,
                        fontWeight = FontWeight.Bold, color = gold))

                    // Note: the Israel/Diaspora schedule is chosen per-luach,
                    // in the "add luach" wizard - not here as a general
                    // setting, since each luach PDF is produced for one
                    // region's holiday reading schedule specifically.

                    // ── Compact view ─────────────────────────────────────
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("תצוגה מצומצמת", style = TextStyle(fontSize = 14.sp,
                                    color = sub, fontWeight = FontWeight.Medium))
                                Text("מציג רק את שולי העמוד השני",
                                    style = TextStyle(fontSize = 11.sp, color = sub.copy(.7f)))
                            }
                            Switch(
                                checked = compactView,
                                onCheckedChange = { compactView = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = gold,
                                    checkedTrackColor = gold.copy(.4f)
                                )
                            )
                        }

                        if (compactView) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("היקף החיתוך מהאמצע: ${(cutPercent * 100).toInt()}%",
                                    style = TextStyle(fontSize = 12.sp, color = sub))
                                Slider(
                                    value = cutPercent,
                                    onValueChange = { cutPercent = it },
                                    valueRange = 0.1f..0.85f,
                                    colors = SliderDefaults.colors(
                                        thumbColor = gold,
                                        activeTrackColor = gold,
                                        inactiveTrackColor = gold.copy(.2f)
                                    )
                                )
                            }
                        }
                    }

                    // ── Calendar sync ─────────────────────────────────────
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("סנכרון ליומן הטלפון", style = TextStyle(fontSize = 14.sp,
                                    color = sub, fontWeight = FontWeight.Medium))
                                Text("אירועי היומן מסתנכרנים אוטומטית עם יומן הטלפון בשני הכיוונים - גם אירועים מהיומן שלנו מופיעים ביומן המובנה, וגם אירועים שנוספו ביומן אחר (Google וכו׳) נכנסים לכאן - הכל מקומי, בלי חשבון ובלי אינטרנט",
                                    style = TextStyle(fontSize = 11.sp, color = sub.copy(.7f)))
                            }
                            Switch(
                                checked = autoSyncCalendar,
                                onCheckedChange = { autoSyncCalendar = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = gold,
                                    checkedTrackColor = gold.copy(.4f)
                                )
                            )
                        }
                    }

                    // ── Daily-changing home-screen shortcut icon ─────────
                    // Real app icons can't be redrawn at runtime on Android -
                    // this renders as a separate pinned shortcut whose OWN
                    // icon bitmap is regenerated every day. No-op on Desktop
                    // (see DailyIconSetting.desktop.kt).
                    DailyIconSettingRow(gold = gold, sub = sub)

                    // ── Diary export / import (ICS) ──────────────────────
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("יומן אירועים (ICS)", style = TextStyle(fontSize = 14.sp,
                            color = sub, fontWeight = FontWeight.Medium))
                        Text("ייצוא/יבוא חד-פעמי, ללא צורך בחשבון או חיבור לאינטרנט",
                            style = TextStyle(fontSize = 11.sp, color = sub.copy(.7f)))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = {
                                    icsStatusMsg = null
                                    val events = repo.getAllEvents()
                                    icsExportText = buildIcs(events, repo, currentJulianDay())
                                    showIcsExport = true
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = gold, contentColor = contrastOn(gold))
                            ) { Text("ייצוא", fontSize = 13.sp) }
                            OutlinedButton(
                                onClick = { icsStatusMsg = null; showIcsImport = true },
                                modifier = Modifier.weight(1f),
                                border = BorderStroke(1.dp, gold.copy(.5f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = gold)
                            ) { Text("יבוא", fontSize = 13.sp) }
                        }
                        icsStatusMsg?.let {
                            Text(it, style = TextStyle(fontSize = 12.sp, color = gold))
                        }
                    }
                }

                Divider(color = gold.copy(.15f))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text("ביטול", color = sub)
                    }
                    Button(onClick = {
                        onSave(settings.copy(
                            compactView = compactView,
                            compactCutPercent = cutPercent,
                            autoSyncCalendar = autoSyncCalendar
                        ))
                    },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = gold, contentColor = contrastOn(gold))) {
                        Text("שמור", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun RowScope.ToggleOption(label: String, selected: Boolean,
                          gold: Color, txt: Color, surf: Color, onClick: () -> Unit) {
    // onGold: contrast colour on top of the accent bg
    // We detect light/dark accent by luminance approximation
    val accentIsLight = gold.red * 0.299f + gold.green * 0.587f + gold.blue * 0.114f > 0.5f
    val onGold = if (accentIsLight) Color(0xFF0D1B2A) else Color(0xFFF0F4FF)
    Box(Modifier.weight(1f)
        .background(if (selected) gold else surf)
        .clickable(onClick = onClick)
        .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center) {
        Text(label, style = TextStyle(fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) onGold else txt))
    }
}

// ── Manage Luach ──────────────────────────────────────────────────────────
@Composable
fun ManageLuachScreen(repo: LuachRepository, surf: Color, gold: Color, txt: Color, sub: Color,
                      onActivate: (LuachEntry) -> Unit, onDismiss: () -> Unit) {
    var luachList      by remember { mutableStateOf(repo.loadLuachList()) }
    var activeKey      by remember { mutableStateOf(repo.loadActiveLuachKey()) }
    var showPicker     by remember { mutableStateOf(false) }
    var newName        by remember { mutableStateOf("לוח חדש") }
    var newYear        by remember { mutableStateOf(5786) }
    var newInIsrael    by remember { mutableStateOf(true) }
    var pickedPath     by remember { mutableStateOf<String?>(null) }
    var showNameDlg    by remember { mutableStateOf(false) }
    var deleteKey      by remember { mutableStateOf<String?>(null) }
    var renameKey      by remember { mutableStateOf<String?>(null) }
    var renameText     by remember { mutableStateOf("") }

    FilePicker(show = showPicker, fileExtensions = listOf("pdf")) { path ->
        showPicker = false
        if (path != null) { pickedPath = path; showNameDlg = true }
    }
    if (showNameDlg && pickedPath != null) {
        AlertDialog(
            onDismissRequest = { showNameDlg = false },
            title = { Text("הוספת לוח", style = TextStyle(color = gold, fontWeight = FontWeight.Bold)) },
            text  = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = newName, onValueChange = { newName = it },
                        label = { Text("שם הלוח") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    Text("שנת הלוח:", style = TextStyle(fontSize = 13.sp, color = sub))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(AVAILABLE_YEARS.size) { i ->
                            val (yr, name) = AVAILABLE_YEARS[i]
                            val sel = yr == newYear
                            Box(Modifier
                                .background(if (sel) gold else Color.Transparent, RoundedCornerShape(8.dp))
                                .border(1.dp, gold.copy(if (sel) 1f else .35f), RoundedCornerShape(8.dp))
                                .clickable { newYear = yr }
                                .padding(horizontal = 10.dp, vertical = 6.dp)) {
                                Text(name, style = TextStyle(fontSize = 13.sp,
                                    color = if (sel) contrastOn(gold) else gold,
                                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal))
                            }
                        }
                    }
                    Text("הלוח מיועד ל:", style = TextStyle(fontSize = 13.sp, color = sub))
                    Surface(shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, gold.copy(.3f)),
                        color  = Color.Transparent,
                        modifier = Modifier.fillMaxWidth()) {
                        Row {
                            ToggleOption("ארץ ישראל", newInIsrael,  gold, txt, surf) { newInIsrael = true  }
                            ToggleOption("חוץ לארץ",  !newInIsrael, gold, txt, surf) { newInIsrael = false }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showNameDlg = false; luachList = repo.addLuach(newName, pickedPath!!, newYear, newInIsrael) },
                    colors = ButtonDefaults.buttonColors(containerColor = gold, contentColor = contrastOn(gold))) {
                    Text("הוסף")
                }
            },
            dismissButton = { TextButton(onClick = { showNameDlg = false }) { Text("ביטול") } }
        )
    }

    // Rename dialog
    val pendingRename = renameKey?.let { k -> luachList.find { it.key == k } }
    if (pendingRename != null) {
        AlertDialog(
            onDismissRequest = { renameKey = null },
            title = { Text("שינוי שם", style = TextStyle(color = gold, fontWeight = FontWeight.Bold)) },
            text  = {
                OutlinedTextField(value = renameText, onValueChange = { renameText = it },
                    label = { Text("שם חדש") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                Button(onClick = {
                    luachList = repo.renameLuach(pendingRename.key, renameText)
                    renameKey = null
                }, colors = ButtonDefaults.buttonColors(containerColor = gold, contentColor = contrastOn(gold))) {
                    Text("שמור")
                }
            },
            dismissButton = { TextButton(onClick = { renameKey = null }) { Text("ביטול") } }
        )
    }
    // Confirm delete dialog
    val pendingDelete = deleteKey?.let { k -> luachList.find { it.key == k } }
    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { deleteKey = null },
            title = { Text("מחיקת לוח", style = TextStyle(fontWeight = FontWeight.Bold, color = Color.Red.copy(.8f))) },
            text  = { Text("האם למחוק את הלוח \"${pendingDelete.name}\"?",
                style = TextStyle(fontSize = 14.sp)) },
            confirmButton = {
                Button(onClick = {
                    luachList = repo.removeLuach(pendingDelete.key)
                    deleteKey = null
                }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(.8f),
                    contentColor = Color.White)) { Text("מחק") }
            },
            dismissButton = {
                TextButton(onClick = { deleteKey = null }) { Text("ביטול") }
            }
        )
    }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(.72f)).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onDismiss() },
        contentAlignment = Alignment.Center) {
        Card(Modifier.fillMaxWidth(.9f).fillMaxHeight(.72f).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {},
            shape  = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = surf),
            elevation = CardDefaults.cardElevation(16.dp)) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("ניהול לוחות", style = TextStyle(fontSize = 18.sp,
                        fontWeight = FontWeight.Bold, color = gold))
                    IconButton(onClick = { showPicker = true },
                        modifier = Modifier.size(36.dp)
                            .background(gold.copy(.15f), RoundedCornerShape(8.dp))
                            .semantics { contentDescription = "הוספת לוח" }) {
                        IconPlus(gold, 18.dp)
                    }
                }
                if (luachList.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("אין לוחות. לחץ + להוספה.",
                            style = TextStyle(color = sub, fontSize = 14.sp))
                    }
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(luachList.size) { i ->
                        val e = luachList[i]; val isActive = e.key == activeKey
                        Row(Modifier.fillMaxWidth()
                            .background(if (isActive) gold.copy(.15f) else Color.Transparent,
                                RoundedCornerShape(10.dp))
                            .border(if (isActive) 1.dp else 0.dp,
                                if (isActive) gold.copy(.5f) else Color.Transparent,
                                RoundedCornerShape(10.dp))
                            .clickable {
                                repo.saveActiveLuachKey(e.key); activeKey = e.key; onActivate(e)
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(e.name, style = TextStyle(fontSize = 15.sp,
                                    color = if (isActive) gold else txt,
                                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${AVAILABLE_YEARS.find { it.first == e.hebrewYear }?.second ?: e.hebrewYear.toString()} · ${if (e.inIsrael) "א״י" else "חו״ל"}  •  ${e.pdfPath.substringAfterLast("/").substringAfterLast("\\")}",
                                    style = TextStyle(fontSize = 11.sp, color = sub),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                // Rename
                                IconButton(onClick = { renameKey = e.key; renameText = e.name },
                                    modifier = Modifier.size(30.dp)
                                        .background(gold.copy(.1f), RoundedCornerShape(6.dp))
                                        .semantics { contentDescription = "שינוי שם הלוח ${e.name}" }) {
                                    IconEdit(gold.copy(.7f), 13.dp)
                                }
                                // Delete (only non-active)
                                if (!isActive)
                                    IconButton(onClick = { deleteKey = e.key },
                                        modifier = Modifier.size(30.dp)
                                            .background(Color.Red.copy(.08f), RoundedCornerShape(6.dp))
                                            .semantics { contentDescription = "מחיקת הלוח ${e.name}" }) {
                                        IconTrash(Color.Red.copy(.6f), 13.dp)
                                    }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Day Context Menu ──────────────────────────────────────────────────────
@Composable
fun DayContextMenu(
    jd: Long, repo: LuachRepository,
    surf: Color, gold: Color, txt: Color, sub: Color,
    onAddEvent: () -> Unit, onOpenEvent: (DiaryEvent) -> Unit, onDismiss: () -> Unit
) {
    val hd      = if (jd > 1000000L) jdToHebrew(jd) else return
    val dowIdx  = ((jd + 1) % 7).toInt()
    val dowName = dayOfWeekName(dowIdx + 1)
    val holiday = remember(jd) { holidayName(jd, repo.loadSettings().inIsrael) }
    val dateStr = "יום $dowName ${formatHebrewDate(hd)}" + if (holiday.isNotEmpty()) " · $holiday" else ""
    val events  = remember(jd) { repo.loadEventsForDay(jd) }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(.5f)).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onDismiss() },
        contentAlignment = Alignment.Center) {
        Card(Modifier.widthIn(min = 200.dp, max = 280.dp).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {},
            shape  = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = surf),
            elevation = CardDefaults.cardElevation(14.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(dateStr, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    color = gold, textAlign = TextAlign.Center),
                    modifier = Modifier.fillMaxWidth())
                Divider(color = gold.copy(.2f))

                // Tapping an event opens it directly for editing
                if (events.isNotEmpty()) {
                    events.take(4).forEach { ev ->
                        Row(Modifier.fillMaxWidth().clickable { onOpenEvent(ev) }
                            .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(6.dp).background(gold, CircleShape))
                            Text(ev.title, style = TextStyle(fontSize = 13.sp, color = txt),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (events.size > 4)
                        Text("ועוד ${events.size - 4}...",
                            style = TextStyle(fontSize = 11.sp, color = sub))
                    Divider(color = gold.copy(.15f))
                }

                Row(Modifier.fillMaxWidth().clickable { onAddEvent() }
                    .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    IconPlus(gold, 16.dp)
                    Text("הוסף אירוע", style = TextStyle(fontSize = 14.sp, color = txt))
                }
            }
        }
    }
}

// ── About ─────────────────────────────────────────────────────────────────
@Composable
fun AboutDialog(surf: Color, gold: Color, txt: Color, sub: Color, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current

    val currentHebrewYear = remember { hebrewYearGematria(jdToHebrew(currentJulianDay()).year) }
    val devEmail = "ys10app@gmail.com"

    Box(Modifier.fillMaxSize().background(Color.Black.copy(.72f)).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onDismiss() },
        contentAlignment = Alignment.Center) {
        Card(Modifier.fillMaxWidth(.86f).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {},
            shape  = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = surf),
            elevation = CardDefaults.cardElevation(20.dp)) {
            Box {
                // Soft decorative arcs, echoing the welcome screen's visual language
                Canvas(Modifier.matchParentSize()) {
                    repeat(6) { i ->
                        drawCircle(color = gold.copy(alpha = 0.05f),
                            radius = i * 46f,
                            center = Offset(size.width * .88f, size.height * .06f),
                            style  = Stroke(1.2f))
                    }
                }

                Column(
                    Modifier.padding(horizontal = 26.dp, vertical = 28.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // App icon
                    Image(
                        painter = painterResource(Res.drawable.app_icon),
                        contentDescription = null,
                        modifier = Modifier.size(64.dp).clip(CircleShape)
                    )

                    Spacer(Modifier.height(14.dp))
                    Text("לוח עתים לבינה", style = TextStyle(fontSize = 21.sp,
                        fontWeight = FontWeight.Bold, color = gold, textAlign = TextAlign.Center))
                    Text("לוח שנה עברי ופרשות השבוע", style = TextStyle(fontSize = 12.5.sp,
                        color = sub, textAlign = TextAlign.Center))

                    Spacer(Modifier.height(6.dp))
                    Surface(shape = RoundedCornerShape(20.dp), color = gold.copy(alpha = .12f)) {
                        Text("גרסה ${VersionInfo.VERSION_NAME}",
                            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = gold),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp))
                    }

                    Spacer(Modifier.height(16.dp))
                    Box(Modifier.width(120.dp).height(1.dp).background(
                        Brush.horizontalGradient(listOf(Color.Transparent, gold.copy(alpha = .5f), Color.Transparent))))
                    Spacer(Modifier.height(16.dp))

                    Text("חישוב פרשות השבוע והתאריך העברי מבוסס על ספריית הקוד הפתוח KosherJava Zmanim",
                        style = TextStyle(fontSize = 12.5.sp, color = txt, textAlign = TextAlign.Center, lineHeight = 18.sp))

                    Spacer(Modifier.height(10.dp))
                    Text("האפליקציה היא ממשק תצוגה עצמאי בלבד, ואין לה כל קשר לגוף \"עתים לבינה\" או לעומד מאחורי הלוחות",
                        style = TextStyle(fontSize = 11.sp, color = sub, textAlign = TextAlign.Center, lineHeight = 16.sp))

                    Spacer(Modifier.height(18.dp))

                    // Developer contact - a plain, static row. Tapping copies the
                    // address to the clipboard silently, with no visual change at
                    // all - no swapped label, no "copied" caption.
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("יצירת קשר עם המפתח", style = TextStyle(fontSize = 11.sp, color = sub))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = gold.copy(alpha = .08f),
                            onClick = { clipboard.setText(AnnotatedString(devEmail)) }
                        ) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(devEmail, style = TextStyle(fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium, color = gold, textAlign = TextAlign.Center))
                                Text("⧉", style = TextStyle(fontSize = 13.sp, color = gold.copy(alpha = .7f)))
                            }
                        }
                    }

                    Spacer(Modifier.height(18.dp))

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = gold.copy(alpha = .08f),
                        onClick = { openUrl("https://itimlabina.co.il/free-products") }
                    ) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("לוחות ניתן להוריד מהאתר \"עתים לבינה\"", style = TextStyle(fontSize = 13.sp,
                                fontWeight = FontWeight.Medium, color = gold))
                            Text("↗", style = TextStyle(fontSize = 13.sp, color = gold.copy(alpha = .7f)))
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = gold.copy(alpha = .08f),
                        onClick = { openUrl("https://mitmachim.top/post/1212243") }
                    ) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("מידע ועדכונים", style = TextStyle(fontSize = 13.sp,
                                fontWeight = FontWeight.Medium, color = gold))
                            Text("↗", style = TextStyle(fontSize = 13.sp, color = gold.copy(alpha = .7f)))
                        }
                    }

                    Spacer(Modifier.height(18.dp))
                    Text("© $currentHebrewYear לוח עתים לבינה · כל הזכויות שמורות",
                        style = TextStyle(fontSize = 10.5.sp, color = txt.copy(alpha = .45f), textAlign = TextAlign.Center))

                    Spacer(Modifier.height(20.dp))
                    Button(onClick = onDismiss,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = gold, contentColor = contrastOn(gold))) {
                        Text("סגור", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold))
                    }
                }
            }
        }
    }
}

// ── Diary Screen ───────────────────────────────────────────────────────────
@Composable
fun DiaryScreen(
    repo: LuachRepository, jd: Long,
    surf: Color, gold: Color, txt: Color, sub: Color,
    enqueueCalOp: (CalSyncOp) -> Unit,
    onJumpToDay: (Long) -> Unit = {},
    onDismiss: () -> Unit
) {
    var refreshTrigger by remember { mutableStateOf(0) }
    var showAdd      by remember { mutableStateOf(false) }
    var editingEvent by remember { mutableStateOf<DiaryEvent?>(null) }
    var pendingDelete by remember { mutableStateOf<DiaryEvent?>(null) }
    val autoSync = remember { repo.loadSettings().autoSyncCalendar }

    // How far the "show earlier/later" buttons have been expanded. 0 = just
    // this day (the default, unchanged view). Each further tap adds one more
    // week on that side.
    var pastExpansions   by remember(jd) { mutableStateOf(0) }
    var futureExpansions by remember(jd) { mutableStateOf(0) }

    val safeJd2 = if (jd < 1000000L) currentJulianDay() else jd
    val hd      = jdToHebrew(safeJd2)
    val dowIdx  = ((jd + 1) % 7).toInt()
    val dowName = dayOfWeekName(dowIdx + 1)
    val holiday = remember(safeJd2) { holidayName(safeJd2, repo.loadSettings().inIsrael) }
    val dateLabel = "יום $dowName  ${formatHebrewDate(hd)}" + if (holiday.isNotEmpty()) "  ·  $holiday" else ""
    val (gy, gm, gd) = repo.jdToGregorian(jd)

    val weekStartJd = jd - dowIdx           // Sunday of this day's week
    val weekEndJd   = weekStartJd + 6
    val rangeStart  = if (pastExpansions == 0) jd else weekStartJd - (pastExpansions - 1) * 7
    val rangeEnd    = if (futureExpansions == 0) jd else weekEndJd + (futureExpansions - 1) * 7

    // Loaded once per screen-open and refreshed whenever an event is added/
    // edited/deleted below - cheap enough for a personal diary's event count.
    val allEvents = remember(refreshTrigger) { repo.getAllEvents() }
    val displayEvents = remember(allEvents, rangeStart, rangeEnd) {
        allEvents.filter { it.jd in rangeStart..rangeEnd }.sortedBy { it.jd }
    }

    // Add / Edit dialog
    if (showAdd || editingEvent != null) {
        val current = editingEvent
        EventEditorDialog(
            isEditing = current != null,
            initialTitle = current?.title ?: "",
            initialNote  = current?.note  ?: "",
            gold = gold, surf = surf, txt = txt, sub = sub,
            autoSync = autoSync,
            onDismiss = { showAdd = false; editingEvent = null },
            onSave = { newTitle, newNote ->
                if (current != null) {
                    repo.editEvent(current.jd, current.idx, newTitle, newNote)
                    if (autoSync) {
                        val (y, m, d) = repo.jdToGregorian(current.jd)
                        enqueueCalOp(CalSyncOp.Upsert(
                            current.jd, current.idx, current.calEventId, y, m, d, newTitle, newNote
                        ))
                    }
                } else {
                    val updated = repo.addEvent(jd, newTitle, newNote)
                    if (autoSync) {
                        val newEv = updated.last()
                        enqueueCalOp(CalSyncOp.Upsert(
                            newEv.jd, newEv.idx, null, gy, gm, gd, newTitle, newNote
                        ))
                    }
                }
                refreshTrigger++
                showAdd = false; editingEvent = null
            }
        )
    }

    // Delete confirmation - a destructive action never fires on a single stray tap
    pendingDelete?.let { ev ->
        ConfirmDeleteDialog(
            eventTitle = ev.title, gold = gold, surf = surf, txt = txt, sub = sub,
            onCancel = { pendingDelete = null },
            onConfirm = {
                val calId = ev.calEventId
                repo.deleteEvent(ev.jd, ev.idx)
                if (autoSync && calId != null) {
                    enqueueCalOp(CalSyncOp.Delete(ev.jd, ev.idx, calId))
                }
                refreshTrigger++
                pendingDelete = null
            }
        )
    }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(.72f)).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onDismiss() },
        contentAlignment = Alignment.Center) {
        Card(Modifier.fillMaxWidth(.9f).fillMaxHeight(.75f).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {},
            shape  = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = surf),
            elevation = CardDefaults.cardElevation(16.dp)) {
            Column(Modifier.fillMaxSize()) {

                // Header
                Row(Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("יומן", style = TextStyle(fontSize = 12.sp, color = sub))
                        Text(dateLabel, style = TextStyle(fontSize = 16.sp,
                            fontWeight = FontWeight.Bold, color = gold))
                    }
                    Surface(
                        onClick = { showAdd = true },
                        shape = RoundedCornerShape(10.dp),
                        color = gold
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            IconPlus(contrastOn(gold), 15.dp)
                            Text("אירוע חדש", style = TextStyle(fontSize = 13.sp,
                                fontWeight = FontWeight.Bold, color = contrastOn(gold)))
                        }
                    }
                }

                Divider(color = gold.copy(.15f))

                // "Show earlier" - 1st tap expands to the start of this week,
                // every tap after that pulls in one more week before that.
                TextButton(
                    onClick = { pastExpansions++ },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("▲  הצג אירועים קודמים", style = TextStyle(fontSize = 12.5.sp, color = gold.copy(.8f)))
                }

                if (displayEvents.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("📭", style = TextStyle(fontSize = 32.sp))
                            Text("אין אירועים בטווח המוצג",
                                style = TextStyle(fontSize = 14.sp, color = sub))
                            TextButton(onClick = { showAdd = true }) {
                                Text("+ הוסף אירוע", color = gold, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                } else {
                    LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(displayEvents) { _, ev ->
                            val isThisDay = ev.jd == jd
                            Surface(
                                onClick = {
                                    if (isThisDay) editingEvent = ev
                                    else { onJumpToDay(ev.jd); onDismiss() }
                                },
                                shape  = RoundedCornerShape(12.dp),
                                color = if (isThisDay) gold.copy(.08f) else gold.copy(.04f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(Modifier.fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column(Modifier.weight(1f)) {
                                        if (!isThisDay) {
                                            val evHd = jdToHebrew(ev.jd)
                                            val evDow = dayOfWeekName(((ev.jd + 1) % 7).toInt() + 1)
                                            Text("יום $evDow  ${formatHebrewDate(evHd)}",
                                                style = TextStyle(fontSize = 11.sp, color = gold.copy(.75f),
                                                    fontWeight = FontWeight.Bold))
                                        }
                                        Text(ev.title, style = TextStyle(fontSize = 14.5.sp,
                                            fontWeight = FontWeight.SemiBold, color = txt))
                                        if (ev.note.isNotBlank())
                                            Text(ev.note, style = TextStyle(
                                                fontSize = 12.sp, color = sub), maxLines = 2)
                                    }
                                    IconButton(onClick = { pendingDelete = ev },
                                        modifier = Modifier.size(30.dp)
                                            .semantics { contentDescription = "מחיקת האירוע ${ev.title}" }) {
                                        IconTrash(Color.Red.copy(.55f), 14.dp)
                                    }
                                }
                            }
                        }
                    }
                }

                // "Show later" - same idea, one more week forward per tap.
                TextButton(
                    onClick = { futureExpansions++ },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("▼  הצג אירועים מאוחר יותר", style = TextStyle(fontSize = 12.5.sp, color = gold.copy(.8f)))
                }

                Divider(color = gold.copy(.1f))
                TextButton(onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                    Text("סגור", style = TextStyle(color = sub, fontSize = 14.sp))
                }
            }
        }
    }
}

/**
 * Simple event editor for adding/editing a diary entry - a plain system
 * AlertDialog with clearly bordered fields for the title and the details,
 * so it's obvious at a glance where to type.
 */
@Composable
private fun EventEditorDialog(
    isEditing: Boolean, initialTitle: String, initialNote: String,
    gold: Color, surf: Color, txt: Color, sub: Color,
    autoSync: Boolean,
    onDismiss: () -> Unit,
    onSave: (title: String, note: String) -> Unit
) {
    var titleText by remember { mutableStateOf(initialTitle) }
    var noteText  by remember { mutableStateOf(initialNote) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = surf,
        title = { Text(if (isEditing) "עריכת אירוע" else "אירוע חדש",
            style = TextStyle(color = gold, fontWeight = FontWeight.Bold)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = titleText, onValueChange = { titleText = it },
                    label = { Text("כותרת") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = gold, cursorColor = gold)
                )
                OutlinedTextField(
                    value = noteText, onValueChange = { noteText = it },
                    label = { Text("פרטים (אופציונלי)") },
                    minLines = 2, maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = gold, cursorColor = gold)
                )
                if (autoSync) {
                    Text("האירוע יתווסף אוטומטית גם ליומן הטלפון",
                        style = TextStyle(fontSize = 11.5.sp, color = sub))
                }
            }
        },
        confirmButton = {
            Button(onClick = { if (titleText.isNotBlank()) onSave(titleText, noteText) },
                colors = ButtonDefaults.buttonColors(containerColor = gold, contentColor = contrastOn(gold))) {
                Text("שמור")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("ביטול") }
        }
    )
}

/** One extra tap before a delete actually happens - cheap insurance against a stray tap losing data. */
@Composable
private fun ConfirmDeleteDialog(
    eventTitle: String, gold: Color, surf: Color, txt: Color, sub: Color,
    onCancel: () -> Unit, onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = surf,
        title = { Text("מחיקת אירוע", style = TextStyle(color = gold, fontWeight = FontWeight.Bold)) },
        text = { Text("למחוק את \"$eventTitle\"? לא ניתן לשחזר לאחר המחיקה.",
            style = TextStyle(color = txt, fontSize = 14.sp)) },
        confirmButton = {
            Button(onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD9534F))) {
                Text("מחיקה")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("ביטול", color = sub) }
        }
    )
}
