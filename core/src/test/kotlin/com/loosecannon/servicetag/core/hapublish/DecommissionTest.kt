package com.loosecannon.servicetag.core.hapublish

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.testing.InMemoryHaPublish
import com.loosecannon.servicetag.core.testing.tombstoneOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * #114 (C34, the tombstone table, B1's and B3's carries) — the decommission state machine, pure: the gate, what a read
 * and a POST come to, the retry class read from the live answer; the one helper that owes a tombstone; and the derived
 * `PublishState` a read shows, quiet only once `1970-01-01` is read back. Every helper and id is fictional.
 */
class DecommissionTest {

    private val here = "installation-a"
    private val date = "input_datetime.example_heater_service_done"
    private val now = 9_000L
    private val owed = tombstoneOf("t-1", date, connectionId = "conn-1", installationId = here)

    private fun answered(state: String) = RemoteRead.Answered(RemoteEntity(date, state, null, hasDate = true, hasTime = false))

    // --- the gate -------------------------------------------------------------------------------------------------

    @Test
    fun aPendingRowOfThisInstallationWithItsConnectionAndTokenIsReadFirst() {
        assertEquals(TombstonePlan.ReadFirst, DecommissionSteps.plan(owed, here, "conn-1", hasToken = true, now = now))
    }

    @Test
    fun anUndeliverableRowWaitsForTheOwner() {
        val drawn = owed.copy(state = DecommissionState.UNDELIVERABLE)
        assertEquals(TombstonePlan.Wait, DecommissionSteps.plan(drawn, here, "conn-1", hasToken = true, now = now))
    }

    @Test
    fun anotherInstallationsRowIsUndeliverableAsCopiedWithNothingSent() {
        val plan = assertIs<TombstonePlan.GiveUp>(DecommissionSteps.plan(owed, "installation-b", "conn-1", true, now))
        assertEquals(DecommissionState.UNDELIVERABLE, plan.record.state)
        assertEquals(PublishErrorKind.COPIED_INSTALLATION, plan.record.errorKind)
        assertEquals(owed.revision, plan.record.revision, "the drain's compare-and-set never moves the revision")
    }

    @Test
    fun noTokenOrAnotherConnectionIsUndeliverable() {
        for (plan in listOf(
            DecommissionSteps.plan(owed, here, "conn-1", hasToken = false, now = now),
            DecommissionSteps.plan(owed, here, "conn-2", hasToken = true, now = now),
            DecommissionSteps.plan(owed, here, null, hasToken = false, now = now),
        )) {
            val giveUp = assertIs<TombstonePlan.GiveUp>(plan)
            assertEquals(DecommissionState.UNDELIVERABLE, giveUp.record.state)
            assertEquals(PublishErrorKind.NEEDS_TOKEN, giveUp.record.errorKind)
        }
    }

    // --- the read, the POST and the read-back ----------------------------------------------------------------------

    @Test
    fun aReadOf1970ClearsWithoutAPost() {
        assertEquals(TombstoneStep.Cleared, DecommissionSteps.afterRead(owed, answered(INVALID_SERVICE_DATE), now))
    }

    @Test
    fun anotherDateIsPosted() {
        assertEquals(TombstoneStep.Post, DecommissionSteps.afterRead(owed, answered("2025-11-15"), now))
    }

    @Test
    fun theRetryClassComesFromTheLiveDetailNeverTheRow() {
        val fiveHundred = RemoteRead.Failed(PublishErrorKind.HTTP_ERROR, "503")
        val pending = assertIs<TombstoneStep.Record>(DecommissionSteps.afterRead(owed, fiveHundred, now))
        assertEquals(DecommissionState.PENDING, pending.record.state)
        assertEquals(PublishErrorKind.HTTP_ERROR, pending.record.errorKind)
        assertEquals("503", pending.detail)

        val fourOhFour = RemoteRead.Failed(PublishErrorKind.HTTP_ERROR, "404")
        val drawn = assertIs<TombstoneStep.Record>(DecommissionSteps.afterRead(owed, fourOhFour, now))
        assertEquals(DecommissionState.UNDELIVERABLE, drawn.record.state)
    }

