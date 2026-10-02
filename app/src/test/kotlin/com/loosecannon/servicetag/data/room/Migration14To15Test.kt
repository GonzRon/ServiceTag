package com.loosecannon.servicetag.data.room

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetSuccession
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [MIGRATION_14_15] (#86, C2; R86-1, R86-15): one new table, and nothing existing moves. `asset_succession` carries
 * two foreign keys to `asset`, both `ON DELETE CASCADE`, and a UNIQUE index on each end.
 *
 * The claims, one per case: every v14 row — assets, an Incident, an attachment with a role, a service case and its
 * entry, an open and a returned loan, a transfer record — reads back identical in every column; the succession table
 * arrives empty in the shape written out here by hand; deleting either end on the migrated file takes the row; and the
 * migrated file is the same schema as a fresh v15 install. The names are fictional.
 */
class Migration14To15Test {

    @Test
    fun everyRowSurvives() = runTest {
        migrating { file, before ->
            withConnection(file) { c ->
                for (row in SEEDED) assertEquals("$row", before.getValue(row), c.rowOf(row.table, row.id, row.key).filterNot { it.substringBefore('=') in V16_ATTACHMENT_COLUMNS + V20_ATTACHMENT_COLUMNS })
            }
        }
    }

    @Test
    fun theSuccessionTableArrivesEmptyWithTwoCascadesAndTwoUniqueEnds() = runTest {
        migrating { file, _ ->
            withConnection(file) { c ->
                assertEquals(
                    listOf(
                        "id TEXT notnull=1 default=- pk=1",
                        "predecessor_asset_id TEXT notnull=1 default=- pk=0",
                        "successor_asset_id TEXT notnull=1 default=- pk=0",
                        "replaced_on TEXT notnull=1 default=- pk=0",
                        "created_at INTEGER notnull=1 default=- pk=0",
                    ),
                    c.columnsOf("asset_succession"),
                )
                assertEquals(
                    listOf(
                        "predecessor_asset_id -> asset(id) ON DELETE CASCADE",
                        "successor_asset_id -> asset(id) ON DELETE CASCADE",
                    ),
                    c.foreignKeysOf("asset_succession"),
                )
                assertEquals(
                    listOf(
                        "index_asset_succession_predecessor_asset_id | CREATE UNIQUE INDEX `index_asset_succession_predecessor_asset_id` " +
                            "ON `asset_succession` (`predecessor_asset_id`)",
                        "index_asset_succession_successor_asset_id | CREATE UNIQUE INDEX `index_asset_succession_successor_asset_id` " +
                            "ON `asset_succession` (`successor_asset_id`)",
                    ),
                    c.indexDefinitionsOn("asset_succession").filterNot { it.startsWith("sqlite_autoindex") },
                )
                assertEquals(listOf("0"), c.lines("SELECT COUNT(*) FROM asset_succession"))
            }
        }
    }

    /** Through the Room adapters on the migrated file: the cascades the migration declared reach the rows. */
    @Test
    fun deletingEitherEndTakesTheRowOnTheMigratedFile() = runTest {
        val file = File.createTempFile("servicetag-migrate-14-15", ".db").also { it.delete() }
        try {
            createSchemaVersion(14, file, ::seedV14)
            val db = openMigrated(file)
            try {
                val successions = RoomAssetSuccessionRepository(db.assetSuccessionDao())
                val assets = RoomAssetRepository(db.assetDao())
                val old = AssetSuccession("s1", AssetId("a3"), AssetId("a1"), "2026-02-01", 1_758_900_000_000L)
                successions.append(old)
                assertEquals(listOf(old), successions.all())

                assets.delete(AssetId("a3"))
                assertEquals(emptyList<AssetSuccession>(), successions.all())
                assertEquals(listOf("a1", "a2"), assets.all().map { it.id.value }.sorted())
            } finally {
                db.close()
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun theMigratedSchemaEqualsAFreshVersion15() = runTest {
        val fresh = File.createTempFile("servicetag-fresh-15", ".db").also { it.delete() }
        try {
            openFresh(fresh).let { db ->
                try {
                    // touching it is what makes Room actually create the file
                    assertEquals(0, db.assetDao().all().size)
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
                        assertEquals("the one new table", V15_TABLES, m.tableNames() - ROOM_INTERNAL - v14Tables() - V18_TABLES - V19_TABLES)
                        assertEquals("no column moved on asset", 30, m.columnsOf("asset").size)
                    }
                }
            }
        } finally {
            fresh.delete()
        }
    }

    // --- the harness --------------------------------------------------------------------------

    private data class Seeded(val table: String, val id: String, val key: String = "id")

    /**
     * Builds a v14 file with [seedV14], snapshots every [SEEDED] row, opens it through Room (which runs
     * [MIGRATION_14_15] and validates the result), closes it, and hands the file and the snapshot on.
     */
    private suspend fun migrating(check: (File, Map<Seeded, List<String>>) -> Unit) {
        val file = File.createTempFile("servicetag-migrate-14-15", ".db").also { it.delete() }
        try {
            createSchemaVersion(14, file, ::seedV14)
            val before = withConnection(file) { c -> SEEDED.associateWith { (t, id, key) -> c.rowOf(t, id, key) } }
            val db = openMigrated(file)
            try {
                // reading through a DAO is what makes Room open the file and run the migration
                db.assetDao().all()
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

    /** The tables a v14 install has, read from the exported `14.json` rather than written out. */
    private fun v14Tables(): Set<String> {
        val file = File.createTempFile("servicetag-v14", ".db").also { it.delete() }
        return try {
            createSchemaVersion(14, file) { }
            withConnection(file) { it.tableNames() - ROOM_INTERNAL }
        } finally {
            file.delete()
        }
    }

    private companion object {
        /** Room's own bookkeeping, which is not a table of the schema. */
        val ROOM_INTERNAL = setOf("room_master_table", "android_metadata", "sqlite_sequence")

        /** Every row [seedV14] writes, as `(table, id, key column)`. */
        val SEEDED = listOf(
            Seeded("asset", "a1"),
            Seeded("asset", "a2"),
            Seeded("asset", "a3"),
            Seeded("asset_event", "e1"),
            Seeded("attachment", "att-manual"),
            Seeded("asset_category", "appliance", "`key`"),
            Seeded("service_case", "c1"),
            Seeded("service_case_entry", "n1"),
            Seeded("asset_loan", "l1"),
            Seeded("asset_loan", "l2"),
            Seeded("asset_transfer", "r1"),
        )

        /**
         * What a #77 install can hold: an in-service heater and an archived component under it, a retired unit,
         * an Incident, an attachment with a document role, a catalog row, a service case with one entry, a
         * returned and an open loan of the heater, and one transfer record.
         */
        fun seedV14(c: SQLiteConnection) {
            asset(c, "a1", "Example Heater", "ACTIVE")
            asset(c, "a2", "Example Heater pump", "ARCHIVED", parent = "'a1'")
            asset(c, "a3", "Old Example Heater", "ACTIVE", retired = "'2026-02-01'")
            c.execSQL(
                "INSERT INTO asset_event (id,asset_id,kind,title,profile_id,occurred_on,occurred_time,tz_id,notes," +
                    "source,source_ref,created_at,updated_at,schedule_id,occurrence_on,details_pending) VALUES " +
                    "('e1','a1','INCIDENT','No hot water',NULL,'2026-09-20','07:40','UTC','','MANUAL',NULL,80,81," +
                    "NULL,NULL,0)",
            )
            c.execSQL(
                "INSERT INTO attachment (id,asset_id,event_id,kind,mode,display_name,mime_type,size_bytes,sha256," +
                    "storage_provider,storage_locator,captured_on,notes,created_at,updated_at,document_role) VALUES " +
                    "('att-manual','a1',NULL,'MANUAL','MANAGED','att-manual.pdf','application/pdf',1000," +
                    "'${"ab".repeat(32)}','SAF_TREE','assets/a1/att-manual.pdf','2026-09-01','',10,17,'WARRANTY')",
            )
            c.execSQL(
                "INSERT INTO asset_category (`key`,display,created_at,updated_at) VALUES ('appliance','Appliance',1000,1000)",
            )
            c.execSQL(
                "INSERT INTO service_case (id,asset_id,title,type,opened_on,closed_on,provider,contact,case_ref,coverage," +
                    "status,outbound_tracking,outbound_carrier,return_tracking,return_carrier,cost_minor,currency,notes," +
                    "incident_event_id,resolution_event_id,created_at,updated_at) VALUES " +
                    "('c1','a1','Example Heater claim','WARRANTY_SERVICE','2026-09-21',NULL,'Northwind Service','','RMA-0001'," +
                    "'IN_WARRANTY','SENT_OUT','TRK-1','Parcel Co','','',NULL,NULL,'','e1',NULL,300,310)",
            )
            c.execSQL(
                "INSERT INTO service_case_entry (id,case_id,occurred_on,occurred_time,tz_id,note,status,created_at) VALUES " +
                    "('n1','c1','2026-09-22','09:30','UTC','Courier booked','SENT_OUT',320)",
            )
            c.execSQL(
                "INSERT INTO asset_loan (id,asset_id,borrower_name,contact_lookup_uri,lent_on,due_on,returned_on," +
                    "reminder_mode,notes,created_at,updated_at,open_marker) VALUES " +
                    "('l1','a1','Sample Borrower',NULL,'2026-08-01','2026-08-15','2026-08-14','NONE','',400,410,NULL)," +
                    "('l2','a1','Example Rentals Ltd',NULL,'2026-09-01','2026-09-30',NULL,'ONCE','',420,420,1)",
            )
            c.execSQL(
                "INSERT INTO asset_transfer (id,asset_id,kind,pack_id,lineage,at,pack_sha256,name_snapshot,note) VALUES " +
                    "('r1','x9','OUT','pack-q','[]',1758960000000,'${"ab".repeat(32)}','Example Compressor','')",
            )
        }

        fun asset(
            c: SQLiteConnection,
            id: String,
            name: String,
            status: String,
            parent: String = "NULL",
            retired: String = "NULL",
        ) {
            c.execSQL(
                "INSERT INTO asset (id,name,description,category,notes,status,template_key,created_at," +
                    "updated_at,manufacturer,model,serial_number,purchase_on,in_service_on," +
                    "purchase_price_minor,currency,vendor,location,warranty_expires_on,warranty_notes," +
                    "retired_on,parent_asset_id,season_start_mmdd,season_end_mmdd,season_mode," +
                    "blackout_start_mmdd,blackout_end_mmdd,health_aggregation,health_primary_subject_id," +
                    "warranty_reminder_lead_days) VALUES " +
                    "('$id','$name','','Appliance','','$status',NULL,1000,1500,'Northwind','NW-1','SN-$id'," +
                    "'2024-03-01',NULL,129900,'EUR','','','2027-03-01','',$retired,$parent,NULL,NULL,'YEAR_ROUND'," +
                    "NULL,NULL,'WORST',NULL,30)",
            )
        }
    }
}
