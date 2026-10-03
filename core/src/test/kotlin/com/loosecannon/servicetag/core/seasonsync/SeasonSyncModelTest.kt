package com.loosecannon.servicetag.core.seasonsync

import com.loosecannon.servicetag.core.testing.InMemoryHaConnectionRepository
import com.loosecannon.servicetag.core.testing.InMemorySeasonSyncRepository
import com.loosecannon.servicetag.core.testing.ScriptedHaStateReader
import com.loosecannon.servicetag.core.testing.haConnectionOf
import java.io.File
import java.lang.reflect.Modifier
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #16 (C4, C4a; rows 7, 8 and 73) — the entity id is one URL path segment, the token prints as nothing, no model
 * type holds a token, and the connection's settings are exactly the contract's. Every value is fictional.
 */
class SeasonSyncModelTest {

    // Row 7 — the entity id rule.

    @Test
    fun aLowercaseDomainDotObjectIdIsAccepted() {
        for (id in listOf(
            "input_boolean.example_heater_in_season",
            "switch.example_heater",
            "input_boolean.example_1",
            "a.b",
            "_._",
            "input_boolean." + "x".repeat(MAX_ENTITY_ID_LENGTH - "input_boolean.".length),
        )) {
            assertTrue(isValidEntityId(id), "\"$id\" (${id.length} characters)")
        }
    }

    @Test
    fun slashesDotsEscapesCapitalsAndEmptyPartsAreRefused() {
        for (id in listOf(
            "a/b.c",
            "input_boolean.a/b",
            "../x",
            "input_boolean.",
            ".example_heater",
            "input_boolean",
            "",
            "a.b.c",
            "Input_Boolean.x",
            "input_boolean.Example",
            "a.b%2e",
            "a b.c",
            "input-boolean.x",
            "input_boolean.x\n",
            "input_boolean.café",
            "input_boolean.x?y=1",
            "input_boolean.x#y",
            "input_boolean." + "x".repeat(MAX_ENTITY_ID_LENGTH + 1 - "input_boolean.".length),
        )) {
            assertFalse(isValidEntityId(id), "\"$id\" (${id.length} characters)")
        }
    }

    // Row 8 — no printable secret (I9).

    @Test
    fun secretsToStringIsConstant() {
        val secret = Secret("fictional-token-1")
        assertEquals("Secret(redacted)", secret.toString())
        assertEquals("Secret(redacted)", "$secret", "a string template")
        assertEquals("[Secret(redacted)]", listOf(secret).toString(), "boxed in a collection")
        val call = ScriptedHaStateReader.Call("http://192.168.0.10:8123", "input_boolean.example_heater_in_season", secret)
        assertFalse(call.toString().contains("fictional-token-1"), "inside a data class: $call")
    }

    /** A property of type [Secret] compiles to a getter whose JVM name carries the type's mangling suffix. */
    private class SecretProbe(val secret: Secret, val maybe: Secret?)

    private val secretSuffixes: Set<String> = SecretProbe::class.java.declaredMethods
        .filter { it.name.startsWith("getSecret-") || it.name.startsWith("getMaybe-") }
        .map { it.name.substringAfter('-') }
        .toSet()

    /**
     * Every getter or component declared on [type] that answers a [Secret]. Fields are not checked: a property typed
     * [Secret] or `Secret?` compiles to a field of the underlying `String`, so only its getter's name shows the type.
     */
    private fun secretMembersOf(type: Class<*>): List<String> =
        type.declaredMethods
            .filter { it.parameterCount == 0 && (it.name.startsWith("get") || it.name.startsWith("component")) }
            .filter { it.name.substringAfter('-', missingDelimiterValue = "") in secretSuffixes }
            .map { "${type.name}.${it.name}" }

    @Test
    fun noModelTypeHasASecretField() {
        assertEquals(2, secretSuffixes.size, "the probe's two getters carry Secret's and Secret?'s suffixes")
        assertEquals(2, secretMembersOf(SecretProbe::class.java).size, "the scan finds the probe's two properties")

        val root = File(Secret::class.java.protectionDomain.codeSource.location.toURI())
        val pkg = Secret::class.java.packageName
        val dir = File(root, pkg.replace('.', '/'))
        assertTrue(dir.isDirectory, "the main classes of $pkg are a directory under $root")
        val types = dir.listFiles { file -> file.name.endsWith(".class") }.orEmpty()
            .map { Class.forName("$pkg.${it.name.removeSuffix(".class")}", false, Secret::class.java.classLoader) }
        assertTrue(types.contains(SeasonSyncBinding::class.java) && types.contains(HaConnection::class.java), "$types")
        assertTrue(types.contains(HaReadOutcome.Observed::class.java), "nested types are scanned: $types")

        val offenders = types.filter { !it.isInterface && it != Secret::class.java }.flatMap(::secretMembersOf)
        assertEquals(emptyList(), offenders, "no model type of $pkg holds a Secret")
    }

