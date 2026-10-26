package com.loosecannon.servicetag.core.season

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.HealthAggregation
import com.loosecannon.servicetag.core.model.HealthSubjectId
import com.loosecannon.servicetag.core.model.MaintenanceGroup
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleRole
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonActivation
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.testing.SeasonFixtures.activationOf
import com.loosecannon.servicetag.core.testing.SeasonFixtures.assetOf
import com.loosecannon.servicetag.core.testing.completionOf
import com.loosecannon.servicetag.core.testing.dayMillis
import com.loosecannon.servicetag.core.testing.groupOf
import com.loosecannon.servicetag.core.testing.scheduleOf
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #114 (C9): the readiness rule over fictional fixtures (rows 3–11, 3a–3c, 4a). The base is scenario 1: the
 * "Example Heater", `MANUAL`, its season ended on 2026-04-20, the role set on 2026-01-10 on its own schedule
 * `s-role`, and a service on 2026-09-18.
 */
class SeasonReadinessTest {

    private fun on(date: String): LocalDate = LocalDate.parse(date)

    private val heater: Asset = assetOf("heater", "Example Heater", SeasonMode.MANUAL)

    private fun roleOf(schedule: MaintenanceSchedule, since: String = "2026-01-10"): MaintenanceSchedule =
        schedule.copy(role = ScheduleRole.SEASON_START, roleSinceOn = since)

    private val roleSchedule: MaintenanceSchedule =
        roleOf(scheduleOf(id = "s-role", assetId = "heater", title = "Example annual service"))

    private fun end(day: String, assetId: String = "heater", id: String = "end-$assetId-$day"): SeasonActivation =
        activationOf(id, assetId, SeasonAction.END, day)

    private fun start(day: String, assetId: String = "heater", id: String = "start-$assetId-$day"): SeasonActivation =
        activationOf(id, assetId, SeasonAction.START, day)

    /** A completion of [scheduleId] written (its `updatedAt`) at midnight UTC of its own day unless told otherwise. */
    private fun done(
        day: String,
        scheduleId: String = "s-role",
        assetId: String = "heater",
        detailsPending: Boolean = false,
        imported: Boolean = false,
        id: String = "e-$assetId-$scheduleId-$day",
    ): AssetEvent = completionOf(
        id = id,
        occurredOn = day,
        occurrenceOn = day,
        assetId = assetId,
        scheduleId = scheduleId,
        detailsPending = detailsPending,
    ).copy(imported = imported)

    /** [event] as written at [localDateTime] in [tzId]: its `updatedAt` and its own zone id. */
    private fun writtenAt(event: AssetEvent, localDateTime: String, tzId: String): AssetEvent = event.copy(
        tzId = tzId,
        updatedAt = LocalDateTime.parse(localDateTime).atZone(ZoneId.of(tzId)).toInstant().toEpochMilli(),
    )

    private val scenario1Activations = listOf(start("2025-10-01"), end("2026-04-20"))

    private fun readiness(
        today: String,
        asset: Asset = heater,
        assets: Collection<Asset> = listOf(asset),
        schedules: Collection<MaintenanceSchedule> = listOf(roleSchedule),
        groups: Collection<MaintenanceGroup> = emptyList(),
        events: Collection<AssetEvent> = emptyList(),
        activations: List<SeasonActivation> = scenario1Activations,
        zone: ZoneId = ZoneOffset.UTC,
        ha: HaBoundary = HaBoundary.NotUsed,
    ): Readiness = seasonReadiness(asset, assets, schedules, groups, events, activations, on(today), zone, ha)

    private fun ready(serviceOn: String, boundary: ReadinessBoundary, scheduleId: String = "s-role"): Readiness =
        Readiness.Ready(on(serviceOn), ScheduleId(scheduleId), boundary)

    private fun notReady(reason: NotReadyReason, boundary: ReadinessBoundary?, scheduleId: String? = "s-role"): Readiness =
        Readiness.NotReady(reason, scheduleId?.let(::ScheduleId), boundary)

    private fun phoneEnd(cutoff: String) = ReadinessBoundary(on(cutoff), BoundarySource.PHONE_SEASON_END)
    private fun commissioning(cutoff: String) = ReadinessBoundary(on(cutoff), BoundarySource.COMMISSIONING)

