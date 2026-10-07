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
import io.github.jannetekka.smtworld.clockin.Leaderboard
import io.github.jannetekka.smtworld.clockin.OnChainCall
import io.github.jannetekka.smtworld.clockin.Streak
import io.github.jannetekka.smtworld.data.Repo
import io.github.jannetekka.smtworld.market.Prices
import io.github.jannetekka.smtworld.market.Quote
import io.github.jannetekka.smtworld.notify.Reminders
import io.github.jannetekka.smtworld.widget.StreakWidget
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
    /** SMT's call exactly as it was when the memo was built: what the reveal shows. */
    val revealed: SmtCall? = null,
    val remindersOn: Boolean = true,
    /** SKR held by this wallet on mainnet (read-only); null until read. */
    val skrBalance: Double? = null,
    val board: List<Leaderboard.Entry> = emptyList(),
    val boardLoading: Boolean = false,
    val boardError: String? = null,
    val nowSec: Long = System.currentTimeMillis() / 1000,
) {
    val boardRows: List<Leaderboard.Row> by lazy { Leaderboard.rows(board, grades) }
    // Derived once per state, not on every recomposition.
    val days: Set<Long> by lazy { calls.map { Streak.localDay(it.blockTime) }.toSet() }
    val today: Long by lazy { Streak.localDay(nowSec) }
    val streak: Int by lazy { Streak.current(days, today) }
    val bestStreak: Int by lazy { Streak.best(days) }
    val clockedInToday: Boolean get() = today in days
    private val shownGrades by lazy { calls.map { it.signature }.toSet().let { sigs -> grades.filterKeys { it in sigs }.values } }
    val scoreYou by lazy { Grading.scoreYou(shownGrades) }
    val scoreSmt by lazy { Grading.scoreSmt(shownGrades) }
    /** Hours since SMT's feed for the chosen coin was written; null if unknown. */
    fun smtAgeHours(coin: String): Double? = smt[coin]?.asOfEpochSec?.let { ((nowSec - it) / 3600.0).coerceAtLeast(0.0) }
}

/** What the screens can ask for. Kept separate so screens render (and screenshot) without a view model. */
interface UiActions {
    fun refresh()
    fun refreshBoard() {}
    fun selectCoin(c: String)
    fun pick(d: Dir)
    fun resetSend()
    fun setReminders(on: Boolean)
}

