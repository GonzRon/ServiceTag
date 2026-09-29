package com.loosecannon.servicetag.ui.transfer

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.servicetag.core.backup.BackupSetIncomplete
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.GroupRepository
import com.loosecannon.servicetag.core.transfer.TransferPack
import com.loosecannon.servicetag.core.transfer.TransferRefusal
import com.loosecannon.servicetag.core.usecase.CreateTransferPack
import com.loosecannon.servicetag.core.usecase.CreateTransferPackResult
import com.loosecannon.servicetag.core.usecase.CreatedPack
import com.loosecannon.servicetag.core.usecase.MarkTransferredOut
import com.loosecannon.servicetag.core.usecase.MarkTransferredOutResult
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.transfer.TransferPackWriter
import com.loosecannon.servicetag.ui.attachments.asFileSize
import com.loosecannon.servicetag.ui.backup.NoAttachmentFolder
import com.loosecannon.servicetag.ui.backup.wording
import com.loosecannon.servicetag.ui.maintenance.ReminderReconcile
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.time.ZoneId
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The ready screen's pack as `SavedStateHandle` keeps it (C18): the [CreatedPack] marking takes, and the file's name
 * and size. A restored screen re-reads its file against [packSha256] before it offers anything (C22, MJ-7).
 */
@Serializable
internal data class ReadyPack(
    val packId: String,
    val packSha256: String,
    val rootIds: List<String>,
    val assetIds: List<String>,
    val lineage: Map<String, List<String>>,
    val contentSha256: String,
    val note: String,
    val fileName: String,
    val bytes: Long,
) {
    fun created(): CreatedPack =
        CreatedPack(packId, packSha256, rootIds.map(::AssetId), assetIds.map(::AssetId), lineage, contentSha256, note)
}

enum class PackPhase { IDLE, CREATING, READY, MARKING, MARKED }

data class TransferPackState(
    val phase: PackPhase = PackPhase.IDLE,
    /** P77-54, the pack's file name, once it exists. */
    val fileName: String? = null,
    /** P77-24's `<asFileSize>`. */
    val size: String? = null,
    /** A restored screen whose file was re-read and still matches its hash; a fresh pack is verified at once. */
    val verified: Boolean = false,
    /** A restored screen whose file is gone or changed (P77-60): Share, Save and Mark are disabled. */
    val gone: Boolean = false,
    /** What stops creation or marking, in the ratified words; empty when nothing does. */
    val errors: List<String> = emptyList(),
) {
    /** Share, Save a copy and Mark transferred are offered only on a ready, verified pack. */
    val offersActions: Boolean get() = phase == PackPhase.READY && verified && !gone

    val sizeLine: String? get() = size?.let(TransferStrings::size)
    val goneLine: String? get() = if (gone) TransferStrings.PACK_GONE else null
}

sealed interface TransferPackEvent {
    /** The mark was written and swept once: the flow ends on the Assets list. */
    data object Marked : TransferPackEvent

    /** `Not now`: nothing written, the flow ends where it began. */
    data object Leave : TransferPackEvent

    /** One line for the snackbar (P77-26, P77-55). */
    data class Say(val line: String) : TransferPackEvent
}

/**
 * #77 (C17's Create, C18, C22; R77-16, R77-18, R77-23, R77-CREATE-SAFETY) — creation, the ready screen and the mark.
 * Create writes the pack into `cache/transfer/` (P77-19 while it runs; P77-20 — the missing-document, entangled or
 * generic reason — P77-57, P77-59 or the reused folder sentence when it cannot);
 * the ready screen offers Share, Save a copy and the mark question, all only after creation and only on a verified
 * file; Mark runs [MarkTransferredOut] (which re-reads, re-selects and re-hashes inside its write, R77-16), then
 * exactly one [ReminderReconcile], and ends the flow; `Not now` writes nothing. **Leaving deletes the pack**
 * ([onCleared]); a restored screen keeps a fresh pack and re-checks it, and offers nothing with P77-60 if it is gone.
 */
