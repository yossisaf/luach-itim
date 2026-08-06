package com.luachitim.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luachitim.util.currentJulianDay
import com.luachitim.util.getTodayDayOfWeek
import com.luachitim.util.getTodayHebrewDisplay
import com.luachitim.util.saveFilePath

private val BgDark    = Color(0xFF0D1B2A)
private val SurfDark  = Color(0xFF1B2A3B)
private val AccentD   = Color(0xFFD8E6FF)
private val TxtDark   = Color(0xFFE8ECF2)
private val SubDark   = Color(0xFF8A94AA)

/**
 * Shown automatically when the person adds the widget to their home screen
 * (registered as its android:configure activity). Lets them pick the
 * widget's theme and text size before it's placed, with a live preview.
 */
class WidgetConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Standard widget-config boilerplate: if the person backs out without
        // tapping "הוספה", the host must treat it as cancelled and not place
        // the widget - this default result covers that until we override it below.
        setResult(RESULT_CANCELED)

        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContent {
            WidgetConfigScreen(
                onDone = { theme, textSize ->
                    saveFilePath(widgetThemeKey(appWidgetId), theme.storageKey)
                    saveFilePath(widgetTextSizeKey(appWidgetId), textSize.storageKey)

                    val appWidgetManager = AppWidgetManager.getInstance(this)
                    LuachWidgetProvider.updateWidgetView(this, appWidgetManager, appWidgetId)

                    val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    setResult(RESULT_OK, resultValue)
                    finish()
                }
            )
        }
    }
}

@Composable
private fun WidgetConfigScreen(onDone: (WidgetTheme, WidgetTextSize) -> Unit) {
    var theme by remember { mutableStateOf(WidgetTheme.DARK) }
    var textSize by remember { mutableStateOf(WidgetTextSize.MEDIUM) }

    val previewIsDark = when (theme) { WidgetTheme.LIGHT -> false; else -> true }
    val previewBg = if (previewIsDark) SurfDark else Color(0xFFE8E0D0)
    val previewAccent = if (previewIsDark) AccentD else Color(0xFF1A2744)
    val previewSub = if (previewIsDark) SubDark else Color(0xFF5A6070)

    val todayHd = remember { getTodayHebrewDisplay() }
    val dowIdx = remember { ((currentJulianDay() + 1) % 7).toInt() }
    val dowNames = listOf("ראשון", "שני", "שלישי", "רביעי", "חמישי", "שישי", "שבת")

    Surface(color = BgDark, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text("הגדרות הוידג'ט", style = TextStyle(fontSize = 20.sp,
                fontWeight = FontWeight.Bold, color = AccentD))
            Text("פרשת השבוע והתאריך העברי של היום",
                style = TextStyle(fontSize = 12.5.sp, color = SubDark))

            // ── Live preview ────────────────────────────────────────────
            Box(
                Modifier.fillMaxWidth().height(78.dp)
                    .background(previewBg, RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("פרשת השבוע", style = TextStyle(
                        fontSize = textSize.parashaSp.sp, fontWeight = FontWeight.Bold,
                        color = previewAccent, textAlign = TextAlign.Center))
                    Spacer(Modifier.height(4.dp))
                    Text("יום ${dowNames.getOrElse(dowIdx) { "" }}, ${todayHd.day}",
                        style = TextStyle(fontSize = textSize.dateSp.sp, color = previewSub,
                            textAlign = TextAlign.Center))
                }
            }

            // ── Theme picker ─────────────────────────────────────────────
            Text("ערכת נושא", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TxtDark))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WidgetTheme.values().forEach { opt ->
                    OptionChip(label = opt.label, selected = opt == theme, modifier = Modifier.weight(1f)) {
                        theme = opt
                    }
                }
            }

            // ── Text size picker ────────────────────────────────────────
            Text("גודל טקסט", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TxtDark))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WidgetTextSize.values().forEach { opt ->
                    OptionChip(label = opt.label, selected = opt == textSize, modifier = Modifier.weight(1f)) {
                        textSize = opt
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            Button(
                onClick = { onDone(theme, textSize) },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentD, contentColor = BgDark)
            ) {
                Text("הוספת הוידג'ט", style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold))
            }
        }
    }
}

@Composable
private fun OptionChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .background(if (selected) AccentD else SurfDark, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = TextStyle(
            fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) BgDark else TxtDark
        ))
    }
}
