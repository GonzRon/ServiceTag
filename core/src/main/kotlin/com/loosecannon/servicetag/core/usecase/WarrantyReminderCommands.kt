package com.loosecannon.servicetag.core.usecase

/**
 * The warranty reminder's one editable fact (#79, C2; R79-11, R79-12): how many whole days before
 * the asset's warranty date to warn, or null for no reminder. It is deliberately **not** a field of
 * [AssetCommand]: a full-replace edit of the asset form that omitted it would otherwise clear it.
 */
data class WarrantyReminderCommand(val leadDays: Int?)

/** One thing wrong with a [WarrantyReminderCommand]. */
sealed interface WarrantyReminderProblem {
    /** A lead under one day. Whole days, at least one, with no upper bound (R79-12a). */
    data object LeadNotPositive : WarrantyReminderProblem

    /** A lead with no warranty date to count back from (R79-12b). */
    data object LeadWithoutDate : WarrantyReminderProblem
}

/** Every problem at once, thrown once, as the other command families do. */
class WarrantyReminderValidation(val problems: List<WarrantyReminderProblem>) :
    IllegalArgumentException("warranty reminder rejected: ${problems.joinToString()}")

/**
 * The one rule, asked by [SetWarrantyReminder], by [SaveAssetSettings] against its own command's date,
 * and by the restore's content check: no lead is always fine; a lead must be at least one day and needs
 * a warranty date.
 */
internal fun warrantyReminderProblems(leadDays: Int?, warrantyExpiresOn: String?): List<WarrantyReminderProblem> =
    if (leadDays == null) {
        emptyList()
    } else {
        listOfNotNull(
            WarrantyReminderProblem.LeadNotPositive.takeIf { leadDays < 1 },
            WarrantyReminderProblem.LeadWithoutDate.takeIf { warrantyExpiresOn.isNullOrBlank() },
        )
    }
