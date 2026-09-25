package com.loosecannon.servicetag.ui.maintenance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.EventProfile
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.MeasurementDefinition
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleProviderRow
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.SeasonInputs
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.model.TimeBasis
import com.loosecannon.servicetag.core.model.seasonInputs
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.DefinitionRepository
import com.loosecannon.servicetag.core.ports.GroupRepository
import com.loosecannon.servicetag.core.ports.ProfileRepository
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.reminders.ProviderId
import com.loosecannon.servicetag.core.schedule.BoundaryKind
import com.loosecannon.servicetag.core.schedule.SeasonContext
import com.loosecannon.servicetag.core.schedule.SeasonPhase
import com.loosecannon.servicetag.core.usecase.HealthSubjectIsPrimary
import com.loosecannon.servicetag.core.usecase.PreServiceNeedsDates
import com.loosecannon.servicetag.core.usecase.SaveSchedule
import com.loosecannon.servicetag.core.usecase.ScheduleCommand
import com.loosecannon.servicetag.core.usecase.ScheduleDrivesHealthSubject
import com.loosecannon.servicetag.core.usecase.ScheduleProblem
import com.loosecannon.servicetag.core.usecase.ScheduleValidation
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.reminders.NotificationPermission
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The names [ScheduleEditState.problems] marks a control by — one per field of the form, so a
 * refused save marks the control the refusal is actually about, in the shipped editors' idiom.
 *
 * **They are field names, never sentences.** §17 ratifies no wording for a refused schedule save,
 * and no brief invents one, so the form marks the field and says nothing — see [fieldOf]. Two
 * refusals are the exceptions, because 1.4 ratified words for them: the health link guard's
 * `ScheduleDrivesHealthSubject` and `HealthSubjectIsPrimary` draw S140–S141 and S137, and the
 * `PreServiceNeedsDates` race draws S77. Neither of them is a field mark.
 */
object ScheduleField {
    const val TITLE = "title"
    const val INTERVAL = "interval"
    const val UNIT = "unit"
    const val ANCHOR = "anchor"
    const val LEAD = "lead"
    const val METER = "meter"
    const val METER_INTERVAL = "meterInterval"
    const val METER_LEAD = "meterLead"
    /** The service-policy question, S65 (spec §10.4). */
    const val POLICY = "policy"
    /** The question's day field: S71 under a "Before…" option, S72 under S74. */
    const val POLICY_OFFSET = "policyOffset"
    const val COMPLETION_MODE = "completionMode"
    const val PROFILE = "profile"
    const val PROVIDER = "provider"
    const val TARGET = "target"
}

/**
 * Which control a refusal belongs to. A pure function, so the mapping is asserted by a JVM test
 * rather than inferred from a screenshot.
 *
 * Every member maps somewhere: an unmapped problem would be a refused save with nothing marked,
 * which is the dead end the target-choice matrix row is about.
 */
fun fieldOf(problem: ScheduleProblem): String = when (problem) {
    ScheduleProblem.TargetInvalid -> ScheduleField.TARGET
    ScheduleProblem.EmptyGroupTarget -> ScheduleField.TARGET
    ScheduleProblem.NoRuleSide -> ScheduleField.INTERVAL
    ScheduleProblem.TimeIntervalNotPositive -> ScheduleField.INTERVAL
    ScheduleProblem.TimeUnitRequired -> ScheduleField.UNIT
    ScheduleProblem.AnchorRequired -> ScheduleField.ANCHOR
    ScheduleProblem.BadAnchorDate -> ScheduleField.ANCHOR
    ScheduleProblem.NegativeLeadDays -> ScheduleField.LEAD
    ScheduleProblem.MeterRuleOnGroupTarget -> ScheduleField.METER
    ScheduleProblem.MeterIntervalRequired -> ScheduleField.METER_INTERVAL
    ScheduleProblem.MeterIntervalNotPositive -> ScheduleField.METER_INTERVAL
    ScheduleProblem.NegativeMeterLead -> ScheduleField.METER_LEAD
    is ScheduleProblem.ForeignMeterDefinition -> ScheduleField.METER
    is ScheduleProblem.MeterDefinitionNotAMeter -> ScheduleField.METER
    // The three policy refusals are unreachable from this editor by mechanism (master dec. 46): a
    // group draws no question, the option set drops every date-moving choice without a time rule, and
    // the day fields filter to 0–365 with Save held while S71 is empty or 0. They still name the
    // control they are about, so a refusal that arrives anyway marks it rather than nothing.
    ScheduleProblem.SeasonFollowsAssetOnGroupTarget -> ScheduleField.POLICY
    ScheduleProblem.PolicyOffsetInvalid -> ScheduleField.POLICY_OFFSET
    ScheduleProblem.SeasonPolicyNeedsATimeRule -> ScheduleField.POLICY
    ScheduleProblem.FormCompletionOnGroupTarget -> ScheduleField.COMPLETION_MODE
    ScheduleProblem.ProfileOnGroupTarget -> ScheduleField.PROFILE
    is ScheduleProblem.ForeignProfile -> ScheduleField.PROFILE
    is ScheduleProblem.UnknownProvider -> ScheduleField.PROVIDER
    ScheduleProblem.PostponeNeedsTimeRule -> ScheduleField.INTERVAL
}

