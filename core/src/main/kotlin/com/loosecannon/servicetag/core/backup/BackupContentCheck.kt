package com.loosecannon.servicetag.core.backup

import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.usecase.BreakCommand
import com.loosecannon.servicetag.core.usecase.SeasonProblem
import com.loosecannon.servicetag.core.usecase.breakProblems
import com.loosecannon.servicetag.core.usecase.conditionFactProblems
import com.loosecannon.servicetag.core.usecase.parseDate
import com.loosecannon.servicetag.core.usecase.policyProblems
import com.loosecannon.servicetag.core.usecase.subjectNameProblem
import com.loosecannon.servicetag.core.usecase.thresholdsProblem
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
 * - a health subject's name, thresholds and weight;
 * - a condition's date, time, zone and reason — the zone by its form alone, never by this device's zone
 *   data (the controller's ruling on B06-F7); an activation's date.
 *
 * What depends on **other rows or on today** is deliberately not asked: a subject naming an archived
 * or retargeted schedule (NOT TRACKED, which a merge may bring — plan decision 17), a TRACK_ONE
 * primary that is gone (S138's fallback), a PRE_SERVICE schedule on a boundary-less asset, two subjects
 * on one schedule (the merge planner's own reason), or a fact dated after the importing device's today.
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
                breakProblems(BreakCommand(asset.blackoutStartMmdd, asset.blackoutEndMmdd)),
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
    }

    private fun refuse(table: String, noun: String, id: String, problems: List<Any>) {
        if (problems.isNotEmpty()) {
            throw BackupCorrupt("$table: $noun $id is refused as its command refuses it: ${problems.joinToString()}")
        }
    }
}
