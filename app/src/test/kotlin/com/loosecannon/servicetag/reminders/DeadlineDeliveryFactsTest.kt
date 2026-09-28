package com.loosecannon.servicetag.reminders

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetLoan
import com.loosecannon.servicetag.core.model.AssetLoanId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.LoanReminderMode
import com.loosecannon.servicetag.core.ports.AssetLoanRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.reminders.DeadlineKind
import com.loosecannon.servicetag.core.reminders.SubjectKey
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #72: the loans in a map, as the port answers them; no delete, as the port has none. */
internal class FakeAssetLoanRepository : AssetLoanRepository {
    val rows = linkedMapOf<String, AssetLoan>()
    override suspend fun upsert(loan: AssetLoan) { rows[loan.id.value] = loan }
    override suspend fun get(id: AssetLoanId): AssetLoan? = rows[id.value]
    override suspend fun forAsset(assetId: AssetId): List<AssetLoan> =
        rows.values.filter { it.assetId == assetId }.sortedWith(compareByDescending<AssetLoan> { it.lentOn }.thenBy { it.id.value })
    override suspend fun openFor(assetId: AssetId): AssetLoan? = rows.values.firstOrNull { it.isOpen && it.assetId == assetId }
    override suspend fun open(): List<AssetLoan> = rows.values.filter { it.isOpen }.sortedBy { it.id.value }
    override suspend fun all(): List<AssetLoan> = rows.values.sortedBy { it.id.value }
    override suspend fun deleteAll() = rows.clear()
    override fun observeForAsset(assetId: AssetId): Flow<List<AssetLoan>> = flowOf(rows.values.filter { it.assetId == assetId })
    override fun observeOpen(): Flow<List<AssetLoan>> = flowOf(rows.values.filter { it.isOpen })
}

/** #72: a loan with every field set; fictional names only. */
internal fun sampleLoan(
    id: String = "l1",
    assetId: String = "a1",
    dueOn: String? = "2031-06-18",
    returnedOn: String? = null,
    reminderMode: LoanReminderMode = LoanReminderMode.ONCE,
    borrowerName: String = "Sample Borrower",
): AssetLoan = AssetLoan(
    id = AssetLoanId(id),
    assetId = AssetId(assetId),
    borrowerName = borrowerName,
    contactLookupUri = null,
    lentOn = "2031-06-01",
    dueOn = dueOn,
    returnedOn = returnedOn,
    reminderMode = reminderMode,
    notes = "",
    createdAt = 1_000L,
    updatedAt = 1_000L,
)

/**
 * #79 (C6): the facts a warranty warning is built from — the asset's name and today — or none once it
 * is gone. #72 (C10, R72-7): a loan's facts add its borrower and two instants, both at the owner's
 * digest hour in the device zone: the due day's (the post opens there) and the latest one at or
 * before now (the Until-returned period starts there).
 */
class DeadlineDeliveryFactsTest {

    private val assets = FakeAssetRepository()
    private val loans = FakeAssetLoanRepository()

    private fun facts(
        now: Instant = Instant.parse("2031-06-20T02:30:00Z"),
        digestHour: Int = 9,
        zone: String = "Asia/Kolkata",
        today: String = "2031-06-10",
    ) = DeadlineDeliveryFacts(
        assets,
        Today { LocalDate.parse(today) },
        loans,
        Clock { now.toEpochMilli() },
        digestHour = { digestHour },
        zone = { ZoneId.of(zone) },
    )

    @Test
    fun theOwnerIsTheAssetsNameTodayIsTodaysAndAGoneAssetHasNone() = runTest {
        assets.upsert(Asset(id = AssetId("a1"), name = "Example Heater", createdAt = 1_000L, updatedAt = 1_000L))
        val facts = facts()

        assertEquals(
            DeadlineFacts("Example Heater", LocalDate.parse("2031-06-10")),
            facts.factsFor(SubjectKey.Deadline(DeadlineKind.WARRANTY_EXPIRY, "a1")),
        )
        assertNull(facts.factsFor(SubjectKey.Deadline(DeadlineKind.WARRANTY_EXPIRY, "gone")))
    }

