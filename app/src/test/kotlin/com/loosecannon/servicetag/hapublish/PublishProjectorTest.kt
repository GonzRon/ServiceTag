package com.loosecannon.servicetag.hapublish

import com.loosecannon.servicetag.api.V1Client
import com.loosecannon.servicetag.core.hapublish.DecommissionState
import com.loosecannon.servicetag.core.hapublish.DecommissionTombstone
import com.loosecannon.servicetag.core.hapublish.INVALID_SERVICE_DATE
import com.loosecannon.servicetag.core.hapublish.PublishBinding
import com.loosecannon.servicetag.core.hapublish.PublishDelivery
import com.loosecannon.servicetag.core.hapublish.PublishErrorKind
import com.loosecannon.servicetag.core.hapublish.PublishKind
import com.loosecannon.servicetag.core.hapublish.RemoteEntity
import com.loosecannon.servicetag.core.hapublish.RemoteRead
import com.loosecannon.servicetag.core.hapublish.ValidationResult
import com.loosecannon.servicetag.core.model.AssetCondition
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleRole
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.ports.ConditionRepository
import com.loosecannon.servicetag.core.ports.EventRepository
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.core.seasonsync.Secret
import com.loosecannon.servicetag.core.usecase.CompletionCommand
import com.loosecannon.servicetag.core.usecase.ConditionCommand
import com.loosecannon.servicetag.core.usecase.EventCommand
import com.loosecannon.servicetag.core.usecase.RecordCondition
import com.loosecannon.servicetag.core.usecase.RecordConditionWithIncident
import com.loosecannon.servicetag.data.room.fileBackedDb
import com.loosecannon.servicetag.core.usecase.TransferImportPreview
import com.loosecannon.servicetag.core.usecase.TransferImportResult
import com.loosecannon.servicetag.ui.transfer.`import`.AppPack
import com.loosecannon.servicetag.ui.transfer.`import`.TransferPackAppFixtures
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flow
import java.time.ZoneOffset
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.assetRow
import com.loosecannon.servicetag.testing.dayMillis
import com.loosecannon.servicetag.testing.scheduleOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * #113/#114 (C31, C34, I18, I20, I28; rows 57a, 57c, 61, 62, 63's retirement, 65, 66, 68) — the projector and its
 * triggers over the Room-backed graph and a scripted Home Assistant: one write transaction per projection, idempotent;
 * every writer reaching Room reaches the helper; only work-arrived inputs trigger a projection, so a failed delivery
 * never triggers the next; start, resume and the midnight tick each reconcile, start and resume reading the helper
 * back. Every asset, helper, address and token is fictional.
 */
class PublishProjectorTest {

    private var graph = FakeGraph()
    private val ha get() = graph.haPublish
    private val extra = mutableListOf<FakeGraph>()
    private val heater = AssetId("example-heater")
    private val service = ScheduleId("example-annual-service")

    @Before fun setUp() = on("2026-02-10")

    /** Row 57a's file-backed database lives here; deleted with its `-wal` and `-shm` after each test. */
    private val tempDir: File = kotlin.io.path.createTempDirectory("projector").toFile()

    @After fun close() {
        graph.close()
        extra.forEach { it.close() }
        tempDir.deleteRecursively()
    }

    private fun run(block: suspend CoroutineScope.() -> Unit) = runBlocking<Unit> { withTimeout(40_000) { block() } }

    private fun on(date: String, hour: Int = 9) {
        graph.today = LocalDate.parse(date)
        graph.now = dayMillis(date) + hour * HOUR
    }

    // --- fixtures -------------------------------------------------------------------------------

    private suspend fun yearRoundHeater() = graph.assets.upsert(assetRow(heater.value, "Example Heater"))

    /** In season 1 April – 31 October: on 10 February 2026 its season last ended on 1 November 2025 (O-1). */
    private suspend fun calendarHeater(end: String = "10-31") = graph.assets.upsert(
        assetRow(heater.value, "Example Heater", seasonMode = SeasonMode.CALENDAR, seasonStart = "04-01", seasonEnd = end),
    )

