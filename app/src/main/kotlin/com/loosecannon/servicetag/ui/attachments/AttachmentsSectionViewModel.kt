package com.loosecannon.servicetag.ui.attachments

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.servicetag.attachments.Thumbnails
import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.model.AttachmentId
import com.loosecannon.servicetag.core.model.AttachmentKind
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.AttachmentProblem
import com.loosecannon.servicetag.core.model.isImage
import com.loosecannon.servicetag.core.ports.AttachmentRepository
import com.loosecannon.servicetag.core.ports.AttachmentStorage
import com.loosecannon.servicetag.core.ports.ByteSource
import com.loosecannon.servicetag.core.ports.StoreState
import com.loosecannon.servicetag.core.usecase.AddAttachment
import com.loosecannon.servicetag.core.usecase.AddAttachmentCommand
import com.loosecannon.servicetag.core.usecase.AttachmentResult
import com.loosecannon.servicetag.core.usecase.DeleteAttachment
import com.loosecannon.servicetag.core.usecase.UpdateAttachment
import com.loosecannon.servicetag.core.usecase.UpdateAttachmentCommand
import com.loosecannon.servicetag.di.AppGraph
import java.io.File
import java.io.InputStream
import java.time.LocalDate
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How long the repository flow stays hot after the last collector leaves (a rotation, typically). */
private const val SUBSCRIPTION_GRACE_MS = 5_000L

/** "Adding 3 of 8…" — the line the section shows while a multi-select lands (spec §8.1). */
internal fun addingProgressLine(index: Int, total: Int): String = "Adding $index of $total…"

/** One picked or captured file, as the section hands it to the use case. */
data class PickedFile(
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long?,
    val fromCamera: Boolean = false,
    val open: () -> InputStream,
)

/** One DOCUMENTS row. `present` is false when the bytes are not on this device (spec §7.2). */
data class AttachmentRowState(
    val id: String,
    val displayName: String,
    val kind: AttachmentKind,
    val sizeBytes: Long,
    val capturedOn: String?,
    val notes: String,
    val mimeType: String,
    val locator: String,
    val isImage: Boolean,
    val present: Boolean,
    val thumbnail: File? = null,
)

data class AttachmentsSectionState(
    val store: StoreState = StoreState.NotConfigured,
    val rows: List<AttachmentRowState> = emptyList(),
    /** "Adding 3 of 8…" while a multi-select runs; null otherwise (spec §8.1). */
    val progress: String? = null,
)

/**
 * The one ViewModel behind DOCUMENTS, keyed by its [owner], so asset detail and event detail draw
 * the same section from the same code (spec §8.1).
 *
 * `storage` is the port rather than `SafAttachmentStorage` so a JVM test can hand in the in-memory
 * twin; the one thing only the SAF implementation can answer — the `content://` URI for
 * `ACTION_VIEW` — comes in as [viewUris], which keeps `android.net.Uri` off the test path.
 */
