package com.loosecannon.servicetag.data.room

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import kotlinx.coroutines.test.runTest
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MIGRATION_3_4]: the table-recreate that gives `asset` its 2B-2 record and its self-referencing
 * `parent_asset_id` (spec §4, §5). A v3 database is built from the exported `3.json`, seeded with
 * what a 2B-1 install would really have — an asset, a tag bound to it, a definition, an event with
 * a reading — and then opened through Room, which runs the migration and validates the result
 * against the compiled v4 schema. See [openMigrated] for why the proof is assembled this way.
 *
 * `asset` is the table everything else hangs off, so what is at risk in recreating it is not only
 * its own rows but every row that names one. That is what is asserted: the asset comes back whole,
 * the new columns arrive at their "not filled in" values, `parent_asset_id` is null (nothing could
 * have had a parent before this version), and the tag and the journal row still resolve to it.
 */
class Migration3To4Test {

    @Test
    fun migratesAndKeepsEverythingThatPointsAtAnAsset() = runTest {
        val file = File.createTempFile("servicetag-migrate-3-4", ".db").also { it.delete() }
        try {
            createSchemaVersion(3, file, ::seedPhase2b1)

            val db = openMigrated(file)
            try {
                val asset = db.assetDao().byId("a1")!!
                assertEquals("Hot tub", asset.name)
                assertEquals("basement", asset.description)
                assertEquals("ACTIVE", asset.status)
                assertEquals("hot_tub", asset.templateKey)
                assertEquals(1_000L, asset.createdAt)

                // The six new NOT NULL text columns arrive empty, not null: "" is how this schema
                // spells "never filled in", and it is what the domain's defaults expect.
                assertEquals("", asset.manufacturer)
                assertEquals("", asset.model)
                assertEquals("", asset.serialNumber)
                assertEquals("", asset.vendor)
                assertEquals("", asset.location)
                assertEquals("", asset.warrantyNotes)

                // Everything nullable arrives null, `parent_asset_id` included: no row could have
                // had a parent before there was a column to put one in.
                assertNull(asset.purchaseOn)
                assertNull(asset.inServiceOn)
                assertNull(asset.purchasePriceMinor)
                assertNull(asset.currency)
                assertNull(asset.warrantyExpiresOn)
                assertNull(asset.retiredOn)
                assertNull(asset.parentAssetId)
                assertNull(asset.seasonStartMmdd)
                assertNull(asset.seasonEndMmdd)

                // And the domain reads that back as an active, un-retired, un-parented asset.
                val domain = RoomAssetRepository(db.assetDao()).get(AssetId("a1"))!!
                assertEquals(AssetStatus.ACTIVE, domain.status)
                assertNull(domain.retiredOn)
                assertNull(domain.parentAssetId)

                // The rows that name the asset survive the DROP and RENAME, through their foreign
                // key columns and not merely as loose strings.
                assertEquals(listOf("t1"), db.nfcTagDao().forAsset("a1").map { it.id })
                assertEquals(listOf("d-ph"), db.definitionDao().forAsset("a1").map { it.id })
                assertEquals(listOf("e1"), db.eventDao().forAsset("a1").map { it.event.id })
                assertEquals(1, db.eventDao().countMeasurementsFor("d-ph"))
            } finally {
                db.close()
            }

            withConnection(file) { c ->
                assertTrue(
                    "the recreated asset table must leave the journal alone",
                    c.tableNames().containsAll(JOURNAL_TABLES),
                )
                assertTrue("the scaffolding table must be gone", "_new_asset" !in c.tableNames())
                // The chain now runs on to v8, whose five asset columns `Migration7To8Test` owns.
                assertEquals(
                    V4_NEW_ASSET_COLUMNS,
                    c.columnNamesOf("asset") - V3_ASSET_COLUMNS - V8_NEW_ASSET_COLUMNS,
                )
                assertTrue(
                    "v4's indexes must exist on the new table, found ${c.indexNamesOn("asset")}",
                    c.indexNamesOn("asset").containsAll(V4_ASSET_INDEXES),
                )
            }
        } finally {
            file.delete()
        }
    }

