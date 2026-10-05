package com.loosecannon.servicetag.core.seasonsync

import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.jupiter.api.Test

/** #105 rows 1–4 (B1): the states-list mapper over scripted answers; every value fictional. */
class HaStatesMapperTest {

    private fun answer(
        body: String,
        status: Int = 200,
        contentType: String? = "application/json",
        truncated: Boolean = false,
    ) = HaHttpAnswer(status, contentType, body.toByteArray(), truncated)

    private fun listed(body: String): List<HaEntityCandidate> =
        assertIs<HaListOutcome.Listed>(mapHaStatesAnswer(answer(body))).entities

    /** Row 1: every object with a valid id maps; the name only when a string; invalid ids, non-objects and duplicates go. */
    @Test
    fun aStatesArrayMapsEveryValidEntityOnceWithItsNameWhenItHasOne() {
        val body = """[
            {"entity_id":"input_boolean.example_heater_in_season","state":"on","attributes":{"friendly_name":"Example Heater In Season"}},
            {"entity_id":"input_boolean.example_pump","state":"off","attributes":{}},
            {"entity_id":"input_boolean.example_numeric_name","state":"off","attributes":{"friendly_name":42}},
            {"entity_id":"input_boolean.example_blank_name","state":"off","attributes":{"friendly_name":"   "}},
            {"entity_id":"switch.example_lamp","state":"on","attributes":{"friendly_name":"Example Lamp"}},
            {"entity_id":"Input_Boolean.Bad_Case","state":"on","attributes":{}},
            {"entity_id":"no_dot","state":"on","attributes":{}},
            {"state":"on","attributes":{"friendly_name":"No id"}},
            {"entity_id":42,"state":"on"},
            "not an object",
            null,
            {"entity_id":"input_boolean.example_heater_in_season","state":"off","attributes":{"friendly_name":"Duplicate"}}
        ]"""

        assertEquals(
            listOf(
                HaEntityCandidate("input_boolean.example_heater_in_season", "Example Heater In Season"),
                HaEntityCandidate("input_boolean.example_pump", null),
                HaEntityCandidate("input_boolean.example_numeric_name", null),
                HaEntityCandidate("input_boolean.example_blank_name", null),
                HaEntityCandidate("switch.example_lamp", "Example Lamp"),
            ),
            listed(body),
        )
        assertEquals(emptyList(), listed("[]"))
    }

    /** Row 2: the statuses map as Test connection's do — a 404 is an HTTP error here, never a missing entity. */
    @Test
    fun statusesMapAsTestConnectionsDo() {
        assertEquals(HaListOutcome.Failed(SyncErrorKind.AUTH_REFUSED, null), mapHaStatesAnswer(answer("", status = 401)))
        assertEquals(HaListOutcome.Failed(SyncErrorKind.AUTH_REFUSED, null), mapHaStatesAnswer(answer("", status = 403)))
        for (status in listOf(301, 302, 307, 308)) {
            assertEquals(HaListOutcome.Failed(SyncErrorKind.REDIRECTED, null), mapHaStatesAnswer(answer("", status = status)), "$status")
        }
        for (status in listOf(404, 500, 502, 503, 418)) {
            assertEquals(
                HaListOutcome.Failed(SyncErrorKind.HTTP_ERROR, status.toString()),
                mapHaStatesAnswer(answer("", status = status)),
                "$status",
            )
        }
    }

    /** Row 3: a cut-off body, the wrong type, an object, a scalar or non-JSON are MALFORMED, never a partial list. */
    @Test
    fun truncatedWrongTypeOrNonArrayIsMalformed() {
        val malformed = HaListOutcome.Failed(SyncErrorKind.MALFORMED, null)
        assertEquals(malformed, mapHaStatesAnswer(answer("""[{"entity_id":"input_boolean.a"}]""", truncated = true)))
        assertEquals(malformed, mapHaStatesAnswer(answer("[]", contentType = "text/html")))
        assertEquals(malformed, mapHaStatesAnswer(answer("[]", contentType = null)))
        assertEquals(malformed, mapHaStatesAnswer(answer("""{"entity_id":"input_boolean.a"}""")))
        assertEquals(malformed, mapHaStatesAnswer(answer("\"on\"")))
        assertEquals(malformed, mapHaStatesAnswer(answer("[{\"entity_id\":")))
        assertEquals(
            listOf(HaEntityCandidate("input_boolean.a", null)),
            assertIs<HaListOutcome.Listed>(
                mapHaStatesAnswer(answer("""[{"entity_id":"input_boolean.a"}]""", contentType = "Application/JSON; charset=utf-8")),
            ).entities,
            "the media type's parameters and case do not matter",
        )
    }

    /** Row 4: the name is bounded at 128 with control characters as `?` and no split pair; an over-long id is skipped. */
    @Test
    fun namesAreBoundedAndControlFreeAndAnOverLongIdIsSkipped() {
        val longName = "n".repeat(127) + "😀" + "tail"
        val longId = "input_boolean." + "x".repeat(MAX_HA_TEXT_LENGTH)
        val body = """[
            {"entity_id":"input_boolean.example_long","attributes":{"friendly_name":"$longName"}},
            {"entity_id":"input_boolean.example_control","attributes":{"friendly_name":"Line\nbreak\ttab"}},
            {"entity_id":"$longId","attributes":{"friendly_name":"Too long an id"}}
        ]"""

        val entities = listed(body)

        assertEquals(2, entities.size)
        assertEquals("n".repeat(127), entities[0].friendlyName, "the pair would be split, so it goes with the tail")
        assertEquals("Line?break?tab", entities[1].friendlyName)
    }
}
