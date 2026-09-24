package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.dayMillis
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 1.4 (B09) — the schedule command's **two forms** on the wire (spec §9.3; master plan §11.4; Q-9;
 * inv. 106, 128): presence decides the form, a mixed body is refused, the legacy form translates only
 * through `LegacySeasonMapping` and is **refused, never reset**, and the derived triple round-trips
 * every representable schedule. Every assertion is on a live route over the fake graph.
 */
class LegacyFormTest {

    private val graph = FakeGraph().apply {
        now = dayMillis("2026-02-01")
        today = LocalDate.parse("2026-02-10")
    }
    private val api = V1Client(graph)

    @After fun close() = graph.close()

    // --- fixtures -------------------------------------------------------------------------------

    /** A snowblower whose calendar season runs through the winter, so PRE_SERVICE has a boundary. */
    private fun calendarAsset(name: String = "Snowblower"): String {
        val id = api.asset(name)
        api.ok(
            AssetSeasonResponse.serializer(), "POST", "/v1/assets/$id/season-mode",
            """{"seasonMode":"CALENDAR","seasonStartMmdd":"11-01","seasonEndMmdd":"03-31"}""",
        )
        return id
    }

    /** A yearly time rule on [assetId], plus whatever season keys [season] adds (a JSON fragment). */
    private fun body(assetId: String, season: String = "", title: String = "Auger belt"): String {
        val tail = if (season.isEmpty()) "" else ",$season"
        return """{"title":"$title","targetAssetId":"$assetId","timeInterval":1,"timeUnit":"YEAR",
            "timeBasis":"FIXED","anchorOn":"2026-02-01"$tail}"""
    }

    private fun create(body: String): ScheduleRowResponse =
        api.ok(ScheduleResponse.serializer(), "POST", "/v1/schedules", body, status = 201).schedule

    private fun stored(id: String) = runBlocking { graph.schedules.get(ScheduleId(id))!! }

    private fun wireRow(response: ApiResponse): JsonObject =
        ApiJson.parseToJsonElement(response.bodyText()).jsonObject.getValue("schedule").jsonObject

    private fun JsonObject.triple(): List<JsonElement?> =
        listOf(get("seasonBehavior"), get("seasonReentry"), get("seasonReentryOffsetDays"))

    // --- the presence rule ------------------------------------------------------------------------

