package app.danim.automotive

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.danim.core.Iso
import app.danim.core.PairingWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Database RFC 3339 offsets must work on Android's older java.time implementation, not only the host JDK. */
@RunWith(AndroidJUnit4::class)
class Rfc3339ParsingTest {
    @Test
    fun postgresPairingExpiryParsesWithMicrosecondsAndOffset() {
        val parsed = PairingWire.parseStart(
            """{"pairingId":"fixture-id","code":"TEST1234","pairingSecret":"fixture","expiresAt":"2026-01-01T00:10:00.76002+00:00"}""",
        )
        assertNotNull("PostgreSQL pairing expiry must be accepted on device", parsed)
        assertEquals(1767226200760L, parsed!!.expiresAtMs)
    }

    @Test
    fun nonUtcRfc3339OffsetRepresentsTheSameInstant() {
        assertEquals(1767226200760L, Iso.parse("2026-01-01T09:10:00.76002+09:00"))
    }
}
