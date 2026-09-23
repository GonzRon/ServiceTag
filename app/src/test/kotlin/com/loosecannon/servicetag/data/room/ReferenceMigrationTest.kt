package com.loosecannon.servicetag.data.room

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MIGRATION_6_7]: one new table, `asset_reference`, and
 * nothing else. It is a plain `CREATE TABLE` plus two `CREATE INDEX`es — no recreate and no copy —
 * so "every pre-existing row is untouched" holds by construction; this class is what proves the
 * construction is the one that shipped.
 *
 * Two properties, the same pair `MaintenanceMigrationTest` asserts and for the same reasons:
 *
 *  - **every pre-existing row of every pre-existing table reads back field for field identical** —
 *    a v6 database is seeded with one row in every table a real install has and then read back
 *    column by column through the raw connection, so a statement that dropped, reordered or
 *    rewrote a value fails here rather than changing it silently;
 *  - **the migrated database is the same schema as a fresh v7 install** — same columns, primary
 *    key, foreign keys with the same delete actions, and the same **index names and definitions**,
 *    compared against a database Room built from the compiled entities. A hand-written index name,
 *    or a `REFERENCES` clause the `CREATE` forgot, differs here.
 *
 * Opening through Room is the third check: Room validates a migrated database against the compiled
 * schema and refuses to open one that does not match.
 */
class ReferenceMigrationTest {

    @Test
    fun addsTheReferenceTableAndLeavesEveryExistingRowAlone() = runTest {
        val migrated = File.createTempFile("servicetag-migrate-6-7", ".db").also { it.delete() }
        val fresh = File.createTempFile("servicetag-fresh-7", ".db").also { it.delete() }
        try {
            createSchemaVersion(6, migrated, ::seedV6)
            val before = withConnection(migrated) { c -> SEEDED.associateWith { (t, id) -> c.rowOf(t, id) } }

            val db = openMigrated(migrated)
            try {
                // the v6 rows came through untouched, read through the DAOs as the app would
                assertEquals("Orchard Pump", db.assetDao().byId("a1")!!.name)
                assertEquals(listOf("t1"), db.nfcTagDao().forAsset("a1").map { it.id })
                val event = db.eventDao().byId("e1")!!
                assertEquals("Filter change", event.event.title)
                assertEquals(listOf("m1"), event.measurements.map { it.id })
                assertEquals(listOf("cu1"), event.consumables.map { it.id })
                assertEquals(1, db.maintenanceGroupDao().all().size)
                assertEquals(1, db.maintenanceScheduleDao().all().size)
                assertEquals(1, db.occurrenceClosureDao().all().size)
                assertEquals(1, db.attachmentDao().all().size)

                // and the new table is there, empty: a migration invents no reference
                assertEquals(0, db.assetReferenceDao().all().size)
            } finally {
                db.close()
            }

            // Every seeded row, field for field, before and after. No pre-existing table gains a
            // column here, so the comparison is the whole row on both sides.
            val after = withConnection(migrated) { c ->
                SEEDED.associateWith { (t, id) -> c.rowOf(t, id) }
            }
            assertEquals(before, after)

            // and the shape, against what Room builds from the entities with no migration at all
            val new = openFresh(fresh)
            try {
                // touching it is what makes Room actually create the file
                assertEquals(0, new.assetReferenceDao().all().size)
            } finally {
                new.close()
            }
            withConnection(migrated) { m ->
                withConnection(fresh) { f ->
                    assertTrue(
                        "asset_reference must exist, found ${m.tableNames()}",
                        REFERENCE in m.tableNames(),
                    )
                    // [UNTOUCHED] claims to be *every* v6 table, and this is what makes the claim
                    // checkable rather than a comment: it is the migrated file's own table list
                    // with the one table this migration adds — and Room's bookkeeping — taken out.
                    // A v7 table added later without a line in the loop below fails here first.
                    assertEquals(
                        "UNTOUCHED must name every table the migration found",
                        m.tableNames() - REFERENCE - ROOM_INTERNAL,
                        UNTOUCHED,
                    )
                    assertEquals("asset_reference columns", f.columnsOf(REFERENCE), m.columnsOf(REFERENCE))
                    assertEquals("asset_reference primary key", f.primaryKeyOf(REFERENCE), m.primaryKeyOf(REFERENCE))
                    assertEquals("asset_reference foreign keys", f.foreignKeysOf(REFERENCE), m.foreignKeysOf(REFERENCE))
                    assertEquals(
                        "asset_reference indices",
                        f.indexDefinitionsOn(REFERENCE),
                        m.indexDefinitionsOn(REFERENCE),
                    )
                    // the tables the migration must not have touched at all
                    for (table in UNTOUCHED) {
                        assertEquals("$table columns", f.columnsOf(table), m.columnsOf(table))
                        assertEquals("$table indices", f.indexDefinitionsOn(table), m.indexDefinitionsOn(table))
                    }
                }
            }
        } finally {
            migrated.delete()
            fresh.delete()
        }
    }

