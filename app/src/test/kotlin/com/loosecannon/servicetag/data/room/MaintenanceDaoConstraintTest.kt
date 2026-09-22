package com.loosecannon.servicetag.data.room

import androidx.sqlite.SQLiteException
import com.loosecannon.servicetag.data.room.entities.AssetEntity
import com.loosecannon.servicetag.data.room.entities.AssetEventEntity
import com.loosecannon.servicetag.data.room.entities.MaintenanceGroupEntity
import com.loosecannon.servicetag.data.room.entities.MaintenanceGroupMemberEntity
import com.loosecannon.servicetag.data.room.entities.MaintenanceScheduleEntity
import com.loosecannon.servicetag.data.room.entities.OccurrenceClosureEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The v6 constraints that carry a rule, asserted against a real database rather than against the
 * code that is supposed to respect them — because the point of each is that it holds even when the
 * caller forgets.
 *
 * The last case is structural instead: it reads the source, because Room's annotations have BINARY
 * retention and are invisible to runtime reflection, and because "nobody ever issues an UPDATE
 * against this table" is a claim about every line in `src/main`, not about one call.
 */
class MaintenanceDaoConstraintTest {

    private fun asset(id: String) = AssetEntity(
        id = id, name = "Asset $id", description = "", category = "", notes = "",
        status = "ACTIVE", templateKey = null, createdAt = 1L, updatedAt = 1L,
    )

    private fun group(id: String) = MaintenanceGroupEntity(
        id = id, name = "Aviary Feeders", description = "", archivedAt = null,
        createdAt = 1L, updatedAt = 2L,
    )

    private fun member(
        id: String,
        groupId: String,
        assetId: String,
        addedAt: Long,
        removedAt: Long? = null,
    ) = MaintenanceGroupMemberEntity(
        id = id, groupId = groupId, assetId = assetId, sortOrder = 0,
        addedAt = addedAt, removedAt = removedAt,
    )

    private fun schedule(id: String, assetId: String) = MaintenanceScheduleEntity(
        id = id, assetId = assetId, groupId = null, title = "Filter change", description = "",
        timeInterval = 3, timeUnit = "MONTH", timeBasis = "COMPLETION", anchorOn = "2026-03-01",
        leadDays = 7, meterDefinitionId = null, meterInterval = null, anchorMeter = null,
        meterLead = null, seasonBehavior = "IGNORE", seasonReentry = null,
        seasonReentryOffsetDays = null, completionMode = "QUICK", profileId = null,
        remindersEnabled = true, status = "ACTIVE", postponedDueOn = null,
        createdAt = 1L, updatedAt = 2L,
    )

    private fun closure(id: String, scheduleId: String, occurrenceOn: String, closedOn: String) =
        OccurrenceClosureEntity(
            id = id, scheduleId = scheduleId, occurrenceOn = occurrenceOn, closedOn = closedOn,
            createdAt = 70L,
        )

    private fun event(
        id: String,
        assetId: String,
        scheduleId: String? = null,
        occurrenceOn: String? = null,
    ) = AssetEventEntity(
        id = id, assetId = assetId, kind = "MAINTENANCE", title = "Filter change",
        profileId = null, occurredOn = "2026-04-02", occurredTime = null, tzId = "UTC", notes = "",
        source = if (scheduleId == null) "MANUAL" else "SCHEDULE_QUICK_COMPLETE",
        sourceRef = null, createdAt = 7L, updatedAt = 8L,
        scheduleId = scheduleId, occurrenceOn = occurrenceOn,
    )