    private suspend fun role(sinceOn: String = "2025-09-01") = graph.uow.write {
        graph.schedules.upsert(
            scheduleOf(service.value, assetId = heater.value, title = "Example annual service", anchorOn = "2025-06-01")
                .copy(role = ScheduleRole.SEASON_START, roleSinceOn = sinceOn),
        )
    }

    private suspend fun complete(on: String): AssetEvent =
        graph.completeSchedule.run(service, CompletionCommand(occurredOn = on, tzId = "UTC"))

    private suspend fun connect() = graph.saveHaConnection.run(
        "http://192.168.0.10:8123", Secret("fictional-token-1"), null, NetworkEligibility.HOME_NETWORK_ONLY,
        "ExampleHomeWifi", null,
    )

    private suspend fun fresh(entityId: String, kind: PublishKind, asset: AssetId = heater): ValidationResult {
        val connection = graph.haConnections.get()!!
        val revision = graph.publishBindings.forAsset(asset).firstOrNull { it.kind == kind }?.revision ?: 0
        return ValidationResult(entityId, ha.helpers.getValue(entityId), connection.id, connection.configGeneration, revision, graph.now)
    }

    /** Enabled through B3's command; its desired value is the projector's to compute. */
    private suspend fun enable(kind: PublishKind, asset: AssetId = heater): PublishBinding {
        val entityId = when (kind) {
            PublishKind.EQUIPMENT_STATUS -> STATUS.also { ha.helpers.putIfAbsent(it, statusHelper("UP")) }
            PublishKind.SEASON_SERVICE_DATE -> DATE.also { ha.helpers.putIfAbsent(it, dateHelper(INVALID_SERVICE_DATE)) }
        }
        return graph.enablePublishing.run(asset, kind, entityId, fresh(entityId, kind, asset), null, graph.installationIdentity.id())
    }

    private suspend fun record(condition: OperationalCondition): AssetCondition {
        graph.now += 1_000
        return graph.recordCondition.run(heater, ConditionCommand(condition, tzId = "UTC"))
    }

    private suspend fun delivery(binding: PublishBinding): PublishDelivery = graph.publishDeliveries.get(binding.id)!!

    private suspend fun reconcile(): Boolean = graph.publishProjector.reconcileDesired(graph.today)

    private suspend fun eventually(what: String, check: suspend () -> Boolean) {
        val until = System.currentTimeMillis() + 15_000
        while (!check()) {
            check(System.currentTimeMillis() < until) { "never: $what" }
            delay(20)
        }
    }

    /** Ten debounce windows with nothing new arriving. */
    private suspend fun tenWindows() = delay(10 * PublishTriggers.DEBOUNCE_MILLIS + 200)

    // --- row 61: idempotent, one revision per change ------------------------------------------

    @Test fun reconcileIsIdempotent() = run {
        calendarHeater()
        role()
        complete("2025-12-01")
        connect()
        val status = enable(PublishKind.EQUIPMENT_STATUS)
        val date = enable(PublishKind.SEASON_SERVICE_DATE)
        record(OperationalCondition.DOWN)

        assertTrue("the first call writes both", reconcile())
        val first = listOf(delivery(status), delivery(date))
        assertEquals(listOf("DOWN", "2025-12-01"), first.map { it.desiredValue })

        assertFalse("a second call over the same database changes nothing", reconcile())
        assertEquals(first, listOf(delivery(status), delivery(date)))
    }

    @Test fun aChangeBumpsTheDesiredRevisionOnce() = run {
        yearRoundHeater()
        connect()
        val status = enable(PublishKind.EQUIPMENT_STATUS)
        val start = delivery(status)

        assertFalse("no condition recorded: nothing to send, nothing written", reconcile())
        assertNull(delivery(status).desiredValue)
        assertEquals(start.desiredRevision, delivery(status).desiredRevision)

        record(OperationalCondition.DOWN)
        reconcile()
        reconcile()
        assertEquals("DOWN", delivery(status).desiredValue)
        assertEquals(start.desiredRevision + 1, delivery(status).desiredRevision)

        record(OperationalCondition.DOWN) // the same value again: no new revision
        reconcile()
        assertEquals(start.desiredRevision + 1, delivery(status).desiredRevision)

        // One attempt at this revision, refused before transmission, so the reset below has a count to reset.
        ha.onCall = { CallOutcome.Refused(PublishErrorKind.UNREACHABLE, null) }
        graph.publishRunner.drain()
        assertEquals(1, delivery(status).attemptsAtRevision)
        ha.onCall = { ha.act(it) }

        record(OperationalCondition.OPERATIONAL)
        reconcile()
        reconcile()
        val moved = delivery(status)
        assertEquals("UP", moved.desiredValue)
        assertEquals(start.desiredRevision + 2, moved.desiredRevision)
        assertEquals(0, moved.attemptsAtRevision)
    }

