package com.loosecannon.servicetag.core.backup

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetCondition
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetLoan
import com.loosecannon.servicetag.core.model.CaseStatus
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.HealthAggregation
import com.loosecannon.servicetag.core.model.HealthSubject
import com.loosecannon.servicetag.core.model.HealthSubjectId
import com.loosecannon.servicetag.core.model.MaintenanceGroup
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.MeasurementDefinition
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.LoanReminderMode
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.SeasonActivation
import com.loosecannon.servicetag.core.model.ServiceCase
import com.loosecannon.servicetag.core.model.ServiceCaseEntry
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.model.ValueType
import com.loosecannon.servicetag.core.testing.GOLDEN_FORMAT_7
import com.loosecannon.servicetag.core.testing.activationOf
import com.loosecannon.servicetag.core.testing.archiveOf
import com.loosecannon.servicetag.core.testing.caseEntryOf
import com.loosecannon.servicetag.core.testing.caseOf
import com.loosecannon.servicetag.core.testing.loanOf
import com.loosecannon.servicetag.core.testing.conditionOf
import com.loosecannon.servicetag.core.testing.groupOf
import com.loosecannon.servicetag.core.testing.plainAssetOf
import com.loosecannon.servicetag.core.testing.resourceBytes
import com.loosecannon.servicetag.core.testing.scheduleOf
import com.loosecannon.servicetag.core.testing.subjectOf
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The format-8 content check (the controller's ruling on B03's concern 3): a restore refuses a row a
 * command would refuse, naming the command's own problem, and lands everything a command — or a
 * merge — could have written. One refused archive per rule; the edges and the merge-only states
 * decode; the golden format-7 archive still decodes through it.
 */
class BackupContentCheckTest {

    private val generator = plainAssetOf("a1", "Generator")

    private val hours = MeasurementDefinition(
        id = DefinitionId("d-hours"), assetId = AssetId("a1"), key = "engine_hours", label = "Engine hours", unit = "h",
        valueType = ValueType.NUMBER, decimals = 1, rangeLow = null, rangeHigh = null, isMeter = true, sortOrder = 0,
        archivedAt = null, createdAt = 100L, updatedAt = 100L,
    )

    private fun timed(
        id: String,
        policy: ServicePolicy,
        offset: Int?,
        assetId: String? = "a1",
        groupId: String? = null,
        status: ScheduleStatus = ScheduleStatus.ACTIVE,
    ): MaintenanceSchedule = scheduleOf(
        id = id, assetId = assetId, groupId = groupId, timeInterval = 1, timeUnit = RecurrenceUnit.YEAR,
        anchorOn = "2026-01-01", servicePolicy = policy, policyOffsetDays = offset, status = status,
    )

    private fun data(
        assets: List<Asset> = listOf(generator),
        definitions: List<MeasurementDefinition> = emptyList(),
        groups: List<MaintenanceGroup> = emptyList(),
        schedules: List<MaintenanceSchedule> = emptyList(),
        subjects: List<HealthSubject> = emptyList(),
        conditions: List<AssetCondition> = emptyList(),
        activations: List<SeasonActivation> = emptyList(),
        categories: List<AssetCategoryDto> = emptyList(),
    ) = BackupData(
        assets = assets.map { it.toDto() },
        nfcTags = emptyList(),
        externalLinks = emptyList(),
        measurementDefinitions = definitions.map { it.toDto() },
        maintenanceGroups = groups.map { it.toDto() },
        maintenanceSchedules = schedules.map { it.toDto() },
        seasonActivations = activations.map { it.toDto() },
        assetConditions = conditions.map { it.toDto() },
        healthSubjects = subjects.map { it.toDto() },
        assetCategories = categories,
    )

    /** The refusal's message, which names the table, the row and the command's problem. */
    private fun refused(data: BackupData): String =
        assertFailsWith<BackupCorrupt> { BackupCodec.decode(archiveOf(data)) }.message!!

    private fun assertRefused(data: BackupData, row: String, problem: String) {
        val message = refused(data)
        assertTrue(message.startsWith(row) && message.contains(problem), message)
    }

