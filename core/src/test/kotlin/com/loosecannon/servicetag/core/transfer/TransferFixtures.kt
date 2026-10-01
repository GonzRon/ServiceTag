package com.loosecannon.servicetag.core.transfer

import com.loosecannon.servicetag.core.backup.BackupData
import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.model.AssetCategory
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetReference
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.model.AttachmentId
import com.loosecannon.servicetag.core.model.AttachmentKind
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.ConsumableUsage
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.EventProfile
import com.loosecannon.servicetag.core.model.ExternalLink
import com.loosecannon.servicetag.core.model.LinkId
import com.loosecannon.servicetag.core.model.LinkKind
import com.loosecannon.servicetag.core.model.MeasurementDefinition
import com.loosecannon.servicetag.core.model.PayloadFormat
import com.loosecannon.servicetag.core.model.ProfileConsumable
import com.loosecannon.servicetag.core.model.ProfileField
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ReferenceId
import com.loosecannon.servicetag.core.model.ReferenceKind
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.model.TagStatus
import com.loosecannon.servicetag.core.model.TagTarget
import com.loosecannon.servicetag.core.model.ValueType
import com.loosecannon.servicetag.core.testing.BackupInstall
import com.loosecannon.servicetag.core.testing.InMemoryAttachmentStore
import com.loosecannon.servicetag.core.testing.activationOf
import com.loosecannon.servicetag.core.testing.caseEntryOf
import com.loosecannon.servicetag.core.testing.caseOf
import com.loosecannon.servicetag.core.testing.closureOf
import com.loosecannon.servicetag.core.testing.completionOf
import com.loosecannon.servicetag.core.testing.conditionOf
import com.loosecannon.servicetag.core.testing.groupOf
import com.loosecannon.servicetag.core.testing.loanOf
import com.loosecannon.servicetag.core.testing.plainAssetOf
import com.loosecannon.servicetag.core.testing.scheduleOf
import com.loosecannon.servicetag.core.testing.seasonalAssetOf
import com.loosecannon.servicetag.core.testing.subjectOf

/**
 * #77 (B1) — one small estate with every canonical table populated, fictional names only:
 *
 * - [HEATER] "Example Water Heater": a calendar season, a break, a warranty and its reminder lead, the
 *   custom category "Appliance", two tags (one LOST), a 2.6 link tombstone and a tag on it, a meter
 *   definition, a profile, a completion with a measurement and a document, an asset document, a
 *   reference, a season activation, a condition, a health subject on its schedule, a closed-out
 *   schedule, an open service case with an entry, and a **returned** loan;
 * - [ANODE] "Example Anode Rod": a component of the heater, a tag, and a completion of [GROUP]'s schedule;
 * - [GROUP] "Example Flush Round": the heater now and the anode once (a removed membership row), with its
 *   schedule and a closure — wholly inside the heater's subtree;
 * - [EMPTY_GROUP]: a group with no membership rows at all;
 * - [OPENER] "Sample Garage Door Opener": archived **and** retired, custom category "Garage";
 * - [COMPRESSOR] "Example Compressor": unrelated — a tag, a schedule, a closure, a completion with a
 *   document, a subject, a case, and an **open** loan;
 * - a spare tag bound to nothing, and an unused custom category.
 */
object TransferFixtures {
    const val HEATER = "h1"
    const val ANODE = "h2"
    const val OPENER = "g1"
    const val COMPRESSOR = "x1"
    const val GROUP = "G1"
    const val EMPTY_GROUP = "G0"

    /** The bytes each MANAGED row's locator holds; each row's sha256 and size are these bytes'. */
    val bytesByLocator: Map<String, ByteArray> = linkedMapOf(
        "assets/h1/at1.pdf" to "Example Water Heater manual".toByteArray(),
        "events/e1/at2.jpg" to "Example flush photo".toByteArray(),
        "assets/x1/at3.pdf" to "Example Compressor receipt".toByteArray(),
    )

    val assets = listOf(
        seasonalAssetOf(HEATER, "Example Water Heater", healthPrimarySubjectId = "hs1").copy(
            category = "Appliance", warrantyExpiresOn = "2027-03-01", warrantyReminderLeadDays = 30,
        ),
        plainAssetOf(ANODE, "Example Anode Rod").copy(parentAssetId = AssetId(HEATER)),
        plainAssetOf(OPENER, "Sample Garage Door Opener").copy(
            category = "Garage", status = AssetStatus.ARCHIVED, retiredOn = "2026-05-01",
        ),
        plainAssetOf(COMPRESSOR, "Example Compressor"),
    )

