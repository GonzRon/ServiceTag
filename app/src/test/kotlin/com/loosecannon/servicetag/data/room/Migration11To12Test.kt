package com.loosecannon.servicetag.data.room

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.CaseCoverage
import com.loosecannon.servicetag.core.model.CaseStatus
import com.loosecannon.servicetag.core.model.CaseType
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.ServiceCase
import com.loosecannon.servicetag.core.model.ServiceCaseEntry
import com.loosecannon.servicetag.core.model.ServiceCaseEntryId
import com.loosecannon.servicetag.core.model.ServiceCaseId
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MIGRATION_11_12] (#79, C17; R79-1): two new tables, and nothing existing moves. `service_case` hangs
 * off `asset` and `service_case_entry` off `service_case`, each by a CASCADE foreign key on an indexed
 * column; a case's two event links are soft, so neither carries a foreign key.
 *
 * The claims, one per case: every v11 row — assets with and without a warranty lead, an entry, an
 * attachment with a role, a condition, a category and a device-local deadline stamp — reads back
 * identical in every column; the two tables arrive empty in the shape written out here by hand; an
 * asset's delete on the migrated file takes its cases and their entries and nothing of another asset's;
 * and the migrated file is the same schema as a fresh v12 install. The names are fictional.
 */
class Migration11To12Test {

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
    fun theCaseTablesArriveEmpty() = runTest {
        migrating { file, _ ->
            withConnection(file) { c ->
                assertEquals(listOf("0"), c.lines("SELECT COUNT(*) FROM service_case"))
                assertEquals(listOf("0"), c.lines("SELECT COUNT(*) FROM service_case_entry"))
                assertEquals(
                    listOf(
                        "id TEXT notnull=1 default=- pk=1",
                        "asset_id TEXT notnull=1 default=- pk=0",
                        "title TEXT notnull=1 default=- pk=0",
                        "type TEXT notnull=1 default=- pk=0",
                        "opened_on TEXT notnull=1 default=- pk=0",
                        "closed_on TEXT notnull=0 default=- pk=0",
                        "provider TEXT notnull=1 default=- pk=0",
                        "contact TEXT notnull=1 default=- pk=0",
                        "case_ref TEXT notnull=1 default=- pk=0",
                        "coverage TEXT notnull=1 default=- pk=0",
                        "status TEXT notnull=1 default=- pk=0",
                        "outbound_tracking TEXT notnull=1 default=- pk=0",
                        "outbound_carrier TEXT notnull=1 default=- pk=0",
                        "return_tracking TEXT notnull=1 default=- pk=0",
                        "return_carrier TEXT notnull=1 default=- pk=0",
                        "cost_minor INTEGER notnull=0 default=- pk=0",
                        "currency TEXT notnull=0 default=- pk=0",
                        "notes TEXT notnull=1 default=- pk=0",
                        "incident_event_id TEXT notnull=0 default=- pk=0",
                        "resolution_event_id TEXT notnull=0 default=- pk=0",
                        "created_at INTEGER notnull=1 default=- pk=0",
                        "updated_at INTEGER notnull=1 default=- pk=0",
                    ),
                    c.columnsOf("service_case"),
                )
                assertEquals(
                    listOf(
                        "id TEXT notnull=1 default=- pk=1",
                        "case_id TEXT notnull=1 default=- pk=0",
                        "occurred_on TEXT notnull=1 default=- pk=0",
                        "occurred_time TEXT notnull=0 default=- pk=0",
                        "tz_id TEXT notnull=1 default=- pk=0",
                        "note TEXT notnull=1 default=- pk=0",
                        "status TEXT notnull=0 default=- pk=0",
                        "created_at INTEGER notnull=1 default=- pk=0",
                    ),
                    c.columnsOf("service_case_entry"),
                )
                assertEquals(
                    "the asset owns its cases; the event links are soft",
                    listOf("asset_id -> asset(id) ON DELETE CASCADE"),
                    c.foreignKeysOf("service_case"),
                )
                assertEquals(listOf("case_id -> service_case(id) ON DELETE CASCADE"), c.foreignKeysOf("service_case_entry"))
                assertEquals(
                    listOf("index_service_case_asset_id | CREATE INDEX `index_service_case_asset_id` ON `service_case` (`asset_id`)"),
                    c.indexDefinitionsOn("service_case").filterNot { it.startsWith("sqlite_autoindex") },
                )
                assertEquals(
                    listOf(
                        "index_service_case_entry_case_id | " +
                            "CREATE INDEX `index_service_case_entry_case_id` ON `service_case_entry` (`case_id`)",
                    ),
                    c.indexDefinitionsOn("service_case_entry").filterNot { it.startsWith("sqlite_autoindex") },
                )
            }
        }
    }

    /** Through the Room adapters on the migrated file, so the CASCADE the migration declared is the one that runs. */
    @Test
    fun deletingAnAssetCascadesItsCasesAndTheirEntries() = runTest {
        val file = File.createTempFile("servicetag-migrate-11-12", ".db").also { it.delete() }
        try {
            createSchemaVersion(11, file, ::seedV11)
            val db = openMigrated(file)
            try {
                val cases = RoomServiceCaseRepository(db.serviceCaseDao())
                val entries = RoomServiceCaseEntryRepository(db.serviceCaseEntryDao())
                // a3 is the standalone retired unit: a1 has a child, which the parent key would refuse.
                cases.upsert(caseOf("c1", "a3"))
                cases.upsert(caseOf("c2", "a3"))
                cases.upsert(caseOf("c3", "a1"))
                entries.insert(entryOf("n1", "c1"))
                entries.insert(entryOf("n2", "c2"))
                entries.insert(entryOf("n3", "c3"))

                RoomAssetRepository(db.assetDao()).delete(AssetId("a3"))

                assertEquals(listOf("c3"), cases.all().map { it.id.value })
                assertEquals(listOf("n3"), entries.all().map { it.id.value })
                assertEquals("the event the cases named is another asset's, and stays", 1, db.eventDao().all().size)
            } finally {
                db.close()
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun theMigratedSchemaEqualsAFreshVersion12() = runTest {
        val fresh = File.createTempFile("servicetag-fresh-12", ".db").also { it.delete() }
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
                        assertEquals("the two new tables", V12_TABLES, m.tableNames() - ROOM_INTERNAL - v11Tables() - V13_TABLES - V14_TABLES - V15_TABLES - V18_TABLES - V19_TABLES)
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
     * Builds a v11 file with [seedV11], snapshots every [SEEDED] row, opens it through Room (which runs
     * [MIGRATION_11_12] and validates the result), closes it, and hands the file and the snapshot on.
     */
    private suspend fun migrating(check: (File, Map<Seeded, List<String>>) -> Unit) {
        val file = File.createTempFile("servicetag-migrate-11-12", ".db").also { it.delete() }
        try {
            createSchemaVersion(11, file, ::seedV11)
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

    /** The tables a v11 install has, read from the exported `11.json` rather than written out. */
    private fun v11Tables(): Set<String> {
        val file = File.createTempFile("servicetag-v11", ".db").also { it.delete() }
        return try {
            createSchemaVersion(11, file) { }
            withConnection(file) { it.tableNames() - ROOM_INTERNAL }
        } finally {
            file.delete()
        }
    }

    private fun caseOf(id: String, assetId: String) = ServiceCase(
        id = ServiceCaseId(id), assetId = AssetId(assetId), title = "Example Heater claim $id", type = CaseType.REPAIR,
        openedOn = "2026-09-20", closedOn = null, provider = "Northwind Service", contact = "", caseRef = "RMA-0001",
        coverage = CaseCoverage.UNKNOWN, status = CaseStatus.OPEN, outboundTracking = "", outboundCarrier = "",
        returnTracking = "", returnCarrier = "", costMinor = null, currency = null, notes = "",
        incidentEventId = EventId("e1"), resolutionEventId = EventId("e-gone"), createdAt = 100L, updatedAt = 100L,
    )

    private fun entryOf(id: String, caseId: String) = ServiceCaseEntry(
        ServiceCaseEntryId(id), ServiceCaseId(caseId), "2026-09-21", null, "UTC", "Courier booked", null, 200L,
    )

    private companion object {
        /** Room's own bookkeeping, which is not a table of the schema. */
        val ROOM_INTERNAL = setOf("room_master_table", "android_metadata", "sqlite_sequence")

        /** Every row [seedV11] writes, as `(table, id, key column)`. */
        val SEEDED = listOf(
            Seeded("asset", "a1"),
            Seeded("asset", "a2"),
            Seeded("asset", "a3"),
            Seeded("asset_event", "e1"),
            Seeded("attachment", "att-manual"),
            Seeded("asset_condition", "cond-1"),
            Seeded("asset_category", "appliance", "`key`"),
            Seeded("deadline_local_delivery", "a1", "subject_id"),
        )

        /**
         * What a #79a install can hold: an in-service heater with a warranty date and a lead, an archived
         * component under it, a retired unit whose warranty has run out, its Incident, an attachment with a
         * document role, a DOWN condition naming the Incident, a catalog row and a warranty stamp.
         */
        fun seedV11(c: SQLiteConnection) {
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
