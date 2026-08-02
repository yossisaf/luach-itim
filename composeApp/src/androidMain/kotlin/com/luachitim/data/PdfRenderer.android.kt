package com.luachitim.data

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer as AndroidPdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

actual class PdfRenderer actual constructor(private val filePath: String) {

    private var pfd: ParcelFileDescriptor? = null
    private var renderer: AndroidPdfRenderer? = null

    init {
        try {
            val f = File(filePath)
            if (f.exists()) {
                pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
                renderer = AndroidPdfRenderer(pfd!!)
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    actual fun renderPage(pageIndex: Int, width: Int, height: Int): ImageBitmap? {
        val r = renderer ?: return null
        if (pageIndex < 0 || pageIndex >= r.pageCount) return null
        return try {
            val page = r.openPage(pageIndex)
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(android.graphics.Color.WHITE)
            page.render(bmp, null, null, AndroidPdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            bmp.asImageBitmap()
        } catch (e: Exception) { e.printStackTrace(); null }
    }

    actual fun getPageCount(): Int = renderer?.pageCount ?: 0

    actual fun getPageSize(pageIndex: Int): Pair<Float, Float> {
        val r = renderer ?: return Pair(595f, 842f)
        if (pageIndex < 0 || pageIndex >= r.pageCount) return Pair(595f, 842f)
        return try {
            val p = r.openPage(pageIndex)
            val result = Pair(p.width.toFloat(), p.height.toFloat())
            p.close(); result
        } catch (e: Exception) { Pair(595f, 842f) }
    }

    actual fun close() {
        try { renderer?.close(); pfd?.close() } catch (e: Exception) { e.printStackTrace() }
    }
}
