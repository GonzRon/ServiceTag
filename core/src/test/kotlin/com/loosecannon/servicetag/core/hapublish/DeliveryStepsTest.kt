package com.loosecannon.servicetag.core.hapublish

import com.loosecannon.servicetag.core.condition.ConditionHistory
import com.loosecannon.servicetag.core.hapublish.DeliveryTrigger.CHANGE
import com.loosecannon.servicetag.core.hapublish.DeliveryTrigger.RECONCILE
import com.loosecannon.servicetag.core.hapublish.DeliveryTrigger.SCHEDULED
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetCondition
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.season.NotReadyReason
import com.loosecannon.servicetag.core.season.Readiness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #113/#114 (C17; rows 35–42, 42a, 45a) — the delivery state machine, driven the way the runner composes it (C30):
 * one snapshot, a read first, a write-ahead before every POST, a read-back after it, and every record a
 * compare-and-set on the snapshot's `(binding revision, desired revision)`. The helper is scripted; every value is
 * fictional.
 */
class DeliveryStepsTest {

    private val install = "installation-a"
    private val heaterId = AssetId("asset-example-heater")
    private val heater = Asset(id = heaterId, name = "Example Heater", createdAt = 1L, updatedAt = 1L)
    private val statusId = "input_select.example_heater_status"
    private val dateId = "input_datetime.example_heater_service_done"
    private val now = 50_000L

    private fun binding(
        kind: PublishKind = PublishKind.EQUIPMENT_STATUS,
        revision: Long = 1,
        enabled: Boolean = true,
        activated: String? = install,
        validatedRevision: Long? = revision,
    ) = PublishBinding(
        id = "binding-1",
        assetId = heaterId,
        connectionId = "connection-1",
        kind = kind,
        entityId = when (kind) {
            PublishKind.EQUIPMENT_STATUS -> statusId
            PublishKind.SEASON_SERVICE_DATE -> dateId
        },
        enabled = enabled,
        revision = revision,
        activatedInstallationId = activated,
        validatedAt = 5L,
        validatedRevision = validatedRevision,
        boundaryEntityId = null,
        createdAt = 1L,
        updatedAt = 5L,
    )

    private fun delivery(desired: String?, dRev: Long = 1) = PublishDelivery("binding-1", desired, dRev, 10L)

    /** [d] verified equal to [value] at `(bindingRevision, d.desiredRevision)`, by a read without our POST. */
    private fun verified(d: PublishDelivery, bindingRevision: Long, value: String) = d.copy(
        lastVerifiedValue = value,
        lastVerifiedAt = 20L,
        verifiedBindingRevision = bindingRevision,
        verifiedDesiredRevision = d.desiredRevision,
        remoteValue = value,
        remoteReadAt = 20L,
    )

    private fun snap(
        b: PublishBinding,
        d: PublishDelivery,
        asset: Asset? = heater,
        held: Set<AssetId> = emptySet(),
        connection: Boolean = true,
        token: Boolean = true,
    ) = Snap(b, d, connection, token, install, asset, held)

    private fun entity(state: String) = RemoteEntity(statusId, state, listOf("UP", "DOWN", "DEGRADED"), null, null)

    private enum class PostScript { APPLY, ACCEPT_IGNORED, REFUSE_FORBIDDEN, UNKNOWN_APPLIED, UNKNOWN_LOST }

    /** A scripted helper in Home Assistant: its state and shape, every request logged. */
    private class FakeHelper(
        var state: String,
        val entityId: String,
        var options: List<String>? = listOf("UP", "DOWN", "DEGRADED"),
        val dateOnly: Boolean = false,
    ) {
        val log = mutableListOf<String>()
        var post = PostScript.APPLY
        var readFailure: PublishErrorKind? = null
        var readBackFailure: PublishErrorKind? = null
        var optionsAfterPost: List<String>? = null

        fun read(back: Boolean = false): RemoteRead {
            log += "GET"
            val failure = if (back) readBackFailure ?: readFailure else readFailure
            if (failure != null) return RemoteRead.Failed(failure, null)
            return RemoteRead.Answered(
                RemoteEntity(entityId, state, options, if (dateOnly) true else null, if (dateOnly) false else null),
            )
        }

        fun posts(): List<String> = log.filter { it.startsWith("POST") }
    }

