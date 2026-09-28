package com.loosecannon.servicetag.core.backup

import com.loosecannon.servicetag.core.model.CaseStatus
import com.loosecannon.servicetag.core.model.ServiceCase
import com.loosecannon.servicetag.core.model.ServiceCaseEntry
import com.loosecannon.servicetag.core.testing.archiveOf
import com.loosecannon.servicetag.core.testing.caseEntryOf
import com.loosecannon.servicetag.core.testing.caseOf
import com.loosecannon.servicetag.core.testing.dataTreeOf
import com.loosecannon.servicetag.core.testing.editRows
import com.loosecannon.servicetag.core.testing.plainAssetOf
import com.loosecannon.servicetag.core.testing.sealed
import com.loosecannon.servicetag.core.testing.with
import com.loosecannon.servicetag.core.testing.without
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Backup format 12 (#79, C18; R79-1, R79-4): two new lists, `serviceCases` and `serviceCaseEntries`,
 * each its own rows, sorted by id. No shipped writer put a case into a format ≤ 11 archive — the keys
 * did not exist — so one carrying a row was built by hand and is refused after the strict decode; an
 * empty list there, or no key at all, is what this build reads anyway. A case's asset and an entry's
 * case resolve inside the file; a case's Incident and repair links are soft and never checked. The
 * names are fictional.
 */
class BackupFormat12Test {

    private val heater = plainAssetOf("a1", "Example Heater")
    private val boiler = plainAssetOf("a2", "Example Boiler")

    private fun data(
        cases: List<ServiceCase> = emptyList(),
        entries: List<ServiceCaseEntry> = emptyList(),
    ) = BackupData(
        assets = listOf(heater.toDto(), boiler.toDto()), nfcTags = emptyList(), externalLinks = emptyList(),
        serviceCases = cases.map { it.toDto() }, serviceCaseEntries = entries.map { it.toDto() },
    )

    private val closed = caseOf("c1", status = CaseStatus.CLOSED, closedOn = "2026-09-24", resolution = "e-repair-gone")
    private val open = caseOf("c0", assetId = "a2", status = CaseStatus.OPEN, costMinor = null, currency = null, incident = null)
    private val entries = listOf(
        caseEntryOf("n2", status = CaseStatus.CLOSED, note = "Repaired under warranty", occurredOn = "2026-09-24"),
        caseEntryOf("n1", occurredTime = null),
        caseEntryOf("n3", caseId = "c0", note = "Opened by phone"),
    )

    @Test
    fun casesAndEntriesRoundTrip() {
        // Stamped 12 explicitly since #72 moved this build's own format to 13: the cases' own format.
        val bytes = archiveOf(data(cases = listOf(closed, open), entries = entries), formatVersion = 12)

        val decoded = BackupCodec.decode(bytes)

        assertEquals(12, decoded.manifest.formatVersion)
        assertEquals(listOf("c0", "c1"), decoded.data.serviceCases.map { it.id }, "sorted by id")
        assertEquals(listOf("n1", "n2", "n3"), decoded.data.serviceCaseEntries.map { it.id }, "their own rows, sorted by id")
        assertEquals(listOf(open, closed), decoded.data.serviceCases.map { it.toDomain() }, "onto the domain rows, field for field")
        assertEquals(entries.sortedBy { it.id.value }, decoded.data.serviceCaseEntries.map { it.toDomain() })
        assertEquals(2, decoded.manifest.counts["serviceCases"])
        assertEquals(3, decoded.manifest.counts["serviceCaseEntries"])
        val tree = dataTreeOf(bytes)
        assertEquals(
            listOf("serviceCases", "serviceCaseEntries"),
            tree.keys.toList().dropLast(1).takeLast(2),
            "the two lists follow the categories, and format 13's loans close data.json",
        )
        assertTrue("closedOn" in tree.getValue("serviceCases").jsonArray.first().jsonObject, "an unset field is written, not left out")
    }

    /** Through the strict decode (11 … 8) and the ≤ 7 upgrade alike; the refusal names the list and the format. */
    @Test
    fun aFormat11ArchiveWithACaseIsRefused() {
        for (format in listOf(11, 10, 9, 8, 7)) {
            val bytes = archiveOf(data(cases = listOf(closed)), formatVersion = format)

            val refusal = assertFailsWith<BackupCorrupt>("format $format") { BackupCodec.decode(bytes) }

            assertTrue(refusal.message!!.startsWith("serviceCases:") && "format $format" in refusal.message!!, refusal.message)
        }
    }

    /** An entry alone — whose case is nowhere — is refused for its format, before the graph is looked at. */
    @Test
    fun aFormat11ArchiveWithAnEntryIsRefused() {
        for (format in listOf(11, 8)) {
            val bytes = archiveOf(data(entries = listOf(caseEntryOf("n1", caseId = "c-nowhere"))), formatVersion = format)

            val refusal = assertFailsWith<BackupCorrupt>("format $format") { BackupCodec.decode(bytes) }

            assertTrue(
                refusal.message!!.startsWith("serviceCaseEntries:") && "format $format" in refusal.message!!,
                refusal.message,
            )
        }
    }

    /** Explicit empty lists in a format-11 archive restore, and so does the shipped shape with no keys at all. */
    @Test
    fun aFormat11ArchiveWithEmptyListsRestores() {
        val explicit = archiveOf(data(), formatVersion = 11)
        assertEquals(JsonArray(emptyList()), dataTreeOf(explicit)["serviceCases"], "the fixture carries an explicit empty list")
        val absent = sealed(dataTreeOf(explicit).without("serviceCases", "serviceCaseEntries"), formatVersion = 11)
        assertTrue("serviceCases" !in dataTreeOf(absent), "the fixture carries no key")

        for (bytes in listOf(explicit, absent)) {
            val decoded = BackupCodec.decode(bytes)
            assertEquals(11, decoded.manifest.formatVersion)
            assertEquals(data(), decoded.data)
        }
    }

    @Test
    fun aCaseOrEntryWhoseOwnerIsNotInTheArchiveIsCorrupt() {
        val cases = listOf(
            data(cases = listOf(caseOf("c1", assetId = "a9"))) to "serviceCases: case c1 points at asset a9",
            data(cases = listOf(closed), entries = listOf(caseEntryOf("n1", caseId = "c9"))) to
                "serviceCaseEntries: entry n1 points at case c9",
            data(cases = listOf(closed, closed.copy(title = "Twin"))) to "serviceCases: duplicate id c1",
            data(cases = listOf(closed), entries = listOf(caseEntryOf("n1"), caseEntryOf("n1", note = "Twin"))) to
                "serviceCaseEntries: duplicate id n1",
        )
        for ((archive, expected) in cases) {
            val refusal = assertFailsWith<BackupCorrupt>(expected) { BackupCodec.decode(archiveOf(archive)) }
            assertTrue(refusal.message!!.startsWith(expected), refusal.message)
        }
    }

    /** R79-4: the Incident and the repair were deleted after they were linked; the case restores as it was. */
    @Test
    fun aDanglingEventLinkIsAccepted() {
        val dangling = data(cases = listOf(closed), entries = entries.take(2).sortedBy { it.id.value })
        assertEquals("e-incident-gone", dangling.serviceCases.single().incidentEventId)
        assertEquals("e-repair-gone", dangling.serviceCases.single().resolutionEventId)
        assertTrue(dangling.assetEvents.isEmpty(), "no event anywhere in the archive")

        assertEquals(dangling, BackupCodec.decode(archiveOf(dangling)).data)
    }

    @Test
    fun anUnknownEnumNameIsRefused() {
        val tree = dataTreeOf(archiveOf(data(cases = listOf(closed), entries = entries.take(2))))
        val edits = listOf(
            Triple("serviceCases", "type", "unknown case type \"PICKUP\" on case c1"),
            Triple("serviceCases", "coverage", "unknown case coverage \"PICKUP\" on case c1"),
            Triple("serviceCases", "status", "unknown case status \"PICKUP\" on case c1"),
            Triple("serviceCaseEntries", "status", "unknown case status \"PICKUP\" on case entry n1"),
        )
        for ((table, key, expected) in edits) {
            val edited = tree.editRows(table) { it.with(key, JsonPrimitive("PICKUP")) }
            val refusal = assertFailsWith<BackupCorrupt>(expected) { BackupCodec.decode(sealed(edited, formatVersion = 12)) }
            assertEquals(expected, refusal.message)
        }
    }
}
