package com.luachitim.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * No native, permission-free "device calendar" exists on desktop the way it
 * does on Android - this is a deliberate no-op that reports every queued
 * item as "not applied", so the diary can keep working without it.
 */
@Composable
actual fun DeviceCalendarImport(
    trigger: Int,
    onResult: (List<ExternalCalEvent>) -> Unit
) {
    LaunchedEffect(trigger) {
        if (trigger > 0) onResult(emptyList())
    }
}

@Composable
actual fun DeviceCalendarSync(
    queue: List<CalSyncOp>,
    onOpDone: (CalSyncOp, Long?) -> Unit
) {
    LaunchedEffect(queue.firstOrNull()) {
        val op = queue.firstOrNull() ?: return@LaunchedEffect
        onOpDone(op, null)
    }
}