    /** The stored binding and delivery; a record lands only through the compare-and-set. */
    private class Store(var binding: PublishBinding, var delivery: PublishDelivery) {
        fun commit(record: DeliveryRecord): Boolean {
            val committed = DeliverySteps.commit(binding, delivery, record) ?: return false
            binding = committed.binding
            delivery = committed.delivery
            return true
        }
    }

    /** One pass over one binding, composed as the runner composes it (C30). */
    private fun pass(
        store: Store,
        helper: FakeHelper,
        trigger: DeliveryTrigger,
        at: Long = now,
        asset: Asset? = heater,
        held: Set<AssetId> = emptySet(),
    ) {
        if (!DeliverySteps.selects(store.binding, store.delivery, trigger)) return
        val s = snap(store.binding, store.delivery, asset, held)
        val readFirst = when (val plan = DeliverySteps.plan(s, trigger)) {
            is DeliveryPlan.Skip -> return
            DeliveryPlan.Done -> return
            is DeliveryPlan.ReadFirst -> plan
        }
        val b = s.binding
        var d = s.delivery
        if (readFirst.reconcile) {
            val ahead = DeliverySteps.reconcileWriteAhead(b, d)
            if (!store.commit(ahead)) return
            d = ahead.delivery
        }
        when (val step = DeliverySteps.afterRead(b, d, helper.read(), at)) {
            is ReadStep.Record -> store.commit(step.record)
            is ReadStep.Post -> {
                if (!store.commit(step.writeAhead)) return
                val ahead = step.writeAhead.delivery
                helper.log += "POST ${step.value}"
                val record = when (helper.post) {
                    PostScript.APPLY -> {
                        helper.state = step.value
                        helper.optionsAfterPost?.let { helper.options = it }
                        DeliverySteps.afterPostAccepted(b, ahead, helper.read(back = true), at)
                    }
                    PostScript.ACCEPT_IGNORED -> DeliverySteps.afterPostAccepted(b, ahead, helper.read(back = true), at)
                    PostScript.REFUSE_FORBIDDEN ->
                        DeliverySteps.afterPostRefused(b, ahead, PublishErrorKind.WRITE_FORBIDDEN, "403", at)
                    PostScript.UNKNOWN_APPLIED -> {
                        helper.state = step.value
                        DeliverySteps.afterPostUnknown(b, ahead, "502", at)
                    }
                    PostScript.UNKNOWN_LOST -> DeliverySteps.afterPostUnknown(b, ahead, null, at)
                }
                store.commit(record)
            }
        }
    }

    // Row 35 — #113: "a match is not a push".

    @Test
    fun anInitialPublicationReadsFirst() {
        val b = binding()
        val d = delivery("UP")
        assertEquals(DeliveryPlan.ReadFirst("UP", reconcile = false), DeliverySteps.plan(snap(b, d), CHANGE))

        val store = Store(b, d)
        val helper = FakeHelper("DOWN", statusId)
        pass(store, helper, CHANGE)
        assertEquals(listOf("GET", "POST UP", "GET"), helper.log)
        with(store.delivery) {
            assertEquals("UP", lastPublishedValue)
            assertEquals(now, lastPublishedAt)
            assertEquals(1L, publishedBindingRevision)
            assertEquals(1L, publishedDesiredRevision)
            assertEquals("UP", lastVerifiedValue)
            assertEquals(now, lastVerifiedAt)
            assertFalse(needsDelivery)
            assertNull(outcomeUnknownSince)
            assertNull(errorKind)
            assertEquals(1, attemptsAtRevision)
        }
        assertTrue(DeliverySteps.isCurrent(store.binding, store.delivery))
        assertEquals(DeliveryPlan.Done, DeliverySteps.plan(snap(store.binding, store.delivery), CHANGE))
    }

