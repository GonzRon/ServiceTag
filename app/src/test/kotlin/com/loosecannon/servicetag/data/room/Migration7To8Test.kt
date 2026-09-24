package com.loosecannon.servicetag.data.room

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MIGRATION_7_8] (spec §8.1, §8.2; master plan §3): the five asset columns and the CALENDAR rule,
 * the 12-step recreate of `maintenance_schedule` through the legacy mapping, `schedule_state`
 * recreated empty, and the three new tables.
 *
 * The claims, one per case: every v7 row reads back identical in every column it shipped with; the
 * migrated file is the same schema as a fresh v8 install; an asset is CALENDAR exactly when both
 * `MM-DD` bounds were set; no `updated_at` moves and `rule_changed_at` is seeded from it; every
 * golden case of `docs/api/legacy-season-mapping.json` migrates to its policy; the recreate leaves
 * every child referencing the schedule; and derived state is not carried over.
 *
 * Opening through Room is itself a further check: Room validates the migrated file against the
 * compiled schema and runs `foreign_key_check` after `migrate`. See [openMigrated] for why the proof
 * is assembled by hand rather than with `SQLiteDriverMigrationTestHelper`.
 */
class Migration7To8Test {

    @Test
    fun everyRowSurvivesFieldForField() = runTest {
        migrating(::seedV7) { file, before ->
            val after = withConnection(file) { c -> SEEDED.associateWith { (t, id, key) -> c.rowOf(t, id, key) } }
            for (row in SEEDED) {
                val was = before.getValue(row)
                val now = after.getValue(row)
                when (row.table) {
                    // the asset gains five columns and changes no value it had
                    "asset" -> assertEquals("$row", was, now.filterNot { it.column() in V8_NEW_ASSET_COLUMNS })
                    // the schedule loses three columns and gains three; every other value is as it was
                    "maintenance_schedule" -> assertEquals(
                        "$row",
                        was.filterNot { it.column() in V7_SEASON_COLUMNS },
                        now.filterNot { it.column() in V8_SCHEDULE_COLUMNS },
                    )
                    else -> assertEquals("$row", was, now)
                }
            }
        }
    }

    @Test
    fun theMigratedSchemaEqualsAFreshVersion8() = runTest {
        val fresh = File.createTempFile("servicetag-fresh-8", ".db").also { it.delete() }
        try {
            openFresh(fresh).let { db ->
                try {
                    // touching it is what makes Room actually create the file
                    assertEquals(0, db.healthSubjectDao().all().size)
                } finally {
                    db.close()
                }
            }
            migrating(::seedV7) { file, _ ->
                withConnection(file) { m ->
                    withConnection(fresh) { f ->
                        assertEquals("the table set", f.tableNames() - ROOM_INTERNAL, m.tableNames() - ROOM_INTERNAL)
                        assertTrue("the three new tables", m.tableNames().containsAll(V8_TABLES))
                        for (table in f.tableNames() - ROOM_INTERNAL) {
                            assertEquals("$table columns", f.columnsOf(table), m.columnsOf(table))
                            assertEquals("$table primary key", f.primaryKeyOf(table), m.primaryKeyOf(table))
                            assertEquals("$table foreign keys", f.foreignKeysOf(table), m.foreignKeysOf(table))
                            assertEquals("$table indices", f.indexDefinitionsOn(table), m.indexDefinitionsOn(table))
                        }
                        assertEquals(
                            listOf(
                                "asset_id -> asset(id) ON DELETE CASCADE",
                                "group_id -> maintenance_group(id) ON DELETE CASCADE",
                                "meter_definition_id -> measurement_definition(id) ON DELETE RESTRICT",
                                "profile_id -> event_profile(id) ON DELETE SET NULL",
                            ),
                            m.foreignKeysOf("maintenance_schedule"),
                        )
                        assertEquals(
                            setOf(
                                "index_maintenance_schedule_asset_id_status",
                                "index_maintenance_schedule_group_id_status",
                                "index_maintenance_schedule_meter_definition_id",
                                "index_maintenance_schedule_profile_id",
                            ),
                            m.indexNamesOn("maintenance_schedule").filterNot { it.startsWith("sqlite_autoindex") }.toSet(),
                        )
                        assertEquals(
                            setOf("index_schedule_state_actionable_due_on"),
                            m.indexNamesOn("schedule_state").filterNot { it.startsWith("sqlite_autoindex") }.toSet(),
                        )
                    }
                }
            }
        } finally {
            fresh.delete()
        }
    }

