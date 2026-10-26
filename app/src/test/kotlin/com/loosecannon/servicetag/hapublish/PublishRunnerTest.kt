package com.loosecannon.servicetag.hapublish

import com.loosecannon.servicetag.core.hapublish.FIELD_ENTITY_ID
import com.loosecannon.servicetag.core.hapublish.HaServiceAction
import com.loosecannon.servicetag.core.hapublish.INVALID_SERVICE_DATE
import com.loosecannon.servicetag.core.hapublish.PublishBinding
import com.loosecannon.servicetag.core.hapublish.PublishDelivery
import com.loosecannon.servicetag.core.hapublish.PublishErrorKind
import com.loosecannon.servicetag.core.hapublish.PublishKind
import com.loosecannon.servicetag.core.hapublish.PublishRefusal
import com.loosecannon.servicetag.core.hapublish.PublishRefused
import com.loosecannon.servicetag.core.hapublish.PublishState
import com.loosecannon.servicetag.core.hapublish.RemoteEntity
import com.loosecannon.servicetag.core.hapublish.RemoteRead
import com.loosecannon.servicetag.core.hapublish.ValidationResult
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.model.TransferRecord
import com.loosecannon.servicetag.core.seasonsync.BackgroundChecks
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncBinding
import com.loosecannon.servicetag.core.seasonsync.Secret
import com.loosecannon.servicetag.core.seasonsync.SyncMode
import com.loosecannon.servicetag.hapublish.PublishRig.Companion.DATE
import com.loosecannon.servicetag.hapublish.PublishRig.Companion.ENDED
import com.loosecannon.servicetag.hapublish.PublishRig.Companion.INBOUND
import com.loosecannon.servicetag.hapublish.PublishRig.Companion.STATUS
import com.loosecannon.servicetag.hapublish.PublishRig.Companion.dateHelper
import com.loosecannon.servicetag.hapublish.PublishRig.Companion.statusHelper
import com.loosecannon.servicetag.seasonsync.SeasonSyncSchedule
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.ScriptedHaPublish
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * #113/#114 (C30; rows 57, 57b, 54a's runner half, 101, 101a) — the runner over the Room-backed graph and a scripted
 * Home Assistant: one request at a time, no transaction across a request, a record that a moved generation drops, the
 * per-send eligibility, an uncertain POST read back before any other, the boundary step and its cache, and `validate`'s
 * one snapshot. Every helper, address and token is fictional.
 */
class PublishRunnerTest {

    private val rig = PublishRig()
    private val graph get() = rig.graph
    private val ha get() = rig.graph.haPublish

    @After fun close() = graph.close()

    // Row 57 — single flight, a disable during the request, no transaction across a request.

    @Test
    fun twoConcurrentDrainsPostOnce() = rig.run {
        val binding = enableStatus(desired = "UP", remote = "DOWN")
        val release = CompletableDeferred<Unit>()
        ha.onRead = { release.await(); ha.answer(it) }

        coroutineScope {
            val drains = List(2) { async(Dispatchers.Default) { graph.publishRunner.drain() } }
            delay(300) // both drains asked; without the lock both are now inside the read
            release.complete(Unit)
            drains.awaitAll()
        }

        assertEquals(listOf(ScriptedHaPublish.Call(HaServiceAction.SELECT_OPTION, STATUS, "UP")), ha.posts)
        assertEquals("UP", delivery(binding).lastPublishedValue)
    }

    @Test
    fun aDisableDuringTheRequestRecordsNothing() = rig.run {
        val binding = enableStatus(desired = "UP", remote = "DOWN")
        var afterDisable: PublishDelivery? = null
        ha.onCall = { call ->
            graph.disablePublishing.run(heater, PublishKind.EQUIPMENT_STATUS)
            afterDisable = delivery(binding)
            ha.act(call)
        }

        graph.publishRunner.drain()

        assertEquals("the reply at the old generation moved nothing", afterDisable, delivery(binding))
        assertNull(delivery(binding).lastPublishedAt)
        assertNull(delivery(binding).lastVerifiedAt)
        assertEquals("one POST, then nothing more for a disabled binding", 1, ha.posts.size)
    }