    @Test
    fun aMatchingHelperIsVerifiedNotPublished() {
        val store = Store(binding(), delivery("UP"))
        val helper = FakeHelper("UP", statusId)
        pass(store, helper, CHANGE)
        assertEquals(listOf("GET"), helper.log)
        with(store.delivery) {
            assertNull(lastPublishedAt)
            assertNull(lastPublishedValue)
            assertNull(publishedDesiredRevision)
            assertEquals("UP", lastVerifiedValue)
            assertEquals(now, lastVerifiedAt)
            assertEquals(1L, verifiedBindingRevision)
            assertEquals(1L, verifiedDesiredRevision)
            assertEquals("UP", remoteValue)
            assertFalse(needsDelivery)
        }
        assertTrue(DeliverySteps.isCurrent(store.binding, store.delivery))
    }

    // Row 36 — coalescing (AC113-5, K1): only the latest desired value exists to send.

    @Test
    fun downDegradedUpBeforeDeliverySendsUpOnce() {
        val store = Store(binding(), delivery(null, dRev = 0))
        val helper = FakeHelper("DEGRADED", statusId)
        for (value in listOf("DOWN", "DEGRADED", "UP")) {
            // The projector's writes, before any pass runs.
            store.delivery = store.delivery.copy(
                desiredValue = value,
                desiredRevision = store.delivery.desiredRevision + 1,
                attemptsAtRevision = 0,
            )
        }
        pass(store, helper, CHANGE)
        pass(store, helper, CHANGE)
        pass(store, helper, SCHEDULED)
        assertEquals(listOf("POST UP"), helper.posts())
        assertEquals(3L, store.delivery.publishedDesiredRevision)
        assertEquals("UP", helper.state)

        // Structural: the delivery holds one desired slot and no queue of values to send.
        val collections = PublishDelivery::class.java.declaredFields.filter {
            Collection::class.java.isAssignableFrom(it.type) || it.type.isArray
        }
        assertEquals(emptyList(), collections.map { it.name })
    }

    // Row 37 — the gate (AC113-2).

    @Test
    fun waitingForSourceSendsNothing() {
        val noCondition = ConditionHistory.of(emptyList())
        val notReady = Readiness.NotReady(NotReadyReason.NO_ROLE, null, null)
        assertEquals(
            Desired.Nothing(PublishState.WAITING_FOR_SOURCE),
            desiredValueOf(binding(), heater, maintainedHere = true, history = noCondition, readiness = notReady),
        )
        val store = Store(binding(), delivery(null, dRev = 0))
        assertEquals(
            DeliveryPlan.Skip(PublishState.WAITING_FOR_SOURCE),
            DeliverySteps.plan(snap(store.binding, store.delivery), RECONCILE),
        )
        val helper = FakeHelper("DOWN", statusId)
        for (trigger in DeliveryTrigger.entries) pass(store, helper, trigger)
        assertEquals(emptyList(), helper.log)
    }

    @Test
    fun notEnabledCopiedNoTokenOrUnvalidatedSkip() {
        val d = delivery("UP")
        fun planOf(s: Snap) = DeliverySteps.plan(s, RECONCILE)
        assertEquals(DeliveryPlan.Skip(PublishState.NOT_ENABLED), planOf(snap(binding(enabled = false), d)))
        assertEquals(DeliveryPlan.Skip(PublishState.COPIED_INSTALLATION), planOf(snap(binding(activated = "installation-b"), d)))
        assertEquals(DeliveryPlan.Skip(PublishState.COPIED_INSTALLATION), planOf(snap(binding(activated = null), d)))
        assertEquals(DeliveryPlan.Skip(PublishState.NEEDS_TOKEN), planOf(snap(binding(), d, token = false)))
        assertEquals(DeliveryPlan.Skip(PublishState.NEEDS_TOKEN), planOf(snap(binding(), d, connection = false)))
        assertEquals(DeliveryPlan.Skip(PublishState.NEEDS_VALIDATION), planOf(snap(binding(validatedRevision = null), d)))
        assertEquals(
            DeliveryPlan.Skip(PublishState.NEEDS_VALIDATION),
            planOf(snap(binding(revision = 2, validatedRevision = 1), d)),
        )

        // Per-send eligibility (A02): the status kind stops and keeps the remote.
        assertEquals(DeliveryPlan.Skip(PublishState.NOT_MAINTAINED_HERE), planOf(snap(binding(), d, asset = null)))
        assertEquals(DeliveryPlan.Skip(PublishState.NOT_MAINTAINED_HERE), planOf(snap(binding(), d, held = setOf(heaterId))))
        assertEquals(
            DeliveryPlan.Skip(PublishState.NOT_MAINTAINED_HERE),
            planOf(snap(binding(), d, asset = heater.copy(retiredOn = "2026-10-01"))),
        )

        // The date kind sends only 1970-01-01 once its asset has left; anything else waits for the projector.
        val date = binding(kind = PublishKind.SEASON_SERVICE_DATE)
        assertEquals(
            DeliveryPlan.Skip(PublishState.PENDING, reproject = true),
            planOf(snap(date, delivery("2026-09-18"), held = setOf(heaterId))),
        )
        assertEquals(DeliveryPlan.Skip(PublishState.PENDING, reproject = true), planOf(snap(date, delivery(null))))
        assertEquals(
            DeliveryPlan.ReadFirst(INVALID_SERVICE_DATE, reconcile = true),
            planOf(snap(date, delivery(INVALID_SERVICE_DATE), asset = null)),
        )
        val store = Store(date, delivery(INVALID_SERVICE_DATE))
        val helper = FakeHelper("2026-09-18", dateId, options = null, dateOnly = true)
        pass(store, helper, CHANGE, asset = null)
        assertEquals(listOf("POST 1970-01-01"), helper.posts())
        assertEquals(INVALID_SERVICE_DATE, store.delivery.lastVerifiedValue)
    }