    // --- row 63 (F-9, C34): a binding whose asset row is gone is retired --------------------------

    @Test fun aDisabledBindingWhoseAssetIsDeletedIsRetired() = run {
        yearRoundHeater()
        connect()
        enable(PublishKind.EQUIPMENT_STATUS)
        val date = enable(PublishKind.SEASON_SERVICE_DATE)
        graph.disablePublishing.run(heater, PublishKind.EQUIPMENT_STATUS)
        graph.disablePublishing.run(heater, PublishKind.SEASON_SERVICE_DATE)
        val owed = graph.publishDecommissions.all()
        assertEquals("Disable wrote the date helper's obligation", listOf(DATE), owed.map { it.entityId })
        graph.deleteAsset.run(heater)
        assertEquals("the soft link left both rows", 2, graph.publishBindings.all().size)

        assertTrue(reconcile())

        assertTrue("both disabled rows deleted", graph.publishBindings.all().isEmpty())
        assertNull("their deliveries with them", graph.publishDeliveries.get(date.id))
        assertEquals("no obligation written for a disabled row (R-1)", owed, graph.publishDecommissions.all())
        assertFalse(reconcile())
    }

    @Test fun anEnabledBindingWhoseAssetIsDeletedIsRetiredAndADateHelperIsOwed1970() = run {
        yearRoundHeater()
        connect()
        enable(PublishKind.EQUIPMENT_STATUS)
        val date = enable(PublishKind.SEASON_SERVICE_DATE)
        graph.deleteAsset.run(heater)

        assertTrue(reconcile())

        assertTrue("both enabled rows deleted", graph.publishBindings.all().isEmpty())
        val owed = graph.publishDecommissions.all().single()
        assertEquals(DATE, owed.entityId)
        assertEquals(date.connectionId, owed.connectionId)
        assertEquals(DecommissionState.PENDING, owed.state)
        assertEquals("the binding's installation (F-25)", graph.installationIdentity.id(), owed.installationId)
        assertEquals(heater, owed.assetId)
        assertNull(owed.errorKind)
        assertFalse(reconcile())
    }

    // --- row 57a: one transaction (A02, I20) ------------------------------------------------------

    @Test fun aProjectionsReadAndWriteAreOneTransaction() = run {
        graph.close()
        graph = FakeGraph(db = fileBackedDb(File(tempDir, "servicetag.db")))
        on("2026-02-10")
        calendarHeater()
        role()
        connect()
        ha.helpers[DATE] = dateHelper("2025-06-01") // an older service Home Assistant still holds
        val date = enable(PublishKind.SEASON_SERVICE_DATE)
        reconcile()
        val serviced = complete("2025-12-01") // committed; no observer has projected it yet

        // Projection A pauses after its read of the asset's events, inside whatever transaction holds that read.
        val paused = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val barrier = object : EventRepository by graph.events {
            override suspend fun forAsset(assetId: AssetId): List<AssetEvent> =
                graph.events.forAsset(assetId).also {
                    paused.complete(Unit)
                    release.await()
                }
        }
        val a = PublishProjector(
            graph.publishBindings, graph.publishDeliveries, graph.publishDecommissions, graph.haConnections,
            graph.seasonSyncBindings, graph.assets, graph.transferRecords, graph.conditions, graph.schedules,
            graph.groups, barrier, graph.seasonActivations, graph.uow, graph.ids, graph.clock,
        ) { java.time.ZoneOffset.UTC }
        val projection = async(Dispatchers.Default) { a.reconcileDesired(graph.today) }
        paused.await()
        val delete = launch(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
            graph.uow.write { graph.events.delete(serviced.id) }
        }
        delay(400)
        assertFalse("the delete is not committed while A is paused", delete.isCompleted)

        release.complete(Unit)
        assertTrue(projection.await())
        delete.join()
        assertEquals("A committed the date it read", "2025-12-01", delivery(date).desiredValue)

        val observer = graph.publishTriggers.observe()
        try {
            eventually("the next projection invalidates") { delivery(date).desiredValue == INVALID_SERVICE_DATE }
            eventually("the helper holds 1970-01-01") { ha.state(DATE) == INVALID_SERVICE_DATE }
            tenWindows()
            assertEquals(INVALID_SERVICE_DATE, delivery(date).desiredValue)
            assertEquals("the last write is 1970-01-01", INVALID_SERVICE_DATE, ha.posts.last().value)
        } finally {
            observer.cancel()
        }
    }

