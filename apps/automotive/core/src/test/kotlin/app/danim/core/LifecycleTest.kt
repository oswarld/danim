package app.danim.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val CAR_A = "device-A"
private const val CAR_B = "device-B"

class OutboxBindingTest {
    private val now = 10_000_000L

    @Test
    fun `a reading queued for the old car is never uploaded under a new device`() = runTest {
        val store = InMemoryOutbox()
        store.insert(listOf(obs(now - 2, id = "old-1"), obs(now - 1, id = "old-2")), now, CAR_A)
        val transport = ScriptedTransport { ackAll(it) }
        val out = OutboxUploader(store, transport, CAR_B, clock = { now }).drain()
        assertEquals(UploadOutcome.Idle, out)
        assertTrue(transport.requests.isEmpty()) // nothing was sent at all
        assertEquals(listOf("old-1", "old-2"), store.ids()) // still local until purged
        assertEquals(2, store.purgeOtherBindings(CAR_B))
        assertTrue(store.ids().isEmpty())
    }

    @Test
    fun `only the current device's rows go out when both are present`() = runTest {
        val store = InMemoryOutbox()
        store.insert(listOf(obs(now - 4, id = "a1"), obs(now - 2, id = "a2")), now, CAR_A)
        store.insert(listOf(obs(now - 3, id = "b1")), now, CAR_B)
        val transport = ScriptedTransport { ackAll(it) }
        OutboxUploader(store, transport, CAR_B, clock = { now }).drain()
        assertEquals(listOf(listOf("b1")), transport.requests.map { r -> r.map { it.eventId } })
        assertEquals(listOf("a1", "a2"), store.ids())
        assertEquals(0, store.count(CAR_B))
        assertEquals(2, store.count(CAR_A))
        assertNull(store.oldestObservedAtMs(CAR_B))
    }

    @Test
    fun `provenance and event ids survive binding and retries`() = runTest {
        val store = InMemoryOutbox()
        val o = Observation("fixed-id", now - 1, Signal.RANGE_KM, Availability.AVAILABLE, Value.Num(10.0), Source.AAOS_CAR_PROPERTY)
        store.insert(listOf(o), now, CAR_A)
        var fail = true
        val t = ScriptedTransport { if (fail) UploadResponse.Transient(null, "io") else ackAll(it) }
        val u = OutboxUploader(store, t, CAR_A, clock = { now })
        u.drain(); fail = false; u.drain()
        assertEquals(listOf(o, o), t.requests.map { it.single() })
        assertEquals(Source.AAOS_CAR_PROPERTY, t.requests.last().single().source)
    }

    @Test
    fun `late callbacks drained after a stop carry the old binding and cannot reach a new device`() = runTest {
        val store = InMemoryOutbox()
        val channel = Channel<Observation>(Channel.UNLIMITED)
        val pipelineOfOldSession = ObservationPipeline(store, SignalGate(), CAR_A, { now })
        channel.trySend(obs(now - 1, id = "late-old-car"))
        channel.close()
        // the old session drains *after* the new device has been paired and its rows purged
        commitPairing(LifecycleLock(), store, DeviceCredential(CAR_B, "veh", "tok")) { true }
        pipelineOfOldSession.consume(channel)
        val t = ScriptedTransport { ackAll(it) }
        assertEquals(UploadOutcome.Idle, OutboxUploader(store, t, CAR_B, clock = { now }).drain())
        assertTrue(t.requests.isEmpty())
    }
}

class LifecycleOrderingTest {
    private val now = 10_000_000L

    @Test
    fun `disconnect waits for the collector drain, so nothing is inserted after the clear`() = runTest {
        val store = InMemoryOutbox()
        val channel = Channel<Observation>(Channel.UNLIMITED)
        val writer = launch { ObservationPipeline(store, SignalGate(), CAR_A, { now }).consume(channel) }
        channel.trySend(obs(now - 1, id = "in-flight")) // already received before stop was requested
        val log = mutableListOf<String>()
        DisconnectCoordinator(
            lock = LifecycleLock(),
            cancelUploads = { log += "cancelUploads" },
            stopCollectorAndJoin = { channel.close(); writer.join(); log += "drained" },
            clearLocal = { store.clear(); log += "cleared"; true },
        ).disconnect()
        assertEquals(listOf("cancelUploads", "drained", "cleared"), log)
        assertTrue(store.ids().isEmpty()) // the drained reading was cleared, not left behind
    }