    val categories = listOf(
        AssetCategory("appliance", "Appliance", 100L, 100L),
        AssetCategory("garage", "Garage", 100L, 100L),
        AssetCategory("spare parts", "Spare parts", 100L, 100L),
    )

    val tags = listOf(
        tagOf("t1", "TEST-0001", TagTarget.AssetTarget(AssetId(HEATER))),
        tagOf("t2", "TEST-0002", TagTarget.AssetTarget(AssetId(HEATER)), TagStatus.LOST),
        tagOf("t3", "TEST-0003", TagTarget.AssetTarget(AssetId(COMPRESSOR))),
        tagOf("t4", "TEST-0004", TagTarget.LinkTarget(LinkId("L1"))),
        tagOf("t5", "TEST-0005", TagTarget.None, TagStatus.UNBOUND),
        tagOf("t6", "TEST-0006", TagTarget.AssetTarget(AssetId(ANODE))),
    )

    val links = listOf(
        ExternalLink(
            id = LinkId("L1"), assetId = AssetId(HEATER), kind = LinkKind.WEB, label = "Example notes",
            uri = "https://example.com/notes", createdAt = 100L, updatedAt = 100L,
        ),
    )

    val definitions = listOf(definitionOf("d1", HEATER), definitionOf("d2", COMPRESSOR))

    val profiles = listOf(
        EventProfile(
            id = ProfileId("p1"), assetId = AssetId(HEATER), name = "Flush", eventKind = EventKind.MAINTENANCE,
            defaultTitle = "Flush", templateKey = null, sortOrder = 0, archivedAt = null, createdAt = 100L,
            updatedAt = 100L,
            fields = listOf(ProfileField("pf1", DefinitionId("d1"), required = true, sortOrder = 0)),
            consumables = listOf(ProfileConsumable("pc1", "Example descaler", 1.0, "l", 0, supplyId = null)),
        ),
    )

    val schedules = listOf(
        scheduleOf("s1", assetId = HEATER, timeInterval = 6, timeUnit = RecurrenceUnit.MONTH),
        scheduleOf("sg", assetId = null, groupId = GROUP, timeInterval = 1, timeUnit = RecurrenceUnit.YEAR),
        scheduleOf("s2", assetId = COMPRESSOR, timeInterval = 3, timeUnit = RecurrenceUnit.MONTH),
    )

    val closures = listOf(
        closureOf("cl1", "2026-03-01", "2026-03-01", scheduleId = "s1"),
        closureOf("cl2", "2026-02-01", "2026-02-01", scheduleId = "sg"),
        closureOf("cl3", "2026-04-01", "2026-04-01", scheduleId = "s2"),
    )

    val groups = listOf(
        groupOf(GROUP, "Example Flush Round", members = listOf(
            Triple(HEATER, "2026-01-01", null),
            Triple(ANODE, "2026-01-01", "2026-02-15"),
        )),
        groupOf(EMPTY_GROUP, "Example Empty Round"),
    )

    val events = listOf(
        completionOf("e1", "2026-03-01", "2026-03-01", assetId = HEATER, scheduleId = "s1", meter = "d1" to 5.0)
            .copy(
                profileId = ProfileId("p1"),
                consumables = listOf(ConsumableUsage("cu1", "Example descaler", 1.0, "l", 0, supplyId = null)),
            ),
        completionOf("e2", "2026-02-01", "2026-02-01", assetId = ANODE, scheduleId = "sg"),
        completionOf("e3", "2026-04-01", "2026-04-01", assetId = COMPRESSOR, scheduleId = "s2", meter = "d2" to 7.0),
    )

    val attachments = listOf(
        attachmentOf("at1", AttachmentOwner.OfAsset(AssetId(HEATER)), "assets/h1/at1.pdf", "application/pdf"),
        attachmentOf("at2", AttachmentOwner.OfEvent(EventId("e1")), "events/e1/at2.jpg", "image/jpeg"),
        attachmentOf("at3", AttachmentOwner.OfAsset(AssetId(COMPRESSOR)), "assets/x1/at3.pdf", "application/pdf"),
    )

    val references = listOf(
        AssetReference(
            id = ReferenceId("r1"), assetId = AssetId(HEATER), kind = ReferenceKind.WEB_URL,
            uri = "https://example.com/heater", displayName = "Example heater page", description = "",
            scheme = "https", createdAt = 100L, updatedAt = 100L,
        ),
    )

