package io.github.jannetekka.smtworld.solana

import io.github.jannetekka.smtworld.data.Http
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** The few Solana JSON-RPC calls the app needs, against public devnet. */
class DevnetRpc(private val endpoint: String = DEVNET) {

    data class SignatureInfo(
        val signature: String,
        val blockTime: Long?,
        val memo: String?,
        val failed: Boolean,
    )

    fun latestBlockhash(): ByteArray {
        val r = call("getLatestBlockhash", JSONArray().put(JSONObject().put("commitment", "confirmed")))
        return Base58.decode(r.getJSONObject("value").getString("blockhash"))
    }

    fun balanceLamports(address: String): Long {
        val r = call("getBalance", JSONArray().put(address).put(JSONObject().put("commitment", "confirmed")))
        return r.getLong("value")
    }

    /** Newest first. The RPC returns each transaction's memo text, so history needs one request. */
    fun signaturesForAddress(address: String, limit: Int = 200): List<SignatureInfo> {
        val opts = JSONObject().put("limit", limit).put("commitment", "confirmed")
        val arr = callArray("getSignaturesForAddress", JSONArray().put(address).put(opts))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SignatureInfo(
                signature = o.getString("signature"),
                blockTime = if (o.isNull("blockTime")) null else o.getLong("blockTime"),
                memo = if (o.isNull("memo")) null else o.getString("memo"),
                failed = !o.isNull("err"),
            )
        }
    }

    // ---- plumbing

    /** One JSON-RPC request; the whole response object. Public so tests can call methods the app never needs. */
    fun raw(method: String, params: JSONArray): JSONObject {
        val req = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", method).put("params", params)
        val res = JSONObject(Http.postJson(endpoint, req.toString()))
        if (res.has("error")) throw RpcException(method, res.getJSONObject("error").toString())
        return res
    }

    private fun call(method: String, params: JSONArray): JSONObject = raw(method, params).getJSONObject("result")
    private fun callArray(method: String, params: JSONArray): JSONArray = raw(method, params).getJSONArray("result")

    companion object {
        const val DEVNET = "https://api.devnet.solana.com"
        const val LAMPORTS_PER_SOL = 1_000_000_000L
        fun explorerTx(signature: String) = "https://explorer.solana.com/tx/$signature?cluster=devnet"
        fun explorerAddress(address: String) = "https://explorer.solana.com/address/$address?cluster=devnet"
    }
}

class RpcException(method: String, detail: String) : IOException("$method failed: $detail")
