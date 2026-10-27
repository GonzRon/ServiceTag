package com.loosecannon.servicetag.core.hapublish

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.seasonsync.EditSeasonSyncEntity
import com.loosecannon.servicetag.core.seasonsync.ForgetHaConnection
import com.loosecannon.servicetag.core.seasonsync.HaConnection
import com.loosecannon.servicetag.core.seasonsync.LinkSeasonSync
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.core.seasonsync.SaveHaConnection
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncGuard
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncHarness
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncScheduler
import com.loosecannon.servicetag.core.seasonsync.Secret
import com.loosecannon.servicetag.core.testing.DeliveryPass
import com.loosecannon.servicetag.core.testing.InMemoryHaPublish
import com.loosecannon.servicetag.core.testing.InMemorySecretStore
import com.loosecannon.servicetag.core.testing.ScriptedPublishTransport
import com.loosecannon.servicetag.core.testing.WithStores
import com.loosecannon.servicetag.core.usecase.SetSeasonMode

/**
 * #113/#114 (B3) — one installation for the publishing commands: #16's harness (its stores, clock and day), the three
 * publishing tables in the same unit of work, the four #16 commands with the publishing side passed (as production
 * does), the publishing commands, a Home Assistant of helpers ([transport]) and one sender pass over the ports
 * ([pass]). Every name, id and address is fictional.
 */
internal class PublishHarness(today: String = "2026-06-10") {
    val h = SeasonSyncHarness(today)
    val publish = InMemoryHaPublish()
    val bindings = publish.bindings
    val deliveries = publish.deliveries
    val tombstones = publish.tombstones
    val uow = WithStores(h.raw.uow, *publish.stores)
    val secrets = InMemorySecretStore()
    val connections = h.raw.haConnections.also { it.cascadesTo(publish.bindings::cascadeFromConnection) }
    val outbound = PublishOutboundTargets(bindings, deliveries, tombstones, h.ids, h.clock)

    private val scheduler = object : SeasonSyncScheduler {
        override suspend fun ensure() = Unit

        override suspend fun cancel() = Unit

        override suspend fun requestFreshRead(assetId: AssetId) = Unit
    }
    private val setSeasonMode = SetSeasonMode(
        h.assets, h.schedules, h.activations, uow, h.ids, h.clock, h.todayPort, h.recompute,
        SeasonSyncGuard(h.raw.seasonSyncBindings),
    )
    val link = LinkSeasonSync(
        h.assets, h.activations, h.raw.transfers, h.bindings, connections, secrets, setSeasonMode, scheduler, uow,
        h.clock, h.todayPort, outbound,
    )
    val edit = EditSeasonSyncEntity(h.bindings, scheduler, uow, h.clock, outbound)
    val save = SaveHaConnection(connections, h.bindings, secrets, scheduler, uow, h.ids, h.clock, outbound)
    val forget = ForgetHaConnection(connections, secrets, scheduler, uow, outbound)

    val enable = EnablePublishing(
        bindings, deliveries, tombstones, connections, h.bindings, h.assets, h.raw.transfers, uow, h.ids, h.clock,
    )
    val change = ChangePublishTarget(
        bindings, deliveries, tombstones, connections, h.bindings, h.assets, h.raw.transfers, uow, h.ids, h.clock,
    )
    val disable = DisablePublishing(bindings, tombstones, uow, h.ids, h.clock)
    val revalidate = RevalidatePublishing(bindings, deliveries, connections, uow, h.clock)
    val dismiss = DismissDecommission(tombstones, uow)

    val transport = ScriptedPublishTransport()

    /** The running installation's id; a test changes it to model a copied phone. */
    var installation = INSTALLATION
    val pass = DeliveryPass(
        bindings, deliveries, tombstones, connections, secrets, h.assets, h.raw.transfers, transport,
        { installation }, { h.now },
    )

    init {
        transport.statusHelper(STATUS)
        transport.statusHelper(STATUS_2)
        transport.dateHelper(DATE)
        transport.dateHelper(DATE_2)
        transport.dateHelper(SEASON_ENDED, state = "2026-05-01")
    }

    /** The fictional connection as the owner saves it, with a token. */
    suspend fun connect(baseUrl: String = "http://192.168.0.10:8123", token: Secret? = Secret("fictional-token-1")): HaConnection =
        save.run(baseUrl, token, null, NetworkEligibility.HOME_NETWORK_ONLY, "ExampleHomeWifi", null)

    fun asset(id: String = HEATER, mode: SeasonMode = SeasonMode.CALENDAR) = h.asset(id, mode)

    /** What the runner's `validate` answers for [entityId] now: one read, with the stored provenance (C30). */
    suspend fun read(entityId: String, assetId: String = HEATER, kind: PublishKind = kindOf(entityId)): ValidationResult {
        val connection = checkNotNull(connections.get()) { "no connection" }
        val revision = bindingOf(assetId, kind)?.revision ?: 0
        val remote = (transport.read(entityId) as RemoteRead.Answered).remote
        return ValidationResult(entityId, remote, connection.id, connection.configGeneration, revision, h.now)
    }

    suspend fun enableStatus(assetId: String = HEATER, entityId: String = STATUS): PublishBinding =
        enable.run(AssetId(assetId), PublishKind.EQUIPMENT_STATUS, entityId, read(entityId, assetId), null, installation)

    suspend fun enableDate(assetId: String = HEATER, entityId: String = DATE, boundary: String? = null): PublishBinding =
        enable.run(
            AssetId(assetId), PublishKind.SEASON_SERVICE_DATE, entityId, read(entityId, assetId),
            boundary?.let { read(it, assetId, PublishKind.SEASON_SERVICE_DATE) }, installation,
        )

    suspend fun bindingOf(assetId: String = HEATER, kind: PublishKind): PublishBinding? =
        bindings.forAsset(AssetId(assetId)).firstOrNull { it.kind == kind }

    /** The projector's write (C31), as B4b1 will make it. */
    suspend fun project(binding: PublishBinding, value: String?) {
        val d = checkNotNull(deliveries.get(binding.id))
        check(deliveries.writeDesired(binding.id, binding.revision, d.desiredRevision, value, h.now))
    }

    /** Moves the clock by [millis]. */
    fun later(millis: Long) {
        h.now += millis
    }

    companion object {
        const val HEATER = SeasonSyncHarness.HEATER
        const val BOILER = "boiler"
        const val INSTALLATION = "installation-a"
        const val STATUS = "input_select.example_heater_status"
        const val STATUS_2 = "input_select.example_heater_status_2"
        const val DATE = "input_datetime.example_heater_service_done"
        const val DATE_2 = "input_datetime.example_heater_service_done_2"
        const val SEASON_ENDED = "input_datetime.example_heater_season_ended"
        const val IN_SEASON = "input_boolean.example_heater_in_season"

        fun kindOf(entityId: String): PublishKind =
            if (entityId.startsWith("input_select.")) PublishKind.EQUIPMENT_STATUS else PublishKind.SEASON_SERVICE_DATE
    }
}
