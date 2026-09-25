package com.loosecannon.servicetag.api

import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private val SerialDescriptor.names: List<String>
    get() = (0 until elementsCount).map { getElementName(it) }

/**
 * 1.4 (B09) — the two documents that describe the `/v1` wire are kept honest by the wire itself.
 *
 * **`docs/api/command-shapes.json`** (master plan §11.6; spec §9.4) lists every command's request
 * keys: the MCP vendors those lists into its package and overlays exactly those keys (B11), so a key
 * added to a request DTO without the file would be a key the MCP silently never sends. Each entry is
 * asserted equal to its request DTO's serializer descriptor, in order.
 *
 * **`docs/api/v1.md`** is the contract: it must name the archive formats this build imports, the
 * fourteen merge tables and every 1.4 code a client can receive.
 */
class CommandShapesGoldenTest {

    private val shapes: JsonObject =
        Json.parseToJsonElement(repoFile("docs/api/command-shapes.json").readText()).jsonObject

    private fun JsonObject.list(key: String): List<String> = getValue(key).jsonArray.map { it.jsonPrimitive.content }

    private fun keysOf(command: String): List<String> = shapes.getValue(command).jsonObject.list("keys")

    @Test fun everyRequestDtoMatchesCommandShapesJson() {
        assertEquals(
            listOf("asset", "schedule", "healthSubject", "seasonMode", "maintenanceBreak", "healthPolicy", "condition", "activation"),
            shapes.keys.toList(),
        )
        assertEquals(keysOf("asset"), AssetCommandRequest.serializer().descriptor.names)
        assertEquals(keysOf("seasonMode"), SeasonModeRequest.serializer().descriptor.names)
        assertEquals(keysOf("maintenanceBreak"), BreakRequest.serializer().descriptor.names)
        assertEquals(keysOf("healthPolicy"), HealthPolicyRequest.serializer().descriptor.names)
        assertEquals(keysOf("condition"), ConditionRequest.serializer().descriptor.names)
        assertEquals(keysOf("activation"), ActivationRequest.serializer().descriptor.names)

        // A subject is created with its asset and replaced without it (inv. 120).
        assertEquals(keysOf("healthSubject"), HealthSubjectCreateRequest.serializer().descriptor.names)
        assertEquals(keysOf("healthSubject") - "assetId", HealthSubjectUpdateRequest.serializer().descriptor.names)

        // The schedule: the 1.4 keys and the deprecated ones, each in the DTO's own order.
        val schedule = shapes.getValue("schedule").jsonObject
        val keys = schedule.list("keys")
        val legacy = schedule.list("legacyKeys")
        val command = ScheduleCommandRequest.serializer().descriptor.names
        assertEquals(keys, command.filterNot { it in legacy })
        assertEquals(legacy, command.filter { it in legacy })
        assertEquals(ScheduleForms.LEGACY_KEYS, legacy)
        assertEquals(ScheduleForms.CURRENT_KEYS, keys.filter { it in ScheduleForms.CURRENT_KEYS })
        assertEquals(ScheduleForms.CURRENT_KEYS, listOf("servicePolicy", "policyOffsetDays"))

        // The action flag: a PATCH's and an archive's, never a key of the command or a row field.
        val flags = schedule.list("actionFlags")
        assertEquals(ScheduleForms.ACTION_FLAGS, flags)
        assertEquals(flags, ScheduleActionFlags.serializer().descriptor.names)
        assertEquals(listOf("archived") + flags, ScheduleArchiveRequest.serializer().descriptor.names)
        val row = ScheduleRowResponse.serializer().descriptor.names
        assertTrue(flags.none { it in command || it in row })

        // `rowToCommand`: the one rename between the row a client reads and the command it sends, and
        // every command key the row reports is found under it.
        val rename = schedule.getValue("rowToCommand").jsonObject.mapValues { it.value.jsonPrimitive.content }
        assertEquals(mapOf("assetId" to "targetAssetId", "groupId" to "targetGroupId"), rename)
        assertEquals((keys + legacy).toSet(), row.map { rename[it] ?: it }.filter { it in command }.toSet())
    }

    @Test fun theContractDocumentNamesFormat8AndFourteenTables() {
        val doc = repoFile("docs/api/v1.md").readText()
        val lines = doc.lines()
        assertTrue("the import range reads 1–8", lines.count { "1–8" in it } >= 2)
        assertEquals(
            "a shipped spelling of the old import range survives",
            emptyList<String>(),
            lines.filter { "format **1–7**" in it || "**format 1–7**" in it },
        )
        assertEquals(emptyList<String>(), lines.filter { "the eleven tables" in it.lowercase() })
        assertTrue("the report's fourteen tables", "fourteen tables" in doc.lowercase())

        for (code in listOf(
            "LEGACY_WRITE_CANNOT_REPRESENT", "LEGACY_AND_CURRENT_FIELDS_MIXED", "SEASON_POLICY_NEEDS_A_TIME_RULE",
            "SEASON_FOLLOWS_ASSET_ON_GROUP_TARGET", "POLICY_OFFSET_INVALID", "SEASON_WINDOW_REQUIRED",
            "SEASON_WINDOW_FORBIDDEN", "MANUAL_PHASE_REQUIRED", "MANUAL_PHASE_FORBIDDEN", "SEASON_DATE_OUT_OF_RANGE",
            "BLACKOUT_COVERS_THE_YEAR", "CONDITION_DATE_IN_FUTURE", "CONDITION_REASON_TOO_LONG", "FOREIGN_EVENT",
            "SCHEDULE_DRIVES_HEALTH_SUBJECT", "HEALTH_SUBJECT_NAME_REQUIRED", "HEALTH_THRESHOLDS_INVALID",
            "HEALTH_DRIVER_MISMATCH", "FOREIGN_SCHEDULE", "HEALTH_SCHEDULE_NEEDS_A_TIME_RULE", "PROFILE_NOT_A_REPLACEMENT",
            "HEALTH_WEIGHT_OUT_OF_RANGE", "HEALTH_PRIMARY_INVALID", "SEASON_NOT_MANUAL", "SEASON_ALREADY_STARTED",
            "SEASON_ALREADY_ENDED", "SEASON_MODE_STRANDS_POLICY", "BREAK_STRANDS_POLICY", "PRE_SERVICE_NEEDS_DATES",
            "HEALTH_SCHEDULE_TAKEN", "HEALTH_SUBJECT_IS_PRIMARY", "NO_SUCH_HEALTH_SUBJECT",
            SEASON_VALIDATION, CONDITION_VALIDATION, "DEFERRED", "UNSCORABLE", "unlinkHealthSubject",
        )) {
            assertTrue("docs/api/v1.md does not name $code", "`$code`" in doc)
        }
        for (path in listOf(
            "/v1/assets/{id}/season", "/v1/assets/{id}/season-mode", "/v1/assets/{id}/maintenance-break",
            "/v1/assets/{id}/health-policy", "/v1/assets/{id}/conditions", "/v1/assets/{id}/health",
            "/v1/assets/{id}/health-subjects", "/v1/health-subjects", "/v1/health-subjects/{id}",
            "/v1/health-subjects/{id}/archive", "/v1/attention", "command-shapes.json",
            // 1.4.1 (#80): the provider repair's two routes.
            "/v1/repairs/schedule-providers/plan", "/v1/repairs/schedule-providers/apply",
        )) {
            assertTrue("docs/api/v1.md does not name $path", "`$path`" in doc || path in doc)
        }
    }
}
