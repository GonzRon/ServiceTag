package com.loosecannon.servicetag.backup

import com.loosecannon.servicetag.core.backup.BackupCodec
import com.loosecannon.servicetag.core.backup.BackupCorrupt
import com.loosecannon.servicetag.core.journal.derivedSpecValid
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.model.DefinitionKind
import com.loosecannon.servicetag.core.model.DerivedFormula
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.EventProfile
import com.loosecannon.servicetag.core.model.ExternalLink
import com.loosecannon.servicetag.core.model.LinkId
import com.loosecannon.servicetag.core.model.LinkKind
import com.loosecannon.servicetag.core.model.MeasurementDefinition
import com.loosecannon.servicetag.core.model.PayloadFormat
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.model.TagStatus
import com.loosecannon.servicetag.core.model.TagTarget
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.StoreState
import com.loosecannon.servicetag.core.usecase.ConsumableInput
import com.loosecannon.servicetag.core.usecase.EventCommand
import com.loosecannon.servicetag.core.usecase.ExportBackupSet
import com.loosecannon.servicetag.core.usecase.ImportBackupReplace
import com.loosecannon.servicetag.core.usecase.RecomputeSchedules
import com.loosecannon.servicetag.core.usecase.ImportReport
import com.loosecannon.servicetag.data.room.AppDatabase
import com.loosecannon.servicetag.data.room.RoomAssetRepository
import com.loosecannon.servicetag.data.room.RoomAttachmentRepository
import com.loosecannon.servicetag.data.room.RoomCategoryRepository
import com.loosecannon.servicetag.data.room.RoomClosureRepository
import com.loosecannon.servicetag.data.room.RoomConditionRepository
import com.loosecannon.servicetag.data.room.RoomDefinitionRepository
import com.loosecannon.servicetag.data.room.RoomEventRepository
import com.loosecannon.servicetag.data.room.RoomGroupRepository
import com.loosecannon.servicetag.data.room.RoomHealthSubjectRepository
import com.loosecannon.servicetag.data.room.RoomLinkRepository
import com.loosecannon.servicetag.data.room.RoomProfileRepository
import com.loosecannon.servicetag.data.room.RoomReferenceRepository
import com.loosecannon.servicetag.data.room.RoomScheduleRepository
import com.loosecannon.servicetag.data.room.RoomScheduleStateRepository
import com.loosecannon.servicetag.data.room.RoomSeasonActivationRepository
import com.loosecannon.servicetag.data.room.RoomTagRepository
import com.loosecannon.servicetag.data.room.RoomUnitOfWork
import com.loosecannon.servicetag.data.room.inMemoryDb
import com.loosecannon.servicetag.testing.FakeAttachmentStorage
import com.loosecannon.servicetag.testing.FakeGraph
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Phase 1A's exit proof. Data is created in one database, exported, and imported into a brand-new
 * empty database — the JVM stand-in for "uninstall the app and install it again". What must come
 * back is not "roughly the same data" but the *same identities*: every id, every target, every
 * foreign key. Sorted domain snapshots are compared whole, then the relationship queries are run
 * against the restored database so the FK columns are proved as well as the id columns.
 */
class RestoreProofTest {