    // Row 38 — AC113-7, K10: reconcile mode repairs a reset helper with no source change.

    @Test
    fun aResetHelperIsRepairedInReconcileModeWithoutASourceChange() {
        val store = Store(binding(), verified(delivery("UP"), 1, "UP"))
        val helper = FakeHelper("DOWN", statusId) // HA restarted and reset the helper
        pass(store, helper, CHANGE)
        pass(store, helper, SCHEDULED)
        assertEquals(emptyList(), helper.log, "nothing outstanding: an ordinary pass makes no request")

        pass(store, helper, RECONCILE)
        assertEquals(listOf("GET", "POST UP", "GET"), helper.log)
        assertEquals("UP", helper.state)
        with(store.delivery) {
            assertEquals("UP", desiredValue)
            assertEquals(1L, desiredRevision)
            assertEquals(now, lastPublishedAt)
            assertFalse(reconcilePending)
            assertFalse(needsDelivery)
        }
    }

    // Row 39 — an ambiguous completion reads before it resends (AC113-6).

    @Test
    fun outcomeUnknownReadsBeforeResending() {
        val store = Store(binding(), delivery("UP"))
        val helper = FakeHelper("DOWN", statusId)
        helper.post = PostScript.UNKNOWN_APPLIED
        pass(store, helper, CHANGE)
        assertEquals(listOf("GET", "POST UP"), helper.log)
        with(store.delivery) {
            assertEquals(PublishErrorKind.OUTCOME_UNKNOWN, errorKind)
            assertTrue(errorIsCurrent(store.binding))
            assertEquals(now, outcomeUnknownSince)
            assertTrue(needsDelivery)
        }
        assertEquals(DeliveryPlan.ReadFirst("UP", reconcile = false), DeliverySteps.plan(snap(store.binding, store.delivery), CHANGE))

        helper.post = PostScript.APPLY
        pass(store, helper, SCHEDULED, at = now + 1)
        assertEquals(listOf("GET", "POST UP", "GET"), helper.log, "one read, no second POST")
        with(store.delivery) {
            assertEquals("UP", lastVerifiedValue)
            assertEquals(now + 1, lastVerifiedAt)
            assertNull(lastPublishedAt, "verified, not published")
            assertNull(outcomeUnknownSince)
            assertFalse(needsDelivery)
            assertNull(errorKind)
        }
    }

    @Test
    fun outcomeUnknownWithADifferentRemotePosts() {
        val store = Store(binding(), delivery("UP"))
        val helper = FakeHelper("DOWN", statusId)
        helper.post = PostScript.UNKNOWN_LOST
        pass(store, helper, CHANGE)
        helper.post = PostScript.APPLY
        pass(store, helper, CHANGE, at = now + 1)
        assertEquals(listOf("GET", "POST UP", "GET", "POST UP", "GET"), helper.log)
        assertEquals(now + 1, store.delivery.lastPublishedAt)
        assertNull(store.delivery.outcomeUnknownSince)
        assertEquals(2, store.delivery.attemptsAtRevision)
    }

