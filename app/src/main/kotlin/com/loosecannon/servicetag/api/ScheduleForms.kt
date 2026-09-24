package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.model.LegacySeasonMapping
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.SeasonBehavior
import com.loosecannon.servicetag.core.model.ServicePolicy
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject

/**
 * The schedule command's **two forms** (spec §9.3; master plan §11.4; Q-9), and the one place the
 * API turns either of them into a policy.
 *
 * **Presence decides the form**, read off the raw JSON object *before* the typed decode, because a
 * typed decode cannot tell an omitted key from an explicit `null`:
 *
 * - a `servicePolicy` or `policyOffsetDays` key — an explicit `null` counts — is the **1.4 form**;
 * - neither is the **legacy form**, with 1.3's omitted-field defaults (`seasonBehavior` → `IGNORE`);
 * - any legacy key (`seasonBehavior`, `seasonReentry`, `seasonReentryOffsetDays`) **and** any 1.4
 *   key is 422 `LEGACY_AND_CURRENT_FIELDS_MIXED`, and nothing is written.
 *
 * **The 1.4 form** takes the policy as sent: an omitted `servicePolicy` is `CONTINUOUS`, and an
 * omitted *or null* `policyOffsetDays` is 0 on `IN_SERVICE_AT_START` — spec §4.2's default, which
 * v1's full replace clears to (plan decision 31) — and null otherwise. Everything else about the
 * pair is `SaveSchedule`'s to refuse (`POLICY_OFFSET_INVALID`, `SEASON_POLICY_NEEDS_A_TIME_RULE`,
 * `SEASON_FOLLOWS_ASSET_ON_GROUP_TARGET`, `PRE_SERVICE_NEEDS_DATES`), so none of it is restated here.
 *
 * **The legacy form translates only through [LegacySeasonMapping]** (inv. 128) — this file holds no
 * table of its own. It is **refused, never reset**, in the one case a schedule command can reach: a
 * PATCH of a stored `PRE_SERVICE` schedule, which 1.3's three fields cannot spell, is 422
 * `LEGACY_WRITE_CANNOT_REPRESENT` and writes nothing (the asset pair's own case is `UpdateAsset`'s).
 * Any other legacy body translates, and a create clobbers nothing. The one documented change: a
 * legacy body that omits `seasonReentry` on an `IN_SERVICE_RESUME_CLAMPED` schedule becomes
 * `IN_SERVICE_AT_START`, because the table reads an absent re-entry as AT_START.
 *
 * **The action flag** `unlinkHealthSubject` is an action parameter and not a column (spec §9.1): a
 * PATCH reads it beside the command, and a create, which has nothing to unlink, refuses it as the
 * unknown field it is there.
 */
internal object ScheduleForms {

    /** The 1.4 form's keys. */
    val CURRENT_KEYS: List<String> = listOf("servicePolicy", "policyOffsetDays")

    /** 1.3's three season keys, the deprecated inputs. */
    val LEGACY_KEYS: List<String> = listOf("seasonBehavior", "seasonReentry", "seasonReentryOffsetDays")

    /** Action parameters a PATCH takes beside the command; never a row field. */
    val ACTION_FLAGS: List<String> = listOf("unlinkHealthSubject")

    enum class Form { CURRENT, LEGACY }

    /** One schedule command as read off the wire: its form, its typed body, and a PATCH's flag. */
    class Parsed(val form: Form, val body: ScheduleCommandRequest, val unlinkHealthSubject: Boolean)

    /**
     * The form [raw] is in, by key presence alone. A body naming keys of both forms is refused here,
     * with `problems` naming the keys it found, legacy ones first.
     */
    fun classify(raw: JsonObject): Form {
        val current = CURRENT_KEYS.filter { it in raw }
        val legacy = LEGACY_KEYS.filter { it in raw }
        if (current.isNotEmpty() && legacy.isNotEmpty()) {
            throw ApiFailure(
                422, "Unprocessable Content", "LEGACY_AND_CURRENT_FIELDS_MIXED",
                "send either servicePolicy/policyOffsetDays or the deprecated season fields, never both",
                legacy + current,
            )
        }
        return if (current.isNotEmpty()) Form.CURRENT else Form.LEGACY
    }

