package com.loosecannon.servicetag.data.room

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MIGRATION_21_22] (#113/#114 S1, C4): four columns on three existing tables — the schedule's passive role and its
 * boundary (`role` `'NONE'`, `role_since_on` null), the event's `imported` marker (0) and the connection's
 * `config_generation` (0) — and the three device-local publishing tables with their keys and indices, and nothing
 * else: no other column, table, index or row moves.
 *
 * The claims, one per case: the role columns default to `NONE` and null on a v21 schedule; the marker and the
 * generation default to 0 on a v21 event and connection; the delivery's request columns default to null (and its three
 * flags to 0); the three tables exist, empty, with their columns, keys, foreign keys and indices — the binding's asset a
 * soft link with **no** foreign key, its connection CASCADE, `UNIQUE(asset_id, kind)` and
 * `UNIQUE(connection_id, claim_entity_id)`; the tombstone with no foreign key at all; every other v21 table is the same
 * table with the same rows, and the three altered ones differ by their added columns only; and the migrated file is
 * the same schema as a fresh v22 install. The names are fictional.
 */
class Migration21To22Test {

    @Test
    fun theRoleColumnsDefaultToNoneAndNull() = runTest {
        migrating { file, _ ->
            withConnection(file) { m ->
                assertEquals(
                    listOf("role TEXT notnull=1 default='NONE' pk=0", "role_since_on TEXT notnull=0 default=- pk=0"),
                    m.columnsOf("maintenance_schedule").takeLast(3).take(2),
                )
                assertEquals(
                    listOf("s1|NONE|NULL", "s2|NONE|NULL"),
                    m.lines("SELECT id, role, role_since_on FROM maintenance_schedule ORDER BY id"),
                )
            }
        }
    }

    /** #125 (U-C1, h7): every v21 schedule follows its asset's season. */
    @Test
    fun theSeasonSourceDefaultsToAsset() = runTest {
        migrating { file, _ ->
            withConnection(file) { m ->
                assertEquals("season_source TEXT notnull=1 default='ASSET' pk=0", m.columnsOf("maintenance_schedule").last())
                assertEquals(listOf("s1|ASSET", "s2|ASSET"), m.lines("SELECT id, season_source FROM maintenance_schedule ORDER BY id"))
            }
        }
    }

    @Test
    fun theEventMarkerAndTheConnectionGenerationDefaultToZero() = runTest {
        migrating { file, _ ->
            withConnection(file) { m ->
                assertEquals("imported INTEGER notnull=1 default=0 pk=0", m.columnsOf("asset_event").last())
                assertEquals(listOf("e1|0"), m.lines("SELECT id, imported FROM asset_event"))
                assertEquals("config_generation INTEGER notnull=1 default=0 pk=0", m.columnsOf("ha_connection").last())
                assertEquals(listOf("c1|0"), m.lines("SELECT id, config_generation FROM ha_connection"))
            }
        }
    }

    @Test
    fun theRequestColumnsDefaultToNull() = runTest {
        migrating { file, _ ->
            withConnection(file) { m ->
                assertEquals(
                    listOf("last_request_id TEXT notnull=0 default=- pk=0", "last_request_at INTEGER notnull=0 default=- pk=0"),
                    m.columnsOf("ha_publish_delivery").takeLast(2),
                )
                // a row that names only what it must: the request stamp is null, the three flags 0
                m.execSQL(
                    "INSERT INTO ha_publish_binding (id, asset_id, connection_id, kind, entity_id, enabled, revision, " +
                        "created_at, updated_at) VALUES ('b1', 'a1', 'c1', 'EQUIPMENT_STATUS', " +
                        "'input_select.example_heater_equipment_status', 0, 1, 2000, 2000)",
                )
                m.execSQL("INSERT INTO ha_publish_delivery (binding_id, desired_revision) VALUES ('b1', 0)")
                assertEquals(
                    listOf("NULL|NULL|0|0|0"),
                    m.lines(
                        "SELECT last_request_id, last_request_at, needs_delivery, attempts_at_revision, " +
                            "reconcile_pending FROM ha_publish_delivery",
                    ),
                )
            }
        }
    }

