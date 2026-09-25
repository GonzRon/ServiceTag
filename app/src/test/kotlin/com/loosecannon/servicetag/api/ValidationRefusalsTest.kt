package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.journal.DerivedProblem
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.usecase.AssetProblem
import com.loosecannon.servicetag.core.usecase.AssetValidation
import com.loosecannon.servicetag.core.usecase.DefinitionProblem
import com.loosecannon.servicetag.core.usecase.DefinitionValidation
import com.loosecannon.servicetag.core.usecase.EventValidation
import com.loosecannon.servicetag.core.usecase.FieldProblem
import com.loosecannon.servicetag.core.usecase.ProfileProblem
import com.loosecannon.servicetag.core.usecase.ProfileValidation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import com.loosecannon.servicetag.core.model.Season as SeasonWindow

/**
 * #52 — every problem of the four 1.1.0 validation families, one instance per leaf, mapped to the
 * Phase 1A plan's §5 sentence and key (rows C1–C37).
 *
 * Each row also pins the problem's `toString`, because that string *is* the wire: `problems` must
 * stay byte-identical, and a `:core` change that moved one would surface here before a client saw it.
 */
class ValidationRefusalsTest {

    /** A reading id, the one caller-sent value a problem here carries. No message may repeat it. */
    private val id = DefinitionId("def-a")

    /** One §5 row: its number, the problem, what `problems` spells, and the refusal it must map to. */
    private data class Row(
        val c: String,
        val problem: Any,
        val wire: String,
        val code: String,
        val message: String,
        val field: String?,
    )

    private val assetRows = listOf(
        Row("C1", AssetProblem.NameRequired, "NameRequired", ASSET, "an asset needs a name", "name"),
        Row(
            "C2", AssetProblem.BadCurrency, "BadCurrency", ASSET,
            "currency must be an ISO 4217 code this build knows", "currency",
        ),
        Row(
            "C3", AssetProblem.CurrencyRequired, "CurrencyRequired", ASSET,
            "a purchasePriceMinor needs a currency", "currency",
        ),
        Row(
            "C4", AssetProblem.NegativePrice, "NegativePrice", ASSET,
            "purchasePriceMinor may not be negative", "purchasePriceMinor",
        ),
        // C5's ⟨field⟩ is each key the domain raises it for: the asset body's three dates, and
        // the retire route's `retiredOn`.
        Row(
            "C5", AssetProblem.BadDate("purchaseOn"), "BadDate(field=purchaseOn)", ASSET,
            "purchaseOn must be an ISO YYYY-MM-DD date", "purchaseOn",
        ),
        Row(
            "C5", AssetProblem.BadDate("inServiceOn"), "BadDate(field=inServiceOn)", ASSET,
            "inServiceOn must be an ISO YYYY-MM-DD date", "inServiceOn",
        ),
        Row(
            "C5", AssetProblem.BadDate("warrantyExpiresOn"), "BadDate(field=warrantyExpiresOn)", ASSET,
            "warrantyExpiresOn must be an ISO YYYY-MM-DD date", "warrantyExpiresOn",
        ),
        Row(
            "C5", AssetProblem.BadDate("retiredOn"), "BadDate(field=retiredOn)", ASSET,
            "retiredOn must be an ISO YYYY-MM-DD date", "retiredOn",
        ),
        Row(
            "C6", AssetProblem.Season(SeasonWindow.Problem.BothOrNeither), "Season(p=BothOrNeither)", ASSET,
            "seasonStartMmdd and seasonEndMmdd go together: send both or neither", "seasonStartMmdd",
        ),
        Row(
            "C7", AssetProblem.Season(SeasonWindow.Problem.BadDate("start")), "Season(p=BadDate(which=start))", ASSET,
            "seasonStartMmdd must be a real MM-DD date", "seasonStartMmdd",
        ),
        Row(
            "C8", AssetProblem.Season(SeasonWindow.Problem.BadDate("end")), "Season(p=BadDate(which=end))", ASSET,
            "seasonEndMmdd must be a real MM-DD date", "seasonEndMmdd",
        ),
        // C8's unreachable arm: `which` is a String, and `Season.validate` only ever says start or end.
        Row(
            "C8", AssetProblem.Season(SeasonWindow.Problem.BadDate("middle")), "Season(p=BadDate(which=middle))",
            ASSET, "a season date must be a real MM-DD date", null,
        ),
        Row(
            "C9", AssetProblem.UnknownParent, "UnknownParent", ASSET,
            "parentAssetId must name an asset on this phone", "parentAssetId",
        ),
    )

