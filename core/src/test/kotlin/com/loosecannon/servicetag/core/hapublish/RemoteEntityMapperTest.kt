package com.loosecannon.servicetag.core.hapublish

import com.loosecannon.servicetag.core.seasonsync.HaHttpAnswer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * #113/#114 (C16; rows 30–31) — a helper's GET answer becomes a [RemoteEntity] only when it is one JSON object for
 * the requested id; every bound holds; a GET's statuses map as #16's do. Every value is fictional.
 */
class RemoteEntityMapperTest {

    private val select = "input_select.example_heater_status"
    private val datetime = "input_datetime.example_heater_service_done"

    private fun q(text: String): String = JsonPrimitive(text).toString()

    private fun ok(body: String, contentType: String? = "application/json", truncated: Boolean = false) =
        HaHttpAnswer(200, contentType, body.encodeToByteArray(), truncated)

    private fun selectBody(state: String = "UP", options: List<String> = listOf("UP", "DOWN", "DEGRADED"), id: String = select) =
        """{"entity_id":${q(id)},"state":${q(state)},"attributes":{"options":${JsonArray(options.map(::JsonPrimitive))},"friendly_name":"Example heater status"}}"""

    private fun dateBody(state: String = "2026-09-18") =
        """{"entity_id":${q(datetime)},"state":${q(state)},"attributes":{"has_date":true,"has_time":false,"friendly_name":"Example service done"}}"""

    private fun status(code: Int) = HaHttpAnswer(code, "text/plain", "refused".encodeToByteArray(), false)

    private val malformed = RemoteRead.Failed(PublishErrorKind.MALFORMED, null)

    // Row 30 — the fields read, another entity's answer refused, the bounds kept.

    @Test
    fun aSelectsOptionsAndADatetimesFlagsAreRead() {
        assertEquals(
            RemoteRead.Answered(RemoteEntity(select, "UP", listOf("UP", "DOWN", "DEGRADED"), null, null)),
            mapRemoteEntity(ok(selectBody()), select),
        )
        assertEquals(
            RemoteRead.Answered(RemoteEntity(datetime, "2026-09-18", null, true, false)),
            mapRemoteEntity(ok(dateBody()), datetime),
        )
        // A state HA reports as text stays text: "unknown" is a state, not a failure.
        assertEquals(
            RemoteRead.Answered(RemoteEntity(datetime, "unknown", null, true, false)),
            mapRemoteEntity(ok(dateBody("unknown")), datetime),
        )
        assertEquals(
            RemoteRead.Answered(RemoteEntity(select, "UP", null, null, null)),
            mapRemoteEntity(ok("""{"entity_id":${q(select)},"state":"UP"}"""), select),
            "no attributes",
        )
    }

    @Test
    fun anotherEntitysAnswerIsMalformed() {
        assertEquals(malformed, mapRemoteEntity(ok(selectBody(id = "input_select.example_boiler_status")), select))
        assertEquals(malformed, mapRemoteEntity(ok(selectBody(), contentType = "text/html"), select), "not JSON")
        assertEquals(malformed, mapRemoteEntity(ok(selectBody(), truncated = true), select), "truncated")
        assertEquals(malformed, mapRemoteEntity(ok("[1,2]"), select), "not an object")
        assertEquals(malformed, mapRemoteEntity(ok("not json"), select), "no JSON")
        assertEquals(malformed, mapRemoteEntity(ok("""{"entity_id":${q(select)},"state":5}"""), select), "a number")
        assertEquals(malformed, mapRemoteEntity(ok("""{"entity_id":${q(select)}}"""), select), "no state")
    }

    @Test
    fun boundsHold() {
        val many = (1..65).map { "OPTION_$it" }
        val read = assertIs<RemoteRead.Answered>(mapRemoteEntity(ok(selectBody(options = many)), select))
        assertEquals(MAX_HA_OPTIONS, read.remote.options!!.size)
        assertEquals(many.take(64), read.remote.options)

        val long = assertIs<RemoteRead.Answered>(mapRemoteEntity(ok(selectBody(state = "S".repeat(10_000))), select))
        assertEquals(64, long.remote.state.length)

        val longOption = assertIs<RemoteRead.Answered>(
            mapRemoteEntity(ok(selectBody(options = listOf("O".repeat(10_000)))), select),
        )
        assertEquals(64, longOption.remote.options!!.single().length)
    }

    // Row 31 — a GET's statuses.

    @Test
    fun `401And403AreAuthRefusedOnAGet`() {
        assertEquals(RemoteRead.Failed(PublishErrorKind.AUTH_REFUSED, null), mapRemoteEntity(status(401), select))
        assertEquals(RemoteRead.Failed(PublishErrorKind.AUTH_REFUSED, null), mapRemoteEntity(status(403), select))
    }

    @Test
    fun `404IsEntityNotFound`() {
        assertEquals(RemoteRead.Failed(PublishErrorKind.ENTITY_NOT_FOUND, null), mapRemoteEntity(status(404), select))
    }

    @Test
    fun `3xxIsRedirected`() {
        for (code in listOf(300, 301, 302, 307, 308, 399)) {
            assertEquals(RemoteRead.Failed(PublishErrorKind.REDIRECTED, null), mapRemoteEntity(status(code), select), "$code")
        }
    }

    @Test
    fun otherIsHttpError() {
        for (code in listOf(204, 400, 405, 500, 503)) {
            assertEquals(
                RemoteRead.Failed(PublishErrorKind.HTTP_ERROR, code.toString()),
                mapRemoteEntity(status(code), select),
                "$code",
            )
        }
    }
}
