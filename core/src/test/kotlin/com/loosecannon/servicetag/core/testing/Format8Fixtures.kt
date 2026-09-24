package com.loosecannon.servicetag.core.testing

import com.loosecannon.servicetag.core.backup.Backup
import com.loosecannon.servicetag.core.backup.BackupCodec
import com.loosecannon.servicetag.core.backup.BackupData
import com.loosecannon.servicetag.core.backup.BackupManifest
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetCondition
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.HealthAggregation
import com.loosecannon.servicetag.core.model.HealthDriver
import com.loosecannon.servicetag.core.model.HealthSubject
import com.loosecannon.servicetag.core.model.HealthSubjectId
import com.loosecannon.servicetag.core.model.HealthSubjectKind
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonActivation
import com.loosecannon.servicetag.core.model.SeasonMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/*
 * 1.4 (B03) — builders for format 8's rows, and the byte-level helpers the format tests need: seal a
 * `data.json` tree into an archive under any format number, and turn a format-8 tree back into the
 * shape a 1.3 writer produced. B01's `MaintenanceFixtures.kt` is not edited; `scheduleOf` there is
 * used as it stands. Every name here is fictional (spec F1–F5).
 */

/** An asset carrying non-default values in every 1.4 field: CALENDAR, a break, WEIGHTED, a primary. */
fun seasonalAssetOf(
    id: String = "a1",
    name: String = "Snowblower",
    seasonMode: SeasonMode = SeasonMode.CALENDAR,
    seasonStartMmdd: String? = "11-01",
    seasonEndMmdd: String? = "03-31",
    blackoutStartMmdd: String? = "01-10",
    blackoutEndMmdd: String? = "01-20",
    healthAggregation: HealthAggregation = HealthAggregation.WEIGHTED,
    healthPrimarySubjectId: String? = "h1",
    updatedAt: Long = 200L,
): Asset = Asset(
    id = AssetId(id),
    name = name,
    createdAt = 100L,
    updatedAt = updatedAt,
    seasonStartMmdd = seasonStartMmdd,
    seasonEndMmdd = seasonEndMmdd,
    seasonMode = seasonMode,
    blackoutStartMmdd = blackoutStartMmdd,
    blackoutEndMmdd = blackoutEndMmdd,
    healthAggregation = healthAggregation,
    healthPrimarySubjectId = healthPrimarySubjectId?.let(::HealthSubjectId),
)

/** A plain asset: YEAR_ROUND, no break, WORST, no primary — the 1.4 defaults. */
fun plainAssetOf(id: String, name: String = "Generator $id", updatedAt: Long = 200L): Asset =
    Asset(id = AssetId(id), name = name, createdAt = 100L, updatedAt = updatedAt)

fun activationOf(
    id: String,
    assetId: String = "a1",
    action: SeasonAction = SeasonAction.START,
    occurredOn: String = "2026-03-01",
    eventId: String? = null,
    createdAt: Long = 300L,
): SeasonActivation = SeasonActivation(
    id = id,
    assetId = AssetId(assetId),
    action = action,
    occurredOn = occurredOn,
    eventId = eventId?.let(::EventId),
    createdAt = createdAt,
)

fun conditionOf(
    id: String,
    assetId: String = "a1",
    condition: OperationalCondition = OperationalCondition.DEGRADED,
    occurredOn: String = "2026-02-01",
    occurredTime: String? = "07:45",
    tzId: String = "Etc/GMT-2",
    reason: String = "auger belt slipping",
    eventId: String? = null,
    createdAt: Long = 400L,
): AssetCondition = AssetCondition(
    id = id,
    assetId = AssetId(assetId),
    condition = condition,
    occurredOn = occurredOn,
    occurredTime = occurredTime,
    tzId = tzId,
    reason = reason,
    eventId = eventId?.let(::EventId),
    createdAt = createdAt,
)

fun subjectOf(
    id: String,
    assetId: String = "a1",
    scheduleId: String? = null,
    archivedAt: Long? = null,
    name: String = "Auger belt $id",
    kind: HealthSubjectKind = HealthSubjectKind.PART,
    driver: HealthDriver = if (scheduleId != null) HealthDriver.MAINTENANCE_OVERDUE else HealthDriver.AGE,
    baselineProfileId: String? = null,
    weight: Int = 3,
    sortOrder: Int = 2,
    updatedAt: Long = 600L,
): HealthSubject = HealthSubject(
    id = HealthSubjectId(id),
    assetId = AssetId(assetId),
    name = name,
    kind = kind,
    driver = driver,
    scheduleId = scheduleId?.let(::ScheduleId),
    baselineProfileId = baselineProfileId?.let(::ProfileId),
    nominalUntilDays = 10,
    warningFromDays = 20,
    criticalFromDays = 30,
    weight = weight,
    sortOrder = sortOrder,
    archivedAt = archivedAt,
    createdAt = 500L,
    updatedAt = updatedAt,
)

/** A decoded-looking [Backup] around [data], for the planner, which never reads the bytes. */
fun backupOf(data: BackupData, formatVersion: Int = BackupCodec.FORMAT_VERSION): Backup = Backup(
    manifest = BackupManifest(
        formatVersion = formatVersion, appVersion = "1.4.0", schemaVersion = 8, createdAt = 1L,
        counts = emptyMap(), dataSha256 = "a".repeat(64), backupSetId = "set-incoming",
    ),
    data = data,
)

