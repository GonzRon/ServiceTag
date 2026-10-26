package com.loosecannon.servicetag.ui.asset

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.servicetag.core.hapublish.DecommissionRepository
import com.loosecannon.servicetag.core.hapublish.DecommissionStillPending
import com.loosecannon.servicetag.core.hapublish.DecommissionTombstone
import com.loosecannon.servicetag.core.hapublish.DeliverySteps
import com.loosecannon.servicetag.core.hapublish.FIELD_BOUNDARY_ENTITY_ID
import com.loosecannon.servicetag.core.hapublish.INVALID_SERVICE_DATE
import com.loosecannon.servicetag.core.hapublish.PublishBinding
import com.loosecannon.servicetag.core.hapublish.PublishBindingRepository
import com.loosecannon.servicetag.core.hapublish.PublishDelivery
import com.loosecannon.servicetag.core.hapublish.PublishDeliveryRepository
import com.loosecannon.servicetag.core.hapublish.NoSuchDecommission
import com.loosecannon.servicetag.core.hapublish.PublishKind
import com.loosecannon.servicetag.core.hapublish.PublishNotSetUp
import com.loosecannon.servicetag.core.hapublish.PublishRefused
import com.loosecannon.servicetag.core.hapublish.PublishState
import com.loosecannon.servicetag.core.hapublish.RetryClass
import com.loosecannon.servicetag.core.hapublish.STATUS_DEGRADED
import com.loosecannon.servicetag.core.hapublish.STATUS_DOWN
import com.loosecannon.servicetag.core.hapublish.STATUS_UP
import com.loosecannon.servicetag.core.hapublish.Snap
import com.loosecannon.servicetag.core.hapublish.errorIsCurrent
import com.loosecannon.servicetag.core.hapublish.retryClassOf
import com.loosecannon.servicetag.core.hapublish.seasonDateOf
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.maintainedHere
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.EventRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.ports.TransferRecordRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork
import com.loosecannon.servicetag.core.season.SeasonReadinessReader
import com.loosecannon.servicetag.core.seasonsync.HaConnectionRepository
import com.loosecannon.servicetag.core.seasonsync.SecretStore
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.hapublish.PublishControls
import com.loosecannon.servicetag.hapublish.Validation
import com.loosecannon.servicetag.l10n.localizedDateTime
import com.loosecannon.servicetag.ui.condition.conditionOption
import com.loosecannon.servicetag.ui.homeassistant.HA_ENTER_TOKEN_AGAIN
import com.loosecannon.servicetag.ui.homeassistant.HA_NOT_CONNECTED
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** #113 (C36) — one row of the status block's mapping: the app's condition word and the option Home Assistant receives. */
internal data class PublishMappingRow(val condition: String, val option: String)

/**
 * #113/#114 (C36) — one publishing block on asset detail as drawn, one immutable value. Each line is its own fact: the
 * derived [PublishState]'s sentence ([stateLine]), the last published line ([publishedLine]: our POST, read back) and
 * the last verified line ([verifiedLine]: Home Assistant's value read equal to ServiceTag's now), never merged; the
 * current error's sentences, a fault ([faultLines]) or, for an error of the `WAIT` class, a fact ([factLines]: the
 * phone was not where it may send, which the next start, resume or Publish now retries); the delivery limit line.
 * [stopAsk] is the stop confirm while open; [notices] the last control's refusal.
 */
