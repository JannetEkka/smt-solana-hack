package io.github.jannetekka.smtworld.data

import android.content.Context
import android.util.Log
import io.github.jannetekka.smtworld.clockin.ClockInCall
import io.github.jannetekka.smtworld.clockin.Grade
import io.github.jannetekka.smtworld.clockin.Grading
import io.github.jannetekka.smtworld.clockin.OnChainCall
import io.github.jannetekka.smtworld.market.Prices
import io.github.jannetekka.smtworld.solana.DevnetRpc

/** History and grades, shared by the screens and the background workers. */
class Repo(context: Context) {
    val store = Store(context)
    val rpc = DevnetRpc()

    /**
     * Reads the wallet's Clock Ins back from devnet. Calls this phone sent that the RPC does not
     * list yet are kept for 30 minutes, then dropped as never landed.
     */
    fun syncHistory(address: String, nowSec: Long = System.currentTimeMillis() / 1000): List<OnChainCall> {
        val onChain = rpc.signaturesForAddress(address).filter { !it.failed }.mapNotNull { s ->
            val c = ClockInCall.parse(s.memo) ?: return@mapNotNull null
            OnChainCall(s.signature, s.blockTime ?: nowSec, c)
        }
        val seen = onChain.map { it.signature }.toSet()
        val pendingLocal = store.calls.filter { it.signature !in seen && nowSec - it.blockTime < 1800 }
        val merged = (onChain + pendingLocal).sortedByDescending { it.blockTime }
        store.calls = merged
        Log.i(TAG, "[HISTORY] ${onChain.size} on chain, ${pendingLocal.size} awaiting the RPC, wallet=$address")
        return merged
    }

    fun addLocal(call: OnChainCall) {
        store.calls = (listOf(call) + store.calls.filter { it.signature != call.signature })
    }

    /** Grades every call whose horizon has passed. Returns the newly graded ones. */
    fun gradeDue(nowSec: Long = System.currentTimeMillis() / 1000): List<Pair<OnChainCall, Grade>> {
        val done = store.grades
        val out = mutableListOf<Pair<OnChainCall, Grade>>()
        for (c in store.calls) {
            if (c.signature in done || c.dueAt + 60 > nowSec) continue
            val g = try { gradeOne(c) } catch (e: Exception) {
                Log.w(TAG, "[GRADE] ${c.call.coin} ${c.signature.take(8)} failed: ${e.message}")
                null
            } ?: continue
            store.putGrade(c.signature, g)
            out += c to g
            Log.i(TAG, "[GRADE] ${c.call.coin} you=${g.you} smt=${g.smt} move=${"%.2f".format(g.movePct)}% src=${g.source}")
        }
        return out
    }

    /** Exit price from the memo's own source when it answers; otherwise both ends from the other one. */
    private fun gradeOne(c: OnChainCall): Grade? {
        val coin = c.call.coin
        val own = c.call.priceSource
        val other = if (own == Prices.BINANCE) Prices.COINGECKO else Prices.BINANCE
        val ownExit = runCatching { Prices.at(coin, c.dueAt, own) }.getOrNull()
        if (ownExit != null) return Grading.grade(c.call, c.call.entryPx, ownExit.px, own)
        val exit = Prices.at(coin, c.dueAt, other) ?: return null
        val entry = Prices.at(coin, c.blockTime, other) ?: return null
        return Grading.grade(c.call, entry.px, exit.px, other)
    }

    companion object { const val TAG = "SMTWorld" }
}
