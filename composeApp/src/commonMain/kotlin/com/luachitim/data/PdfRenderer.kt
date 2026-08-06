package com.luachitim.data

import androidx.compose.ui.graphics.ImageBitmap

expect class PdfRenderer(filePath: String) {
    fun renderPage(pageIndex: Int, width: Int, height: Int): ImageBitmap?
    fun getPageCount(): Int
    fun getPageSize(pageIndex: Int): Pair<Float, Float>
    fun close()
}