    @Test
    fun `disconnect waits for an in-flight upload and the upload keeps its own binding`() = runTest {
        val lock = LifecycleLock()
        val store = InMemoryOutbox()
        store.insert(listOf(obs(now - 1, id = "a")), now, CAR_A)
        val inUpload = CompletableDeferred<Unit>()
        val finishUpload = CompletableDeferred<Unit>()
        val log = mutableListOf<String>()
        val upload = launch {
            uploadExclusive(lock, { DeviceCredential(CAR_A, null, "t") }) { cred ->
                log += "upload-start:${cred.deviceId}"
                inUpload.complete(Unit)
                finishUpload.await()
                log += "upload-end"
            }
        }
        inUpload.await()
        val disc = launch {
            DisconnectCoordinator(lock, {}, {}, { log += "cleared"; store.clear(); true }).disconnect()
        }
        testScheduler.runCurrent()
        assertEquals(listOf("upload-start:$CAR_A"), log) // clear is blocked while the upload runs
        finishUpload.complete(Unit)
        upload.join(); disc.join()
        assertEquals(listOf("upload-start:$CAR_A", "upload-end", "cleared"), log)
    }

    @Test
    fun `an upload that starts after disconnect has no credential and does nothing`() = runTest {
        val lock = LifecycleLock()
        var credential: DeviceCredential? = DeviceCredential(CAR_A, null, "t")
        DisconnectCoordinator(lock, {}, {}, { credential = null; true }).disconnect()
        var ran = false
        assertNull(uploadExclusive(lock, { credential }) { ran = true })
        assertTrue(!ran)
    }

    @Test
    fun `re-pair commit durably publishes the new credential before purging the previous device's rows`() = runTest {
        val store = InMemoryOutbox()
        store.insert(listOf(obs(now - 1, id = "old")), now, CAR_A)
        var saved: String? = null
        val log = mutableListOf<String>()
        val result = commitPairing(LifecycleLock(), store, DeviceCredential(CAR_B, null, "t"), save = {
            assertEquals(listOf("old"), store.ids()) // nothing is purged before the save succeeded
            saved = it.deviceId
            true
        }, onPublished = { log += "published:${store.ids().size}"; true })
        assertEquals(PairingCommit.Published(1), result)
        assertEquals(CAR_B, saved)
        assertEquals(listOf("published:0"), log)
    }

    @Test
    fun `a pairing whose credential was not durably stored keeps the previous device's queue`() = runTest {
        val store = InMemoryOutbox()
        store.insert(listOf(obs(now - 1, id = "old")), now, CAR_A)
        var published = false
        val result = commitPairing(LifecycleLock(), store, DeviceCredential(CAR_B, null, "t"), save = { false }, onPublished = { published = true; true })
        assertEquals(PairingCommit.StorageFailed, result)
        assertTrue(!published) // no unpause, collection start or "Paired" may follow
        assertEquals(listOf("old"), store.ids())
        assertEquals(CAR_A, store.bindingOf("old"))
    }
}

class PausedReauthScopeTest {
    private val now = 10_000_000L

    @Test
    fun `a 401 for the old session is applied before a re-pair can commit, and the new session starts unpaused`() = runTest {
        val lock = LifecycleLock()
        val store = InMemoryOutbox()
        var credential: DeviceCredential? = DeviceCredential(CAR_A, null, "tok-a")
        var paused = false
        val log = mutableListOf<String>()
        val inUpload = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val upload = launch {
            uploadAndApply(
                lock, { credential }, { paused },
                run = { cred -> log += "upload:${cred.deviceId}"; inUpload.complete(Unit); finish.await(); UploadOutcome.PausedReauth(401) },
                onPausedReauth = { cred, _ -> paused = true; log += "paused:${cred.deviceId}" },
            )
        }
        inUpload.await()
        val repair = launch {
            commitPairing(lock, store, DeviceCredential(CAR_B, null, "tok-b")) {
                credential = it; paused = false; log += "commit:${it.deviceId}"; true
            }
        }
        testScheduler.runCurrent()
        assertEquals(listOf("upload:$CAR_A"), log) // re-pair is blocked behind the in-flight upload
        finish.complete(Unit)
        upload.join(); repair.join()
        assertEquals(listOf("upload:$CAR_A", "paused:$CAR_A", "commit:$CAR_B"), log)
        assertTrue(!paused) // the new car is not paused by the old car's rejection
        var ranForB = false
        uploadAndApply(lock, { credential }, { paused }, run = { ranForB = true; UploadOutcome.Idle }, onPausedReauth = { _, _ -> error("no") })
        assertTrue(ranForB)
    }

