package com.loosecannon.servicetag.ui.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.servicetag.core.health.HealthSubjectShape
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.HealthAggregation
import com.loosecannon.servicetag.core.model.HealthDriver
import com.loosecannon.servicetag.core.model.HealthSubject
import com.loosecannon.servicetag.core.model.HealthSubjectId
import com.loosecannon.servicetag.core.model.HealthSubjectKind
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.HealthSubjectRepository
import com.loosecannon.servicetag.core.ports.ProfileRepository
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.usecase.ArchiveHealthSubject
import com.loosecannon.servicetag.core.usecase.HealthScheduleTaken
import com.loosecannon.servicetag.core.usecase.HealthSubjectCommand
import com.loosecannon.servicetag.core.usecase.HealthSubjectIsPrimary
import com.loosecannon.servicetag.core.usecase.HealthValidation
import com.loosecannon.servicetag.core.usecase.SUBJECT_NAME_LENGTH
import com.loosecannon.servicetag.core.usecase.SaveHealthSubject
import com.loosecannon.servicetag.core.usecase.hasTimeRule
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.asset.ratifiedParts
import com.loosecannon.servicetag.ui.asset.requiredMark
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One row of a picker: its id and the owner's own words for it (a quick action's name, a schedule's title). */
data class PickRow<T>(val id: T, val label: String)

/** An AGE subject's baseline (S120): any replacement (S121), or one REPLACEMENT quick action. */
sealed interface Baseline {
    /** S121 — the soft link left empty, which is the API's own default. */
    data object AnyReplacement : Baseline

    data class Action(val id: ProfileId) : Baseline
}

/**
 * The two starting points of spec §6.3 (Q-1), named by S128's two ratified parts, in S128's order.
 * **Not defaults and not safety claims:** a starting point fills the three fields only after the owner
 * confirms S129 with S130 (inv. 121), and an AGE subject is offered none.
 */
enum class StartingPoint(val nominalUntil: Int, val warningFrom: Int, val criticalFrom: Int) {
    ENGINE_SERVICE(14, 45, 120),
    WATER_CARE(2, 7, 14),
    ;

    /** This point's S128 words. */
    val label: String get() = ratifiedParts(STARTING_POINT_NAMES)[ordinal]
}

/**
 * The health subject editor's form (spec §10.4, §6.1, §6.3). Every text field is what was typed; the
 * command is built once, on Save.
 *
 * **Nothing is decided for the owner.** "What is it?" and "What wears it down?" start unanswered, the
 * three thresholds start **empty** and are filled by hand or by a starting point after S130 (Q-1,
 * inv. 121). A refusal that has no ratified sentence is made unreachable by mechanism (master dec. 46):
 * a 60-character name, digits capped at 36,500, a 1–10 stepper, pickers that offer only valid links,
 * and a held Save, whose unanswered fields carry an asterisk.
 */