/**
 * The answers to S65, "When should this maintenance be done?" (spec §10.4) — the editor's own model,
 * called by no other surface. Each one is a ratified option word, and [ScheduleEditState.servicePolicy]
 * is what it means:
 *
 * - [BEFORE_SEASON] (S66) and [BEFORE_BREAK] (S69) are PRE_SERVICE, `−(Days before it starts)`;
 * - [WHEN_SEASON_STARTS] (S67) is IN_SERVICE_AT_START or IN_SERVICE_RESUME_CLAMPED, by
 *   [StartCountingFrom];
 * - [AFTER_BREAK] (S70) is IN_SERVICE_AT_START at offset 0, with no field;
 * - [WHENEVER_DUE] (S68) is CONTINUOUS.
 */
enum class PolicyOption { BEFORE_SEASON, BEFORE_BREAK, WHEN_SEASON_STARTS, AFTER_BREAK, WHENEVER_DUE }

/** S73 "Start counting from", under S67: S74 is AT_START ([SEASON_START]), S75 RESUME_CLAMPED ([OWN_DATE]). */
enum class StartCountingFrom { SEASON_START, OWN_DATE }

/**
 * S140–S141, and S137 when the answer to "Archive both" is that the subject is the one its asset's
 * health follows (spec §6.1, D-30; inv. 130). Shared by the editor's save and the detail's archive.
 */
sealed interface LinkGuardPrompt {
    /** S140 naming the subject, with S141 "Archive both" and the shipped Cancel. */
    data class Asks(val subjectName: String) : LinkGuardPrompt

    /** S137: "Archive both" was refused with `HEALTH_SUBJECT_IS_PRIMARY`, and nothing was written. */
    data object Primary : LinkGuardPrompt
}

/**
 * The options spec §10.4's table draws for a target, in order; **empty means the question is not
 * drawn** and the policy is CONTINUOUS — a group target ([season] null, inv. 106) and a YEAR_ROUND
 * asset without a break.
 *
 * Without a time rule (a meter-only schedule) no "Before…" option is offered, because PRE_SERVICE
 * moves a date the schedule does not have (O-7); the in-service option keeps its asset's label (master
 * dec. 38). "A break is set" is read through [SeasonContext], the engine's own reading, so a break the
 * engine would ignore (one bound only, merge-only) offers nothing it could not honour.
 *
 * **Plan decision (B08):** on a MANUAL asset with a break, S69 is drawn first, so every row reads
 * earliest to latest and "Whenever it is due" is always last.
 */
internal fun policyOptionsFor(season: SeasonInputs?, hasTimeRule: Boolean): List<PolicyOption> {
    if (season == null) return emptyList()
    val hasBreak = SeasonContext.of(season).boundaryKind == BoundaryKind.BREAK
    return when (season.mode) {
        SeasonMode.CALENDAR -> listOfNotNull(
            PolicyOption.BEFORE_SEASON.takeIf { hasTimeRule },
            PolicyOption.WHEN_SEASON_STARTS,
            PolicyOption.WHENEVER_DUE,
        )
        SeasonMode.MANUAL -> listOfNotNull(
            PolicyOption.BEFORE_BREAK.takeIf { hasTimeRule && hasBreak },
            PolicyOption.WHEN_SEASON_STARTS,
            PolicyOption.WHENEVER_DUE,
        )
        SeasonMode.YEAR_ROUND -> if (hasBreak) {
            listOfNotNull(
                PolicyOption.BEFORE_BREAK.takeIf { hasTimeRule },
                PolicyOption.AFTER_BREAK,
                PolicyOption.WHENEVER_DUE,
            )
        } else {
            emptyList()
        }
    }
}

/** One answer to the question, with the text of whichever day field it carries. */
internal data class PolicyChoice(
    val option: PolicyOption,
    val startCountingFrom: StartCountingFrom = StartCountingFrom.SEASON_START,
    val daysBefore: String = "",
    val daysAfter: String = "",
)

/**
 * Which answer a stored row is, among [options] — or null when none of them is it, which the editor
 * never guesses at: PRE_SERVICE −14 is the "Before…" option with 14, AT_START 5 is S67 · S74 · 5,
 * RESUME_CLAMPED is S67 · S75, CONTINUOUS is S68. AT_START is S70 only at offset 0, because S70 has no
 * field to show another offset in.
 */
internal fun storedChoice(policy: ServicePolicy, offsetDays: Int?, options: List<PolicyOption>): PolicyChoice? =
    when (policy) {
        ServicePolicy.CONTINUOUS -> PolicyChoice(PolicyOption.WHENEVER_DUE)
        ServicePolicy.PRE_SERVICE ->
            listOf(PolicyOption.BEFORE_SEASON, PolicyOption.BEFORE_BREAK).firstOrNull { it in options }
                ?.let { PolicyChoice(it, daysBefore = offsetDays?.let { days -> (-days).toString() }.orEmpty()) }
        ServicePolicy.IN_SERVICE_AT_START -> when {
            PolicyOption.WHEN_SEASON_STARTS in options ->
                PolicyChoice(PolicyOption.WHEN_SEASON_STARTS, daysAfter = (offsetDays ?: 0).toString())
            PolicyOption.AFTER_BREAK in options && (offsetDays ?: 0) == 0 -> PolicyChoice(PolicyOption.AFTER_BREAK)
            else -> null
        }
        ServicePolicy.IN_SERVICE_RESUME_CLAMPED ->
            PolicyChoice(PolicyOption.WHEN_SEASON_STARTS, StartCountingFrom.OWN_DATE)
                .takeIf { PolicyOption.WHEN_SEASON_STARTS in options }
    }