    /** `SaveSchedule`'s offset ranges (spec §4.2), through B04's `policyProblems`. */
    @Test
    fun aPolicyOffsetOutsideItsRangeIsRefused() {
        assertRefused(
            data(schedules = listOf(timed("s1", ServicePolicy.IN_SERVICE_AT_START, 366))),
            "maintenanceSchedules: schedule s1", "PolicyOffsetInvalid",
        )
        assertRefused(
            data(schedules = listOf(timed("s1", ServicePolicy.PRE_SERVICE, null))),
            "maintenanceSchedules: schedule s1", "PolicyOffsetInvalid",
        )
    }

    /** PRE_SERVICE on a meter-only schedule has no date to move (O-7). */
    @Test
    fun preServiceOnAMeterOnlyScheduleIsRefused() {
        val meterOnly = scheduleOf(
            id = "s1", assetId = "a1", meterDefinitionId = "d-hours", meterInterval = 250.0,
            servicePolicy = ServicePolicy.PRE_SERVICE, policyOffsetDays = -14,
        )
        assertRefused(
            data(definitions = listOf(hours), schedules = listOf(meterOnly)),
            "maintenanceSchedules: schedule s1", "SeasonPolicyNeedsATimeRule",
        )
    }

    /** A group target is CONTINUOUS only (inv. 106). */
    @Test
    fun aNonContinuousPolicyOnAGroupTargetIsRefused() {
        assertRefused(
            data(
                groups = listOf(groupOf("g1")),
                schedules = listOf(timed("s1", ServicePolicy.IN_SERVICE_RESUME_CLAMPED, null, assetId = null, groupId = "g1")),
            ),
            "maintenanceSchedules: schedule s1", "SeasonFollowsAssetOnGroupTarget",
        )
    }

    /** `SetMaintenanceBreak`'s year-long break (master plan §7.2): its shape is the graph check's, its reach this one's. */
    @Test
    fun aBreakCoveringTheYearIsRefused() {
        val yearLong = generator.copy(blackoutStartMmdd = "03-01", blackoutEndMmdd = "02-28")
        assertRefused(data(assets = listOf(yearLong)), "assets: asset a1", "BlackoutCoversTheYear")
    }

    /** `SaveHealthSubject`'s name (1–60 after trimming), thresholds and weight. */
    @Test
    fun aSubjectsNameThresholdsAndWeightAreChecked() {
        for (name in listOf("  ", "n".repeat(61))) {
            assertRefused(
                data(subjects = listOf(subjectOf("h1", name = name))), "healthSubjects: subject h1", "NameRequired(limit=1..60)",
            )
        }
        val sameTwice = subjectOf("h1").copy(nominalUntilDays = 5, warningFromDays = 5, criticalFromDays = 6)
        assertRefused(data(subjects = listOf(sameTwice)), "healthSubjects: subject h1", "ThresholdsInvalid")
        val tooLong = subjectOf("h1").copy(criticalFromDays = 36_501)
        assertRefused(data(subjects = listOf(tooLong)), "healthSubjects: subject h1", "ThresholdsInvalid")
        assertRefused(data(subjects = listOf(subjectOf("h1", weight = 0))), "healthSubjects: subject h1", "WeightOutOfRange")
        assertRefused(data(subjects = listOf(subjectOf("h1", weight = 11))), "healthSubjects: subject h1", "WeightOutOfRange")
    }

    /** `RecordCondition`'s shapes: an ISO date, an `HH:MM` time, a real zone, a reason of at most 500. */
    @Test
    fun aConditionsDateTimeZoneAndReasonAreChecked() {
        val cases = listOf(
            conditionOf("c1", occurredOn = "2026-02-30") to "BadDate(field=occurredOn)",
            conditionOf("c1", occurredTime = "7:45") to "BadTime(field=occurredTime)",
            conditionOf("c1", tzId = "not a zone") to "BadTimeZone(field=tzId)",
            conditionOf("c1", reason = "r".repeat(501)) to "ReasonTooLong(limit=500)",
        )
        for ((row, problem) in cases) assertRefused(data(conditions = listOf(row)), "assetConditions: condition c1", problem)
    }