    // Row 40 — generation fencing (AC113-6).

    @Test
    fun aStaleReplyAtAMovedGenerationRecordsNothing() {
        val b = binding()
        val d = delivery("UP")
        val matched = assertIs<ReadStep.Record>(DeliverySteps.afterRead(b, d, RemoteRead.Answered(entity("UP")), now))
        assertNotNull(DeliverySteps.commit(b, d, matched.record), "the control: at the snapshot's pair it lands")

        val disabled = b.copy(enabled = false, revision = 2)
        assertNull(DeliverySteps.commit(disabled, d, matched.record))
        val retargeted = b.copy(entityId = "input_select.example_boiler_status", revision = 2)
        assertNull(DeliverySteps.commit(retargeted, d, matched.record))

        // The POST path: a write-ahead that cannot land abandons the attempt, and the stored rows stay as they were.
        val store = Store(b, d)
        val post = assertIs<ReadStep.Post>(DeliverySteps.afterRead(b, d, RemoteRead.Answered(entity("DOWN")), now))
        store.binding = disabled
        assertFalse(store.commit(post.writeAhead))
        assertEquals(d, store.delivery)
        assertEquals(disabled, store.binding)
    }

    @Test
    fun aReplyForAnOlderDesiredRevisionRecordsNothing() {
        val b = binding()
        val d = delivery("UP", dRev = 7).copy(needsDelivery = true, outcomeUnknownSince = now)
        val published = DeliverySteps.afterPostAccepted(b, d, RemoteRead.Answered(entity("UP")), now)
        assertNotNull(DeliverySteps.commit(b, d, published), "the control")
        val newer = d.copy(desiredValue = "DOWN", desiredRevision = 8)
        assertNull(DeliverySteps.commit(b, newer, published))
        val refused = DeliverySteps.afterPostRefused(b, d, PublishErrorKind.WRITE_FORBIDDEN, "403", now)
        assertNull(DeliverySteps.commit(b, newer, refused))
    }

    // Row 41 — the read-back decides; a 200 alone is never "published".

    @Test
    fun aReadbackThatDiffersAfterA200IsRemoteDiffers() {
        val store = Store(binding(), delivery("UP"))
        val helper = FakeHelper("DOWN", statusId)
        helper.post = PostScript.ACCEPT_IGNORED
        pass(store, helper, CHANGE)
        assertEquals(listOf("GET", "POST UP", "GET"), helper.log)
        with(store.delivery) {
            assertEquals(PublishErrorKind.REMOTE_DIFFERS, errorKind)
            assertTrue(errorIsCurrent(store.binding))
            assertEquals("DOWN", remoteValue)
            assertNull(lastPublishedAt)
            assertNull(lastVerifiedValue)
            assertTrue(needsDelivery)
        }
        assertFalse(DeliverySteps.selects(store.binding, store.delivery, CHANGE))
        assertTrue(DeliverySteps.selects(store.binding, store.delivery, RECONCILE))
    }

    @Test
    fun aReadbackThatFailsValidationClearsTheValidation() {
        val store = Store(binding(), delivery("UP"))
        val helper = FakeHelper("DOWN", statusId)
        helper.optionsAfterPost = listOf("UP", "DOWN") // the options changed in HA
        pass(store, helper, CHANGE)
        assertEquals(PublishErrorKind.OPTIONS_MISMATCH, store.delivery.errorKind)
        assertNull(store.binding.validatedRevision)
        assertNull(store.delivery.lastPublishedAt)
        assertEquals(
            DeliveryPlan.Skip(PublishState.NEEDS_VALIDATION),
            DeliverySteps.plan(snap(store.binding, store.delivery), RECONCILE),
        )

        // The first read validates too: a re-optioned helper is never written.
        val first = Store(binding(), delivery("UP"))
        val reoptioned = FakeHelper("DOWN", statusId, options = listOf("UP", "DOWN", "DEGRADED", "UNKNOWN"))
        pass(first, reoptioned, CHANGE)
        assertEquals(listOf("GET"), reoptioned.log)
        assertEquals(PublishErrorKind.OPTIONS_MISMATCH, first.delivery.errorKind)
        assertNull(first.binding.validatedRevision)

        // A read-back GET that fails leaves the outcome unknown, never published.
        val lost = Store(binding(), delivery("UP"))
        val failing = FakeHelper("DOWN", statusId)
        failing.readBackFailure = PublishErrorKind.TIMED_OUT
        pass(lost, failing, CHANGE)
        with(lost.delivery) {
            assertEquals(PublishErrorKind.OUTCOME_UNKNOWN, errorKind)
            assertEquals(now, outcomeUnknownSince)
            assertTrue(needsDelivery)
            assertNull(lastPublishedAt)
        }
    }

