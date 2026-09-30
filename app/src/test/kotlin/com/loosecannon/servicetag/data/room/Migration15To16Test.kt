package com.loosecannon.servicetag.data.room

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MIGRATION_15_16] (#85, C2; R85-2, R85-3): `attachment` gains four nullable provenance columns —
 * `source_uri`, `source_resolved_uri`, `source_retrieved_at`, `source_name` — with no default, no index
 * and no backfill, so every row a 1.4.x install holds arrives with no source and no timestamp moves.
 *
 * The claims, one per case: every v15 row — assets, an entry, attachments on both owners and in both
 * modes, a succession — reads back identical in every column it had, with the four new columns NULL on
 * the attachments and `updated_at` untouched; the migrated file is the same schema as a fresh v16
 * install; and the rows read back through the Room adapter with no source. The names are fictional.
 */
class Migration15To16Test {

    @Test
    fun everyV15RowSurvivesWithFourNulls() = runTest {
        migrating { file, before ->
            withConnection(file) { c ->
                for (row in SEEDED) {
                    val after = c.rowOf(row.table, row.id, row.key)
                    val expected = if (row.table == "attachment") {
                        before.getValue(row) + V16_ATTACHMENT_COLUMNS.map { "$it=NULL" }
                    } else {
                        before.getValue(row)
                    }
                    assertEquals("$row", expected, after)
                }
                // the timestamps the seed wrote are the timestamps that come back: nothing was touched
                assertEquals(
                    ATTACHMENT_IDS.map { "$it|${it.length + 7}" },
                    c.lines("SELECT id, updated_at FROM attachment ORDER BY id"),
                )
                assertEquals(
                    listOf("${ATTACHMENT_IDS.size}"),
                    c.lines("SELECT COUNT(*) FROM attachment WHERE source_uri IS NULL AND source_resolved_uri IS NULL " +
                        "AND source_retrieved_at IS NULL AND source_name IS NULL"),
                )
            }
        }
    }

    @Test
    fun theMigratedSchemaEqualsAFreshVersion16() = runTest {
        val fresh = File.createTempFile("servicetag-fresh-16", ".db").also { it.delete() }
        try {
            openFresh(fresh).let { db ->
                try {
                    // touching it is what makes Room actually create the file
                    assertEquals(0, db.attachmentDao().count())
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
                        // The four new columns, last, nullable and with no default: nothing is backfilled.
                        assertEquals(
                            listOf(
                                "source_uri TEXT notnull=0 default=- pk=0",
                                "source_resolved_uri TEXT notnull=0 default=- pk=0",
                                "source_retrieved_at INTEGER notnull=0 default=- pk=0",
                                "source_name TEXT notnull=0 default=- pk=0",
                            ),
                            m.columnsOf("attachment").takeLast(V16_ATTACHMENT_COLUMNS.size),
                        )
                        assertEquals(20, m.columnsOf("attachment").size)
                        assertEquals(
                            "no index on the source columns",
                            emptyList<String>(),
                            m.indexDefinitionsOn("attachment").filter { "source_" in it },
                        )
                    }
                }
            }
        } finally {
            fresh.delete()
        }
    }

    /** The migrated rows are ordinary rows: the Room adapter reads each of them with no source. */
    @Test
    fun theMigratedRowsReadBackThroughTheAdapterWithNoSource() = runTest {
        val file = File.createTempFile("servicetag-migrate-15-16", ".db").also { it.delete() }
        try {
            createSchemaVersion(15, file, ::seedV15)
            val db = openMigrated(file)
            try {
                val rows = RoomAttachmentRepository(db.attachmentDao()).all()
                assertEquals(ATTACHMENT_IDS, rows.map { it.id.value }.sorted())
                assertTrue("no source on a migrated row", rows.all { it.source == null })
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
     * Builds a v15 file with [seedV15], snapshots every [SEEDED] row, opens it through Room (which runs
     * [MIGRATION_15_16] and validates the result), closes it, and hands the file and the snapshot on.
     */
    private suspend fun migrating(check: (File, Map<Seeded, List<String>>) -> Unit) {
        val file = File.createTempFile("servicetag-migrate-15-16", ".db").also { it.delete() }
        try {
            createSchemaVersion(15, file, ::seedV15)
            val before = withConnection(file) { c -> SEEDED.associateWith { (t, id, key) -> c.rowOf(t, id, key) } }
            val db = openMigrated(file)
            try {
                // reading through a DAO is what makes Room open the file and run the migration
                db.attachmentDao().count()
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

        /** Every attachment [seedV15] writes, in id order. */
        val ATTACHMENT_IDS = listOf("att-event-photo", "att-manual", "att-reference", "att-receipt")
            .sorted()

        /** Every row [seedV15] writes, as `(table, id, key column)`. */
        val SEEDED = listOf(
            Seeded("asset", "a1"),
            Seeded("asset", "a2"),
            Seeded("asset_event", "e1"),
            Seeded("asset_succession", "s1"),
        ) + ATTACHMENT_IDS.map { Seeded("attachment", it) }

        /**
         * What a #86 install can hold around its attachments: two assets, an entry, a succession, and
         * attachments on both owners — one with a document role, one behind a `SAF_DOCUMENT` locator.
         */
        fun seedV15(c: SQLiteConnection) {
            asset(c, "a1", "Example Heater")
            asset(c, "a2", "Sample Water Heater")
            c.execSQL(
                "INSERT INTO asset_event (id,asset_id,kind,title,profile_id,occurred_on,occurred_time,tz_id,notes," +
                    "source,source_ref,created_at,updated_at,schedule_id,occurrence_on,details_pending) VALUES " +
                    "('e1','a1','MAINTENANCE','Flush tank',NULL,'2026-09-20','07:40','UTC','','MANUAL',NULL,80,81," +
                    "NULL,NULL,0)",
            )
            c.execSQL(
                "INSERT INTO asset_succession (id,predecessor_asset_id,successor_asset_id,replaced_on,created_at) VALUES " +
                    "('s1','a2','a1','2026-09-25',500)",
            )
            attachment(c, "att-manual", "a1", null, "MANUAL", "pdf", role = "USER_MANUAL")
            attachment(c, "att-receipt", "a1", null, "RECEIPT", "pdf", role = "PURCHASE_INVOICE_OR_RECEIPT")
            attachment(c, "att-event-photo", null, "e1", "PHOTO", "jpg", role = null)
            attachment(c, "att-reference", "a2", null, "DOCUMENT", "pdf", role = null, mode = "REFERENCE", provider = "SAF_DOCUMENT")
        }

        fun attachment(
            c: SQLiteConnection,
            id: String,
            assetId: String?,
            eventId: String?,
            kind: String,
            ext: String,
            role: String?,
            mode: String = "MANAGED",
            provider: String = "SAF_TREE",
        ) {
            fun q(v: String?) = v?.let { "'$it'" } ?: "NULL"
            val owner = if (assetId != null) "assets/$assetId" else "events/$eventId"
            c.execSQL(
                "INSERT INTO attachment (id,asset_id,event_id,kind,mode,display_name,mime_type,size_bytes,sha256," +
                    "storage_provider,storage_locator,captured_on,notes,created_at,updated_at,document_role) VALUES " +
                    "('$id',${q(assetId)},${q(eventId)},'$kind','$mode','$id.$ext','application/$ext'," +
                    "${id.length * 100},'${"ab".repeat(32)}','$provider','$owner/$id.$ext','2026-09-01'," +
                    "'',${id.length},${id.length + 7},${q(role)})",
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
