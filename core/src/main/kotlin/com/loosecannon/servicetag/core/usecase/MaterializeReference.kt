package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.fetch.FetchDocument
import com.loosecannon.servicetag.core.fetch.FetchOutcome
import com.loosecannon.servicetag.core.fetch.FetchProblem
import com.loosecannon.servicetag.core.fetch.HopPolicy
import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.model.AttachmentId
import com.loosecannon.servicetag.core.model.AttachmentKind
import com.loosecannon.servicetag.core.model.AttachmentProblem
import com.loosecannon.servicetag.core.model.AttachmentSource
import com.loosecannon.servicetag.core.model.DocumentRole
import com.loosecannon.servicetag.core.model.ReferenceId
import com.loosecannon.servicetag.core.model.ReferenceKind
import com.loosecannon.servicetag.core.model.ReferenceOwner
import com.loosecannon.servicetag.core.model.asAttachmentOwner
import com.loosecannon.servicetag.core.model.attachmentSourceProblem
import com.loosecannon.servicetag.core.ports.AttachmentRepository
import com.loosecannon.servicetag.core.ports.AttachmentStorage
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.ReferenceRepository
import com.loosecannon.servicetag.core.ports.StoreState
import com.loosecannon.servicetag.core.references.LinkDecision
import com.loosecannon.servicetag.core.references.LinkLaunchPolicy
import com.loosecannon.servicetag.core.references.MAX_REFERENCE_URI_CHARS
import com.loosecannon.servicetag.core.references.ReferenceUris
import java.util.concurrent.atomic.AtomicBoolean

/**
 * #85 (C12–C15; R85-1, R85-2, R85-3, R85-6, R85-8, R85-14): **Save as document** — a web reference's file
 * downloaded, reviewed by the owner, and stored as an ordinary managed attachment on the reference's own owner
 * (#69, C17: an asset, a SupplyItem or an installed component), carrying a write-once snapshot of where it came
 * from. Built once in `AppGraph`; used by the Save-as-document sheet (whose view model owns the job, so
 * leaving or cancelling stops it — R85-8) and by the API's materialize route (#69 B4).
 *
 * - **A copy, never a move (R85-1).** Nothing here writes or deletes a reference: [references] is only read,
 *   once, at [prepare], and the snapshot is taken from that read. The attachment keeps no `reference_id`
 *   (R85-3), so either side can go without taking the other.
 * - **One write or none.** [prepare] writes nothing; [commit] makes exactly [addAttachment]'s one write and
 *   opens no transaction of its own. Every refusal and failure leaves no row, no bytes and no staging.
 * - **The staging file is ours from the moment the fetch hands it over.** [prepare] discards it on every path
 *   that does not return [Prepared.Ready] (a refusal, a throw, a cancel during the duplicate check); after
 *   that it belongs to the caller, and [commit] or [discard] drops it. Only a blank review name keeps it,
 *   so the owner can fix the name and save again.
 * - **The network comes last.** The transport and the resolver are reached only through [fetch], after the
 *   reference, its eligibility, the store and the permission have all been checked.
 *
 * Two saves on one owner at once cannot come from the phone (the sheet is modal and holds one job), so the
 * duplicate check is not repeated inside the write: a stated limit, not a lock.
 */
