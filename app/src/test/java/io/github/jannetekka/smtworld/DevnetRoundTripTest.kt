package io.github.jannetekka.smtworld

import io.github.jannetekka.smtworld.clockin.ClockInCall
import io.github.jannetekka.smtworld.clockin.Dir
import io.github.jannetekka.smtworld.clockin.Lean
import io.github.jannetekka.smtworld.solana.Base58
import io.github.jannetekka.smtworld.solana.DevnetRpc
import io.github.jannetekka.smtworld.solana.MemoTransaction
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.NamedParameterSpec

/**
 * Checks our hand-built memo transaction against real Solana devnet, off by default:
 *   DEVNET_E2E=1 ./gradlew testDebugUnitTest --tests '*DevnetRoundTrip*'
 *
 * Stage 1 (always): devnet simulates the signed transaction with signature checks on. Any
 *   wire-format or signing mistake fails here, before a lamport is spent.
 * Stage 2 (when the test key holds devnet SOL): send it, wait for confirmation, and read the
 *   memo back through getSignaturesForAddress, the call the app's history uses.
 * The throwaway key's seed is kept in DEVNET_E2E_KEYFILE (default: build/devnet-e2e-seed.hex)
 * so it can be funded once from faucet.solana.com when the RPC airdrop is rate-limited.
 */
class DevnetRoundTripTest {
    @Test fun memoTransactionOnDevnet() {
        assumeTrue("set DEVNET_E2E=1 to run", System.getenv("DEVNET_E2E") == "1")
        val rpc = DevnetRpc()
        val kp = loadOrCreateKey()
        val pub = kp.public.encoded.takeLast(32).toByteArray()      // X.509 prefix + 32-byte key
        val addr = Base58.encode(pub)
        println("[E2E] test address $addr")

        val call = ClockInCall("SOL", Dir.UP, 151.23, "binance", Lean.DOWN, 41, "WAIT")
        val unsigned = MemoTransaction.unsigned(pub, rpc.latestBlockhash(), call.toMemo())
        val sig = Signature.getInstance("Ed25519").apply { initSign(kp.private); update(MemoTransaction.messageOf(unsigned)) }.sign()
        val b64 = java.util.Base64.getEncoder().encodeToString(MemoTransaction.withSignature(unsigned, sig))

        // Stage 1: simulate with signature verification.
        val sim = rpc.raw("simulateTransaction", JSONArray().put(b64)
            .put(JSONObject().put("encoding", "base64").put("sigVerify", true).put("commitment", "confirmed")))
            .getJSONObject("result").getJSONObject("value")
        val err = if (sim.isNull("err")) null else sim.get("err").toString()
        println("[E2E] simulate err=$err logs=${sim.optJSONArray("logs")}")
        assertTrue("devnet rejected the transaction itself: $err", err == null || err.contains("AccountNotFound"))

        // Stage 1b: run both instructions as a funded wallet would, without its key: signature
        // checks off, any funded devnet address as the payer (DEVNET_E2E_FUNDED_PAYER).
        System.getenv("DEVNET_E2E_FUNDED_PAYER")?.let { funded ->
            val asFunded = MemoTransaction.unsigned(Base58.decode(funded), rpc.latestBlockhash(), call.toMemo())
            val run = rpc.raw("simulateTransaction", JSONArray().put(java.util.Base64.getEncoder().encodeToString(asFunded))
                .put(JSONObject().put("encoding", "base64").put("sigVerify", false).put("replaceRecentBlockhash", true)))
                .getJSONObject("result").getJSONObject("value")
            val logs = run.optJSONArray("logs").toString()
            println("[E2E] as $funded: err=${run.opt("err")} logs=$logs")
            assertTrue("execution failed: ${run.opt("err")}", run.isNull("err"))
            assertTrue(logs.contains("Program ${MemoTransaction.MEMO_PROGRAM_ID} success"))
            assertTrue(logs.contains("Program ${MemoTransaction.MEMO_V1_PROGRAM_ID} success"))
        }

        // Stage 2: a real send, if the key is funded (or the airdrop answers).
        var balance = rpc.balanceLamports(addr)
        if (balance < 100_000) {
            runCatching { waitConfirmed(rpc, rpc.raw("requestAirdrop", JSONArray().put(addr).put(20_000_000L)).getString("result")) }
                .onFailure { println("[E2E] airdrop refused: ${it.message}") }
            balance = rpc.balanceLamports(addr)
        }
        assumeTrue("stage 2 skipped: fund $addr with a little devnet SOL (faucet.solana.com) and rerun", balance >= 100_000)
        assertTrue(err == null)

        val sent = rpc.raw("sendTransaction", JSONArray().put(b64).put(JSONObject().put("encoding", "base64"))).getString("result")
        assertEquals(Base58.encode(sig), sent)
        waitConfirmed(rpc, sent)
        println("[E2E] memo confirmed ${DevnetRpc.explorerTx(sent)}")

        var found: ClockInCall? = null
        for (i in 0 until 20) {
            found = rpc.signaturesForAddress(addr).firstOrNull { it.signature == sent }?.let { ClockInCall.parse(it.memo) }
            if (found != null) break
            Thread.sleep(1500)
        }
        assertEquals(call, found)
        println("[E2E] read back: ${found!!.toMemo()}")
    }

    /** Ed25519 from a stored 32-byte seed: the generator draws exactly the seed from "random". */
    private fun loadOrCreateKey(): KeyPair {
        val f = File(System.getenv("DEVNET_E2E_KEYFILE") ?: "build/devnet-e2e-seed.hex")
        val seed = if (f.exists()) f.readText().trim().chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                   else ByteArray(32).also { SecureRandom().nextBytes(it); f.parentFile?.mkdirs(); f.writeText(it.joinToString("") { b -> "%02x".format(b) }) }
        val fixed = object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) { seed.copyInto(bytes, 0, 0, bytes.size) }
        }
        return KeyPairGenerator.getInstance("Ed25519").apply { initialize(NamedParameterSpec.ED25519, fixed) }.generateKeyPair()
    }

    private fun waitConfirmed(rpc: DevnetRpc, signature: String) {
        repeat(40) {
            val v = rpc.raw("getSignatureStatuses", JSONArray().put(JSONArray().put(signature))).getJSONObject("result").getJSONArray("value")
            if (!v.isNull(0)) {
                val s = v.getJSONObject(0)
                check(s.isNull("err")) { "transaction failed: ${s.get("err")}" }
                if (s.optString("confirmationStatus") in setOf("confirmed", "finalized")) return
            }
            Thread.sleep(1500)
        }
        error("not confirmed in time: $signature")
    }
}