    /**
     * Reads one schedule command. [stored] is the schedule a PATCH replaces (null on a create, and
     * null when the id names nothing — `SaveSchedule` answers that 404). [acceptsFlags] is true on
     * a PATCH only.
     *
     * The order is the contract's: the body is JSON (415, 400), its form is decided (the mixed 422),
     * a legacy body over a stored PRE_SERVICE schedule is refused (its 422) — before the typed decode,
     * so a PRE_SERVICE row's own null triple sent back is refused by name rather than as a null in a
     * non-null field — and only then is the body decoded strictly, unknown keys refused by name.
     */
    fun read(request: ApiRequest, stored: MaintenanceSchedule?, acceptsFlags: Boolean): Parsed {
        val raw = request.decode(JsonObject.serializer())
        val form = classify(raw)
        if (form == Form.LEGACY && stored?.servicePolicy == ServicePolicy.PRE_SERVICE) {
            throw ApiFailure(
                422, "Unprocessable Content", "LEGACY_WRITE_CANNOT_REPRESENT",
                "this schedule is PRE_SERVICE, which the deprecated season fields cannot represent; " +
                    "send servicePolicy and policyOffsetDays instead",
                listOf("LegacyWriteCannotRepresent(scheduleId=${stored.id.value}, servicePolicy=PRE_SERVICE)"),
            )
        }
        val flagKeys = if (acceptsFlags) ACTION_FLAGS.filter { it in raw } else emptyList()
        val flags = strict(ScheduleActionFlags.serializer(), JsonObject(raw.filterKeys { it in flagKeys }))
        val body = strict(ScheduleCommandRequest.serializer(), JsonObject(raw.filterKeys { it !in flagKeys }))
        return Parsed(form, body, flags.unlinkHealthSubject)
    }

    /** The policy and offset [body] asks for, in its [form]. */
    fun policyOf(form: Form, body: ScheduleCommandRequest): LegacySeasonMapping.Policy = when (form) {
        Form.CURRENT -> current(body.servicePolicy, body.policyOffsetDays)
        Form.LEGACY -> legacy(
            body.seasonBehavior, body.seasonReentry, body.seasonReentryOffsetDays, hasTimeRule = body.timeInterval != null,
        )
    }

    /**
     * The 1.4 form: the policy by name (an unknown one is the shipped 400), and the offset as sent —
     * or, omitted or null, 0 on `IN_SERVICE_AT_START` and null on every other policy.
     */
    private fun current(servicePolicy: String, offsetDays: Int?): LegacySeasonMapping.Policy {
        val policy = enumOr400<ServicePolicy>(servicePolicy, "servicePolicy")
        val offset = offsetDays ?: if (policy == ServicePolicy.IN_SERVICE_AT_START) 0 else null
        return LegacySeasonMapping.Policy(policy, offset)
    }

    /**
     * The legacy form: 1.3's names, through the one table. An unknown `seasonBehavior` name is still
     * the shipped 400 — the table is for the names that exist, and never guesses.
     */
    private fun legacy(behavior: String, reentry: String?, offsetDays: Int?, hasTimeRule: Boolean) =
        LegacySeasonMapping.toPolicy(
            behavior = enumOr400<SeasonBehavior>(behavior, "seasonBehavior"),
            reentry = reentry,
            offsetDays = offsetDays,
            hasTimeRule = hasTimeRule,
        )

    /**
     * The shipped strict decode, over a body this object has already parsed once: re-read as text so
     * a legacy body decodes exactly as 1.3 decoded it, and any failure is the shipped 400 naming the
     * key the decoder objected to.
     */
    private fun <T> strict(serializer: DeserializationStrategy<T>, body: JsonObject): T = try {
        ApiJson.decodeFromString(serializer, body.toString())
    } catch (e: SerializationException) {
        throw ApiFailure.badRequest(e.message ?: "that is not the JSON this endpoint wants")
    }
}
