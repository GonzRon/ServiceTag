package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * `SetWarrantyReminder` (#79, C2; R79-11, R79-12): the lead is written on the asset row alone, by its
 * own command; it is whole days, at least one, with no upper bound; it needs a warranty date; an
 * equal lead writes nothing; null turns it off. Every name is fictional.
 */
class SetWarrantyReminderTest {

    private val h = ConditionHealthHarness(today = "2026-09-24")

    /** An asset with a warranty date (and optionally a lead), stored as it is. */
    private fun heater(id: String = "a1", expiresOn: String? = "2027-03-01", lead: Int? = null): Asset =
        h.asset(id, name = "Example Heater $id").copy(warrantyExpiresOn = expiresOn, warrantyReminderLeadDays = lead)
            .also { h.assets.rows[id] = it }

    private suspend fun refused(id: String, lead: Int?): List<WarrantyReminderProblem> =
        assertFailsWith<WarrantyReminderValidation> {
            h.setWarrantyReminder.run(AssetId(id), WarrantyReminderCommand(lead))
        }.problems

    @Test
    fun aLeadIsWrittenOnTheAssetRowAlone() = runBlocking<Unit> {
        val before = heater()
        val neighbour = heater("a2", expiresOn = "2028-01-01", lead = 7)
        h.schedule("s1")
        h.now += 5_000L

        val saved = h.setWarrantyReminder.run(AssetId("a1"), WarrantyReminderCommand(30))

        val expected = before.copy(warrantyReminderLeadDays = 30, updatedAt = h.now)
        assertEquals(expected, saved)
        assertEquals(expected, h.stored("a1"), "the lead and the stamp, and nothing else on the row")
        assertEquals(neighbour, h.stored("a2"), "no other asset")
        assertEquals(1, h.assets.upserts, "one asset write")
        assertTrue(h.activations.rows.isEmpty() && h.conditions.rows.isEmpty() && h.events.rows.isEmpty())
        assertTrue(h.states.rows.isEmpty(), "no recompute: nothing a schedule reads moved")
        assertTrue(h.categories.rows.isEmpty())
        assertFailsWith<NoSuchAsset> { h.setWarrantyReminder.run(AssetId("a9"), WarrantyReminderCommand(30)) }
    }

    @Test
    fun anEqualLeadWritesNothing() = runBlocking<Unit> {
        val before = heater(lead = 30)
        h.now += 5_000L

        assertEquals(before, h.setWarrantyReminder.run(AssetId("a1"), WarrantyReminderCommand(30)))
        assertEquals(before, h.stored("a1"), "not even the stamp")
        assertEquals(0, h.assets.upserts)

        val off = heater("a2")
        assertEquals(off, h.setWarrantyReminder.run(AssetId("a2"), WarrantyReminderCommand(null)), "off stays off")
        assertEquals(0, h.assets.upserts)
    }

    @Test
    fun aLeadWithoutAWarrantyDateIsRefused() = runBlocking<Unit> {
        val before = heater(expiresOn = null)

        assertEquals(listOf(WarrantyReminderProblem.LeadWithoutDate), refused("a1", 30))
        assertEquals(
            listOf(WarrantyReminderProblem.LeadNotPositive, WarrantyReminderProblem.LeadWithoutDate),
            refused("a1", 0),
            "every problem at once",
        )
        assertEquals(before, h.stored("a1"))
        assertEquals(0, h.assets.upserts)
        assertEquals(before, h.setWarrantyReminder.run(AssetId("a1"), WarrantyReminderCommand(null)), "no lead needs no date")
    }

    @Test
    fun zeroOrLessIsRefusedAndAnyLargerLeadIsAccepted() = runBlocking<Unit> {
        val before = heater()
        for (lead in listOf(0, -1, -30, Int.MIN_VALUE)) {
            assertEquals(listOf(WarrantyReminderProblem.LeadNotPositive), refused("a1", lead), "$lead")
        }
        assertEquals(before, h.stored("a1"))
        assertEquals(0, h.assets.upserts)

        for ((n, lead) in listOf(1, 30, 365, 3_650, Int.MAX_VALUE).withIndex()) {
            assertEquals(lead, h.setWarrantyReminder.run(AssetId("a1"), WarrantyReminderCommand(lead)).warrantyReminderLeadDays)
            assertEquals(lead, h.stored("a1").warrantyReminderLeadDays, "no upper bound")
            assertEquals(n + 1, h.assets.upserts)
        }
    }

    @Test
    fun nullTurnsItOff() = runBlocking<Unit> {
        val before = heater(lead = 30)
        h.now += 5_000L

        val off = h.setWarrantyReminder.run(AssetId("a1"), WarrantyReminderCommand(null))

        assertNull(off.warrantyReminderLeadDays)
        assertEquals(before.copy(warrantyReminderLeadDays = null, updatedAt = h.now), h.stored("a1"))
        assertEquals("2027-03-01", h.stored("a1").warrantyExpiresOn, "the date stays")
        assertEquals(1, h.assets.upserts)
    }
}