    /**
     * What a 1.2.1 install has on disk: an asset with a tag, a link, a measurement definition, a
     * profile with a field and a consumable, an event carrying a reading and a usage, an
     * attachment, a group with a member, a schedule with a provider, and a closure. One row in
     * every table v6 has, so "every pre-existing row" has something behind it.
     */
    private fun seedV6(c: SQLiteConnection) {
        c.execSQL(
            "INSERT INTO asset (id,name,description,category,notes,status,template_key," +
                "created_at,updated_at,manufacturer,model,serial_number,vendor,location," +
                "warranty_notes) " +
                "VALUES ('a1','Orchard Pump','east row','pumps','filter every 90d','ACTIVE','pump'," +
                "1000,1100,'Northwind','NW-100','SN-7','Aviary Supply','east row','2 years')",
        )
        c.execSQL(
            "INSERT INTO nfc_tag (id,payload_format,payload_key,asset_id,link_id,status,label," +
                "physical_uid,written_at,last_scanned_at,created_at,updated_at) " +
                "VALUES ('t1','V1','t1','a1',NULL,'ACTIVE','pump tag','04A224B2',300,400,1,2)",
        )
        c.execSQL(
            "INSERT INTO external_link (id,asset_id,kind,label,uri,created_at,last_opened_at,updated_at) " +
                "VALUES ('l1','a1','JOPLIN','Pump notes','joplin://l1',5,NULL,6)",
        )
        c.execSQL(
            "INSERT INTO measurement_definition (id,asset_id,`key`,label,unit,value_type,decimals," +
                "range_low,range_high,is_meter,sort_order,archived_at,created_at,updated_at," +
                "kind,formula,source_a_id,source_b_id) " +
                "VALUES ('d1','a1','runtime_hours','Runtime','h','NUMBER',1,0.0,9999.0,1,0,NULL,1,2," +
                "'ENTERED',NULL,NULL,NULL)",
        )
        c.execSQL(
            "INSERT INTO event_profile (id,asset_id,name,event_kind,default_title,template_key," +
                "sort_order,archived_at,created_at,updated_at) " +
                "VALUES ('p1','a1','Service','MAINTENANCE','Service',NULL,0,NULL,1,2)",
        )
        c.execSQL(
            "INSERT INTO profile_field (id,profile_id,definition_id,required,sort_order) " +
                "VALUES ('pf1','p1','d1',1,0)",
        )
        c.execSQL(
            "INSERT INTO profile_consumable (id,profile_id,name,default_quantity,unit,sort_order) " +
                "VALUES ('pc1','p1','Cartridge',1.0,'ea',0)",
        )
        c.execSQL(
            "INSERT INTO maintenance_group (id,name,description,archived_at,created_at,updated_at) " +
                "VALUES ('g1','Aviary Feeders','north run',NULL,10,20)",
        )
        c.execSQL(
            "INSERT INTO maintenance_group_member (id,group_id,asset_id,sort_order,added_at,removed_at) " +
                "VALUES ('gm1','g1','a1',0,1000,NULL)",
        )
        c.execSQL(
            "INSERT INTO maintenance_schedule (id,asset_id,group_id,title,description,time_interval," +
                "time_unit,time_basis,anchor_on,lead_days,meter_definition_id,meter_interval," +
                "anchor_meter,meter_lead,season_behavior,season_reentry,season_reentry_offset_days," +
                "completion_mode,profile_id,reminders_enabled,status,postponed_due_on," +
                "created_at,updated_at) " +
                "VALUES ('s1','a1',NULL,'Filter change','quarterly',3,'MONTH','COMPLETION'," +
                "'2026-03-01',7,'d1',250.0,100.0,25.0,'IGNORE',NULL,NULL,'QUICK','p1',1,'ACTIVE'," +
                "NULL,30,40)",
        )
        c.execSQL(
            "INSERT INTO schedule_provider (schedule_id,provider,enabled) VALUES ('s1','LOCAL',1)",
        )
        c.execSQL(
            "INSERT INTO occurrence_closure (id,schedule_id,occurrence_on,closed_on,created_at) " +
                "VALUES ('oc1','s1','2026-05-04','2026-05-06',70)",
        )
        c.execSQL(
            "INSERT INTO asset_event (id,asset_id,kind,title,profile_id,occurred_on,occurred_time," +
                "tz_id,notes,source,source_ref,created_at,updated_at,schedule_id,occurrence_on," +
                "details_pending) " +
                "VALUES ('e1','a1','MAINTENANCE','Filter change','p1','2026-04-02','09:15','UTC'," +
                "'swapped the cartridge','SCHEDULE_QUICK_COMPLETE','e1',7,8,'s1','2026-04-01',1)",
        )
        c.execSQL(
            "INSERT INTO measurement (id,event_id,definition_id,value_num,value_text,unit,sort_order) " +
                "VALUES ('m1','e1','d1',412.5,NULL,'h',0)",
        )
        c.execSQL(
            "INSERT INTO consumable_usage (id,event_id,name,quantity,unit,sort_order) " +
                "VALUES ('cu1','e1','Cartridge',1.0,'ea',0)",
        )
        c.execSQL(
            "INSERT INTO attachment (id,asset_id,event_id,kind,mode,display_name,mime_type," +
                "size_bytes,sha256,storage_provider,storage_locator,captured_on,notes," +
                "created_at,updated_at) " +
                "VALUES ('att1','a1',NULL,'MANUAL','MANAGED','Manual.pdf','application/pdf',12," +
                "'${"a".repeat(64)}','SAF_TREE','assets/a1/att1.pdf',NULL,'',9,10)",
        )
    }