    @Test
    fun theThreeTablesExistEmptyWithTheirIndicesAndKeys() = runTest {
        migrating { file, before ->
            withConnection(file) { m ->
                assertEquals("the five new tables", V22_TABLES, m.tableNames() - ROOM_INTERNAL - before.keys)
                for (table in V22_TABLES) {
                    assertEquals("$table is empty", listOf("0"), m.lines("SELECT COUNT(*) FROM $table"))
                }

                assertEquals(
                    listOf(
                        "id TEXT notnull=1 default=- pk=1",
                        "asset_id TEXT notnull=1 default=- pk=0",
                        "connection_id TEXT notnull=1 default=- pk=0",
                        "kind TEXT notnull=1 default=- pk=0",
                        "entity_id TEXT notnull=1 default=- pk=0",
                        "enabled INTEGER notnull=1 default=- pk=0",
                        "revision INTEGER notnull=1 default=- pk=0",
                        "activated_installation_id TEXT notnull=0 default=- pk=0",
                        "validated_at INTEGER notnull=0 default=- pk=0",
                        "validated_revision INTEGER notnull=0 default=- pk=0",
                        "claim_entity_id TEXT notnull=0 default=- pk=0",
                        "boundary_entity_id TEXT notnull=0 default=- pk=0",
                        "created_at INTEGER notnull=1 default=- pk=0",
                        "updated_at INTEGER notnull=1 default=- pk=0",
                    ),
                    m.columnsOf("ha_publish_binding"),
                )
                assertEquals(listOf("id"), m.primaryKeyOf("ha_publish_binding"))
                // the asset is a soft link (C34, R-N2): only the connection owns the row
                assertEquals(
                    listOf("connection_id -> ha_connection(id) ON DELETE CASCADE"),
                    m.foreignKeysOf("ha_publish_binding"),
                )
                assertEquals(
                    listOf(
                        "index_ha_publish_binding_asset_id_kind | CREATE UNIQUE INDEX " +
                            "`index_ha_publish_binding_asset_id_kind` ON `ha_publish_binding` (`asset_id`, `kind`)",
                        "index_ha_publish_binding_connection_id | CREATE INDEX " +
                            "`index_ha_publish_binding_connection_id` ON `ha_publish_binding` (`connection_id`)",
                        "index_ha_publish_binding_connection_id_claim_entity_id | CREATE UNIQUE INDEX " +
                            "`index_ha_publish_binding_connection_id_claim_entity_id` ON `ha_publish_binding` " +
                            "(`connection_id`, `claim_entity_id`)",
                        "sqlite_autoindex_ha_publish_binding_1 | (implicit)",
                    ),
                    m.indexDefinitionsOn("ha_publish_binding"),
                )

                assertEquals(
                    listOf(
                        "binding_id TEXT notnull=1 default=- pk=1",
                        "desired_value TEXT notnull=0 default=- pk=0",
                        "desired_revision INTEGER notnull=1 default=- pk=0",
                        "desired_at INTEGER notnull=0 default=- pk=0",
                        "last_published_value TEXT notnull=0 default=- pk=0",
                        "last_published_at INTEGER notnull=0 default=- pk=0",
                        "published_desired_revision INTEGER notnull=0 default=- pk=0",
                        "published_binding_revision INTEGER notnull=0 default=- pk=0",
                        "last_verified_value TEXT notnull=0 default=- pk=0",
                        "last_verified_at INTEGER notnull=0 default=- pk=0",
                        "verified_binding_revision INTEGER notnull=0 default=- pk=0",
                        "verified_desired_revision INTEGER notnull=0 default=- pk=0",
                        "remote_value TEXT notnull=0 default=- pk=0",
                        "remote_read_at INTEGER notnull=0 default=- pk=0",
                        "error_kind TEXT notnull=0 default=- pk=0",
                        "error_detail TEXT notnull=0 default=- pk=0",
                        "error_at INTEGER notnull=0 default=- pk=0",
                        "error_binding_revision INTEGER notnull=0 default=- pk=0",
                        "error_desired_revision INTEGER notnull=0 default=- pk=0",
                        "outcome_unknown_since INTEGER notnull=0 default=- pk=0",
                        "needs_delivery INTEGER notnull=1 default=0 pk=0",
                        "attempts_at_revision INTEGER notnull=1 default=0 pk=0",
                        "last_attempt_at INTEGER notnull=0 default=- pk=0",
                        "boundary_value TEXT notnull=0 default=- pk=0",
                        "boundary_read_at INTEGER notnull=0 default=- pk=0",
                        "boundary_binding_revision INTEGER notnull=0 default=- pk=0",
                        "reconcile_pending INTEGER notnull=1 default=0 pk=0",
                        "last_request_id TEXT notnull=0 default=- pk=0",
                        "last_request_at INTEGER notnull=0 default=- pk=0",
                    ),
                    m.columnsOf("ha_publish_delivery"),
                )
                assertEquals(listOf("binding_id"), m.primaryKeyOf("ha_publish_delivery"))
                assertEquals(
                    listOf("binding_id -> ha_publish_binding(id) ON DELETE CASCADE"),
                    m.foreignKeysOf("ha_publish_delivery"),
                )
                assertEquals(
                    listOf("sqlite_autoindex_ha_publish_delivery_1 | (implicit)"),
                    m.indexDefinitionsOn("ha_publish_delivery"),
                )

                assertEquals(
                    listOf(
                        "id TEXT notnull=1 default=- pk=1",
                        "connection_id TEXT notnull=1 default=- pk=0",
                        "entity_id TEXT notnull=1 default=- pk=0",
                        "asset_id TEXT notnull=0 default=- pk=0",
                        "installation_id TEXT notnull=1 default=- pk=0",
                        "state TEXT notnull=1 default=- pk=0",
                        "requested_at INTEGER notnull=1 default=- pk=0",
                        "error_kind TEXT notnull=0 default=- pk=0",
                        "error_at INTEGER notnull=0 default=- pk=0",
                        "revision INTEGER notnull=1 default=- pk=0",
                    ),
                    m.columnsOf("ha_publish_decommission"),
                )
                assertEquals(listOf("id"), m.primaryKeyOf("ha_publish_decommission"))
                // it outlives a Forget (C34): no key to the connection, nor to the asset
                assertEquals(emptyList<String>(), m.foreignKeysOf("ha_publish_decommission"))
                assertEquals(
                    listOf(
                        "index_ha_publish_decommission_connection_id_entity_id | CREATE UNIQUE INDEX " +
                            "`index_ha_publish_decommission_connection_id_entity_id` ON `ha_publish_decommission` " +
                            "(`connection_id`, `entity_id`)",
                        "sqlite_autoindex_ha_publish_decommission_1 | (implicit)",
                    ),
                    m.indexDefinitionsOn("ha_publish_decommission"),
                )
            }
        }
    }

