package com.luachitim.ui

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import java.util.Calendar
import java.util.TimeZone

private const val TAG = "DeviceCalendarSync"

// Both permissions are needed together: WRITE_CALENDAR to insert/update/
// delete events, READ_CALENDAR to look up (or confirm the existence of) the
// app's own local calendar row first. On modern Android, granting one of a
// permission group does NOT automatically grant the other - both must be
// requested and checked explicitly, or a "granted" WRITE_CALENDAR still
// leaves any query() call throwing SecurityException.
private val CALENDAR_PERMISSIONS = arrayOf(
    Manifest.permission.READ_CALENDAR,
    Manifest.permission.WRITE_CALENDAR
)

private fun hasCalendarPermissions(context: Context): Boolean =
    CALENDAR_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

// Identifies this app's own calendar within the device's shared Calendar
// Provider. ACCOUNT_TYPE_LOCAL means it's a plain on-device calendar with no
// account and no sync adapter behind it - purely local storage, same as any
// other row in the provider's SQLite database. No network access is involved
// in creating it, writing to it, or in any other calendar app reading it.
private const val LOCAL_ACCOUNT_NAME  = "לוח עתים לבינה"
private const val LOCAL_CALENDAR_NAME = "לוח עתים לבינה"

/**
 * Finds this app's own local calendar, creating it the first time it's
 * needed. Never throws - any failure (including a missing permission the
 * caller thought it had) is caught and reported as "not available" rather
 * than crashing the app.
 */
private fun findOrCreateLocalCalendarId(context: Context): Long? {
    return try {
        val cr = context.contentResolver
        val existing = cr.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND ${CalendarContract.Calendars.ACCOUNT_TYPE} = ?",
            arrayOf(LOCAL_ACCOUNT_NAME, CalendarContract.ACCOUNT_TYPE_LOCAL),
            null
        )
        existing?.use { if (it.moveToFirst()) return it.getLong(0) }

        // Not found yet - create it. Calendar rows can only be inserted while
        // "acting as the sync adapter" for that account, per CalendarContract's
        // own rules - hence the extra query params on top of the normal insert.
        val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT_NAME)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            .build()
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT_NAME)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, LOCAL_CALENDAR_NAME)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, LOCAL_CALENDAR_NAME)
            put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFFC98A12.toInt())
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, LOCAL_ACCOUNT_NAME)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
        }
        val created = cr.insert(uri, values) ?: return null
        ContentUris.parseId(created)
    } catch (e: Exception) {
        Log.e(TAG, "findOrCreateLocalCalendarId failed", e)
        null
    }
}

/** Inserts a fresh row, or updates the row at [CalSyncOp.Upsert.calEventId] in place. */
private fun applyUpsert(context: Context, op: CalSyncOp.Upsert): Long? {
    return try {
        val cr = context.contentResolver
        val calId = findOrCreateLocalCalendarId(context)
        if (calId == null) {
            Log.w(TAG, "applyUpsert: could not find or create the app's local calendar")
            return null
        }

        // Per CalendarContract docs, ALL_DAY events must be anchored to UTC
        // midnight with EVENT_TIMEZONE = "UTC" - otherwise the event can land
        // on the wrong day depending on the device's timezone offset.
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.set(op.year, op.month - 1, op.day, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val startMillis = cal.timeInMillis
        val endMillis   = startMillis + 24 * 60 * 60 * 1000  // all-day = +1 day

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, op.title)
            put(CalendarContract.Events.DESCRIPTION, op.note)
            put(CalendarContract.Events.DTSTART, startMillis)
            put(CalendarContract.Events.DTEND, endMillis)
            put(CalendarContract.Events.ALL_DAY, 1)
            put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
        }

        val existingId = op.calEventId
        if (existingId != null) {
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, existingId)
            val rows = cr.update(uri, values, null, null)
            if (rows > 0) {
                existingId
            } else {
                // The row is gone (e.g. deleted by hand in another calendar
                // app) - re-insert rather than silently losing the event.
                val newUri = cr.insert(CalendarContract.Events.CONTENT_URI, values)
                newUri?.let { ContentUris.parseId(it) }
            }
        } else {
            val uri = cr.insert(CalendarContract.Events.CONTENT_URI, values)
            uri?.let { ContentUris.parseId(it) }
        }
    } catch (e: Exception) {
        Log.e(TAG, "applyUpsert failed for ${op.year}-${op.month}-${op.day}", e)
        null
    }
}

private fun applyDelete(context: Context, calEventId: Long): Boolean {
    return try {
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, calEventId)
        context.contentResolver.delete(uri, null, null) > 0
    } catch (e: Exception) {
        Log.e(TAG, "applyDelete failed for id=$calEventId", e)
        false
    }
}

/**
 * Reads single (non-recurring), non-deleted events from every calendar
 * EXCEPT this app's own mirror calendar, within a bounded window around
 * today. Never throws - any failure yields an empty result rather than a
 * crash, same policy as the outbound side.
 */
