package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.model.AttachmentId
import com.loosecannon.servicetag.core.model.AttachmentKinds
import com.loosecannon.servicetag.core.model.AttachmentLocator
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.AttachmentProblem
import com.loosecannon.servicetag.core.model.MAX_ATTACHMENT_BYTES
import com.loosecannon.servicetag.core.model.MimeTypes
import com.loosecannon.servicetag.core.model.accepts
import com.loosecannon.servicetag.core.model.attachmentSourceProblem
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.AttachmentRepository
import com.loosecannon.servicetag.core.ports.AttachmentStorage
import com.loosecannon.servicetag.core.ports.ByteSource
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.EventRepository
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.InstalledComponentRepository
import com.loosecannon.servicetag.core.ports.StoreState
import com.loosecannon.servicetag.core.ports.SupplyItemRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork

/**
 * Bytes first, row second (spec §6). The store hashes while it copies, so the row records what
 * the store saw and the two can never disagree; if the row write then fails, the bytes are
 * deleted, because nothing would point at them.
 *
 * The owner check and the `put` both happen *outside* `uow.write`: the transaction holds the row
 * write alone. `FakeUnitOfWork` is not re-entrant and a multi-megabyte copy has no business
 * inside a database transaction.
 */
class AddAttachment(
    private val attachments: AttachmentRepository,
    private val assets: AssetRepository,
    private val events: EventRepository,
    private val supplyItems: SupplyItemRepository,
    private val installedComponents: InstalledComponentRepository,
    private val storage: AttachmentStorage,
    private val uow: UnitOfWork,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    suspend fun run(
        owner: AttachmentOwner,
        cmd: AddAttachmentCommand,
        source: ByteSource,
        /**
         * #92 (C13): the row's id when the caller derived it (the API upload's operation key); null mints one, as
         * shipped. A preset id must be free — a `put` at an occupied locator would delete that row's bytes — so it is
         * checked before any byte moves; the caller's lock keeps it free until the row lands.
         */
        presetId: AttachmentId? = null,
    ): AttachmentResult<Attachment> {
        // #67, C1: first, so a role on an event can never reach `put`. Not a refusal the section
        // draws — no screen offers a role on an event's file — but a caller's mistake.
        require(owner.accepts(cmd.role)) {
            "a document role belongs on an asset's, a supply item's or an installed component's file, not an entry's"
        }
        // #85, C12: provenance never enters malformed. A caller's mistake too, and just as early.
        val sourceProblem = cmd.source?.let { attachmentSourceProblem(it.uri, it.resolvedUri, it.retrievedAt, it.name) }
        require(sourceProblem == null) { "a malformed attachment source: $sourceProblem" }
        val name = cmd.displayName.trim()
        if (name.isEmpty()) return AttachmentResult.Refused(AttachmentProblem.BlankName)
        if (!ownerExists(owner)) return AttachmentResult.Refused(AttachmentProblem.OwnerMissing)
        if (cmd.sizeBytes != null && cmd.sizeBytes > MAX_ATTACHMENT_BYTES) {
            return AttachmentResult.Refused(AttachmentProblem.TooLarge(MAX_ATTACHMENT_BYTES))
        }
        val store = when (storage.state()) {
            StoreState.NotConfigured -> return AttachmentResult.Refused(AttachmentProblem.NoStore)
            is StoreState.AccessLost ->
                return AttachmentResult.Refused(AttachmentProblem.StoreUnavailable)
            is StoreState.Ready -> storage.store()
                ?: return AttachmentResult.Refused(AttachmentProblem.StoreUnavailable)
        }

        val id = presetId ?: AttachmentId(ids.newId())
        if (presetId != null) require(attachments.get(presetId) == null) { "a preset attachment id is already a row's" }
        val mimeType = MimeTypes.normalise(cmd.mimeType)
        // #85, planner finding 5: a sourced add's name is a reference's title, not a filename, so the type alone
        // picks its extension (an empty name has none). Every other add is the shipped call, untouched.
        val locator = if (cmd.source == null) {
            AttachmentLocator.forOwner(owner, id, name, mimeType)
        } else {
            AttachmentLocator.forOwner(owner, id, "", mimeType)
        }
        val stored = store.put(locator, source)
        // A provider that under-reported its size (or reported none) is caught here instead.
        if (stored.sizeBytes > MAX_ATTACHMENT_BYTES) {
            store.deleteBestEffort(locator)   // a store that will not delete is not a second error
            return AttachmentResult.Refused(AttachmentProblem.TooLarge(MAX_ATTACHMENT_BYTES))
        }

        val now = clock.nowMillis()
        val row = Attachment(
            id = id,
            owner = owner,
            kind = cmd.kind ?: AttachmentKinds.inferFrom(mimeType, cmd.fromCamera),
            displayName = name,
            mimeType = mimeType,
            sizeBytes = stored.sizeBytes,
            sha256 = stored.sha256,
            storageLocator = locator,
            capturedOn = cmd.capturedOn?.trim()?.takeIf { it.isNotEmpty() },
            notes = cmd.notes.trim(),
            createdAt = now,
            updatedAt = now,
            role = cmd.role,
            source = cmd.source,
        )
        try {
            uow.write { attachments.upsert(row) }
        } catch (t: Throwable) {
            // The bytes were ours and now nothing names them. A cleanup that fails is recorded
            // against the failure in flight, never raised over it: the row write is what the
            // caller needs to hear about.
            try {
                store.delete(locator)
            } catch (cleanup: Throwable) {
                if (cleanup !== t) t.addSuppressed(cleanup)
            }
            throw t
        }
        return AttachmentResult.Ok(row)
    }

    /** #69 (R69-10): an archived SupplyItem and a removed component are owners like any other. */
    private suspend fun ownerExists(owner: AttachmentOwner): Boolean = when (owner) {
        is AttachmentOwner.OfAsset -> assets.get(owner.assetId) != null
        is AttachmentOwner.OfEvent -> events.get(owner.eventId) != null
        is AttachmentOwner.OfSupplyItem -> supplyItems.get(owner.supplyId) != null
        is AttachmentOwner.OfInstalledComponent -> installedComponents.get(owner.componentId) != null
    }
}
