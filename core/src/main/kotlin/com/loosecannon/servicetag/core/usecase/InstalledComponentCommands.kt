package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.CompositionEntry
import java.time.LocalDate

/**
 * One thing wrong with an installed component command (#47, C2, C5), C2's code and status beside each. No member
 * carries a sentence. These are the shape problems [installedComponentProblems] and [compositionProblems] find;
 * the problems about other rows come with the use cases (C15).
 */
sealed interface InstalledComponentProblem {
    /** `INSTALLED_COMPONENT_NAME_REQUIRED` (422, `name`): the name is blank once trimmed. */
    data object NameRequired : InstalledComponentProblem

    /** `INSTALLED_COMPONENT_DATE_INVALID` (422, [field]): not an ISO `YYYY-MM-DD` date. */
    data class BadDate(val field: String) : InstalledComponentProblem

    /** `INSTALLED_COMPONENT_DATE_AFTER_TODAY` (422, [field]): later than the phone's today. */
    data class AfterToday(val field: String) : InstalledComponentProblem

    /** `INSTALLED_COMPONENT_REMOVED_BEFORE_INSTALLED` (422, [field]): a closing date before the install date. */
    data class RemovedBeforeInstalled(val field: String) : InstalledComponentProblem

    /** `COMPOSITION_QUANTITY_INVALID` (422, `composition`): entry [index]'s quantity is not a finite number above zero. */
    data class QuantityInvalid(val index: Int) : InstalledComponentProblem
}

/**
 * The shape of an installed component, whatever wrote it, and nothing about other rows (#47, C5) —
 * [loanProblems]' contract. The use cases ask it of the command they were sent, with their [today]; the backup
 * content check asks it of every restored row with none, so a restore refuses what a command refuses but never
 * judges a row by the importing phone's date. A null [installedOn] is a date not recorded (R47-8) and a null
 * [removedOn] a current row; neither is a problem. Every problem is collected, in field order.
 */
internal fun installedComponentProblems(
    name: String,
    installedOn: String?,
    removedOn: String?,
    today: LocalDate? = null,
): List<InstalledComponentProblem> {
    val problems = mutableListOf<InstalledComponentProblem>()
    if (name.isBlank()) problems += InstalledComponentProblem.NameRequired
    val installed = installedOn?.let { value ->
        val parsed = parseDate(value)
        if (parsed == null) {
            problems += InstalledComponentProblem.BadDate("installedOn")
        } else if (today != null && parsed > today) {
            problems += InstalledComponentProblem.AfterToday("installedOn")
        }
        parsed
    }
    if (removedOn != null) {
        val removed = parseDate(removedOn)
        if (removed == null) {
            problems += InstalledComponentProblem.BadDate("removedOn")
        } else {
            if (installed != null && removed < installed) problems += InstalledComponentProblem.RemovedBeforeInstalled("removedOn")
            if (today != null && removed > today) problems += InstalledComponentProblem.AfterToday("removedOn")
        }
    }
    return problems
}

/**
 * The shape of a composition (#47, C5): each entry's quantity is a finite number above zero, reported by the
 * entry's index in [entries]. Every problem is collected. Which SupplyItems the entries name is about other rows,
 * so it is the use cases' and the graph check's, not this.
 */
internal fun compositionProblems(entries: List<CompositionEntry>): List<InstalledComponentProblem> =
    entries.mapIndexedNotNull { index, entry ->
        InstalledComponentProblem.QuantityInvalid(index).takeIf { !entry.quantity.isFinite() || entry.quantity <= 0.0 }
    }