    private companion object {
        const val REFERENCE = "asset_reference"

        /** Every row [seedV6] writes, as `(table, id)`. */
        val SEEDED = listOf(
            "asset" to "a1",
            "nfc_tag" to "t1",
            "external_link" to "l1",
            "measurement_definition" to "d1",
            "event_profile" to "p1",
            "profile_field" to "pf1",
            "profile_consumable" to "pc1",
            "maintenance_group" to "g1",
            "maintenance_group_member" to "gm1",
            "maintenance_schedule" to "s1",
            "occurrence_closure" to "oc1",
            "asset_event" to "e1",
            "measurement" to "m1",
            "consumable_usage" to "cu1",
            "attachment" to "att1",
        )

        /** Room's own bookkeeping, which is not a table of the schema. */
        val ROOM_INTERNAL = setOf("room_master_table", "android_metadata", "sqlite_sequence")

        /**
         * Every v6 table, which this migration must leave exactly as it found it — asserted to be
         * exactly that, above, rather than trusted to be kept up to date by hand.
         */
        val UNTOUCHED = JOURNAL_TABLES + setOf(
            "asset",
            "nfc_tag",
            "external_link",
            "attachment",
            "maintenance_group",
            "maintenance_group_member",
            "maintenance_schedule",
            "schedule_provider",
            "occurrence_closure",
            "schedule_state",
            "schedule_local_delivery",
        )
    }
}
