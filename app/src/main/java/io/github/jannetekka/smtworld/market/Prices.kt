package io.github.jannetekka.smtworld.market

import io.github.jannetekka.smtworld.data.Http
import org.json.JSONArray
import org.json.JSONObject

data class Quote(val px: Double, val source: String)

/**
 * Prices from public, keyless APIs. Binance's public market-data host first; CoinGecko when
 * Binance refuses (it answers HTTP 451 in some countries, the US among them). The source
 * travels with every number so a grade never mixes the two.
 */
object Prices {
    const val BINANCE = "binance"
    const val COINGECKO = "coingecko"

    /** The 8 coins SMT trades and calls. */
    val COINS = listOf("BTC", "ETH", "SOL", "BNB", "XRP", "LTC", "ADA", "DOGE")
    /** SKR, the Solana Mobile ecosystem's token: playable, but SMT doesn't call it. Not on Binance. */
    const val SKR = "SKR"
    const val SKR_MINT = "SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3"
    /** What a player can call: SMT's 8 coins, then SKR. */
    val GAME_COINS = COINS + SKR
    private val CG_IDS = mapOf(
        "BTC" to "bitcoin", "ETH" to "ethereum", "SOL" to "solana", "BNB" to "binancecoin",
        "XRP" to "ripple", "LTC" to "litecoin", "ADA" to "cardano", "DOGE" to "dogecoin",
        SKR to "seeker",
    )
    private const val BN = "https://data-api.binance.vision/api/v3"
    private const val CG = "https://api.coingecko.com/api/v3"

    /**
     * Latest price for every playable coin: SMT's 8 from Binance (CoinGecko if Binance refuses),
     * SKR from CoinGecko. A failure on SKR never costs the other 8.
     */
    fun spotAll(): Map<String, Quote> {
        val main = try {
            val symbols = JSONArray(COINS.map { "${it}USDT" }).toString()
            val arr = JSONArray(Http.get("$BN/ticker/price?symbols=" + java.net.URLEncoder.encode(symbols, "UTF-8")))
            (0 until arr.length()).associate { i ->
                val o = arr.getJSONObject(i)
                o.getString("symbol").removeSuffix("USDT") to Quote(o.getString("price").toDouble(), BINANCE)
            }
        } catch (e: Exception) {
            coingeckoSpot(GAME_COINS)
        }
        if (SKR in main) return main
        return main + (runCatching { coingeckoSpot(listOf(SKR)) }.getOrNull() ?: emptyMap())
    }

    private fun coingeckoSpot(coins: List<String>): Map<String, Quote> {
        val o = JSONObject(Http.get("$CG/simple/price?vs_currencies=usd&ids=" + coins.joinToString(",") { CG_IDS.getValue(it) }))
        return coins.mapNotNull { c -> o.optJSONObject(CG_IDS.getValue(c))?.let { c to Quote(it.getDouble("usd"), COINGECKO) } }.toMap()
    }

    fun spot(coin: String): Quote = spotAll()[coin] ?: throw IllegalStateException("no price for $coin")

    /**
     * The price at a past moment from one named source, or null if that source has no data yet.
     * [notBefore]: only a price from at or after the moment counts (grading the end of a horizon
     * on an earlier price would grade a shorter move). Binance's 1-minute candle already is.
     */
    fun at(coin: String, epochSec: Long, source: String, notBefore: Boolean = false): Quote? = when (source) {
        BINANCE -> binanceAt(coin, epochSec)
        COINGECKO -> coingeckoAt(coin, epochSec, notBefore)
        else -> null
    }

    /** The 1-minute candle that opens at or after the moment; its open is the price then. */
    private fun binanceAt(coin: String, epochSec: Long): Quote? {
        if (coin !in COINS) return null                    // SKR isn't listed on Binance
        val arr = JSONArray(Http.get("$BN/klines?symbol=${coin}USDT&interval=1m&startTime=${epochSec * 1000}&limit=1"))
        if (arr.length() == 0) return null
        return Quote(arr.getJSONArray(0).getString(1).toDouble(), BINANCE)
    }

    /** CoinGecko's nearest point within 45 minutes (5-minute data for the last day, hourly before). */
    private fun coingeckoAt(coin: String, epochSec: Long, notBefore: Boolean): Quote? {
        val id = CG_IDS[coin] ?: return null
        val o = JSONObject(Http.get("$CG/coins/$id/market_chart/range?vs_currency=usd&from=${epochSec - 2700}&to=${epochSec + 2700}"))
        val prices = o.optJSONArray("prices") ?: return null
        var best: Pair<Long, Double>? = null
        for (i in 0 until prices.length()) {
            val p = prices.getJSONArray(i)
            val t = p.getLong(0) / 1000
            if (notBefore && t < epochSec - 60) continue
            val dt = kotlin.math.abs(t - epochSec)
            if (best == null || dt < best.first) best = dt to p.getDouble(1)
        }
        return best?.let { Quote(it.second, COINGECKO) }
    }
}
