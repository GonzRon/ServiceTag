package com.loosecannon.servicetag.ui.references

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.servicetag.core.fetch.FetchProblem
import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.model.AttachmentKind
import com.loosecannon.servicetag.core.model.AttachmentKinds
import com.loosecannon.servicetag.core.model.AttachmentProblem
import com.loosecannon.servicetag.core.model.DocumentRole
import com.loosecannon.servicetag.core.model.MAX_ATTACHMENT_BYTES
import com.loosecannon.servicetag.core.model.ReferenceId
import com.loosecannon.servicetag.core.model.ReferenceOwner
import com.loosecannon.servicetag.core.references.ReferenceUris
import com.loosecannon.servicetag.core.usecase.AttachmentResult
import com.loosecannon.servicetag.core.usecase.MaterializeReference
import com.loosecannon.servicetag.core.usecase.MaterializeRefusal
import com.loosecannon.servicetag.core.usecase.MaterializeReview
import com.loosecannon.servicetag.core.usecase.Prepared
import com.loosecannon.servicetag.core.usecase.SourceSnapshot
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.share.IntakeStrings
import com.loosecannon.servicetag.ui.attachments.AttachmentFailure
import com.loosecannon.servicetag.ui.attachments.couldNotSave
import com.loosecannon.servicetag.ui.attachments.sentence
import com.loosecannon.servicetag.ui.transfer.transferredOutOr
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** #85 C21: the Save-as-document sheet, one state at a time. Every line is a §6 id or a reused home's. */
sealed interface MaterializeState {
    /** P85-2 and P85-3; [total] is null when unknown or already outrun (never less than [done]). */
    data class Downloading(val host: String, val done: Long, val total: Long?) : MaterializeState

    /** P85-1, P85-4 ([host]), P85-5 ([typeLine]); the four fields are the owner's to edit; [nameError] is reused. */
    data class Review(
        val host: String,
        val typeLine: String,
        val name: String,
        val kind: AttachmentKind,
        val role: DocumentRole?,
        val notes: String,
        val nameError: String?,
    ) : MaterializeState

    /** The copy into the folder: no Cancel. */
    data object Saving : MaterializeState

    /** One line and Close; [offersAppSettings] only for P85-10. */
    data class Refused(val line: String, val offersAppSettings: Boolean) : MaterializeState

    /** Saved: the host takes it once through [MaterializeViewModel.handOffDone] and says P85-7 (#84). */
    data object Done : MaterializeState

    /** Nothing to show: cancelled, handed off, or a vanished or ineligible row (no ratified line for either). */
    data object Closed : MaterializeState
}

/**
 * #85 C21 (R85-8, R85-9): the sheet's view model owns the one job — download first, then the owner's review, then
 * Save. A [Prepared.Ready] is held only while [MaterializeState.Review] shows; every path that leaves Review without
 * Save (Cancel, leaving the screen) discards it, and Save hands it to `commit`, which spends it.
 *
 * The job's state changes happen on the main thread, as [cancel] and [save] do, so a Cancel can never slip between a
 * download landing and its review; only the progress callback updates from the fetch's thread.
 */
