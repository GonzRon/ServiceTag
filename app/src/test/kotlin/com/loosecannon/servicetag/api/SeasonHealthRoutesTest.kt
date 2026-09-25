package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.model.HealthSubjectId
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.dayMillis
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 1.4 (B09) — the fourteen new `/v1` rows and every refusal they and the widened schedule command
 * can raise, over `FakeGraph`, on the JVM (spec §9.1, §9.2; master plan §11). **Every named refusal
 * has its own case**, asserting the status **and** the code — and, for the codes master plan §11.3
 * names a key for, the envelope's `field` — so no 1.4 refusal can fall through to a 500 unnoticed.
 *
 * The 422/409 tie-break (RN-8) is what each status below encodes: 422 when the fix is in this body,
 * 409 when another row must change first.
 */
class SeasonHealthRoutesTest {

    private val graph = FakeGraph().apply {
        now = dayMillis("2026-02-01")
        today = LocalDate.parse("2026-02-10")
    }
    private val api = V1Client(graph)

    @After fun close() = graph.close()

    // --- fixtures -------------------------------------------------------------------------------

    private fun call(method: String, path: String, body: String = "") = api.call(method, path, body)

    /** Asserts a refusal's status, code and `field`, and hands the envelope back. */
    private fun refused(response: ApiResponse, status: Int, code: String, field: String? = null): ApiErrorDetail {
        assertEquals(response.bodyText(), status, response.status)
        val error = response.errorDetail()
        assertEquals(response.bodyText(), code, error.code)
        assertEquals(response.bodyText(), field, error.field)
        return error
    }

    private fun seasonMode(asset: String, body: String) =
        api.ok(AssetSeasonResponse.serializer(), "POST", "/v1/assets/$asset/season-mode", body)

    private fun calendar(asset: String) =
        seasonMode(asset, """{"seasonMode":"CALENDAR","seasonStartMmdd":"11-01","seasonEndMmdd":"03-31"}""")

    private fun manual(asset: String, phase: String) =
        seasonMode(asset, """{"seasonMode":"MANUAL","manualPhase":"$phase"}""")

    /** A yearly time-rule schedule on [asset], with [season] (a JSON fragment) appended. */
    private fun schedule(asset: String, title: String = "Service", season: String = ""): String {
        val tail = if (season.isEmpty()) "" else ",$season"
        return api.ok(
            ScheduleResponse.serializer(), "POST", "/v1/schedules",
            """{"title":"$title","targetAssetId":"$asset","timeInterval":1,"timeUnit":"YEAR","anchorOn":"2026-02-01"$tail}""",
            status = 201,
        ).schedule.id
    }

    private fun meter(asset: String) = api.ok(
        DefinitionResponse.serializer(), "POST", "/v1/definitions",
        """{"assetId":"$asset","label":"Engine hours","unit":"h","isMeter":true}""",
    ).definition.id

    private fun meterOnlySchedule(asset: String, meter: String, season: String = ""): ApiResponse {
        val tail = if (season.isEmpty()) "" else ",$season"
        return call(
            "POST", "/v1/schedules",
            """{"title":"Oil","targetAssetId":"$asset","meterDefinitionId":"$meter","meterInterval":25.0,"anchorMeter":0.0$tail}""",
        )
    }

