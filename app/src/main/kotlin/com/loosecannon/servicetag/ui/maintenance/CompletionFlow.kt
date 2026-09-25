package com.loosecannon.servicetag.ui.maintenance

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.ports.DefinitionRepository
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.usecase.CompleteGroupMembers
import com.loosecannon.servicetag.core.usecase.CompleteSchedule
import com.loosecannon.servicetag.core.usecase.CompletionCommand
import com.loosecannon.servicetag.core.usecase.GroupCompletionNotSupported
import com.loosecannon.servicetag.core.usecase.NoSuchSchedule
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.asset.DateField
import com.loosecannon.servicetag.ui.condition.EventOfferDialog
import com.loosecannon.servicetag.ui.condition.OperationalOfferPrompt
import com.loosecannon.servicetag.ui.condition.OperationalOffers
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** RATIFIED, verbatim (master plan §17): the completion affordance's own question. */
const val WHEN_WAS_THIS_DONE = "When was this done?"

/** RATIFIED (§17): the two group-round completion labels. */
const val COMPLETE_ALL = "Complete all"
const val COMPLETE_SELECTED = "Complete selected"

/**
 * What a completion attempt ended as. Every member is a fact, not a message: no user-visible string
 * is minted here, because the three surfaces that call this flow render their own outcome and §17
 * ratifies no wording for a refusal.
 */
sealed interface CompletionOutcome {
    /** The events that were written — one for an asset target, one per member for a group round. */
    data class Completed(val events: List<AssetEvent>) : CompletionOutcome

    /**
     * A `FORM` schedule: the details are collected by the profile form and **nothing is fabricated
     * here**. The caller navigates; this flow writes no event at all for such a schedule, which is
     * why the in-app path can never produce a `details_pending` completion.
     */
    data class NeedsForm(
        val scheduleId: ScheduleId,
        val assetId: AssetId,
        val profileId: ProfileId?,
    ) : CompletionOutcome

    /** The owner backed out of the affordance, or a prompt was already open. Nothing was written. */
    data object Cancelled : CompletionOutcome

    /** A use case refused. The cause is carried, never a sentence. */
    data class Refused(val cause: Throwable) : CompletionOutcome
}

/**
 * The affordance's open question, as a surface renders it.
 *
 * [meterDefinitionId] non-null is the whole meter rule: the schedule counts something, so the
 * completion **cannot** be recorded until the reading is entered (#11, D5 §3). The label and unit
 * are the definition's own stored data and not a drafted string.
 */
data class CompletionPrompt(
    val scheduleId: ScheduleId,
    val scheduleTitle: String,
    val occurredOn: String,
    val meterDefinitionId: DefinitionId? = null,
    val meterLabel: String? = null,
    val meterUnit: String? = null,
) {
    val needsMeterReading: Boolean get() = meterDefinitionId != null
}

/** What the owner answered: the service date, an optional time, and the reading where one is owed. */
data class CompletionAnswer(
    val occurredOn: String,
    val occurredTime: String? = null,
    val meterValue: String? = null,
)

/**
 * **The only completion mechanism in 1.2.**
 *
 * Three surfaces reach it and none of them writes an event of its own: the schedule detail screen
 * (this brief), the scan completion sheet (#50) and the Maintenance destination's "Log maintenance"
 * quick action (F4). One collaborator is how they cannot diverge — spec §2.8 has the sheet delegate
 * entirely to the #4 use cases and spec §2.9 gives the in-app close the same date affordance as the
 * API, so a second implementation of "when was this done" would be a second answer to one question
 * (master plan decision 36).
 *
 * It holds one open [prompt] at a time and [complete] **suspends** until the owner answers it or
 * backs out — the shape the brief's interface asks for, and the reason the return value can promise
 * that the event is written. A second concurrent attempt is [CompletionOutcome.Cancelled] rather
 * than a second dialog: two prompts over one store is how a stale answer gets written.
 *
 * It re-implements no rule. `occurrence_on`, the idempotence index, the conditional postponement
 * clear and the recompute are all the use cases'; what is here is the affordance, the optional
 * time, the meter demand and the routing of a `FORM` schedule into its own form.
 *
 * **1.4 — the operational offer** (spec §5.4; master plan §9, decision 12). After a completion, each
 * completed asset that is DOWN or DEGRADED is asked "Mark operational?" — **one at a time**, one
 * offer per completed member of a group round, in the order the events were written — and the call
 * returns once every offer is answered. Declining one leaves the others to be asked. Only the
 * accept writes, through [OperationalOffers]; the completion itself never touches a condition
 * (inv. 81). The notification's one-tap completion does not come through this flow at all, so it
 * offers nothing: there is no screen to ask on.
 */
