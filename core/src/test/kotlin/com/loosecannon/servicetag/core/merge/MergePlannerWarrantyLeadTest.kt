package com.loosecannon.servicetag.core.merge

import com.loosecannon.servicetag.core.backup.BackupData
import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.testing.FakeUnitOfWork
import com.loosecannon.servicetag.core.testing.InMemoryAssetRepository
import com.loosecannon.servicetag.core.testing.backupOf
import com.loosecannon.servicetag.core.testing.plainAssetOf
import com.loosecannon.servicetag.core.usecase.SetWarrantyReminder
import com.loosecannon.servicetag.core.usecase.WarrantyReminderCommand
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * #79 (C19; R79-11b, option B on the #67 shape with its stamp rule): an archive older than format 11
 * cannot speak about the warranty reminder, so an asset given a lead here since that export — through
 * the real `SetWarrantyReminder`, which moves the last-modified stamp — is compared **without the lead
 * and without the stamp**, and every pre-11 export keeps re-planning IDENTICAL. Every other field still
 * counts, and a row here with no lead compares its stamp as before. A format-11 archive compares the
 * lead and the stamp like any field; there is no update path. The names are fictional.
 */
class MergePlannerWarrantyLeadTest {

    private val heater = plainAssetOf("a1", "Example Heater").copy(warrantyExpiresOn = "2027-03-01")

    private fun data(vararg assets: Asset) = BackupData(
        assets = assets.map { it.toDto() }, nfcTags = emptyList(), externalLinks = emptyList(),
    )

    private fun planOf(incoming: Asset, formatVersion: Int, local: Asset?): MergePlan = mergePlanOf(
        backupOf(data(incoming), formatVersion),
        MergeSnapshot(assets = listOfNotNull(local), attachmentStoreConfigured = true),
    )

    private fun decision(incoming: Asset, formatVersion: Int, local: Asset): MergeDecision =
        planOf(incoming, formatVersion, local).decisions.single { it.table == MergeTable.ASSETS && it.id == "a1" }

    private fun identical() = MergeDecision(MergeTable.ASSETS, "a1", MergeVerdict.IDENTICAL)

    private fun differs() =
        MergeDecision(MergeTable.ASSETS, "a1", MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, "a1")

    /** [row] with its lead set the way the app sets it: the real use case, at [at], so the stamp moves. */
    private fun ledInTheApp(row: Asset, lead: Int?, at: Long = 5_000L): Asset = runBlocking {
        val here = InMemoryAssetRepository().also { it.upsert(row) }
        SetWarrantyReminder(here, FakeUnitOfWork(here), Clock { at }).run(row.id, WarrantyReminderCommand(lead))
    }

    @Test
    fun aFormat10ArchiveIsIdenticalToAnAssetWhoseLeadWasSetThroughSetWarrantyReminder() {
        val led = ledInTheApp(heater, 30)
        assertEquals(30 to 5_000L, led.warrantyReminderLeadDays to led.updatedAt, "the use case moved the stamp")

        for (format in listOf(10, 9, 8, 5)) {
            val plan = planOf(heater, format, led)
            assertTrue(plan.applicable, "format $format")
            assertEquals(identical(), plan.decisions.single { it.table == MergeTable.ASSETS }, "format $format")
            assertEquals(emptyList(), plan.writes.assets, "format $format")
        }
    }

    /**
     * Only the lead and its stamp are set aside, and only when the row here carries a lead: with none
     * here the stamp counts as it always did — a lead set and then cleared leaves a moved stamp, which
     * conflicts — and a rename is still a rename, lead or not.
     */
    @Test
    fun aFormat10ArchiveWithoutALeadHereComparesAsBefore() {
        val setThenCleared = ledInTheApp(ledInTheApp(heater, 30, at = 5_000L), null, at = 6_000L)
        assertEquals(null to 6_000L, setThenCleared.warrantyReminderLeadDays to setThenCleared.updatedAt)
        assertEquals(differs(), decision(heater, 10, setThenCleared), "no lead here: the moved stamp counts")
        assertEquals(identical(), decision(heater, 10, heater), "an untouched row is as it was")

        val renamed = ledInTheApp(heater, 30).copy(name = "Example Heater, basement")
        assertEquals(differs(), decision(heater, 10, renamed), "a rename here still conflicts beside a lead")
        assertEquals(differs(), decision(heater.copy(warrantyExpiresOn = "2028-01-01"), 10, ledInTheApp(heater, 30)),
            "so does another warranty date")
    }

    /** Format 11 speaks about the lead: it compares with the stamp like any field. */
    @Test
    fun aFormat11ArchiveComparesTheLead() {
        val led = ledInTheApp(heater, 30)
        assertEquals(identical(), decision(led, 11, led), "the same lead and stamp")
        assertEquals(differs(), decision(heater, 11, led), "no lead against a lead here")
        assertEquals(differs(), decision(led.copy(warrantyReminderLeadDays = 45), 11, led), "another lead")
        assertEquals(differs(), decision(led, 11, heater.copy(updatedAt = led.updatedAt)), "a lead against none here")
        assertEquals(differs(), decision(heater.copy(warrantyReminderLeadDays = 30), 11, led), "the same lead, the old stamp")
    }

    /** A format-11 archive's new asset brings its lead with it: the insert writes the row as the archive has it. */
    @Test
    fun aFormat11ArchivesNewAssetBringsItsLead() {
        val plan = planOf(heater.copy(warrantyReminderLeadDays = 14), 11, local = null)
        assertEquals(MergeVerdict.INSERT, plan.decisions.single { it.table == MergeTable.ASSETS }.verdict)
        assertEquals(listOf(14), plan.writes.assets.map { it.warrantyReminderLeadDays })
    }
}
