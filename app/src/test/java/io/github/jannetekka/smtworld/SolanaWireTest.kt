package io.github.jannetekka.smtworld

import io.github.jannetekka.smtworld.solana.Base58
import io.github.jannetekka.smtworld.solana.MemoTransaction
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream

class SolanaWireTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun base58KnownVectors() {
        assertEquals("", Base58.encode(ByteArray(0)))
        assertEquals("1", Base58.encode(byteArrayOf(0)))
        assertEquals("112", Base58.encode(byteArrayOf(0, 0, 1)))
        assertEquals("JxF12TrwUP45BMd", Base58.encode("Hello World".toByteArray()))
        assertArrayEquals("Hello World".toByteArray(), Base58.decode("JxF12TrwUP45BMd"))
        assertArrayEquals(byteArrayOf(0, 0, 1), Base58.decode("112"))
    }

    @Test fun base58RoundTripsKeysAndSignatures() {
        val r = java.util.Random(7)
        repeat(200) {
            val b = ByteArray(if (it % 2 == 0) 32 else 64).also(r::nextBytes)
            if (it % 5 == 0) b[0] = 0
            assertArrayEquals(b, Base58.decode(Base58.encode(b)))
        }
        assertEquals(32, Base58.decode(MemoTransaction.MEMO_PROGRAM_ID).size)
        assertEquals(32, Base58.decode("11111111111111111111111111111111").size)
    }

    @Test fun compactU16() {
        fun enc(v: Int) = ByteArrayOutputStream().also { MemoTransaction.writeCompactU16(it, v) }.toByteArray()
        assertEquals("00", hex(enc(0)))
        assertEquals("7f", hex(enc(127)))
        assertEquals("8001", hex(enc(128)))
        assertEquals("ff7f", hex(enc(16383)))
        assertEquals("808001", hex(enc(16384)))
    }

    /**
     * The same message built by solders (the Rust solana-sdk's Python bindings), an
     * implementation that shares no code with ours. Regenerate with docs/golden_memo_message.py.
     */
    @Test fun messageMatchesIndependentReference() {
        val golden = "0100010285936cbc16f8e003dfcba292cebb36bb0e83976084351d9261cfe8f278a45bc8054a535a992921064d24e87160da387c7c35b5ddbc92bb81e41fa8404105448dcc490e928cd2e3873bb343fc95da33179ca60f4dbf46c2c36e91299d55d4e6b90101010050534d5420436c6f636b20496e207631207c20425443207c206d6520555020402036323334352e31322062696e616e6365207c20534d5420444f574e203336252057414954207c206772616465202b3468"
        val msg = MemoTransaction.message(
            Base58.decode("9zRcCvqFV9jVDLCPPAhUC17NwJUZypHaPB5YcM6xEhJw"),
            Base58.decode("EkSnNWid2cvwEVnVx9aBqawnmiCNiDgp3gUdkDPTKN1N"),
            "SMT Clock In v1 | BTC | me UP @ 62345.12 binance | SMT DOWN 36% WAIT | grade +4h",
        )
        assertEquals(golden, hex(msg))
    }

    @Test fun unsignedTransactionHasOneEmptySignatureSlot() {
        val payer = ByteArray(32) { 1 }
        val tx = MemoTransaction.unsigned(payer, ByteArray(32) { 2 }, "hi")
        assertEquals(1, tx[0].toInt())
        assertArrayEquals(ByteArray(64), tx.copyOfRange(1, 65))
        assertArrayEquals(MemoTransaction.message(payer, ByteArray(32) { 2 }, "hi"), MemoTransaction.messageOf(tx))
        val signed = MemoTransaction.withSignature(tx, ByteArray(64) { 9 })
        assertArrayEquals(ByteArray(64) { 9 }, signed.copyOfRange(1, 65))
    }

    @Test fun refusesOversizedMemo() {
        assertThrows(IllegalArgumentException::class.java) {
            MemoTransaction.unsigned(ByteArray(32), ByteArray(32), "x".repeat(1200))
        }
    }
}