    /** Everything Phase 1A needs, assembled over one database. The JVM twin of `AppGraph`. */
    private class Graph(val db: AppDatabase) {
        val assets = RoomAssetRepository(db.assetDao())
        val tags = RoomTagRepository(db.nfcTagDao())
        val links = RoomLinkRepository(db.externalLinkDao())
        val definitions = RoomDefinitionRepository(db.definitionDao())
        val profiles = RoomProfileRepository(db.profileDao())
        val events = RoomEventRepository(db.eventDao())
        val attachments = RoomAttachmentRepository(db.attachmentDao())
        val groups = RoomGroupRepository(db.maintenanceGroupDao())
        val schedules = RoomScheduleRepository(db.maintenanceScheduleDao())
        val closures = RoomClosureRepository(db.occurrenceClosureDao())
        val references = RoomReferenceRepository(db.assetReferenceDao())
        val scheduleStates = RoomScheduleStateRepository(db.scheduleStateDao())
        val seasonActivations = RoomSeasonActivationRepository(db.seasonActivationDao())
        val conditions = RoomConditionRepository(db.assetConditionDao())
        val healthSubjects = RoomHealthSubjectRepository(db.healthSubjectDao())
        val categories = RoomCategoryRepository(db.assetCategoryDao())
        val uow = RoomUnitOfWork(db)
        // The restore's rebuild seam, wired to the real engine over the same database: the proof
        // is about the canonical rows, and derived state is rebuilt after any import.
        val recompute = RecomputeSchedules(
            schedules, scheduleStates, events, closures, groups, assets, seasonActivations,
            Today { LocalDate.parse("2026-02-10") }, Clock { FIXED_NOW }, zone = { ZoneOffset.UTC },
        )
        // This proof is about the data archive. The set's artifacts half carries bytes, and
        // bytes are what `BackupViewModelTest` and `ArtifactsCodecTest` prove.
        val export = ExportBackupSet(
            assets, groups, tags, links, definitions, profiles, schedules, closures, events,
            attachments, references, seasonActivations, conditions, healthSubjects, categories, uow,
            IdGenerator { FIXED_SET_ID }, Clock { FIXED_NOW }, "test", SCHEMA_VERSION,
        )
        val import = ImportBackupReplace(
            assets, groups, tags, links, definitions, profiles, schedules, closures, events,
            attachments, references, seasonActivations, conditions, healthSubjects, categories,
            FakeAttachmentStorage(state = StoreState.NotConfigured), uow,
            rebuildAll = { recompute.all() },
        )
    }

    private data class Snapshot(
        val assets: List<Asset>,
        val tags: List<TagBinding>,
        val links: List<ExternalLink>,
        val definitions: List<MeasurementDefinition>,
        val profiles: List<EventProfile>,
        val events: List<AssetEvent>,
        val attachments: List<Attachment>,
    )

    private suspend fun snapshot(g: Graph): Snapshot = Snapshot(
        assets = g.assets.all().sortedBy { it.id.value },
        tags = g.tags.all().sortedBy { it.id.value },
        links = g.links.all().sortedBy { it.id.value },
        definitions = g.definitions.all().sortedBy { it.id.value },
        profiles = g.profiles.all().sortedBy { it.id.value },
        events = g.events.all().sortedBy { it.id.value },
        attachments = g.attachments.all().sortedBy { it.id.value },
    )

    private fun graphOver(db: AppDatabase) = Graph(db)

