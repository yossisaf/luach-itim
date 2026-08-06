package com.luachitim.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.widget.RemoteViews
import com.luachitim.MainActivity
import com.luachitim.R
import com.luachitim.data.LuachRepository
import com.luachitim.util.initAndroidPlatform
import java.io.File
import java.util.concurrent.Executors

/**
 * Home-screen widget that renders the actual PDF page ("עמוד ב'") of the
 * currently active luach for the current week - the same page shown in the
 * app's own main view, not just a text summary of it. Rendering is done off
 * the main thread via goAsync(), since decoding a PDF page is too heavy to
 * run inline inside onUpdate/onReceive.
 *
 * Unlike LuachWidgetProvider (the small text widget), this one has no live
 * data feed while the app is closed - it refreshes on its own periodic
 * schedule (see updatePeriodMillis in luach_page_widget_info.xml), at
 * midnight (via MidnightIconReceiver's broadcast handling, same as the text
 * widget), and whenever requestUpdate() below is called (e.g. after
 * switching the active luach or navigating to a different week while the
 * app is open).
 */
class LuachPageWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id -> updateOne(context, appWidgetManager, id) }
    }

    private fun updateOne(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val pending = goAsync()
        executor.execute {
            try {
                renderAndPush(context, appWidgetManager, appWidgetId)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                pending.finish()
            }
        }
    }

    private fun renderAndPush(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        initAndroidPlatform(context)
        val repo = LuachRepository()
        val settings = repo.loadSettings()
        val active = repo.getActiveLuach()
        val views = RemoteViews(context.packageName, R.layout.widget_luach_page)

        val openAppIntent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, appWidgetId, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_page_image, pendingIntent)

        if (active == null) {
            appWidgetManager.updateAppWidget(appWidgetId, views)
            return
        }

        val weekIdx = repo.getCurrentWeekIndex(settings.inIsrael, active.hebrewYear)
        val info = repo.getWeekInfoForIndex(weekIdx, settings.inIsrael, active.hebrewYear)
        val pageIndex = info.pdfPageEnd - 1  // 0-based

        val file = File(active.pdfPath)
        if (!file.exists()) {
            appWidgetManager.updateAppWidget(appWidgetId, views)
            return
        }

        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            if (pageIndex < 0 || pageIndex >= renderer.pageCount) {
                appWidgetManager.updateAppWidget(appWidgetId, views)
                return
            }
            val page = renderer.openPage(pageIndex)
            // Render at a fixed, widget-friendly resolution - the actual
            // on-screen size is decided by the launcher/RemoteViews scaling.
            val targetW = 900
            val targetH = (targetW.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(AColor.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()

            views.setImageViewBitmap(R.id.widget_page_image, bmp)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    companion object {
        private val executor = Executors.newSingleThreadExecutor()

        /** Public hook so other components (e.g. after switching luach, or at
         *  midnight via MidnightIconReceiver) can force this widget to
         *  refresh immediately, instead of waiting for its own multi-hour
         *  schedule. No-op if no instance of the widget is currently placed. */
        fun requestUpdate(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, LuachPageWidgetProvider::class.java))
            if (ids.isNotEmpty()) {
                val intent = Intent(context, LuachPageWidgetProvider::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                }
                context.sendBroadcast(intent)
            }
        }
    }
}
