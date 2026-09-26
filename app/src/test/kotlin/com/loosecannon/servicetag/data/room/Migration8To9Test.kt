package com.loosecannon.servicetag.data.room

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.loosecannon.servicetag.core.model.AssetCategory
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MIGRATION_8_9] (#74, C10; R74-4): the `asset_category` table, then the promotion rule applied once,
 * retroactively, through the pure core backfill — row by row, in Kotlin, with no `updated_at` moved.
 *
 * The claims, one per case: every v8 row reads back identical in every column except an asset's
 * `category` where the plan rewrites it; the migrated file is the same schema as a fresh v9 install;
 * the seeded variant set yields exactly the planned rows and spellings — a non-ASCII case pair and a
 * decomposed spelling among them, which SQLite's ASCII-only lowering would split; no `updated_at`
 * moves; the rows read back through the Room adapter; and an install with no assets gets an empty
 * catalog. Every expected key, spelling and timestamp is written out by hand.
 */
class Migration8To9Test {

    @Test
    fun everyRowSurvivesFieldForFieldExceptThePlannedCategory() = runTest {
        migrating(::seedV8) { file, before ->
            val after = withConnection(file) { c -> SEEDED.associateWith { (t, id, key) -> c.rowOf(t, id, key) } }
            for (row in SEEDED) {
                val was = before.getValue(row)
                val expected = if (row.table == "asset") {
                    was.map { if (it.column() == "category") "category=${CATEGORY_AFTER.getValue(row.id)}" else it }
                } else {
                    was
                }
                assertEquals("$row", expected, after.getValue(row))
            }
        }
    }

    @Test
    fun theMigratedSchemaEqualsAFreshVersion9() = runTest {
        val fresh = File.createTempFile("servicetag-fresh-9", ".db").also { it.delete() }
        try {
            openFresh(fresh).let { db ->
                try {
                    // touching it is what makes Room actually create the file
                    assertEquals(0, db.assetCategoryDao().all().size)
                } finally {
                    db.close()
                }
            }
            migrating(::seedV8) { file, _ ->
                withConnection(file) { m ->
                    withConnection(fresh) { f ->
                        assertEquals("the table set", f.tableNames() - ROOM_INTERNAL, m.tableNames() - ROOM_INTERNAL)
                        assertTrue("the new table", m.tableNames().containsAll(V9_TABLES))
                        for (table in f.tableNames() - ROOM_INTERNAL) {
                            assertEquals("$table columns", f.columnsOf(table), m.columnsOf(table))
                            assertEquals("$table primary key", f.primaryKeyOf(table), m.primaryKeyOf(table))
                            assertEquals("$table foreign keys", f.foreignKeysOf(table), m.foreignKeysOf(table))
                            assertEquals("$table indices", f.indexDefinitionsOn(table), m.indexDefinitionsOn(table))
                        }
                        assertEquals(
                            listOf(
                                "key TEXT notnull=1 default=- pk=1",
                                "display TEXT notnull=1 default=- pk=0",
                                "created_at INTEGER notnull=1 default=- pk=0",
                                "updated_at INTEGER notnull=1 default=- pk=0",
                            ),
                            m.columnsOf("asset_category"),
                        )
                        // One classification dimension: no foreign key either way between an asset and the catalog.
                        assertEquals(emptyList<String>(), m.foreignKeysOf("asset_category"))
                        assertEquals(listOf("parent_asset_id -> asset(id) ON DELETE RESTRICT"), m.foreignKeysOf("asset"))
                    }
                }
            }
        } finally {
            fresh.delete()
        }
    }

    /**
     * `appliance`, `Appliance` and ` APPLIANCE` make one row spelled as the oldest (`Appliance`); two
     * spellings of a water heater make one collapsed row; `Éclairage`, `éclairage` and a decomposed
     * `É` make one row keyed `éclairage` and spelled as the oldest, precomposed; `hot tub` becomes the
     * built-in label with no row; `RO system` is already a label; the blank stays blank.
     */
    @Test
    fun theSeededVariantSetYieldsExactlyThePlannedRowsAndRewrites() = runTest {
        migrating(::seedV8) { file, _ ->
            withConnection(file) { c ->
                assertEquals(
                    listOf(
                        "appliance|Appliance|1000|1000",
                        "water heater|Water heater|6000|6000",
                        "\u00e9clairage|\u00c9clairage|500|500",
                    ),
                    c.lines("SELECT `key`, display, created_at, updated_at FROM asset_category ORDER BY `key`"),
                )
                assertEquals(CATEGORY_AFTER, c.pairs("SELECT id, category FROM asset"))
            }
        }
    }

    @Test
    fun noUpdatedAtMovesAnywhere() = runTest {
        migrating(::seedV8) { file, before ->
            withConnection(file) { c ->
                for (row in SEEDED) {
                    val was = before.getValue(row).firstOrNull { it.column() == "updated_at" } ?: continue
                    val now = c.rowOf(row.table, row.id, row.key).single { it.column() == "updated_at" }
                    assertEquals("$row updated_at", was, now)
                }
                assertEquals(ASSET_UPDATED_AT, c.pairs("SELECT id, updated_at FROM asset").mapValues { it.value!!.toLong() })
            }
        }
    }

    /** The backfilled rows are ordinary rows: the Room adapter reads them as the domain rows. */
    @Test
    fun theBackfilledRowsReadBackThroughTheAdapter() = runTest {
        val file = File.createTempFile("servicetag-migrate-8-9", ".db").also { it.delete() }
        try {
            createSchemaVersion(8, file, ::seedV8)
            val db = openMigrated(file)
            try {
                assertEquals(
                    listOf(
                        AssetCategory("appliance", "Appliance", 1_000L, 1_000L),
                        AssetCategory("water heater", "Water heater", 6_000L, 6_000L),
                        AssetCategory("\u00e9clairage", "\u00c9clairage", 500L, 500L),
                    ),
                    RoomCategoryRepository(db.assetCategoryDao()).all(),
                )
            } finally {
                db.close()
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun anInstallWithNoAssetsGetsAnEmptyCatalog() = runTest {
        migrating({ }) { file, _ ->
            withConnection(file) { c -> assertEquals(emptyList<String>(), c.lines("SELECT `key` FROM asset_category")) }
        }
    }

    // --- the harness --------------------------------------------------------------------------

    private data class Seeded(val table: String, val id: String, val key: String = "id")

    private fun String.column() = substringBefore('=')

    /**
     * Builds a v8 file with [seed], snapshots every [SEEDED] row, opens it through Room (which runs
     * [MIGRATION_8_9] and validates the result), closes it, and hands the file and the snapshot on.
     */
    private suspend fun migrating(
        seed: (SQLiteConnection) -> Unit,
        check: (File, Map<Seeded, List<String>>) -> Unit,
    ) {
        val file = File.createTempFile("servicetag-migrate-8-9", ".db").also { it.delete() }
        try {
            createSchemaVersion(8, file, seed)
            val before = withConnection(file) { c ->
                SEEDED.filter { c.has(it) }.associateWith { (t, id, key) -> c.rowOf(t, id, key) }
            }
            val db = openMigrated(file)
            try {
                // reading through a DAO is what makes Room open the file and run the migration
                db.assetCategoryDao().all()
            } finally {
                db.close()
            }
            check(file, before)
        } finally {
            file.delete()
        }
    }

    private fun SQLiteConnection.has(row: Seeded): Boolean = prepare(
        "SELECT COUNT(*) FROM ${row.table} WHERE ${row.key} = ?",
    ).use { s ->
        s.bindText(1, row.id)
        s.step()
        s.getLong(0) == 1L
    }

    private fun SQLiteConnection.pairs(sql: String): Map<String, String?> = buildMap {
        prepare(sql).use { s ->
            while (s.step()) put(s.getText(0), if (s.isNull(1)) null else s.getText(1))
        }
    }

    /** Each row of [sql] as its columns joined by `|`. */
    private fun SQLiteConnection.lines(sql: String): List<String> = buildList {
        prepare(sql).use { s ->
            while (s.step()) add((0 until s.getColumnCount()).joinToString("|") { if (s.isNull(it)) "NULL" else s.getText(it) })
        }
    }

    private companion object {
        /** Room's own bookkeeping, which is not a table of the schema. */
        val ROOM_INTERNAL = setOf("room_master_table", "android_metadata", "sqlite_sequence")

        /** Every asset's category after the migration, written out by hand. */
        val CATEGORY_AFTER = mapOf(
            "a-old" to "Appliance",
            "a-lower" to "Appliance",
            "a-upper" to "Appliance",
            "a-tub" to "Hot tub",
            "a-blank" to "",
            "a-water" to "Water heater",
            "a-ro" to "RO system",
            "a-arch" to "Water heater",
            "a-light-upper" to "\u00c9clairage",
            "a-light-lower" to "\u00c9clairage",
            "a-light-nfd" to "\u00c9clairage",
        )

        /** Every asset's `updated_at` as seeded — distinct from its `created_at`, so a move shows. */
        val ASSET_UPDATED_AT = mapOf(
            "a-old" to 1_500L,
            "a-lower" to 3_500L,
            "a-upper" to 2_500L,
            "a-tub" to 4_500L,
            "a-blank" to 5_500L,
            "a-water" to 6_500L,
            "a-ro" to 7_500L,
            "a-arch" to 9_500L,
            "a-light-upper" to 550L,
            "a-light-lower" to 650L,
            "a-light-nfd" to 750L,
        )

        /** Every row [seedV8] writes, as `(table, id, key column)`. */
        val SEEDED = listOf(
            Seeded("asset", "a-old"),
            Seeded("asset", "a-lower"),
            Seeded("asset", "a-upper"),
            Seeded("asset", "a-tub"),
            Seeded("asset", "a-blank"),
            Seeded("asset", "a-water"),
            Seeded("asset", "a-ro"),
            Seeded("asset", "a-arch"),
            Seeded("asset", "a-light-upper"),
            Seeded("asset", "a-light-lower"),
            Seeded("asset", "a-light-nfd"),
            Seeded("nfc_tag", "t1"),
            Seeded("external_link", "l1"),
            Seeded("measurement_definition", "d1"),
            Seeded("event_profile", "p1"),
            Seeded("asset_event", "e1"),
            Seeded("maintenance_group", "g1"),
            Seeded("maintenance_group_member", "gm1"),
            Seeded("maintenance_schedule", "s1"),
            Seeded("schedule_provider", "s1", "schedule_id"),
            Seeded("schedule_local_delivery", "s1", "schedule_id"),
            Seeded("asset_reference", "r1"),
            Seeded("asset_season_activation", "act1"),
            Seeded("asset_condition", "c1"),
            Seeded("health_subject", "h1"),
        )

        fun insertAsset(
            c: SQLiteConnection,
            id: String,
            category: String,
            createdAt: Long,
            updatedAt: Long,
            status: String = "ACTIVE",
            retiredOn: String? = null,
        ) {
            fun q(v: String?) = v?.let { "'$it'" } ?: "NULL"
            c.execSQL(
                "INSERT INTO asset (id,name,description,category,notes,status,template_key,created_at," +
                    "updated_at,manufacturer,model,serial_number,purchase_on,in_service_on," +
                    "purchase_price_minor,currency,vendor,location,warranty_expires_on,warranty_notes," +
                    "retired_on,parent_asset_id,season_start_mmdd,season_end_mmdd,season_mode," +
                    "blackout_start_mmdd,blackout_end_mmdd,health_aggregation,health_primary_subject_id) VALUES " +
                    "('$id','Asset $id','','$category','','$status',NULL,$createdAt,$updatedAt,'Northwind'," +
                    "'NW-1','SN-$id','2024-03-01',NULL,129900,'USD','','',NULL,'',${q(retiredOn)},NULL,NULL," +
                    "NULL,'YEAR_ROUND',NULL,NULL,'WORST',NULL)",
            )
        }

        /**
         * What a 1.4 install can hold: the plan's variant set (`appliance` / `Appliance` / ` APPLIANCE`,
         * `hot tub`, a blank), a double-spaced and an archived, retired variant of one more category, a
         * non-ASCII trio (`Éclairage` oldest, `éclairage`, and `E` + U+0301 + `clairage`), a built-in
         * already spelled as its label, and one row in each other table [SEEDED] names — the 2.6
         * tombstone `external_link` among them — so every row the cases compare has something behind it.
         * The attachment, journal-child and derived tables are not seeded: the migration reads `asset`
         * and writes `asset` and `asset_category` only, and the fresh-schema case covers every table.
         */
        fun seedV8(c: SQLiteConnection) {
            insertAsset(c, "a-old", "Appliance", 1_000, 1_500)
            insertAsset(c, "a-lower", "appliance", 3_000, 3_500)
            insertAsset(c, "a-upper", " APPLIANCE", 2_000, 2_500)
            insertAsset(c, "a-tub", "hot tub", 4_000, 4_500)
            insertAsset(c, "a-blank", "", 5_000, 5_500)
            insertAsset(c, "a-water", "Water  heater", 6_000, 6_500)
            insertAsset(c, "a-ro", "RO system", 7_000, 7_500)
            insertAsset(c, "a-arch", "WATER HEATER", 9_000, 9_500, status = "ARCHIVED", retiredOn = "2025-01-01")
            insertAsset(c, "a-light-upper", "\u00c9clairage", 500, 550)
            insertAsset(c, "a-light-lower", "\u00e9clairage", 600, 650)
            insertAsset(c, "a-light-nfd", "E\u0301clairage", 700, 750)
            c.execSQL(
                "INSERT INTO nfc_tag (id,payload_format,payload_key,asset_id,link_id,status,label,physical_uid," +
                    "written_at,last_scanned_at,created_at,updated_at) " +
                    "VALUES ('t1','V1','t1','a-old',NULL,'ACTIVE','tag one','04A224B2',300,400,1,2)",
            )
            c.execSQL(
                "INSERT INTO external_link (id,asset_id,kind,label,uri,created_at,last_opened_at,updated_at) " +
                    "VALUES ('l1','a-old','URL','Manual','https://example.com/manual',10,NULL,11)",
            )
            c.execSQL(
                "INSERT INTO measurement_definition (id,asset_id,key,label,unit,value_type,decimals,range_low," +
                    "range_high,is_meter,sort_order,archived_at,created_at,updated_at,kind,formula,source_a_id," +
                    "source_b_id) VALUES ('d1','a-old','hours','Hours','h','NUMBER',1,NULL,NULL,1,0,NULL,1,2," +
                    "'ENTERED',NULL,NULL,NULL)",
            )
            c.execSQL(
                "INSERT INTO event_profile (id,asset_id,name,event_kind,default_title,template_key,sort_order," +
                    "archived_at,created_at,updated_at) VALUES ('p1','a-old','Service','MAINTENANCE','Service',NULL," +
                    "0,NULL,3,4)",
            )
            c.execSQL(
                "INSERT INTO asset_event (id,asset_id,kind,title,profile_id,occurred_on,occurred_time,tz_id,notes," +
                    "source,source_ref,created_at,updated_at,schedule_id,occurrence_on,details_pending) VALUES " +
                    "('e1','a-old','MAINTENANCE','Oil change',NULL,'2026-01-16','09:15','UTC','','MANUAL',NULL,80,81," +
                    "NULL,NULL,0)",
            )
            c.execSQL(
                "INSERT INTO maintenance_group (id,name,description,archived_at,created_at,updated_at) " +
                    "VALUES ('g1','North run','',NULL,5,6)",
            )
            c.execSQL(
                "INSERT INTO maintenance_group_member (id,group_id,asset_id,sort_order,added_at,removed_at) " +
                    "VALUES ('gm1','g1','a-lower',0,100,NULL)",
            )
            c.execSQL(
                "INSERT INTO maintenance_schedule (id,asset_id,group_id,title,description,time_interval,time_unit," +
                    "time_basis,anchor_on,lead_days,meter_definition_id,meter_interval,anchor_meter,meter_lead," +
                    "service_policy,policy_offset_days,completion_mode,profile_id,reminders_enabled,status," +
                    "postponed_due_on,created_at,updated_at,rule_changed_at) VALUES ('s1','a-old',NULL,'Service','',6," +
                    "'MONTH','FIXED','2026-01-15',7,NULL,NULL,NULL,NULL,'IGNORE',NULL,'QUICK',NULL,1,'ACTIVE',NULL," +
                    "50,55,55)",
            )
            c.execSQL("INSERT INTO schedule_provider (schedule_id,provider,enabled) VALUES ('s1','LOCAL',1)")
            c.execSQL(
                "INSERT INTO schedule_local_delivery (schedule_id,snoozed_until_at,last_notified_at," +
                    "first_entry_seen,action_nonce,nonce_issued_at,updated_at) VALUES ('s1',777,888,1,'n1',999,1234)",
            )
            c.execSQL(
                "INSERT INTO asset_reference (id,asset_id,kind,uri,display_name,description,scheme,created_at," +
                    "updated_at) VALUES ('r1','a-water','WEB','https://example.com/doc','Doc','','https',20,21)",
            )
            c.execSQL(
                "INSERT INTO asset_season_activation (id,asset_id,action,occurred_on,event_id,created_at) " +
                    "VALUES ('act1','a-tub','START','2026-05-01',NULL,30)",
            )
            c.execSQL(
                "INSERT INTO asset_condition (id,asset_id,condition,occurred_on,occurred_time,tz_id,reason," +
                    "event_id,created_at) VALUES ('c1','a-old','DEGRADED','2026-02-01',NULL,'UTC','Low output',NULL,40)",
            )
            c.execSQL(
                "INSERT INTO health_subject (id,asset_id,name,kind,driver,schedule_id,baseline_profile_id," +
                    "nominal_until_days,warning_from_days,critical_from_days,weight,sort_order,archived_at," +
                    "created_at,updated_at) VALUES ('h1','a-old','Battery age','PART','AGE',NULL,NULL,365,1095," +
                    "1460,1,0,NULL,60,61)",
            )
        }
    }
}
