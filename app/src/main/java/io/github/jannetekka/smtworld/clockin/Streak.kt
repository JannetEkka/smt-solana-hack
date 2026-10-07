package io.github.jannetekka.smtworld.clockin

import java.util.TimeZone

/**
 * Streaks count calendar days in the user's own time zone, rebuilt from the chain every time,
 * so reinstalling the app or switching phones never loses one.
 */
object Streak {
    /** Days since 1970-01-01 in the given zone (no java.time, which needs API 26). */
    fun localDay(epochSec: Long, tz: TimeZone = TimeZone.getDefault()): Long {
        val offsetSec = tz.getOffset(epochSec * 1000) / 1000
        return Math.floorDiv(epochSec + offsetSec, 86_400L)
    }

    /**
     * Consecutive days with at least one Clock In, ending today. If today has none yet the
     * streak is still alive from yesterday; a missed day ends it.
     */
    fun current(days: Set<Long>, today: Long): Int {
        var d = if (today in days) today else today - 1
        var n = 0
        while (d in days) { n++; d-- }
        return n
    }

    fun best(days: Set<Long>): Int {
        var best = 0
        for (d in days) {
            if (d - 1 in days) continue
            var n = 0
            while (d + n in days) n++
            if (n > best) best = n
        }
        return best
    }
}
