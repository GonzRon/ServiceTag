package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.SupplyId

/** One ordered field of a profile, as the editor lists it; position is the row's sort order. */
data class ProfileFieldInput(val definitionId: DefinitionId, val required: Boolean)

/**
 * One consumable suggestion. [id] is the stored [com.loosecannon.servicetag.core.model.ProfileConsumable]'s
 * id when the editor is handing back a row it loaded, and null for a row a person just added —
 * that is how a rename keeps its identity instead of becoming a delete plus an insert.
 *
 * #15 (C19, C20): [supplyId] is the SupplyItem the line names, or null for an unlinked line. It has no default, so
 * every writer says which; a request or an editor that leaves a link out sends an unlinked line, because the save
 * is a full replace (limit 1). [name] and [unit] stay the line's own readable snapshot, never filled from the
 * SupplyItem. A quick action's line is where a replace-on-cadence item keeps its SupplyItem identity.
 */
data class ProfileConsumableInput(
    val id: String?,
    val name: String,
    val defaultQuantity: Double?,
    val unit: String,
    val supplyId: SupplyId?,
)

/** The profile form's raw input. A blank [defaultTitle] falls back to the profile's name. */
data class ProfileCommand(
    val assetId: AssetId,
    val name: String,
    val eventKind: EventKind,
    val defaultTitle: String,
    val fields: List<ProfileFieldInput>,
    val consumables: List<ProfileConsumableInput>,
)

/** One thing wrong with a [ProfileCommand], reported so a form can mark the right row. */
sealed interface ProfileProblem {
    data object NameRequired : ProfileProblem
    data object NameTaken : ProfileProblem

    /**
     * The field's definition can't be offered on this profile, for [cause]. A form says it in the owner's language
     * (#102); [reason] is the same cause as log text, which the Developer API's `problems` carries verbatim.
     */
    data class BadField(val id: DefinitionId, val cause: BadFieldCause) : ProfileProblem {
        val reason: String get() = cause.logText

        override fun toString(): String = "BadField(id=$id, reason=$reason)"
    }
    data class BadConsumable(val index: Int) : ProfileProblem

    /**
     * #15 (C20): the line at [index] names a SupplyItem this phone does not hold. An archived one still resolves
     * (R15-6), and nothing deletes a SupplyItem (R15-5), so only a race or a hand-made request lands here.
     */
    data class UnknownSupplyItem(val index: Int) : ProfileProblem
}

/** Why a field cannot be offered on a profile; [logText] is the log and API wording, never shown to the owner. */
enum class BadFieldCause(val logText: String) {
    NOT_THIS_ASSET("not a definition of this asset"),
    DERIVED("derived values are computed, not entered"),
    ARCHIVED("archived"),
    LISTED_TWICE("listed twice"),
}

/** Field validation failed; every problem found, collected once rather than fail-fast. */
class ProfileValidation(val problems: List<ProfileProblem>) :
    IllegalArgumentException("invalid profile: $problems")

/** The profile this edit, archive or delete was aimed at is no longer there. */
class NoSuchProfile(id: ProfileId) : IllegalArgumentException("no profile ${id.value}")