    /** S-9: both bounds set is CALENDAR; one bound, or none, is YEAR_ROUND. No asset gets a break. */
    @Test
    fun anAssetIsCalendarExactlyWhenBothMonthDaysWereSet() = runTest {
        migrating(::seedV7) { file, _ ->
            withConnection(file) { c ->
                assertEquals(
                    mapOf(
                        "a-both" to "CALENDAR",
                        "a-start" to "YEAR_ROUND",
                        "a-none" to "YEAR_ROUND",
                        "a-part" to "YEAR_ROUND",
                    ),
                    c.pairs("SELECT id, season_mode FROM asset"),
                )
                assertEquals(
                    emptyMap<String, String?>(),
                    c.pairs(
                        "SELECT id, 'break' FROM asset WHERE blackout_start_mmdd IS NOT NULL " +
                            "OR blackout_end_mmdd IS NOT NULL OR health_primary_subject_id IS NOT NULL " +
                            "OR health_aggregation != 'WORST'",
                    ),
                )
            }
        }
    }

    @Test
    fun noUpdatedAtMovesAndRuleChangedAtIsSeededFromIt() = runTest {
        migrating(::seedV7) { file, before ->
            withConnection(file) { c ->
                for (row in SEEDED) {
                    val was = before.getValue(row).firstOrNull { it.column() == "updated_at" } ?: continue
                    val now = c.rowOf(row.table, row.id, row.key).single { it.column() == "updated_at" }
                    assertEquals("$row updated_at", was, now)
                }
                val seeded = c.pairs("SELECT id, updated_at FROM maintenance_schedule")
                assertEquals(SCHEDULE_UPDATED_AT, seeded.mapValues { it.value!!.toLong() })
                assertEquals(seeded, c.pairs("SELECT id, rule_changed_at FROM maintenance_schedule"))
            }
        }
    }

    /** Each golden case, as a v7 row, migrates to the policy and offset the file names. */
    @Test
    fun everyGoldenCaseMigratesToItsPolicy() = runTest {
        // `season_behavior` is NOT NULL in v7, so an absent one is not a row a v7 file can hold.
        val cases = goldenCases().filter { it.behavior != null }
        assertTrue("the golden cases", cases.size >= 20)

        val seed = { c: SQLiteConnection ->
            insertAsset(c, "a-both", "Snowblower", "11-01", "03-31", 1_000, 1_100)
            c.execSQL(
                "INSERT INTO measurement_definition VALUES ('d-hours','a-both','engine_hours','Engine hours'," +
                    "'h','NUMBER',1,NULL,NULL,1,0,NULL,1,2,'ENTERED',NULL,NULL,NULL)",
            )
            cases.forEachIndexed { i, case ->
                insertSchedule(
                    c, id = "g$i", assetId = "a-both", behavior = case.behavior!!, reentry = case.reentry,
                    offset = case.offset, timeRule = case.hasTimeRule, updatedAt = 100L + i,
                )
            }
        }
        migrating(seed) { file, _ ->
            withConnection(file) { c ->
                val policies = c.pairs("SELECT id, service_policy FROM maintenance_schedule")
                val offsets = c.pairs("SELECT id, policy_offset_days FROM maintenance_schedule")
                cases.forEachIndexed { i, case ->
                    assertEquals("$case", case.policy, policies.getValue("g$i"))
                    assertEquals("$case", case.policyOffset?.toString(), offsets.getValue("g$i"))
                }
            }
        }
    }

    /**
     * The recreate orphans nothing: `foreign_key_check` is clean, and deleting a schedule after the
     * migration still cascades its providers, closures, state and delivery row and sets its
     * completion event's `schedule_id` null — so every child still references the table by name.
     */
    @Test
    fun foreignKeyCheckIsCleanAndChildrenStillReferenceTheSchedule() = runTest {
        migrating(::seedV7) { file, _ ->
            withConnection(file) { c ->
                assertEquals(emptyMap<String, String?>(), c.pairs("SELECT \"table\", parent FROM pragma_foreign_key_check"))

                c.execSQL("PRAGMA foreign_keys = ON")
                // derived state arrives with the next recompute; one row stands in for it here
                c.execSQL(
                    "INSERT INTO schedule_state VALUES ('s-ignore',NULL,NULL,NULL,NULL,NULL,NULL,'NONE'," +
                        "'2026-01-15','2026-08-01','ACTIVE','2026-08-01','NONE',0,'2026-02-10',1)",
                )
                assertEquals("1", c.scalar("SELECT COUNT(*) FROM schedule_provider WHERE schedule_id = 's-ignore'"))
                assertEquals("1", c.scalar("SELECT COUNT(*) FROM occurrence_closure WHERE schedule_id = 's-ignore'"))
                assertEquals("1", c.scalar("SELECT COUNT(*) FROM schedule_local_delivery WHERE schedule_id = 's-ignore'"))

                c.execSQL("DELETE FROM maintenance_schedule WHERE id = 's-ignore'")

                for (table in listOf("schedule_provider", "occurrence_closure", "schedule_state", "schedule_local_delivery")) {
                    assertEquals(table, "0", c.scalar("SELECT COUNT(*) FROM $table WHERE schedule_id = 's-ignore'"))
                }
                assertEquals(mapOf("e1" to null), c.pairs("SELECT id, schedule_id FROM asset_event WHERE id = 'e1'"))
                // and the other schedules' children are untouched
                assertEquals("1", c.scalar("SELECT COUNT(*) FROM occurrence_closure WHERE schedule_id = 's-group'"))
                assertEquals("2", c.scalar("SELECT COUNT(*) FROM schedule_provider"))
            }
        }
    }

