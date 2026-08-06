package com.luachitim.ui

import androidx.compose.runtime.Composable

/**
 * Presents a platform-native "save/share" flow for an already-generated
 * .ics document. No server, no background sync - this is a one-shot export:
 * the user picks where the file goes (share sheet on Android, save dialog
 * on desktop) each time they tap "export".
 */
@Composable
expect fun ExportIcsFile(show: Boolean, fileName: String, icsContent: String, onDone: () -> Unit)

/**
 * Presents a platform-native "open file" flow to pick an .ics file to
 * import, returning its raw text content (or null if the user cancelled
 * or reading failed).
 */
@Composable
expect fun ImportIcsFile(show: Boolean, onIcsSelected: (String?) -> Unit)
