package com.loosecannon.servicetag.core.hapublish

import com.loosecannon.servicetag.core.seasonsync.EntityScope
import com.loosecannon.servicetag.core.seasonsync.HaEntityCandidate
import com.loosecannon.servicetag.core.seasonsync.HaHttpAnswer
import com.loosecannon.servicetag.core.seasonsync.HaListOutcome
import com.loosecannon.servicetag.core.seasonsync.HelperAttributes
import com.loosecannon.servicetag.core.seasonsync.MAX_LISTED_OPTIONS
import com.loosecannon.servicetag.core.seasonsync.mapHaStatesAnswer
import com.loosecannon.servicetag.core.seasonsync.pickerRows
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

/**
 * #113/#114 (C29b; row 115) — helper discovery's attributes on #105's list mapper: an `input_select` row keeps its
 * options under C16's bounds and an `input_datetime` row its two flags; every other domain keeps none, so #105's
 * shipped fixtures (`input_boolean` and `switch` rows only) map exactly as before. Every id is fictional.
 */
class HelperAttributesTest {

    private fun listed(body: String): List<HaEntityCandidate> =
        assertIs<HaListOutcome.Listed>(
            mapHaStatesAnswer(HaHttpAnswer(200, "application/json", body.toByteArray(), truncated = false)),
        ).entities

    private fun one(body: String): HaEntityCandidate = listed("[$body]").single()

    @Test
    fun aDropdownKeepsItsOptionsInOrder() {
        val row = one(
            """{"entity_id":"input_select.example_heater_equipment_status","state":"UP",
               "attributes":{"friendly_name":"Example Heater status","options":["UP","DEGRADED","DOWN"],"has_date":true}}""",
        )
        assertEquals(HelperAttributes(listOf("UP", "DEGRADED", "DOWN"), hasDate = null, hasTime = null), row.attributes)
        assertEquals("Example Heater status", row.friendlyName)
    }

    @Test
    fun aDateHelperKeepsItsTwoFlagsAndNoOptions() {
        val row = one(
            """{"entity_id":"input_datetime.example_heater_service_done","state":"2026-02-10",
               "attributes":{"has_date":true,"has_time":false,"options":["x"]}}""",
        )
        assertEquals(HelperAttributes(options = null, hasDate = true, hasTime = false), row.attributes)
    }

    @Test
    fun aFlagThatIsNotABooleanIsNull() {
        val row = one(
            """{"entity_id":"input_datetime.example_boiler_service_done","state":"unknown",
               "attributes":{"has_date":"true","has_time":1}}""",
        )
        assertEquals(HelperAttributes(options = null, hasDate = null, hasTime = null), row.attributes)
    }

    @Test
    fun anOnOffHelperOrAnotherDomainKeepsNoAttributes() {
        val rows = listed(
            """[
              {"entity_id":"input_boolean.example_heater_in_season","state":"on","attributes":{"options":["UP"],"has_date":true,"has_time":false}},
              {"entity_id":"switch.example_lamp","state":"on","attributes":{"options":["UP"],"has_date":true}},
              {"entity_id":"sensor.example_select_like","state":"UP","attributes":{"options":["UP","DOWN"]}}
            ]""",
        )
        assertEquals(listOf(null, null, null), rows.map { it.attributes })
    }

    @Test
    fun sixtyFourOptionsAreKeptAndSixtyFiveAreNull() {
        fun options(n: Int) = (1..n).joinToString(",") { "\"option-$it\"" }
        val kept = one("""{"entity_id":"input_select.example_many","state":"option-1","attributes":{"options":[${options(MAX_LISTED_OPTIONS)}]}}""")
        assertEquals(MAX_LISTED_OPTIONS, kept.attributes?.options?.size)
        val over = one("""{"entity_id":"input_select.example_many","state":"option-1","attributes":{"options":[${options(MAX_LISTED_OPTIONS + 1)}]}}""")
        assertEquals(HelperAttributes(options = null, hasDate = null, hasTime = null), over.attributes)
    }

    @Test
    fun aNonStringEmptyOrMissingOptionListIsNull() {
        for (attributes in listOf(
            """{"options":["UP",2,"DOWN"]}""",
            """{"options":["UP",null]}""",
            """{"options":"UP,DOWN"}""",
            """{"options":[]}""",
            """{}""",
        )) {
            val row = one("""{"entity_id":"input_select.example_odd","state":"UP","attributes":$attributes}""")
            assertNull(row.attributes?.options, attributes)
        }
        assertNull(one("""{"entity_id":"input_select.example_bare","state":"UP"}""").attributes?.options)
    }

    @Test
    fun anOptionIsBoundedLikeAnIdWithControlCharactersReplaced() {
        val long = "x".repeat(200)
        val row = one("""{"entity_id":"input_select.example_long","state":"UP","attributes":{"options":["$long","a\u0007b"]}}""")
        assertEquals(listOf("x".repeat(64), "a?b"), row.attributes?.options)
    }

    @Test
    fun pickerRowsListsEachNewScopeByItsDomainAlone() {
        val rows = listed(
            """[
              {"entity_id":"input_select.example_heater_equipment_status","state":"UP","attributes":{"friendly_name":"Status","options":["UP","DEGRADED","DOWN"]}},
              {"entity_id":"input_datetime.example_heater_service_done","state":"1970-01-01","attributes":{"friendly_name":"Done","has_date":true,"has_time":false}},
              {"entity_id":"input_datetime.example_heater_season_ended","state":"2026-05-01","attributes":{"friendly_name":"Ended","has_date":true,"has_time":false}},
              {"entity_id":"input_boolean.example_heater_in_season","state":"on","attributes":{"friendly_name":"In season"}}
            ]""",
        )
        assertEquals(
            listOf("input_select.example_heater_equipment_status"),
            pickerRows(rows, EntityScope.INPUT_SELECTS, "").rows.map { it.entityId },
        )
        assertEquals(
            listOf("input_datetime.example_heater_service_done", "input_datetime.example_heater_season_ended"),
            pickerRows(rows, EntityScope.INPUT_DATETIMES, "").rows.map { it.entityId },
        )
        assertEquals(
            listOf("input_boolean.example_heater_in_season"),
            pickerRows(rows, EntityScope.INPUT_BOOLEANS, "").rows.map { it.entityId },
        )
        assertEquals(listOf("input_select", "input_datetime"), listOf(EntityScope.INPUT_SELECTS, EntityScope.INPUT_DATETIMES).map { it.domain })
    }
}