    /**
     * Two assets, one link hanging off an asset and one standalone link, and three tags covering
     * every shape of `TagTarget`: bound to an asset, bound to a standalone link, and unbound.
     */
    private suspend fun seed(g: Graph) {
        g.uow.write {
            g.assets.upsert(
                Asset(
                    id = AssetId("asset-furnace"),
                    name = "Furnace",
                    description = "basement, north wall",
                    category = "HVAC",
                    notes = "filter 16x25x1",
                    status = AssetStatus.ACTIVE,
                    createdAt = 1_000L,
                    updatedAt = 1_100L,
                ),
            )
            g.assets.upsert(
                Asset(
                    id = AssetId("asset-mower"),
                    name = "Mower",
                    description = "shed",
                    category = "Yard",
                    notes = "",
                    status = AssetStatus.ARCHIVED,
                    createdAt = 2_000L,
                    updatedAt = 2_100L,
                ),
            )
            g.links.upsert(
                ExternalLink(
                    id = LinkId("link-furnace-note"),
                    assetId = AssetId("asset-furnace"),
                    kind = LinkKind.JOPLIN,
                    label = "Furnace service log",
                    uri = "joplin://x-callback-url/openNote?id=abc123",
                    createdAt = 3_000L,
                    lastOpenedAt = 3_500L,
                    updatedAt = 3_100L,
                ),
            )
            g.links.upsert(
                ExternalLink(
                    id = LinkId("link-standalone"),
                    assetId = null,
                    kind = LinkKind.WEB,
                    label = "Manual PDF",
                    uri = "https://example.invalid/furnace.pdf",
                    createdAt = 4_000L,
                    lastOpenedAt = null,
                    updatedAt = 4_100L,
                ),
            )
            g.tags.upsert(
                TagBinding(
                    id = TagId("tag-on-furnace"),
                    payloadFormat = PayloadFormat.V1,
                    payloadKey = "11111111-1111-4111-8111-111111111111",
                    target = TagTarget.AssetTarget(AssetId("asset-furnace")),
                    status = TagStatus.ACTIVE,
                    label = "sticker on the cover",
                    physicalUid = "04A1B2C3D4E5F6",
                    writtenAt = 5_000L,
                    lastScannedAt = 5_500L,
                    createdAt = 5_000L,
                    updatedAt = 5_100L,
                ),
            )
            g.tags.upsert(
                TagBinding(
                    id = TagId("tag-on-manual"),
                    payloadFormat = PayloadFormat.V1,
                    payloadKey = "link-standalone",
                    target = TagTarget.LinkTarget(LinkId("link-standalone")),
                    status = TagStatus.ACTIVE,
                    label = "inside the binder",
                    physicalUid = null,
                    writtenAt = 6_000L,
                    lastScannedAt = null,
                    createdAt = 6_000L,
                    updatedAt = 6_100L,
                ),
            )
            g.tags.upsert(
                TagBinding(
                    id = TagId("tag-spare"),
                    payloadFormat = PayloadFormat.V1,
                    payloadKey = "spare-blank",
                    target = TagTarget.None,
                    status = TagStatus.UNBOUND,
                    label = null,
                    physicalUid = null,
                    writtenAt = null,
                    lastScannedAt = null,
                    createdAt = 7_000L,
                    updatedAt = 7_100L,
                ),
            )
        }
    }

    @Test
    fun forgetEverythingAndRebuildTheSameGraph() = runTest {
        val db1 = inMemoryDb()
        val before: Snapshot
        val bytes: ByteArray
        try {
            val g1 = graphOver(db1)
            seed(g1)
            bytes = g1.export.run().data
            before = snapshot(g1)
        } finally {
            db1.close() // the "uninstall": that database and everything in it is gone
        }

        // Reinstall-equivalent: a brand-new, empty database that has never seen this data.
        val db2 = inMemoryDb()
        try {
            val g2 = graphOver(db2)
            assertTrue("the new database must start empty", snapshot(g2).assets.isEmpty())

            val report = g2.import.run(bytes)
            val after = snapshot(g2)

            // Same ids, same names, same targets, same links — the whole graph, compared whole.
            assertEquals(before, after)
            assertEquals(
                ImportReport(
                    formatVersion = BackupCodec.FORMAT_VERSION, assets = 2, tags = 3, links = 2,
                    definitions = 0, profiles = 0, events = 0, attachments = 0,
                    lastRestoredBackupSetId = FIXED_SET_ID,
                ),
                report,
            )

            // And the relationships survive through the FK columns, not only through the id strings.
            assertEquals(1, g2.tags.forAsset(before.assets[0].id).size)
            assertEquals(
                TagId("tag-on-furnace"),
                g2.tags.forAsset(before.assets[0].id).single().id,
            )
            val standalone = before.links.single { it.assetId == null }
            assertEquals(1, g2.db.nfcTagDao().forLink(standalone.id.value).size)
            assertEquals("tag-on-manual", g2.db.nfcTagDao().forLink(standalone.id.value).single().id)
            assertEquals(1, g2.db.externalLinkDao().forAsset("asset-furnace").size)
            assertEquals(listOf("link-standalone"), g2.db.externalLinkDao().standalone().map { it.id })
            assertEquals(TagTarget.None, g2.tags.get(TagId("tag-spare"))!!.target)
            // The payload lookup finds the furnace's row: (format, key) survived as a pair, and
            // the row it names is still pointing at the asset it was bound to.
            val byPayload = g2.tags.findByPayload(PayloadFormat.V1, "11111111-1111-4111-8111-111111111111")!!
            assertEquals(TagId("tag-on-furnace"), byPayload.id)
            assertEquals("11111111-1111-4111-8111-111111111111", byPayload.payloadKey)
            assertEquals(TagTarget.AssetTarget(AssetId("asset-furnace")), byPayload.target)
        } finally {
            db2.close()
        }
    }