    /**
     * #125 (U-C1, h7): a schedule's own season history, owned by its schedule (CASCADE) and indexed by it; and the
     * device-local schedule binding, keyed by the **schedule** — so two schedules of one asset each hold one — owned by
     * the schedule and by the connection (both CASCADE), the connection indexed.
     */
    @Test
    fun theTwoScheduleSeasonTablesExistEmptyKeyedByTheSchedule() = runTest {
        migrating { file, _ ->
            withConnection(file) { m ->
                assertEquals(
                    listOf(
                        "id TEXT notnull=1 default=- pk=1",
                        "schedule_id TEXT notnull=1 default=- pk=0",
                        "action TEXT notnull=1 default=- pk=0",
                        "occurred_on TEXT notnull=1 default=- pk=0",
                        "created_at INTEGER notnull=1 default=- pk=0",
                    ),
                    m.columnsOf("schedule_season_activation"),
                )
                assertEquals(listOf("id"), m.primaryKeyOf("schedule_season_activation"))
                assertEquals(
                    listOf("schedule_id -> maintenance_schedule(id) ON DELETE CASCADE"),
                    m.foreignKeysOf("schedule_season_activation"),
                )
                assertEquals(
                    listOf(
                        "index_schedule_season_activation_schedule_id | CREATE INDEX " +
                            "`index_schedule_season_activation_schedule_id` ON `schedule_season_activation` (`schedule_id`)",
                        "sqlite_autoindex_schedule_season_activation_1 | (implicit)",
                    ),
                    m.indexDefinitionsOn("schedule_season_activation"),
                )

                // #16's binding columns, the schedule in the asset's place
                assertEquals(
                    listOf("schedule_id TEXT notnull=1 default=- pk=1") +
                        m.columnsOf("season_sync_binding").drop(1),
                    m.columnsOf("schedule_season_sync_binding"),
                )
                assertEquals(listOf("schedule_id"), m.primaryKeyOf("schedule_season_sync_binding"))
                assertEquals(
                    listOf(
                        "connection_id -> ha_connection(id) ON DELETE CASCADE",
                        "schedule_id -> maintenance_schedule(id) ON DELETE CASCADE",
                    ),
                    m.foreignKeysOf("schedule_season_sync_binding"),
                )
                assertEquals(
                    listOf(
                        "index_schedule_season_sync_binding_connection_id | CREATE INDEX " +
                            "`index_schedule_season_sync_binding_connection_id` ON `schedule_season_sync_binding` " +
                            "(`connection_id`)",
                        "sqlite_autoindex_schedule_season_sync_binding_1 | (implicit)",
                    ),
                    m.indexDefinitionsOn("schedule_season_sync_binding"),
                )

                // two schedules of one asset each hold their own binding: the key is the schedule
                for (schedule in listOf("s1", "s2")) {
                    m.execSQL(
                        "INSERT INTO schedule_season_sync_binding (schedule_id, connection_id, entity_id, mode, enabled, " +
                            "revision, created_at, updated_at) VALUES ('$schedule', 'c1', " +
                            "'input_boolean.example_heater_winterize_season', 'FOLLOW', 1, 1, 2000, 2000)",
                    )
                }
                assertEquals(listOf("s1", "s2"), m.lines("SELECT schedule_id FROM schedule_season_sync_binding ORDER BY schedule_id"))
            }
        }
    }

