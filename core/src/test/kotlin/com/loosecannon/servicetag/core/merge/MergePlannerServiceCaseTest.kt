package com.loosecannon.servicetag.core.merge

import com.loosecannon.servicetag.core.backup.BackupData
import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.CaseCoverage
import com.loosecannon.servicetag.core.model.CaseStatus
import com.loosecannon.servicetag.core.model.CaseType
import com.loosecannon.servicetag.core.model.ServiceCase
import com.loosecannon.servicetag.core.model.ServiceCaseEntry
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.testing.BackupInstall
import com.loosecannon.servicetag.core.testing.backupOf
import com.loosecannon.servicetag.core.testing.caseEntryOf
import com.loosecannon.servicetag.core.testing.caseOf
import com.loosecannon.servicetag.core.testing.plainAssetOf
import com.loosecannon.servicetag.core.usecase.AddServiceCaseEntry
import com.loosecannon.servicetag.core.usecase.CaseEntryCommand
import com.loosecannon.servicetag.core.usecase.OpenServiceCase
import com.loosecannon.servicetag.core.usecase.ServiceCaseCommand
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * #79 (C19; R79-1, R79-5, R79-8): a service case is an **aggregate root** whose timeline is append-only.
 *
 * - A case: identity is its id. Here with the same header → IDENTICAL; here with any difference →
 *   CONFLICT `CONTENT_DIFFERS`; its asset neither here nor inserted by this plan → CONFLICT
 *   `OWNER_NOT_AVAILABLE`; else INSERT. There is no UPDATE, and its Incident and repair links are soft:
 *   an event that exists nowhere is never an owner.
 * - An entry: the conditions precedent, owned by its case. Here → IDENTICAL or CONFLICT; its case here
 *   or accepted by this plan → INSERT; else `OWNER_NOT_AVAILABLE`. Nothing is updated or deleted.
 *
 * So a note added on another phone re-merges as one entry INSERT beside an IDENTICAL case, while a status
 * moved there — which stamps the header — conflicts (plan §5). The names are fictional.
 */
class MergePlannerServiceCaseTest {

    private val heater = plainAssetOf("a1", "Example Heater")

    private fun data(
        assets: List<Asset> = listOf(heater),
        cases: List<ServiceCase> = emptyList(),
        entries: List<ServiceCaseEntry> = emptyList(),
    ) = BackupData(
        assets = assets.map { it.toDto() }, nfcTags = emptyList(), externalLinks = emptyList(),
        serviceCases = cases.map { it.toDto() }, serviceCaseEntries = entries.map { it.toDto() },
    )

    private fun planOf(
        incoming: BackupData,
        assets: List<Asset> = listOf(heater),
        cases: List<ServiceCase> = emptyList(),
        entries: List<ServiceCaseEntry> = emptyList(),
    ): MergePlan = mergePlanOf(
        backupOf(incoming),
        MergeSnapshot(assets = assets, serviceCases = cases, caseEntries = entries, attachmentStoreConfigured = true),
    )

    private fun MergePlan.of(table: MergeTable) = decisions.filter { it.table == table }

    private fun insert(table: MergeTable, id: String) = MergeDecision(table, id, MergeVerdict.INSERT)
    private fun identical(table: MergeTable, id: String) = MergeDecision(table, id, MergeVerdict.IDENTICAL)
    private fun differs(table: MergeTable, id: String) =
        MergeDecision(table, id, MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, id)
    private fun noOwner(table: MergeTable, id: String, owner: String) =
        MergeDecision(table, id, MergeVerdict.CONFLICT, MergeReason.OWNER_NOT_AVAILABLE, owner)

    // --- the case: four verdicts ----------------------------------------------------------------

