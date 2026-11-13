package app.danim.core

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Public, non-secret deployment settings. */
data class ApiConfig(val apiBaseUrl: String, val siteUrl: String) {
    fun endpoint(path: String): String = apiBaseUrl.trimEnd('/') + "/" + path.trimStart('/')

    /** Link encoded in the QR: `${SITE_URL}/pair?code=<code>`. */
    fun pairingLink(code: String): String =
        siteUrl.trimEnd('/') + "/pair?code=" + java.net.URLEncoder.encode(code, "UTF-8")

    companion object {
        /**
         * Accepts https URLs. Plain http is accepted only when [allowLocalHttp] (debug builds) and the host is
         * exactly localhost, 127.0.0.1 or 10.0.2.2. Release builds must pass false. Returns null when invalid.
         */
        fun parse(apiBaseUrl: String, siteUrl: String, allowLocalHttp: Boolean = false): ApiConfig? {
            val api = normalize(apiBaseUrl, allowLocalHttp) ?: return null
            val site = normalize(siteUrl, allowLocalHttp) ?: return null
            return ApiConfig(api, site)
        }

        private fun normalize(raw: String, allowLocalHttp: Boolean): String? {
            val url = raw.trim().trimEnd('/')
            val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
            val host = uri.host ?: return null
            val local = host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2"
            val ok = uri.scheme == "https" || (allowLocalHttp && uri.scheme == "http" && local)
            return if (ok && uri.userInfo == null && uri.query == null && uri.fragment == null) url else null
        }
    }
}

data class PairingStart(
    val pairingId: String,
    val code: String,
    val pairingSecret: String,
    val expiresAtMs: Long,
)

/** [expiresAtMs] is known only after a renewal; null means unknown, never "no expiry". Never prints the token. */
data class DeviceCredential(
    val deviceId: String,
    val vehicleId: String?,
    val deviceToken: String,
    val expiresAtMs: Long? = null,
) {
    override fun toString() = "DeviceCredential(deviceId=$deviceId, vehicleId=$vehicleId, expiresAtMs=$expiresAtMs)"
}

sealed interface PollResult {
    data object Pending : PollResult
    data object Expired : PollResult
    data class Claimed(val credential: DeviceCredential) : PollResult
    data class TransientFailure(val reason: String) : PollResult
    data class Fatal(val reason: String) : PollResult
}

interface PairingApi {
    suspend fun start(): Result<PairingStart>
    suspend fun poll(pairingId: String, pairingSecret: String): PollResult
}

object PairingWire {
    private const val INVALID_CLAIM = "claimed_invalid_credential"

    private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    private val CODE = Regex("[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{8}")
    private val SECRET = Regex("[A-Za-z0-9_-]{43}")
    private val TOKEN = Regex("dnm_[A-Za-z0-9_-]{43}")

    /** Value of [k] only when it is a real JSON string; numbers, booleans, null and containers yield null. */
    private fun JsonObject.str(k: String): String? =
        (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.strict(k: String, shape: Regex): String? = str(k)?.takeIf { shape.matches(it) }

    fun parseStart(body: String): PairingStart? = runCatching {
        val o = TelemetryWire.parseObject(body)!!
        PairingStart(
            pairingId = o.strict("pairingId", UUID)!!,
            code = o.strict("code", CODE)!!,
            pairingSecret = o.strict("pairingSecret", SECRET)!!,
            expiresAtMs = Iso.parse(o.str("expiresAt")!!),
        )
    }.getOrNull()

    fun parsePoll(body: String): PollResult {
        val o = TelemetryWire.parseObject(body) ?: return PollResult.TransientFailure("bad_body")
        return when (o.str("status")) {
            "pending" -> PollResult.Pending
            "expired" -> PollResult.Expired
            "claimed" -> {
                val d = o.strict("deviceId", UUID)
                val v = o.strict("vehicleId", UUID)
                val t = o.strict("deviceToken", TOKEN)
                if (d == null || v == null || t == null) PollResult.Fatal(INVALID_CLAIM)
                else PollResult.Claimed(DeviceCredential(d, v, t))
            }
            else -> PollResult.TransientFailure("unknown_status")
        }
    }

    /**
     * Maps an HTTP reply to a poll result. Bodies are interpreted only for 2xx: expiry is the successful
     * `{status:"expired"}` reply, never a 404/410. Failure reasons are fixed strings, never body content.
     */
    fun classifyPoll(code: Int, body: String): PollResult = when {
        code in 200..299 -> parsePoll(body)
        code == 429 || code >= 500 -> PollResult.TransientFailure("http_$code")
        else -> PollResult.Fatal("http_$code")
    }

    fun pollBody(id: String, secret: String): String =
        TelemetryWire.encodeObject(mapOf("pairingId" to id, "pairingSecret" to secret))
}

sealed interface PairingEnd {
    data class Claimed(val credential: DeviceCredential) : PairingEnd
    data object Expired : PairingEnd
    data class Failed(val reason: String) : PairingEnd
}

/**
 * Polls no more often than every [minIntervalMs] (contract: once per five seconds) and stops at expiry.
 * [delayFn] and [clock] are injected so tests run without real time.
 */
class PairingPoller(
    private val api: PairingApi,
    private val clock: () -> Long = System::currentTimeMillis,
    private val delayFn: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    private val minIntervalMs: Long = 5_000,
    private val maxTransientFailures: Int = 20,
    private val onTick: (PollResult) -> Unit = {},
) {
    init {
        require(minIntervalMs >= 5_000) { "contract allows at most one poll per five seconds" }
    }

    suspend fun run(start: PairingStart): PairingEnd {
        var failures = 0
        while (true) {
            if (clock() >= start.expiresAtMs) return PairingEnd.Expired
            val result = try {
                api.poll(start.pairingId, start.pairingSecret)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PollResult.TransientFailure(e.javaClass.simpleName)
            }
            onTick(result)
            when (result) {
                is PollResult.Claimed -> return PairingEnd.Claimed(result.credential)
                PollResult.Expired -> return PairingEnd.Expired
                is PollResult.Fatal -> return PairingEnd.Failed(result.reason)
                is PollResult.TransientFailure -> if (++failures > maxTransientFailures) return PairingEnd.Failed(result.reason)
                PollResult.Pending -> failures = 0
            }
            val remaining = start.expiresAtMs - clock()
            if (remaining <= 0) return PairingEnd.Expired
            delayFn(minOf(minIntervalMs, remaining))
        }
    }
}

object Qr {
    /** Row-major module matrix (true = dark) without quiet zone; the UI adds the border. */
    fun matrix(text: String): Array<BooleanArray> {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 0,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        return Array(m.height) { y -> BooleanArray(m.width) { x -> m.get(x, y) } }
    }
}
