package com.loosecannon.servicetag.ui.transfer

import androidx.compose.runtime.Composable
import com.loosecannon.servicetag.ui.components.ServiceTagIcons
import com.loosecannon.servicetag.ui.components.StatusBadge
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme

/**
 * #77 (C19; R77-24) — P77-31 on the list row and the identity plate: the word and the `ic_handover` glyph in the
 * `seasonInactive` tone, drawn upper-case and read as written (the badge keeps its label for TalkBack), never
 * by colour alone.
 */
@Composable
fun TransferredBadge() {
    StatusBadge(
        label = TransferStrings.TRANSFERRED,
        colors = ServiceTagTheme.semanticColors.seasonInactive,
        icon = ServiceTagIcons.Handover,
    )
}
