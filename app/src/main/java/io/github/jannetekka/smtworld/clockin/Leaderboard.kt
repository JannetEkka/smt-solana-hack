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
     * One row per player plus one for SMT: SMT's record is its call on every player's Clock In,
     * counted once per (coin, hour) so a busy coin doesn't count SMT's same call many times.
     * Ranked players first (by hit rate, then hits), then the rest by how many calls they made.
     */
    fun rows(entries: List<Entry>, grades: Map<String, Grade>): List<Row> {
        val players = entries.groupBy { it.player }.map { (p, es) ->
            val gs = es.mapNotNull { grades[it.call.signature] }.filter { it.you != Outcome.NOT_SCORED }
            Row(p, es.size, gs.size, gs.count { it.you == Outcome.RIGHT }, es.maxOf { it.call.blockTime })
        }
        val smtCalls = entries.mapNotNull { e -> grades[e.call.signature]?.let { e to it } }
            .filter { it.second.smt != Outcome.NOT_SCORED }
            .distinctBy { (e, _) -> e.call.call.coin to e.call.blockTime / 3600 }
        val smt = Row(SMT, smtCalls.size, smtCalls.size, smtCalls.count { it.second.smt == Outcome.RIGHT },
            entries.maxOfOrNull { it.call.blockTime } ?: 0)
        val order = compareByDescending<Row> { it.ranked }
            .thenByDescending { if (it.ranked) it.pct ?: 0 else 0 }
            .thenByDescending { if (it.ranked) it.right else it.calls }
            .thenByDescending { it.lastCall }
        return (players + smt).sortedWith(order)
    }
}
