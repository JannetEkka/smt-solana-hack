package io.github.jannetekka.smtworld.solana

import java.math.BigInteger

/** Bitcoin-alphabet base58, the encoding Solana uses for addresses, blockhashes and signatures. */
object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val BASE = BigInteger.valueOf(58)

    fun encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val zeros = bytes.takeWhile { it == 0.toByte() }.size
        var n = BigInteger(1, bytes)
        val sb = StringBuilder()
        while (n.signum() > 0) {
            val qr = n.divideAndRemainder(BASE)
            sb.append(ALPHABET[qr[1].toInt()])
            n = qr[0]
        }
        repeat(zeros) { sb.append('1') }
        return sb.reverse().toString()
    }

    fun decode(s: String): ByteArray {
        if (s.isEmpty()) return ByteArray(0)
        var n = BigInteger.ZERO
        for (c in s) {
            val i = ALPHABET.indexOf(c)
            require(i >= 0) { "not base58: '$c'" }
            n = n.multiply(BASE).add(BigInteger.valueOf(i.toLong()))
        }
        val raw = n.toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        val zeros = s.takeWhile { it == '1' }.length
        val body = if (n.signum() == 0) ByteArray(0) else raw
        return ByteArray(zeros) + body
    }
}
