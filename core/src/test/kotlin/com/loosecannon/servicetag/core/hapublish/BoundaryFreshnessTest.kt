package com.loosecannon.servicetag.core.hapublish

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.season.HaBoundary
import com.loosecannon.servicetag.core.seasonsync.SyncCadence
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #114 (C9a, D; row 101b) — whose season end decides an asset, and whether Home Assistant's cached date is fresh:
 * read at the binding's current revision, within max(a week, the cadence), and not before the newest activation.
 * Every value is fictional.
 */
class BoundaryFreshnessTest {

    private val day = 24L * 60 * 60 * 1000
    private val readAt = 1_000L * day
    private val validatedAt = readAt - 3 * day
    private val seasonEnded = LocalDate.parse("2026-05-10")

    private fun dateBinding(revision: Long = 4, helper: String? = "input_datetime.example_season_ended") = PublishBinding(
        id = "binding-date",
        assetId = AssetId("asset-example-heater"),
        connectionId = "connection-1",
        kind = PublishKind.SEASON_SERVICE_DATE,
        entityId = "input_datetime.example_heater_service_done",
        enabled = true,
        revision = revision,
        activatedInstallationId = "installation-a",
        validatedAt = validatedAt,
        validatedRevision = revision,
        boundaryEntityId = helper,
        createdAt = validatedAt - day,
        updatedAt = validatedAt,
    )

    private val cache = BoundaryCache("2026-05-10", readAt, 4)

    private fun boundary(
        now: Long,
        cache: BoundaryCache? = this.cache,
        binding: PublishBinding? = dateBinding(),
        mode: SeasonMode = SeasonMode.MANUAL,
        inbound: Boolean = true,
        cadence: SyncCadence = SyncCadence.DAILY,
        activation: Long? = null,
    ): HaBoundary = haBoundaryOf(cache, binding, mode, inbound, cadence, activation, now)

    @Test
    fun freshWithinSevenDays() {
        assertEquals(HaBoundary.Fresh(seasonEnded, readAt), boundary(now = readAt))
        assertEquals(HaBoundary.Fresh(seasonEnded, readAt), boundary(now = readAt + 7 * day))
        assertEquals(HaBoundary.Unverified(seasonEnded, readAt), boundary(now = readAt + 7 * day + 1))
        // A short cadence never narrows the week.
        assertEquals(HaBoundary.Fresh(seasonEnded, readAt), boundary(now = readAt + 7 * day, cadence = SyncCadence.EVERY_12_HOURS))
        assertEquals(HaBoundary.Unverified(seasonEnded, readAt), boundary(now = readAt + 8 * day, cadence = SyncCadence.WEEKLY))
    }

    @Test
    fun aLongerCadenceWidensTheWindow() {
        assertEquals(HaBoundary.Fresh(seasonEnded, readAt), boundary(now = readAt + 8 * day, cadence = SyncCadence.MONTHLY))
        assertEquals(HaBoundary.Fresh(seasonEnded, readAt), boundary(now = readAt + 30 * day, cadence = SyncCadence.MONTHLY))
        assertEquals(HaBoundary.Unverified(seasonEnded, readAt), boundary(now = readAt + 31 * day, cadence = SyncCadence.MONTHLY))
    }

    @Test
    fun anOlderRevisionIsUnverified() {
        assertEquals(HaBoundary.Unverified(seasonEnded, readAt), boundary(now = readAt + day, binding = dateBinding(revision = 5)))
    }

    @Test
    fun aNewerActivationIsUnverified() {
        assertEquals(HaBoundary.Unverified(seasonEnded, readAt), boundary(now = readAt + day, activation = readAt + 1))
        assertEquals(HaBoundary.Fresh(seasonEnded, readAt), boundary(now = readAt + day, activation = readAt))
        assertEquals(HaBoundary.Fresh(seasonEnded, readAt), boundary(now = readAt + day, activation = readAt - day))
    }

    @Test
    fun neverReadIsUnverifiedSinceValidation() {
        assertEquals(HaBoundary.Unverified(null, validatedAt), boundary(now = readAt, cache = null))
        assertEquals(HaBoundary.Unverified(null, validatedAt), boundary(now = readAt, cache = BoundaryCache(null, null, null)))
        // A cached text that is not a day is no valid read.
        assertEquals(
            HaBoundary.Unverified(null, validatedAt),
            boundary(now = readAt, cache = BoundaryCache("unknown", readAt, 4)),
        )
    }

    @Test
    fun calendarNoInboundOrNoHelperIsNotUsed() {
        // A CALENDAR asset's window decides: a fresh, later HA date never reaches it.
        assertEquals(HaBoundary.NotUsed, boundary(now = readAt, mode = SeasonMode.CALENDAR))
        assertEquals(HaBoundary.NotUsed, boundary(now = readAt, mode = SeasonMode.YEAR_ROUND))
        assertEquals(HaBoundary.NotUsed, boundary(now = readAt, inbound = false))
        assertEquals(HaBoundary.NotUsed, boundary(now = readAt, binding = dateBinding(helper = null)))
        assertEquals(HaBoundary.NotUsed, boundary(now = readAt, binding = null))
        val statusBinding = dateBinding().copy(kind = PublishKind.EQUIPMENT_STATUS)
        assertEquals(HaBoundary.NotUsed, boundary(now = readAt, binding = statusBinding))
    }

    /** Ruling (5): Stop publishing never hands the cutoff back to the phone; Disable moved the revision. */
    @Test
    fun aDisabledDateBindingStillLeavesTheBoundaryToHa() {
        val disabled = dateBinding(revision = 5).copy(enabled = false)
        assertEquals(HaBoundary.Unverified(seasonEnded, readAt), boundary(now = readAt, binding = disabled))
        assertEquals(HaBoundary.NotUsed, boundary(now = readAt, binding = disabled.copy(boundaryEntityId = null)))
    }
}
