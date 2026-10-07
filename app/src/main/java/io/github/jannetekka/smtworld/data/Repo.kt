package io.github.jannetekka.smtworld.data

import android.content.Context
import android.util.Log
import io.github.jannetekka.smtworld.clockin.ClockInCall
import io.github.jannetekka.smtworld.clockin.Grade
import io.github.jannetekka.smtworld.clockin.Grading
import io.github.jannetekka.smtworld.clockin.Leaderboard
import io.github.jannetekka.smtworld.clockin.OnChainCall
import io.github.jannetekka.smtworld.market.Prices
import io.github.jannetekka.smtworld.solana.DevnetRpc
import io.github.jannetekka.smtworld.solana.MemoTransaction

/** History and grades, shared by the screens and the background workers. */
class Repo(context: Context) {
    val store = Store(context)
    val rpc = DevnetRpc()

    /**
     * Reads the wallet's Clock Ins back from devnet, newest first, up to [MAX_PAGES] pages of 200
     * transactions. The merge keeps cached calls older than the oldest page read (the chain is
     * append-only, so they are still true) and calls this phone sent in the last 30 minutes that
     * the RPC does not list yet. Everything else comes from the chain.
     */
    fun syncHistory(address: String, nowSec: Long = System.currentTimeMillis() / 1000): List<OnChainCall> {
        val sigs = mutableListOf<DevnetRpc.SignatureInfo>()
        var before: String? = null
        var exhausted = false
        for (page in 0 until MAX_PAGES) {
            val got = rpc.signaturesForAddress(address, PAGE, before)
            sigs += got
            if (got.size < PAGE) { exhausted = true; break }
            before = got.last().signature
        }
        val onChain = sigs.filter { !it.failed }.mapNotNull { s ->
            val c = ClockInCall.parse(s.memo) ?: return@mapNotNull null
            OnChainCall(s.signature, s.blockTime ?: nowSec, c)
        }
        val seen = sigs.map { it.signature }.toSet()
        val oldestRead = if (exhausted) Long.MIN_VALUE else sigs.mapNotNull { it.blockTime }.minOrNull() ?: Long.MIN_VALUE
        val merged = synchronized(LOCK) {
            val cached = store.calls.filter { it.signature !in seen }
            val older = cached.filter { it.blockTime < oldestRead }
            val pending = cached.filter { it.blockTime >= oldestRead && nowSec - it.blockTime < 1800 }
            (onChain + older + pending).distinctBy { it.signature }.sortedByDescending { it.blockTime }
                .also { store.calls = it }
        }
        Log.i(TAG, "[HISTORY] ${onChain.size} on chain (${sigs.size} txs read), ${merged.size - onChain.size} kept from cache, wallet=$address")
        return merged
    }

    fun addLocal(call: OnChainCall) = synchronized(LOCK) {
        store.calls = (listOf(call) + store.calls.filter { it.signature != call.signature })
    }

    /**
     * Every player's Clock Ins, read from the shared registry address (each Clock In names it in
     * its second memo). The player is the transaction's fee payer, looked up in batches and cached
     * for good; at most [MAX_PAYER_LOOKUPS] new lookups per sync.
     */
    fun syncLeaderboard(nowSec: Long = System.currentTimeMillis() / 1000): List<Leaderboard.Entry> {
        val sigs = rpc.signaturesForAddress(MemoTransaction.REGISTRY, 1000).filter { !it.failed }
        val calls = sigs.mapNotNull { s -> ClockInCall.parse(s.memo)?.let { OnChainCall(s.signature, s.blockTime ?: nowSec, it) } }
        val missing = calls.map { it.signature }.filter { it !in store.payers }.take(MAX_PAYER_LOOKUPS)
        val fetched = if (missing.isEmpty()) emptyMap() else rpc.feePayers(missing)
        val payers = synchronized(LOCK) {
            store.boardCalls = calls
            (store.payers + fetched).also { store.payers = it }
        }
        val entries = calls.mapNotNull { c -> payers[c.signature]?.let { Leaderboard.Entry(c, it) } }
        Log.i(TAG, "[BOARD] ${calls.size} Clock Ins on the registry, ${entries.map { it.player }.distinct().size} players, ${fetched.size} payers looked up")
        return entries
    }

    fun boardEntries(): List<Leaderboard.Entry> {
        val payers = store.payers
        return store.boardCalls.mapNotNull { c -> payers[c.signature]?.let { Leaderboard.Entry(c, it) } }
    }

    /**
     * Grades calls whose horizon has passed, newest first: this wallet's own first, then other
     * players' from the leaderboard, at most [max] of each per run so a reinstall or a busy
     * board doesn't fire hundreds of price requests at once.
     */
    fun gradeDue(nowSec: Long = System.currentTimeMillis() / 1000, max: Int = 12): List<Pair<OnChainCall, Grade>> {
        val done = store.grades
        fun pending(list: List<OnChainCall>) = list.filter { it.signature !in done && it.dueAt + 60 <= nowSec }
            .sortedByDescending { it.blockTime }.take(max)
        val own = pending(store.calls)
        val ownSigs = own.map { it.signature }.toSet()
        val due = own + pending(store.boardCalls).filter { it.signature !in ownSigs }
        val out = mutableListOf<Pair<OnChainCall, Grade>>()
        for (c in due) {
            val g = try { gradeOne(c) } catch (e: Exception) {
                Log.w(TAG, "[GRADE] ${c.call.coin} ${c.signature.take(8)} failed: ${e.message}")
                null
            } ?: continue
            synchronized(LOCK) { store.putGrade(c.signature, g) }
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
        val ownExit = runCatching { Prices.at(coin, c.dueAt, own, notBefore = true) }.getOrNull()
        if (ownExit != null) return Grading.grade(c.call, c.call.entryPx, ownExit.px, own)
        val exit = Prices.at(coin, c.dueAt, other, notBefore = true) ?: return null
        val entry = Prices.at(coin, c.blockTime, other) ?: return null
        return Grading.grade(c.call, entry.px, exit.px, other)
    }

    companion object {
        const val TAG = "SMTWorld"
        private const val PAGE = 200
        private const val MAX_PAGES = 5
        private const val MAX_PAYER_LOOKUPS = 100
        /** One lock for every read-modify-write of the cache, from the screens and the workers alike. */
        val LOCK = Any()
    }
}
