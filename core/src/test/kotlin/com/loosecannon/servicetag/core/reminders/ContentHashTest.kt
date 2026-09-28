package com.loosecannon.servicetag.core.reminders

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.LoanReminderMode
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.TimeBasis
import com.loosecannon.servicetag.core.testing.InMemoryAssetLoanRepository
import com.loosecannon.servicetag.core.testing.InMemoryAssetRepository
import com.loosecannon.servicetag.core.testing.loanOf
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlinx.coroutines.test.runTest

/**
 * #79 (C4, K2): the bytes that must not move.
 *
 * A schedule subject's hash is part of its tag, and the tag is how the shade answers "already
 * showing, in this form?". A field appended for every subject would move every shipped hash, and an
 * upgrade would then re-post every standing maintenance reminder. So the repeat fact is appended
 * **only when it is there**, and a schedule's hash is pinned to the value the pre-#79 code computed.
 */
class ContentHashTest {

    /**
     * The two hex values were computed at #79a's A2 base (805d28c8) from the shipped canonical form:
     * the six fields joined by the unit separator, with the absent marker for each null. One subject
     * carries every field; the other carries every absent marker, so neither half of the form can
     * move unseen.
     */
    @Test
    fun aScheduleSubjectsHashIsTheBaseValue() {
        val rule = RuleFacts(TimeBasis.FIXED, 3, RecurrenceUnit.MONTH, hasMeter = false, seasonal = false)
        assertEquals(
            "d23ba950ef1895c76ec1de1657ceba85eb4d85dc1b51113461ed9c4a75e426c6",
            ContentHash.of("Filter change", "DUE", LocalDate.parse("2026-04-20"), 14, SubjectState.Active, rule),
        )
        assertEquals(
            "73c0b0f2d4a5dfba4d2e8d5c7e84872e87bf60e0ef72d927a54b5fa6f81b867a",
            ContentHash.of("Anode check", "", null, 0, SubjectState.Parked(null), null),
        )
        assertEquals(
            ContentHash.of("Filter change", "DUE", LocalDate.parse("2026-04-20"), 14, SubjectState.Active, rule),
            ContentHash.of("Filter change", "DUE", LocalDate.parse("2026-04-20"), 14, SubjectState.Active, rule, null),
            "an explicit null repeat is the schedule form, byte for byte",
        )
    }

    /** The repeat fact is content: a deadline announced once is not the same thing as one without it. */
    @Test
    fun theRepeatFactMovesADeadlinesHash() {
        val expiry = LocalDate.parse("2031-06-30")
        val without = ContentHash.of("Warranty", "", expiry, 30, SubjectState.Active, null)
        val once = ContentHash.of("Warranty", "", expiry, 30, SubjectState.Active, null, DeadlineRepeat.ONCE)

        assertNotEquals(without, once)
        assertEquals("b934b705123fd8773912363c7508d26cbce128ff02753d5a2992dedefca8cf4e", once)
    }

    /**
     * #72 (C8, K2): a warranty subject's hash, as the real builder hands it over, is the value
     * computed at #72 B2's base (9cb0b845): the loan work adds a kind and a repeat and moves no byte
     * of the warranty's canonical form, so no standing warning is re-posted on upgrade.
     */
    @Test
    fun aWarrantySubjectsHashIsTheBaseValue() = runTest {
        val assets = InMemoryAssetRepository()
        assets.upsert(
            Asset(
                id = AssetId("a1"),
                name = "Example Heater",
                createdAt = 1_000L,
                updatedAt = 1_000L,
                warrantyExpiresOn = "2031-06-30",
                warrantyReminderLeadDays = 30,
            ),
        )
        val subject = BuildDeadlineSubjects(assets).forProvider(ProviderId.LOCAL, LocalDate.parse("2031-06-01")).single()

        assertEquals("b934b705123fd8773912363c7508d26cbce128ff02753d5a2992dedefca8cf4e", subject.contentHash)
    }

    /**
     * #72 (C9, R72-6): the reminder mode is content. A loan moved from Once to Until returned is a new
     * occurrence — its old post is taken down and the new one announced — so the two hashes differ,
     * and each is pinned to the value the builder hands over for a loan due 2026-10-04.
     */
    @Test
    fun theModeMovesALoansHash() = runTest {
        val loans = InMemoryAssetLoanRepository()
        loans.upsert(loanOf("l1", assetId = "a1", reminderMode = LoanReminderMode.ONCE))
        loans.upsert(loanOf("l2", assetId = "a2", reminderMode = LoanReminderMode.UNTIL_RETURNED))
        val subjects = BuildLoanSubjects(loans).forProvider(ProviderId.LOCAL, LocalDate.parse("2026-10-01"))

        assertEquals(listOf(LOAN_ONCE_HASH, LOAN_UNTIL_CLEARED_HASH), subjects.map { it.contentHash })
        assertNotEquals(subjects[0].contentHash, subjects[1].contentHash)
    }

    private companion object {
        /**
         * The canonical form "Due back", "", 2026-10-04, 0, ACTIVE, the absent rule, then the repeat's
         * name, joined by the unit separator — hashed outside this codebase, so a builder that dropped
         * or reordered a field cannot agree with it by accident.
         */
        const val LOAN_ONCE_HASH = "74cc03459c7b0fa1be179aaa2e48944a811cfe1db646873c9d9aabf80b2a05b5"
        const val LOAN_UNTIL_CLEARED_HASH = "8fb9e2a656f8742256a42929b8f89d7f6e68e217c81f94360c3c9c0e6791933b"
    }
}
