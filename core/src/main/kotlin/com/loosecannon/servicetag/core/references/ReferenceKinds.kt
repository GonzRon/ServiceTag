package com.loosecannon.servicetag.core.references

import com.loosecannon.servicetag.core.model.ReferenceKind

/**
 * The rule that picks a [ReferenceKind], which is why it lives here and not beside the enum: the
 * kind is **inferred from the scheme and is never a picker** (spec §3.2). The byte path is the
 * opposite — there a Type control is shown, because `AttachmentKinds.inferFrom` yields only three
 * of seven values and only the owner knows a receipt from a manual.
 *
 * `NOTE_LINK` is read off [LinkLaunchPolicy.ALLOWED] rather than a second list of note apps, so
 * an allow-listed scheme can never be launchable and yet unclassifiable.
 */
object ReferenceKinds {
    fun inferFrom(scheme: String?): ReferenceKind {
        val normalised = scheme?.trim()?.lowercase().orEmpty()
        return when {
            normalised == "http" || normalised == "https" -> ReferenceKind.WEB_URL
            normalised in LinkLaunchPolicy.ALLOWED -> ReferenceKind.NOTE_LINK
            else -> ReferenceKind.OTHER
        }
    }
}
