package app.danim.automotive

import android.content.Context
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.danim.automotive.data.CredentialVault
import app.danim.core.CredentialState
import app.danim.core.DeviceCredential
import app.danim.core.PendingRenewal
import app.danim.core.StoredCredential
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKey

/**
 * Real Keystore + SharedPreferences persistence of the renewal state. Uses its own preferences file and key alias,
 * so the app's real vault and any paired credential on the device are never read or touched. Synthetic values only.
 */
@RunWith(AndroidJUnit4::class)
class CredentialVaultTest {
    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefsName = "danim_vault_test_renewal"
    private val alias = "danim_vault_test_renewal_key"
    private val device = "0d9c8b7a-6e5f-4d3c-8b2a-1f0e9d8c7b6a"
    private val vehicle = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"
    private val oldToken = "dnm_" + "A".repeat(43)
    private val newToken = "dnm_" + "B".repeat(43)
    private val request = "11111111-2222-4333-8444-555555555555"

    private fun prefs() = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private fun vault() = CredentialVault(ctx, prefsName, alias)

    @Before @After
    fun clean() {
        assertTrue(prefs().edit().clear().commit())
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    @Test
    fun activeAndPendingPersistAsOneRecordAcrossInstances() {
        val state = CredentialState(DeviceCredential(device, vehicle, oldToken), PendingRenewal(request, newToken))
        assertTrue(vault().state.write(state))
        assertEquals(StoredCredential.Present(state, durable = true), vault().state.read())
        assertEquals(setOf("credential_state_v2"), prefs().all.keys)
        assertFalse("ciphertext only", prefs().all.values.any { (it as String).contains(newToken.removePrefix("dnm_")) })
    }

    @Test
    fun legacyRecordMigratesWithoutLosingItsToken() {
        assertTrue(vault().saveCredential(DeviceCredential(device, vehicle, oldToken))) // creates the key
        assertTrue(prefs().edit().clear().commit())
        // Exactly the pre-renewal on-disk format: Base64(iv + AES-GCM ciphertext), record name as AAD.
        val legacy = """{"deviceId":"$device","vehicleId":"$vehicle","deviceToken":"$oldToken"}"""
        val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias, null) as SecretKey
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key); updateAAD("credential".toByteArray()) }
        val sealed = Base64.encodeToString(c.iv + c.doFinal(legacy.toByteArray()), Base64.NO_WRAP)
        assertTrue(prefs().edit().putString("credential", sealed).commit())

        val v = vault()
        assertEquals(DeviceCredential(device, vehicle, oldToken), v.loadCredential())
        val read = v.state.read() as StoredCredential.Present
        assertFalse("must be rewritten before use", read.durable)
        assertTrue(v.state.write(read.state))
        assertEquals(setOf("credential_state_v2"), prefs().all.keys)
        assertEquals(StoredCredential.Present(read.state, durable = true), vault().state.read())
    }

    @Test
    fun repairingClearsPendingAndDisconnectClearsEverything() {
        val v = vault()
        assertTrue(v.state.write(CredentialState(DeviceCredential(device, vehicle, oldToken), PendingRenewal(request, newToken))))
        assertTrue(v.saveCredential(DeviceCredential(device, vehicle, newToken)))
        assertNull((vault().state.read() as StoredCredential.Present).state.pending)
        assertTrue(v.clearCredential())
        assertEquals(StoredCredential.Absent, vault().state.read())
        assertTrue(prefs().all.isEmpty())
    }

    @Test
    fun undecryptableRecordIsNotDeleted() {
        assertTrue(vault().saveCredential(DeviceCredential(device, vehicle, oldToken)))
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias) // simulates key invalidation
        val v = vault()
        assertNull(v.loadCredential())
        assertEquals(StoredCredential.Unreadable, v.state.read())
        assertTrue(prefs().contains("credential_state_v2"))
    }
}
