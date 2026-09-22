package com.loosecannon.servicetag.core.backup

import com.loosecannon.servicetag.core.journal.derivedProblems
import com.loosecannon.servicetag.core.model.AssetTree
import com.loosecannon.servicetag.core.model.AttachmentLocator
import com.loosecannon.servicetag.core.model.AttachmentMode
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.DefinitionKind
import com.loosecannon.servicetag.core.model.Money
import com.loosecannon.servicetag.core.model.Season
import com.loosecannon.servicetag.core.model.isCode
import com.loosecannon.servicetag.core.model.shapeMatches
import com.loosecannon.servicetag.core.usecase.isIsoDate
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Backup format v6: a ZIP holding exactly two entries. This is the *data* archive; a format ≥5
 * backup set pairs it with an artifacts archive, and `backupSetId` is what ties the two together.
 *
 * ```
 * manifest.json   { formatVersion, appVersion, schemaVersion, createdAt, counts, dataSha256,
 *                    backupSetId, artifactFormatVersion, artifactCount, artifactBytes }
 * data.json       { assets: [...], nfcTags: [...], externalLinks: [...],
 *                    measurementDefinitions: [...], eventProfiles: [...], assetEvents: [...],
 *                    attachments: [...], maintenanceGroups: [...], maintenanceSchedules: [...],
 *                    occurrenceClosures: [...] }
 * ```
 *
 * IDs are written verbatim, lists are sorted by id (children by sortOrder within their parent),
 * and the manifest carries the SHA-256 of the data entry, so the same input always produces the
 * same bytes and an edited file is refused. A format-1 file (the three original lists only), a
 * format-2 file (measurementDefinitions without kind/formula/sourceAId/sourceBId — every DERIVED
 * definition needs those), a format-3 file (assets without the §4 fields), a format-4 file
 * (no attachments and none of the four new manifest fields) and a format-5 file (no groups, no
 * schedules, no closures, and events with no occurrence link) still decode: the new fields default
 * to ENTERED with no formula/sources, to empty/null asset fields, to an empty attachment list with
 * an empty `backupSetId` and zero artifact tallies, and to three empty lists with every new event
 * field at its default, respectively. **Restoring one never invents a schedule.** JDK ZIP + JDK
 * SHA-256 + kotlinx-serialization only; no Android types anywhere in here.
 *
 * Two of schema 6's tables are deliberately absent from this format, and are named nowhere in this
 * package: the schedule's **derived** due state, which the recompute function rebuilds after any
 * import, and its **device-local** notification bookkeeping. Neither is ever exported and neither is
 * ever merged.
 */
object BackupCodec {
    const val FORMAT_VERSION = 6
    const val MANIFEST_ENTRY = "manifest.json"
    const val DATA_ENTRY = "data.json"

    /** Lowercase hex, 64 chars — the shape every attachment row promises for its bytes. */
    private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    fun encode(
        data: BackupData,
        appVersion: String,
        schemaVersion: Int,
        createdAt: Long,
        backupSetId: String,
    ): ByteArray = encode(data, appVersion, schemaVersion, createdAt, backupSetId, FORMAT_VERSION)

