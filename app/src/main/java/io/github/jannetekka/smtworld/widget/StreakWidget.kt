package io.github.jannetekka.smtworld.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import io.github.jannetekka.smtworld.MainActivity
import io.github.jannetekka.smtworld.R
import io.github.jannetekka.smtworld.clockin.Streak
import io.github.jannetekka.smtworld.data.Store

/**
 * The streak on the home screen. Reads the local cache only (no network), so it is instant and
 * free; the app refreshes it after every Clock In and history sync, and Android every 30 minutes
 * so the day can turn over.
 */
class StreakWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        manager.updateAppWidget(ids, views(context))
    }

    companion object {
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, StreakWidget::class.java))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(context))
        }

        private fun views(context: Context): RemoteViews {
            val store = Store(context)
            val days = store.calls.map { Streak.localDay(it.blockTime) }.toSet()
            val today = Streak.localDay(System.currentTimeMillis() / 1000)
            val streak = Streak.current(days, today)
            val v = RemoteViews(context.packageName, R.layout.widget_streak)
            v.setTextViewText(R.id.widget_streak, "🔥 $streak")
            v.setTextViewText(
                R.id.widget_status,
                when {
                    store.address == null -> "Tap to connect a wallet"
                    today in days -> "Clocked in today ✓"
                    streak > 0 -> "Not yet today. Tap to keep your $streak-day streak"
                    else -> "Tap to make today's call"
                },
            )
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            v.setOnClickPendingIntent(R.id.widget_root, open)
            return v
        }
    }
}
