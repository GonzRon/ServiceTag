package com.loosecannon.servicetag.ui.references

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetReference
import com.loosecannon.servicetag.core.model.ReferenceId
import com.loosecannon.servicetag.core.model.ReferenceKind
import com.loosecannon.servicetag.core.ports.ReferenceRepository
import com.loosecannon.servicetag.core.references.LinkDecision
import com.loosecannon.servicetag.core.references.LinkLaunchPolicy
import com.loosecannon.servicetag.core.usecase.AddReference
import com.loosecannon.servicetag.core.usecase.AddReferenceCommand
import com.loosecannon.servicetag.core.usecase.ReferenceProblem
import com.loosecannon.servicetag.core.usecase.ReferenceResult
import com.loosecannon.servicetag.core.usecase.RemoveReference
import com.loosecannon.servicetag.core.usecase.UpdateReference
import com.loosecannon.servicetag.core.usecase.UpdateReferenceCommand
import com.loosecannon.servicetag.di.AppGraph
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** How long the repository flow stays hot after the last collector leaves (a rotation, typically). */
private const val SUBSCRIPTION_GRACE_MS = 5_000L

/**
 * One REFERENCES row. There is no locator, no size, no sha256 and no thumbnail, and that is the
 * point: a reference has no bytes (I-3), so copying the DOCUMENTS row's shape wholesale would drag
 * the byte machinery onto a pointer.
 */
data class ReferenceRowState(
    val id: String,
    val displayName: String,
    val description: String,
    val uri: String,
    val kind: ReferenceKind,
    /** False when [LinkLaunchPolicy] now refuses this stored URI: shown, never launched. */
    val launchable: Boolean,
)

data class ReferencesSectionState(
    val rows: List<ReferenceRowState> = emptyList(),
    /** The unknown scheme awaiting "Save this link?"; null when nothing is being asked. */
    val pendingConfirmation: String? = null,
)

/**
 * The one ViewModel behind REFERENCES, in the shape `AttachmentsSectionViewModel` already uses: a
 * flow off the repository, a message channel, and one `when` over the problem type.
 *
 * Every refusal it can draw comes back from the use case (I-2), so this class never re-implements
 * one; what it owns is which ratified sentence answers each, and the one thing the use cases
 * cannot answer — whether a **stored** URI may still be launched, which is the same policy asked
 * again at read time (spec §4.2).
 */
