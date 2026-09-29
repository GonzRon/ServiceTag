package com.loosecannon.servicetag.data.room

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetLoan
import com.loosecannon.servicetag.core.model.AssetLoanId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.ExternalLink
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.LinkId
import com.loosecannon.servicetag.core.model.LinkKind
import com.loosecannon.servicetag.core.model.LoanReminderMode
import com.loosecannon.servicetag.core.model.PayloadFormat
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.model.TagTarget
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.model.heldIds
import com.loosecannon.servicetag.core.usecase.TransferImportPreview
import com.loosecannon.servicetag.core.usecase.TransferImportResult
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.groupOf
import com.loosecannon.servicetag.testing.meterDefinitionOf
import com.loosecannon.servicetag.testing.readingOf
import com.loosecannon.servicetag.testing.scheduleOf
import com.loosecannon.servicetag.ui.transfer.`import`.AppPack
import com.loosecannon.servicetag.ui.transfer.`import`.TransferPackAppFixtures
import com.loosecannon.servicetag.ui.transfer.`import`.TransferPackAppFixtures.HEATER
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #77 (B3; C15, R77-25 (a), the 2.6 tombstone rule) — a transfer back through the **real schema**: the scoped
 * replace deletes the returning asset's stale graph by Room's own cascades (RESTRICT meters and measurements
 * included), inserts the pack's, and writes the sender-local returned loan, the 2.6 link and the tag on it back
 * unchanged. Row 24's REDs are the core's; this proves the database agrees. Fictional names only.
 */
class TransferBackRoomTest {

    private val sender = FakeGraph()
    private val recipient = FakeGraph()

    @After fun close() {
        sender.close()
        recipient.close()
    }

    private val loan = AssetLoan(
        id = AssetLoanId("l1"), assetId = AssetId(HEATER), borrowerName = "Example Buyer", contactLookupUri = null,
        lentOn = "2026-08-01", dueOn = null, returnedOn = "2026-08-02", reminderMode = LoanReminderMode.NONE,
        notes = "", createdAt = 200L, updatedAt = 210L,
    )
    private val link = ExternalLink(
        id = LinkId("L1"), assetId = AssetId(HEATER), kind = LinkKind.WEB, label = "Example notes",
        uri = "https://example.com/notes", createdAt = 100L, updatedAt = 100L,
    )
    private val linkTag = TagBinding(
        id = TagId("t4"), payloadFormat = PayloadFormat.V1, payloadKey = "TEST-0004",
        target = TagTarget.LinkTarget(LinkId("L1")), createdAt = 100L, updatedAt = 100L,
    )

    private suspend fun seedSender() {
        TransferPackAppFixtures.seedHeater(sender)
        sender.definitions.upsert(meterDefinitionOf("d1", HEATER))
        sender.schedules.upsert(scheduleOf("s1", assetId = HEATER, meterDefinitionId = "d1", meterInterval = 100.0, anchorMeter = 0.0))
        sender.events.upsert(readingOf("e1", HEATER, "d1", 42.0))
        sender.groups.upsert(groupOf("G1", "Example Flush Round", members = listOf(Triple(HEATER, "2026-01-01", null))))
        sender.schedules.upsert(scheduleOf("sg", groupId = "G1"))
        sender.links.upsert(link)
        sender.tags.upsert(linkTag)
        sender.loans.upsert(loan)
    }

    private suspend fun FakeGraph.importAll(pack: AppPack): TransferImportResult {
        val ready = importTransferPack.preview { pack.bytes.inputStream() } as TransferImportPreview.Ready
        return importTransferPack.import(ready) { pack.bytes.inputStream() }
    }

    @Test
    fun aReturnReplacesTheStaleGraphAndKeepsTheLoanAndTheTombstones() = runTest {
        seedSender()
        val q1 = TransferPackAppFixtures.seal(sender, HEATER)
        sender.markTransferredOut.run(q1.created)
        assertEquals(setOf(AssetId(HEATER)), heldIds(sender.transferRecords.all()))

        assertTrue(recipient.importAll(q1) is TransferImportResult.Imported)
        repeat(20) { recipient.ids.newId() }
        recipient.events.upsert(readingOf("e2", HEATER, "d1", 57.0, occurredOn = "2026-09-01"))
        val q2 = TransferPackAppFixtures.seal(recipient, HEATER)

        val result = sender.importAll(q2)

        assertTrue("$result", result is TransferImportResult.Imported)
        assertEquals(emptySet<AssetId>(), heldIds(sender.transferRecords.all()))
        assertEquals(
            listOf(TransferKind.OUT, TransferKind.IN),
            sender.transferRecords.forAsset(AssetId(HEATER)).sortedBy { it.at }.map { it.kind },
        )
        assertEquals(recipient.assets.get(AssetId(HEATER)), sender.assets.get(AssetId(HEATER)))
        assertEquals(AssetStatus.ACTIVE, sender.assets.get(AssetId(HEATER))?.status)
        assertEquals(listOf("e1", "e2"), sender.events.forAsset(AssetId(HEATER)).map { it.id.value }.sorted())
        assertNotNull(sender.schedules.get(ScheduleId("s1")))
        assertEquals(recipient.groups.get(GroupId("G1")), sender.groups.get(GroupId("G1")))
        assertNotNull(sender.schedules.get(ScheduleId("sg")))
        assertEquals("R77-25 (a): the returned loan stays", listOf(loan), sender.loans.forAsset(AssetId(HEATER)))
        assertEquals("the 2.6 tombstone stays", listOf(link), sender.links.all())
        assertEquals("and its tag", linkTag, sender.tags.get(TagId("t4")))
        assertEquals(listOf("t1", "t4"), sender.tags.all().map { it.id.value }.sorted())
    }
}