data class HealthSubjectEditState(
    val loaded: Boolean = false,
    /** An existing subject: S136's action is drawn. */
    val editing: Boolean = false,
    /** The subject's asset, for the app bar: data, never a drafted string. */
    val assetName: String = "",
    val name: String = "",
    /** S113's answer; unanswered on a new subject. */
    val kind: HealthSubjectKind? = null,
    /** S117's answer; unanswered on a new subject. */
    val driver: HealthDriver? = null,
    /** S120's answer: S121 on a new subject; null only when a stored baseline is no longer offered. */
    val baseline: Baseline? = Baseline.AnyReplacement,
    /** S122's answer: one of [timedSchedules], or null. */
    val scheduleId: ScheduleId? = null,
    val nominalUntil: String = "",
    val warningFrom: String = "",
    val criticalFrom: String = "",
    /** S133's value, 1–10; kept as stored while hidden, never reset. */
    val weight: Int = 1,
    /** The asset combines by Weighted average, so S133 is drawn. */
    val weighted: Boolean = false,
    /** This asset's REPLACEMENT quick actions, for S120 after S121. */
    val replacementActions: List<PickRow<ProfileId>> = emptyList(),
    /** S122's choices: this asset's own schedules with a time rule, **archived ones excluded** (dec. 45). */
    val timedSchedules: List<PickRow<ScheduleId>> = emptyList(),
    /** The stored subject is archived: S136's action is "Restore subject". */
    val archived: Boolean = false,
    /** Restore is offered only while the stored subject's link would be accepted (B06's full re-check). */
    val restoreAllowed: Boolean = false,
    /** The starting point whose S129 is open; nothing is filled until S130. */
    val confirming: StartingPoint? = null,
    /**
     * The three fields still hold exactly what S130 put there. Those numbers belong to overdue
     * maintenance, so a switch to "Age since replacement" empties them (the controller's ruling on
     * B10-M2); any edit of a threshold makes them the owner's own and ends this.
     */
    val filledByStartingPoint: Boolean = false,
    /**
     * S125, as last judged: set when a threshold field is left or a Save is tried with numbers that do
     * not rise, and cleared by the next threshold edit — never judged keystroke by keystroke (the
     * controller's ruling on B10-M3).
     */
    val orderRefused: Boolean = false,
    /** S135: the schedule already drives another non-archived subject. Drawn under S122 only. */
    val scheduleTaken: Boolean = false,
    /** S137: archiving the subject its asset's health follows was refused. */
    val primaryRefused: Boolean = false,
    val saving: Boolean = false,
) {
    /** S123's three labels for an age subject, S124's for an overdue one; none until S117 is answered. */
    val thresholdLabels: List<String>
        get() = when (driver) {
            HealthDriver.AGE -> ratifiedParts(AGE_THRESHOLD_LABELS)
            HealthDriver.MAINTENANCE_OVERDUE -> ratifiedParts(OVERDUE_THRESHOLD_LABELS)
            null -> emptyList()
        }

    val thresholds: List<String> get() = listOf(nominalUntil, warningFrom, criticalFrom)

    /** S126: a threshold is still empty. */
    val thresholdsMissing: Boolean get() = thresholds.any { it.isEmpty() }

    /** The numbers entered so far do not rise strictly, in field order: what S125 is judged on. */
    val thresholdsOutOfOrder: Boolean
        get() = thresholds.mapNotNull { it.toIntOrNull() }.zipWithNext().any { (before, after) -> after <= before }

    /** S127 is offered for overdue maintenance only: AGE has no starting point (spec §6.3). */
    val startingPointsOffered: Boolean get() = driver == HealthDriver.MAINTENANCE_OVERDUE

    /** S120's rows: S121 first, then this asset's REPLACEMENT quick actions. */
    val baselineOptions: List<PickRow<Baseline>>
        get() = listOf(PickRow<Baseline>(Baseline.AnyReplacement, ANY_REPLACEMENT)) +
            replacementActions.map { PickRow(Baseline.Action(it.id), it.label) }

    val nameLabel: String get() = marked(NAME, name.isBlank())
    val kindLabel: String get() = marked(WHAT_IS_IT, kind == null)
    val driverLabel: String get() = marked(WHAT_WEARS_IT_DOWN, driver == null)
    val baselineLabel: String get() = marked(REPLACEMENT_QUICK_ACTION, baseline == null)
    val scheduleLabel: String get() = marked(MAINTENANCE_SCHEDULE, !scheduleChosen)

    private val scheduleChosen: Boolean get() = timedSchedules.any { it.id == scheduleId }

    /**
     * Save is held until every answer is given: a name, S113, S117, the link S117 needs, all three
     * thresholds, and a weight of 1–10. No refusal of these needs a sentence. Numbers that do not rise
     * are the one thing a Save may be tried with: the try is answered by S125 and writes nothing.
     */
    val canSave: Boolean
        get() = loaded && !saving && name.isNotBlank() && kind != null && linkAnswered &&
            !thresholdsMissing && HealthSubjectShape.weightValid(weight)

    private val linkAnswered: Boolean
        get() = when (driver) {
            HealthDriver.AGE -> baseline != null
            HealthDriver.MAINTENANCE_OVERDUE -> scheduleChosen
            null -> false
        }

    private fun marked(label: String, missing: Boolean): String = if (missing) requiredMark(label) else label
}

/**
 * The threshold fields' filter (master dec. 46): digits only, at most 36,500. A keystroke it refuses
 * is not taken, so the field keeps what it had and no refusal is ever reached.
 */
internal fun acceptsThresholdDays(text: String): Boolean =
    text.isEmpty() ||
        (text.length <= MAX_THRESHOLD_DIGITS && text.all { it in '0'..'9' } &&
            text.toInt() <= HealthSubjectShape.MAX_THRESHOLD_DAYS)