    // --- row 57c: work arrived, never bookkeeping (C, I28) --------------------------------------

    @Test fun aTransientFailureDoesNotReTriggerItself() = run {
        yearRoundHeater()
        connect()
        val status = enable(PublishKind.EQUIPMENT_STATUS)
        record(OperationalCondition.DOWN)
        graph.publishDecommissions.upsertMerging(
            DecommissionTombstone(
                "tombstone-1", graph.haConnections.get()!!.id, "input_datetime.example_old_service_done", heater,
                graph.installationIdentity.id(), DecommissionState.PENDING, graph.now, null, null, 1,
            ),
        )
        ha.onRead = { RemoteRead.Failed(PublishErrorKind.UNREACHABLE, null) }
        val observer = graph.publishTriggers.observe()
        try {
            eventually("the first projection asks for delivery") { graph.deliveryRequests.get() == 1 }
            eventually("its drain reads and fails") { delivery(status).errorKind == PublishErrorKind.UNREACHABLE }
            tenWindows()
            assertEquals("one projection", 1, graph.desiredStateReconciler.days.size)
            assertEquals("one drain", 1, graph.deliveryRequests.get())

            // The runner's bookkeeping, each a write the observer must not see as work arrived.
            graph.publishRunner.publishNow(heater) // a reconcile write-ahead, a failed read, reconcilePending
            assertTrue(delivery(status).reconcilePending)
            val tombstone = graph.publishDecommissions.all().single()
            assertTrue(graph.publishDecommissions.update(tombstone.copy(errorKind = PublishErrorKind.UNREACHABLE, errorAt = graph.now)))
            tenWindows()
            assertEquals("no projection from bookkeeping", 1, graph.desiredStateReconciler.days.size)
            assertEquals("no drain from bookkeeping", 1, graph.deliveryRequests.get())

            record(OperationalCondition.DEGRADED)
            eventually("one source change projects") { graph.desiredStateReconciler.days.size == 2 }
            tenWindows()
            assertEquals(2, graph.desiredStateReconciler.days.size)
            assertEquals("and asks for exactly one more drain", 2, graph.deliveryRequests.get())

            val current = graph.publishBindings.get(status.id)!!
            assertTrue(graph.publishBindings.clearValidation(status.id, current.revision))
            tenWindows()
            assertEquals("a cleared validation is no input", 2, graph.desiredStateReconciler.days.size)
            assertEquals(2, graph.deliveryRequests.get())

            record(OperationalCondition.DOWN)
            eventually("the next source change projects once more") { graph.desiredStateReconciler.days.size == 3 }
            tenWindows()
            assertEquals(3, graph.desiredStateReconciler.days.size)
        } finally {
            observer.cancel()
        }
    }

    // --- row 62: every writer reaches the projection through Room -------------------------------

