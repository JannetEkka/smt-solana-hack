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
     * The price at a past moment from one named source: the FIRST price at or after it, read the
     * same way at both ends of a call, so neither end is a price the caller had already seen.
     * Null when that source has no such price yet. (Ported from SMT World's Calls tab, whose
     * /code-review of 10-07 found the 45-minute CoinGecko window missed about a quarter of calls.)
     */
    fun at(coin: String, epochSec: Long, source: String): Quote? = when (source) {
        BINANCE -> binanceAt(coin, epochSec)
        COINGECKO -> coingeckoAt(coin, epochSec)
        else -> null
    }

    /** CoinGecko's range data is 5-minute inside the last day and hourly beyond it. */
    const val CG_WINDOW_S = 3900L

    /** The open of the 1-minute candle starting on the next whole minute, and only that candle (not one after a gap). */
    private fun binanceAt(coin: String, epochSec: Long): Quote? {
        if (coin !in COINS) return null                    // SKR isn't listed on Binance
        val start = ceilMinute(epochSec)
        val arr = JSONArray(Http.get("$BN/klines?symbol=${coin}USDT&interval=1m&startTime=${start * 1000}&limit=1"))
        if (arr.length() == 0) return null
        val k = arr.getJSONArray(0)
        if (k.getLong(0) != start * 1000) return null
        return k.getString(1).toDouble().takeIf { it > 0 }?.let { Quote(it, BINANCE) }
    }

    /** CoinGecko's first point at or after the moment, within [CG_WINDOW_S]. */
    private fun coingeckoAt(coin: String, epochSec: Long): Quote? {
        val id = CG_IDS[coin] ?: return null
        val o = JSONObject(Http.get("$CG/coins/$id/market_chart/range?vs_currency=usd&from=$epochSec&to=${epochSec + CG_WINDOW_S}"))
        val prices = o.optJSONArray("prices") ?: return null
        return firstAtOrAfter((0 until prices.length()).map { prices.getJSONArray(it).let { p -> p.getLong(0) / 1000 to p.getDouble(1) } }, epochSec)
            ?.let { Quote(it, COINGECKO) }
    }

    fun ceilMinute(epochSec: Long) = (epochSec + 59) / 60 * 60

    /** The earliest positive price at or after [epochSec] and within [CG_WINDOW_S] of it. */
    fun firstAtOrAfter(points: List<Pair<Long, Double>>, epochSec: Long): Double? =
        points.filter { (t, v) -> t >= epochSec && t <= epochSec + CG_WINDOW_S && v > 0 }.minByOrNull { it.first }?.second
}
