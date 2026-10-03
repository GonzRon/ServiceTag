package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.seasonsync.BackgroundChecks
import com.loosecannon.servicetag.core.seasonsync.HaReadOutcome
import com.loosecannon.servicetag.core.seasonsync.HaSwitchState
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.core.seasonsync.Secret
import com.loosecannon.servicetag.core.seasonsync.SyncCadence
import com.loosecannon.servicetag.core.seasonsync.SyncErrorKind
import com.loosecannon.servicetag.seasonsync.JdkAead
import com.loosecannon.servicetag.seasonsync.KeyedAead
import com.loosecannon.servicetag.seasonsync.KeystoreSecretStore
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.dayMillis
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyStoreException
import java.time.LocalDate

/**
 * #16 (C3, C24; R16-9) — `GET /v1/assets/{id}/season-sync`, the twenty-eighth asset sub-resource, over the production
 * router and `FakeGraph`, on the JVM: the binding's non-secret state and status, the connection's settings, typed
 * 404s, and nothing secret. Bodies are read as JSON trees, so an absent key and a `null` are told apart. Every value
 * is fictional: the asset, the entity `input_boolean.example_heater_in_season`, the addresses
 * `http://192.168.0.10:8123` and `https://ha.example:8123`, the Wi-Fi name `ExampleHomeWifi` and the token
 * `fictional-token-1`.
 */
class SeasonSyncRoutesTest {

    private val graph = FakeGraph().apply {
        now = dayMillis("2026-02-10")
        today = LocalDate.parse("2026-02-10")
    }
    private val api = V1Client(graph)

    @After fun close() = graph.close()

    // --- fixtures -------------------------------------------------------------------------------

    private fun call(method: String, path: String, body: String = "") = api.call(method, path, body)

    private fun seasonSync(asset: String): JsonObject {
        val response = call("GET", "/v1/assets/$asset/season-sync")
        assertEquals(response.bodyText(), 200, response.status)
        return Json.parseToJsonElement(response.bodyText()).jsonObject
    }

    private fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.flag(key: String): Boolean = getValue(key).jsonPrimitive.boolean

    private fun JsonObject.millis(key: String): Long = getValue(key).jsonPrimitive.long

    private fun JsonObject.isNull(key: String): Boolean = getValue(key) == JsonNull

    private fun connect(
        address: String = "http://192.168.0.10:8123",
        eligibility: NetworkEligibility = NetworkEligibility.HOME_NETWORK_ONLY,
        wifi: String? = "ExampleHomeWifi",
        cadence: SyncCadence? = null,
        background: BackgroundChecks? = null,
    ) = runBlocking {
        graph.saveHaConnection.run(address, Secret("fictional-token-1"), cadence, eligibility, wifi, background)
    }

    /** Links [asset] to the fictional entity and waits for the fresh read the link asks for. */
    private fun link(asset: String) = runBlocking {
        graph.linkSeasonSync.run(AssetId(asset), "input_boolean.example_heater_in_season")
        graph.awaitSeasonSyncReads()
    }

    private fun manualOutOfSeason(asset: String) {
        val response = call("POST", "/v1/assets/$asset/season-mode", """{"seasonMode":"MANUAL","manualPhase":"OUT_OF_SEASON"}""")
        assertEquals(response.bodyText(), 200, response.status)
    }

    // --- row 61 ---------------------------------------------------------------------------------

