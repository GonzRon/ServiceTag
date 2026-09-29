package com.loosecannon.servicetag.core.reminders

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.testing.InMemoryAssetRepository
import com.loosecannon.servicetag.core.testing.InMemoryTransferRecordRepository
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * #79 (C5, R79-15): which warranty dates a provider is asked to hold.
 *
 * A subject exists exactly while the asset is in service, its warranty date parses, a lead is set
 * and today is not past the expiry. Otherwise it is **absent**, and absence is how a provider is
 * told to let go. The dates here are years away from any real clock, so a builder that read one
 * instead of the `today` it was handed cannot pass.
 */
class BuildDeadlineSubjectsTest {

    private val assets = InMemoryAssetRepository()
    private val builder = BuildDeadlineSubjects(assets, InMemoryTransferRecordRepository())

    private suspend fun seed(
        id: String,
        expiresOn: String? = EXPIRY.toString(),
        lead: Int? = 30,
        name: String = "Example Heater",
        status: AssetStatus = AssetStatus.ACTIVE,
        retiredOn: String? = null,
        seasonMode: SeasonMode = SeasonMode.YEAR_ROUND,
        seasonStart: String? = null,
        seasonEnd: String? = null,
        blackoutStart: String? = null,
        blackoutEnd: String? = null,
    ): Asset = Asset(
        id = AssetId(id),
        name = name,
        status = status,
        createdAt = 1_000L,
        updatedAt = 1_000L,
        warrantyExpiresOn = expiresOn,
        retiredOn = retiredOn,
        seasonMode = seasonMode,
        seasonStartMmdd = seasonStart,
        seasonEndMmdd = seasonEnd,
        blackoutStartMmdd = blackoutStart,
        blackoutEndMmdd = blackoutEnd,
        warrantyReminderLeadDays = lead,
    ).also { assets.upsert(it) }

    private suspend fun subjectsOn(today: LocalDate) = builder.forProvider(ProviderId.LOCAL, today)

    /** Every field C5 names, on a day long before the window and on the expiry day itself. */
    @Test
    fun everyFieldOfAWarrantySubjectThroughItsExpiryDay() = runTest {
        seed("a1")
        val expected = ReminderSubject(
            key = SubjectKey.Deadline(DeadlineKind.WARRANTY_EXPIRY, "a1"),
            title = "Warranty",
            body = "",
            dueOn = EXPIRY,
            leadDays = 30,
            state = SubjectState.Active,
            rule = null,
            contentHash = ContentHash.of("Warranty", "", EXPIRY, 30, SubjectState.Active, null, DeadlineRepeat.ONCE),
            repeat = DeadlineRepeat.ONCE,
        )

        assertEquals(listOf(expected), subjectsOn(EXPIRY.minusYears(3)), "the window is the provider's business")
        assertEquals(listOf(expected), subjectsOn(EXPIRY.minusDays(30)))
        assertEquals(listOf(expected), subjectsOn(EXPIRY), "the expiry day is still in warranty")
    }

    /**
     * The withdrawal (R79-15): nothing to hold without a date or a lead, once the asset is out of
     * service, or after the expiry day. An unparseable date is no date.
     */
    @Test
    fun absentWithNoDateNoLeadArchivedRetiredUnparseableOrTheDayAfter() = runTest {
        seed("a1-no-date", expiresOn = null)
        seed("a2-no-lead", lead = null)
        seed("a3-archived", status = AssetStatus.ARCHIVED)
        seed("a4-retired", retiredOn = "2030-01-01")
        seed("a5-unparseable", expiresOn = "2031-02-30")
        assertEquals(emptyList(), subjectsOn(EXPIRY).map { it.key })

        assets.deleteAll()
        seed("a6")
        assertEquals(1, subjectsOn(EXPIRY).size)
        assertEquals(emptyList(), subjectsOn(EXPIRY.plusDays(1)), "the day after the expiry holds nothing")
    }

    /** R79-15: the builder reads no season and no break, so an out-of-season asset still warns. */
    @Test
    fun anOutOfSeasonAssetStillWarns() = runTest {
        seed(
            "a1",
            seasonMode = SeasonMode.CALENDAR,
            seasonStart = "11-01",
            seasonEnd = "02-28",
            blackoutStart = "06-01",
            blackoutEnd = "07-31",
        )

        assertEquals(
            listOf(SubjectKey.Deadline(DeadlineKind.WARRANTY_EXPIRY, "a1")),
            subjectsOn(EXPIRY).map { it.key },
            "30 June is out of this asset's season and inside its break, and it still warns",
        )
    }

    /** A stable order is what lets two consecutive answers be compared at all. */
    @Test
    fun theSubjectsAreSortedByAssetId() = runTest {
        seed("c3")
        seed("a1")
        seed("b2")

        assertEquals(listOf("a1", "b2", "c3"), subjectsOn(EXPIRY).map { (it.key as SubjectKey.Deadline).subjectId })
    }

    /** Two days either side of a date far from the real one: only a builder that reads `today` tells them apart. */
    @Test
    fun itReadsTodayNeverAClock() = runTest {
        seed("a1", expiresOn = "2044-03-10", lead = 7)

        assertEquals(1, subjectsOn(LocalDate.parse("2044-03-10")).size)
        assertEquals(0, subjectsOn(LocalDate.parse("2044-03-11")).size)
    }

    private companion object {
        val EXPIRY: LocalDate = LocalDate.parse("2031-06-30")
    }
}