/** S71's and S72's bounds: 1–365 before, 0–365 after (spec §4.2). */
internal const val MAX_POLICY_DAYS = 365

/**
 * S71's and S72's input filter (master dec. 46, the ruling on I10): digits only, at most three of them,
 * and never above [MAX_POLICY_DAYS]. A keystroke that breaks it is **not accepted as typed** — the
 * field keeps what it had — so no out-of-range value is ever turned into a different one and no
 * refusal needs a sentence.
 */
internal fun acceptsPolicyDays(value: String): Boolean =
    value.isEmpty() || (value.length <= 3 && value.all { it in '0'..'9' } && value.toInt() <= MAX_POLICY_DAYS)

/**
 * The schedule form. Numbers are held as text until a save parses them, exactly as the shipped
 * editors do: a half-typed "1" or an emptied lead is a state a form has to be able to sit in.
 *
 * **[target] is one value, not two ids**, which is what makes invariant 1 unreachable through this
 * screen rather than merely refused by the command: there is no state in which both a target asset
 * and a target group are set, whatever the owner taps. The choice is made once — by the entry point
 * that opened the editor — and an edit keeps the stored target, so it is never editable afterwards.
 *
 * The three [isGroup] hides are the D-12 and D-28 rules made structural: a group target draws no
 * meter block, no profile picker and no service-policy question (inv. 106).
 *
 * **The policy is a choice, never a default the owner must decide** (spec §10.4, inv. 121): a new
 * schedule starts on S68, the API's own default (master dec. 43), and the pre-service margin starts
 * empty. [policyOption] is null while the question stands unanswered — a stored policy the options
 * cannot show, or an option the time rule's removal withdrew — and Save waits for the owner's pick.
 */