    /** `schedule_state` is derived: the migration drops it and recreates it empty. */
    @Test
    fun scheduleStateIsRecreatedEmpty() = runTest {
        migrating(::seedV7) { file, _ ->
            withConnection(file) { c ->
                assertEquals("0", c.scalar("SELECT COUNT(*) FROM schedule_state"))
                assertTrue("season_active" !in c.columnNamesOf("schedule_state"))
            }
        }
    }

    // --- the harness --------------------------------------------------------------------------

    private data class Seeded(val table: String, val id: String, val key: String = "id")

    private fun String.column() = substringBefore('=')

    /**
     * Builds a v7 file with [seed], snapshots every [SEEDED] row, opens it through Room (which runs
     * [MIGRATION_7_8] and validates the result), closes it, and hands the file and the snapshot on.
     */
    private suspend fun migrating(
        seed: (SQLiteConnection) -> Unit,
        check: (File, Map<Seeded, List<String>>) -> Unit,
    ) {
        val file = File.createTempFile("servicetag-migrate-7-8", ".db").also { it.delete() }
        try {
            createSchemaVersion(7, file, seed)
            val before = withConnection(file) { c ->
                SEEDED.filter { c.has(it) }.associateWith { (t, id, key) -> c.rowOf(t, id, key) }
            }
            val db = openMigrated(file)
            try {
                // reading through a DAO is what makes Room open the file and run the migration
                db.maintenanceScheduleDao().all()
            } finally {
                db.close()
            }
            check(file, before)
        } finally {
            file.delete()
        }
    }

    private fun SQLiteConnection.has(row: Seeded): Boolean =
        scalar("SELECT COUNT(*) FROM ${row.table} WHERE ${row.key} = '${row.id}'") == "1"

    private fun SQLiteConnection.scalar(sql: String): String? = prepare(sql).use { s ->
        check(s.step()) { "no row: $sql" }
        if (s.isNull(0)) null else s.getText(0)
    }

    private fun SQLiteConnection.pairs(sql: String): Map<String, String?> = buildMap {
        prepare(sql).use { s ->
            while (s.step()) put(s.getText(0), if (s.isNull(1)) null else s.getText(1))
        }
    }

    private data class GoldenCase(
        val behavior: String?,
        val reentry: String?,
        val offset: Int?,
        val hasTimeRule: Boolean,
        val policy: String,
        val policyOffset: Int?,
    )