    @Test
    fun replaceImportIsAtomic() = runTest {
        // A populated database, and a backup taken from a *different* database.
        val donor = inMemoryDb()
        val backup: ByteArray
        try {
            val gd = graphOver(donor)
            seed(gd)
            backup = gd.export.run().data
        } finally {
            donor.close()
        }

        val db = inMemoryDb()
        try {
            val g = graphOver(db)
            g.uow.write {
                g.assets.upsert(
                    Asset(
                        id = AssetId("asset-local"),
                        name = "Local only",
                        description = "",
                        category = "",
                        notes = "",
                        status = AssetStatus.ACTIVE,
                        createdAt = 10L,
                        updatedAt = 11L,
                    ),
                )
                g.links.upsert(
                    ExternalLink(
                        id = LinkId("link-local"),
                        assetId = AssetId("asset-local"),
                        kind = LinkKind.OBSIDIAN,
                        label = "local note",
                        uri = "obsidian://open?file=local",
                        createdAt = 12L,
                        lastOpenedAt = null,
                        updatedAt = 13L,
                    ),
                )
                g.tags.upsert(
                    TagBinding(
                        id = TagId("tag-local"),
                        payloadFormat = PayloadFormat.V1,
                        payloadKey = "local",
                        target = TagTarget.AssetTarget(AssetId("asset-local")),
                        status = TagStatus.ACTIVE,
                        label = null,
                        physicalUid = null,
                        writtenAt = null,
                        lastScannedAt = null,
                        createdAt = 14L,
                        updatedAt = 15L,
                    ),
                )
            }
            val untouched = snapshot(g)

            // 1. Corrupt bytes are refused and change nothing.
            val corrupt = backup.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
            assertThrows(BackupCorrupt::class.java) {
                kotlinx.coroutines.runBlocking { g.import.run(corrupt) }
            }
            assertEquals(untouched, snapshot(g))

            // 2. A valid backup replaces everything: none of the local rows survive.
            val report = g.import.run(backup)
            assertEquals(
                ImportReport(
                    formatVersion = BackupCodec.FORMAT_VERSION, assets = 2, tags = 3, links = 2,
                    definitions = 0, profiles = 0, events = 0, attachments = 0,
                    lastRestoredBackupSetId = FIXED_SET_ID,
                ),
                report,
            )
            val after = snapshot(g)
            assertTrue(
                "no local row may survive a replace import",
                after.assets.none { it.id.value == "asset-local" } &&
                    after.links.none { it.id.value == "link-local" } &&
                    after.tags.none { it.id.value == "tag-local" },
            )
            assertEquals(2, after.assets.size)
            assertEquals(3, after.tags.size)
            assertEquals(2, after.links.size)
        } finally {
            db.close()
        }
    }

