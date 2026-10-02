package com.loosecannon.servicetag.core.backup

import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.model.SuccessionProblem
import com.loosecannon.servicetag.core.model.successionProblems
import com.loosecannon.servicetag.core.usecase.isIsoDate
import com.loosecannon.servicetag.core.transfer.TransferPack
import com.loosecannon.servicetag.core.journal.CategoryKey
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.usecase.BreakCommand
import com.loosecannon.servicetag.core.usecase.KEY_PATTERN
import com.loosecannon.servicetag.core.usecase.SeasonProblem
import com.loosecannon.servicetag.core.usecase.ServiceCaseProblem
import com.loosecannon.servicetag.core.usecase.breakProblems
import com.loosecannon.servicetag.core.usecase.caseEntryProblems
import com.loosecannon.servicetag.core.usecase.compositionProblems
import com.loosecannon.servicetag.core.usecase.conditionFactProblems
import com.loosecannon.servicetag.core.usecase.installedComponentProblems
import com.loosecannon.servicetag.core.usecase.loanProblems
import com.loosecannon.servicetag.core.usecase.parseDate
import com.loosecannon.servicetag.core.usecase.policyProblems
import com.loosecannon.servicetag.core.usecase.serviceCaseHeaderProblems
import com.loosecannon.servicetag.core.usecase.subjectNameProblem
import com.loosecannon.servicetag.core.usecase.thresholdsProblem
import com.loosecannon.servicetag.core.usecase.warrantyReminderProblems
import com.loosecannon.servicetag.core.usecase.weightProblem
import com.loosecannon.servicetag.core.usecase.wellFormedZone

/**
 * **The format-8 content check**: one pass over a decoded archive, after the strict decode and the
 * graph check, that refuses a row a command would refuse — asking the command's own rule, so the
 * refusal names the same problem (the controller's ruling on B03's concern 3; master plan §5). A
 * restore therefore never lands what `SaveSchedule`, `SetMaintenanceBreak`, `SaveHealthSubject` or
 * `RecordCondition` would refuse about the row itself:
 *
 * - a schedule's service policy: the offset's range, PRE_SERVICE or an offset on a meter-only
 *   schedule, a non-CONTINUOUS policy on a group target — B04's `policyProblems`, and nothing of its own;
 * - an asset's break covering every day of some year (`breakProblems`; its shape is the graph check's);
 * - an asset's warranty reminder lead (#79, C18) under one day, or without a warranty date — the rule
 *   `SetWarrantyReminder` asks (`warrantyReminderProblems`);
 * - a health subject's name, thresholds and weight;
 * - a condition's date, time, zone and reason — the zone by its form alone, never by this device's zone
 *   data (the controller's ruling on B06-F7); an activation's date.
 *
 * - a category (#74, C11) that is **malformed**: a blank display, a key that is not
 *   `CategoryKey.of(display)`, or (ruling R74-14) a display not in `CategoryKey.display` form —
 *   untrimmed, a run of whitespace, or not NFC — the row a promotion, a rename or the backfill could
 *   never have written. It lands with format 9 itself, because tightening a restore check later would
 *   refuse archives an earlier build accepted. Nothing else about a
 *   category is refused: a row filed under a **built-in's** key decodes, because a built-in added by a
 *   later release must never make an older archive unrestorable, and the replace and the merge
 *   planner drop it. (A duplicate key is the graph check's `uniqueIds`, which runs first.)
 *
 * - a service case (#79, C18) whose header a case command would refuse — a blank title, a malformed
 *   `openedOn`, a negative cost, a cost with no currency, a malformed currency
 *   (`serviceCaseHeaderProblems`, asked with no today) — or whose `closedOn` is not set exactly when it
 *   is CLOSED or CANCELLED, which only a status entry moves; and a case entry with neither a note nor a
 *   status, a malformed date or time, or a malformed zone — by its form alone, as a condition's
 *   (`caseEntryProblems`).
 *
 * - a loan (#72, C6) that a loan command would refuse about the row itself — a blank borrower, a lent,
 *   due or return date that is not a date, a due date or a return date before the lent date, a reminder
 *   mode with no due date, a contact link failing `CONTACT_LOOKUP_URI` (`loanProblems`, asked with no
 *   today) — and a loan id holding a `/`, which the reminder key `<assetId>/<loanId>` could not split.
 *   Two open loans for one asset is a rule about other rows, and is the graph check's.
 *
 * - a transfer record (#77, C7) with a blank id, asset id, pack id or name snapshot, a pack sha256 that is
 *   not 64 lowercase hex, an `at` not after the epoch or a note breaking the pack note's rule — and, as the
 *   plan places it here, a WITHDRAWN with no OUT of its asset and pack in the file. An asset held by the
 *   archive's own records is the graph check's.
 *
 * - a succession (#86, C3) with a blank id, one asset at both ends (I1), a `replacedOn` that is not an ISO date, or a
 *   `createdAt` not after the epoch. A missing end, a taken end and a cycle are the graph check's.
 *
 * - a SupplyItem (#15, C9) with a blank name, or a specification with a blank label or value or a key outside the
 *   slug rule or taken within its SupplyItem; an applicability row with a blank or uncleaned role, or holding the
 *   `(asset, SupplyItem, role)` another row holds. A missing target is the graph check's.
 *
 * - an installed component (#47, C10) that its commands would refuse about the row itself — a blank name, an
 *   install or removal date that is not ISO, a removal before the install (`installedComponentProblems`, asked with
 *   no today) — or a composition entry whose quantity is not a finite number above zero, named by its index
 *   (`compositionProblems`). A missing or misplaced parent, SupplyItem or replaced row is the graph check's.
 *
 * What depends on **other rows or on today** is deliberately not asked: a subject naming an archived
 * or retargeted schedule (NOT TRACKED, which a merge may bring — plan decision 17), a TRACK_ONE
 * primary that is gone (S138's fallback), a PRE_SERVICE schedule on a boundary-less asset, two subjects
 * on one schedule (the merge planner's own reason), a fact, a case or an entry dated after the importing
 * device's today, a case's Incident or repair link (soft, R79-4), a loan lent or returned after the
 * importing device's today, or an installed component fitted or removed after it.
 *
 * Every refusal is [BackupCorrupt] naming the table, the row and the problem.
 */
