package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.fetch.StagingArea
import com.loosecannon.servicetag.core.fetch.StagingFile
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.model.AttachmentId
import com.loosecannon.servicetag.core.model.AttachmentKind
import com.loosecannon.servicetag.core.model.AttachmentKinds
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.AttachmentProblem
import com.loosecannon.servicetag.core.model.DocumentRole
import com.loosecannon.servicetag.core.model.MimeTypes
import com.loosecannon.servicetag.core.model.ReferenceId
import com.loosecannon.servicetag.core.model.accepts
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.AttachmentRepository
import com.loosecannon.servicetag.core.ports.AttachmentStorage
import com.loosecannon.servicetag.core.ports.ReferenceRepository
import com.loosecannon.servicetag.core.ports.StoreState
import com.loosecannon.servicetag.core.ports.TransferRecordRepository
import com.loosecannon.servicetag.core.transfer.AssetTransferredOut
import com.loosecannon.servicetag.core.usecase.AddAttachment
import com.loosecannon.servicetag.core.usecase.AddAttachmentCommand
import com.loosecannon.servicetag.core.usecase.AttachmentResult
import com.loosecannon.servicetag.core.usecase.MaterializeReference
import com.loosecannon.servicetag.core.usecase.MaterializeReview
import com.loosecannon.servicetag.core.usecase.NoSuchAsset
import com.loosecannon.servicetag.core.usecase.Prepared
import com.loosecannon.servicetag.core.usecase.ReferenceProblem
import com.loosecannon.servicetag.core.usecase.SourceSnapshot
import com.loosecannon.servicetag.core.usecase.UpdateAttachment
import com.loosecannon.servicetag.core.usecase.isIsoDate
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.prefs.InstallationIdentity
import com.loosecannon.servicetag.ui.references.reviewPrefill
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.CharacterCodingException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * #92's attachment rows (C5–C8), reached from the router as `handlers.attachmentRoutes.*` — not `attachments`, which
 * `ApiHandlers` already spends on its repository for the status count.
 *
 * The rule is every collaborator's: **the one write is one `UpdateAttachment` call**, and the two reads read the
 * attachment repository as the DOCUMENTS section does. Nothing here deletes an attachment, reads or writes its bytes,
 * or writes provenance: the API adds and amends, the phone removes. A role on an event's attachment is refused here as
 * a 422 before the use case is called, because the use case treats it as a programming error (R67-11).
 *
 * It also holds this installation's [InstallationIdentity] (C5a), which `/v1/status` reports and the upload's id
 * derivation (C13) reads.
 *
 * **The upload (B1b, C9–C13)** is one `AddAttachment` call over a file streamed into [staging] — the app's cache
 * staging area, `cache/materialize/`, the one #85 sweeps at start — never into memory. Its steps 2–5 hold
 * [apiLongWrites], the process-wide lock `AppGraph` owns (C33), so a retried upload on a new listener generation
 * waits for the first and then finds its row. The router has already checked the token on the headers; nothing here
 * runs for an unauthenticated caller (C10).
 *
 * **Save as document (B2, C14–C17)** is `MaterializeReference.prepare` then `commit`, for an existing web reference
 * **by id** — the asset is the reference's, and `prepare` alone reads its URI — under the same [apiLongWrites]. The
 * download runs inside a `Job` the listener's `stop()` cancels; the commit, once it began, runs to its end (R87-4).
 */