class AttachmentsSectionViewModel(
    private val owner: AttachmentOwner,
    attachments: AttachmentRepository,
    private val storage: AttachmentStorage,
    private val addAttachment: AddAttachment,
    private val updateAttachment: UpdateAttachment,
    private val deleteAttachment: DeleteAttachment,
    private val thumbnails: Thumbnails,
    private val viewUris: (String) -> Uri? = { null },
    /** Today, as the ISO date a picked file's `capturedOn` defaults to (spec §8.1). */
    private val today: () -> String = { LocalDate.now().toString() },
    /**
     * Where the scan, the writes and the folder re-read run: `Dispatchers.IO` in the app, and the
     * test's own scheduler in a JVM test, so none of that work outlives the test that started it.
     */
    private val io: CoroutineContext = Dispatchers.IO,
) : ViewModel() {

    constructor(graph: AppGraph, owner: AttachmentOwner) : this(
        owner, graph.attachments, graph.attachmentStorage, graph.addAttachment,
        graph.updateAttachment, graph.deleteAttachment, graph.thumbnails,
        viewUris = graph.attachmentStorage::viewUri,
    )

    /** Already ordered by display name, collated case-insensitively, by the query itself. */
    private val rows: Flow<List<Attachment>> = attachments.observeForOwner(owner)

    /** Filled by [scan], off the main thread; a row not in here yet is assumed to be there. */
    private val presence = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    private val thumbs = MutableStateFlow<Map<String, File>>(emptyMap())
    private val progress = MutableStateFlow<String?>(null)

    /**
     * Bumped after every write, because a refusal is itself news about the folder: an add that
     * came back `StoreUnavailable` must flip the section to its status block without waiting for
     * a row to change, and a folder chosen in Settings meanwhile is re-read the same way.
     */
    private val refresh = MutableStateFlow(0)

    /**
     * Re-read by [scan], once per refresh rather than once per row: resolving the tree and asking
     * whether it is writable are provider calls, so they have no business in the mapping below.
     * The seed is the only one read on the caller's thread, and only once per ViewModel — the
     * alternative is a status block flashing over a folder that was there all along.
     */
    private val storeState = MutableStateFlow(storage.state())

    /**
     * The same values, plus a fresh read whenever something starts collecting. Choosing a folder
     * in Settings changes neither the rows nor anything this ViewModel wrote, and the ViewModel
     * itself survives the push, so a resubscription is one of the two moments the section can
     * learn that the person did what the status block asked ([refreshStore] is the other).
     */
    private val storeReads: Flow<StoreState> = storeState
        .onStart { withContext(io) { storeState.value = storage.state() } }

    /** Pure mapping: everything expensive has already happened by the time a value gets here. */
    val state: StateFlow<AttachmentsSectionState> =
        combine(rows, presence, thumbs, progress, storeReads) { attachmentRows, present, thumbnails, line, store ->
            AttachmentsSectionState(
                store = store,
                rows = attachmentRows.map { row(it, present, thumbnails) },
                progress = line,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS),
            AttachmentsSectionState(store = storeState.value),
        )

    /** One line per finished operation or refusal, shown once (the 1C snackbar pattern). */
    private val _messages = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /**
     * The id of an attachment whose edit sheet may close: the save landed, or it changed nothing.
     * A refusal is deliberately absent — the sheet stays open holding what the person typed, so
     * the line the snackbar just showed is something they can act on.
     *
     * Room for two: a double tap on Save puts two saves in flight, and the second one's signal
     * must not be dropped because the screen has not yet taken the first.
     */
    private val _saved = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 2)
    val saved: SharedFlow<String> = _saved.asSharedFlow()

    /** The id of an attachment that is gone — row and bytes. A failed delete emits nothing. */
    private val _deleted = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val deleted: SharedFlow<String> = _deleted.asSharedFlow()

    init {
        // `collectLatest` drops a pass whose row list is already stale, so a delete or a rename
        // mid-scan restarts the checks rather than finishing the old ones.
        // The whole pass lives on [io] (`Dispatchers.IO` in the app): nothing in it touches the
        // UI, and a job that runs for the life of the screen should not be bouncing off the main
        // thread to do it.
        viewModelScope.launch(io) {
            try {
                combine(rows, refresh) { attachmentRows, _ -> attachmentRows }
                    .collectLatest { guardedScan(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // The row flow itself died (a database closed under a screen that is going away,
                // a provider that broke mid-query). A background check must never take the app
                // down or escape to the uncaught handler; the rows the combine already holds
                // stay on screen, and the next refresh starts a fresh pass.
                if (isActive) _messages.tryEmit(SCAN_FAILED)
            }
        }
    }

    /** One pass that fails leaves the rows it did not reach on their glyphs and the collector alive. */
    private suspend fun guardedScan(attachmentRows: List<Attachment>) {
        try {
            scan(attachmentRows)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            _messages.tryEmit(SCAN_FAILED)
        }
    }

    /**
     * Sequential, so a failure names its file and the rest still land (spec §8.1).
     *
     * On [io] (`Dispatchers.IO` in the app), like [save] and [delete]: the first thing every one
     * of these use cases does is ask the storage whether there is a folder, which is a handful of
     * provider round trips before any of them reaches a suspension point of its own.
     */
    fun add(files: List<PickedFile>) {
        if (files.isEmpty()) return
        viewModelScope.launch(io) {
            val capturedOn = today()
            files.forEachIndexed { index, file ->
                // One file on its own is not a batch: nothing to count, so nothing to say.
                progress.value =
                    if (files.size > 1) addingProgressLine(index + 1, files.size) else null
                addOne(file, capturedOn)
            }
            progress.value = null
            refresh.value++
        }
    }

    fun save(id: String, cmd: UpdateAttachmentCommand) {
        viewModelScope.launch(io) {
            val outcome = try {
                updateAttachment.run(AttachmentId(id), cmd)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // A database that would not take the write. The sheet stays open with the values.
                _messages.tryEmit("Could not save ${cmd.displayName}")
                refresh.value++
                return@launch
            }
            when (outcome) {
                is AttachmentResult.Ok -> _saved.tryEmit(id)
                // Nothing to write is not a failure: the sheet closes without claiming a save.
                is AttachmentResult.Refused -> {
                    if (outcome.problem == AttachmentProblem.Unchanged) _saved.tryEmit(id)
                    say(outcome.problem)
                }
            }
            refresh.value++
        }
    }

    fun delete(id: String) {
        viewModelScope.launch(io) {
            try {
                deleteAttachment.run(AttachmentId(id))
                _deleted.tryEmit(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // The row write failed, or the store would not give the bytes up. Either way the
                // person asked for one thing and it did not happen, so they hear about it.
                _messages.tryEmit("Could not delete that file")
            }
            refresh.value++
        }
    }

    /**
     * Re-read the folder. The section calls this whenever it enters composition, which is how
     * coming back from Settings with a folder chosen flips it over (spec §8.1).
     */
    fun refreshStore() {
        refresh.value++
    }

    /** Null when the bytes are not on this device; the caller shows the snackbar. */
    fun viewUri(locator: String): Uri? = viewUris(locator)

    private suspend fun addOne(file: PickedFile, capturedOn: String) {
        val outcome = try {
            addAttachment.run(
                owner,
                AddAttachmentCommand(
                    displayName = file.displayName,
                    mimeType = file.mimeType,
                    sizeBytes = file.sizeBytes,
                    capturedOn = capturedOn,
                    fromCamera = file.fromCamera,
                ),
                ByteSource { file.open() },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // A broken provider or a folder that went away mid-copy. Name the file the person
            // picked — they chose eight, and "something failed" would not tell them which.
            _messages.tryEmit("Could not add ${file.displayName}")
            return
        }
        if (outcome is AttachmentResult.Refused) say(outcome.problem)
    }

    /**
     * The store state, then existence, then thumbnails: on [io] (`Dispatchers.IO` in the app) and
     * one row at a time (spec §8.2). The store is resolved once for the whole pass — a `state()`
     * per row would be a binder call per row.
     */
    private suspend fun scan(attachmentRows: List<Attachment>) {
        storeState.value = storage.state()
        val live = attachmentRows.map { it.id.value }.toSet()
        presence.update { it.filterKeys { id -> id in live } }
        thumbs.update { it.filterKeys { id -> id in live } }

        val store = storage.store()
        attachmentRows.forEach { attachment ->
            val there = store != null && store.exists(attachment.storageLocator)
            presence.update { it + (attachment.id.value to there) }
            if (there && attachment.isImage && thumbs.value[attachment.id.value] == null) {
                thumbnail(attachment)?.let { file ->
                    thumbs.update { it + (attachment.id.value to file) }
                }
            }
        }
    }

    /** A decode that cannot run leaves the row on its kind glyph and the pass alive (spec §8.2). */
    private suspend fun thumbnail(attachment: Attachment): File? = try {
        thumbnails.thumbnail(attachment)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        null
    }

    private fun row(
        attachment: Attachment,
        present: Map<String, Boolean>,
        thumbnails: Map<String, File>,
    ) = AttachmentRowState(
        id = attachment.id.value,
        displayName = attachment.displayName,
        kind = attachment.kind,
        sizeBytes = attachment.sizeBytes,
        capturedOn = attachment.capturedOn,
        notes = attachment.notes,
        mimeType = attachment.mimeType,
        locator = attachment.storageLocator,
        isImage = attachment.isImage,
        // Assumed present until [scan] says otherwise: a row's bytes are there far more often
        // than not, and dimming every row for the first frame of every visit would be a lie.
        present = present[attachment.id.value] ?: true,
        thumbnail = thumbnails[attachment.id.value],
    )

    /** One line per refusal. `Unchanged` is silent: the sheet simply closes (spec §8.1). */
    private fun say(problem: AttachmentProblem) {
        val line = when (problem) {
            AttachmentProblem.BlankName -> "Give the file a name"
            AttachmentProblem.NoStore -> "Choose an attachment folder in Settings first"
            AttachmentProblem.StoreUnavailable -> "The attachment folder is not available"
            is AttachmentProblem.TooLarge -> "That file is larger than 256 MB"
            // `UpdateAttachment` reports a vanished *attachment* row as `OwnerMissing` too, so the
            // wording is about the file: the person never named an owner.
            AttachmentProblem.OwnerMissing -> "That file is no longer here"
            AttachmentProblem.Unchanged -> return
        }
        _messages.tryEmit(line)
    }
}

/** Said once per failed presence/thumbnail pass; the rows stay, only their checks are unknown. */
internal const val SCAN_FAILED = "Could not check the attachment folder"
