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
import com.loosecannon.servicetag.core.model.SeasonMode
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
import kotlinx.serialization.json.JsonObject

/**
 * Backup format v9: a ZIP holding exactly two entries. This is the *data* archive; a format ≥5
 * backup set pairs it with an artifacts archive, and `backupSetId` is what ties the two together.
 *
 * ```
 * manifest.json   { formatVersion, appVersion, schemaVersion, createdAt, counts, dataSha256,
 *                    backupSetId, artifactFormatVersion, artifactCount, artifactBytes }
 * data.json       { assets: [...], nfcTags: [...], externalLinks: [...],
 *                    measurementDefinitions: [...], eventProfiles: [...], assetEvents: [...],
 *                    attachments: [...], maintenanceGroups: [...], maintenanceSchedules: [...],
 *                    occurrenceClosures: [...], assetReferences: [...],
 *                    seasonActivations: [...], assetConditions: [...], healthSubjects: [...],
 *                    assetCategories: [...] }
 * ```
 *
 * IDs are written verbatim, lists are sorted by id (children by sortOrder within their parent, and
 * format 9's categories by their key, which is their id),
 * and the manifest carries the SHA-256 of the data entry, so the same input always produces the
 * same bytes and an edited file is refused. A format-1 file (the three original lists only), a
 * format-2 file (measurementDefinitions without kind/formula/sourceAId/sourceBId — every DERIVED
 * definition needs those), a format-3 file (assets without the §4 fields), a format-4 file
 * (no attachments and none of the four new manifest fields) and a format-5 file (no groups, no
 * schedules, no closures, and events with no occurrence link) still decode: the new fields default
 * to ENTERED with no formula/sources, to empty/null asset fields, to an empty attachment list with
 * an empty `backupSetId` and zero artifact tallies, and to three empty lists with every new event
 * field at its default, and to an empty reference list, respectively. **Restoring one never invents a schedule.** JDK ZIP + JDK
 * SHA-256 + kotlinx-serialization only; no Android types anywhere in here.
 *
 * **Formats 1–7 are read through one upgrade.** Format 8 dropped 1.3's season triple from the
 * schedule row and gave the asset five fields with no defaults, so a format ≤7 `data.json` is
 * parsed as a tree, rewritten by [LegacyArchive], and then decoded by the **same** strict decode as
 * a native format-8 file: one DTO set, and one place that reads a legacy season field. Format 8
 * itself is decoded as it stands, so a legacy field in it is an unknown key and corrupt (inv. 124).
 *
 * **Format 9 (#74, C11) adds one list and no upgrade.** `assetCategories` defaults to empty, so a
 * format-8 archive decodes through the same strict decode with no categories and every 1.4 field as
 * it stands — [LegacyArchive.LAST_LEGACY_FORMAT] stays 7, deliberately: raising it would route every
 * format-8 archive through the ≤7 rewrite, which strips the three 1.4 lists and resets season, break,
 * health and policy. No shipped writer ever put a category into a format ≤8 archive, so one that
 * carries a row is a hand-built file and is refused, as a format-8 build refuses the unknown key.
 * A row whose key is a **built-in's** is not refused (a built-in added by a later release must never
 * make an older archive unrestorable); the replace and the merge planner drop it.
 *
 * Two of schema 8's tables are deliberately absent from this format, and are named nowhere in this
 * package: the schedule's **derived** due state, which the recompute function rebuilds after any
 * import, and its **device-local** notification bookkeeping. Neither is ever exported and neither is
 * ever merged. Nor is any health value: a subject travels as configuration, and health is computed
 * at read time (inv. 111).
 */
object BackupCodec {
    const val FORMAT_VERSION = 9
    const val MANIFEST_ENTRY = "manifest.json"
    const val DATA_ENTRY = "data.json"

    /** The first format that can carry the owner's categories (#74). */
    private const val FIRST_CATEGORY_FORMAT = 9

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
            assetReferences = data.assetReferences.sortedBy { it.id },
            seasonActivations = data.seasonActivations.sortedBy { it.id },
            assetConditions = data.assetConditions.sortedBy { it.id },
            healthSubjects = data.healthSubjects.sortedBy { it.id },
            // Format 9: the key is the row's identity, so it is the sort key too.
            assetCategories = data.assetCategories.sortedBy { it.key },
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
                "assetReferences" to sorted.assetReferences.size,
                "seasonActivations" to sorted.seasonActivations.size,
                "assetConditions" to sorted.assetConditions.size,
                "healthSubjects" to sorted.healthSubjects.size,
                "assetCategories" to sorted.assetCategories.size,
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

    fun decode(bytes: ByteArray): Backup = decode(bytes, FORMAT_VERSION)

