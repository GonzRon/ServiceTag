package com.loosecannon.servicetag.ui.asset

import com.loosecannon.servicetag.core.condition.ConditionHistory
import com.loosecannon.servicetag.core.hapublish.PublishBinding
import com.loosecannon.servicetag.core.hapublish.PublishKind
import com.loosecannon.servicetag.core.hapublish.STATUS_DEGRADED
import com.loosecannon.servicetag.core.hapublish.STATUS_DOWN
import com.loosecannon.servicetag.core.hapublish.STATUS_UP
import com.loosecannon.servicetag.core.hapublish.equipmentStatusOf
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetCondition
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonActivation
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncBinding
import com.loosecannon.servicetag.core.seasonsync.SyncMode
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.assetRow
import com.loosecannon.servicetag.testing.conditionRow
import com.loosecannon.servicetag.testing.dayMillis
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * #116 (U-C1; rows u1–u3) — the three facts an Assets row carries beside its asset, from [rowFactsOf]: the Home
 * Assistant link, the condition and the in-season mark. Three sources kept apart, each read for this asset alone. The
 * join's two folds ([enabledInboundIds], [enabledPublishingIds]) feed it here as the list feeds it. Every name and
 * entity id is fictional.
 */
class AssetRowFactsTest {

    private val today = LocalDate.parse("2026-06-15")
    private val heater = assetRow("heater", name = "Example Heater", seasonMode = SeasonMode.MANUAL)

    /** Built lazily: only the read-model agreement case needs a store. */
    private var graph: FakeGraph? = null

    @After fun tearDown() {
        graph?.close()
    }

    private fun facts(
        asset: Asset = heater,
        held: Boolean = false,
        outOfSeason: Boolean = false,
        rows: List<AssetCondition> = emptyList(),
        inbound: List<SeasonSyncBinding> = emptyList(),
        publishing: List<PublishBinding> = emptyList(),
    ): RowFacts = rowFactsOf(
        asset = asset,
        held = held,
        outOfSeason = outOfSeason,
        conditionRows = rows,
        inboundEnabled = asset.id in enabledInboundIds(inbound),
        publishingEnabled = asset.id in enabledPublishingIds(publishing),
    )

    private fun inbound(assetId: String, enabled: Boolean = true) = SeasonSyncBinding(
        assetId = AssetId(assetId), connectionId = "connection-1", entityId = "input_boolean.${assetId}_in_season",
        mode = SyncMode.FOLLOW, enabled = enabled, revision = 1, observedState = null, observedChangedAt = null,
        lastSuccessAt = null, lastAttemptAt = null, errorKind = null, errorDetail = null, errorAt = null,
        appliedAction = null, appliedOn = null, appliedAt = null, lastAppliedSource = null, createdAt = 1_000L,
        updatedAt = 1_000L,
    )

    private fun publish(assetId: String, kind: PublishKind, enabled: Boolean = true) = PublishBinding(
        id = "binding-$assetId-$kind", assetId = AssetId(assetId), connectionId = "connection-1", kind = kind,
        entityId = "input_select.${assetId}_$kind", enabled = enabled, revision = 1, activatedInstallationId = null,
        validatedAt = null, validatedRevision = null, boundaryEntityId = null, createdAt = 1_000L, updatedAt = 1_000L,
    )

    private fun activation(id: String, action: SeasonAction, on: String, assetId: String = "heater") = SeasonActivation(
        id = id, assetId = AssetId(assetId), action = action, occurredOn = on, eventId = null, createdAt = dayMillis(on),
    )

    // ---------------------------------------------------------------------------------------------------------------
    // u1 — the Home Assistant link: any enabled binding, either direction, either kind; one mark.
    // ---------------------------------------------------------------------------------------------------------------

    @Test fun anEnabledBindingOfEitherDirectionAndEitherKindLinksTheRow() {
        assertFalse("nothing bound", facts().haLinked)
        assertTrue("an enabled #16 binding", facts(inbound = listOf(inbound("heater"))).haLinked)
        assertTrue(
            "an enabled equipment-status binding",
            facts(publishing = listOf(publish("heater", PublishKind.EQUIPMENT_STATUS))).haLinked,
        )
        assertTrue(
            "an enabled Season Start date binding",
            facts(publishing = listOf(publish("heater", PublishKind.SEASON_SERVICE_DATE))).haLinked,
        )
    }

    @Test fun aDisabledBindingOrAnotherAssetsBindingLinksNothing() {
        assertFalse(
            "a disabled equipment-status binding",
            facts(publishing = listOf(publish("heater", PublishKind.EQUIPMENT_STATUS, enabled = false))).haLinked,
        )
        assertFalse(
            "a disabled Season Start date binding",
            facts(publishing = listOf(publish("heater", PublishKind.SEASON_SERVICE_DATE, enabled = false))).haLinked,
        )
        assertFalse("a disabled #16 binding", facts(inbound = listOf(inbound("heater", enabled = false))).haLinked)
        assertFalse(
            "another asset's enabled bindings",
            facts(
                inbound = listOf(inbound("boiler")),
                publishing = listOf(publish("boiler", PublishKind.EQUIPMENT_STATUS)),
            ).haLinked,
        )
    }

