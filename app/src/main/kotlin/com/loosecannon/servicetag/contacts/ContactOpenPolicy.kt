package com.loosecannon.servicetag.contacts

import com.loosecannon.servicetag.R
import com.loosecannon.servicetag.core.model.CONTACT_LOOKUP_URI
import com.loosecannon.servicetag.l10n.localized

/**
 * #72, P72-20 (RATIFIED verbatim, R72-23): what "Open contact" says when nothing on the phone takes the
 * link, or the handler refuses it. It names no URI (the shipped `NO_HANDLER_MESSAGE` rule).
 */
val NO_APP_CAN_OPEN_THIS_CONTACT: String get() = localized(R.string.contacts_no_app_can_open)

/**
 * #72 (C15; R72-4): which stored links may be launched — only one the shared `CONTACT_LOOKUP_URI`
 * rule matches, so a restored or hand-edited row can never turn "Open contact" into a launch of
 * something else. Pure: [ContactOpener] is the Android half.
 */
object ContactOpenPolicy {
    /** The link to launch, or null when the rule refuses it. */
    fun decide(uri: String?): String? = uri?.takeIf { CONTACT_LOOKUP_URI.matches(it) }
}

/**
 * The whole of "Open contact" with no Android type in it (the `LinkLauncher` split): the policy first,
 * then [launch] — fired only for a link the policy allowed — which answers whether a handler took it.
 * False is P72-20 ([contactOpenFailure]); a link the rule refuses is never launched at all.
 */
fun openContact(uri: String?, launch: (String) -> Boolean): Boolean {
    val allowed = ContactOpenPolicy.decide(uri) ?: return false
    return launch(allowed)
}

/** The sentence the section shows after [openContact]: none once it opened, P72-20 otherwise. */
fun contactOpenFailure(opened: Boolean): String? = if (opened) null else NO_APP_CAN_OPEN_THIS_CONTACT
