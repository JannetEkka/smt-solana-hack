package io.github.jannetekka.smtworld.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Plain HttpURLConnection: no client library, works the same on phones, Fire TV and the JVM tests. */
object Http {
    fun get(url: String, timeoutMs: Int = 8000): String = request(url, "GET", null, timeoutMs)

    fun postJson(url: String, body: String, timeoutMs: Int = 10000): String = request(url, "POST", body, timeoutMs)

    private fun request(url: String, method: String, body: String?, timeoutMs: Int): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "smt-world-android/0.1")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw HttpException(code, url, text.take(300))
            return text
        } finally {
            c.disconnect()
        }
    }
}

class HttpException(val code: Int, url: String, body: String) : IOException("HTTP $code from $url: $body")