    @Test fun eachWriterReachesTheProjection() = run {
        yearRoundHeater()
        connect()
        enable(PublishKind.EQUIPMENT_STATUS)
        val observer = graph.publishTriggers.observe()
        suspend fun reaches(value: String, by: String) = eventually("$by reaches the helper as $value") { ha.state(STATUS) == value }
        try {
            record(OperationalCondition.DOWN)
            reaches("DOWN", "RecordCondition")

            graph.now += 1_000
            val note = graph.logEvent.run(event(EventKind.MAINTENANCE, "Example cleaning"))
            graph.now += 1_000
            graph.acceptOperationalOffer.run(heater, note)
            reaches("UP", "AcceptOperationalOffer")

            graph.now += 1_000
            val incident = graph.logEvent.run(event(EventKind.INCIDENT, "Example smoke"))
            graph.now += 1_000
            graph.acceptImpairmentOffer.run(heater, incident, OperationalCondition.DEGRADED)
            reaches("DEGRADED", "AcceptImpairmentOffer")

            graph.now += 1_000
            graph.recordConditionWithIncident.run(
                heater, "example-condition-1", ConditionCommand(OperationalCondition.DOWN, tzId = "UTC"),
                event(EventKind.INCIDENT, "Example will not start"),
            )
            reaches("DOWN", "RecordConditionWithIncident")

            graph.now += 1_000
            val answer = V1Client(graph).call("POST", "/v1/assets/${heater.value}/conditions", """{"condition":"OPERATIONAL","tzId":"UTC"}""")
            assertEquals(201, answer.status)
            reaches("UP", "the API's POST …/conditions")

            // Another of the owner's archives: the same asset with a later condition, merged, then restored whole.
            val source = FakeGraph().also { extra += it }
            source.assets.upsert(assetRow(heater.value, "Example Heater"))
            source.today = graph.today
            source.now = graph.now + 10_000
            source.recordCondition.run(heater, ConditionCommand(OperationalCondition.DEGRADED, tzId = "UTC"))
            graph.importBackupMerge.run(source.exportBackupSet.run().data)
            reaches("DEGRADED", "a merge apply")

            source.now += 10_000
            source.recordCondition.run(heater, ConditionCommand(OperationalCondition.DOWN, tzId = "UTC"))
            graph.importBackupReplace.run(source.exportBackupSet.run().data)
            reaches("DOWN", "a replace restore")
        } finally {
            observer.cancel()
        }
    }

    @Test fun aWindowEditOrModeSwitchRepublishes() = run {
        calendarHeater()
        role()
        complete("2025-12-01")
        connect()
        enable(PublishKind.SEASON_SERVICE_DATE)
        val observer = graph.publishTriggers.observe()
        try {
            eventually("the service date") { ha.state(DATE) == "2025-12-01" }

            calendarHeater(end = "12-31") // the season now ended on 1 January 2026: December's service is before it
            eventually("a window edit") { ha.state(DATE) == INVALID_SERVICE_DATE }

            graph.assets.upsert(assetRow(heater.value, "Example Heater", seasonMode = SeasonMode.MANUAL))
            eventually("MANUAL with no season end counts from the role's day") { ha.state(DATE) == "2025-12-01" }

            calendarHeater(end = "12-31")
            eventually("a MANUAL → CALENDAR switch") { ha.state(DATE) == INVALID_SERVICE_DATE }
        } finally {
            observer.cancel()
        }
    }

    /**
     * Row 62's eighth writer (B4b1 review I1), and R-N5's return path through the observer: the asset leaves by a
     * Transfer Pack (nothing desired, the helper kept), Home Assistant's helper changes while it is away, and the
     * return's import brings its DOWN back to the helper.
     */
    @Test fun aTransferPackReturnReachesTheProjection() = run {
        val returning = AssetId(TransferPackAppFixtures.HEATER)
        TransferPackAppFixtures.seedHeater(graph)
        connect()
        ha.helpers[STATUS] = statusHelper("UP")
        val status = enable(PublishKind.EQUIPMENT_STATUS, returning)
        graph.now += 1_000
        graph.recordCondition.run(returning, ConditionCommand(OperationalCondition.DOWN, tzId = "UTC"))
        val observer = graph.publishTriggers.observe()
        try {
            eventually("DOWN published") { ha.state(STATUS) == "DOWN" }

            val out = TransferPackAppFixtures.seal(graph, TransferPackAppFixtures.HEATER)
            graph.markTransferredOut.run(out.created)
            eventually("nothing desired while it is away") { delivery(status).desiredValue == null }
            assertEquals("the remote value is kept", "DOWN", ha.state(STATUS))
            ha.helpers[STATUS] = statusHelper("UP") // changed in Home Assistant meanwhile

            val recipient = FakeGraph().also { extra += it }
            recipient.today = graph.today
            recipient.now = graph.now
            assertTrue(recipient.importAll(out) is TransferImportResult.Imported)
            repeat(20) { recipient.ids.newId() }
            val back = TransferPackAppFixtures.seal(recipient, TransferPackAppFixtures.HEATER)

            assertTrue(graph.importAll(back) is TransferImportResult.Imported)
            eventually("a Transfer Pack import reaches the helper as DOWN") { ha.state(STATUS) == "DOWN" }
            assertEquals(emptySet<AssetId>(), graph.transferRecords.heldIds())
        } finally {
            observer.cancel()
        }
    }