    // Row 42 — K1: the retry classes, and a transport failure is never DOWN.

    @Test
    fun eachKindHasItsRetryClass() {
        val table = mapOf(
            PublishErrorKind.ENDPOINT_REFUSED to RetryClass.NEEDS_OWNER,
            PublishErrorKind.DENIED to RetryClass.WAIT,
            PublishErrorKind.UNREACHABLE to RetryClass.TRANSIENT,
            PublishErrorKind.TIMED_OUT to RetryClass.TRANSIENT,
            PublishErrorKind.AUTH_REFUSED to RetryClass.NEEDS_OWNER,
            PublishErrorKind.REDIRECTED to RetryClass.NEEDS_OWNER,
            PublishErrorKind.HTTP_ERROR to RetryClass.TRANSIENT, // a GET's 5xx, below
            PublishErrorKind.MALFORMED to RetryClass.NEEDS_OWNER,
            PublishErrorKind.NEEDS_TOKEN to RetryClass.NEEDS_OWNER,
            PublishErrorKind.NOT_ON_LOCAL_NETWORK to RetryClass.WAIT,
            PublishErrorKind.NAME_NOT_LOCAL to RetryClass.NEEDS_OWNER,
            PublishErrorKind.TLS_FAILED to RetryClass.NEEDS_OWNER,
            PublishErrorKind.ENTITY_NOT_FOUND to RetryClass.NEEDS_OWNER,
            PublishErrorKind.WRITE_FORBIDDEN to RetryClass.NEEDS_OWNER,
            PublishErrorKind.WRONG_DOMAIN to RetryClass.NEEDS_OWNER,
            PublishErrorKind.OPTIONS_MISMATCH to RetryClass.NEEDS_OWNER,
            PublishErrorKind.NOT_DATE_ONLY to RetryClass.NEEDS_OWNER,
            PublishErrorKind.SERVICE_REFUSED to RetryClass.NEEDS_OWNER,
            PublishErrorKind.OUTCOME_UNKNOWN to RetryClass.TRANSIENT,
            PublishErrorKind.REMOTE_DIFFERS to RetryClass.NEEDS_OWNER,
            PublishErrorKind.COPIED_INSTALLATION to RetryClass.NEEDS_OWNER,
        )
        assertEquals(PublishErrorKind.entries.toSet(), table.keys, "every kind is in the table")
        for ((kind, retry) in table) {
            val detail = if (kind == PublishErrorKind.HTTP_ERROR) "503" else null
            assertEquals(retry, retryClassOf(kind, detail), "$kind")
        }
        assertEquals(RetryClass.TRANSIENT, retryClassOf(PublishErrorKind.HTTP_ERROR, "500"))
        assertEquals(RetryClass.TRANSIENT, retryClassOf(PublishErrorKind.HTTP_ERROR, "599"))
        assertEquals(RetryClass.NEEDS_OWNER, retryClassOf(PublishErrorKind.HTTP_ERROR, "404"), "a 4xx is permanent")
        assertEquals(RetryClass.NEEDS_OWNER, retryClassOf(PublishErrorKind.HTTP_ERROR, "400"))
        assertEquals(RetryClass.NEEDS_OWNER, retryClassOf(PublishErrorKind.HTTP_ERROR, null))
    }