internal class AttachmentHandlers(
    private val attachments: AttachmentRepository,
    private val assets: AssetRepository,
    private val storage: AttachmentStorage,
    private val updateAttachment: UpdateAttachment,
    private val installation: InstallationIdentity,
    private val transfers: TransferRecordRepository,
    private val addAttachment: AddAttachment,
    private val staging: StagingArea,
    private val apiLongWrites: Mutex,
    /** C12's wall clock over every body read on the upload path; parameterised so a test need not wait ten minutes. */
    private val uploadDeadlineMillis: Long = UPLOAD_DEADLINE_MILLIS,
    private val nanoTime: () -> Long = System::nanoTime,
    /** B2: the reference, read by id for its asset, and save as document; null only in a fixture that never saves. */
    private val references: ReferenceRepository? = null,
    private val materializeReference: MaterializeReference? = null,
    /** C14 step 4: the phone's own starting designation; parameterised only so a test can reach commit's blank name. */
    private val prefill: (SourceSnapshot, String) -> MaterializeReview =
        { snapshot, type -> reviewPrefill(snapshot, type) },
) {
    constructor(graph: AppGraph) : this(
        graph.attachments, graph.assets, graph.attachmentStorage, graph.updateAttachment, graph.installationIdentity,
        graph.transferRecords, graph.addAttachment, graph.materializeStaging, graph.apiLongWrites,
        references = graph.references, materializeReference = graph.materializeReference,
    )

    /** `/v1/status`' `installationId` (C5a): read from its file once per process, never minted twice. */
    fun installationId(): String = installation.id()

    /**
     * `GET /v1/assets/{id}/attachments` (C5): the asset's own rows — never its events' — oldest first, then by id, and
     * the folder's state by name alone. A transferred-out asset's rows read like any other. Writes nothing.
     */
    suspend fun listForAsset(assetId: String): ApiResponse {
        val id = AssetId(assetId)
        assets.get(id) ?: throw NoSuchAsset(id)
        val rows = attachments.forAsset(id).sortedWith(compareBy<Attachment>({ it.createdAt }, { it.id.value }))
        return ok(
            AttachmentListResponse.serializer(),
            AttachmentListResponse(rows.map { it.toDto() }, folderOf(storage.state())),
        )
    }

    /** `GET /v1/attachments/{id}` (C6): one row of any owner. Writes nothing. */
    suspend fun get(attachmentId: String): ApiResponse =
        ok(AttachmentResponse.serializer(), AttachmentResponse(row(attachmentId).toDto()))

    /**
     * `PATCH /v1/attachments/{id}` (C7): decode, then the row, then `capturedOn`, then the role against the row's
     * owner, then the use case. A no-op is 200 with the stored row and nothing written, as a reference's is.
     * `capturedOn` is trimmed and a blank one is null, as the asset commands and the use case treat a date; only a
     * non-blank value that is not an ISO day is refused.
     */
    suspend fun update(attachmentId: String, request: ApiRequest): ApiResponse {
        val decoded = request.decode(UpdateAttachmentRequest.serializer()).toCommand()
        val command = decoded.copy(capturedOn = decoded.capturedOn?.trim()?.takeIf { it.isNotEmpty() })
        val row = row(attachmentId)
        if (command.capturedOn?.let(::isIsoDate) == false) throw attachmentBadDate()
        if (!row.owner.accepts(command.role)) throw attachmentRoleNotAllowed()
        val saved = when (val result = updateAttachment.run(row.id, command)) {
            is AttachmentResult.Ok -> result.value
            is AttachmentResult.Refused -> attachmentRefusal(result.problem)?.let { throw it } ?: row
        }
        return ok(AttachmentResponse.serializer(), AttachmentResponse(saved.toDto()))
    }

    /**
     * `POST /v1/assets/{id}/attachments` (C12). **An answer is written only after the declared body has been read to
     * its end** — into staging on the way to `AddAttachment`, into a hashing sink on a replay, into a counting sink
     * when a refusal was decided first — so a refusal is never a reset. A failure of the request's own stream (a read
     * timeout, a reset, `stop()`, the deadline) is [RequestStreamFailed]: nothing written, staging discarded, no
     * answer. A failure writing staging is 409 `UPLOAD_NOT_STAGED`. The two are told apart by which stream failed.
     */
    suspend fun upload(assetId: String, request: ApiRequest): ApiResponse {
        val body = UploadBody(request.stream ?: ByteArrayInputStream(request.body))
        return try {
            val upload = admit(AssetId(assetId), request)
            apiLongWrites.withLock { write(upload, body) }
        } catch (gone: RequestStreamFailed) {
            throw gone
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (refused: Exception) {
            body.finish()
            throw refused
        }
    }

    /**
     * Step 1: the cheap checks, in C12's order, before a body byte is read — each refusal spares a 256 MiB stage. The
     * row id is derived here too (the installation id is a file read on a process's first call, and its failure is
     * no staging failure); it is looked up under the lock, in [write].
     */
    private suspend fun admit(assetId: AssetId, request: ApiRequest): Upload {
        val meta = decodeMetadata(request.headers[UPLOAD_METADATA_HEADER])
        assets.get(assetId) ?: throw NoSuchAsset(assetId)
        if (assetId in transfers.heldIds()) throw AssetTransferredOut(assetId)
        val folderProblem = when (storage.state()) {
            StoreState.NotConfigured -> AttachmentProblem.NoStore
            is StoreState.AccessLost -> AttachmentProblem.StoreUnavailable
            is StoreState.Ready -> if (storage.store() == null) AttachmentProblem.StoreUnavailable else null
        }
        folderProblem?.let { throw addRefusal(assetId, it) }
        if (!isOperationKey(meta.operationKey)) throw operationKeyInvalid()
        val name = meta.displayName.trim()
        if (name.isEmpty()) throw addRefusal(assetId, AttachmentProblem.BlankName)
        val capturedOn = meta.capturedOn?.trim()?.takeIf { it.isNotEmpty() }
        if (capturedOn?.let(::isIsoDate) == false) throw attachmentBadDate()
        if (!SHA256_HEX.matches(meta.sha256)) throw sha256Invalid()
        val mimeType = MimeTypes.normalise(request.headers["content-type"] ?: OCTET_STREAM)
        return Upload(
            assetId = assetId,
            id = attachmentOperationId(installation.id(), assetId, meta.operationKey),
            meta = meta.copy(displayName = name, capturedOn = capturedOn),
            mimeType = mimeType,
            kind = meta.kind ?: AttachmentKinds.inferFrom(mimeType, fromCamera = false),
        )
    }

    /** Steps 2–5, under the lock: the derived id re-checked, then a replay or one new row. */
    private suspend fun write(upload: Upload, body: UploadBody): ApiResponse {
        attachments.get(upload.id)?.let { return replay(it, upload, body) }
        val staged = try {
            staging.create()
        } catch (write: IOException) {
            throw uploadNotStaged()
        }
        try {
            val arrived = body.stageInto(staged)
            if (arrived.size == 0L) throw attachmentEmpty()
            if (arrived.sha256 != upload.meta.sha256) throw sha256Mismatch()
            val command = AddAttachmentCommand(
                displayName = upload.meta.displayName,
                mimeType = upload.mimeType,
                sizeBytes = arrived.size,
                kind = upload.kind,
                capturedOn = upload.meta.capturedOn,
                notes = upload.meta.notes,
                fromCamera = false,
                role = upload.meta.role,
                source = null,
            )
            val owner = AttachmentOwner.OfAsset(upload.assetId)
            return when (val result = addAttachment.run(owner, command, staged.source(), presetId = upload.id)) {
                is AttachmentResult.Ok ->
                    createdResponse(AttachmentResponse.serializer(), AttachmentResponse(result.value.toDto()))
                is AttachmentResult.Refused -> throw addRefusal(upload.assetId, result.problem)
            }
        } finally {
            staged.discard()
        }
    }

    /**
     * Step 2's replay (C13, R92-7 strict): the body hashed to its end and never staged, then the request's
     * fingerprint — owner, kind as resolved, role, trimmed name, sha256, size — against **the row as it stands**.
     * Equal is 200 with the row; anything else is 409 naming it. Nothing is written either way: a metadata change
     * belongs to `PATCH`.
     */
    private fun replay(row: Attachment, upload: Upload, body: UploadBody): ApiResponse {
        val arrived = body.pump { _, _ -> }
        if (arrived.sha256 != upload.meta.sha256) throw sha256Mismatch()
        val same = row.owner == AttachmentOwner.OfAsset(upload.assetId) &&
            row.kind == upload.kind &&
            row.role == upload.meta.role &&
            row.displayName == upload.meta.displayName &&
            row.sha256 == arrived.sha256 &&
            row.sizeBytes == arrived.size
        if (!same) throw operationKeyReused(row.id)
        return ok(AttachmentResponse.serializer(), AttachmentResponse(row.toDto()))
    }

    /**
     * `POST /v1/references/{id}/materialize` (C14–C17, R92-3): save as document, synchronously, for the reference
     * `{id}` as it is stored. In C14's order:
     * 1. the four review fields (BC1: any other key, `url` and `uri` included, is the strict decoder's 400); a
     *    **given** name blank after trim is 422 — before any fetch;
     * 2. the reference by id (404), its asset, and that asset transferred out (409) — before any fetch;
     * 3. the download's `Job`, a child of the listener [generation] that read the request, registered in [downloads]
     *    **before** it waits for [apiLongWrites]: a `stop()` while it waits, or one that landed before it registered
     *    (BC5), cancels it and nothing is fetched. Under the lock, `prepare` inside that `Job`; a refusal is C17's;
     * 4.–6. the review, the hand-off and the commit ([saveAsDocument]).
     * The slot is cleared only if it still holds this `Job`, and the reference row is never written.
     */
    suspend fun materialize(
        referenceId: String,
        request: ApiRequest,
        generation: Job?,
        downloads: DownloadInFlight,
    ): ApiResponse {
        val references = checkNotNull(references) { "save as document is not wired" }
        val materializeReference = checkNotNull(materializeReference) { "save as document is not wired" }
        val given = request.decode(MaterializeRequest.serializer())
        if (given.displayName?.isBlank() == true) throw nameRequired()
        val reference = references.get(ReferenceId(referenceId))
            ?: throw ReferenceRefused(ReferenceProblem.NoSuchReference)
        val assetId = reference.assetId
        if (assetId in transfers.heldIds()) throw AssetTransferredOut(assetId)

        val download = Job(generation)
        downloads.register(download)
        try {
            return withContext(download) {
                apiLongWrites.withLock { saveAsDocument(materializeReference, assetId, reference.id, given) }
            }
        } finally {
            downloads.clear(download)
            download.complete()
        }
    }

    /**
     * C14 steps 3–6, under the lock and inside the registered download's `Job`:
     * - `prepare` downloads (the address policy, the limits, the nineteen types and R85-6's same-bytes check are all
     *   its own); a refusal answers C17's code, and a cancel propagates with its staging already discarded;
     * - the review is [prefill]'s with each given key laid over it;
     * - **the hand-off:** a `stop()` that landed after `prepare` returned and before the commit began wins — the
     *   download's `Job` is cancelled, so nothing is written; otherwise the commit runs `NonCancellable` and wins —
     *   neither `stop()` nor a client disconnect can cut it, and a cancellation that surfaces after it is not a
     *   failure (BC6): the row is durable and no answer is sent;
     * - `finally`, [MaterializeReference.discard] for any `Ready` the commit did not spend (a blank name keeps its
     *   staging, a throw building the review never reaches the commit; plan review C-9). It touches staging only,
     *   never the store or the row, and after a commit that spent it, it is the idempotent no-op.
     * Every failure the commit raises maps through the shipped arms (BC8): nothing here builds a message from it.
     */
    private suspend fun saveAsDocument(
        materializeReference: MaterializeReference,
        assetId: AssetId,
        referenceId: ReferenceId,
        given: MaterializeRequest,
    ): ApiResponse {
        val ready = when (val prepared = materializeReference.prepare(assetId, referenceId)) {
            is Prepared.Refused -> throw materializeRefusal(prepared.why)
            is Prepared.Ready -> prepared
        }
        try {
            val prefilled = prefill(ready.snapshot, ready.fetched.mimeType)
            val review = MaterializeReview(
                displayName = given.displayName ?: prefilled.displayName,
                kind = given.kind ?: prefilled.kind,
                role = given.role ?: prefilled.role,
                notes = given.notes ?: prefilled.notes,
            )
            currentCoroutineContext().ensureActive()
            val saved = withContext(NonCancellable) { materializeReference.commit(ready, review) }
            return when (saved) {
                is AttachmentResult.Ok ->
                    createdResponse(AttachmentResponse.serializer(), AttachmentResponse(saved.value.toDto()))
                is AttachmentResult.Refused -> throw addRefusal(assetId, saved.problem)
            }
        } finally {
            materializeReference.discard(ready)
        }
    }

    private fun nameRequired(): Exception =
        attachmentRefusal(AttachmentProblem.BlankName) ?: IllegalStateException("a blank name is always refused")

    /** An add's `OwnerMissing` is the asset (a phone-side delete racing the upload), not an attachment row. */
    private fun addRefusal(assetId: AssetId, problem: AttachmentProblem): Exception =
        if (problem == AttachmentProblem.OwnerMissing) {
            NoSuchAsset(assetId)
        } else {
            attachmentRefusal(problem) ?: IllegalStateException("an add is never Unchanged")
        }

    private suspend fun row(id: String): Attachment = attachments.get(AttachmentId(id)) ?: throw noSuchAttachment()

    /** One upload's checked request: the derived id, the normalised metadata, the type and the kind as resolved. */
    private class Upload(
        val assetId: AssetId,
        val id: AttachmentId,
        val meta: UploadMetadata,
        val mimeType: String,
        val kind: AttachmentKind,
    )

    private class Arrived(val size: Long, val sha256: String)

    /**
     * The request body, read once, in 64 KiB chunks, counted and hashed, under the deadline. A read that throws is the
     * **request stream's** failure ([RequestStreamFailed]; an early end is the shipped 400); a write that throws is
     * **staging's** (`UPLOAD_NOT_STAGED`). Each `catch` wraps exactly one stream.
     */
    private inner class UploadBody(private val input: InputStream) {
        private var finished = false
        private var startedAt: Long? = null

        fun pump(sink: (ByteArray, Int) -> Unit): Arrived {
            val digest = MessageDigest.getInstance("SHA-256")
            val chunk = ByteArray(CHUNK_BYTES)
            var size = 0L
            val started = startedAt ?: nanoTime().also { startedAt = it }
            val deadline = TimeUnit.MILLISECONDS.toNanos(uploadDeadlineMillis)
            while (true) {
                if (nanoTime() - started > deadline) throw RequestStreamFailed("the upload's deadline passed")
                val n = readOnce(chunk)
                if (n < 0) break
                digest.update(chunk, 0, n)
                size += n
                sink(chunk, n)
            }
            finished = true
            return Arrived(size, digest.digest().joinToString("") { "%02x".format(it) })
        }

        fun stageInto(file: StagingFile): Arrived {
            val out: OutputStream = try {
                file.output()
            } catch (write: IOException) {
                throw uploadNotStaged()
            }
            val arrived = try {
                pump { chunk, n ->
                    try {
                        out.write(chunk, 0, n)
                    } catch (write: IOException) {
                        throw uploadNotStaged()
                    }
                }
            } catch (t: Throwable) {
                try {
                    out.close()
                } catch (write: IOException) {
                    t.addSuppressed(write)
                }
                throw t
            }
            try {
                out.close()
            } catch (write: IOException) {
                throw uploadNotStaged()
            }
            return arrived
        }

        /** What is left of the body, into a counting sink, so the answer that follows is never a reset. */
        fun finish() {
            if (!finished) pump { _, _ -> }
        }

        private fun readOnce(chunk: ByteArray): Int = try {
            input.read(chunk)
        } catch (early: EarlyEndOfBody) {
            finished = true
            throw ApiFailure.badRequest("the body was shorter than Content-Length")
        } catch (read: IOException) {
            throw RequestStreamFailed("the request stream failed", read)
        }
    }
}

/**
 * `POST /v1/references/{id}/materialize` (C14): the review's four fields, every one optional — absent or null takes
 * the phone's prefill. **No key carries a URL, a host or a path** (R92-3, BC1): the strict decoder answers any other
 * key with the shipped 400, and the URI fetched is the stored reference's, read by `prepare` alone.
 */
@Serializable
internal data class MaterializeRequest(
    val displayName: String? = null,
    val kind: AttachmentKind? = null,
    val role: DocumentRole? = null,
    val notes: String? = null,
)

/** C12: ten minutes over every body read of one upload; past it the connection is closed and nothing is written. */
internal const val UPLOAD_DEADLINE_MILLIS: Long = 10 * 60 * 1000L

/** C11: the upload's metadata header, lower-cased as the parser keeps every header name. */
internal const val UPLOAD_METADATA_HEADER: String = "x-servicetag-attachment"

private const val OCTET_STREAM = "application/octet-stream"
private const val CHUNK_BYTES = 64 * 1024
private val SHA256_HEX = Regex("[0-9a-f]{64}")

/** C11: unpadded base64url of UTF-8 JSON, decoded strictly; anything else is the shipped 400. */
private fun decodeMetadata(header: String?): UploadMetadata {
    if (header == null) throw ApiFailure.badRequest("an upload needs its X-ServiceTag-Attachment header")
    if ('=' in header) throw ApiFailure.badRequest("X-ServiceTag-Attachment is not unpadded base64url")
    val bytes = try {
        Base64.getUrlDecoder().decode(header)
    } catch (notBase64: IllegalArgumentException) {
        throw ApiFailure.badRequest("X-ServiceTag-Attachment is not unpadded base64url")
    }
    val text = try {
        bytes.decodeToString(throwOnInvalidSequence = true)
    } catch (notUtf8: CharacterCodingException) {
        throw ApiFailure.badRequest("X-ServiceTag-Attachment is not UTF-8")
    }
    return decodeOr400(UploadMetadata.serializer(), text)
}

// --- #92 (B1b), the upload's codes (C2), each code, status, `field` and sentence verbatim ---------------------------

private fun attachmentEmpty(): ApiFailure =
    ApiFailure(422, "Unprocessable Content", "ATTACHMENT_EMPTY", "the file is empty")

private fun sha256Invalid(): ApiFailure = ApiFailure(
    422, "Unprocessable Content", "ATTACHMENT_SHA256_INVALID", "sha256 is not 64 lowercase hex characters",
    field = "sha256",
)

private fun sha256Mismatch(): ApiFailure = ApiFailure(
    422, "Unprocessable Content", "ATTACHMENT_SHA256_MISMATCH", "the bytes that arrived do not match sha256",
    field = "sha256",
)

private fun operationKeyInvalid(): ApiFailure = ApiFailure(
    422, "Unprocessable Content", "OPERATION_KEY_INVALID", "operationKey is not a valid key", field = "operationKey",
)

private fun operationKeyReused(id: AttachmentId): ApiFailure = ApiFailure(
    409, "Conflict", "OPERATION_KEY_REUSED",
    "this operation key was used for a different upload; read or update that attachment",
    listOf("OperationKeyReused(attachmentId=${id.value})"), "operationKey",
)

private fun uploadNotStaged(): ApiFailure =
    ApiFailure(409, "Conflict", "UPLOAD_NOT_STAGED", "the upload could not be staged on this phone")
