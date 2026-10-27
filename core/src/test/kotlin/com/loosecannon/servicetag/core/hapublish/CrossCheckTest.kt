package com.loosecannon.servicetag.core.hapublish

import com.loosecannon.servicetag.core.hapublish.PublishHarness.Companion.BOILER
import com.loosecannon.servicetag.core.hapublish.PublishHarness.Companion.DATE
import com.loosecannon.servicetag.core.hapublish.PublishHarness.Companion.HEATER
import com.loosecannon.servicetag.core.hapublish.PublishHarness.Companion.INSTALLATION
import com.loosecannon.servicetag.core.hapublish.PublishHarness.Companion.IN_SEASON
import com.loosecannon.servicetag.core.hapublish.PublishHarness.Companion.SEASON_ENDED
import com.loosecannon.servicetag.core.hapublish.PublishHarness.Companion.STATUS
import com.loosecannon.servicetag.core.hapublish.PublishHarness.Companion.STATUS_2
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleSeasonSource
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.seasonsync.LinkScheduleSeason
import com.loosecannon.servicetag.core.seasonsync.ScheduleSeasonRefusal
import com.loosecannon.servicetag.core.seasonsync.ScheduleSeasonRefused
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncLinkRefusal
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncLinkRefused
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncScheduler
import com.loosecannon.servicetag.core.testing.InMemoryScheduleSeasonBindingRepository
import com.loosecannon.servicetag.core.testing.WithStores
import com.loosecannon.servicetag.core.testing.scheduleOf
import com.loosecannon.servicetag.core.testing.scheduleSeasonBindingOf
import com.loosecannon.servicetag.core.testing.tombstoneOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * #113/#114 (C26, G1, G9, K6; row 86) — the C2 cross-check on exact ids, both ways, on the one connection: an inbound
 * #16 link or entity edit refuses a helper this phone writes — any publish binding's target, enabled or not, or a
 * PENDING tombstone's — and Enable or Change target refuses a helper an inbound binding reads or a date binding reads
 * as its season-ended helper. The ids are fictional; a shared domain is chosen so the cross-check, not the domain rule,
 * is what refuses.
 *
 * #125 (C26's schedule half; row h14): both ways for a schedule's own link too. A schedule link takes `input_boolean`
 * ids only, so a shared domain is reached by a stored row, as a hand-built archive or a later domain could leave it.
 */
class CrossCheckTest {

    private val DEHUMIDIFIER = "dehumidifier"

    private suspend fun linkRefused(p: PublishHarness, entityId: String, assetId: String = HEATER) {
        val thrown = assertFailsWith<SeasonSyncLinkRefused> { p.link.run(AssetId(assetId), entityId) }
        assertEquals(SeasonSyncLinkRefusal.OUTBOUND_TARGET, thrown.reason)
    }

    @Test
    fun linkAndEditRefuseAnOutboundTarget() = runTest {
        val p = PublishHarness()
        p.asset(HEATER, SeasonMode.MANUAL)
        p.asset(BOILER)
        p.asset(DEHUMIDIFIER)
        p.connect()
        p.enableStatus(BOILER, STATUS)
        p.enableStatus(DEHUMIDIFIER, STATUS_2)
        p.disable.run(AssetId(DEHUMIDIFIER), PublishKind.EQUIPMENT_STATUS)

        linkRefused(p, STATUS)
        linkRefused(p, STATUS_2) // a disabled binding's target is still one this phone writes
        assertNull(p.h.raw.seasonSyncBindings.rows[HEATER])

        p.link.run(AssetId(HEATER), IN_SEASON)
        val thrown = assertFailsWith<SeasonSyncLinkRefused> { p.edit.run(AssetId(HEATER), STATUS_2) }
        assertEquals(SeasonSyncLinkRefusal.OUTBOUND_TARGET, thrown.reason)
        assertEquals(IN_SEASON, p.h.raw.seasonSyncBindings.rows.getValue(HEATER).entityId)
    }

    @Test
    fun enableAndChangeTargetRefuseAnInboundSource() = runTest {
        val p = PublishHarness()
        p.asset(HEATER, SeasonMode.MANUAL)
        p.asset(BOILER)
        p.connect()
        p.link.run(AssetId(HEATER), STATUS) // an inbound helper of the outbound domain, so only C26 can refuse it

        val enabled = assertFailsWith<PublishRefused> { p.enableStatus(BOILER, STATUS) }
        assertEquals(PublishRefusal.INBOUND_SOURCE, enabled.reason)
        p.enableStatus(BOILER, STATUS_2)
        val changed = assertFailsWith<PublishRefused> {
            p.change.run(AssetId(BOILER), PublishKind.EQUIPMENT_STATUS, STATUS, p.read(STATUS, BOILER), null, INSTALLATION)
        }
        assertEquals(PublishRefusal.INBOUND_SOURCE, changed.reason)

        // A date binding's season-ended helper is read, so it is never another binding's target.
        val q = PublishHarness()
        q.asset(HEATER, SeasonMode.MANUAL)
        q.asset(BOILER)
        val connection = q.connect()
        q.h.link(HEATER) { it.copy(connectionId = connection.id, entityId = IN_SEASON) }
        q.enableDate(HEATER, DATE, boundary = SEASON_ENDED)
        val read = assertFailsWith<PublishRefused> { q.enableDate(BOILER, SEASON_ENDED) }
        assertEquals(PublishRefusal.INBOUND_SOURCE, read.reason)
    }

