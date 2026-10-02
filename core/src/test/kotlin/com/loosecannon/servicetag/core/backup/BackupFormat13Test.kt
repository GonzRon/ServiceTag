package com.loosecannon.servicetag.core.backup

import com.loosecannon.servicetag.core.model.AssetLoan
import com.loosecannon.servicetag.core.model.LoanReminderMode
import com.loosecannon.servicetag.core.testing.archiveOf
import com.loosecannon.servicetag.core.testing.dataTreeOf
import com.loosecannon.servicetag.core.testing.editRows
import com.loosecannon.servicetag.core.testing.loanOf
import com.loosecannon.servicetag.core.testing.plainAssetOf
import com.loosecannon.servicetag.core.testing.sealed
import com.loosecannon.servicetag.core.testing.with
import com.loosecannon.servicetag.core.testing.without
import com.loosecannon.servicetag.core.testing.zipEntries
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Backup format 13 (#72, C6; R72-1, R72-4): one new list, `assetLoans`, its own rows sorted by id, the
 * canonical contact link carried beside the name snapshot. No shipped writer put a loan into a format
 * ≤ 12 archive — the key did not exist — so one carrying a row was built by hand and is refused after the
 * strict decode; an empty list there, or no key at all, is what this build reads anyway. A loan's
 * standing and the Room layer's open marker are never in the archive. The names and links are fictional.
 */
class BackupFormat13Test {

    private val drill = plainAssetOf("a1", "Example Drill")
    private val ladder = plainAssetOf("a2", "Example Ladder")

    private fun data(loans: List<AssetLoan> = emptyList()) = BackupData(
        assets = listOf(drill.toDto(), ladder.toDto()), nfcTags = emptyList(), externalLinks = emptyList(),
        assetLoans = loans.map { it.toDto() },
    )

    private val open = loanOf("l2", assetId = "a1", reminderMode = LoanReminderMode.UNTIL_RETURNED)
    private val returned = loanOf("l1", assetId = "a1", lentOn = "2026-08-01", dueOn = "2026-08-15", returnedOn = "2026-08-14")
    private val nameOnly = loanOf(
        "l0", assetId = "a2", borrowerName = "Example Rentals Ltd", contactLookupUri = null, dueOn = null,
        reminderMode = LoanReminderMode.NONE, notes = "",
    )

    @Test
    fun openAndReturnedLoansRoundTripFieldForField() {
        val bytes = archiveOf(data(listOf(open, returned, nameOnly)))

        val decoded = BackupCodec.decode(bytes)

        assertEquals(20, decoded.manifest.formatVersion)
        assertEquals(listOf("l0", "l1", "l2"), decoded.data.assetLoans.map { it.id }, "sorted by id")
        assertEquals(listOf(nameOnly, returned, open), decoded.data.assetLoans.map { it.toDomain() }, "field for field, the link null and set")
        val tree = dataTreeOf(bytes)
        assertEquals("assetLoans", tree.keys.toList().dropLast(5).last(), "the list follows the case lists; format 14's records, format 15's successions, format 18's two lists and format 19's one follow it")
        val row = tree.getValue("assetLoans").jsonArray.first().jsonObject
        assertTrue("contactLookupUri" in row && "dueOn" in row && "returnedOn" in row, "an unset field is written, not left out: $row")
    }

    @Test
    fun theManifestCountsCarryAssetLoans() {
        val manifest = BackupCodec.decode(archiveOf(data(listOf(open, returned, nameOnly)))).manifest

        assertEquals(3, manifest.counts["assetLoans"])
        assertEquals(31, manifest.counts.size, "twenty-three through format 12, the loans, format 14's records, format 15's successions, format 18's three and format 19's two")
        assertEquals(0, BackupCodec.decode(archiveOf(data())).manifest.counts["assetLoans"], "present at zero")
    }

    /** Equal content is equal bytes: the list is written sorted by id whatever order it was handed in. */
    @Test
    fun aShuffledArchiveEncodesToTheSameBytes() {
        val sorted = archiveOf(data(listOf(nameOnly, returned, open)))
        for (order in listOf(listOf(open, returned, nameOnly), listOf(returned, open, nameOnly))) {
            assertContentEquals(dataJson(sorted), dataJson(archiveOf(data(order))), order.map { it.id.value }.toString())
        }
    }

    /** Through the strict decode (12 … 8) and the ≤ 7 upgrade alike; the refusal names the list and the format. */
    @Test
    fun aFormat12ArchiveWithALoanIsRefused() {
        for (format in listOf(12, 11, 9, 8, 7)) {
            val bytes = archiveOf(data(listOf(returned)), formatVersion = format)

            val refusal = assertFailsWith<BackupCorrupt>("format $format") { BackupCodec.decode(bytes) }

            assertTrue(refusal.message!!.startsWith("assetLoans:") && "format $format" in refusal.message!!, refusal.message)
        }
    }

    /** Explicit empty lists in a format-12 archive restore, and so does the shipped shape with no key at all. */
    @Test
    fun aFormat12ArchiveWithEmptyListsRestores() {
        val explicit = archiveOf(data(), formatVersion = 12)
        assertEquals(JsonArray(emptyList()), dataTreeOf(explicit)["assetLoans"], "the fixture carries an explicit empty list")
        val absent = sealed(dataTreeOf(explicit).without("assetLoans"), formatVersion = 12)
        assertTrue("assetLoans" !in dataTreeOf(absent), "the fixture carries no key")

        for (bytes in listOf(explicit, absent)) {
            val decoded = BackupCodec.decode(bytes)
            assertEquals(12, decoded.manifest.formatVersion)
            assertEquals(data(), decoded.data)
        }
    }

    @Test
    fun anUnknownModeIsRefused() {
        val tree = dataTreeOf(archiveOf(data(listOf(open))))
        for (mode in listOf("WEEKLY", "once", "")) {
            val edited = tree.editRows("assetLoans") { it.with("reminderMode", JsonPrimitive(mode)) }
            val refusal = assertFailsWith<BackupCorrupt>(mode) { BackupCodec.decode(sealed(edited, formatVersion = 13)) }
            assertEquals("unknown loan reminder mode \"$mode\" on loan l2", refusal.message)
        }
    }

    /**
     * C6: a loan row is exactly the domain's eleven fields. The Room layer's open marker is a storage
     * detail and the standing is derived at read time, so neither is ever written — and an archive that
     * carries either is an unknown key, refused by the strict decode.
     */
    @Test
    fun theArchiveCarriesNoMarkerOrStanding() {
        val bytes = archiveOf(data(listOf(open, returned)))
        val row = dataTreeOf(bytes).getValue("assetLoans").jsonArray.first().jsonObject
        assertEquals(
            listOf(
                "id", "assetId", "borrowerName", "contactLookupUri", "lentOn", "dueOn", "returnedOn", "reminderMode",
                "notes", "createdAt", "updatedAt",
            ),
            row.keys.toList(),
        )
        val text = String(zipEntries(bytes).values.flatMap { it.toList() }.toByteArray(), Charsets.UTF_8)
        for (word in listOf("openMarker", "open_marker", "standing", "OVERDUE", "LENT_OUT")) {
            assertTrue(word !in text, "the archive names $word")
        }
        for (key in listOf("openMarker", "standing")) {
            val tree = dataTreeOf(bytes).editRows("assetLoans") { it.with(key, JsonPrimitive(1)) }
            assertFailsWith<BackupCorrupt>(key) { BackupCodec.decode(sealed(tree, formatVersion = 13)) }
        }
    }

    private fun dataJson(bytes: ByteArray): ByteArray = zipEntries(bytes).getValue(BackupCodec.DATA_ENTRY)
}
