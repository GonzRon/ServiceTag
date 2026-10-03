package com.loosecannon.servicetag.data.room

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MIGRATION_19_20] (#69, C6–C8; R69-1, R69-5): `attachment` gains two nullable CASCADE owner columns by `ALTER`, and
 * `asset_reference` is rebuilt with a nullable `asset_id`, the same two owner columns and one unique `(owner, uri)`
 * index per owner. No table is added and no row is re-owned.
 *
 * The claims, one per case: the attachment's two columns arrive last, nullable, CASCADE and indexed; every v19 file —
 * on an asset with a role and a source, with a role alone, with neither, on an entry — is the same row with two NULLs; every
 * v19 link — with a role, without one, the same URI on two assets — survives the rebuild in every column with two
 * NULLs; the rebuilt table carries Room's four index names; and the migrated file is the same schema as a fresh v20
 * install. The names are fictional.
 */
class Migration19To20Test {

    @Test
    fun attachmentGainsTwoNullableCascadeOwnersAndTheirIndices() = runTest {
        migrating { file, _ ->
            withConnection(file) { m ->
                assertEquals(
                    listOf(
                        "supply_item_id TEXT notnull=0 default=- pk=0",
                        "installed_component_id TEXT notnull=0 default=- pk=0",
                    ),
                    m.columnsOf("attachment").takeLast(V20_ATTACHMENT_COLUMNS.size),
                )
                assertEquals(22, m.columnsOf("attachment").size)
                assertEquals(
                    listOf(
                        "asset_id -> asset(id) ON DELETE CASCADE",
                        "event_id -> asset_event(id) ON DELETE CASCADE",
                        "installed_component_id -> installed_component(id) ON DELETE CASCADE",
                        "supply_item_id -> supply_item(id) ON DELETE CASCADE",
                    ),
                    m.foreignKeysOf("attachment"),
                )
                val indices = m.indexDefinitionsOn("attachment")
                for (column in V20_ATTACHMENT_COLUMNS) {
                    val index = indices.single { it.startsWith("index_attachment_$column ") }
                    assertFalse("$column's index is not unique", index.contains("UNIQUE"))
                }
            }
        }
    }

    @Test
    fun everyV19AttachmentRowIsUnchanged() = runTest {
        migrating { file, before ->
            withConnection(file) { m ->
                for (id in ATTACHMENT_IDS) {
                    assertEquals(
                        id,
                        before.getValue("attachment" to id) + V20_ATTACHMENT_COLUMNS.map { "$it=NULL" },
                        m.rowOf("attachment", id),
                    )
                }
                assertEquals(listOf("${ATTACHMENT_IDS.size}"), m.lines("SELECT COUNT(*) FROM attachment"))
                // the owners, locators, roles and sources the seed wrote are the ones that come back
                assertEquals(
                    listOf(
                        "att-entry|NULL|e1|events/e1/att-entry.jpg|NULL|NULL",
                        "att-plain|a2|NULL|assets/a2/att-plain.pdf|NULL|NULL",
                        "att-saved|a1|NULL|assets/a1/att-saved.pdf|USER_MANUAL|https://example.invalid/ups/manual.pdf",
                        "att-service|a1|NULL|assets/a1/att-service.pdf|SERVICE_MANUAL|NULL",
                    ),
                    m.lines(
                        "SELECT id, asset_id, event_id, storage_locator, document_role, source_uri FROM attachment ORDER BY id",
                    ),
                )
            }
        }
    }

    @Test
    fun everyV19ReferenceRowSurvivesTheRebuildByteForByte() = runTest {
        migrating { file, before ->
            withConnection(file) { m ->
                for (id in REFERENCE_IDS) {
                    assertEquals(
                        id,
                        before.getValue("asset_reference" to id) + V20_REFERENCE_COLUMNS.map { "$it=NULL" },
                        m.rowOf("asset_reference", id),
                    )
                }
                assertEquals(listOf("${REFERENCE_IDS.size}"), m.lines("SELECT COUNT(*) FROM asset_reference"))
                // the stored types too: a copy that rewrote a timestamp as text, or a role as a number, shows here
                assertEquals(
                    REFERENCE_IDS.map { "$it|text|integer|integer" },
                    m.lines("SELECT id, typeof(uri), typeof(created_at), typeof(updated_at) FROM asset_reference ORDER BY id"),
                )
                assertEquals(
                    listOf("r-manual|USER_MANUAL", "r-other-asset|SERVICE_MANUAL", "r-plain|NULL"),
                    m.lines("SELECT id, document_role FROM asset_reference ORDER BY id"),
                )
            }
        }
    }

    @Test
    fun theFourReferenceIndicesCarryRoomsNames() = runTest {
        migrating { file, _ ->
            withConnection(file) { m ->
                assertEquals(
                    listOf(
                        "id TEXT notnull=1 default=- pk=1",
                        "asset_id TEXT notnull=0 default=- pk=0",
                        "kind TEXT notnull=1 default=- pk=0",
                        "uri TEXT notnull=1 default=- pk=0",
                        "display_name TEXT notnull=1 default=- pk=0",
                        "description TEXT notnull=1 default=- pk=0",
                        "scheme TEXT notnull=1 default=- pk=0",
                        "created_at INTEGER notnull=1 default=- pk=0",
                        "updated_at INTEGER notnull=1 default=- pk=0",
                        "document_role TEXT notnull=0 default=- pk=0",
                        "supply_item_id TEXT notnull=0 default=- pk=0",
                        "installed_component_id TEXT notnull=0 default=- pk=0",
                    ),
                    m.columnsOf("asset_reference"),
                )
                assertEquals(
                    listOf(
                        "asset_id -> asset(id) ON DELETE CASCADE",
                        "installed_component_id -> installed_component(id) ON DELETE CASCADE",
                        "supply_item_id -> supply_item(id) ON DELETE CASCADE",
                    ),
                    m.foreignKeysOf("asset_reference"),
                )
                assertEquals(
                    setOf(
                        "sqlite_autoindex_asset_reference_1",
                        "index_asset_reference_asset_id_uri",
                        "index_asset_reference_asset_id",
                        "index_asset_reference_supply_item_id_uri",
                        "index_asset_reference_installed_component_id_uri",
                    ),
                    m.indexNamesOn("asset_reference"),
                )
                val indices = m.indexDefinitionsOn("asset_reference")
                for (owner in listOf("asset_id", "supply_item_id", "installed_component_id")) {
                    val pair = indices.single { it.startsWith("index_asset_reference_${owner}_uri ") }
                    assertTrue("($owner, uri) is unique", pair.contains("CREATE UNIQUE INDEX"))
                }
                assertFalse(
                    "asset_id alone is not unique",
                    indices.single { it.startsWith("index_asset_reference_asset_id ") }.contains("UNIQUE"),
                )
                assertEquals("no table is left behind", emptyList<String>(), m.lines(LEFTOVER_TABLES))
            }
        }
    }

    @Test
    fun theMigratedSchemaEqualsAFreshVersion20() = runTest {
        val fresh = File.createTempFile("servicetag-fresh-20", ".db").also { it.delete() }
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
                        assertEquals("no table is added", V19_ALL_TABLES + V21_TABLES, f.tableNames() - ROOM_INTERNAL)
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
     * Builds a v19 file with [seedV19], reads every seeded row whole, opens it through Room (which runs
     * [MIGRATION_19_20] and validates the result), closes it, and hands the file and the rows on.
     */
    private suspend fun migrating(check: (File, Map<Pair<String, String>, List<String>>) -> Unit) {
        val file = File.createTempFile("servicetag-migrate-19-20", ".db").also { it.delete() }
        try {
            createSchemaVersion(19, file, ::seedV19)
            val before = withConnection(file) { c -> SEEDED.associateWith { (table, id) -> c.rowOf(table, id) } }
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

        const val LEFTOVER_TABLES = "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE '\\_new\\_%' ESCAPE '\\'"

        val ATTACHMENT_IDS = listOf("att-entry", "att-plain", "att-saved", "att-service")
        val REFERENCE_IDS = listOf("r-manual", "r-other-asset", "r-plain")

        val SEEDED: List<Pair<String, String>> =
            ATTACHMENT_IDS.map { "attachment" to it } + REFERENCE_IDS.map { "asset_reference" to it }

        /** The table set the shipped `19.json` builds, which v20 neither grows nor shrinks. */
        val V19_ALL_TABLES: Set<String> by lazy {
            val file = File.createTempFile("servicetag-v19-tables", ".db").also { it.delete() }
            try {
                createSchemaVersion(19, file) {}
                withConnection(file) { it.tableNames() - ROOM_INTERNAL }
            } finally {
                file.delete()
            }
        }

        /**
         * What a schema-19 install can hold where #69's columns land: two assets, a SupplyItem and an installed
         * component (so the new foreign keys have parents to name, though no row names them yet), an entry, a file of
         * each shape — saved from a link with a role and a source, a manual with a role, a plain one, one on the entry
         * — and links with a role, without one, and the same URI on a second asset.
         */
        fun seedV19(c: SQLiteConnection) {
            asset(c, "a1", "Example UPS")
            asset(c, "a2", "Example Generator")
            c.execSQL(
                "INSERT INTO supply_item (id,name,category,manufacturer,model,part_number,preferred_unit,notes," +
                    "archived_at,created_at,updated_at) VALUES " +
                    "('s1','Example 12 V Battery','Batteries','Example Power Co.','EB-12','EB-12-9','ea','',NULL,11,12)",
            )
            c.execSQL(
                "INSERT INTO installed_component (id,asset_id,parent_id,name,supply_id,serial_or_lot,installed_on," +
                    "removed_on,replaces_id,sort_order,notes,created_at,updated_at) VALUES " +
                    "('c1','a1',NULL,'Example Battery Tray','s1','','2026-01-05',NULL,NULL,0,'',21,22)",
            )
            c.execSQL(
                "INSERT INTO asset_event (id,asset_id,kind,title,profile_id,occurred_on,occurred_time,tz_id,notes," +
                    "source,source_ref,created_at,updated_at,schedule_id,occurrence_on,details_pending) VALUES " +
                    "('e1','a1','REPLACEMENT','Replace battery',NULL,'2026-09-01',NULL,'UTC','','MANUAL',NULL," +
                    "301,302,NULL,NULL,0)",
            )
            attachment(c, "att-saved", "'a1'", "NULL", "MANUAL", "assets/a1/att-saved.pdf", "'USER_MANUAL'",
                "'https://example.invalid/ups/manual.pdf','https://example.invalid/ups/manual-v2.pdf',401,'manual.pdf'", 41)
            attachment(c, "att-service", "'a1'", "NULL", "MANUAL", "assets/a1/att-service.pdf",
                "'SERVICE_MANUAL'", "NULL,NULL,NULL,NULL", 42)
            attachment(c, "att-plain", "'a2'", "NULL", "DOCUMENT", "assets/a2/att-plain.pdf", "NULL", "NULL,NULL,NULL,NULL", 43)
            attachment(c, "att-entry", "NULL", "'e1'", "PHOTO", "events/e1/att-entry.jpg", "NULL", "NULL,NULL,NULL,NULL", 44)
            c.execSQL(
                "INSERT INTO asset_reference (id,asset_id,kind,uri,display_name,description,scheme,created_at," +
                    "updated_at,document_role) VALUES " +
                    "('r-manual','a1','WEB_URL','https://example.invalid/ups/manual','Manual','The UPS manual','https',51,52,'USER_MANUAL')," +
                    "('r-plain','a1','NOTE_LINK','obsidian://open?vault=Example','Notes','','obsidian',53,54,NULL)," +
                    "('r-other-asset','a2','WEB_URL','https://example.invalid/ups/manual','Shared manual','','https',55,56,'SERVICE_MANUAL')",
            )
        }

        fun attachment(
            c: SQLiteConnection,
            id: String,
            assetId: String,
            eventId: String,
            kind: String,
            locator: String,
            role: String,
            source: String,
            at: Long,
        ) {
            c.execSQL(
                "INSERT INTO attachment (id,asset_id,event_id,kind,mode,display_name,mime_type,size_bytes,sha256," +
                    "storage_provider,storage_locator,captured_on,notes,created_at,updated_at,document_role,source_uri," +
                    "source_resolved_uri,source_retrieved_at,source_name) VALUES " +
                    "('$id',$assetId,$eventId,'$kind','MANAGED','$id.pdf','application/pdf',${at * 10}," +
                    "'${"b".repeat(64)}','SAF_TREE','$locator',NULL,'',$at,${at + 1},$role,$source)",
            )
        }

        fun asset(c: SQLiteConnection, id: String, name: String) {
            c.execSQL(
                "INSERT INTO asset (id,name,description,category,notes,status,created_at,updated_at,manufacturer," +
                    "model,serial_number,vendor,location,warranty_notes) VALUES " +
                    "('$id','$name','','Appliance','','ACTIVE',1000,1500,'Example Power Co.','EP-1','','','','')",
            )
        }
    }
}