    /**
     * The controller's ruling on B06-F7: a condition's zone is judged within the archive's own contents,
     * never against the importing device's zone data. A well-formed region id that no zone data here
     * knows — one only the archive holds — restores, row for row; only a malformed id is refused.
     */
    @Test
    fun aConditionsZoneIsJudgedByTheArchiveNotThisDevice() {
        val elsewhere = conditionOf("c1", tzId = "Mars/Olympus_Mons")
        val archive = data(conditions = listOf(elsewhere))

        val restored = BackupCodec.decode(archiveOf(archive)).data.assetConditions.map { it.toDomain() }
        assertEquals(listOf(elsewhere), restored)
        assertRefused(
            data(conditions = listOf(conditionOf("c1", tzId = "UTC+99"))), "assetConditions: condition c1",
            "BadTimeZone(field=tzId)",
        )
    }

    /** An activation's date is an ISO date, as `RecordSeasonActivation` requires. */
    @Test
    fun anActivationsDateIsChecked() {
        assertRefused(
            data(activations = listOf(activationOf("act-1", occurredOn = "2026-3-1"))),
            "seasonActivations: activation act-1", "BadDate(field=occurredOn)",
        )
    }

    /**
     * Shape only: what a command allows — every bound at its edge — and what only a merge brings (a
     * subject on an archived schedule, a missing TRACK_ONE primary, PRE_SERVICE on an asset with no
     * boundary) still decode, unchanged; and the golden format-7 archive passes through the check.
     */
    @Test
    fun whatACommandOrAMergeAllowsStillDecodes() {
        val edges = data(
            assets = listOf(
                generator.copy(
                    healthAggregation = HealthAggregation.TRACK_ONE, healthPrimarySubjectId = HealthSubjectId("h-gone"),
                ),
            ),
            definitions = listOf(hours),
            schedules = listOf(
                timed("s1", ServicePolicy.IN_SERVICE_AT_START, 365),
                timed("s2", ServicePolicy.PRE_SERVICE, -365),
                timed("s3", ServicePolicy.CONTINUOUS, null, status = ScheduleStatus.ARCHIVED),
                scheduleOf(id = "s4", assetId = "a1", meterDefinitionId = "d-hours", meterInterval = 250.0,
                    servicePolicy = ServicePolicy.IN_SERVICE_AT_START, policyOffsetDays = 0),
            ),
            subjects = listOf(
                subjectOf("h1", scheduleId = "s3", name = "n".repeat(60), weight = 10)
                    .copy(nominalUntilDays = 0, warningFromDays = 1, criticalFromDays = 36_500),
                subjectOf("h2", name = " Battery age ", weight = 1),
            ),
            conditions = listOf(
                conditionOf("c1", occurredTime = null, reason = "r".repeat(500)),
                conditionOf("c2", reason = ""),
            ),
            activations = listOf(activationOf("act-1", occurredOn = "2026-02-28")),
        )
        assertEquals(edges, BackupCodec.decode(archiveOf(edges)).data)

        val golden = BackupCodec.decode(resourceBytes(GOLDEN_FORMAT_7))
        assertTrue(golden.data.maintenanceSchedules.isNotEmpty())
    }

    // --- #74 (C11): the owner's categories -----------------------------------------------------------

    /**
     * A category row is refused only when it is **malformed**: a blank display, or a key that is not
     * the key rule's own for its display — the row a promotion (C5) could never have written. The
     * duplicate key is the graph check's `uniqueIds`, which runs first, and is pinned beside them.
     */
    @Test
    fun aMalformedCategoryRowIsRefused() {
        assertRefused(
            data(categories = listOf(AssetCategoryDto("", "   ", 1L, 1L))),
            "assetCategories: category ", "has a blank display",
        )
        assertRefused(
            data(categories = listOf(AssetCategoryDto("Appliance", "Appliance", 1L, 1L))),
            "assetCategories: category Appliance", "is not keyed by its display",
        )
        assertRefused(
            data(categories = listOf(AssetCategoryDto("water  heater", "Water  heater", 1L, 1L))),
            "assetCategories: category water  heater", "is not keyed by its display",
        )
        assertRefused(
            data(
                categories = listOf(
                    AssetCategoryDto("appliance", "Appliance", 1L, 1L),
                    AssetCategoryDto("appliance", "APPLIANCE", 2L, 2L),
                ),
            ),
            "assetCategories: duplicate id appliance", "",
        )
    }

