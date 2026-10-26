package com.loosecannon.servicetag.core.hapublish

import com.loosecannon.servicetag.core.condition.ConditionHistory
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetCondition
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.season.BoundarySource
import com.loosecannon.servicetag.core.season.NotReadyReason
import com.loosecannon.servicetag.core.season.Readiness
import com.loosecannon.servicetag.core.season.ReadinessBoundary
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * #113/#114 (C15; rows 25–29) — the two projections: the equipment status is the current condition alone, the
 * service date is readiness's day or `1970-01-01`, and the kinds part ways when the asset leaves. Every value is
 * fictional.
 */
class ProjectionsTest {

    private val heaterId = AssetId("asset-example-heater")

    private fun row(
        id: String,
        condition: OperationalCondition,
        on: String,
        createdAt: Long,
        reason: String = "",
    ) = AssetCondition(id, heaterId, condition, on, null, "UTC", reason, null, createdAt)

    private fun history(vararg rows: AssetCondition) = ConditionHistory.of(rows.toList())

    private fun asset(
        mode: SeasonMode = SeasonMode.MANUAL,
        start: String? = null,
        end: String? = null,
    ) = Asset(
        id = heaterId,
        name = "Example Heater",
        createdAt = 1L,
        updatedAt = 1L,
        seasonMode = mode,
        seasonStartMmdd = start,
        seasonEndMmdd = end,
    )

