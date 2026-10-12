package io.github.jannetekka.smtworld.solana

import com.solana.mobilewalletadapter.clientlib.protocol.JsonRpc20Client
import com.solana.mobilewalletadapter.common.ProtocolContract

/**
 * Mobile Wallet Adapter's failures in plain words, plus the library's own words for the small
 * grey line under them, so a screenshot of a failure names its cause. Plain Kotlin: unit-tested.
 */
object WalletErrors {
    /**
     * The wallet never connected back to the app. MWA 2.0.8 gives a wallet 10 seconds to connect
     * after it opens, and closes the session 5 seconds after the wallet's screen closes.
     */
    fun isAssociationFailure(msg: String) =
        msg.contains("local association", true) || msg.contains("association intent", true)

    /**
     * The wallet's own error code, wherever MWA wrapped it. MWA 2.0.8 reports a wallet that
     * answered with an error during the session's first request as "Failed establishing local
     * association with wallet", so the text alone can't tell a refusal from a wallet that never
     * connected (Jannet's phone, 12 Oct: the wallet's -1 was inside that message).
     */
    fun walletCode(e: Throwable?): Int? = generateSequence(e) { it.cause }.take(8)
        .filterIsInstance<JsonRpc20Client.JsonRpc20RemoteException>().firstOrNull()?.code

    /**
     * The wallet answered "not authorized" (-1): it refused our saved session (an expired or
     * revoked auth token), or the person declined its connect sheet. The caller tells them apart
     * by whether a saved token was in use.
     */
    fun isAuthFailure(msg: String, e: Throwable?) =
        msg.contains("Auth token", true) || walletCode(e) == ProtocolContract.ERROR_AUTHORIZATION_FAILED

    fun friendly(msg: String, e: Throwable? = null): String = when {
        walletCode(e) == ProtocolContract.ERROR_CLUSTER_NOT_SUPPORTED -> "Your wallet isn't on Devnet. Switch it to Devnet, then tap Clock in again."
        isAuthFailure(msg, e) -> "The wallet didn't authorize this app, so nothing was sent. Tap Clock in to ask again."
        msg.contains("did not authorize", true) || msg.contains("declined", true) ||
            msg.contains("interrupted", true) || msg.contains("cancelled", true) -> "Cancelled in the wallet. Nothing was sent."
        isAssociationFailure(msg) -> "Couldn't reach the wallet. Open Solflare or Phantom, unlock it, then come back and tap Clock in."
        msg.contains("Timed out", true) -> "The wallet didn't answer in time. Nothing was sent. Try again."
        msg.contains("not all transactions were submitted", true) || msg.contains("Not submitted", true) ->
            "The wallet signed but could not send. Is it on Devnet, with a little devnet SOL? (faucet.solana.com)"
        else -> msg
    }

    /** The library's message and each cause's, deduplicated: "Failed establishing … · Scenario closed while connecting". */
    fun detail(msg: String, e: Throwable?): String =
        (listOf(msg) + generateSequence(e) { it.cause }.take(4).map { it.message ?: it.javaClass.simpleName })
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(" · ")
}