    /**
     * Ruling R74-14 (review N5): a display not in `CategoryKey.display` form — untrimmed, a run of
     * spaces, or decomposed (NFD) — is malformed too, even when its key is the key rule's own: no
     * promotion, rename or backfill writes one. It lands with format 9, because tightening a restore
     * check later would refuse archives this build once accepted.
     */
    @Test
    fun aDisplayNotInItsStoredFormIsRefused() {
        for ((key, display) in listOf(
            "appliance" to " Appliance",
            "water heater" to "Water  heater",
            "\u00e9clairage" to "E\u0301clairage",
        )) {
            assertRefused(
                data(categories = listOf(AssetCategoryDto(key, display, 1L, 1L))),
                "assetCategories: category $key", "has a display not in its stored form",
            )
        }
    }

    /**
     * The follow-ups' K4 (owner, 2026-09-26): a display that carries a removed invisible character is
     * not in `CategoryKey.display` form. Refused both when keyed by the visible text (the fourth clause)
     * and when keyed with the character, the row the rule before the follow-ups would have written.
     */
    @Test
    fun aDisplayCarryingAZeroWidthSpaceIsRefused() {
        assertRefused(
            data(categories = listOf(AssetCategoryDto("appliance", "App\u200Bliance", 1L, 1L))),
            "assetCategories: category appliance", "has a display not in its stored form",
        )
        assertRefused(
            data(categories = listOf(AssetCategoryDto("app\u200Bliance", "App\u200Bliance", 1L, 1L))),
            "assetCategories: category app\u200Bliance", "is not keyed by its display",
        )
    }

    /**
     * A row filed under a **built-in's** key is never refused: a built-in added by a later release
     * must not make an older archive unrestorable (MAJOR by `versioning.md`). The planner and the
     * replace drop it instead. A well-formed row of the owner's own decodes beside it.
     */
    @Test
    fun aBuiltInKeyedCategoryRowStillDecodes() {
        val rows = data(
            categories = listOf(
                AssetCategoryDto("appliance", "Appliance", 1L, 1L),
                AssetCategoryDto("hot tub", "Hot tub", 2L, 2L),
                AssetCategoryDto("ro system", "ro SYSTEM", 3L, 3L),
            ),
        )
        assertEquals(rows, BackupCodec.decode(archiveOf(rows)).data)
    }

    // --- #79 (C18): the warranty reminder's lead --------------------------------------------------

    /**
     * R79-12b: a lead needs a warranty date — the row `SetWarrantyReminder` refuses and every edit that
     * clears the date clears. The same lead with a date decodes.
     */
    @Test
    fun aLeadWithoutADate() {
        assertRefused(
            data(assets = listOf(generator.copy(warrantyReminderLeadDays = 30))),
            "assets: asset a1", "LeadWithoutDate",
        )
        val dated = generator.copy(warrantyExpiresOn = "2027-03-01", warrantyReminderLeadDays = 30)
        assertEquals(data(assets = listOf(dated)), BackupCodec.decode(archiveOf(data(assets = listOf(dated)))).data)
    }

    /** R79-12a: whole days, at least one, and no upper bound — zero or less is refused, any larger lead decodes. */
    @Test
    fun aLeadOfZero() {
        for (lead in listOf(0, -1)) {
            assertRefused(
                data(assets = listOf(generator.copy(warrantyExpiresOn = "2027-03-01", warrantyReminderLeadDays = lead))),
                "assets: asset a1", "LeadNotPositive",
            )
        }
        val far = data(assets = listOf(generator.copy(warrantyExpiresOn = "2027-03-01", warrantyReminderLeadDays = Int.MAX_VALUE)))
        assertEquals(far, BackupCodec.decode(archiveOf(far)).data)
    }

    // --- #79 (C18): the service case aggregate, format 12 -----------------------------------------

    private fun cased(vararg cases: ServiceCase, entries: List<ServiceCaseEntry> = emptyList()) =
        data().copy(serviceCases = cases.map { it.toDto() }, serviceCaseEntries = entries.map { it.toDto() })

