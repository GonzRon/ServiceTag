package com.loosecannon.servicetag.ui.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.servicetag.R
import com.loosecannon.servicetag.backup.BackupSetNames
import com.loosecannon.servicetag.core.backup.ArtifactsCodec
import com.loosecannon.servicetag.core.backup.ArtifactsSetMismatch
import com.loosecannon.servicetag.core.backup.ArtifactsWriteFailed
import com.loosecannon.servicetag.core.backup.ArtifactsWritten
import com.loosecannon.servicetag.core.backup.BackupSetIncomplete
import com.loosecannon.servicetag.core.backup.TransferredGraphEntangled
import com.loosecannon.servicetag.core.backup.TransferredOutInArchive
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.ports.AttachmentStorage
import com.loosecannon.servicetag.core.ports.BackupIO
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.StoreIoException
import com.loosecannon.servicetag.core.ports.TransferRecordRepository
import com.loosecannon.servicetag.core.usecase.ArtifactsReport
import com.loosecannon.servicetag.core.usecase.ExportBackupSet
import com.loosecannon.servicetag.core.usecase.ImportBackupReplace
import com.loosecannon.servicetag.core.usecase.ImportReport
import com.loosecannon.servicetag.core.usecase.RestoreArtifacts
import com.loosecannon.servicetag.core.usecase.StoreIsEmpty
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.l10n.localized
import com.loosecannon.servicetag.l10n.localizedList
import com.loosecannon.servicetag.l10n.localizedPlural
import com.loosecannon.servicetag.prefs.AppPrefs
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A destination for one export: two named files, and a way to take one back. */
interface BackupSetSink {
    /** Returns a handle the caller can pass to [delete] — in the app, the document's URI. */
    suspend fun write(name: String, body: suspend (OutputStream) -> Unit): String

    /**
     * Takes one written file back. **True only if it is really gone.**
     *
     * A provider is free to refuse a delete, and when it does, a failed export has left a
     * complete, importable data archive in the owner's folder. Saying so is the difference
     * between "Nothing was saved" and a file they can be told to remove by hand.
     */
    suspend fun delete(handle: String): Boolean
}

/**
 * The export refused before it wrote anything, because rows name bytes and no folder is chosen.
 * Its message is the one Settings' own wording, so the owner is told what to do rather than how
 * many files the archive could not find. #102: the message is that sentence in the current
 * language, read whenever it is asked for, because it is shown to the owner as it is.
 */
class NoAttachmentFolder : Exception() {
    override val message: String get() = localized(R.string.backup_no_attachment_folder)
}

/**
 * A failed export the sink would not fully take back. [leftBehind] names the documents still in
 * the folder — names, not URIs, because the owner has to find them in a file manager.
 */
class ExportLeftFilesBehind(
    val leftBehind: List<String>,
    override val cause: Throwable,
) : Exception(cause.message, cause)

/** When the last export landed, whether one is in flight, and which set was restored here. */
data class BackupState(
    val lastBackupAt: Long? = null,
    val busy: Boolean = false,
    /** The set id of the last data archive restored here, which the files archive must match. */
    val lastRestoredBackupSetId: String? = null,
)

/**
 * The backup *set* and the two-step restore, over ports the caller chose — in the app a folder and
 * SAF documents, in a test a map of byte arrays. Nothing here knows what a `ContentResolver` is,
 * and the one rule that matters can therefore be tested at all:
 *
 * the preferences are marked **only after** both files have landed and the second one covers the
 * plan. A backup nudge is a promise that a *restorable set* exists; a data archive with no
 * artifacts archive beside it is not one, so a failed export moves nothing.
 */