private fun scanExternalEvents(context: Context): List<ExternalCalEvent> {
    return try {
        val cr = context.contentResolver
        val ourCalId = findOrCreateLocalCalendarId(context)

        val now = System.currentTimeMillis()
        val windowStart = now - 365L  * 24 * 60 * 60 * 1000   // ~1 year back
        val windowEnd   = now + 730L  * 24 * 60 * 60 * 1000   // ~2 years ahead

        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.EVENT_TIMEZONE
        )
        // RRULE/RDATE both null = not part of a recurring series - imported
        // one at a time like this, a recurring event would otherwise flood
        // the diary with one entry per occurrence.
        val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ? " +
                "AND ${CalendarContract.Events.RRULE} IS NULL AND ${CalendarContract.Events.RDATE} IS NULL " +
                "AND ${CalendarContract.Events.DELETED} != 1"
        val args = arrayOf(windowStart.toString(), windowEnd.toString())

        val results = mutableListOf<ExternalCalEvent>()
        cr.query(CalendarContract.Events.CONTENT_URI, projection, selection, args, null)?.use { c ->
            val idIdx    = c.getColumnIndexOrThrow(CalendarContract.Events._ID)
            val calIdIdx = c.getColumnIndexOrThrow(CalendarContract.Events.CALENDAR_ID)
            val titleIdx = c.getColumnIndexOrThrow(CalendarContract.Events.TITLE)
            val descIdx  = c.getColumnIndexOrThrow(CalendarContract.Events.DESCRIPTION)
            val startIdx = c.getColumnIndexOrThrow(CalendarContract.Events.DTSTART)
            val allDayIdx = c.getColumnIndexOrThrow(CalendarContract.Events.ALL_DAY)
            val tzIdx    = c.getColumnIndexOrThrow(CalendarContract.Events.EVENT_TIMEZONE)

            while (c.moveToNext()) {
                val calId = c.getLong(calIdIdx)
                if (ourCalId != null && calId == ourCalId) continue  // skip our own mirror - no feedback loop

                val title = c.getString(titleIdx)
                if (title.isNullOrBlank()) continue

                val id = c.getLong(idIdx)
                val note = c.getString(descIdx) ?: ""
                val start = c.getLong(startIdx)
                val allDay = c.getInt(allDayIdx) == 1
                val tzId = c.getString(tzIdx)

                val cal = Calendar.getInstance(
                    if (allDay) TimeZone.getTimeZone("UTC")
                    else (tzId?.let { runCatching { TimeZone.getTimeZone(it) }.getOrNull() } ?: TimeZone.getDefault())
                )
                cal.timeInMillis = start
                val y = cal.get(Calendar.YEAR)
                val m = cal.get(Calendar.MONTH) + 1
                val d = cal.get(Calendar.DAY_OF_MONTH)

                results.add(ExternalCalEvent(id, y, m, d, title, note))
            }
        }
        results
    } catch (e: Exception) {
        Log.e(TAG, "scanExternalEvents failed", e)
        emptyList()
    }
}

@Composable
actual fun DeviceCalendarImport(
    trigger: Int,
    onResult: (List<ExternalCalEvent>) -> Unit
) {
    val context = LocalContext.current
    LaunchedEffect(trigger) {
        if (trigger <= 0) return@LaunchedEffect
        // Only scans once permission is already granted - it never requests
        // it itself, to avoid two permission dialogs racing each other. The
        // outbound side (DeviceCalendarSync) is what asks; once that's
        // granted, the app bumps this trigger to run the first scan.
        if (!hasCalendarPermissions(context)) return@LaunchedEffect
        onResult(scanExternalEvents(context))
    }
}

@Composable
actual fun DeviceCalendarSync(
    queue: List<CalSyncOp>,
    onOpDone: (CalSyncOp, Long?) -> Unit
) {
    val context = LocalContext.current
    var awaitingPermissionFor by remember { mutableStateOf<CalSyncOp?>(null) }

    // A calendar-sync failure must never take the whole app down with it -
    // any op the calendar layer can't complete is reported as "not applied"
    // rather than allowed to throw back into the caller.
    fun process(op: CalSyncOp) {
        val result = try {
            when (op) {
                is CalSyncOp.EnsureRegistered -> findOrCreateLocalCalendarId(context)
                is CalSyncOp.Upsert -> applyUpsert(context, op)
                is CalSyncOp.Delete -> if (applyDelete(context, op.calEventId)) op.calEventId else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "process failed for $op", e)
            null
        }
        onOpDone(op, result)
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val op = awaitingPermissionFor
        awaitingPermissionFor = null
        if (op != null) {
            if (grants.values.all { it }) process(op) else onOpDone(op, null)
        }
    }

    // Drains the queue one item at a time: each time the head of the queue
    // changes (the caller removes a finished item in onOpDone), this fires
    // again for the new head - a self-advancing worklist.
    LaunchedEffect(queue.firstOrNull()) {
        val op = queue.firstOrNull() ?: return@LaunchedEffect
        if (hasCalendarPermissions(context)) {
            process(op)
        } else {
            awaitingPermissionFor = op
            permLauncher.launch(CALENDAR_PERMISSIONS)
        }
    }
}
