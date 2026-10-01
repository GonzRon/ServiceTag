package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetCondition
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.OperationalCondition.DEGRADED
import com.loosecannon.servicetag.core.model.OperationalCondition.DOWN
import com.loosecannon.servicetag.core.model.OperationalCondition.OPERATIONAL
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.ports.ConditionRepository
import com.loosecannon.servicetag.core.testing.RiggedFailure
import com.loosecannon.servicetag.core.testing.InMemorySupplyItemRepository
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * #82 C1, Workflow A's combined write: an INCIDENT event and the DOWN or DEGRADED row that names it,
 * in one transaction or not at all. The pump is fictional: "Pump will not start", then the detail line.
 */
class RecordConditionWithIncidentTest {

    private val h = ConditionHealthHarness(today = "2026-09-24")

    private fun incident(
        title: String = "Pump will not start",
        on: String = "2026-09-24",
        notes: String = "Breaker trips on start",
        kind: EventKind = EventKind.INCIDENT,
        assetId: String = "a1",
        scheduleId: String? = null,
    ) = EventCommand(
        assetId = AssetId(assetId), profileId = null, kind = kind, title = title, occurredOn = on,
        occurredTime = null, tzId = "UTC", notes = notes, values = emptyMap(), consumables = emptyList(),
        scheduleId = scheduleId?.let(::ScheduleId), occurrenceOn = scheduleId?.let { on },
    )

    private fun held(
        condition: OperationalCondition = DOWN,
        on: String = "2026-09-24",
        time: String? = null,
        reason: String = "Pump will not start\nBreaker trips on start",
        eventId: String? = null,
    ) = ConditionCommand(condition, on, time, "UTC", reason, eventId?.let(::EventId))

    /**
     * Hazard: the two facts land unlinked, or the row is not the one the flow held. One INCIDENT
     * event; one row carrying the supplied id, the command's date, time and reason, and the event's
     * id; the recompute ran (the schedule has its state); nothing else is written.
     */
    @Test
    fun writesOneIncidentAndOneRowLinkedToIt() = runBlocking<Unit> {
        h.asset("a1")
        h.schedule("s1")
        h.asset("a2", name = "Generator")

        val down = h.recordConditionWithIncident.run(AssetId("a1"), "c-held", held(), incident())

        val event = h.events.rows.values.single()
        assertEquals(EventKind.INCIDENT, event.kind)
        assertEquals("Pump will not start" to "Breaker trips on start", event.title to event.notes)
        assertEquals(null to null, event.scheduleId to event.profileId)
        assertEquals(event, down.incident)
        val row = AssetCondition(
            id = "c-held", assetId = AssetId("a1"), condition = DOWN, occurredOn = "2026-09-24", occurredTime = null,
            tzId = "UTC", reason = "Pump will not start\nBreaker trips on start", eventId = event.id, createdAt = h.now,
        )
        assertEquals(row, down.condition)
        assertEquals(listOf(row), h.rows())
        assertTrue("s1" in h.states.rows, "the event's recompute ran")

        val degraded = h.recordConditionWithIncident.run(
            AssetId("a2"),
            "c-held-2",
            held(DEGRADED, on = "2026-09-20", time = "07:30", reason = "Reduced output"),
            incident(title = "Only producing reduced output", on = "2026-09-20", notes = "", assetId = "a2"),
        )
        assertEquals(
            listOf(Triple("c-held-2", DEGRADED, "2026-09-20" to "07:30")),
            h.rows("a2").map { Triple(it.id, it.condition, it.occurredOn to it.occurredTime) },
        )
        assertEquals("Reduced output", degraded.condition.reason)
        assertEquals(degraded.incident?.id, degraded.condition.eventId)
        assertEquals(2, h.events.rows.size)
        assertEquals(0, h.assets.upserts, "no asset column")
        assertTrue(h.activations.rows.isEmpty() && h.healthSubjects.rows.isEmpty() && h.closures.rows.isEmpty())
    }

    /** [RecordConditionWithIncident] over a condition store whose every insert throws. */
    private fun withThrowingInsert(): RecordConditionWithIncident {
        val throwing = object : ConditionRepository by h.conditions {
            override suspend fun insert(row: AssetCondition) = throw RiggedFailure("rigged condition insert")
        }
        val record = RecordCondition(h.assets, h.events, throwing, h.uow, h.ids, h.clock, h.todayPort)
        return RecordConditionWithIncident(
            h.events, h.definitions, h.profiles, h.assets, InMemorySupplyItemRepository(), h.uow, h.ids, h.clock, h.recompute, throwing, h.todayPort,
            record,
        )
    }