    /**
     * A v3 install with a `RETIRED` status on disk. No shipped code path ever wrote one — the enum
     * constant existed, nothing used it — so this is the hand-edited-database case, and the
     * migration maps it to `ARCHIVED` rather than leaving a value v4's enum cannot name.
     */
    @Test
    fun aStoredRetiredStatusBecomesArchived() = runTest {
        val file = File.createTempFile("servicetag-migrate-3-4-retired", ".db").also { it.delete() }
        try {
            createSchemaVersion(3, file) { c ->
                c.execSQL(
                    "INSERT INTO asset (id,name,description,category,notes,status,template_key," +
                        "created_at,updated_at) " +
                        "VALUES ('a-old','Old mower','','','','RETIRED',NULL,1,1)," +
                        "('a-live','Spa','','','','ACTIVE',NULL,1,1)",
                )
            }

            val db = openMigrated(file)
            try {
                assertEquals("ARCHIVED", db.assetDao().byId("a-old")?.status)
                assertEquals("ACTIVE", db.assetDao().byId("a-live")?.status)

                // The point of the mapping: `AssetStatus.valueOf` can still read the row.
                val repo = RoomAssetRepository(db.assetDao())
                assertEquals(AssetStatus.ARCHIVED, repo.get(AssetId("a-old"))!!.status)
                assertNull("archiving is not retiring", repo.get(AssetId("a-old"))!!.retiredOn)
            } finally {
                db.close()
            }
        } finally {
            file.delete()
        }
    }

    /** What a 2B-1 install has on disk: an asset with a tag, a definition, an event and a reading. */
    private fun seedPhase2b1(c: SQLiteConnection) {
        c.execSQL(
            "INSERT INTO asset (id,name,description,category,notes,status,template_key," +
                "created_at,updated_at) " +
                "VALUES ('a1','Hot tub','basement','Spa','filter every 90d','ACTIVE','hot_tub',1000,1100)",
        )
        c.execSQL(
            "INSERT INTO nfc_tag (id,payload_format,payload_key,asset_id,link_id,status,label," +
                "physical_uid,written_at,last_scanned_at,created_at,updated_at) " +
                "VALUES ('t1','V1','t1','a1',NULL,'ACTIVE',NULL,NULL,NULL,NULL,1,1)",
        )
        c.execSQL(
            "INSERT INTO measurement_definition (id,asset_id,`key`,label,unit,value_type,decimals," +
                "range_low,range_high,is_meter,sort_order,archived_at,created_at,updated_at," +
                "kind,formula,source_a_id,source_b_id) " +
                "VALUES ('d-ph','a1','ph','pH','','NUMBER',1,7.2,7.8,0,0,NULL,1,1,'ENTERED',NULL,NULL,NULL)",
        )
        c.execSQL(
            "INSERT INTO asset_event (id,asset_id,kind,title,profile_id,occurred_on,occurred_time," +
                "tz_id,notes,source,source_ref,created_at,updated_at) " +
                "VALUES ('e1','a1','MEASUREMENT','Water test',NULL,'2026-09-14','08:30','UTC','',"
                + "'MANUAL','e1',1,1)",
        )
        c.execSQL(
            "INSERT INTO measurement (id,event_id,definition_id,value_num,value_text,unit,sort_order) " +
                "VALUES ('m1','e1','d-ph',7.4,NULL,'',0)",
        )
    }

    private companion object {
        val V3_ASSET_COLUMNS = setOf(
            "id", "name", "description", "category", "notes", "status", "template_key",
            "created_at", "updated_at",
        )
        val V4_NEW_ASSET_COLUMNS = setOf(
            "manufacturer", "model", "serial_number", "purchase_on", "in_service_on",
            "purchase_price_minor", "currency", "vendor", "location", "warranty_expires_on",
            "warranty_notes", "retired_on", "parent_asset_id", "season_start_mmdd",
            "season_end_mmdd",
        )
        val V4_ASSET_INDEXES = setOf(
            "index_asset_status",
            "index_asset_name",
            "index_asset_parent_asset_id",
        )
    }
}
