package com.loosecannon.servicetag.data.room

import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.data.room.entities.AssetConditionEntity
import com.loosecannon.servicetag.data.room.entities.AssetEntity
import com.loosecannon.servicetag.data.room.entities.AssetEventEntity
import com.loosecannon.servicetag.data.room.entities.AssetSeasonActivationEntity
import com.loosecannon.servicetag.data.room.entities.HealthSubjectEntity
import com.loosecannon.servicetag.data.room.entities.MaintenanceScheduleEntity
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Schema v8's three new tables, asserted against a real database (inv. 89, 107, 108, 109, 127's
 * DAO half): the two facts are insert and query only and carry no `updated_at`, their event links
 * are soft, and the three tables leave only by the CASCADEs the schema declares.
 *
 * The first case reads the source, for `MaintenanceDaoConstraintTest`'s reason: a Room annotation's
 * retention is BINARY, so runtime reflection cannot see an `@Update` or `@Delete`.
 */
class SeasonHealthDaoConstraintTest {

    private fun asset(id: String) = AssetEntity(
        id = id, name = "Generator $id", description = "", category = "", notes = "",
        status = "ACTIVE", templateKey = null, createdAt = 1L, updatedAt = 1L,
    )

    private fun schedule(id: String, assetId: String) = MaintenanceScheduleEntity(
        id = id, assetId = assetId, groupId = null, title = "Oil change", description = "",
        timeInterval = 6, timeUnit = "MONTH", timeBasis = "FIXED", anchorOn = "2026-03-01",
        leadDays = 7, meterDefinitionId = null, meterInterval = null, anchorMeter = null,
        meterLead = null, servicePolicy = "CONTINUOUS", policyOffsetDays = null,
        completionMode = "QUICK", profileId = null, remindersEnabled = true, status = "ACTIVE",
        postponedDueOn = null, createdAt = 1L, updatedAt = 2L, ruleChangedAt = 2L,
    )

    private fun event(id: String, assetId: String) = AssetEventEntity(
        id = id, assetId = assetId, kind = "NOTE", title = "Wouldn't start", profileId = null,
        occurredOn = "2026-04-02", occurredTime = null, tzId = "UTC", notes = "", source = "MANUAL",
        sourceRef = null, createdAt = 7L, updatedAt = 8L,
    )

    private fun activation(id: String, assetId: String, eventId: String?) = AssetSeasonActivationEntity(
        id = id, assetId = assetId, action = "START", occurredOn = "2026-04-02", eventId = eventId,
        createdAt = 10L,
    )

    private fun condition(id: String, assetId: String, eventId: String?) = AssetConditionEntity(
        id = id, assetId = assetId, condition = "DOWN", occurredOn = "2026-04-02", occurredTime = "09:15",
        tzId = "UTC", reason = "Won't crank", eventId = eventId, createdAt = 11L,
    )

    private fun subject(id: String, assetId: String, scheduleId: String?) = HealthSubjectEntity(
        id = id, assetId = assetId, name = "Oil", kind = "MEDIUM",
        driver = if (scheduleId == null) "AGE" else "MAINTENANCE_OVERDUE", scheduleId = scheduleId,
        baselineProfileId = null, nominalUntilDays = 10, warningFromDays = 20, criticalFromDays = 40,
        sortOrder = 0, archivedAt = null, createdAt = 12L, updatedAt = 12L,
    )

    /**
     * Inv. 89 and 107, and 127's DAO half: no `@Update`, no `@Delete`, and no UPDATE or DELETE text
     * on either fact DAO — comments aside — and no `updated_at` on either fact table.
     */
    @Test
    fun factDaosAreInsertAndQueryOnly() = runTest {
        val daos = mainSourceFile("kotlin/com/loosecannon/servicetag/data/room/dao/SeasonHealthDaos.kt").readText()
        for (name in listOf("SeasonActivationDao", "AssetConditionDao")) {
            val body = withoutComments(interfaceBody(daos, name))
            assertFalse("$name must not offer an update: $body", "@Update" in body)
            assertFalse("$name must not offer a delete: $body", "@Delete" in body)
            assertFalse("$name must not offer an upsert: $body", "@Upsert" in body)
            assertFalse(
                "$name must issue no UPDATE or DELETE: $body",
                Regex("\\b(update|delete)\\b", RegexOption.IGNORE_CASE).containsMatchIn(body),
            )
        }

        val file = File.createTempFile("servicetag-fresh-8-facts", ".db").also { it.delete() }
        try {
            openFresh(file).let { db ->
                try {
                    assertEquals(0, db.assetConditionDao().all().size)
                } finally {
                    db.close()
                }
            }
            withConnection(file) { c ->
                for (table in listOf("asset_season_activation", "asset_condition")) {
                    assertFalse("an immutable fact has no updated_at: $table", "updated_at" in c.columnNamesOf(table))
                }
            }
        } finally {
            file.delete()
        }
    }

