package com.loosecannon.servicetag.data.room

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MIGRATION_20_21] (#16, C9–C10; R16-4, R16-10): two new device-local tables — `ha_connection` and
 * `season_sync_binding` — and the binding's one index, and nothing else: no column on an existing table, no row
 * written, no timestamp moved, and the backup format stays 20.
 *
 * The claims, one per case: both tables exist, empty, with their columns, keys, foreign keys and index — the
 * binding's asset key and its connection key both CASCADE, its asset key its primary key (one binding per asset);
 * every v20 table — two assets, a season row, a link and a deadline stamp — is the same table with the same rows
 * after the step; and the migrated file is the same schema as a fresh v21 install. The names are fictional.
 */
class Migration20To21Test {

    @Test
    fun bothTablesExistEmptyWithKeysForeignKeysAndTheIndex() = runTest {
        migrating { file, before ->
            withConnection(file) { m ->
                assertEquals("the two new tables", V21_TABLES, m.tableNames() - ROOM_INTERNAL - before.keys)
                for (table in V21_TABLES) {
                    assertEquals("$table is empty", listOf("0"), m.lines("SELECT COUNT(*) FROM $table"))
                }

                assertEquals(
                    listOf(
                        "id TEXT notnull=1 default=- pk=1",
                        "base_url TEXT notnull=1 default=- pk=0",
                        "cadence TEXT notnull=1 default=- pk=0",
                        "network_eligibility TEXT notnull=1 default=- pk=0",
                        "home_network_ssid TEXT notnull=0 default=- pk=0",
                        "background_checks TEXT notnull=1 default=- pk=0",
                        "created_at INTEGER notnull=1 default=- pk=0",
                        "updated_at INTEGER notnull=1 default=- pk=0",
                    ),
                    m.columnsOf("ha_connection"),
                )
                assertEquals(listOf("id"), m.primaryKeyOf("ha_connection"))
                assertEquals(emptyList<String>(), m.foreignKeysOf("ha_connection"))
                assertEquals(setOf("sqlite_autoindex_ha_connection_1"), m.indexNamesOn("ha_connection"))

                assertEquals(
                    listOf(
                        "asset_id TEXT notnull=1 default=- pk=1",
                        "connection_id TEXT notnull=1 default=- pk=0",
                        "entity_id TEXT notnull=1 default=- pk=0",
                        "mode TEXT notnull=1 default=- pk=0",
                        "enabled INTEGER notnull=1 default=- pk=0",
                        "revision INTEGER notnull=1 default=- pk=0",
                        "observed_state TEXT notnull=0 default=- pk=0",
                        "observed_changed_at TEXT notnull=0 default=- pk=0",
                        "last_success_at INTEGER notnull=0 default=- pk=0",
                        "last_attempt_at INTEGER notnull=0 default=- pk=0",
                        "error_kind TEXT notnull=0 default=- pk=0",
                        "error_detail TEXT notnull=0 default=- pk=0",
                        "error_at INTEGER notnull=0 default=- pk=0",
                        "applied_action TEXT notnull=0 default=- pk=0",
                        "applied_on TEXT notnull=0 default=- pk=0",
                        "applied_at INTEGER notnull=0 default=- pk=0",
                        "created_at INTEGER notnull=1 default=- pk=0",
                        "updated_at INTEGER notnull=1 default=- pk=0",
                    ),
                    m.columnsOf("season_sync_binding"),
                )
                // one binding per asset (R16-10): the asset is the key
                assertEquals(listOf("asset_id"), m.primaryKeyOf("season_sync_binding"))
                assertEquals(
                    listOf(
                        "asset_id -> asset(id) ON DELETE CASCADE",
                        "connection_id -> ha_connection(id) ON DELETE CASCADE",
                    ),
                    m.foreignKeysOf("season_sync_binding"),
                )
                assertEquals(
                    listOf(
                        "index_season_sync_binding_connection_id | " +
                            "CREATE INDEX `index_season_sync_binding_connection_id` " +
                            "ON `season_sync_binding` (`connection_id`)",
                        "sqlite_autoindex_season_sync_binding_1 | (implicit)",
                    ),
                    m.indexDefinitionsOn("season_sync_binding"),
                )
            }
        }
    }

    /**
     * Every table the v20 file had is the same table afterwards — its columns, key, foreign keys and indices — holding
     * the same rows in every column: no column is added to an existing table, and no row is touched or derived.
     */
    @Test
    fun everyV20TableIsUnchanged() = runTest {
        migrating { file, before ->
            // every table the shipped `20.json` declares was built and snapshotted
            assertEquals("the v20 tables", 34, before.size)
            withConnection(file) { m ->
                for ((table, snapshot) in before) {
                    assertEquals("$table", snapshot, m.snapshotOf(table))
                }
                // the seeded rows are there
                assertEquals(listOf("a1|MANUAL", "a2|YEAR_ROUND"), m.lines("SELECT id, season_mode FROM asset ORDER BY id"))
                assertEquals(listOf("sa1|a1|START"), m.lines("SELECT id, asset_id, action FROM asset_season_activation"))
                assertEquals(listOf("r1|a1"), m.lines("SELECT id, asset_id FROM asset_reference"))
                assertEquals(listOf("WARRANTY|a2"), m.lines("SELECT kind, subject_id FROM deadline_local_delivery"))
            }
        }
    }

    @Test
    fun theMigratedSchemaEqualsAFreshVersion21() = runTest {
        val fresh = File.createTempFile("servicetag-fresh-21", ".db").also { it.delete() }
        try {
            openFresh(fresh).let { db ->
                try {
                    // touching it is what makes Room actually create the file
                    assertEquals(0, db.seasonSyncBindingDao().all().size)
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

    // --- the harness --------------------------------------------------------------------------

    /**
     * Builds a v20 file with [seedV20], snapshots every table it holds, opens it through Room (which runs
     * [MIGRATION_20_21] and validates the result), closes it, and hands the file and the snapshot on.
     */
    private suspend fun migrating(check: (File, Map<String, List<String>>) -> Unit) {
        val file = File.createTempFile("servicetag-migrate-20-21", ".db").also { it.delete() }
        try {
            createSchemaVersion(20, file, ::seedV20)
            val before = withConnection(file) { c -> (c.tableNames() - ROOM_INTERNAL).associateWith { c.snapshotOf(it) } }
            val db = openMigrated(file)
            try {
                // reading through a DAO is what makes Room open the file and run the migration
                db.seasonSyncBindingDao().all()
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

        /**
         * What a 1.6.0 install can hold where #16's tables will hang: a MANUAL asset with a season row and a link, and
         * a second asset with a device-local deadline stamp — so a migration that touched a row, or derived a binding
         * from any of them, would show here.
         */
        fun seedV20(c: SQLiteConnection) {
            asset(c, "a1", "Example Heater", "MANUAL")
            asset(c, "a2", "Example Dehumidifier", "YEAR_ROUND")
            c.execSQL(
                "INSERT INTO asset_season_activation (id,asset_id,action,occurred_on,event_id,created_at) VALUES " +
                    "('sa1','a1','START','2026-10-01',NULL,700)",
            )
            c.execSQL(
                "INSERT INTO asset_reference (id,asset_id,kind,uri,display_name,description,scheme,created_at," +
                    "updated_at,document_role,supply_item_id,installed_component_id) VALUES " +
                    "('r1','a1','WEB_URL','https://example.invalid/heater','Example heater page','','https',800,801," +
                    "NULL,NULL,NULL)",
            )
            c.execSQL(
                "INSERT INTO deadline_local_delivery (kind,subject_id,announced_hash,announced_boot,updated_at) VALUES " +
                    "('WARRANTY','a2','hash-1',NULL,900)",
            )
            assertTrue("the seed is a v20 file", c.tableNames().containsAll(V19_TABLES))
        }

        fun asset(c: SQLiteConnection, id: String, name: String, seasonMode: String) {
            c.execSQL(
                "INSERT INTO asset (id,name,description,category,notes,status,template_key,created_at," +
                    "updated_at,manufacturer,model,serial_number,purchase_on,in_service_on," +
                    "purchase_price_minor,currency,vendor,location,warranty_expires_on,warranty_notes," +
                    "retired_on,parent_asset_id,season_start_mmdd,season_end_mmdd,season_mode," +
                    "blackout_start_mmdd,blackout_end_mmdd,health_aggregation,health_primary_subject_id," +
                    "warranty_reminder_lead_days) VALUES " +
                    "('$id','$name','','Appliance','','ACTIVE',NULL,1000,1500,'Example Heat Co.','EH-1',''," +
                    "'2024-03-01',NULL,NULL,'','','',NULL,'',NULL,NULL,NULL,NULL,'$seasonMode'," +
                    "NULL,NULL,'WORST',NULL,NULL)",
            )
        }
    }
}
