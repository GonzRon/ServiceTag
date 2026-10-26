package com.loosecannon.servicetag.core.hapublish

import com.loosecannon.servicetag.core.hapublish.PublishHarness.Companion.BOILER
import com.loosecannon.servicetag.core.hapublish.PublishHarness.Companion.HEATER
import com.loosecannon.servicetag.core.hapublish.PublishHarness.Companion.STATUS
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.seasonsync.EditSeasonSyncEntity
import com.loosecannon.servicetag.core.seasonsync.ForgetHaConnection
import com.loosecannon.servicetag.core.seasonsync.LinkSeasonSync
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.core.seasonsync.OutboundTargets
import com.loosecannon.servicetag.core.seasonsync.SaveHaConnection
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncGuard
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncScheduler
import com.loosecannon.servicetag.core.seasonsync.Secret
import com.loosecannon.servicetag.core.testing.InMemorySecretStore
import com.loosecannon.servicetag.core.usecase.SetSeasonMode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * #113/#114 (F-6, G3; row 95) — the four #16 commands take the publishing side as a trailing parameter defaulted to
 * [OutboundTargets.NONE]: their shipped positional constructions compile unchanged, and the default refuses nothing,
 * writes nothing and moves nothing. R6's grep is the guard that production never uses it.
 */
class DefaultedCollaboratorTest {

    private val scheduler = object : SeasonSyncScheduler {
        override suspend fun ensure() = Unit

        override suspend fun cancel() = Unit

        override suspend fun requestFreshRead(assetId: AssetId) = Unit
    }

    @Test
    fun theFourCommandsCompileWithTheirShippedPositionalArguments() = runTest {
        val p = PublishHarness()
        val h = p.h
        val uow = h.raw.uow
        val secrets = InMemorySecretStore()
        val setSeasonMode = SetSeasonMode(
            h.assets, h.schedules, h.activations, uow, h.ids, h.clock, h.todayPort, h.recompute,
            SeasonSyncGuard(h.raw.seasonSyncBindings),
        )
        // The shipped argument lists, as `CT/seasonsync/LinkSeasonSyncTest.kt` builds them.
        val link = LinkSeasonSync(
            h.assets, h.activations, h.raw.transfers, h.bindings, h.raw.haConnections, secrets, setSeasonMode, scheduler,
            uow, h.clock, h.todayPort,
        )
        val edit = EditSeasonSyncEntity(h.bindings, scheduler, uow, h.clock)
        val save = SaveHaConnection(h.raw.haConnections, h.bindings, secrets, scheduler, uow, h.ids, h.clock)
        val forget = ForgetHaConnection(h.raw.haConnections, secrets, scheduler, uow)

        // With the default, an id the publishing side writes is linked: nothing outbound is consulted.
        p.asset(HEATER, SeasonMode.MANUAL)
        p.asset(BOILER)
        p.connect()
        p.enableStatus(BOILER, STATUS)
        save.run(
            "http://192.168.0.10:8123", Secret("fictional-token-1"), null,
            NetworkEligibility.HOME_NETWORK_ONLY, "ExampleHomeWifi", null,
        )
        link.run(AssetId(HEATER), "input_boolean.example_heater_in_season")
        edit.run(AssetId(HEATER), STATUS)
        assertEquals(STATUS, h.raw.seasonSyncBindings.rows.getValue(HEATER).entityId)
        forget.run()
    }

    @Test
    fun theDefaultRefusesNothing() = runTest {
        val none = OutboundTargets.NONE
        assertFalse(none.writes("conn-1", STATUS))
        assertFalse(none.blocksAddressChange("conn-1"))
        none.reauthorize("conn-1", addressChanged = true)
        none.forget("conn-1")
    }
}
