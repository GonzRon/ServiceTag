package com.loosecannon.servicetag.ui.references

import com.loosecannon.servicetag.core.model.DocumentRole

/**
 * #91 (C23, R91-5): what "Add link" holds besides its name and description — the typed link and
 * the role picked for it — as a plain value the JVM tests drive and the sheet renders.
 *
 * Whether a link may carry a role is never decided here. Every member takes `roleOffered`, the
 * view model's answer (the kind `AddReference` will derive from the same text, asked whether it
 * takes a role, C-4), so the chips follow the live text with no second classifier: they are drawn
 * only while the text is a web link, a pick the new text cannot carry is cleared, and Save sends a
 * role only for a link that takes one. No refusal sentence is needed, so none exists (G1).
 */
internal data class AddLinkForm(val link: String = "", val role: DocumentRole? = null) {

    /** Whether the Role chips are drawn for the text as it stands. */
    fun offersRole(roleOffered: (String) -> Boolean): Boolean = roleOffered(link)

    /**
     * The new text. A pick survives only while the new text still takes a role; otherwise it is
     * cleared, so chips that come back start again with no role chosen rather than a stale pick.
     */
    fun withLink(text: String, roleOffered: (String) -> Boolean): AddLinkForm =
        AddLinkForm(link = text, role = role.takeIf { roleOffered(text) })

    /** A chip tapped: recorded only while the chips are offered. Choosing no role is a pick too. */
    fun withRole(pick: DocumentRole?, roleOffered: (String) -> Boolean): AddLinkForm =
        if (roleOffered(link)) copy(role = pick) else this

    /** The role Save sends: none for a link that cannot take one, whatever the form holds. */
    fun roleAtSave(roleOffered: (String) -> Boolean): DocumentRole? = role.takeIf { roleOffered(link) }
}
