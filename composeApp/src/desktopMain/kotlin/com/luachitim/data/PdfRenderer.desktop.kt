package com.luachitim.data

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.rendering.ImageType
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File

actual class PdfRenderer actual constructor(private val filePath: String) {

    private var doc: PDDocument? = null
    private var renderer: PDFRenderer? = null

    init {
        try {
            // PDFBox 3.x: use Loader.loadPDF() instead of PDDocument.load()
            doc      = Loader.loadPDF(File(filePath))
            renderer = PDFRenderer(doc)
        } catch (e: Exception) { e.printStackTrace() }
    }

    actual fun renderPage(pageIndex: Int, width: Int, height: Int): ImageBitmap? {
        val r = renderer ?: return null
        val d = doc      ?: return null
        if (pageIndex < 0 || pageIndex >= d.numberOfPages) return null
        return try {
            val page  = d.getPage(pageIndex)
            val rotation = ((page.rotation % 360) + 360) % 360
            // Use the ROTATION-CORRECTED width (matches getPageSize()) so the
            // DPI we ask PDFBox to render at lines up with the `width` the
            // caller expects for the already-upright, rotation-baked image.
            val pageW = if (rotation == 90 || rotation == 270)
                page.mediaBox.height else page.mediaBox.width
            val dpi   = (width / pageW * 72f).coerceIn(72f, 300f)
            val raw: BufferedImage = r.renderImageWithDPI(pageIndex, dpi, ImageType.RGB)

            val scaled = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            val g = scaled.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.setRenderingHint(RenderingHints.KEY_RENDERING,     RenderingHints.VALUE_RENDER_QUALITY)
            g.drawImage(raw, 0, 0, width, height, null)
            g.dispose()
            scaled.toComposeImageBitmap()
        } catch (e: Exception) { e.printStackTrace(); null }
    }

    actual fun getPageCount(): Int = doc?.numberOfPages ?: 0

    actual fun getPageSize(pageIndex: Int): Pair<Float, Float> {
        val d = doc ?: return Pair(595f, 842f)
        if (pageIndex < 0 || pageIndex >= d.numberOfPages) return Pair(595f, 842f)
        return try {
            val p = d.getPage(pageIndex)
            val rawW = p.mediaBox.width
            val rawH = p.mediaBox.height
            // IMPORTANT: renderImageWithDPI() already bakes the page's /Rotate
            // value into the output bitmap (it renders it upright). mediaBox
            // width/height do NOT reflect that rotation though — for a page
            // rotated 90°/270° they're swapped relative to the actual rendered
            // image. Without this correction, aspect-ratio math (and anything
            // measured as a fraction of page height, like the day-strip
            // overlay) comes out wrong ONLY on desktop, since Android's
            // PdfRenderer.Page.width/height already account for rotation.
            val rotation = ((p.rotation % 360) + 360) % 360
            if (rotation == 90 || rotation == 270) Pair(rawH, rawW) else Pair(rawW, rawH)
        } catch (e: Exception) { Pair(595f, 842f) }
    }

    actual fun close() {
        try { doc?.close() } catch (e: Exception) { e.printStackTrace() }
    }
}