    @Test
    fun noTransactionSpansTheRequest() = rig.run {
        enableStatus(desired = "UP", remote = "DOWN")

        graph.publishRunner.drain()

        assertEquals(listOf("GET $STATUS", "POST $STATUS=UP", "GET $STATUS"), ha.log)
        assertEquals("no read or write transaction open at any request", listOf(0, 0, 0), ha.transactionsAtRequest)
        val stored = graph.haConnections.get()
        assertTrue("each request at the stored connection (B2)", ha.connections.all { it == stored })
        assertEquals(List(3) { Secret("fictional-token-1") }, ha.tokens)
    }

    // Row 57b — the per-send eligibility (A02).

    @Test
    fun aQueuedStatusSendForAnAssetArchivedBeforeTheRequestPostsNothing() = rig.run {
        enableStatus(desired = "DOWN", remote = "UP")
        graph.assets.upsert(graph.assets.get(heater)!!.copy(status = AssetStatus.ARCHIVED))

        val pass = graph.publishRunner.publishNow(heater)

        assertEquals(PassResult.Skipped(PublishState.NOT_MAINTAINED_HERE), pass.outcomes.single().result)
        assertTrue("nothing read or sent", ha.log.isEmpty())
        assertEquals("UP", ha.state(STATUS))
    }

    @Test
    fun aQueuedStatusSendForAnAssetTransferredOutPostsNothing() = rig.run {
        enableStatus(desired = "DOWN", remote = "UP")
        graph.transferRecords.append(
            TransferRecord("transfer-1", heater, TransferKind.OUT, "pack-1", emptyList(), 900L, "0".repeat(64), "Example Heater", ""),
        )

        graph.publishRunner.drain()

        assertTrue("nothing read or sent", ha.log.isEmpty())
        assertEquals("UP", ha.state(STATUS))
    }

    @Test
    fun aDateSendForAnIneligibleAssetSendsOnly1970() = rig.run {
        val binding = enableDate(desired = "2026-05-01", remote = "2026-04-01")
        graph.assets.upsert(graph.assets.get(heater)!!.copy(status = AssetStatus.ARCHIVED))

        // No projection yet: the stale date waits and nothing is sent.
        graph.publishRunner.drain()
        assertTrue("never the stale date", ha.posts.isEmpty())

        // The projector writes 1970-01-01 for an asset that left; the runner sends exactly that.
        graph.desiredStateReconciler.answer = { invalidate(binding) }
        graph.publishRunner.drain()

        assertEquals(listOf(ScriptedHaPublish.Call(HaServiceAction.SET_DATE, DATE, INVALID_SERVICE_DATE)), ha.posts)
        assertEquals(INVALID_SERVICE_DATE, ha.state(DATE))
    }

    // Row 54a, the runner's half — an outcome that may have acted is read back before any POST.

    @Test
    fun a500AfterTheServerChangedTheHelperIsReadBackNotResent() = rig.run {
        val binding = enableStatus(desired = "UP", remote = "DOWN")
        ha.onCall = { call -> ha.act(call); CallOutcome.Unknown("500") }

        assertTrue("an unknown outcome is transient work", graph.publishRunner.drain())
        assertEquals(PublishErrorKind.OUTCOME_UNKNOWN, delivery(binding).errorKind)
        assertNotNull(delivery(binding).outcomeUnknownSince)

        ha.onCall = { ha.act(it) }
        ha.log.clear()
        assertFalse(graph.publishRunner.drain())

        assertEquals("read first, and the helper already holds it", listOf("GET $STATUS"), ha.log)
        assertEquals(1, ha.posts.size)
        assertNull(delivery(binding).outcomeUnknownSince)
        assertFalse(delivery(binding).needsDelivery)
        assertEquals("UP", delivery(binding).lastVerifiedValue)
    }

