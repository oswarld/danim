package app.danim.automotive.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.danim.core.CommitOutcome
import app.danim.core.CredentialState
import app.danim.core.DeviceCredential
import app.danim.core.PairingStart
import app.danim.core.RecordBackend
import app.danim.core.RecordCipher
import app.danim.core.TelemetryWire
import app.danim.core.VaultStateStore
import app.danim.core.commitOrRestore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Device credential (active token plus any pending renewal, one record) and in-flight pairing secret, encrypted with a
 * non-exportable AES-256-GCM key in the Android Keystore. Ciphertext lives in app-private preferences excluded from
 * backup. Writes use synchronous `commit()` and report failure; nothing is deleted because it could not be encrypted
 * or decrypted. An undecryptable credential reads as "not paired": the user re-pairs, which overwrites it.
 * [prefsName] and [keyAlias] exist only so instrumentation tests never touch the real vault.
 */
class CredentialVault(
    context: Context,
    prefsName: String = "danim_vault",
    private val keyAlias: String = "danim_device_credential_v1",
) {
    private val backend = PrefsBackend(context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE))
    private val cipher = KeystoreCipher()

    /** Durable credential state used by renewal; written only under the lifecycle lock. */
    val state = VaultStateStore(backend, cipher)

    /** Publishes a freshly paired credential, discarding any pending renewal of the previous one. */
    fun saveCredential(c: DeviceCredential): Boolean = state.write(CredentialState(c))

    fun loadCredential(): DeviceCredential? = state.current()

    fun clearCredential(): Boolean = state.clear()

    fun savePending(p: PairingStart): Boolean {
        val plain = TelemetryWire.encodeObject(
            mapOf("pairingId" to p.pairingId, "code" to p.code, "pairingSecret" to p.pairingSecret, "expiresAtMs" to p.expiresAtMs.toString()),
        )
        val sealed = runCatching { cipher.seal(PENDING, plain) }.getOrNull() ?: return false
        return backend.commitOrRestore(mapOf(PENDING to sealed), emptySet()) == CommitOutcome.COMMITTED
    }

    fun loadPending(): PairingStart? {
        val stored = backend.read(PENDING) ?: return null
        val o = runCatching { cipher.open(PENDING, stored) }.getOrNull()?.let(TelemetryWire::parseObject) ?: return null
        fun s(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
        return PairingStart(
            s("pairingId") ?: return null, s("code") ?: return null, s("pairingSecret") ?: return null,
            s("expiresAtMs")?.toLongOrNull() ?: return null,
        )
    }

    fun clearPending(): Boolean = backend.commitOrRestore(emptyMap(), setOf(PENDING)) == CommitOutcome.COMMITTED

    /** `commit()` is the durable, synchronous result; `apply()` would hide disk failures. Failed commits are rolled back by callers. */
    private class PrefsBackend(private val prefs: SharedPreferences) : RecordBackend {
        override fun read(name: String): String? = prefs.getString(name, null)
        override fun commit(puts: Map<String, String>, removes: Set<String>): Boolean {
            val e = prefs.edit()
            removes.forEach { e.remove(it) }
            puts.forEach { (k, v) -> e.putString(k, v) }
            return e.commit()
        }
    }

    /** AES-GCM with the record name as associated data, so ciphertext cannot be moved between records. */
    private inner class KeystoreCipher : RecordCipher {
        override fun seal(name: String, plain: String): String {
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.ENCRYPT_MODE, key())
            c.updateAAD(name.toByteArray())
            val ct = c.doFinal(plain.toByteArray())
            return Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)
        }

        override fun open(name: String, sealed: String): String {
            val raw = Base64.decode(sealed, Base64.NO_WRAP)
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, IV_LEN))
            c.updateAAD(name.toByteArray())
            return String(c.doFinal(raw, IV_LEN, raw.size - IV_LEN))
        }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
        const val PENDING = "pending_pairing"
    }
}