class ReferencesSectionViewModel(
    private val assetId: AssetId,
    references: ReferenceRepository,
    private val addReference: AddReference,
    private val updateReference: UpdateReference,
    private val removeReference: RemoveReference,
    private val policy: LinkLaunchPolicy,
) : ViewModel() {

    constructor(graph: AppGraph, assetId: AssetId) : this(
        assetId,
        graph.references,
        graph.addReference,
        graph.updateReference,
        graph.removeReference,
        graph.linkLaunchPolicy,
    )

    /** The command the person has been asked about but has not answered for yet. */
    private data class PendingLink(val command: AddReferenceCommand, val scheme: String)

    private val pending = MutableStateFlow<PendingLink?>(null)

    /** Already ordered by display name, then id, by the query itself. */
    val state: StateFlow<ReferencesSectionState> =
        combine(references.observeForAsset(assetId), pending) { rows, awaiting ->
            ReferencesSectionState(
                rows = rows.map(::row),
                pendingConfirmation = awaiting?.scheme,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS),
            ReferencesSectionState(),
        )

    /** One line per refusal, shown once (the 1C snackbar pattern). No replay, by design. */
    private val _messages = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /**
     * The id of a reference whose edit sheet may close: the save landed, or it changed nothing.
     * A refusal is deliberately absent — the sheet stays open holding what was typed.
     */
    private val _saved = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val saved: SharedFlow<String> = _saved.asSharedFlow()

    /** Emitted when the add sheet's link is in, which is what closes that sheet. */
    private val _added = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    val added: SharedFlow<Unit> = _added.asSharedFlow()

    /**
     * "Add link", which calls **the same use case a share does** and writes an identical row: no
     * `provenance`, nothing set differently, the two indistinguishable afterwards (D-21 C).
     */
    fun addLink(uri: String, displayName: String, description: String) {
        submit(AddReferenceCommand(uri = uri, displayName = displayName, description = description))
    }

    /** "Save this link?" answered with Save: the same command again, confirmed exactly once. */
    fun confirmUnknownScheme() {
        val awaiting = pending.value ?: return
        pending.value = null
        submit(awaiting.command.copy(confirmedUnknownScheme = true))
    }

    fun dismissUnknownScheme() {
        pending.value = null
    }

    fun save(id: String, cmd: UpdateReferenceCommand) {
        viewModelScope.launch(Dispatchers.IO) {
            val outcome = try {
                updateReference.run(ReferenceId(id), cmd)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // A database that would not take the write. §10 ratifies no sentence for it, and
                // this brief may not invent one, so the sheet stays open holding what was typed
                // and the list keeps saying what the store says (controller ruling, spec silence).
                return@launch
            }
            when (outcome) {
                is ReferenceResult.Ok -> _saved.tryEmit(id)
                // Nothing to write is not a failure: the sheet closes without claiming a save.
                is ReferenceResult.Refused -> {
                    if (outcome.problem == ReferenceProblem.Unchanged) _saved.tryEmit(id)
                    say(outcome.problem)
                }
            }
        }
    }

    /**
     * A hard delete: one metadata row, no bytes, nothing to orphan (D-9). Merge inserts and never
     * deletes, so re-importing an archive taken before the removal puts the row back — exactly as
     * it does for a journal event, and deliberately.
     */
    fun remove(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val outcome = try {
                removeReference.run(ReferenceId(id))
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // The same ruling as the other two write paths: §10 ratifies no sentence for a
                // delete the database refused, so nothing is drawn and the row stays listed
                // because the store still holds it (controller ruling, spec silence).
                return@launch
            }
            if (outcome is ReferenceResult.Refused) say(outcome.problem)
        }
    }

    private fun submit(cmd: AddReferenceCommand) {
        viewModelScope.launch(Dispatchers.IO) {
            val outcome = try {
                addReference.run(assetId, cmd)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // A database that would not take the write. §10 ratifies no sentence for it, and
                // this brief may not invent one, so the sheet stays open holding what was typed.
                return@launch
            }
            when (outcome) {
                is ReferenceResult.Ok -> {
                    pending.value = null
                    _added.tryEmit(Unit)
                }
                is ReferenceResult.Refused -> {
                    val problem = outcome.problem
                    if (problem is ReferenceProblem.UnknownSchemeNeedsConfirmation) {
                        pending.value = PendingLink(cmd, problem.scheme)
                    } else {
                        say(problem)
                    }
                }
            }
        }
    }

    private fun row(reference: AssetReference) = ReferenceRowState(
        id = reference.id.value,
        displayName = reference.displayName,
        description = reference.description,
        uri = reference.uri,
        kind = reference.kind,
        // The policy, asked again at read time rather than trusted from the write: a URI that was
        // legal when it was saved and is not now — a restored archive, a block list that grew —
        // is shown and refused, never launched (spec §4.2).
        launchable = policy.classify(reference.uri) != LinkDecision.Blocked,
    )

    /**
     * One ratified line per refusal (§10). Four of the nine say nothing on this surface:
     * `UnknownSchemeNeedsConfirmation` is a question and is asked as one; `Unchanged` simply
     * closes the sheet; and `OwnerMissing` and `NoSuchReference` mean the screen is looking at
     * something that has gone, for which §10 ratifies no sentence and this brief may invent none.
     */
    private fun say(problem: ReferenceProblem) {
        val line = when (problem) {
            ReferenceProblem.BlankName -> "Give the reference a name"
            ReferenceProblem.NotALink -> "That is not a link."
            ReferenceProblem.UriTooLong -> "That link is too long to save."
            ReferenceProblem.SchemeBlocked -> "ServiceTag will not save that kind of link."
            ReferenceProblem.DuplicateUri -> "That link is already on this asset"
            is ReferenceProblem.UnknownSchemeNeedsConfirmation -> return
            ReferenceProblem.Unchanged -> return
            ReferenceProblem.OwnerMissing -> return
            ReferenceProblem.NoSuchReference -> return
        }
        _messages.tryEmit(line)
    }
}