    private suspend fun FakeGraph.importAll(pack: AppPack): TransferImportResult {
        val ready = importTransferPack.preview { pack.bytes.inputStream() } as TransferImportPreview.Ready
        return importTransferPack.import(ready) { pack.bytes.inputStream() }
    }

    // --- row 65: only committed state is projected -----------------------------------------------

    @Test fun aRolledBackWriteChangesNothing() = run {
        yearRoundHeater()
        connect()
        ha.helpers[STATUS] = statusHelper("DOWN")
        val status = enable(PublishKind.EQUIPMENT_STATUS)
        record(OperationalCondition.OPERATIONAL)
        val observer = graph.publishTriggers.observe()
        try {
            eventually("UP published") { ha.state(STATUS) == "UP" }
            val before = delivery(status)
            val posts = ha.posts.size

            // The combined write, its condition insert failing after the Incident is in the transaction.
            val refusing = object : ConditionRepository by graph.conditions {
                override suspend fun insert(row: AssetCondition) {
                    graph.conditions.insert(row) // the DOWN row is inside the transaction…
                    throw IllegalStateException("the disk is full") // …and rolled back with it
                }
            }
            val record = RecordCondition(graph.assets, graph.events, refusing, graph.uow, graph.ids, graph.clock, graph.todayPort)
            val combined = RecordConditionWithIncident(
                graph.events, graph.definitions, graph.profiles, graph.assets, graph.supplyItems, graph.uow, graph.ids,
                graph.clock, graph.recomputeSchedules, refusing, graph.todayPort, record,
            )
            graph.now += 1_000
            val thrown = runCatching {
                combined.run(
                    heater, "example-condition-2", ConditionCommand(OperationalCondition.DOWN, tzId = "UTC"),
                    event(EventKind.INCIDENT, "Example will not start"),
                )
            }.exceptionOrNull()
            assertNotNull(thrown)
            tenWindows()

            assertEquals("nothing projected from a rolled-back write", before, delivery(status))
            assertEquals("nothing sent", posts, ha.posts.size)
            assertEquals("UP", ha.state(STATUS))
        } finally {
            observer.cancel()
        }
    }

    // --- row 66: start, resume and midnight each reconcile; start and resume read back (F-3) ----

    @Test fun startResumeMidnightAndWorkerEachReconcile() = run {
        yearRoundHeater()
        connect()
        val status = enable(PublishKind.EQUIPMENT_STATUS)

        record(OperationalCondition.DOWN) // the process dies before any projection: no observer runs here
        graph.publishTriggers.startPass()
        assertEquals("the start pass projects and sends", "DOWN", delivery(status).desiredValue)
        assertEquals("DOWN", ha.state(STATUS))

        record(OperationalCondition.DEGRADED)
        graph.publishTriggers.resumePass()
        assertEquals("DEGRADED", ha.state(STATUS))

        record(OperationalCondition.OPERATIONAL)
        graph.publishTriggers.midnightPass()
        assertEquals("UP", ha.state(STATUS))
        // The worker's reconcile is B4b2's (row 67).
    }

