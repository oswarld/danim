package app.danim.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Bounded reader for HTTP response bodies. The limit counts incoming UTF-8 bytes and is enforced while reading,
 * before any decoding or parsing. Failures use fixed messages and never carry body, header or credential text.
 * The caller owns [InputStream] closing.
 */
object HttpResponseBody {
    const val MAX_CONFIGURABLE_BYTES = 1024 * 1024
    private const val CHUNK = 8 * 1024

    /** Returns the strictly decoded UTF-8 body, or throws a sanitized [IOException] on overflow, I/O error or bad encoding. */
    fun read(stream: InputStream, maxBytes: Int): String {
        require(maxBytes in 1..MAX_CONFIGURABLE_BYTES) { "maxBytes out of range" }
        val out = ByteArrayOutputStream(minOf(maxBytes, CHUNK))
        val chunk = ByteArray(minOf(maxBytes + 1, CHUNK))
        var total = 0
        while (true) {
            // Never request more than one byte past the limit, so an endless stream is read at most maxBytes + 1 bytes.
            val want = minOf(chunk.size, maxBytes + 1 - total)
            val n = try {
                stream.read(chunk, 0, want)
            } catch (_: IOException) {
                throw IOException("response_read_failed")
            }
            if (n < 0) break
            if (n == 0) continue
            total += n
            if (total > maxBytes) throw IOException("response_too_large")
            out.write(chunk, 0, n)
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(out.toByteArray()))
                .toString()
        } catch (_: CharacterCodingException) {
            throw IOException("invalid_response_encoding")
        }
    }
}
