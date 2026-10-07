package io.github.jannetekka.smtworld.clockin

enum class Outcome { RIGHT, WRONG, NOT_SCORED }

/** A call compared with the price [horizonHours] later. Both prices come from one source. */
data class Grade(
    val entryPx: Double,
    val exitPx: Double,
    val source: String,
    val you: Outcome,
    val smt: Outcome,
    /** The memo's stated entry price is more than [MAX_MEMO_DRIFT] from the market's: not a real call. */
    val memoMismatch: Boolean = false,
) {
    val movePct: Double get() = (exitPx - entryPx) / entryPx * 100.0
}

object Grading {
    /** How far a memo's stated price may sit from the market price at that minute (fees, ticker vs candle). */
    const val MAX_MEMO_DRIFT = 0.02

    /**
     * Grades on market prices only: [entryPx] and [exitPx] come from the price source at the
     * call's time and its horizon, never from the memo, which anyone can write. A memo whose stated
     * price is far from the market's is flagged and kept off the leaderboard.
     */
    fun graded(call: ClockInCall, entryPx: Double, exitPx: Double, source: String): Grade {
        val drift = kotlin.math.abs(call.entryPx / entryPx - 1)
        return grade(call, entryPx, exitPx, source).copy(memoMismatch = drift > MAX_MEMO_DRIFT)
    }

    fun grade(call: ClockInCall, entryPx: Double, exitPx: Double, source: String): Grade {
        val up = exitPx > entryPx
        val flat = exitPx == entryPx
        fun score(dir: Dir): Outcome = when {
            flat -> Outcome.NOT_SCORED
            (dir == Dir.UP) == up -> Outcome.RIGHT
            else -> Outcome.WRONG
        }
        val smt = when (call.smtLean) {
            Lean.UP -> score(Dir.UP)
            Lean.DOWN -> score(Dir.DOWN)
            Lean.FLAT -> Outcome.NOT_SCORED
        }
        return Grade(entryPx, exitPx, source, score(call.you), smt)
    }

    data class Score(val right: Int, val scored: Int) {
        val pct: Int? get() = if (scored == 0) null else Math.round(right * 100.0 / scored).toInt()
    }

    fun scoreYou(grades: Collection<Grade>) = Score(grades.count { it.you == Outcome.RIGHT }, grades.count { it.you != Outcome.NOT_SCORED })
    fun scoreSmt(grades: Collection<Grade>) = Score(grades.count { it.smt == Outcome.RIGHT }, grades.count { it.smt != Outcome.NOT_SCORED })
}
