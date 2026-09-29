package com.loosecannon.servicetag.ui.transfer.`import`

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.servicetag.core.usecase.ImportTransferPack
import com.loosecannon.servicetag.core.usecase.TransferImportOutcome
import com.loosecannon.servicetag.core.usecase.TransferImportPreview
import com.loosecannon.servicetag.core.usecase.TransferImportResult
import com.loosecannon.servicetag.core.transfer.TransferPackManifest
import com.loosecannon.servicetag.ui.maintenance.ReminderReconcile
import java.io.File
import java.io.FileInputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which door opened the import (C16, R77-2): the Backup screen's button, or a share into ServiceTag. */
enum class TransferDoor { BACKUP, SHARE }

/** Where the import screen is (C16). */
enum class TransferImportPhase { READING, PREVIEW, IMPORTING, DONE, REFUSED }

/**
 * The one state the import screen draws. Every line is a ratified sentence, already composed; the screen adds none.
 * [refusal] is a reader refusal, the door's folder sentence or P77-52; [done] is P77-50.
 */
data class TransferImportState(
    val phase: TransferImportPhase = TransferImportPhase.READING,
    /** P77-39, or null for a pack with no note. */
    val note: String? = null,
    /** P77-40. */
    val created: String? = null,
    /** P77-6…11 under P77-41. */
    val contains: List<String> = emptyList(),
    /** P77-66, one per returning asset. */
    val comingBack: List<String> = emptyList(),
    /** P77-46, one per duplicate candidate. */
    val duplicates: List<String> = emptyList(),
    /** P77-43; or P77-44 and a P77-45 per bound tag; or a P77-67 per asset not brought back. */
    val outcome: List<String> = emptyList(),
    val importEnabled: Boolean = false,
    val refusal: String? = null,
    val done: String? = null,
    /** Cancel, or the share host's Close: the screen leaves. */
    val finished: Boolean = false,
) {
    val importing: Boolean get() = phase == TransferImportPhase.IMPORTING
}

/**
 * #77 (C14, C16; R77-2, R77-14) — the Transfer Pack import screen's state machine, for both doors. It reads the copy
 * in `cache/transfer-in/` ([TransferPackInbox]) and never a `Uri`: reading → preview → Import → P77-50, or a refusal.
 * **Cancel writes nothing**, **Import runs once** — `importing` is set before the first suspension, so two taps in one
 * frame start one import — and **every end deletes the copy**: a refusal, an import that finished or failed, a
 * preview that offers nothing to import, Cancel, Close, and the screen going away. A successful import, from either
 * door, runs one [ReminderReconcile] after its write (R77-IMPORT-SWEEP).
 *
 * The folder refusal is the door's own sentence: the Backup screen's shipped `NoAttachmentFolder` wording, or the
 * intake's `IntakeStrings.NO_FOLDER` (R77-2).
 */