    @Test fun startAndResumeReadBackAVerifiedHelper() = run {
        yearRoundHeater()
        connect() // background checks Off: FOREGROUND_ONLY
        enable(PublishKind.EQUIPMENT_STATUS)
        record(OperationalCondition.OPERATIONAL)
        graph.publishTriggers.startPass()
        assertEquals("UP", ha.state(STATUS))

        ha.helpers[STATUS] = statusHelper("DOWN") // Home Assistant restarted and reset its helper
        ha.log.clear()
        graph.elapsedMillis = 10 * MINUTE
        graph.publishTriggers.resumePass()
        assertTrue("within an hour of the start's read: nothing read", ha.log.isEmpty())

        graph.elapsedMillis = 61 * MINUTE
        graph.publishTriggers.resumePass()
        assertEquals("an hour on, resume reads it back and repairs it", "UP", ha.state(STATUS))

        ha.helpers[STATUS] = statusHelper("DOWN")
        ha.log.clear()
        graph.elapsedMillis = 62 * MINUTE
        graph.publishTriggers.resumePass()
        assertTrue("the window counts from that read", ha.log.isEmpty())

        // The next open: a new process over the same store, its start always reading back.
        val before = graph
        graph = FakeGraph(db = before.db, secretStore = before.secretStore, installationDir = before.installationDir)
        extra += before
        graph.haPublish.helpers.putAll(before.haPublish.helpers)
        on("2026-02-10")
        graph.publishTriggers.startPass()
        assertEquals("the start repairs it", "UP", graph.haPublish.state(STATUS))
    }

    /** B4b1 review M2: a resume during the start's read-back waits for it, so a cold start reads each helper once. */
    @Test fun aResumeDuringTheStartsReadBackDoesNotReadAgain() = run {
        yearRoundHeater()
        connect()
        enable(PublishKind.EQUIPMENT_STATUS)
        record(OperationalCondition.OPERATIONAL)
        reconcile()
        val reading = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        ha.onRead = { reading.complete(Unit); release.await(); ha.answer(it) }

        coroutineScope {
            val start = async(Dispatchers.Default) { graph.publishTriggers.startPass() }
            reading.await()
            val resume = async(Dispatchers.Default) { graph.publishTriggers.resumePass() }
            delay(200)
            release.complete(Unit)
            start.await()
            resume.await()
        }
        assertEquals("one read-back per cold start", 1, ha.log.count { it == "GET $STATUS" })
    }

    /** B4b1 review M4 (N-1): a start pass that read nothing opens no window, so the next resume reads back. */
    @Test fun aStartThatReadNothingStartsNoWindow() = run {
        yearRoundHeater()
        connect()
        graph.publishTriggers.startPass() // no binding yet: nothing read
        enable(PublishKind.EQUIPMENT_STATUS)
        record(OperationalCondition.OPERATIONAL)
        reconcile()
        graph.publishRunner.drain()
        assertEquals("UP", ha.state(STATUS))

        ha.helpers[STATUS] = statusHelper("DOWN")
        graph.elapsedMillis = 10 * MINUTE
        graph.publishTriggers.resumePass()
        assertEquals("no window: resume reads back and repairs", "UP", ha.state(STATUS))
    }

