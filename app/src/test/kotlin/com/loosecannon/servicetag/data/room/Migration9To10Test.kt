package com.loosecannon.servicetag.data.room

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MIGRATION_9_10] (#67, C3): `attachment` gains one nullable column, `document_role`, with no
 * default and no backfill — so every row a 1.4.x or #74 install holds arrives with no role.
 *
 * The claims, one per case: every v9 attachment row — all seven kinds, both owners, both modes,
 * both providers, a null `captured_on` among them — reads back identical in every column it had, with
 * `document_role` NULL beside them, and the rows around it (an asset, an entry, a category) are
 * untouched; the migrated file is the same schema as a fresh v10 install; and the rows read back
 * through the Room adapter with no role. Every expected value is written out by hand.
 */
class Migration9To10Test {

    @Test
    fun everyAttachmentRowSurvivesWithANullRole() = runTest {
        migrating { file, before ->
            withConnection(file) { c ->
                for (row in SEEDED) {
                    // The chain runs on to v16: v11's one asset column `Migration10To11Test` owns, v16's four attachment columns `Migration15To16Test` owns.
                    val after = c.rowOf(row.table, row.id, row.key).filterNot { it.substringBefore('=') in V11_ASSET_COLUMNS + V16_ATTACHMENT_COLUMNS }
                    val expected = if (row.table == "attachment") before.getValue(row) + "document_role=NULL" else before.getValue(row)
                    assertEquals("$row", expected, after)
                }
                assertEquals(
                    "every attachment, and no role on any of them",
                    ATTACHMENT_IDS.map { "$it|NULL" },
                    c.lines("SELECT id, document_role FROM attachment ORDER BY id"),
                )
            }
        }
    }

    @Test
    fun theMigratedSchemaEqualsAFreshVersion10() = runTest {
        val fresh = File.createTempFile("servicetag-fresh-10", ".db").also { it.delete() }
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
                        // The one new column, last, nullable and with no default: nothing is backfilled.
                        assertEquals(
                            "document_role TEXT notnull=0 default=- pk=0",
                            m.columnsOf("attachment").dropLast(V16_ATTACHMENT_COLUMNS.size).last(),
                        )
                        assertEquals(20, m.columnsOf("attachment").size)
                    }
                }
            }
        } finally {
            fresh.delete()
        }
    }

    /** The migrated rows are ordinary rows: the Room adapter reads each of them with no role. */
    @Test
    fun theMigratedRowsReadBackThroughTheAdapterWithNoRole() = runTest {
        val file = File.createTempFile("servicetag-migrate-9-10", ".db").also { it.delete() }
        try {
            createSchemaVersion(9, file, ::seedV9)
            val db = openMigrated(file)
            try {
                val rows = RoomAttachmentRepository(db.attachmentDao()).all()
                assertEquals(ATTACHMENT_IDS, rows.map { it.id.value }.sorted())
                assertTrue("no role on a migrated row", rows.all { it.role == null })
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
     * Builds a v9 file with [seedV9], snapshots every [SEEDED] row, opens it through Room (which
     * runs [MIGRATION_9_10] and validates the result), closes it, and hands the file and the
     * snapshot on.
     */
    private suspend fun migrating(check: (File, Map<Seeded, List<String>>) -> Unit) {
        val file = File.createTempFile("servicetag-migrate-9-10", ".db").also { it.delete() }
        try {
            createSchemaVersion(9, file, ::seedV9)
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

        /** Every attachment [seedV9] writes, in id order. */
        val ATTACHMENT_IDS = listOf(
            "att-document", "att-label", "att-manual", "att-other", "att-photo", "att-receipt",
            "att-reference", "att-warranty",
        )

        /** Every row [seedV9] writes, as `(table, id, key column)`. */
        val SEEDED = listOf(
            Seeded("asset", "a1"),
            Seeded("asset_event", "e1"),
            Seeded("asset_category", "appliance", "`key`"),
        ) + ATTACHMENT_IDS.map { Seeded("attachment", it) }

        /**
         * What a #74 install can hold around its attachments: one asset, one entry on it, one catalog
         * row, and an attachment of every kind — six on the asset, one on the entry, a `REFERENCE`
         * row behind a `SAF_DOCUMENT` locator, a null `captured_on` and a non-empty note among them.
         */
        fun seedV9(c: SQLiteConnection) {
            c.execSQL(
                "INSERT INTO asset (id,name,description,category,notes,status,template_key,created_at," +
                    "updated_at,manufacturer,model,serial_number,purchase_on,in_service_on," +
                    "purchase_price_minor,currency,vendor,location,warranty_expires_on,warranty_notes," +
                    "retired_on,parent_asset_id,season_start_mmdd,season_end_mmdd,season_mode," +
                    "blackout_start_mmdd,blackout_end_mmdd,health_aggregation,health_primary_subject_id) VALUES " +
                    "('a1','Hot tub','','Appliance','','ACTIVE',NULL,1000,1500,'Northwind','NW-1','SN-1'," +
                    "'2024-03-01',NULL,129900,'USD','','',NULL,'',NULL,NULL,NULL,NULL,'YEAR_ROUND',NULL,NULL," +
                    "'WORST',NULL)",
            )
            c.execSQL(
                "INSERT INTO asset_event (id,asset_id,kind,title,profile_id,occurred_on,occurred_time,tz_id,notes," +
                    "source,source_ref,created_at,updated_at,schedule_id,occurrence_on,details_pending) VALUES " +
                    "('e1','a1','MAINTENANCE','Filter change',NULL,'2026-01-16','09:15','UTC','','MANUAL',NULL,80,81," +
                    "NULL,NULL,0)",
            )
            c.execSQL(
                "INSERT INTO asset_category (`key`,display,created_at,updated_at) VALUES ('appliance','Appliance',1000,1000)",
            )
            attachment(c, "att-photo", "PHOTO", "image/jpeg", "jpg", capturedOn = "2026-09-01")
            attachment(c, "att-label", "LABEL_PHOTO", "image/png", "png", capturedOn = "2026-09-02")
            attachment(c, "att-receipt", "RECEIPT", "application/pdf", "pdf", capturedOn = "2024-03-01", notes = "paid in full")
            attachment(c, "att-manual", "MANUAL", "application/pdf", "pdf", capturedOn = null)
            attachment(c, "att-warranty", "WARRANTY", "application/pdf", "pdf", capturedOn = "2024-03-02")
            attachment(c, "att-document", "DOCUMENT", "text/plain", "txt", capturedOn = "2026-09-03", eventId = "e1")
            attachment(c, "att-other", "OTHER", "application/zip", "zip", capturedOn = "2026-09-04")
            attachment(
                c, "att-reference", "DOCUMENT", "application/pdf", "pdf", capturedOn = "2026-09-05",
                mode = "REFERENCE", provider = "SAF_DOCUMENT",
            )
        }

        fun attachment(
            c: SQLiteConnection,
            id: String,
            kind: String,
            mime: String,
            ext: String,
            capturedOn: String?,
            notes: String = "",
            eventId: String? = null,
            mode: String = "MANAGED",
            provider: String = "SAF_TREE",
        ) {
            fun q(v: String?) = v?.let { "'$it'" } ?: "NULL"
            val owner = if (eventId == null) "assets/a1" else "events/$eventId"
            c.execSQL(
                "INSERT INTO attachment (id,asset_id,event_id,kind,mode,display_name,mime_type,size_bytes,sha256," +
                    "storage_provider,storage_locator,captured_on,notes,created_at,updated_at) VALUES " +
                    "('$id',${q(if (eventId == null) "a1" else null)},${q(eventId)},'$kind','$mode','$id.$ext','$mime'," +
                    "${id.length * 100},'${"ab".repeat(32)}','$provider','$owner/$id.$ext',${q(capturedOn)}," +
                    "'$notes',${id.length},${id.length + 7})",
            )
        }
    }
}
