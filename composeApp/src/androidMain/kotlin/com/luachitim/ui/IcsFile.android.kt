package com.luachitim.ui

import android.app.Activity
import android.content.Intent
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File

private const val TAG = "IcsFile"

@Composable
actual fun ExportIcsFile(show: Boolean, fileName: String, icsContent: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { onDone() }

    LaunchedEffect(show) {
        if (!show) return@LaunchedEffect
        try {
            // A dedicated "exports" subfolder is the only path exposed by the
            // FileProvider (see file_paths.xml) - nothing else on disk is reachable.
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val file = File(dir, fileName)
            file.writeText(icsContent, Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/calendar"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            launcher.launch(Intent.createChooser(sendIntent, "שיתוף / שמירת קובץ יומן"))
        } catch (e: Exception) {
            Log.e(TAG, "ICS export failed", e)
            onDone()
        }
    }
}

@Composable
actual fun ImportIcsFile(show: Boolean, onIcsSelected: (String?) -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = if (result.resultCode == Activity.RESULT_OK) result.data?.data else null
        val text = uri?.let {
            try {
                context.contentResolver.openInputStream(it)
                    ?.bufferedReader(Charsets.UTF_8)?.use { r -> r.readText() }
            } catch (e: Exception) {
                Log.e(TAG, "ICS import read failed", e)
                null
            }
        }
        onIcsSelected(text)
    }

    LaunchedEffect(show) {
        if (show) {
            launcher.launch(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    // .ics has no single universally-registered MIME type across devices,
                    // so accept the common possibilities rather than risk an empty picker.
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES,
                        arrayOf("text/calendar", "text/plain", "application/octet-stream"))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
        }
    }
}
