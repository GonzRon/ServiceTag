package com.loosecannon.servicetag.ui.loan

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.loosecannon.servicetag.core.model.LoanStanding
import com.loosecannon.servicetag.ui.components.ServiceTagIcons
import com.loosecannon.servicetag.ui.components.StatusBadge
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme

/** P72-1 while the loan is out, P72-2 after its due day. */
fun loanBadgeLabel(standing: LoanStanding): String = when (standing) {
    LoanStanding.LENT_OUT -> LENT_OUT
    LoanStanding.OVERDUE -> LOAN_OVERDUE
}

/**
 * #72 (C16, C19, C20; R72-22, R72-23): the one loan badge the plate, the open block, the Assets row
 * and the Dashboard row draw — the word (`StatusBadge` draws it upper-case and describes the ratified
 * mixed-case literal to TalkBack) beside the loan glyph, in the neutral tone the archived badge uses.
 * "Loan overdue" takes the same tone: never maintenance OVERDUE's or DEGRADED's, and never colour
 * alone.
 */
@Composable
fun LoanBadge(standing: LoanStanding, modifier: Modifier = Modifier) {
    StatusBadge(
        label = loanBadgeLabel(standing),
        colors = ServiceTagTheme.semanticColors.seasonInactive,
        icon = ServiceTagIcons.Outbox,
        modifier = modifier,
    )
}