    @Test
    fun aTransportFailureIsNeverDown() {
        val operational = ConditionHistory.of(
            listOf(AssetCondition("c1", heaterId, OperationalCondition.OPERATIONAL, "2026-10-01", null, "UTC", "", null, 1L)),
        )
        val b = binding()
        val store = Store(b, verified(delivery("UP"), 1, "UP"))
        for (kind in listOf(PublishErrorKind.UNREACHABLE, PublishErrorKind.TIMED_OUT, PublishErrorKind.NOT_ON_LOCAL_NETWORK)) {
            val helper = FakeHelper("UP", statusId)
            helper.readFailure = kind
            pass(store, helper, RECONCILE)
            assertEquals(listOf("GET"), helper.log, "$kind")
            with(store.delivery) {
                assertEquals(kind, errorKind)
                assertEquals("UP", desiredValue, "$kind")
                assertEquals(1L, desiredRevision)
                assertEquals("UP", remoteValue, "$kind: a failure is no reading")
                assertEquals("UP", lastVerifiedValue)
                assertTrue(reconcilePending, "$kind: the reconcile is still owed")
            }
        }
        assertEquals("UP", equipmentStatusOf(operational))
        assertEquals(
            Desired.Send("UP"),
            desiredValueOf(b, heater, maintainedHere = true, history = operational, readiness = Readiness.NotReady(NotReadyReason.NO_ROLE, null, null)),
        )
        // The pending reconcile is outstanding work: an ordinary pass retries the read in reconcile mode (C, row 57d).
        store.delivery = store.delivery.copy(errorKind = PublishErrorKind.UNREACHABLE)
        assertTrue(DeliverySteps.selects(store.binding, store.delivery, CHANGE))
        assertEquals(DeliveryPlan.ReadFirst("UP", reconcile = true), DeliverySteps.plan(snap(store.binding, store.delivery), CHANGE))
    }

    /** C — a reconcile's completed read hands a difference to `needsDelivery` in the write that clears its flag. */
    @Test
    fun aReconcileReadMovesTheWorkFromOneFlagToTheOther() {
        val b = binding()
        val d = verified(delivery("UP"), 1, "UP")
        val ahead = DeliverySteps.reconcileWriteAhead(b, d)
        assertTrue(ahead.delivery.reconcilePending)
        assertEquals(1L to 1L, ahead.bindingRevision to ahead.desiredRevision)

        val post = assertIs<ReadStep.Post>(DeliverySteps.afterRead(b, ahead.delivery, RemoteRead.Answered(entity("DOWN")), now))
        with(post.writeAhead.delivery) {
            assertFalse(reconcilePending)
            assertTrue(needsDelivery)
            assertEquals(now, outcomeUnknownSince)
            assertEquals(now, lastAttemptAt)
            assertEquals("DOWN", remoteValue)
        }
        assertEquals(HaServiceAction.SELECT_OPTION, post.action)
        assertEquals("UP", post.value)

        // The boundary helper's read still owed (C9a): the flag stays.
        val owed = assertIs<ReadStep.Record>(
            DeliverySteps.afterRead(b, ahead.delivery, RemoteRead.Answered(entity("UP")), now, boundaryPending = true),
        )
        assertTrue(owed.record.delivery.reconcilePending)
        assertEquals("UP", owed.record.delivery.lastVerifiedValue)

        // A refused repair keeps the delivery owed and the flag already handed over.
        val refused = DeliverySteps.afterPostRefused(b, post.writeAhead.delivery, PublishErrorKind.WRITE_FORBIDDEN, "403", now)
        assertTrue(refused.delivery.needsDelivery)
        assertNull(refused.delivery.outcomeUnknownSince)
        assertFalse(refused.delivery.reconcilePending)
    }

    // Row 42a — R-2, keyed to the persisted error pair (A04).

