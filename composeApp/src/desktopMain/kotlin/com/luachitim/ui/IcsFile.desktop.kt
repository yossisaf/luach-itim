package com.luachitim.ui

import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
actual fun ExportIcsFile(show: Boolean, fileName: String, icsContent: String, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(show) {
        if (!show) return@LaunchedEffect
        scope.launch(Dispatchers.IO) {
            val latch = java.util.concurrent.CountDownLatch(1)
            SwingUtilities.invokeLater {
                try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) } catch (_: Exception) {}
                val chooser = JFileChooser().apply {
                    dialogTitle = "שמירת קובץ יומן (ICS)"
                    fileSelectionMode = JFileChooser.FILES_ONLY
                    isAcceptAllFileFilterUsed = false
                    addChoosableFileFilter(FileNameExtensionFilter("iCalendar (*.ics)", "ics"))
                    selectedFile = File(fileName)
                }
                if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                    try {
                        var target = chooser.selectedFile
                        if (!target.name.endsWith(".ics", ignoreCase = true)) {
                            target = File(target.parentFile, target.name + ".ics")
                        }
                        target.writeText(icsContent, Charsets.UTF_8)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                latch.countDown()
            }
            latch.await()
            onDone()
        }
    }
}

@Composable
actual fun ImportIcsFile(show: Boolean, onIcsSelected: (String?) -> Unit) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(show) {
        if (!show) return@LaunchedEffect
        scope.launch(Dispatchers.IO) {
            var result: String? = null
            val latch = java.util.concurrent.CountDownLatch(1)
            SwingUtilities.invokeLater {
                try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) } catch (_: Exception) {}
                val chooser = JFileChooser().apply {
                    dialogTitle = "יבוא קובץ יומן (ICS)"
                    fileSelectionMode = JFileChooser.FILES_ONLY
                    isAcceptAllFileFilterUsed = false
                    addChoosableFileFilter(FileNameExtensionFilter("iCalendar (*.ics)", "ics"))
                }
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    result = try {
                        chooser.selectedFile.readText(Charsets.UTF_8)
                    } catch (e: Exception) {
                        e.printStackTrace(); null
                    }
                }
                latch.countDown()
            }
            latch.await()
            onIcsSelected(result)
        }
    }
}