    private fun goldenCases(): List<GoldenCase> {
        var dir = File(".").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("cannot find the repository root from ${File(".").absolutePath}")
        }
        val text = File(dir, "docs/api/legacy-season-mapping.json").readText()
        fun kotlinx.serialization.json.JsonElement.text() = if (this is JsonNull) null else jsonPrimitive.contentOrNull
        fun kotlinx.serialization.json.JsonElement.int() = if (this is JsonNull) null else jsonPrimitive.intOrNull
        return Json.parseToJsonElement(text).jsonArray.map {
            val row = it.jsonObject
            GoldenCase(
                behavior = row.getValue("seasonBehavior").text(),
                reentry = row.getValue("seasonReentry").text(),
                offset = row.getValue("seasonReentryOffsetDays").int(),
                hasTimeRule = row.getValue("hasTimeRule").jsonPrimitive.booleanOrNull!!,
                policy = row.getValue("servicePolicy").text()!!,
                policyOffset = row.getValue("policyOffsetDays").int(),
            )
        }
    }

    private companion object {
        /** Room's own bookkeeping, which is not a table of the schema. */
        val ROOM_INTERNAL = setOf("room_master_table", "android_metadata", "sqlite_sequence")

        val V7_SEASON_COLUMNS = setOf("season_behavior", "season_reentry", "season_reentry_offset_days")
        val V8_SCHEDULE_COLUMNS = setOf("service_policy", "policy_offset_days", "rule_changed_at")

        /** Every row [seedV7] writes, as `(table, id, key column)`. */
        val SEEDED = listOf(
            Seeded("asset", "a-both"),
            Seeded("asset", "a-start"),
            Seeded("asset", "a-none"),
            Seeded("asset", "a-part"),
            Seeded("measurement_definition", "d-hours"),
            Seeded("event_profile", "p1"),
            Seeded("maintenance_group", "g1"),
            Seeded("maintenance_group_member", "gm1"),
            Seeded("maintenance_schedule", "s-ignore"),
            Seeded("maintenance_schedule", "s-follow-null"),
            Seeded("maintenance_schedule", "s-follow-at-start"),
            Seeded("maintenance_schedule", "s-follow-clamped"),
            Seeded("maintenance_schedule", "s-follow-mmdd"),
            Seeded("maintenance_schedule", "s-meter-only"),
            Seeded("maintenance_schedule", "s-group"),
            Seeded("schedule_provider", "s-ignore", "schedule_id"),
            Seeded("schedule_provider", "s-follow-at-start", "schedule_id"),
            Seeded("schedule_provider", "s-group", "schedule_id"),
            Seeded("occurrence_closure", "oc1"),
            Seeded("occurrence_closure", "oc2"),
            Seeded("asset_event", "e1"),
            Seeded("schedule_local_delivery", "s-ignore", "schedule_id"),
        )

        /** Each seeded schedule's `updated_at` — distinct from its `created_at`, so a seed from the wrong one shows. */
        val SCHEDULE_UPDATED_AT = mapOf(
            "s-ignore" to 5_500L,
            "s-follow-null" to 6_600L,
            "s-follow-at-start" to 7_700L,
            "s-follow-clamped" to 8_800L,
            "s-follow-mmdd" to 9_900L,
            "s-meter-only" to 11_000L,
            "s-group" to 13_000L,
        )

        fun insertAsset(
            c: SQLiteConnection,
            id: String,
            name: String,
            start: String?,
            end: String?,
            createdAt: Long,
            updatedAt: Long,
            parent: String? = null,
        ) {
            fun q(v: String?) = v?.let { "'$it'" } ?: "NULL"
            c.execSQL(
                "INSERT INTO asset (id,name,description,category,notes,status,template_key,created_at," +
                    "updated_at,manufacturer,model,serial_number,purchase_on,in_service_on," +
                    "purchase_price_minor,currency,vendor,location,warranty_expires_on,warranty_notes," +
                    "retired_on,parent_asset_id,season_start_mmdd,season_end_mmdd) VALUES " +
                    "('$id','$name','','Yard','','ACTIVE',NULL,$createdAt,$updatedAt,'Northwind','NW-1'," +
                    "'SN-$id','2024-03-01',NULL,129900,'USD','','',NULL,'',NULL,${q(parent)},${q(start)},${q(end)})",
            )
        }

        fun insertSchedule(
            c: SQLiteConnection,
            id: String,
            assetId: String? = null,
            groupId: String? = null,
            behavior: String,
            reentry: String?,
            offset: Int?,
            timeRule: Boolean = true,
            meter: Boolean = !timeRule,
            profileId: String? = null,
            status: String = "ACTIVE",
            postponed: String? = null,
            reminders: Int = 1,
            createdAt: Long = 1L,
            updatedAt: Long,
        ) {
            fun q(v: String?) = v?.let { "'$it'" } ?: "NULL"
            val time = if (timeRule) "6,'MONTH','FIXED','2026-01-15'" else "NULL,NULL,'FIXED',NULL"
            val meterSide = if (meter) "'d-hours',25.0,10.0,2.5" else "NULL,NULL,NULL,NULL"
            c.execSQL(
                "INSERT INTO maintenance_schedule (id,asset_id,group_id,title,description,time_interval," +
                    "time_unit,time_basis,anchor_on,lead_days,meter_definition_id,meter_interval," +
                    "anchor_meter,meter_lead,season_behavior,season_reentry,season_reentry_offset_days," +
                    "completion_mode,profile_id,reminders_enabled,status,postponed_due_on,created_at," +
                    "updated_at) VALUES ('$id',${q(assetId)},${q(groupId)},'Job $id','about $id',$time,7," +
                    "$meterSide,'$behavior',${q(reentry)},${offset ?: "NULL"}," +
                    "'${if (profileId == null) "QUICK" else "FORM"}',${q(profileId)},$reminders,'$status'," +
                    "${q(postponed)},$createdAt,$updatedAt)",
            )
        }

        /**
         * What a 1.3 install can hold: a CALENDAR-shaped asset, a half-set one, one with no window
         * and a component; a meter definition and a profile; a group with a member; schedules that
         * IGNORE and FOLLOW_ASSET with a null, an `AT_START`, a `RESUME_CLAMPED` and an `04-01`
         * re-entry, offsets 5, 400 and null, a meter-only one and a group one; providers, two
         * closures, a completion event linked by `schedule_id`, a local delivery row and a derived
         * state row.
         */
        fun seedV7(c: SQLiteConnection) {
            insertAsset(c, "a-both", "Snowblower", "11-01", "03-31", 1_000, 1_100)
            insertAsset(c, "a-start", "Mower", "04-01", null, 2_000, 2_100)
            insertAsset(c, "a-none", "Generator", null, null, 3_000, 3_100)
            insertAsset(c, "a-part", "Battery pack", null, null, 4_000, 4_100, parent = "a-none")
            c.execSQL(
                "INSERT INTO measurement_definition VALUES ('d-hours','a-both','engine_hours','Engine hours'," +
                    "'h','NUMBER',1,NULL,NULL,1,0,NULL,1,2,'ENTERED',NULL,NULL,NULL)",
            )
            c.execSQL(
                "INSERT INTO event_profile VALUES ('p1','a-both','Service','MAINTENANCE','Service',NULL,0,NULL,3,4)",
            )
            c.execSQL("INSERT INTO maintenance_group VALUES ('g1','North run','',NULL,5,6)")
            c.execSQL("INSERT INTO maintenance_group_member VALUES ('gm1','g1','a-none',0,100,NULL)")

            insertSchedule(
                c, "s-ignore", assetId = "a-none", behavior = "IGNORE", reentry = null, offset = null,
                postponed = "2026-08-01", createdAt = 5_000, updatedAt = 5_500,
            )
            insertSchedule(
                c, "s-follow-null", assetId = "a-both", behavior = "FOLLOW_ASSET", reentry = null,
                offset = null, createdAt = 6_000, updatedAt = 6_600,
            )
            insertSchedule(
                c, "s-follow-at-start", assetId = "a-both", behavior = "FOLLOW_ASSET", reentry = "AT_START",
                offset = 5, meter = true, profileId = "p1", createdAt = 7_000, updatedAt = 7_700,
            )
            insertSchedule(
                c, "s-follow-clamped", assetId = "a-both", behavior = "FOLLOW_ASSET",
                reentry = "RESUME_CLAMPED", offset = null, status = "PAUSED", createdAt = 8_000, updatedAt = 8_800,
            )
            insertSchedule(
                c, "s-follow-mmdd", assetId = "a-both", behavior = "FOLLOW_ASSET", reentry = "04-01",
                offset = 400, reminders = 0, createdAt = 9_000, updatedAt = 9_900,
            )
            insertSchedule(
                c, "s-meter-only", assetId = "a-both", behavior = "FOLLOW_ASSET", reentry = null, offset = null,
                timeRule = false, createdAt = 10_000, updatedAt = 11_000,
            )
            insertSchedule(
                c, "s-group", groupId = "g1", behavior = "IGNORE", reentry = null, offset = null,
                createdAt = 12_000, updatedAt = 13_000,
            )

            c.execSQL("INSERT INTO schedule_provider VALUES ('s-ignore','LOCAL',1)")
            c.execSQL("INSERT INTO schedule_provider VALUES ('s-follow-at-start','LOCAL',0)")
            c.execSQL("INSERT INTO schedule_provider VALUES ('s-group','LOCAL',1)")
            c.execSQL("INSERT INTO occurrence_closure VALUES ('oc1','s-group','2026-02-02','2026-02-03',70)")
            c.execSQL("INSERT INTO occurrence_closure VALUES ('oc2','s-ignore','2025-07-15','2025-07-20',71)")
            c.execSQL(
                "INSERT INTO asset_event VALUES ('e1','a-none','MAINTENANCE','Oil change',NULL,'2026-01-16'," +
                    "'09:15','UTC','','SCHEDULE_QUICK_COMPLETE',NULL,80,81,'s-ignore','2026-01-15',0)",
            )
            c.execSQL("INSERT INTO schedule_local_delivery VALUES ('s-ignore',777,888,1,'n1',999,1234)")
            c.execSQL(
                "INSERT INTO schedule_state VALUES ('s-ignore','2026-01-16','e1',NULL,NULL,NULL,'2026-01-16'," +
                    "'COMPLETED','2026-07-15','2026-08-01',1,'2026-02-10',42)",
            )
        }
    }
}