private const val MAX_THRESHOLD_DIGITS = 5

/**
 * Creates ([subjectId] null) or edits one health subject of [assetId] (spec §10.4, §6.1). Every rule is
 * the use cases'; this turns the form into one [HealthSubjectCommand] and draws what comes back.
 *
 * - [HealthScheduleTaken] is S135 (inv. 120); archiving the primary is S137 ([HealthSubjectIsPrimary]).
 * - **The race rule** (B08's shape; the plan-review follow-up's F5): an API or MCP write can archive, retarget or
 *   de-rule the schedule between this model's check and the tap. The [HealthValidation] that answers
 *   it — `FOREIGN_SCHEDULE`, `HEALTH_SCHEDULE_NEEDS_A_TIME_RULE` — **reloads the pickers and the
 *   Restore state, writes nothing and draws no sentence**: the schedule simply leaves S122.
 * - Cancel is the screen's: nothing is written until Save, Archive or Restore.
 */
class HealthSubjectEditViewModel(
    private val assets: AssetRepository,
    private val subjects: HealthSubjectRepository,
    private val schedules: ScheduleRepository,
    private val profiles: ProfileRepository,
    private val saveHealthSubject: SaveHealthSubject,
    private val archiveHealthSubject: ArchiveHealthSubject,
    private val assetId: AssetId,
    private val subjectId: HealthSubjectId?,
) : ViewModel() {

    constructor(graph: AppGraph, assetId: String, subjectId: String?) : this(
        graph.assets, graph.healthSubjects, graph.schedules, graph.profiles,
        graph.saveHealthSubject, graph.archiveHealthSubject,
        AssetId(assetId), subjectId?.let(::HealthSubjectId),
    )

    private val _state = MutableStateFlow(HealthSubjectEditState())
    val state: StateFlow<HealthSubjectEditState> = _state.asStateFlow()

    /** One shot per successful Save: the screen leaves. Archive and Restore stay on the form. */
    private val _saved = MutableSharedFlow<HealthSubjectId>(replay = 0, extraBufferCapacity = 1)
    val saved: SharedFlow<HealthSubjectId> = _saved.asSharedFlow()

    /** The asset the subject belongs to: the stored subject's own, once read (a subject never moves). */
    private var owner: AssetId = assetId

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val stored = subjectId?.let { subjects.get(it) }
        owner = stored?.assetId ?: assetId
        val asset = assets.get(owner)
        val links = linksFor(keepProfile = stored?.baselineProfileId)
        _state.update { form ->
            val base = form.copy(
                loaded = true,
                editing = stored != null,
                assetName = asset?.name.orEmpty(),
                weighted = asset?.healthAggregation == HealthAggregation.WEIGHTED,
                replacementActions = links.actions,
                timedSchedules = links.schedules,
            )
            if (stored == null) base else base.filledFrom(stored, links)
        }
    }

    /** The 60-character input limit (master dec. 46): the name stops there, so no name refusal is reached. */
    fun onName(value: String) = _state.update { it.copy(name = value.take(SUBJECT_NAME_LENGTH.last)) }

    fun onKind(kind: HealthSubjectKind) = _state.update { it.copy(kind = kind) }

    /**
     * Kind and driver are independent (spec §6.1); the driver decides which link and labels apply. S135
     * is about S122's schedule, so it goes with the change; so do numbers a starting point put there
     * when the driver becomes age, which has no starting point (spec §6.3).
     */
    fun onDriver(driver: HealthDriver) = _state.update { form ->
        if (form.driver == driver) return@update form
        val cleared = form.filledByStartingPoint && driver == HealthDriver.AGE
        form.copy(
            driver = driver,
            confirming = null,
            scheduleTaken = false,
            nominalUntil = if (cleared) "" else form.nominalUntil,
            warningFrom = if (cleared) "" else form.warningFrom,
            criticalFrom = if (cleared) "" else form.criticalFrom,
            filledByStartingPoint = form.filledByStartingPoint && !cleared,
            orderRefused = form.orderRefused && !cleared,
        )
    }

    fun onBaseline(baseline: Baseline) = _state.update { form ->
        val offered = form.baselineOptions.any { it.id == baseline }
        if (offered) form.copy(baseline = baseline) else form
    }

    fun onSchedule(id: ScheduleId) = _state.update { form ->
        if (form.timedSchedules.any { it.id == id }) form.copy(scheduleId = id, scheduleTaken = false) else form
    }

    /** One of the three thresholds, [index] 0–2, through the digits filter. */
    fun onThreshold(index: Int, text: String) {
        if (!acceptsThresholdDays(text)) return
        _state.update { form ->
            val edited = form.copy(filledByStartingPoint = false, orderRefused = false)
            when (index) {
                0 -> edited.copy(nominalUntil = text)
                1 -> edited.copy(warningFrom = text)
                2 -> edited.copy(criticalFrom = text)
                else -> form
            }
        }
    }

    /** A threshold field was left: S125 is judged now, on what the three fields hold. */
    fun commitThresholds() = _state.update { it.copy(orderRefused = it.thresholdsOutOfOrder) }

    /** S133's stepper: one step at a time, never below 1 or above 10. */
    fun onWeightStep(step: Int) = _state.update { form ->
        form.copy(weight = (form.weight + step).coerceIn(HealthSubjectShape.WEIGHTS))
    }

    /** An S128 name was picked: S129 opens. Nothing is filled yet, and AGE is offered none. */
    fun chooseStartingPoint(point: StartingPoint) = _state.update { form ->
        if (form.startingPointsOffered) form.copy(confirming = point) else form
    }

    /** S130, and only S130, fills the three fields (inv. 121). */
    fun confirmStartingPoint() = _state.update { form ->
        val point = form.confirming ?: return@update form
        form.copy(
            nominalUntil = point.nominalUntil.toString(),
            warningFrom = point.warningFrom.toString(),
            criticalFrom = point.criticalFrom.toString(),
            confirming = null,
            filledByStartingPoint = true,
            orderRefused = false,
        )
    }

    /** Cancel on S129 fills nothing. */
    fun cancelStartingPoint() = _state.update { it.copy(confirming = null) }

    fun dismissPrimaryRefusal() = _state.update { it.copy(primaryRefused = false) }

    /**
     * One create or update. The guard is set before the first suspension, so two taps in one frame
     * write once. A refusal writes nothing, because the use case wrote nothing.
     */
    fun save() {
        val form = _state.value
        if (!form.canSave) return
        if (form.thresholdsOutOfOrder) {
            _state.update { it.copy(orderRefused = true) }
            return
        }
        _state.update { it.copy(saving = true, scheduleTaken = false) }
        viewModelScope.launch {
            val cmd = form.command()
            val outcome = runCatching {
                if (subjectId == null) saveHealthSubject.create(owner, cmd) else saveHealthSubject.update(subjectId, cmd)
            }
            when (outcome.exceptionOrNull()) {
                null -> {
                    _state.update { it.copy(saving = false) }
                    outcome.getOrNull()?.let { _saved.tryEmit(it.id) }
                }
                is HealthScheduleTaken -> _state.update { it.copy(saving = false, scheduleTaken = true) }
                is HealthValidation -> reloadLinks()
                else -> _state.update { it.copy(saving = false) }
            }
        }
    }

    /** S136's first action. The primary answers S137 and stays live. */
    fun archive() = operate(archived = true)

    /**
     * S136's second action, offered only while the stored link would be accepted. A schedule that now
     * drives another subject still answers S135; a link that broke since the check reloads silently.
     */
    fun restore() {
        if (_state.value.restoreAllowed) operate(archived = false)
    }

    private fun operate(archived: Boolean) {
        val id = subjectId ?: return
        if (_state.value.saving) return
        _state.update { it.copy(saving = true, scheduleTaken = false) }
        viewModelScope.launch {
            when (runCatching { archiveHealthSubject.run(id, archived) }.exceptionOrNull()) {
                null, is HealthValidation -> reloadLinks()
                is HealthSubjectIsPrimary -> _state.update { it.copy(saving = false, primaryRefused = true) }
                is HealthScheduleTaken -> _state.update { it.copy(saving = false, scheduleTaken = true) }
                else -> _state.update { it.copy(saving = false) }
            }
        }
    }

    /**
     * Re-reads the pickers and the stored subject's archive and Restore state, keeping everything the
     * owner typed. A chosen schedule or quick action that is no longer offered is let go, so Save waits
     * for a new answer instead of repeating a refusal.
     */
    private suspend fun reloadLinks() {
        val stored = subjectId?.let { subjects.get(it) }
        val kept = (_state.value.baseline as? Baseline.Action)?.id
        val links = linksFor(keepProfile = kept ?: stored?.baselineProfileId)
        _state.update { form ->
            form.copy(
                replacementActions = links.actions,
                timedSchedules = links.schedules,
                scheduleId = form.scheduleId?.takeIf { id -> links.schedules.any { it.id == id } },
                baseline = form.baseline?.takeIf { chosen ->
                    chosen == Baseline.AnyReplacement || links.actions.any { Baseline.Action(it.id) == chosen }
                },
                archived = stored?.archivedAt != null,
                restoreAllowed = stored?.let { restorable(it, links) } == true,
                saving = false,
            )
        }
    }

    private fun HealthSubjectEditState.command() = HealthSubjectCommand(
        name = name.trim(),
        kind = requireNotNull(kind),
        driver = requireNotNull(driver),
        scheduleId = scheduleId.takeIf { driver == HealthDriver.MAINTENANCE_OVERDUE },
        baselineProfileId = (baseline as? Baseline.Action)?.id.takeIf { driver == HealthDriver.AGE },
        nominalUntilDays = nominalUntil.toIntOrNull(),
        warningFromDays = warningFrom.toIntOrNull(),
        criticalFromDays = criticalFrom.toIntOrNull(),
        weight = weight,
    )

    /** A stored subject as form text. A link no longer offered is let go; the weight is kept as stored. */
    private fun HealthSubjectEditState.filledFrom(stored: HealthSubject, links: Links) = copy(
        name = stored.name,
        kind = stored.kind,
        driver = stored.driver,
        baseline = when (val profile = stored.baselineProfileId) {
            null -> Baseline.AnyReplacement
            else -> Baseline.Action(profile).takeIf { links.actions.any { it.id == profile } }
        },
        scheduleId = stored.scheduleId?.takeIf { id -> links.schedules.any { it.id == id } },
        nominalUntil = stored.nominalUntilDays.toString(),
        warningFrom = stored.warningFromDays.toString(),
        criticalFrom = stored.criticalFromDays.toString(),
        weight = stored.weight,
        archived = stored.archivedAt != null,
        restoreAllowed = restorable(stored, links),
    )

    /**
     * What the pickers offer. S120: this asset's REPLACEMENT quick actions that are not archived, plus
     * [keepProfile] when it is one of them that has been archived since, so an edit never silently
     * swaps a subject's baseline. S122: this asset's own schedules that have a time rule and are not
     * archived — never a group's, another asset's or a meter-only one (spec §6.1, §6.2; dec. 45).
     */
    private suspend fun linksFor(keepProfile: ProfileId?): Links {
        val replacements = profiles.forAsset(owner).filter { it.eventKind == EventKind.REPLACEMENT }
        val actions = replacements
            .filter { it.archivedAt == null || it.id == keepProfile }
            .sortedWith(compareBy({ it.sortOrder }, { it.id.value }))
            .map { PickRow(it.id, it.name) }
        val timed = schedules.forAsset(owner)
            .filter { it.target == ScheduleTarget.AssetTarget(owner) && it.status != ScheduleStatus.ARCHIVED && it.timed() }
            .sortedWith(compareBy({ it.title.lowercase() }, { it.id.value }))
            .map { PickRow(it.id, it.title) }
        return Links(actions, timed)
    }

    /**
     * Whether Restore would pass B06's full link re-check on the stored row: an age subject names no
     * schedule; an overdue one names no baseline and a schedule S122 still offers.
     */
    private fun restorable(stored: HealthSubject, links: Links): Boolean =
        stored.archivedAt != null && when (stored.driver) {
            HealthDriver.AGE -> stored.scheduleId == null
            HealthDriver.MAINTENANCE_OVERDUE ->
                stored.baselineProfileId == null && links.schedules.any { it.id == stored.scheduleId }
        }

    /**
     * A time side, by `:core`'s own [hasTimeRule] — the rule B06's link check refuses by — so S122's filter
     * and `HEALTH_SCHEDULE_NEEDS_A_TIME_RULE` can never read a schedule differently.
     */
    private fun MaintenanceSchedule.timed(): Boolean = hasTimeRule()

    private data class Links(val actions: List<PickRow<ProfileId>>, val schedules: List<PickRow<ScheduleId>>)
}
