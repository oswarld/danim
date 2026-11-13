package app.danim.automotive.net

import app.danim.automotive.data.Settings
import app.danim.core.CollectorReport
import app.danim.core.CollectorReportApi
import app.danim.core.CollectorReportReply
import app.danim.core.CollectorReportWire
import app.danim.core.CredentialApi
import app.danim.core.DeviceCredential
import app.danim.core.HttpClassifier
import app.danim.core.MetadataReply
import app.danim.core.RenewReply
import app.danim.core.RenewalWire
import app.danim.core.HttpResponseBody
import app.danim.core.Observation
import app.danim.core.PairingApi
import app.danim.core.PairingStart
import app.danim.core.PairingWire
import app.danim.core.PollResult
import app.danim.core.TelemetryTransport
import app.danim.core.TelemetryWire
import app.danim.core.UploadResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal JSON-over-HTTPS client for the DANIM API. Never logs URLs with secrets, headers or bodies.
 * Redirects are not followed so the bearer token cannot be forwarded to another host.
 */
class DanimHttp(private val settings: Settings) : PairingApi {

    private data class Reply(val code: Int, val body: String, val retryAfter: String?)

    private suspend fun postJson(path: String, body: String, bearer: String?): Reply = withContext(Dispatchers.IO) {
        val cfg = settings.apiConfig() ?: throw IOException("invalid_config")
        val conn = URL(cfg.endpoint(path)).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            bearer?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { HttpResponseBody.read(it, MAX_BODY) }.orEmpty()
            Reply(code, text, conn.getHeaderField("Retry-After"))
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Transport fixed to one credential: device id in the body and bearer token always come from the same
     * [cred], matching the outbox binding the uploader was built with. It never re-reads the vault mid-run.
     */
    fun telemetry(cred: DeviceCredential): TelemetryTransport = object : TelemetryTransport {
        override suspend fun post(events: List<Observation>): UploadResponse = try {
            val r = postJson("telemetry", TelemetryWire.encode(cred.deviceId, events), cred.deviceToken)
            HttpClassifier.classify(r.code, r.body, r.retryAfter)
        } catch (e: IOException) {
            UploadResponse.Transient(null, e.javaClass.simpleName)
        }
    }

    /** Device-authenticated renewal routes. The token is used only as the bearer; only its replacement's hash is sent. */
    val credentials: CredentialApi = object : CredentialApi {
        override suspend fun metadata(token: String, deviceId: String): MetadataReply = try {
            val r = postJson("devices/credential", RenewalWire.metadataBody(deviceId), token)
            RenewalWire.classifyMetadata(r.code, r.body)
        } catch (e: IOException) {
            MetadataReply.Unknown(e.javaClass.simpleName)
        }

        override suspend fun renew(token: String, deviceId: String, requestId: String, replacementTokenHash: String): RenewReply = try {
            val r = postJson("devices/renew", RenewalWire.renewBody(deviceId, requestId, replacementTokenHash), token)
            RenewalWire.classifyRenew(r.code, r.body)
        } catch (e: IOException) {
            RenewReply.Unknown(e.javaClass.simpleName)
        }
    }

    /** Collector diagnostics with the given credential as Bearer; same bounded, no-redirect transport. */
    val collectorReports: CollectorReportApi = object : CollectorReportApi {
        override suspend fun report(token: String, report: CollectorReport): CollectorReportReply = try {
            val r = postJson("devices/collector-report", CollectorReportWire.encode(report), token)
            CollectorReportWire.classify(r.code, r.body, report.reportId)
        } catch (e: IOException) {
            CollectorReportReply.Failed(e.javaClass.simpleName)
        }
    }

    override suspend fun start(): Result<PairingStart> = try {
        val r = postJson("pairing/start", "{}", null)
        if (r.code in 200..299) {
            PairingWire.parseStart(r.body)?.let { Result.success(it) }
                ?: Result.failure(IOException("bad_start_body"))
        } else {
            Result.failure(IOException("http_${r.code}"))
        }
    } catch (e: IOException) {
        Result.failure(e)
    }

    override suspend fun poll(pairingId: String, pairingSecret: String): PollResult = try {
        val r = postJson("pairing/poll", PairingWire.pollBody(pairingId, pairingSecret), null)
        PairingWire.classifyPoll(r.code, r.body)
    } catch (e: IOException) {
        PollResult.TransientFailure(e.javaClass.simpleName)
    }

    private companion object {
        const val MAX_BODY = 64 * 1024
    }
}
