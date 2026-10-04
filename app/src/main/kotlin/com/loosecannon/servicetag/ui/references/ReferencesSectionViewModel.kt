package com.loosecannon.servicetag.ui.references

import com.loosecannon.servicetag.ui.transfer.transferredOutOr
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.servicetag.R
import com.loosecannon.servicetag.core.fetch.HopPolicy
import com.loosecannon.servicetag.core.model.AssetReference
import com.loosecannon.servicetag.core.model.DocumentRole
import com.loosecannon.servicetag.core.model.ReferenceId
import com.loosecannon.servicetag.core.model.ReferenceKind
import com.loosecannon.servicetag.core.model.ReferenceOwner
import com.loosecannon.servicetag.core.model.asAttachmentOwner
import com.loosecannon.servicetag.core.ports.AttachmentRepository
import com.loosecannon.servicetag.core.ports.ReferenceRepository
import com.loosecannon.servicetag.core.references.LinkDecision
import com.loosecannon.servicetag.core.references.LinkLaunchPolicy
import com.loosecannon.servicetag.core.references.ReferenceKinds
import com.loosecannon.servicetag.core.references.takesRole
import com.loosecannon.servicetag.core.usecase.AddReference
import com.loosecannon.servicetag.core.usecase.AddReferenceCommand
import com.loosecannon.servicetag.core.usecase.ReferenceProblem
import com.loosecannon.servicetag.core.usecase.ReferenceResult
import com.loosecannon.servicetag.core.usecase.RemoveReference
import com.loosecannon.servicetag.core.usecase.UpdateReference
import com.loosecannon.servicetag.core.usecase.UpdateReferenceCommand
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.l10n.localized
import kotlin.coroutines.CoroutineContext
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
    /** #85 (C20, R85-4, R85-15): an https web link the hop rule accepts, so Save as document is offered. */
    val materializable: Boolean = false,
    /**
     * #85 (C20, R85-1, R85-3): derived, never stored — a file of this row's own owner has this URI as its
     * source (#69, H4).
     */
    val savedAsDocument: Boolean = false,
    /** #91 (C15, C22, C24): the stored role — the edit sheet's chips start from it, and the row draws its label. */
    val role: DocumentRole? = null,
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
    /** #69 (C25): whose references these are — an asset, a SupplyItem or an installed component. */
    private val owner: ReferenceOwner,
    references: ReferenceRepository,
    private val addReference: AddReference,
    private val updateReference: UpdateReference,
    private val removeReference: RemoveReference,
    private val policy: LinkLaunchPolicy,
    attachments: AttachmentRepository,
    private val hops: HopPolicy,
    /**
     * Where the three writes run: `Dispatchers.IO` in the app, and the test's own scheduler in a
     * JVM test, so none of that work outlives the test that started it.
     */
    private val io: CoroutineContext = Dispatchers.IO,
) : ViewModel() {

    constructor(graph: AppGraph, owner: ReferenceOwner) : this(
        owner,
        graph.references,
        graph.addReference,
        graph.updateReference,
        graph.removeReference,
        graph.linkLaunchPolicy,
        graph.attachments,
        graph.hops,
    )

    /** The command the person has been asked about but has not answered for yet. */
    private data class PendingLink(val command: AddReferenceCommand, val scheme: String)

    private val pending = MutableStateFlow<PendingLink?>(null)

    /** Already ordered by display name, then id, by the query itself. */
    val state: StateFlow<ReferencesSectionState> =
        combine(
            references.observeForOwner(owner),
            attachments.observeForOwner(owner.asAttachmentOwner()),
            pending,
        ) { rows, files, awaiting ->
            // The (owner, uri) second identity (R85-3, #69 H4): this owner's own files, by their source's URI
            // alone — another owner's file with the same source never marks this owner's link.
            val sourced = files.mapNotNullTo(HashSet()) { it.source?.uri }
            ReferencesSectionState(
                rows = rows.map { row(it, sourced) },
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
     *
     * #91 (C23): [role] is the person's pick, or null; the sheet sends one only while [roleOffered]
     * says the link takes it, so an unknown scheme's confirmed re-submit carries none by construction.
     */
    fun addLink(uri: String, displayName: String, description: String, role: DocumentRole?) {
        submit(AddReferenceCommand(uri = uri, displayName = displayName, description = description, role = role))
    }

    /**
     * #91 (C23, R91-5, C-4): whether Add link draws the Role chips for [link] as typed — the kind
     * `AddReference` will derive from the same text, asked whether it takes a role. The one
     * classifier and the one rule; never a prefix test, and never a role guessed from the text.
     */
    fun roleOffered(link: String): Boolean = ReferenceKinds.inferFrom(policy.schemeOf(link.trim())).takesRole

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
        viewModelScope.launch(io) {
            val outcome = try {
                updateReference.run(ReferenceId(id), cmd)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // A database that would not take the write. §10 ratifies no sentence for it, and
                // this brief may not invent one, so the sheet stays open holding what was typed
                // and the list keeps saying what the store says (controller ruling, spec silence).
                // #77: P77-35, a ratified sentence, when the asset was transferred out meanwhile.
                t.transferredOutOr("").takeIf { it.isNotEmpty() }?.let { _messages.tryEmit(it) }
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
        viewModelScope.launch(io) {
            val outcome = try {
                removeReference.run(ReferenceId(id))
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // The same ruling as the other two write paths: §10 ratifies no sentence for a
                // delete the database refused, so nothing is drawn and the row stays listed
                // because the store still holds it (controller ruling, spec silence).
                // #77: P77-35, a ratified sentence, when the asset was transferred out meanwhile.
                t.transferredOutOr("").takeIf { it.isNotEmpty() }?.let { _messages.tryEmit(it) }
                return@launch
            }
            if (outcome is ReferenceResult.Refused) say(outcome.problem)
        }
    }

    private fun submit(cmd: AddReferenceCommand) {
        viewModelScope.launch(io) {
            val outcome = try {
                addReference.run(owner, cmd)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // A database that would not take the write. §10 ratifies no sentence for it, and
                // this brief may not invent one, so the sheet stays open holding what was typed.
                // #77: P77-35, a ratified sentence, when the asset was transferred out meanwhile.
                t.transferredOutOr("").takeIf { it.isNotEmpty() }?.let { _messages.tryEmit(it) }
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

    private fun row(reference: AssetReference, sourced: Set<String>) = ReferenceRowState(
        id = reference.id.value,
        displayName = reference.displayName,
        description = reference.description,
        uri = reference.uri,
        kind = reference.kind,
        // The policy, asked again at read time rather than trusted from the write: a URI that was
        // legal when it was saved and is not now — a restored archive, a block list that grew —
        // is shown and refused, never launched (spec §4.2).
        launchable = policy.classify(reference.uri) != LinkDecision.Blocked,
        materializable = reference.kind == ReferenceKind.WEB_URL &&
            policy.classify(reference.uri) != LinkDecision.Blocked &&
            hops.staticProblem(reference.uri) == null,
        savedAsDocument = reference.uri in sourced,
        role = reference.role,
    )

    /**
     * One ratified line per refusal (§10). Five of the ten say nothing on this surface:
     * `UnknownSchemeNeedsConfirmation` is a question and is asked as one; `Unchanged` simply
     * closes the sheet; `OwnerMissing` and `NoSuchReference` mean the screen is looking at
     * something that has gone, for which §10 ratifies no sentence and this brief may invent none;
     * and `RoleNotAllowed` (#91, R91-14) is unreachable here, because the sheets never send a role
     * a link cannot take.
     */
    private fun say(problem: ReferenceProblem) {
        val line = when (problem) {
            ReferenceProblem.BlankName -> localized(R.string.references_blank_name)
            ReferenceProblem.NotALink -> localized(R.string.references_not_a_link)
            ReferenceProblem.UriTooLong -> localized(R.string.references_uri_too_long)
            ReferenceProblem.SchemeBlocked -> localized(R.string.references_scheme_blocked)
            ReferenceProblem.DuplicateUri -> duplicateUriOn(owner)
            is ReferenceProblem.UnknownSchemeNeedsConfirmation -> return
            ReferenceProblem.Unchanged -> return
            ReferenceProblem.OwnerMissing -> return
            ReferenceProblem.NoSuchReference -> return
            ReferenceProblem.RoleNotAllowed -> return
        }
        _messages.tryEmit(line)
    }
}

/** #69 P69-11 (C28): the in-app and the Share duplicate, for a SupplyItem's link. */
internal val DUPLICATE_URI_ON_SUPPLY: String get() = localized(R.string.references_duplicate_on_supply)

/** #69 P69-12 (C28): the in-app and the Share duplicate, for an installed component's link. */
internal val DUPLICATE_URI_ON_INSTALLED_COMPONENT: String
    get() = localized(R.string.references_duplicate_on_installed_component)

/** #69 (C28, R69-13): the duplicate sentence by the owner's kind; an asset keeps its shipped wording. */
internal fun duplicateUriOn(owner: ReferenceOwner): String = when (owner) {
    is ReferenceOwner.OfAsset -> localized(R.string.references_duplicate_on_asset)
    is ReferenceOwner.OfSupplyItem -> DUPLICATE_URI_ON_SUPPLY
    is ReferenceOwner.OfInstalledComponent -> DUPLICATE_URI_ON_INSTALLED_COMPONENT
}
