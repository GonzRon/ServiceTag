package com.loosecannon.servicetag.core.backup

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AttachmentId
import com.loosecannon.servicetag.core.transfer.EntangledRef

/** Anything that stops a backup from being read. Never thrown while writing one. */
sealed class BackupException(message: String) : Exception(message)

/** The file was written by a newer build than this one understands; refuse rather than guess. */
class BackupNewerFormat(val found: Int, val supported: Int) :
    BackupException("backup format $found is newer than supported $supported")

/** Missing entries, a hash mismatch, unparsable JSON, or a value this format cannot name. */
class BackupCorrupt(reason: String) : BackupException(reason)

/** The artifacts archive was written by a newer build than this one understands. */
class ArtifactsNewerFormat(val found: Int, val supported: Int) :
    BackupException("artifact format $found is newer than supported $supported")

/** These bytes belong to a different backup set than the data that was restored (spec §11.3). */
class ArtifactsSetMismatch(val expected: String, val found: String) :
    BackupException("artifacts belong to backup set $found, not $expected")

/**
 * The artifacts archive could not be written: a source that vanished or changed length between
 * the codec's two passes, or a ZIP the JDK refused to seal. Deliberately *not* a
 * [BackupException] — that family is what a reader raises, and this is a write failure. The
 * half-written archive is unusable, so the export deletes both files and says so (spec §7.3).
 */
class ArtifactsWriteFailed(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Thrown by the export when [ArtifactsWritten.covers] is false; the archives are already gone. */
class BackupSetIncomplete(val missing: List<AttachmentId>, val mismatched: List<AttachmentId>) :
    Exception("backup set incomplete: ${missing.size} missing, ${mismatched.size} mismatched")

/**
 * #77 (C9; P77-58, mapped by the Backup screen): rows that stay on this phone name rows of an asset it has
 * transferred out, so no ordinary backup of what stays would decode. Thrown by the export **before** any
 * byte exists; [refs] names every such reference. Not a [BackupException]: nothing is being read.
 */
class TransferredGraphEntangled(val refs: List<EntangledRef>) :
    Exception("${refs.size} rows here name rows of an asset transferred out")

/**
 * #77 (R77-13, C9 amended): a Replace restore of an archive that carries the graph of [assetIds] — assets
 * this phone transferred out and holds as such — with no later IN in the archive's own records whose lineage
 * closes this phone's OUT. Thrown **before** anything is wiped. Its sentence is still to be ratified; until
 * then the Backup screen shows its generic restore failure.
 */
class TransferredOutInArchive(val assetIds: List<AssetId>) :
    Exception("the archive carries ${assetIds.size} assets transferred out from this phone")
