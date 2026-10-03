package com.loosecannon.servicetag.core.seasonsync

import com.loosecannon.servicetag.core.seasonsync.HaReadOutcome.NoDecision
import com.loosecannon.servicetag.core.seasonsync.HaReadOutcome.Observed
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #16 (C5; rows 1–5) — HA's answer becomes a decision only for exactly `on` or `off`; every other answer is a status
 * kind and no decision (I3). Every value is fictional.
 */
class HaStateMapperTest {

    private val entity = "input_boolean.example_heater_in_season"
    private val changed = "2026-10-01T06:00:00.000000+00:00"

    private fun q(text: String): String = JsonPrimitive(text).toString()

    /** HA's shape for `GET /api/states/<entity_id>`, with [stateJson] written as raw JSON. */
    private fun bodyWith(stateJson: String, entityId: String = entity, lastChangedJson: String? = q(changed)): String =
        buildString {
            append("""{"entity_id":${q(entityId)},"state":$stateJson,""")
            if (lastChangedJson != null) append("\"last_changed\":$lastChangedJson,")
            append("\"attributes\":{\"friendly_name\":\"Example heater in season\"}}")
        }

    private fun stateBody(state: String, lastChanged: String? = changed): String =
        bodyWith(q(state), lastChangedJson = lastChanged?.let(::q))

    private fun ok(body: String, contentType: String? = "application/json", truncated: Boolean = false) =
        HaHttpAnswer(200, contentType, body.encodeToByteArray(), truncated)

    private fun status(code: Int) = HaHttpAnswer(code, "text/plain", "refused".encodeToByteArray(), false)

    private fun map(answer: HaHttpAnswer): HaReadOutcome = mapHaAnswer(answer, entity)

    private val malformed = NoDecision(SyncErrorKind.MALFORMED, null)

    // Row 1 — exact on/off (AC1, I3).

    @Test
    fun onAndOffExactlyAreObservations() {
        assertEquals(Observed(HaSwitchState.ON, changed), map(ok(stateBody("on"))))
        assertEquals(Observed(HaSwitchState.OFF, changed), map(ok(stateBody("off"))))
        assertEquals(Observed(HaSwitchState.ON, null), map(ok(stateBody("on", lastChanged = null))), "no last_changed")
    }

    @Test
    fun caseVariantsAndSynonymsAreUnsupportedNamingTheState() {
        for (state in listOf("On", "ON", " on", "on ", "Off", "OFF", "off\n", "true", "1", "yes")) {
            assertEquals(
                NoDecision(SyncErrorKind.UNSUPPORTED_STATE, state.replace('\n', '?')),
                map(ok(stateBody(state))),
                "\"$state\" is not exactly on or off",
            )
        }
    }

    @Test
    fun unknownUnavailableAndEmptyAreUnsupported() {
        for (state in listOf("unknown", "unavailable", "")) {
            assertEquals(NoDecision(SyncErrorKind.UNSUPPORTED_STATE, state), map(ok(stateBody(state))), "\"$state\"")
        }
    }

    // Row 2 — the statuses (AC5).

    @Test
    fun statuses401And403AreAuthRefused() {
        assertEquals(NoDecision(SyncErrorKind.AUTH_REFUSED, null), map(status(401)), "401")
        assertEquals(NoDecision(SyncErrorKind.AUTH_REFUSED, null), map(status(403)), "403")
    }

    @Test
    fun status404IsEntityNotFound() {
        assertEquals(NoDecision(SyncErrorKind.ENTITY_NOT_FOUND, null), map(status(404)))
    }

    @Test
    fun everyThreeHundredIsRedirected() {
        for (code in listOf(300, 301, 302, 303, 304, 307, 308, 399)) {
            assertEquals(NoDecision(SyncErrorKind.REDIRECTED, null), map(status(code)), "$code")
        }
    }

    @Test
    fun otherStatusesAreHttpErrorWithTheCode() {
        for (code in listOf(500, 502, 503, 418, 400, 405, 429, 201, 204, 100)) {
            assertEquals(NoDecision(SyncErrorKind.HTTP_ERROR, "$code"), map(status(code)), "$code")
        }
    }

    // Row 3 — malformed (AC5).