    /** The header's shape — what `OpenServiceCase` and `UpdateServiceCase` refuse about the row itself. */
    @Test
    fun aCasesTitleCostCurrencyAndDateAreChecked() {
        val cases = listOf(
            caseOf("c1", title = "  ") to "TitleRequired",
            caseOf("c1", costMinor = -1) to "NegativeCost",
            caseOf("c1", currency = null) to "CostWithoutCurrency",
            caseOf("c1", currency = "eur") to "BadCurrency",
            caseOf("c1").copy(openedOn = "2026-02-30") to "BadDate(field=openedOn)",
        )
        for ((row, problem) in cases) assertRefused(cased(row), "serviceCases: case c1", problem)
        val free = cased(caseOf("c1", costMinor = 0), caseOf("c2", costMinor = null, currency = null))
        assertEquals(free, BackupCodec.decode(archiveOf(free)).data, "no charge, and no cost, both decode")
    }

    /** R79-5, R79-9: a status entry sets `closedOn` exactly when it closes or cancels, and clears it otherwise. */
    @Test
    fun closedOnIsSetExactlyWhenTheCaseIsClosedOrCancelled() {
        for (status in listOf(CaseStatus.CLOSED, CaseStatus.CANCELLED)) {
            assertRefused(cased(caseOf("c1", status = status)), "serviceCases: case c1", "is $status with no closedOn")
        }
        for (status in listOf(CaseStatus.OPEN, CaseStatus.SENT_OUT, CaseStatus.AT_SERVICE_CENTER, CaseStatus.RETURNED)) {
            assertRefused(cased(caseOf("c1", status = status, closedOn = "2026-09-24")), "serviceCases: case c1", "is $status with a closedOn")
        }
        assertRefused(
            cased(caseOf("c1", status = CaseStatus.CLOSED, closedOn = "2026-13-01")), "serviceCases: case c1", "BadDate(field=closedOn)",
        )
        val shaped = cased(
            caseOf("c1", status = CaseStatus.CLOSED, closedOn = "2026-09-24"),
            caseOf("c2", status = CaseStatus.CANCELLED, closedOn = "2026-09-23"),
            caseOf("c3", status = CaseStatus.RETURNED),
        )
        assertEquals(shaped, BackupCodec.decode(archiveOf(shaped)).data)
    }

    /** What `AddServiceCaseEntry` refuses about the row itself: neither a note nor a status, a bad date or time. */
    @Test
    fun anEmptyEntryIsRefused() {
        val cases = listOf(
            caseEntryOf("n1", note = " ", status = null) to "EntryEmpty",
            caseEntryOf("n1", occurredOn = "2026-02-30") to "BadDate(field=occurredOn)",
            caseEntryOf("n1", occurredTime = "7:45") to "BadTime(field=occurredTime)",
        )
        for ((row, problem) in cases) {
            assertRefused(cased(caseOf("c1"), entries = listOf(row)), "serviceCaseEntries: entry n1", problem)
        }
        val fine = cased(
            caseOf("c1"),
            entries = listOf(caseEntryOf("n1", note = "", status = CaseStatus.RETURNED), caseEntryOf("n2", occurredTime = null)),
        )
        assertEquals(fine, BackupCodec.decode(archiveOf(fine)).data, "a status alone, and a note with no time, decode")
    }

    /**
     * Review N4 (controller ruling), the condition rule for a restored row: an entry's zone is judged by
     * its form alone, never by this device's zone data — a well-formed region only the archive knows
     * restores; a blank or malformed id is refused, naming the command's problem.
     */
    @Test
    fun anEntrysZoneIsJudgedByItsForm() {
        val elsewhere = cased(caseOf("c1"), entries = listOf(caseEntryOf("n1").copy(tzId = "Mars/Olympus_Mons")))
        assertEquals(elsewhere, BackupCodec.decode(archiveOf(elsewhere)).data)
        for (zone in listOf("UTC+99", "", "not a zone")) {
            assertRefused(
                cased(caseOf("c1"), entries = listOf(caseEntryOf("n1").copy(tzId = zone))),
                "serviceCaseEntries: entry n1", "BadTimeZone(field=tzId)",
            )
        }
    }