    /**
     * Phase 2A's half of the same proof. The journal is seeded the way the app itself would seed
     * it — a template applied by [com.loosecannon.servicetag.core.usecase.CreateAsset], an event
     * logged through [com.loosecannon.servicetag.core.usecase.LogEvent] — so the rows under test are
     * rows the production path actually produces, children and their ids included. Then the whole
     * seven-table graph — attachment rows included — goes out to a backup and comes back
     * into an empty database unchanged.
     */
    @Test
    fun theJournalSurvivesTheSameRoundTrip() = runTest {
        val before: Snapshot
        val bytes: ByteArray
        val setId: String
        val g1 = FakeGraph()
        try {
            val spa = g1.createAsset.run(name = "Hot tub", templateKey = "hot_tub")
            val waterTest = g1.profiles.forAsset(spa.id).first { it.name == "Water test" }
            g1.logEvent.run(
                EventCommand(
                    assetId = spa.id,
                    profileId = waterTest.id,
                    kind = waterTest.eventKind,
                    title = "",
                    occurredOn = "2026-09-14",
                    occurredTime = "08:30",
                    tzId = "UTC",
                    notes = "after the storm",
                    values = waterTest.fields.associate { it.definitionId to "7.4" },
                    consumables = listOf(ConsumableInput("Chlorine", "2", "tab")),
                ),
            )
            // Only the data archive: see the note on `Graph.export` above.
            val set = g1.exportBackupSet.run()
            bytes = set.data
            setId = set.plan.backupSetId
            before = snapshot(graphOver(g1.db))
        } finally {
            g1.close()
        }

        assertTrue("the template must have seeded definitions", before.definitions.isNotEmpty())
        assertTrue("the template must have seeded profiles", before.profiles.isNotEmpty())
        assertEquals(1, before.events.size)
        assertTrue("the event must carry its children", before.events.single().measurements.isNotEmpty())

        val db2 = inMemoryDb()
        try {
            val g2 = graphOver(db2)
            assertTrue("the new database must start empty", snapshot(g2).events.isEmpty())

            val report = g2.import.run(bytes)
            assertEquals(
                ImportReport(
                    formatVersion = BackupCodec.FORMAT_VERSION,
                    assets = 1,
                    tags = 0,
                    links = 0,
                    definitions = before.definitions.size,
                    profiles = before.profiles.size,
                    events = 1,
                    attachments = 0,
                    lastRestoredBackupSetId = setId,
                ),
                report,
            )
            // The seven tables this fixture carries, attachments included, compared whole: ids,
            // child ids, foreign keys, snapshot units. Format 6's other three — groups, schedules
            // and closures — are empty here; their round trip is `BackupFormat6Test`'s.
            assertEquals(before, snapshot(g2))

            // And the journal reads back through its own relationship queries, not only `all()`.
            val spa = before.assets.single()
            assertEquals(before.definitions, g2.definitions.forAsset(spa.id).sortedBy { it.id.value })
            assertEquals(before.profiles, g2.profiles.forAsset(spa.id).sortedBy { it.id.value })
            assertEquals(before.events, g2.events.forAsset(spa.id))
            assertEquals("hot_tub", spa.templateKey)
        } finally {
            db2.close()
        }
    }

    /**
     * Phase 2B-1's half: a DERIVED definition and the two definitions it reads. The interesting
     * part is not the row but the *self*-reference — `source_a_id` and `source_b_id` are foreign
     * keys into `measurement_definition` itself, and they are RESTRICT, so a replace import has to
     * clear the derived rows before the rows they point at and insert them after. If either order
     * were wrong this test would not merely mismatch, it would throw.
     */
    @Test
    fun theDerivedDefinitionSurvivesWithItsSources() = runTest {
        val before: Snapshot
        val bytes: ByteArray
        val g1 = FakeGraph()
        try {
            val ro = g1.createAsset.run(name = "RO filter", templateKey = "ro_water")
            val tdsTest = g1.profiles.forAsset(ro.id).first { it.name == "TDS test" }
            g1.logEvent.run(
                EventCommand(
                    assetId = ro.id,
                    profileId = tdsTest.id,
                    kind = tdsTest.eventKind,
                    title = "",
                    occurredOn = "2026-09-15",
                    occurredTime = null,
                    tzId = "UTC",
                    notes = "",
                    values = tdsTest.fields.associate { it.definitionId to "250" },
                    consumables = emptyList(),
                ),
            )
            // Only the data archive: see the note on `Graph.export` above.
            bytes = g1.exportBackupSet.run().data
            before = snapshot(graphOver(g1.db))
        } finally {
            g1.close()
        }

        val derivedBefore = before.definitions.single { it.kind == DefinitionKind.DERIVED }
        assertEquals(DerivedFormula.PERCENT_DROP, derivedBefore.derived!!.formula)

        val db2 = inMemoryDb()
        try {
            val g2 = graphOver(db2)
            val report = g2.import.run(bytes)
            assertEquals(before.definitions.size, report.definitions)

            // The whole graph, compared whole: kind, formula and both source ids included.
            assertEquals(before, snapshot(g2))

            val restored = g2.definitions.get(derivedBefore.id)!!
            assertEquals(derivedBefore, restored)
            val byId = g2.definitions.forAsset(restored.assetId).associateBy { it.id }
            assertTrue(
                "the restored sources must still be the entered definitions of the same asset",
                restored.derivedSpecValid(byId),
            )

            // Again, over data that now contains derived rows: the wipe half of a replace import
            // has to clear a definition that points at another definition before clearing the one
            // it points at. That is the order `clearInstall` on a device relies on too.
            g2.import.run(bytes)
            assertEquals(before, snapshot(g2))
        } finally {
            db2.close()
        }
    }

