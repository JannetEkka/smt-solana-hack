package io.github.jannetekka.smtworld

import io.github.jannetekka.smtworld.clockin.ClockInCall
import io.github.jannetekka.smtworld.clockin.Dir
import io.github.jannetekka.smtworld.clockin.Grading
import io.github.jannetekka.smtworld.clockin.Lean
import io.github.jannetekka.smtworld.clockin.OnChainCall
import io.github.jannetekka.smtworld.notify.Reminders
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class RemindersTest {
    private val ist = TimeZone.getTimeZone("Asia/Kolkata")
    private val now = 1791819000L                                    // 2026-10-12 21:00 IST (15:30 UTC)

    private fun call(sig: String, blockTime: Long) =
        OnChainCall(sig, blockTime, ClockInCall("BTC", Dir.UP, 100.0, "binance", Lean.DOWN, 24, "WAIT"))

    @Test fun eachGradedCallNotifiesOnceWithinADay() {
        val dueAnHourAgo = call("a", now - 5 * 3600)                // due 1 h ago, graded
        val dueTwoDaysAgo = call("b", now - 52 * 3600)              // graded long ago: never replayed
        val notGraded = call("c", now - 5 * 3600)
        val grades = listOf("a", "b").associateWith { Grading.grade(dueAnHourAgo.call, 100.0, 99.0, "binance") }
        val calls = listOf(dueAnHourAgo, dueTwoDaysAgo, notGraded)
        assertEquals(listOf("a"), Reminders.toNotify(calls, grades, emptyList(), now).map { it.signature })
        assertEquals(emptyList<String>(), Reminders.toNotify(calls, grades, listOf("a"), now).map { it.signature })
        // Another player's graded call isn't in this wallet's calls, so it never notifies.
        assertEquals(emptyList<String>(), Reminders.toNotify(listOf(notGraded), grades, emptyList(), now).map { it.signature })
    }

    @Test fun theNudgeIsAlwaysTheNextNineOClock() {
        val nine = 1791775800L * 1000                               // 2026-10-12 09:00 IST
        assertEquals(nine, Reminders.nextAt(nine - 60_000, 9, ist))          // 08:59 → 09:00 today
        assertEquals(nine + 86_400_000, Reminders.nextAt(nine, 9, ist))      // 09:00 sharp → tomorrow
        assertEquals(nine + 86_400_000, Reminders.nextAt(now * 1000, 9, ist)) // 21:00 → tomorrow 09:00
    }
}