    /** No rule is relative to the importing device's today: a case and an entry dated far ahead restore. */
    @Test
    fun aCaseOrEntryIsNeverJudgedByToday() {
        val ahead = cased(
            caseOf("c1").copy(openedOn = "2099-01-01"),
            entries = listOf(caseEntryOf("n1", occurredOn = "2099-01-02")),
        )
        assertEquals(ahead, BackupCodec.decode(archiveOf(ahead)).data)
    }

    // --- #72 (C6): loans, format 13 --------------------------------------------------------------

    private fun lent(vararg loans: AssetLoan) = data().copy(assetLoans = loans.map { it.toDto() })

    @Test
    fun aBlankBorrowerIsRefused() {
        assertRefused(lent(loanOf("l1", borrowerName = "  ")), "assetLoans: loan l1", "BorrowerRequired")
    }

    @Test
    fun aDueDateBeforeTheLentDateIsRefused() {
        assertRefused(lent(loanOf("l1", lentOn = "2026-09-20", dueOn = "2026-09-19")), "assetLoans: loan l1", "DueBeforeLent")
        val sameDay = lent(loanOf("l1", lentOn = "2026-09-20", dueOn = "2026-09-20"))
        assertEquals(sameDay, BackupCodec.decode(archiveOf(sameDay)).data, "due back the day it was lent")
    }

    @Test
    fun aReturnDateBeforeTheLentDateIsRefused() {
        assertRefused(
            lent(loanOf("l1", lentOn = "2026-09-20", returnedOn = "2026-09-19")), "assetLoans: loan l1", "ReturnedBeforeLent",
        )
        val sameDay = lent(loanOf("l1", lentOn = "2026-09-20", returnedOn = "2026-09-20"))
        assertEquals(sameDay, BackupCodec.decode(archiveOf(sameDay)).data, "returned the day it was lent")
    }

    /** N10: a mode without a due date is refused, as the commands refuse it — never read as None. */
    @Test
    fun aModeWithoutADueDateIsRefused() {
        for (mode in listOf(LoanReminderMode.ONCE, LoanReminderMode.UNTIL_RETURNED)) {
            assertRefused(lent(loanOf("l1", dueOn = null, reminderMode = mode)), "assetLoans: loan l1", "ReminderWithoutDueDate")
        }
        val plain = lent(loanOf("l1", dueOn = null, reminderMode = LoanReminderMode.NONE))
        assertEquals(plain, BackupCodec.decode(archiveOf(plain)).data)
    }

    /** R72-4: the stored link is shape-checked by the one rule; a name-only loan's null link restores. */
    @Test
    fun aLinkFailingTheRuleIsRefused() {
        for (link in listOf("content://com.android.contacts/contacts/7", "content://media/external/images/media/7", "")) {
            assertRefused(lent(loanOf("l1", contactLookupUri = link)), "assetLoans: loan l1", "ContactLinkInvalid")
        }
        val nameOnly = lent(loanOf("l1", contactLookupUri = null))
        assertEquals(nameOnly, BackupCodec.decode(archiveOf(nameOnly)).data)
    }

    @Test
    fun aLoansDatesMustBeDates() {
        val cases = listOf(
            loanOf("l1", lentOn = "2026-02-30") to "BadDate(field=lentOn)",
            loanOf("l1", dueOn = "4 Oct") to "BadDate(field=dueOn)",
            loanOf("l1", returnedOn = "2026-13-01") to "BadDate(field=returnedOn)",
        )
        for ((row, problem) in cases) assertRefused(lent(row), "assetLoans: loan l1", problem)
    }

    /**
     * C9's key `<assetId>/<loanId>` splits at the last `/`, so a loan id may never hold one. No writer on
     * this build mints such an id; an archive carrying one was built by hand.
     */
    @Test
    fun aLoanIdHoldingASlash() {
        val refusal = refused(lent(loanOf("l/1")))
        assertEquals("assetLoans: loan l/1 has an id holding a '/'", refusal)
    }

    /** No rule is relative to the importing device's today: a loan lent and returned far ahead restores. */
    @Test
    fun aLoanIsNeverJudgedByToday() {
        val ahead = lent(loanOf("l1", lentOn = "2099-01-01", dueOn = "2099-02-01", returnedOn = "2099-01-15"))
        assertEquals(ahead, BackupCodec.decode(archiveOf(ahead)).data)
    }
}
