package com.loosecannon.servicetag.core.testing

import com.loosecannon.servicetag.core.model.PolicyPhase
import com.loosecannon.servicetag.core.model.PolicyReason
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleState
import com.loosecannon.servicetag.core.model.TerminationKind
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * The shared fake's `observeAll` orders state rows exactly as the Room DAO does (B02's
 * carry-forward, made behavioural by B07's review M5): both order the one fixture in
 * `state-order/rows.csv` — this class through [InMemoryScheduleStateRepository], `:app`'s
 * `DueReadModelPolicyTest` through `ScheduleStateDao.observeAll` — and both must produce its
 * "expected" line, so the fake and the DAO agree over the same rows.
 */
class InMemoryScheduleStateOrderTest {

    @Test
    fun theFakeOrdersTheSharedFixtureAsTheDaoMust(): Unit = runBlocking {
        val lines = javaClass.getResource("/state-order/rows.csv")!!.readText().lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split(",") }
        val repository = InMemoryScheduleStateRepository()
        // Inserted in reverse, so the fixture's own order can never pass for the sorted one.
        lines.filter { it[0] == "row" }.reversed().forEach { (_, id, effective, actionable) ->
            repository.upsert(stateOf(id, effective, actionable.ifEmpty { null }))
        }
        val expected = lines.single { it[0] == "expected" }.drop(1)

        assertEquals(expected, repository.observeAll().first().map { it.scheduleId.value })
    }

    private fun stateOf(id: String, effective: String, actionable: String?) = ScheduleState(
        scheduleId = ScheduleId(id),
        lastCompletedOn = null,
        lastCompletionEventId = null,
        lastCompletedMeter = null,
        currentMeter = null,
        computedDueMeter = null,
        lastTerminationEffectiveOn = null,
        lastTerminationKind = TerminationKind.NONE,
        computedDueOn = effective,
        effectiveDueOn = effective,
        policyPhase = PolicyPhase.ACTIVE,
        actionableDueOn = actionable,
        policyReason = PolicyReason.NONE,
        quiet = false,
        computedForOn = "2026-09-24",
        computedAt = 0L,
    )
}
