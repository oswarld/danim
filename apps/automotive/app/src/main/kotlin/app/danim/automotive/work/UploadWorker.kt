package app.danim.automotive.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.danim.automotive.graph
import app.danim.core.CollectorReportReply
import app.danim.core.OutboxUploader
import app.danim.core.RenewalStatus
import app.danim.core.UploadOutcome
import app.danim.core.UploadStats
import app.danim.core.renewAndUpload
import java.util.concurrent.TimeUnit

/**
 * Upload and acknowledge only: drains the Room outbox through [OutboxUploader]. It never reads
 * vehicle signals. Retries are bounded by [MAX_RUN_ATTEMPTS]; after that the periodic worker or the
 * next collector flush starts a fresh run, so a dead network never spins the device.
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val g = applicationContext.graph
        val s = g.settings
        // Counts are written the moment they happen, so an early return can never lose them.
        val stats = object : UploadStats {
            override fun acked(count: Int) = s.addAcked(count)
            override fun quarantined(count: Int) = s.addQuarantined(count)
            override fun expired(count: Int) = s.addExpired(count)
        }
        val attempt = runAttemptCount + 1
        // One lock hold covers: the paused check, credential metadata (even with an empty outbox), durable renewal
        // staging/promotion, the upload with the resulting credential (its device id is the outbox binding) and
        // applying a 401/403 pause, so disconnect/re-pair cannot interleave and an old session's rejection cannot
        // pause a newly paired one.
        val cycle = renewAndUpload(
            g.lifecycle, g.renewer, g.vault::loadCredential, skipIf = { s.pausedReauth || s.disconnectIncomplete },
            run = { cred -> OutboxUploader(g.outbox, g.http.telemetry(cred), cred.deviceId, stats = stats).drain(attempt) },
            onPausedReauth = { _, paused ->
                s.pausedReauth = true
                s.lastUploadResult = "paused: server rejected credential (HTTP ${paused.httpCode}); it may have expired " +
                    "or been revoked. Queued data is kept; disconnect and pair again"
            },
            // Diagnostics after renewal and telemetry, with the resulting current credential; never affects the outcome below.
            report = { cred -> g.collectorReporter.send(cred) },
        ) ?: return Result.success()
        s.lastUploadAtMs = System.currentTimeMillis()
        s.lastCredentialResult = credentialNote(cycle.renewal)
        cycle.report?.let { s.lastCollectorReportResult = reportNote(it) }
        val outcome = cycle.upload ?: return held(cycle.renewal as RenewalStatus.Held, attempt)
        return when (outcome) {
            UploadOutcome.Idle -> { s.lastUploadResult = "idle"; Result.success() }
            is UploadOutcome.Progress -> {
                s.lastUploadResult = "ok acked=${outcome.acked} quarantined=${outcome.quarantined}"
                if (outcome.morePending) UploadScheduler.continueNow(applicationContext)
                Result.success()
            }
            is UploadOutcome.RetryLater -> {
                s.lastUploadResult = "retry(${outcome.reason}) attempt=$attempt"
                if (attempt >= MAX_RUN_ATTEMPTS) {
                    // Bounded work per run only: nothing is deleted; the periodic worker / next flush tries again.
                    s.lastUploadResult = "gave up this run after $attempt attempts; data kept, will retry on next trigger"
                    Result.success()
                } else {
                    Result.retry()
                }
            }
            is UploadOutcome.EndpointError -> {
                s.lastUploadResult = "endpoint/config error HTTP ${outcome.httpCode}: data kept; check the API URL"
                Result.success()
            }
            is UploadOutcome.PausedReauth -> Result.success() // already applied under the lock

        }
    }

    /** Nothing was uploaded and every local record was kept. Retries stay bounded; after that the periodic run retries. */
    private fun held(h: RenewalStatus.Held, attempt: Int): Result {
        val s = applicationContext.graph.settings
        return if (h.retryable && attempt < MAX_RUN_ATTEMPTS) {
            s.lastUploadResult = "waiting for credential renewal (${h.reason}) attempt=$attempt; data kept"
            Result.retry()
        } else {
            s.lastUploadResult = "credential renewal held (${h.reason}); data kept, will retry on next trigger"
            Result.success()
        }
    }

    private fun reportNote(r: CollectorReportReply): String = when (r) {
        is CollectorReportReply.Acked -> r.ack.outcome.wire
        CollectorReportReply.Unauthorized -> "rejected (HTTP 401)"
        CollectorReportReply.Conflict -> "conflict"
        is CollectorReportReply.Failed -> "failed (${r.reason})"
    }

    /** Fixed reasons only; never a token, hash or body. */
    private fun credentialNote(r: RenewalStatus): String = when (r) {
        is RenewalStatus.Ready -> when (r.note) {
            "renewed" -> "renewed; valid until ${r.credential.expiresAtMs?.let { java.time.Instant.ofEpochMilli(it) } ?: "unknown"}"
            else -> r.note
        }
        is RenewalStatus.Held -> "held: ${r.reason}"
        is RenewalStatus.Rejected -> "rejected: pair again"
        RenewalStatus.NoCredential -> "not paired"
    }

    companion object {
        const val MAX_RUN_ATTEMPTS = 8
    }
}

object UploadScheduler {
    private const val UNIQUE = "danim-upload"
    private const val CONTINUE = "danim-upload-continue"
    private const val PERIODIC = "danim-upload-periodic"

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Request an upload run; coalesced with any pending run. */
    fun kick(context: Context) {
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.KEEP, req)
    }

    fun continueNow(context: Context) {
        val req = OneTimeWorkRequestBuilder<UploadWorker>().setConstraints(network).build()
        WorkManager.getInstance(context).enqueueUniqueWork(CONTINUE, ExistingWorkPolicy.KEEP, req)
    }

    /** Safety net: retries leftovers every 15 minutes (WorkManager's minimum) when connected. */
    fun ensurePeriodic(context: Context) {
        val req = PeriodicWorkRequestBuilder<UploadWorker>(15, TimeUnit.MINUTES).setConstraints(network).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun cancelAll(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(UNIQUE); wm.cancelUniqueWork(CONTINUE); wm.cancelUniqueWork(PERIODIC)
    }
}