    /** B4b1 review M1: an observed flow that fails ends the observer quietly, never on the scope's handler. */
    @Test fun aFailingInputEndsTheObserverQuietly() = run {
        val escaped = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> escaped += e })
        val inputs = PublishInputs(
            graph.conditions, graph.assets, graph.schedules, graph.groups, graph.transferRecords, graph.events,
            graph.seasonActivations, flow { throw IllegalStateException("example: the database is closed") },
            graph.seasonSyncBindings, graph.haConnections, graph.db.haPublishDecommissionDao().observeObligations(),
        )
        val triggers = PublishTriggers(
            graph.desiredStateReconciler, graph.publishProjector::deliveryWorkPending, graph.publishDispatch, inputs,
            graph.deliveryRequester, graph.todayPort, scope, { 0L }, MidnightTick(graph.clock, { ZoneOffset.UTC }),
        )
        triggers.observe().join()
        assertEquals(emptyList<Throwable>(), escaped)
        scope.cancel()
    }

    /**
     * B4b1 review M3 (A03): the delivery signal is computed apart from the projection's answer — an unrelated change
     * asks for nothing; a generation move alone (Check helper) and a new obligation alone (Disable) each ask once.
     */
    @Test fun theDeliverySignalIsComputedApartFromTheProjection() = run {
        yearRoundHeater()
        connect()
        enable(PublishKind.EQUIPMENT_STATUS)
        enable(PublishKind.SEASON_SERVICE_DATE)
        record(OperationalCondition.OPERATIONAL)
        val answers = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
        graph.desiredStateReconciler.answer = { graph.publishProjector.reconcileDesired(it).also { a -> answers += a } }
        val observer = graph.publishTriggers.observe()
        try {
            eventually("everything delivered") { answers.isNotEmpty() && !graph.publishProjector.deliveryWorkPending() }
            tenWindows()
            val n = graph.deliveryRequests.get()

            val projections = answers.size
            graph.assets.upsert(assetRow("example-boiler", "Example Boiler"))
            eventually("one more projection") { answers.size > projections }
            tenWindows()
            assertEquals("nothing pending: nothing asked", n, graph.deliveryRequests.get())

            graph.revalidatePublishing.run(heater, PublishKind.EQUIPMENT_STATUS, fresh(STATUS, PublishKind.EQUIPMENT_STATUS), null)
            eventually("a generation move alone asks once") { graph.deliveryRequests.get() == n + 1 }
            assertFalse(answers.last())
            // That request's pass drains tombstones too (C34): let it end, so the obligation below is the observer's.
            graph.awaitDispatched()

            graph.disablePublishing.run(heater, PublishKind.SEASON_SERVICE_DATE)
            eventually("a new obligation alone asks once") { graph.deliveryRequests.get() == n + 2 }
            assertFalse(answers.last())
        } finally {
            observer.cancel()
        }
    }

    // --- row 68: the midnight tick alone carries a time-only change (K14) -----------------------

    @Test fun aCalendarWindowEndPassingAtMidnightPublishes1970() = run {
        on("2026-10-31")
        calendarHeater()
        role()
        complete("2026-03-15") // after last season's end (2025-11-01): ready through 31 October
        connect()
        enable(PublishKind.SEASON_SERVICE_DATE)
        val started: Job = graph.publishTriggers.start()
        try {
            eventually("the start pass publishes the date") { ha.state(DATE) == "2026-03-15" }
            eventually("the tick sleeps") { graph.midnightSleeps.requested.isNotEmpty() }
            assertEquals("until local midnight", 15 * HOUR, graph.midnightSleeps.requested.first())
            tenWindows()
            val posts = ha.posts.size

            on("2026-11-01", hour = 0) // no database write: only the day moves
            graph.midnightSleeps.wake()

            eventually("the season ended at midnight") { ha.state(DATE) == INVALID_SERVICE_DATE }
            assertEquals(posts + 1, ha.posts.size)
            eventually("the tick re-arms") { graph.midnightSleeps.requested.size == 2 }
        } finally {
            started.cancel()
        }
    }

    // --- C, I28: the observer's sources, by name (R6) ---------------------------------------------

    @Test fun theObserverNeverWatchesADeliveryWrite() {
        val code = listOf("PublishProjector.kt", "PublishInputs.kt").flatMap { name ->
            source(name).filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }
        }
        assertTrue("no delivery flow", code.none { Regex("""deliveries\.observe""").containsMatchIn(it) })
        val observed = code.flatMap { line -> Regex("""(\w+)\.observeAll\(\)""").findAll(line).map { it.groupValues[1] }.toList() }
        assertEquals(listOf("conditions", "assets", "schedules", "groups", "seasonSync"), observed)
    }

    private fun source(name: String): List<String> {
        val path = "src/main/kotlin/com/loosecannon/servicetag/hapublish/$name"
        val file = listOf(File(path), File("app/$path")).firstOrNull { it.isFile } ?: error("cannot find $path")
        return file.readLines()
    }

    private fun event(kind: EventKind, title: String) = EventCommand(
        assetId = heater, profileId = null, kind = kind, title = title, occurredOn = graph.today.toString(),
        occurredTime = null, tzId = "UTC", notes = "", values = emptyMap(), consumables = emptyList(),
    )

    private companion object {
        const val STATUS = "input_select.example_heater_equipment_status"
        const val DATE = "input_datetime.example_heater_service_done"
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE

        fun statusHelper(state: String) = RemoteEntity(STATUS, state, listOf("UP", "DEGRADED", "DOWN"), null, null)

        fun dateHelper(state: String) = RemoteEntity(DATE, state, null, true, false)
    }
}
