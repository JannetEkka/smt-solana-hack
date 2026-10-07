package io.github.jannetekka.smtworld.solana

import java.io.ByteArrayOutputStream

/**
 * Builds the one transaction this app sends, paid and signed by the user's wallet. Legacy
 * wire format, written by hand so there is no dependency to trust and every byte is covered by
 * a unit test against an independent library and a devnet simulation.
 *
 * Two instructions:
 *  1. SPL Memo v2 with the Clock In text, the payer as its account: the Memo program checks the
 *     payer signed, and explorers show "Signed by <wallet>".
 *  2. SPL Memo v1 with a short tag and the shared [REGISTRY] address as its account. v1 doesn't
 *     check signers, so the registry can be listed without a key. Because an instruction uses
 *     it, the registry survives a wallet rebuilding the transaction (Solflare adds priority-fee
 *     instructions), and every Clock In by every player is indexed under it: the leaderboard
 *     is `getSignaturesForAddress(REGISTRY)`, with no server.
 *
 * Message: header(1 signer, 0 readonly signed, 3 readonly unsigned)
 *          · keys [payer, registry, memo v2, memo v1] · recent blockhash
 *          · ix(program=2, accounts=[0], text) · ix(program=3, accounts=[1], tag)
 */
object MemoTransaction {
    const val MEMO_PROGRAM_ID = "MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr"
    const val MEMO_V1_PROGRAM_ID = "Memo1UhkJRfHyvLMcVucJwxXeuD728EqVDDwQDxFMNo"
    /** sha256("SMT Clock In registry v1"): a fixed address nobody holds a key for. */
    const val REGISTRY = "2Hbn8xzmfySdmSYfwaCc5EtieZan29SGFaNNj2f1ZLVg"
    const val REGISTRY_TAG = "SMT Clock In"
    /** Solana's packet limit for a whole transaction. */
    const val MAX_TX_BYTES = 1232

    fun message(payer: ByteArray, recentBlockhash: ByteArray, memo: String): ByteArray {
        require(payer.size == 32) { "payer must be a 32-byte public key" }
        require(recentBlockhash.size == 32) { "blockhash must be 32 bytes" }
        val data = memo.toByteArray(Charsets.UTF_8)
        val tag = REGISTRY_TAG.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(1, 0, 3))                 // header
        writeCompactU16(out, 4)                         // account keys
        out.write(payer)
        out.write(Base58.decode(REGISTRY))
        out.write(Base58.decode(MEMO_PROGRAM_ID))
        out.write(Base58.decode(MEMO_V1_PROGRAM_ID))
        out.write(recentBlockhash)
        writeCompactU16(out, 2)                         // instructions
        out.write(2)                                    // memo v2: the Clock In, signed by the payer
        writeCompactU16(out, 1); out.write(0)
        writeCompactU16(out, data.size); out.write(data)
        out.write(3)                                    // memo v1: the tag, naming the registry
        writeCompactU16(out, 1); out.write(1)
        writeCompactU16(out, tag.size); out.write(tag)
        return out.toByteArray()
    }

    /** The unsigned wire transaction a wallet's signAndSendTransactions expects. */
    fun unsigned(payer: ByteArray, recentBlockhash: ByteArray, memo: String): ByteArray {
        val msg = message(payer, recentBlockhash, memo)
        val out = ByteArrayOutputStream()
        writeCompactU16(out, 1)
        out.write(ByteArray(64))
        out.write(msg)
        val tx = out.toByteArray()
        require(tx.size <= MAX_TX_BYTES) { "memo too long: transaction is ${tx.size} bytes" }
        return tx
    }

    /** Puts a 64-byte signature into an unsigned transaction (used by tests and the devnet check). */
    fun withSignature(unsignedTx: ByteArray, signature: ByteArray): ByteArray {
        require(signature.size == 64)
        return unsignedTx.copyOf().also { signature.copyInto(it, destinationOffset = 1) }
    }

    fun messageOf(tx: ByteArray): ByteArray = tx.copyOfRange(1 + 64, tx.size)

    fun writeCompactU16(out: ByteArrayOutputStream, value: Int) {
        require(value in 0..0xFFFF)
        var v = value
        while (true) {
            val b = v and 0x7F
            v = v ushr 7
            if (v == 0) { out.write(b); return }
            out.write(b or 0x80)
        }
    }
}
