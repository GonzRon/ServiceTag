package com.loosecannon.servicetag.ui.condition

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.loosecannon.servicetag.core.condition.ConditionHistory
import com.loosecannon.servicetag.core.model.AssetCondition
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonActivation
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.ConditionRepository
import com.loosecannon.servicetag.core.ports.SeasonActivationRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.usecase.AcceptOperationalOffer
import com.loosecannon.servicetag.core.usecase.AcceptSeasonOffer
import com.loosecannon.servicetag.core.usecase.operationalOfferFor
import com.loosecannon.servicetag.core.usecase.seasonOfferFor
import com.loosecannon.servicetag.ui.maintenance.NOT_NOW
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

/** S19, "You logged <event title>. Is <asset> working normally again?": the operational offer's body. */
fun operationalOfferBody(eventTitle: String, assetName: String): String =
    "You logged $eventTitle. Is $assetName working normally again?"

/** S20: the operational offer's dismiss. It writes nothing. */
const val NOT_YET = "Not yet"

/** S40: accepting the start offer, and asset detail's action (B14). */
const val START_SEASON = "Start season"

/** S41: accepting the end offer, and asset detail's action (B14). */
const val END_SEASON = "End season"

/** S51. */
const val START_THE_SEASON_NOW = "Start the season now?"

/** S52. */
const val END_THE_SEASON_NOW = "End the season now?"

/** S53, "You logged <event title>.": the season offer's body. */
fun seasonOfferBody(eventTitle: String): String = "You logged $eventTitle."

/**
 * An offer made after an event (spec §3.3, §5.4): a question, never an action. Nothing is written
 * unless the owner taps [acceptLabel]; [declineLabel] writes nothing (inv. 81, 93). [accepting] is
 * set on the first tap and disables both buttons, so a double tap can never write twice.
 */
sealed interface EventOffer {
    val event: AssetEvent
    val accepting: Boolean
    val title: String
    val body: String
    val acceptLabel: String
    val declineLabel: String
}

/** "Mark operational?" after a completion, or a MAINTENANCE or REPLACEMENT event, on a DOWN or DEGRADED asset. */
data class OperationalOfferPrompt(
    override val event: AssetEvent,
    val assetName: String,
    override val accepting: Boolean = false,
) : EventOffer {
    override val title: String get() = MARK_OPERATIONAL_TITLE
    override val body: String get() = operationalOfferBody(event.title, assetName)
    override val acceptLabel: String get() = MARK_OPERATIONAL
    override val declineLabel: String get() = NOT_YET
}

/** "Start the season now?" or "End the season now?" after a season event on a MANUAL asset. */
data class SeasonOfferPrompt(
    override val event: AssetEvent,
    val action: SeasonAction,
    override val accepting: Boolean = false,
) : EventOffer {
    override val title: String
        get() = if (action == SeasonAction.START) START_THE_SEASON_NOW else END_THE_SEASON_NOW
    override val body: String get() = seasonOfferBody(event.title)
    override val acceptLabel: String get() = if (action == SeasonAction.START) START_SEASON else END_SEASON
    override val declineLabel: String get() = NOT_NOW
}

/**
 * The offers already asked within one batch of completions — one "Complete selected" on the scan
 * sheet (the controller's rulings on B12's review, M-1 and RS-2). Each **asset** is asked each offer
 * **at most once per batch, whatever the item kinds**: after "Not yet" on the first of several items
 * done on one DOWN asset, the later items do not ask it again — a quick item done by the flow and a
 * form item saved in the journal entry alike, because the memory is keyed by the asset, never by the
 * item. A batch lives only as long as the run that made it, which opens it on [EventOffers] and
 * closes it when the run ends.
 */
class OfferBatch {
    private val asked = mutableSetOf<Pair<AssetId, String>>()

    /** True the first time [offer]'s asset is asked this offer in the batch, and false ever after. */
    fun firstTime(offer: EventOffer): Boolean = asked.add(offer.event.assetId to offer.kind)

    private val EventOffer.kind: String
        get() = when (this) {
            is OperationalOfferPrompt -> "operational"
            is SeasonOfferPrompt -> "season-${action.name}"
        }
}

fun EventOffer.tapped(): EventOffer = when (this) {
    is OperationalOfferPrompt -> copy(accepting = true)
    is SeasonOfferPrompt -> copy(accepting = true)
}

/**
 * Whether the app **asks** "Mark operational?" after [event] (spec §5.4; master plan §9): B06's
 * predicate — a completion, a MAINTENANCE or a REPLACEMENT event on an asset whose [current]
 * condition is DOWN or DEGRADED — and two more conditions, each a case in which accepting would be
 * refused, so an offer that cannot be kept is never made:
 *
 * - an event dated **after [today]** — the row would be `CONDITION_DATE_IN_FUTURE`, and there is no
 *   clamp (the carry-forward from B06);
 * - an event whose zone **does not resolve** on this device — the row takes the event's zone, which
 *   `RecordCondition` would refuse (the carry-forward from B06's review).
 */