    @Test
    fun `a rejection whose credential is no longer current is not applied`() = runTest {
        val lock = LifecycleLock()
        var current: DeviceCredential? = DeviceCredential(CAR_A, null, "t")
        var applied = false
        uploadAndApply(
            lock, { current }, run = { current = DeviceCredential(CAR_B, null, "t2"); UploadOutcome.PausedReauth(403) },
            onPausedReauth = { _, _ -> applied = true },
        )
        assertTrue(!applied)
    }

    @Test
    fun `paused state skips the run inside the lock`() = runTest {
        var ran = false
        val out = uploadAndApply(LifecycleLock(), { DeviceCredential(CAR_A, null, "t") }, { true }, run = { ran = true; UploadOutcome.Idle }, onPausedReauth = { _, _ -> })
        assertNull(out)
        assertTrue(!ran)
    }
}

class ShutdownBarrierTest {
    @Test
    fun `a quick executor drains and every queued task has run before drained is reported`() = runTest {
        val ex = java.util.concurrent.Executors.newSingleThreadExecutor()
        val ran = java.util.concurrent.atomic.AtomicInteger()
        repeat(20) { ex.execute { ran.incrementAndGet() } }
        val r = shutdownAndAwait(ex, 2_000)
        assertEquals(DrainResult(true, 0), r)
        assertEquals(20, ran.get())
    }

    @Test
    fun `a stuck executor is never reported drained and its queued work is counted as discarded`() = runTest {
        val ex = java.util.concurrent.Executors.newSingleThreadExecutor()
        val release = java.util.concurrent.CountDownLatch(1)
        val ran = java.util.concurrent.atomic.AtomicInteger()
        ex.execute { try { release.await() } catch (_: InterruptedException) { } } // stuck first task (interruptible)
        repeat(3) { ex.execute { ran.incrementAndGet() } }
        val r = shutdownAndAwait(ex, 100)
        assertEquals(false, r.drained)
        assertEquals(3, r.discardedTasks)
        assertEquals(0, ran.get())
        release.countDown()
    }
}

class DisconnectOutcomeTest {
    @Test
    fun `disconnect reports a partial local removal instead of success`() = runTest {
        val log = mutableListOf<String>()
        val r = DisconnectCoordinator(LifecycleLock(), { log += "cancel" }, { log += "stopped" }, { log += "clear"; false },
            fenceDurably = { log += "fence"; true }, finish = { log += "finish"; true }).disconnect()
        assertEquals(DisconnectResult(fenced = true, removed = false, finished = false), r)
        // The durable fence comes first; uploads cancelled and collection stopped regardless; the fence stays up.
        assertEquals(listOf("fence", "cancel", "stopped", "clear"), log)
        assertTrue(DisconnectCoordinator(LifecycleLock(), {}, {}, { true }).disconnect().complete)
    }

    @Test
    fun `total storage failure is reported as an unfenced partial disconnect, never as a guaranteed stop`() = runTest {
        val r = DisconnectCoordinator(LifecycleLock(), {}, {}, { false }, fenceDurably = { false }, finish = { error("not after a failed removal") }).disconnect()
        assertEquals(DisconnectResult(fenced = false, removed = false, finished = false), r)
        assertTrue(!r.complete)
    }

    @Test
    fun `removal that cannot lift the fence is not complete`() = runTest {
        val log = mutableListOf<String>()
        val lock = LifecycleLock()
        val r = DisconnectCoordinator(lock, {}, {}, { log += "clear"; true }, finish = { log += "finish"; false }).disconnect()
        assertEquals(DisconnectResult(fenced = true, removed = true, finished = false), r)
        assertEquals(listOf("clear", "finish"), log)
        assertTrue(!r.complete)
    }

    @Test
    fun `publication reports unsaved cleanup instead of plain success`() = runTest {
        val r = commitPairing(LifecycleLock(), InMemoryOutbox(), DeviceCredential(CAR_B, null, "t"), onPublished = { false }) { true }
        assertEquals(PairingCommit.Published(0, cleanupSaved = false), r)
    }
}