    /** A `val` or `var` whose declared type names [Secret]: a property, a holder such as `List<Secret>`, or a local. */
    private val secretDeclaration = Regex("""\b(?:val|var)\s+\w+\s*:\s*[^=\n]*\bSecret\b""")

    /**
     * What the reflection guard cannot see — a `private val` typed [Secret] has no getter, and a `List<Secret>`'s
     * getter is not mangled — the main source shows: in `seasonsync` the token crosses core only as a function
     * parameter, never in a `val` or `var` whose declared type names it. An inferred type stays unseen.
     */
    @Test
    fun noMainSourceDeclaresAValOrVarOfASecretType() {
        for (line in listOf(
            "    private val token: Secret,",
            "val tokens: List<Secret> = listOf()",
            "var maybe: Secret?",
        )) {
            assertTrue(secretDeclaration.containsMatchIn(line), "the scan sees \"$line\"")
        }
        for (line in listOf(
            "    suspend fun put(key: String, secret: Secret)",
            "class SaveExample(private val store: SecretStore)",
            "value class Secret(val value: String) {",
        )) {
            assertFalse(secretDeclaration.containsMatchIn(line), "the scan passes \"$line\"")
        }

        val relative = "kotlin/com/loosecannon/servicetag/core/seasonsync"
        val dir = listOf(File("src/main/$relative"), File("core/src/main/$relative")).firstOrNull { it.isDirectory }
            ?: error("cannot find src/main/$relative from ${File(".").absolutePath}")
        val sources = dir.listFiles { file -> file.extension == "kt" }.orEmpty().sortedBy { it.name }
        assertTrue(sources.any { it.name == "SeasonSyncModel.kt" }, "the scan reads the model: $sources")
        val offenders = sources.flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> secretDeclaration.containsMatchIn(line) }
                .map { (index, line) -> "${file.name}:${index + 1}: ${line.trim()}" }
        }
        assertEquals(emptyList(), offenders, "no val or var in ${dir.path} is declared with a Secret type")
    }

    // The closed sets and the columns.

    @Test
    fun theSeventeenErrorKindsAreTheContractsOwn() {
        assertEquals(
            listOf(
                "ENDPOINT_REFUSED", "DENIED", "UNREACHABLE", "TIMED_OUT", "AUTH_REFUSED", "ENTITY_NOT_FOUND",
                "REDIRECTED", "HTTP_ERROR", "MALFORMED", "UNSUPPORTED_STATE", "NEEDS_TOKEN", "NOT_MAINTAINED_HERE",
                "NOT_MANUAL", "DATE_BEFORE_HISTORY", "NOT_ON_LOCAL_NETWORK", "NAME_NOT_LOCAL", "TLS_FAILED",
            ),
            SyncErrorKind.entries.map { it.name },
        )
        assertEquals(listOf("FOLLOW", "FORCE_IN", "FORCE_OUT"), SyncMode.entries.map { it.name })
        assertEquals(listOf("ON", "OFF"), HaSwitchState.entries.map { it.name })
    }

    /** The instance fields [type] declares, sorted: a companion's or a data object's static field is not a column. */
    private fun instanceFieldsOf(type: Class<*>): List<String> =
        type.declaredFields.filter { !Modifier.isStatic(it.modifiers) }.map { it.name }.sorted()

    /** A column's Kotlin name: `snake_case` to `camelCase`. */
    private fun camel(column: String): String {
        val parts = column.split('_')
        return parts.first() + parts.drop(1).joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
    }

    @Test
    fun theBindingCarriesExactlyTheColumnsOfItsTable() {
        val columns = listOf(
            "asset_id", "connection_id", "entity_id", "mode", "enabled", "revision", "observed_state",
            "observed_changed_at", "last_success_at", "last_attempt_at", "error_kind", "error_detail", "error_at",
            "applied_action", "applied_on", "applied_at", "created_at", "updated_at",
        )
        assertEquals(
            columns.map(::camel).sorted(),
            instanceFieldsOf(SeasonSyncBinding::class.java),
            "season_sync_binding (C9)",
        )

        val connectionColumns = listOf(
            "id", "base_url", "cadence", "network_eligibility", "home_network_ssid", "background_checks", "created_at",
            "updated_at",
        )
        assertEquals(
            connectionColumns.map(::camel).sorted(),
            instanceFieldsOf(HaConnection::class.java),
            "ha_connection (C9): its eight columns; no token, no display name, no secret marker",
        )
    }

    // Row 73 — the connection's settings (C4a).

    @Test
    fun theCadenceIsExactlyFourWithTheirHours() {
        assertEquals(
            listOf("EVERY_12_HOURS" to 12L, "DAILY" to 24L, "WEEKLY" to 168L, "MONTHLY" to 720L),
            SyncCadence.entries.map { it.name to it.hours },
        )
        assertEquals(SyncCadence.DAILY, haConnectionOf().cadence, "the fixture checks daily, as a new connection does")
    }

    @Test
    fun backgroundChecksAreOffOrOnAndOffByDefault() = runTest {
        assertEquals(listOf("OFF", "ON"), BackgroundChecks.entries.map { it.name })
        assertEquals(BackgroundChecks.OFF, haConnectionOf().backgroundChecks, "the fixture's is a new connection's")

        val connections = InMemoryHaConnectionRepository(InMemorySeasonSyncRepository())
        val anyNetworkOn = haConnectionOf(
            baseUrl = "https://ha.example:8123",
            networkEligibility = NetworkEligibility.ANY_NETWORK,
            backgroundChecks = BackgroundChecks.ON,
        )
        connections.upsert(anyNetworkOn)
        assertEquals(anyNetworkOn, connections.get(), "stored whatever the eligibility")
    }

    @Test
    fun theEligibilityAndTheCurrentNetworkAreClosedSets() {
        assertEquals(listOf("ANY_NETWORK", "HOME_NETWORK_ONLY"), NetworkEligibility.entries.map { it.name })
        assertEquals(
            listOf("None", "Other", "Wifi", "WifiUnnamed", "Wired"),
            CurrentNetwork::class.java.declaredClasses.map { it.simpleName }.sorted(),
        )
        assertEquals(
            listOf("ssid"),
            instanceFieldsOf(CurrentNetwork.Wifi::class.java),
            "a name: never a BSSID, an address, a location or a time",
        )
    }

    @Test
    fun eligibleNowFollowsTheMode() {
        val homeWifi = CurrentNetwork.Wifi("ExampleHomeWifi")
        val everyNetwork = listOf(
            homeWifi,
            CurrentNetwork.Wifi("ExampleCafeWifi"),
            CurrentNetwork.Wifi("examplehomewifi"),
            CurrentNetwork.Wifi("\"ExampleHomeWifi\""),
            CurrentNetwork.WifiUnnamed,
            CurrentNetwork.Wired,
            CurrentNetwork.Other,
            CurrentNetwork.None,
        )
        val anyNetwork =
            haConnectionOf(baseUrl = "https://ha.example:8123", networkEligibility = NetworkEligibility.ANY_NETWORK)
        val onHomeWifi = haConnectionOf()
        val nothingCaptured = haConnectionOf(homeNetworkSsid = null)

        val expected = everyNetwork.map { Triple("any network", anyNetwork, it) to true } +
            everyNetwork.map { Triple("the home Wi-Fi", onHomeWifi, it) to (it == homeWifi) } +
            everyNetwork.map { Triple("nothing captured", nothingCaptured, it) to false }
        val wrong = expected
            .filter { (case, eligible) -> eligibleNow(case.second, case.third) != eligible }
            .map { (case, eligible) -> "${case.first} × ${case.third}: expected $eligible" }
        assertEquals(emptyList(), wrong, "only the captured name, exactly, is home; a hidden name or wired never is")
    }

    @Test
    fun theDoubleRefusesHomeWithoutANetworkAndHttpWithAnyNetwork() = runTest {
        val connections = InMemoryHaConnectionRepository(InMemorySeasonSyncRepository())
        val https = "https://ha.example:8123"
        val refused = listOf(
            "the home network with nothing captured" to haConnectionOf(homeNetworkSsid = null),
            "any network with a captured network" to haConnectionOf(
                baseUrl = https,
                networkEligibility = NetworkEligibility.ANY_NETWORK,
                homeNetworkSsid = "ExampleHomeWifi",
            ),
            "http with any network" to haConnectionOf(networkEligibility = NetworkEligibility.ANY_NETWORK),
        )
        for ((why, connection) in refused) {
            assertFailsWith<AssertionError>(why) { connections.upsert(connection) }
        }
        assertEquals(null, connections.get(), "nothing refused was kept")

        for (connection in listOf(
            haConnectionOf(),
            haConnectionOf(baseUrl = https),
            haConnectionOf(baseUrl = https, networkEligibility = NetworkEligibility.ANY_NETWORK),
        )) {
            connections.upsert(connection)
            assertEquals(connection, connections.get())
        }
    }
}
