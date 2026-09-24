package com.loosecannon.servicetag.core.health

import com.loosecannon.servicetag.core.model.HealthDriver
import com.loosecannon.servicetag.core.model.HealthSubjectKind
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.seasonInputs
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.ScheduleLocalDelivery
import com.loosecannon.servicetag.core.ports.ScheduleLocalDeliveryRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.schedule.ScheduleRecompute
import com.loosecannon.servicetag.core.schedule.SeasonContext
import com.loosecannon.servicetag.core.testing.FakeUnitOfWork
import com.loosecannon.servicetag.core.testing.HealthFixtures
import com.loosecannon.servicetag.core.testing.HealthFixtures.SPEC_DAY
import com.loosecannon.servicetag.core.testing.InMemoryAssetRepository
import com.loosecannon.servicetag.core.testing.InMemoryClosureRepository
import com.loosecannon.servicetag.core.testing.InMemoryEventRepository
import com.loosecannon.servicetag.core.testing.InMemoryGroupRepository
import com.loosecannon.servicetag.core.testing.InMemoryScheduleRepository
import com.loosecannon.servicetag.core.testing.InMemoryScheduleStateRepository
import com.loosecannon.servicetag.core.testing.InMemorySeasonActivationRepository
import com.loosecannon.servicetag.core.testing.SeasonFixtures
import com.loosecannon.servicetag.core.testing.dayMillis
import com.loosecannon.servicetag.core.testing.scheduleOf
import com.loosecannon.servicetag.core.usecase.PostponeSchedule
import com.loosecannon.servicetag.core.usecase.RecomputeSchedules
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * O-6 and inv. 131: postponing is a canonical reassessment of the occurrence's actionable date and
 * may restart maintenance-overdue health from it, with the earlier lateness not retained and no
 * journal; snoozing delays only the reminder and never changes health.
 */
class PostponeSnoozeHealthTest {

    private val assets = InMemoryAssetRepository()
    private val events = InMemoryEventRepository()
    private val groups = InMemoryGroupRepository()
    private val closures = InMemoryClosureRepository()
    private val states = InMemoryScheduleStateRepository()
    private val schedules = InMemoryScheduleRepository(closures, states)
    private val activations = InMemorySeasonActivationRepository()
    private val uow = FakeUnitOfWork(assets, events, groups, closures, schedules, states, activations)
    private var today = SPEC_DAY
    private val recompute = RecomputeSchedules(
        schedules, states, events, closures, groups, assets, activations, Today { today }, Clock { dayMillis(today.toString()) },
    ) { ZoneOffset.UTC }
    private val postpone = PostponeSchedule(schedules, uow, recompute)

    private val asset = SeasonFixtures.assetOf(id = "a1", name = "Pump", mode = SeasonMode.YEAR_ROUND)

    /** Yearly, due 16 May 2026 and never done: 131 days late on the spec's date, CRITICAL on 14 / 45 / 120. */
    private val schedule = scheduleOf(
        id = "s1", title = "Seal check", timeInterval = 1, timeUnit = RecurrenceUnit.YEAR, anchorOn = "2026-05-16", createdOn = "2026-01-01",
    )
    private val subject = HealthFixtures.subjectOf(
        id = "h1", assetId = "a1", kind = HealthSubjectKind.PART, driver = HealthDriver.MAINTENANCE_OVERDUE, scheduleId = "s1",
        nominalUntil = 14, warningFrom = 45, criticalFrom = 120,
    )

    private suspend fun seed() {
        assets.upsert(asset)
        schedules.upsert(schedule)
        recompute.forSchedule(schedule.id)
    }

    /** The read path the app takes: the stored schedule, `readState`, the policy inputs, the engine. */
    private suspend fun health(): SubjectHealth {
        val stored = schedules.get(schedule.id)!!
        val inputs = ScheduleRecompute.policyInputsOf(stored, recompute.readState(stored), ZoneOffset.UTC)
        return AssetHealthEngine.evaluate(
            asset, listOf(subject), mapOf(stored.id to LinkedSchedule(stored, inputs)), events.all(), { true },
            SeasonContext.of(asset.seasonInputs(emptyList())), today,
        ).subjects.single()
    }

