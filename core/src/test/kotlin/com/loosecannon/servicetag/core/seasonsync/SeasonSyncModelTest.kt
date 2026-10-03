package com.loosecannon.servicetag.core.seasonsync

import com.loosecannon.servicetag.core.testing.ScriptedHaStateReader
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #16 (C4; rows 7–8) — the entity id is one URL path segment, the token prints as nothing, and no model type holds
 * a token. Every value is fictional.
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

    /** Every field typed [Secret], and every getter or component that answers one, declared on [type]. */
    private fun secretMembersOf(type: Class<*>): List<String> =
        type.declaredFields.filter { it.type == Secret::class.java }.map { "${type.name}.${it.name}" } +
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

    @Test
    fun theBindingCarriesExactlyTheColumnsOfItsTable() {
        val columns = listOf(
            "asset_id", "connection_id", "entity_id", "mode", "enabled", "revision", "observed_state",
            "observed_changed_at", "last_success_at", "last_attempt_at", "error_kind", "error_detail", "error_at",
            "applied_action", "applied_on", "applied_at", "created_at", "updated_at",
        )
        val camel = columns.map { column -> column.split('_').let { it.first() + it.drop(1).joinToString("") { p -> p.replaceFirstChar(Char::uppercaseChar) } } }
        assertEquals(camel.sorted(), SeasonSyncBinding::class.java.declaredFields.map { it.name }.sorted(), "season_sync_binding (C9)")
        assertEquals(
            listOf("baseUrl", "createdAt", "id", "updatedAt"),
            HaConnection::class.java.declaredFields.map { it.name }.sorted(),
            "ha_connection (C9): no token, no display name, no secret marker",
        )
    }
}