    @Test
    fun theFourCaseVerdicts() {
        val here = caseOf("c1")

        assertEquals(listOf(identical(MergeTable.SERVICE_CASES, "c1")), planOf(data(cases = listOf(here)), cases = listOf(here)).of(MergeTable.SERVICE_CASES))
        for (other in listOf(here.copy(title = "Example Heater claim, again"), here.copy(status = CaseStatus.RETURNED, updatedAt = here.updatedAt + 1))) {
            assertEquals(
                listOf(differs(MergeTable.SERVICE_CASES, "c1")),
                planOf(data(cases = listOf(other)), cases = listOf(here)).of(MergeTable.SERVICE_CASES),
                "no UPDATE: a header that differs in anything is the owner's to resolve",
            )
        }
        val orphan = caseOf("c2", assetId = "a9")
        assertEquals(
            listOf(noOwner(MergeTable.SERVICE_CASES, "c2", "a9")),
            planOf(data(cases = listOf(orphan))).of(MergeTable.SERVICE_CASES),
        )
        val fresh = planOf(data(cases = listOf(caseOf("c3"))))
        assertEquals(listOf(insert(MergeTable.SERVICE_CASES, "c3")), fresh.of(MergeTable.SERVICE_CASES))
        assertEquals(listOf(caseOf("c3")), fresh.writes.serviceCases, "the header as the archive has it")
        assertEquals(listOf(MergeVerdict.INSERT, MergeVerdict.IDENTICAL, MergeVerdict.CONFLICT, MergeVerdict.SKIPPED), MergeVerdict.entries)
    }

    /** A case's asset inserted by this same plan owns it; one refused by this plan does not. */
    @Test
    fun aCaseWhoseAssetArrivesInThePlanInserts() {
        val boiler = plainAssetOf("a2", "Example Boiler")
        val plan = planOf(data(assets = listOf(heater, boiler), cases = listOf(caseOf("c1", assetId = "a2"))), assets = listOf(heater))
        assertEquals(listOf(insert(MergeTable.SERVICE_CASES, "c1")), plan.of(MergeTable.SERVICE_CASES))

        // The boiler's own parent is nowhere, so the plan refuses the boiler and its case has no owner.
        val refused = planOf(
            data(assets = listOf(boiler.copy(parentAssetId = AssetId("a9"))), cases = listOf(caseOf("c1", assetId = "a2"))),
            assets = listOf(heater),
        )
        assertEquals(listOf(noOwner(MergeTable.SERVICE_CASES, "c1", "a2")), refused.of(MergeTable.SERVICE_CASES))
    }

    /** R79-4: an Incident or repair that exists on neither side is a readable dangling link, never an owner. */
    @Test
    fun aCaseNamingEventsThatExistNowhereStillInserts() {
        val dangling = caseOf("c1", incident = "e-incident-gone", resolution = "e-repair-gone")
        val plan = planOf(data(cases = listOf(dangling)))
        assertTrue(plan.applicable)
        assertEquals(listOf(dangling), plan.writes.serviceCases)
    }

    // --- the entries: append-only ---------------------------------------------------------------

    @Test
    fun anEntryIsIdenticalAConflictOrAnInsert() {
        val case = caseOf("c1")
        val here = caseEntryOf("n1")
        val plan = planOf(
            data(cases = listOf(case), entries = listOf(here, caseEntryOf("n2", note = "Courier collected"))),
            cases = listOf(case), entries = listOf(here),
        )
        assertEquals(
            listOf(identical(MergeTable.CASE_ENTRIES, "n1"), insert(MergeTable.CASE_ENTRIES, "n2")),
            plan.of(MergeTable.CASE_ENTRIES),
        )
        assertEquals(listOf(caseEntryOf("n2", note = "Courier collected")), plan.writes.caseEntries)

        val rewritten = planOf(
            data(cases = listOf(case), entries = listOf(here.copy(note = "Courier booked for Tuesday"))),
            cases = listOf(case), entries = listOf(here),
        )
        assertEquals(listOf(differs(MergeTable.CASE_ENTRIES, "n1")), rewritten.of(MergeTable.CASE_ENTRIES), "an entry is never amended")
    }

    @Test
    fun anEntryWhoseCaseIsAcceptedInThisPlanInserts() {
        val plan = planOf(data(cases = listOf(caseOf("c1")), entries = listOf(caseEntryOf("n1"), caseEntryOf("n2", status = CaseStatus.CLOSED))))

        assertTrue(plan.applicable)
        assertEquals(listOf(insert(MergeTable.CASE_ENTRIES, "n1"), insert(MergeTable.CASE_ENTRIES, "n2")), plan.of(MergeTable.CASE_ENTRIES))
        assertEquals(listOf("n1", "n2"), plan.writes.caseEntries.map { it.id.value })
    }