    @Test
    fun aNeedsOwnerErrorIsNotRetriedByDrainUntilThePairMovesOrPublishNow() {
        val b = binding(revision = 1)
        val d = delivery("UP", dRev = 7).copy(
            needsDelivery = true,
            errorKind = PublishErrorKind.WRITE_FORBIDDEN,
            errorDetail = "403",
            errorAt = 30L,
            errorBindingRevision = 1,
            errorDesiredRevision = 7,
        )
        assertFalse(DeliverySteps.selects(b, d, CHANGE))
        assertFalse(DeliverySteps.selects(b, d, SCHEDULED))
        assertTrue(DeliverySteps.selects(b, d, RECONCILE))
        assertEquals(DeliveryPlan.Skip(PublishState.ERROR), DeliverySteps.plan(snap(b, d), CHANGE))

        val store = Store(b, d)
        val helper = FakeHelper("DOWN", statusId)
        pass(store, helper, CHANGE)
        pass(store, helper, SCHEDULED)
        assertEquals(emptyList(), helper.log, "no request until the pair moves or Publish now")
        pass(store, helper, RECONCILE)
        assertEquals(listOf("GET", "POST UP", "GET"), helper.log)

        // The pair moves: a re-authorisation (F-2 moves the validation with it) or a new desired value.
        val reauthorised = b.copy(revision = 2, validatedRevision = 2)
        assertTrue(DeliverySteps.selects(reauthorised, d, CHANGE))
        assertTrue(DeliverySteps.selects(reauthorised, d, SCHEDULED))
        assertTrue(DeliverySteps.selects(b, d.copy(desiredValue = "DOWN", desiredRevision = 8), CHANGE))

        // WAIT: skipped by the tick and the worker, retried at an eligibility change and at resume.
        val denied = d.copy(errorKind = PublishErrorKind.DENIED, errorDetail = null)
        assertTrue(DeliverySteps.selects(b, denied, CHANGE))
        assertFalse(DeliverySteps.selects(b, denied, SCHEDULED))
        assertTrue(DeliverySteps.selects(b, denied, RECONCILE))

        // TRANSIENT: never skipped.
        val unreachable = d.copy(errorKind = PublishErrorKind.UNREACHABLE, errorDetail = null)
        for (trigger in DeliveryTrigger.entries) assertTrue(DeliverySteps.selects(b, unreachable, trigger), "$trigger")
    }

    // Row 45a — F-1: the verified pair decides, never the value alone.

    @Test
    fun aVerifiedValueAtAnOlderBindingRevisionIsNotDone() {
        val d = verified(delivery("UP"), 1, "UP")
        assertTrue(DeliverySteps.isCurrent(binding(revision = 1), d))
        assertEquals(DeliveryPlan.Done, DeliverySteps.plan(snap(binding(revision = 1), d), CHANGE))

        val reauthorised = binding(revision = 2)
        assertFalse(DeliverySteps.isCurrent(reauthorised, d))
        assertEquals(DeliveryPlan.ReadFirst("UP", reconcile = false), DeliverySteps.plan(snap(reauthorised, d), CHANGE))

        val b = binding(revision = 1)
        assertFalse(DeliverySteps.isCurrent(b, d.copy(needsDelivery = true)))
        assertFalse(DeliverySteps.isCurrent(b, d.copy(reconcilePending = true)))
        assertFalse(DeliverySteps.isCurrent(b, d.copy(outcomeUnknownSince = 30L)))
        assertEquals(DeliveryPlan.ReadFirst("UP", reconcile = true), DeliverySteps.plan(snap(b, d), RECONCILE))
    }

    @Test
    fun drainSelectsByThePairNotTheValue() {
        val b = binding()
        val d = verified(delivery("UP"), 1, "UP")
        assertFalse(DeliverySteps.selects(b, d, CHANGE))
        assertFalse(DeliverySteps.selects(b, d, SCHEDULED))

        // DOWN, then UP again, before any delivery: the value equals what was verified; the pair does not.
        val again = d.copy(desiredValue = "UP", desiredRevision = 3)
        assertTrue(DeliverySteps.selects(b, again, CHANGE))
        assertTrue(DeliverySteps.selects(b, again, SCHEDULED))
        assertEquals(DeliveryPlan.ReadFirst("UP", reconcile = false), DeliverySteps.plan(snap(b, again), CHANGE))

        // Reconcile mode selects every enabled binding, and only those.
        assertTrue(DeliverySteps.selects(b, d, RECONCILE))
        assertFalse(DeliverySteps.selects(b.copy(enabled = false), again, RECONCILE))
        assertFalse(DeliverySteps.selects(b.copy(enabled = false), again, CHANGE))
    }
}