internal object BackupContentCheck {

    fun check(data: BackupData) {
        data.maintenanceSchedules.forEach { dto ->
            val schedule = dto.toDomain()
            refuse(
                "maintenanceSchedules", "schedule", schedule.id.value,
                policyProblems(
                    schedule.servicePolicy,
                    schedule.policyOffsetDays,
                    hasTimeRule = schedule.timeInterval != null,
                    groupTarget = schedule.target is ScheduleTarget.GroupTarget,
                ),
            )
        }
        data.assets.forEach { dto ->
            val asset = dto.toDomain()
            refuse(
                "assets", "asset", asset.id.value,
                breakProblems(BreakCommand(asset.blackoutStartMmdd, asset.blackoutEndMmdd)) +
                    warrantyReminderProblems(asset.warrantyReminderLeadDays, asset.warrantyExpiresOn),
            )
        }
        data.healthSubjects.forEach { dto ->
            val subject = dto.toDomain()
            refuse(
                "healthSubjects", "subject", subject.id.value,
                listOfNotNull(
                    subjectNameProblem(subject.name.trim()),
                    thresholdsProblem(subject.nominalUntilDays, subject.warningFromDays, subject.criticalFromDays),
                    weightProblem(subject.weight),
                ),
            )
        }
        data.assetConditions.forEach { dto ->
            val row = dto.toDomain()
            refuse(
                "assetConditions", "condition", row.id,
                conditionFactProblems(
                    row.occurredOn, row.occurredTime, row.tzId, row.reason, zone = ::wellFormedZone,
                ),
            )
        }
        data.seasonActivations.forEach { dto ->
            val row = dto.toDomain()
            refuse(
                "seasonActivations", "activation", row.id,
                listOfNotNull(SeasonProblem.BadDate("occurredOn").takeIf { parseDate(row.occurredOn) == null }),
            )
        }
        checkCategories(data)
        checkServiceCases(data)
        checkLoans(data)
        checkTransferRecords(data)
        checkSuccessions(data)
        checkSupplies(data)
        checkInstalledComponents(data)
    }

