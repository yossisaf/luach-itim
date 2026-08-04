package com.luachitim.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Renders the "daily-changing home-screen shortcut icon" settings row inside
 * SettingsScreen (see LuachApp.kt). Real, static app icons cannot be redrawn
 * at runtime on Android - the actual implementation instead offers to pin a
 * separate home-screen shortcut whose OWN icon is regenerated every day
 * (day-of-week / big Hebrew day / month). No equivalent concept exists on
 * Desktop, so the desktop actual is a no-op that renders nothing - the same
 * pattern already used by DeviceCalendarSync/DeviceCalendarImport for
 * platform-only capabilities.
 */
@Composable
expect fun DailyIconSettingRow(gold: Color, sub: Color)