    private fun subjectBody(
        asset: String,
        name: String = "Filter",
        driver: String = "AGE",
        schedule: String? = null,
        extra: String = """"nominalUntilDays":0,"warningFromDays":30,"criticalFromDays":60""",
    ): String {
        val link = schedule?.let { ""","scheduleId":"$it"""" } ?: ""
        val tail = if (extra.isEmpty()) "" else ",$extra"
        return """{"assetId":"$asset","name":"$name","kind":"PART","driver":"$driver"$link$tail}"""
    }

    private fun subject(asset: String, name: String = "Filter", driver: String = "AGE", schedule: String? = null): String =
        api.ok(SubjectResponse.serializer(), "POST", "/v1/health-subjects", subjectBody(asset, name, driver, schedule), status = 201)
            .subject.id

    private fun event(asset: String): String = api.ok(
        EventResponse.serializer(), "POST", "/v1/events",
        """{"assetId":"$asset","kind":"NOTE","title":"Beeping","occurredOn":"2026-02-09","tzId":"UTC"}""", status = 201,
    ).event.id

    // --- one of each status class ---------------------------------------------------------------

    /** 200, 201, 404, 405, 409 and 422 over the new surface, one assertion each. */
    @Test fun oneOfEach() {
        val tub = api.asset("Hot tub")
        assertEquals(200, call("GET", "/v1/assets/$tub/season").status)
        assertEquals(201, call("POST", "/v1/assets/$tub/conditions", """{"condition":"DOWN","tzId":"UTC"}""").status)
        refused(call("GET", "/v1/health-subjects/00000000-0000-4000-8000-999999999999"), 404, "NO_SUCH_HEALTH_SUBJECT")
        refused(call("GET", "/v1/health-subjects"), 405, "method_not_allowed")
        refused(call("POST", "/v1/assets/$tub/season", """{"action":"START"}"""), 409, "SEASON_NOT_MANUAL")
        refused(
            call("POST", "/v1/assets/$tub/conditions", """{"condition":"DOWN","tzId":"UTC","occurredOn":"2026-02-11"}"""),
            422, "CONDITION_DATE_IN_FUTURE", "occurredOn",
        )
    }

    // --- 422: compatibility ---------------------------------------------------------------------

    @Test fun legacyWriteCannotRepresentIs422OnAManualPairAndAPreServicePatch() {
        val tub = api.asset("Hot tub")
        manual(tub, "IN_SEASON")
        refused(
            call("PATCH", "/v1/assets/$tub", """{"name":"Hot tub","seasonStartMmdd":"04-01","seasonEndMmdd":"10-31"}"""),
            422, "LEGACY_WRITE_CANNOT_REPRESENT",
        )

        val snow = api.asset("Snowblower")
        calendar(snow)
        val pre = schedule(snow, "Tune-up", """"servicePolicy":"PRE_SERVICE","policyOffsetDays":-14""")
        refused(
            call(
                "PATCH", "/v1/schedules/$pre",
                """{"title":"Tune-up","targetAssetId":"$snow","timeInterval":1,"timeUnit":"YEAR","anchorOn":"2026-02-01","seasonBehavior":"IGNORE"}""",
            ),
            422, "LEGACY_WRITE_CANNOT_REPRESENT",
        )
    }

    @Test fun legacyAndCurrentFieldsMixedIs422() {
        val snow = api.asset("Snowblower")
        refused(
            call(
                "POST", "/v1/schedules",
                """{"title":"x","targetAssetId":"$snow","timeInterval":1,"timeUnit":"YEAR","anchorOn":"2026-02-01",
                   "seasonBehavior":"IGNORE","servicePolicy":"CONTINUOUS"}""",
            ),
            422, "LEGACY_AND_CURRENT_FIELDS_MIXED",
        )
    }

    // --- 422: policy ---------------------------------------------------------------------------

    @Test fun seasonPolicyNeedsATimeRuleIs422() {
        val snow = api.asset("Snowblower")
        calendar(snow)
        val hours = meter(snow)
        refused(meterOnlySchedule(snow, hours, """"servicePolicy":"PRE_SERVICE","policyOffsetDays":-14"""), 422, "SEASON_POLICY_NEEDS_A_TIME_RULE")
        refused(meterOnlySchedule(snow, hours, """"servicePolicy":"IN_SERVICE_AT_START","policyOffsetDays":5"""), 422, "SEASON_POLICY_NEEDS_A_TIME_RULE")
    }

    @Test fun seasonFollowsAssetOnGroupTargetIs422() {
        val pump = api.asset("Pump A")
        val group = api.ok(
            GroupResponse.serializer(), "POST", "/v1/groups", """{"name":"North run","members":[{"assetId":"$pump"}]}""", status = 201,
        ).group.id
        refused(
            call(
                "POST", "/v1/schedules",
                """{"title":"x","targetGroupId":"$group","timeInterval":1,"timeUnit":"YEAR","anchorOn":"2026-02-01",
                   "servicePolicy":"IN_SERVICE_AT_START"}""",
            ),
            422, "SEASON_FOLLOWS_ASSET_ON_GROUP_TARGET",
        )
    }

    @Test fun policyOffsetInvalidIs422WithItsField() {
        val snow = api.asset("Snowblower")
        refused(
            call(
                "POST", "/v1/schedules",
                """{"title":"x","targetAssetId":"$snow","timeInterval":1,"timeUnit":"YEAR","anchorOn":"2026-02-01",
                   "servicePolicy":"IN_SERVICE_AT_START","policyOffsetDays":400}""",
            ),
            422, "POLICY_OFFSET_INVALID", "policyOffsetDays",
        )
    }

    // --- 422: season and break ----------------------------------------------------------------

    @Test fun seasonWindowRequiredIs422WithItsField() {
        val mower = api.asset("Mower")
        refused(
            call("POST", "/v1/assets/$mower/season-mode", """{"seasonMode":"CALENDAR","seasonStartMmdd":"04-01"}"""),
            422, "SEASON_WINDOW_REQUIRED", "seasonStartMmdd",
        )
    }

    @Test fun seasonWindowForbiddenIs422WithItsField() {
        val mower = api.asset("Mower")
        refused(
            call(
                "POST", "/v1/assets/$mower/season-mode",
                """{"seasonMode":"YEAR_ROUND","seasonStartMmdd":"04-01","seasonEndMmdd":"10-31"}""",
            ),
            422, "SEASON_WINDOW_FORBIDDEN", "seasonStartMmdd",
        )
    }

    @Test fun manualPhaseRequiredIs422WithItsField() {
        val tub = api.asset("Hot tub")
        refused(call("POST", "/v1/assets/$tub/season-mode", """{"seasonMode":"MANUAL"}"""), 422, "MANUAL_PHASE_REQUIRED", "manualPhase")
    }

    @Test fun manualPhaseForbiddenIs422WithItsField() {
        val tub = api.asset("Hot tub")
        refused(
            call(
                "POST", "/v1/assets/$tub/season-mode",
                """{"seasonMode":"CALENDAR","seasonStartMmdd":"04-01","seasonEndMmdd":"10-31","manualPhase":"IN_SEASON"}""",
            ),
            422, "MANUAL_PHASE_FORBIDDEN", "manualPhase",
        )
    }

    /** A future activation, and on a MANUAL asset one dated before its latest row. */
    @Test fun seasonDateOutOfRangeIs422WithItsField() {
        val tub = api.asset("Hot tub")
        manual(tub, "OUT_OF_SEASON")
        refused(
            call("POST", "/v1/assets/$tub/season", """{"action":"START","occurredOn":"2026-02-11"}"""),
            422, "SEASON_DATE_OUT_OF_RANGE", "occurredOn",
        )
        refused(
            call("POST", "/v1/assets/$tub/season", """{"action":"START","occurredOn":"2026-02-01"}"""),
            422, "SEASON_DATE_OUT_OF_RANGE", "occurredOn",
        )
    }

    @Test fun blackoutCoversTheYearIs422() {
        val generator = api.asset("Generator")
        refused(
            call("POST", "/v1/assets/$generator/maintenance-break", """{"blackoutStartMmdd":"01-01","blackoutEndMmdd":"12-31"}"""),
            422, "BLACKOUT_COVERS_THE_YEAR",
        )
    }

    /**
     * Spec §9.2: a malformed `MM-DD`, `occurredOn`, `occurredTime` or `tzId` — and a break with one
     * bound — keeps the shipped validation shape: 422, the `…_validation` code and its message,
     * `problems` naming the field. Since #52 the envelope's `field` names it too.
     */
    @Test fun aMalformedValueKeepsTheShippedValidationShape() {
        val mower = api.asset("Mower")
        val mmdd = refused(
            call("POST", "/v1/assets/$mower/season-mode", """{"seasonMode":"CALENDAR","seasonStartMmdd":"13-45","seasonEndMmdd":"10-31"}"""),
            422, "season_validation", "seasonStartMmdd",
        )
        assertEquals(listOf("BadDate(field=seasonStartMmdd)"), mmdd.problems)
        assertEquals("the season command was refused", mmdd.message)
        val bound = refused(
            call("POST", "/v1/assets/$mower/maintenance-break", """{"blackoutStartMmdd":"13-45","blackoutEndMmdd":"02-28"}"""),
            422, "season_validation", "blackoutStartMmdd",
        )
        assertEquals(listOf("BadDate(field=blackoutStartMmdd)"), bound.problems)
        assertEquals("the season command was refused", bound.message)
        val half = refused(
            call("POST", "/v1/assets/$mower/maintenance-break", """{"blackoutStartMmdd":"12-01","blackoutEndMmdd":null}"""),
            422, "season_validation",
        )
        assertEquals(listOf("BothOrNeither"), half.problems)
        assertEquals("the season command was refused", half.message)

        val date = refused(
            call("POST", "/v1/assets/$mower/conditions", """{"condition":"DOWN","tzId":"UTC","occurredOn":"not a date"}"""),
            422, "condition_validation", "occurredOn",
        )
        assertEquals(listOf("BadDate(field=occurredOn)"), date.problems)
        assertEquals("the condition was refused", date.message)
        val time = refused(
            call("POST", "/v1/assets/$mower/conditions", """{"condition":"DOWN","tzId":"UTC","occurredTime":"25:00"}"""),
            422, "condition_validation", "occurredTime",
        )
        assertEquals(listOf("BadTime(field=occurredTime)"), time.problems)
        assertEquals("the condition was refused", time.message)
        val zone = refused(
            call("POST", "/v1/assets/$mower/conditions", """{"condition":"DOWN","tzId":"not a zone"}"""),
            422, "condition_validation", "tzId",
        )
        assertEquals(listOf("BadTimeZone(field=tzId)"), zone.problems)
        assertEquals("the condition was refused", zone.message)
        assertTrue(runBlocking { graph.conditions.all() }.isEmpty())
    }

    // --- 422: condition --------------------------------------------------------------------------

    @Test fun conditionDateInFutureIs422WithItsField() {
        val ups = api.asset("UPS")
        refused(
            call("POST", "/v1/assets/$ups/conditions", """{"condition":"DEGRADED","tzId":"UTC","occurredOn":"2026-03-01"}"""),
            422, "CONDITION_DATE_IN_FUTURE", "occurredOn",
        )
    }

    @Test fun conditionReasonTooLongIs422WithItsField() {
        val ups = api.asset("UPS")
        refused(
            call("POST", "/v1/assets/$ups/conditions", """{"condition":"DEGRADED","tzId":"UTC","reason":"${"x".repeat(501)}"}"""),
            422, "CONDITION_REASON_TOO_LONG", "reason",
        )
    }

    /** Another asset's event is `FOREIGN_EVENT` on a condition and on an activation alike. */
    @Test fun foreignEventIs422WithItsField() {
        val ups = api.asset("UPS")
        val tub = api.asset("Hot tub")
        manual(tub, "OUT_OF_SEASON")
        val elsewhere = event(ups)
        refused(
            call("POST", "/v1/assets/$tub/conditions", """{"condition":"DOWN","tzId":"UTC","eventId":"$elsewhere"}"""),
            422, "FOREIGN_EVENT", "eventId",
        )
        refused(
            call("POST", "/v1/assets/$tub/season", """{"action":"START","eventId":"$elsewhere"}"""),
            422, "FOREIGN_EVENT", "eventId",
        )
    }

    // --- 422: health ------------------------------------------------------------------------------

    /** The remedy is the flag in this body; the refusal names the subject that depends on it. */
    @Test fun scheduleDrivesHealthSubjectIs422AndNamesTheSubject() {
        val generator = api.asset("Generator")
        val service = schedule(generator)
        val oil = subject(generator, "Oil", "MAINTENANCE_OVERDUE", service)
        val error = refused(
            call("POST", "/v1/schedules/$service/archive", """{"archived":true}"""),
            422, "SCHEDULE_DRIVES_HEALTH_SUBJECT",
        )
        assertEquals(listOf("ScheduleDrivesHealthSubject(subjectId=$oil, name=Oil)"), error.problems)
    }

    /**
     * Plan decision 33: blank and over-long are the one code, `field` `name`, and the message states
     * the 1–60 limit, so an over-long name is not read as a missing one.
     */
    @Test fun healthSubjectNameRequiredStatesTheLimitForBlankAndOverLong() {
        val pack = api.asset("Battery pack")
        for (name in listOf("   ", "n".repeat(61))) {
            val error = refused(
                call("POST", "/v1/health-subjects", subjectBody(pack, name = name)),
                422, "HEALTH_SUBJECT_NAME_REQUIRED", "name",
            )
            assertTrue(error.message, "1–60" in error.message)
        }
        assertTrue(runBlocking { graph.healthSubjects.all() }.isEmpty())
    }

    @Test fun healthThresholdsInvalidIs422WithItsField() {
        val pack = api.asset("Battery pack")
        refused(call("POST", "/v1/health-subjects", subjectBody(pack, extra = "")), 422, "HEALTH_THRESHOLDS_INVALID", "criticalFromDays")
        refused(
            call(
                "POST", "/v1/health-subjects",
                subjectBody(pack, extra = """"nominalUntilDays":0,"warningFromDays":60,"criticalFromDays":30"""),
            ),
            422, "HEALTH_THRESHOLDS_INVALID", "criticalFromDays",
        )
    }

    @Test fun healthDriverMismatchIs422() {
        val generator = api.asset("Generator")
        val service = schedule(generator)
        refused(call("POST", "/v1/health-subjects", subjectBody(generator, driver = "AGE", schedule = service)), 422, "HEALTH_DRIVER_MISMATCH")
    }

    /** Another asset's schedule is `FOREIGN_SCHEDULE`; so is an archived one, whose message says so. */
    @Test fun foreignScheduleIs422AndSaysArchivedForAnArchivedSchedule() {
        val generator = api.asset("Generator")
        val mower = api.asset("Mower")
        val theirs = schedule(mower, "Blades")
        val foreign = refused(
            call("POST", "/v1/health-subjects", subjectBody(generator, driver = "MAINTENANCE_OVERDUE", schedule = theirs)),
            422, "FOREIGN_SCHEDULE",
        )
        assertTrue(foreign.message, "archived" !in foreign.message)

        val retired = schedule(generator, "Old service")
        assertEquals(200, call("POST", "/v1/schedules/$retired/archive", """{"archived":true}""").status)
        val archived = refused(
            call("POST", "/v1/health-subjects", subjectBody(generator, driver = "MAINTENANCE_OVERDUE", schedule = retired)),
            422, "FOREIGN_SCHEDULE",
        )
        assertTrue(archived.message, "archived" in archived.message)
    }

    @Test fun healthScheduleNeedsATimeRuleIs422() {
        val generator = api.asset("Generator")
        val meterOnly = meterOnlySchedule(generator, meter(generator))
        assertEquals(meterOnly.bodyText(), 201, meterOnly.status)
        val id = ApiJson.decodeFromString(ScheduleResponse.serializer(), meterOnly.bodyText()).schedule.id
        refused(
            call("POST", "/v1/health-subjects", subjectBody(generator, driver = "MAINTENANCE_OVERDUE", schedule = id)),
            422, "HEALTH_SCHEDULE_NEEDS_A_TIME_RULE",
        )
    }

    @Test fun profileNotAReplacementIs422() {
        val ups = api.asset("UPS")
        val profile = api.ok(
            ProfileResponse.serializer(), "POST", "/v1/profiles",
            """{"assetId":"$ups","name":"Self test","eventKind":"INSPECTION"}""",
        ).profile.id
        val body = subjectBody(ups).replace(""""driver":"AGE"""", """"driver":"AGE","baselineProfileId":"$profile"""")
        refused(call("POST", "/v1/health-subjects", body), 422, "PROFILE_NOT_A_REPLACEMENT")
    }

    @Test fun healthWeightOutOfRangeIs422WithItsField() {
        val ups = api.asset("UPS")
        refused(
            call("POST", "/v1/health-subjects", subjectBody(ups, extra = """"nominalUntilDays":0,"warningFromDays":30,"criticalFromDays":60,"weight":11""")),
            422, "HEALTH_WEIGHT_OUT_OF_RANGE", "weight",
        )
    }

    @Test fun healthPrimaryInvalidIs422WithItsField() {
        val ups = api.asset("UPS")
        refused(
            call("POST", "/v1/assets/$ups/health-policy", """{"healthAggregation":"TRACK_ONE"}"""),
            422, "HEALTH_PRIMARY_INVALID", "healthPrimarySubjectId",
        )
    }

    // --- 409 ------------------------------------------------------------------------------------

    @Test fun seasonNotManualIs409() {
        val mower = api.asset("Mower")
        refused(call("POST", "/v1/assets/$mower/season", """{"action":"END"}"""), 409, "SEASON_NOT_MANUAL")
    }

    @Test fun seasonAlreadyStartedIs409() {
        val tub = api.asset("Hot tub")
        manual(tub, "IN_SEASON")
        refused(call("POST", "/v1/assets/$tub/season", """{"action":"START"}"""), 409, "SEASON_ALREADY_STARTED")
    }

    @Test fun seasonAlreadyEndedIs409() {
        val tub = api.asset("Hot tub")
        manual(tub, "OUT_OF_SEASON")
        refused(call("POST", "/v1/assets/$tub/season", """{"action":"END"}"""), 409, "SEASON_ALREADY_ENDED")
    }

    /**
     * B04's refusal order: the body's 422 wins over the stored state's 409 when both apply — a
     * future-dated START on an asset that is not MANUAL is the 422.
     */
    @Test fun aBodyRefusalOnAnActivationWinsOverTheStoredStatesRefusal() {
        val mower = api.asset("Mower")
        refused(
            call("POST", "/v1/assets/$mower/season", """{"action":"START","occurredOn":"2026-02-11"}"""),
            422, "SEASON_DATE_OUT_OF_RANGE", "occurredOn",
        )
    }

    /** The remedy is the schedules' policy — another row — and the refusal names each of them. */
    @Test fun seasonModeStrandsPolicyIs409AndNamesTheSchedules() {
        val snow = api.asset("Snowblower")
        calendar(snow)
        val pre = schedule(snow, "Tune-up", """"servicePolicy":"PRE_SERVICE","policyOffsetDays":-14""")
        val error = refused(call("POST", "/v1/assets/$snow/season-mode", """{"seasonMode":"YEAR_ROUND"}"""), 409, "SEASON_MODE_STRANDS_POLICY")
        assertEquals(listOf("StrandedSchedule(id=$pre, title=Tune-up)"), error.problems)
    }

    /**
     * The asset command's pair reaches the same refusal (spec §3.2): a PATCH that leaves the pair out of a
     * CALENDAR asset — a full replace, so the season would become YEAR_ROUND — strands its PRE_SERVICE
     * schedule, is 409 naming it, and leaves the asset row exactly as it was.
     */
    @Test fun anAssetPatchThatDropsThePairIs409SeasonModeStrandsPolicy() {
        val snow = api.asset("Snowblower")
        calendar(snow)
        val pre = schedule(snow, "Tune-up", """"servicePolicy":"PRE_SERVICE","policyOffsetDays":-14""")
        val before = runBlocking { graph.assets.get(com.loosecannon.servicetag.core.model.AssetId(snow)) }
        graph.now = dayMillis("2026-02-05")
        val error = refused(call("PATCH", "/v1/assets/$snow", """{"name":"Snowblower"}"""), 409, "SEASON_MODE_STRANDS_POLICY")
        assertEquals(listOf("StrandedSchedule(id=$pre, title=Tune-up)"), error.problems)
        assertEquals(before, runBlocking { graph.assets.get(com.loosecannon.servicetag.core.model.AssetId(snow)) })
    }

    /**
     * Defence in depth (review M4): a validation refusal that names no problem — unreachable, since every
     * throw site collects one — still answers an envelope, under its family's lower-snake code, rather
     * than throwing out of the mapper.
     */
    @Test fun aValidationRefusalWithNoProblemStillAnswersAnEnvelope() {
        for ((failure, code) in listOf(
            com.loosecannon.servicetag.core.usecase.SeasonValidation(emptyList()) to "season_validation",
            com.loosecannon.servicetag.core.usecase.ConditionValidation(emptyList()) to "condition_validation",
            com.loosecannon.servicetag.core.usecase.HealthValidation(emptyList()) to "health_validation",
        )) {
            refused(mapDomainFailure(failure), 422, code)
        }
    }

    @Test fun breakStrandsPolicyIs409AndNamesTheSchedules() {
        val generator = api.asset("Generator")
        api.ok(
            AssetSeasonResponse.serializer(), "POST", "/v1/assets/$generator/maintenance-break",
            """{"blackoutStartMmdd":"12-01","blackoutEndMmdd":"02-28"}""",
        )
        val pre = schedule(generator, "Load test", """"servicePolicy":"PRE_SERVICE","policyOffsetDays":-7""")
        val error = refused(
            call("POST", "/v1/assets/$generator/maintenance-break", """{"blackoutStartMmdd":null,"blackoutEndMmdd":null}"""),
            409, "BREAK_STRANDS_POLICY",
        )
        assertEquals(listOf("StrandedSchedule(id=$pre, title=Load test)"), error.problems)
    }

    @Test fun preServiceNeedsDatesIs409() {
        val generator = api.asset("Generator")
        refused(
            call(
                "POST", "/v1/schedules",
                """{"title":"x","targetAssetId":"$generator","timeInterval":1,"timeUnit":"YEAR","anchorOn":"2026-02-01",
                   "servicePolicy":"PRE_SERVICE","policyOffsetDays":-14}""",
            ),
            409, "PRE_SERVICE_NEEDS_DATES",
        )
    }

    @Test fun healthScheduleTakenIs409() {
        val generator = api.asset("Generator")
        val service = schedule(generator)
        subject(generator, "Oil", "MAINTENANCE_OVERDUE", service)
        refused(
            call("POST", "/v1/health-subjects", subjectBody(generator, "Oil filter", "MAINTENANCE_OVERDUE", service)),
            409, "HEALTH_SCHEDULE_TAKEN",
        )
    }

    /** The TRACK_ONE primary cannot be archived — directly, or by a schedule's unlink (plan decision 14). */
    @Test fun healthSubjectIsPrimaryIs409() {
        val ups = api.asset("UPS")
        val battery = subject(ups, "Battery")
        assertEquals(
            200,
            call("POST", "/v1/assets/$ups/health-policy", """{"healthAggregation":"TRACK_ONE","healthPrimarySubjectId":"$battery"}""").status,
        )
        refused(call("POST", "/v1/health-subjects/$battery/archive", """{"archived":true}"""), 409, "HEALTH_SUBJECT_IS_PRIMARY")

        val generator = api.asset("Generator")
        val service = schedule(generator)
        val oil = subject(generator, "Oil", "MAINTENANCE_OVERDUE", service)
        assertEquals(
            200,
            call("POST", "/v1/assets/$generator/health-policy", """{"healthAggregation":"TRACK_ONE","healthPrimarySubjectId":"$oil"}""").status,
        )
        refused(
            call("POST", "/v1/schedules/$service/archive", """{"archived":true,"unlinkHealthSubject":true}"""),
            409, "HEALTH_SUBJECT_IS_PRIMARY",
        )
    }

    // --- 404 ------------------------------------------------------------------------------------

    @Test fun noSuchHealthSubjectIs404() {
        val missing = "00000000-0000-4000-8000-999999999999"
        val ups = api.asset("UPS")
        refused(call("GET", "/v1/health-subjects/$missing"), 404, "NO_SUCH_HEALTH_SUBJECT")
        refused(
            call("PATCH", "/v1/health-subjects/$missing", subjectBody(ups).replace(""""assetId":"$ups",""", "")),
            404, "NO_SUCH_HEALTH_SUBJECT",
        )
        refused(call("POST", "/v1/health-subjects/$missing/archive", """{"archived":true}"""), 404, "NO_SUCH_HEALTH_SUBJECT")
    }

    /** Every new asset sub-resource, read or written, answers the shipped `no_such_asset`. */
    @Test fun noSuchAssetIs404OnEveryNewAssetSubResource() {
        val missing = "00000000-0000-4000-8000-999999999999"
        for ((method, path, body) in listOf(
            Triple("GET", "/v1/assets/$missing/season", ""),
            Triple("POST", "/v1/assets/$missing/season", """{"action":"START"}"""),
            Triple("POST", "/v1/assets/$missing/season-mode", """{"seasonMode":"YEAR_ROUND"}"""),
            Triple("POST", "/v1/assets/$missing/maintenance-break", """{"blackoutStartMmdd":null,"blackoutEndMmdd":null}"""),
            Triple("POST", "/v1/assets/$missing/health-policy", """{"healthAggregation":"WORST"}"""),
            Triple("GET", "/v1/assets/$missing/conditions", ""),
            Triple("POST", "/v1/assets/$missing/conditions", """{"condition":"DOWN","tzId":"UTC"}"""),
            Triple("GET", "/v1/assets/$missing/health", ""),
            Triple("GET", "/v1/assets/$missing/health-subjects", ""),
            Triple("POST", "/v1/health-subjects", subjectBody(missing)),
        )) {
            refused(call(method, path, body), 404, "no_such_asset")
        }
    }

    // --- 404 / 405 / 415 ------------------------------------------------------------------------

    /**
     * A new `/v1/assets/{id}/…` sub-resource, and `/v1/health-subjects/{id}/archive`, answer 404 for a
     * verb they do not take (the shipped sub-resource convention); `/v1/health-subjects`,
     * `/v1/health-subjects/{id}` and `/v1/attention` answer 405; the wrong type is 415.
     */
    @Test fun wrongVerbsAndTypesOverTheNewPaths() {
        val ups = api.asset("UPS")
        val battery = subject(ups, "Battery")
        for ((method, path, status) in listOf(
            Triple("PATCH", "/v1/assets/$ups/season", 404),
            Triple("DELETE", "/v1/assets/$ups/season", 404),
            Triple("DELETE", "/v1/assets/$ups/conditions", 404),
            Triple("DELETE", "/v1/assets/$ups/health-subjects", 404),
            Triple("DELETE", "/v1/health-subjects/$battery/archive", 404),
            Triple("GET", "/v1/assets/$ups/season-mode", 404),
            Triple("GET", "/v1/assets/$ups/maintenance-break", 404),
            Triple("GET", "/v1/assets/$ups/health-policy", 404),
            Triple("PATCH", "/v1/assets/$ups/conditions", 404),
            Triple("POST", "/v1/assets/$ups/health", 404),
            Triple("POST", "/v1/assets/$ups/health-subjects", 404),
            Triple("GET", "/v1/health-subjects/$battery/archive", 404),
            Triple("PATCH", "/v1/health-subjects/$battery/archive", 404),
            Triple("GET", "/v1/health-subjects", 405),
            Triple("PATCH", "/v1/health-subjects", 405),
            Triple("POST", "/v1/health-subjects/$battery", 405),
            Triple("DELETE", "/v1/health-subjects/$battery", 405),
            Triple("DELETE", "/v1/attention", 405),
            Triple("POST", "/v1/attention", 405),
            Triple("PATCH", "/v1/attention", 405),
        )) {
            val response = call(method, path, if (method == "GET") "" else "{}")
            refused(response, status, if (status == 404) "not_found" else "method_not_allowed")
        }
        for (path in listOf("/v1/assets/$ups/conditions", "/v1/assets/$ups/season-mode", "/v1/health-subjects")) {
            val wrongType = api.call("POST", path, """{"condition":"DOWN"}""", contentType = "text/plain")
            refused(wrongType, 415, "unsupported_media_type")
        }
    }

    // --- unknown fields -------------------------------------------------------------------------

    /** Every new command, and the schedule's action flag where it is not taken, is a 400 naming the key. */
    @Test fun everyNewCommandRejectsAnUnknownFieldByName() {
        val ups = api.asset("UPS")
        val battery = subject(ups, "Battery")
        val service = schedule(ups)
        for ((path, body) in listOf(
            "/v1/assets/$ups/season-mode" to """{"seasonMode":"YEAR_ROUND","seasonStrtMmdd":"04-01"}""",
            "/v1/assets/$ups/maintenance-break" to """{"blackoutStartMmdd":null,"blackoutEndMmdd":null,"blackotuNote":1}""",
            "/v1/assets/$ups/health-policy" to """{"healthAggregation":"WORST","healthPrimaryId":"x"}""",
            "/v1/assets/$ups/season" to """{"action":"START","occuredOn":"2026-02-10"}""",
            "/v1/assets/$ups/conditions" to """{"condition":"DOWN","tzId":"UTC","reasn":"x"}""",
            "/v1/health-subjects" to subjectBody(ups).replace(""""kind":"PART"""", """"kind":"PART","wieght":2"""),
            "/v1/health-subjects/$battery/archive" to """{"archived":true,"unlinkHealthSubject":true}""",
            "/v1/schedules" to """{"title":"x","targetAssetId":"$ups","timeInterval":1,"timeUnit":"YEAR","anchorOn":"2026-02-01","unlinkHealthSubject":true}""",
            "/v1/schedules/$service/archive" to """{"archived":true,"unlinkHealthSubjekt":true}""",
        )) {
            val response = call("POST", path, body)
            val misspelt = Regex("\"(seasonStrtMmdd|blackotuNote|healthPrimaryId|occuredOn|reasn|wieght|unlinkHealthSubjekt|unlinkHealthSubject)\"")
                .findAll(body).last().groupValues[1]
            refused(response, 400, "bad_request")
            assertTrue("$path did not name $misspelt: ${response.bodyText()}", misspelt in response.bodyText())
        }
        val patch = call(
            "PATCH", "/v1/health-subjects/$battery",
            """{"name":"Battery","kind":"PART","driver":"AGE","nominalUntilDays":0,"warningFromDays":30,"criticalFromDays":60,"nmae":"x"}""",
        )
        refused(patch, 400, "bad_request")
        assertTrue(patch.bodyText(), "nmae" in patch.bodyText())
    }

    /** A subject never changes asset (inv. 120): its PATCH has no `assetId`, so naming one is a 400. */
    @Test fun aSubjectPatchNamingAssetIdIs400() {
        val ups = api.asset("UPS")
        val battery = subject(ups, "Battery")
        val before = runBlocking { graph.healthSubjects.get(HealthSubjectId(battery)) }
        val response = call("PATCH", "/v1/health-subjects/$battery", subjectBody(api.asset("Generator"), "Battery"))
        refused(response, 400, "bad_request")
        assertTrue(response.bodyText(), "assetId" in response.bodyText())
        assertEquals(before, runBlocking { graph.healthSubjects.get(HealthSubjectId(battery)) })
    }

    // --- the link guard on the wire ------------------------------------------------------------

    /**
     * Inv. 130 at the wire: a PATCH that moves a driving schedule to another asset, and an archive of
     * one, are 422 without the flag and write nothing; with `unlinkHealthSubject: true` they succeed
     * and archive the subject in the same write. `false` is the same as leaving it out.
     */
    @Test fun unlinkHealthSubjectOnPatchAndArchive() {
        val generator = api.asset("Generator")
        val ups = api.asset("UPS")
        val service = schedule(generator, "Oil change")
        val oil = subject(generator, "Oil", "MAINTENANCE_OVERDUE", service)
        fun archivedAt(id: String) = runBlocking { graph.healthSubjects.get(HealthSubjectId(id))!!.archivedAt }
        fun moved(flag: String) =
            """{"title":"Oil change","targetAssetId":"$ups","timeInterval":1,"timeUnit":"YEAR","anchorOn":"2026-02-01"$flag}"""

        val before = runBlocking { graph.schedules.get(ScheduleId(service)) }
        refused(call("PATCH", "/v1/schedules/$service", moved("")), 422, "SCHEDULE_DRIVES_HEALTH_SUBJECT")
        refused(call("PATCH", "/v1/schedules/$service", moved(""","unlinkHealthSubject":false""")), 422, "SCHEDULE_DRIVES_HEALTH_SUBJECT")
        assertEquals(before, runBlocking { graph.schedules.get(ScheduleId(service)) })
        assertNull(archivedAt(oil))

        val patched = call("PATCH", "/v1/schedules/$service", moved(""","unlinkHealthSubject":true"""))
        assertEquals(patched.bodyText(), 200, patched.status)
        assertEquals(ups, ApiJson.decodeFromString(ScheduleResponse.serializer(), patched.bodyText()).schedule.assetId)
        assertNotNull(archivedAt(oil))

        val load = schedule(generator, "Load test")
        val coolant = subject(generator, "Coolant", "MAINTENANCE_OVERDUE", load)
        refused(call("POST", "/v1/schedules/$load/archive", """{"archived":true}"""), 422, "SCHEDULE_DRIVES_HEALTH_SUBJECT")
        assertNull(archivedAt(coolant))
        val archived = call("POST", "/v1/schedules/$load/archive", """{"archived":true,"unlinkHealthSubject":true}""")
        assertEquals(archived.bodyText(), 200, archived.status)
        assertEquals("ARCHIVED", ApiJson.decodeFromString(ScheduleResponse.serializer(), archived.bodyText()).schedule.status)
        assertNotNull(archivedAt(coolant))
    }
}
