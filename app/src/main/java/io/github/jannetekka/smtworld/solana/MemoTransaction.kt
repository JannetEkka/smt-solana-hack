package io.github.jannetekka.smtworld.solana

import java.io.ByteArrayOutputStream

/**
 * Builds the one transaction this app sends: a single SPL Memo instruction, paid and signed
 * by the user's wallet. Legacy (v0-free) wire format, written by hand so there is no
 * dependency to trust and every byte is covered by a unit test and a devnet round trip.
 *
 * Layout: [compact-u16 #sigs=1][64-byte signature placeholder][message]
 * Message: header(1 signer, 0 readonly signed, 1 readonly unsigned) · keys [payer, memo program]
 *          · recent blockhash · 1 instruction(program=1, accounts=[0], data=utf8 memo)
 * The payer is listed as the memo's account, so the Memo program checks it signed and
 * explorers show the memo as "signed by" that wallet.
 */
object MemoTransaction {
    const val MEMO_PROGRAM_ID = "MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr"
    /** Solana's packet limit for a whole transaction. */
    const val MAX_TX_BYTES = 1232

    fun message(payer: ByteArray, recentBlockhash: ByteArray, memo: String): ByteArray {
        require(payer.size == 32) { "payer must be a 32-byte public key" }
        require(recentBlockhash.size == 32) { "blockhash must be 32 bytes" }
        val data = memo.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(1, 0, 1))                 // header
        writeCompactU16(out, 2)                         // account keys
        out.write(payer)
        out.write(Base58.decode(MEMO_PROGRAM_ID))
        out.write(recentBlockhash)
        writeCompactU16(out, 1)                         // instructions
        out.write(1)                                    // program id index → memo program
        writeCompactU16(out, 1); out.write(0)           // accounts: [payer]
        writeCompactU16(out, data.size); out.write(data)
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