    /**
     * Every key of C3 on a linked asset that followed Home Assistant into season, then the same binding after a
     * failed check: the three times stay three fields — the failure moves the attempt and records the error with
     * its kind and detail, and the last success stays where it was. Neither the address nor the token is a byte of
     * either body.
     */
    @Test fun aLinkedAssetReadsC3WithNoAddressAndNoToken() {
        val heater = api.asset("Example Heater")
        manualOutOfSeason(heater)
        connect()
        graph.haStateReader.answer = { HaReadOutcome.Observed(HaSwitchState.ON, "2026-02-10T06:30:00+00:00") }
        val linkedAt = graph.now
        link(heater)

        val response = call("GET", "/v1/assets/$heater/season-sync")
        val body = response.bodyText()
        assertEquals(body, 200, response.status)
        assertFalse("the address is never on the wire: $body", "192.168.0.10" in body)
        assertFalse("the token is never on the wire: $body", "fictional-token-1" in body)
        assertFalse("the home Wi-Fi's name is never on the wire: $body", "ExampleHomeWifi" in body)

        val tree = Json.parseToJsonElement(body).jsonObject
        assertEquals(setOf("assetId", "connection", "binding"), tree.keys)
        assertEquals(heater, tree.text("assetId"))
        val connection = tree.obj("connection")
        assertEquals(
            setOf(
                "configured", "needsToken", "cadence", "networkEligibility", "backgroundChecks", "backgroundAllowed",
                "homeNetworkSet",
            ),
            connection.keys,
        )
        assertTrue(connection.flag("configured"))
        assertFalse(connection.flag("needsToken"))

        val binding = tree.obj("binding")
        assertEquals(
            setOf(
                "entityId", "mode", "enabled", "state", "observation", "lastSuccessAt", "lastAttemptAt", "lastError",
                "lastApplied",
            ),
            binding.keys,
        )
        assertEquals("input_boolean.example_heater_in_season", binding.text("entityId"))
        assertEquals("FOLLOW", binding.text("mode"))
        assertTrue(binding.flag("enabled"))
        assertEquals("ACTIVE", binding.text("state"))
        val observation = binding.obj("observation")
        assertEquals(setOf("state", "haLastChanged"), observation.keys)
        assertEquals("ON", observation.text("state"))
        assertEquals("2026-02-10T06:30:00+00:00", observation.text("haLastChanged"))
        assertEquals(linkedAt, binding.millis("lastSuccessAt"))
        assertEquals(linkedAt, binding.millis("lastAttemptAt"))
        assertTrue(binding.isNull("lastError"))
        val applied = binding.obj("lastApplied")
        assertEquals(setOf("action", "occurredOn", "at"), applied.keys)
        assertEquals("START", applied.text("action"))
        assertEquals("2026-02-10", applied.text("occurredOn"))
        assertEquals(linkedAt, applied.millis("at"))

        graph.now = linkedAt + 60_000
        graph.haStateReader.answer = { HaReadOutcome.NoDecision(SyncErrorKind.HTTP_ERROR, "503") }
        runBlocking { graph.seasonSyncRunner.syncNow(AssetId(heater)) }

        val failed = seasonSync(heater).obj("binding")
        assertEquals("a failure never moves the last success", linkedAt, failed.millis("lastSuccessAt"))
        assertEquals(linkedAt + 60_000, failed.millis("lastAttemptAt"))
        val error = failed.obj("lastError")
        assertEquals(setOf("kind", "detail", "at"), error.keys)
        assertEquals("HTTP_ERROR", error.text("kind"))
        assertEquals("503", error.text("detail"))
        assertEquals(linkedAt + 60_000, error.millis("at"))
        assertEquals("the last change stays the binding's provenance", applied, failed.obj("lastApplied"))

        graph.now = linkedAt + 120_000
        graph.haStateReader.answer = { HaReadOutcome.Observed(HaSwitchState.ON, "2026-02-10T06:30:00+00:00") }
        runBlocking { graph.seasonSyncRunner.syncNow(AssetId(heater)) }
        val fresh = seasonSync(heater).obj("binding")
        assertEquals("an unchanged answer is a fresh success", linkedAt + 120_000, fresh.millis("lastSuccessAt"))
        assertEquals("no new change, so the applied time stays", linkedAt, fresh.obj("lastApplied").millis("at"))
        assertTrue("a success clears the error", fresh.isNull("lastError"))
    }

    /** No binding is a 200 with `binding: null`, never a 404 — with no connection, and with one. */
    @Test fun anUnlinkedAssetReadsBindingNull() {
        val heater = api.asset("Example Heater")

        val bare = seasonSync(heater)
        assertEquals(heater, bare.text("assetId"))
        assertTrue(bare.isNull("binding"))
        val none = bare.obj("connection")
        assertEquals("no connection: no settings either", setOf("configured", "needsToken"), none.keys)
        assertFalse(none.flag("configured"))
        assertFalse(none.flag("needsToken"))

        connect()
        val connected = seasonSync(heater)
        assertTrue(connected.isNull("binding"))
        assertTrue(connected.obj("connection").flag("configured"))
    }

    /** The shipped 404 `no_such_asset`, before anything else is asked. */
    @Test fun anUnknownAssetIs404() {
        connect()
        val response = call("GET", "/v1/assets/00000000-0000-4000-8000-999999999999/season-sync")
        assertEquals(response.bodyText(), 404, response.status)
        assertEquals("no_such_asset", response.errorDetail().code)
    }

    /** Read only: every other verb falls to the sub-resource convention's 404, and nothing is written. */
    @Test fun postIs404NotA405() {
        val heater = api.asset("Example Heater")
        connect()
        link(heater)
        val before = runBlocking { graph.seasonSyncBindings.get(AssetId(heater)) }
        for (method in listOf("POST", "PATCH", "DELETE")) {
            val response = call(method, "/v1/assets/$heater/season-sync", if (method == "DELETE") "" else "{}")
            assertEquals("$method: ${response.bodyText()}", 404, response.status)
            assertEquals(method, "not_found", response.errorDetail().code)
        }
        assertEquals(before, runBlocking { graph.seasonSyncBindings.get(AssetId(heater)) })
    }

