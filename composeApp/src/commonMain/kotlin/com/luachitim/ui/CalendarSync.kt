package com.luachitim.ui

import androidx.compose.runtime.Composable

/** One pending change to mirror into the device's own local calendar. */
sealed class CalSyncOp {
    /**
     * Makes sure the app's own on-device calendar row exists right away -
     * so "לוח עתים לבינה" shows up as a calendar in Google Calendar/Samsung
     * Calendar/etc. immediately after granting permission, even before the
     * person has added a single diary event.
     */
    object EnsureRegistered : CalSyncOp()

    /**
     * Create ([calEventId] == null) or update (!= null) an all-day event for
     * a diary entry identified by ([jd], [idx]).
     */
    data class Upsert(
        val jd: Long, val idx: Int, val calEventId: Long?,
        val year: Int, val month: Int, val day: Int,
        val title: String, val note: String
    ) : CalSyncOp()

    /** Remove a previously-synced event from the device calendar. */
    data class Delete(val jd: Long, val idx: Int, val calEventId: Long) : CalSyncOp()
}

/**
 * Keeps the phone's own calendar automatically in step with the diary -
 * exactly the way any calendar app keeps its events in step with its own
 * storage. Every diary event is mirrored into a private, account-free
 * ("local") calendar row that lives entirely in the on-device Calendar
 * Provider database - the same store every calendar app on the phone reads
 * from, so the event simply shows up there too. Nothing here ever touches
 * the network: not the sync itself, and not even the one-time system
 * permission prompt, which is a plain on-device dialog. This works fully
 * offline, including on a phone that has never been online at all.
 *
 * [queue] is drained one item at a time (first-in, first-out) as the caller
 * removes finished items from it; [onOpDone] reports the outcome for each -
 * for [CalSyncOp.Upsert] the resulting device-calendar event ID (or null on
 * failure/denied permission), for [CalSyncOp.Delete] simply whether it
 * succeeded. On platforms with no OS calendar API to hook into (desktop)
 * this is a safe no-op that reports every item as "not applied".
 */
@Composable
expect fun DeviceCalendarSync(
    queue: List<CalSyncOp>,
    onOpDone: (CalSyncOp, Long?) -> Unit
)

/** A single (non-recurring) event read from one of the device's OTHER
 *  calendars - the source side of inbound sync. [eventId] is that event's own
 *  row ID in the device's Calendar Provider, kept so re-scans can recognize
 *  it again and update the diary copy in place instead of duplicating it. */
data class ExternalCalEvent(
    val eventId: Long, val year: Int, val month: Int, val day: Int,
    val title: String, val note: String
)

/**
 * Reads events from every calendar on the device EXCEPT this app's own
 * mirror calendar - i.e. events the person created in Google Calendar,
 * Samsung Calendar, etc. - so they can be brought into the diary
 * automatically, the way any calendar app picks up what's already on the
 * phone. Purely local: the Calendar Provider query never touches the
 * network. Only single, dated events are read (not recurring series - a
 * daily/weekly recurring event would otherwise flood the diary with one
 * entry per occurrence), within roughly the past year through two years
 * ahead. Call with a bumped [trigger] to re-scan (e.g. once per app launch);
 * [onResult] receives the full current list each time, or an empty list on
 * platforms with no calendar to read (desktop) or without permission yet.
 */
@Composable
expect fun DeviceCalendarImport(
    trigger: Int,
    onResult: (List<ExternalCalEvent>) -> Unit
)