class MaterializeReference(
    private val references: ReferenceRepository,
    private val attachments: AttachmentRepository,
    private val storage: AttachmentStorage,
    private val policy: LinkLaunchPolicy,
    private val hops: HopPolicy,
    private val fetch: FetchDocument,
    private val addAttachment: AddAttachment,
    private val networkPermissionGranted: () -> Boolean,
    private val clock: Clock,
) {
    /**
     * C13, in order: the reference exists and [owner] is its owner (#69, C17); it is an https web link the static
     * hop rule accepts; the store is there; the network permission is granted; then the fetch, with [onProgress] as
     * the fetch calls it (on its `io` thread); then the duplicate check over [owner]'s own files. A [Prepared.Ready]
     * carries the kept staging file; the caller must [commit] or [discard] it. A cancellation propagates, never a
     * refusal.
     */
    suspend fun prepare(
        owner: ReferenceOwner,
        referenceId: ReferenceId,
        onProgress: (done: Long, total: Long?) -> Unit = { _, _ -> },
    ): Prepared {
        val reference = references.get(referenceId)
            ?.takeIf { it.owner == owner }
            ?: return refused(MaterializeRefusal.NoSuchReference)
        val uri = reference.uri
        // The last clause asks the source shape rule now, before any byte is fetched: a restored or merged
        // reference can carry a name or URI the rule refuses, and it must never reach `commit` (1L stands in for
        // the retrieval time, read after the fetch).
        val eligible = reference.kind == ReferenceKind.WEB_URL &&
            policy.classify(uri) == LinkDecision.Allowed &&
            hops.staticProblem(uri) == null &&
            attachmentSourceProblem(uri, null, 1L, reference.displayName) == null
        if (!eligible) return refused(MaterializeRefusal.NotEligible)
        val host = ReferenceUris.hostOf(uri) ?: return refused(MaterializeRefusal.NotEligible)
        when (storage.state()) {
            StoreState.NotConfigured -> return refused(MaterializeRefusal.Store(AttachmentProblem.NoStore))
            is StoreState.AccessLost -> return refused(MaterializeRefusal.Store(AttachmentProblem.StoreUnavailable))
            is StoreState.Ready -> Unit
        }
        if (!networkPermissionGranted()) return refused(MaterializeRefusal.NetworkDenied)

        val fetched = when (val outcome = fetch.run(uri, onProgress)) {
            is FetchOutcome.Fetched -> outcome
            is FetchOutcome.Refused -> return refused(
                if (outcome.problem == FetchProblem.NetworkDenied) {
                    MaterializeRefusal.NetworkDenied
                } else {
                    MaterializeRefusal.Fetch(outcome.problem)
                },
            )
        }

        // Review m2: from here until `Ready` is returned the staging file is ours, and the duplicate check
        // suspends — so any refusal, throw or cancellation discards it before it leaves. #69 (C17, H4): the owner's
        // own files only, so the same bytes on another owner never refuse this save.
        var handedOver = false
        try {
            val retrievedAt = clock.nowMillis()
            val same = attachments.forOwner(owner.asAttachmentOwner())
                .filter { it.sha256 == fetched.sha256 && it.sizeBytes == fetched.sizeBytes }
                .minWithOrNull(compareBy<Attachment>({ it.createdAt }, { it.id.value }))
            if (same != null) return refused(MaterializeRefusal.AlreadyHave(same.displayName, same.id))
            val snapshot = SourceSnapshot(uri, reference.displayName, reference.description, host, reference.role)
            return Prepared.Ready(owner, snapshot, fetched, retrievedAt).also { handedOver = true }
        } finally {
            if (!handedOver) fetched.staged.discard()
        }
    }

    /**
     * C14: exactly one [AddAttachment] call on the reference's owner (#69, C17), the review's name, kind, role and
     * notes, the sniffed type, and the snapshot as the source. A blank name answers [AttachmentProblem.BlankName]
     * and keeps the staging for the next Save; every other outcome — stored, refused or thrown (the guard's
     * `AssetTransferredOut`, a store failure) — discards it first.
     */
    suspend fun commit(ready: Prepared.Ready, review: MaterializeReview): AttachmentResult<Attachment> {
        val name = review.displayName.trim()
        if (name.isEmpty()) return AttachmentResult.Refused(AttachmentProblem.BlankName)
        // A caller's mistake, like AddAttachment's requires: a second commit, two at once, or one after discard
        // would read a discarded file or add the same bytes twice. The blank name above does not spend it.
        check(ready.spend()) { "a prepared download is committed or discarded once" }
        try {
            val source = AttachmentSource(
                uri = ready.snapshot.uri,
                resolvedUri = resolvedUri(ready.snapshot.uri, ready.fetched.finalUrl),
                retrievedAt = ready.retrievedAt,
                name = ready.snapshot.displayName,
            )
            val command = AddAttachmentCommand(
                displayName = name,
                mimeType = ready.fetched.mimeType,
                sizeBytes = ready.fetched.sizeBytes,
                kind = review.kind,
                notes = review.notes,
                role = review.role,
                source = source,
            )
            return addAttachment.run(ready.owner.asAttachmentOwner(), command, ready.fetched.staged.source())
        } finally {
            ready.fetched.staged.discard()   // the bytes are in the store now, or nowhere
        }
    }

    /** Drops the staging and nothing else, and spends [ready]. Idempotent, as the staging's own discard is. */
    fun discard(ready: Prepared.Ready) {
        ready.spend()
        ready.fetched.staged.discard()
    }

    private fun refused(why: MaterializeRefusal) = Prepared.Refused(why)

    /**
     * R85-2, R85-14: where the redirects ended, as scheme + authority + the ordinary path — never a query, a
     * fragment, a segment's `;` parameters or userinfo — and only when that differs from where the reference
     * points and fits the URI limit. Null when the destination did not move.
     */
    private fun resolvedUri(original: String, finalUrl: String): String? {
        val destination = ReferenceUris.destinationOf(finalUrl) ?: return null
        if (destination == ReferenceUris.destinationOf(original)) return null
        return destination.takeIf { it.length <= MAX_REFERENCE_URI_CHARS }
    }
}

