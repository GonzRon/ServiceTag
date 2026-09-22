package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.backup.BackupCodec
import com.loosecannon.servicetag.core.merge.MergePlan
import com.loosecannon.servicetag.core.merge.mergePlanOf
import com.loosecannon.servicetag.core.merge.mergeSnapshotOf
import com.loosecannon.servicetag.core.merge.storedBytesOf
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.AttachmentRepository
import com.loosecannon.servicetag.core.ports.AttachmentStorage
import com.loosecannon.servicetag.core.ports.ClosureRepository
import com.loosecannon.servicetag.core.ports.DefinitionRepository
import com.loosecannon.servicetag.core.ports.EventRepository
import com.loosecannon.servicetag.core.ports.GroupRepository
import com.loosecannon.servicetag.core.ports.LinkRepository
import com.loosecannon.servicetag.core.ports.ProfileRepository
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.TagRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork

/**
 * Decides what merging one archive into this install would do, and **writes nothing** (1.1.0, #46;
 * semantics from #44).
 *
 * Four steps and no more: decode — which refuses a corrupt or future-format file before anything
 * else happens — ask whether there is an attachment folder at all, hash and size whatever that
 * folder holds for the locators the archive names, and read the ten canonical tables in one
 * `uow.read` so the planner sees a single consistent point in time rather than ten. The decision
 * itself is `mergePlanOf`, a pure function.
 *
 * The same twelve collaborators, in the same order, as [ImportBackupReplace] — because the two are
 * the two halves of the same question, and a reader comparing them should have nothing to subtract.
 */
class BuildBackupMergePlan(
    private val assets: AssetRepository,
    private val groups: GroupRepository,
    private val tags: TagRepository,
    private val links: LinkRepository,
    private val definitions: DefinitionRepository,
    private val profiles: ProfileRepository,
    private val schedules: ScheduleRepository,
    private val closures: ClosureRepository,
    private val events: EventRepository,
    private val attachments: AttachmentRepository,
    private val storage: AttachmentStorage,
    private val uow: UnitOfWork,
) {
    suspend fun run(bytes: ByteArray): MergePlan {
        val backup = BackupCodec.decode(bytes)
        // Both store questions before the transaction: `store()` resolves a preference and a grant,
        // and `open` is a document-provider round trip. Neither belongs inside a Room transaction.
        // And `store()` is asked exactly once, so "is there a folder?" and "what is in it?" cannot
        // answer about two different states of the store.
        val store = storage.store()
        val configured = store != null
        val stored = storedBytesOf(backup, store)
        val snapshot = uow.read {
            mergeSnapshotOf(
                assets, groups, tags, links, definitions, profiles, schedules, closures,
                events, attachments, stored, configured,
            )
        }
        return mergePlanOf(backup, snapshot)
    }
}
