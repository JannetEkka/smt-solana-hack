package io.github.jannetekka.smtworld

import io.github.jannetekka.smtworld.clockin.ClockInCall
import io.github.jannetekka.smtworld.clockin.Dir
import io.github.jannetekka.smtworld.clockin.Grading
import io.github.jannetekka.smtworld.clockin.Leaderboard
import io.github.jannetekka.smtworld.clockin.Lean
import io.github.jannetekka.smtworld.clockin.OnChainCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LeaderboardTest {
    private var n = 0
    private fun entry(player: String, coin: String, you: Dir, t: Long, lean: Lean = Lean.DOWN) =
        Leaderboard.Entry(OnChainCall("sig${n++}", t, ClockInCall(coin, you, 100.0, "binance", lean, 30, "WAIT")), player)

    @Test fun ranksByHitRateOnlyAfterEnoughGradedCalls() {
        val alice = (0 until 4).map { entry("alice", "BTC", Dir.UP, 10_000L * it) }      // 4 calls, price goes up: 4/4
        val bob = (0 until 3).map { entry("bob", "ETH", Dir.DOWN, 10_000L * it + 1) }    // 3 calls, price goes up: 0/3
        val carol = listOf(entry("carol", "SOL", Dir.UP, 5))                              // 1 lucky call: unranked
        val all = alice + bob + carol
        val grades = all.associate { it.call.signature to Grading.grade(it.call.call, 100.0, 101.0, "binance") }
        val rows = Leaderboard.rows(all, grades)
        assertEquals("alice", rows[0].player)
        assertEquals(100, rows[0].pct)
        val bobRow = rows.first { it.player == "bob" }
        assertTrue(bobRow.ranked); assertEquals(0, bobRow.pct)
        val carolRow = rows.first { it.player == "carol" }
        assertFalse(carolRow.ranked)
        assertTrue(rows.indexOf(carolRow) > rows.indexOf(bobRow))   // unranked below every ranked row
    }

    @Test fun smtCountsItsSameCallOnce() {
        // Two players call BTC in the same hour: SMT's lean on BTC that hour counts once.
        val es = listOf(entry("a", "BTC", Dir.UP, 3600), entry("b", "BTC", Dir.DOWN, 3700), entry("c", "ETH", Dir.UP, 3650, Lean.UP))
        val grades = es.associate { it.call.signature to Grading.grade(it.call.call, 100.0, 99.0, "binance") }
        val smt = Leaderboard.rows(es, grades).first { it.player == Leaderboard.SMT }
        assertEquals(2, smt.graded)    // BTC once + ETH once
        assertEquals(1, smt.right)     // BTC DOWN was right, ETH UP was wrong
    }

    @Test fun emptyBoardStillHasSmtRow() {
        val rows = Leaderboard.rows(emptyList(), emptyMap())
        assertEquals(listOf(Leaderboard.SMT), rows.map { it.player })
        assertEquals(0, rows[0].calls)
    }
}
