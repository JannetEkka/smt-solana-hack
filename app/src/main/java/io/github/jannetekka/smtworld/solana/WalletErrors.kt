package io.github.jannetekka.smtworld.solana

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

    fun friendly(msg: String): String = when {
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
