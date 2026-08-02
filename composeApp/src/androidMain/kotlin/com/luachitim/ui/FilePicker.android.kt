package com.luachitim.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun FilePicker(
    show: Boolean,
    fileExtensions: List<String>,
    onError: () -> Unit,
    onFileSelected: (String?) -> Unit
) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri: Uri? = if (result.resultCode == Activity.RESULT_OK) result.data?.data else null
        if (uri != null) {
            val copied = copyUriToInternal(context, uri)
            if (copied == null) onError() else onFileSelected(copied)
        } else {
            onFileSelected(null)   // plain cancel - nothing went wrong, stay silent
        }
    }

    LaunchedEffect(show) {
        if (show) {
            launcher.launch(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/pdf"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }
            )
        }
    }
}

private fun copyUriToInternal(ctx: android.content.Context, uri: Uri): String? = try {
    val input = ctx.contentResolver.openInputStream(uri) ?: return null
    // Unique filename per PDF — prevents overwriting
    val ts     = System.currentTimeMillis()
    val output = java.io.File(ctx.filesDir, "luach_$ts.pdf")
    output.outputStream().use { input.copyTo(it) }
    input.close()
    output.absolutePath
} catch (e: Exception) { e.printStackTrace(); null }
