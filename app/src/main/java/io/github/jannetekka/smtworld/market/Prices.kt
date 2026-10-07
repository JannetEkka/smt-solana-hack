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

    val COINS = listOf("BTC", "ETH", "SOL", "BNB", "XRP", "LTC", "ADA", "DOGE")
    private val CG_IDS = mapOf(
        "BTC" to "bitcoin", "ETH" to "ethereum", "SOL" to "solana", "BNB" to "binancecoin",
        "XRP" to "ripple", "LTC" to "litecoin", "ADA" to "cardano", "DOGE" to "dogecoin",
    )
    private const val BN = "https://data-api.binance.vision/api/v3"
    private const val CG = "https://api.coingecko.com/api/v3"

    /** Latest price for each coin, one request per source. */
    fun spotAll(): Map<String, Quote> = try {
        val symbols = JSONArray(COINS.map { "${it}USDT" }).toString()
        val arr = JSONArray(Http.get("$BN/ticker/price?symbols=" + java.net.URLEncoder.encode(symbols, "UTF-8")))
        (0 until arr.length()).associate { i ->
            val o = arr.getJSONObject(i)
            o.getString("symbol").removeSuffix("USDT") to Quote(o.getString("price").toDouble(), BINANCE)
        }
    } catch (e: Exception) {
        val ids = COINS.joinToString(",") { CG_IDS.getValue(it) }
        val o = JSONObject(Http.get("$CG/simple/price?vs_currencies=usd&ids=$ids"))
        COINS.mapNotNull { c -> o.optJSONObject(CG_IDS.getValue(c))?.let { c to Quote(it.getDouble("usd"), COINGECKO) } }.toMap()
    }

    fun spot(coin: String): Quote = spotAll()[coin] ?: throw IllegalStateException("no price for $coin")

    /** The price at a past moment from one named source, or null if that source has no data yet. */
    fun at(coin: String, epochSec: Long, source: String): Quote? = when (source) {
        BINANCE -> binanceAt(coin, epochSec)
        COINGECKO -> coingeckoAt(coin, epochSec)
        else -> null
    }

    /** The 1-minute candle that opens at or after the moment; its open is the price then. */
    private fun binanceAt(coin: String, epochSec: Long): Quote? {
        val arr = JSONArray(Http.get("$BN/klines?symbol=${coin}USDT&interval=1m&startTime=${epochSec * 1000}&limit=1"))
        if (arr.length() == 0) return null
        return Quote(arr.getJSONArray(0).getString(1).toDouble(), BINANCE)
    }

    /** CoinGecko's nearest point within 45 minutes (5-minute data for the last day, hourly before). */
    private fun coingeckoAt(coin: String, epochSec: Long): Quote? {
        val id = CG_IDS[coin] ?: return null
        val o = JSONObject(Http.get("$CG/coins/$id/market_chart/range?vs_currency=usd&from=${epochSec - 2700}&to=${epochSec + 2700}"))
        val prices = o.optJSONArray("prices") ?: return null
        var best: Pair<Long, Double>? = null
        for (i in 0 until prices.length()) {
            val p = prices.getJSONArray(i)
            val dt = kotlin.math.abs(p.getLong(0) / 1000 - epochSec)
            if (best == null || dt < best.first) best = dt to p.getDouble(1)
        }
        return best?.let { Quote(it.second, COINGECKO) }
    }
}
