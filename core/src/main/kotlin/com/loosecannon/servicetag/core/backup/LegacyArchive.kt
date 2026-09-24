package com.loosecannon.servicetag.core.backup

import com.loosecannon.servicetag.core.model.HealthAggregation
import com.loosecannon.servicetag.core.model.LegacySeasonMapping
import com.loosecannon.servicetag.core.model.SeasonBehavior
import com.loosecannon.servicetag.core.model.SeasonMode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * The format ≤7 reader (spec §8.3, inv. 124; master plan §5): a pure rewrite of an old `data.json`
 * **tree** into format 8's shape, after which the codec runs the one strict decode every archive
 * goes through. So there is one DTO set, and this file is the only place in the codec that reads
 * 1.3's three season fields.
 *
 * Per schedule, the triple goes through [LegacySeasonMapping.toPolicy] — the same table the 7 → 8
 * migration uses, with `hasTimeRule = timeInterval != null` — the three keys are removed, and
 * `ruleChangedAt` is set to the row's own `updatedAt`, exactly as the migration seeds it. An absent
 * `seasonBehavior` is IGNORE's absent row; an unrecognised name stays unrecognised and the archive is
 * [BackupCorrupt], which is 1.3's answer. Per asset: CALENDAR exactly when both `MM-DD` bounds are
 * set, else YEAR_ROUND, no break, `WORST`, no primary subject. The three format-8 lists are absent.
 *
 * A format ≤7 file can hold none of format 8's fields, so whatever a writer put there anyway is
 * **replaced** by what the old format means, never read. Nothing else in the tree is touched: a
 * node of the wrong shape is left for the strict decode to refuse.
 */
object LegacyArchive {

    /** The newest format this object upgrades. Format 8 is decoded as it stands. */
    const val LAST_LEGACY_FORMAT: Int = 7

    private const val BEHAVIOR = "seasonBehavior"
    private const val REENTRY = "seasonReentry"
    private const val OFFSET = "seasonReentryOffsetDays"

    private val FORMAT_8_LISTS = listOf("seasonActivations", "assetConditions", "healthSubjects")

    /** Rewrites a format ≤7 data tree into format 8's shape. Pure; never called for format 8. */
    fun upgrade(tree: JsonObject, formatVersion: Int): JsonObject {
        require(formatVersion <= LAST_LEGACY_FORMAT) { "format $formatVersion needs no upgrade" }
        val upgraded = tree.toMutableMap()
        FORMAT_8_LISTS.forEach { upgraded.remove(it) }
        upgraded.rewriteRows("assets") { upgradeAsset(it) }
        upgraded.rewriteRows("maintenanceSchedules") { upgradeSchedule(it) }
        return JsonObject(upgraded)
    }

    private fun upgradeAsset(asset: JsonObject): JsonObject {
        val calendar = asset.text("seasonStartMmdd") != null && asset.text("seasonEndMmdd") != null
        val mode = if (calendar) SeasonMode.CALENDAR else SeasonMode.YEAR_ROUND
        return JsonObject(
            asset + mapOf(
                "seasonMode" to JsonPrimitive(mode.name),
                "blackoutStartMmdd" to JsonNull,
                "blackoutEndMmdd" to JsonNull,
                "healthAggregation" to JsonPrimitive(HealthAggregation.WORST.name),
                "healthPrimarySubjectId" to JsonNull,
            ),
        )
    }

    private fun upgradeSchedule(schedule: JsonObject): JsonObject {
        val owner = "schedule ${schedule.text("id") ?: "?"}"
        val behavior = if (BEHAVIOR in schedule) {
            val name = schedule.requiredText(BEHAVIOR, owner)
            SeasonBehavior.entries.firstOrNull { it.name == name }
                ?: throw BackupCorrupt("unknown season behavior \"$name\" on $owner")
        } else {
            null
        }
        val timeInterval = schedule["timeInterval"]
        val policy = LegacySeasonMapping.toPolicy(
            behavior = behavior,
            reentry = schedule.optionalText(REENTRY, owner),
            offsetDays = schedule.optionalInt(OFFSET, owner),
            hasTimeRule = timeInterval != null && timeInterval !is JsonNull,
        )
        val upgraded = schedule.toMutableMap()
        upgraded.remove(BEHAVIOR)
        upgraded.remove(REENTRY)
        upgraded.remove(OFFSET)
        upgraded["servicePolicy"] = JsonPrimitive(policy.servicePolicy.name)
        upgraded["policyOffsetDays"] = policy.policyOffsetDays?.let(::JsonPrimitive) ?: JsonNull
        val updatedAt = schedule["updatedAt"]
        if (updatedAt != null) upgraded["ruleChangedAt"] = updatedAt else upgraded.remove("ruleChangedAt")
        return JsonObject(upgraded)
    }

    /** Rewrites each object row of the array under [key]; anything else is left for the decode. */
    private fun MutableMap<String, JsonElement>.rewriteRows(
        key: String,
        rewrite: (JsonObject) -> JsonObject,
    ) {
        val rows = this[key] as? JsonArray ?: return
        this[key] = JsonArray(rows.map { if (it is JsonObject) rewrite(it) else it })
    }

    /** A string value, or null for an absent key, a JSON null or any other shape. */
    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** A string the 1.3 DTO declared non-null: anything else was corrupt in 1.3 and still is. */
    private fun JsonObject.requiredText(key: String, owner: String): String =
        text(key) ?: throw BackupCorrupt("$key on $owner is not a string")

    /** A nullable string: absent and JSON null are null; any other non-string was corrupt in 1.3. */
    private fun JsonObject.optionalText(key: String, owner: String): String? = when (this[key]) {
        null, JsonNull -> null
        else -> text(key) ?: throw BackupCorrupt("$key on $owner is not a string")
    }

    /**
     * A nullable Int, read as 1.3's `Int?` field read it: absent and JSON null are null, and a
     * whole number is accepted **quoted or not** (`5` and `"5"`), because 1.3's strict decoder
     * accepted both and every archive 1.3 read must still import. A non-integral or non-numeric
     * value was corrupt in 1.3 and still is.
     */
    private fun JsonObject.optionalInt(key: String, owner: String): Int? = when (val value = this[key]) {
        null, JsonNull -> null
        is JsonPrimitive -> value.intOrNull ?: throw BackupCorrupt("$key on $owner is not a whole number")
        else -> throw BackupCorrupt("$key on $owner is not a whole number")
    }
}