    @Test
    fun anUnknownThatDidNotActIsReadThenSentOnce() = rig.run {
        enableStatus(desired = "UP", remote = "DOWN")
        ha.onCall = { CallOutcome.Unknown(null) }
        graph.publishRunner.drain()

        ha.onCall = { ha.act(it) }
        ha.log.clear()
        graph.publishRunner.drain()

        assertEquals(listOf("GET $STATUS", "POST $STATUS=UP", "GET $STATUS"), ha.log)
        assertEquals("UP", ha.state(STATUS))
    }

    @Test
    fun cancellationAroundCompletionLeavesTheAttemptOutstandingAndTheNextReadsFirst() = rig.run {
        val binding = enableStatus(desired = "UP", remote = "DOWN")
        val posted = CompletableDeferred<Unit>()
        ha.onCall = { call -> ha.act(call); posted.complete(Unit); awaitCancellation() }

        coroutineScope {
            val running = launch(Dispatchers.Default) { graph.publishRunner.drain() }
            posted.await()
            running.cancel()
            running.join()
        }

        val outstanding = delivery(binding)
        assertTrue(outstanding.needsDelivery)
        assertNotNull("the write-ahead keeps the attempt visible", outstanding.outcomeUnknownSince)

        ha.onCall = { ha.act(it) }
        ha.log.clear()
        graph.publishRunner.drain()
        assertEquals("read first, no blind resend", listOf("GET $STATUS"), ha.log)
        assertEquals("UP", delivery(binding).lastVerifiedValue)
    }

    // What remains for the worker is the retry class of what was recorded (B1's carry).

    @Test
    fun drainAnswersWhetherTransientWorkRemains() = rig.run {
        val binding = enableStatus(desired = "UP", remote = "DOWN")

        ha.onCall = { CallOutcome.Refused(PublishErrorKind.UNREACHABLE, null) }
        assertTrue(graph.publishRunner.drain())

        ha.onCall = { CallOutcome.Refused(PublishErrorKind.WRITE_FORBIDDEN, null) }
        assertFalse("a refused write waits for the owner", graph.publishRunner.publishNow(heater).transientWorkRemains)
        assertEquals(PublishErrorKind.WRITE_FORBIDDEN, delivery(binding).errorKind)

        ha.onRead = { RemoteRead.Failed(PublishErrorKind.HTTP_ERROR, "503") }
        assertTrue("a 5xx read is transient", graph.publishRunner.publishNow(heater).transientWorkRemains)
        ha.onRead = { RemoteRead.Failed(PublishErrorKind.HTTP_ERROR, "418") }
        assertFalse("a 4xx read is not", graph.publishRunner.publishNow(heater).transientWorkRemains)

        ha.onRead = { ha.answer(it) }
        ha.onCall = { ha.act(it) }
        val pass = graph.publishRunner.publishNow(heater)
        assertFalse(pass.transientWorkRemains)
        assertTrue(pass.readCompleted)
        assertEquals("UP", ha.state(STATUS))
    }

    // Row 101 — the boundary step (C9a).

