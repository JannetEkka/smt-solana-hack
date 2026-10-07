package io.github.jannetekka.smtworld.clockin

import java.math.BigDecimal
import java.math.MathContext

enum class Dir { UP, DOWN }

/** SMT's side of a call. FLAT = its personas cancel out, so there is nothing to grade. */
enum class Lean { UP, DOWN, FLAT }

/**
 * One Clock In, exactly as it is written on chain. The memo is plain text so anyone can read
 * it on Solana Explorer without this app:
 *
 *   SMT Clock In v1 | BTC | me UP @ 62345.12 binance | SMT DOWN 36% WAIT | grade +4h
 *
 * The user's call and SMT's call are stamped by the same transaction, so neither can be
 * written after the market moves. The entry price and its source are in the memo so the
 * grade can be re-checked against public data.
 */
data class ClockInCall(
    val coin: String,
    val you: Dir,
    val entryPx: Double,
    val priceSource: String,
    val smtLean: Lean,
    val smtConvictionPct: Int,
    val smtAction: String,
    val horizonHours: Int = HORIZON_HOURS,
) {
    fun toMemo(): String =
        "$PREFIX | $coin | me ${you.name} @ ${fmtPx(entryPx)} $priceSource | " +
            "SMT ${smtLean.name} $smtConvictionPct% $smtAction | grade +${horizonHours}h"

    companion object {
        const val PREFIX = "SMT Clock In v1"
        /** SMT grades its own persona calls on the +4h move; the game uses the same clock. */
        const val HORIZON_HOURS = 4

        private val RE = Regex(
            """^SMT Clock In v1 \| ([A-Z0-9]{2,10}) \| me (UP|DOWN) @ ([0-9.]+) ([a-z]+) \| SMT (UP|DOWN|FLAT) (\d{1,3})% ([A-Z]+) \| grade \+(\d{1,3})h$"""
        )

        fun fmtPx(px: Double): String =
            BigDecimal(px).round(MathContext(8)).stripTrailingZeros().toPlainString()

        /** Accepts the memo text or the RPC's memo field, which prefixes each memo with "[length] ". */
        fun parse(memo: String?): ClockInCall? {
            if (memo == null) return null
            val text = memo.trim().replaceFirst(Regex("""^\[\d+\]\s*"""), "")
            val m = RE.matchEntire(text) ?: return null
            val g = m.groupValues
            return ClockInCall(
                coin = g[1],
                you = Dir.valueOf(g[2]),
                entryPx = g[3].toDoubleOrNull() ?: return null,
                priceSource = g[4],
                smtLean = Lean.valueOf(g[5]),
                smtConvictionPct = g[6].toInt(),
                smtAction = g[7],
                horizonHours = g[8].toInt(),
            )
        }
    }
}

/** A Clock In read back from devnet. */
data class OnChainCall(val signature: String, val blockTime: Long, val call: ClockInCall) {
    val dueAt: Long get() = blockTime + call.horizonHours * 3600L
}