class CompletionFlow(
    private val schedules: ScheduleRepository,
    private val definitions: DefinitionRepository,
    private val completeSchedule: CompleteSchedule,
    private val completeGroupMembers: CompleteGroupMembers,
    private val today: Today,
    private val offers: OperationalOffers,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {

    constructor(graph: AppGraph) : this(
        graph.schedules,
        graph.definitions,
        graph.completeSchedule,
        graph.completeGroupMembers,
        graph.today,
        OperationalOffers(graph.assets, graph.conditions, graph.acceptOperationalOffer, graph.today),
    )

    private val _prompt = MutableStateFlow<CompletionPrompt?>(null)

    /** The open affordance, or null. A surface renders it with [CompletionFlowHost]. */
    val prompt: StateFlow<CompletionPrompt?> = _prompt.asStateFlow()

    private var pending: CompletableDeferred<CompletionAnswer?>? = null

    private val _offer = MutableStateFlow<OperationalOfferPrompt?>(null)

    /** The open "Mark operational?" offer, or null. [CompletionFlowHost] renders it too. */
    val offer: StateFlow<OperationalOfferPrompt?> = _offer.asStateFlow()

    private var offerAnswer: CompletableDeferred<Boolean>? = null

    /**
     * One occurrence of one schedule, done.
     *
     * [assetId] names the member for a group-targeted schedule and is ignored for an asset target,
     * whose member is its own Asset. A group target with no member named is refused rather than
     * guessed at: picking one would write a maintenance record onto somebody else's equipment
     * (invariants 28, 29).
     */
    suspend fun complete(scheduleId: ScheduleId, assetId: AssetId? = null): CompletionOutcome {
        val schedule = schedules.get(scheduleId)
            ?: return CompletionOutcome.Refused(NoSuchSchedule(scheduleId))
        return when (val target = schedule.target) {
            is ScheduleTarget.AssetTarget ->
                // A `FORM` schedule collects its details in its profile form. Asking "when was this
                // done" here and writing a minimal completion would mark the event `details_pending`
                // from a surface that had the owner's whole attention — the one place the quick path
                // is not wanted (spec §2.1, D-18b).
                if (schedule.completionMode == CompletionMode.FORM) {
                    CompletionOutcome.NeedsForm(scheduleId, target.assetId, schedule.profileId)
                } else {
                    val answer = ask(schedule) ?: return CompletionOutcome.Cancelled
                    attempt { listOf(completeSchedule.run(scheduleId, answer.command(schedule))) }
                }
            is ScheduleTarget.GroupTarget -> {
                if (assetId == null) {
                    return CompletionOutcome.Refused(GroupCompletionNotSupported(scheduleId))
                }
                completeSelected(scheduleId, listOf(assetId))
            }
        }
    }

    /** "Complete selected": the named members of a group round, in one write. */
    suspend fun completeSelected(scheduleId: ScheduleId, assetIds: List<AssetId>): CompletionOutcome {
        val schedule = schedules.get(scheduleId)
            ?: return CompletionOutcome.Refused(NoSuchSchedule(scheduleId))
        val answer = ask(schedule) ?: return CompletionOutcome.Cancelled
        return attempt { completeGroupMembers.run(scheduleId, assetIds, answer.command(schedule)) }
    }

    /** "Complete all": every required member of a group round that is not yet done. */
    suspend fun completeAll(scheduleId: ScheduleId): CompletionOutcome {
        val schedule = schedules.get(scheduleId)
            ?: return CompletionOutcome.Refused(NoSuchSchedule(scheduleId))
        val answer = ask(schedule) ?: return CompletionOutcome.Cancelled
        return attempt { completeGroupMembers.all(scheduleId, answer.command(schedule)) }
    }

    /**
     * Answers the open prompt. **False means the answer was incomplete and the prompt stays open**
     * — the one case being a schedule whose meter rule owes a reading, which cannot be completed
     * until the reading is entered. Returning false rather than writing is what keeps the next
     * threshold right instead of derived from a baseline nobody measured.
     */
    fun submit(answer: CompletionAnswer): Boolean {
        val open = _prompt.value ?: return false
        if (open.needsMeterReading && answer.meterValue?.trim().isNullOrEmpty()) return false
        return pending?.complete(answer) ?: false
    }

    /** The owner backed out. Nothing is written, which is the affordance's whole promise. */
    fun cancel() {
        pending?.complete(null)
    }

    /**
     * **"Mark operational"** on the open offer. The offer is marked as tapped **before** anything
     * else, which disables its buttons, and a second tap finds it tapped and does nothing — so a double
     * tap can never write a second OPERATIONAL row. False when there was nothing to accept.
     */
    fun acceptOffer(): Boolean {
        val open = _offer.value ?: return false
        if (open.accepting) return false
        _offer.value = open.copy(accepting = true)
        return offerAnswer?.complete(true) ?: false
    }

    /** **"Not yet"**: nothing is written, and the next offer, if any, is asked. */
    fun declineOffer() {
        val open = _offer.value ?: return
        if (open.accepting) return
        offerAnswer?.complete(false)
    }

    /**
     * Opens the affordance and waits. Null means the owner backed out, or a prompt was already open
     * — in both cases nothing is written, which is what [CompletionOutcome.Cancelled] states.
     *
     * A group target carries no meter rule (invariant 2), so its prompt is the date and the optional
     * time and nothing else; the demand below is reached by an asset target alone.
     */
    private suspend fun ask(schedule: MaintenanceSchedule): CompletionAnswer? {
        if (pending != null) return null
        val meter = schedule.meterDefinitionId?.let { definitions.get(it) }
        val answer = CompletableDeferred<CompletionAnswer?>()
        pending = answer
        _prompt.value = CompletionPrompt(
            scheduleId = schedule.id,
            scheduleTitle = schedule.title,
            occurredOn = today.localDate().toString(),
            // A meter rule whose definition has gone leaves nothing to demand a reading of, and
            // demanding one anyway would make the schedule permanently uncompletable.
            meterDefinitionId = meter?.id,
            meterLabel = meter?.label,
            meterUnit = meter?.unit,
        )
        return try {
            answer.await()
        } finally {
            pending = null
            _prompt.value = null
        }
    }

    /**
     * A refusal is an outcome rather than an exception crossing a view model. A completion that was
     * written is followed by its offers before the outcome is returned.
     */
    private suspend fun attempt(block: suspend () -> List<AssetEvent>): CompletionOutcome {
        val events = try {
            block()
        } catch (failure: Throwable) {
            return CompletionOutcome.Refused(failure)
        }
        offerAfter(events)
        return CompletionOutcome.Completed(events)
    }

    /**
     * One "Mark operational?" per completed asset that qualifies, **one at a time**: each is read
     * fresh when its turn comes, asked, and answered before the next is read. A refused accept is
     * logged and the next offer still asked — §10.7 ratifies no sentence for it, and the asset's
     * condition simply stays as recorded.
     */
    private suspend fun offerAfter(events: List<AssetEvent>) {
        for (event in events) {
            val prompt = offers.offerFor(event) ?: continue
            val answer = CompletableDeferred<Boolean>()
            offerAnswer = answer
            _offer.value = prompt
            try {
                if (answer.await()) {
                    try {
                        offers.accept(prompt)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (refused: Exception) {
                        Log.w(TAG, "an accepted operational offer was refused", refused)
                    }
                }
            } finally {
                offerAnswer = null
                _offer.value = null
            }
        }
    }

    private companion object {
        const val TAG = "CompletionFlow"
    }

    private fun CompletionAnswer.command(schedule: MaintenanceSchedule): CompletionCommand {
        val reading = meterValue?.trim()?.takeIf { it.isNotEmpty() }
        val meterId = schedule.meterDefinitionId
        return CompletionCommand(
            occurredOn = occurredOn.trim(),
            occurredTime = occurredTime?.trim()?.takeIf { it.isNotEmpty() },
            tzId = zone().id,
            // The reading rides the ordinary measurement path, against the schedule's **own** meter
            // definition, so `currentMeter` and `lastCompletedMeter` advance from a real event
            // rather than a number written into derived state.
            values = if (meterId != null && reading != null) mapOf(meterId to reading) else emptyMap(),
        )
    }
}

/**
 * The affordance itself: **"When was this done?"**, today by default, an optional time, and the
 * reading where the rule owes one.
 *
 * Drawn wherever a [CompletionFlow] is driven, which is why it is a composable over the flow rather
 * than part of any one screen — the scan sheet and the quick action get the same dialog, by
 * construction. The confirm button stays disabled while a required reading is missing, so the meter
 * rule is unreachable through the UI as well as refused by [CompletionFlow.submit].
 */
@Composable
fun CompletionFlowHost(flow: CompletionFlow) {
    val prompt by flow.prompt.collectAsStateWithLifecycle()
    val offer by flow.offer.collectAsStateWithLifecycle()
    // The operational offer after a completion (1.4), on every surface that drives the flow.
    offer?.let { EventOfferDialog(it, onAccept = { flow.acceptOffer() }, onDecline = flow::declineOffer) }
    val open = prompt ?: return

    // Keyed on the schedule so a second prompt starts from today again rather than the last answer.
    var occurredOn by remember(open.scheduleId.value) { mutableStateOf(open.occurredOn) }
    var occurredTime by remember(open.scheduleId.value) { mutableStateOf("") }
    var reading by remember(open.scheduleId.value) { mutableStateOf("") }

    val answered = !open.needsMeterReading || reading.trim().isNotEmpty()

    AlertDialog(
        onDismissRequest = flow::cancel,
        title = { Text(WHEN_WAS_THIS_DONE) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(open.scheduleTitle, style = MaterialTheme.typography.bodyMedium)
                DateField(value = occurredOn, onValueChange = { occurredOn = it }, label = "Date")
                MaintenanceField(
                    value = occurredTime,
                    onValueChange = { occurredTime = it },
                    label = "Time",
                    placeholder = "HH:MM",
                    mono = true,
                )
                if (open.needsMeterReading) {
                    MaintenanceField(
                        value = reading,
                        onValueChange = { reading = it },
                        // The RATIFIED repair label, which is exactly what this field is for.
                        label = LOG_METER_READING,
                        hint = open.meterUnit?.takeIf { it.isNotBlank() },
                        numeric = true,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = answered,
                onClick = {
                    flow.submit(
                        CompletionAnswer(
                            occurredOn = occurredOn,
                            occurredTime = occurredTime,
                            meterValue = reading,
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = flow::cancel) { Text("Cancel") } },
    )
}

/**
 * F4's **"Log maintenance"**, connected: pick the schedule, then the canonical flow.
 *
 * B08 left the quick action wired to a no-op so that this brief could connect it to the one
 * completion mechanism rather than give it a second one (master plan §1.2, #50) — and this is that
 * connection. It writes nothing of its own: the flow writes, exactly as it does for the detail
 * screen and B09's sheet, and a `FORM` schedule leaves through [onLogForm] with nothing fabricated.
 *
 * The list is the shared projection's **actionable** rows, drawn with the same [DueItemRow] every
 * other surface draws, which is why this picker needs no string of its own. A phone with nothing
 * actionable gets no dialog at all: §17 ratifies no line for "there is nothing to log", and a brief
 * that needed one would be drafting it — so the finding is reported and the action stays quiet.
 */
@Composable
fun LogMaintenancePicker(
    flow: CompletionFlow,
    due: DueReadModel,
    onDismiss: () -> Unit,
    onLogForm: (assetId: String, profileId: String?) -> Unit,
    onOpenSchedule: (scheduleId: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val items by produceState(initialValue = emptyList<DueItem>(), due) {
        value = due.items().filter { it.countsAsDue || it.isRepairableNoData }
    }
    val prompt by flow.prompt.collectAsStateWithLifecycle()
    val offer by flow.offer.collectAsStateWithLifecycle()

    // The affordance, hosted here too, so the quick action asks the same question in the same words.
    CompletionFlowHost(flow)

    // Once the flow has the owner's attention — its question, or the offer after it — the picker
    // steps out of the way rather than stacking a second dialog over its own.
    if (prompt != null || offer != null) return
    if (items.isEmpty()) {
        LaunchedEffect(items) { onDismiss() }
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(MAINTENANCE_TITLE) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items.forEach { item ->
                    DueItemRow(
                        item = item,
                        onClick = {
                            val asset = (item.target as? ScheduleTarget.AssetTarget)?.assetId
                            if (asset == null) {
                                // A **group** round is a checklist, and this flow cannot pick which
                                // member did the work — it would have to write a maintenance record
                                // onto somebody else's equipment (invariants 28, 29). Completing it
                                // here with no member named is refused, so the row opens the screen
                                // that has "Complete all", "Complete selected" and the checklist,
                                // exactly as the form branch below leaves for its own form. A group
                                // row is one row by D-15 and is routinely due, so the alternative is
                                // a tap that closes the dialog and does nothing, with no ratified
                                // string available to explain it.
                                onDismiss()
                                onOpenSchedule(item.scheduleId.value)
                            } else {
                                scope.launch {
                                    val outcome = flow.complete(item.scheduleId, asset)
                                    if (outcome is CompletionOutcome.NeedsForm) {
                                        onLogForm(outcome.assetId.value, outcome.profileId?.value)
                                    }
                                    onDismiss()
                                }
                            }
                        },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The destination's RATIFIED title (§17), reused as the picker's — it is the same subject. */
private const val MAINTENANCE_TITLE = "Maintenance"
