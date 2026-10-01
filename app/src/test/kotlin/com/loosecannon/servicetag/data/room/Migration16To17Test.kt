package com.loosecannon.servicetag.data.room

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MIGRATION_16_17] (#91, C5; R91-6): `asset_reference` gains one nullable column, `document_role`,
 * with no default, no index and no backfill, so every reference a 1.5.0 install holds arrives with no
 * role — whatever its kind, name or URI says — and no timestamp moves.
 *
 * The claims, one per case: every v16 row — two assets and two references of each kind, one web link
 * named "User manual" among them — reads back identical in every column it had, with `document_role`
 * NULL on the references and `updated_at` untouched; the migrated file is the same schema as a fresh
 * v17 install; and the rows read back through the Room adapter with no role. The names are fictional.
 */
class Migration16To17Test {

    @Test
    fun everyV16ReferenceSurvivesWithNoRole() = runTest {
        migrating { file, before ->
            withConnection(file) { c ->
                for (row in SEEDED) {
                    val after = c.rowOf(row.table, row.id, row.key)
                    val expected = if (row.table == "asset_reference") {
                        before.getValue(row) + V17_REFERENCE_COLUMNS.map { "$it=NULL" }
                    } else {
                        before.getValue(row)
                    }
                    assertEquals("$row", expected, after)
                }
                // the timestamps the seed wrote are the timestamps that come back: nothing was touched
                assertEquals(
                    REFERENCE_IDS.map { "$it|${it.length + 7}" },
                    c.lines("SELECT id, updated_at FROM asset_reference ORDER BY id"),
                )
                assertEquals(
                    listOf("${REFERENCE_IDS.size}"),
                    c.lines("SELECT COUNT(*) FROM asset_reference WHERE document_role IS NULL"),
                )
            }
        }
    }

    @Test
    fun theMigratedSchemaEqualsAFreshVersion17() = runTest {
        val fresh = File.createTempFile("servicetag-fresh-17", ".db").also { it.delete() }
        try {
            openFresh(fresh).let { db ->
                try {
                    // touching it is what makes Room actually create the file
                    assertEquals(0, db.assetReferenceDao().all().size)
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
                            listOf("document_role TEXT notnull=0 default=- pk=0"),
                            m.columnsOf("asset_reference").takeLast(V17_REFERENCE_COLUMNS.size),
                        )
                        assertEquals(10, m.columnsOf("asset_reference").size)
                        assertEquals(
                            "no index on the role column",
                            emptyList<String>(),
                            m.indexDefinitionsOn("asset_reference").filter { "document_role" in it },
                        )
                    }
                }
            }
        } finally {
            fresh.delete()
        }
    }

    /** The migrated rows are ordinary rows: the Room adapter reads each of them, its kind kept, with no role. */
    @Test
    fun theMigratedRowsReadBackThroughTheAdapterWithNoRole() = runTest {
        val file = File.createTempFile("servicetag-migrate-16-17", ".db").also { it.delete() }
        try {
            createSchemaVersion(16, file, ::seedV16)
            val db = openMigrated(file)
            try {
                val rows = RoomReferenceRepository(db.assetReferenceDao()).all()
                assertEquals(REFERENCE_KINDS, rows.associate { it.id.value to it.kind.name })
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
     * Builds a v16 file with [seedV16], snapshots every [SEEDED] row, opens it through Room (which runs
     * [MIGRATION_16_17] and validates the result), closes it, and hands the file and the snapshot on.
     */
    private suspend fun migrating(check: (File, Map<Seeded, List<String>>) -> Unit) {
        val file = File.createTempFile("servicetag-migrate-16-17", ".db").also { it.delete() }
        try {
            createSchemaVersion(16, file, ::seedV16)
            val before = withConnection(file) { c -> SEEDED.associateWith { (t, id, key) -> c.rowOf(t, id, key) } }
            val db = openMigrated(file)
            try {
                // reading through a DAO is what makes Room open the file and run the migration
                db.assetReferenceDao().all()
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

        /** Every reference [seedV16] writes, by id, with the kind it was stored as. */
        val REFERENCE_KINDS = mapOf(
            "ref-web-https" to "WEB_URL",
            "ref-web-http" to "WEB_URL",
            "ref-note-joplin" to "NOTE_LINK",
            "ref-note-obsidian" to "NOTE_LINK",
            "ref-other-ftp" to "OTHER",
            "ref-other-geo" to "OTHER",
        )

        /** Every reference [seedV16] writes, in id order. */
        val REFERENCE_IDS = REFERENCE_KINDS.keys.sorted()

        /** Every row [seedV16] writes, as `(table, id, key column)`. */
        val SEEDED = listOf(
            Seeded("asset", "a1"),
            Seeded("asset", "a2"),
        ) + REFERENCE_IDS.map { Seeded("asset_reference", it) }

        /**
         * What a 1.5.0 install can hold in its references: two of each kind across two assets — the
         * https one named "User manual" and pointing at a file of that name, so a migration that read a
         * role off the name, the URI or the kind would show here.
         */
        fun seedV16(c: SQLiteConnection) {
            asset(c, "a1", "Example Water Heater")
            asset(c, "a2", "Sample Mower")
            reference(c, "ref-web-https", "a1", "WEB_URL", "https://manuals.example.invalid/heater/user-manual.pdf", "User manual", "https")
            reference(c, "ref-web-http", "a2", "WEB_URL", "http://manuals.example.invalid/mower/parts", "Parts lookup", "http")
            reference(c, "ref-note-joplin", "a1", "NOTE_LINK", "joplin://x-callback-url/openNote?id=example", "Service manual", "joplin")
            reference(c, "ref-note-obsidian", "a2", "NOTE_LINK", "obsidian://open?vault=example&file=mower", "Mower notes", "obsidian")
            reference(c, "ref-other-ftp", "a1", "OTHER", "ftp://files.example.invalid/heater.txt", "Invoice", "ftp")
            reference(c, "ref-other-geo", "a2", "OTHER", "geo:0,0?q=example", "Where it is", "geo")
        }

        fun reference(
            c: SQLiteConnection,
            id: String,
            assetId: String,
            kind: String,
            uri: String,
            name: String,
            scheme: String,
        ) {
            c.execSQL(
                "INSERT INTO asset_reference (id,asset_id,kind,uri,display_name,description,scheme,created_at," +
                    "updated_at) VALUES ('$id','$assetId','$kind','$uri','$name','about $id','$scheme'," +
                    "${id.length},${id.length + 7})",
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
