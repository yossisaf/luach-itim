package com.luachitim.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** No home-screen / pinned-shortcut concept on Desktop - safe no-op. */
@Composable
actual fun DailyIconSettingRow(gold: Color, sub: Color) {
    // Intentionally empty on Desktop.
}
