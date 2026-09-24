package com.loosecannon.servicetag.core.backup

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetReference
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.testing.FakeAttachmentStorage
import com.loosecannon.servicetag.core.testing.FakeUnitOfWork
import com.loosecannon.servicetag.core.testing.InMemoryAssetRepository
import com.loosecannon.servicetag.core.testing.InMemoryAttachmentRepository
import com.loosecannon.servicetag.core.testing.InMemoryClosureRepository
import com.loosecannon.servicetag.core.testing.InMemoryConditionRepository
import com.loosecannon.servicetag.core.testing.InMemoryDefinitionRepository
import com.loosecannon.servicetag.core.testing.InMemoryEventRepository
import com.loosecannon.servicetag.core.testing.InMemoryGroupRepository
import com.loosecannon.servicetag.core.testing.InMemoryHealthSubjectRepository
import com.loosecannon.servicetag.core.testing.InMemoryLinkRepository
import com.loosecannon.servicetag.core.testing.InMemoryProfileRepository
import com.loosecannon.servicetag.core.testing.InMemoryReferenceRepository
import com.loosecannon.servicetag.core.testing.InMemoryScheduleRepository
import com.loosecannon.servicetag.core.testing.InMemorySeasonActivationRepository
import com.loosecannon.servicetag.core.testing.InMemoryTagRepository
import com.loosecannon.servicetag.core.usecase.ExportBackupSet
import com.loosecannon.servicetag.core.usecase.ImportBackupReplace
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.descriptors.elementNames
import org.junit.jupiter.api.Test

/**
 * Backup **format 7**: one new top-level table, `assetReferences`, one new `counts` key, the
 * sorting that keeps the bytes deterministic, the eager validation and the owner check. Everything
 * format ≤6 already promised is `BackupCodecTest`'s and `BackupFormat6Test`'s and is not repeated
 * here; what this class adds is one case per way format 7 could go wrong.
 *
 * The fixture carries **non-default values in every field**, including a URI with a query and a
 * fragment, because a field that is only ever tested at its default is a field a dropped mapper
 * line cannot fail. The 2.6 `externalLinks` tombstone rides along untouched in the same archive,
 * which is what makes "a new table, not a re-purposed one" a value comparison rather than a claim.
 */
class BackupFormat7Test {

    // --- fixture ---------------------------------------------------------------------------------

    private fun asset(id: String = "a1", name: String = "Cub Cadet XT1") =
        AssetDto(id, name, "east row", "mowers", "", "ACTIVE", 100L, 200L)

    /** A web reference whose URI carries a query **and** a fragment, both of which survive verbatim. */
    private fun webReference() = AssetReferenceDto(
        id = "r1",
        assetId = "a1",
        kind = "WEB_URL",
        uri = "https://example-mower.invalid/parts?model=XT1&page=2#deck",
        displayName = "OEM parts lookup",
        description = "deck belt and blades",
        scheme = "https",
        createdAt = 1_000L,
        updatedAt = 2_000L,
    )

    /** A note reference, so both ends of the kind vocabulary travel. */
    private fun noteReference() = AssetReferenceDto(
        id = "r2",
        assetId = "a1",
        kind = "NOTE_LINK",
        uri = "joplin://x-callback-url/openNote?id=abc123",
        displayName = "Mower notes",
        description = "",
        scheme = "joplin",
        createdAt = 3_000L,
        updatedAt = 4_000L,
    )

    /** The tombstone that must ride along untouched: a format-5 `external_link` row (I-5). */
    private fun tombstone() = ExternalLinkDto(
        id = "l1", assetId = "a1", kind = "JOPLIN", label = "Mower notes",
        uri = "joplin://l1", createdAt = 5L, lastOpenedAt = null, updatedAt = 6L,
    )

    private fun fixture() = BackupData(
        assets = listOf(asset()),
        nfcTags = emptyList(),
        externalLinks = listOf(tombstone()),
        assetReferences = listOf(webReference(), noteReference()),
    )

