package com.loosecannon.servicetag.core.seasonsync

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** #105 rows 5–8 (B1): the browser's rows, pure; every entity fictional. */
class EntityPickerTest {

    private val stove = HaEntityCandidate("input_boolean.pellet_stove_in_season", "Pellet Stove In Season")
    private val heater = HaEntityCandidate("input_boolean.example_heater_in_season", "Example Heater In Season")
    private val nameless = HaEntityCandidate("input_boolean.example_unnamed", null)
    private val lamp = HaEntityCandidate("switch.example_lamp", "Example Lamp")
    private val sensor = HaEntityCandidate("binary_sensor.example_door", "Pellet Stove Door")
    private val lookalike = HaEntityCandidate("input_boolean_x.example", "Pellet Stove Lookalike")
    private val all = listOf(lamp, stove, sensor, nameless, lookalike, heater)

    private fun rows(query: String) = pickerRows(all, EntityScope.INPUT_BOOLEANS, query)

    /** Row 5: the scope is the id's domain, exactly — `input_boolean_x.` and `switch.` are out, whatever their names say. */
    @Test
    fun theScopeIsTheDomainBeforeTheDot() {
        assertEquals(listOf(heater, nameless, stove), rows("").rows)
    }

    /** Row 6: a casefolded substring of the name or of the id; trimmed; empty matches all. */
    @Test
    fun searchIsACasefoldedSubstringOfNameOrId() {
        assertEquals(listOf(stove), rows("pellet").rows)
        assertEquals(listOf(stove), rows("  STOVE IN  ").rows)
        assertEquals(listOf(stove), rows("pellet_stove").rows)
        assertEquals(listOf(heater, nameless), rows("example_").rows)
        assertEquals(listOf(nameless), rows("unnamed").rows, "a nameless candidate is found by its id")
        assertEquals(emptyList(), rows("door").rows, "the sensor's name is out of scope")
        assertEquals(listOf(heater, nameless, stove), rows("   ").rows)
    }

    /** Row 7: name casefolded then id; a nameless candidate sorts by its id among the names. */
    @Test
    fun orderIsNameCasefoldedThenId() {
        val b = HaEntityCandidate("input_boolean.b", "alpha")
        val a = HaEntityCandidate("input_boolean.a", "Alpha")
        val c = HaEntityCandidate("input_boolean.c", "Beta")
        val n = HaEntityCandidate("input_boolean.aardvark", null)
        // "alpha" twice, told apart by id; "beta"; then the nameless one, whose key is its own id ("input_boolean…").
        assertEquals(listOf(a, b, c, n), pickerRows(listOf(c, b, n, a), EntityScope.INPUT_BOOLEANS, "").rows)
    }

    /** Row 8: at most MAX_LISTED_ENTITIES rows, and the flag when more matched. */
    @Test
    fun theListIsBoundedAndSaysSo() {
        val many = (1..MAX_LISTED_ENTITIES + 1).map { HaEntityCandidate("input_boolean.e${"%05d".format(it)}", null) }
        val over = pickerRows(many, EntityScope.INPUT_BOOLEANS, "")
        assertEquals(MAX_LISTED_ENTITIES, over.rows.size)
        assertTrue(over.truncatedList)
        val exact = pickerRows(many.dropLast(1), EntityScope.INPUT_BOOLEANS, "")
        assertEquals(MAX_LISTED_ENTITIES, exact.rows.size)
        assertFalse(exact.truncatedList)
        assertFalse(pickerRows(many, EntityScope.INPUT_BOOLEANS, "e00001").truncatedList, "a narrowed list is whole")
    }
}
