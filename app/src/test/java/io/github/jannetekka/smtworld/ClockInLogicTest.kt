package io.github.jannetekka.smtworld

import io.github.jannetekka.smtworld.clockin.ClockInCall
import io.github.jannetekka.smtworld.clockin.Dir
import io.github.jannetekka.smtworld.clockin.Grading
import io.github.jannetekka.smtworld.clockin.Lean
import io.github.jannetekka.smtworld.clockin.OnChainCall
import io.github.jannetekka.smtworld.clockin.Outcome
import io.github.jannetekka.smtworld.clockin.Streak
import io.github.jannetekka.smtworld.data.Store
import io.github.jannetekka.smtworld.smt.SmtFeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class ClockInLogicTest {
    private val call = ClockInCall("BTC", Dir.UP, 62345.12, "binance", Lean.DOWN, 36, "WAIT")

    @Test fun memoIsReadableAndRoundTrips() {
        val memo = call.toMemo()
        assertEquals("SMT Clock In v1 | BTC | me UP @ 62345.12 binance | SMT DOWN 36% WAIT | grade +4h", memo)
        assertEquals(call, ClockInCall.parse(memo))
        // The RPC's memo field prefixes each memo with its byte length.
        assertEquals(call, ClockInCall.parse("[${memo.length}] $memo"))
        assertTrue(memo.toByteArray().size < 120)
    }

    @Test fun memoParserRejectsOtherMemos() {
        assertNull(ClockInCall.parse(null))
        assertNull(ClockInCall.parse("gm"))
        assertNull(ClockInCall.parse("[4] test"))
        assertNull(ClockInCall.parse("SMT Clock In v1 | BTC | me SIDEWAYS @ 1 binance | SMT UP 1% WAIT | grade +4h"))
    }

    @Test fun smallPricesKeepTheirDigits() {
        assertEquals("0.25123456", ClockInCall.fmtPx(0.251234561))
        assertEquals("62345.12", ClockInCall.fmtPx(62345.12))
        val doge = call.copy(coin = "DOGE", entryPx = 0.1834)
        assertEquals(0.1834, ClockInCall.parse(doge.toMemo())!!.entryPx, 1e-12)
    }

    @Test fun gradingUsesDirection() {
        val g = Grading.grade(call, 100.0, 101.0, "binance")
        assertEquals(Outcome.RIGHT, g.you)
        assertEquals(Outcome.WRONG, g.smt)
        assertEquals(1.0, g.movePct, 1e-9)
        val down = Grading.grade(call, 100.0, 99.0, "binance")
        assertEquals(Outcome.WRONG, down.you); assertEquals(Outcome.RIGHT, down.smt)
        val flat = Grading.grade(call, 100.0, 100.0, "binance")
        assertEquals(Outcome.NOT_SCORED, flat.you); assertEquals(Outcome.NOT_SCORED, flat.smt)
        val noLean = Grading.grade(call.copy(smtLean = Lean.FLAT), 100.0, 105.0, "binance")
        assertEquals(Outcome.NOT_SCORED, noLean.smt)
    }

    @Test fun scoresCountOnlyScoredCalls() {
        val gs = listOf(
            Grading.grade(call, 100.0, 101.0, "b"), Grading.grade(call, 100.0, 99.0, "b"),
            Grading.grade(call.copy(smtLean = Lean.FLAT), 100.0, 101.0, "b"),
        )
        assertEquals(Grading.Score(2, 3), Grading.scoreYou(gs))
        assertEquals(Grading.Score(1, 2), Grading.scoreSmt(gs))
        assertEquals(67, Grading.scoreYou(gs).pct)
        assertNull(Grading.scoreYou(emptyList()).pct)
    }

    @Test fun streakCountsLocalDays() {
        val utc = TimeZone.getTimeZone("UTC")
        val ist = TimeZone.getTimeZone("Asia/Kolkata")
        val utcMidnightOct7 = 1791331200L                   // 2026-10-07 00:00:00 UTC
        assertEquals(Streak.localDay(utcMidnightOct7, utc), Streak.localDay(utcMidnightOct7 + 86399, utc))
        // 00:00 IST on Oct 7 is 18:30 UTC on Oct 6: an Indian user's day turns over then, not at UTC midnight.
        val istMidnightOct7 = utcMidnightOct7 - 19800
        assertEquals(Streak.localDay(istMidnightOct7 - 1, ist) + 1, Streak.localDay(istMidnightOct7, ist))
        assertEquals(Streak.localDay(utcMidnightOct7, utc), Streak.localDay(istMidnightOct7, ist))
    }

    @Test fun streakRules() {
        assertEquals(3, Streak.current(setOf(10L, 11L, 12L), today = 12))
        assertEquals(3, Streak.current(setOf(10L, 11L, 12L), today = 13))   // not yet today: still alive
        assertEquals(0, Streak.current(setOf(10L, 11L, 12L), today = 14))   // a missed day ends it
        assertEquals(1, Streak.current(setOf(8L, 12L), today = 12))
        assertEquals(0, Streak.current(emptySet(), today = 12))
        assertEquals(4, Streak.best(setOf(1L, 2L, 3L, 4L, 7L, 8L)))
        assertEquals(0, Streak.best(emptySet()))
    }

    @Test fun smtFeedParsesTheLiveShape() {
        val text = javaClass.classLoader!!.getResource("decisions_sample.json")!!.readText()
        val feed = SmtFeed.parse(text)
        assertEquals(8, feed.size)
        val btc = feed.getValue("BTC")
        assertEquals("WAIT", btc.action)
        assertEquals(36, btc.convictionPct)
        assertEquals(Lean.DOWN, btc.lean)                    // flow, flows and sentiment all voted SHORT
        assertEquals(Lean.DOWN, feed.getValue("LTC").lean)   // an explicit SHORT
        assertEquals("Order flow", btc.votes.first { it.key == "flow" }.name)
        assertEquals(1791306644L, btc.asOfEpochSec)          // 2026-10-06T17:10:44+00:00
    }

    @Test fun leanIsFlatWhenVotesCancel() {
        val text = """{"XRP":{"action":"WAIT","conf":0.1,"votes":{"a":["LONG",0.5,""],"b":["SHORT",0.5,""],"c":["NEUTRAL",0,""]}}}"""
        assertEquals(Lean.FLAT, SmtFeed.parse(text).getValue("XRP").lean)
    }

    @Test fun isoTimestamps() {
        assertEquals(1791306644L, SmtFeed.parseIso("2026-10-06T17:10:44.773432+00:00"))
        assertEquals(1791306644L - 19800, SmtFeed.parseIso("2026-10-06T17:10:44+05:30"))
        assertNull(SmtFeed.parseIso("yesterday"))
    }

    @Test fun cacheRoundTrips() {
        val calls = listOf(OnChainCall("sig1", 1791306644L, call), OnChainCall("sig2", 1791310000L, call.copy(you = Dir.DOWN)))
        assertEquals(calls, Store.readCalls(Store.writeCalls(calls)))
        val grades = mapOf("sig1" to Grading.grade(call, 100.0, 101.0, "binance"))
        assertEquals(grades, Store.readGrades(Store.writeGrades(grades)))
        assertEquals(emptyList<OnChainCall>(), Store.readCalls(null))
    }
}