    /**
     * Phase 2B-2's half: a three-level tree — root → child → grandchild — through the whole cycle,
     * export, wipe, import, over one database. Three separate things could go wrong here and each
     * would throw rather than merely mismatch: the wipe has to delete children before parents or
     * RESTRICT refuses it; the load has to insert parents before children or the same foreign key
     * refuses that; and `parent_asset_id` has to come back as an id, not as a position in a list.
     * The ids are asserted exactly.
     */
    @Test
    fun aThreeLevelTreeSurvivesTheRoundTrip() = runTest {
        val db = inMemoryDb()
        try {
            val g = graphOver(db)
            g.uow.write {
                g.assets.upsert(tree("asset-tractor", "Tractor", parent = null))
                g.assets.upsert(tree("asset-deck", "Mower deck", parent = "asset-tractor"))
                g.assets.upsert(tree("asset-blade", "Blade", parent = "asset-deck"))
            }
            val before = snapshot(g)
            assertEquals(3, before.assets.size)

            val bytes = g.export.run().data

            // The wipe half on its own, against the tree: children-first or nothing.
            g.uow.write { g.assets.deleteAll() }
            assertTrue("the wipe must clear the whole tree", g.assets.all().isEmpty())

            // And the load half.
            val report = g.import.run(bytes)
            assertEquals(3, report.assets)

            val after = snapshot(g)
            assertEquals(before, after)
            assertEquals(
                listOf("asset-blade", "asset-deck", "asset-tractor"),
                after.assets.map { it.id.value },
            )
            val byId = after.assets.associateBy { it.id }
            assertNull(byId.getValue(AssetId("asset-tractor")).parentAssetId)
            assertEquals(
                AssetId("asset-tractor"),
                byId.getValue(AssetId("asset-deck")).parentAssetId,
            )
            assertEquals(
                AssetId("asset-deck"),
                byId.getValue(AssetId("asset-blade")).parentAssetId,
            )

            // A second replace import over the tree it already holds: the wipe now meets a real
            // three-level tree rather than an empty table, which is the case the debug Wipe and
            // the instrumentation's `clearInstall` hit on a device.
            g.import.run(bytes)
            assertEquals(before, snapshot(g))
        } finally {
            db.close()
        }
    }

    private fun tree(id: String, name: String, parent: String?) = Asset(
        id = AssetId(id),
        name = name,
        description = "",
        category = "Yard",
        notes = "",
        status = AssetStatus.ACTIVE,
        createdAt = 1_000L,
        updatedAt = 1_100L,
        parentAssetId = parent?.let(::AssetId),
    )

    private companion object {
        const val FIXED_NOW = 1_757_000_000_000L
        const val SCHEMA_VERSION = 5
        const val FIXED_SET_ID = "backup-set-restore-proof"
    }
}