    private fun binding(kind: PublishKind) = PublishBinding(
        id = "binding-$kind",
        assetId = heaterId,
        connectionId = "connection-1",
        kind = kind,
        entityId = when (kind) {
            PublishKind.EQUIPMENT_STATUS -> "input_select.example_heater_status"
            PublishKind.SEASON_SERVICE_DATE -> "input_datetime.example_heater_service_done"
        },
        enabled = true,
        revision = 1,
        activatedInstallationId = "installation-a",
        validatedAt = 1L,
        validatedRevision = 1,
        boundaryEntityId = null,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private val status = binding(PublishKind.EQUIPMENT_STATUS)
    private val date = binding(PublishKind.SEASON_SERVICE_DATE)

    private val boundary = ReadinessBoundary(LocalDate.parse("2026-05-10"), BoundarySource.PHONE_SEASON_END)
    private val ready = Readiness.Ready(LocalDate.parse("2026-09-18"), ScheduleId("schedule-season-start"), boundary)
    private fun notReady(reason: NotReadyReason) = Readiness.NotReady(reason, ScheduleId("schedule-season-start"), boundary)
    private val everyReadiness: List<Readiness> = listOf(ready) + NotReadyReason.entries.map(::notReady)

    // Row 25 — exactly three values, and no row is no value (AC113-2).

    @Test
    fun operationalDegradedDownMapExactly() {
        assertEquals("UP", equipmentStatusOf(history(row("c1", OperationalCondition.OPERATIONAL, "2026-10-01", 10))))
        assertEquals("DEGRADED", equipmentStatusOf(history(row("c1", OperationalCondition.DEGRADED, "2026-10-01", 10))))
        assertEquals("DOWN", equipmentStatusOf(history(row("c1", OperationalCondition.DOWN, "2026-10-01", 10))))
        assertEquals(setOf("UP", "DEGRADED", "DOWN"), STATUS_OPTIONS)
    }

    @Test
    fun noConditionIsNullNeverUp() {
        assertNull(equipmentStatusOf(history()))
        assertEquals(
            Desired.Nothing(PublishState.WAITING_FOR_SOURCE),
            desiredValueOf(status, asset(), maintainedHere = true, history = history(), readiness = ready),
        )
    }

    // Row 26 — the date kind: the qualifying day, or the invalid date for every not-ready answer (AC114-5).

    @Test
    fun readyIsTheDateNotReadyIs1970() {
        assertEquals("2026-09-18", seasonDateOf(ready))
        for (reason in NotReadyReason.entries) {
            assertEquals("1970-01-01", seasonDateOf(notReady(reason)), "$reason")
            assertEquals(
                Desired.Send("1970-01-01"),
                desiredValueOf(date, asset(), maintainedHere = true, history = history(), readiness = notReady(reason)),
                "$reason",
            )
        }
        assertEquals(
            Desired.Send("2026-09-18"),
            desiredValueOf(date, asset(), maintainedHere = true, history = history(), readiness = ready),
        )
    }

    // Row 27 — AC113-3 by construction: only the current row decides.

    @Test
    fun aNewReasonWithTheSameEnumIsNoChange() {
        val first = row("c1", OperationalCondition.DOWN, "2026-10-01", 10, reason = "pump")
        val again = row("c2", OperationalCondition.DOWN, "2026-10-02", 20, reason = "pump and fan")
        assertEquals(equipmentStatusOf(history(first)), equipmentStatusOf(history(first, again)))
        assertEquals("DOWN", equipmentStatusOf(history(first, again)))
    }

    @Test
    fun aBackdatedNonCurrentRowIsNoChange() {
        val down = row("c1", OperationalCondition.DOWN, "2026-10-20", 100)
        // Recorded later (a newer createdAt) but dated earlier: it sorts before the current row.
        val backdated = row("c2", OperationalCondition.OPERATIONAL, "2026-10-01", 200)
        assertEquals("DOWN", equipmentStatusOf(history(down)))
        assertEquals("DOWN", equipmentStatusOf(history(down, backdated)))
        assertEquals("DOWN", equipmentStatusOf(history(backdated, down)))
    }

    @Test
    fun aDuplicateRowIdIsNoChange() {
        val degraded = row("c1", OperationalCondition.DEGRADED, "2026-10-01", 10)
        val up = row("c0", OperationalCondition.OPERATIONAL, "2026-09-01", 5)
        assertEquals(equipmentStatusOf(history(up, degraded)), equipmentStatusOf(history(up, degraded, degraded)))
        assertEquals("DEGRADED", equipmentStatusOf(history(degraded, up, degraded)))
    }

    // Row 28 — G8: maintenance health, due dates, the season and readiness never reach the status.

    @Test
    fun healthBandsDueDatesSeasonAndReadinessNeverReachStatus() {
        // The function's whole input is the condition history.
        val method = Class.forName("com.loosecannon.servicetag.core.hapublish.ProjectionsKt")
            .declaredMethods.single { it.name == "equipmentStatusOf" }
        assertEquals(listOf<Class<*>>(ConditionHistory::class.java), method.parameterTypes.toList())

        // desiredValueOf carries no health, band, due date, schedule or reminder input at all.
        val desired = Class.forName("com.loosecannon.servicetag.core.hapublish.ProjectionsKt")
            .declaredMethods.single { it.name == "desiredValueOf" }
        assertEquals(
            listOf(
                PublishBinding::class.java, Asset::class.java, Boolean::class.javaPrimitiveType,
                ConditionHistory::class.java, Readiness::class.java,
            ),
            desired.parameterTypes.toList(),
        )

        // Vary the season and readiness: the status is the condition, every time.
        val seasons = listOf(
            asset(SeasonMode.YEAR_ROUND),
            asset(SeasonMode.MANUAL),
            asset(SeasonMode.CALENDAR, start = "11-01", end = "03-31"), // a winter window
            asset(SeasonMode.CALENDAR, start = "06-01", end = "08-31"), // a summer window
        )
        val operational = history(row("c1", OperationalCondition.OPERATIONAL, "2026-07-15", 10))
        val degraded = history(row("c1", OperationalCondition.DEGRADED, "2026-07-15", 10))
        for (season in seasons) {
            for (readiness in everyReadiness) {
                assertEquals(
                    Desired.Send("UP"),
                    desiredValueOf(status, season, maintainedHere = true, history = operational, readiness = readiness),
                    "${season.seasonMode} ${season.seasonStartMmdd} $readiness",
                )
                assertEquals(
                    Desired.Send("DEGRADED"),
                    desiredValueOf(status, season, maintainedHere = true, history = degraded, readiness = readiness),
                    "${season.seasonMode} ${season.seasonStartMmdd} $readiness",
                )
            }
        }
    }

    /** Row 102's clause, held here: an out-of-season, not-ready asset whose condition is OPERATIONAL is never DOWN. */
    @Test
    fun anOutOfSeasonAssetIsNeverDown() {
        val winterHotTub = asset(SeasonMode.CALENDAR, start = "11-01", end = "03-31")
        val operational = history(row("c1", OperationalCondition.OPERATIONAL, "2027-04-05", 10))
        val outOfSeason = notReady(NotReadyReason.NOT_DONE_SINCE_SEASON_END)
        val sent = desiredValueOf(status, winterHotTub, maintainedHere = true, history = operational, readiness = outOfSeason)
        assertEquals(Desired.Send("UP"), sent)
        assertNotEquals<Desired>(Desired.Send("DOWN"), sent)
    }

    // Row 29 — K12: the status kind stops and keeps the remote; the date kind invalidates.

    @Test
    fun notMaintainedHereStopsStatusAndInvalidatesDate() {
        val down = history(row("c1", OperationalCondition.DOWN, "2026-10-01", 10))
        assertEquals(
            Desired.Nothing(PublishState.NOT_MAINTAINED_HERE),
            desiredValueOf(status, asset(), maintainedHere = false, history = down, readiness = ready),
        )
        assertEquals(
            Desired.Send(INVALID_SERVICE_DATE),
            desiredValueOf(date, asset(), maintainedHere = false, history = down, readiness = ready),
        )
    }

    @Test
    fun aMissingAssetRetiresStatusAndInvalidatesDate() {
        val up = history(row("c1", OperationalCondition.OPERATIONAL, "2026-10-01", 10))
        assertEquals(
            Desired.Nothing(PublishState.NOT_MAINTAINED_HERE),
            desiredValueOf(status, null, maintainedHere = true, history = up, readiness = ready),
        )
        assertEquals(
            Desired.Send(INVALID_SERVICE_DATE),
            desiredValueOf(date, null, maintainedHere = true, history = up, readiness = ready),
        )
    }
}