    @Test
    fun theBoundaryReadIsCachedAtTheBindingRevisionAndAFailedReadKeepsTheOldCache() = rig.run {
        val binding = enableDate(desired = "2026-09-18", remote = "2026-09-18", haDecides = true, ended = "2026-05-10")
        val seeded = delivery(binding)
        assertEquals("2026-05-10", seeded.boundaryValue)

        // A valid read is cached at the binding's revision, with its read time.
        ha.helpers[ENDED] = dateHelper(ENDED, "2026-05-15")
        graph.now = 2_000L
        graph.publishRunner.publishNow(heater)
        assertEquals("2026-05-15", delivery(binding).boundaryValue)
        assertEquals(2_000L, delivery(binding).boundaryReadAt)
        assertEquals(binding.revision, delivery(binding).boundaryBindingRevision)

        // A failed read records nothing and keeps the old cache.
        ha.onRead = { if (it == ENDED) RemoteRead.Failed(PublishErrorKind.UNREACHABLE, null) else ha.answer(it) }
        graph.now = 3_000L
        graph.publishRunner.publishNow(heater)
        assertEquals("2026-05-15", delivery(binding).boundaryValue)
        assertEquals(2_000L, delivery(binding).boundaryReadAt)

        // A read answered after the binding moved (a token change, F-2) is not cached at the new revision.
        ha.helpers[ENDED] = dateHelper(ENDED, "2026-05-20")
        ha.onRead = { id ->
            if (id == ENDED) rig.connect(token = "fictional-token-2")
            ha.answer(id)
        }
        graph.now = 4_000L
        graph.publishRunner.publishNow(heater)
        val moved = graph.publishBindings.get(binding.id)!!
        assertEquals(binding.revision + 1, moved.revision)
        assertEquals("2026-05-15", delivery(binding).boundaryValue)
        assertEquals(binding.revision, delivery(binding).boundaryBindingRevision)
    }

    @Test
    fun aBoundaryHelperFailingValidationClearsValidation() = rig.run {
        val binding = enableDate(desired = "2026-09-18", remote = "2026-04-01", haDecides = true, ended = "2026-05-10")
        ha.helpers[ENDED] = RemoteEntity(ENDED, "2026-05-10 00:00:00", null, true, true)

        graph.publishRunner.publishNow(heater)

        val stored = graph.publishBindings.get(binding.id)!!
        assertNull("re-validation needed", stored.validatedRevision)
        assertEquals(PublishErrorKind.NOT_DATE_ONLY, delivery(binding).errorKind)
        assertEquals(stored.revision, delivery(binding).errorBindingRevision)
        assertEquals("nothing sent, the target not read", listOf("GET $ENDED"), ha.log)
    }

    @Test
    fun theBoundaryStepRunsTheProjectionBeforeTheSend() = rig.run {
        val binding = enableDate(desired = "2026-09-18", remote = "2026-09-18", haDecides = true, ended = "2026-05-10")
        ha.helpers[ENDED] = dateHelper(ENDED, "2026-10-01") // HA stamped a later end: the service no longer counts
        graph.desiredStateReconciler.answer = { invalidate(binding) }

        graph.publishRunner.publishNow(heater)

        assertEquals(listOf(graph.today), graph.desiredStateReconciler.days)
        assertEquals(
            listOf("GET $ENDED", "GET $DATE", "POST $DATE=$INVALID_SERVICE_DATE", "GET $DATE"),
            ha.log,
        )
        assertEquals(INVALID_SERVICE_DATE, ha.state(DATE))
    }

    // Row 101a — `validate`: both results from one snapshot.

    @Test
    fun validateReturnsTheTargetAndBoundaryResultsFromOneSnapshot() = rig.run {
        manualAssetFollowingHa()
        ha.helpers[DATE] = dateHelper(DATE, "2026-04-01")
        ha.helpers[ENDED] = dateHelper(ENDED, "2026-05-10")
        val before = graph.haConnections.get()!!
        ha.onRead = { id ->
            if (id == DATE) rig.connect(token = "fictional-token-2") // a save racing between the two reads
            ha.answer(id)
        }

        val validated = graph.publishRunner.validate(heater, PublishKind.SEASON_SERVICE_DATE, DATE, ENDED)
            as Validation.Validated

        val boundary = checkNotNull(validated.boundary)
        assertEquals(listOf(DATE, ENDED), listOf(validated.target.target, boundary.target))
        for (result in listOf(validated.target, boundary)) {
            assertEquals(before.id, result.connectionId)
            assertEquals(before.configGeneration, result.configGeneration)
            assertEquals(0L, result.bindingRevision)
        }
        try {
            graph.enablePublishing.run(
                heater, PublishKind.SEASON_SERVICE_DATE, DATE, validated.target, validated.boundary,
                graph.installationIdentity.id(),
            )
            fail("a read from before the save is stale")
        } catch (e: PublishRefused) {
            assertEquals(PublishRefusal.STALE_READ, e.reason)
        }
    }