    @Test
    fun transientAndWaitStayPendingAndNeedsOwnerIsUndeliverable() {
        val cases = mapOf(
            PublishErrorKind.UNREACHABLE to DecommissionState.PENDING,
            PublishErrorKind.TIMED_OUT to DecommissionState.PENDING,
            PublishErrorKind.NOT_ON_LOCAL_NETWORK to DecommissionState.PENDING,
            PublishErrorKind.DENIED to DecommissionState.PENDING,
            PublishErrorKind.AUTH_REFUSED to DecommissionState.UNDELIVERABLE,
            PublishErrorKind.ENTITY_NOT_FOUND to DecommissionState.UNDELIVERABLE,
        )
        for ((kind, state) in cases) {
            val step = assertIs<TombstoneStep.Record>(DecommissionSteps.afterRead(owed, RemoteRead.Failed(kind, null), now))
            assertEquals(state, step.record.state, kind.name)
            assertEquals(kind, step.record.errorKind)
            assertEquals(now, step.record.errorAt)
        }
    }

    @Test
    fun aHelperThatIsNoLongerDateOnlyIsUndeliverable() {
        val withTime = RemoteRead.Answered(RemoteEntity(date, "2025-11-15 00:00:00", null, hasDate = true, hasTime = true))
        val step = assertIs<TombstoneStep.Record>(DecommissionSteps.afterRead(owed, withTime, now))
        assertEquals(DecommissionState.UNDELIVERABLE, step.record.state)
        assertEquals(PublishErrorKind.NOT_DATE_ONLY, step.record.errorKind)
    }

    @Test
    fun aPostIsClearedOnlyByItsReadBack() {
        assertEquals(TombstoneStep.Cleared, DecommissionSteps.afterPostAccepted(owed, answered(INVALID_SERVICE_DATE), now))
        val differs = assertIs<TombstoneStep.Record>(DecommissionSteps.afterPostAccepted(owed, answered("2025-11-15"), now))
        assertEquals(PublishErrorKind.REMOTE_DIFFERS, differs.record.errorKind)
        assertEquals(DecommissionState.UNDELIVERABLE, differs.record.state)
        val lost = assertIs<TombstoneStep.Record>(
            DecommissionSteps.afterPostAccepted(owed, RemoteRead.Failed(PublishErrorKind.TIMED_OUT, null), now),
        )
        assertEquals(PublishErrorKind.OUTCOME_UNKNOWN, lost.record.errorKind)
        assertEquals(DecommissionState.PENDING, lost.record.state)
    }

    @Test
    fun aRefusedPostKeepsItsKindAndAnUnknownOneStaysPending() {
        val forbidden = assertIs<TombstoneStep.Record>(
            DecommissionSteps.afterPostRefused(owed, PublishErrorKind.WRITE_FORBIDDEN, null, now),
        )
        assertEquals(DecommissionState.UNDELIVERABLE, forbidden.record.state)
        val unknown = assertIs<TombstoneStep.Record>(DecommissionSteps.afterPostUnknown(owed, "502", now))
        assertEquals(PublishErrorKind.OUTCOME_UNKNOWN, unknown.record.errorKind)
        assertEquals(DecommissionState.PENDING, unknown.record.state)
    }

    // --- the one helper that owes a tombstone (B4b1's carry) -------------------------------------------------------

    @Test
    fun oweInvalidationOwesADateHelperAndNeverAStatusOneOrAClaimedOne() = runTest {
        val publish = InMemoryHaPublish()
        val left = binding(PublishKind.SEASON_SERVICE_DATE, date)
        oweInvalidation(left, publish.bindings, publish.tombstones, { "t-new" }, now)
        val held = publish.tombstones.all().single()
        assertEquals(DecommissionState.PENDING, held.state)
        assertEquals(here, held.installationId)
        assertEquals(now, held.requestedAt)

        val status = InMemoryHaPublish()
        oweInvalidation(binding(PublishKind.EQUIPMENT_STATUS, "input_select.example_heater_status"), status.bindings, status.tombstones, { "t" }, now)
        assertEquals(emptyList(), status.tombstones.all())

        val claimed = InMemoryHaPublish()
        claimed.bindings.insert(binding(PublishKind.SEASON_SERVICE_DATE, date).copy(id = "b-2", assetId = AssetId("example-boiler")))
        oweInvalidation(left, claimed.bindings, claimed.tombstones, { "t" }, now)
        assertNull(claimed.tombstones.byTarget("conn-1", date), "I19: an enabled claim writes its own value")
    }

