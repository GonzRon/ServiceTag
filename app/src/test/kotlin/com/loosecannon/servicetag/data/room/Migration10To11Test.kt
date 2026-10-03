package com.loosecannon.servicetag.data.room

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MIGRATION_10_11] (#79, C17): `asset` gains one nullable column, `warranty_reminder_lead_days`, with
 * no default and no backfill, and the device-local `deadline_local_delivery` table arrives empty.
 *
 * The claims, one per case: every v10 asset row — a warranty date and notes among them, a child, an
 * archived and a retired one — reads back identical in every column it had, with the lead NULL beside
 * them, and the rows around it (an entry, an attachment with a role, a category) are untouched; the
 * new table is empty, keyed `(kind, subject_id)`, with no foreign key; the migrated file is the same
 * schema as a fresh v11 install; and the rows read back through the Room adapter with no lead. Every
 * expected value is written out by hand. The names are fictional.
 */
class Migration10To11Test {

    @Test
    fun everyAssetRowSurvivesFieldForFieldWithANullLead() = runTest {
        migrating { file, before ->
            withConnection(file) { c ->
                for (row in SEEDED) {
                    val after = c.rowOf(row.table, row.id, row.key).filterNot { it.substringBefore('=') in V16_ATTACHMENT_COLUMNS + V20_ATTACHMENT_COLUMNS }
                    val expected = if (row.table == "asset") {
                        before.getValue(row) + "warranty_reminder_lead_days=NULL"
                    } else {
                        before.getValue(row)
                    }
                    assertEquals("$row", expected, after)
                }
                assertEquals(
                    "every asset, its warranty facts kept, and no lead on any of them",
                    listOf(
                        "a1|2027-03-01|pump and heater only|NULL",
                        "a2|NULL||NULL",
                        "a3|2025-01-31|expired|NULL",
                    ),
                    c.lines("SELECT id, warranty_expires_on, warranty_notes, warranty_reminder_lead_days FROM asset ORDER BY id"),
                )
            }
        }
    }

    @Test
    fun theDeadlineTableArrivesEmptyWithNoForeignKey() = runTest {
        migrating { file, _ ->
            withConnection(file) { c ->
                assertTrue("deadline_local_delivery" in c.tableNames())
                assertEquals(listOf("0"), c.lines("SELECT COUNT(*) FROM deadline_local_delivery"))
                assertEquals(
                    listOf(
                        "kind TEXT notnull=1 default=- pk=1",
                        "subject_id TEXT notnull=1 default=- pk=2",
                        "announced_hash TEXT notnull=1 default=- pk=0",
                        "announced_boot INTEGER notnull=0 default=- pk=0",
                        "updated_at INTEGER notnull=1 default=- pk=0",
                    ),
                    c.columnsOf("deadline_local_delivery"),
                )
                assertEquals(listOf("kind", "subject_id"), c.primaryKeyOf("deadline_local_delivery"))
                assertEquals("no foreign key: the subject is soft", emptyList<String>(), c.foreignKeysOf("deadline_local_delivery"))
            }
        }
    }

    @Test
    fun theMigratedSchemaEqualsAFreshVersion11() = runTest {
        val fresh = File.createTempFile("servicetag-fresh-11", ".db").also { it.delete() }
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
                        // The one new column, last, nullable and with no default: nothing is backfilled.
                        assertEquals(
                            "warranty_reminder_lead_days INTEGER notnull=0 default=- pk=0",
                            m.columnsOf("asset").last(),
                        )
                        assertEquals(30, m.columnsOf("asset").size)
                        // #79b's two case tables follow in the next step of the chain.
                        assertEquals("the one new table", V11_TABLES, m.tableNames() - ROOM_INTERNAL - v10Tables() - V12_TABLES - V13_TABLES - V14_TABLES - V15_TABLES - V18_TABLES - V19_TABLES - V21_TABLES)
                    }
                }
            }
        } finally {
            fresh.delete()
        }
    }

    /** The migrated rows are ordinary rows: the Room adapter reads each of them with no lead. */
    @Test
    fun theMigratedRowsReadBackThroughTheAdapterWithNoLead() = runTest {
        val file = File.createTempFile("servicetag-migrate-10-11", ".db").also { it.delete() }
        try {
            createSchemaVersion(10, file, ::seedV10)
            val db = openMigrated(file)
            try {
                val rows = RoomAssetRepository(db.assetDao()).all()
                assertEquals(listOf("a1", "a2", "a3"), rows.map { it.id.value }.sorted())
                assertTrue("no lead on a migrated row", rows.all { it.warrantyReminderLeadDays == null })
                assertEquals("2027-03-01", rows.single { it.id.value == "a1" }.warrantyExpiresOn)
                assertEquals(emptyList<Any>(), RoomDeadlineLocalDeliveryRepository(db.deadlineLocalDeliveryDao()).all())
            } finally {
                db.close()
            }
        } finally {
            file.delete()
        }
    }

    // --- the harness --------------------------------------------------------------------------

    private data class Seeded(val table: String, val id: String, val key: String = "id")

    /**
     * Builds a v10 file with [seedV10], snapshots every [SEEDED] row and the v10 table names, opens it
     * through Room (which runs [MIGRATION_10_11] and validates the result), closes it, and hands the
     * file and the snapshot on.
     */
    private suspend fun migrating(check: (File, Map<Seeded, List<String>>) -> Unit) {
        val file = File.createTempFile("servicetag-migrate-10-11", ".db").also { it.delete() }
        try {
            createSchemaVersion(10, file, ::seedV10)
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

    /** The tables a v10 install has, read from the exported `10.json` rather than written out. */
    private fun v10Tables(): Set<String> {
        val file = File.createTempFile("servicetag-v10", ".db").also { it.delete() }
        return try {
            createSchemaVersion(10, file) { }
            withConnection(file) { it.tableNames() - ROOM_INTERNAL }
        } finally {
            file.delete()
        }
    }

    private companion object {
        /** Room's own bookkeeping, which is not a table of the schema. */
        val ROOM_INTERNAL = setOf("room_master_table", "android_metadata", "sqlite_sequence")

        /** Every row [seedV10] writes, as `(table, id, key column)`. */
        val SEEDED = listOf(
            Seeded("asset", "a1"),
            Seeded("asset", "a2"),
            Seeded("asset", "a3"),
            Seeded("asset_event", "e1"),
            Seeded("attachment", "att-manual"),
            Seeded("asset_category", "appliance", "`key`"),
        )

        /**
         * What a #67 install can hold: an in-service heater with a warranty date and notes, an archived
         * component under it with no warranty, a retired unit whose warranty has run out, an entry, an
         * attachment carrying a document role, and a catalog row.
         */
        fun seedV10(c: SQLiteConnection) {
            asset(c, "a1", "Example Heater", "ACTIVE", warranty = "'2027-03-01'", notes = "pump and heater only")
            asset(c, "a2", "Example Heater pump", "ARCHIVED", parent = "'a1'")
            asset(c, "a3", "Old Example Heater", "ACTIVE", warranty = "'2025-01-31'", notes = "expired", retired = "'2026-02-01'")
            c.execSQL(
                "INSERT INTO asset_event (id,asset_id,kind,title,profile_id,occurred_on,occurred_time,tz_id,notes," +
                    "source,source_ref,created_at,updated_at,schedule_id,occurrence_on,details_pending) VALUES " +
                    "('e1','a1','MAINTENANCE','Filter change',NULL,'2026-01-16','09:15','UTC','','MANUAL',NULL,80,81," +
                    "NULL,NULL,0)",
            )
            c.execSQL(
                "INSERT INTO attachment (id,asset_id,event_id,kind,mode,display_name,mime_type,size_bytes,sha256," +
                    "storage_provider,storage_locator,captured_on,notes,created_at,updated_at,document_role) VALUES " +
                    "('att-manual','a1',NULL,'MANUAL','MANAGED','att-manual.pdf','application/pdf',1000," +
                    "'${"ab".repeat(32)}','SAF_TREE','assets/a1/att-manual.pdf','2026-09-01','',10,17,'USER_MANUAL')",
            )
            c.execSQL(
                "INSERT INTO asset_category (`key`,display,created_at,updated_at) VALUES ('appliance','Appliance',1000,1000)",
            )
        }

        fun asset(
            c: SQLiteConnection,
            id: String,
            name: String,
            status: String,
            warranty: String = "NULL",
            notes: String = "",
            parent: String = "NULL",
            retired: String = "NULL",
        ) {
            c.execSQL(
                "INSERT INTO asset (id,name,description,category,notes,status,template_key,created_at," +
                    "updated_at,manufacturer,model,serial_number,purchase_on,in_service_on," +
                    "purchase_price_minor,currency,vendor,location,warranty_expires_on,warranty_notes," +
                    "retired_on,parent_asset_id,season_start_mmdd,season_end_mmdd,season_mode," +
                    "blackout_start_mmdd,blackout_end_mmdd,health_aggregation,health_primary_subject_id) VALUES " +
                    "('$id','$name','','Appliance','','$status',NULL,1000,1500,'Northwind','NW-1','SN-$id'," +
                    "'2024-03-01',NULL,129900,'USD','','',$warranty,'$notes',$retired,$parent,NULL,NULL,'YEAR_ROUND'," +
                    "NULL,NULL,'WORST',NULL)",
            )
        }
    }
}
