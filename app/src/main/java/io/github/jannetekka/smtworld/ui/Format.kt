package io.github.jannetekka.smtworld.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Format {
    fun price(px: Double?): String = when {
        px == null -> "—"
        px >= 100 -> "$" + String.format(Locale.US, "%,.2f", px)
        px >= 1 -> "$" + String.format(Locale.US, "%.4f", px)
        else -> "$" + String.format(Locale.US, "%.5f", px)
    }

    fun pct(v: Double): String = String.format(Locale.US, "%+.2f%%", v)

    fun time(epochSec: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochSec * 1000))

    fun dayTime(epochSec: Long): String = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(epochSec * 1000))

    fun shortAddr(a: String): String = if (a.length > 10) a.take(4) + "…" + a.takeLast(4) else a

    fun age(hours: Double): String = when {
        hours < 1 -> "${(hours * 60).toInt()} min ago"
        hours < 48 -> "${hours.toInt()} h ago"
        else -> "${(hours / 24).toInt()} days ago"
    }
}