    @Test
    fun aStatusValidationHasNoBoundaryResult() = rig.run {
        asset()
        connect()
        ha.helpers[STATUS] = statusHelper("UP")

        val validated = graph.publishRunner.validate(heater, PublishKind.EQUIPMENT_STATUS, STATUS, null)
            as Validation.Validated

        assertNull(validated.boundary)
        assertEquals(STATUS, validated.target.target)
        assertEquals(listOf("GET $STATUS"), ha.log)
        // The result is what Enable consumes.
        graph.enablePublishing.run(
            heater, PublishKind.EQUIPMENT_STATUS, STATUS, validated.target, null, graph.installationIdentity.id(),
        )
    }

    @Test
    fun validateReadsNothingWithoutAConnectionOrForAWrongDomain() = rig.run {
        asset()
        assertEquals(
            Validation.NoConnection,
            graph.publishRunner.validate(heater, PublishKind.EQUIPMENT_STATUS, STATUS, null),
        )
        connect()
        assertEquals(
            Validation.ReadFailed(FIELD_ENTITY_ID, PublishErrorKind.WRONG_DOMAIN, null),
            graph.publishRunner.validate(heater, PublishKind.EQUIPMENT_STATUS, INBOUND, null),
        )
        assertTrue(ha.log.isEmpty())
    }

    // C30 — the periodic work's rule is #16's, over any enabled binding or any PENDING tombstone.

    @Test
    fun theScheduleRuleCountsEnabledBindingsAndPendingTombstones() = rig.run {
        assertEquals(SeasonSyncSchedule.NONE, graph.publishRunner.schedule(backgroundAllowed = true))
        enableDate(desired = "2026-09-18", remote = "2026-09-18")
        assertEquals(SeasonSyncSchedule.FOREGROUND_ONLY, graph.publishRunner.schedule(backgroundAllowed = true))
        graph.disablePublishing.run(heater, PublishKind.SEASON_SERVICE_DATE) // a PENDING tombstone remains
        assertEquals(SeasonSyncSchedule.FOREGROUND_ONLY, graph.publishRunner.schedule(backgroundAllowed = true))
        connect(backgroundChecks = BackgroundChecks.ON)
        assertEquals(SeasonSyncSchedule.PERIODIC, graph.publishRunner.schedule(backgroundAllowed = true))
        assertEquals(SeasonSyncSchedule.PAUSED, graph.publishRunner.schedule(backgroundAllowed = false))
    }
}

/**
 * The shared fixture: one Room-backed graph, a fictional "Example Heater", the fictional connection, and helpers
 * Home Assistant holds in the graph's script. Each binding is enabled through B3's command with a read built here.
 */
internal class PublishRig(val graph: FakeGraph = FakeGraph()) {
    val heater = AssetId("asset-example-heater")

    /** Runs [block] with this rig as its receiver, bounded so a lock or transaction held by mistake fails, not hangs. */
    fun run(block: suspend PublishRig.() -> Unit) = runBlocking<Unit> {
        withTimeout(20_000) { block() }
    }

    suspend fun asset(mode: SeasonMode = SeasonMode.YEAR_ROUND) =
        graph.assets.upsert(Asset(id = heater, name = "Example Heater", createdAt = 500L, updatedAt = 500L, seasonMode = mode))

    suspend fun connect(token: String = "fictional-token-1", backgroundChecks: BackgroundChecks? = null) =
        graph.saveHaConnection.run(
            "http://192.168.0.10:8123", Secret(token), null, NetworkEligibility.HOME_NETWORK_ONLY, "ExampleHomeWifi",
            backgroundChecks,
        )

