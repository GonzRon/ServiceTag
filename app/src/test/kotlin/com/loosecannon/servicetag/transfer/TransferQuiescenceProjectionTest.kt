package com.loosecannon.servicetag.transfer

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.LoanReminderMode
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.model.TransferRecord
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.reminders.DeliveryFactsSource
import com.loosecannon.servicetag.reminders.FakeDeliveryRepository
import com.loosecannon.servicetag.reminders.FakeReminderNotifications
import com.loosecannon.servicetag.reminders.GrantablePermission
import com.loosecannon.servicetag.reminders.LocalReminderProvider
import com.loosecannon.servicetag.reminders.MutablePlatformState
import com.loosecannon.servicetag.reminders.NonceStore
import com.loosecannon.servicetag.reminders.QuickActionShapeSource
import com.loosecannon.servicetag.reminders.QuickActions
import com.loosecannon.servicetag.reminders.RecordingBackstop
import com.loosecannon.servicetag.reminders.RecordingDigestAlarm
import com.loosecannon.servicetag.reminders.ReminderHealthCheck
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.assetRow
import com.loosecannon.servicetag.testing.conditionRow
import com.loosecannon.servicetag.testing.loanRow
import com.loosecannon.servicetag.testing.scheduleOf
import com.loosecannon.servicetag.ui.dashboard.DashboardViewModel
import com.loosecannon.servicetag.ui.dashboard.SectionEntry
import com.loosecannon.servicetag.ui.maintenance.NoHealthFindings
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #77 (C11, R77-20) — held ⇒ no sender-side actionable projection, on the app's real read models over Room.
 *
 * "Example Water Heater" (h1) is transferred out from this phone but reads **ACTIVE** (merged transfer history,
 * R77-17), so ARCHIVED cannot be what quiesces it: it has an overdue maintenance schedule with no provider, a DOWN
 * condition and an overdue open loan. "Example Compressor" (x1) stays and has the same three facts, as the control.
 * Each projection that answers "what needs attention" leaves h1 out and keeps x1.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferQuiescenceProjectionTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var graph: FakeGraph

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        graph = FakeGraph(queryContext = StandardTestDispatcher(scheduler)).also {
            it.today = LocalDate.parse("2026-04-10")
        }
    }

    @After fun tearDown() {
        graph.close()
        Dispatchers.resetMain()
    }

    /** Everything is written before the OUT, as marking writes it; the OUT makes h1 held. */
    private suspend fun seedHeldHeaterAndStayingCompressor() {
        graph.assets.upsert(assetRow("h1", name = "Example Water Heater"))
        graph.assets.upsert(assetRow("x1", name = "Example Compressor"))
        graph.schedules.upsert(scheduleOf("s-h1", assetId = "h1", title = "Flush the tank").copy(providers = emptyList()))
        graph.schedules.upsert(scheduleOf("s-x1", assetId = "x1", title = "Drain the tank"))
        graph.conditions.insert(conditionRow("c-h1", "h1", OperationalCondition.DOWN, "2026-04-01"))
        graph.conditions.insert(conditionRow("c-x1", "x1", OperationalCondition.DOWN, "2026-04-01"))
        graph.loans.upsert(loanRow("l-h1", "h1", lentOn = "2026-03-01", dueOn = "2026-04-01", mode = LoanReminderMode.ONCE))
        graph.loans.upsert(loanRow("l-x1", "x1", lentOn = "2026-03-01", dueOn = "2026-04-01", mode = LoanReminderMode.ONCE))
        graph.recomputeSchedules.all()
        graph.transferRecords.append(
            TransferRecord(
                id = "out-1", assetId = AssetId("h1"), kind = TransferKind.OUT, packId = "0f1e2d3c-pack",
                lineage = emptyList(), at = 1_758_960_000_000L, packSha256 = "ab".repeat(32),
                nameSnapshot = "Example Water Heater", note = "",
            ),
        )
        assertEquals(AssetStatus.ACTIVE, graph.assets.get(AssetId("h1"))!!.status)
        assertEquals(setOf(AssetId("h1")), graph.transferRecords.heldIds())
    }

    private fun assetsOf(targets: List<ScheduleTarget>) =
        targets.mapNotNull { (it as? ScheduleTarget.AssetTarget)?.assetId?.value }.toSet()

    @Test fun absentFromTheDueProjection() = runTest {
        seedHeldHeaterAndStayingCompressor()
        assertEquals(setOf("x1"), assetsOf(graph.dueReadModel.items().map { it.target }))
    }

    @Test fun absentFromTheAttentionProjection() = runTest {
        seedHeldHeaterAndStayingCompressor()
        assertEquals(listOf("x1"), graph.attentionReadModel.items().map { it.assetId.value })
    }

    @Test fun absentFromTheHealthFleet() = runTest {
        seedHeldHeaterAndStayingCompressor()
        val fleet = graph.assetHealthReadModel.observeRowHealth(flowOf(Unit)).first { it.isNotEmpty() }
        assertEquals(setOf(AssetId("x1")), fleet.keys)
    }

    private fun dashboard() = DashboardViewModel(
        assets = graph.assets,
        schedules = graph.schedules,
        states = graph.scheduleStates,
        due = graph.dueReadModel,
        attention = graph.attentionReadModel,
        assetHealth = graph.assetHealthReadModel,
        health = NoHealthFindings,
        prefs = graph.prefs,
        transfers = graph.transferRecords,
        loans = graph.loans,
        today = graph.todayPort,
    )

    /** The in-service set: nothing drawn names h1, so only that set could list it as a plain row. */
    @Test fun absentFromTheDashboardsInServiceSet() = runTest {
        seedHeldHeaterAndStayingCompressor()
        val vm = dashboard()
        backgroundScope.launch { vm.state.collect() }

        val state = vm.state.first { it.sections.isNotEmpty() }
        assertFalse("no plain row for the held heater", state.assets.any { it.asset.id == AssetId("h1") })
        assertFalse(
            "no section row for the held heater",
            state.sections.flatMap { it.entries }.any { it.assetId == "h1" },
        )
        assertTrue(state.anyInService)
    }

    @Test fun absentFromTheDashboardsLoanRows() = runTest {
        seedHeldHeaterAndStayingCompressor()
        val vm = dashboard()
        backgroundScope.launch { vm.state.collect() }

        val state = vm.state.first { it.sections.isNotEmpty() }
        val loanRows = state.sections.flatMap { it.entries }.filterIsInstance<SectionEntry.Loan>().map { it.row.loanId }
        assertEquals("only the staying compressor's overdue loan is drawn", listOf("l-x1"), loanRows)
    }

    /** The one finding the store drives: a providerless schedule on a held asset is not the owner's to fix here. */
    @Test fun absentFromReminderHealth() = runTest {
        seedHeldHeaterAndStayingCompressor()
        val platform = MutablePlatformState()
        val alarm = RecordingDigestAlarm(isArmed = true)
        val check = ReminderHealthCheck(
            provider = LocalReminderProvider(
                facts = DeliveryFactsSource { null },
                delivery = FakeDeliveryRepository(),
                notifications = FakeReminderNotifications(),
                permission = GrantablePermission(),
                platform = platform,
                alarm = alarm,
                prefs = graph.prefs,
                clock = graph.clock,
                quickActions = QuickActions(
                    shapes = QuickActionShapeSource { null },
                    nonces = NonceStore(FakeDeliveryRepository(), IdGenerator { "nonce" }, graph.clock),
                ),
            ),
            platform = platform,
            backstop = RecordingBackstop(isEnqueued = true),
            alarm = alarm,
            schedules = graph.schedules,
            states = graph.scheduleStateReader,
            assets = graph.assets,
            groups = graph.groups,
            io = Dispatchers.Unconfined,
            transfers = graph.transferRecords,
        )

        assertFalse(
            "the held heater's providerless schedule raises no finding",
            "SCHEDULE_NO_PROVIDER" in check.run().map { it.code },
        )
    }
}
