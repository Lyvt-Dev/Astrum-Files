package com.lyvt.files.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.lyvt.files.MainActivity
import com.lyvt.files.R
import com.lyvt.files.core.common.Formatters
import com.lyvt.files.core.storage.StorageVolumes

private fun open(ctx: Context, route: String, code: Int): PendingIntent =
    PendingIntent.getActivity(ctx, code, Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, route)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

/** Shows real StatFs numbers for the primary volume. Updated by the system (every 6 h) and by the snapshot worker. */
class StorageWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val v = StorageVolumes(context).volumes().firstOrNull { it.isPrimary }
        ids.forEach { id ->
            val rv = RemoteViews(context.packageName, R.layout.widget_storage)
            if (v != null) {
                val pct = (v.usedFraction * 100).toInt()
                rv.setTextViewText(R.id.w_percent, "$pct%")
                rv.setTextViewText(R.id.w_detail, "${Formatters.size(v.usedBytes)} / ${Formatters.size(v.totalBytes)} · ${Formatters.size(v.freeBytes)} free")
                rv.setProgressBar(R.id.w_bar, 100, pct, false)
            }
            rv.setOnClickPendingIntent(R.id.w_root, open(context, "analytics", 1))
            rv.setOnClickPendingIntent(R.id.w_clean, open(context, "cleaner", 2))
            manager.updateAppWidget(id, rv)
        }
    }

    companion object {
        fun refreshAll(ctx: Context) {
            val m = AppWidgetManager.getInstance(ctx)
            val ids = m.getAppWidgetIds(ComponentName(ctx, StorageWidget::class.java))
            if (ids.isNotEmpty()) StorageWidget().onUpdate(ctx, m, ids)
        }
    }
}

class QuickWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id ->
            val rv = RemoteViews(context.packageName, R.layout.widget_quick)
            rv.setOnClickPendingIntent(R.id.q_files, open(context, "files", 10))
            rv.setOnClickPendingIntent(R.id.q_downloads, open(context, "downloads", 11))
            rv.setOnClickPendingIntent(R.id.q_cleaner, open(context, "cleaner", 12))
            rv.setOnClickPendingIntent(R.id.q_transfer, open(context, "transfer", 13))
            manager.updateAppWidget(id, rv)
        }
    }
}
