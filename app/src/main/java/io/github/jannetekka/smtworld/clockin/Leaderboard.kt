package io.github.jannetekka.smtworld.clockin

/** Every player's Clock Ins, read from the shared registry address, ranked by graded hits. */
object Leaderboard {
    /** A player needs this many graded calls before being ranked, so one lucky call can't top the list. */
    const val MIN_GRADED = 3
    const val SMT = "SMT"

    data class Row(
        val player: String,          // wallet address, or SMT
        val calls: Int,
        val graded: Int,
        val right: Int,
        val lastCall: Long,
    ) {
        val pct: Int? get() = if (graded == 0) null else Math.round(right * 100.0 / graded).toInt()
        val ranked: Boolean get() = graded >= MIN_GRADED
    }

    data class Entry(val call: OnChainCall, val player: String)

    /**
     * One row per player plus one for SMT. Only calls whose memo price matches the market count.
     *
     * Players write SMT's lean into their own memos, so a single player could misreport it. SMT's
     * row therefore takes, for each (coin, hour), the lean most distinct players recorded, and
     * counts that call once.
     */
    fun rows(entries: List<Entry>, grades: Map<String, Grade>): List<Row> {
        val valid = entries.filter { grades[it.call.signature]?.memoMismatch != true }
        val players = valid.groupBy { it.player }.map { (p, es) ->
            val gs = es.mapNotNull { grades[it.call.signature] }.filter { it.you != Outcome.NOT_SCORED }
            Row(p, es.size, gs.size, gs.count { it.you == Outcome.RIGHT }, es.maxOf { it.call.blockTime })
        }
        val smtCalls = valid.mapNotNull { e -> grades[e.call.signature]?.let { e to it } }
            .groupBy { (e, _) -> e.call.call.coin to e.call.blockTime / 3600 }
            .mapNotNull { (_, inBucket) ->
                // the lean the most distinct players recorded for this coin and hour
                val byLean = inBucket.groupBy { it.first.call.call.smtLean }
                    .mapValues { (_, xs) -> xs.map { it.first.player }.distinct().size }
                val lean = byLean.maxByOrNull { it.value }?.key ?: return@mapNotNull null
                if (byLean.values.count { it == byLean.getValue(lean) } > 1) return@mapNotNull null   // a tie: no consensus
                inBucket.first { it.first.call.call.smtLean == lean }.second.smt.takeIf { it != Outcome.NOT_SCORED }
            }
        val smt = Row(SMT, smtCalls.size, smtCalls.size, smtCalls.count { it == Outcome.RIGHT },
            valid.maxOfOrNull { it.call.blockTime } ?: 0)
        val order = compareByDescending<Row> { it.ranked }
            .thenByDescending { if (it.ranked) it.pct ?: 0 else 0 }
            .thenByDescending { if (it.ranked) it.right else it.calls }
            .thenByDescending { it.lastCall }
        return (players + smt).sortedWith(order)
    }
}
