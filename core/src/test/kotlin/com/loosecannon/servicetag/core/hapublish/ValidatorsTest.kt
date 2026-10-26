package com.loosecannon.servicetag.core.hapublish

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #113/#114 (C16; rows 32–34) — a status helper is an `input_select` with exactly UP, DOWN and DEGRADED; a date
 * helper is a date-only `input_datetime`; a wrong domain is refused from the id, before any read. Every value is
 * fictional.
 */
class ValidatorsTest {

    private val select = "input_select.example_heater_status"
    private val datetime = "input_datetime.example_heater_service_done"

    private fun selectWith(options: List<String>?) = RemoteEntity(select, "UP", options, null, null)
    private fun datetimeWith(hasDate: Boolean?, hasTime: Boolean?) =
        RemoteEntity(datetime, "2026-09-18", null, hasDate, hasTime)

    // Row 32 — the option set, exactly (AC113-1).

    @Test
    fun exactlyUpDownDegradedInAnyOrderPasses() {
        for (order in listOf(
            listOf("UP", "DOWN", "DEGRADED"),
            listOf("DEGRADED", "UP", "DOWN"),
            listOf("DOWN", "DEGRADED", "UP"),
        )) {
            assertNull(validateStatusTarget(select, selectWith(order)), "$order")
            assertNull(validateTarget(PublishKind.EQUIPMENT_STATUS, select, selectWith(order)), "$order")
        }
    }

    @Test
    fun aMissingExtraOrCaseVariantOptionIsOptionsMismatch() {
        for (options in listOf(
            listOf("Up", "DOWN", "DEGRADED"),
            listOf("UP", "DOWN", "DEGRADED", "UNKNOWN"),
            listOf("UP", "DEGRADED"),
            emptyList(),
            null,
        )) {
            assertEquals(PublishErrorKind.OPTIONS_MISMATCH, validateStatusTarget(select, selectWith(options)), "$options")
        }
    }

    // Row 33 — WRONG_DOMAIN from the id alone, before any read.

    @Test
    fun aWrongDomainIdIsRefusedBeforeAnyRead() {
        val reads = mutableListOf<String>()
        val wellShaped = selectWith(listOf("UP", "DOWN", "DEGRADED"))
        val read: (String) -> RemoteRead = { reads += it; RemoteRead.Answered(wellShaped) }

        val inbound = "input_boolean.example_heater_in_season"
        assertEquals(
            TargetCheck.Refused(PublishErrorKind.WRONG_DOMAIN, null),
            checkTarget(PublishKind.EQUIPMENT_STATUS, inbound, read),
        )
        assertEquals(
            TargetCheck.Refused(PublishErrorKind.WRONG_DOMAIN, null),
            checkTarget(PublishKind.SEASON_SERVICE_DATE, select, read),
        )
        assertEquals(emptyList(), reads, "no GET for a wrong domain")

        assertEquals(PublishErrorKind.WRONG_DOMAIN, domainRefusalOf(PublishKind.EQUIPMENT_STATUS, inbound))
        assertEquals(PublishErrorKind.WRONG_DOMAIN, domainRefusalOf(PublishKind.EQUIPMENT_STATUS, "input_selectx.example"))
        assertEquals(PublishErrorKind.WRONG_DOMAIN, domainRefusalOf(PublishKind.EQUIPMENT_STATUS, "input_select"))
        assertEquals(PublishErrorKind.WRONG_DOMAIN, validateStatusTarget(inbound, wellShaped))
        assertEquals(PublishErrorKind.WRONG_DOMAIN, validateDateTarget(select, datetimeWith(true, false)))

        // The right domain reads once, then validates.
        assertEquals(TargetCheck.Valid(wellShaped), checkTarget(PublishKind.EQUIPMENT_STATUS, select, read))
        assertEquals(listOf(select), reads)
        val failing: (String) -> RemoteRead = { RemoteRead.Failed(PublishErrorKind.ENTITY_NOT_FOUND, null) }
        assertEquals(
            TargetCheck.Refused(PublishErrorKind.ENTITY_NOT_FOUND, null),
            checkTarget(PublishKind.EQUIPMENT_STATUS, select, failing),
        )
    }

    // Row 34 — date only (AC114-5).

    @Test
    fun aDateOnlyHelperPasses() {
        assertNull(validateDateTarget(datetime, datetimeWith(hasDate = true, hasTime = false)))
        assertEquals(
            TargetCheck.Valid(datetimeWith(true, false)),
            checkTarget(PublishKind.SEASON_SERVICE_DATE, datetime) { RemoteRead.Answered(datetimeWith(true, false)) },
        )
    }

    @Test
    fun aHelperWithATimeOrWithoutADateIsNotDateOnly() {
        for ((hasDate, hasTime) in listOf(true to true, false to false, false to true, null to null, true to null, null to false)) {
            assertEquals(
                PublishErrorKind.NOT_DATE_ONLY,
                validateDateTarget(datetime, datetimeWith(hasDate, hasTime)),
                "has_date=$hasDate has_time=$hasTime",
            )
        }
    }

    // C16's two readers.

    @Test
    fun aBoundaryDateIsExactlyADayElseNull() {
        assertEquals(LocalDate.parse("2026-05-10"), boundaryDateOf(RemoteEntity(datetime, "2026-05-10", null, true, false)))
        for (state in listOf("unknown", "unavailable", "", "2026-5-10", "2026-05-10 00:00:00", "2026-02-30", " 2026-05-10")) {
            assertNull(boundaryDateOf(RemoteEntity(datetime, state, null, true, false)), state)
        }
    }

    @Test
    fun aMatchIsExactStringEquality() {
        assertTrue(remoteMatches(PublishKind.EQUIPMENT_STATUS, selectWith(null).copy(state = "UP"), "UP"))
        assertFalse(remoteMatches(PublishKind.EQUIPMENT_STATUS, selectWith(null).copy(state = "Up"), "UP"))
        assertTrue(remoteMatches(PublishKind.SEASON_SERVICE_DATE, datetimeWith(true, false), "2026-09-18"))
        assertFalse(remoteMatches(PublishKind.SEASON_SERVICE_DATE, datetimeWith(true, false), "2026-09-19"))
    }
}