    /**
     * A fixed zone far from UTC (+05:30), at 08:00 local on 20 June: at digest hour 9 today's 09:00
     * is still ahead, so the day opens at 09:00 today and the period began yesterday at 09:00; at
     * digest hour 7 both are today's 07:00. Then digest hour 2 on New York's spring-forward day, whose
     * 02:00–03:00 does not exist: both instants move forward to 03:00 local.
     */
    @Test
    fun aLoanFactNamesItsAssetBorrowerAndItsDigestHourInstants() = runTest {
        assets.upsert(Asset(id = AssetId("a1"), name = "Example Drill", createdAt = 1_000L, updatedAt = 1_000L))
        loans.upsert(sampleLoan(dueOn = "2031-06-18"))
        val key = SubjectKey.Deadline(DeadlineKind.LOAN_DUE_BACK, "a1/l1")

        val nine = facts(digestHour = 9).factsFor(key)!!
        assertEquals("Example Drill", nine.ownerName)
        assertEquals(LocalDate.parse("2031-06-10"), nine.today)
        assertEquals("Sample Borrower", nine.borrower)
        assertEquals("today's 09:00, still ahead", Instant.parse("2031-06-20T03:30:00Z").toEpochMilli(), nine.dayOpensAt)
        assertEquals("yesterday's 09:00", Instant.parse("2031-06-19T03:30:00Z").toEpochMilli(), nine.cadenceSince)
        assertEquals(
            "the words turn at the start of the day after the due day, local",
            Instant.parse("2031-06-18T18:30:00Z").toEpochMilli(),
            nine.wordsTurnAt,
        )

        val seven = facts(digestHour = 7).factsFor(key)!!
        assertEquals("today's 07:00", Instant.parse("2031-06-20T01:30:00Z").toEpochMilli(), seven.dayOpensAt)
        assertEquals("today's 07:00", Instant.parse("2031-06-20T01:30:00Z").toEpochMilli(), seven.cadenceSince)

        loans.upsert(sampleLoan(dueOn = "2031-03-09"))
        val springForward = facts(
            now = Instant.parse("2031-03-09T09:00:00Z"),
            digestHour = 2,
            zone = "America/New_York",
        ).factsFor(key)!!
        assertEquals("03:00 EDT", Instant.parse("2031-03-09T07:00:00Z").toEpochMilli(), springForward.dayOpensAt)
        assertEquals("03:00 EDT", Instant.parse("2031-03-09T07:00:00Z").toEpochMilli(), springForward.cadenceSince)
        assertEquals("00:00 EDT the next day", Instant.parse("2031-03-10T04:00:00Z").toEpochMilli(), springForward.wordsTurnAt)
    }

    /**
     * R72-10 (fix round 1, MINOR-1): custody is independent of service. An open loan on a retired
     * asset, and one on an archived asset, still have facts named after their asset, so their
     * reminders are delivered.
     */
    @Test
    fun aRetiredOrArchivedAssetsOpenLoanStillHasFacts() = runTest {
        assets.upsert(Asset(id = AssetId("a1"), name = "Example Drill", createdAt = 1_000L, updatedAt = 1_000L, retiredOn = "2031-06-15"))
        assets.upsert(
            Asset(id = AssetId("a2"), name = "Example Ladder", status = AssetStatus.ARCHIVED, createdAt = 1_000L, updatedAt = 1_000L),
        )
        loans.upsert(sampleLoan(id = "l1", assetId = "a1"))
        loans.upsert(sampleLoan(id = "l2", assetId = "a2"))
        val facts = facts()

        assertEquals("Example Drill", facts.factsFor(SubjectKey.Deadline(DeadlineKind.LOAN_DUE_BACK, "a1/l1"))?.ownerName)
        assertEquals("Example Ladder", facts.factsFor(SubjectKey.Deadline(DeadlineKind.LOAN_DUE_BACK, "a2/l2"))?.ownerName)
    }

    /** Returned, gone, its asset gone, or a subject id that is not this loan's: no facts, so the post comes down. */
    @Test
    fun aReturnedOrGoneLoanHasNoFacts() = runTest {
        assets.upsert(Asset(id = AssetId("a1"), name = "Example Drill", createdAt = 1_000L, updatedAt = 1_000L))
        assets.upsert(Asset(id = AssetId("other"), name = "Example Ladder", createdAt = 1_000L, updatedAt = 1_000L))
        loans.upsert(sampleLoan(id = "l1", assetId = "a1", returnedOn = "2031-06-19"))
        loans.upsert(sampleLoan(id = "l2", assetId = "gone"))
        loans.upsert(sampleLoan(id = "l3", assetId = "a1"))
        val facts = facts()

        listOf("a1/l1", "a1/nobody", "gone/l2", "a1", "other/l3").forEach { subjectId ->
            assertNull(subjectId, facts.factsFor(SubjectKey.Deadline(DeadlineKind.LOAN_DUE_BACK, subjectId)))
        }
        assertEquals("the open one on its own asset has facts", "Sample Borrower",
            facts.factsFor(SubjectKey.Deadline(DeadlineKind.LOAN_DUE_BACK, "a1/l3"))?.borrower)
    }
}
