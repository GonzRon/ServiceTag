package com.loosecannon.servicetag.data.room

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.CompositionEntry
import com.loosecannon.servicetag.core.model.InstalledComponent
import com.loosecannon.servicetag.core.model.InstalledComponentId
import com.loosecannon.servicetag.core.model.SupplyId
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MIGRATION_18_19] (#47, C7; R47-2, R47-3, R47-5): two new tables — `installed_component` and
 * `installed_component_composition` — and their six indices, and nothing else: no column on an existing table, no
 * row written, no timestamp moved.
 *
 * The claims, one per case: both tables exist, empty, with their columns, keys and indices — every self- and child
 * key CASCADE, both `supply_id` keys RESTRICT, `replaces_id` unique and with no foreign key (H1); every v18 table —
 * two assets, a SupplyItem with a specification and an archived one, an applicability row, and a linked material
 * line on a quick action and on an event — is the same table with the same rows after the step; the migrated file is
 * the same schema as a fresh v19 install; and the migrated file takes an installed component through the Room
 * adapter. The names are fictional.
 */
class Migration18To19Test {

    @Test
    fun bothTablesExistEmptyWithTheirIndicesAndForeignKeys() = runTest {
        migrating { file, before ->
            withConnection(file) { m ->
                assertEquals("the two new tables", V19_TABLES, m.tableNames() - ROOM_INTERNAL - before.keys - V21_TABLES)
                for (table in V19_TABLES) {
                    assertEquals("$table is empty", listOf("0"), m.lines("SELECT COUNT(*) FROM $table"))
                    assertEquals("$table primary key", listOf("id"), m.primaryKeyOf(table))
                }

                assertEquals(
                    listOf(
                        "id TEXT notnull=1 default=- pk=1",
                        "asset_id TEXT notnull=1 default=- pk=0",
                        "parent_id TEXT notnull=0 default=- pk=0",
                        "name TEXT notnull=1 default=- pk=0",
                        "supply_id TEXT notnull=0 default=- pk=0",
                        "serial_or_lot TEXT notnull=1 default=- pk=0",
                        "installed_on TEXT notnull=0 default=- pk=0",
                        "removed_on TEXT notnull=0 default=- pk=0",
                        "replaces_id TEXT notnull=0 default=- pk=0",
                        "sort_order INTEGER notnull=1 default=- pk=0",
                        "notes TEXT notnull=1 default=- pk=0",
                        "created_at INTEGER notnull=1 default=- pk=0",
                        "updated_at INTEGER notnull=1 default=- pk=0",
                    ),
                    m.columnsOf("installed_component"),
                )
                // H1: the asset's and the parent's keys CASCADE; the SupplyItem's RESTRICT; `replaces_id` has none.
                assertEquals(
                    listOf(
                        "asset_id -> asset(id) ON DELETE CASCADE",
                        "parent_id -> installed_component(id) ON DELETE CASCADE",
                        "supply_id -> supply_item(id) ON DELETE RESTRICT",
                    ),
                    m.foreignKeysOf("installed_component"),
                )
                assertEquals(
                    setOf(
                        "sqlite_autoindex_installed_component_1",
                        "index_installed_component_asset_id",
                        "index_installed_component_parent_id",
                        "index_installed_component_supply_id",
                        "index_installed_component_replaces_id",
                    ),
                    m.indexNamesOn("installed_component"),
                )
                val rowIndices = m.indexDefinitionsOn("installed_component")
                assertTrue(
                    "one successor per row",
                    rowIndices.single { it.startsWith("index_installed_component_replaces_id ") }.contains("CREATE UNIQUE INDEX"),
                )
                for (column in listOf("asset_id", "parent_id", "supply_id")) {
                    assertFalse(
                        "$column's index is not unique",
                        rowIndices.single { it.startsWith("index_installed_component_${column} ") }.contains("UNIQUE"),
                    )
                }

                assertEquals(
                    listOf(
                        "id TEXT notnull=1 default=- pk=1",
                        "component_id TEXT notnull=1 default=- pk=0",
                        "supply_id TEXT notnull=1 default=- pk=0",
                        "quantity REAL notnull=1 default=- pk=0",
                        "unit TEXT notnull=1 default=- pk=0",
                        "sort_order INTEGER notnull=1 default=- pk=0",
                    ),
                    m.columnsOf("installed_component_composition"),
                )
                assertEquals(
                    listOf(
                        "component_id -> installed_component(id) ON DELETE CASCADE",
                        "supply_id -> supply_item(id) ON DELETE RESTRICT",
                    ),
                    m.foreignKeysOf("installed_component_composition"),
                )
                assertEquals(
                    setOf(
                        "sqlite_autoindex_installed_component_composition_1",
                        "index_installed_component_composition_component_id",
                        "index_installed_component_composition_supply_id",
                    ),
                    m.indexNamesOn("installed_component_composition"),
                )
                assertFalse(
                    "the entry indices are not unique",
                    m.indexDefinitionsOn("installed_component_composition").any { it.contains("UNIQUE") },
                )
            }
        }
    }

    /**
     * Every table the v18 file had is the same table afterwards — its columns, key, foreign keys and indices — holding
     * the same rows in every column: no column is added to an existing table, and no row is touched or derived.
     */
    @Test
    fun everyV18TableIsUnchanged() = runTest {
        migrating { file, before ->
            // every table the shipped `18.json` declares was built and snapshotted: 32, the catalog's three among them
            assertEquals("the v18 tables", 32, before.size)
            assertTrue("the v18 catalog tables", before.keys.containsAll(V18_TABLES))
            withConnection(file) { m ->
                // the chain runs on to v20, which reshapes `attachment` and `asset_reference` (#69); their rows are
                // `Migration19To20Test`'s, and every other v18 table is held whole here
                for ((table, snapshot) in before - V20_RESHAPED_TABLES) {
                    assertEquals("$table", snapshot, m.snapshotOf(table))
                }
                // the seeded rows are there, the links on both material lines with them
                assertEquals(listOf("pc1|s1", "pc2|NULL"), m.lines("SELECT id, supply_id FROM profile_consumable ORDER BY id"))
                assertEquals(listOf("cu1|s1"), m.lines("SELECT id, supply_id FROM consumable_usage ORDER BY id"))
                assertEquals(listOf("s1|NULL", "s2|50"), m.lines("SELECT id, archived_at FROM supply_item ORDER BY id"))
            }
        }
    }

    @Test
    fun theMigratedSchemaEqualsAFreshVersion19() = runTest {
        val fresh = File.createTempFile("servicetag-fresh-19", ".db").also { it.delete() }
        try {
            openFresh(fresh).let { db ->
                try {
                    // touching it is what makes Room actually create the file
                    assertEquals(0, db.installedComponentDao().all().size)
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
                    }
                }
            }
        } finally {
            fresh.delete()
        }
    }

    /**
     * The migrated tables are ordinary tables: the Room adapter writes a row with an entry on the seeded asset,
     * naming the seeded SupplyItem directly and by its entry, and reads it back whole.
     */
    @Test
    fun theMigratedFileTakesAnInstalledComponentThroughTheAdapter() = runTest {
        val file = File.createTempFile("servicetag-migrate-18-19", ".db").also { it.delete() }
        try {
            createSchemaVersion(18, file, ::seedV18)
            val db = openMigrated(file)
            try {
                val repo = RoomInstalledComponentRepository(db.installedComponentDao())
                assertEquals(0, repo.all().size)
                val pack = InstalledComponent(
                    id = InstalledComponentId("c1"), assetId = AssetId("a1"), parentId = null,
                    name = "Example Battery Pack", supplyId = SupplyId("s1"),
                    composition = listOf(CompositionEntry("e1", SupplyId("s1"), 4.0, "ea", 0)),
                    serialOrLot = "", installedOn = null, removedOn = null, replacesId = null, sortOrder = 0,
                    notes = "", createdAt = 600L, updatedAt = 600L,
                )
                repo.insert(pack)
                assertEquals(listOf(pack), repo.forAsset(AssetId("a1")))
            } finally {
                db.close()
            }
        } finally {
            file.delete()
        }
    }

    // --- the harness --------------------------------------------------------------------------

    /**
     * Builds a v18 file with [seedV18], snapshots every table it holds, opens it through Room (which runs
     * [MIGRATION_18_19] and validates the result), closes it, and hands the file and the snapshot on.
     */
    private suspend fun migrating(check: (File, Map<String, List<String>>) -> Unit) {
        val file = File.createTempFile("servicetag-migrate-18-19", ".db").also { it.delete() }
        try {
            createSchemaVersion(18, file, ::seedV18)
            val before = withConnection(file) { c -> (c.tableNames() - ROOM_INTERNAL).associateWith { c.snapshotOf(it) } }
            val db = openMigrated(file)
            try {
                // reading through a DAO is what makes Room open the file and run the migration
                db.supplyItemDao().all()
            } finally {
                db.close()
            }
            check(file, before)
        } finally {
            file.delete()
        }
    }

    /** One table whole: its columns, key, foreign keys and indices, then every row by rowid. */
    private fun SQLiteConnection.snapshotOf(table: String): List<String> =
        columnsOf(table) + primaryKeyOf(table) + foreignKeysOf(table) + indexDefinitionsOn(table) +
            lines("SELECT * FROM $table ORDER BY rowid")

    /** Each row of [sql] as its columns joined by `|`. */
    private fun SQLiteConnection.lines(sql: String): List<String> = buildList {
        prepare(sql).use { s ->
            while (s.step()) add((0 until s.getColumnCount()).joinToString("|") { if (s.isNull(it)) "NULL" else s.getText(it) })
        }
    }

    private companion object {
        /** Room's own bookkeeping, which is not a table of the schema. */
        val ROOM_INTERNAL = setOf("room_master_table", "android_metadata", "sqlite_sequence")

        /** The two tables schema v20 reshapes (#69), measured by `Migration19To20Test`. */
        val V20_RESHAPED_TABLES = setOf("attachment", "asset_reference")

        /**
         * What a 1.6.0 install can hold where #47's tables will hang: two assets, a SupplyItem with a specification
         * and an archived one, the first taken by the first asset, and a material line linked to it on a quick action
         * and on an event (and one unlinked) — so a migration that touched a row, or derived an installed component
         * from any of them, would show here.
         */
        fun seedV18(c: SQLiteConnection) {
            asset(c, "a1", "Example UPS")
            asset(c, "a2", "Example RO System")
            c.execSQL(
                "INSERT INTO supply_item (id,name,category,manufacturer,model,part_number,preferred_unit,notes," +
                    "archived_at,created_at,updated_at) VALUES " +
                    "('s1','Example 12 V Battery','Batteries','Example Power Co.','EB-12','EB-12-9','ea','',NULL,11,12)," +
                    "('s2','Example Battery Tray','Trays','Example Power Co.','ET-4','ET-4-1','ea','',50,21,50)",
            )
            c.execSQL(
                "INSERT INTO supply_specification (id,supply_id,key,label,value,unit,sort_order) VALUES " +
                    "('sp1','s1','voltage','Voltage','12','V',0)",
            )
            c.execSQL(
                "INSERT INTO asset_supply (id,asset_id,supply_id,role,created_at,updated_at) VALUES " +
                    "('as1','a1','s1','Battery',31,32)",
            )
            c.execSQL(
                "INSERT INTO event_profile (id,asset_id,name,event_kind,default_title,template_key,sort_order," +
                    "archived_at,created_at,updated_at) VALUES " +
                    "('p1','a1','Replace battery','REPLACEMENT','Replace battery',NULL,0,NULL,101,102)",
            )
            c.execSQL(
                "INSERT INTO profile_consumable (id,profile_id,name,default_quantity,unit,sort_order,supply_id) VALUES " +
                    "('pc1','p1','Example 12 V Battery',4.0,'ea',0,'s1')," +
                    "('pc2','p1','Example terminal grease',NULL,'',1,NULL)",
            )
            c.execSQL(
                "INSERT INTO asset_event (id,asset_id,kind,title,profile_id,occurred_on,occurred_time,tz_id,notes," +
                    "source,source_ref,created_at,updated_at,schedule_id,occurrence_on,details_pending) VALUES " +
                    "('e1','a1','REPLACEMENT','Replace battery','p1','2026-09-01',NULL,'UTC','','MANUAL',NULL," +
                    "301,302,NULL,NULL,0)",
            )
            c.execSQL(
                "INSERT INTO consumable_usage (id,event_id,name,quantity,unit,sort_order,supply_id) VALUES " +
                    "('cu1','e1','Example 12 V Battery',4.0,'ea',0,'s1')",
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
                    "('$id','$name','','Appliance','','ACTIVE',NULL,1000,1500,'Example Power Co.','EP-1',''," +
                    "'2024-03-01',NULL,NULL,'','','',NULL,'',NULL,NULL,NULL,NULL,'YEAR_ROUND'," +
                    "NULL,NULL,'WORST',NULL,NULL)",
            )
        }
    }
}
