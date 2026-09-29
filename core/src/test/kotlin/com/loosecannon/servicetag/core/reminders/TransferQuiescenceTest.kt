package com.loosecannon.servicetag.core.reminders

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.maintainedHere
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.testing.InMemoryAssetLoanRepository
import com.loosecannon.servicetag.core.testing.InMemoryAssetRepository
import com.loosecannon.servicetag.core.testing.InMemoryClosureRepository
import com.loosecannon.servicetag.core.testing.InMemoryEventRepository
import com.loosecannon.servicetag.core.testing.InMemoryGroupRepository
import com.loosecannon.servicetag.core.testing.InMemoryScheduleRepository
import com.loosecannon.servicetag.core.testing.InMemoryScheduleStateRepository
import com.loosecannon.servicetag.core.testing.InMemorySeasonActivationRepository
import com.loosecannon.servicetag.core.testing.InMemoryTransferRecordRepository
import com.loosecannon.servicetag.core.testing.dayMillis
import com.loosecannon.servicetag.core.testing.groupOf
import com.loosecannon.servicetag.core.testing.loanOf
import com.loosecannon.servicetag.core.testing.scheduleOf
import com.loosecannon.servicetag.core.testing.transferOf
import com.loosecannon.servicetag.core.usecase.RecomputeSchedules
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * #77 (C11, R77-20) — **held ⇒ no sender-side reminder**, by an explicit record rule and independent of ARCHIVED.
 *
 * The held assets are forced ACTIVE here — the state merged transfer history can leave (R77-17) — and each carries
 * everything that would otherwise remind: "Example Water Heater" (h1) an overdue schedule, a warranty inside its
 * lead and a hand-built open loan (a transfer refuses an open loan, so only merged history has one); h1 and its
 * "Example Anode Rod" (h2) make up a wholly held, **unarchived** group with its own overdue schedule. The staying
 * "Example Compressor" (x1) carries the same three facts and is the control: it still reminds.
 */
class TransferQuiescenceTest {

    private val assets = InMemoryAssetRepository()
    private val events = InMemoryEventRepository()
    private val groups = InMemoryGroupRepository()
    private val closures = InMemoryClosureRepository()
    private val states = InMemoryScheduleStateRepository()
    private val schedules = InMemoryScheduleRepository(closures, states)
    private val loans = InMemoryAssetLoanRepository()
    private val transfers = InMemoryTransferRecordRepository()

    private val today = LocalDate.parse("2026-04-15")
    private val recompute = RecomputeSchedules(
        schedules, states, events, closures, groups, assets, InMemorySeasonActivationRepository(), Today { today },
        Clock { dayMillis("2026-04-15") },
    ) { ZoneOffset.UTC }

    private val reminders = BuildReminderSubjects(schedules, groups, assets, transfers, recompute)
    private val deadlines = BuildDeadlineSubjects(assets, transfers)
    private val lent = BuildLoanSubjects(loans, transfers)

    private suspend fun seed() {
        for ((id, name) in listOf("h1" to "Example Water Heater", "h2" to "Example Anode Rod", "x1" to "Example Compressor")) {
            assets.upsert(
                Asset(
                    id = AssetId(id), name = name, status = AssetStatus.ACTIVE, createdAt = 1L, updatedAt = 1L,
                    warrantyExpiresOn = "2026-05-01", warrantyReminderLeadDays = 30,
                    parentAssetId = if (id == "h2") AssetId("h1") else null,
                ),
            )
        }
        groups.upsert(
            groupOf(id = "g1", members = listOf(Triple("h1", "2025-12-01", null), Triple("h2", "2025-12-01", null))),
        )
        val monthly = scheduleOf(id = "s-h1", assetId = "h1", timeInterval = 1, timeUnit = RecurrenceUnit.MONTH, anchorOn = "2026-03-01")
        schedules.upsert(monthly)
        schedules.upsert(scheduleOf(id = "s-x1", assetId = "x1", timeInterval = 1, timeUnit = RecurrenceUnit.MONTH, anchorOn = "2026-03-01"))
        schedules.upsert(
            scheduleOf(id = "s-g1", assetId = null, groupId = "g1", timeInterval = 1, timeUnit = RecurrenceUnit.MONTH, anchorOn = "2026-03-01"),
        )
        loans.upsert(loanOf("l-h1", assetId = "h1", dueOn = "2026-04-01"))
        loans.upsert(loanOf("l-x1", assetId = "x1", dueOn = "2026-04-01"))
        recompute.all()
        transfers.append(transferOf("out-1", assetId = "h1"))
        transfers.append(transferOf("out-2", assetId = "h2"))
    }

    @Test
    fun theHeldAssetsReadActiveAndAreNotMaintainedHere() = runTest {
        seed()
        val held = transfers.heldIds()
        assertEquals(setOf(AssetId("h1"), AssetId("h2")), held)
        val heater = assets.get(AssetId("h1"))!!
        assertTrue(heater.status == AssetStatus.ACTIVE && heater.retiredOn == null, "forced ACTIVE, not retired")
        assertFalse(heater.maintainedHere(held))
        assertTrue(assets.get(AssetId("x1"))!!.maintainedHere(held))
    }

    @Test
    fun scheduleAndGroupScheduleWithdrawn() = runTest {
        seed()
        val subjects = reminders.forProvider(ProviderId.LOCAL, today)
            .associate { (it.key as SubjectKey.Schedule).scheduleId.value to it.state }

        assertEquals(SubjectState.Withdrawn, subjects["s-h1"], "a held asset's live schedule is withdrawn")
        assertEquals(SubjectState.Withdrawn, subjects["s-g1"], "a wholly held, unarchived group's schedule is withdrawn")
        assertEquals(SubjectState.Active, subjects["s-x1"], "the staying asset still reminds")
    }

    @Test
    fun noDeadlineSubject() = runTest {
        seed()
        assertEquals(
            listOf("x1"),
            deadlines.forProvider(ProviderId.LOCAL, today).map { (it.key as SubjectKey.Deadline).subjectId },
            "a held asset has no warranty warning here, whatever its status",
        )
    }

    @Test
    fun noLoanSubject() = runTest {
        seed()
        assertEquals(
            listOf("x1/l-x1"),
            lent.forProvider(ProviderId.LOCAL, today).map { (it.key as SubjectKey.Deadline).subjectId },
            "a held asset's open loan (merged history) reminds nobody here",
        )
    }
}
