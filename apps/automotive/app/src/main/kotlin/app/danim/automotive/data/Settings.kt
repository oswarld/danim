package app.danim.automotive.data

import android.content.Context
import app.danim.automotive.BuildConfig
import app.danim.core.ApiConfig

/** Non-secret app state. Secrets live in [CredentialVault]. */
class Settings(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("danim_settings", Context.MODE_PRIVATE)

    var apiBaseUrl: String
        get() = p.getString("apiBaseUrl", null) ?: BuildConfig.DEFAULT_API_BASE_URL
        set(v) = p.edit().putString("apiBaseUrl", v.trim()).apply()

    var siteUrl: String
        get() = p.getString("siteUrl", null) ?: BuildConfig.DEFAULT_SITE_URL
        set(v) = p.edit().putString("siteUrl", v.trim()).apply()

    /** Plain http to localhost/127.0.0.1/10.0.2.2 is accepted only in debug builds; release is HTTPS only. */
    fun apiConfig(): ApiConfig? = ApiConfig.parse(apiBaseUrl, siteUrl, allowLocalHttp = BuildConfig.DEBUG)

    /** Time the user confirmed "parked" and consent in the app; 0 = never. */
    var consentAtMs: Long
        get() = p.getLong("consentAtMs", 0)
        set(v) = p.edit().putLong("consentAtMs", v).apply()

    /** User intent: collect whenever possible (survives reboot). False after an explicit stop or disconnect. */
    var collectionWanted: Boolean
        get() = p.getBoolean("collectionWanted", false)
        set(v) = p.edit().putBoolean("collectionWanted", v).apply()

    /** Server rejected the device credential (401/403); uploads stay paused until re-pairing. */
    var pausedReauth: Boolean
        get() = p.getBoolean("pausedReauth", false)
        set(v) = p.edit().putBoolean("pausedReauth", v).apply()

    /**
     * A disconnect could not durably remove the local credential. Automatic upload, boot and collection stay
     * disabled until a disconnect completes or a new pairing is published. Set before removal ([beginDisconnect]).
     */
    val disconnectIncomplete: Boolean get() = p.getBoolean("disconnectIncomplete", false)

    /** Synchronous `commit()`: returns whether the fence reached disk. */
    fun setDisconnectIncomplete(v: Boolean): Boolean = p.edit().putBoolean("disconnectIncomplete", v).commit()

    var ackedTotal: Long
        get() = p.getLong("ackedTotal", 0)
        set(v) = p.edit().putLong("ackedTotal", v).apply()

    /** Rows removed by local retention (age / row cap) before the server acknowledged them. */
    var expiredTotal: Long
        get() = p.getLong("expiredTotal", 0)
        set(v) = p.edit().putLong("expiredTotal", v).apply()

    /** Events the server proved invalid; kept out of the upload queue. */
    var quarantinedTotal: Long
        get() = p.getLong("quarantinedTotal", 0)
        set(v) = p.edit().putLong("quarantinedTotal", v).apply()

    /** AAOS work discarded because the source did not stop in time (timeout); never silently counted as drained. */
    var lostOnShutdownTotal: Long
        get() = p.getLong("lostOnShutdownTotal", 0)
        set(v) = p.edit().putLong("lostOnShutdownTotal", v).apply()

    private val counterLock = Any()
    fun addLostOnShutdown(n: Int) = synchronized(counterLock) { lostOnShutdownTotal += n }

    fun addAcked(n: Int) = synchronized(counterLock) { ackedTotal += n }
    fun addExpired(n: Int) = synchronized(counterLock) { expiredTotal += n }
    fun addQuarantined(n: Int) = synchronized(counterLock) { quarantinedTotal += n }

    var lastUploadAtMs: Long
        get() = p.getLong("lastUploadAtMs", 0)
        set(v) = p.edit().putLong("lastUploadAtMs", v).apply()

    var lastUploadResult: String
        get() = p.getString("lastUploadResult", "") ?: ""
        set(v) = p.edit().putString("lastUploadResult", v).apply()

    /** Last credential check/renewal result; fixed reasons only, never credential material. */
    var lastCredentialResult: String
        get() = p.getString("lastCredentialResult", "") ?: ""
        set(v) = p.edit().putString("lastCredentialResult", v).apply()

    /** Last collector diagnostic report result; fixed reasons only. Separate from upload results. */
    var lastCollectorReportResult: String
        get() = p.getString("lastCollectorReportResult", "") ?: ""
        set(v) = p.edit().putString("lastCollectorReportResult", v).apply()

    var lastBootNote: String
        get() = p.getString("lastBootNote", "") ?: ""
        set(v) = p.edit().putString("lastBootNote", v).apply()

    /**
     * First disconnect step, before anything is removed: one synchronous commit of "no collection intent, no consent,
     * fence up". Returns whether it reached disk; if not, it holds only in this process (the memory cache).
     */
    fun beginDisconnect(): Boolean = p.edit()
        .putBoolean("collectionWanted", false).putLong("consentAtMs", 0).putBoolean("disconnectIncomplete", true)
        .commit()

    /** After a complete removal: reset diagnostics and lift the fence in one synchronous commit. */
    fun finishDisconnect(): Boolean = p.edit()
        .putBoolean("collectionWanted", false).putLong("consentAtMs", 0).putBoolean("pausedReauth", false)
        .putLong("ackedTotal", 0).putLong("expiredTotal", 0).putLong("quarantinedTotal", 0).putLong("lostOnShutdownTotal", 0).putLong("lastUploadAtMs", 0).putString("lastUploadResult", "")
        .putString("lastCredentialResult", "").putString("lastCollectorReportResult", "").putBoolean("disconnectIncomplete", false)
        .commit()
}