    @Test fun oneMarkWhateverTheKindsAndDirections() {
        val inboundOnly = facts(inbound = listOf(inbound("heater")))
        val statusOnly = facts(publishing = listOf(publish("heater", PublishKind.EQUIPMENT_STATUS)))
        val dateOnly = facts(publishing = listOf(publish("heater", PublishKind.SEASON_SERVICE_DATE)))
        val everything = facts(
            inbound = listOf(inbound("heater")),
            publishing = listOf(
                publish("heater", PublishKind.EQUIPMENT_STATUS),
                publish("heater", PublishKind.SEASON_SERVICE_DATE),
            ),
        )
        assertEquals(inboundOnly, statusOnly)
        assertEquals(inboundOnly, dateOnly)
        assertEquals(inboundOnly, everything)
        // An enabled binding beside a disabled one of the other kind still links: enabled is per binding.
        assertTrue(
            facts(
                publishing = listOf(
                    publish("heater", PublishKind.EQUIPMENT_STATUS, enabled = false),
                    publish("heater", PublishKind.SEASON_SERVICE_DATE),
                ),
            ).haLinked,
        )
    }

    // ---------------------------------------------------------------------------------------------------------------
    // u2 — the condition: ConditionHistory.current, only while the asset is maintained here.
    // ---------------------------------------------------------------------------------------------------------------

    @Test fun theConditionIsTheCurrentRowNotTheNewestWritten() {
        val operational = conditionRow("c1", "heater", OperationalCondition.OPERATIONAL, "2026-09-01", reason = "Serviced")
        val down = conditionRow(
            "c2", "heater", OperationalCondition.DOWN, "2026-10-01", reason = "Igniter fault", occurredTime = "08:30",
        )
        val shown = facts(rows = listOf(operational, down)).condition
        assertNotNull(shown)
        assertEquals(OperationalCondition.DOWN, shown!!.condition)
        assertEquals(LocalDate.parse("2026-10-01"), shown.since)
        assertEquals("Igniter fault", shown.reason)
        assertEquals("08:30", shown.occurredTime)
        assertFalse("the row never opens the event", shown.eventExists)

        // A backdated correction, written last — so the newest createdAt — sorts into place before the current row.
        val backdated = conditionRow(
            "c3", "heater", OperationalCondition.DEGRADED, "2026-09-15", createdAt = dayMillis("2026-10-20"),
        )
        assertEquals("a backdated row changes nothing", shown, facts(rows = listOf(operational, down, backdated)).condition)
        assertEquals("nor does the order the rows arrive in", shown, facts(rows = listOf(backdated, down, operational)).condition)
    }

    @Test fun noRowOrAnAssetNotMaintainedHereHasNoCondition() {
        val rows = listOf(conditionRow("c1", "heater", OperationalCondition.DOWN, "2026-10-01"))
        assertNull("none recorded", facts().condition)
        assertNotNull("the control: in service, not held, one row", facts(rows = rows).condition)
        assertNull("retired", facts(asset = heater.copy(retiredOn = "2026-10-02"), rows = rows).condition)
        assertNull("archived", facts(asset = heater.copy(status = AssetStatus.ARCHIVED), rows = rows).condition)
        assertNull("held", facts(held = true, rows = rows).condition)
    }