    @Test
    fun anEntryWhoseCaseIsNeitherHereNorAcceptedIsOwnerNotAvailable() {
        val nowhere = planOf(data(entries = listOf(caseEntryOf("n1", caseId = "c9"))))
        assertEquals(listOf(noOwner(MergeTable.CASE_ENTRIES, "n1", "c9")), nowhere.of(MergeTable.CASE_ENTRIES))

        // The case is in the archive but refused — its asset is nowhere — so its entry has no owner either.
        val refused = planOf(data(cases = listOf(caseOf("c1", assetId = "a9")), entries = listOf(caseEntryOf("n1"))))
        assertEquals(listOf(noOwner(MergeTable.CASE_ENTRIES, "n1", "c1")), refused.of(MergeTable.CASE_ENTRIES))
        assertEquals(MergeWrites(), refused.writes, "no partial merge")
    }

    // --- two phones, through the real writers ---------------------------------------------------

    /** A phone that took a case by merge, and the phone it came from adding to its timeline afterwards. */
    private class TwoPhones {
        val source = BackupInstall(setId = "set-source")
        val target = BackupInstall(setId = "set-target")
        var now = 1_758_600_000_000L
        private var n = 0
        private val clock = Clock { now }
        private val ids = IdGenerator { "id-%03d".format(++n) }
        private val today = Today { LocalDate.parse("2026-09-24") }
        val open = OpenServiceCase(source.assets, source.events, source.serviceCases, source.uow, ids, clock, today)
        val add = AddServiceCaseEntry(source.serviceCases, source.caseEntries, source.uow, ids, clock, today)

        fun opened(): ServiceCase = runBlocking {
            source.assets.upsert(plainAssetOf("a1", "Example Heater"))
            val case = open.run(
                AssetId("a1"),
                ServiceCaseCommand("Example Heater claim", CaseType.REPAIR, "2026-09-20", CaseCoverage.OUT_OF_WARRANTY, null),
                null,
            )
            add.run(case.id, CaseEntryCommand("2026-09-21", null, "UTC", "Called the provider", null))
            val bytes = source.export.run().data
            target.apply.run(target.build.run(bytes))
            case
        }

        fun replan(): MergePlan = runBlocking { target.build.run(source.export.run().data) }
    }

    @Test
    fun aNoteAddedElsewhereMergesAsEntryInsertWithTheHeaderIdentical() = runBlocking<Unit> {
        val phones = TwoPhones()
        val case = phones.opened()
        phones.now += 60_000L
        phones.add.run(case.id, CaseEntryCommand("2026-09-24", "10:15", "UTC", "Provider says parts on order", null))

        val plan = phones.replan()

        assertTrue(plan.applicable, plan.conflicts.toString())
        assertEquals(listOf(identical(MergeTable.SERVICE_CASES, case.id.value)), plan.of(MergeTable.SERVICE_CASES))
        assertEquals(listOf(MergeVerdict.IDENTICAL, MergeVerdict.INSERT), plan.of(MergeTable.CASE_ENTRIES).map { it.verdict })
        assertEquals(MergeTally(1, 1, 0, 0), plan.tally(MergeTable.CASE_ENTRIES))
        phones.target.apply.run(plan)
        assertEquals(2, phones.target.caseEntries.all().size)
        assertEquals(phones.source.serviceCases.all(), phones.target.serviceCases.all(), "the header came across once and was never rewritten")
    }

    /** Plan §5: a status moved on the other phone stamps the header there, and there is no UPDATE to adopt it. */
    @Test
    fun aStatusMovedElsewhereConflictsOnReMerge() = runBlocking<Unit> {
        val phones = TwoPhones()
        val case = phones.opened()
        phones.now += 60_000L
        phones.add.run(case.id, CaseEntryCommand("2026-09-24", null, "UTC", "Sent to the service centre", CaseStatus.SENT_OUT))

        val plan = phones.replan()

        assertEquals(listOf(differs(MergeTable.SERVICE_CASES, case.id.value)), plan.of(MergeTable.SERVICE_CASES))
        assertEquals(MergeWrites(), plan.writes)
    }

    @Test
    fun theReportTalliesBothTables() {
        val report = planOf(
            data(cases = listOf(caseOf("c1"), caseOf("c2")), entries = listOf(caseEntryOf("n1"), caseEntryOf("n2"), caseEntryOf("n3", caseId = "c2"))),
            cases = listOf(caseOf("c1")),
        ).report()

        assertEquals(MergeTally(insert = 1, identical = 1, conflict = 0, skipped = 0), report.serviceCases)
        assertEquals(MergeTally(insert = 3, identical = 0, conflict = 0, skipped = 0), report.caseEntries)
    }
}