class BackupViewModel(
    private val exportBackupSet: ExportBackupSet,
    private val importBackupReplace: ImportBackupReplace,
    private val restoreArtifacts: RestoreArtifacts,
    private val storeIsEmpty: StoreIsEmpty,
    private val storage: AttachmentStorage,
    private val prefs: AppPrefs,
    private val clock: Clock,
    /** #77 (P77-68): the transfer records, which name a refused asset by the name it left with. */
    private val transfers: TransferRecordRepository,
) : ViewModel() {

    constructor(graph: AppGraph) : this(
        graph.exportBackupSet, graph.importBackupReplace, graph.restoreArtifacts,
        graph.storeIsEmpty, graph.attachmentStorage, graph.prefs, graph.clock, graph.transferRecords,
    )

    private val _state = MutableStateFlow(
        BackupState(
            lastBackupAt = prefs.lastBackupAt,
            lastRestoredBackupSetId = prefs.lastRestoredBackupSetId,
        ),
    )
    val state: StateFlow<BackupState> = _state.asStateFlow()

    /**
     * One line per finished operation. No replay and a buffer of one: the screen that started the
     * work is told how it went, and a screen that arrives later is not told again.
     */
    private val _messages = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /**
     * Writes both archives and marks the export **only when both landed** (spec §7.3): the nudge
     * is a promise that a restorable set exists, and a data file with no artifacts beside it is
     * not one. A failed artifacts write deletes the data file this export already wrote.
     *
     * Completeness rule (owner's ruling, spec §7.3): the set is a backup only if every managed
     * row's bytes landed in the artifacts archive with the planned size. A missing or drifted
     * file fails the whole export, both files are removed, and `lastBackupAt` does not move.
     *
     * One refusal comes *before* the first write: managed rows with no attachment folder to read
     * them from is not a damaged backup, it is a phone that has not been set up, and calling it
     * "N attachment files are missing" would send the owner looking for files instead of for the
     * Settings screen. The store is resolved once here and reused for every entry, rather than
     * being asked for per byte-source.
     *
     * Everything runs on [Dispatchers.IO]: `BackupCodec.encode` and the artifacts write are a zip
     * and a hash over every attachment, which is not the main thread's work even for one tap.
     */
    suspend fun exportSet(sink: BackupSetSink): Result<String> = runCatching {
        withContext(Dispatchers.IO) {
            val set = exportBackupSet.run()
            val store = storage.store()
            if (store == null && set.plan.entries.isNotEmpty()) throw NoAttachmentFolder()
            val stamp = BackupSetNames.stamp(set.plan.createdAt)
            val dataName = BackupSetNames.data(stamp)
            val artifactsName = BackupSetNames.artifacts(stamp)
            val dataHandle = sink.write(dataName) { out -> out.write(set.data) }
            var artifactsHandle: String? = null
            try {
                lateinit var written: ArtifactsWritten
                artifactsHandle = sink.write(artifactsName) { out ->
                    // With zero attachments this is a manifest-only archive: a set is always two
                    // files, and `store` is allowed to be null only because there is nothing to
                    // open.
                    written = ArtifactsCodec.write(out, set.plan) { locator -> store?.open(locator) }
                }
                if (!written.covers(set.plan)) {
                    throw BackupSetIncomplete(written.missing, written.mismatched)
                }
            } catch (t: Throwable) {
                // [NonCancellable] because the failure being handled is often a cancellation, and a
                // sink's `delete` suspends: on an already-cancelled coroutine it would throw before
                // doing anything and leave the data archive behind — the one thing the invariant
                // forbids. Each delete stands alone, so one that fails cannot stop the other.
                //
                // The SAF sink already removed a partial artifacts document; deleting a handle it
                // never returned cannot happen, and deleting one it did is harmless.
                val leftBehind = mutableListOf<String>()
                withContext(NonCancellable) {
                    artifactsHandle?.let { handle ->
                        if (!sink.deleted(handle)) leftBehind += artifactsName
                    }
                    if (!sink.deleted(dataHandle)) leftBehind += dataName
                }
                // Past the data write, the owner's news is the same whatever failed down here: the
                // second half did not happen and the first half is gone again. The completeness
                // ruling keeps its own wording, and a cancellation is not a failure at all.
                val failure = when (t) {
                    is BackupSetIncomplete, is CancellationException, is ArtifactsWriteFailed -> t
                    else -> ArtifactsWriteFailed("the files archive could not be written", t) // l10n-ok: exception message
                }
                // ...unless "the first half is gone again" is not true. A cancellation still
                // travels as itself: nothing is reported for an operation nobody is waiting on.
                throw if (leftBehind.isEmpty() || failure is CancellationException) {
                    failure
                } else {
                    ExportLeftFilesBehind(leftBehind, failure)
                }
            }
            prefs.markBackupExported(clock.nowMillis())
            _state.update { it.copy(lastBackupAt = prefs.lastBackupAt) }
            dataName + " + " + artifactsName
        }
    }.rethrowCancellation()

    /** Wipes and loads the data archive, and remembers the set id the files step must match. */
    suspend fun restoreData(io: BackupIO): Result<ImportReport> = runCatching {
        withContext(Dispatchers.IO) {
            val report = importBackupReplace.run(io.read())
            prefs.lastRestoredBackupSetId = report.lastRestoredBackupSetId
            _state.update { it.copy(lastRestoredBackupSetId = prefs.lastRestoredBackupSetId) }
            report
        }
    }.rethrowCancellation()

    /**
     * Adds the bytes the data archive only listed. It deletes nothing, so there is no dialog —
     * and, since `RestoreArtifacts` leaves bytes that already match where they are, running it
     * twice is harmless.
     */
    suspend fun restoreFiles(io: BackupIO): Result<ArtifactsReport> = runCatching {
        withContext(Dispatchers.IO) {
            io.openStream().use { restoreArtifacts.run(it, prefs.lastRestoredBackupSetId) }
        }
    }.rethrowCancellation()

    /**
     * Whether this phone holds any records at all (#40) — what the restore confirmation turns on.
     *
     * Deliberately not called `storeIsEmpty()`: that is the name of the use case this delegates to,
     * and a property and a function sharing a name would read as one thing. Deliberately a suspend
     * function rather than a state flow: it is asked at most once per visit to the screen, and the
     * answer that matters is the one true at the moment a file was picked. No `withContext` either
     * — Room already runs these five reads on the graph's own query context, which is what lets the
     * JVM fixture settle them on its shared test scheduler.
     */
    suspend fun isStoreEmpty(): Boolean = storeIsEmpty.run()

    /** What the screen calls once the owner has picked a folder. */
    fun exportSetTo(sink: BackupSetSink) = once {
        exportSet(sink).fold({ localized(R.string.backup_exported_as, it) }, ::exportReason)
    }

    fun restoreDataFrom(io: BackupIO) = once {
        restoreData(io).fold(::restoredLine) { error ->
            if (error is TransferredOutInArchive) transferredOutLine(error) else restoreReason(error)
        }
    }

    fun restoreFilesFrom(io: BackupIO) = once {
        restoreFiles(io).fold(::restoredFilesLine, ::filesReason)
    }

    /**
     * Runs [block] in `viewModelScope`, not in the composition's scope: a rotation halfway through
     * an export must not abandon a half-written document. The guard is set before the first
     * suspension, so two taps inside one frame start one operation, not two.
     *
     * On [Dispatchers.IO], because the work is zips and digests over every attachment — and
     * `ZipInputStream.getNextEntry` inflates even the entries a restore decides to skip, which on
     * a large archive is an ANR on the main thread. The three suspend entry points move themselves
     * as well, so a direct caller (the debug harness, the instrumented suite) gets it too. State
     * and message publication are thread-safe already: a `MutableStateFlow.update` and a
     * `tryEmit`, both callable from anywhere.
     */
    private fun once(block: suspend () -> String) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val message = block()
            _state.update { it.copy(busy = false) }
            _messages.tryEmit(message)
        }
    }

    /**
     * The counts, and — when the file listed attachments — what is still to do. A data-only
     * restore leaves every attachment row saying it is not on this device; that is a first-class
     * outcome rather than an error, so it is said plainly instead of being hidden.
     */
    private fun restoredLine(report: ImportReport): String {
        val assets = localizedPlural(R.plurals.backup_restored_assets, report.assets, report.assets)
        val tags = localizedPlural(R.plurals.backup_restored_tags, report.tags, report.tags)
        val links = localizedPlural(R.plurals.backup_restored_links, report.links, report.links)
        return if (report.attachments > 0) {
            val listed = localizedPlural(R.plurals.backup_restored_attachments_listed, report.attachments, report.attachments)
            localized(R.string.backup_restored_data_attachments, assets, tags, links, listed)
        } else {
            localized(R.string.backup_restored_data, assets, tags, links)
        }
    }

    /**
     * Restored and skipped, then the rows whose bytes were already here and right — a second
     * restore of the same set says that rather than claiming it did nothing — and then the
     * archive's own damage if it had any: entries the manifest promised and the file did not carry
     * (those rows are still without bytes), and bytes the manifest never named. Reporting only the
     * first two numbers would call a damaged archive a clean restore.
     */
    private fun restoredFilesLine(report: ArtifactsReport): String = listOfNotNull(
        localizedPlural(R.plurals.backup_restored_files, report.restored, report.restored, report.skipped),
        report.alreadyPresent.takeIf { it > 0 }?.let { n ->
            localizedPlural(R.plurals.backup_files_already_present, n, n)
        },
        report.missingEntries.size.takeIf { it > 0 }?.let { n ->
            localizedPlural(R.plurals.backup_files_missing_entries, n, n)
        },
        report.unexpectedEntries.size.takeIf { it > 0 }?.let { n ->
            localizedPlural(R.plurals.backup_files_unexpected_entries, n, n)
        },
    ).joinToString(localized(R.string.backup_report_separator))

    private fun exportReason(error: Throwable): String = when (error) {
        // The one case where "Nothing was saved" would be a lie: say what is still there, and
        // say it by the name the owner will see in their file manager.
        is ExportLeftFilesBehind -> localizedPlural(
            R.plurals.backup_left_behind,
            error.leftBehind.size,
            exportFailure(error.cause).trimEnd('.'),
            localizedList(error.leftBehind),
        )
        // These two say what to do, or what is missing, and nothing more.
        is NoAttachmentFolder, is BackupSetIncomplete -> exportFailure(error)
        else -> localized(R.string.backup_nothing_saved, exportFailure(error))
    }

    /**
     * Why an export failed, without the closing "Nothing was saved." — which [exportReason] adds, or replaces with
     * the files left behind.
     */
    private fun exportFailure(error: Throwable): String = when (error) {
        is NoAttachmentFolder -> error.message
        // #77 (P77-58): a record here still names a transferred asset's graph; refused before any byte.
        is TransferredGraphEntangled -> localized(R.string.backup_export_failed_entangled)
        is BackupSetIncomplete -> localized(R.string.backup_not_saved, error.wording())
        is ArtifactsWriteFailed -> localized(R.string.backup_export_failed_files_archive)
        else -> localized(R.string.backup_export_failed, reason(error))
    }

    private fun filesReason(error: Throwable): String = when (error) {
        is ArtifactsSetMismatch ->
            localized(R.string.backup_set_mismatch, error.found.take(SHORT_ID), error.expected.take(SHORT_ID))
        is StoreIoException -> localized(R.string.backup_no_attachment_folder)
        else -> restoreReason(error)
    }

    /**
     * #77 (P77-68, R77-13): the Replace restore refused a backup that still holds an asset this phone transferred out.
     * One asset is named as it left (its OUT record's name); several are counted.
     */
    private suspend fun transferredOutLine(error: TransferredOutInArchive): String {
        val ids = error.assetIds.distinct()
        if (ids.size != 1) {
            return localizedPlural(R.plurals.backup_restore_transferred_out_many, ids.size, ids.size)
        }
        val name = transfers.forAsset(ids.single())
            .filter { it.kind == TransferKind.OUT }
            .maxWithOrNull(compareBy({ it.at }, { it.id }))
            ?.nameSnapshot
            ?: ids.single().value
        return localized(R.string.backup_restore_transferred_out_one, name)
    }

    /** The export side's lead-in, on the restore side: a bare exception message is not news. */
    private fun restoreReason(error: Throwable): String = localized(R.string.backup_restore_failed, reason(error))

    private fun reason(error: Throwable): String = error.message ?: error.javaClass.simpleName

    private companion object {
        /** Enough of a set id to tell two apart by eye, and short enough to read in a snackbar. */
        const val SHORT_ID = 8
    }
}

