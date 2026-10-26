package com.loosecannon.servicetag.core.season

import com.loosecannon.servicetag.core.hapublish.PublishBinding
import com.loosecannon.servicetag.core.hapublish.PublishDelivery
import com.loosecannon.servicetag.core.hapublish.PublishKind
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleRole
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.testing.activationOf
import com.loosecannon.servicetag.core.testing.completionOf
import com.loosecannon.servicetag.core.testing.dayMillis
import com.loosecannon.servicetag.core.testing.haConnectionOf
import com.loosecannon.servicetag.core.testing.seasonSyncBindingOf
import com.loosecannon.servicetag.core.usecase.SeasonStartHarness
import com.loosecannon.servicetag.core.usecase.SeasonStartRefusal
import com.loosecannon.servicetag.core.usecase.SeasonStartRefused
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * #124 (C41; row 141) — the one reader of the opening-service day answers C9's own `serviceOn`, or null, over the
 * inputs C9 reads: an asset role, a group role read per member (C1), a QUICK role the command refused without the
 * election (O-2), an imported service (O-20), a future-dated and an advance-written one (O-16), and a Home Assistant
 * boundary (C9a). Each case is also C9 itself, asked over the same stores. Every name is fictional; today is
 * 2026-10-26 and the zone UTC.
 */
class SeasonReadinessReaderTest {
    private val h = SeasonStartHarness()

    private suspend fun read(assetId: String): LocalDate? =
        h.raw.uow.read { h.openingServiceReader.openingServiceOn(AssetId(assetId)) }

    /** C9 over everything stored, with no Home Assistant boundary: what the reader must agree with. */
    private suspend fun c9(assetId: String): LocalDate? = (h.readiness(assetId) as? Readiness.Ready)?.serviceOn

    /** A completion of [scheduleId] by [assetId] dated [day], written (its `updatedAt`) on [writtenOn]. */
    private fun done(
        day: String,
        scheduleId: String = "s-role",
        assetId: String = "heater",
        imported: Boolean = false,
        writtenOn: String = day,
    ): AssetEvent = completionOf(
        id = "e-$assetId-$scheduleId-$day",
        occurredOn = day,
        occurrenceOn = day,
        assetId = assetId,
        scheduleId = scheduleId,
        createdAt = dayMillis(writtenOn),
    ).copy(imported = imported).also { h.raw.events.rows[it.id.value] = it }

    @Test fun anAssetRoleAnswersItsLatestQualifyingService() = runTest {
        h.asset("heater")
        h.asset("boiler")
        h.schedule("s-role", role = ScheduleRole.SEASON_START, since = "2026-09-01")
        assertNull(read("heater"), "no service yet")

        done("2026-09-20")
        done("2026-10-01")
        assertEquals(LocalDate.parse("2026-10-01"), read("heater"), "the latest (O-3)")
        assertEquals(c9("heater"), read("heater"))
        assertNull(read("boiler"), "no role covers the boiler")
        assertNull(read("nobody"), "an asset that is not there")
    }

    /** The readiness route's read (C29): C9's whole answer, not only its day, and null for an asset that is not there. */
    @Test fun readinessIsC9sWholeAnswer() = runTest {
        h.asset("heater")
        h.schedule("s-role", role = ScheduleRole.SEASON_START, since = "2026-09-01")
        assertEquals(h.readiness("heater"), h.raw.uow.read { h.openingServiceReader.readiness(AssetId("heater")) })

        done("2026-10-01")
        val answer = h.raw.uow.read { h.openingServiceReader.readiness(AssetId("heater")) }
        assertIs<Readiness.Ready>(answer)
        assertEquals(h.readiness("heater"), answer)
        assertNull(h.raw.uow.read { h.openingServiceReader.readiness(AssetId("nobody")) })
    }

    @Test fun aGroupRoleIsReadPerMemberFromItsOwnCompletion() = runTest {
        h.asset("heater")
        h.asset("boiler")
        h.asset("pump")
        h.group(
            "g1", Triple("heater", "2026-01-01", null), Triple("boiler", "2026-01-01", null), Triple("pump", "2026-01-01", null),
        )
        h.schedule("g-role", groupId = "g1", role = ScheduleRole.SEASON_START, since = "2026-09-01")
        done("2026-10-01", scheduleId = "g-role", assetId = "heater")
        done("2026-10-05", scheduleId = "g-role", assetId = "boiler")

        assertEquals(LocalDate.parse("2026-10-01"), read("heater"))
        assertEquals(LocalDate.parse("2026-10-05"), read("boiler"), "the boiler's own, never the heater's")
        assertNull(read("pump"), "a member that has not done it")
        listOf("heater", "boiler", "pump").forEach { assertEquals(c9(it), read(it), it) }
    }

