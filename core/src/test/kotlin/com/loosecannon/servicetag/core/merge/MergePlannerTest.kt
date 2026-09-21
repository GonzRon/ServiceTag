package com.loosecannon.servicetag.core.merge

import com.loosecannon.servicetag.core.backup.Backup
import com.loosecannon.servicetag.core.backup.BackupData
import com.loosecannon.servicetag.core.backup.BackupManifest
import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.model.AttachmentId
import com.loosecannon.servicetag.core.model.AttachmentKind
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.ConsumableUsage
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.DefinitionKind
import com.loosecannon.servicetag.core.model.DerivedFormula
import com.loosecannon.servicetag.core.model.DerivedSpec
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.EventProfile
import com.loosecannon.servicetag.core.model.EventSource
import com.loosecannon.servicetag.core.model.ExternalLink
import com.loosecannon.servicetag.core.model.LinkId
import com.loosecannon.servicetag.core.model.LinkKind
import com.loosecannon.servicetag.core.model.Measurement
import com.loosecannon.servicetag.core.model.MeasurementDefinition
import com.loosecannon.servicetag.core.model.PayloadFormat
import com.loosecannon.servicetag.core.model.ProfileConsumable
import com.loosecannon.servicetag.core.model.ProfileField
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.model.TagTarget
import com.loosecannon.servicetag.core.model.ValueType
import com.loosecannon.servicetag.core.ports.StoredBytes
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * 1.1.0 (#46), semantics from #44 — the merge planner, as a function.
 *
 * Every case here is (archive, snapshot) in and a plan out. No repository, no transaction, no zip:
 * the planner is pure precisely so that the rules #44 spends two pages on can be asserted as
 * values, one rule per case, and so that a future reader can see what each rule *is* rather than
 * what a database did.
 *
 * Two cases are deliberate **negative controls** — `the physical uid is never identity` and
 * `a matching name alone is neither identity nor a hint` — and they are labelled so, because a
 * reader should not mistake them for behaviour tests. **Seven** more are **planner guards**: each
 * asserts a rule against an archive or a snapshot the codec or the schema would not allow, so that
 * **fixture** cannot arrive through the API in 1.1.0. Each one says so in its own KDoc and names the
 * line that refuses it, because a reader should not mistake a guard for a live path either.
 *
 * A guard label is a claim about the fixture, **not always about the reason code**: a DERIVED
 * definition's `OWNER_NOT_AVAILABLE` has a guard arm — a source that is nowhere at all — *and* a
 * live arm — a source the archive carries that then loses a key collision — and both are tested,
 * the way `PROFILE_FIELD_DEFINITION_TAKEN` also distinguishes its unreachable half from its live
 * one. The guards are kept because the planner must not depend on the codec for its own invariants,
 * and because #44's slice B makes several of them live.
 *
 * The end-to-end half — the real codec, the fakes, one transaction, the refusals — is
 * `ImportBackupMergeTest`.
 */
class MergePlannerTest {

    // --- fixtures ---------------------------------------------------------------------------

    private fun asset(
        id: String,
        name: String,
        parent: String? = null,
        manufacturer: String = "",
        model: String = "",
        serial: String = "",
        updatedAt: Long = 2L,
    ) = Asset(
        id = AssetId(id), name = name, createdAt = 1L, updatedAt = updatedAt,
        parentAssetId = parent?.let(::AssetId),
        manufacturer = manufacturer, model = model, serialNumber = serial,
    )

    private fun tag(
        id: String,
        key: String,
        assetId: String?,
        linkId: String? = null,
        physicalUid: String? = null,
        label: String? = null,
    ) = TagBinding(
        id = TagId(id),
        payloadFormat = PayloadFormat.V1,
        payloadKey = key,
        target = when {
            assetId != null -> TagTarget.AssetTarget(AssetId(assetId))
            linkId != null -> TagTarget.LinkTarget(LinkId(linkId))
            else -> TagTarget.None
        },
        label = label,
        physicalUid = physicalUid,
        createdAt = 3L,
        updatedAt = 4L,
    )

    private fun link(id: String, assetId: String? = null) = ExternalLink(
        id = LinkId(id), assetId = assetId?.let(::AssetId), kind = LinkKind.JOPLIN,
        label = "note $id", uri = "joplin://$id", createdAt = 5L, updatedAt = 6L,
    )

    private fun definition(
        id: String,
        assetId: String,
        key: String = "key_$id",
        derived: DerivedSpec? = null,
    ) = MeasurementDefinition(
        id = DefinitionId(id), assetId = AssetId(assetId), key = key, label = "Label $id",
        unit = "", valueType = ValueType.NUMBER, decimals = 1, rangeLow = null, rangeHigh = null,
        isMeter = false, sortOrder = 0, archivedAt = null, createdAt = 1L, updatedAt = 2L,
        kind = if (derived == null) DefinitionKind.ENTERED else DefinitionKind.DERIVED,
        derived = derived,
    )

    private fun field(id: String, definitionId: String, sortOrder: Int = 0) =
        ProfileField(id = id, definitionId = DefinitionId(definitionId), required = false, sortOrder = sortOrder)

    /** A profile's consumable: the fourth child list, and the second of the four durable child ids. */
    private fun consumable(id: String, sortOrder: Int = 0) = ProfileConsumable(
        id = id, name = "Cartridge", defaultQuantity = 1.0, unit = "ea", sortOrder = sortOrder,
    )

    private fun profile(
        id: String,
        assetId: String,
        fields: List<ProfileField> = emptyList(),
        consumables: List<ProfileConsumable> = emptyList(),
    ) = EventProfile(
        id = ProfileId(id), assetId = AssetId(assetId), name = "Action $id",
        eventKind = EventKind.MEASUREMENT, defaultTitle = "Action $id", templateKey = null,
        sortOrder = 0, archivedAt = null, createdAt = 1L, updatedAt = 2L,
        fields = fields, consumables = consumables,
    )

    private fun measurement(id: String, definitionId: String) = Measurement(
        id = id, definitionId = DefinitionId(definitionId), valueNum = 7.4, valueText = null,
        unit = "", sortOrder = 0,
    )

    /** An event's consumable use: the last of the four durable child ids. */
    private fun usage(id: String, sortOrder: Int = 0) = ConsumableUsage(
        id = id, name = "Cartridge", quantity = 1.0, unit = "ea", sortOrder = sortOrder,
    )

    private fun event(
        id: String,
        assetId: String,
        title: String = "Filter change",
        sourceRef: String? = null,
        profileId: String? = null,
        measurements: List<Measurement> = emptyList(),
        consumables: List<ConsumableUsage> = emptyList(),
    ) = AssetEvent(
        id = EventId(id), assetId = AssetId(assetId), kind = EventKind.MAINTENANCE, title = title,
        profileId = profileId?.let(::ProfileId),
        occurredOn = "2026-09-21", occurredTime = null, tzId = "UTC", notes = "",
        source = EventSource.MANUAL, sourceRef = sourceRef, createdAt = 7L, updatedAt = 8L,
        measurements = measurements, consumables = consumables,
    )

    /** Four bytes, and the sha256 the row must carry for them. */
    private val bytes = byteArrayOf(1, 2, 3, 4)
    private val bytesSha = "9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a"
    private val storedFour = StoredBytes(sha256 = bytesSha, sizeBytes = 4L)

    private fun attachment(
        id: String,
        assetId: String,
        locator: String = "assets/$assetId/$id.pdf",
        sha256: String = bytesSha,
        sizeBytes: Long = 4L,
    ) = Attachment(
        id = AttachmentId(id), owner = AttachmentOwner.OfAsset(AssetId(assetId)),
        kind = AttachmentKind.DOCUMENT, displayName = "Manual.pdf",
        mimeType = "application/pdf", sizeBytes = sizeBytes, sha256 = sha256,
        storageLocator = locator, capturedOn = null, createdAt = 9L, updatedAt = 10L,
    )

    /**
     * The other arm of [AttachmentOwner], which the asset-owned fixture above cannot reach: a row
     * that belongs to an **event**. `AttachmentLocator.dirFor` puts those under `events/<id>`, so
     * the default locator is the codec-shaped one for this owner.
     */
    private fun eventAttachment(
        id: String,
        eventId: String,
        locator: String = "events/$eventId/$id.pdf",
    ) = Attachment(
        id = AttachmentId(id), owner = AttachmentOwner.OfEvent(EventId(eventId)),
        kind = AttachmentKind.DOCUMENT, displayName = "Manual.pdf",
        mimeType = "application/pdf", sizeBytes = 4L, sha256 = bytesSha,
        storageLocator = locator, capturedOn = null, createdAt = 9L, updatedAt = 10L,
    )

    private fun backupOf(
        assets: List<Asset> = emptyList(),
        tags: List<TagBinding> = emptyList(),
        links: List<ExternalLink> = emptyList(),
        definitions: List<MeasurementDefinition> = emptyList(),
        profiles: List<EventProfile> = emptyList(),
        events: List<AssetEvent> = emptyList(),
        attachments: List<Attachment> = emptyList(),
    ) = Backup(
        manifest = BackupManifest(
            formatVersion = 5, appVersion = "1.1.0", schemaVersion = 5, createdAt = 1L,
            counts = emptyMap(), dataSha256 = "a".repeat(64), backupSetId = "set-incoming",
        ),
        data = BackupData(
            assets = assets.map { it.toDto() },
            nfcTags = tags.map { it.toDto() },
            externalLinks = links.map { it.toDto() },
            measurementDefinitions = definitions.map { it.toDto() },
            eventProfiles = profiles.map { it.toDto() },
            assetEvents = events.map { it.toDto() },
            attachments = attachments.map { it.toDto() },
        ),
    )

    private fun snapshotOf(
        assets: List<Asset> = emptyList(),
        tags: List<TagBinding> = emptyList(),
        links: List<ExternalLink> = emptyList(),
        definitions: List<MeasurementDefinition> = emptyList(),
        profiles: List<EventProfile> = emptyList(),
        events: List<AssetEvent> = emptyList(),
        attachments: List<Attachment> = emptyList(),
        storedBytes: Map<String, StoredBytes> = emptyMap(),
        attachmentStoreConfigured: Boolean = true,
    ) = MergeSnapshot(
        assets, tags, links, definitions, profiles, events, attachments,
        storedBytes, attachmentStoreConfigured,
    )

    /** The one decision the plan reached about [id] in [table]. */
    private fun MergePlan.decision(table: MergeTable, id: String): MergeDecision =
        decisions.single { it.table == table && it.id == id }

    // --- the conflict-free union ------------------------------------------------------------

    /** #44 acceptance 1 and 2: disjoint graphs merge, and every imported UUID survives. */
    @Test
    fun `a disjoint archive is all INSERT and applicable`() {
        val plan = mergePlanOf(
            backupOf(
                assets = listOf(asset("a1", "Hot tub"), asset("a2", "Filter", parent = "a1")),
                tags = listOf(tag("t1", "key-1", "a1")),
                definitions = listOf(definition("d1", "a1")),
                profiles = listOf(profile("p1", "a1", listOf(field("pf1", "d1")))),
                events = listOf(event("e1", "a1")),
            ),
            snapshotOf(assets = listOf(asset("z9", "Generator"))),
        )

        assertTrue(plan.applicable)
        assertEquals(emptyList(), plan.conflicts)
        assertEquals(MergeTally(insert = 2, identical = 0, conflict = 0, skipped = 0), plan.tally(MergeTable.ASSETS))
        assertEquals(MergeTally(1, 0, 0, 0), plan.tally(MergeTable.TAGS))
        assertEquals(MergeTally(1, 0, 0, 0), plan.tally(MergeTable.DEFINITIONS))
        assertEquals(MergeTally(1, 0, 0, 0), plan.tally(MergeTable.PROFILES))
        assertEquals(MergeTally(1, 0, 0, 0), plan.tally(MergeTable.EVENTS))
        assertEquals(listOf("a1", "a2"), plan.writes.assets.map { it.id.value })
        assertEquals(listOf("t1"), plan.writes.tags.map { it.id.value })
    }

    /** Parents before children, and ENTERED definitions before the DERIVED ones that read them. */
    @Test
    fun `writes are ordered so that every reference resolves as it is applied`() {
        val plan = mergePlanOf(
            backupOf(
                // Ids chosen so plain id order is the wrong order in both tables.
                assets = listOf(asset("a2", "Filter", parent = "a9"), asset("a9", "Hot tub")),
                definitions = listOf(
                    definition("d1", "a9", derived = DerivedSpec(DerivedFormula.PERCENT_DROP, DefinitionId("d2"), DefinitionId("d3"))),
                    definition("d2", "a9"),
                    definition("d3", "a9"),
                ),
            ),
            snapshotOf(),
        )

        assertTrue(plan.applicable)
        assertEquals(listOf("a9", "a2"), plan.writes.assets.map { it.id.value })
        assertEquals(listOf("d2", "d3", "d1"), plan.writes.definitions.map { it.id.value })
    }

    // --- the same id ------------------------------------------------------------------------

    /**
     * #44 acceptance 4: the same archive twice is a no-op.
     *
     * The row carries a full manufacturer/model/serial signature on purpose, so this also pins the
     * one line that stops an asset being reported as a duplicate of *itself* — without which every
     * idempotent re-import would hand the owner a review hint per asset.
     */
    @Test
    fun `rows already here with identical content are IDENTICAL and nothing is written`() {
        val rows = listOf(asset("a1", "Hot tub", manufacturer = "Cub Cadet", model = "XT1", serial = "SN-7"))
        val plan = mergePlanOf(backupOf(assets = rows), snapshotOf(assets = rows))

        assertTrue(plan.applicable)
        assertEquals(MergeTally(0, 1, 0, 0), plan.tally(MergeTable.ASSETS))
        assertEquals(emptyList(), plan.writes.assets)
        assertEquals(emptyList(), plan.duplicateCandidates)
    }

    /** #44 acceptance 5: divergence is a conflict, and `updatedAt` never picks a winner. */
    @Test
    fun `the same id with different content is a CONFLICT`() {
        val plan = mergePlanOf(
            backupOf(assets = listOf(asset("a1", "Hot tub"))),
            snapshotOf(assets = listOf(asset("a1", "Spa"))),
        )

        assertFalse(plan.applicable)
        assertEquals(
            MergeDecision(MergeTable.ASSETS, "a1", MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, "a1"),
            plan.decision(MergeTable.ASSETS, "a1"),
        )
        assertEquals(emptyList(), plan.writes.assets)
    }

    /**
     * The strict reading of canonical content, ratified: every backup-format field is compared,
     * `createdAt` and `updatedAt` included. A row that differs only in a timestamp is a conflict,
     * because two installs that both touched it have both touched it.
     */
    @Test
    fun `a row that differs only in updatedAt is still a CONFLICT`() {
        val plan = mergePlanOf(
            backupOf(assets = listOf(asset("a1", "Hot tub", updatedAt = 50L))),
            snapshotOf(assets = listOf(asset("a1", "Hot tub", updatedAt = 99L))),
        )

        assertFalse(plan.applicable)
        assertEquals(MergeReason.CONTENT_DIFFERS, plan.decision(MergeTable.ASSETS, "a1").reason)
    }

    /** #44 acceptance 10: different event ids are different records, however alike they look. */
    @Test
    fun `events with different ids are both kept even when every visible field matches`() {
        val plan = mergePlanOf(
            backupOf(assets = listOf(asset("a1", "Hot tub")), events = listOf(event("e-import", "a1"))),
            snapshotOf(assets = listOf(asset("a1", "Hot tub")), events = listOf(event("e-local", "a1"))),
        )

        assertTrue(plan.applicable)
        assertEquals(MergeTally(1, 0, 0, 0), plan.tally(MergeTable.EVENTS))
        assertEquals(listOf("e-import"), plan.writes.events.map { it.id.value })
    }

    /**
     * Decision 4: a `sortOrder` tie is broken by the child's id, so two sides that stored the same
     * two fields in opposite order still compare equal. Without the tie-break this is
     * `CONTENT_DIFFERS` on one machine and `IDENTICAL` on another.
     */
    @Test
    fun `a sortOrder tie in a child list is broken by the child id`() {
        val incoming = profile("p1", "a1", listOf(field("pf-a", "d1", sortOrder = 0), field("pf-b", "d2", sortOrder = 0)))
        val local = profile("p1", "a1", listOf(field("pf-b", "d2", sortOrder = 0), field("pf-a", "d1", sortOrder = 0)))
        val plan = mergePlanOf(
            backupOf(assets = listOf(asset("a1", "Hot tub")), profiles = listOf(incoming)),
            snapshotOf(
                assets = listOf(asset("a1", "Hot tub")),
                definitions = listOf(definition("d1", "a1"), definition("d2", "a1")),
                profiles = listOf(local),
            ),
        )

        assertTrue(plan.applicable)
        assertEquals(MergeVerdict.IDENTICAL, plan.decision(MergeTable.PROFILES, "p1").verdict)
    }

    // --- NFC payload identity ---------------------------------------------------------------

    /** #44 case 1 / acceptance 6: the same tag under a different row id, bound the same way. */
    @Test
    fun `the same payload identity under a different row id and the same asset is IDENTICAL`() {
        val plan = mergePlanOf(
            backupOf(assets = listOf(asset("a1", "Hot tub")), tags = listOf(tag("t-import", "key-1", "a1"))),
            snapshotOf(assets = listOf(asset("a1", "Hot tub")), tags = listOf(tag("t-local", "key-1", "a1"))),
        )

        assertTrue(plan.applicable)
        assertEquals(
            MergeDecision(
                MergeTable.TAGS, "t-import", MergeVerdict.IDENTICAL,
                MergeReason.PAYLOAD_HELD_BY_AN_EQUIVALENT_LOCAL_TAG, "t-local",
            ),
            plan.decision(MergeTable.TAGS, "t-import"),
        )
        assertEquals(emptyList(), plan.writes.tags)
    }

    /** #44 case 3 / acceptance 6: the same tag pointing at two different things is refused. */
    @Test
    fun `the same payload identity bound to another asset is a CONFLICT`() {
        val plan = mergePlanOf(
            backupOf(
                assets = listOf(asset("a1", "Hot tub")),
                tags = listOf(tag("t-import", "key-1", "a1")),
            ),
            snapshotOf(
                assets = listOf(asset("a1", "Hot tub"), asset("a2", "Mower")),
                tags = listOf(tag("t-local", "key-1", "a2")),
            ),
        )

        assertFalse(plan.applicable)
        assertEquals(
            MergeDecision(
                MergeTable.TAGS, "t-import", MergeVerdict.CONFLICT,
                MergeReason.PAYLOAD_BOUND_TO_ANOTHER_ASSET, "t-local",
            ),
            plan.decision(MergeTable.TAGS, "t-import"),
        )
    }

    /** Same payload, same asset, some other field diverged: still refused, with its own code. */
    @Test
    fun `the same payload identity on a diverged local tag is a CONFLICT of its own kind`() {
        val plan = mergePlanOf(
            backupOf(assets = listOf(asset("a1", "Hot tub")), tags = listOf(tag("t-import", "key-1", "a1", label = "Lid"))),
            snapshotOf(assets = listOf(asset("a1", "Hot tub")), tags = listOf(tag("t-local", "key-1", "a1", label = "Side"))),
        )

        assertFalse(plan.applicable)
        assertEquals(
            MergeReason.PAYLOAD_HELD_BY_A_DIVERGED_LOCAL_TAG,
            plan.decision(MergeTable.TAGS, "t-import").reason,
        )
    }

    /** The unique index does not care which side the duplicate came from. */
    @Test
    fun `two tags in one archive sharing a payload identity is a CONFLICT`() {
        val plan = mergePlanOf(
            backupOf(
                assets = listOf(asset("a1", "Hot tub")),
                tags = listOf(tag("t1", "key-1", "a1"), tag("t2", "key-1", "a1")),
            ),
            snapshotOf(),
        )

        assertFalse(plan.applicable)
        assertEquals(MergeVerdict.INSERT, plan.decision(MergeTable.TAGS, "t1").verdict)
        assertEquals(
            MergeReason.PAYLOAD_DUPLICATED_IN_ARCHIVE,
            plan.decision(MergeTable.TAGS, "t2").reason,
        )
    }

    /**
     * **Negative control.** #44 acceptance 7: the hardware UID is informational and is never
     * identity. This case passes trivially if no pass reads `physicalUid` — which is the point, and
     * the grep in Task 6 Step 3 is what actually enforces it. It is here so that a future pass that
     * *did* key on the UID would have to delete a test to ship.
     */
    @Test
    fun `the physical uid is never identity`() {
        val plan = mergePlanOf(
            backupOf(assets = listOf(asset("a1", "Hot tub")), tags = listOf(tag("t1", "key-1", "a1", physicalUid = "04a1"))),
            snapshotOf(assets = listOf(asset("a1", "Hot tub")), tags = listOf(tag("t9", "key-9", "a1", physicalUid = "04a1"))),
        )

        // Same hardware UID, different payload identity: two different tags, and the import inserts.
        assertTrue(plan.applicable)
        assertEquals(MergeVerdict.INSERT, plan.decision(MergeTable.TAGS, "t1").verdict)
    }

    // --- the schema's five unique indices ---------------------------------------------------

    @Test
    fun `a definition whose key is taken on the same asset is a CONFLICT`() {
        val plan = mergePlanOf(
            backupOf(
                assets = listOf(asset("a1", "Hot tub")),
                definitions = listOf(definition("d-import", "a1", key = "ph")),
            ),
            snapshotOf(
                assets = listOf(asset("a1", "Hot tub")),
                definitions = listOf(definition("d-local", "a1", key = "ph")),
            ),
        )

        assertFalse(plan.applicable)
        assertEquals(
            MergeDecision(
                MergeTable.DEFINITIONS, "d-import", MergeVerdict.CONFLICT,
                MergeReason.DEFINITION_KEY_TAKEN, "d-local",
            ),
            plan.decision(MergeTable.DEFINITIONS, "d-import"),
        )
    }

    /** Decision 6: the same index, the archive's own two rows. */
    @Test
    fun `two definitions in one archive with the same key on one asset is a CONFLICT`() {
        val plan = mergePlanOf(
            backupOf(
                assets = listOf(asset("a1", "Hot tub")),
                definitions = listOf(definition("d1", "a1", key = "ph"), definition("d2", "a1", key = "ph")),
            ),
            snapshotOf(),
        )

        assertFalse(plan.applicable)
        assertEquals(MergeVerdict.INSERT, plan.decision(MergeTable.DEFINITIONS, "d1").verdict)
        assertEquals(
            MergeDecision(
                MergeTable.DEFINITIONS, "d2", MergeVerdict.CONFLICT,
                MergeReason.DEFINITION_KEY_TAKEN, "d1",
            ),
            plan.decision(MergeTable.DEFINITIONS, "d2"),
        )
    }

    /** #44 acceptance 11: a source/sourceRef collision is found before the commit. */
    @Test
    fun `an event whose source and sourceRef are taken is a CONFLICT`() {
        val plan = mergePlanOf(
            backupOf(assets = listOf(asset("a1", "Hot tub")), events = listOf(event("e-import", "a1", sourceRef = "ref-1"))),
            snapshotOf(assets = listOf(asset("a1", "Hot tub")), events = listOf(event("e-local", "a1", sourceRef = "ref-1"))),
        )

        assertFalse(plan.applicable)
        assertEquals(
            MergeReason.EVENT_SOURCE_REF_TAKEN,
            plan.decision(MergeTable.EVENTS, "e-import").reason,
        )
    }

    @Test
    fun `two events in one archive with the same sourceRef is a CONFLICT`() {
        val plan = mergePlanOf(
            backupOf(
                assets = listOf(asset("a1", "Hot tub")),
                events = listOf(event("e1", "a1", sourceRef = "ref-1"), event("e2", "a1", sourceRef = "ref-1")),
            ),
            snapshotOf(),
        )

        assertFalse(plan.applicable)
        assertEquals(MergeVerdict.INSERT, plan.decision(MergeTable.EVENTS, "e1").verdict)
        assertEquals(
            MergeDecision(MergeTable.EVENTS, "e2", MergeVerdict.CONFLICT, MergeReason.EVENT_SOURCE_REF_TAKEN, "e1"),
            plan.decision(MergeTable.EVENTS, "e2"),
        )
    }

    /** A null sourceRef cannot collide: SQLite treats NULLs as distinct in a unique index. */
    @Test
    fun `events with no sourceRef never collide on it`() {
        val plan = mergePlanOf(
            backupOf(assets = listOf(asset("a1", "Hot tub")), events = listOf(event("e1", "a1"), event("e2", "a1"))),
            snapshotOf(assets = listOf(asset("a1", "Hot tub")), events = listOf(event("e9", "a1"))),
        )

        assertTrue(plan.applicable)
        assertEquals(MergeTally(2, 0, 0, 0), plan.tally(MergeTable.EVENTS))
    }

    /**
     * The fifth unique index, `profile_field(profile_id, definition_id)` (`JournalEntities.kt:113`).
     * `BackupCodec.decode` does not check the pair — it checks field-*id* uniqueness and that each
     * field's definition belongs to the asset — so this archive decodes cleanly and would otherwise
     * die on the insert. The product guards the same shape at `SaveProfile.kt:67`.
     */
    @Test
    fun `a profile listing one definition twice is a CONFLICT`() {
        val plan = mergePlanOf(
            backupOf(
                assets = listOf(asset("a1", "Hot tub")),
                definitions = listOf(definition("d1", "a1")),
                profiles = listOf(profile("p1", "a1", listOf(field("pf1", "d1"), field("pf2", "d1", sortOrder = 1)))),
            ),
            snapshotOf(),
        )

        assertFalse(plan.applicable)
        assertEquals(
            MergeDecision(
                MergeTable.PROFILES, "p1", MergeVerdict.CONFLICT,
                MergeReason.PROFILE_FIELD_DEFINITION_TAKEN, "d1",
            ),
            plan.decision(MergeTable.PROFILES, "p1"),
        )
    }

    /**
     * The other half of that index, and why the key is the *pair*: two different profiles may each
     * offer the same reading, and must not be refused for it. Without this case a fix for the one
     * above could plausibly key on the definition alone and still look green.
     */
    @Test
    fun `two profiles may each offer the same definition`() {
        val plan = mergePlanOf(
            backupOf(
                assets = listOf(asset("a1", "Hot tub")),
                definitions = listOf(definition("d1", "a1")),
                profiles = listOf(
                    profile("p1", "a1", listOf(field("pf1", "d1"))),
                    profile("p2", "a1", listOf(field("pf2", "d1"))),
                ),
            ),
            snapshotOf(),
        )

        assertTrue(plan.applicable)
        assertEquals(MergeTally(2, 0, 0, 0), plan.tally(MergeTable.PROFILES))
    }

    /**
     * #44: "never overwrite different local bytes merely because a locator collides".
     *
     * **Planner guard.** Through the API this arm cannot fire: `BackupCodec.kt:392` requires
     * `AttachmentLocator.matchesShape`, and that shape embeds the row's own id
     * (`model/Attachment.kt:78`–`80`), so a locator collision implies an id collision — which the
     * `local != null` pair answers two arms earlier — and an archive-internal duplicate locator is
     * refused by `BackupCodec.kt:397`–`399`. The fixture's shared locator is therefore *deliberately
     * not* codec-shaped, and cannot be: two different rows sharing a codec-shaped locator is the
     * very thing the shape makes impossible. Kept, and tested, because the planner must not depend
     * on the codec for its own invariants and because slice B's owner remapping makes it live.
     */
    @Test
    fun `an attachment whose locator is claimed by another row is a CONFLICT`() {
        val locator = "assets/a1/shared.pdf"
        val plan = mergePlanOf(
            backupOf(
                assets = listOf(asset("a1", "Hot tub")),
                attachments = listOf(attachment("att-import", "a1", locator)),
            ),
            snapshotOf(
                assets = listOf(asset("a1", "Hot tub")),
                attachments = listOf(attachment("att-local", "a1", locator)),
                storedBytes = mapOf(locator to storedFour),
            ),
        )

        assertFalse(plan.applicable)
        assertEquals(
            MergeDecision(
                MergeTable.ATTACHMENTS, "att-import", MergeVerdict.CONFLICT,
                MergeReason.ATTACHMENT_LOCATOR_TAKEN, "att-local",
            ),
            plan.decision(MergeTable.ATTACHMENTS, "att-import"),
        )
    }

    // --- the four aggregate child-row primary keys ------------------------------------------

    /**
     * `profile_field.id` is a durable primary key of its own (`core/model/Journal.kt:28`–`30`), and
     * `decode` only checks it is unique *within the file*. Two distinct profiles — one local, one
     * incoming — can therefore carry the same field id, which is a constraint failure inside the
     * transaction unless the plan catches it.
     */
    @Test
    fun `a profile whose field id is held by another local profile is a CONFLICT`() {
        val plan = mergePlanOf(
            backupOf(
                assets = listOf(asset("a1", "Hot tub")),
                definitions = listOf(definition("d1", "a1")),
                profiles = listOf(profile("p-import", "a1", listOf(field("pf-shared", "d1")))),
            ),
            snapshotOf(
                assets = listOf(asset("a1", "Hot tub")),
                definitions = listOf(definition("d1", "a1")),
                profiles = listOf(profile("p-local", "a1", listOf(field("pf-shared", "d1")))),
            ),
        )

        assertFalse(plan.applicable)
        assertEquals(
            MergeDecision(
                MergeTable.PROFILES, "p-import", MergeVerdict.CONFLICT,
                MergeReason.CHILD_ROW_ID_TAKEN, "pf-shared",
            ),
            plan.decision(MergeTable.PROFILES, "p-import"),
        )
    }

    @Test
    fun `an event whose measurement id is held by another local event is a CONFLICT`() {
        val plan = mergePlanOf(
            backupOf(
                assets = listOf(asset("a1", "Hot tub")),
                definitions = listOf(definition("d1", "a1")),
                events = listOf(event("e-import", "a1", measurements = listOf(measurement("m-shared", "d1")))),
            ),
            snapshotOf(
                assets = listOf(asset("a1", "Hot tub")),
                definitions = listOf(definition("d1", "a1")),
                events = listOf(event("e-local", "a1", measurements = listOf(measurement("m-shared", "d1")))),
            ),
        )

        assertFalse(plan.applicable)
        assertEquals(
            MergeDecision(
                MergeTable.EVENTS, "e-import", MergeVerdict.CONFLICT,
                MergeReason.CHILD_ROW_ID_TAKEN, "m-shared",
            ),
            plan.decision(MergeTable.EVENTS, "e-import"),
        )
    }

    /** The third durable child id, `profile_consumable.id`, and its own claim map. */
    @Test
    fun `a profile whose consumable id is held by another local profile is a CONFLICT`() {
        val plan = mergePlanOf(
            backupOf(
                assets = listOf(asset("a1", "Hot tub")),
                profiles = listOf(profile("p-import", "a1", consumables = listOf(consumable("pc-shared")))),
            ),
            snapshotOf(
                assets = listOf(asset("a1", "Hot tub")),
                profiles = listOf(profile("p-local", "a1", consumables = listOf(consumable("pc-shared")))),
            ),
        )

        assertFalse(plan.applicable)
        assertEquals(
            MergeDecision(
                MergeTable.PROFILES, "p-import", MergeVerdict.CONFLICT,
                MergeReason.CHILD_ROW_ID_TAKEN, "pc-shared",
            ),
            plan.decision(MergeTable.PROFILES, "p-import"),
        )
    }

    /** The fourth, `consumable_usage.id`. Four child keys, four claim maps, four cases. */
    @Test
    fun `an event whose consumable usage id is held by another local event is a CONFLICT`() {
        val plan = mergePlanOf(
            backupOf(
                assets = listOf(asset("a1", "Hot tub")),
                events = listOf(event("e-import", "a1", consumables = listOf(usage("cu-shared")))),
            ),
            snapshotOf(
                assets = listOf(asset("a1", "Hot tub")),
                events = listOf(event("e-local", "a1", consumables = listOf(usage("cu-shared")))),
            ),
        )

        assertFalse(plan.applicable)
        assertEquals(
            MergeDecision(
                MergeTable.EVENTS, "e-import", MergeVerdict.CONFLICT,
                MergeReason.CHILD_ROW_ID_TAKEN, "cu-shared",
            ),
            plan.decision(MergeTable.EVENTS, "e-import"),
        )
    }

    // --- the links table, which travels in every real archive as a 2.6 tombstone ------------

    /**
     * The tombstone rows merge like any other table's, and diverge like any other table's. Both
     * arms in one case, because a table with only an INSERT case has no divergence coverage at all.
     */
    @Test
    fun `a link already here is IDENTICAL, and a diverged one is a CONFLICT`() {
        val plan = mergePlanOf(
            backupOf(links = listOf(link("l1"), link("l2"))),
            snapshotOf(links = listOf(link("l1").copy(label = "Spa manual"), link("l2"))),
        )

        assertFalse(plan.applicable)
        assertEquals(MergeTally(insert = 0, identical = 1, conflict = 1, skipped = 0), plan.tally(MergeTable.LINKS))
        assertEquals(
            MergeDecision(MergeTable.LINKS, "l1", MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, "l1"),
            plan.decision(MergeTable.LINKS, "l1"),
        )
        assertEquals(MergeVerdict.IDENTICAL, plan.decision(MergeTable.LINKS, "l2").verdict)
    }

    // --- foreign keys and the tree ----------------------------------------------------------

    @Test
    fun `a contested parent does not blame its child`() {
        val plan = mergePlanOf(
            backupOf(
                assets = listOf(asset("a1", "Hot tub"), asset("a2", "Filter", parent = "a1")),
            ),
            snapshotOf(assets = listOf(asset("a1", "Spa"))),
        )

        assertFalse(plan.applicable)
        assertEquals(MergeReason.CONTENT_DIFFERS, plan.decision(MergeTable.ASSETS, "a1").reason)
        // `a1` exists locally, so `a2`'s foreign key does resolve: it is insertable, and it is the
        // parent that is contested. The plan says so instead of blaming the child.
        assertEquals(MergeVerdict.INSERT, plan.decision(MergeTable.ASSETS, "a2").verdict)
        // And nothing is written, because one conflict empties the whole write set.
        assertEquals(MergeWrites(), plan.writes)
    }

    /**
     * Decision 8's **live** shape: the cascade. `decode` refuses a genuinely dangling reference, so
     * through the API `OWNER_NOT_AVAILABLE` can only fire when the referenced row *is* in the
     * archive and was not accepted. Here the definition's key is taken, so the profile whose field
     * names it has nothing to point at.
     */
    @Test
    fun `a profile whose field names a conflicted definition is OWNER_NOT_AVAILABLE`() {
        val plan = mergePlanOf(
            backupOf(
                assets = listOf(asset("a1", "Hot tub")),
                definitions = listOf(definition("d1", "a1", key = "ph")),
                profiles = listOf(profile("p1", "a1", listOf(field("pf1", "d1")))),
            ),
            snapshotOf(
                assets = listOf(asset("a1", "Hot tub")),
                definitions = listOf(definition("d-local", "a1", key = "ph")),
            ),
        )

        assertFalse(plan.applicable)
        assertEquals(MergeReason.DEFINITION_KEY_TAKEN, plan.decision(MergeTable.DEFINITIONS, "d1").reason)
        assertEquals(
            MergeDecision(
                MergeTable.PROFILES, "p1", MergeVerdict.CONFLICT,
                MergeReason.OWNER_NOT_AVAILABLE, "d1",
            ),
            plan.decision(MergeTable.PROFILES, "p1"),
        )
    }

    /**
     * **Planner guard.** The flat form of the same rule, on an archive `BackupCodec.decode` would
     * refuse outright (`BackupCodec.kt:274`–`279`). Kept because the planner must not depend on the
     * codec for its own invariants; not reachable through the API.
     */
    @Test
    fun `a definition on an asset that is nowhere is OWNER_NOT_AVAILABLE`() {
        val plan = mergePlanOf(
            backupOf(definitions = listOf(definition("d1", "a-missing"))),
            snapshotOf(),
        )

        assertFalse(plan.applicable)
        assertEquals(
            MergeDecision(
                MergeTable.DEFINITIONS, "d1", MergeVerdict.CONFLICT,
                MergeReason.OWNER_NOT_AVAILABLE, "a-missing",
            ),
            plan.decision(MergeTable.DEFINITIONS, "d1"),
        )
    }

    /**
     * A second **live** cascade: the event names a profile that *is* in the archive — which is what
     * `decode` requires — and that profile was refused for listing one reading twice. So the event
     * has no profile to point at, and says so with the profile's id.
     */
    @Test
    fun `an event whose profile was not accepted is OWNER_NOT_AVAILABLE`() {
        val plan = mergePlanOf(
            backupOf(
                assets = listOf(asset("a1", "Hot tub")),
                definitions = listOf(definition("d1", "a1")),
                profiles = listOf(profile("p1", "a1", listOf(field("pf1", "d1"), field("pf2", "d1", sortOrder = 1)))),
                events = listOf(event("e1", "a1", profileId = "p1")),
            ),
            snapshotOf(),
        )

        assertFalse(plan.applicable)
        assertEquals(MergeReason.PROFILE_FIELD_DEFINITION_TAKEN, plan.decision(MergeTable.PROFILES, "p1").reason)
        assertEquals(
            MergeDecision(
                MergeTable.EVENTS, "e1", MergeVerdict.CONFLICT,
                MergeReason.OWNER_NOT_AVAILABLE, "p1",
            ),
            plan.decision(MergeTable.EVENTS, "e1"),
        )
    }

    /**
     * The third **live** cascade, and the reason a measurement's definition is checked separately
     * from the event's asset: the definition's key is taken, so the reading the event carries names
     * something that will not exist.
     */
    @Test
    fun `an event whose measurement names a conflicted definition is OWNER_NOT_AVAILABLE`() {
        val plan = mergePlanOf(
            backupOf(
                assets = listOf(asset("a1", "Hot tub")),
                definitions = listOf(definition("d1", "a1", key = "ph")),
                events = listOf(event("e1", "a1", measurements = listOf(measurement("m1", "d1")))),
            ),
            snapshotOf(
                assets = listOf(asset("a1", "Hot tub")),
                definitions = listOf(definition("d-local", "a1", key = "ph")),
            ),
        )

        assertFalse(plan.applicable)
        assertEquals(MergeReason.DEFINITION_KEY_TAKEN, plan.decision(MergeTable.DEFINITIONS, "d1").reason)
        assertEquals(
            MergeDecision(
                MergeTable.EVENTS, "e1", MergeVerdict.CONFLICT,
                MergeReason.OWNER_NOT_AVAILABLE, "d1",
            ),
            plan.decision(MergeTable.EVENTS, "e1"),
        )
    }

    /**
     * **Planner guard — the guard arm of this reason, not the whole of it.** A DERIVED row reads two
     * other definitions, and `d-missing` is in neither the archive nor the destination.
     * `BackupCodec.kt:287`–`295` refuses *that archive* outright (`journal/Derived.kt:51` will not
     * even accept a source that is not ENTERED), so this **fixture** cannot arrive through the API —
     * but the ENTERED-before-DERIVED ordering is only *safe* because the planner checks rather than
     * assumes. The same reason on the same field also has a **live** arm, which is the case below:
     * `derivedProblems` looks only inside the archive, so a source it accepts can still lose a key
     * collision against the destination.
     */
    @Test
    fun `a derived definition whose source is nowhere is OWNER_NOT_AVAILABLE`() {
        val plan = mergePlanOf(
            backupOf(
                assets = listOf(asset("a1", "Hot tub")),
                definitions = listOf(
                    definition("d1", "a1", derived = DerivedSpec(DerivedFormula.PERCENT_DROP, DefinitionId("d2"), DefinitionId("d-missing"))),
                    definition("d2", "a1"),
                ),
            ),
            snapshotOf(),
        )

        assertFalse(plan.applicable)
        assertEquals(MergeVerdict.INSERT, plan.decision(MergeTable.DEFINITIONS, "d2").verdict)
        assertEquals(
            MergeDecision(
                MergeTable.DEFINITIONS, "d1", MergeVerdict.CONFLICT,
                MergeReason.OWNER_NOT_AVAILABLE, "d-missing",
            ),
            plan.decision(MergeTable.DEFINITIONS, "d1"),
        )
    }

    /**
     * The **live** arm of that same reason, and the reason the guard label above is about a fixture
     * rather than a reason code. `journal/Derived.kt`'s `derivedProblems` resolves a DERIVED row's
     * sources inside the **archive** only — it can see neither the destination nor the merge — so an
     * archive holding ENTERED `d2` and DERIVED `d1` sourcing it decodes cleanly, and then `d2` loses
     * a `(assetId, key)` collision against a local row. `d2` never reaches `acceptedDefinitions`
     * and is not `local` either, so `d1` has no source to read and is refused naming `d2`.
     *
     * Structurally the same cascade as `an event whose measurement names a conflicted definition is
     * OWNER_NOT_AVAILABLE`, one level removed — and reachable **today**, not only after slice B.
     * The sibling source `d3` is insertable and says so, which is what makes this a cascade rather
     * than a broken archive; nothing is written anyway, because one conflict empties the write set.
     */
    @Test
    fun `a derived definition whose source lost a key collision is OWNER_NOT_AVAILABLE`() {
        val plan = mergePlanOf(
            backupOf(
                assets = listOf(asset("a1", "Hot tub")),
                definitions = listOf(
                    definition("d1", "a1", derived = DerivedSpec(DerivedFormula.PERCENT_DROP, DefinitionId("d2"), DefinitionId("d3"))),
                    definition("d2", "a1", key = "ph"),
                    definition("d3", "a1", key = "orp"),
                ),
            ),
            snapshotOf(
                assets = listOf(asset("a1", "Hot tub")),
                definitions = listOf(definition("d-local", "a1", key = "ph")),
            ),
        )

        assertFalse(plan.applicable)
        assertEquals(
            MergeDecision(
                MergeTable.DEFINITIONS, "d2", MergeVerdict.CONFLICT,
                MergeReason.DEFINITION_KEY_TAKEN, "d-local",
            ),
            plan.decision(MergeTable.DEFINITIONS, "d2"),
        )
        assertEquals(
            MergeDecision(
                MergeTable.DEFINITIONS, "d1", MergeVerdict.CONFLICT,
                MergeReason.OWNER_NOT_AVAILABLE, "d2",
            ),
            plan.decision(MergeTable.DEFINITIONS, "d1"),
        )
        assertEquals(MergeVerdict.INSERT, plan.decision(MergeTable.DEFINITIONS, "d3").verdict)
        // Decided in `entered + derived` order — d2, d3, then d1 — and reported by id regardless.
        assertEquals(
            listOf(MergeTable.DEFINITIONS to "d1", MergeTable.DEFINITIONS to "d2"),
            plan.conflicts.map { it.table to it.id },
        )
        assertEquals(MergeWrites(), plan.writes)
    }

    /**
     * **Planner guard.** A 2.6 tombstone row still carries an owning asset, and the same rule
     * applies to it as to every other table. `BackupCodec.kt:256`–`261` refuses a link whose asset
     * is not in the file, so through the API this arm is unreachable — and through slice B's
     * remapping it is not.
     */
    @Test
    fun `a link whose owning asset is nowhere is OWNER_NOT_AVAILABLE`() {
        val plan = mergePlanOf(
            backupOf(links = listOf(link("l1", assetId = "a-missing"))),
            snapshotOf(),
        )

        assertFalse(plan.applicable)
        assertEquals(
            MergeDecision(
                MergeTable.LINKS, "l1", MergeVerdict.CONFLICT,
                MergeReason.OWNER_NOT_AVAILABLE, "a-missing",
            ),
            plan.decision(MergeTable.LINKS, "l1"),
        )
    }

    /**
     * **Planner guard.** A tag's asset, checked after every payload question — so a tag with an
     * unavailable owner is reported as an owner problem and not as a payload one. `decode` refuses
     * a dangling tag target (`BackupCodec.kt:200`).
     */
    @Test
    fun `a tag whose asset is nowhere is OWNER_NOT_AVAILABLE`() {
        val plan = mergePlanOf(
            backupOf(tags = listOf(tag("t1", "key-1", "a-missing"))),
            snapshotOf(),
        )

        assertFalse(plan.applicable)
        assertEquals(
            MergeDecision(
                MergeTable.TAGS, "t1", MergeVerdict.CONFLICT,
                MergeReason.OWNER_NOT_AVAILABLE, "a-missing",
            ),
            plan.decision(MergeTable.TAGS, "t1"),
        )
    }

    /**
     * **Planner guard**, and the only case that exercises `TagTarget.LinkTarget` at all: a tag may
     * point at a link instead of an asset, and that reference gets the same rule. `decode` refuses
     * a dangling one; the arm exists because the planner owns its own invariants.
     */
    @Test
    fun `a tag whose link is nowhere is OWNER_NOT_AVAILABLE`() {
        val plan = mergePlanOf(
            backupOf(tags = listOf(tag("t1", "key-1", assetId = null, linkId = "l-missing"))),
            snapshotOf(),
        )

        assertFalse(plan.applicable)
        assertEquals(
            MergeDecision(
                MergeTable.TAGS, "t1", MergeVerdict.CONFLICT,
                MergeReason.OWNER_NOT_AVAILABLE, "l-missing",
            ),
            plan.decision(MergeTable.TAGS, "t1"),
        )
    }

    /**
     * **Planner guard**, and decision 9 says why: the cycle rule cannot fire against an FK-valid
     * destination in 1.1.0, because a local parent chain stays local and terminates at a local
     * root. This snapshot — a local child whose parent is not local — is one the RESTRICT self-FK
     * makes impossible. The guard is kept for slice B's remapping, where it becomes live.
     */
    @Test
    fun `the cycle rule holds even for a snapshot the schema would not allow`() {
        val plan = mergePlanOf(
            backupOf(assets = listOf(asset("a1", "Hot tub", parent = "a2"))),
            snapshotOf(assets = listOf(asset("a2", "Filter", parent = "a1"))),
        )

        assertFalse(plan.applicable)
        assertEquals(MergeReason.PARENT_CYCLE, plan.decision(MergeTable.ASSETS, "a1").reason)
    }

    // --- attachments' bytes -----------------------------------------------------------------

    @Test
    fun `an attachment row is SKIPPED when its bytes are not in the store, and INSERTed when they are`() {
        val here = attachment("att-here", "a1", "assets/a1/here.pdf")
        val gone = attachment("att-gone", "a1", "assets/a1/gone.pdf")
        val plan = mergePlanOf(
            backupOf(assets = listOf(asset("a1", "Hot tub")), attachments = listOf(here, gone)),
            snapshotOf(storedBytes = mapOf("assets/a1/here.pdf" to storedFour)),
        )

        assertTrue(plan.applicable)
        assertEquals(MergeTally(insert = 1, identical = 0, conflict = 0, skipped = 1), plan.tally(MergeTable.ATTACHMENTS))
        assertEquals(
            MergeDecision(
                MergeTable.ATTACHMENTS, "att-gone", MergeVerdict.SKIPPED,
                MergeReason.ATTACHMENT_BYTES_ABSENT, "assets/a1/gone.pdf",
            ),
            plan.decision(MergeTable.ATTACHMENTS, "att-gone"),
        )
        assertEquals(listOf("att-here"), plan.writes.attachments.map { it.id.value })
    }

    /**
     * #44 acceptance 12, *"Verify size + SHA-256 before writing"*. Bytes at the locator that are
     * not the row's bytes are a diverged overlap, not a missing file: writing the row would leave a
     * destination whose recorded hash does not describe what is stored.
     */
    @Test
    fun `an attachment whose stored bytes do not match the row is a CONFLICT`() {
        val locator = "assets/a1/att1.pdf"
        val wrongHash = mergePlanOf(
            backupOf(assets = listOf(asset("a1", "Hot tub")), attachments = listOf(attachment("att1", "a1", locator))),
            snapshotOf(storedBytes = mapOf(locator to StoredBytes(sha256 = "b".repeat(64), sizeBytes = 4L))),
        )
        assertFalse(wrongHash.applicable)
        assertEquals(
            MergeDecision(
                MergeTable.ATTACHMENTS, "att1", MergeVerdict.CONFLICT,
                MergeReason.ATTACHMENT_BYTES_DIFFER, locator,
            ),
            wrongHash.decision(MergeTable.ATTACHMENTS, "att1"),
        )

        val wrongSize = mergePlanOf(
            backupOf(assets = listOf(asset("a1", "Hot tub")), attachments = listOf(attachment("att1", "a1", locator))),
            snapshotOf(storedBytes = mapOf(locator to StoredBytes(sha256 = bytesSha, sizeBytes = 9L))),
        )
        assertFalse(wrongSize.applicable)
        assertEquals(MergeReason.ATTACHMENT_BYTES_DIFFER, wrongSize.decision(MergeTable.ATTACHMENTS, "att1").reason)
    }

    /**
     * Decision 12: no attachment folder is a *configuration* state, not a data state, and it must
     * not be reported as missing bytes. The other six tables still merge and `applicable` stays
     * true.
     */
    @Test
    fun `with no attachment folder every attachment row is SKIPPED with its own reason`() {
        val plan = mergePlanOf(
            backupOf(
                assets = listOf(asset("a1", "Hot tub")),
                attachments = listOf(attachment("att1", "a1")),
            ),
            snapshotOf(attachmentStoreConfigured = false),
        )

        assertTrue(plan.applicable)
        assertEquals(MergeTally(insert = 0, identical = 0, conflict = 0, skipped = 1), plan.tally(MergeTable.ATTACHMENTS))
        assertEquals(
            MergeDecision(
                MergeTable.ATTACHMENTS, "att1", MergeVerdict.SKIPPED,
                MergeReason.ATTACHMENT_STORE_NOT_CONFIGURED, "assets/a1/att1.pdf",
            ),
            plan.decision(MergeTable.ATTACHMENTS, "att1"),
        )
        assertEquals(MergeTally(1, 0, 0, 0), plan.tally(MergeTable.ASSETS))
    }

    /**
     * The other arm of the owner sum type, which every other attachment case leaves untouched: a
     * row owned by an **event**, whose availability is answered against the events pass rather than
     * the assets pass. It is also the only case that reaches `dto.assetId ?: dto.eventId!!`.
     */
    @Test
    fun `an event-owned attachment row is INSERTed when the store holds its bytes`() {
        val plan = mergePlanOf(
            backupOf(
                assets = listOf(asset("a1", "Hot tub")),
                events = listOf(event("e1", "a1")),
                attachments = listOf(eventAttachment("att1", "e1")),
            ),
            snapshotOf(storedBytes = mapOf("events/e1/att1.pdf" to storedFour)),
        )

        assertTrue(plan.applicable)
        assertEquals(MergeTally(1, 0, 0, 0), plan.tally(MergeTable.ATTACHMENTS))
        assertEquals(
            AttachmentOwner.OfEvent(EventId("e1")),
            plan.writes.attachments.single().owner,
        )
    }

    /**
     * The cascade on that arm: the owning event is in the archive and was refused, so the row that
     * hangs off it is `OWNER_NOT_AVAILABLE` and names the **event**. Reachable through the API,
     * because an event can conflict on `(source, sourceRef)` while being absent locally.
     */
    @Test
    fun `an event-owned attachment whose event was not accepted is OWNER_NOT_AVAILABLE`() {
        val plan = mergePlanOf(
            backupOf(
                assets = listOf(asset("a1", "Hot tub")),
                events = listOf(event("e1", "a1", sourceRef = "ref-1")),
                attachments = listOf(eventAttachment("att1", "e1")),
            ),
            snapshotOf(
                assets = listOf(asset("a1", "Hot tub")),
                events = listOf(event("e-local", "a1", sourceRef = "ref-1")),
                storedBytes = mapOf("events/e1/att1.pdf" to storedFour),
            ),
        )

        assertFalse(plan.applicable)
        assertEquals(MergeReason.EVENT_SOURCE_REF_TAKEN, plan.decision(MergeTable.EVENTS, "e1").reason)
        assertEquals(
            MergeDecision(
                MergeTable.ATTACHMENTS, "att1", MergeVerdict.CONFLICT,
                MergeReason.OWNER_NOT_AVAILABLE, "e1",
            ),
            plan.decision(MergeTable.ATTACHMENTS, "att1"),
        )
    }

    // --- hints, and the shape of the report -------------------------------------------------

    /** #44 identity 3 and acceptance 9: a review hint, and no action whatsoever. */
    @Test
    fun `manufacturer model and serial produce a duplicate candidate and change nothing`() {
        val plan = mergePlanOf(
            backupOf(assets = listOf(asset("a-import", "Mower", manufacturer = "Cub Cadet", model = "XT1", serial = "SN-7"))),
            snapshotOf(assets = listOf(asset("a-local", "Garage mower", manufacturer = "Cub Cadet", model = "XT1", serial = "SN-7"))),
        )

        assertTrue(plan.applicable)
        assertEquals(MergeVerdict.INSERT, plan.decision(MergeTable.ASSETS, "a-import").verdict)
        assertEquals(
            listOf(DuplicateCandidate("a-import", "a-local", MergeHint.SAME_MANUFACTURER_MODEL_SERIAL)),
            plan.duplicateCandidates,
        )
    }

    /**
     * **Negative control**, and a real one: a name-based hint or a name-based identity would fail
     * this. #44 identity 4 and acceptance 9.
     */
    @Test
    fun `a matching name alone is neither identity nor a hint`() {
        val plan = mergePlanOf(
            backupOf(assets = listOf(asset("a-import", "Hot tub"))),
            snapshotOf(assets = listOf(asset("a-local", "Hot tub"))),
        )

        assertTrue(plan.applicable)
        assertEquals(emptyList(), plan.duplicateCandidates)
        assertEquals(MergeVerdict.INSERT, plan.decision(MergeTable.ASSETS, "a-import").verdict)
    }

    /** Deterministic reporting: table order, then id, with a stable code on every conflict. */
    @Test
    fun `conflicts are reported in table order then id, and the plan is reproducible`() {
        val backup = backupOf(
            assets = listOf(asset("a2", "Spa"), asset("a1", "Hot tub")),
            definitions = listOf(definition("d1", "a1", key = "ph")),
            events = listOf(event("e1", "a1", sourceRef = "ref-1")),
        )
        val snapshot = snapshotOf(
            assets = listOf(asset("a1", "Sauna"), asset("a2", "Pool")),
            definitions = listOf(definition("d-local", "a1", key = "ph")),
            events = listOf(event("e-local", "a1", sourceRef = "ref-1")),
        )

        val plan = mergePlanOf(backup, snapshot)
        val again = mergePlanOf(backup, snapshot)

        assertFalse(plan.applicable)
        assertEquals(
            listOf(
                MergeTable.ASSETS to "a1",
                MergeTable.ASSETS to "a2",
                MergeTable.DEFINITIONS to "d1",
                MergeTable.EVENTS to "e1",
            ),
            plan.conflicts.map { it.table to it.id },
        )
        assertTrue(plan.conflicts.none { it.reason == MergeReason.NONE })
        assertEquals(plan.decisions, again.decisions)
        assertEquals(plan.fingerprint, again.fingerprint)
    }

    /** Every table reports a tally, and the seven of them cover every decision exactly once. */
    @Test
    fun `the report accounts for every row exactly once`() {
        val plan = mergePlanOf(
            backupOf(
                assets = listOf(asset("a1", "Hot tub")),
                tags = listOf(tag("t1", "key-1", "a1")),
                links = listOf(link("l1")),
                definitions = listOf(definition("d1", "a1")),
                profiles = listOf(profile("p1", "a1", listOf(field("pf1", "d1")))),
                events = listOf(event("e1", "a1")),
                attachments = listOf(attachment("att1", "a1")),
            ),
            snapshotOf(),
        )
        val report = plan.report()
        val tallies = listOf(
            report.assets, report.definitions, report.profiles, report.links,
            report.tags, report.events, report.attachments,
        )

        assertEquals(
            plan.decisions.size,
            tallies.sumOf { it.insert + it.identical + it.conflict + it.skipped },
        )
        assertEquals(5, report.formatVersion)
        assertEquals("set-incoming", report.backupSetId)
        assertTrue(report.applicable)
        // One attachment, no bytes in the store: SKIPPED, and `applicable` is unaffected.
        assertEquals(MergeTally(0, 0, 0, 1), report.attachments)
    }
}