/** `delete`, with a provider that throws counted exactly like one that answers no. */
private suspend fun BackupSetSink.deleted(handle: String): Boolean =
    runCatching { delete(handle) }.getOrDefault(false)

/**
 * "1 attachment file is missing", "2 attachment files have changed since they were added".
 *
 * The fallback is for the count-and-total half of `covers`: a write can fall short of the plan
 * with both lists empty, and "Backup not saved: " with nothing after it is not a sentence.
 */
internal fun BackupSetIncomplete.wording(): String {
    val missingLine = missing.size.takeIf { it > 0 }?.let { n ->
        localizedPlural(R.plurals.backup_incomplete_missing, n, n)
    }
    val changedLine = mismatched.size.takeIf { it > 0 }?.let { n ->
        localizedPlural(R.plurals.backup_incomplete_changed, n, n)
    }
    return if (missingLine != null && changedLine != null) {
        localized(R.string.backup_incomplete_both, missingLine, changedLine)
    } else {
        missingLine ?: changedLine ?: localized(R.string.backup_incomplete_not_covered)
    }
}

/**
 * `runCatching` catches everything, including the cancellation a cleared ViewModel throws at its
 * own coroutines. Being cancelled is not a backup that failed — nobody should be told "export
 * failed: Job was cancelled" — so it goes back out the way it came.
 */
private fun <T> Result<T>.rethrowCancellation(): Result<T> =
    onFailure { if (it is CancellationException) throw it }