/** What [MaterializeReference.prepare] answers. */
sealed interface Prepared {
    /**
     * The download is in staging, proven a document, and not already among [owner]'s own files; [owner] is the
     * reference's, and the saved file's owner follows from it (#69, C17). [retrievedAt] is when the fetch finished.
     * [toString] names no URI, host or title (review NOTE 1): a stray log line carries none.
     *
     * Single use: the first `commit` that gets past a blank name, or the first `discard`, spends it, and any
     * later `commit` throws `IllegalStateException`. The flag is atomic, so two commits at once cannot both pass.
     */
    data class Ready(
        val owner: ReferenceOwner,
        val snapshot: SourceSnapshot,
        val fetched: FetchOutcome.Fetched,
        val retrievedAt: Long,
    ) : Prepared {
        // Not a constructor property, so equals, hashCode and copy are untouched.
        private val spent = AtomicBoolean(false)

        /** True for the one caller that spends it; false for every later one. */
        internal fun spend(): Boolean = spent.compareAndSet(false, true)

        override fun toString() = "Ready(owner=$owner, fetched=$fetched, retrievedAt=$retrievedAt)"
    }

    data class Refused(val why: MaterializeRefusal) : Prepared
}

/**
 * The reference as it was at [MaterializeReference.prepare]: [uri] verbatim, its name and description, [host]
 * (`ReferenceUris.hostOf`) for the review, and [role], the owner's own designation of it (#91, none = null) —
 * deliberately without a default, so no construction can drop it. [toString] names the host only, never the URI.
 */
data class SourceSnapshot(
    val uri: String,
    val displayName: String,
    val description: String,
    val host: String,
    val role: DocumentRole?,
) {
    override fun toString() = "SourceSnapshot(host=$host)"
}

/** What the owner settles on the review before Save; the name is trimmed at [MaterializeReference.commit]. */
data class MaterializeReview(
    val displayName: String,
    val kind: AttachmentKind,
    val role: DocumentRole?,
    val notes: String,
)

/** Why [MaterializeReference.prepare] stopped short of a download in hand. */
sealed interface MaterializeRefusal {
    /** No such reference, or it belongs to another owner. */
    data object NoSuchReference : MaterializeRefusal

    /**
     * Not an https web link the hop rule accepts (a note link, `http`, userinfo, a local name, an odd host), or a
     * name or URI the source shape rule would refuse at Save (a blank or over-long name, an over-long URI).
     */
    data object NotEligible : MaterializeRefusal

    /** [AttachmentProblem.NoStore] or [AttachmentProblem.StoreUnavailable], checked before any download. */
    data class Store(val problem: AttachmentProblem) : MaterializeRefusal

    /** The INTERNET permission is off, before the fetch or as the transport reported it. */
    data object NetworkDenied : MaterializeRefusal

    /** The fetch refused; the staging is already gone. */
    data class Fetch(val problem: FetchProblem) : MaterializeRefusal

    /**
     * R85-6: the same bytes (digest and size) are already among the owner's own files, as the earliest such row
     * [name]s. #92 (C17): [attachmentId] is that row's id, so the API can name the row without its name.
     */
    data class AlreadyHave(val name: String, val attachmentId: AttachmentId) : MaterializeRefusal
}
