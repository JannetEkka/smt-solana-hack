package io.github.jannetekka.smtworld.smt

import io.github.jannetekka.smtworld.clockin.Lean
import io.github.jannetekka.smtworld.data.Http
import org.json.JSONObject

data class PersonaVote(val key: String, val name: String, val dir: String, val conf: Double, val say: String)

/** SMT's current call on one coin, from the public SMT World feed. */
data class SmtCall(
    val coin: String,
    val action: String,          // LONG · SHORT · WAIT
    val conf: Double,            // 0..1 committee conviction
    val why: String,
    val asOfEpochSec: Long?,
    val votes: List<PersonaVote>,
) {
    /**
     * What gets graded. A LONG or SHORT is the call itself. On a WAIT the committee is below its
     * trade bar, but its personas still lean one way: the conviction-weighted sum of their votes.
     * The app always labels this a lean, never a trade.
     */
    val lean: Lean get() = when (action) {
        "LONG" -> Lean.UP
        "SHORT" -> Lean.DOWN
        else -> {
            val s = votes.sumOf { when (it.dir) { "LONG" -> it.conf; "SHORT" -> -it.conf; else -> 0.0 } }
            when { s > 1e-9 -> Lean.UP; s < -1e-9 -> Lean.DOWN; else -> Lean.FLAT }
        }
    }
    val convictionPct: Int get() = Math.round(conf * 100).toInt().coerceIn(0, 100)
}

object SmtFeed {
    const val SITE = "https://smt-weex-trading-bot.jannet-ekka.workers.dev/"
    const val URL = SITE + "decisions.json"

    private val NAMES = mapOf(
        "flow" to "Order flow", "flows" to "Whales & on-chain", "sentiment" to "Sentiment",
        "technical" to "Technical", "regime" to "Regime", "catalyst" to "Catalyst",
    )

    fun fetch(): Pair<String, Map<String, SmtCall>> {
        val text = Http.get(URL)
        return text to parse(text)
    }

    fun parse(text: String): Map<String, SmtCall> {
        val root = JSONObject(text)
        val out = LinkedHashMap<String, SmtCall>()
        for (coin in root.keys()) {
            val o = root.optJSONObject(coin) ?: continue
            val votes = mutableListOf<PersonaVote>()
            o.optJSONObject("votes")?.let { v ->
                for (k in v.keys()) {
                    val a = v.optJSONArray(k) ?: continue
                    votes += PersonaVote(k, NAMES[k] ?: k.replaceFirstChar { it.uppercase() },
                        a.optString(0, "NEUTRAL"), a.optDouble(1, 0.0), a.optString(2, ""))
                }
            }
            out[coin] = SmtCall(
                coin = coin,
                // Upper-case letters only: the action goes into the memo, whose parser accepts [A-Z]+.
                action = o.optString("action", "WAIT").uppercase().filter { it in 'A'..'Z' }.take(12).ifEmpty { "WAIT" },
                conf = o.optDouble("conf", 0.0),
                why = o.optString("why", ""),
                asOfEpochSec = parseIso(o.optString("as_of", "")),
                votes = votes.sortedByDescending { it.conf },
            )
        }
        return out
    }

    /** "2026-10-06T17:10:44.773432+00:00" → epoch seconds; null if it is not that shape. */
    fun parseIso(s: String): Long? {
        val m = Regex("""^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})""").find(s) ?: return null
        val (y, mo, d, h, mi, se) = m.destructured
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(y.toInt(), mo.toInt() - 1, d.toInt(), h.toInt(), mi.toInt(), se.toInt())
        val offset = Regex("""([+-])(\d{2}):(\d{2})$""").find(s)?.destructured?.let { (sign, oh, om) ->
            (oh.toInt() * 3600 + om.toInt() * 60) * if (sign == "-") -1 else 1
        } ?: 0
        return cal.timeInMillis / 1000 - offset
    }
}
