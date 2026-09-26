package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.journal.DerivedProblem
import com.loosecannon.servicetag.core.usecase.AssetProblem
import com.loosecannon.servicetag.core.usecase.DefinitionProblem
import com.loosecannon.servicetag.core.usecase.FieldProblem
import com.loosecannon.servicetag.core.usecase.ProfileProblem
import com.loosecannon.servicetag.core.model.Season as SeasonWindow

// --- #52: the four 1.1.0 validation families ---------------------------------------------------
//
// Each family keeps its shipped `lower_snake` code for every problem, because a client branches on
// it, and `problems` keeps every problem in the domain's own `toString` form: `mapDomainFailure`
// builds that list and nothing here touches it. What these mappers add is the envelope's `message`
// and `field` for the **first** problem: a sentence that names a key or a rule, never a value the
// caller sent, and the one body key the problem is about.
//
// The `field` rule is 1.4's. A problem about one key names that key. A problem about a pair names
// the pair's first key, as `SEASON_WINDOW_REQUIRED` does. A problem that names a reading's id but not
// the request key that sent it answers `null`: the id is already in `problems`, and a guessed key
// would be worse than none.
//
// Every `when` here has no `else`, the nested ones included, so a problem added to `:core` later is
// a compile error rather than a refusal nobody documented. The one exception is the `String`-typed
// `which` of the season window's `BadDate`, whose last arm is unreachable. `docs/api/v1.md` lists
// every row under **The 1.1.0 validation families**.

internal const val ASSET_VALIDATION: String = "asset_validation"
internal const val EVENT_VALIDATION: String = "event_validation"
internal const val DEFINITION_VALIDATION: String = "definition_validation"
internal const val PROFILE_VALIDATION: String = "profile_validation"

/** Every [AssetProblem] as a refusal. */
internal fun assetRefusal(problem: AssetProblem): Refusal = when (problem) {
    AssetProblem.NameRequired -> Refusal(ASSET_VALIDATION, "an asset needs a name", "name")
    AssetProblem.BadCurrency ->
        Refusal(ASSET_VALIDATION, "currency must be an ISO 4217 code this build knows", "currency")
    AssetProblem.CurrencyRequired -> Refusal(ASSET_VALIDATION, "a purchasePriceMinor needs a currency", "currency")
    AssetProblem.NegativePrice ->
        Refusal(ASSET_VALIDATION, "purchasePriceMinor may not be negative", "purchasePriceMinor")
    // The domain's own key — `purchaseOn`, `inServiceOn`, `warrantyExpiresOn`, or the retire route's
    // `retiredOn` — and never the value that failed.
    is AssetProblem.BadDate ->
        Refusal(ASSET_VALIDATION, "${problem.field} must be an ISO YYYY-MM-DD date", problem.field)
    is AssetProblem.Season -> seasonWindowRefusal(problem.p)
    AssetProblem.UnknownParent ->
        Refusal(ASSET_VALIDATION, "parentAssetId must name an asset on this phone", "parentAssetId")
}

/** The asset body's season pair. */
private fun seasonWindowRefusal(problem: SeasonWindow.Problem): Refusal = when (problem) {
    // The pair's first key (owner ruling, 2026-09-25): a caller can put the fix there.
    SeasonWindow.Problem.BothOrNeither -> Refusal(
        ASSET_VALIDATION, "seasonStartMmdd and seasonEndMmdd go together: send both or neither", "seasonStartMmdd",
    )
    is SeasonWindow.Problem.BadDate -> when (problem.which) {
        "start" -> Refusal(ASSET_VALIDATION, "seasonStartMmdd must be a real MM-DD date", "seasonStartMmdd")
        "end" -> Refusal(ASSET_VALIDATION, "seasonEndMmdd must be a real MM-DD date", "seasonEndMmdd")
        // Unreachable: `Season.validate` only ever says start or end, but `which` is a String.
        else -> Refusal(ASSET_VALIDATION, "a season date must be a real MM-DD date")
    }
}

/**
 * Every [FieldProblem] as a refusal. A `BadDate` or `BadTime` with no reading id is the event's own
 * `occurredOn` or `occurredTime`, which is every one the domain raises today; one carrying an id
 * would be a reading's value, so it names `values`.
 */