    /**
     * `UNIQUE(schedule_id, occurrence_on, asset_id)`: **occurrence idempotence is a database
     * constraint, not a discipline.** Without the index the second completion of one occurrence
     * would simply be a second row, and "complete all" would duplicate work on every repeat.
     *
     * The NULL half is the other reason the index is shaped this way: SQLite treats NULLs as
     * distinct, so the index is **inert for every event that is not a completion** — any number of
     * ordinary journal events coexist, and the shipped journal keeps working unchanged.
     */
    @Test
    fun oneOccurrenceCanBeCompletedOnceByOneAsset() = runTest {
        val db = inMemoryDb()
        try {
            db.assetDao().upsert(asset("a1"))
            db.assetDao().upsert(asset("a2"))
            db.maintenanceScheduleDao().upsert(schedule("s1", "a1"), emptyList())
            val dao = db.eventDao()

            dao.upsert(event("e1", "a1", "s1", "2026-04-01"), emptyList(), emptyList())
            try {
                dao.upsert(event("e2", "a1", "s1", "2026-04-01"), emptyList(), emptyList())
                fail("expected a UNIQUE violation on (schedule_id, occurrence_on, asset_id)")
            } catch (e: Exception) {
                // The driver reports this as androidx.sqlite.SQLiteException; its message is not
                // asserted, for the reason NfcTagDaoTest records.
                assertTrue("unexpected exception: $e", e is SQLiteException)
            }

            // a different member of the same occurrence is a different key
            dao.upsert(event("e3", "a2", "s1", "2026-04-01"), emptyList(), emptyList())
            // and so is the same member of a different occurrence
            dao.upsert(event("e4", "a1", "s1", "2026-05-01"), emptyList(), emptyList())
            // NULLs are distinct: three ordinary journal events on one asset all land
            dao.upsert(event("j1", "a1"), emptyList(), emptyList())
            dao.upsert(event("j2", "a1"), emptyList(), emptyList())
            dao.upsert(event("j3", "a1"), emptyList(), emptyList())

            assertEquals(
                listOf("e1", "e3", "e4", "j1", "j2", "j3"),
                dao.all().map { it.event.id }.sorted(),
            )
        } finally {
            db.close()
        }
    }

    /**
     * `UNIQUE(schedule_id, occurrence_on)`: closing the same round twice leaves exactly one row,
     * and it is the **first** one — the second attempt is refused and changes nothing, which is why
     * the route can answer 409 and still be telling the truth about what is stored.
     */
    @Test
    fun oneRoundCanBeClosedOnce() = runTest {
        val db = inMemoryDb()
        try {
            db.assetDao().upsert(asset("a1"))
            db.maintenanceScheduleDao().upsert(schedule("s1", "a1"), emptyList())
            val dao = db.occurrenceClosureDao()

            val first = closure("oc1", "s1", "2026-05-04", "2026-05-06")
            dao.insert(first)
            try {
                dao.insert(closure("oc2", "s1", "2026-05-04", "2026-05-09"))
                fail("expected a UNIQUE violation on (schedule_id, occurrence_on)")
            } catch (e: Exception) {
                assertTrue("unexpected exception: $e", e is SQLiteException)
            }

            assertEquals(listOf(first), dao.all())
            assertEquals(first, dao.find("s1", "2026-05-04"))

            // a different round on the same schedule is a different key
            dao.insert(closure("oc3", "s1", "2026-05-11", "2026-05-12"))
            assertEquals(listOf("oc1", "oc3"), dao.forSchedule("s1").map { it.id })
        } finally {
            db.close()
        }
    }

    /**
     * `UNIQUE(group_id, asset_id, added_at)`: the index that lets membership be **append-only**.
     * Remove an asset and add it again later and there are two rows for one `(group, asset)`, which
     * is what keeps a past occurrence's required set from ever changing; two rows claiming the same
     * `added_at` are the same window twice and are refused.
     *
     * `UNIQUE(group_id, asset_id)` — the shape the issue originally sketched — would have rejected
     * the first of those two, which is the legitimate one.
     */
    @Test
    fun anAssetCanRejoinAGroupButNotTwiceInTheSameInstant() = runTest {
        val db = inMemoryDb()
        try {
            db.assetDao().upsert(asset("a1"))
            val dao = db.maintenanceGroupDao()
            dao.upsert(
                group("g1"),
                listOf(
                    member("gm1", "g1", "a1", addedAt = 1_000L, removedAt = 2_000L),
                    member("gm2", "g1", "a1", addedAt = 3_000L),
                ),
            )
            assertEquals(listOf("gm1", "gm2"), dao.byId("g1")!!.members.map { it.id }.sorted())

            try {
                dao.insertMember(member("gm3", "g1", "a1", addedAt = 3_000L))
                fail("expected a UNIQUE violation on (group_id, asset_id, added_at)")
            } catch (e: Exception) {
                assertTrue("unexpected exception: $e", e is SQLiteException)
            }
            assertEquals(2, dao.byId("g1")!!.members.size)

            // Several *closed* windows in one group are fine — the asset left and rejoined twice —
            // and so is membership of more than one group at a time, which is what makes the key
            // `(group, asset, added_at)` rather than anything narrower.
            dao.insertMember(member("gm4", "g1", "a1", addedAt = 500L, removedAt = 900L))
            dao.upsert(
                group("g2").copy(name = "Orchard Row"),
                listOf(member("gm5", "g2", "a1", addedAt = 4_000L)),
            )
            assertEquals(3, dao.byId("g1")!!.members.size)
            assertEquals(2, dao.byId("g1")!!.members.count { it.removedAt != null })

            // and the open-window lookup sees only the windows that are still running
            assertEquals(listOf("g1", "g2"), dao.openForAsset("a1").map { it.group.id }.sorted())
            assertEquals(listOf("g1", "g2"), dao.everForAsset("a1").map { it.group.id }.sorted())
        } finally {
            db.close()
        }
    }