    // --- the derived state (C14) -----------------------------------------------------------------------------------

    @Test
    fun theQuietStateComesOnlyAfterTheReadBack() {
        val archived = Asset(id = HEATER, name = "Example Heater", status = AssetStatus.ARCHIVED, createdAt = 1L, updatedAt = 1L)
        val b = binding(PublishKind.SEASON_SERVICE_DATE, date)
        val owing = PublishDelivery(b.id, INVALID_SERVICE_DATE, desiredRevision = 2, desiredAt = 10L)
        assertEquals(PublishState.PENDING, DeliverySteps.stateOf(snap(b, owing, archived)))

        val readBack = owing.copy(
            lastVerifiedValue = INVALID_SERVICE_DATE, lastVerifiedAt = 20L, verifiedBindingRevision = 1, verifiedDesiredRevision = 2,
        )
        assertEquals(PublishState.INVALIDATED_INELIGIBLE, DeliverySteps.stateOf(snap(b, readBack, archived)))
        assertEquals(PublishState.MATCHES, DeliverySteps.stateOf(snap(b, readBack, archived.copy(status = AssetStatus.ACTIVE))))
        val ours = readBack.copy(lastPublishedValue = INVALID_SERVICE_DATE, publishedBindingRevision = 1, publishedDesiredRevision = 2)
        assertEquals(PublishState.PUBLISHED, DeliverySteps.stateOf(snap(b, ours, archived.copy(status = AssetStatus.ACTIVE))))
    }

    @Test
    fun theGateErrorsAndOutcomesEachHaveTheirState() {
        val b = binding(PublishKind.EQUIPMENT_STATUS, "input_select.example_heater_status")
        val d = PublishDelivery(b.id, "UP", desiredRevision = 1, desiredAt = 10L)
        val active = Asset(id = HEATER, name = "Example Heater", createdAt = 1L, updatedAt = 1L)
        assertEquals(PublishState.NOT_ENABLED, DeliverySteps.stateOf(snap(b.copy(enabled = false), d, active)))
        assertEquals(PublishState.COPIED_INSTALLATION, DeliverySteps.stateOf(snap(b.copy(activatedInstallationId = "x"), d, active)))
        assertEquals(PublishState.NEEDS_TOKEN, DeliverySteps.stateOf(snap(b, d, active).copy(hasToken = false)))
        assertEquals(PublishState.NEEDS_VALIDATION, DeliverySteps.stateOf(snap(b.copy(validatedRevision = null), d, active)))
        assertEquals(PublishState.WAITING_FOR_SOURCE, DeliverySteps.stateOf(snap(b, d.copy(desiredValue = null), active)))
        assertEquals(PublishState.NOT_MAINTAINED_HERE, DeliverySteps.stateOf(snap(b, d, null)))
        assertEquals(PublishState.PENDING, DeliverySteps.stateOf(snap(b, d, active)))
        val failed = d.copy(errorKind = PublishErrorKind.AUTH_REFUSED, errorAt = 5L, errorBindingRevision = 1, errorDesiredRevision = 1)
        assertEquals(PublishState.ERROR, DeliverySteps.stateOf(snap(b, failed, active)))
        assertEquals(PublishState.PENDING, DeliverySteps.stateOf(snap(b, failed.copy(errorBindingRevision = 0), active)), "A04")
        val unknown = failed.copy(errorKind = PublishErrorKind.OUTCOME_UNKNOWN, outcomeUnknownSince = 4L)
        assertEquals(PublishState.OUTCOME_UNKNOWN, DeliverySteps.stateOf(snap(b, unknown, active)))
        assertEquals(PublishState.OUTCOME_UNKNOWN, DeliverySteps.stateOf(snap(b, d.copy(outcomeUnknownSince = 4L), active)))
    }

    private fun binding(kind: PublishKind, entityId: String) = PublishBinding(
        id = "b-1", assetId = HEATER, connectionId = "conn-1", kind = kind, entityId = entityId, enabled = true,
        revision = 1, activatedInstallationId = here, validatedAt = 5L, validatedRevision = 1, boundaryEntityId = null,
        createdAt = 1L, updatedAt = 5L,
    )

    private fun snap(b: PublishBinding, d: PublishDelivery, asset: Asset?) = Snap(b, d, true, true, here, asset, emptySet())

    private companion object {
        val HEATER = AssetId("heater")
    }
}
