package io.github.jannetekka.smtworld.clockin

enum class Outcome { RIGHT, WRONG, NOT_SCORED }

/** A call compared with the price [horizonHours] later. Both prices come from one source. */
data class Grade(
    val entryPx: Double,
    val exitPx: Double,
    val source: String,
    val you: Outcome,
    val smt: Outcome,
) {
    val movePct: Double get() = (exitPx - entryPx) / entryPx * 100.0
}

object Grading {
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
