package com.loosecannon.servicetag.data.room

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetLoan
import com.loosecannon.servicetag.core.model.AssetLoanId
import com.loosecannon.servicetag.core.model.LoanReminderMode
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [MIGRATION_12_13] (#72, C5; R72-1, R72-2): one new table, and nothing existing moves. `asset_loan` hangs
 * off `asset` by a CASCADE foreign key on an indexed column, and carries the unique
 * `(asset_id, open_marker)` index that holds an asset to one open loan.
 *
 * The claims, one per case: every v12 row — assets with and without a warranty lead, an Incident, an
 * attachment with a role, a condition, a category, a service case and its entry, and a device-local
 * deadline stamp — reads back identical in every column; the loan table arrives empty in the shape written
 * out here by hand; the device-local stamp table is exactly as it was, with no column added; an asset's
 * delete on the migrated file takes its loans and nothing of another asset's; and the migrated file is the
 * same schema as a fresh v13 install. The names are fictional.
 */
class Migration12To13Test {

    @Test
    fun everyRowSurvives() = runTest {
        migrating { file, before ->
            withConnection(file) { c ->
                for (row in SEEDED) assertEquals("$row", before.getValue(row), c.rowOf(row.table, row.id, row.key).filterNot { it.substringBefore('=') in V16_ATTACHMENT_COLUMNS + V20_ATTACHMENT_COLUMNS })
                assertEquals(
                    "every asset, its warranty lead kept",
                    listOf("a1|2027-03-01|30", "a2|NULL|NULL", "a3|2025-01-31|NULL"),
                    c.lines("SELECT id, warranty_expires_on, warranty_reminder_lead_days FROM asset ORDER BY id"),
                )
            }
        }
    }

    @Test
    fun theLoanTableArrivesEmpty() = runTest {
        migrating { file, _ ->
            withConnection(file) { c ->
                assertEquals(listOf("0"), c.lines("SELECT COUNT(*) FROM asset_loan"))
                assertEquals(
                    listOf(
                        "id TEXT notnull=1 default=- pk=1",
                        "asset_id TEXT notnull=1 default=- pk=0",
                        "borrower_name TEXT notnull=1 default=- pk=0",
                        "contact_lookup_uri TEXT notnull=0 default=- pk=0",
                        "lent_on TEXT notnull=1 default=- pk=0",
                        "due_on TEXT notnull=0 default=- pk=0",
                        "returned_on TEXT notnull=0 default=- pk=0",
                        "reminder_mode TEXT notnull=1 default=- pk=0",
                        "notes TEXT notnull=1 default=- pk=0",
                        "created_at INTEGER notnull=1 default=- pk=0",
                        "updated_at INTEGER notnull=1 default=- pk=0",
                        "open_marker INTEGER notnull=0 default=- pk=0",
                    ),
                    c.columnsOf("asset_loan"),
                )
                assertEquals(
                    "the asset owns its loans",
                    listOf("asset_id -> asset(id) ON DELETE CASCADE"),
                    c.foreignKeysOf("asset_loan"),
                )
                assertEquals(
                    listOf(
                        "index_asset_loan_asset_id | CREATE INDEX `index_asset_loan_asset_id` ON `asset_loan` (`asset_id`)",
                        "index_asset_loan_asset_id_open_marker | " +
                            "CREATE UNIQUE INDEX `index_asset_loan_asset_id_open_marker` ON `asset_loan` (`asset_id`, `open_marker`)",
                    ),
                    c.indexDefinitionsOn("asset_loan").filterNot { it.startsWith("sqlite_autoindex") },
                )
            }
        }
    }

    /** C5: the device-local deadline stamp gains no column and loses no row — a loan's key is soft text. */
    @Test
    fun theDeadlineStampTableIsUnchanged() = runTest {
        val file = File.createTempFile("servicetag-v12-stamp", ".db").also { it.delete() }
        val v12 = try {
            createSchemaVersion(12, file) { }
            withConnection(file) { it.columnsOf("deadline_local_delivery") to it.indexDefinitionsOn("deadline_local_delivery") }
        } finally {
            file.delete()
        }
        migrating { migrated, _ ->
            withConnection(migrated) { c ->
                assertEquals(v12.first, c.columnsOf("deadline_local_delivery"))
                assertEquals(v12.second, c.indexDefinitionsOn("deadline_local_delivery"))
                assertEquals(
                    listOf("WARRANTY_EXPIRY|a1|0123456789abcdef|7|2000"),
                    c.lines("SELECT kind, subject_id, announced_hash, announced_boot, updated_at FROM deadline_local_delivery"),
                )
            }
        }
    }

    /** Through the Room adapters on the migrated file, so the CASCADE the migration declared is the one that runs. */
    @Test
    fun deletingAnAssetCascadesItsLoans() = runTest {
        val file = File.createTempFile("servicetag-migrate-12-13", ".db").also { it.delete() }
        try {
            createSchemaVersion(12, file, ::seedV12)
            val db = openMigrated(file)
            try {
                val loans = RoomAssetLoanRepository(db.assetLoanDao())
                // a3 is the standalone retired unit: a1 has a child, which the parent key would refuse.
                loans.upsert(loanOf("l1", "a3", returnedOn = "2026-09-10"))
                loans.upsert(loanOf("l2", "a3"))
                loans.upsert(loanOf("l3", "a1"))

                RoomAssetRepository(db.assetDao()).delete(AssetId("a3"))

                assertEquals(listOf("l3"), loans.all().map { it.id.value })
            } finally {
                db.close()
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun theMigratedSchemaEqualsAFreshVersion13() = runTest {
        val fresh = File.createTempFile("servicetag-fresh-13", ".db").also { it.delete() }
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
                        assertEquals("the one new table", V13_TABLES, m.tableNames() - ROOM_INTERNAL - v12Tables() - V14_TABLES - V15_TABLES - V18_TABLES - V19_TABLES - V21_TABLES)
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
     * Builds a v12 file with [seedV12], snapshots every [SEEDED] row, opens it through Room (which runs
     * [MIGRATION_12_13] and validates the result), closes it, and hands the file and the snapshot on.
     */
    private suspend fun migrating(check: (File, Map<Seeded, List<String>>) -> Unit) {
        val file = File.createTempFile("servicetag-migrate-12-13", ".db").also { it.delete() }
        try {
            createSchemaVersion(12, file, ::seedV12)
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

    /** The tables a v12 install has, read from the exported `12.json` rather than written out. */
    private fun v12Tables(): Set<String> {
        val file = File.createTempFile("servicetag-v12", ".db").also { it.delete() }
        return try {
            createSchemaVersion(12, file) { }
            withConnection(file) { it.tableNames() - ROOM_INTERNAL }
        } finally {
            file.delete()
        }
    }

    private fun loanOf(id: String, assetId: String, returnedOn: String? = null) = AssetLoan(
        id = AssetLoanId(id), assetId = AssetId(assetId), borrowerName = "Sample Borrower", contactLookupUri = null,
        lentOn = "2026-09-01", dueOn = "2026-09-30", returnedOn = returnedOn, reminderMode = LoanReminderMode.ONCE,
        notes = "", createdAt = 100L, updatedAt = 100L,
    )

    private companion object {
        /** Room's own bookkeeping, which is not a table of the schema. */
        val ROOM_INTERNAL = setOf("room_master_table", "android_metadata", "sqlite_sequence")

        /** Every row [seedV12] writes, as `(table, id, key column)`. */
        val SEEDED = listOf(
            Seeded("asset", "a1"),
            Seeded("asset", "a2"),
            Seeded("asset", "a3"),
            Seeded("asset_event", "e1"),
            Seeded("attachment", "att-manual"),
            Seeded("asset_condition", "cond-1"),
            Seeded("asset_category", "appliance", "`key`"),
            Seeded("service_case", "c1"),
            Seeded("service_case_entry", "n1"),
            Seeded("deadline_local_delivery", "a1", "subject_id"),
        )

        /**
         * What a #79 install can hold: an in-service heater with a warranty date and a lead, an archived
         * component under it, a retired unit whose warranty has run out, its Incident, an attachment with a
         * document role, a DOWN condition naming the Incident, a catalog row, a service case with one
         * timeline entry and a warranty stamp.
         */
        fun seedV12(c: SQLiteConnection) {
            asset(c, "a1", "Example Heater", "ACTIVE", warranty = "'2027-03-01'", lead = "30")
            asset(c, "a2", "Example Heater pump", "ARCHIVED", parent = "'a1'")
            asset(c, "a3", "Old Example Heater", "ACTIVE", warranty = "'2025-01-31'", retired = "'2026-02-01'")
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
                "INSERT INTO asset_condition (id,asset_id,condition,occurred_on,occurred_time,tz_id,reason,event_id," +
                    "created_at) VALUES ('cond-1','a1','DOWN','2026-09-20','07:45','UTC','No hot water','e1',90)",
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
                "INSERT INTO deadline_local_delivery (kind,subject_id,announced_hash,announced_boot,updated_at) VALUES " +
                    "('WARRANTY_EXPIRY','a1','0123456789abcdef',7,2000)",
            )
        }

        fun asset(
            c: SQLiteConnection,
            id: String,
            name: String,
            status: String,
            warranty: String = "NULL",
            lead: String = "NULL",
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
                    "'2024-03-01',NULL,129900,'EUR','','',$warranty,'',$retired,$parent,NULL,NULL,'YEAR_ROUND'," +
                    "NULL,NULL,'WORST',NULL,$lead)",
            )
        }
    }
}