internal fun eventRefusal(problem: FieldProblem): Refusal = when (problem) {
    FieldProblem.TitleRequired ->
        Refusal(EVENT_VALIDATION, "title is required unless the quick action gives a default title", "title")
    is FieldProblem.BadDate -> if (problem.definitionId == null) {
        Refusal(EVENT_VALIDATION, "occurredOn must be an ISO YYYY-MM-DD date", "occurredOn")
    } else {
        badReadingValue()
    }
    is FieldProblem.BadTime -> if (problem.definitionId == null) {
        Refusal(EVENT_VALIDATION, "occurredTime must be an HH:MM time of day", "occurredTime")
    } else {
        badReadingValue()
    }
    is FieldProblem.Required -> Refusal(EVENT_VALIDATION, "a required reading in values has no value", "values")
    is FieldProblem.NotANumber -> Refusal(
        EVENT_VALIDATION,
        "a value in values does not fit its reading: a number must be finite, a yes-or-no must be true, false, 1 or 0",
        "values",
    )
    is FieldProblem.BadConsumable ->
        Refusal(EVENT_VALIDATION, "every consumable needs a name and a quantity of zero or more", "consumables")
}

private fun badReadingValue(): Refusal =
    Refusal(EVENT_VALIDATION, "a value in values is not a valid date or time", "values")

/** Every [DefinitionProblem] as a refusal, a DERIVED shape problem included. */
internal fun definitionRefusal(problem: DefinitionProblem): Refusal = when (problem) {
    DefinitionProblem.LabelRequired -> Refusal(DEFINITION_VALIDATION, "a reading needs a label", "label")
    DefinitionProblem.BadKey -> Refusal(
        DEFINITION_VALIDATION,
        "key must be a lower-case letter followed by up to 39 lower-case letters, digits or underscores",
        "key",
    )
    DefinitionProblem.KeyTaken ->
        Refusal(DEFINITION_VALIDATION, "another reading of this asset already uses that key", "key")
    DefinitionProblem.BadDecimals -> Refusal(DEFINITION_VALIDATION, "decimals must be 0–4", "decimals")
    DefinitionProblem.RangeOrder ->
        Refusal(DEFINITION_VALIDATION, "rangeLow may not be greater than rangeHigh", "rangeLow")
    DefinitionProblem.RangeOnNonNumber ->
        Refusal(DEFINITION_VALIDATION, "only a NUMBER reading takes rangeLow or rangeHigh", "rangeLow")
    DefinitionProblem.MeterOnNonNumber ->
        Refusal(DEFINITION_VALIDATION, "only a NUMBER reading can be a meter", "isMeter")
    is DefinitionProblem.Derived -> derivedRefusal(problem.p)
}

private fun derivedRefusal(problem: DerivedProblem): Refusal = when (problem) {
    DerivedProblem.NotNumber -> Refusal(DEFINITION_VALIDATION, "a DERIVED reading must be a NUMBER", "valueType")
    DerivedProblem.IsMeter -> Refusal(DEFINITION_VALIDATION, "a DERIVED reading cannot be a meter", "isMeter")
    DerivedProblem.MissingSpec ->
        Refusal(DEFINITION_VALIDATION, "a DERIVED reading needs formula, sourceAId and sourceBId", "formula")
    DerivedProblem.SpecOnEntered ->
        Refusal(DEFINITION_VALIDATION, "an ENTERED reading takes no formula, sourceAId or sourceBId", "formula")
    DerivedProblem.SameSource ->
        Refusal(DEFINITION_VALIDATION, "sourceAId and sourceBId must name two different readings", "sourceAId")
    // The five below name a reading id but not whether `sourceAId` or `sourceBId` sent it, so
    // `field` stays null; the id itself is in `problems`.
    is DerivedProblem.UnknownSource -> Refusal(DEFINITION_VALIDATION, "a source names no reading on this phone")
    is DerivedProblem.SourceOtherAsset ->
        Refusal(DEFINITION_VALIDATION, "a source must be a reading of the same asset")
    is DerivedProblem.SourceNotEntered -> Refusal(DEFINITION_VALIDATION, "a source must be an ENTERED reading")
    is DerivedProblem.SourceNotNumber -> Refusal(DEFINITION_VALIDATION, "a source must be a NUMBER reading")
    is DerivedProblem.SourceIsMeter -> Refusal(DEFINITION_VALIDATION, "a source cannot be a meter")
}

/** Every [ProfileProblem] as a refusal. `BadField`'s reason is log text, and stays in `problems` only. */
internal fun profileRefusal(problem: ProfileProblem): Refusal = when (problem) {
    ProfileProblem.NameRequired -> Refusal(PROFILE_VALIDATION, "a quick action needs a name", "name")
    ProfileProblem.NameTaken -> Refusal(
        PROFILE_VALIDATION, "another quick action of this asset, archived ones included, already has that name", "name",
    )
    is ProfileProblem.BadField -> Refusal(
        PROFILE_VALIDATION,
        "every entry in fields must name an ENTERED reading of this asset, at most once; " +
            "an archived reading stays only if the quick action already had it",
        "fields",
    )
    is ProfileProblem.BadConsumable -> Refusal(
        PROFILE_VALIDATION,
        "every consumable needs a name, and a defaultQuantity of zero or more when one is given",
        "consumables",
    )
}
