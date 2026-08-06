import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.luachitim.ui.LuachApp
import com.luachitim.util.loadFilePath
import com.luachitim.util.saveFilePath
import org.jetbrains.skia.Image

private fun loadAppIcon(): BitmapPainter {
    val bytes = object {}.javaClass.classLoader.getResourceAsStream("icon.png")!!.readBytes()
    return BitmapPainter(Image.makeFromEncoded(bytes).toComposeImageBitmap())
}

private fun savedFloat(key: String): Float? = loadFilePath(key)?.toFloatOrNull()

fun main() = application {
    // Restore the window to wherever it was left last time - falls back to a
    // sane default size and the platform's own default placement the very
    // first time the app runs (or if nothing was ever saved).
    val savedWidth  = savedFloat("window_width")  ?: 900f
    val savedHeight = savedFloat("window_height") ?: 1100f
    val savedX = savedFloat("window_x")
    val savedY = savedFloat("window_y")
    val initialPosition =
        if (savedX != null && savedY != null) WindowPosition(savedX.dp, savedY.dp)
        else WindowPosition.PlatformDefault

    val windowState = rememberWindowState(
        width = savedWidth.dp, height = savedHeight.dp,
        position = initialPosition
    )

    Window(
        onCloseRequest = {
            saveFilePath("window_width", windowState.size.width.value.toString())
            saveFilePath("window_height", windowState.size.height.value.toString())
            if (windowState.position.isSpecified) {
                saveFilePath("window_x", windowState.position.x.value.toString())
                saveFilePath("window_y", windowState.position.y.value.toString())
            }
            exitApplication()
        },
        title = "לוח עתים לבינה",
        state = windowState,
        icon = loadAppIcon()
    ) {
        LuachApp()
    }
}