    /**
     * U-C5: the row's condition agrees with B1's [equipmentStatusOf] — the value #113 publishes — over a table of
     * histories, and with the health read model's `conditionViewOf` on every field but `eventExists`.
     */
    @Test fun theConditionAgreesWithWhatIsPublishedAndWithTheHealthReadModel() = runTest {
        val readModel = FakeGraph().also { graph = it }.assetHealthReadModel
        fun row(id: String, condition: OperationalCondition, on: String, vararg more: Pair<String, Any?>): AssetCondition {
            val extra = more.toMap()
            return conditionRow(
                id, "heater", condition, on,
                reason = extra["reason"] as String? ?: "",
                occurredTime = extra["time"] as String?,
                eventId = extra["event"] as String?,
                createdAt = (extra["created"] as String?)?.let(::dayMillis) ?: dayMillis(on),
            )
        }
        val histories: List<List<AssetCondition>> = listOf(
            emptyList(),
            listOf(row("a", OperationalCondition.OPERATIONAL, "2026-09-01")),
            listOf(row("a", OperationalCondition.DEGRADED, "2026-09-01", "reason" to "Weak flame")),
            listOf(row("a", OperationalCondition.DOWN, "2026-09-01", "event" to "event-example-1")),
            // DOWN re-recorded with a new reason: "since" stays on the first DOWN of the run.
            listOf(
                row("a", OperationalCondition.OPERATIONAL, "2026-08-01"),
                row("b", OperationalCondition.DOWN, "2026-09-01", "reason" to "No ignition"),
                row("c", OperationalCondition.DOWN, "2026-09-10", "reason" to "Still no ignition"),
            ),
            // Back in service after a fault.
            listOf(row("a", OperationalCondition.DOWN, "2026-08-01"), row("b", OperationalCondition.OPERATIONAL, "2026-09-01")),
            // A backdated DEGRADED written last.
            listOf(
                row("a", OperationalCondition.OPERATIONAL, "2026-09-01"),
                row("b", OperationalCondition.DEGRADED, "2026-08-15", "created" to "2026-09-20"),
            ),
            // One day: a timed row sorts after an untimed one, whatever was written first.
            listOf(
                row("a", OperationalCondition.DOWN, "2026-09-01", "time" to "08:00"),
                row("b", OperationalCondition.OPERATIONAL, "2026-09-01", "created" to "2026-09-02"),
            ),
        )
        for (rows in histories) {
            val history = ConditionHistory.of(rows)
            val shown = facts(rows = rows).condition
            assertEquals("published, $rows", equipmentStatusOf(history), shown?.let { published(it.condition) })
            assertEquals("the read model's view, $rows", readModel.conditionViewOf(history)?.copy(eventExists = false), shown)
        }
    }

    /** U-C5's oracle table: the condition each published status stands for. */
    private fun published(condition: OperationalCondition): String = when (condition) {
        OperationalCondition.OPERATIONAL -> STATUS_UP
        OperationalCondition.DEGRADED -> STATUS_DEGRADED
        OperationalCondition.DOWN -> STATUS_DOWN
    }

    // ---------------------------------------------------------------------------------------------------------------
    // u3 — the season: the asset's own mode and its own phase; never its name or its category.
    // ---------------------------------------------------------------------------------------------------------------

    /** The facts as the list builds them: the phase from the shipped rule over the asset's own activations. */
    private fun seasonFacts(asset: Asset, activations: List<SeasonActivation> = emptyList()): RowFacts {
        val outOfSeason = outOfSeasonOn(asset, activations, today)
        return facts(asset = asset, outOfSeason = outOfSeason)
    }

    @Test fun aYearRoundAssetGetsNeitherSeasonMark() {
        val pool = assetRow("pool", name = "Example Indoor Pool")
        assertFalse("never out of season", outOfSeasonOn(pool, emptyList(), today))
        assertFalse("and no in-season mark", seasonFacts(pool).inSeason)
    }

    @Test fun inSeasonIsTheAssetsOwnPhase() {
        val boiler = assetRow(
            "boiler", name = "Example Boiler", seasonMode = SeasonMode.CALENDAR, seasonStart = "05-01", seasonEnd = "09-30",
        )
        assertTrue("a CALENDAR asset inside its window", seasonFacts(boiler).inSeason)

        val started = listOf(activation("a1", SeasonAction.START, "2026-04-01"))
        assertTrue("a MANUAL asset after a START", seasonFacts(heater, started).inSeason)

        val ended = started + activation("a2", SeasonAction.END, "2026-06-01")
        assertTrue("a MANUAL asset after an END is out of season", outOfSeasonOn(heater, ended, today))
        assertFalse("and gets no in-season mark", seasonFacts(heater, ended).inSeason)

        val outside = boiler.copy(seasonStartMmdd = "11-01", seasonEndMmdd = "03-31")
        assertFalse("a CALENDAR asset outside its window", seasonFacts(outside).inSeason)
    }

    /**
     * Constraint 2: seasonality is the asset's own. The same inputs under another name or category answer alike. A
     * shared membership has no way in: [rowFactsOf] takes none, and the list's live case holds it there.
     */
    @Test fun neitherNameNorCategoryDecidesTheSeason() {
        val boiler = assetRow(
            "boiler", name = "Example Boiler", seasonMode = SeasonMode.CALENDAR, seasonStart = "05-01", seasonEnd = "09-30",
        )
        val ended = listOf(
            activation("a1", SeasonAction.START, "2026-04-01"),
            activation("a2", SeasonAction.END, "2026-06-01"),
        )
        val cases = listOf(heater to emptyList(), heater to ended.take(1), heater to ended, boiler to emptyList())
        for ((asset, activations) in cases) {
            val base = seasonFacts(asset, activations)
            for (variant in listOf(
                asset.copy(name = "Example Hot Tub"),
                asset.copy(category = "Pool"),
                asset.copy(name = "Example Heater", category = "Heating"),
                asset.copy(category = ""),
            )) {
                assertEquals("${asset.id} as $variant", base, seasonFacts(variant, activations))
            }
        }
    }
}
