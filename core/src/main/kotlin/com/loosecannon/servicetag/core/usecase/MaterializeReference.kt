package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.fetch.FetchDocument
import com.loosecannon.servicetag.core.fetch.FetchOutcome
import com.loosecannon.servicetag.core.fetch.FetchProblem
import com.loosecannon.servicetag.core.fetch.HopPolicy
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.model.AttachmentKind
import com.loosecannon.servicetag.core.model.AttachmentProblem
import com.loosecannon.servicetag.core.model.DocumentRole
import com.loosecannon.servicetag.core.model.ReferenceId
import com.loosecannon.servicetag.core.ports.AttachmentRepository
import com.loosecannon.servicetag.core.ports.AttachmentStorage
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.ReferenceRepository
import com.loosecannon.servicetag.core.references.LinkLaunchPolicy

/** Test-first stub (B3): the types are final, the bodies are not. */
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
    suspend fun prepare(
        assetId: AssetId,
        referenceId: ReferenceId,
        onProgress: (done: Long, total: Long?) -> Unit = { _, _ -> },
    ): Prepared = Prepared.Refused(MaterializeRefusal.NoSuchReference)

    suspend fun commit(ready: Prepared.Ready, review: MaterializeReview): AttachmentResult<Attachment> =
        AttachmentResult.Refused(AttachmentProblem.BlankName)

    fun discard(ready: Prepared.Ready) = Unit
}

sealed interface Prepared {
    data class Ready(
        val assetId: AssetId,
        val snapshot: SourceSnapshot,
        val fetched: FetchOutcome.Fetched,
        val retrievedAt: Long,
    ) : Prepared

    data class Refused(val why: MaterializeRefusal) : Prepared
}

data class SourceSnapshot(val uri: String, val displayName: String, val description: String, val host: String)

data class MaterializeReview(
    val displayName: String,
    val kind: AttachmentKind,
    val role: DocumentRole?,
    val notes: String,
)

sealed interface MaterializeRefusal {
    data object NoSuchReference : MaterializeRefusal
    data object NotEligible : MaterializeRefusal
    data class Store(val problem: AttachmentProblem) : MaterializeRefusal
    data object NetworkDenied : MaterializeRefusal
    data class Fetch(val problem: FetchProblem) : MaterializeRefusal
    data class AlreadyHave(val name: String) : MaterializeRefusal
}