    // ---------------------------------------------------------------------------------------------
    // Row 3 — the core rule (AC114-2) and O-3.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun aQualifyingCompletionAfterTheEndIsReady() {
        assertEquals(
            ready("2026-09-18", phoneEnd("2026-04-20")),
            readiness("2026-09-20", events = listOf(done("2026-09-18"))),
        )
        // Services dated on or after the cutoff count: the END day itself is the first that does.
        assertEquals(
            ready("2026-04-20", phoneEnd("2026-04-20")),
            readiness("2026-09-20", events = listOf(done("2026-04-20"))),
        )
    }

    @Test
    fun theLatestOfSeveralQualifyingIsPublished() {
        val events = listOf(done("2026-06-30"), done("2026-09-18"), done("2026-05-02"))
        assertEquals(ready("2026-09-18", phoneEnd("2026-04-20")), readiness("2026-09-20", events = events))
    }

    // ---------------------------------------------------------------------------------------------
    // Row 3a — the boundary per asset (C9, C9a, D, I25).
    // ---------------------------------------------------------------------------------------------

    private val lateObservation = listOf(start("2025-10-01"), end("2026-10-22"))

    @Test
    fun aFreshHaBoundaryWinsOverALaterPhoneEnd() {
        val readAt = dayMillis("2026-10-24")
        assertEquals(
            ready("2026-09-18", ReadinessBoundary(on("2026-05-10"), BoundarySource.HA_SEASON_ENDED, checkedAt = readAt)),
            readiness(
                "2026-10-25",
                events = listOf(done("2026-09-18")),
                activations = lateObservation,
                ha = HaBoundary.Fresh(on("2026-05-10"), readAt),
            ),
        )
    }

    @Test
    fun anUnverifiedBoundaryUsesTheLaterOfTheCacheAndThePhoneEnd() {
        val since = dayMillis("2026-10-01")
        val events = listOf(done("2026-09-18"))
        assertEquals(
            notReady(
                NotReadyReason.NOT_DONE_SINCE_SEASON_END,
                ReadinessBoundary(on("2026-10-22"), BoundarySource.HA_UNVERIFIED, unverifiedSince = since),
            ),
            readiness("2026-10-25", events = events, activations = lateObservation, ha = HaBoundary.Unverified(on("2026-05-10"), since)),
            "the phone's later END withholds: a stale cache never passes as HA's word",
        )
        assertEquals(
            notReady(
                NotReadyReason.NOT_DONE_SINCE_SEASON_END,
                ReadinessBoundary(on("2026-09-20"), BoundarySource.HA_UNVERIFIED, unverifiedSince = since),
            ),
            readiness("2026-10-25", events = events, ha = HaBoundary.Unverified(on("2026-09-20"), since)),
            "a cached date later than the phone's END withholds too",
        )
        assertEquals(
            ready("2026-09-18", ReadinessBoundary(on("2026-04-20"), BoundarySource.HA_UNVERIFIED, unverifiedSince = since)),
            readiness("2026-10-25", events = events, ha = HaBoundary.Unverified(null, since)),
            "nothing cached: the phone's END, still reported unverified",
        )
    }

    @Test
    fun notUsedUsesThePhoneEnd() {
        val events = listOf(done("2026-09-18"))
        assertEquals(
            notReady(NotReadyReason.NOT_DONE_SINCE_SEASON_END, phoneEnd("2026-10-22")),
            readiness("2026-10-25", events = events, activations = lateObservation),
        )
        assertEquals(
            readiness("2026-10-25", events = events, activations = lateObservation),
            readiness("2026-10-25", events = events, activations = lateObservation, ha = HaBoundary.NotUsed),
            "NotUsed is the default",
        )
    }

    @Test
    fun theCutoffAndItsSourceAreReported() {
        val readAt = dayMillis("2026-10-24")
        val since = dayMillis("2026-10-01")
        val events = listOf(done("2026-09-18"))
        val fresh = HaBoundary.Fresh(on("2026-05-10"), readAt)
        assertEquals(
            ready("2026-09-18", commissioning("2026-06-01")),
            readiness("2026-10-25", schedules = listOf(roleOf(roleSchedule, "2026-06-01")), events = events, ha = fresh),
            "roleSinceOn later than HA's date: COMMISSIONING, and no check time",
        )
        assertEquals(
            ready("2026-09-18", ReadinessBoundary(on("2026-05-10"), BoundarySource.HA_SEASON_ENDED, checkedAt = readAt)),
            readiness("2026-10-25", schedules = listOf(roleOf(roleSchedule, "2026-05-10")), events = events, ha = fresh),
            "a tie reports the season end's source",
        )
        assertEquals(
            ready("2026-09-18", ReadinessBoundary(on("2026-06-01"), BoundarySource.COMMISSIONING, unverifiedSince = since)),
            readiness(
                "2026-10-25",
                schedules = listOf(roleOf(roleSchedule, "2026-06-01")),
                events = events,
                ha = HaBoundary.Unverified(on("2026-05-10"), since),
            ),
            "unverified is reported whatever won",
        )
        assertEquals(
            ready("2026-09-18", commissioning("2026-01-10")),
            readiness("2026-10-25", events = events, activations = listOf(start("2025-10-01"))),
            "no season end yet: COMMISSIONING",
        )
        val calendar = assetOf("heater", "Example Heater", SeasonMode.CALENDAR, seasonStart = "11-01", seasonEnd = "03-31")
        assertEquals(
            ready("2026-09-18", ReadinessBoundary(on("2026-04-01"), BoundarySource.CALENDAR_WINDOW)),
            readiness("2026-10-25", asset = calendar, events = events, ha = HaBoundary.Fresh(on("2026-09-20"), readAt)),
            "a CALENDAR asset's window is never overridden by HA",
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Row 3b — the evidence rule: an imported event never counts (B, I24).
    // ---------------------------------------------------------------------------------------------

    @Test
    fun anImportedEventNeverQualifies() {
        assertEquals(
            notReady(NotReadyReason.INHERITED_SERVICE_ONLY, phoneEnd("2026-04-20")),
            readiness("2026-09-18", events = listOf(done("2026-09-18", imported = true))),
            "imported on the day of the seller's service",
        )
    }

    @Test
    fun anImportedFutureDatedEventNeverQualifiesOnItsDay() {
        val events = listOf(done("2026-09-25", imported = true))
        assertEquals(
            notReady(NotReadyReason.NOT_DONE_SINCE_SEASON_END, phoneEnd("2026-04-20")),
            readiness("2026-09-20", events = events),
            "an imported event is never FUTURE_DATED either",
        )
        assertEquals(
            notReady(NotReadyReason.INHERITED_SERVICE_ONLY, phoneEnd("2026-04-20")),
            readiness("2026-09-25", events = events),
        )
        assertEquals(
            notReady(NotReadyReason.INHERITED_SERVICE_ONLY, phoneEnd("2026-04-20")),
            readiness("2026-10-10", events = events),
        )
    }

    @Test
    fun theRecipientsOwnServiceQualifies() {
        assertEquals(
            ready("2026-09-19", phoneEnd("2026-04-20")),
            readiness("2026-09-20", events = listOf(done("2026-09-18", imported = true), done("2026-09-19"))),
        )
        assertEquals(
            ready("2026-09-10", phoneEnd("2026-04-20")),
            readiness("2026-09-20", events = listOf(done("2026-09-10"), done("2026-09-18", imported = true))),
            "an older own service wins over a newer imported one",
        )
    }

    @Test
    fun noClockOrCreatedAtTakesPart() {
        val events = listOf(done("2026-09-18"), done("2026-09-25"), done("2026-05-01", detailsPending = true))
        val baseline = readiness("2026-09-20", events = events)
        assertEquals(ready("2026-09-18", phoneEnd("2026-04-20")), baseline)
        for (createdAt in listOf(0L, dayMillis("2020-01-01"), dayMillis("2030-01-01"), Long.MAX_VALUE)) {
            assertEquals(
                baseline,
                readiness("2026-09-20", events = events.map { it.copy(createdAt = createdAt) }),
                "createdAt = $createdAt",
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Row 3c — story 1: a warm spell's END is harmless while HA's boundary is fresh (D, E).
    // ---------------------------------------------------------------------------------------------

    private val warmSpell = listOf(start("2024-10-01"), end("2025-05-12"), start("2025-10-05"), end("2026-03-10"), start("2026-03-20"))
    private val story1Role = roleOf(roleSchedule, "2025-04-01")

    @Test
    fun aWarmSpellEndIsHarmlessWhileTheHaBoundaryIsFresh() {
        val readAt = dayMillis("2026-03-24")
        assertEquals(
            ready("2025-09-18", ReadinessBoundary(on("2025-05-10"), BoundarySource.HA_SEASON_ENDED, checkedAt = readAt)),
            readiness(
                "2026-03-25",
                schedules = listOf(story1Role),
                events = listOf(done("2025-09-18")),
                activations = warmSpell,
                ha = HaBoundary.Fresh(on("2025-05-10"), readAt),
            ),
        )
    }

    @Test
    fun theSameSpellWithholdsWhileUnverified() {
        val since = dayMillis("2026-02-01")
        assertEquals(
            notReady(
                NotReadyReason.NOT_DONE_SINCE_SEASON_END,
                ReadinessBoundary(on("2026-03-10"), BoundarySource.HA_UNVERIFIED, unverifiedSince = since),
            ),
            readiness(
                "2026-03-25",
                schedules = listOf(story1Role),
                events = listOf(done("2025-09-18")),
                activations = warmSpell,
                ha = HaBoundary.Unverified(on("2025-05-10"), since),
            ),
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Row 4 — the same-day pin (#114 §2) and O-16's future-dated read.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun aCompletionOnTheEndDayCounts() {
        assertEquals(
            ready("2026-04-20", phoneEnd("2026-04-20")),
            readiness("2026-04-25", events = listOf(done("2026-04-20"))),
        )
    }

    @Test
    fun aCompletionTheDayBeforeTheEndDoesNot() {
        assertEquals(
            notReady(NotReadyReason.NOT_DONE_SINCE_SEASON_END, phoneEnd("2026-04-20")),
            readiness("2026-04-25", events = listOf(done("2026-04-19"))),
        )
    }

    @Test
    fun aFutureDatedCompletionIsFutureDated() {
        // Written on its own day (a merged or restored row): only "on or before today" keeps it out.
        val future = done("2026-09-25")
        assertEquals(
            notReady(NotReadyReason.FUTURE_DATED, phoneEnd("2026-04-20")),
            readiness("2026-09-20", events = listOf(future)),
        )
        assertEquals(
            ready("2026-09-10", phoneEnd("2026-04-20")),
            readiness("2026-09-20", events = listOf(done("2026-09-10"), future)),
            "a future-dated row never hides a qualifying one",
        )
        assertEquals(ready("2026-09-25", phoneEnd("2026-04-20")), readiness("2026-09-25", events = listOf(future)), "on its day it counts")
    }

    // ---------------------------------------------------------------------------------------------
    // Row 4a — the write day (rev 6, O-16 amended, I37).
    // ---------------------------------------------------------------------------------------------

    private val advance = writtenAt(done("2026-10-20"), "2026-10-18T12:00", "Europe/Paris")

    @Test
    fun anAdvanceRecordNeverBecomesAServiceAtMidnight() {
        assertEquals(notReady(NotReadyReason.FUTURE_DATED, phoneEnd("2026-04-20")), readiness("2026-10-19", events = listOf(advance)))
        for (today in listOf("2026-10-20", "2026-10-25", "2027-03-01")) {
            assertEquals(
                notReady(NotReadyReason.RECORDED_IN_ADVANCE, phoneEnd("2026-04-20")),
                readiness(today, events = listOf(advance)),
                "on $today",
            )
        }
    }

    @Test
    fun anEditOnOrAfterItsDayReAttestsIt() {
        val editedLater = writtenAt(advance, "2026-10-21T09:00", "Europe/Paris")
        assertEquals(ready("2026-10-20", phoneEnd("2026-04-20")), readiness("2026-10-21", events = listOf(editedLater)))
        val editedOnTheDay = writtenAt(advance, "2026-10-20T00:01", "Europe/Paris")
        assertEquals(ready("2026-10-20", phoneEnd("2026-04-20")), readiness("2026-10-20", events = listOf(editedOnTheDay)))
    }

    @Test
    fun theWriteDayIsReadInTheEventsOwnZone() {
        // 00:30 on 10-20 in Paris is still 10-19 in New York: the event's own zone decides.
        val event = writtenAt(done("2026-10-20"), "2026-10-20T00:30", "Europe/Paris")
        assertEquals(
            ready("2026-10-20", phoneEnd("2026-04-20")),
            readiness("2026-10-22", events = listOf(event), zone = ZoneId.of("America/New_York")),
        )
    }

    @Test
    fun anUnresolvableTzIdFallsBackToTheZone() {
        // 01:00 on 10-20 in Tokyo is 16:00 on 10-19 in UTC.
        val instant = LocalDateTime.parse("2026-10-20T01:00").atZone(ZoneId.of("Asia/Tokyo")).toInstant().toEpochMilli()
        for (tzId in listOf("Not/A_Zone", "", "+99:00")) {
            val event = done("2026-10-20").copy(tzId = tzId, updatedAt = instant)
            assertEquals(
                ready("2026-10-20", phoneEnd("2026-04-20")),
                readiness("2026-10-22", events = listOf(event), zone = ZoneId.of("Asia/Tokyo")),
                "tzId '$tzId' read in the caller's zone",
            )
            assertEquals(
                notReady(NotReadyReason.RECORDED_IN_ADVANCE, phoneEnd("2026-04-20")),
                readiness("2026-10-22", events = listOf(event), zone = ZoneOffset.UTC),
                "tzId '$tzId' read in UTC is the day before",
            )
        }
    }

    @Test
    fun anOlderQualifyingServiceStillWins() {
        assertEquals(
            ready("2026-10-05", phoneEnd("2026-04-20")),
            readiness("2026-10-22", events = listOf(done("2026-10-05"), advance)),
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Row 5 — the commissioning boundary (#114 review 2).
    // ---------------------------------------------------------------------------------------------

    @Test
    fun aCompletionBeforeTheCommissioningBoundaryNeverCounts() {
        assertEquals(
            notReady(NotReadyReason.NOT_DONE_SINCE_COMMISSIONING, commissioning("2026-10-22")),
            readiness("2026-10-25", schedules = listOf(roleOf(roleSchedule, "2026-10-22")), events = listOf(done("2026-10-21"))),
        )
    }

    @Test
    fun theLaterOfEndAndCommissioningWins() {
        assertEquals(
            notReady(NotReadyReason.NOT_DONE_SINCE_COMMISSIONING, commissioning("2026-05-01")),
            readiness("2026-09-20", schedules = listOf(roleOf(roleSchedule, "2026-05-01")), events = listOf(done("2026-04-25"))),
            "the role set after the END",
        )
        assertEquals(
            notReady(NotReadyReason.NOT_DONE_SINCE_SEASON_END, phoneEnd("2026-04-20")),
            readiness("2026-09-20", schedules = listOf(roleOf(roleSchedule, "2026-04-01")), events = listOf(done("2026-04-10"))),
            "the role set before the END",
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Row 6 — details pending (K3, scenario 3; O-15).
    // ---------------------------------------------------------------------------------------------

    @Test
    fun aDetailsPendingCompletionIsNotReadyWithItsReason() {
        assertEquals(
            notReady(NotReadyReason.DETAILS_PENDING, phoneEnd("2026-04-20")),
            readiness("2026-09-20", events = listOf(done("2026-09-18", detailsPending = true))),
        )
    }

    @Test
    fun filledInDetailsMakeItReady() {
        val pending = done("2026-09-18", detailsPending = true)
        assertEquals(
            ready("2026-09-18", phoneEnd("2026-04-20")),
            readiness("2026-09-20", events = listOf(pending.copy(detailsPending = false))),
        )
    }

    @Test
    fun anOlderQualifyingPlusANewerPendingIsReadyFromTheOlder() {
        assertEquals(
            ready("2026-09-10", phoneEnd("2026-04-20")),
            readiness("2026-09-20", events = listOf(done("2026-09-10"), done("2026-09-18", detailsPending = true))),
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Row 7 — re-derived from today's season end, never cached at role time (AC114-3).
    // ---------------------------------------------------------------------------------------------

    private val row7Role = roleOf(roleSchedule, "2026-05-01")

    @Test
    fun aNewEndAfterTheServiceMakesItNotReady() {
        val history = scenario1Activations + start("2026-10-01")
        val events = listOf(done("2026-09-18"))
        assertEquals(
            ready("2026-09-18", commissioning("2026-05-01")),
            readiness("2026-10-10", schedules = listOf(row7Role), events = events, activations = history),
        )
        assertEquals(
            notReady(NotReadyReason.NOT_DONE_SINCE_SEASON_END, phoneEnd("2027-04-15")),
            readiness("2027-04-16", schedules = listOf(row7Role), events = events, activations = history + end("2027-04-15")),
        )
    }

    @Test
    fun aBackdatedEndBeforeTheServiceKeepsItReady() {
        val backdated = activationOf("end-backdated", "heater", SeasonAction.END, "2026-09-01", createdAt = dayMillis("2026-10-05"))
        assertEquals(
            ready("2026-09-18", phoneEnd("2026-09-01")),
            readiness("2026-10-05", schedules = listOf(row7Role), events = listOf(done("2026-09-18")), activations = scenario1Activations + backdated),
        )
    }

    @Test
    fun aBackdatedEndAfterTheServiceRevokesIt() {
        val backdated = activationOf("end-backdated", "heater", SeasonAction.END, "2026-09-20", createdAt = dayMillis("2026-10-05"))
        assertEquals(
            notReady(NotReadyReason.NOT_DONE_SINCE_SEASON_END, phoneEnd("2026-09-20")),
            readiness("2026-10-05", schedules = listOf(row7Role), events = listOf(done("2026-09-18")), activations = scenario1Activations + backdated),
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Row 8 — only this schedule's completions of this asset, never inferred.
    // ---------------------------------------------------------------------------------------------

    private val otherSchedule = scheduleOf(id = "s-other", assetId = "heater", title = "Filter check")

    @Test
    fun deletingOrRepointingTheCompletionReDerives() {
        val service = done("2026-09-18")
        val schedules = listOf(roleSchedule, otherSchedule)
        assertEquals(ready("2026-09-18", phoneEnd("2026-04-20")), readiness("2026-09-20", schedules = schedules, events = listOf(service)))
        assertEquals(
            notReady(NotReadyReason.NOT_DONE_SINCE_SEASON_END, phoneEnd("2026-04-20")),
            readiness("2026-09-20", schedules = schedules, events = emptyList()),
            "deleted",
        )
        assertEquals(
            notReady(NotReadyReason.NOT_DONE_SINCE_SEASON_END, phoneEnd("2026-04-20")),
            readiness("2026-09-20", schedules = schedules, events = listOf(service.copy(scheduleId = ScheduleId("s-other")))),
            "re-pointed to another schedule of the asset",
        )
    }

    @Test
    fun anotherSchedulesCompletionNeverCounts() {
        assertEquals(
            notReady(NotReadyReason.NOT_DONE_SINCE_SEASON_END, phoneEnd("2026-04-20")),
            readiness("2026-09-20", schedules = listOf(roleSchedule, otherSchedule), events = listOf(done("2026-09-18", scheduleId = "s-other"))),
        )
    }

    @Test
    fun aChildsOrParentsCompletionNeverCounts() {
        val plant = assetOf("plant", "Example Plant Room", SeasonMode.MANUAL)
        val middle = heater.copy(parentAssetId = AssetId("plant"))
        val igniter = assetOf("igniter", "Example Igniter", SeasonMode.MANUAL).copy(parentAssetId = AssetId("heater"))
        val assets = listOf(plant, middle, igniter)
        val activations = scenario1Activations + end("2026-04-20", assetId = "plant") + end("2026-04-20", assetId = "igniter")
        val others = listOf(done("2026-09-18", assetId = "plant"), done("2026-09-18", assetId = "igniter"))
        assertEquals(
            notReady(NotReadyReason.NOT_DONE_SINCE_SEASON_END, phoneEnd("2026-04-20")),
            readiness("2026-09-20", asset = middle, assets = assets, events = others, activations = activations),
        )
        assertEquals(
            ready("2026-09-17", phoneEnd("2026-04-20")),
            readiness("2026-09-20", asset = middle, assets = assets, events = others + done("2026-09-17"), activations = activations),
            "its own service counts",
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Row 9 — the consequences of inv. 90 (C8).
    // ---------------------------------------------------------------------------------------------

    @Test
    fun aCalendarToManualSwitchRowIsABoundary() {
        val calendar = assetOf("heater", "Example Heater", SeasonMode.CALENDAR, seasonStart = "11-01", seasonEnd = "03-31")
        val events = listOf(done("2026-09-18"))
        assertEquals(
            ready("2026-09-18", ReadinessBoundary(on("2026-04-01"), BoundarySource.CALENDAR_WINDOW)),
            readiness("2026-10-10", asset = calendar, events = events, activations = emptyList()),
            "before the switch",
        )
        // Switched to MANUAL out of season on 10-10: SetSeasonMode writes an END dated that day, and it is a boundary.
        val switched = calendar.copy(seasonMode = SeasonMode.MANUAL, seasonStartMmdd = null, seasonEndMmdd = null)
        assertEquals(
            notReady(NotReadyReason.NOT_DONE_SINCE_SEASON_END, phoneEnd("2026-10-10")),
            readiness("2026-10-10", asset = switched, events = events, activations = listOf(end("2026-10-10"))),
        )
    }

    @Test
    fun aManualToCalendarSwitchReadsTheWindow() {
        val calendar = assetOf("heater", "Example Heater", SeasonMode.CALENDAR, seasonStart = "11-01", seasonEnd = "03-31")
        // The MANUAL history stays as unread rows: its END of 10-01 would revoke the service if it were read.
        assertEquals(
            ready("2026-09-18", ReadinessBoundary(on("2026-04-01"), BoundarySource.CALENDAR_WINDOW)),
            readiness(
                "2026-10-10",
                asset = calendar,
                events = listOf(done("2026-09-18")),
                activations = listOf(start("2025-10-01"), end("2026-10-01")),
            ),
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Row 10 — C9's reason order.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun noRoleIsNoRole() {
        val events = listOf(done("2026-09-18"))
        assertEquals(
            notReady(NotReadyReason.NO_ROLE, null, null),
            readiness("2026-09-20", schedules = listOf(scheduleOf(id = "s-role", assetId = "heater")), events = events),
        )
        assertEquals(
            notReady(NotReadyReason.NO_ROLE, null, null),
            readiness("2026-09-20", schedules = listOf(roleOf(scheduleOf(id = "s-role", assetId = "boiler"))), events = events),
            "a role on another asset",
        )
        assertEquals(
            notReady(NotReadyReason.NO_ROLE, null, null),
            readiness("2026-09-20", schedules = listOf(roleSchedule.copy(roleSinceOn = null)), events = events),
            "a row that breaks R1 reads NO_ROLE, defensively",
        )
    }

    @Test
    fun anArchivedRoleScheduleIsScheduleArchived() {
        val archived = roleSchedule.copy(status = ScheduleStatus.ARCHIVED)
        val events = listOf(done("2026-09-18"))
        assertEquals(
            notReady(NotReadyReason.SCHEDULE_ARCHIVED, null),
            readiness("2026-09-20", schedules = listOf(archived), events = events),
        )
        val live = roleOf(scheduleOf(id = "s-live", assetId = "heater"))
        assertEquals(
            ready("2026-09-17", phoneEnd("2026-04-20"), scheduleId = "s-live"),
            readiness("2026-09-20", schedules = listOf(archived, live), events = events + done("2026-09-17", scheduleId = "s-live")),
            "a live role beside an archived one covers",
        )
    }

    @Test
    fun aPausedRoleScheduleIsLive() {
        assertEquals(
            ready("2026-09-18", phoneEnd("2026-04-20")),
            readiness("2026-09-20", schedules = listOf(roleSchedule.copy(status = ScheduleStatus.PAUSED)), events = listOf(done("2026-09-18"))),
        )
    }

    @Test
    fun twoRolesAreTwoRoles() {
        val second = roleOf(scheduleOf(id = "s-second", assetId = "heater"))
        val events = listOf(done("2026-09-18"))
        assertEquals(
            notReady(NotReadyReason.TWO_ROLES, null, null),
            readiness("2026-09-20", schedules = listOf(roleSchedule, second), events = events),
        )
        val group = groupOf(id = "g1", members = listOf(Triple("heater", "2026-01-01", null)))
        val groupRole = roleOf(scheduleOf(id = "s-group", assetId = null, groupId = "g1"))
        assertEquals(
            notReady(NotReadyReason.TWO_ROLES, null, null),
            readiness("2026-09-20", schedules = listOf(roleSchedule, groupRole), groups = listOf(group), events = events),
            "an asset role and a group role",
        )
    }

    @Test
    fun aLeafChildOrYearRoundIsRoleNotAllowed() {
        val plant = assetOf("plant", "Example Plant Room", SeasonMode.MANUAL)
        val leaf = heater.copy(parentAssetId = AssetId("plant"))
        val events = listOf(done("2026-09-18"))
        assertEquals(
            notReady(NotReadyReason.ROLE_NOT_ALLOWED, null),
            readiness("2026-09-20", asset = leaf, assets = listOf(plant, leaf), events = events),
            "a leaf child",
        )
        val yearRound = heater.copy(seasonMode = SeasonMode.YEAR_ROUND)
        assertEquals(
            notReady(NotReadyReason.ROLE_NOT_ALLOWED, null),
            readiness("2026-09-20", asset = yearRound, events = events),
            "a YEAR_ROUND asset",
        )
    }

    @Test
    fun aParentWithAnArchivedChildIsAllowed() {
        val plant = assetOf("plant", "Example Plant Room", SeasonMode.MANUAL)
        val middle = heater.copy(parentAssetId = AssetId("plant"))
        val archivedChild = assetOf("igniter", "Example Igniter", SeasonMode.MANUAL)
            .copy(parentAssetId = AssetId("heater"), status = AssetStatus.ARCHIVED, retiredOn = "2026-02-01")
        assertEquals(
            ready("2026-09-18", phoneEnd("2026-04-20")),
            readiness("2026-09-20", asset = middle, assets = listOf(plant, middle, archivedChild), events = listOf(done("2026-09-18"))),
        )
    }

    @Test
    fun aRoleOnAnArchivedGroupIsScheduleArchived() {
        val archivedGroup = groupOf(id = "g1", archivedAt = dayMillis("2026-08-01"), members = listOf(Triple("heater", "2026-01-01", null)))
        val groupRole = roleOf(scheduleOf(id = "s-group", assetId = null, groupId = "g1"))
        assertEquals(
            notReady(NotReadyReason.SCHEDULE_ARCHIVED, null, scheduleId = "s-group"),
            readiness(
                "2026-09-20",
                schedules = listOf(groupRole),
                groups = listOf(archivedGroup),
                events = listOf(done("2026-09-18", scheduleId = "s-group")),
            ),
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Row 11 — a group role runs per member, on that member's own events (C1).
    // ---------------------------------------------------------------------------------------------

    private val boiler: Asset = assetOf("boiler", "Example Boiler", SeasonMode.MANUAL)
    private val preSeason = groupOf(
        id = "g1",
        name = "Example pre-season check",
        members = listOf(Triple("heater", "2026-01-01", null), Triple("boiler", "2026-01-01", null)),
    )
    private val groupRole = roleOf(scheduleOf(id = "s-group", assetId = null, groupId = "g1", title = "Example pre-season check"))
    private val bothHistories = scenario1Activations + start("2025-10-01", assetId = "boiler") + end("2026-04-20", assetId = "boiler")

    private fun member(asset: Asset, groups: List<MaintenanceGroup> = listOf(preSeason), events: List<AssetEvent>): Readiness =
        readiness(
            "2026-09-20",
            asset = asset,
            assets = listOf(heater, boiler),
            schedules = listOf(groupRole),
            groups = groups,
            events = events,
            activations = bothHistories,
        )

    @Test
    fun aGroupRoleRunsPerMemberOnItsOwnEvents() {
        val events = listOf(done("2026-09-18", scheduleId = "s-group", assetId = "boiler"))
        assertEquals(
            notReady(NotReadyReason.NOT_DONE_SINCE_SEASON_END, phoneEnd("2026-04-20"), scheduleId = "s-group"),
            member(heater, events = events),
            "the boiler's service never makes the heater ready",
        )
        assertEquals(ready("2026-09-18", phoneEnd("2026-04-20"), scheduleId = "s-group"), member(boiler, events = events))
    }

    @Test
    fun aRemovedMemberIsNoRole() {
        val removed = preSeason.copy(members = preSeason.members.map { if (it.assetId == heater.id) it.copy(removedAt = dayMillis("2026-06-01")) else it })
        val events = listOf(done("2026-09-18", scheduleId = "s-group", assetId = "heater"))
        assertEquals(notReady(NotReadyReason.NO_ROLE, null, null), member(heater, groups = listOf(removed), events = events))
        val readded = removed.copy(members = removed.members + preSeason.members.first().copy(id = "g1-m3", addedAt = dayMillis("2026-07-01")))
        assertEquals(
            ready("2026-09-18", phoneEnd("2026-04-20"), scheduleId = "s-group"),
            member(heater, groups = listOf(readded), events = events),
            "an open window again covers it",
        )
    }

    @Test
    fun conditionHealthPhaseAndRemindersChangeNothing() {
        val events = listOf(done("2026-09-18"))
        val baseline = readiness("2026-10-10", events = events)
        assertEquals(ready("2026-09-18", phoneEnd("2026-04-20")), baseline)
        val variants = listOf(
            "reminders off" to readiness("2026-10-10", schedules = listOf(roleSchedule.copy(remindersEnabled = false)), events = events),
            "lead and postponement" to readiness(
                "2026-10-10",
                schedules = listOf(roleSchedule.copy(leadDays = 60, postponedDueOn = "2027-01-01")),
                events = events,
            ),
            "a seasonal policy" to readiness(
                "2026-10-10",
                schedules = listOf(roleSchedule.copy(servicePolicy = ServicePolicy.IN_SERVICE_AT_START, policyOffsetDays = 0)),
                events = events,
            ),
            "the health settings" to readiness(
                "2026-10-10",
                asset = heater.copy(healthAggregation = HealthAggregation.TRACK_ONE, healthPrimarySubjectId = HealthSubjectId("hs-1")),
                events = events,
            ),
            "in season today" to readiness("2026-10-10", events = events, activations = scenario1Activations + start("2026-10-01")),
        )
        for ((label, answer) in variants) assertEquals(baseline, answer, label)
    }
}