    /**
     * #47 (C10): each installed component by the shape its commands ask — [installedComponentProblems] with no today,
     * so a row is never judged by the importing phone's date — and its composition by [compositionProblems], whose
     * problem names the entry by its index; both through [refuse], which names the list and the row.
     */
    private fun checkInstalledComponents(data: BackupData) {
        data.installedComponents.forEach { dto ->
            val row = dto.toDomain()
            refuse(
                "installedComponents", "installed component", row.id.value,
                installedComponentProblems(row.name, row.installedOn, row.removedOn) + compositionProblems(row.composition),
            )
        }
    }

    /**
     * #15 (C9): a SupplyItem with a blank name; a specification with a blank label or value, a key outside the
     * definition slug rule (`KEY_PATTERN`, R15-11) or one another specification of the same SupplyItem holds; an
     * applicability row whose role is blank or not in `CategoryKey.display` form (R15-3); and two rows holding one
     * `(asset, SupplyItem, role)` — what the schema's unique index would refuse only after a replace had wiped the
     * owner's data. A missing asset, SupplyItem or link target is the graph check's.
     */
    private fun checkSupplies(data: BackupData) {
        data.supplyItems.forEach { item ->
            fun refuse(problem: String): Nothing = throw BackupCorrupt("supplyItems: supply item ${item.id} $problem")
            if (item.name.isBlank()) refuse("has a blank name")
            val keyHolders = HashMap<String, String>()
            item.specifications.forEach { spec ->
                if (spec.label.isBlank()) refuse("specification ${spec.id} has a blank label")
                if (spec.value.isBlank()) refuse("specification ${spec.id} has a blank value")
                if (!KEY_PATTERN.matches(spec.key)) refuse("specification ${spec.id} has a key \"${spec.key}\" outside the key rule")
                keyHolders.put(spec.key, spec.id)?.let { first ->
                    refuse("specifications $first and ${spec.id} share the key \"${spec.key}\"")
                }
            }
        }
        val tripleHolders = HashMap<Triple<String, String, String>, String>()
        data.assetSupplies.forEach { row ->
            if (row.role.isBlank()) throw BackupCorrupt("assetSupplies: row ${row.id} has a blank role")
            if (row.role != CategoryKey.display(row.role)) {
                throw BackupCorrupt("assetSupplies: row ${row.id} has a role not in its stored form")
            }
            tripleHolders.put(Triple(row.assetId, row.supplyId, row.role), row.id)?.let { first ->
                throw BackupCorrupt("assetSupplies: rows $first and ${row.id} name the same asset, supply item and role")
            }
        }
    }

    /** #86 (C3): each succession by its form, one row at a time; the rules about other rows are the graph check's. */
    private fun checkSuccessions(data: BackupData) {
        data.assetSuccessions.forEach { row ->
            if (row.id.isBlank()) throw BackupCorrupt("assetSuccessions: a succession has a blank id")
            fun refuse(problem: String): Nothing = throw BackupCorrupt("assetSuccessions: succession ${row.id} $problem")
            if (successionProblems(listOf(row.toDomain())).any { it is SuccessionProblem.SelfLink }) {
                refuse("names asset ${row.predecessorAssetId} at both ends")
            }
            if (!isIsoDate(row.replacedOn)) refuse("has a replacedOn \"${row.replacedOn}\" that is not an ISO date")
            if (row.createdAt <= 0) refuse("has createdAt ${row.createdAt}, not after the epoch")
        }
    }