class TransferImportViewModel(
    private val importPack: ImportTransferPack,
    private val inbox: TransferPackInbox,
    private val copy: File?,
    private val noFolderSentence: String,
    /** R77-IMPORT-SWEEP: the one reminder sweep after a successful import, from either door. */
    private val reconcile: ReminderReconcile,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val io: CoroutineContext = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(TransferImportState())
    val state: StateFlow<TransferImportState> = _state.asStateFlow()

    private var ready: TransferImportPreview.Ready? = null

    init {
        viewModelScope.launch {
            val preview = try {
                withContext(io) {
                    val file = copy ?: throw java.io.FileNotFoundException("no Transfer Pack copy")
                    importPack.preview { FileInputStream(file) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Transfer Pack preview failed", e)
                return@launch refuse(TransferImportStrings.COULD_NOT_IMPORT)
            }
            when (preview) {
                TransferImportPreview.NotAPack -> refuse(TransferImportStrings.NOT_A_PACK)
                TransferImportPreview.NewerPack -> refuse(TransferImportStrings.NEWER)
                is TransferImportPreview.Damaged -> {
                    Log.w(TAG, "Transfer Pack damaged: ${preview.reason}")
                    refuse(TransferImportStrings.DAMAGED)
                }
                TransferImportPreview.NoAttachmentFolder -> refuse(noFolderSentence)
                is TransferImportPreview.Ready -> show(preview)
            }
        }
    }

    private fun show(preview: TransferImportPreview.Ready) {
        ready = preview
        val outcome = when (preview.outcome) {
            TransferImportOutcome.ALREADY_HERE -> listOf(TransferImportStrings.ALREADY_HERE)
            TransferImportOutcome.NOT_BROUGHT_BACK -> preview.refused.map { TransferImportStrings.notBroughtBack(it.name) }
            TransferImportOutcome.CONFLICTS ->
                listOf(TransferImportStrings.CONFLICTS) + preview.tagsUsedHere.map(TransferImportStrings::tagUsedHere)
            TransferImportOutcome.READY -> emptyList()
        }
        _state.update {
            it.copy(
                phase = TransferImportPhase.PREVIEW,
                note = preview.manifest.note.takeIf { note -> note.isNotBlank() }?.let(TransferImportStrings::note),
                created = TransferImportStrings.created(
                    DISPLAY_DATE.format(Instant.ofEpochMilli(preview.manifest.createdAt).atZone(zone).toLocalDate()),
                ),
                contains = countsOf(preview.manifest),
                comingBack = preview.returning.map { asset -> TransferImportStrings.comingBack(asset.name) },
                duplicates = preview.duplicates.map { d -> TransferImportStrings.duplicate(d.incoming, d.local) },
                outcome = outcome,
                importEnabled = preview.importable,
            )
        }
        // Nothing to import: the copy has done its work.
        if (!preview.importable) deleteCopy()
    }

    /** Import — once. The guard is set before the first suspension. */
    fun import() {
        val preview = ready ?: return
        val current = _state.value
        if (current.phase != TransferImportPhase.PREVIEW || !current.importEnabled) return
        _state.update { it.copy(phase = TransferImportPhase.IMPORTING, importEnabled = false) }
        viewModelScope.launch {
            val result = try {
                withContext(io) {
                    val file = copy ?: throw java.io.FileNotFoundException("no Transfer Pack copy")
                    importPack.import(preview) { FileInputStream(file) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TransferImportResult.Failed(e)
            }
            deleteCopy()
            when (result) {
                is TransferImportResult.Imported -> {
                    sweepOnce()
                    _state.update {
                        it.copy(
                            phase = TransferImportPhase.DONE,
                            done = TransferImportStrings.imported(countsOf(result.manifest).joinToString(", ")),
                        )
                    }
                }
                is TransferImportResult.Conflicted -> _state.update {
                    it.copy(phase = TransferImportPhase.PREVIEW, outcome = listOf(TransferImportStrings.CONFLICTS))
                }
                TransferImportResult.NoAttachmentFolder -> refuse(noFolderSentence)
                is TransferImportResult.Failed -> {
                    Log.w(TAG, "Transfer Pack import failed", result.cause)
                    refuse(TransferImportStrings.COULD_NOT_IMPORT)
                }
            }
        }
    }

    /**
     * R77-IMPORT-SWEEP: one reminder sweep after a committed import, while the screen still holds Back. It is a
     * post-write step, never a condition of success: a sweep that fails is logged and the backstop's to repeat.
     */
    private suspend fun sweepOnce() {
        try {
            reconcile.run()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "the sweep after a Transfer Pack import failed", e)
        }
    }

    private var handedOff = false

    /**
     * #84 C3 (77-1, D-3): the Backup door's hand-off. At DONE it answers P77-50 — exactly [TransferImportState.done]
     * — **once**, for the Backup screen to show as it returns; before DONE, after a refusal and on every later call
     * it answers null, so a recomposition or a rotation never hands it over (or pops) twice. It writes nothing,
     * deletes nothing (the import's end already deleted the copy) and is not [cancel]'s exit.
     */
    fun handOff(): String? {
        val done = _state.value.takeIf { it.phase == TransferImportPhase.DONE }?.done ?: return null
        if (handedOff) return null
        handedOff = true
        return done
    }

    /**
     * Cancel writes nothing: the copy goes, and the screen leaves. Refused while an import is running, and after the
     * hand-off (#84 m-1): the Backup door is already leaving, and a back tap during that pop must not pop again.
     */
    fun cancel() {
        if (_state.value.importing || handedOff) return
        deleteCopy()
        _state.update { it.copy(finished = true) }
    }

    /** The share host's Close, after an end: the same exit as [cancel]. */
    fun close() = cancel()

    override fun onCleared() {
        deleteCopy()
    }

    private fun refuse(sentence: String) {
        deleteCopy()
        _state.update { it.copy(phase = TransferImportPhase.REFUSED, refusal = sentence, importEnabled = false) }
    }

    private fun deleteCopy() {
        copy?.let(inbox::delete)
    }

    private fun countsOf(manifest: TransferPackManifest): List<String> = TransferImportStrings.countLines(
        assets = manifest.assetIds.size,
        tags = manifest.counts["nfcTags"] ?: 0,
        records = manifest.counts["assetEvents"] ?: 0,
        schedules = manifest.counts["maintenanceSchedules"] ?: 0,
        documents = manifest.counts["attachments"] ?: 0,
        cases = manifest.counts["serviceCases"] ?: 0,
    )

    private companion object {
        const val TAG = "TransferImport"
        val DISPLAY_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM uuuu")
    }
}