    @Test
    fun postponingRestartsTheClockFromTheNewActionableDate() = runTest {
        seed()
        assertEquals(SubjectValue.Scored(22, HealthBand.CRITICAL, 131), health().value, "131 days late")

        postpone.run(schedule.id, "2026-10-04")
        val postponed = health()
        assertEquals(SubjectValue.Scored(100, HealthBand.NOMINAL, 0), postponed.value, "the earlier lateness is not retained")
        assertEquals(listOf(DriverLine.Postponed("Seal check", LocalDate.parse("2026-10-04"))), postponed.lines)

        today = LocalDate.parse("2026-10-05")
        val lateAgain = health()
        assertEquals(SubjectValue.Scored(100, HealthBand.NOMINAL, 1), lateAgain.value, "counted from the postponed date, not from 16 May")
        assertEquals(listOf(DriverLine.Overdue("Seal check", 1), DriverLine.Grace(14)), lateAgain.lines)
    }

    @Test
    fun noEventIsWritten() = runTest {
        seed()
        val before = schedules.get(schedule.id)!!
        postpone.run(schedule.id, "2026-10-04")
        health()
        assertEquals(emptyList(), events.all(), "no postponement journal (inv. 131)")
        assertEquals(emptyList(), closures.all())
        assertEquals(emptyList(), activations.all())
        assertEquals(
            before.copy(postponedDueOn = "2026-10-04"),
            schedules.get(schedule.id),
            "the clock restarts through P alone: no rule column, no rule_changed_at, no updated_at moves",
        )
    }

    /** The snooze is device-local delivery state: nothing the engine takes can carry it. */
    @Test
    fun theEngineHasNoInputASnoozeCanReach() = runTest {
        seed()
        val critical = health()
        assertEquals(HealthBand.CRITICAL, (critical.value as SubjectValue.Scored).band)
        val delivery = MapDelivery()
        delivery.upsert(
            ScheduleLocalDelivery(
                scheduleId = ScheduleId("s1"), snoozedUntilAt = dayMillis("2026-12-31"), lastNotifiedAt = null,
                firstEntrySeen = true, actionNonce = null, nonceIssuedAt = null, updatedAt = dayMillis("2026-09-24"),
            ),
        )
        assertEquals(critical, health(), "snoozing a CRITICAL item leaves its health unchanged")

        val reachable = reachableFrom(
            AssetHealthEngine::class.java.methods.single { it.name == "evaluate" }.genericParameterTypes.toList() +
                HealthClock::class.java.methods.single { it.name == "countedDays" }.genericParameterTypes.toList(),
        )
        val offenders = reachable.filter { (name, type) ->
            name.contains("snooz", ignoreCase = true) || type.name.contains("LocalDelivery")
        }
        assertEquals(emptyList(), offenders.map { it.first }, "an input a snooze could reach")
        assertTrue(reachable.any { it.second == LinkedSchedule::class.java }, "the walk reached the linked schedule")
        assertTrue(reachable.any { it.first == "postponedDueOn" }, "and the one override it does read")
    }

    /**
     * Every (field name, field class) reachable from [roots] through the app's own types: the whole
     * shape of what the engine can be handed.
     */
    private fun reachableFrom(roots: List<Type>): List<Pair<String, Class<*>>> {
        val seen = mutableSetOf<Class<*>>()
        val found = mutableListOf<Pair<String, Class<*>>>()
        val queue = ArrayDeque(roots)
        while (queue.isNotEmpty()) {
            when (val type = queue.removeFirst()) {
                is ParameterizedType -> { queue += type.rawType; queue += type.actualTypeArguments }
                is WildcardType -> { queue += type.upperBounds; queue += type.lowerBounds }
                is Class<*> -> if (type.name.startsWith("com.loosecannon.") && seen.add(type)) {
                    found += type.simpleName to type
                    for (field in type.declaredFields) {
                        found += field.name to field.type
                        queue += field.genericType
                    }
                    type.declaredClasses.forEach { queue += it }
                }
            }
        }
        return found
    }

    private class MapDelivery : ScheduleLocalDeliveryRepository {
        private val rows = mutableMapOf<ScheduleId, ScheduleLocalDelivery>()
        override suspend fun get(id: ScheduleId): ScheduleLocalDelivery? = rows[id]
        override suspend fun upsert(row: ScheduleLocalDelivery) { rows[row.scheduleId] = row }
        override suspend fun all(): List<ScheduleLocalDelivery> = rows.values.toList()
        override suspend fun deleteAll() = rows.clear()
    }
}