    /** A `MANUAL` asset whose season an enabled #16 binding drives: its date binding names a season-ended helper. */
    suspend fun manualAssetFollowingHa() {
        asset(SeasonMode.MANUAL)
        val connection = connect()
        graph.seasonSyncBindings.insert(
            SeasonSyncBinding(
                heater, connection.id, INBOUND, SyncMode.FOLLOW, enabled = true, revision = 1, observedState = null,
                observedChangedAt = null, lastSuccessAt = null, lastAttemptAt = null, errorKind = null, errorDetail = null,
                errorAt = null, appliedAction = null, appliedOn = null, appliedAt = null, lastAppliedSource = null,
                createdAt = 900L, updatedAt = 900L,
            ),
        )
    }

    /** A read as the runner's `validate` would build it, from the helper Home Assistant holds now. */
    suspend fun fresh(entityId: String, kind: PublishKind): ValidationResult {
        val connection = graph.haConnections.get()!!
        val revision = graph.publishBindings.forAsset(heater).firstOrNull { it.kind == kind }?.revision ?: 0
        return ValidationResult(entityId, graph.haPublish.helpers.getValue(entityId), connection.id, connection.configGeneration, revision, graph.now)
    }

    suspend fun enableStatus(desired: String?, remote: String): PublishBinding {
        asset()
        connect()
        graph.haPublish.helpers[STATUS] = statusHelper(remote)
        val binding = graph.enablePublishing.run(
            heater, PublishKind.EQUIPMENT_STATUS, STATUS, fresh(STATUS, PublishKind.EQUIPMENT_STATUS), null,
            graph.installationIdentity.id(),
        )
        check(graph.publishDeliveries.writeDesired(binding.id, binding.revision, 0, desired, graph.now))
        return binding
    }

    /** A date binding; with [haDecides], on a `MANUAL` asset following HA, naming [ENDED] as its season-ended helper. */
    suspend fun enableDate(desired: String, remote: String, haDecides: Boolean = false, ended: String? = null): PublishBinding {
        if (haDecides) manualAssetFollowingHa() else { asset(); connect() }
        graph.haPublish.helpers[DATE] = dateHelper(DATE, remote)
        ended?.let { graph.haPublish.helpers[ENDED] = dateHelper(ENDED, it) }
        val binding = graph.enablePublishing.run(
            heater, PublishKind.SEASON_SERVICE_DATE, DATE, fresh(DATE, PublishKind.SEASON_SERVICE_DATE),
            if (haDecides) fresh(ENDED, PublishKind.SEASON_SERVICE_DATE) else null, graph.installationIdentity.id(),
        )
        check(graph.publishDeliveries.writeDesired(binding.id, binding.revision, 0, desired, graph.now))
        return binding
    }

    /** What the projector writes for a date binding whose service no longer counts: `1970-01-01`, once. */
    suspend fun invalidate(binding: PublishBinding): Boolean {
        val stored = graph.publishBindings.get(binding.id)!!
        val delivery = graph.publishDeliveries.get(binding.id)!!
        if (delivery.desiredValue == INVALID_SERVICE_DATE) return false
        return graph.publishDeliveries.writeDesired(stored.id, stored.revision, delivery.desiredRevision, INVALID_SERVICE_DATE, graph.now)
    }

    suspend fun delivery(binding: PublishBinding): PublishDelivery = graph.publishDeliveries.get(binding.id)!!

    companion object {
        const val STATUS = "input_select.example_heater_equipment_status"
        const val DATE = "input_datetime.example_heater_service_done"
        const val ENDED = "input_datetime.example_heater_season_ended"
        const val INBOUND = "input_boolean.example_heater_in_season"

        fun statusHelper(state: String) = RemoteEntity(STATUS, state, listOf("UP", "DEGRADED", "DOWN"), null, null)

        fun dateHelper(entityId: String, state: String) = RemoteEntity(entityId, state, null, true, false)
    }
}