class TransferPackViewModel(
    private val createPack: CreateTransferPack,
    private val writer: TransferPackWriter,
    private val markTransferredOut: MarkTransferredOut,
    private val reconcile: ReminderReconcile,
    private val assets: AssetRepository,
    private val groups: GroupRepository,
    private val saved: SavedStateHandle,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val io: CoroutineContext = Dispatchers.IO,
) : ViewModel() {

    constructor(graph: AppGraph, saved: SavedStateHandle) : this(
        graph.createTransferPack, graph.transferPackWriter, graph.markTransferredOut, graph.reminderReconcile,
        graph.assets, graph.groups, saved,
    )

    private var ready: ReadyPack? = saved.get<String>(KEY)?.let { stored ->
        runCatching { JSON.decodeFromString(ReadyPack.serializer(), stored) }.getOrNull()
    }

    private val _state = MutableStateFlow(TransferPackState())
    val state: StateFlow<TransferPackState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<TransferPackEvent>(replay = 0, extraBufferCapacity = 4)
    val events: SharedFlow<TransferPackEvent> = _events.asSharedFlow()

    init {
        ready?.let { pack ->
            // R77-18 (MJ-7): nothing is offered until the file is read back and still hashes to the pack.
            _state.value = TransferPackState(PackPhase.READY, pack.fileName, pack.bytes.asFileSize())
            viewModelScope.launch {
                val intact = withContext(io) { writer.find(pack.fileName)?.let { sha256Of(it) == pack.packSha256 } == true }
                _state.update { it.copy(verified = intact, gone = !intact) }
            }
        }
    }

    /** The sealed file, for the share; null before creation or once it is gone. */
    fun packFile(): File? = ready?.takeIf { _state.value.offersActions }?.let { writer.find(it.fileName) }

    /** Create (P77-14): select, encode and seal. Runs once; `CREATING` is set before the first suspension. */
    fun create(roots: List<AssetId>, note: String) {
        if (_state.value.phase != PackPhase.IDLE || roots.isEmpty() || !TransferPack.noteAccepted(note)) return
        _state.value = TransferPackState(PackPhase.CREATING)
        viewModelScope.launch {
            val made: Made = try {
                withContext(io) { createAndSeal(roots, note) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: NoAttachmentFolder) {
                Made.Refused(listOf(e.message!!))
            } catch (e: BackupSetIncomplete) {
                Made.Refused(listOf(TransferStrings.notCreated(e.wording())))
            } catch (e: Exception) {
                Log.w(TAG, "the Transfer Pack could not be written", e)
                Made.Refused(listOf(TransferStrings.NOT_CREATED))
            }
            when (made) {
                is Made.Ready -> {
                    val pack = made.pack
                    ready = pack
                    saved[KEY] = JSON.encodeToString(ReadyPack.serializer(), pack)
                    _state.value = TransferPackState(PackPhase.READY, pack.fileName, pack.bytes.asFileSize(), verified = true)
                }
                is Made.Refused -> _state.value = TransferPackState(errors = made.lines)
            }
        }
    }

    /** What Create came to: the sealed pack, or the ratified lines saying why there is none. */
    private sealed interface Made {
        data class Ready(val pack: ReadyPack) : Made
        data class Refused(val lines: List<String>) : Made
    }

    private suspend fun createAndSeal(roots: List<AssetId>, note: String): Made =
        when (val result = createPack.run(roots, note)) {
            is CreateTransferPackResult.Refused -> Made.Refused(result.refusals.map { refusalLineOf(it, ::nameOf, ::groupNameOf) })
            is CreateTransferPackResult.TooLarge -> Made.Refused(listOf(TransferStrings.TOO_LARGE))
            // R77-CREATE-SAFETY: creation's own read refuses, before any file exists, a selected asset held here
            // (B4 hand-off 2; an asset can leave between the review and Create) and a pack that could never be marked.
            is CreateTransferPackResult.AlreadyTransferred -> Made.Refused(listOf(TransferStrings.ALREADY_TRANSFERRED))
            is CreateTransferPackResult.Entangled -> Made.Refused(listOf(TransferStrings.NOT_CREATED_ENTANGLED))
            is CreateTransferPackResult.Created -> {
                val draft = result.draft
                val name = TransferStrings.packFileName(draft.createdAt, zone, draft.packId)
                val written = writer.write(draft, name)
                val pack = CreatedPack.of(draft, written.packSha256)
                Made.Ready(ReadyPack(
                    packId = pack.packId,
                    packSha256 = pack.packSha256,
                    rootIds = pack.rootIds.map { it.value },
                    assetIds = pack.assetIds.map { it.value },
                    lineage = pack.lineage,
                    contentSha256 = pack.contentSha256,
                    note = pack.note,
                    fileName = name,
                    bytes = written.bytes,
                ))
            }
        }

    /**
     * P77-28: marks the pack's assets transferred out (C8), then sweeps the reminders once (R77-23) and ends the flow.
     * Only after creation, only on a verified pack; every refusal writes nothing and sweeps nothing.
     */
    fun mark() {
        val pack = ready ?: return
        if (!_state.value.offersActions) return
        _state.update { it.copy(phase = PackPhase.MARKING, errors = emptyList()) }
        viewModelScope.launch {
            val result = try {
                withContext(io) { markTransferredOut.run(pack.created()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "the mark failed", e)
                null
            }
            val refusal = when (result) {
                is MarkTransferredOutResult.Marked -> null
                is MarkTransferredOutResult.AlreadyTransferred -> TransferStrings.ALREADY_TRANSFERRED
                is MarkTransferredOutResult.PackOutdated -> TransferStrings.changedSince(nameOf(result.assetId))
                is MarkTransferredOutResult.OpenLoan -> TransferStrings.lentOut(nameOf(result.assetId))
                is MarkTransferredOutResult.Entangled -> TransferStrings.MARK_ENTANGLED
                null -> TransferStrings.COULD_NOT_MARK
            }
            if (refusal != null) {
                _state.update { it.copy(phase = PackPhase.READY, errors = listOf(refusal)) }
                return@launch
            }
            // R77-23: once, after the successful write. A sweep that fails is the backstop's to repeat.
            try {
                reconcile.run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "the sweep after marking failed", e)
            }
            _state.update { it.copy(phase = PackPhase.MARKED) }
            _events.tryEmit(TransferPackEvent.Marked)
        }
    }

    /** mn-2: a refused Create's lines belong to the review they answered; a changed selection clears them. */
    fun clearErrors() {
        _state.update { if (it.phase == PackPhase.IDLE) it.copy(errors = emptyList()) else it }
    }

    /** The reused `Not now`: nothing is written; the flow ends and leaving deletes the pack. */
    fun notNow() {
        _events.tryEmit(TransferPackEvent.Leave)
    }

    /** Save a copy (P77-23): every byte of the pack into the document [open] answers; P77-26, or P77-55. */
    fun saveCopy(open: suspend () -> OutputStream?) {
        val pack = ready ?: return
        if (!_state.value.offersActions) return
        viewModelScope.launch {
            val saved = try {
                withContext(io) {
                    val file = writer.find(pack.fileName) ?: return@withContext false
                    val sink = open() ?: return@withContext false
                    sink.use { out -> file.inputStream().use { it.copyTo(out) } }
                    true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "the copy could not be saved", e)
                false
            }
            _events.tryEmit(TransferPackEvent.Say(if (saved) TransferStrings.SAVED else TransferStrings.COULD_NOT_SAVE))
        }
    }

    /** R77-18: leaving the ready screen deletes the working pack. */
    override fun onCleared() {
        ready?.let { writer.delete(it.fileName) }
        super.onCleared()
    }

    private suspend fun nameOf(id: AssetId): String = assets.get(id)?.name ?: id.value

    private suspend fun groupNameOf(id: GroupId): String = groups.get(id)?.name ?: id.value

    private companion object {
        const val TAG = "TransferPack"
        const val KEY = "transfer.ready"

        val JSON = Json { ignoreUnknownKeys = true }

        fun sha256Of(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/** P77-15…18 for one refusal, the assets and groups named as this phone knows them. */
internal suspend fun refusalLineOf(
    refusal: TransferRefusal,
    nameOf: suspend (AssetId) -> String,
    groupNameOf: suspend (GroupId) -> String,
): String = when (refusal) {
    is TransferRefusal.MixedGroup ->
        TransferStrings.mixedGroup(groupNameOf(GroupId(refusal.groupId)), refusal.stayingIds.map { nameOf(it) }.joinToString(", "))
    is TransferRefusal.ParentNotSelected -> TransferStrings.parentNotSelected(nameOf(refusal.childId), nameOf(refusal.parentId))
    is TransferRefusal.OpenLoan -> TransferStrings.lentOut(nameOf(refusal.assetId))
    is TransferRefusal.OutsideReference -> TransferStrings.outsideReference(nameOf(refusal.assetId))
}