    /**
     * Hazard: the Incident survives a failed condition write. The row's insert throws after the event
     * and the recompute have been written; the throw leaves the outer write, and neither fact — nor
     * the derived state — is left behind.
     */
    @Test
    fun aThrowingConditionInsertLeavesNeitherFact() = runBlocking<Unit> {
        h.asset("a1")
        h.schedule("s1")

        val thrown = assertFailsWith<RiggedFailure> {
            withThrowingInsert().run(AssetId("a1"), "c-held", held(), incident())
        }

        assertEquals("rigged condition insert", thrown.message)
        assertTrue(h.events.rows.isEmpty(), "no Incident without its row")
        assertTrue(h.rows().isEmpty())
        assertTrue(h.states.rows.isEmpty(), "the recompute is rolled back with them")
        assertEquals(0, h.uow.commits, "nothing committed at any level")
    }

    /**
     * Hazard: a failure between the two halves leaves the event. The asset holds one schedule and the
     * recompute's state write throws, after `events.upsert`: nothing is left.
     */
    @Test
    fun aFailureAfterTheEventWriteLeavesNeitherFact() = runBlocking<Unit> {
        h.asset("a1")
        h.schedule("s1")
        h.states.failOnUpsert = 1

        assertFailsWith<RiggedFailure> {
            h.recordConditionWithIncident.run(AssetId("a1"), "c-held", held(), incident())
        }

        assertTrue(h.events.rows.isEmpty(), "the event write is undone")
        assertTrue(h.rows().isEmpty())
        assertEquals(0, h.uow.commits)
    }

    /**
     * Hazard (K3): the combined Save reports one half and hides the other, or writes before it knows.
     * Both halves' problems are collected into one refusal, and nothing is written.
     */
    @Test
    fun aBlankTitleAndATooLongReasonAreReportedTogetherAndWriteNothing() = runBlocking<Unit> {
        h.asset("a1")

        val both = assertFailsWith<IncidentConditionRefused> {
            h.recordConditionWithIncident.run(AssetId("a1"), "c-held", held(reason = "x".repeat(501)), incident(title = "  "))
        }
        assertEquals(listOf<FieldProblem>(FieldProblem.TitleRequired), both.eventProblems)
        assertEquals(listOf<ConditionProblem>(ConditionProblem.ReasonTooLong(500)), both.conditionProblems)
        assertEquals(false, both.incidentAfterToday)

        val again = assertFailsWith<IncidentConditionRefused> {
            h.recordConditionWithIncident.run(
                AssetId("a1"), "c-held", held(on = "2026-09-25", time = "25:00"), incident(on = "2026-02-30"),
            )
        }
        assertEquals(listOf<FieldProblem>(FieldProblem.BadDate()), again.eventProblems)
        assertEquals(
            listOf(ConditionProblem.DateInFuture, ConditionProblem.BadTime("occurredTime")),
            again.conditionProblems,
        )

        assertEquals(0, h.uow.commits)
        assertTrue(h.events.rows.isEmpty())
        assertTrue(h.rows().isEmpty())
    }

    /**
     * Hazard (R82-3): an Incident dated after today is written because events have no future-date
     * rule. The combined Save is refused whole, the event half flagged, and nothing is written.
     */
    @Test
    fun anIncidentDatedAfterTodayIsRefusedWhole() = runBlocking<Unit> {
        h.asset("a1")

        val incidentOnly = assertFailsWith<IncidentConditionRefused> {
            h.recordConditionWithIncident.run(AssetId("a1"), "c-held", held(), incident(on = "2026-09-25"))
        }
        assertEquals(Triple(emptyList<Any>(), emptyList<Any>(), true), incidentOnly.let {
            Triple(it.eventProblems, it.conditionProblems, it.incidentAfterToday)
        })

        val both = assertFailsWith<IncidentConditionRefused> {
            h.recordConditionWithIncident.run(AssetId("a1"), "c-held", held(on = "2026-09-25"), incident(on = "2026-09-25"))
        }
        assertEquals(listOf<ConditionProblem>(ConditionProblem.DateInFuture), both.conditionProblems)
        assertEquals(true, both.incidentAfterToday)

        assertTrue(h.events.rows.isEmpty())
        assertTrue(h.rows().isEmpty())
    }

    /**
     * Hazard (K5): a Save after the commit landed writes a second Incident, or is refused because the
     * form no longer validates. A stored row id is answered first, before any check: the stored pair
     * is returned, nothing written, and the recompute does not run (its next state write would throw).
     */
    @Test
    fun aStoredRowIdWritesNothingAndReturnsThePair() = runBlocking<Unit> {
        h.asset("a1")
        h.schedule("s1")
        val first = h.recordConditionWithIncident.run(AssetId("a1"), "c-held", held(), incident())
        val events = h.events.rows.toMap()
        val rows = h.rows()
        h.states.failOnUpsert = 2
        h.now += 60_000L

        val again = h.recordConditionWithIncident.run(AssetId("a1"), "c-held", held(), incident())
        val nowInvalid = h.recordConditionWithIncident.run(
            AssetId("a1"), "c-held", held(on = "2026-10-01", reason = "y".repeat(600)), incident(title = " ", on = "2026-10-01"),
        )

        assertEquals(first, again)
        assertEquals(first, nowInvalid, "the stored id is answered before validation")
        assertEquals(events, h.events.rows.toMap())
        assertEquals(rows, h.rows())
    }