internal data class PublishBlockState(
    val drawn: Boolean = false,
    val title: String = "",
    /** The switch: an enabled binding. */
    val enabled: Boolean = false,
    val destination: String? = null,
    val mapping: List<PublishMappingRow> = emptyList(),
    val stateLine: String? = null,
    val publishedLine: String? = null,
    val verifiedLine: String? = null,
    val faultLines: List<String> = emptyList(),
    val factLines: List<String> = emptyList(),
    val limitLine: String? = null,
    /** The switch turned on, or Check helper on a copied setup: the setup sheet (P113-21's "turn publishing on again"). */
    val offersSetUp: Boolean = false,
    val offersPublishNow: Boolean = false,
    val offersCheck: Boolean = false,
    val offersStop: Boolean = false,
    val stopAsk: String? = null,
    val notices: List<String> = emptyList(),
    val busy: Boolean = false,
    /**
     * #114 (F-16) — beside the date binding's own lines while its `1970-01-01` is not read back: P114-36, or P114-37
     * when its current error needs the owner.
     */
    val invalidationLine: String? = null,
    /** #114 (C34) — this asset's obligations to helpers it let go of (P114-36, P114-37 with P114-38). */
    val tombstones: List<PublishTombstoneLine> = emptyList(),
)

/**
 * What one read of the stores saw for one kind: the binding and its delivery (null when the asset has none), the
 * derived state (C17's own [DeliverySteps.stateOf]), whether a connection is stored, the asset's name, whether it is
 * maintained here, and whether it is writable here (not held by an open transfer).
 */
internal data class PublishBlockFacts(
    val binding: PublishBinding? = null,
    val delivery: PublishDelivery? = null,
    val state: PublishState? = null,
    val connected: Boolean = false,
    val assetName: String = "",
    val maintainedHere: Boolean = false,
    val writable: Boolean = false,
    /** #114 (C34) — the date kind's: this asset's decommission tombstones. */
    val tombstones: List<DecommissionTombstone> = emptyList(),
    /** #114 (C36) — the date kind's: C9's answer now and the value it maps to (the invalid date's meaning). */
    val meaning: PublishMappingRow? = null,
)

/**
 * C36 — the block for [kind], pure. Not drawn while the kind has no words ([publishWordsOf]), or with no binding unless
 * the owner could set one up (a connection, the asset maintained here and not held) or the asset still owes a helper
 * `1970-01-01` (C34: an obligation is never silent, not even after Forget). [time] draws a stored instant in the
 * phone's date and time.
 */