    /**
     * #77 (C7): each transfer record by its form — a blank id, asset id, pack id or name snapshot; a pack
     * sha256 that is not 64 lowercase hex; an `at` not after the epoch; a note over 200 characters or not one
     * line (the pack note's rule) — and, the one rule about another row, a WITHDRAWN whose pack no OUT **of the
     * same asset** in the file names: a withdrawal is only ever appended beside the OUT it withdraws, and an
     * ordinary backup carries every record, so the pair always travels together.
     */
    private fun checkTransferRecords(data: BackupData) {
        val outs = data.transferRecords.filter { it.kind == TransferKind.OUT.name }.map { it.assetId to it.packId }.toSet()
        data.transferRecords.forEach { dto ->
            val record = dto.toDomain()
            val id = record.id
            if (id.isBlank()) throw BackupCorrupt("transferRecords: a record has a blank id")
            fun refuse(problem: String): Nothing = throw BackupCorrupt("transferRecords: record $id $problem")
            if (record.assetId.value.isBlank()) refuse("has a blank assetId")
            if (record.packId.isBlank()) refuse("has a blank packId")
            if (record.nameSnapshot.isBlank()) refuse("has a blank nameSnapshot")
            if (!SHA256_LOWER_HEX.matches(record.packSha256)) refuse("has a packSha256 that is not 64 lowercase hex")
            if (record.at <= 0) refuse("has at ${record.at}, not after the epoch")
            if (!TransferPack.noteAccepted(record.note)) refuse("has a note over 200 characters or not one line")
            if (record.kind == TransferKind.WITHDRAWN && (dto.assetId to dto.packId) !in outs) {
                refuse("withdraws pack ${record.packId}, which no OUT of asset ${record.assetId.value} in the archive names")
            }
        }
    }

    /** #72 (C6): each loan by the rules the loan commands ask, with no today, one row at a time. */
    private fun checkLoans(data: BackupData) {
        data.assetLoans.forEach { dto ->
            val loan = dto.toDomain()
            val id = loan.id.value
            if ('/' in id) throw BackupCorrupt("assetLoans: loan $id has an id holding a '/'")
            refuse(
                "assetLoans", "loan", id,
                loanProblems(
                    loan.borrowerName, loan.contactLookupUri, loan.lentOn, loan.dueOn, loan.reminderMode, loan.returnedOn,
                ),
            )
        }
    }

    /**
     * #79 (C18): a case header and a timeline entry, by the rules the case commands ask, and the one
     * rule only a status entry keeps — `closedOn` set exactly when the case is CLOSED or CANCELLED.
     */
    private fun checkServiceCases(data: BackupData) {
        data.serviceCases.forEach { dto ->
            val case = dto.toDomain()
            val id = case.id.value
            refuse(
                "serviceCases", "case", id,
                serviceCaseHeaderProblems(case.title, case.openedOn, case.costMinor, case.currency) +
                    listOfNotNull(
                        ServiceCaseProblem.BadDate("closedOn").takeIf { case.closedOn != null && parseDate(case.closedOn) == null },
                    ),
            )
            if (case.status.isTerminal && case.closedOn == null) {
                throw BackupCorrupt("serviceCases: case $id is ${case.status} with no closedOn")
            }
            if (!case.status.isTerminal && case.closedOn != null) {
                throw BackupCorrupt("serviceCases: case $id is ${case.status} with a closedOn")
            }
        }
        data.serviceCaseEntries.forEach { dto ->
            val entry = dto.toDomain()
            refuse(
                "serviceCaseEntries", "entry", entry.id.value,
                caseEntryProblems(
                    entry.occurredOn, entry.occurredTime, entry.tzId, entry.note, entry.status, zone = ::wellFormedZone,
                ),
            )
        }
    }

    /**
     * A promotion writes `key = CategoryKey.of(display)` with `display` already in
     * `CategoryKey.display` form, and never a blank display (C2, C5; R74-14).
     */
    private fun checkCategories(data: BackupData) {
        data.assetCategories.forEach { row ->
            if (row.display.isBlank()) {
                throw BackupCorrupt("assetCategories: category ${row.key} has a blank display")
            }
            val expected = CategoryKey.of(row.display)
            if (row.key != expected) {
                throw BackupCorrupt(
                    "assetCategories: category ${row.key} is not keyed by its display (its key is \"$expected\")",
                )
            }
            if (row.display != CategoryKey.display(row.display)) {
                throw BackupCorrupt("assetCategories: category ${row.key} has a display not in its stored form")
            }
        }
    }

    /** A transfer record's pack sha256: exactly 64 lowercase hex characters. */
    private val SHA256_LOWER_HEX = Regex("^[0-9a-f]{64}$")

    private fun refuse(table: String, noun: String, id: String, problems: List<Any>) {
        if (problems.isNotEmpty()) {
            throw BackupCorrupt("$table: $noun $id is refused as its command refuses it: ${problems.joinToString()}")
        }
    }
}
