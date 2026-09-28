package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.ServiceCase
import com.loosecannon.servicetag.core.model.ServiceCaseEntry
import com.loosecannon.servicetag.core.model.ServiceCaseEntryId
import com.loosecannon.servicetag.core.model.ServiceCaseId
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.ServiceCaseEntryRepository
import com.loosecannon.servicetag.core.ports.ServiceCaseRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.ports.UnitOfWork

/** What [AddServiceCaseEntry] wrote: the entry, and the case as it stands after it. */
data class CaseEntryAdded(val serviceCase: ServiceCase, val entry: ServiceCaseEntry)

/**
 * Appends one immutable entry to a case's timeline (#79, C14; R79-5, R79-8, R79-9), and is the **only**
 * writer of a case's status:
 *
 * - a **note-only** entry writes the entry and nothing else — the header, its stamp included, is left
 *   exactly as it was, so a note never makes a case re-merge as anything but IDENTICAL;
 * - a **status** entry also sets the header's status and stamp, in the same write: CLOSED or CANCELLED
 *   sets `closedOn` to the entry's date, and any other status clears it — which is how a case is
 *   reopened. Closing writes no condition, no event and no completion; CANCELLED is how a case is
 *   abandoned, since nothing deletes one.
 *
 * The entry is inserted before the header is written, inside one transaction, so a header write that
 * fails leaves no entry behind. The problems — an entry with neither note nor status, a bad date or
 * time, a zone this device does not resolve, a date after today — are collected before any write into
 * one [ServiceCaseValidation]; a missing case is [NoSuchServiceCase].
 */
class AddServiceCaseEntry(
    private val cases: ServiceCaseRepository,
    private val entries: ServiceCaseEntryRepository,
    private val uow: UnitOfWork,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val today: Today,
) {
    suspend fun run(caseId: ServiceCaseId, entry: CaseEntryCommand): CaseEntryAdded = uow.write {
        val stored = cases.get(caseId) ?: throw NoSuchServiceCase(caseId)
        val occurredOn = entry.occurredOn.trim()
        val occurredTime = entry.occurredTime.blankToNull()
        val note = entry.note.trim()
        val tzId = entry.tzId.trim()
        val problems = caseEntryProblems(occurredOn, occurredTime, tzId, note, entry.status, ::resolvesHere).toMutableList()
        val on = parseDate(occurredOn)
        if (on != null && on > today.localDate()) problems += ServiceCaseProblem.EntryAfterToday
        if (problems.isNotEmpty()) throw ServiceCaseValidation(problems)

        val now = clock.nowMillis()
        val row = ServiceCaseEntry(
            id = ServiceCaseEntryId(ids.newId()),
            caseId = caseId,
            occurredOn = occurredOn,
            occurredTime = occurredTime,
            tzId = tzId,
            note = note,
            status = entry.status,
            createdAt = now,
        )
        entries.insert(row)
        val status = entry.status ?: return@write CaseEntryAdded(stored, row)
        val header = stored.copy(
            status = status,
            closedOn = occurredOn.takeIf { status.isTerminal },
            updatedAt = now,
        )
        cases.upsert(header)
        CaseEntryAdded(header, row)
    }
}