    /**
     * The derived state, in C17's order: a stopped binding reads STOPPED and not enabled; an enabled one on an
     * archived asset reads NOT_MAINTAINED_HERE; and with the token gone from this phone every binding reads
     * NEEDS_TOKEN, ahead of both, and the connection says so.
     */
    @Test fun needsTokenAndStoppedReadAsSuch() {
        val heater = api.asset("Example Heater")
        val boiler = api.asset("Example Boiler")
        connect()
        link(heater)
        link(boiler)
        runBlocking { graph.stopSeasonSync.run(AssetId(heater)) }
        val archived = call("POST", "/v1/assets/$boiler/archive", """{"archived":true}""")
        assertEquals(archived.bodyText(), 200, archived.status)

        val stopped = seasonSync(heater).obj("binding")
        assertEquals("STOPPED", stopped.text("state"))
        assertFalse(stopped.flag("enabled"))
        val elsewhere = seasonSync(boiler).obj("binding")
        assertEquals("NOT_MAINTAINED_HERE", elsewhere.text("state"))
        assertTrue(elsewhere.flag("enabled"))

        val connectionId = runBlocking { graph.haConnections.get()!!.id }
        runBlocking { graph.secretStore.delete(connectionId) }
        for (asset in listOf(heater, boiler)) {
            val tree = seasonSync(asset)
            assertEquals(asset, "NEEDS_TOKEN", tree.obj("binding").text("state"))
            assertTrue(asset, tree.obj("connection").flag("needsToken"))
            assertTrue(asset, tree.obj("connection").flag("configured"))
        }
    }

    /**
     * The settings are shown, non-secret, in their enum names; whether a home network is set is shown and its name
     * never is; and neither address — the home-network http one nor an https name — is a byte of the body.
     */
    @Test fun theFourSettingsAreShownTheHomeWifiNameIsNot() {
        val heater = api.asset("Example Heater")
        graph.backgroundAllowed = true
        connect(cadence = SyncCadence.WEEKLY, background = BackgroundChecks.ON)

        val home = seasonSync(heater).toString()
        assertFalse(home, "ExampleHomeWifi" in home)
        assertFalse(home, "192.168.0.10" in home)
        val atHome = Json.parseToJsonElement(home).jsonObject.obj("connection")
        assertEquals("WEEKLY", atHome.text("cadence"))
        assertEquals("HOME_NETWORK_ONLY", atHome.text("networkEligibility"))
        assertEquals("ON", atHome.text("backgroundChecks"))
        assertTrue(atHome.flag("backgroundAllowed"))
        assertTrue(atHome.flag("homeNetworkSet"))

        graph.backgroundAllowed = false
        connect(address = "https://ha.example:8123", eligibility = NetworkEligibility.ANY_NETWORK, wifi = null)
        val any = seasonSync(heater).toString()
        assertFalse(any, "ha.example" in any)
        val anywhere = Json.parseToJsonElement(any).jsonObject.obj("connection")
        assertEquals("a kept cadence", "WEEKLY", anywhere.text("cadence"))
        assertEquals("ANY_NETWORK", anywhere.text("networkEligibility"))
        assertEquals("ON", anywhere.text("backgroundChecks"))
        assertFalse(anywhere.flag("backgroundAllowed"))
        assertFalse(anywhere.flag("homeNetworkSet"))
    }

    /** B4's rule: a key store that cannot load answers the shipped 500 `internal`, never a guessed state. */
    @Test fun aKeyStoreThatCannotLoadIs500() {
        val aead = BreakableAead()
        val broken = FakeGraph(
            secretStore = KeystoreSecretStore(kotlin.io.path.createTempDirectory("no-backup").toFile(), aead),
        )
        try {
            val client = V1Client(broken)
            val heater = client.asset("Example Heater")
            runBlocking {
                broken.saveHaConnection.run(
                    "http://192.168.0.10:8123", Secret("fictional-token-1"), null,
                    NetworkEligibility.HOME_NETWORK_ONLY, "ExampleHomeWifi", null,
                )
            }
            aead.broken = true
            val response = client.call("GET", "/v1/assets/$heater/season-sync")
            assertEquals(response.bodyText(), 500, response.status)
            assertEquals("internal", response.errorDetail().code)
        } finally {
            broken.close()
        }
    }

    /** `docs/api/v1.md` documents the route once, its section, and the one new code in exactly one row. */
    @Test fun theDocumentNamesTheRouteAndTheCode() {
        val text = repoFile("docs/api/v1.md").readText()
        val lines = text.lines()
        assertEquals(1, lines.count { it.startsWith("| `GET` | `/v1/assets/{id}/season-sync` |") })
        assertEquals(1, lines.count { it == "### Home Assistant season sync (#16)" })
        assertEquals(1, lines.count { it.startsWith("| 409 | `SEASON_SYNC_ENABLED` |") })
        val section = text.substringAfter("### Home Assistant season sync (#16)").substringBefore("\n## ")
        assertTrue("the section says what is never on the wire", "no address, no token, no write" in section)
        assertTrue("the section names G1's sentence", G1 in text)
    }

    /** A JDK AEAD whose key store stops loading on demand, as a broken Android Keystore would. */
    private class BreakableAead(private val inner: JdkAead = JdkAead()) : KeyedAead by inner {
        @Volatile var broken = false

        override fun hasKey(alias: String): Boolean {
            if (broken) throw KeyStoreException("the fictional key store cannot load")
            return inner.hasKey(alias)
        }
    }

    private companion object {
        const val G1 = "this asset's season follows Home Assistant; stop its season sync on the phone first"
    }
}
