package com.loosecannon.servicetag.ui.asset

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.warranty.WarrantyStatus
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.assetRow
import com.loosecannon.servicetag.testing.dayMillis
import com.loosecannon.servicetag.ui.condition.displayDate
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #79 (C10, R79-17; §3 row 26): the asset detail's Warranty section, as the detail state carries it,
 * over a Room-backed [FakeGraph]. The status is derived from the stored date and the `Today` port,
 * never from the clock; the reminder line is drawn only while the asset is in warranty, has a lead
 * and is in service; and DETAILS no longer carries the warranty's two rows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AssetWarrantyStateTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var graph: FakeGraph

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        graph = FakeGraph(queryContext = StandardTestDispatcher(scheduler))
    }

    @After fun tearDown() {
        graph.close()
        Dispatchers.resetMain()
    }

    private fun detailModel(id: AssetId) = AssetDetailViewModel(
        graph.assets, graph.tags,
        graph.definitions, graph.profiles, graph.events,
        graph.schedules, graph.scheduleStates, graph.groups, graph.dueReadModel,
        graph.conditions, graph.seasonActivations, graph.healthSubjects, graph.attachments,
        graph.assetHealthReadModel, graph.getAssetSeason, graph.recordSeasonActivation,
        graph.archiveAsset, graph.retireAsset, graph.deleteAsset,
        graph.applyTemplate, graph.uow, graph.clock, graph.todayPort, id,
    )

    private suspend fun TestScope.loaded(id: String): AssetDetailState {
        val vm = detailModel(AssetId(id))
        backgroundScope.launch { vm.state.collect() }
        return vm.state.first { it != null }!!
    }

    private suspend fun heater(
        id: String,
        expiresOn: String?,
        lead: Int? = null,
        notes: String = "",
        status: AssetStatus = AssetStatus.ACTIVE,
        retiredOn: String? = null,
    ) = graph.assets.upsert(
        assetRow(id, name = "Example Heater", status = status, retiredOn = retiredOn).copy(
            warrantyExpiresOn = expiresOn,
            warrantyReminderLeadDays = lead,
            warrantyNotes = notes,
        ),
    )

    private fun shown(date: String) = displayDate(LocalDate.parse(date))

    /**
     * K7: the clock and `Today` are set years apart, each on the wrong side of the date, so a status
     * read from the clock says the opposite of the one read from `Today`. The expiry day is still in.
     */
    @Test fun theStatusFollowsTodayNotTheClock() = runTest {
        heater("heater", expiresOn = "2028-06-30")
        heater("bare", expiresOn = null)

        graph.today = LocalDate.parse("2028-06-30")
        graph.now = dayMillis("2031-01-01")
        val onTheDay = loaded("heater").warranty
        assertEquals("the expiry day is in", WarrantyStatus.IN_WARRANTY, onTheDay.status)
        assertEquals(IN_WARRANTY_WORD, onTheDay.badge)
        assertEquals(warrantyExpiresLine(shown("2028-06-30")), onTheDay.dateLine)

        graph.today = LocalDate.parse("2028-07-01")
        graph.now = dayMillis("2020-01-01")
        val after = loaded("heater").warranty
        assertEquals("the day after is not", WarrantyStatus.OUT_OF_WARRANTY, after.status)
        assertEquals(OUT_OF_WARRANTY_WORD, after.badge)
        assertEquals(warrantyExpiredLine(shown("2028-06-30")), after.dateLine)

        val bare = loaded("bare").warranty
        assertEquals(WarrantyStatus.NOT_RECORDED, bare.status)
        assertNull("P79-3 is drawn instead of a badge", bare.badge)
        assertNull(bare.dateLine)
        assertNull(bare.reminderLine)
    }

    /**
     * m9: the line promises a warning, so it is drawn only where one can be delivered — in warranty,
     * with a lead, on an asset in service (active and not retired). P79-7 at one day.
     */
    @Test fun theReminderLineOnlyWhileInWarrantyWithALeadAndInService() = runTest {
        graph.today = LocalDate.parse("2028-01-10")
        heater("thirty", expiresOn = "2028-06-30", lead = 30)
        heater("one", expiresOn = "2028-06-30", lead = 1)
        heater("none", expiresOn = "2028-06-30")
        heater("out", expiresOn = "2027-12-31", lead = 30)
        heater("retired", expiresOn = "2028-06-30", lead = 30, retiredOn = "2027-06-01")
        heater("archived", expiresOn = "2028-06-30", lead = 30, status = AssetStatus.ARCHIVED)

        assertEquals("Reminder: 30 days before", loaded("thirty").warranty.reminderLine)
        assertEquals(REMINDER_ONE_DAY_BEFORE, loaded("one").warranty.reminderLine)
        assertEquals("Reminder: 1 day before", REMINDER_ONE_DAY_BEFORE)
        assertNull("no lead, no line", loaded("none").warranty.reminderLine)
        assertNull("out of warranty, no line", loaded("out").warranty.reminderLine)
        for (id in listOf("retired", "archived")) {
            val facts = loaded(id).warranty
            assertFalse("$id is not in service", facts.inService)
            assertEquals("$id still says where its warranty stands", IN_WARRANTY_WORD, facts.badge)
            assertEquals(30, facts.leadDays)
            assertNull("$id: no warning can be delivered, so no line promises one", facts.reminderLine)
        }
    }

    /** R79-17: the section takes the date and the notes, and " (expired)" retires with them. */
    @Test fun theDetailsRowsNoLongerCarryTheWarranty() = runTest {
        graph.today = LocalDate.parse("2028-01-10")
        graph.assets.upsert(
            assetRow("heater", name = "Example Heater").copy(
                purchaseOn = "2026-01-15",
                vendor = "Example Supply",
                warrantyExpiresOn = "2027-06-30",
                warrantyNotes = "Parts only",
            ),
        )

        val page = loaded("heater")
        val rows = detailsFacts(page)

        assertEquals(listOf("Purchase date", "Vendor"), rows.map { it.first })
        assertTrue(rows.none { (_, value) -> "expired" in value })
        assertEquals("Parts only", page.warranty.notes)
        assertEquals(warrantyExpiredLine(shown("2027-06-30")), page.warranty.dateLine)
    }
}