internal fun publishBlockOf(
    kind: PublishKind,
    words: PublishKindWords?,
    facts: PublishBlockFacts,
    time: (Long) -> String,
): PublishBlockState {
    if (words == null) return PublishBlockState()
    val canSetUp = facts.connected && facts.maintainedHere && facts.writable
    val binding = facts.binding
    val delivery = facts.delivery
    val state = facts.state
    val tombstones = when (kind) {
        PublishKind.EQUIPMENT_STATUS -> emptyList()
        PublishKind.SEASON_SERVICE_DATE -> tombstoneLinesOf(facts.tombstones)
    }
    if (binding == null || delivery == null || state == null || !binding.enabled) {
        return PublishBlockState(
            drawn = binding != null || canSetUp || tombstones.isNotEmpty(),
            title = words.title,
            offersSetUp = canSetUp,
            tombstones = tombstones,
        )
    }
    val entityId = binding.entityId
    val stateLine = when (state) {
        PublishState.COPIED_INSTALLATION -> HA_PUBLISH_COPIED_INSTALLATION
        PublishState.NEEDS_VALIDATION -> HA_PUBLISH_NEEDS_VALIDATION
        PublishState.NEEDS_TOKEN -> if (facts.connected) HA_ENTER_TOKEN_AGAIN else HA_NOT_CONNECTED
        PublishState.WAITING_FOR_SOURCE,
        PublishState.NOT_MAINTAINED_HERE,
        PublishState.INVALIDATED_INELIGIBLE,
        -> words.ownStateLine(state, entityId)
        PublishState.PENDING -> {
            val succeeded = delivery.lastPublishedAt != null || delivery.lastVerifiedAt != null
            delivery.desiredValue?.takeIf { succeeded }?.let(::haPublishSending) ?: HA_PUBLISH_NOT_PUBLISHED_YET
        }
        // P113-19 once: the current error's own line says it when there is one.
        PublishState.OUTCOME_UNKNOWN -> HA_PUBLISH_OUTCOME_UNKNOWN.takeUnless { delivery.errorIsCurrent(binding) }
        // The error's lines, and the published and verified lines below, carry these three.
        PublishState.ERROR, PublishState.PUBLISHED, PublishState.MATCHES -> null
        // Not reached: the binding is enabled.
        PublishState.NOT_ENABLED -> null
    }
    val errorLines = delivery.errorKind?.takeIf { delivery.errorIsCurrent(binding) }?.let { kindOf ->
        val lines = publishErrorLines(kindOf, delivery.errorDetail, entityId, words, delivery.remoteValue)
            .filterNot { it == stateLine }
        // B4b2 review N4: work handed off away from home records a WAIT error. That is where the phone was, not a fault.
        when (retryClassOf(kindOf, delivery.errorDetail)) {
            RetryClass.WAIT -> emptyList<String>() to lines
            RetryClass.TRANSIENT, RetryClass.NEEDS_OWNER -> lines to emptyList()
        }
    } ?: (emptyList<String>() to emptyList())
    val publishedLine = delivery.lastPublishedValue?.let { value ->
        delivery.lastPublishedAt?.let { haPublishPublished(value, time(it)) }
    }
    // "Matches ServiceTag" is said only of the value ServiceTag wants now, verified at the binding's current pair.
    val verifiedNow = delivery.lastVerifiedValue != null && delivery.lastVerifiedValue == delivery.desiredValue &&
        delivery.verifiedBindingRevision == binding.revision && delivery.verifiedDesiredRevision == delivery.desiredRevision
    val verifiedLine = delivery.lastVerifiedAt?.takeIf { verifiedNow }?.let {
        haPublishMatches(delivery.lastVerifiedValue.orEmpty(), time(it))
    }
    val sends = facts.writable && facts.maintainedHere
    return PublishBlockState(
        drawn = true,
        title = words.title,
        enabled = true,
        destination = entityId,
        mapping = when (kind) {
            PublishKind.EQUIPMENT_STATUS -> mappingOf(kind)
            PublishKind.SEASON_SERVICE_DATE -> listOfNotNull(facts.meaning)
        },
        stateLine = stateLine,
        publishedLine = publishedLine,
        verifiedLine = verifiedLine,
        faultLines = errorLines.first,
        factLines = errorLines.second,
        limitLine = HA_PUBLISH_DELIVERY_LIMIT,
        offersSetUp = state == PublishState.COPIED_INSTALLATION && canSetUp,
        offersPublishNow = sends && when (state) {
            PublishState.PENDING,
            PublishState.OUTCOME_UNKNOWN,
            PublishState.ERROR,
            PublishState.PUBLISHED,
            PublishState.MATCHES,
            -> true
            PublishState.NOT_ENABLED,
            PublishState.COPIED_INSTALLATION,
            PublishState.NEEDS_VALIDATION,
            PublishState.NEEDS_TOKEN,
            PublishState.WAITING_FOR_SOURCE,
            PublishState.NOT_MAINTAINED_HERE,
            PublishState.INVALIDATED_INELIGIBLE,
            -> false
        },
        offersCheck = facts.writable && facts.connected && when (state) {
            PublishState.NEEDS_VALIDATION,
            PublishState.WAITING_FOR_SOURCE,
            PublishState.PENDING,
            PublishState.OUTCOME_UNKNOWN,
            PublishState.ERROR,
            PublishState.PUBLISHED,
            PublishState.MATCHES,
            -> true
            // A copied setup is turned on again through the sheet (A08: Check helper never activates); no token, no
            // read; an asset that left sends nothing.
            PublishState.NOT_ENABLED,
            PublishState.COPIED_INSTALLATION,
            PublishState.NEEDS_TOKEN,
            PublishState.NOT_MAINTAINED_HERE,
            PublishState.INVALIDATED_INELIGIBLE,
            -> false
        },
        offersStop = facts.writable,
        invalidationLine = invalidationLineOf(kind, state, binding, delivery),
        tombstones = tombstones,
    )
}