    /**
     * [formatVersion] escape hatch exists only so tests can seal a manifest that claims an older
     * format than this codec writes by default (`formatOneFileStillDecodes`). Production callers
     * use the five-arg overload above, which always stamps [FORMAT_VERSION].
     */
    internal fun encode(
        data: BackupData,
        appVersion: String,
        schemaVersion: Int,
        createdAt: Long,
        backupSetId: String,
        formatVersion: Int,
    ): ByteArray {
        val sorted = BackupData(
            assets = data.assets.sortedBy { it.id },
            nfcTags = data.nfcTags.sortedBy { it.id },
            externalLinks = data.externalLinks.sortedBy { it.id },
            measurementDefinitions = data.measurementDefinitions.sortedBy { it.id },
            eventProfiles = data.eventProfiles.sortedBy { it.id }.map { profile ->
                profile.copy(
                    fields = profile.fields.sortedBy { it.sortOrder },
                    consumables = profile.consumables.sortedBy { it.sortOrder },
                )
            },
            assetEvents = data.assetEvents.sortedBy { it.id }.map { event ->
                event.copy(
                    measurements = event.measurements.sortedBy { it.sortOrder },
                    consumables = event.consumables.sortedBy { it.sortOrder },
                )
            },
            attachments = data.attachments.sortedBy { it.id },
            // Members tie-break on `id` because `sortOrder` is not promised unique within a parent
            // and the planner's own normalisation is `(sortOrder, id)`; two stable sorts over two
            // different bases would otherwise disagree. Providers sort by the only key they have.
            maintenanceGroups = data.maintenanceGroups.sortedBy { it.id }.map { group ->
                group.copy(members = group.members.sortedWith(compareBy({ it.sortOrder }, { it.id })))
            },
            maintenanceSchedules = data.maintenanceSchedules.sortedBy { it.id }.map { schedule ->
                schedule.copy(providers = schedule.providers.sortedBy { it.provider })
            },
            occurrenceClosures = data.occurrenceClosures.sortedBy { it.id },
        )
        val dataBytes = json.encodeToString(BackupData.serializer(), sorted).toByteArray(Charsets.UTF_8)
        // The artifact tallies are derived here, in one place, from the rows themselves: a MANAGED
        // row is a row whose bytes belong in the artifacts archive.
        val managed = sorted.attachments.filter { it.mode == AttachmentMode.MANAGED.name }
        val manifest = BackupManifest(
            formatVersion = formatVersion,
            appVersion = appVersion,
            schemaVersion = schemaVersion,
            createdAt = createdAt,
            counts = mapOf(
                "assets" to sorted.assets.size,
                "nfcTags" to sorted.nfcTags.size,
                "externalLinks" to sorted.externalLinks.size,
                "measurementDefinitions" to sorted.measurementDefinitions.size,
                "eventProfiles" to sorted.eventProfiles.size,
                "assetEvents" to sorted.assetEvents.size,
                "profileFields" to sorted.eventProfiles.sumOf { it.fields.size },
                "profileConsumables" to sorted.eventProfiles.sumOf { it.consumables.size },
                "measurements" to sorted.assetEvents.sumOf { it.measurements.size },
                "consumableUsages" to sorted.assetEvents.sumOf { it.consumables.size },
                "attachments" to sorted.attachments.size,
                "maintenanceGroups" to sorted.maintenanceGroups.size,
                "groupMembers" to sorted.maintenanceGroups.sumOf { it.members.size },
                "maintenanceSchedules" to sorted.maintenanceSchedules.size,
                "scheduleProviders" to sorted.maintenanceSchedules.sumOf { it.providers.size },
                "occurrenceClosures" to sorted.occurrenceClosures.size,
            ),
            dataSha256 = sha256Hex(dataBytes),
            backupSetId = backupSetId,
            artifactFormatVersion = ArtifactsCodec.ARTIFACT_FORMAT_VERSION,
            artifactCount = managed.size,
            artifactBytes = managed.sumOf { it.sizeBytes },
        )
        val manifestBytes =
            json.encodeToString(BackupManifest.serializer(), manifest).toByteArray(Charsets.UTF_8)

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            zos.writeEntry(MANIFEST_ENTRY, manifestBytes, createdAt)
            zos.writeEntry(DATA_ENTRY, dataBytes, createdAt)
        }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): Backup {
        val entries = readEntries(bytes)

        val manifestBytes = entries[MANIFEST_ENTRY]
            ?: throw BackupCorrupt("backup is missing $MANIFEST_ENTRY")
        val manifest = try {
            json.decodeFromString(BackupManifest.serializer(), String(manifestBytes, Charsets.UTF_8))
        } catch (e: SerializationException) {
            throw BackupCorrupt("$MANIFEST_ENTRY is not readable: ${e.message}")
        }

        // Refuse a newer file before touching its contents: we cannot know what we would drop.
        if (manifest.formatVersion > FORMAT_VERSION) {
            throw BackupNewerFormat(manifest.formatVersion, FORMAT_VERSION)
        }

        // A format-5 file without a set id could never be paired with its artifacts archive.
        if (manifest.formatVersion >= 5 && manifest.backupSetId.isBlank()) {
            throw BackupCorrupt("$MANIFEST_ENTRY is format ${manifest.formatVersion} with no backupSetId")
        }

        val dataBytes = entries[DATA_ENTRY]
            ?: throw BackupCorrupt("backup is missing $DATA_ENTRY")
        val actual = sha256Hex(dataBytes)
        if (!actual.equals(manifest.dataSha256, ignoreCase = true)) {
            throw BackupCorrupt("$DATA_ENTRY sha256 $actual does not match manifest ${manifest.dataSha256}")
        }

        val data = try {
            json.decodeFromString(BackupData.serializer(), String(dataBytes, Charsets.UTF_8))
        } catch (e: SerializationException) {
            throw BackupCorrupt("$DATA_ENTRY is not readable: ${e.message}")
        }

        // Every row must be nameable in the domain, otherwise the caller would only find out
        // halfway through a destructive import. Result discarded; this is a validation pass.
        data.assets.forEach { it.toDomain() }
        data.externalLinks.forEach { it.toDomain() }
        data.nfcTags.forEach { it.toDomain() }
        data.measurementDefinitions.forEach { it.toDomain() }
        data.eventProfiles.forEach { it.toDomain() }
        data.assetEvents.forEach { it.toDomain() }
        data.attachments.forEach { it.toDomain() }
        data.maintenanceGroups.forEach { it.toDomain() }
        data.maintenanceSchedules.forEach { it.toDomain() }
        data.occurrenceClosures.forEach { it.toDomain() }

        // And the graph has to hold together. A replace-mode import deletes everything and then
        // replays the insert loops in one transaction: a duplicate id or a reference to a row
        // that is not in the file would only fail down there, on the foreign keys, with the
        // user's data already gone.
        validateGraph(data)

        return Backup(manifest, data)
    }

    /** Ids unique within each table, and every non-null reference resolvable inside the file. */
    private fun validateGraph(data: BackupData) {
        val assetIds = uniqueIds("assets", data.assets.map { it.id })
        val linkIds = uniqueIds("externalLinks", data.externalLinks.map { it.id })
        uniqueIds("nfcTags", data.nfcTags.map { it.id })

        // --- asset fields and hierarchy (spec §10) ----------------------------------------------

        data.assets.forEach { asset ->
            if (asset.parentAssetId != null && asset.parentAssetId !in assetIds) {
                throw BackupCorrupt(
                    "assets: asset ${asset.id} points at parent ${asset.parentAssetId}, " +
                        "which is not in assets",
                )
            }
        }
        // Every asset was already proven nameable (enum-check pass above), so toDomain() here
        // cannot throw; it is only how we get at AssetTree's own cycle detection.
        try {
            AssetTree.parentsFirst(data.assets.map { it.toDomain() })
        } catch (e: IllegalStateException) {
            throw BackupCorrupt("assets: cycle in asset hierarchy")
        }
        data.assets.forEach { asset ->
            val currency = asset.currency
            val price = asset.purchasePriceMinor
            if (currency != null && !Money.isCode(currency)) {
                throw BackupCorrupt("assets: asset ${asset.id} has a malformed currency \"$currency\"")
            }
            if (price != null && currency == null) {
                throw BackupCorrupt("assets: asset ${asset.id} has a price but no currency")
            }
            if (price != null && currency != null && Money.fractionDigits(currency) == null) {
                throw BackupCorrupt("assets: asset ${asset.id} has an unresolvable currency \"$currency\"")
            }
            if (price != null && price < 0) {
                throw BackupCorrupt("assets: asset ${asset.id} has a negative price")
            }
            for ((field, value) in listOf(
                "purchaseOn" to asset.purchaseOn,
                "inServiceOn" to asset.inServiceOn,
                "warrantyExpiresOn" to asset.warrantyExpiresOn,
                "retiredOn" to asset.retiredOn,
            )) {
                if (value != null && !isIsoDate(value)) {
                    throw BackupCorrupt("assets: asset ${asset.id} has a malformed $field \"$value\"")
                }
            }
            val seasonProblems = Season.validate(asset.seasonStartMmdd, asset.seasonEndMmdd)
            if (seasonProblems.isNotEmpty()) {
                throw BackupCorrupt(
                    "assets: asset ${asset.id} has an invalid season window: ${seasonProblems.first()}",
                )
            }
        }

        data.externalLinks.forEach { link ->
            if (link.assetId != null && link.assetId !in assetIds) {
                throw BackupCorrupt(
                    "externalLinks: link ${link.id} points at asset ${link.assetId}, " +
                        "which is not in assets",
                )
            }
        }
        data.nfcTags.forEach { tag ->
            if (tag.assetId != null && tag.assetId !in assetIds) {
                throw BackupCorrupt(
                    "nfcTags: tag ${tag.id} points at asset ${tag.assetId}, which is not in assets",
                )
            }
            if (tag.linkId != null && tag.linkId !in linkIds) {
                throw BackupCorrupt(
                    "nfcTags: tag ${tag.id} points at link ${tag.linkId}, " +
                        "which is not in externalLinks",
                )
            }
        }

        // --- journal tables --------------------------------------------------------------------

        uniqueIds("measurementDefinitions", data.measurementDefinitions.map { it.id })
        val definitionsById = data.measurementDefinitions.associateBy { it.id }
        data.measurementDefinitions.forEach { definition ->
            if (definition.assetId !in assetIds) {
                throw BackupCorrupt(
                    "measurementDefinitions: definition ${definition.id} points at asset " +
                        "${definition.assetId}, which is not in assets",
                )
            }
        }

        // Every row was already proven nameable (enum-check pass above), so toDomain() here
        // cannot throw; it is only how we get at the derived spec and its DefinitionKind.
        val domainDefinitionsById = data.measurementDefinitions.associate {
            DefinitionId(it.id) to it.toDomain()
        }
        domainDefinitionsById.values.filter { it.kind == DefinitionKind.DERIVED }.forEach { definition ->
            val problems = definition.derivedProblems(domainDefinitionsById)
            if (problems.isNotEmpty()) {
                throw BackupCorrupt(
                    "measurementDefinitions: definition ${definition.id.value} is DERIVED but invalid: " +
                        problems.first()::class.simpleName,
                )
            }
        }

        uniqueIds("eventProfiles", data.eventProfiles.map { it.id })
        val profileAssetIds = data.eventProfiles.associate { it.id to it.assetId }
        val profileFieldIds = mutableListOf<String>()
        val profileConsumableIds = mutableListOf<String>()
        data.eventProfiles.forEach { profile ->
            if (profile.assetId !in assetIds) {
                throw BackupCorrupt(
                    "eventProfiles: profile ${profile.id} points at asset ${profile.assetId}, " +
                        "which is not in assets",
                )
            }
            profile.fields.forEach { field ->
                profileFieldIds += field.id
                val definition = definitionsById[field.definitionId]
                    ?: throw BackupCorrupt(
                        "eventProfiles: profile ${profile.id} field ${field.id} references " +
                            "definition ${field.definitionId}, which is not in measurementDefinitions",
                    )
                if (definition.assetId != profile.assetId) {
                    throw BackupCorrupt(
                        "eventProfiles: profile ${profile.id} field ${field.id} references " +
                            "definition ${field.definitionId} from a different asset",
                    )
                }
            }
            profile.consumables.forEach { consumable -> profileConsumableIds += consumable.id }
        }
        uniqueIds("profileFields", profileFieldIds)
        uniqueIds("profileConsumables", profileConsumableIds)

        // --- maintenance: groups, schedules and closures (format 6) -----------------------------
        // Decided before the events loop, because an event may name a schedule.

        val groupIds = uniqueIds("maintenanceGroups", data.maintenanceGroups.map { it.id })
        val groupMemberIds = mutableListOf<String>()
        data.maintenanceGroups.forEach { group ->
            group.members.forEach { member ->
                groupMemberIds += member.id
                if (member.assetId !in assetIds) {
                    throw BackupCorrupt(
                        "maintenanceGroups: group ${group.id} member ${member.id} points at asset " +
                            "${member.assetId}, which is not in assets",
                    )
                }
            }
        }
        uniqueIds("groupMembers", groupMemberIds)

        val scheduleIds = uniqueIds("maintenanceSchedules", data.maintenanceSchedules.map { it.id })
        data.maintenanceSchedules.forEach { schedule ->
            // Already proven nameable in the enum-check pass above, which is also where a row
            // naming both targets or neither was refused; so exactly one of these two is non-null.
            if (schedule.assetId != null && schedule.assetId !in assetIds) {
                throw BackupCorrupt(
                    "maintenanceSchedules: schedule ${schedule.id} points at asset " +
                        "${schedule.assetId}, which is not in assets",
                )
            }
            if (schedule.groupId != null && schedule.groupId !in groupIds) {
                throw BackupCorrupt(
                    "maintenanceSchedules: schedule ${schedule.id} points at group " +
                        "${schedule.groupId}, which is not in maintenanceGroups",
                )
            }
            if (schedule.meterDefinitionId != null && schedule.meterDefinitionId !in definitionsById) {
                throw BackupCorrupt(
                    "maintenanceSchedules: schedule ${schedule.id} points at definition " +
                        "${schedule.meterDefinitionId}, which is not in measurementDefinitions",
                )
            }
            if (schedule.profileId != null && schedule.profileId !in profileAssetIds) {
                throw BackupCorrupt(
                    "maintenanceSchedules: schedule ${schedule.id} points at profile " +
                        "${schedule.profileId}, which is not in eventProfiles",
                )
            }
            // A meter-only schedule has no calendar occurrence, so there is no date for a
            // postponement to replace: `computedDueOn` is null for it and `effectiveDueOn` would
            // become non-null anyway, which is exactly the state invariant 10 forbids. The
            // postpone operation already refuses it; without this the import is the way in.
            if (schedule.timeInterval == null && schedule.postponedDueOn != null) {
                throw BackupCorrupt(
                    "maintenanceSchedules: schedule ${schedule.id} carries a postponedDueOn " +
                        "but has no time rule to postpone",
                )
            }
        }

        uniqueIds("occurrenceClosures", data.occurrenceClosures.map { it.id })
        data.occurrenceClosures.forEach { closure ->
            if (closure.scheduleId !in scheduleIds) {
                throw BackupCorrupt(
                    "occurrenceClosures: closure ${closure.id} points at schedule " +
                        "${closure.scheduleId}, which is not in maintenanceSchedules",
                )
            }
        }

        // --- events ------------------------------------------------------------------------------

        uniqueIds("assetEvents", data.assetEvents.map { it.id })
        val measurementIds = mutableListOf<String>()
        val consumableUsageIds = mutableListOf<String>()
        data.assetEvents.forEach { event ->
            if (event.assetId !in assetIds) {
                throw BackupCorrupt(
                    "assetEvents: event ${event.id} points at asset ${event.assetId}, " +
                        "which is not in assets",
                )
            }
            if (event.profileId != null) {
                val profileAssetId = profileAssetIds[event.profileId]
                    ?: throw BackupCorrupt(
                        "assetEvents: event ${event.id} points at profile ${event.profileId}, " +
                            "which is not in eventProfiles",
                    )
                if (profileAssetId != event.assetId) {
                    throw BackupCorrupt(
                        "assetEvents: event ${event.id} references profile ${event.profileId} " +
                            "from a different asset",
                    )
                }
            }
            if (event.scheduleId != null && event.scheduleId !in scheduleIds) {
                throw BackupCorrupt(
                    "assetEvents: event ${event.id} points at schedule ${event.scheduleId}, " +
                        "which is not in maintenanceSchedules",
                )
            }
            event.measurements.forEach { measurement ->
                measurementIds += measurement.id
                val definition = definitionsById[measurement.definitionId]
                    ?: throw BackupCorrupt(
                        "assetEvents: measurement ${measurement.id} references definition " +
                            "${measurement.definitionId}, which is not in measurementDefinitions",
                    )
                if (definition.assetId != event.assetId) {
                    throw BackupCorrupt(
                        "assetEvents: measurement ${measurement.id} references definition " +
                            "${measurement.definitionId} from a different asset",
                    )
                }
                if (definition.kind == DefinitionKind.DERIVED.name) {
                    throw BackupCorrupt(
                        "assetEvents: measurement ${measurement.id} references DERIVED definition " +
                            "${definition.id}, which cannot be measured directly",
                    )
                }
                // The definition's valueType was already proven nameable in the enum-check pass
                // above, so toDomain() here cannot throw; it is only how we get at the enum.
                val valueType = definition.toDomain().valueType
                if (!measurement.toDomain().shapeMatches(valueType)) {
                    throw BackupCorrupt(
                        "assetEvents: measurement ${measurement.id} does not match definition " +
                            "${definition.id}'s value shape for $valueType",
                    )
                }
            }
            event.consumables.forEach { consumable -> consumableUsageIds += consumable.id }
        }
        uniqueIds("measurements", measurementIds)
        uniqueIds("consumableUsages", consumableUsageIds)

        // --- attachments (spec §7.1) ---------------------------------------------------------------

        val eventIds = data.assetEvents.map { it.id }.toSet()
        uniqueIds("attachments", data.attachments.map { it.id })
        val locators = mutableSetOf<Pair<String, String>>()
        data.attachments.forEach { attachment ->
            // Already proven nameable in the enum-check pass above; this is how we get the owner.
            val domain = attachment.toDomain()
            when (val owner = domain.owner) {
                is AttachmentOwner.OfAsset -> if (owner.assetId.value !in assetIds) throw BackupCorrupt(
                    "attachments: attachment ${attachment.id} points at asset ${owner.assetId.value}, " +
                        "which is not in assets",
                )
                is AttachmentOwner.OfEvent -> if (owner.eventId.value !in eventIds) throw BackupCorrupt(
                    "attachments: attachment ${attachment.id} points at event ${owner.eventId.value}, " +
                        "which is not in assetEvents",
                )
            }
            if (!SHA256_HEX.matches(attachment.sha256)) throw BackupCorrupt(
                "attachments: attachment ${attachment.id} has a malformed sha256",
            )
            if (attachment.sizeBytes < 0) throw BackupCorrupt(
                "attachments: attachment ${attachment.id} has a negative size",
            )
            if (!AttachmentLocator.matchesShape(attachment.storageLocator, domain.owner, domain.id)) {
                throw BackupCorrupt(
                    "attachments: attachment ${attachment.id} has a locator that is not its own",
                )
            }
            if (!locators.add(attachment.storageProvider to attachment.storageLocator)) throw BackupCorrupt(
                "attachments: duplicate locator ${attachment.storageLocator}",
            )
        }
    }

    private fun uniqueIds(table: String, ids: List<String>): Set<String> {
        val seen = LinkedHashSet<String>(ids.size)
        ids.forEach { id ->
            if (!seen.add(id)) throw BackupCorrupt("$table: duplicate id $id")
        }
        return seen
    }

    private fun ZipOutputStream.writeEntry(name: String, payload: ByteArray, time: Long) {
        val entry = ZipEntry(name)
        entry.time = time // fixed, not "now", so encode() is reproducible
        putNextEntry(entry)
        write(payload)
        closeEntry()
    }

    private fun readEntries(bytes: ByteArray): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
                while (true) {
                    val entry = zin.nextEntry ?: break
                    entries[entry.name] = zin.readBytes()
                    zin.closeEntry()
                }
            }
        } catch (e: Exception) {
            throw BackupCorrupt("backup is not a readable zip: ${e.message}")
        }
        if (entries.isEmpty()) throw BackupCorrupt("backup is not a readable zip: no entries")
        return entries
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { b -> "%02x".format(b) }
}
