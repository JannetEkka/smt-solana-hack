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
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.jannetekka.smtworld.MainActivity
import io.github.jannetekka.smtworld.R
import io.github.jannetekka.smtworld.clockin.Grade
import io.github.jannetekka.smtworld.clockin.OnChainCall
import io.github.jannetekka.smtworld.clockin.Outcome
import io.github.jannetekka.smtworld.clockin.Streak
import io.github.jannetekka.smtworld.data.Repo
import java.util.Calendar
import java.util.TimeZone
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

    /** The next [hour]:00 strictly after [nowMs] in [tz]. */
    fun nextAt(nowMs: Long, hour: Int, tz: TimeZone): Long {
        val now = Calendar.getInstance(tz).apply { timeInMillis = nowMs }
        return (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (!after(now)) add(Calendar.DAY_OF_MONTH, 1)
        }.timeInMillis
    }

    /**
     * The 9:00 nudge as a one-time job that re-schedules itself for the next 9:00 when it runs
     * ([replace] = true from the worker; app start keeps a pending one). It used to be a 24-hour
     * periodic job, and those count each period from the last run, so a run the phone deferred
     * pushed every later one back: Jannet's came at 09:15, ~09:47, ~09:57, ~11:47 (Oct 9-12).
     */
    fun scheduleDaily(ctx: Context, replace: Boolean = false) {
        val nowMs = System.currentTimeMillis()
        val next = nextAt(nowMs, REMINDER_HOUR, TimeZone.getDefault())
        val req = OneTimeWorkRequestBuilder<DailyReminderWorker>()
            .setInitialDelay(next - nowMs, TimeUnit.MILLISECONDS)
            .build()
        val wm = WorkManager.getInstance(ctx)
        wm.cancelUniqueWork(OLD_DAILY)
        wm.enqueueUniqueWork(DAILY, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, req)
        Log.i(Repo.TAG, "[REMINDER] daily nudge scheduled for ${java.util.Date(next)}")
    }

    fun cancelDaily(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(DAILY)
        WorkManager.getInstance(ctx).cancelUniqueWork(OLD_DAILY)
        Log.i(Repo.TAG, "[REMINDER] daily nudge off")
    }

    private const val DAILY = "daily-9am"
    private const val OLD_DAILY = "daily-clock-in"     // the drifting periodic job of 0.1-0.3.2
    private const val NOTIFY_WINDOW_S = 86_400L

    /** Own calls that are graded, not yet notified, and came due within the last day. */
    fun toNotify(calls: List<OnChainCall>, grades: Map<String, Grade>, notified: Collection<String>, nowSec: Long): List<OnChainCall> {
        val done = notified.toSet()
        return calls.filter { it.signature in grades && it.signature !in done && it.dueAt <= nowSec && nowSec - it.dueAt < NOTIFY_WINDOW_S }
    }

    /**
     * One "graded" notification per own call, whoever graded it. The background job and the open
     * app both grade due calls; until 0.3.3 only the job notified, and only for calls it graded
     * itself, so a call the app graded first never notified (Jannet's BTC call, 12 Oct 21:05).
     * The notification id is the transaction's, so the two racing can't post it twice.
     */
    fun notifyGraded(ctx: Context, repo: Repo) {
        val store = repo.store
        val grades = store.grades
        val fresh = synchronized(Repo.LOCK) {
            toNotify(store.calls, grades, store.notified, System.currentTimeMillis() / 1000)
                .also { if (it.isNotEmpty()) store.notified = store.notified + it.map { c -> c.signature } }
        }
        if (!store.remindersOn) return          // marked anyway, so switching reminders on can't replay old grades
        for (c in fresh) {
            val g = grades.getValue(c.signature)
            val you = when (g.you) { Outcome.RIGHT -> "you were right"; Outcome.WRONG -> "you were wrong"; else -> "no move" }
            val smt = when (g.smt) { Outcome.RIGHT -> "SMT was right"; Outcome.WRONG -> "SMT was wrong"; else -> "SMT sat it out" }
            postGrade(ctx, c.signature.hashCode(), "${c.call.coin} ${c.call.you.name} call graded",
                "${"%+.2f".format(g.movePct)}% in ${c.call.horizonHours}h: $you, $smt.")
            Log.i(Repo.TAG, "[REMINDER] graded ${c.call.coin} ${c.signature.take(8)} notified")
        }
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
        if (today !in days) {
            val streak = Streak.current(days, today)
            val text = if (streak > 0) "Your $streak-day streak ends tonight unless you make today's call."
                       else "Make today's call before you see SMT's."
            Reminders.postDaily(applicationContext, "Time to clock in", text)
        }
        // Last: the next 9:00. REPLACE ends this (already finished) run's record, nothing else.
        Reminders.scheduleDaily(applicationContext, replace = true)
        return Result.success()
    }
}

class GradeWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val repo = Repo(applicationContext)
        val addr = repo.store.address
        if (addr != null) runCatching { repo.syncHistory(addr) }
        repo.gradeDue()
        io.github.jannetekka.smtworld.widget.StreakWidget.refresh(applicationContext)
        // Grades are always saved. Only this wallet's own calls notify (other players' are graded
        // for the leaderboard), once each, whether this job or the open app graded them.
        Reminders.notifyGraded(applicationContext, repo)
        // Retry a few times for a call that came due in the last day and couldn't be priced yet
        // (an API down for a while); after that the app grades it on its next refresh.
        val now = System.currentTimeMillis() / 1000
        val grades = repo.store.grades
        val stuck = repo.store.calls.any { it.signature !in grades && it.dueAt + 600 < now && now - it.dueAt < 86_400 }
        return if (stuck && runAttemptCount < 5) Result.retry() else Result.success()
    }
}