data class ScheduleEditState(
    val target: ScheduleTarget? = null,
    val targetName: String = "",
    val title: String = "",
    val description: String = "",
    val timeInterval: String = "",
    val timeUnit: RecurrenceUnit = RecurrenceUnit.MONTH,
    val timeBasis: TimeBasis = TimeBasis.FIXED,
    val anchorOn: String = "",
    val leadDays: String = "0",
    val meterDefinitionId: DefinitionId? = null,
    val meterInterval: String = "",
    val anchorMeter: String = "",
    val meterLead: String = "",
    /** The target asset's season and break, read once; null for a group target, which has neither. */
    val season: SeasonInputs? = null,
    /** The answer to S65; null while it stands unanswered. */
    val policyOption: PolicyOption? = PolicyOption.WHENEVER_DUE,
    val startCountingFrom: StartCountingFrom = StartCountingFrom.SEASON_START,
    /** S71, as typed: **empty until the owner enters it**, and nothing suggests a value (inv. 121). */
    val daysBefore: String = "",
    /** S72, as typed; empty means 0, the start itself (spec §4.2). */
    val daysAfter: String = "",
    /**
     * The S77 state (master dec. 30): a stored non-CONTINUOUS policy on an asset with no boundary to be
     * ready before — reachable through a merge, a migrated 1.3 row, or a race with an asset edit
     * (`PreServiceNeedsDates`). The question then offers S68 alone and waits for it to be picked.
     */
    val noBoundary: Boolean = false,
    /** The link-guard dialog, when a save was refused for a health subject it would strand. */
    val linkGuard: LinkGuardPrompt? = null,
    /** The day S84 measures the season from: its current span, or the next one. */
    val todayOn: LocalDate = LocalDate.of(1970, 1, 1),
    val completionMode: CompletionMode = CompletionMode.QUICK,
    val profileId: ProfileId? = null,
    val remindersEnabled: Boolean = true,
    /**
     * The one provider this release has (decision 8). It is **not settable from the screen**: §17.1c
     * ratifies the row's label and no option word, so there is nothing for a second choice to be
     * called, and with one member the single-choice row of #4 is a choice of one. Kept as state
     * rather than hardcoded at the command, so #25's multi-provider UI is additive: the seam is
     * here, not at the command.
     *
     * **What a stored row naming an unknown provider does, precisely:** the loader maps it to
     * `LOCAL` (`?: ProviderId.LOCAL` below), so saving rewrites it. That is unreachable in 1.2 —
     * `ProviderId` has exactly one member, and nothing writes another name — and it is the one
     * line #25 has to revisit when a second provider exists, because a phone running an older
     * build must not silently re-point a schedule at itself. It is recorded here rather than
     * guarded now: a guard for a value that cannot exist is a branch no test can reach.
     */
    val provider: ProviderId? = ProviderId.LOCAL,
    /** The asset's meter definitions, offered only for an asset target (D-12). */
    val meters: List<MeasurementDefinition> = emptyList(),
    /** The asset's quick actions, offered only for a `FORM` asset target (D-12). */
    val profiles: List<EventProfile> = emptyList(),
    val problems: List<ScheduleProblem> = emptyList(),
    /** D-11's non-blocking line: shown, and the schedule still saves. */
    val duplicateWarning: Boolean = false,
    /** The permission rationale is open, which happens once and on a create only (#24 AC 1). */
    val askingForNotifications: Boolean = false,
    val editing: Boolean = false,
    val saving: Boolean = false,
    val loaded: Boolean = false,
) {
    val isGroup: Boolean get() = target is ScheduleTarget.GroupTarget

    /** Which controls a refused save marked. Never a sentence — see [ScheduleField]. */
    val marks: Set<String> get() = problems.map(::fieldOf).toSet()

    /** A meter rule is present when a definition is chosen, which a group target can never be. */
    val hasMeterRule: Boolean get() = meterDefinitionId != null && !isGroup

    /** Whether the command will carry a time rule: the interval is what `SaveSchedule` asks about. */
    val hasTimeRule: Boolean get() = timeInterval.trim().toIntOrNull() != null

    /** S65's options, in order; S68 alone in the S77 state, and none for a group. */
    val policyOptions: List<PolicyOption>
        get() = when {
            isGroup -> emptyList()
            noBoundary -> listOf(PolicyOption.WHENEVER_DUE)
            else -> policyOptionsFor(season, hasTimeRule)
        }

    /** Whether S65 is drawn at all. When it is not, the policy is CONTINUOUS (inv. 106). */
    val questionDrawn: Boolean get() = policyOptions.isNotEmpty()

    /** The policy the chosen answer means; null while the question is drawn and unanswered. */
    val servicePolicy: ServicePolicy?
        get() = if (!questionDrawn) {
            ServicePolicy.CONTINUOUS
        } else {
            when (policyOption) {
                null -> null
                PolicyOption.BEFORE_SEASON, PolicyOption.BEFORE_BREAK -> ServicePolicy.PRE_SERVICE
                PolicyOption.WHEN_SEASON_STARTS -> when (startCountingFrom) {
                    StartCountingFrom.SEASON_START -> ServicePolicy.IN_SERVICE_AT_START
                    StartCountingFrom.OWN_DATE -> ServicePolicy.IN_SERVICE_RESUME_CLAMPED
                }
                PolicyOption.AFTER_BREAK -> ServicePolicy.IN_SERVICE_AT_START
                PolicyOption.WHENEVER_DUE -> ServicePolicy.CONTINUOUS
            }
        }

    /**
     * The signed offset spec §4.2 stores: `−(Days before it starts)` for PRE_SERVICE; S72 for S74, where
     * nothing typed is 0; 0 for S70; null otherwise. **On a meter-only schedule S74's offset is 0**
     * whatever was typed, since the field is hidden and 0 is the only value one may carry (O-7) — the
     * one documented place where the form sends something other than its text.
     */
    val policyOffsetDays: Int?
        get() = if (!questionDrawn) {
            null
        } else {
            when (policyOption) {
                PolicyOption.BEFORE_SEASON, PolicyOption.BEFORE_BREAK -> daysBefore.toIntOrNull()?.let { -it }
                PolicyOption.WHEN_SEASON_STARTS -> when (startCountingFrom) {
                    StartCountingFrom.SEASON_START -> if (hasTimeRule) daysAfter.toIntOrNull() ?: 0 else 0
                    StartCountingFrom.OWN_DATE -> null
                }
                PolicyOption.AFTER_BREAK -> 0
                PolicyOption.WHENEVER_DUE, null -> null
            }
        }

    /** A "Before…" option is chosen, so S71 is drawn and must hold a margin. */
    val marginNeeded: Boolean
        get() = questionDrawn &&
            (policyOption == PolicyOption.BEFORE_SEASON || policyOption == PolicyOption.BEFORE_BREAK)

    /** S83 under S71: the margin is still empty. */
    val marginMissing: Boolean get() = marginNeeded && daysBefore.isEmpty()

    /**
     * Save is offered only for a command the policy half cannot refuse (master dec. 46): the question,
     * when drawn, is answered, and S71 holds 1–365 — an empty margin and a margin of 0 both hold it.
     */
    val canSave: Boolean
        get() = loaded && !saving && linkGuard == null && servicePolicy != null &&
            (!marginNeeded || daysBefore.toIntOrNull() in 1..MAX_POLICY_DAYS)

    /** The calendar season this asset reads, or null when it has none (only CALENDAR carries S76/S84). */
    private val calendarSeason: SeasonContext?
        get() = season?.takeIf { questionDrawn && it.mode == SeasonMode.CALENDAR }?.let(SeasonContext::of)

    /**
     * S76, a warning and never a refusal: S67 is chosen on a CALENDAR asset and the anchor date lies
     * outside the season **window**.
     */
    val anchorOutsideSeason: Boolean
        get() {
            val context = calendarSeason ?: return false
            if (policyOption != PolicyOption.WHEN_SEASON_STARTS || !hasTimeRule) return false
            val anchor = runCatching { LocalDate.parse(anchorOn.trim()) }.getOrNull() ?: return false
            return context.phaseAt(anchor) == SeasonPhase.OUT_OF_SEASON
        }

    /**
     * S84, a warning and never a refusal: S74 is chosen on a CALENDAR asset and the season's start plus
     * the offset falls after the end of **that** season — the span containing the start the offset is
     * counted from (the current one, or the next), so a wrapping season is measured across the year end.
     */
    val offsetPassesSeasonEnd: Boolean
        get() {
            val context = calendarSeason ?: return false
            if (policyOption != PolicyOption.WHEN_SEASON_STARTS) return false
            if (startCountingFrom != StartCountingFrom.SEASON_START) return false
            val offset = policyOffsetDays ?: return false
            val start = context.cycleStartAt(todayOn) ?: return false
            val end = context.seasonEndAt(start) ?: return false
            return start.plusDays(offset.toLong()) > end
        }

    /** The same form with the question's answer cleared when it is no longer one of the options. */
    internal fun withOfferedOption(): ScheduleEditState =
        if (questionDrawn && policyOption != null && policyOption !in policyOptions) copy(policyOption = null) else this

    /**
     * A stored row's policy, loaded into its option. A policy the options cannot show is never guessed
     * at: the question stands unanswered, and when the asset has no boundary at all it is the S77 state.
     */
    internal fun withStoredPolicy(row: MaintenanceSchedule): ScheduleEditState {
        val choice = storedChoice(row.servicePolicy, row.policyOffsetDays, policyOptions)
        if (choice != null) {
            return copy(
                policyOption = choice.option,
                startCountingFrom = choice.startCountingFrom,
                daysBefore = choice.daysBefore,
                daysAfter = choice.daysAfter,
            )
        }
        val boundaryless = season?.let { SeasonContext.of(it).boundaryKind == BoundaryKind.NONE } == true
        return copy(policyOption = null, noBoundary = boundaryless)
    }
}

