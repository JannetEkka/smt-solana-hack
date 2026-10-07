package io.github.jannetekka.smtworld.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.jannetekka.smtworld.MainActivity
import io.github.jannetekka.smtworld.R
import io.github.jannetekka.smtworld.clockin.Outcome
import io.github.jannetekka.smtworld.clockin.Streak
import io.github.jannetekka.smtworld.data.Repo
import java.util.Calendar
import java.util.concurrent.TimeUnit

/** The daily hook: a morning nudge to clock in, and a ping when a call has been graded. */
object Reminders {
    private const val CHANNEL_DAILY = "daily"
    private const val CHANNEL_GRADES = "grades"
    const val REMINDER_HOUR = 9

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_DAILY, "Daily Clock In", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_GRADES, "Graded calls", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun canNotify(ctx: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    fun scheduleDaily(ctx: Context) {
        val now = Calendar.getInstance()
        val next = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, REMINDER_HOUR); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
            if (!after(now)) add(Calendar.DAY_OF_MONTH, 1)
        }
        val req = PeriodicWorkRequestBuilder<DailyReminderWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(next.timeInMillis - now.timeInMillis, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("daily-clock-in", ExistingPeriodicWorkPolicy.KEEP, req)
        Log.i(Repo.TAG, "[REMINDER] daily nudge scheduled for ${next.time}")
    }

    fun cancelDaily(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork("daily-clock-in")
        Log.i(Repo.TAG, "[REMINDER] daily nudge off")
    }

    fun scheduleGrade(ctx: Context, dueAtSec: Long) {
        val delayMs = (dueAtSec + 120) * 1000 - System.currentTimeMillis()
        val req = OneTimeWorkRequestBuilder<GradeWorker>()
            .setInitialDelay(delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("grade-$dueAtSec", ExistingWorkPolicy.KEEP, req)
        Log.i(Repo.TAG, "[REMINDER] grade check scheduled in ${delayMs / 60000} min")
    }

    fun post(ctx: Context, channel: String, id: Int, title: String, text: String) {
        if (!canNotify(ctx)) {
            Log.w(Repo.TAG, "[REMINDER] notifications off; not shown: $title")
            return
        }
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_clock)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(id, n) } catch (e: SecurityException) {
            Log.w(Repo.TAG, "[REMINDER] notify refused: ${e.message}")
        }
    }

    fun postDaily(ctx: Context, title: String, text: String) = post(ctx, CHANNEL_DAILY, 1, title, text)
    fun postGrade(ctx: Context, id: Int, title: String, text: String) = post(ctx, CHANNEL_GRADES, id, title, text)
}

class DailyReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val repo = Repo(applicationContext)
        io.github.jannetekka.smtworld.widget.StreakWidget.refresh(applicationContext)   // the day has turned over
        if (!repo.store.remindersOn) return Result.success()
        val days = repo.store.calls.map { Streak.localDay(it.blockTime) }.toSet()
        val today = Streak.localDay(System.currentTimeMillis() / 1000)
        if (today in days) return Result.success()
        val streak = Streak.current(days, today)
        val text = if (streak > 0) "Your $streak-day streak ends tonight unless you make today's call."
                   else "Make today's call before you see SMT's."
        Reminders.postDaily(applicationContext, "Time to clock in", text)
        return Result.success()
    }
}

class GradeWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val repo = Repo(applicationContext)
        val addr = repo.store.address
        if (addr != null) runCatching { repo.syncHistory(addr) }
        val graded = repo.gradeDue()
        io.github.jannetekka.smtworld.widget.StreakWidget.refresh(applicationContext)
        // Grades are always saved; the notification respects the reminders switch.
        // Only this wallet's own calls notify; other players' calls are graded for the leaderboard.
        val mine = repo.store.calls.map { it.signature }.toSet()
        if (repo.store.remindersOn) for ((c, g) in graded.filter { it.first.signature in mine }) {
            val you = when (g.you) { Outcome.RIGHT -> "you were right"; Outcome.WRONG -> "you were wrong"; else -> "no move" }
            val smt = when (g.smt) { Outcome.RIGHT -> "SMT was right"; Outcome.WRONG -> "SMT was wrong"; else -> "SMT sat it out" }
            Reminders.postGrade(applicationContext, c.signature.hashCode(),
                "${c.call.coin} ${c.call.you.name} call graded",
                "${"%+.2f".format(g.movePct)}% in ${c.call.horizonHours}h: $you, $smt.")
        }
        // Retry a few times for a call that came due in the last day and couldn't be priced yet
        // (an API down for a while); after that the app grades it on its next refresh.
        val now = System.currentTimeMillis() / 1000
        val grades = repo.store.grades
        val stuck = repo.store.calls.any { it.signature !in grades && it.dueAt + 600 < now && now - it.dueAt < 86_400 }
        return if (stuck && runAttemptCount < 5) Result.retry() else Result.success()
    }
}