/** Encodes [data] as this build would, stamped [formatVersion] (the codec's test escape hatch). */
fun archiveOf(data: BackupData, formatVersion: Int = BackupCodec.FORMAT_VERSION): ByteArray =
    BackupCodec.encode(
        data, appVersion = "1.4.0", schemaVersion = 8, createdAt = 1_758_700_000_000L,
        backupSetId = "set-format-8", formatVersion = formatVersion,
    )

// --- bytes and trees ------------------------------------------------------------------------------

private val prettyJson = Json {
    prettyPrint = true
    encodeDefaults = true
}

fun zipEntries(bytes: ByteArray): Map<String, ByteArray> {
    val entries = LinkedHashMap<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
        while (true) {
            val entry = zin.nextEntry ?: break
            entries[entry.name] = zin.readBytes()
            zin.closeEntry()
        }
    }
    return entries
}

/** The archive's `data.json`, as text. */
fun dataJsonOf(bytes: ByteArray): String =
    String(zipEntries(bytes).getValue(BackupCodec.DATA_ENTRY), Charsets.UTF_8)

/** The archive's `data.json`, as a tree. */
fun dataTreeOf(bytes: ByteArray): JsonObject = Json.parseToJsonElement(dataJsonOf(bytes)).jsonObject

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/**
 * Seals [tree] as the `data.json` of an archive whose manifest says [formatVersion], with a
 * `dataSha256` that matches — so the only thing a decode can object to is the tree itself.
 */
fun sealed(tree: JsonObject, formatVersion: Int, backupSetId: String = "set-sealed"): ByteArray {
    val dataBytes = prettyJson.encodeToString(JsonObject.serializer(), tree).toByteArray(Charsets.UTF_8)
    val manifest = BackupManifest(
        formatVersion = formatVersion, appVersion = "1.3.0", schemaVersion = 7,
        createdAt = 1_758_700_000_000L, counts = emptyMap(), dataSha256 = sha256Hex(dataBytes),
        backupSetId = backupSetId,
    )
    val manifestBytes =
        prettyJson.encodeToString(BackupManifest.serializer(), manifest).toByteArray(Charsets.UTF_8)
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zos ->
        for ((name, payload) in listOf(BackupCodec.MANIFEST_ENTRY to manifestBytes, BackupCodec.DATA_ENTRY to dataBytes)) {
            zos.putNextEntry(ZipEntry(name).apply { time = 1_758_700_000_000L })
            zos.write(payload)
            zos.closeEntry()
        }
    }
    return out.toByteArray()
}

/** Rewrites one table's rows of [tree]: [edit] gets each row object and returns its replacement. */
fun JsonObject.editRows(table: String, edit: (JsonObject) -> JsonObject): JsonObject =
    JsonObject(this + (table to JsonArray(getValue(table).jsonArray.map { edit(it.jsonObject) })))

/** [this] row with [key] set to [value]. */
fun JsonObject.with(key: String, value: JsonElement): JsonObject = JsonObject(this + (key to value))

/** [this] row without [keys]. */
fun JsonObject.without(vararg keys: String): JsonObject = JsonObject(filterKeys { it !in keys })

/** 1.3's season triple on one schedule: `null` behaviour means the key is **absent**. */
data class LegacyTriple(val seasonBehavior: String?, val seasonReentry: String?, val seasonReentryOffsetDays: Int?)

/**
 * A format-8 tree put back into the shape a **1.3 writer** produced: no format-8 lists, none of the
 * asset's five fields, and each schedule carrying 1.3's triple ([triples], by schedule id) in place
 * of the policy, its offset and `ruleChangedAt`. Test-only: the codec never writes this shape.
 */
fun JsonObject.asFormat7(triples: Map<String, LegacyTriple>): JsonObject {
    var tree = without("seasonActivations", "assetConditions", "healthSubjects")
    tree = tree.editRows("assets") {
        it.without("seasonMode", "blackoutStartMmdd", "blackoutEndMmdd", "healthAggregation", "healthPrimarySubjectId")
    }
    if ("maintenanceSchedules" in tree) {
        tree = tree.editRows("maintenanceSchedules") { row ->
            val id = (row.getValue("id") as JsonPrimitive).content
            val triple = triples[id] ?: LegacyTriple("IGNORE", null, null)
            var legacy = row.without("servicePolicy", "policyOffsetDays", "ruleChangedAt")
            if (triple.seasonBehavior != null) legacy = legacy.with("seasonBehavior", JsonPrimitive(triple.seasonBehavior))
            legacy
                .with("seasonReentry", triple.seasonReentry?.let(::JsonPrimitive) ?: JsonNull)
                .with("seasonReentryOffsetDays", triple.seasonReentryOffsetDays?.let(::JsonPrimitive) ?: JsonNull)
        }
    }
    return tree
}

/** A committed test resource's bytes. */
fun resourceBytes(name: String): ByteArray =
    checkNotNull(Format8Resources::class.java.classLoader.getResourceAsStream(name)) { "no test resource $name" }
        .use { it.readBytes() }

private object Format8Resources

/** B01's committed golden format-7 archive, read and never rewritten. */
const val GOLDEN_FORMAT_7 = "golden/format-7-legacy-seasons.zip"
const val GOLDEN_FORMAT_7_EXPECTED = "golden/format-7-legacy-seasons.expected.json"