    /**
     * `occurrence_closure` is immutable, and that is a property of the **whole module**, not of one
     * DAO method. Three assertions, one per way it could stop being true: an `@Update` or `@Delete`
     * added to the DAO for convenience; an `updated_at` column added to the entity; or an UPDATE or
     * DELETE statement against the table written anywhere in `src/main`.
     *
     * Read off the source, because a Room annotation's retention is BINARY and runtime reflection
     * cannot see it.
     */
    @Test
    fun theClosureTableIsInsertAndQueryOnly() {
        val daos = mainSourceFile("kotlin/com/loosecannon/servicetag/data/room/dao/MaintenanceDaos.kt")
            .readText()
        val closureDao = daos.substringAfter("interface OccurrenceClosureDao {")
            .substringBefore("\n}")
        assertFalse("OccurrenceClosureDao must not offer an update: $closureDao", "@Update" in closureDao)
        assertFalse("OccurrenceClosureDao must not offer a delete: $closureDao", "@Delete" in closureDao)
        assertFalse("OccurrenceClosureDao must issue no DELETE: $closureDao", "DELETE" in closureDao)
        assertFalse("OccurrenceClosureDao must issue no UPDATE: $closureDao", "UPDATE" in closureDao)

        val entity = mainSourceFile("kotlin/com/loosecannon/servicetag/data/room/entities/MaintenanceEntities.kt")
            .readText()
            .substringAfter("data class OccurrenceClosureEntity(")
            .substringBefore(")\n")
        assertFalse("an immutable row has no updated_at: $entity", "updated_at" in entity)

        val statement = Regex("(UPDATE|DELETE)\\s+(FROM\\s+)?`?occurrence_closure", RegexOption.IGNORE_CASE)
        val offenders = mainSourceFiles().filter { statement.containsMatchIn(it.readText()) }
        assertEquals(
            "no UPDATE or DELETE may name occurrence_closure",
            emptyList<String>(),
            offenders.map { it.name },
        )
    }

    /**
     * The one way a closure row *does* leave: the CASCADE from its schedule. Which is also how the
     * replace import clears the table, since no port and no DAO offers a delete.
     */
    @Test
    fun deletingASchedulesRowCascadesItsClosuresAndProviders() = runTest {
        val db = inMemoryDb()
        try {
            db.assetDao().upsert(asset("a1"))
            db.maintenanceScheduleDao().upsert(
                schedule("s1", "a1"),
                listOf(
                    com.loosecannon.servicetag.data.room.entities.ScheduleProviderEntity(
                        scheduleId = "s1", provider = "LOCAL", enabled = true,
                    ),
                ),
            )
            db.occurrenceClosureDao().insert(closure("oc1", "s1", "2026-05-04", "2026-05-06"))
            assertEquals(1, db.occurrenceClosureDao().all().size)
            assertEquals(1, db.maintenanceScheduleDao().byId("s1")!!.providers.size)

            db.maintenanceScheduleDao().deleteAll()

            assertEquals(0, db.occurrenceClosureDao().all().size)
            assertEquals(0, db.maintenanceScheduleDao().all().size)
        } finally {
            db.close()
        }
    }
}