    /** The same estate with nothing reference-shaped in it, for the format-6 cases. */
    private fun formatSixData() = BackupData(
        assets = listOf(asset()),
        nfcTags = emptyList(),
        externalLinks = listOf(tombstone()),
    )

    private fun encoded(data: BackupData, formatVersion: Int = BackupCodec.FORMAT_VERSION) =
        BackupCodec.encode(
            data, appVersion = "1.3.0", schemaVersion = 7, createdAt = 1_758_400_000_000L,
            backupSetId = "set-format-7", formatVersion = formatVersion,
        )

    private fun dataJson(bytes: ByteArray): String {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                if (entry.name == BackupCodec.DATA_ENTRY) return String(zin.readBytes(), Charsets.UTF_8)
                zin.closeEntry()
            }
        }
        error("no ${BackupCodec.DATA_ENTRY} in the archive")
    }

    /** One install, enough of it to export from and restore an archive into. */
    private class Fakes(val references: InMemoryReferenceRepository = InMemoryReferenceRepository()) {
        val assets = InMemoryAssetRepository()
        val tags = InMemoryTagRepository()
        val links = InMemoryLinkRepository()
        val definitions = InMemoryDefinitionRepository()
        val profiles = InMemoryProfileRepository()
        val events = InMemoryEventRepository()
        val attachments = InMemoryAttachmentRepository()
        val groups = InMemoryGroupRepository()
        val closures = InMemoryClosureRepository()
        val schedules = InMemoryScheduleRepository(closures)
        val storage = FakeAttachmentStorage()
        val uow = FakeUnitOfWork(
            assets, groups, tags, links, definitions, profiles, schedules, closures,
            events, attachments, references,
        )
        val export = ExportBackupSet(
            assets, groups, tags, links, definitions, profiles, schedules, closures,
            events, attachments, references,
            InMemorySeasonActivationRepository(), InMemoryConditionRepository(), InMemoryHealthSubjectRepository(),
            uow, IdGenerator { "set-format-7" },
            Clock { 1_758_400_000_000L }, appVersion = "1.3.0", schemaVersion = 7,
        )
        val restore = ImportBackupReplace(
            assets, groups, tags, links, definitions, profiles, schedules, closures,
            events, attachments, references,
            InMemorySeasonActivationRepository(), InMemoryConditionRepository(), InMemoryHealthSubjectRepository(),
            storage, uow, rebuildAll = { },
        )
    }

    // --- the round trip --------------------------------------------------------------------------

    /**
     * Hazard: a new field silently dropped in transit. Every field of [AssetReferenceDto] carries a
     * non-default value here — the URI's query and fragment included — so a field omitted from the
     * DTO or from either half of its mapper comes back at its default and this fails.
     *
     * The value comparison is the whole assertion: `BackupData` is a data class, so it covers every
     * field of every row without naming them one at a time. The domain read after it is what proves
     * the mapper, and the tombstone row beside it is what proves format 7 left it alone (I-5).
     */
    @Test
    fun `every reference field survives encode and decode with a non-default value`() {
        val data = fixture()
        val decoded = BackupCodec.decode(encoded(data))

        assertEquals(data, decoded.data)

        val reference = decoded.data.assetReferences.single { it.id == "r1" }.toDomain()
        assertEquals("https://example-mower.invalid/parts?model=XT1&page=2#deck", reference.uri)
        assertEquals("OEM parts lookup", reference.displayName)
        assertEquals("deck belt and blades", reference.description)
        assertEquals("https", reference.scheme)
        assertEquals(1_000L, reference.createdAt)
        assertEquals(2_000L, reference.updatedAt)
        assertEquals(AssetId("a1"), reference.assetId)
        assertEquals("joplin", decoded.data.assetReferences.single { it.id == "r2" }.toDomain().scheme)

        // the 2.6 tombstone travelled beside it, byte for byte, and was not re-purposed
        assertEquals(listOf(tombstone()), decoded.data.externalLinks)
    }

    /**
     * Hazard: DTO field-set drift. The nine element names are pinned in order, and `"provenance"`
     * is asserted absent — the assertion that actually carries D-21 C, since an in-app "Add link"
     * and a share write identical rows and nothing in the product distinguishes them.
     *
     * `BackupData`'s own new member is pinned last for the same reason, and the two 2.6 tombstone
     * fields are pinned where they have always been.
     */
    @Test
    fun `the reference DTO's field set is exactly the contract and carries no provenance`() {
        val names = AssetReferenceDto.serializer().descriptor.elementNames.toList()
        assertEquals(
            listOf(
                "id", "assetId", "kind", "uri", "displayName", "description", "scheme",
                "createdAt", "updatedAt",
            ),
            names,
        )
        assertEquals(9, AssetReferenceDto.serializer().descriptor.elementsCount)
        assertTrue("provenance" !in names, "a reference carries no provenance (D-21 C)")

        val tables = BackupData.serializer().descriptor.elementNames.toList()
        assertEquals("assetReferences", tables.last())
        assertEquals(11, tables.size)
        // and the tombstone is still its own list, neither bumped nor renamed (I-5)
        assertTrue("externalLinks" in tables)
    }

    // --- direction -------------------------------------------------------------------------------

    /**
     * Hazard: an old archive stops working. A hand-built **format-6** archive decodes with an empty
     * reference list and restores with every other count unchanged — no reference is invented, and
     * the tombstone rows it does carry are restored as they always were.
     *
     * Without the `= emptyList()` default the decode would throw on the missing key.
     */
    @Test
    fun `a format-6 archive still decodes and restores, and invents no reference`() {
        val bytes = encoded(formatSixData(), formatVersion = 6)
        val decoded = BackupCodec.decode(bytes)

        assertEquals(6, decoded.manifest.formatVersion)
        assertEquals(emptyList(), decoded.data.assetReferences)

        val f = Fakes()
        val report = runBlocking { f.restore.run(bytes) }

        assertEquals(6, report.formatVersion)
        runBlocking {
            assertEquals(1, f.assets.all().size)
            assertEquals(1, f.links.all().size)
            assertEquals(emptyList(), f.references.all())
        }
    }

    /**
     * Hazard: a newer archive read by an older build. The shipped gate refuses a manifest whose
     * `formatVersion` exceeds what this build supports **before a single row is read** — asserted
     * with an archive whose own reference row names an absent asset, so a `BackupCorrupt` would
     * prove the rows *were* read.
     *
     * The 1.2.x direction is the same gate with `FORMAT_VERSION` held at [LAST_1_2_X_FORMAT]: a
     * format-7 archive exceeds it, so a 1.2.x build refuses it loudly instead of dropping the rows
     * it cannot see. That is the forward-only half of why 1.3.0 is a MINOR (spec §12).
     */
    @Test
    fun `an archive from a newer format is refused before any row is read`() {
        val unreadable = fixture().let { f ->
            f.copy(assetReferences = listOf(webReference().copy(assetId = "a-nowhere")))
        }
        val bytes = encoded(unreadable, formatVersion = BackupCodec.FORMAT_VERSION + 1)

        val refusal = assertFailsWith<BackupNewerFormat> { BackupCodec.decode(bytes) }
        assertEquals(BackupCodec.FORMAT_VERSION + 1, refusal.found)
        assertEquals(BackupCodec.FORMAT_VERSION, refusal.supported)

        // The same row, at a version this build does support, *is* read — and refused as corrupt.
        // That is what makes the assertion above a statement about ordering and not about the row.
        assertFailsWith<BackupCorrupt> { BackupCodec.decode(encoded(unreadable)) }

        // What a 1.2.x build sees: this format is greater than the 6 it supported, so its gate fires.
        assertTrue(BackupCodec.FORMAT_VERSION > LAST_1_2_X_FORMAT)
    }

    // --- the manifest ----------------------------------------------------------------------------

    /**
     * Hazard: counts drift from rows. The manifest carries exactly seventeen keys and the new one
     * is the reference row count — so a missing key, or a count taken from the wrong list, gives
     * the wrong number here.
     */
    @Test
    fun `the manifest carries the reference count and it equals the rows`() {
        val manifest = BackupCodec.decode(encoded(fixture())).manifest

        assertEquals(2, manifest.counts["assetReferences"])
        assertEquals(17, manifest.counts.size)
        assertEquals(
            mapOf(
                "assets" to 1, "nfcTags" to 0, "externalLinks" to 1,
                "measurementDefinitions" to 0, "eventProfiles" to 0, "assetEvents" to 0,
                "profileFields" to 0, "profileConsumables" to 0,
                "measurements" to 0, "consumableUsages" to 0,
                "attachments" to 0,
                "maintenanceGroups" to 0, "groupMembers" to 0,
                "maintenanceSchedules" to 0, "scheduleProviders" to 0,
                "occurrenceClosures" to 0,
                "assetReferences" to 2,
            ),
            manifest.counts,
        )
    }

    // --- determinism -----------------------------------------------------------------------------

    /**
     * Hazard: non-deterministic bytes. The same data with the reference list shuffled encodes to
     * the same bytes, and the rows come out in `id` order. An unsorted list makes the two byte
     * arrays differ.
     */
    @Test
    fun `encoding is deterministic however the reference list arrives`() {
        val data = fixture()
        val canonical = encoded(data)

        assertContentEquals(canonical, encoded(data.copy(assetReferences = data.assetReferences.reversed())))

        val json = dataJson(encoded(data.copy(assetReferences = data.assetReferences.reversed())))
        val referencesJson = json.substring(json.indexOf("\"assetReferences\""))
        assertTrue(
            referencesJson.indexOf("\"r1\"") < referencesJson.indexOf("\"r2\""),
            "references not sorted by id",
        )
    }

    // --- eager validation ------------------------------------------------------------------------

    /**
     * Hazard: a duplicate id in an archive. Two reference rows under one id are refused by
     * `uniqueIds`, and the message names the table — without that call the duplicate reaches the
     * apply and fails there, on the unique index, with the owner's data already deleted.
     */
    @Test
    fun `two references under one id are refused, naming the table`() {
        val twice = fixture().let { f ->
            f.copy(assetReferences = listOf(webReference(), noteReference().copy(id = "r1")))
        }

        val e = assertFailsWith<BackupCorrupt> { BackupCodec.decode(encoded(twice)) }
        assertTrue("assetReferences" in e.message!!, "unhelpful message: ${e.message}")
        assertTrue("r1" in e.message!!, "unhelpful message: ${e.message}")
    }

    /**
     * Hazard: an orphan reference in an archive. A row whose `assetId` is absent from the archive's
     * own assets is refused before any import begins, exactly as an `externalLinks` row is — without
     * the owner check a replace import fails halfway, with the owner's data already gone.
     */
    @Test
    fun `a reference naming an absent asset is refused, naming both ids`() {
        val orphan = fixture().let { f ->
            f.copy(assetReferences = listOf(webReference().copy(assetId = "a-nowhere")))
        }

        val e = assertFailsWith<BackupCorrupt> { BackupCodec.decode(encoded(orphan)) }
        assertTrue("assetReferences" in e.message!!, "unhelpful message: ${e.message}")
        assertTrue("r1" in e.message!!, "unhelpful message: ${e.message}")
        assertTrue("a-nowhere" in e.message!!, "unhelpful message: ${e.message}")
    }

    /**
     * Hazard: in-archive pair uniqueness checked too early. Two rows claiming one `(assetId, uri)`
     * under different ids **decode successfully**, because that check belongs to the planner and
     * nowhere else: checking it here would make `REFERENCE_DUPLICATED_IN_ARCHIVE` unreachable,
     * which spec §5 requires reachable — exactly as `CLOSURE_DUPLICATED_IN_ARCHIVE` is.
     */
    @Test
    fun `two references claiming one pair decode, because the planner is what answers that`() {
        val samePair = fixture().let { f ->
            f.copy(assetReferences = listOf(webReference(), noteReference().copy(uri = webReference().uri)))
        }

        val decoded = BackupCodec.decode(encoded(samePair))

        assertEquals(2, decoded.data.assetReferences.size)
        assertEquals(
            listOf(webReference().uri, webReference().uri),
            decoded.data.assetReferences.map { it.uri },
        )
    }

    // --- export and the replace import -----------------------------------------------------------

    /**
     * Hazard: a replace-import drops or misorders references. An archive holding references on two
     * assets is replace-imported into an install that already holds a different one, and then read
     * back: the archive's rows are there **field for field**, the pre-existing row is gone because
     * `deleteAll()` ran, and no row was replayed before its asset — proved by a repository that
     * refuses a reference whose owner is not inserted yet, the shape `BackupUseCasesTest` uses for
     * the DERIVED definition order.
     *
     * The format-6 case above proves only the *empty*-list path, so without this row the replace
     * import's change is untested.
     */
    @Test
    fun `a replace import replays every reference, after its asset, and drops what was here`() {
        val source = Fakes()
        val secondAsset = asset(id = "a2", name = "Orchard Pump")
        val onSecond = webReference().copy(
            id = "r3", assetId = "a2", uri = "https://example-mower.invalid/pump",
            displayName = "Pump manual",
        )
        val outgoing = fixture().copy(
            assets = listOf(asset(), secondAsset),
            assetReferences = listOf(webReference(), noteReference(), onSecond),
        )
        runBlocking { source.restore.run(encoded(outgoing)) }
        val bytes = runBlocking { source.export.run().data }

        // A destination that already holds a reference of its own, on an asset the archive replaces.
        lateinit var target: Fakes
        target = Fakes(references = OwnerCheckingReferenceRepository { target.assets.rows.keys })
        runBlocking {
            target.restore.run(encoded(formatSixData()))
            target.references.upsert(
                webReference().copy(id = "stale", uri = "https://example-mower.invalid/old").toDomain(),
            )
            assertEquals(listOf("stale"), target.references.all().map { it.id.value })

            target.restore.run(bytes)

            assertEquals(
                listOf("r1", "r2", "r3"),
                target.references.all().map { it.id.value }.sorted(),
            )
            assertEquals(
                outgoing.assetReferences.sortedBy { it.id },
                target.references.all().map { it.toDto() }.sortedBy { it.id },
            )
            assertNotNull(target.references.findByUri(AssetId("a2"), onSecond.uri))
        }
    }

    /**
     * The export's half of the contract: the new table is read into the archive, and a second
     * install restored from those bytes holds exactly the same rows.
     */
    @Test
    fun `an export carries the reference table and round-trips through a restore`() {
        val source = Fakes()
        runBlocking {
            source.restore.run(encoded(fixture()))
            val bytes = source.export.run().data
            val decoded = BackupCodec.decode(bytes)

            assertEquals(BackupCodec.FORMAT_VERSION, decoded.manifest.formatVersion)
            assertEquals(fixture().assetReferences, decoded.data.assetReferences)

            val target = Fakes()
            target.restore.run(bytes)
            assertEquals(source.references.all(), target.references.all())
        }
    }

    /**
     * Refuses a reference whose owning asset is not in the destination yet, the shape
     * `BackupUseCasesTest`'s `FkCheckingDefinitionRepository` uses. [owners] is read at the moment
     * of the write, not captured, so it sees exactly what the replay has inserted so far.
     */
    private class OwnerCheckingReferenceRepository(
        private val owners: () -> Set<String>,
    ) : InMemoryReferenceRepository() {
        override suspend fun upsert(reference: AssetReference) {
            check(reference.assetId.value in owners()) {
                "reference ${reference.id.value} names asset ${reference.assetId.value}, " +
                    "which is not inserted yet"
            }
            super.upsert(reference)
        }
    }

    private companion object {
        /** The last format a 1.2.x build could read. Named so the direction claim is explicit. */
        const val LAST_1_2_X_FORMAT = 6
    }
}
