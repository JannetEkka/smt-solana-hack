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
    fun signaturesForAddress(address: String, limit: Int = 200, before: String? = null): List<SignatureInfo> {
        val opts = JSONObject().put("limit", limit).put("commitment", "confirmed")
        if (before != null) opts.put("before", before)
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

    /**
     * The fee payer (the player) of each transaction, in batched requests of [chunk]. Responses in a
     * batch can come back in any order, so they are matched by id. Unknown or pruned ones are left out.
     */
    fun feePayers(signatures: List<String>, chunk: Int = 25): Map<String, String> {
        val out = HashMap<String, String>()
        for (part in signatures.chunked(chunk)) {
            val batch = JSONArray()
            part.forEachIndexed { i, sig ->
                batch.put(JSONObject().put("jsonrpc", "2.0").put("id", i).put("method", "getTransaction")
                    .put("params", JSONArray().put(sig).put(JSONObject().put("encoding", "json")
                        .put("maxSupportedTransactionVersion", 0).put("commitment", "confirmed"))))
            }
            val res = JSONArray(Http.postJson(endpoint, batch.toString(), timeoutMs = 20_000))
            for (k in 0 until res.length()) {
                val r = res.getJSONObject(k)
                val tx = r.optJSONObject("result") ?: continue
                val keys = tx.getJSONObject("transaction").getJSONObject("message").getJSONArray("accountKeys")
                out[part[r.getInt("id")]] = keys.getString(0)
            }
        }
        return out
    }

    /** Total balance of one SPL token held by [owner] (all its token accounts), in whole tokens. */
    fun tokenBalance(owner: String, mint: String): Double {
        val r = call("getTokenAccountsByOwner", JSONArray().put(owner).put(JSONObject().put("mint", mint))
            .put(JSONObject().put("encoding", "jsonParsed").put("commitment", "confirmed")))
        val accounts = r.getJSONArray("value")
        return (0 until accounts.length()).sumOf { i ->
            accounts.getJSONObject(i).getJSONObject("account").getJSONObject("data").getJSONObject("parsed")
                .getJSONObject("info").getJSONObject("tokenAmount").optDouble("uiAmount", 0.0)
        }
    }

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
        /** Mainnet, read-only: the app only ever reads balances here and never sends anything. */
        const val MAINNET = "https://api.mainnet-beta.solana.com"
        const val LAMPORTS_PER_SOL = 1_000_000_000L
        fun explorerTx(signature: String) = "https://explorer.solana.com/tx/$signature?cluster=devnet"
        fun explorerAddress(address: String) = "https://explorer.solana.com/address/$address?cluster=devnet"
    }
}

class RpcException(method: String, detail: String) : IOException("$method failed: $detail")
