package com.luachitim.ui

import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
actual fun FilePicker(
    show: Boolean,
    fileExtensions: List<String>,
    onError: () -> Unit,
    onFileSelected: (String?) -> Unit
) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(show) {
        if (!show) return@LaunchedEffect
        scope.launch(Dispatchers.IO) {
            var result: String? = null
            val latch = java.util.concurrent.CountDownLatch(1)
            SwingUtilities.invokeLater {
                try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) } catch (_: Exception) {}
                val chooser = JFileChooser().apply {
                    dialogTitle = "בחר קובץ לוח (PDF)"
                    fileSelectionMode = JFileChooser.FILES_ONLY
                    isAcceptAllFileFilterUsed = false
                    addChoosableFileFilter(FileNameExtensionFilter("PDF (*.pdf)", "pdf"))
                }
                result = if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION)
                    chooser.selectedFile?.absolutePath else null
                latch.countDown()
            }
            latch.await()
            onFileSelected(result)
        }
    }
}