    /**
     * Inv. 109: a condition's and an activation's `event_id` are soft links. A row naming an event
     * that does not exist inserts; deleting a real linked event leaves both rows byte-identical.
     */
    @Test
    fun eventLinksAreSoft() = runTest {
        val db = inMemoryDb()
        try {
            db.assetDao().upsert(asset("a1"))
            db.seasonActivationDao().insert(activation("act-ghost", "a1", eventId = "e-never"))
            db.assetConditionDao().insert(condition("cond-ghost", "a1", eventId = "e-never"))

            db.eventDao().insert(event("e1", "a1"))
            val linkedActivation = activation("act-1", "a1", eventId = "e1")
            val linkedCondition = condition("cond-1", "a1", eventId = "e1")
            db.seasonActivationDao().insert(linkedActivation)
            db.assetConditionDao().insert(linkedCondition)

            db.eventDao().delete("e1")

            assertNull(db.eventDao().byId("e1"))
            assertEquals(
                listOf(linkedActivation, activation("act-ghost", "a1", "e-never")),
                db.seasonActivationDao().forAsset("a1").sortedBy { it.id },
            )
            assertEquals(
                listOf(linkedCondition, condition("cond-ghost", "a1", "e-never")),
                db.assetConditionDao().forAsset("a1").sortedBy { it.id },
            )
        } finally {
            db.close()
        }
    }

    /** An asset's deletion takes its activations, its conditions and its health subjects with it. */
    @Test
    fun anAssetTakesItsFactsAndSubjectsWithIt() = runTest {
        val db = inMemoryDb()
        try {
            db.assetDao().upsert(asset("a1"))
            db.assetDao().upsert(asset("a2"))
            db.maintenanceScheduleDao().upsert(schedule("s1", "a1"), emptyList())
            db.seasonActivationDao().insert(activation("act-1", "a1", eventId = null))
            db.assetConditionDao().insert(condition("cond-1", "a1", eventId = null))
            db.healthSubjectDao().upsert(subject("hs-age", "a1", scheduleId = null))
            db.healthSubjectDao().upsert(subject("hs-overdue", "a1", scheduleId = "s1"))
            // the other asset's rows are the control
            db.seasonActivationDao().insert(activation("act-2", "a2", eventId = null))
            db.assetConditionDao().insert(condition("cond-2", "a2", eventId = null))
            db.healthSubjectDao().upsert(subject("hs-2", "a2", scheduleId = null))

            db.assetDao().delete("a1")

            assertEquals(listOf("act-2"), db.seasonActivationDao().all().map { it.id })
            assertEquals(listOf("cond-2"), db.assetConditionDao().all().map { it.id })
            assertEquals(listOf("hs-2"), db.healthSubjectDao().all().map { it.id })
        } finally {
            db.close()
        }
    }

    /** A deleted schedule takes the subject it drives; the asset's other subject stays. */
    @Test
    fun aScheduleTakesItsSubjectWithIt() = runTest {
        val db = inMemoryDb()
        try {
            db.assetDao().upsert(asset("a1"))
            db.maintenanceScheduleDao().upsert(schedule("s1", "a1"), emptyList())
            db.healthSubjectDao().upsert(subject("hs-overdue", "a1", scheduleId = "s1"))
            db.healthSubjectDao().upsert(subject("hs-age", "a1", scheduleId = null))
            assertEquals(listOf("hs-overdue"), db.healthSubjectDao().forSchedule("s1").map { it.id })

            db.maintenanceScheduleDao().deleteAll()

            assertEquals(listOf("hs-age"), db.healthSubjectDao().all().map { it.id })
        } finally {
            db.close()
        }
    }

    /** Inv. 108: three values and no fourth — no UNKNOWN is stored, and none reads back. */
    @Test
    fun conditionHasExactlyThreeValues() = runTest {
        assertEquals(
            listOf("OPERATIONAL", "DEGRADED", "DOWN"),
            OperationalCondition.entries.map { it.name },
        )

        val db = inMemoryDb()
        try {
            db.assetDao().upsert(asset("a1"))
            val repository = RoomConditionRepository(db.assetConditionDao())
            for (value in OperationalCondition.entries) {
                db.assetConditionDao().insert(condition("cond-${value.name}", "a1", null).copy(condition = value.name))
            }
            assertEquals(
                OperationalCondition.entries.toSet(),
                repository.forAsset(com.loosecannon.servicetag.core.model.AssetId("a1")).map { it.condition }.toSet(),
            )
            // a fourth name in the column is a corrupt row, refused at the repository boundary
            db.assetConditionDao().insert(condition("cond-unknown", "a1", null).copy(condition = "UNKNOWN"))
            val refused = runCatching { repository.all() }.exceptionOrNull()
            assertEquals(IllegalArgumentException::class, refused?.let { it::class })
        } finally {
            db.close()
        }
    }

    private companion object {
        /** The text of `interface <name> { … }`, matched brace for brace. */
        fun interfaceBody(source: String, name: String): String {
            val start = source.indexOf("interface $name {")
            check(start >= 0) { "no interface $name" }
            var depth = 0
            for (i in source.indexOf('{', start) until source.length) {
                when (source[i]) {
                    '{' -> depth += 1
                    '}' -> if (--depth == 0) return source.substring(start, i + 1)
                }
            }
            error("unbalanced braces in $name")
        }

        fun withoutComments(code: String): String = code
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//[^\\n]*"), "")
    }
}