/**
 * Create ([scheduleId] null) or edit one schedule, against **one asset or one group**.
 *
 * Every rule is `SaveSchedule`'s, called: this view model constructs the command, sends it and maps
 * whatever comes back onto the control it belongs to. What it adds is the three target-dependent
 * hides that make an illegal group schedule unreachable through the UI, D-11's non-blocking warning
 * and — on the **first** schedule creation only — the notification permission request. 1.4 adds the
 * service-policy question (S65–S84, spec §10.4), whose filters and held Save keep every policy refusal
 * unreachable, and the health link guard's dialog on a refused save (S140–S141, S137; inv. 130).
 *
 * The permission request lives here and nowhere else (master plan decision 23, #24 AC 1). "First"
 * is derived from the store rather than a remembered flag: a create is the first one when the store
 * held no schedule before it. That is the same fact a persisted "already asked" flag would carry,
 * with nothing to drift out of step, and it makes both halves of the matrix row true by
 * construction — never at launch, and never again once a schedule exists.
 */
class ScheduleEditViewModel(
    private val schedules: ScheduleRepository,
    private val assets: AssetRepository,
    private val groups: GroupRepository,
    private val definitions: DefinitionRepository,
    private val profiles: ProfileRepository,
    private val saveSchedule: SaveSchedule,
    private val notifications: NotificationPermission,
    private val today: Today,
    private val scheduleId: ScheduleId?,
    private val targetAssetId: AssetId?,
    private val targetGroupId: GroupId?,
) : ViewModel() {

    constructor(
        graph: AppGraph,
        scheduleId: String?,
        targetAssetId: String?,
        targetGroupId: String?,
    ) : this(
        graph.schedules, graph.assets, graph.groups, graph.definitions, graph.profiles,
        graph.saveSchedule, graph.notificationPermission, graph.today,
        scheduleId?.let(::ScheduleId),
        targetAssetId?.let(::AssetId),
        // Both ids arriving is a serialised back stack from an older process or a hand-built key.
        // The asset wins and the group is dropped rather than both being carried into a command the
        // engine would refuse: one target is the only representable state (invariant 1).
        targetGroupId?.takeIf { targetAssetId == null }?.let(::GroupId),
    )

    private val _state = MutableStateFlow(ScheduleEditState(editing = scheduleId != null))
    val state: StateFlow<ScheduleEditState> = _state.asStateFlow()

    /** One shot per successful save; the screen that started it is told to leave, once. */
    private val _saved = MutableSharedFlow<ScheduleId>(replay = 0, extraBufferCapacity = 1)
    val saved: SharedFlow<ScheduleId> = _saved.asSharedFlow()

    init {
        viewModelScope.launch {
            val existing = scheduleId?.let { schedules.get(it) }
            // An edit keeps the stored target, whatever the route carried: the choice is made once.
            val target = existing?.target ?: targetAssetId?.let(ScheduleTarget::AssetTarget)
                ?: targetGroupId?.let(ScheduleTarget::GroupTarget)
            val assetId = (target as? ScheduleTarget.AssetTarget)?.assetId
            // The asset's season and break decide which answers S65 offers. Activations are not read:
            // no option depends on a MANUAL asset's phase, and CALENDAR's warnings read the window.
            val season = assetId?.let { assets.get(it) }?.seasonInputs(emptyList())
            val todayOn = today.localDate()
            _state.update { form ->
                val filled = (existing?.let { form.filledFrom(it) } ?: form.blank()).copy(
                    target = target,
                    targetName = nameOf(target),
                    season = season,
                    todayOn = todayOn,
                    // A group target is offered neither, so nothing is read for one (D-12).
                    meters = assetId?.let { definitions.forAsset(it).filter { d -> d.isMeter && d.archivedAt == null } }
                        .orEmpty(),
                    profiles = assetId?.let { profiles.forAsset(it).filter { p -> p.archivedAt == null } }
                        .orEmpty(),
                    loaded = true,
                )
                // After the rule fields are in, because the options depend on the time rule.
                existing?.let { filled.withStoredPolicy(it) } ?: filled
            }
            refreshDuplicateWarning()
        }
    }

    private suspend fun nameOf(target: ScheduleTarget?): String = when (target) {
        is ScheduleTarget.AssetTarget -> assets.get(target.assetId)?.name.orEmpty()
        is ScheduleTarget.GroupTarget -> groups.get(target.groupId)?.name.orEmpty()
        null -> ""
    }

    /** A new schedule opens anchored on today: the one date that is never a guess. */
    private fun ScheduleEditState.blank() = copy(anchorOn = today.localDate().toString())

    private fun ScheduleEditState.filledFrom(row: MaintenanceSchedule) = copy(
        title = row.title,
        description = row.description,
        timeInterval = row.timeInterval?.toString().orEmpty(),
        timeUnit = row.timeUnit ?: RecurrenceUnit.MONTH,
        timeBasis = row.timeBasis,
        anchorOn = row.anchorOn.orEmpty(),
        leadDays = row.leadDays.toString(),
        meterDefinitionId = row.meterDefinitionId,
        meterInterval = row.meterInterval?.let(::plainNumber).orEmpty(),
        anchorMeter = row.anchorMeter?.let(::plainNumber).orEmpty(),
        meterLead = row.meterLead?.let(::plainNumber).orEmpty(),
        completionMode = row.completionMode,
        profileId = row.profileId,
        remindersEnabled = row.remindersEnabled,
        // **Without the `enabled` filter, and never null.** Filtering on `enabled` lost the row of
        // a reminders-off schedule: opening and saving one deleted its `schedule_provider` row with
        // nothing said, and switching reminders back on in the same session then wrote
        // `remindersEnabled = true` with **no** provider at all — B10's `SCHEDULE_NO_PROVIDER`
        // finding, reached through the editor's ordinary path. The row carries its own `enabled`
        // flag, so the provider and the switch are two facts and not one.
        provider = row.providers.firstOrNull()
            ?.let { stored -> ProviderId.entries.firstOrNull { it.name == stored.provider } }
            ?: ProviderId.LOCAL,
        editing = true,
    )

    /** Typing in a field clears that field's mark and nothing else — the rest is still wrong. */
    private fun clearing(vararg fields: String, block: (ScheduleEditState) -> ScheduleEditState) =
        _state.update { form ->
            block(form).copy(problems = form.problems.filterNot { fieldOf(it) in fields })
        }

    fun onTitle(value: String) {
        clearing(ScheduleField.TITLE) { it.copy(title = value) }
        // D-11's warning follows the title, because a case-insensitive title match is what "similar"
        // means (master plan decision 37) — so it has to be re-asked as the title is typed.
        viewModelScope.launch { refreshDuplicateWarning() }
    }

    fun onDescription(value: String) = _state.update { it.copy(description = value) }

    /**
     * The interval **is** the time rule, so it also decides which answers S65 offers. Removing it
     * withdraws the "Before…" options: a chosen one is cleared and the question stands unanswered
     * until the owner picks again — never silently re-answered.
     */
    fun onInterval(value: String) =
        clearing(ScheduleField.INTERVAL) { it.copy(timeInterval = value).withOfferedOption() }

    fun onUnit(value: RecurrenceUnit) = clearing(ScheduleField.UNIT) { it.copy(timeUnit = value) }

    fun onBasis(value: TimeBasis) = _state.update { it.copy(timeBasis = value) }

    fun onAnchor(value: String) = clearing(ScheduleField.ANCHOR) { it.copy(anchorOn = value) }

    fun onLead(value: String) = clearing(ScheduleField.LEAD) { it.copy(leadDays = value) }

    /**
     * Choosing or clearing the meter definition **is** the meter rule's on/off. A group target has
     * no meter block at all, so this is unreachable for one; it is still guarded, because a
     * serialised state restored onto a group target must not be able to set one (invariant 2).
     */
    fun onMeterDefinition(value: DefinitionId?) = clearing(
        ScheduleField.METER,
        ScheduleField.METER_INTERVAL,
    ) { form ->
        if (form.isGroup) form else form.copy(meterDefinitionId = value)
    }

    fun onMeterInterval(value: String) =
        clearing(ScheduleField.METER_INTERVAL) { it.copy(meterInterval = value) }

    fun onAnchorMeter(value: String) = _state.update { it.copy(anchorMeter = value) }

    fun onMeterLead(value: String) = clearing(ScheduleField.METER_LEAD) { it.copy(meterLead = value) }

    /**
     * The owner's answer to S65. Only an offered option is taken: a group target is offered none
     * (inv. 106), and an asset only what its season and break make meaningful.
     */
    fun onPolicy(value: PolicyOption) = clearing(ScheduleField.POLICY, ScheduleField.POLICY_OFFSET) { form ->
        if (value in form.policyOptions) form.copy(policyOption = value) else form
    }

    /** S73's answer, under S67 only. */
    fun onStartCountingFrom(value: StartCountingFrom) =
        clearing(ScheduleField.POLICY, ScheduleField.POLICY_OFFSET) { form ->
            if (form.policyOption == PolicyOption.WHEN_SEASON_STARTS) form.copy(startCountingFrom = value) else form
        }

    /** S71, through its filter: a keystroke the filter refuses leaves the field as it was. */
    fun onDaysBefore(value: String) = clearing(ScheduleField.POLICY_OFFSET) { form ->
        if (acceptsPolicyDays(value)) form.copy(daysBefore = value) else form
    }

    /** S72, through the same filter. */
    fun onDaysAfter(value: String) = clearing(ScheduleField.POLICY_OFFSET) { form ->
        if (acceptsPolicyDays(value)) form.copy(daysAfter = value) else form
    }

    /**
     * `FORM` is unreachable for a group target (D-12): it carries no profile, so a form would have
     * nothing to collect. Choosing `QUICK` also drops the profile, which is the field that would
     * otherwise be sent for a mode that does not use it.
     */
    fun onCompletionMode(value: CompletionMode) = clearing(
        ScheduleField.COMPLETION_MODE,
        ScheduleField.PROFILE,
    ) { form ->
        when {
            form.isGroup && value == CompletionMode.FORM -> form
            value == CompletionMode.QUICK -> form.copy(completionMode = value, profileId = null)
            else -> form.copy(completionMode = value)
        }
    }

    fun onProfile(value: ProfileId?) = clearing(ScheduleField.PROFILE) { form ->
        if (form.isGroup) form else form.copy(profileId = value)
    }

    fun onReminders(value: Boolean) = _state.update { it.copy(remindersEnabled = value) }

    /**
     * D-11: the asset being scheduled already has a **similar operation through a group**.
     *
     * "Similar" is a case-insensitive `title` match against the schedules of the groups this Asset
     * is an **open** member of (decision 37). The comparison set is deliberately that narrow: a
     * global title match would have two owners both scheduling "Replace filter" warning each other
     * for ever, and an asset's own schedules are not "through another group".
     *
     * It is **non-blocking** — it never gates [save] — because D-11 rules the warning in and
     * semantic deduplication out.
     */
    private suspend fun refreshDuplicateWarning() {
        val form = _state.value
        val assetId = (form.target as? ScheduleTarget.AssetTarget)?.assetId
        val typed = form.title.trim()
        val similar = if (assetId == null || typed.isEmpty()) {
            false
        } else {
            groups.forAsset(assetId)
                .flatMap { schedules.forGroup(it.id) }
                .any { it.id != scheduleId && it.title.trim().equals(typed, ignoreCase = true) }
        }
        // The title is re-read before the answer lands, because one query is launched per keystroke
        // and two of them can finish out of order — which would leave the line answering a title
        // the owner has already typed past. An answer about a title that is no longer there is
        // dropped rather than shown.
        _state.update { form ->
            if (form.title.trim() == typed) form.copy(duplicateWarning = similar) else form
        }
    }

    /**
     * Saves through `SaveSchedule` and, on the **first** schedule creation, asks for notification
     * permission with the ratified rationale.
     *
     * The order is deliberate: the schedule is written **first**, so a denial — or a rationale the
     * owner dismisses — cannot cost them the schedule they came here to make. D-22 says a denial
     * disables nothing, and this is where that is true rather than asserted.
     *
     * The guard is set before the first suspension, so two taps in one frame write one row.
     *
     * **Nothing reaches `SaveSchedule` unless [ScheduleEditState.canSave]**: an unanswered question, an
     * empty margin (S83) or a margin of 0 is held here, which is what keeps the policy refusals
     * unreachable without a sentence (master dec. 46).
     */
    fun save() {
        val form = _state.value
        if (!form.canSave) return
        submit(form.command(), unlinkHealthSubject = false)
    }

    /**
     * S141 "Archive both": **the same command** that was refused, with the unlink flag and nothing
     * else changed (spec §6.1, D-30) — not a rebuild from the form.
     */
    fun archiveBoth() {
        val form = _state.value
        val refused = pendingCommand
        if (form.saving || form.linkGuard !is LinkGuardPrompt.Asks || refused == null) return
        submit(refused, unlinkHealthSubject = true)
    }

    /** Cancel on the link-guard dialog: it closes, nothing is written, and the form is as typed. */
    fun cancelLinkGuard() {
        pendingCommand = null
        _state.update { it.copy(linkGuard = null) }
    }

    /** The command S140 asked about, held for "Archive both" to repeat. */
    private var pendingCommand: ScheduleCommand? = null

    private fun submit(cmd: ScheduleCommand, unlinkHealthSubject: Boolean) {
        _state.update { it.copy(saving = true, problems = emptyList(), linkGuard = null) }
        viewModelScope.launch {
            // Asked before the write, because after it the store is never empty again.
            val firstEver = scheduleId == null && schedules.all().isEmpty()
            val outcome = runCatching { saveSchedule.run(scheduleId, cmd, unlinkHealthSubject) }
            val failure = outcome.exceptionOrNull()
            if (failure != null) {
                refused(failure, cmd)
                return@launch
            }
            pendingCommand = null
            val saved = outcome.getOrThrow()
            if (firstEver && !notifications.granted()) {
                // The rationale first, then the system dialog: spec §5.1 asks for the permission
                // with a reason, on a creation, and never at launch.
                _state.update { it.copy(saving = false, askingForNotifications = true) }
                pendingSave = saved.id
            } else {
                _state.update { it.copy(saving = false) }
                _saved.tryEmit(saved.id)
            }
        }
    }

    /**
     * What a refused save shows. Nothing was written in any branch.
     *
     * - `ScheduleDrivesHealthSubject` asks S140 about the subject it names;
     * - `HealthSubjectIsPrimary`, the answer to "Archive both" for the subject its asset's health
     *   follows, shows S137;
     * - `PreServiceNeedsDates` — the asset lost its boundary while the form was open — re-reads the
     *   asset and takes the S77 path: S77, the question with S68 alone, unanswered;
     * - a `ScheduleValidation` marks its fields, as it always has.
     */
    private suspend fun refused(failure: Throwable, cmd: ScheduleCommand) {
        when (failure) {
            is ScheduleDrivesHealthSubject -> {
                pendingCommand = cmd
                _state.update { it.copy(saving = false, linkGuard = LinkGuardPrompt.Asks(failure.name)) }
            }
            is HealthSubjectIsPrimary -> {
                pendingCommand = null
                _state.update { it.copy(saving = false, linkGuard = LinkGuardPrompt.Primary) }
            }
            is PreServiceNeedsDates -> {
                val fresh = assets.get(failure.assetId)?.seasonInputs(emptyList())
                _state.update {
                    it.copy(saving = false, season = fresh ?: it.season, noBoundary = true, policyOption = null)
                }
            }
            else -> _state.update {
                it.copy(saving = false, problems = (failure as? ScheduleValidation)?.problems.orEmpty())
            }
        }
    }

    /** The schedule that is written and waiting for the rationale to be answered. */
    private var pendingSave: ScheduleId? = null

    /**
     * The owner read the rationale. Requests the permission through B05's seam and finishes either
     * way: the answer is a fact about the phone, not a condition on the schedule (D-22,
     * invariant 61).
     */
    fun requestNotifications() {
        val id = pendingSave ?: return
        viewModelScope.launch {
            runCatching { notifications.request() }
            pendingSave = null
            _state.update { it.copy(askingForNotifications = false) }
            _saved.tryEmit(id)
        }
    }

    /** The owner dismissed the rationale. Nothing is requested, and the schedule still stands. */
    fun dismissNotifications() {
        val id = pendingSave ?: return
        pendingSave = null
        _state.update { it.copy(askingForNotifications = false) }
        _saved.tryEmit(id)
    }

    private fun ScheduleEditState.command(): ScheduleCommand {
        val group = target as? ScheduleTarget.GroupTarget
        val asset = target as? ScheduleTarget.AssetTarget
        val meter = meterDefinitionId.takeIf { group == null }
        return ScheduleCommand(
            // One target or the other, from one value: there is no path that sets both.
            targetAssetId = asset?.assetId,
            targetGroupId = group?.groupId,
            title = title,
            description = description,
            timeInterval = timeInterval.trim().toIntOrNull(),
            timeUnit = timeUnit,
            timeBasis = timeBasis,
            // The anchor is only part of a time rule; a meter-only schedule has no series to anchor.
            anchorOn = anchorOn.trim().takeIf { it.isNotEmpty() && timeInterval.trim().isNotEmpty() },
            // A blank lead is no lead, which is zero, not a refusal.
            leadDays = leadDays.trim().toIntOrNull() ?: 0,
            meterDefinitionId = meter,
            meterInterval = meterInterval.trim().toDoubleOrNull().takeIf { meter != null },
            anchorMeter = anchorMeter.trim().toDoubleOrNull().takeIf { meter != null },
            // Sent **as typed**, negative or not. Dropping a negative silently saved the schedule
            // with *no* lead while the field still showed the number the owner entered — a value
            // quietly turned into a different one. `NegativeMeterLead` refuses it instead, and the
            // form marks the field (carry-forward (c): non-negative in the editor, now by refusal
            // rather than by erasure).
            meterLead = meterLead.trim().toDoubleOrNull().takeIf { meter != null },
            // The answer to S65, or CONTINUOUS where the question is not drawn — a group target
            // always (inv. 106). `save` never builds a command while the question is unanswered.
            servicePolicy = servicePolicy ?: ServicePolicy.CONTINUOUS,
            policyOffsetDays = policyOffsetDays,
            completionMode = if (group != null) CompletionMode.QUICK else completionMode,
            profileId = profileId.takeIf { group == null && completionMode == CompletionMode.FORM },
            remindersEnabled = remindersEnabled,
            // At most one row, ever (#4). #25 is the multi-provider UI and this is not it.
            providers = listOfNotNull(
                provider?.let { ScheduleProviderRow(it.name, enabled = remindersEnabled) },
            ),
        )
    }
}

/** A stored `Double` as the form shows it: "3" rather than "3.0", and "0.5" unchanged. */
internal fun plainNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