class AppViewModel(app: Application) : AndroidViewModel(app), UiActions {
    private val repo = Repo(app)
    private val store = repo.store
    private val wallet = MobileWalletAdapter(
        ConnectionIdentity(
            identityUri = Uri.parse(SmtFeed.SITE.removeSuffix("/")),
            iconUri = Uri.parse("smt_logo.png"),
            identityName = "SMT World",
        ),
        // 3 minutes per wallet request (the library's default is 90 s): a first-time user reads
        // the wallet's "unknown site" warning before approving, and that took longer than 90 s.
        timeout = 180_000,
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
            board = repo.boardEntries(),
        )
    )
    val state: StateFlow<UiState> = _state

    init {
        Reminders.createChannels(app)
        if (store.remindersOn) Reminders.scheduleDaily(app)
        Log.i(Repo.TAG, "[START] walletApp=${_state.value.walletApp} address=${store.address} cachedCalls=${store.calls.size}")
        refresh()
    }

    override fun refresh() {
        lastRefreshMs = System.currentTimeMillis()
        viewModelScope.launch {
            _state.update { it.copy(loading = true, nowSec = System.currentTimeMillis() / 1000) }
            val io = Dispatchers.IO
            val prices = async(io) { runCatching { Prices.spotAll() } }
            val feed = async(io) { runCatching { SmtFeed.fetch() } }
            val addr = store.address
            val history = async(io) { addr?.let { a -> runCatching { repo.syncHistory(a) } } }
            val balance = async(io) { addr?.let { a -> runCatching { repo.rpc.balanceLamports(a) } } }
            val readSkr = addr != null && (addr != skrFor || System.currentTimeMillis() - skrReadMs > 600_000)
            val skr = async(io) { if (readSkr) runCatching { mainnet.tokenBalance(addr!!, Prices.SKR_MINT) } else null }

            val p = prices.await()
            val f = feed.await()
            f.getOrNull()?.first?.let { store.decisionsJson = it }
            val h = history.await()
            val b = balance.await()
            val k = skr.await()
            if (k?.isSuccess == true) { skrFor = addr; skrReadMs = System.currentTimeMillis() }
            _state.update { s ->
                s.copy(
                    prices = p.getOrNull() ?: s.prices,
                    pricesError = p.exceptionOrNull()?.let { "Prices unavailable: ${it.message}" },
                    smt = f.getOrNull()?.second ?: s.smt,
                    smtError = f.exceptionOrNull()?.let { "SMT's feed did not answer; showing the last copy." },
                    calls = if (h?.isSuccess == true) store.calls else s.calls,
                    historyError = h?.exceptionOrNull()?.let { "Devnet did not answer: ${it.message}" },
                    balanceSol = b?.getOrNull()?.let { it.toDouble() / DevnetRpc.LAMPORTS_PER_SOL } ?: s.balanceSol,
                    skrBalance = k?.getOrNull() ?: s.skrBalance,
                    loading = false,
                )
            }
            if (f.isFailure) Log.w(Repo.TAG, "[FEED] SMT feed fetch failed, using cache: ${f.exceptionOrNull()?.message}")
            if (h?.isSuccess == true) StreakWidget.refresh(getApplication())
            if (boardRefreshedMs > 0) refreshBoard() else gradeDue()   // the board run grades too
        }
    }

    private var boardRefreshedMs = 0L

    /** Reads every player's Clock Ins from the registry address, then grades what's due. */
    override fun refreshBoard() {
        if (_state.value.boardLoading) return
        boardRefreshedMs = System.currentTimeMillis()
        viewModelScope.launch {
            _state.update { it.copy(boardLoading = true) }
            val r = withContext(Dispatchers.IO) { runCatching { repo.syncLeaderboard() } }
            _state.update { it.copy(board = r.getOrNull() ?: it.board, boardLoading = false,
                boardError = r.exceptionOrNull()?.let { e -> "Devnet did not answer: ${e.message}" }) }
            gradeDue()
        }
    }

    private val grading = kotlinx.coroutines.sync.Mutex()

    /** One grading run at a time: overlapping runs would fetch and grade the same calls twice. */
    private suspend fun gradeDue() {
        if (!grading.tryLock()) return
        try {
            val newly = withContext(Dispatchers.IO) { repo.gradeDue() }
            if (newly.isNotEmpty()) _state.update { it.copy(grades = store.grades) }
        } finally {
            grading.unlock()
        }
    }

    private var lastRefreshMs = 0L

    /** Mainnet is read for one thing, the SKR balance, and at most every 10 minutes (public RPC limits). */
    private val mainnet = DevnetRpc(DevnetRpc.MAINNET)
    private var skrFor: String? = null
    private var skrReadMs = 0L

    /**
     * Back in the foreground: a wallet app may have been installed meanwhile (Fire TV never has
     * one), and if the app sat in the background, "today", the streak and due grades have moved.
     */
    fun onResume() {
        val has = hasWalletApp(getApplication())
        if (has != _state.value.walletApp) _state.update { it.copy(walletApp = has) }
        val busy = _state.value.send.let { it is SendState.Preparing || it is SendState.InWallet }
        if (!busy && System.currentTimeMillis() - lastRefreshMs > 120_000) refresh()
    }

    override fun selectCoin(c: String) = _state.update {
        // Picking a coin starts a new call; keep the reveal of the one just sent until then.
        if (it.send is SendState.Done) it.copy(coin = c, pick = null) else it.copy(coin = c, pick = null, send = SendState.Idle)
    }
    override fun pick(d: Dir) = _state.update { it.copy(pick = d) }
    override fun resetSend() = _state.update { it.copy(send = SendState.Idle, pick = null, revealed = null) }

    override fun setReminders(on: Boolean) {
        store.remindersOn = on
        if (on) Reminders.scheduleDaily(getApplication()) else Reminders.cancelDaily(getApplication())
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
        StreakWidget.refresh(getApplication())
        _state.update { it.copy(address = null, balanceSol = null, skrBalance = null, calls = emptyList(), send = SendState.Idle) }
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
                    quote to smt
                }
            }
            val (quote, smt) = prepared.getOrElse { e ->
                _state.update { it.copy(send = SendState.Failed("Could not reach the network: ${e.message}")) }
                return@launch
            }
            val call = buildCall(coin, dir, quote, smt)
            val snapshot = smt
            _state.update { it.copy(send = SendState.InWallet) }
            val r = withAuthRetry {
                wallet.transact(sender) { auth ->
                    val pk = auth.accounts.first().publicKey
                    // Fetched here, after the wallet has authorized, so the time the user spends
                    // approving the connection can't expire it (a blockhash lives about a minute).
                    val blockhash = repo.rpc.latestBlockhash()
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
                    StreakWidget.refresh(getApplication())
                    Log.i(Repo.TAG, "[CLOCKIN] sent ${call.toMemo()} sig=$sig")
                    _state.update {
                        it.copy(send = SendState.Done(sig), lastSent = oc, revealed = snapshot, calls = store.calls,
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
            coin !in Prices.COINS -> ClockInCall(coin, dir, quote.px, quote.source, Lean.FLAT, 0, "NOCALL")
            smt == null -> ClockInCall(coin, dir, quote.px, quote.source, Lean.FLAT, 0, "NOFEED")
            !fresh -> ClockInCall(coin, dir, quote.px, quote.source, Lean.FLAT, smt.convictionPct, "STALE")
            else -> ClockInCall(coin, dir, quote.px, quote.source, smt.lean, smt.convictionPct, smt.action)
        }
    }

    private fun rememberWallet(pk: ByteArray) {
        val addr = Base58.encode(pk)
        if (store.address != addr) {
            store.clearWallet()
            _state.update { it.copy(calls = emptyList(), skrBalance = null) }
            StreakWidget.refresh(getApplication())
        }
        store.address = addr
        store.authToken = wallet.authToken
        _state.update { it.copy(address = addr) }
    }

    /** A wallet can drop our saved session; on "Auth token invalid" forget it and ask once more. */
    private suspend fun <T> withAuthRetry(op: suspend () -> TransactionResult<T>): TransactionResult<T> {
        var first = guarded(op)
        // A wallet starting cold (or asking for its PIN) can miss MWA's 10-second window to open
        // the local connection. Nothing was signed at that stage, so one retry is safe; the
        // wallet is awake by then. Never retried after signing has started (no double send).
        if (first is TransactionResult.Failure && isAssociationFailure(first.message)) {
            Log.w(Repo.TAG, "[WALLET] association failed (${first.message}); retrying once")
            first = guarded(op)
        }
        if (first is TransactionResult.Failure && first.message.contains("Auth token", ignoreCase = true)) {
            wallet.authToken = null
            store.authToken = null
            return guarded(op)
        }
        return first
    }

    /**
     * MWA turns its own errors into Failure, but an exception thrown inside our block (the devnet
     * blockhash request is an IOException) escapes it; catch it here so it can't crash the app.
     */
    private suspend fun <T> guarded(op: suspend () -> TransactionResult<T>): TransactionResult<T> = try {
        op()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(Repo.TAG, "[WALLET] ${e.javaClass.simpleName}: ${e.message}")
        TransactionResult.Failure("Network error: ${e.message}", e)
    }

    private fun isAssociationFailure(msg: String) =
        msg.contains("local association", true) || msg.contains("association intent", true)

    private fun friendly(msg: String, e: Exception): String = when {
        msg.contains("did not authorize", true) || msg.contains("declined", true) ||
            msg.contains("interrupted", true) || msg.contains("cancelled", true) -> "Cancelled in the wallet. Nothing was sent."
        isAssociationFailure(msg) -> "Couldn't reach the wallet app. Open Solflare or Phantom once, then tap Clock in again."
        msg.contains("Timed out", true) -> "The wallet didn't answer in time. Nothing was sent. Try again."
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