/**
 * F-16, C34 — an enabled date binding owing its helper `1970-01-01` that is not yet read back at the current pair with
 * nothing outstanding ([DeliverySteps.isCurrent]) may still let Home Assistant start the appliance: P114-36 beside its
 * own lines, or P114-37 when its current error needs the owner. Never for the status kind, a service date being sent,
 * or a copied setup, whose own line says this phone does not publish (P113-21).
 */
private fun invalidationLineOf(
    kind: PublishKind,
    state: PublishState,
    binding: PublishBinding,
    delivery: PublishDelivery,
): String? {
    when (kind) {
        PublishKind.EQUIPMENT_STATUS -> return null
        PublishKind.SEASON_SERVICE_DATE -> Unit
    }
    if (delivery.desiredValue != INVALID_SERVICE_DATE || DeliverySteps.isCurrent(binding, delivery)) return null
    if (state == PublishState.COPIED_INSTALLATION) return null
    val error = delivery.errorKind?.takeIf { delivery.errorIsCurrent(binding) }
    val ownerMustAct = error != null && when (retryClassOf(error, delivery.errorDetail)) {
        RetryClass.NEEDS_OWNER -> true
        RetryClass.TRANSIENT, RetryClass.WAIT -> false
    }
    return if (ownerMustAct) haPublishCannotReach(binding.entityId) else haPublishNotYetCleared(binding.entityId)
}

/** C36 — the status kind's mapping, in the condition's own order; the date kind draws its meaning row ([DateMeaning]). */
private fun mappingOf(kind: PublishKind): List<PublishMappingRow> = when (kind) {
    PublishKind.EQUIPMENT_STATUS -> OperationalCondition.entries.map { PublishMappingRow(conditionOption(it), optionOf(it)) }
    PublishKind.SEASON_SERVICE_DATE -> emptyList()
}

/** C15's mapping, the helper's option for [condition]. */
private fun optionOf(condition: OperationalCondition): String = when (condition) {
    OperationalCondition.OPERATIONAL -> STATUS_UP
    OperationalCondition.DEGRADED -> STATUS_DEGRADED
    OperationalCondition.DOWN -> STATUS_DOWN
}

