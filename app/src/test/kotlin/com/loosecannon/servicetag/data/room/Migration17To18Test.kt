package com.loosecannon.servicetag.data.room

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MIGRATION_17_18] (#15, C6; R15-4): three new tables — `supply_item`, `supply_specification` and
 * `asset_supply` — and one nullable `supply_id` column on each material-line table, `profile_consumable`
 * and `consumable_usage`, with no default, no index, no foreign key and no backfill. Every material line a
 * 1.6.0 install holds arrives unlinked — whatever its name says, and one is named exactly as a catalog
 * product would be — and no timestamp moves.
 *
 * The claims, one per case: every v17 row — two assets, two quick actions and two events with several
 * material lines between them — reads back identical in every column it had, with `supply_id` NULL on
 * each line and the parents' `updated_at` untouched; the three new tables exist, empty, with their keys
 * and indices and nothing on the line tables beyond the one column; the migrated file is the same schema
 * as a fresh v18 install; and the lines read back through the Room adapters unlinked. The names are
 * fictional.
 */
class Migration17To18Test {

    @Test
    fun everyV17MaterialLineSurvivesWithANullSupplyId() = runTest {
        migrating { file, before ->
            withConnection(file) { c ->
                for (row in SEEDED) {
                    val after = c.rowOf(row.table, row.id)
                    val expected = if (row.table in V18_LINE_TABLES) {
                        before.getValue(row) + V18_LINE_COLUMNS.map { "$it=NULL" }
                    } else {
                        before.getValue(row)
                    }
                    assertEquals("$row", expected, after)
                }
                // the parents' timestamps the seed wrote are the ones that come back: nothing was touched
                assertEquals(listOf("p1|102", "p2|202"), c.lines("SELECT id, updated_at FROM event_profile ORDER BY id"))
                assertEquals(listOf("e1|302", "e2|402"), c.lines("SELECT id, updated_at FROM asset_event ORDER BY id"))
                assertEquals(
                    listOf("${PROFILE_LINES.size}|${PROFILE_LINES.size}"),
                    c.lines("SELECT COUNT(*), COUNT(*) - COUNT(supply_id) FROM profile_consumable"),
                )
                assertEquals(
                    listOf("${EVENT_LINES.size}|${EVENT_LINES.size}"),
                    c.lines("SELECT COUNT(*), COUNT(*) - COUNT(supply_id) FROM consumable_usage"),
                )
            }
        }
    }

    @Test
    fun theThreeTablesExistEmptyWithTheirIndices() = runTest {
        val file = File.createTempFile("servicetag-migrate-17-18", ".db").also { it.delete() }
        try {
            createSchemaVersion(17, file, ::seedV17)
            val (v17Tables, lineIndices, lineKeys) = withConnection(file) { c ->
                Triple(
                    c.tableNames() - ROOM_INTERNAL,
                    V18_LINE_TABLES.associateWith { c.indexDefinitionsOn(it) },
                    V18_LINE_TABLES.associateWith { c.foreignKeysOf(it) },
                )
            }
            openMigrated(file).let { db ->
                try {
                    // reading through a DAO is what makes Room open the file and run the migration
                    db.profileDao().all()
                } finally {
                    db.close()
                }
            }
            withConnection(file) { m ->
                assertEquals("the three new tables", V18_TABLES, m.tableNames() - ROOM_INTERNAL - v17Tables)
                for (table in V18_TABLES) {
                    assertEquals("$table is empty", listOf("0"), m.lines("SELECT COUNT(*) FROM $table"))
                    assertEquals("$table primary key", listOf("id"), m.primaryKeyOf(table))
                }
                assertEquals(
                    listOf(
                        "id TEXT notnull=1 default=- pk=1",
                        "name TEXT notnull=1 default=- pk=0",
                        "category TEXT notnull=1 default=- pk=0",
                        "manufacturer TEXT notnull=1 default=- pk=0",
                        "model TEXT notnull=1 default=- pk=0",
                        "part_number TEXT notnull=1 default=- pk=0",
                        "preferred_unit TEXT notnull=1 default=- pk=0",
                        "notes TEXT notnull=1 default=- pk=0",
                        "archived_at INTEGER notnull=0 default=- pk=0",
                        "created_at INTEGER notnull=1 default=- pk=0",
                        "updated_at INTEGER notnull=1 default=- pk=0",
                    ),
                    m.columnsOf("supply_item"),
                )
                assertEquals(emptyList<String>(), m.foreignKeysOf("supply_item"))
                assertEquals(
                    "supply_item carries no index of its own",
                    setOf("sqlite_autoindex_supply_item_1"),
                    m.indexNamesOn("supply_item"),
                )

                assertEquals(
                    listOf("supply_id -> supply_item(id) ON DELETE CASCADE"),
                    m.foreignKeysOf("supply_specification"),
                )
                assertEquals(
                    setOf("sqlite_autoindex_supply_specification_1", "index_supply_specification_supply_id_key"),
                    m.indexNamesOn("supply_specification"),
                )
                assertTrue(
                    "the key is unique per SupplyItem",
                    m.indexDefinitionsOn("supply_specification")
                        .single { it.startsWith("index_supply_specification_supply_id_key ") }
                        .contains("CREATE UNIQUE INDEX"),
                )

                assertEquals(
                    listOf(
                        "asset_id -> asset(id) ON DELETE CASCADE",
                        "supply_id -> supply_item(id) ON DELETE RESTRICT",
                    ),
                    m.foreignKeysOf("asset_supply"),
                )
                assertEquals(
                    setOf(
                        "sqlite_autoindex_asset_supply_1",
                        "index_asset_supply_asset_id_supply_id_role",
                        "index_asset_supply_supply_id",
                    ),
                    m.indexNamesOn("asset_supply"),
                )
                assertTrue(
                    "the triple is unique",
                    m.indexDefinitionsOn("asset_supply")
                        .single { it.startsWith("index_asset_supply_asset_id_supply_id_role ") }
                        .contains("CREATE UNIQUE INDEX"),
                )

                // The line tables gain the column and nothing else: no index, no foreign key.
                for (table in V18_LINE_TABLES) {
                    assertEquals("$table indices", lineIndices.getValue(table), m.indexDefinitionsOn(table))
                    assertEquals("$table foreign keys", lineKeys.getValue(table), m.foreignKeysOf(table))
                }
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun theMigratedSchemaEqualsAFreshVersion18() = runTest {
        val fresh = File.createTempFile("servicetag-fresh-18", ".db").also { it.delete() }
        try {
            openFresh(fresh).let { db ->
                try {
                    // touching it is what makes Room actually create the file
                    assertEquals(0, db.supplyItemDao().all().size)
                } finally {
                    db.close()
                }
            }
            migrating { file, _ ->
                withConnection(file) { m ->
                    withConnection(fresh) { f ->
                        assertEquals("the table set", f.tableNames() - ROOM_INTERNAL, m.tableNames() - ROOM_INTERNAL)
                        for (table in f.tableNames() - ROOM_INTERNAL) {
                            assertEquals("$table columns", f.columnsOf(table), m.columnsOf(table))
                            assertEquals("$table primary key", f.primaryKeyOf(table), m.primaryKeyOf(table))
                            assertEquals("$table foreign keys", f.foreignKeysOf(table), m.foreignKeysOf(table))
                            assertEquals("$table indices", f.indexDefinitionsOn(table), m.indexDefinitionsOn(table))
                        }
                        // The one new column on each line table, last, nullable and with no default.
                        assertEquals(
                            listOf("supply_id TEXT notnull=0 default=- pk=0"),
                            m.columnsOf("profile_consumable").takeLast(V18_LINE_COLUMNS.size),
                        )
                        assertEquals(7, m.columnsOf("profile_consumable").size)
                        assertEquals(
                            listOf("supply_id TEXT notnull=0 default=- pk=0"),
                            m.columnsOf("consumable_usage").takeLast(V18_LINE_COLUMNS.size),
                        )
                        assertEquals(7, m.columnsOf("consumable_usage").size)
                        for (table in V18_LINE_TABLES) {
                            assertEquals(
                                "no index on $table's link",
                                emptyList<String>(),
                                m.indexDefinitionsOn(table).filter { "supply_id" in it },
                            )
                            assertEquals(
                                "no foreign key on $table's link",
                                emptyList<String>(),
                                m.foreignKeysOf(table).filter { it.startsWith("supply_id") },
                            )
                        }
                    }
                }
            }
        } finally {
            fresh.delete()
        }
    }

    /** The migrated lines are ordinary lines: the Room adapters read every one of them back, unlinked. */
    @Test
    fun theMigratedLinesReadBackThroughTheAdaptersUnlinked() = runTest {
        val file = File.createTempFile("servicetag-migrate-17-18", ".db").also { it.delete() }
        try {
            createSchemaVersion(17, file, ::seedV17)
            val db = openMigrated(file)
            try {
                val profileLines = RoomProfileRepository(db.profileDao()).all().flatMap { it.consumables }
                val eventLines = RoomEventRepository(db.eventDao()).all().flatMap { it.consumables }
                assertEquals(PROFILE_LINES.sorted(), profileLines.map { it.id }.sorted())
                assertEquals(EVENT_LINES.sorted(), eventLines.map { it.id }.sorted())
                assertTrue("no link on a migrated line", (profileLines.map { it.supplyId } + eventLines.map { it.supplyId }).all { it == null })
                assertEquals(0, db.supplyItemDao().all().size)
                assertEquals(0, db.assetSupplyDao().all().size)
            } finally {
                db.close()
            }
        } finally {
            file.delete()
        }
    }

    // --- the harness --------------------------------------------------------------------------

    private data class Seeded(val table: String, val id: String)

    /**
     * Builds a v17 file with [seedV17], snapshots every [SEEDED] row, opens it through Room (which runs
     * [MIGRATION_17_18] and validates the result), closes it, and hands the file and the snapshot on.
     */
    private suspend fun migrating(check: (File, Map<Seeded, List<String>>) -> Unit) {
        val file = File.createTempFile("servicetag-migrate-17-18", ".db").also { it.delete() }
        try {
            createSchemaVersion(17, file, ::seedV17)
            val before = withConnection(file) { c -> SEEDED.associateWith { (t, id) -> c.rowOf(t, id) } }
            val db = openMigrated(file)
            try {
                // reading through a DAO is what makes Room open the file and run the migration
                db.profileDao().all()
            } finally {
                db.close()
            }
            check(file, before)
        } finally {
            file.delete()
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

        /** Every quick-action line [seedV17] writes, by id. */
        val PROFILE_LINES = listOf("pc1", "pc2", "pc3")

        /** Every event line [seedV17] writes, by id. */
        val EVENT_LINES = listOf("cu1", "cu2", "cu3")

        /** Every row [seedV17] writes. */
        val SEEDED = listOf(
            Seeded("asset", "a1"),
            Seeded("asset", "a2"),
            Seeded("event_profile", "p1"),
            Seeded("event_profile", "p2"),
            Seeded("asset_event", "e1"),
            Seeded("asset_event", "e2"),
        ) + PROFILE_LINES.map { Seeded("profile_consumable", it) } + EVENT_LINES.map { Seeded("consumable_usage", it) }

        /**
         * What a 1.6.0 install can hold in its material lines: two quick actions and two events across two
         * assets, with a quantity and without, with a unit and without — the first line named exactly as a
         * catalog product would be, so a migration that linked a line by its name would show here.
         */
        fun seedV17(c: SQLiteConnection) {
            asset(c, "a1", "Example RO System")
            asset(c, "a2", "Sample Water Softener")
            c.execSQL(
                "INSERT INTO event_profile (id,asset_id,name,event_kind,default_title,template_key,sort_order," +
                    "archived_at,created_at,updated_at) VALUES " +
                    "('p1','a1','Replace prefilter','REPLACEMENT','Replace prefilter',NULL,0,NULL,101,102)," +
                    "('p2','a2','Add salt','MAINTENANCE','Add salt','water_softener',1,NULL,201,202)",
            )
            c.execSQL(
                "INSERT INTO profile_consumable (id,profile_id,name,default_quantity,unit,sort_order) VALUES " +
                    "('pc1','p1','Example Prefilter Cartridge',1.0,'ea',0)," +
                    "('pc2','p1','Example sealing ring',NULL,'',1)," +
                    "('pc3','p2','Softener salt',20.0,'kg',0)",
            )
            c.execSQL(
                "INSERT INTO asset_event (id,asset_id,kind,title,profile_id,occurred_on,occurred_time,tz_id,notes," +
                    "source,source_ref,created_at,updated_at,schedule_id,occurrence_on,details_pending) VALUES " +
                    "('e1','a1','REPLACEMENT','Replace prefilter','p1','2026-09-01','08:30','UTC','',"+
                    "'MANUAL',NULL,301,302,NULL,NULL,0)," +
                    "('e2','a2','MAINTENANCE','Add salt',NULL,'2026-09-15',NULL,'UTC','two bags'," +
                    "'MANUAL',NULL,401,402,NULL,NULL,0)",
            )
            c.execSQL(
                "INSERT INTO consumable_usage (id,event_id,name,quantity,unit,sort_order) VALUES " +
                    "('cu1','e1','Example Prefilter Cartridge',1.0,'ea',0)," +
                    "('cu2','e1','Example sealing ring',2.0,'',1)," +
                    "('cu3','e2','Softener salt',40.0,'kg',0)",
            )
        }

        fun asset(c: SQLiteConnection, id: String, name: String) {
            c.execSQL(
                "INSERT INTO asset (id,name,description,category,notes,status,template_key,created_at," +
                    "updated_at,manufacturer,model,serial_number,purchase_on,in_service_on," +
                    "purchase_price_minor,currency,vendor,location,warranty_expires_on,warranty_notes," +
                    "retired_on,parent_asset_id,season_start_mmdd,season_end_mmdd,season_mode," +
                    "blackout_start_mmdd,blackout_end_mmdd,health_aggregation,health_primary_subject_id," +
                    "warranty_reminder_lead_days) VALUES " +
                    "('$id','$name','','Appliance','','ACTIVE',NULL,1000,1500,'Northwind','NW-1','SN-$id'," +
                    "'2024-03-01',NULL,129900,'EUR','','','2027-03-01','',NULL,NULL,NULL,NULL,'YEAR_ROUND'," +
                    "NULL,NULL,'WORST',NULL,30)",
            )
        }
    }
}