    @Test
    fun nonJsonWrongTypeTruncatedOrStatelessIsMalformed() {
        val on = stateBody("on")
        assertEquals(malformed, map(ok(on, truncated = true)), "a truncated answer, even one that parses")
        assertEquals(malformed, map(ok("<html>Home Assistant</html>", contentType = "text/html")), "html")
        assertEquals(malformed, map(ok(on, contentType = "text/plain")), "the right body under another type")
        assertEquals(malformed, map(ok(on, contentType = null)), "no content type")
        assertEquals(malformed, map(ok("not json at all")), "not JSON")
        assertEquals(malformed, map(ok("")), "an empty body")
        assertEquals(malformed, map(ok("[$on]")), "an array, not one object")
        assertEquals(malformed, map(ok(q("on"))), "a bare string, not one object")
        assertEquals(malformed, map(ok("$on $on")), "two objects")
        assertEquals(malformed, map(ok("""{"entity_id":${q(entity)},"last_changed":${q(changed)}}""")), "no state")
        assertEquals(malformed, map(ok("""{"state":"on"}""")), "no entity_id")
        assertEquals(
            Observed(HaSwitchState.ON, changed),
            map(ok(on, contentType = "application/json; charset=utf-8")),
            "parameters on the content type are allowed",
        )
    }

    @Test
    fun aNonStringStateIsMalformed() {
        for (stateJson in listOf("true", "1", "null", "[\"on\"]", "{\"value\":\"on\"}")) {
            assertEquals(malformed, map(ok(bodyWith(stateJson))), "state $stateJson")
        }
    }

    // Row 4 — the bounds.

    @Test
    fun detailAndLastChangedAreBoundedAndControlFree() {
        val long = "x".repeat(10_000)
        val unsupported = map(ok(stateBody(long))) as NoDecision
        assertEquals(SyncErrorKind.UNSUPPORTED_STATE, unsupported.kind)
        assertEquals("x".repeat(MAX_HA_TEXT_LENGTH), unsupported.detail, "a 10 000-character state keeps 64")

        assertEquals(
            NoDecision(SyncErrorKind.UNSUPPORTED_STATE, "un?avail?able?"),
            map(ok(stateBody("un\navail\table\u0007"))),
            "control characters inside the state become ?",
        )

        val observed = map(ok(stateBody("on", lastChanged = "2026\r\n" + "y".repeat(10_000)))) as Observed
        assertEquals("2026??" + "y".repeat(MAX_HA_TEXT_LENGTH - 6), observed.haLastChanged, "last_changed too")
    }

    @Test
    fun aCutNeverSplitsASurrogatePair() {
        val state = "x".repeat(MAX_HA_TEXT_LENGTH - 1) + "😀" + "tail"
        assertEquals(NoDecision(SyncErrorKind.UNSUPPORTED_STATE, "x".repeat(MAX_HA_TEXT_LENGTH - 1)), map(ok(stateBody(state))))
    }

    // Row 5 — the answer names the entity.

    @Test
    fun anotherEntitysAnswerIsMalformed() {
        assertEquals(malformed, map(ok(bodyWith(q("on"), entityId = "input_boolean.example_cooler_in_season"))), "another")
        assertEquals(malformed, map(ok(bodyWith(q("on"), entityId = "Input_Boolean.Example_Heater_In_Season"))), "case")
        assertEquals(malformed, map(ok(bodyWith(q("on"), entityId = " $entity"))), "a space")
        assertEquals(malformed, map(ok("""{"entity_id":7,"state":"on"}""")), "not a string")
    }

    // The text HA sends stays text.

    @Test
    fun lastChangedIsKeptAsTextAndNeverDecides() {
        assertEquals(Observed(HaSwitchState.OFF, "yesterday"), map(ok(stateBody("off", lastChanged = "yesterday"))))
        assertEquals(
            Observed(HaSwitchState.ON, null),
            map(ok(bodyWith(q("on"), lastChangedJson = "1759298400"))),
            "a non-string last_changed is dropped; the state still decides",
        )
    }

    @Test
    fun theAnswersToStringCarriesNoBody() {
        val text = ok(stateBody("on")).toString()
        assertTrue(text.contains("status=200"), text)
        assertFalse(text.contains("example_heater"), text)
    }
}
