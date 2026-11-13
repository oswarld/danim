package app.danim.core

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HttpResponseBodyTest {
    private val secret = "dnm_SECRET-token-body"

    private fun bytes(s: String) = ByteArrayInputStream(s.toByteArray(Charsets.UTF_8))

    /** Endless stream that records how many bytes were pulled and whether it was closed. */
    private class Endless(private val fill: Int = 'a'.code) : InputStream() {
        var consumed = 0L
        override fun read(): Int { consumed++; return fill }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            java.util.Arrays.fill(b, off, off + len, fill.toByte())
            consumed += len
            return len
        }
    }

    @Test fun emptyBodyIsEmptyString() = assertEquals("", HttpResponseBody.read(bytes(""), 16))

    @Test fun smallBodyIsReturnedIntact() = assertEquals("""{"ok":true}""", HttpResponseBody.read(bytes("""{"ok":true}"""), 64))

    @Test fun exactLimitBodyIsAccepted() {
        val body = "x".repeat(1024)
        assertEquals(body, HttpResponseBody.read(bytes(body), 1024))
    }

    @Test fun oneByteOverLimitFailsInsteadOfTruncating() {
        val e = assertFailsWith<IOException> { HttpResponseBody.read(bytes("x".repeat(1025)), 1024) }
        assertEquals("response_too_large", e.message)
    }

    @Test fun endlessStreamStopsAtLimitPlusOneByte() {
        val s = Endless()
        assertFailsWith<IOException> { HttpResponseBody.read(s, 64 * 1024) }
        assertTrue(s.consumed <= 64 * 1024 + 1, "consumed ${s.consumed}")
    }

    @Test fun oneByteAtATimeStreamIsNotReadPastLimitPlusOne() {
        var n = 0L
        val s = object : InputStream() {
            override fun read(): Int { n++; return 'a'.code }
        }
        assertFailsWith<IOException> { HttpResponseBody.read(s, 100) }
        assertTrue(n <= 101, "read $n")
    }

    @Test fun multibyteAtExactByteLimitIsAccepted() {
        val body = "한".repeat(4) // 12 bytes, 4 chars
        assertEquals(body, HttpResponseBody.read(bytes(body), 12))
    }

    @Test fun multibyteOverByteLimitFailsEvenWhenCharCountIsUnderLimit() {
        val body = "한".repeat(4) // 4 chars <= 11, but 12 bytes > 11
        assertFailsWith<IOException> { HttpResponseBody.read(bytes(body), 11) }
    }

    @Test fun invalidUtf8IsRejectedWithoutLeakingContent() {
        val raw = secret.toByteArray() + byteArrayOf(0xC3.toByte(), 0x28)
        val e = assertFailsWith<IOException> { HttpResponseBody.read(ByteArrayInputStream(raw), 256) }
        assertEquals("invalid_response_encoding", e.message)
    }

    @Test fun incompleteTrailingUtf8SequenceIsRejected() {
        val raw = "ok".toByteArray() + byteArrayOf(0xED.toByte(), 0x95.toByte()) // truncated 3-byte char
        assertFailsWith<IOException> { HttpResponseBody.read(ByteArrayInputStream(raw), 256) }
    }

    @Test fun ioFailurePropagatesAsFixedSanitizedIoException() {
        val s = object : InputStream() {
            override fun read(): Int = throw IOException("boom $secret")
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("boom $secret")
        }
        val e = assertFailsWith<IOException> { HttpResponseBody.read(s, 64) }
        assertEquals("response_read_failed", e.message)
        assertTrue(e.cause == null)
    }

    @Test fun callerUseClosesStreamOnOverflowAndOnIoError() {
        var closed = 0
        val overflow = object : InputStream() {
            override fun read(): Int = 'a'.code
            override fun close() { closed++ }
        }
        assertFailsWith<IOException> { overflow.use { HttpResponseBody.read(it, 8) } }
        val failing = object : InputStream() {
            override fun read(): Int = throw IOException("x")
            override fun close() { closed++ }
        }
        assertFailsWith<IOException> { failing.use { HttpResponseBody.read(it, 8) } }
        assertEquals(2, closed)
    }

    @Test fun overflowMessageNeverContainsBodyText() {
        val e = assertFailsWith<IOException> { HttpResponseBody.read(bytes(secret.repeat(10)), 8) }
        assertTrue(!e.message.orEmpty().contains("SECRET") && e.cause == null)
    }

    @Test fun nonPositiveOrUnboundedConfigIsRejected() {
        assertFailsWith<IllegalArgumentException> { HttpResponseBody.read(bytes(""), 0) }
        assertFailsWith<IllegalArgumentException> { HttpResponseBody.read(bytes(""), -1) }
        assertFailsWith<IllegalArgumentException> { HttpResponseBody.read(bytes(""), Int.MAX_VALUE) }
    }
}
