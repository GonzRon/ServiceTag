package com.loosecannon.servicetag.data.room

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.model.AttachmentId
import com.loosecannon.servicetag.core.model.AttachmentKind
import com.loosecannon.servicetag.core.model.AttachmentMode
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.StorageProvider
import kotlinx.coroutines.test.runTest
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MIGRATION_4_5]: the additive migration that gives the schema its `attachment` table (spec
 * §9.1). A v4 database is built from the exported `4.json`, seeded with what a 2B-2 install really
 * has, and opened through Room, which runs the migration and validates the result against the
 * compiled v5 schema. See [openMigrated] for why the proof is assembled this way.
 *
 * Nothing is recreated here, so what is at risk is not the old rows but the new table's shape:
 * the two nullable foreign keys, the three indexes, and the cascades that make an attachment row
 * die with its owner.
 */
class Migration4To5Test {

    @Test
    fun addsTheAttachmentTableAndLeavesEverythingElseAlone() = runTest {
        val file = File.createTempFile("servicetag-migrate-4-5", ".db").also { it.delete() }
        try {
            createSchemaVersion(4, file, ::seedPhase2b2)

            val db = openMigrated(file)
            try {
                // the v4 rows came through untouched
                val asset = db.assetDao().byId("a1")!!
                assertEquals("Hot tub", asset.name)
                assertEquals("Spa", asset.category)
                assertEquals(listOf("t1"), db.nfcTagDao().forAsset("a1").map { it.id })
                assertEquals(listOf("e1"), db.eventDao().forAsset("a1").map { it.event.id })

                // and the new table is there, empty, and takes a row on either owner
                val repo = RoomAttachmentRepository(db.attachmentDao())
                assertEquals(0, repo.count())
                repo.upsert(
                    attachment("att-1", AttachmentOwner.OfAsset(AssetId("a1")), "assets/a1/att-1.pdf"),
                )
                repo.upsert(
                    attachment("att-2", AttachmentOwner.OfEvent(EventId("e1")), "events/e1/att-2.jpg"),
                )
                assertEquals(2, repo.count())
                assertEquals(
                    listOf(AttachmentId("att-1")),
                    repo.forAsset(AssetId("a1")).map { it.id },
                )
                assertEquals(
                    listOf(AttachmentId("att-2")),
                    repo.forOwner(AttachmentOwner.OfEvent(EventId("e1"))).map { it.id },
                )

                // The new foreign keys are live on a migrated file, not only on a fresh one:
                // deleting the event takes its row, and deleting the asset takes what is left.
                db.eventDao().delete("e1")
                assertNull(repo.get(AttachmentId("att-2")))
                assertEquals(1, repo.count())
                db.assetDao().delete("a1")
                assertEquals(0, repo.count())
            } finally {
                db.close()
            }

            withConnection(file) { c ->
                assertTrue("attachment" in c.tableNames())
                assertTrue(c.tableNames().containsAll(JOURNAL_TABLES))
                // The chain runs on to v10, whose one new attachment column `Migration9To10Test` owns.
                assertEquals(V5_ATTACHMENT_COLUMNS, c.columnNamesOf("attachment") - V10_ATTACHMENT_COLUMNS)
                assertTrue(
                    "v5's indexes must exist, found ${c.indexNamesOn("attachment")}",
                    c.indexNamesOn("attachment").containsAll(V5_ATTACHMENT_INDEXES),
                )
            }
        } finally {
            file.delete()
        }
    }

    /** What a 2B-2 install has on disk: an asset with a tag and an event carrying a reading. */
    private fun seedPhase2b2(c: SQLiteConnection) {
        c.execSQL(
            "INSERT INTO asset (id,name,description,category,notes,status,template_key," +
                "created_at,updated_at,manufacturer,model,serial_number,vendor,location," +
                "warranty_notes) " +
                "VALUES ('a1','Hot tub','basement','Spa','filter every 90d','ACTIVE','hot_tub'," +
                "1000,1100,'','','','','','')",
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

    private fun attachment(id: String, owner: AttachmentOwner, locator: String) = Attachment(
        id = AttachmentId(id), owner = owner, kind = AttachmentKind.DOCUMENT,
        mode = AttachmentMode.MANAGED, displayName = "$id.pdf", mimeType = "application/pdf",
        sizeBytes = 12L, sha256 = "a".repeat(64), storageProvider = StorageProvider.SAF_TREE,
        storageLocator = locator, capturedOn = null, notes = "", createdAt = 1L, updatedAt = 1L,
    )

    private companion object {
        val V5_ATTACHMENT_COLUMNS = setOf(
            "id", "asset_id", "event_id", "kind", "mode", "display_name", "mime_type",
            "size_bytes", "sha256", "storage_provider", "storage_locator", "captured_on",
            "notes", "created_at", "updated_at",
        )
        val V5_ATTACHMENT_INDEXES = setOf(
            "index_attachment_asset_id",
            "index_attachment_event_id",
            "index_attachment_storage_provider_storage_locator",
        )
    }
}