    /**
     * [supportedFormat] escape hatch, the decode-side twin of `encode`'s: it exists only so a test
     * can hold this build's gate at an older number and watch it refuse a newer archive the way that
     * older build does (inv. 63). Production callers use the one-arg overload above, which always
     * supports [FORMAT_VERSION].
     */
    internal fun decode(bytes: ByteArray, supportedFormat: Int): Backup {
        val entries = readEntries(bytes)

        val manifestBytes = entries[MANIFEST_ENTRY]
            ?: throw BackupCorrupt("backup is missing $MANIFEST_ENTRY")
        val manifest = try {
            json.decodeFromString(BackupManifest.serializer(), String(manifestBytes, Charsets.UTF_8))
        } catch (e: SerializationException) {
            throw BackupCorrupt("$MANIFEST_ENTRY is not readable: ${e.message}")
        }

        // Refuse a newer file before touching its contents: we cannot know what we would drop.
        if (manifest.formatVersion > supportedFormat) {
            throw BackupNewerFormat(manifest.formatVersion, supportedFormat)
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

        val data = readData(String(dataBytes, Charsets.UTF_8), manifest.formatVersion)

        // No shipped writer put a category into a format ≤8 archive: one that carries a row was
        // built by hand, and a format-8 build would have refused the key outright.
        if (manifest.formatVersion < FIRST_CATEGORY_FORMAT && data.assetCategories.isNotEmpty()) {
            throw BackupCorrupt(
                "assetCategories: a format ${manifest.formatVersion} archive cannot carry categories",
            )
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
        data.assetReferences.forEach { it.toDomain() }
        data.seasonActivations.forEach { it.toDomain() }
        data.assetConditions.forEach { it.toDomain() }
        data.healthSubjects.forEach { it.toDomain() }
        data.assetCategories.forEach { it.toDomain() }

        // And the graph has to hold together. A replace-mode import deletes everything and then
        // replays the insert loops in one transaction: a duplicate id or a reference to a row
        // that is not in the file would only fail down there, on the foreign keys, with the
        // user's data already gone.
        validateGraph(data)

        // And no row may be one a command would refuse: a restore lands nothing the app itself could
        // not have written (B03's concern 3, ruled).
        BackupContentCheck.check(data)

        return Backup(manifest, data)
    }

    /**
     * The version dispatch: formats 8 and 9 decode strictly as they stand; formats 1–7 are rewritten as a
     * tree by [LegacyArchive] first and then go through the very same strict decode.
     * `SerializationException` is an `IllegalArgumentException`, and so is the malformed-number
     * failure a tree decode can raise, so one catch covers both.
     */
    private fun readData(text: String, formatVersion: Int): BackupData = try {
        if (formatVersion > LegacyArchive.LAST_LEGACY_FORMAT) {
            json.decodeFromString(BackupData.serializer(), text)
        } else {
            val tree = json.parseToJsonElement(text) as? JsonObject
                ?: throw BackupCorrupt("$DATA_ENTRY is not a JSON object")
            json.decodeFromJsonElement(BackupData.serializer(), LegacyArchive.upgrade(tree, formatVersion))
        }
    } catch (e: IllegalArgumentException) {
        throw BackupCorrupt("$DATA_ENTRY is not readable: ${e.message}")
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
            // Inv. 88, the decoder's half: the window is set exactly when the mode is CALENDAR. The
            // check above already made the pair both-or-neither, so one bound speaks for both.
            val calendar = asset.seasonMode == SeasonMode.CALENDAR.name
            if (calendar != (asset.seasonStartMmdd != null)) {
                throw BackupCorrupt(
                    "assets: asset ${asset.id} is ${asset.seasonMode} " +
                        (if (calendar) "with no season window" else "with a season window"),
                )
            }
            val breakProblems = Season.validate(asset.blackoutStartMmdd, asset.blackoutEndMmdd)
            if (breakProblems.isNotEmpty()) {
                throw BackupCorrupt(
                    "assets: asset ${asset.id} has an invalid maintenance break: ${breakProblems.first()}",
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

        // --- references (format 7) ----------------------------------------------------------------
        // The owner check only, exactly as `externalLinks` above: in-archive `(assetId, uri)`
        // uniqueness is deliberately **not** checked here, because that is what keeps
        // `REFERENCE_DUPLICATED_IN_ARCHIVE` reachable in the planner, as `CLOSURE_DUPLICATED_IN_ARCHIVE` is.

        uniqueIds("assetReferences", data.assetReferences.map { it.id })
        data.assetReferences.forEach { reference ->
            if (reference.assetId !in assetIds) {
                throw BackupCorrupt(
                    "assetReferences: reference ${reference.id} points at asset ${reference.assetId}, " +
                        "which is not in assets",
                )
            }
        }

        // --- seasons, conditions and health (format 8) --------------------------------------------
        // Each row's asset must be in the file, and so must a subject's schedule: those are real
        // foreign keys. The soft links — `eventId` on both fact tables, `baselineProfileId` on a
        // subject, `healthPrimarySubjectId` on an asset — are deliberately **not** checked (inv. 109).
        // Nor is the subject's second identity: two non-archived subjects on one schedule are left
        // for the planner to name, exactly as `assetReferences` leaves its pair above.

        uniqueIds("seasonActivations", data.seasonActivations.map { it.id })
        data.seasonActivations.forEach { activation ->
            if (activation.assetId !in assetIds) {
                throw BackupCorrupt(
                    "seasonActivations: activation ${activation.id} points at asset " +
                        "${activation.assetId}, which is not in assets",
                )
            }
        }
        uniqueIds("assetConditions", data.assetConditions.map { it.id })
        data.assetConditions.forEach { condition ->
            if (condition.assetId !in assetIds) {
                throw BackupCorrupt(
                    "assetConditions: condition ${condition.id} points at asset " +
                        "${condition.assetId}, which is not in assets",
                )
            }
        }
        uniqueIds("healthSubjects", data.healthSubjects.map { it.id })
        data.healthSubjects.forEach { subject ->
            if (subject.assetId !in assetIds) {
                throw BackupCorrupt(
                    "healthSubjects: subject ${subject.id} points at asset ${subject.assetId}, " +
                        "which is not in assets",
                )
            }
            if (subject.scheduleId != null && subject.scheduleId !in scheduleIds) {
                throw BackupCorrupt(
                    "healthSubjects: subject ${subject.id} points at schedule ${subject.scheduleId}, " +
                        "which is not in maintenanceSchedules",
                )
            }
        }

        // --- categories (format 9) -----------------------------------------------------------------
        // The key is the row's identity and the table's primary key. Nothing points at a category and
        // a category points at nothing, so uniqueness is the whole graph check; what makes a row
        // malformed is `BackupContentCheck`'s.

        uniqueIds("assetCategories", data.assetCategories.map { it.key })

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
