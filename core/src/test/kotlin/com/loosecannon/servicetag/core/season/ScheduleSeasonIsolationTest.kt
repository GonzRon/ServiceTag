package com.loosecannon.servicetag.core.season

import com.loosecannon.servicetag.core.condition.ConditionHistory
import com.loosecannon.servicetag.core.hapublish.PublishBinding
import com.loosecannon.servicetag.core.hapublish.PublishKind
import com.loosecannon.servicetag.core.hapublish.desiredValueOf
import com.loosecannon.servicetag.core.hapublish.haBoundaryOf
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleRole
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.model.seasonInputs
import com.loosecannon.servicetag.core.schedule.SeasonContext
import com.loosecannon.servicetag.core.seasonsync.HaSwitchState
import com.loosecannon.servicetag.core.seasonsync.SyncCadence
import com.loosecannon.servicetag.core.testing.SeasonFixtures
import com.loosecannon.servicetag.core.testing.completionOf
import com.loosecannon.servicetag.core.usecase.ScheduleSeasonHarness
import com.loosecannon.servicetag.core.usecase.SeasonView
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlinx.coroutines.test.runTest

/**
 * #125 (constraint 3, I-R; row h5) — readiness and publishing stay on the asset's season. A role schedule following
 * its own Home Assistant switch writes START and END rows of its own, and no asset-level answer moves: C8/C9's
 * readiness (cutoff, source, reason), #124's opening-service day, C9a's [haBoundaryOf], the projector's desired value,
 * the asset's season view, and the asset's phase that the Assets list's out-of-season flag and #116's marks read
 * (`SeasonContext.of(asset.seasonInputs(asset rows))`, their one input). Each answer is taken with the schedule's rows
 * absent, with a START and with an END, while the schedule's own due date moves. Every name is fictional.
 */
class ScheduleSeasonIsolationTest {
    private val h = ScheduleSeasonHarness("2026-10-22")

    /** A `MANUAL` boiler whose phone END on 04-20 is its readiness cutoff, and a date binding publishing it. */
    private val dateBinding = PublishBinding(
        id = "pb-boiler", assetId = AssetId("boiler"), connectionId = "conn-1", kind = PublishKind.SEASON_SERVICE_DATE,
        entityId = "input_datetime.example_boiler_service_done", enabled = true, revision = 1,
        activatedInstallationId = "installation-a", validatedAt = null, validatedRevision = null, boundaryEntityId = null,
        createdAt = 0L, updatedAt = 0L,
    )

    private suspend fun estate() {
        h.asset() // the CALENDAR heater, in use 11-01…03-31
        h.raw.assets.rows["boiler"] = SeasonFixtures.assetOf("boiler", "Example Boiler", SeasonMode.MANUAL)
        h.raw.activations.rows["a-1"] = SeasonFixtures.activationOf("a-1", "boiler", SeasonAction.START, "2025-10-01")
        h.raw.activations.rows["a-2"] = SeasonFixtures.activationOf("a-2", "boiler", SeasonAction.END, "2026-04-20")
        for ((id, asset) in listOf("s-heater" to "heater", "s-boiler" to "boiler")) {
            h.schedule(id, assetId = asset, policy = ServicePolicy.IN_SERVICE_AT_START, offset = 0, role = ScheduleRole.SEASON_START)
        }
        h.raw.events.rows["e-1"] = completionOf("e-1", occurredOn = "2026-09-18", occurrenceOn = null, assetId = "boiler", scheduleId = "s-boiler")
        h.raw.events.rows["e-2"] = completionOf("e-2", occurredOn = "2026-10-01", occurrenceOn = null, assetId = "heater", scheduleId = "s-heater")
        h.connect()
        h.link.run(ScheduleId("s-heater"), "input_boolean.example_heater_winterize_season")
        h.link.run(ScheduleId("s-boiler"), "input_boolean.example_boiler_winterize_season")
    }

    /** Every asset-level answer, for both assets, as each reader takes it today. */
    private suspend fun answers(): Map<String, Any?> = buildMap {
        for (id in listOf("heater", "boiler")) {
            val asset = h.raw.assets.rows.getValue(id)
            val own = h.raw.activations.forAsset(asset.id)
            val boundary = haBoundaryOf(
                cache = null,
                binding = dateBinding.takeIf { it.assetId == asset.id },
                assetMode = asset.seasonMode,
                inboundEnabled = h.raw.seasonSyncBindings.get(asset.id)?.enabled == true,
                cadence = SyncCadence.DAILY,
                newestActivationCreatedAt = own.maxOfOrNull { it.createdAt },
                now = h.now,
            )
            val readiness = seasonReadiness(
                asset, h.raw.assets.all(), h.raw.schedules.all(), h.raw.groups.all(), h.raw.events.forAsset(asset.id), own,
                h.day, ZoneOffset.UTC, boundary,
            )
            put("$id C9", readiness)
            put("$id haBoundary", boundary)
            put("$id opening service", h.uow.read { h.openingServiceReader.openingServiceOn(asset.id) })
            put("$id desired", desiredValueOf(dateBinding, asset, true, ConditionHistory.of(emptyList()), readiness))
            put("$id season view", SeasonView.of(asset, own, h.day))
            put("$id phase", SeasonContext.of(asset.seasonInputs(own)).phaseAt(h.day))
        }
    }

    private suspend fun dueDates(): List<String?> = listOf("s-heater", "s-boiler").map { h.state(it).actionableDueOn }

    @Test
    fun aSchedulesOwnStartAndEndMoveNoAssetLevelAnswer() = runTest {
        estate()
        val absent = answers()
        val waiting = dueDates()
        assertEquals(
            Readiness.Ready(LocalDate.parse("2026-09-18"), ScheduleId("s-boiler"),
                ReadinessBoundary(LocalDate.parse("2026-04-20"), BoundarySource.PHONE_SEASON_END)),
            absent["boiler C9"],
            "the boiler's cutoff is its own phone END",
        )

        h.answer("s-heater", HaSwitchState.ON)
        h.answer("s-boiler", HaSwitchState.ON)
        val started = dueDates()
        assertNotEquals(waiting, started, "the schedules' own due dates move")
        assertEquals(absent, answers(), "a schedule's START moves no asset-level answer")

        h.now += 60_000L
        h.answer("s-heater", HaSwitchState.OFF)
        h.answer("s-boiler", HaSwitchState.OFF)
        assertEquals(mapOf(SeasonAction.START to 2, SeasonAction.END to 2), h.rows.rows.values.groupingBy { it.action }.eachCount())
        assertEquals(absent, answers(), "nor its END")
        assertEquals(2, h.raw.activations.rows.size, "no asset row was written")
    }
}