/**
 * #113/#114 (C36, C32) — one publishing block's model. It reads the binding, its delivery, the stored connection,
 * whether the token is on this phone, the asset and the open transfers in one snapshot and derives the state with
 * C17's own rules; it is read again on every change to the bindings, the connection, the assets and the transfers, on
 * resume, after each control, and every two seconds while drawn ([ticks]), because a delivery record has no flow by
 * design (I28) and a pass runs after the control returns. Every control goes through [PublishControls], which asks for
 * delivery after its commit (A03): Publish now, Check helper (a fresh read of the stored helper, then Revalidate, which
 * never activates), Stop publishing after its confirm and, for the date kind, the owner's P114-38 on an obligation. A
 * refusal comes back as a code and is drawn as its sentence. The date kind also reads its asset's obligations (C34) and
 * its meaning row through the one readiness reader ([DateMeaning]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PublishBlockViewModel(
    private val assetId: AssetId,
    private val kind: PublishKind,
    private val bindings: PublishBindingRepository,
    private val deliveries: PublishDeliveryRepository,
    private val decommissions: DecommissionRepository,
    private val connections: HaConnectionRepository,
    private val secrets: SecretStore,
    private val assets: AssetRepository,
    private val transfers: TransferRecordRepository,
    private val installationId: () -> String,
    private val uow: UnitOfWork,
    private val controls: PublishControls,
    /** The runner's validation (C30): one snapshot, then the helper's fresh read. */
    private val validate: suspend (AssetId, PublishKind, String, String?) -> Validation,
    /** The date kind's meaning row, read through the one readiness reader (C36; B5's single source). */
    private val meaning: DateMeaning,
    private val ticks: Flow<Unit>,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : ViewModel() {

    constructor(graph: AppGraph, assetId: AssetId, kind: PublishKind) : this(
        assetId, kind, graph.publishBindings, graph.publishDeliveries, graph.publishDecommissions, graph.haConnections,
        graph.secretStore, graph.assets, graph.transferRecords, graph.installationIdentity::id, graph.uow,
        graph.publishControls, graph.publishRunner::validate,
        DateMeaning.of(graph.openingServiceReader, graph.events, graph.today) { ZoneId.systemDefault() },
        everyTwoSeconds(),
    )

    private data class Pending(val busy: Boolean = false, val notices: List<String> = emptyList(), val stopAsk: String? = null)

    private val pending = MutableStateFlow(Pending())
    private val refreshes = MutableStateFlow(0)
    private val words: PublishKindWords? = publishWordsOf(kind)

    private val reads: Flow<PublishBlockFacts> = combine(
        listOf<Flow<Any?>>(
            bindings.observeAll(), decommissions.observeAll(), connections.observe(), assets.observeAll(),
            transfers.observeHeldIds(), ticks.onStart { emit(Unit) }, refreshes,
        ),
    ) { }.mapLatest { read() }

    val state: StateFlow<PublishBlockState> = combine(reads, pending) { facts, pending ->
        publishBlockOf(kind, words, facts, ::dateTime).copy(
            busy = pending.busy,
            notices = pending.notices,
            stopAsk = pending.stopAsk,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), PublishBlockState())

    /** Reads the stores again: the screen calls it on every resume. */
    fun onResumed() = refreshes.update { it + 1 }

    /** P113-12: the controls' Publish now, which projects and reads the helper back; never awaited here. */
    fun publishNow() = command {
        controls.requestPublishNow(assetId)
        emptyList()
    }

    /** P113-25: a fresh read of the stored helper, then Revalidate; a read that answered nothing is its sentence. */
    fun checkHelper() = command {
        val stored = uow.read { bindings.forAsset(assetId).firstOrNull { it.kind == kind } } ?: return@command emptyList()
        when (val read = validate(assetId, kind, stored.entityId, stored.boundaryEntityId)) {
            Validation.NoConnection -> listOf(HA_NOT_CONNECTED)
            is Validation.ReadFailed -> {
                val named = if (read.field == FIELD_BOUNDARY_ENTITY_ID) stored.boundaryEntityId else stored.entityId
                words?.let { publishErrorLines(read.kind, read.detail, named.orEmpty(), it) }.orEmpty()
            }
            is Validation.Validated -> {
                controls.revalidate(assetId, kind, read.target, read.boundary)
                emptyList()
            }
        }
    }

    /** P113-13: the stop confirm, naming the helper (P113-14). */
    fun askStop() {
        val shown = state.value
        val entityId = shown.destination ?: return
        pending.update { it.copy(stopAsk = words?.stopConfirm(entityId)) }
    }

    fun dismissStop() = pending.update { it.copy(stopAsk = null) }

    /**
     * P114-38: the owner's own word that the helper is cleared in Home Assistant, for one UNDELIVERABLE obligation — the
     * only way one is let go (O-21(b)), never inferred. A row that raced back to PENDING or is already gone is refused
     * (C25a) and the block simply re-reads it: no sentence.
     */
    fun dismissTombstone(id: String) = command {
        try {
            controls.dismissDecommission(id)
        } catch (_: NoSuchDecommission) {
            // Already gone: the re-read shows it.
        } catch (_: DecommissionStillPending) {
            // ServiceTag is still trying: the re-read draws P114-36 again.
        }
        emptyList()
    }

    fun confirmStop() {
        pending.update { it.copy(stopAsk = null) }
        command {
            controls.disable(assetId, kind)
            emptyList()
        }
    }

    private fun command(block: suspend () -> List<String>) {
        if (pending.value.busy) return
        pending.update { it.copy(busy = true, notices = emptyList()) }
        viewModelScope.launch {
            val notices = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: PublishRefused) {
                val facts = read()
                words?.let {
                    listOfNotNull(publishRefusalLine(e.reason, facts.binding?.entityId.orEmpty(), facts.assetName, it))
                }.orEmpty()
            } catch (e: PublishNotSetUp) {
                // The binding went between the read and the tap: the block redraws without it.
                emptyList()
            } catch (e: Exception) {
                // A store failed: nothing was written and no sentence is ratified for it; the next read tells the truth.
                emptyList()
            }
            pending.update { it.copy(busy = false, notices = notices) }
            refreshes.update { it + 1 }
        }
    }

    private suspend fun read(): PublishBlockFacts {
        val here = installationId()
        val seen = uow.read {
            val asset = assets.get(assetId)
            val connection = connections.get()
            val binding = bindings.forAsset(assetId).firstOrNull { it.kind == kind }
            // #114 (C34, C36): the date kind's obligations, and its meaning row while it publishes.
            val owed = when (kind) {
                PublishKind.EQUIPMENT_STATUS -> emptyList()
                PublishKind.SEASON_SERVICE_DATE -> decommissions.all().filter { it.assetId == assetId }
            }
            val row = when (kind) {
                PublishKind.EQUIPMENT_STATUS -> null
                PublishKind.SEASON_SERVICE_DATE -> if (binding?.enabled == true) meaning.rowOf(assetId) else null
            }
            Seen(
                asset?.name.orEmpty(), asset, connection?.id, binding, binding?.let { deliveries.get(it.id) },
                transfers.heldIds(), owed, row,
            )
        }
        val hasToken = seen.connectionId != null && tokenHeld(seen.connectionId)
        val derived = seen.binding?.let { binding ->
            seen.delivery?.let { delivery ->
                DeliverySteps.stateOf(
                    Snap(binding, delivery, seen.connectionId == binding.connectionId, hasToken, here, seen.asset, seen.held),
                )
            }
        }
        return PublishBlockFacts(
            binding = seen.binding,
            delivery = seen.delivery,
            state = derived,
            connected = seen.connectionId != null,
            assetName = seen.name,
            maintainedHere = seen.asset?.maintainedHere(seen.held) == true,
            writable = assetId !in seen.held,
            tombstones = seen.tombstones,
            meaning = seen.meaning,
        )
    }

    private data class Seen(
        val name: String,
        val asset: Asset?,
        val connectionId: String?,
        val binding: PublishBinding?,
        val delivery: PublishDelivery?,
        val held: Set<AssetId>,
        val tombstones: List<DecommissionTombstone>,
        val meaning: PublishMappingRow?,
    )

    /** A key store that cannot answer reads as no token (B4's rule), never a crash. */
    private suspend fun tokenHeld(key: String): Boolean = try {
        secrets.has(key)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /** The language's date-and-time display, in this phone's zone. */
    private fun dateTime(at: Long): String = localizedDateTime(Instant.ofEpochMilli(at).atZone(zone()).toLocalDateTime())

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L
        private const val TICK_MILLIS = 2_000L

        /** The production re-read while the block is drawn. */
        fun everyTwoSeconds(): Flow<Unit> = flow {
            while (true) {
                delay(TICK_MILLIS)
                emit(Unit)
            }
        }
    }
}

/**
 * #114 (C36) — the date block's meaning row: C9's answer for the asset now, as the season card says it (A5a's
 * sentences), beside the value it maps to (C15: the service date, or `1970-01-01` for every not-ready answer). Read
 * inside the caller's transaction through the one [SeasonReadinessReader] (B5's single source: the readiness route,
 * the projector's feed and this row assemble the Home Assistant boundary in one place). Null when the asset is gone.
 */
internal fun interface DateMeaning {
    suspend fun rowOf(assetId: AssetId): PublishMappingRow?

    companion object {
        fun of(reader: SeasonReadinessReader, events: EventRepository, today: Today, zone: () -> ZoneId) =
            DateMeaning { assetId ->
                reader.readiness(assetId)?.let { readiness ->
                    val day = today.localDate()
                    readinessLinesOf(readiness, hasDateBinding = true, events.forAsset(assetId), day, zone())
                        .line?.let { PublishMappingRow(it, seasonDateOf(readiness)) }
                }
            }
    }
}