class MaterializeViewModel(
    /** #69 (C25): the reference's owner — `prepare` refuses a reference of any other owner. */
    private val owner: ReferenceOwner,
    private val referenceId: ReferenceId,
    uri: String,
    private val materialize: MaterializeReference,
    private val io: CoroutineContext = Dispatchers.IO,
) : ViewModel() {

    constructor(graph: AppGraph, owner: ReferenceOwner, referenceId: ReferenceId, uri: String) :
        this(owner, referenceId, uri, graph.materializeReference)

    /** The reference's own host, never a redirect's (§6). */
    private val host: String? = ReferenceUris.hostOf(uri)

    private val _state = MutableStateFlow<MaterializeState>(MaterializeState.Closed)
    val state: StateFlow<MaterializeState> = _state.asStateFlow()

    private var job: Job? = null
    private var ready: Prepared.Ready? = null

    init {
        start()
    }

    /** Opens the sheet: a fresh download from [MaterializeState.Closed], and nothing in any other state. */
    fun start() {
        val host = host ?: return
        if (!_state.compareAndSet(MaterializeState.Closed, MaterializeState.Downloading(host, 0L, null))) return
        job = viewModelScope.launch {
            var landed: Prepared.Ready? = null
            try {
                val prepared = withContext(io) {
                    materialize.prepare(owner, referenceId) { done, total ->
                        _state.update { if (it is MaterializeState.Downloading) downloading(host, done, total) else it }
                    }.also { landed = it as? Prepared.Ready }
                }
                _state.value = when (prepared) {
                    is Prepared.Ready -> review(prepared).also { ready = prepared }
                    is Prepared.Refused -> refusalState(prepared.why, host, owner)
                }
            } catch (e: CancellationException) {
                if (ready !== landed) landed?.let(materialize::discard)
                throw e
            } catch (e: Throwable) {
                // Anything else that went wrong (a read that failed): P85-18, reused within its meaning (ruling).
                if (ready !== landed) landed?.let(materialize::discard)
                _state.value = refused(MaterializeStrings.INTERRUPTED)
            }
        }
    }

    fun rename(name: String) = edit { it.copy(name = name, nameError = null) }
    fun chooseKind(kind: AttachmentKind) = edit { it.copy(kind = kind) }
    fun chooseRole(role: DocumentRole?) = edit { it.copy(role = role) }
    fun editNotes(notes: String) = edit { it.copy(notes = notes) }

    /** Saving is set before the first suspension, so a second tap finds it and does nothing. */
    fun save() {
        val review = _state.value as? MaterializeState.Review ?: return
        val prepared = ready ?: return
        _state.value = MaterializeState.Saving
        ready = null
        job = viewModelScope.launch {
            val next = try {
                // R87-4: the commit alone is uncancellable (the download and the review stay cancellable), so a
                // popped screen cannot interrupt the durable write or its failure cleanup.
                val outcome = withContext(io + NonCancellable) {
                    materialize.commit(prepared, MaterializeReview(review.name, review.kind, review.role, review.notes))
                }
                afterCommit(outcome, review, prepared)
            } catch (e: CancellationException) {
                materialize.discard(prepared)
                throw e
            } catch (e: Throwable) {
                MaterializeState.Refused(e.transferredOutOr(couldNotSave(review.name.trim())), false)
            }
            _state.value = next
        }
    }

    /** Cancel in Downloading or Review, and Close on a refusal: nothing written, the staging dropped. */
    fun cancel() {
        if (_state.value == MaterializeState.Saving || _state.value == MaterializeState.Done) return
        job?.cancel()
        ready?.let(materialize::discard)
        ready = null
        _state.value = MaterializeState.Closed
    }

    /** True exactly once, at Done, for the host's P85-7; the state then reads Closed (#84's hand-off rule). */
    fun handOffDone(): Boolean = _state.compareAndSet(MaterializeState.Done, MaterializeState.Closed)

    override fun onCleared() {
        ready?.let(materialize::discard)
        ready = null
    }

    private fun edit(change: (MaterializeState.Review) -> MaterializeState.Review) =
        _state.update { if (it is MaterializeState.Review) change(it) else it }

    private fun review(ready: Prepared.Ready): MaterializeState.Review {
        val prefill = reviewPrefill(ready.snapshot, ready.fetched.mimeType)
        return MaterializeState.Review(
            host = ready.snapshot.host,
            typeLine = MaterializeStrings.typeLine(ready.fetched.mimeType, ready.fetched.sizeBytes),
            name = prefill.displayName, kind = prefill.kind, role = prefill.role, notes = prefill.notes,
            nameError = null,
        )
    }

    private fun afterCommit(
        outcome: AttachmentResult<Attachment>,
        review: MaterializeState.Review,
        prepared: Prepared.Ready,
    ): MaterializeState = when (outcome) {
        is AttachmentResult.Ok -> MaterializeState.Done
        is AttachmentResult.Refused -> when (val problem = outcome.problem) {
            // The one refusal that keeps the staging: the owner fixes the name and saves again.
            AttachmentProblem.BlankName ->
                review.copy(nameError = AttachmentFailure.Refused(problem).sentence()).also { ready = prepared }
            AttachmentProblem.NoStore, AttachmentProblem.StoreUnavailable, is AttachmentProblem.TooLarge ->
                refused(AttachmentFailure.Refused(problem).sentence() ?: couldNotSave(review.name.trim()))
            else -> refused(couldNotSave(review.name.trim()))
        }
    }
}