    private val eventRows = listOf(
        Row(
            "C10", FieldProblem.TitleRequired, "TitleRequired", EVENT,
            "title is required unless the quick action gives a default title", "title",
        ),
        Row(
            "C11", FieldProblem.BadDate(), "BadDate(definitionId=null)", EVENT,
            "occurredOn must be an ISO YYYY-MM-DD date", "occurredOn",
        ),
        Row(
            "C12", FieldProblem.BadTime(), "BadTime(definitionId=null)", EVENT,
            "occurredTime must be an HH:MM time of day", "occurredTime",
        ),
        // Not raised today: a date or time that is a reading's value, not the event's own.
        Row(
            "C13", FieldProblem.BadDate(id), "BadDate(definitionId=DefinitionId(value=def-a))", EVENT,
            "a value in values is not a valid date or time", "values",
        ),
        Row(
            "C13", FieldProblem.BadTime(id), "BadTime(definitionId=DefinitionId(value=def-a))", EVENT,
            "a value in values is not a valid date or time", "values",
        ),
        Row(
            "C14", FieldProblem.Required(id), "Required(definitionId=DefinitionId(value=def-a))", EVENT,
            "a required reading in values has no value", "values",
        ),
        Row(
            "C15", FieldProblem.NotANumber(id), "NotANumber(definitionId=DefinitionId(value=def-a))", EVENT,
            "a value in values does not fit its reading: a number must be finite, a yes-or-no must be true, " +
                "false, 1 or 0",
            "values",
        ),
        Row(
            "C16", FieldProblem.BadConsumable(2), "BadConsumable(index=2)", EVENT,
            "every consumable needs a name and a quantity of zero or more", "consumables",
        ),
    )

    private val derivedRows = listOf(
        Row(
            "C24", DerivedProblem.NotNumber, "NotNumber", DEFINITION,
            "a DERIVED reading must be a NUMBER", "valueType",
        ),
        Row(
            "C25", DerivedProblem.IsMeter, "IsMeter", DEFINITION,
            "a DERIVED reading cannot be a meter", "isMeter",
        ),
        Row(
            "C26", DerivedProblem.MissingSpec, "MissingSpec", DEFINITION,
            "a DERIVED reading needs formula, sourceAId and sourceBId", "formula",
        ),
        Row(
            "C27", DerivedProblem.SpecOnEntered, "SpecOnEntered", DEFINITION,
            "an ENTERED reading takes no formula, sourceAId or sourceBId", "formula",
        ),
        Row(
            "C28", DerivedProblem.SameSource, "SameSource", DEFINITION,
            "sourceAId and sourceBId must name two different readings", "sourceAId",
        ),
        Row(
            "C29", DerivedProblem.UnknownSource(id), "UnknownSource(id=DefinitionId(value=def-a))", DEFINITION,
            "a source names no reading on this phone", null,
        ),
        Row(
            "C30", DerivedProblem.SourceOtherAsset(id), "SourceOtherAsset(id=DefinitionId(value=def-a))", DEFINITION,
            "a source must be a reading of the same asset", null,
        ),
        Row(
            "C31", DerivedProblem.SourceNotEntered(id), "SourceNotEntered(id=DefinitionId(value=def-a))", DEFINITION,
            "a source must be an ENTERED reading", null,
        ),
        Row(
            "C32", DerivedProblem.SourceNotNumber(id), "SourceNotNumber(id=DefinitionId(value=def-a))", DEFINITION,
            "a source must be a NUMBER reading", null,
        ),
        Row(
            "C33", DerivedProblem.SourceIsMeter(id), "SourceIsMeter(id=DefinitionId(value=def-a))", DEFINITION,
            "a source cannot be a meter", null,
        ),
    )

    /** C17–C23, then every [DerivedProblem] as the `Derived(p=…)` the definition command wraps it in. */
    private val definitionRows = listOf(
        Row("C17", DefinitionProblem.LabelRequired, "LabelRequired", DEFINITION, "a reading needs a label", "label"),
        Row(
            "C18", DefinitionProblem.BadKey, "BadKey", DEFINITION,
            "key must be a lower-case letter followed by up to 39 lower-case letters, digits or underscores", "key",
        ),
        Row(
            "C19", DefinitionProblem.KeyTaken, "KeyTaken", DEFINITION,
            "another reading of this asset already uses that key", "key",
        ),
        Row("C20", DefinitionProblem.BadDecimals, "BadDecimals", DEFINITION, "decimals must be 0–4", "decimals"),
        Row(
            "C21", DefinitionProblem.RangeOrder, "RangeOrder", DEFINITION,
            "rangeLow may not be greater than rangeHigh", "rangeLow",
        ),
        Row(
            "C22", DefinitionProblem.RangeOnNonNumber, "RangeOnNonNumber", DEFINITION,
            "only a NUMBER reading takes rangeLow or rangeHigh", "rangeLow",
        ),
        Row(
            "C23", DefinitionProblem.MeterOnNonNumber, "MeterOnNonNumber", DEFINITION,
            "only a NUMBER reading can be a meter", "isMeter",
        ),
    ) + derivedRows.map { it.copy(problem = DefinitionProblem.Derived(it.problem as DerivedProblem), wire = "Derived(p=${it.wire})") }

