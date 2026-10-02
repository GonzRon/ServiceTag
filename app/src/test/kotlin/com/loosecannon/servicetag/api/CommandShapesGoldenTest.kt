package com.loosecannon.servicetag.api

import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * twenty-three merge tables (#74's format 9 added the categories, #79b's format 12 the service cases and
 * their entries, #72's format 13 the loans, #77's format 14 the transfer records, #86's format 15 the
 * successions, #15's format 18 the supply items and the asset supplies, #47's format 19 the installed components) and every 1.4 code a client can receive, #74's two category reasons, #79's two warranty
 * routes and their refusal family, #79b's five service-case routes and theirs, #72's five loan routes and
 * theirs, #77's one 409, its status count, its report tally and its two merge reasons, and #86's read-only
 * succession route, its status count, its report tally and its two merge reasons. #92 adds five request shapes
 * (the attachment edit, the upload's header object, save as document's review fields, the replace draft and its
 * successor) and every code C2 answers, and retires the sentences that said only a tap on the phone fetches.
 */
class CommandShapesGoldenTest {

    private val shapes: JsonObject =
        Json.parseToJsonElement(repoFile("docs/api/command-shapes.json").readText()).jsonObject

    private fun JsonObject.list(key: String): List<String> = getValue(key).jsonArray.map { it.jsonPrimitive.content }

    private fun keysOf(command: String): List<String> = shapes.getValue(command).jsonObject.list("keys")

    @Test fun everyRequestDtoMatchesCommandShapesJson() {
        assertEquals(
            listOf(
                "asset", "schedule", "healthSubject", "seasonMode", "maintenanceBreak", "healthPolicy", "condition", "activation",
                // #79 (C12): the lead's own command, never a key of the asset's.
                "warrantyReminder",
                // #79b (C24): the case header and its timeline entry, lowerCamel like every key here.
                "serviceCase", "caseEntry",
                // #72 (C21): the lend and the return; the replace is the lend's keys less two.
                "loan", "loanReturn",
                // #92 (C26): the attachment edit, the upload's header object, save as document, the replace draft.
                "attachmentUpdate", "attachmentUpload", "materialize", "replaceDraft", "replaceSuccessor",
            ),
            shapes.keys.toList(),
        )
        assertEquals(keysOf("attachmentUpdate"), UpdateAttachmentRequest.serializer().descriptor.names)
        assertEquals(keysOf("attachmentUpload"), UploadMetadata.serializer().descriptor.names)
        assertEquals("operationKey", keysOf("attachmentUpload").first())
        assertTrue("an upload never sets provenance", keysOf("attachmentUpload").none { it.startsWith("source") })
        assertEquals(keysOf("materialize"), MaterializeRequest.serializer().descriptor.names)
        assertTrue("save as document takes no URL (R92-3)", keysOf("materialize").none { it in setOf("uri", "url") })
        assertEquals(keysOf("replaceDraft"), ReplaceDraftRequest.serializer().descriptor.names)
        assertFalse("the predecessor is the path's", "predecessorId" in keysOf("replaceDraft"))
        assertEquals(keysOf("replaceSuccessor"), ReplaceSuccessorRequest.serializer().descriptor.names)
        assertEquals(
            keysOf("asset") - listOf("description", "notes", "templateKey", "seasonStartMmdd", "seasonEndMmdd").toSet(),
            keysOf("replaceSuccessor"),
        )
        assertEquals(keysOf("asset"), AssetCommandRequest.serializer().descriptor.names)
        assertEquals(keysOf("seasonMode"), SeasonModeRequest.serializer().descriptor.names)
        assertEquals(keysOf("maintenanceBreak"), BreakRequest.serializer().descriptor.names)
        assertEquals(keysOf("healthPolicy"), HealthPolicyRequest.serializer().descriptor.names)
        assertEquals(keysOf("condition"), ConditionRequest.serializer().descriptor.names)
        assertEquals(keysOf("activation"), ActivationRequest.serializer().descriptor.names)
        assertEquals(keysOf("warrantyReminder"), WarrantyReminderRequest.serializer().descriptor.names)
        assertFalse("the asset command never carries the lead (K4)", "warrantyReminderLeadDays" in keysOf("asset"))

        // A case is opened with its asset and, optionally, its Incident, and replaced without either;
        // its status and `closedOn` are in neither body — they move only through a status entry (R79-5).
        assertEquals(keysOf("serviceCase"), ServiceCaseCreateRequest.serializer().descriptor.names)
        assertEquals(
            keysOf("serviceCase") - "assetId" - "incidentEventId",
            ServiceCaseUpdateRequest.serializer().descriptor.names,
        )
        assertEquals(keysOf("caseEntry"), CaseEntryRequest.serializer().descriptor.names)
        assertTrue("no header body writes the status", listOf("status", "closedOn").none { it in keysOf("serviceCase") })

        // A loan is lent with its asset and borrower and replaced without either (R72-17); the return date
        // is the return's alone; and no body takes a contact link (R72-4).
        assertEquals(keysOf("loan"), LoanCreateRequest.serializer().descriptor.names)
        assertEquals(keysOf("loan") - "assetId" - "borrowerName", LoanUpdateRequest.serializer().descriptor.names)
        assertEquals(keysOf("loanReturn"), LoanReturnRequest.serializer().descriptor.names)
        assertEquals(listOf("returnedOn"), keysOf("loanReturn"))
        assertTrue(
            "no loan body carries a link or the return date",
            (keysOf("loan") + keysOf("loanReturn") - "returnedOn").none { "contact" in it || "Uri" in it || it == "returnedOn" },
        )

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

    @Test fun theContractDocumentNamesFormat19AndTwentyThreeTables() {
        val doc = repoFile("docs/api/v1.md").readText()
        val lines = doc.lines()
        // Anchored to the two spellings: a bare "1–10" is also the health weight's range.
        assertEquals(
            "the import range reads 1–19 at both sites",
            2,
            lines.count { "format **1–19**" in it || "**format 1–19**" in it },
        )
        assertEquals(
            "a shipped spelling of an old import range survives",
            emptyList<String>(),
            lines.filter { line ->
                listOf("1–7", "1–8", "1–9", "1–10", "1–11", "1–12", "1–13", "1–14", "1–15", "1–16", "1–17", "1–18").any {
                    "format **$it**" in line || "**format $it**" in line
                }
            },
        )
        // #67: the status line names the new numbers, and IDENTICAL states R67-12's rule for the role.
        assertTrue("the status line says 10 since #67", lines.count { "10 since #67" in it } >= 1)
        // #79: and 11 since the warranty reminder lead, and IDENTICAL states R79-11b's rule for the lead.
        assertTrue("the status line says 11 since #79", lines.count { "11 since #79 (warranty reminders)" in it } >= 1)
        // #79b: and 12 since the service cases.
        assertTrue("the status line says 12 since #79", lines.count { "12 since #79 (service cases)" in it } >= 1)
        // #72: and 13 since the loans.
        assertTrue("the status line says 13 since #72", lines.count { "13 since #72 (loans)" in it } >= 1)
        // #77: and 14 since the transfer records.
        assertTrue("the status line says 14 since #77", lines.count { "14 since #77 (transfer records)" in it } >= 1)
        // #86: and 15 since the asset successions.
        assertTrue("the status line says 15 since #86", lines.count { "15 since #86 (asset successions)" in it } >= 1)
        // #85: and 16 since the attachment provenance.
        assertTrue(
            "the status line says 16 since #85",
            lines.count { "16 since #85 (attachment provenance)" in it } >= 1,
        )
        // #91: and 17 since the reference document role, on both status lines (schema and format), and
        // IDENTICAL states C7's rule for a reference's role in #67's words.
        assertEquals(
            "both status lines say 17 since #91",
            2,
            lines.count { "17 since #91 (reference document role)" in it },
        )
        // #15: and 18 since the supply items, on both status lines.
        assertEquals("both status lines say 18 since #15", 2, lines.count { "18 since #15 (supply items)" in it })
        // #47: and 19 since the installed components, on both status lines.
        assertEquals(
            "both status lines say 19 since #47",
            2,
            lines.count { "19 since #47 (installed components)" in it },
        )
        val identical = lines.single { it.startsWith("| `IDENTICAL` |") }
        assertTrue("IDENTICAL must state the role rule: $identical", "document role" in identical && "format 10" in identical)
        assertTrue(
            "IDENTICAL must state the lead rule: $identical",
            "`warrantyReminderLeadDays`" in identical && "format 11" in identical,
        )
        assertTrue(
            "IDENTICAL must state the reference role rule: $identical",
            "a reference's document role" in identical && "format 17" in identical,
        )
        assertEquals(emptyList<String>(), lines.filter { "the eleven tables" in it.lowercase() })
        assertTrue("the report's twenty-three tables", "twenty-three tables" in doc.lowercase())
        assertFalse("the report's old twenty-two tables", "twenty-two tables" in doc.lowercase())
        assertFalse("the report's old twenty tables", "twenty tables" in doc.lowercase())
        assertFalse("the report's old fourteen tables", "fourteen tables" in doc.lowercase())
        assertFalse("the report's old fifteen tables", "fifteen tables" in doc.lowercase())
        assertFalse("the report's old seventeen tables", "seventeen tables" in doc.lowercase())
        assertFalse("the report's old eighteen tables", "eighteen tables" in doc.lowercase())
        assertFalse("the report's old nineteen tables", "nineteen tables" in doc.lowercase())
        // #74: the two reasons a category row can be declined with, and the new status key and tally.
        for (name in listOf("CATEGORY_KEY_HELD", "CATEGORY_IS_BUILT_IN", "assetCategories", "categories")) {
            assertTrue("docs/api/v1.md does not name $name", "`$name`" in doc)
        }

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
            // #79: the lead's refusal family and the row field it writes.
            "warranty_reminder_validation", "warrantyReminderLeadDays",
            // #79b: the case family, its 404, the two status counts and the two report tallies.
            "service_case_validation", "no_such_service_case", "serviceCases", "serviceCaseEntries", "caseEntries",
            // #72: the loan family, its 404 and two 409s, the status count, the report tally and the merge reason.
            "loan_validation", "no_such_loan", "asset_already_lent", "loan_returned", "assetLoans", "loans",
            "ASSET_ALREADY_LENT",
            // #77: the one 409, the status count, the report tally and the two merge reasons.
            "asset_transferred_out", "transferRecords", "transfers", "ASSET_TRANSFERRED_OUT", "TRANSFER_DIVERGED",
            // #86: the status count, the report tally and the two merge reasons.
            "assetSuccessions", "successions", "SUCCESSION_TAKEN", "SUCCESSION_CYCLE",
            // #92 (C2, C25): every code the attachment, save-as-document and replace routes answer, and the status key.
            "NO_SUCH_ATTACHMENT", "ATTACHMENT_NAME_REQUIRED", "ATTACHMENT_BAD_DATE", "ATTACHMENT_ROLE_NOT_ALLOWED",
            "ATTACHMENT_EMPTY", "ATTACHMENT_SHA256_INVALID", "ATTACHMENT_SHA256_MISMATCH", "ATTACHMENT_TOO_LARGE",
            "ATTACHMENT_STORE_NOT_CONFIGURED", "store_unavailable", "ATTACHMENT_ALREADY_HELD", "OPERATION_KEY_INVALID",
            "OPERATION_KEY_REUSED", "UPLOAD_NOT_STAGED", "REFERENCE_NOT_MATERIALIZABLE", "NETWORK_DENIED",
            "FETCH_NOT_HTTPS", "FETCH_HAS_CREDENTIALS", "FETCH_LOCAL_ADDRESS", "FETCH_UNREACHABLE", "FETCH_INTERRUPTED",
            "FETCH_TIMED_OUT", "FETCH_TOO_LARGE", "FETCH_EMPTY", "FETCH_NOT_A_DOCUMENT", "FETCH_NEEDS_SIGN_IN",
            "FETCH_SERVER_ERROR", "FETCH_REDIRECT_REFUSED", "ASSET_ALREADY_REPLACED", "REPLACE_STALE",
            "REPLACE_NAME_REQUIRED", "REPLACE_BAD_DATE", "REPLACE_NOT_OFFERED", "REPLACE_NEEDS_SETUP",
            "REPLACE_NEEDS_SEASON", "REPLACE_PHASE_REQUIRED", "REPLACE_DATE_AFTER_TODAY", "asset_validation",
            "installationId", "X-ServiceTag-Attachment", "sourcesDigest",
            // #91 (C2): the one new code, a role on a reference that is not a web link.
            "REFERENCE_ROLE_NOT_ALLOWED",
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
            // #79 (C12): the warranty and its reminder lead.
            "/v1/assets/{id}/warranty", "/v1/assets/{id}/warranty-reminder",
            // #79b (C24): the case routes' four path shapes.
            "/v1/assets/{id}/service-cases", "/v1/service-cases", "/v1/service-cases/{id}", "/v1/service-cases/{id}/entries",
            // #72 (C21): the loan routes' four path shapes.
            "/v1/assets/{id}/loans", "/v1/loans", "/v1/loans/{id}", "/v1/loans/{id}/return",
            // #86 (C20): the one read-only succession route.
            "/v1/assets/{id}/succession",
            // #92 (C3): the eight rows' six path shapes, and the upload's golden vector.
            "/v1/assets/{id}/attachments", "/v1/attachments/{id}", "/v1/references/{id}/materialize",
            "/v1/assets/{id}/replace-offer", "/v1/assets/{id}/replace-plan", "/v1/assets/{id}/replace",
            "attachment-operation-ids.json",
        )) {
            assertTrue("docs/api/v1.md does not name $path", "`$path`" in doc || path in doc)
        }
        // #92 (C18, R92-1): a paired workstation can now fetch and replace, so these sentences are retired.
        for (retired in listOf("never connects out", "no route here and no MCP tool reaches", "Recorded by the phone alone")) {
            assertFalse("docs/api/v1.md still says \"$retired\"", retired in doc)
        }
    }
}
