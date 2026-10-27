package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.model.LegacySeasonMapping
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.dayMillis
import com.loosecannon.servicetag.testing.scheduleOf
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #124 (C40, row 140): `ScheduleForms`' two arms for `IN_SERVICE_AFTER_OPENING_SERVICE` — the 1.4
 * form defaults its offset to 0, as AT_START's, and the legacy form, which 1.3's three fields cannot
 * spell it in, is refused over a stored row rather than resetting it.
 */
class ScheduleFormsTest {

    private val graph = FakeGraph().apply {
        now = dayMillis("2026-02-01")
        today = LocalDate.parse("2026-02-10")
    }
    private val api = V1Client(graph)

    @After fun close() = graph.close()

    /** The 1.4 form: an omitted or null offset is 0 on the value, and one sent is kept as sent. */
    @Test fun theCurrentFormDefaultsTheOpeningServiceOffsetToZero() {
        fun policyOf(offset: Int?) = ScheduleForms.policyOf(
            ScheduleForms.Form.CURRENT,
            ScheduleCommandRequest(
                title = "Annual service", servicePolicy = "IN_SERVICE_AFTER_OPENING_SERVICE", policyOffsetDays = offset,
            ),
        )
        assertEquals(LegacySeasonMapping.Policy(ServicePolicy.IN_SERVICE_AFTER_OPENING_SERVICE, 0), policyOf(null))
        assertEquals(LegacySeasonMapping.Policy(ServicePolicy.IN_SERVICE_AFTER_OPENING_SERVICE, 14), policyOf(14))
        assertEquals(LegacySeasonMapping.Policy(ServicePolicy.IN_SERVICE_AFTER_OPENING_SERVICE, 0), policyOf(0))
        // The shipped arms are unchanged: AT_START defaults to 0, every other policy to none.
        assertEquals(
            LegacySeasonMapping.Policy(ServicePolicy.IN_SERVICE_AT_START, 0),
            ScheduleForms.policyOf(
                ScheduleForms.Form.CURRENT,
                ScheduleCommandRequest(title = "Annual service", servicePolicy = "IN_SERVICE_AT_START"),
            ),
        )
        assertEquals(
            LegacySeasonMapping.Policy(ServicePolicy.IN_SERVICE_RESUME_CLAMPED, null),
            ScheduleForms.policyOf(
                ScheduleForms.Form.CURRENT,
                ScheduleCommandRequest(title = "Annual service", servicePolicy = "IN_SERVICE_RESUME_CLAMPED"),
            ),
        )
    }

    /**
     * A legacy-form PATCH of a stored opening-service schedule is 422 `LEGACY_WRITE_CANNOT_REPRESENT`
     * naming the policy, and writes nothing — PRE_SERVICE's precedent, never a reset to the table's
     * reading of the three fields.
     */
    @Test fun aLegacyWriteOfTheOpeningServiceIsRefusedAndWritesNothing() {
        val heater = api.asset("Example Heater")
        api.ok(
            AssetSeasonResponse.serializer(), "POST", "/v1/assets/$heater/season-mode",
            """{"seasonMode":"CALENDAR","seasonStartMmdd":"10-01","seasonEndMmdd":"04-30"}""",
        )
        val row = scheduleOf("s-opening", assetId = heater, title = "Annual service")
            .copy(servicePolicy = ServicePolicy.IN_SERVICE_AFTER_OPENING_SERVICE, policyOffsetDays = 7)
        runBlocking { graph.schedules.upsert(row) }
        val before = runBlocking { graph.schedules.get(ScheduleId("s-opening")) }

        val legacyBodies = listOf(
            "",
            ""","seasonBehavior":"FOLLOW_ASSET"""",
            ""","seasonBehavior":"FOLLOW_ASSET","seasonReentry":"AT_START","seasonReentryOffsetDays":7""",
        )
        for (tail in legacyBodies) {
            val refused = api.call(
                "PATCH", "/v1/schedules/s-opening",
                """{"title":"Annual service","targetAssetId":"$heater","timeInterval":1,"timeUnit":"YEAR",
                    "timeBasis":"FIXED","anchorOn":"2026-02-01"$tail}""",
            )
            assertEquals("$tail: ${refused.bodyText()}", 422, refused.status)
            val detail = refused.errorDetail()
            assertEquals(tail, "LEGACY_WRITE_CANNOT_REPRESENT", detail.code)
            assertTrue(refused.bodyText(), "IN_SERVICE_AFTER_OPENING_SERVICE" in refused.bodyText())
            assertEquals("a refusal writes nothing", before, runBlocking { graph.schedules.get(ScheduleId("s-opening")) })
        }

        // The 1.4 form over the same row is read as sent: the refusal is the legacy form's only.
        val current = api.call(
            "PATCH", "/v1/schedules/s-opening",
            """{"title":"Annual service","targetAssetId":"$heater","timeInterval":1,"timeUnit":"YEAR",
                "timeBasis":"FIXED","anchorOn":"2026-02-01","servicePolicy":"CONTINUOUS"}""",
        )
        assertEquals(current.bodyText(), 200, current.status)
        assertEquals(ServicePolicy.CONTINUOUS, runBlocking { graph.schedules.get(ScheduleId("s-opening")) }!!.servicePolicy)
    }
}