    private val profileRows = listOf(
        Row("C34", ProfileProblem.NameRequired, "NameRequired", PROFILE, "a quick action needs a name", "name"),
        Row(
            "C35", ProfileProblem.NameTaken, "NameTaken", PROFILE,
            "another quick action of this asset, archived ones included, already has that name", "name",
        ),
        Row(
            "C36", ProfileProblem.BadField(id, "listed twice"), "BadField(id=DefinitionId(value=def-a), reason=listed twice)",
            PROFILE,
            "every entry in fields must name an ENTERED reading of this asset, at most once; an archived reading " +
                "stays only if the quick action already had it",
            "fields",
        ),
        Row(
            "C37", ProfileProblem.BadConsumable(1), "BadConsumable(index=1)", PROFILE,
            "every consumable needs a name, and a defaultQuantity of zero or more when one is given", "consumables",
        ),
    )

    /**
     * Each row maps to exactly its §5 refusal, its problem still spells what the wire has always
     * carried, and no message repeats a value the caller sent (the reading id, the log reason).
     */
    private fun <P : Any> assertEveryRow(rows: List<Row>, map: (P) -> Refusal) {
        for (row in rows) {
            @Suppress("UNCHECKED_CAST")
            val refusal = map(row.problem as P)
            assertEquals(row.wire, Refusal(row.code, row.message, row.field), refusal)
            assertEquals(row.c, row.wire, row.problem.toString())
            assertFalse(row.wire, "def-a" in refusal.message || "listed twice" in refusal.message)
        }
    }

    /** One row per leaf nested in [sealed]: a leaf added to `:core` later needs its own row here. */
    private fun assertCoversEveryLeaf(sealed: Class<*>, problems: List<Any>) {
        val leaves = sealed.declaredClasses.filter { sealed.isAssignableFrom(it) }.map { it.simpleName }.toSet()
        assertEquals(sealed.simpleName, leaves, problems.map { it.javaClass.simpleName }.toSet())
    }

    @Test fun everyAssetProblem() {
        assertEveryRow(assetRows, ::assetRefusal)
        assertCoversEveryLeaf(AssetProblem::class.java, assetRows.map { it.problem })
        assertCoversEveryLeaf(
            SeasonWindow.Problem::class.java,
            assetRows.mapNotNull { (it.problem as? AssetProblem.Season)?.p },
        )
    }

    @Test fun everyEventProblem() {
        assertEveryRow(eventRows, ::eventRefusal)
        assertCoversEveryLeaf(FieldProblem::class.java, eventRows.map { it.problem })
    }

    @Test fun everyDefinitionProblem() {
        assertEveryRow(definitionRows, ::definitionRefusal)
        assertCoversEveryLeaf(DefinitionProblem::class.java, definitionRows.map { it.problem })
        assertCoversEveryLeaf(DerivedProblem::class.java, derivedRows.map { it.problem })
    }

    @Test fun everyProfileProblem() {
        assertEveryRow(profileRows, ::profileRefusal)
        assertCoversEveryLeaf(ProfileProblem::class.java, profileRows.map { it.problem })
    }

    /**
     * K5: a source-id problem names a reading id but not whether `sourceAId` or `sourceBId` sent it,
     * so `field` stays `null` rather than a guess, and the id stays in `problems`, never the message.
     */
    @Test fun aSourceIdProblemNamesNoKey() {
        for (problem in listOf(
            DerivedProblem.UnknownSource(id),
            DerivedProblem.SourceOtherAsset(id),
            DerivedProblem.SourceNotEntered(id),
            DerivedProblem.SourceNotNumber(id),
            DerivedProblem.SourceIsMeter(id),
        )) {
            val refusal = definitionRefusal(DefinitionProblem.Derived(problem))
            assertEquals(problem.toString(), null, refusal.field)
            assertEquals(problem.toString(), DEFINITION, refusal.code)
            assertFalse(problem.toString(), "def-a" in refusal.message)
        }
    }

    /**
     * A refusal that named no problem is unreachable — every throw site collects at least one — but
     * the mapper runs inside the router's `catch`, so it must still answer the family's shipped
     * envelope rather than throw.
     */
    @Test fun aRefusalNamingNoProblemFallsBackToTheFamilySentence() {
        for ((failure, code, message) in listOf<Triple<Exception, String, String>>(
            Triple(AssetValidation(emptyList()), ASSET, "the asset was refused"),
            Triple(EventValidation(emptyList()), EVENT, "the event was refused"),
            Triple(DefinitionValidation(emptyList()), DEFINITION, "the reading was refused"),
            Triple(ProfileValidation(emptyList()), PROFILE, "the quick action was refused"),
        )) {
            val response = mapDomainFailure(failure)
            assertEquals(code, 422, response.status)
            assertEquals(ApiErrorDetail(code, message, emptyList(), null), response.errorDetail())
        }
    }

    private companion object {
        // The shipped codes, spelled out here rather than read from the production constants, so a
        // renamed constant cannot move a code a client branches on.
        const val ASSET = "asset_validation"
        const val EVENT = "event_validation"
        const val DEFINITION = "definition_validation"
        const val PROFILE = "profile_validation"
    }
}