    @Test fun aQuickRoleTheCommandRefusedWithoutTheElectionCountsNothing() = runTest {
        h.asset("heater")
        h.schedule("s-quick", mode = CompletionMode.QUICK)
        done("2026-10-01", scheduleId = "s-quick")

        val refused = assertFailsWith<SeasonStartRefused> {
            h.setScheduleRole.run(ScheduleId("s-quick"), ScheduleRole.SEASON_START, "2026-09-01")
        }
        assertEquals(SeasonStartRefusal.QuickNotElected, refused.reason)
        assertNull(read("heater"), "no role landed (O-2)")

        h.setScheduleRole.run(ScheduleId("s-quick"), ScheduleRole.SEASON_START, "2026-09-01", quickElected = true)
        assertEquals(LocalDate.parse("2026-10-01"), read("heater"), "the owner's election")
        assertEquals(c9("heater"), read("heater"))
    }

    @Test fun anImportedServiceNeverCounts() = runTest {
        h.asset("heater")
        h.schedule("s-role", role = ScheduleRole.SEASON_START, since = "2026-09-01")
        done("2026-10-01", imported = true)
        assertNull(read("heater"), "a Transfer Pack's service (O-20)")
        assertEquals(c9("heater"), read("heater"))

        done("2026-10-03")
        assertEquals(LocalDate.parse("2026-10-03"), read("heater"), "the phone's own")
    }

    @Test fun aFutureDatedOrAdvanceWrittenServiceNeverCounts() = runTest {
        h.asset("heater")
        h.schedule("s-role", role = ScheduleRole.SEASON_START, since = "2026-09-01")
        done("2026-10-30", writtenOn = "2026-10-26")
        assertNull(read("heater"), "dated after today (O-16)")

        h.raw.events.rows.clear()
        done("2026-10-20", writtenOn = "2026-10-19")
        assertNull(read("heater"), "written before its day (O-16, RECORDED_IN_ADVANCE)")
        assertEquals(c9("heater"), read("heater"))

        done("2026-10-10")
        assertEquals(LocalDate.parse("2026-10-10"), read("heater"))
    }

    /**
     * C9a: a `MANUAL` heater following Home Assistant, whose date binding names a season-ended helper read yesterday
     * at the binding's revision. HA's 2026-10-10 is the cutoff, so a service on 2026-10-01 does not count — while C9
     * with no boundary (the phone's END, 2026-09-15) would count it.
     */
    @Test fun aFreshHomeAssistantBoundaryDecidesAManualAsset() = runTest {
        h.asset("heater")
        h.schedule("s-role", role = ScheduleRole.SEASON_START, since = "2026-09-01")
        h.raw.activations.rows["end"] = activationOf(
            "end", "heater", SeasonAction.END, "2026-09-15", createdAt = dayMillis("2026-09-15"),
        )
        done("2026-10-01")
        assertEquals(LocalDate.parse("2026-10-01"), c9("heater"), "the phone's END alone would count it")

        val connection = haConnectionOf()
        h.raw.uow.write {
            h.raw.haConnections.upsert(connection)
            h.raw.seasonSyncBindings.insert(seasonSyncBindingOf("heater", connection.id))
            h.publishBindings.insert(dateBinding(connection.id))
            h.publishDeliveries.upsert(
                PublishDelivery(
                    bindingId = "binding-date", desiredValue = null, desiredRevision = 0, desiredAt = null,
                    boundaryValue = "2026-10-10", boundaryReadAt = h.now - DAY, boundaryBindingRevision = 3,
                ),
            )
        }
        assertNull(read("heater"), "HA's season end is later than the service")

        h.raw.uow.write {
            h.publishDeliveries.upsert(h.publishDeliveries.get("binding-date")!!.copy(boundaryValue = "2026-09-25"))
        }
        assertEquals(LocalDate.parse("2026-10-01"), read("heater"), "a service after HA's season end counts")
        assertIs<Readiness.Ready>(h.readiness("heater"))
    }

    private fun dateBinding(connectionId: String) = PublishBinding(
        id = "binding-date",
        assetId = AssetId("heater"),
        connectionId = connectionId,
        kind = PublishKind.SEASON_SERVICE_DATE,
        entityId = "input_datetime.example_heater_service_done",
        enabled = true,
        revision = 3,
        activatedInstallationId = "installation-a",
        validatedAt = h.now - 2 * DAY,
        validatedRevision = 3,
        boundaryEntityId = "input_datetime.example_heater_season_ended",
        createdAt = h.now - 3 * DAY,
        updatedAt = h.now - 2 * DAY,
    )

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
    }
}