    val activations = listOf(activationOf("sa1", HEATER))
    val conditions = listOf(conditionOf("co1", HEATER))
    val subjects = listOf(subjectOf("hs1", HEATER, scheduleId = "s1"), subjectOf("hs2", COMPRESSOR))
    val cases = listOf(caseOf("sc1", HEATER), caseOf("sc2", COMPRESSOR))
    val caseEntries = listOf(caseEntryOf("n1", caseId = "sc1"), caseEntryOf("n2", caseId = "sc2"))
    val loans = listOf(
        loanOf("l1", HEATER, lentOn = "2026-08-01", returnedOn = "2026-08-02"),
        loanOf("l2", COMPRESSOR),
    )

    /** The estate as the archive names it, every list in the store's order. */
    fun estate(): BackupData = BackupData(
        assets = assets.map { it.toDto() },
        nfcTags = tags.map { it.toDto() },
        externalLinks = links.map { it.toDto() },
        measurementDefinitions = definitions.map { it.toDto() },
        eventProfiles = profiles.map { it.toDto() },
        assetEvents = events.map { it.toDto() },
        attachments = attachments.map { it.toDto() },
        maintenanceGroups = groups.map { it.toDto() },
        maintenanceSchedules = schedules.map { it.toDto() },
        occurrenceClosures = closures.map { it.toDto() },
        assetReferences = references.map { it.toDto() },
        seasonActivations = activations.map { it.toDto() },
        assetConditions = conditions.map { it.toDto() },
        healthSubjects = subjects.map { it.toDto() },
        assetCategories = categories.map { it.toDto() },
        serviceCases = cases.map { it.toDto() },
        serviceCaseEntries = caseEntries.map { it.toDto() },
        assetLoans = loans.map { it.toDto() },
    )

    /** The same estate in an install's stores, and every MANAGED row's bytes in its store. */
    suspend fun seed(install: BackupInstall) {
        assets.forEach { install.assets.upsert(it) }
        categories.forEach { install.categories.upsert(it) }
        tags.forEach { install.tags.upsert(it) }
        links.forEach { install.links.upsert(it) }
        definitions.forEach { install.definitions.upsert(it) }
        profiles.forEach { install.profiles.upsert(it) }
        groups.forEach { install.groups.upsert(it) }
        schedules.forEach { install.schedules.upsert(it) }
        closures.forEach { install.closures.insert(it) }
        events.forEach { install.events.upsert(it) }
        attachments.forEach { install.attachments.upsert(it) }
        references.forEach { install.references.upsert(it) }
        activations.forEach { install.activations.insert(it) }
        conditions.forEach { install.conditions.insert(it) }
        subjects.forEach { install.subjects.upsert(it) }
        cases.forEach { install.serviceCases.upsert(it) }
        caseEntries.forEach { install.caseEntries.insert(it) }
        loans.forEach { install.loans.upsert(it) }
        bytesByLocator.forEach { (locator, bytes) -> install.storage.store.files[locator] = bytes }
    }

    fun tagOf(id: String, key: String, target: TagTarget, status: TagStatus = TagStatus.ACTIVE) = TagBinding(
        id = TagId(id), payloadFormat = PayloadFormat.V1, payloadKey = key, target = target, status = status,
        createdAt = 100L, updatedAt = 100L,
    )

    fun definitionOf(id: String, assetId: String) = MeasurementDefinition(
        id = DefinitionId(id), assetId = AssetId(assetId), key = "hours_$id", label = "Hours", unit = "h",
        valueType = ValueType.NUMBER, decimals = 1, rangeLow = null, rangeHigh = null, isMeter = true,
        sortOrder = 0, archivedAt = null, createdAt = 100L, updatedAt = 100L,
    )

    fun attachmentOf(id: String, owner: AttachmentOwner, locator: String, mime: String): Attachment {
        val bytes = bytesByLocator.getValue(locator)
        return Attachment(
            id = AttachmentId(id), owner = owner, kind = AttachmentKind.DOCUMENT, displayName = "$id file",
            mimeType = mime, sizeBytes = bytes.size.toLong(), sha256 = InMemoryAttachmentStore.sha256Hex(bytes),
            storageLocator = locator, capturedOn = null, createdAt = 100L, updatedAt = 100L,
        )
    }
}