    /**
     * A `servicePolicy` **or** a `policyOffsetDays` key makes the 1.4 form — and an explicit `null`
     * counts as present. The proof is a PATCH of a PRE_SERVICE schedule: the legacy form would be
     * refused there, so a 200 means the body was read as the 1.4 form.
     */
    @Test fun aPolicyKeyOrExplicitNullMakesThe14Form() {
        val asset = calendarAsset()
        val pre = create(body(asset, """"servicePolicy":"PRE_SERVICE","policyOffsetDays":-14"""))
        assertEquals("PRE_SERVICE", pre.servicePolicy)

        // `policyOffsetDays: null` alone, no `servicePolicy`: the 1.4 form, whose omitted policy is CONTINUOUS.
        val nulled = api.call("PATCH", "/v1/schedules/${pre.id}", body(asset, """"policyOffsetDays":null"""))
        assertEquals(nulled.bodyText(), 200, nulled.status)
        assertEquals(ServicePolicy.CONTINUOUS, stored(pre.id).servicePolicy)
        assertNull(stored(pre.id).policyOffsetDays)

        // `servicePolicy` alone is the 1.4 form too.
        val back = api.call("PATCH", "/v1/schedules/${pre.id}", body(asset, """"servicePolicy":"PRE_SERVICE","policyOffsetDays":-7"""))
        assertEquals(back.bodyText(), 200, back.status)
        assertEquals(-7, stored(pre.id).policyOffsetDays)
        val clamped = create(body(asset, """"servicePolicy":"IN_SERVICE_RESUME_CLAMPED"""", title = "Shear pins"))
        assertEquals("IN_SERVICE_RESUME_CLAMPED", clamped.servicePolicy)
        assertNull(clamped.policyOffsetDays)
    }

    /**
     * Neither key is the **legacy form**, with 1.3's omitted-field defaults: no season field at all
     * is `IGNORE` (CONTINUOUS), `FOLLOW_ASSET` alone is AT_START at 0, and a re-entry without a
     * behaviour is still `IGNORE`. So a body with neither, sent over a PRE_SERVICE schedule, is the
     * legacy form and refused rather than read as a 1.4 body that clears the policy.
     */
    @Test fun neitherIsTheLegacyFormWith1_3Defaults() {
        val asset = calendarAsset()
        val plain = create(body(asset))
        assertEquals("CONTINUOUS", plain.servicePolicy)
        assertEquals(listOf("IGNORE", null, null), listOf(plain.seasonBehavior, plain.seasonReentry, plain.seasonReentryOffsetDays))

        val follow = create(body(asset, """"seasonBehavior":"FOLLOW_ASSET"""", title = "Skid shoes"))
        assertEquals("IN_SERVICE_AT_START", follow.servicePolicy)
        assertEquals(0, follow.policyOffsetDays)

        val reentryOnly = create(body(asset, """"seasonReentry":"RESUME_CLAMPED"""", title = "Chute"))
        assertEquals("CONTINUOUS", reentryOnly.servicePolicy)

        val pre = create(body(asset, """"servicePolicy":"PRE_SERVICE","policyOffsetDays":-14""", title = "Tune-up"))
        val refused = api.call("PATCH", "/v1/schedules/${pre.id}", body(asset, title = "Tune-up"))
        assertEquals(refused.bodyText(), 422, refused.status)
        assertEquals("LEGACY_WRITE_CANNOT_REPRESENT", refused.errorDetail().code)
        assertEquals(ServicePolicy.PRE_SERVICE, stored(pre.id).servicePolicy)
    }

    /**
     * A legacy key **and** a 1.4 key in one body is 422 `LEGACY_AND_CURRENT_FIELDS_MIXED`, writing
     * nothing — on a create and on a PATCH, and with explicit `null`s, which count as present.
     */
    @Test fun aMixedBodyIs422AndWritesNothing() {
        val asset = calendarAsset()
        for (season in listOf(
            """"seasonBehavior":"FOLLOW_ASSET","servicePolicy":"IN_SERVICE_AT_START"""",
            """"seasonBehavior":"IGNORE","policyOffsetDays":null""",
            """"seasonReentry":null,"servicePolicy":"CONTINUOUS"""",
        )) {
            val refused = api.call("POST", "/v1/schedules", body(asset, season))
            assertEquals(season, 422, refused.status)
            assertEquals(season, "LEGACY_AND_CURRENT_FIELDS_MIXED", refused.errorDetail().code)
        }
        assertEquals(
            listOf("seasonBehavior", "servicePolicy"),
            api.call("POST", "/v1/schedules", body(asset, """"servicePolicy":"CONTINUOUS","seasonBehavior":"IGNORE""""))
                .errorDetail().problems,
        )
        assertTrue(runBlocking { graph.schedules.all() }.isEmpty())

        val row = create(body(asset))
        val before = stored(row.id)
        graph.now = dayMillis("2026-02-05")
        val patched = api.call(
            "PATCH", "/v1/schedules/${row.id}",
            body(asset, """"seasonReentryOffsetDays":null,"policyOffsetDays":0"""),
        )
        assertEquals(patched.bodyText(), 422, patched.status)
        assertEquals("LEGACY_AND_CURRENT_FIELDS_MIXED", patched.errorDetail().code)
        assertEquals(before, stored(row.id))
    }

    // --- refused, never reset ------------------------------------------------------------------

    /**
     * A legacy PATCH of a PRE_SERVICE schedule — which 1.3's fields cannot spell — is 422
     * `LEGACY_WRITE_CANNOT_REPRESENT` and writes nothing: the row, its policy, its offset and its
     * `updatedAt` are exactly as they were. The row's own null triple sent back is refused by name.
     */
    @Test fun aLegacyPatchOnPreServiceIs422AndWritesNothing() {
        val asset = calendarAsset()
        val pre = create(body(asset, """"servicePolicy":"PRE_SERVICE","policyOffsetDays":-14"""))
        val before = stored(pre.id)
        graph.now = dayMillis("2026-02-05")

        for (season in listOf(
            """"seasonBehavior":"IGNORE"""",
            """"seasonBehavior":"FOLLOW_ASSET","seasonReentry":"AT_START","seasonReentryOffsetDays":0""",
            """"seasonBehavior":null,"seasonReentry":null,"seasonReentryOffsetDays":null""",
        )) {
            val refused = api.call("PATCH", "/v1/schedules/${pre.id}", body(asset, season))
            assertEquals(season, 422, refused.status)
            assertEquals(season, "LEGACY_WRITE_CANNOT_REPRESENT", refused.errorDetail().code)
            assertEquals(season, before, stored(pre.id))
        }
    }

    /**
     * The asset command's pair is its one compatibility input: a **different** pair on a MANUAL
     * asset is 422 `LEGACY_WRITE_CANNOT_REPRESENT` and writes nothing — not the asset row, not an
     * activation, not a recompute.
     */
    @Test fun aLegacyPairChangeOnAManualAssetIs422AndWritesNothing() {
        val tub = api.asset("Hot tub")
        api.ok(
            AssetSeasonResponse.serializer(), "POST", "/v1/assets/$tub/season-mode",
            """{"seasonMode":"MANUAL","manualPhase":"IN_SEASON"}""",
        )
        val before = runBlocking { graph.assets.get(AssetId(tub))!! }
        val activations = runBlocking { graph.seasonActivations.all() }
        graph.now = dayMillis("2026-02-05")

        val refused = api.call(
            "PATCH", "/v1/assets/$tub",
            """{"name":"Hot tub","seasonStartMmdd":"04-01","seasonEndMmdd":"10-31"}""",
        )
        assertEquals(refused.bodyText(), 422, refused.status)
        assertEquals("LEGACY_WRITE_CANNOT_REPRESENT", refused.errorDetail().code)
        assertEquals(before, runBlocking { graph.assets.get(AssetId(tub)) })
        assertEquals(activations, runBlocking { graph.seasonActivations.all() })
    }

    /**
     * The same asset sent back with the pair it already reports — none, on a MANUAL asset — is an
     * ordinary edit (inv. 128): the name changes, and the season stays MANUAL with its one row.
     */
    @Test fun anEqualPairOnAManualAssetIsAccepted() {
        val tub = api.asset("Hot tub")
        api.ok(
            AssetSeasonResponse.serializer(), "POST", "/v1/assets/$tub/season-mode",
            """{"seasonMode":"MANUAL","manualPhase":"IN_SEASON"}""",
        )
        val edited = api.call("PATCH", "/v1/assets/$tub", """{"name":"Hot tub (deck)","seasonStartMmdd":null,"seasonEndMmdd":null}""")
        assertEquals(edited.bodyText(), 200, edited.status)
        val after = runBlocking { graph.assets.get(AssetId(tub))!! }
        assertEquals("Hot tub (deck)", after.name)
        assertEquals(SeasonMode.MANUAL, after.seasonMode)
        assertEquals(1, runBlocking { graph.seasonActivations.all() }.size)
    }

    // --- the documented change and the 1.4 defaults --------------------------------------------

    /**
     * The one documented behaviour change (spec §9.3): a legacy body that says `FOLLOW_ASSET` and
     * omits `seasonReentry` on a RESUME_CLAMPED schedule becomes AT_START at 0 — the table reads an
     * absent re-entry as AT_START. Echoing the re-entry keeps it.
     */
    @Test fun omittingSeasonReentryOnResumeClampedBecomesAtStart() {
        val asset = calendarAsset()
        val clamped = create(body(asset, """"servicePolicy":"IN_SERVICE_RESUME_CLAMPED""""))

        val kept = api.call(
            "PATCH", "/v1/schedules/${clamped.id}",
            body(asset, """"seasonBehavior":"FOLLOW_ASSET","seasonReentry":"RESUME_CLAMPED""""),
        )
        assertEquals(kept.bodyText(), 200, kept.status)
        assertEquals(ServicePolicy.IN_SERVICE_RESUME_CLAMPED, stored(clamped.id).servicePolicy)

        val omitted = api.call("PATCH", "/v1/schedules/${clamped.id}", body(asset, """"seasonBehavior":"FOLLOW_ASSET""""))
        assertEquals(omitted.bodyText(), 200, omitted.status)
        assertEquals(ServicePolicy.IN_SERVICE_AT_START, stored(clamped.id).servicePolicy)
        assertEquals(0, stored(clamped.id).policyOffsetDays)
        assertEquals(listOf(JsonPrimitive("FOLLOW_ASSET"), JsonPrimitive("AT_START"), JsonPrimitive(0)), wireRow(omitted).triple())
    }

    /**
     * The 1.4 form's defaults (spec §4.2; plan decision 31): an omitted **or null** offset is 0 on
     * IN_SERVICE_AT_START, stored as 0; PRE_SERVICE has no default at all, so an omitted or null
     * offset is 422 `POLICY_OFFSET_INVALID`, `field` `policyOffsetDays`, and nothing is written.
     */
    @Test fun anOmittedOffsetIsZeroForAtStartAnd422ForPreService() {
        val asset = calendarAsset()
        val omitted = create(body(asset, """"servicePolicy":"IN_SERVICE_AT_START""""))
        assertEquals(0, stored(omitted.id).policyOffsetDays)
        val nulled = create(body(asset, """"servicePolicy":"IN_SERVICE_AT_START","policyOffsetDays":null""", title = "Skid shoes"))
        assertEquals(0, stored(nulled.id).policyOffsetDays)
        val count = runBlocking { graph.schedules.all() }.size

        for (season in listOf(""""servicePolicy":"PRE_SERVICE"""", """"servicePolicy":"PRE_SERVICE","policyOffsetDays":null""")) {
            val refused = api.call("POST", "/v1/schedules", body(asset, season, title = "Tune-up"))
            assertEquals(season, 422, refused.status)
            assertEquals(season, "POLICY_OFFSET_INVALID", refused.errorDetail().code)
            assertEquals(season, "policyOffsetDays", refused.errorDetail().field)
        }
        assertEquals(count, runBlocking { graph.schedules.all() }.size)
    }

    // --- the compatibility triple --------------------------------------------------------------

    /**
     * Every representable schedule reads a triple that, sent back in the legacy form, lands on the
     * same policy and offset — and moves no rule floor (spec §9.3, "the derived triple round-trips
     * every representable schedule"). A meter-only AT_START schedule is included: its offset is 0.
     */
    @Test fun everyRepresentableRowRoundTripsThroughItsTriple() {
        val asset = calendarAsset()
        val meter = api.ok(
            DefinitionResponse.serializer(), "POST", "/v1/definitions",
            """{"assetId":"$asset","label":"Engine hours","unit":"h","isMeter":true}""",
        ).definition.id
        val meterOnly = """{"title":"Oil","targetAssetId":"$asset","meterDefinitionId":"$meter","meterInterval":25.0,"anchorMeter":0.0"""

        val cases = listOf(
            body(asset, """"servicePolicy":"CONTINUOUS"""", title = "c"),
            body(asset, """"servicePolicy":"IN_SERVICE_AT_START","policyOffsetDays":0""", title = "a0"),
            body(asset, """"servicePolicy":"IN_SERVICE_AT_START","policyOffsetDays":5""", title = "a5"),
            body(asset, """"servicePolicy":"IN_SERVICE_AT_START","policyOffsetDays":365""", title = "a365"),
            body(asset, """"servicePolicy":"IN_SERVICE_RESUME_CLAMPED"""", title = "r"),
            """$meterOnly,"servicePolicy":"IN_SERVICE_AT_START"}""",
            """$meterOnly,"servicePolicy":"IN_SERVICE_RESUME_CLAMPED"}""",
        )
        for (case in cases) {
            val created = api.call("POST", "/v1/schedules", case)
            assertEquals(created.bodyText(), 201, created.status)
            val row = wireRow(created)
            val id = (row.getValue("id") as JsonPrimitive).content
            val before = stored(id)

            // The same command, in the legacy form, carrying the triple exactly as the row reported it.
            val legacy = JsonObject(
                ApiJson.parseToJsonElement(case).jsonObject.filterKeys { it !in ScheduleForms.CURRENT_KEYS } +
                    ScheduleForms.LEGACY_KEYS.associateWith { row.getValue(it) },
            )
            graph.now += 1_000
            val echoed = api.call("PATCH", "/v1/schedules/$id", legacy.toString())
            assertEquals(echoed.bodyText(), 200, echoed.status)
            val after = stored(id)
            assertEquals(case, before.servicePolicy, after.servicePolicy)
            assertEquals(case, before.policyOffsetDays, after.policyOffsetDays)
            assertEquals(case, before.ruleChangedAt, after.ruleChangedAt)
            assertEquals(case, row.triple(), wireRow(echoed).triple())
        }
    }

    /**
     * PRE_SERVICE has no 1.3 spelling: its row reports `null` in all three derived fields — present,
     * not absent — beside its own policy and offset, and writing that back is refused by name.
     */
    @Test fun aPreServiceRowReadsNulls() {
        val asset = calendarAsset()
        val pre = create(body(asset, """"servicePolicy":"PRE_SERVICE","policyOffsetDays":-14"""))
        val row = wireRow(api.call("GET", "/v1/schedules/${pre.id}"))
        assertEquals(listOf(JsonNull, JsonNull, JsonNull), row.triple())
        assertEquals(JsonPrimitive("PRE_SERVICE"), row["servicePolicy"])
        assertEquals(JsonPrimitive(-14), row["policyOffsetDays"])

        val sentBack = api.call(
            "PATCH", "/v1/schedules/${pre.id}",
            body(asset, """"seasonBehavior":null,"seasonReentry":null,"seasonReentryOffsetDays":null"""),
        )
        assertEquals("LEGACY_WRITE_CANNOT_REPRESENT", sentBack.errorDetail().code)
    }

    // --- group targets --------------------------------------------------------------------------

    /**
     * A group target is CONTINUOUS only, **in both forms** (inv. 106): the legacy `FOLLOW_ASSET` and
     * every non-CONTINUOUS 1.4 policy are 422 `SEASON_FOLLOWS_ASSET_ON_GROUP_TARGET` and write
     * nothing; CONTINUOUS — said either way — is accepted.
     */
    @Test fun aGroupTargetIsContinuousOnlyInBothForms() {
        val pump = api.asset("Pump A")
        val group = api.ok(
            GroupResponse.serializer(), "POST", "/v1/groups",
            """{"name":"North run","members":[{"assetId":"$pump"}]}""", status = 201,
        ).group.id
        fun groupBody(season: String, title: String = "Winterise") =
            """{"title":"$title","targetGroupId":"$group","timeInterval":1,"timeUnit":"YEAR","anchorOn":"2026-02-01",$season}"""

        for (season in listOf(
            """"seasonBehavior":"FOLLOW_ASSET"""",
            """"seasonBehavior":"FOLLOW_ASSET","seasonReentry":"RESUME_CLAMPED"""",
            """"servicePolicy":"IN_SERVICE_AT_START"""",
            """"servicePolicy":"IN_SERVICE_RESUME_CLAMPED"""",
            """"servicePolicy":"PRE_SERVICE","policyOffsetDays":-14""",
        )) {
            val refused = api.call("POST", "/v1/schedules", groupBody(season))
            assertEquals(season, 422, refused.status)
            assertEquals(season, "SEASON_FOLLOWS_ASSET_ON_GROUP_TARGET", refused.errorDetail().code)
        }
        assertTrue(runBlocking { graph.schedules.all() }.isEmpty())

        assertEquals("CONTINUOUS", create(groupBody(""""seasonBehavior":"IGNORE"""", "a")).servicePolicy)
        assertEquals("CONTINUOUS", create(groupBody(""""servicePolicy":"CONTINUOUS"""", "b")).servicePolicy)
    }
}
