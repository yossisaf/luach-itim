package com.luachitim.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luachitim.widget.isDailyIconEnabled
import com.luachitim.widget.setDailyIconEnabled

/**
 * Toggle that requests pinning (or just re-syncs the enabled flag for) the
 * "daily icon" home-screen shortcut - see DynamicIconShortcut.kt for the
 * actual bitmap-rendering / ShortcutManagerCompat wiring. Pinning a shortcut
 * always shows the OS's own confirmation UI, so this switch just requests
 * it; the person still explicitly confirms placement on their home screen.
 */
@Composable
actual fun DailyIconSettingRow(gold: Color, sub: Color) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(isDailyIconEnabled()) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "קיצור-דרך עם תאריך יומי",
                    style = TextStyle(fontSize = 14.sp, color = sub, fontWeight = FontWeight.Medium)
                )
                Text(
                    "מוסיף למסך הבית קיצור-דרך שהאייקון שלו מציג יום בשבוע, תאריך עברי גדול, וחודש - ומתעדכן כל יום. " +
                        "אנדרואיד לא מאפשר לשנות את אייקון האפליקציה עצמה בזמן ריצה, לכן זהו קיצור-דרך נפרד.",
                    style = TextStyle(fontSize = 11.sp, color = sub.copy(alpha = 0.7f))
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { checked ->
                    enabled = checked
                    setDailyIconEnabled(context, checked)
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = gold,
                    checkedTrackColor = gold.copy(alpha = 0.4f)
                )
            )
        }
    }
}
