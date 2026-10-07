package io.github.jannetekka.smtworld.data

import android.content.Context
import io.github.jannetekka.smtworld.clockin.ClockInCall
import io.github.jannetekka.smtworld.clockin.Grade
import io.github.jannetekka.smtworld.clockin.OnChainCall
import io.github.jannetekka.smtworld.clockin.Outcome
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local cache only. The chain is the record; this exists so the app opens instantly and
 * works offline. Losing it loses nothing: history and streak are rebuilt from devnet.
 */
class Store(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("smt_world", Context.MODE_PRIVATE)

    var address: String?
        get() = p.getString("address", null)
        set(v) = p.edit().putString("address", v).apply()

    var authToken: String?
        get() = p.getString("auth_token", null)
        set(v) = p.edit().putString("auth_token", v).apply()

    var decisionsJson: String?
        get() = p.getString("decisions", null)
        set(v) = p.edit().putString("decisions", v).apply()

    var remindersOn: Boolean
        get() = p.getBoolean("reminders", true)
        set(v) = p.edit().putBoolean("reminders", v).apply()

    var calls: List<OnChainCall>
        get() = readCalls(p.getString("calls", null))
        set(v) = p.edit().putString("calls", writeCalls(v)).apply()

    var grades: Map<String, Grade>
        get() = readGrades(p.getString("grades", null))
        set(v) = p.edit().putString("grades", writeGrades(v)).apply()

    fun putGrade(signature: String, g: Grade) { grades = grades + (signature to g) }

    /** Every player's Clock Ins, as last read from the registry address. */
    var boardCalls: List<OnChainCall>
        get() = readCalls(p.getString("board_calls", null))
        set(v) = p.edit().putString("board_calls", writeCalls(v)).apply()

    /** Transaction signature → the wallet that paid for it (never changes, so cached for good). */
    var payers: Map<String, String>
        get() = p.getString("payers", null)?.let { s -> org.json.JSONObject(s).let { o -> o.keys().asSequence().associateWith { o.getString(it) } } } ?: emptyMap()
        set(v) = p.edit().putString("payers", org.json.JSONObject(v as Map<*, *>).toString()).apply()

    fun clearWallet() {
        p.edit().remove("address").remove("auth_token").remove("calls").remove("grades").apply()
    }

    companion object {
        fun writeCalls(calls: List<OnChainCall>): String = JSONArray().apply {
            calls.forEach { put(JSONObject().put("sig", it.signature).put("t", it.blockTime).put("memo", it.call.toMemo())) }
        }.toString()

        fun readCalls(s: String?): List<OnChainCall> {
            if (s.isNullOrEmpty()) return emptyList()
            val a = JSONArray(s)
            return (0 until a.length()).mapNotNull { i ->
                val o = a.getJSONObject(i)
                ClockInCall.parse(o.getString("memo"))?.let { OnChainCall(o.getString("sig"), o.getLong("t"), it) }
            }
        }

        fun writeGrades(m: Map<String, Grade>): String = JSONObject().apply {
            m.forEach { (sig, g) ->
                put(sig, JSONObject().put("in", g.entryPx).put("out", g.exitPx).put("src", g.source)
                    .put("you", g.you.name).put("smt", g.smt.name))
            }
        }.toString()

        fun readGrades(s: String?): Map<String, Grade> {
            if (s.isNullOrEmpty()) return emptyMap()
            val o = JSONObject(s)
            return o.keys().asSequence().associateWith { k ->
                val g = o.getJSONObject(k)
                Grade(g.getDouble("in"), g.getDouble("out"), g.getString("src"),
                    Outcome.valueOf(g.getString("you")), Outcome.valueOf(g.getString("smt")))
            }
        }
    }
}
