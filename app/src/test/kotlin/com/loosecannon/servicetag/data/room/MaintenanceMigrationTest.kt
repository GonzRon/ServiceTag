package com.loosecannon.servicetag.data.room

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MIGRATION_5_6]: the seven maintenance tables, and the three columns plus two indices that
 * `asset_event` gains with them.
 *
 * Because `asset_event` is **altered** and not only added to, this is not a pure table addition,
 * and the two things that could go wrong are different from [Migration4To5Test]'s. So two
 * properties are asserted, and they are the requirement:
 *
 *  - **every pre-existing row of every pre-existing table reads back field for field identical** —
 *    a v5 database is seeded with what a real install has, migrated, and then read column by column
 *    through the raw connection rather than through the DAOs, so a recreate that dropped a column,
 *    reordered one or forgot a value fails here rather than silently changing a value;
 *  - **the migrated database is the same schema as a fresh v6 install** — same columns with the
 *    same types, nullability and defaults, same primary keys, same foreign keys with the same
 *    delete actions, and the same index names and definitions, compared against a database Room
 *    built from the compiled entities. An `ADD COLUMN` that did not register the foreign key, or a
 *    hand-written index name, differs here.
 *
 * Opening through Room is itself the third check: Room validates a migrated database against the
 * compiled schema and refuses to open one that does not match. See [openMigrated] for why the proof
 * is assembled by hand rather than with `SQLiteDriverMigrationTestHelper`.
 */
class MaintenanceMigrationTest {

    @Test
    fun addsTheMaintenanceTablesAndLeavesEveryExistingRowAlone() = runTest {
        val migrated = File.createTempFile("servicetag-migrate-5-6", ".db").also { it.delete() }
        val fresh = File.createTempFile("servicetag-fresh-6", ".db").also { it.delete() }
        try {
            createSchemaVersion(5, migrated, ::seedV5)
            val before = withConnection(migrated) { c -> SEEDED.associateWith { (t, id) -> c.rowOf(t, id) } }

            val db = openMigrated(migrated)
            try {
                // the v5 rows came through untouched, read through the DAOs as the app would
                assertEquals("Orchard Pump", db.assetDao().byId("a1")!!.name)
                assertEquals(listOf("t1"), db.nfcTagDao().forAsset("a1").map { it.id })
                val event = db.eventDao().byId("e1")!!
                assertEquals("Filter change", event.event.title)
                assertEquals(listOf("m1"), event.measurements.map { it.id })
                assertEquals(listOf("cu1"), event.consumables.map { it.id })
                // and the three new columns are at their defaults on a row written before they existed
                assertEquals(null, event.event.scheduleId)
                assertEquals(null, event.event.occurrenceOn)
                assertEquals(false, event.event.detailsPending)

                // the seven new tables are there, empty, and nothing invented a schedule
                assertEquals(0, db.maintenanceGroupDao().all().size)
                assertEquals(0, db.maintenanceScheduleDao().all().size)
                assertEquals(0, db.occurrenceClosureDao().all().size)
                assertEquals(0, db.scheduleStateDao().all().size)
                assertEquals(0, db.scheduleLocalDeliveryDao().all().size)
            } finally {
                db.close()
            }

            // Every seeded row, field for field, before and after — restricted to the columns the
            // row had *before*, because `asset_event` legitimately gains three of them here and the
            // claim is about the values that were already on disk, not about the column list. That
            // the three arrive at their defaults is asserted through the DAO above.
            val after = withConnection(migrated) { c ->
                SEEDED.associateWith { key ->
                    val names = before.getValue(key).map { it.substringBefore('=') }.toSet()
                    c.rowOf(key.first, key.second).filter { it.substringBefore('=') in names }
                }
            }
            assertEquals(before, after)

            // and the shape, against what Room builds from the entities with no migration at all
            val new = openFresh(fresh)
            try {
                // touching it is what makes Room actually create the file
                assertEquals(0, new.maintenanceGroupDao().all().size)
            } finally {
                new.close()
            }
            withConnection(migrated) { m ->
                withConnection(fresh) { f ->
                    assertTrue(
                        "the new tables must exist, found ${m.tableNames()}",
                        m.tableNames().containsAll(V6_TABLES),
                    )
                    for (table in V6_TABLES + "asset_event") {
                        assertEquals("$table columns", f.columnsOf(table), m.columnsOf(table))
                        assertEquals("$table primary key", f.primaryKeyOf(table), m.primaryKeyOf(table))
                        assertEquals("$table foreign keys", f.foreignKeysOf(table), m.foreignKeysOf(table))
                        assertEquals("$table indices", f.indexDefinitionsOn(table), m.indexDefinitionsOn(table))
                    }
                    // the tables the migration must not have touched at all
                    for (table in JOURNAL_TABLES + "asset" + "nfc_tag" + "external_link" + "attachment") {
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
     * What a 1.1.0 install has on disk: an asset with a tag, a link, a measurement definition, a
     * profile with a field and a consumable, an event carrying a reading and a usage, and an
     * attachment. One row in every table v5 has, so "every pre-existing row" has something behind
     * it.
     */
    private fun seedV5(c: SQLiteConnection) {
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
            "INSERT INTO asset_event (id,asset_id,kind,title,profile_id,occurred_on,occurred_time," +
                "tz_id,notes,source,source_ref,created_at,updated_at) " +
                "VALUES ('e1','a1','MAINTENANCE','Filter change','p1','2026-04-02','09:15','UTC'," +
                "'swapped the cartridge','MANUAL','e1',7,8)",
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
        /** Every row [seedV5] writes, as `(table, id)`. */
        val SEEDED = listOf(
            "asset" to "a1",
            "nfc_tag" to "t1",
            "external_link" to "l1",
            "measurement_definition" to "d1",
            "event_profile" to "p1",
            "profile_field" to "pf1",
            "profile_consumable" to "pc1",
            "asset_event" to "e1",
            "measurement" to "m1",
            "consumable_usage" to "cu1",
            "attachment" to "att1",
        )

        val V6_TABLES = setOf(
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