/**
 * **The owner's designation invariant (#85):** materialization carries forward any semantic designation the source
 * Reference already has, exactly, and never guesses or silently downgrades it. The one place the review's starting
 * designation is decided. A reference has no document kind, so the kind is the one the proven type implies — never
 * one derived from the role (R91-9); nothing is read from the name or description, and the owner can still change or
 * clear the role on the review before Save. The reference's role is copied here exactly (#91).
 */
internal fun reviewPrefill(snapshot: SourceSnapshot, mimeType: String): MaterializeReview = MaterializeReview(
    displayName = snapshot.displayName,
    kind = AttachmentKinds.inferFrom(mimeType, fromCamera = false),
    role = snapshot.role,
    notes = snapshot.description,
)

/** B2c's clamp: a declared length the bytes outran is not shown, so `done` never exceeds a total on screen. */
internal fun downloading(host: String, done: Long, total: Long?) =
    MaterializeState.Downloading(host, done, total?.takeIf { it >= done })

private fun refused(line: String) = MaterializeState.Refused(line, offersAppSettings = false)

/**
 * C21's refusal lines. `NoSuchReference`, `NotEligible` and the two static hop problems close without one. #69 (C28):
 * the already-have line names [owner]'s kind.
 */
internal fun refusalState(why: MaterializeRefusal, host: String, owner: ReferenceOwner): MaterializeState = when (why) {
    MaterializeRefusal.NoSuchReference, MaterializeRefusal.NotEligible -> MaterializeState.Closed
    is MaterializeRefusal.Store -> AttachmentFailure.Refused(why.problem).sentence()?.let(::refused)
        ?: MaterializeState.Closed
    MaterializeRefusal.NetworkDenied -> MaterializeState.Refused(MaterializeStrings.NETWORK_DENIED, true)
    is MaterializeRefusal.AlreadyHave -> refused(MaterializeStrings.alreadyHave(owner, why.name))
    is MaterializeRefusal.Fetch -> when (val problem = why.problem) {
        FetchProblem.NotHttps, FetchProblem.HasCredentials -> MaterializeState.Closed
        FetchProblem.NetworkDenied -> MaterializeState.Refused(MaterializeStrings.NETWORK_DENIED, true)
        FetchProblem.Unreachable -> refused(MaterializeStrings.unreachable(host))
        FetchProblem.TimedOut -> refused(MaterializeStrings.TIMED_OUT)
        FetchProblem.NotADocument -> refused(MaterializeStrings.NOT_A_DOCUMENT)
        FetchProblem.NeedsSignIn -> refused(MaterializeStrings.NEEDS_SIGN_IN)
        is FetchProblem.ServerError -> refused(MaterializeStrings.serverError(problem.code))
        FetchProblem.RedirectRefused -> refused(MaterializeStrings.REDIRECT_REFUSED)
        FetchProblem.Interrupted -> refused(MaterializeStrings.INTERRUPTED)
        FetchProblem.LocalAddress -> refused(MaterializeStrings.LOCAL_ADDRESS)
        FetchProblem.TooLarge -> AttachmentFailure.Refused(AttachmentProblem.TooLarge(MAX_ATTACHMENT_BYTES))
            .sentence()?.let(::refused) ?: MaterializeState.Closed
        FetchProblem.Empty -> refused(IntakeStrings.EMPTY_FILE)
    }
}