fun operationalOfferShown(current: AssetCondition?, event: AssetEvent, today: LocalDate): Boolean {
    if (!operationalOfferFor(current, event)) return false
    val on = try {
        LocalDate.parse(event.occurredOn)
    } catch (_: DateTimeParseException) {
        return false
    }
    return on <= today && zoneResolves(event.tzId)
}

private fun zoneResolves(tzId: String): Boolean = try {
    ZoneId.of(tzId)
    true
} catch (_: DateTimeException) {
    false
}

/**
 * The operational offer as the app makes it: the question to ask after an event, read from the
 * store, and accepting it through B06's [AcceptOperationalOffer] (plan decision 51). An API write
 * never comes here — there is nobody to ask.
 */
class OperationalOffers(
    private val assets: AssetRepository,
    private val conditions: ConditionRepository,
    private val accept: AcceptOperationalOffer,
    private val today: Today,
) {
    /** The offer to make after [event], or null when none is due. Reads only. */
    suspend fun offerFor(event: AssetEvent): OperationalOfferPrompt? {
        val current = ConditionHistory.of(conditions.forAsset(event.assetId)).current
        if (!operationalOfferShown(current, event, today.localDate())) return null
        val asset = assets.get(event.assetId) ?: return null
        return OperationalOfferPrompt(event, asset.name)
    }

    /** The one write: OPERATIONAL, dated `max(event date, current row's date)`, linked to the event. */
    suspend fun accept(offer: OperationalOfferPrompt): AssetCondition = accept.run(offer.event.assetId, offer.event)
}

/**
 * The season offer (spec §3.3; inv. 93): after a SEASON_START or SEASON_END event on a MANUAL asset
 * in the opposite phase ([seasonOfferFor]), and accepting it through B04's [AcceptSeasonOffer], which
 * clamps the date — so the dialog shows no date of its own.
 */
class SeasonOffers(
    private val assets: AssetRepository,
    private val activations: SeasonActivationRepository,
    private val accept: AcceptSeasonOffer,
    private val today: Today,
) {
    suspend fun offerFor(event: AssetEvent): SeasonOfferPrompt? {
        val asset = assets.get(event.assetId) ?: return null
        val action = seasonOfferFor(asset, activations.forAsset(asset.id), event, today.localDate()) ?: return null
        return SeasonOfferPrompt(event, action)
    }

    suspend fun accept(offer: SeasonOfferPrompt): SeasonActivation =
        accept.run(offer.event.assetId, offer.event, offer.action)
}

/**
 * Every offer an event makes, in the order they are asked: "Mark operational?" first, then the
 * season offer. A journal entry makes at most one — its kind is either a MAINTENANCE or REPLACEMENT
 * or a season kind — but a **completion** can make both: it counts for the operational offer whatever
 * its kind, and it takes its profile's kind (`CompleteSchedule`), so a season-start task completed on
 * a DOWN MANUAL asset that is out of season is asked both, one after the other (spec §3.3, §5.4).
 *
 * Built once, in the graph, and shared by the completion flow and the journal entry.
 */
class EventOffers(private val operational: OperationalOffers, private val season: SeasonOffers) {

    /**
     * The batch a scan-sheet selection is running, from [open] to [close], or null. While it is open,
     * every offer asked goes through it — the completion flow's and the journal entry's that one of
     * its form items opens — so an asset is asked each offer once in the whole selection.
     */
    @Volatile private var selection: OfferBatch? = null

    /** Opens [batch] as the running selection's memory. */
    fun open(batch: OfferBatch) {
        selection = batch
    }

    /** Closes [batch] if it is still the running one; nothing else is closed by a late call. */
    fun close(batch: OfferBatch) {
        if (selection === batch) selection = null
    }

    /**
     * What [event] asks, read now — less any offer the open selection has already asked of that
     * asset, which this call then counts as asked. The two offers touch different facts, so neither
     * answer changes the other.
     */
    suspend fun offersAfter(event: AssetEvent): List<EventOffer> {
        val asked = listOfNotNull(operational.offerFor(event), season.offerFor(event))
        val open = selection ?: return asked
        return asked.filter(open::firstTime)
    }

    suspend fun accept(offer: EventOffer) {
        when (offer) {
            is OperationalOfferPrompt -> operational.accept(offer)
            is SeasonOfferPrompt -> season.accept(offer)
        }
    }
}

/**
 * The offer on screen: its title, its body, and the two answers. Dismissing it is declining it, and
 * declining writes nothing. Both buttons disable once accept is tapped.
 */
@Composable
fun EventOfferDialog(offer: EventOffer, onAccept: () -> Unit, onDecline: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if (!offer.accepting) onDecline() },
        title = { Text(offer.title) },
        text = { Text(offer.body, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(enabled = !offer.accepting, onClick = onAccept) { Text(offer.acceptLabel) }
        },
        dismissButton = {
            TextButton(enabled = !offer.accepting, onClick = onDecline) { Text(offer.declineLabel) }
        },
    )
}