    /**
     * Hazard: a retry after the Incident was deleted writes a new one, or fails. The row is returned
     * with a null Incident (its link now dangles, S24), and nothing is written.
     */
    @Test
    fun aStoredRowWhoseEventWasDeletedReturnsANullIncident() = runBlocking<Unit> {
        h.asset("a1")
        val first = h.recordConditionWithIncident.run(AssetId("a1"), "c-held", held(), incident())
        h.events.rows.clear()

        val again = h.recordConditionWithIncident.run(AssetId("a1"), "c-held", held(), incident())

        assertNull(again.incident)
        assertEquals(first.condition, again.condition)
        assertTrue(h.events.rows.isEmpty(), "no second Incident")
        assertEquals(listOf(first.condition), h.rows())
    }

    /**
     * Hazard: the app's "Could not save this entry." path is folded into the owner's refusal (C1, C7). A
     * quick action of another asset is exactly [EventOwnership], and nothing is written.
     */
    @Test
    fun anOwnershipFailureIsNotARefusal() = runBlocking<Unit> {
        h.asset("a1")
        h.asset("a2", name = "Generator")
        h.profile("p-other", EventKind.INCIDENT, assetId = "a2")

        val foreign = assertFailsWith<IllegalArgumentException> {
            h.recordConditionWithIncident.run(
                AssetId("a1"), "c-held", held(), incident().copy(profileId = ProfileId("p-other")),
            )
        }

        assertEquals(EventOwnership::class, foreign::class)
        assertTrue(h.events.rows.isEmpty())
        assertTrue(h.conditions.rows.isEmpty())
        assertEquals(0, h.uow.commits)
    }

    /**
     * Hazard: a missing asset reaches the owner as a refusal. It is exactly [NoSuchAsset], answered before
     * the command is checked — a blank title on a missing asset is still [NoSuchAsset] — and nothing is
     * written.
     */
    @Test
    fun aMissingAssetIsNotARefusalAndIsAnsweredFirst() = runBlocking<Unit> {
        for (cmd in listOf(incident(assetId = "a9"), incident(title = " ", assetId = "a9"))) {
            val missing = assertFailsWith<IllegalArgumentException> {
                h.recordConditionWithIncident.run(AssetId("a9"), "c-held", held(), cmd)
            }
            assertEquals(NoSuchAsset::class, missing::class, "title \"${cmd.title}\"")
        }

        assertTrue(h.events.rows.isEmpty())
        assertTrue(h.conditions.rows.isEmpty())
        assertEquals(0, h.uow.commits)
    }

    /**
     * Hazard: a caller's mistake is written. OPERATIONAL, a completion, another kind, a preset link and
     * another asset's Incident are programming errors — a plain `IllegalArgumentException`, not the
     * owner's refusal — thrown before any write.
     */
    @Test
    fun rejectsOperationalACompletionAnotherKindOrAPresetLink() = runBlocking<Unit> {
        h.asset("a1")
        h.asset("a2", name = "Generator")
        h.schedule("s1")
        h.event("e-own", EventKind.MAINTENANCE, "2026-09-20")
        val events = h.events.rows.toMap()

        val cases = listOf<Pair<String, suspend () -> Any>>(
            "OPERATIONAL" to { h.recordConditionWithIncident.run(AssetId("a1"), "c1", held(OPERATIONAL), incident()) },
            "a completion" to {
                h.recordConditionWithIncident.run(AssetId("a1"), "c2", held(), incident(scheduleId = "s1"))
            },
            "a NOTE" to { h.recordConditionWithIncident.run(AssetId("a1"), "c3", held(), incident(kind = EventKind.NOTE)) },
            "a preset link" to {
                h.recordConditionWithIncident.run(AssetId("a1"), "c4", held(eventId = "e-own"), incident())
            },
            "another asset's Incident" to {
                h.recordConditionWithIncident.run(AssetId("a1"), "c5", held(), incident(assetId = "a2"))
            },
        )
        for ((what, call) in cases) {
            val thrown = assertFailsWith<IllegalArgumentException>(what) { call() }
            assertEquals(IllegalArgumentException::class, thrown::class, "$what: ${thrown::class.simpleName}")
        }

        assertEquals(events, h.events.rows.toMap())
        assertTrue(h.conditions.rows.isEmpty())
        assertEquals(0, h.uow.commits)
    }
}
