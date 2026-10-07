package io.github.jannetekka.smtworld.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.Solana
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import io.github.jannetekka.smtworld.clockin.ClockInCall
import io.github.jannetekka.smtworld.clockin.Dir
import io.github.jannetekka.smtworld.clockin.Grade
import io.github.jannetekka.smtworld.clockin.Grading
import io.github.jannetekka.smtworld.clockin.Lean
import io.github.jannetekka.smtworld.clockin.OnChainCall
import io.github.jannetekka.smtworld.clockin.Streak
import io.github.jannetekka.smtworld.data.Repo
import io.github.jannetekka.smtworld.market.Prices
import io.github.jannetekka.smtworld.market.Quote
import io.github.jannetekka.smtworld.notify.Reminders
import io.github.jannetekka.smtworld.smt.SmtCall
import io.github.jannetekka.smtworld.smt.SmtFeed
import io.github.jannetekka.smtworld.solana.Base58
import io.github.jannetekka.smtworld.solana.DevnetRpc
import io.github.jannetekka.smtworld.solana.MemoTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface SendState {
    data object Idle : SendState
    data object Preparing : SendState
    data object InWallet : SendState
    data class Done(val signature: String) : SendState
    data class Failed(val message: String) : SendState
}

data class UiState(
    val walletApp: Boolean = false,
    val address: String? = null,
    val balanceSol: Double? = null,
    val coin: String = "BTC",
    val pick: Dir? = null,
    val prices: Map<String, Quote> = emptyMap(),
    val pricesError: String? = null,
    val smt: Map<String, SmtCall> = emptyMap(),
    val smtError: String? = null,
    val calls: List<OnChainCall> = emptyList(),
    val grades: Map<String, Grade> = emptyMap(),
    val historyError: String? = null,
    val loading: Boolean = false,
    val send: SendState = SendState.Idle,
    val lastSent: OnChainCall? = null,
    val remindersOn: Boolean = true,
    val nowSec: Long = System.currentTimeMillis() / 1000,
) {
    val days: Set<Long> get() = calls.map { Streak.localDay(it.blockTime) }.toSet()
    val today: Long get() = Streak.localDay(nowSec)
    val streak: Int get() = Streak.current(days, today)
    val bestStreak: Int get() = Streak.best(days)
    val clockedInToday: Boolean get() = today in days
    val todaysCalls: List<OnChainCall> get() = calls.filter { Streak.localDay(it.blockTime) == today }
    val scoreYou get() = Grading.scoreYou(grades.filterKeys { k -> calls.any { it.signature == k } }.values)
    val scoreSmt get() = Grading.scoreSmt(grades.filterKeys { k -> calls.any { it.signature == k } }.values)
    /** Hours since SMT's feed for the chosen coin was written; null if unknown. */
    fun smtAgeHours(coin: String): Double? = smt[coin]?.asOfEpochSec?.let { (nowSec - it) / 3600.0 }
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repo(app)
    private val store = repo.store
    private val wallet = MobileWalletAdapter(
        ConnectionIdentity(
            identityUri = Uri.parse(SmtFeed.SITE.removeSuffix("/")),
            iconUri = Uri.parse("smt_logo.png"),
            identityName = "SMT World",
        )
    ).apply {
        blockchain = Solana.Devnet
        authToken = store.authToken
    }

    private val _state = MutableStateFlow(
        UiState(
            walletApp = hasWalletApp(app),
            address = store.address,
            calls = store.calls,
            grades = store.grades,
            smt = store.decisionsJson?.let { runCatching { SmtFeed.parse(it) }.getOrNull() } ?: emptyMap(),
            remindersOn = store.remindersOn,
        )
    )
    val state: StateFlow<UiState> = _state

    init {
        Reminders.createChannels(app)
        if (store.remindersOn) Reminders.scheduleDaily(app)
        Log.i(Repo.TAG, "[START] walletApp=${_state.value.walletApp} address=${store.address} cachedCalls=${store.calls.size}")
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, nowSec = System.currentTimeMillis() / 1000) }
            val io = Dispatchers.IO
            val prices = async(io) { runCatching { Prices.spotAll() } }
            val feed = async(io) { runCatching { SmtFeed.fetch() } }
            val addr = store.address
            val history = async(io) { addr?.let { a -> runCatching { repo.syncHistory(a) } } }
            val balance = async(io) { addr?.let { a -> runCatching { repo.rpc.balanceLamports(a) } } }

            val p = prices.await()
            val f = feed.await()
            f.getOrNull()?.first?.let { store.decisionsJson = it }
            val h = history.await()
            val b = balance.await()
            _state.update { s ->
                s.copy(
                    prices = p.getOrNull() ?: s.prices,
                    pricesError = p.exceptionOrNull()?.let { "Prices unavailable: ${it.message}" },
                    smt = f.getOrNull()?.second ?: s.smt,
                    smtError = f.exceptionOrNull()?.let { "SMT's feed did not answer; showing the last copy." },
                    calls = h?.getOrNull() ?: s.calls,
                    historyError = h?.exceptionOrNull()?.let { "Devnet did not answer: ${it.message}" },
                    balanceSol = b?.getOrNull()?.let { it.toDouble() / DevnetRpc.LAMPORTS_PER_SOL } ?: s.balanceSol,
                    loading = false,
                )
            }
            if (f.isFailure) Log.w(Repo.TAG, "[FEED] SMT feed fetch failed, using cache: ${f.exceptionOrNull()?.message}")
            gradeDue()
        }
    }

    private suspend fun gradeDue() {
        val newly = withContext(Dispatchers.IO) { repo.gradeDue() }
        if (newly.isNotEmpty()) _state.update { it.copy(grades = store.grades) }
    }

    /** A wallet app may be installed while we are in the background (Fire TV never has one). */
    fun recheckWallet() {
        val has = hasWalletApp(getApplication())
        if (has != _state.value.walletApp) _state.update { it.copy(walletApp = has) }
    }

    fun selectCoin(c: String) = _state.update { it.copy(coin = c, pick = null, send = SendState.Idle) }
    fun pick(d: Dir) = _state.update { it.copy(pick = d) }
    fun resetSend() = _state.update { it.copy(send = SendState.Idle, pick = null) }

    fun setReminders(on: Boolean) {
        store.remindersOn = on
        if (on) Reminders.scheduleDaily(getApplication())
        _state.update { it.copy(remindersOn = on) }
    }

    fun connect(sender: ActivityResultSender) {
        viewModelScope.launch {
            _state.update { it.copy(send = SendState.InWallet) }
            when (val r = withAuthRetry { wallet.connect(sender) }) {
                is TransactionResult.Success -> {
                    rememberWallet(r.authResult.accounts.first().publicKey)
                    _state.update { it.copy(send = SendState.Idle) }
                    refresh()
                }
                is TransactionResult.NoWalletFound -> _state.update { it.copy(walletApp = false, send = SendState.Idle) }
                is TransactionResult.Failure -> _state.update { it.copy(send = SendState.Failed(r.message)) }
            }
        }
    }

    fun forgetWallet() {
        store.clearWallet()
        wallet.authToken = null
        _state.update { it.copy(address = null, balanceSol = null, calls = emptyList(), grades = emptyMap(), send = SendState.Idle) }
    }

    /**
     * The Clock In: price + SMT's call are fetched first (SMT's stays hidden until the user has
     * committed), then the wallet signs and sends one memo transaction holding both calls.
     */
    fun clockIn(sender: ActivityResultSender) {
        val s = _state.value
        val dir = s.pick ?: return
        val coin = s.coin
        viewModelScope.launch {
            _state.update { it.copy(send = SendState.Preparing) }
            val prepared = withContext(Dispatchers.IO) {
                runCatching {
                    val quote = Prices.spot(coin)
                    val feed = runCatching { SmtFeed.fetch() }.getOrNull()
                    feed?.first?.let { store.decisionsJson = it }
                    val smt = feed?.second?.get(coin) ?: _state.value.smt[coin]
                    Triple(quote, smt, repo.rpc.latestBlockhash())
                }
            }
            val (quote, smt, blockhash) = prepared.getOrElse { e ->
                _state.update { it.copy(send = SendState.Failed("Could not reach the network: ${e.message}")) }
                return@launch
            }
            val call = buildCall(coin, dir, quote, smt)
            _state.update { it.copy(send = SendState.InWallet) }
            val r = withAuthRetry {
                wallet.transact(sender) { auth ->
                    val pk = auth.accounts.first().publicKey
                    val tx = MemoTransaction.unsigned(pk, blockhash, call.toMemo())
                    pk to signAndSendTransactions(arrayOf(tx)).signatures.first()
                }
            }
            when (r) {
                is TransactionResult.Success -> {
                    val (pk, sigBytes) = r.payload
                    rememberWallet(pk)
                    val sig = Base58.encode(sigBytes)
                    val oc = OnChainCall(sig, System.currentTimeMillis() / 1000, call)
                    repo.addLocal(oc)
                    Reminders.scheduleGrade(getApplication(), oc.dueAt)
                    Log.i(Repo.TAG, "[CLOCKIN] sent ${call.toMemo()} sig=$sig")
                    _state.update {
                        it.copy(send = SendState.Done(sig), lastSent = oc, calls = store.calls,
                            smt = store.decisionsJson?.let { j -> runCatching { SmtFeed.parse(j) }.getOrNull() } ?: it.smt,
                            nowSec = System.currentTimeMillis() / 1000)
                    }
                    delay(8000)
                    refresh()
                }
                is TransactionResult.NoWalletFound -> _state.update { it.copy(walletApp = false, send = SendState.Idle) }
                is TransactionResult.Failure -> {
                    Log.w(Repo.TAG, "[CLOCKIN] failed: ${r.message}", r.e)
                    _state.update { it.copy(send = SendState.Failed(friendly(r.message, r.e))) }
                }
            }
        }
    }

    private fun buildCall(coin: String, dir: Dir, quote: Quote, smt: SmtCall?): ClockInCall {
        val now = System.currentTimeMillis() / 1000
        val fresh = smt?.asOfEpochSec?.let { now - it < STALE_AFTER_SEC } ?: false
        return when {
            smt == null -> ClockInCall(coin, dir, quote.px, quote.source, Lean.FLAT, 0, "NOFEED")
            !fresh -> ClockInCall(coin, dir, quote.px, quote.source, Lean.FLAT, smt.convictionPct, "STALE")
            else -> ClockInCall(coin, dir, quote.px, quote.source, smt.lean, smt.convictionPct, smt.action)
        }
    }

    private fun rememberWallet(pk: ByteArray) {
        val addr = Base58.encode(pk)
        if (store.address != addr) {
            store.clearWallet()
            _state.update { it.copy(calls = emptyList(), grades = emptyMap()) }
        }
        store.address = addr
        store.authToken = wallet.authToken
        _state.update { it.copy(address = addr) }
    }

    /** A wallet can drop our saved session; on "Auth token invalid" forget it and ask once more. */
    private suspend fun <T> withAuthRetry(op: suspend () -> TransactionResult<T>): TransactionResult<T> {
        val first = op()
        if (first is TransactionResult.Failure && first.message.contains("Auth token", ignoreCase = true)) {
            wallet.authToken = null
            store.authToken = null
            return op()
        }
        return first
    }

    private fun friendly(msg: String, e: Exception): String = when {
        msg.contains("did not authorize", true) || msg.contains("declined", true) -> "You cancelled in the wallet. Nothing was sent."
        msg.contains("not all transactions were submitted", true) || msg.contains("Not submitted", true) ->
            "The wallet signed but could not send. Is it on Devnet, with a little devnet SOL? (faucet.solana.com)"
        else -> "$msg${e.cause?.message?.let { " ($it)" } ?: ""}"
    }

    companion object {
        /** SMT's feed is written every cycle; older than this it is not graded as SMT's call. */
        const val STALE_AFTER_SEC = 6 * 3600L

        /** Same probe MWA itself sends: a VIEW intent on the solana-wallet: scheme. */
        fun hasWalletApp(ctx: Context): Boolean {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("solana-wallet:/v1/associate/local?association=x&port=1"))
                .addCategory(Intent.CATEGORY_BROWSABLE)
            return ctx.packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY) != null
        }
    }
}
