package com.loosecannon.servicetag.core.testing

import com.loosecannon.servicetag.core.model.AssetCondition
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.HealthDriver
import com.loosecannon.servicetag.core.model.HealthSubject
import com.loosecannon.servicetag.core.model.HealthSubjectId
import com.loosecannon.servicetag.core.model.HealthSubjectKind
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.PayloadFormat
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.model.TagTarget
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * #71 (plan C1, C2, E3): the in-memory doubles' three new `observeAll` flows are live, as the Room
 * adapters' are — one collection sees every upsert, delete and snapshot restore, never a single
 * snapshot taken when the collection began.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InMemoryObserveAllTest {

    private fun tag(id: String) = TagBinding(
        id = TagId(id),
        payloadFormat = PayloadFormat.V1,
        payloadKey = "key-$id",
        target = TagTarget.AssetTarget(AssetId("pump")),
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun subject(id: String) = HealthSubject(
        id = HealthSubjectId(id),
        assetId = AssetId("pump"),
        name = id,
        kind = HealthSubjectKind.PART,
        driver = HealthDriver.AGE,
        scheduleId = null,
        baselineProfileId = null,
        nominalUntilDays = 0,
        warningFromDays = 40,
        criticalFromDays = 75,
        weight = 1,
        sortOrder = 0,
        archivedAt = null,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun condition(id: String) = AssetCondition(
        id = id,
        assetId = AssetId("pump"),
        condition = OperationalCondition.DOWN,
        occurredOn = "2026-04-01",
        occurredTime = null,
        tzId = "UTC",
        reason = "",
        eventId = null,
        createdAt = 1L,
    )

    @Test
    fun theInMemoryTagAndSubjectFlowsEmitOnUpsertDeleteAndSnapshotRestore() = runTest {
        val tags = InMemoryTagRepository()
        val subjects = InMemoryHealthSubjectRepository()
        val conditions = InMemoryConditionRepository()
        val seenTags = mutableListOf<List<String>>()
        val seenSubjects = mutableListOf<List<String>>()
        val seenConditions = mutableListOf<List<String>>()
        val eager = UnconfinedTestDispatcher(testScheduler)
        backgroundScope.launch(eager) { tags.observeAll().collect { rows -> seenTags += rows.map { it.id.value } } }
        backgroundScope.launch(eager) { subjects.observeAll().collect { rows -> seenSubjects += rows.map { it.id.value } } }
        backgroundScope.launch(eager) { conditions.observeAll().collect { rows -> seenConditions += rows.map { it.id } } }

        tags.upsert(tag("t1"))
        val restoreTags = tags.snapshot()
        tags.upsert(tag("t2"))
        tags.delete(TagId("t1"))
        restoreTags()

        subjects.upsert(subject("h1"))
        val restoreSubjects = subjects.snapshot()
        subjects.upsert(subject("h2"))
        restoreSubjects()

        conditions.insert(condition("c1"))
        val restoreConditions = conditions.snapshot()
        conditions.insert(condition("c2"))
        restoreConditions()

        assertEquals(
            listOf(emptyList(), listOf("t1"), listOf("t1", "t2"), listOf("t2"), listOf("t1")),
            seenTags,
            "tags: empty, upsert, upsert, delete, restore",
        )
        assertEquals(
            listOf(emptyList(), listOf("h1"), listOf("h1", "h2"), listOf("h1")),
            seenSubjects,
            "subjects: empty, upsert, upsert, restore",
        )
        assertEquals(
            listOf(emptyList(), listOf("c1"), listOf("c1", "c2"), listOf("c1")),
            seenConditions,
            "conditions: empty, insert, insert, restore",
        )
    }
}