    @Test
    fun aPendingTombstonesEntityIsOutboundToo() = runTest {
        val p = PublishHarness()
        p.asset(HEATER, SeasonMode.MANUAL)
        val connection = p.connect()
        p.tombstones.upsertMerging(tombstoneOf("t-1", DATE, connection.id, assetId = BOILER))
        linkRefused(p, DATE)

        // An UNDELIVERABLE obligation is no longer written by this phone: the owner clears it in Home Assistant.
        p.tombstones.rows.clear()
        p.tombstones.upsertMerging(
            tombstoneOf("t-2", DATE, connection.id, assetId = BOILER, state = DecommissionState.UNDELIVERABLE),
        )
        p.link.run(AssetId(HEATER), DATE)
        assertEquals(DATE, p.h.raw.seasonSyncBindings.rows.getValue(HEATER).entityId)
    }

    // ---- #125 (row h14) — a schedule's own link, both ways ------------------------------------------------------

    @Test
    fun aScheduleBindingsEntityIsRefusedAsAPublishTarget() = runTest {
        val p = PublishHarness()
        p.asset(HEATER, SeasonMode.MANUAL)
        p.asset(BOILER)
        val connection = p.connect()
        val scheduleSeasons = InMemoryScheduleSeasonBindingRepository()
        scheduleSeasons.insert(scheduleSeasonBindingOf("s-heater", connectionId = connection.id, entityId = STATUS))
        val enable = EnablePublishing(
            p.bindings, p.deliveries, p.tombstones, p.connections, p.h.bindings, p.h.assets, p.h.raw.transfers, p.uow,
            p.h.ids, p.h.clock, scheduleSeasons,
        )
        val change = ChangePublishTarget(
            p.bindings, p.deliveries, p.tombstones, p.connections, p.h.bindings, p.h.assets, p.h.raw.transfers, p.uow,
            p.h.ids, p.h.clock, scheduleSeasons,
        )

        val enabled = assertFailsWith<PublishRefused> {
            enable.run(AssetId(BOILER), PublishKind.EQUIPMENT_STATUS, STATUS, p.read(STATUS, BOILER), null, INSTALLATION)
        }
        assertEquals(PublishRefusal.INBOUND_SOURCE, enabled.reason)
        enable.run(AssetId(BOILER), PublishKind.EQUIPMENT_STATUS, STATUS_2, p.read(STATUS_2, BOILER), null, INSTALLATION)
        val changed = assertFailsWith<PublishRefused> {
            change.run(AssetId(BOILER), PublishKind.EQUIPMENT_STATUS, STATUS, p.read(STATUS, BOILER), null, INSTALLATION)
        }
        assertEquals(PublishRefusal.INBOUND_SOURCE, changed.reason)
    }

    @Test
    fun aScheduleLinkRefusesAHelperThisPhoneWrites() = runTest {
        val p = PublishHarness()
        p.asset(HEATER, SeasonMode.CALENDAR)
        val connection = p.connect()
        p.h.raw.schedules.rows["s-heater"] = scheduleOf("s-heater", assetId = HEATER, title = "Example winterize")
        val scheduleSeasons = InMemoryScheduleSeasonBindingRepository()
        val scheduler = object : SeasonSyncScheduler {
            override suspend fun ensure() = Unit

            override suspend fun cancel() = Unit

            override suspend fun requestFreshRead(assetId: AssetId) = Unit
        }
        val link = LinkScheduleSeason(
            p.h.schedules, p.h.assets, p.h.raw.transfers, scheduleSeasons, p.connections, p.secrets, p.h.recompute,
            scheduler, WithStores(p.uow, scheduleSeasons), p.h.clock, p.outbound,
        )
        val owed = "input_boolean.example_heater_winterize_season"
        p.tombstones.upsertMerging(tombstoneOf("t-1", owed, connection.id, assetId = BOILER))

        val refused = assertFailsWith<ScheduleSeasonRefused> { link.run(ScheduleId("s-heater"), owed) }

        assertEquals(ScheduleSeasonRefusal.OUTBOUND_TARGET, refused.reason)
        assertNull(scheduleSeasons.rows["s-heater"])
        assertEquals(ScheduleSeasonSource.ASSET, p.h.raw.schedules.rows.getValue("s-heater").seasonSource)
    }
}