    /**
     * Every table the v21 file had is the same table afterwards — its columns, key, foreign keys and indices — holding
     * the same rows in every column it had; `maintenance_schedule`, `asset_event` and `ha_connection` differ by their
     * appended columns only, and no other table gains one.
     */
    @Test
    fun everyOtherV21TableIsUnchanged() = runTest {
        migrating { file, before ->
            // every table the shipped `21.json` declares was built and snapshotted
            assertEquals("the v21 tables", 36, before.size)
            withConnection(file) { m ->
                for ((table, shape) in before) {
                    assertEquals("$table", shape, m.shapeOf(table, shape.columns))
                    val added = m.columnsOf(table).drop(shape.columns.size)
                    assertEquals("$table's appended columns", ADDED_COLUMNS[table].orEmpty(), added)
                }
                // the seeded rows are there
                assertEquals(listOf("a1|MANUAL"), m.lines("SELECT id, season_mode FROM asset"))
                assertEquals(listOf("a1|c1|3"), m.lines("SELECT asset_id, connection_id, revision FROM season_sync_binding"))
                assertEquals(listOf("e1|s1|2026-09-01"), m.lines("SELECT id, schedule_id, occurrence_on FROM asset_event"))
            }
        }
    }

    @Test
    fun theMigratedSchemaEqualsAFreshVersion22() = runTest {
        val fresh = File.createTempFile("servicetag-fresh-22", ".db").also { it.delete() }
        try {
            openFresh(fresh).let { db ->
                try {
                    // touching it is what makes Room actually create the file
                    assertEquals(0, db.haPublishBindingDao().all().size)
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

    /** One table as the v21 file had it: its columns, then its key, foreign keys, indices and every row of [columns]. */
    private data class Shape(val columns: List<String>, val rest: List<String>)

    /**
     * Builds a v21 file with [seedV21], records every table's shape, opens it through Room (which runs
     * [MIGRATION_21_22] and validates the result), closes it, and hands the file and the shapes on.
     */
    private suspend fun migrating(check: (File, Map<String, Shape>) -> Unit) {
        val file = File.createTempFile("servicetag-migrate-21-22", ".db").also { it.delete() }
        try {
            createSchemaVersion(21, file, ::seedV21)
            val before = withConnection(file) { c ->
                (c.tableNames() - ROOM_INTERNAL).associateWith { c.shapeOf(it, c.columnsOf(it)) }
            }
            val db = openMigrated(file)
            try {
                // reading through a DAO is what makes Room open the file and run the migration
                db.haPublishBindingDao().all()
            } finally {
                db.close()
            }
            check(file, before)
        } finally {
            file.delete()
        }
    }

    /**
     * [table] restricted to the column definitions it had in [columns] (as [columnsOf] prints them): those definitions,
     * then the key, foreign keys and indices, then every row by rowid over exactly those columns.
     */
    private fun SQLiteConnection.shapeOf(table: String, columns: List<String>): Shape {
        val names = columns.map { it.substringBefore(' ') }
        val select = names.joinToString(", ") { "`$it`" }
        return Shape(
            columns = columnsOf(table).filter { it.substringBefore(' ') in names },
            rest = primaryKeyOf(table) + foreignKeysOf(table) + indexDefinitionsOn(table) +
                lines("SELECT $select FROM $table ORDER BY rowid"),
        )
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

        /** C4.1: the only columns an existing table gains, appended in this order. */
        val ADDED_COLUMNS = mapOf(
            "maintenance_schedule" to listOf(
                "role TEXT notnull=1 default='NONE' pk=0",
                "role_since_on TEXT notnull=0 default=- pk=0",
                // #125 (U-C1)
                "season_source TEXT notnull=1 default='ASSET' pk=0",
            ),
            "asset_event" to listOf("imported INTEGER notnull=1 default=0 pk=0"),
            "ha_connection" to listOf("config_generation INTEGER notnull=1 default=0 pk=0"),
        )

        /**
         * What a 1.8.1 install can hold where the new columns land: a MANUAL asset with a schedule, its completion
         * event, the Home Assistant connection and the asset's #16 binding — so a migration that touched a row, or
         * derived a role, a marker or a publishing row from any of them, would show here.
         */
        fun seedV21(c: SQLiteConnection) {
            c.execSQL(
                "INSERT INTO asset (id,name,description,category,notes,status,template_key,created_at," +
                    "updated_at,manufacturer,model,serial_number,purchase_on,in_service_on," +
                    "purchase_price_minor,currency,vendor,location,warranty_expires_on,warranty_notes," +
                    "retired_on,parent_asset_id,season_start_mmdd,season_end_mmdd,season_mode," +
                    "blackout_start_mmdd,blackout_end_mmdd,health_aggregation,health_primary_subject_id," +
                    "warranty_reminder_lead_days) VALUES " +
                    "('a1','Example Heater','','Appliance','','ACTIVE',NULL,1000,1500,'Example Heat Co.','EH-1',''," +
                    "'2024-03-01',NULL,NULL,'','','',NULL,'',NULL,NULL,NULL,NULL,'MANUAL'," +
                    "NULL,NULL,'WORST',NULL,NULL)",
            )
            c.execSQL(
                "INSERT INTO maintenance_schedule (id,asset_id,group_id,title,description,time_interval,time_unit," +
                    "time_basis,anchor_on,lead_days,meter_definition_id,meter_interval,anchor_meter,meter_lead," +
                    "service_policy,policy_offset_days,completion_mode,profile_id,reminders_enabled,status," +
                    "postponed_due_on,created_at,updated_at,rule_changed_at) VALUES " +
                    "('s1','a1',NULL,'Example annual service','',1,'YEARS','FROM_COMPLETION','2025-09-01',14," +
                    "NULL,NULL,NULL,NULL,'IN_SERVICE_AT_START',NULL,'QUICK',NULL,1,'ACTIVE',NULL,1100,1200,1100), " +
                    "('s2','a1',NULL,'Example weekly check','',1,'WEEKS','FROM_COMPLETION','2025-11-01',0," +
                    "NULL,NULL,NULL,NULL,'IN_SERVICE_AT_START',NULL,'QUICK',NULL,1,'ACTIVE',NULL,1100,1200,1100)",
            )
            c.execSQL(
                "INSERT INTO asset_event (id,asset_id,kind,title,profile_id,occurred_on,occurred_time,tz_id,notes," +
                    "source,source_ref,created_at,updated_at,schedule_id,occurrence_on,details_pending) VALUES " +
                    "('e1','a1','MAINTENANCE','Example annual service',NULL,'2026-09-15',NULL,'UTC',''," +
                    "'MANUAL',NULL,1300,1300,'s1','2026-09-01',0)",
            )
            c.execSQL(
                "INSERT INTO ha_connection (id,base_url,cadence,network_eligibility,home_network_ssid," +
                    "background_checks,created_at,updated_at) VALUES " +
                    "('c1','http://192.168.0.10:8123','WEEKLY','HOME_NETWORK_ONLY','ExampleHomeWifi','ON',1400,1400)",
            )
            c.execSQL(
                "INSERT INTO season_sync_binding (asset_id,connection_id,entity_id,mode,enabled,revision," +
                    "observed_state,observed_changed_at,last_success_at,last_attempt_at,error_kind,error_detail," +
                    "error_at,applied_action,applied_on,applied_at,last_applied_source,created_at,updated_at) VALUES " +
                    "('a1','c1','input_boolean.example_heater_in_season','FOLLOW',1,3,'ON',NULL,NULL,NULL,NULL," +
                    "NULL,NULL,NULL,NULL,NULL,NULL,1500,1500)",
            )
            assertTrue("the seed is a v21 file", c.tableNames().containsAll(V21_TABLES))
        }
    }
}
