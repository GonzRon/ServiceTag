package com.loosecannon.servicetag.ui.scan

import android.util.Log
import com.loosecannon.nfc.tagcore.NdefRecordData
import com.loosecannon.nfc.tagcore.NdefSize
import com.loosecannon.nfc.tagcore.OverwriteDecision
import com.loosecannon.nfc.tagcore.OverwriteReason
import com.loosecannon.nfc.tagcore.android.CapacityVerdict
import com.loosecannon.nfc.tagcore.android.TagHandle
import com.loosecannon.nfc.tagcore.android.TagInspection
import com.loosecannon.nfc.tagcore.android.TagIo
import com.loosecannon.nfc.tagcore.android.TagRead
import com.loosecannon.nfc.tagcore.android.WriteResult
import com.loosecannon.nfc.tagcore.android.WriteRoute
import com.loosecannon.nfc.tagcore.android.fit
import com.loosecannon.nfc.tagcore.android.route
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagTarget
import com.loosecannon.servicetag.core.nfc.NdefCodec
import com.loosecannon.servicetag.core.nfc.OverwriteReasons
import com.loosecannon.servicetag.core.nfc.TagPayload
import com.loosecannon.servicetag.core.usecase.OverwriteSubject
import com.loosecannon.servicetag.core.usecase.OverwriteSubjects
import com.loosecannon.servicetag.core.usecase.ProvisionTag
import com.loosecannon.servicetag.core.usecase.Resolution
import com.loosecannon.servicetag.core.usecase.ResolveTag
import com.loosecannon.servicetag.di.AppGraph
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What the write screen draws. One state at a time; the lock switch is separate state. */
sealed interface WriteState {
    /** Nothing has happened yet, the tag was only formatted, or the last tap deliberately left it alone. */
    data class Idle(val message: String) : WriteState

    /** The tag already holds something; [subject] names it, in this product's words (#70). */
    data class Confirm(val subject: OverwriteSubject) : WriteState

    data class Written(val tagId: String, val locked: Boolean) : WriteState
    data class Error(val message: String) : WriteState
}

/**
 * The Phase 1B write flow on the nfc-tag-core seam: read first, route before planning, confirm
 * before overwriting anything but an empty tag or the same id, write off the main thread, and let
 * the write itself lock after its own verified read-back (invariant 9 is the library's). A tag that
 * still needs formatting is formatted and nothing else — `format(null)`, no payload — and the next
 * tap is an ordinary write against the capacity that now exists (invariant 7).
 *
 * A ServiceTag row is provisioned on the first writable tap — a format-only tap creates no
 * product state at all — and is reused across retries; [abandonIfUnwritten] deletes it if the
 * screen closes before a verified write claims it, so no phantom tag is left behind.
 *
 * When the tag holds a different ServiceTag v1 identity, the question names what that identity
 * means on this phone (#70): [ResolveTag.peek] — the scan's own classification, recording nothing —
 * runs first, bounded by [LOOKUP_BOUND], and only then is the question recorded and shown, in one
 * breath. The lookup changes the words and nothing else: a lookup that fails or runs out of time
 * still asks (with the honest "could not be checked" line); a cancelled one asks nothing and frees
 * the tap.
 */
class TagWriteController(
    private val provisionTag: ProvisionTag,
    /** Outlives the screen: abandoning a row must finish even though the screen is going away. */
    private val appScope: CoroutineScope,
    private val io: TagIo,
    private val codec: NdefCodec,
    private val target: TagTarget,
    label: String?,
    private val scope: CoroutineScope,
    /** Read-only here: only [ResolveTag.peek] is called, never the scan-recording `run`. */
    private val resolveTag: ResolveTag,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(graph: AppGraph, io: TagIo, target: TagTarget, label: String?, scope: CoroutineScope) :
        this(graph.provisionTag, graph.appScope, io, graph.ndefCodec, target, label, scope, graph.resolveTag)

    /**
     * The "Tag placement" value carried into [ProvisionTag.begin] (#49). It starts at the
     * constructor's [label] and stays settable until the first tap provisions [pending]: the
     * screen lets the owner type it before tapping, and [ProvisionTag] itself normalises a blank
     * value to null, so no normalisation is duplicated here.
     */
    @Volatile private var currentLabel: String? = label

    /** Called by the screen as the owner types the placement, before the row is provisioned. */
    fun setLabel(value: String?) { currentLabel = value }

    private val _placementLocked = MutableStateFlow(false)

    /**
     * True once [pending] is provisioned, so [currentLabel] is already baked into the row and any
     * further [setLabel] call is inert (review fix round 1, finding 2). The screen reads this to
     * disable the placement field rather than accepting typing it silently discards.
     */
    val placementLocked: StateFlow<Boolean> = _placementLocked.asStateFlow()

    private val _state = MutableStateFlow<WriteState>(InitialState)
    val state: StateFlow<WriteState> = _state.asStateFlow()

    private val _lock = MutableStateFlow(false)

    /** Whether the user has armed "lock permanently"; the warning dialog lives on the screen. */
    val lock: StateFlow<Boolean> = _lock.asStateFlow()

    @Volatile private var pending: TagBinding? = null

    /**
     * What the user agreed to overwrite. The handle captured before the confirmation sheet can go
     * stale while it is up (the NFC service re-discovers the tag and then refuses the old handle
     * with "Tag is out of date" — seen on an Android 17 phone), so a confirmation is remembered as
     * consent for *this content* and honoured on the next tap of a tag carrying it (invariant 10).
     */
    @Volatile private var confirmedOverwrite: TagPayload? = null
    @Volatile private var done = false
    private val busy = AtomicBoolean(false)

    /**
     * The content the confirmation sheet is asking about; it owns [busy] until it is answered. Only
     * the content is kept: the handle that raised the question is exactly the one that may be
     * stale by the time the answer arrives, so it is never written through (owner, 2026-09-17).
     */
    @Volatile private var awaitingAnswer: TagPayload? = null

    fun setLock(value: Boolean) { _lock.value = value }

    /** Reader mode calls this from a binder thread; nothing here touches the main thread (invariant 11). */
    fun onTag(tag: TagHandle) {
        if (done || !busy.compareAndSet(false, true)) return
        scope.launch {
            var sheetOwnsBusy = false
            try {
                sheetOwnsBusy = handle(tag)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The platform's message is not the user's business; the exception is the log's (R4).
                Log.w(TAG, "tap failed", e)
                _state.value = WriteState.Error("Could not read the tag. Hold it still and try again.")
            } finally {
                if (!sheetOwnsBusy) busy.set(false)
            }
        }
    }

    /** Returns true when the confirmation sheet now owns the [busy] flag. */
    private suspend fun handle(tag: TagHandle): Boolean {
        val inspection = withContext(ioDispatcher) { io.inspect(tag) }
        if (inspection == null) {
            _state.value = WriteState.Error("This tag does not support NDEF. Use an NTAG213/215/216 or similar.")
            return false
        }
        // Route BEFORE planning, and without a message size: a tag that needs formatting has no
        // capacity yet, and a read-only tag is refused before capacity is even a question (F-3).
        val writable = when (val r = inspection.route()) {
            WriteRoute.Format -> { format(tag); return false }
            WriteRoute.ReadOnly -> { _state.value = WriteState.Error("This tag is read-only (locked). Nothing written."); return false }
            is WriteRoute.Writable -> r
        }
        val row = pending ?: provisionTag.begin(target, currentLabel).also {
            pending = it
            _placementLocked.value = true
        }
        val intended = codec.encodeV1(row.id)
        when (val v = writable.fit(NdefSize.serialisedSize(intended))) {
            is CapacityVerdict.TooSmall -> {
                _state.value = WriteState.Error("Tag too small: it holds ${v.maxSize} bytes, the message needs ${v.needed}.")
                return false
            }
            CapacityVerdict.Write -> Unit
        }
        val existing = existingOn(inspection)
        val consent = confirmedOverwrite
        if (consent != null) {
            confirmedOverwrite = null                       // consent is consumed by this tap, either way
            if (consent == existing) {
                // The user already agreed to replace exactly this content; THIS tap's handle is
                // fresh, so the write goes ahead through it without asking twice.
                write(tag, intended, row)
                return false
            }
            // a different tag, or the same tag changed under the sheet: ask again
        }
        return when (val d = OverwriteReasons.decide(existing, row.id)) {
            OverwriteDecision.Proceed -> { write(tag, intended, row); false }
            is OverwriteDecision.Confirm -> {
                // Look the id up FIRST (R70-2): no suspension point may separate recording the
                // question from showing it, or `keepIt()` / `confirmOverwrite()` could answer a
                // question that is not on screen yet. `d` is final; the lookup only picks words.
                val resolution = if (d.reason == OverwriteReason.OTHER_TAG_SAME_PRODUCT) lookUp(existing) else null
                awaitingAnswer = existing
                _state.value = WriteState.Confirm(OverwriteSubjects.of(d, resolution))
                true
            }
        }
    }

    /**
     * What the id on the tag means on this phone, or null when the lookup failed or took longer
     * than [LOOKUP_BOUND] (C7). `withTimeoutOrNull`, not `withTimeout`: running out of time must
     * still ask. A cancellation is rethrown, never turned into a sheet: `onTag`'s `finally` frees
     * the tap. No io hop — Room keeps its own threads, as `provisionTag.begin` already relies on.
     * The bound cuts the lookup off only where it suspends; a query blocked inside SQLite is not
     * interrupted, it is merely no longer waited for.
     */
    private suspend fun lookUp(existing: TagPayload): Resolution? =
        try {
            withTimeoutOrNull(LOOKUP_BOUND) { resolveTag.peek(existing) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "overwrite lookup failed", e)
            null
        }

    /** What the tag holds, in this product's terms. Unreadable NDEF is unreadable — never "empty" (C1). */
    private fun existingOn(inspection: TagInspection): TagPayload = when (val read = inspection.read) {
        is TagRead.Readable -> codec.decode(read.records)
        is TagRead.Unreadable -> {
            read.cause?.let { Log.w(TAG, "tag NDEF unreadable: ${read.reason}", it) }
            TagPayload.Malformed(read.reason)
        }
    }

    /** `format(null)`: the tag is made NDEF-capable, left empty and unlocked, and nothing is planned or written (R1). */
    private suspend fun format(tag: TagHandle) {
        when (val r = withContext(ioDispatcher) { io.format(tag) }) {
            WriteResult.Formatted -> _state.value = WriteState.Idle("Formatted. Lift the tag off and hold it again to write.")
            is WriteResult.Failed -> {
                Log.w(TAG, "format failed: ${r.reason}", r.cause)
                _state.value = WriteState.Error("Could not format the tag. Hold it still and try again.")
            }
            WriteResult.Unsupported -> _state.value = WriteState.Error("This tag does not support NDEF.")
            is WriteResult.Written, is WriteResult.TooSmall, WriteResult.ReadOnly, is WriteResult.VerifyMismatch ->
                _state.value = WriteState.Error("Unexpected result while formatting. Hold the tag still and try again.")
        }
    }

    /**
     * "Overwrite" on the confirmation sheet: record consent for the content that was asked about
     * and release the sheet. NO tag I/O here — the handle that raised the question may be stale;
     * the next tap re-inspects through a fresh handle and, if the content still matches, writes
     * through that one (invariant 10).
     */
    fun confirmOverwrite() {
        val asked = awaitingAnswer ?: return
        awaitingAnswer = null
        confirmedOverwrite = asked
        busy.set(false)
        _state.value = WriteState.Idle("Overwrite confirmed. Hold the same tag to the phone again to write.")
    }

    /** "Keep it", and the same thing a dismissed sheet means: the tag is left exactly as it was. */
    fun keepIt() {
        if (awaitingAnswer == null) return
        awaitingAnswer = null
        confirmedOverwrite = null
        busy.set(false)
        _state.value = WriteState.Idle("Not written. The tag was left as it was.")
    }

    private suspend fun write(tag: TagHandle, intended: List<NdefRecordData>, row: TagBinding) {
        val wantLock = _lock.value
        when (val r = withContext(ioDispatcher) { io.write(tag, intended, wantLock) }) {
            // A Written is verified by construction; the lock, if asked for, rode on it (invariant 9).
            is WriteResult.Written -> finishWrite(row, tag.uid, r.locked)
            is WriteResult.TooSmall ->
                _state.value = WriteState.Error("Tag too small: it holds ${r.maxSize} bytes, the message needs ${r.needed}.")
            WriteResult.ReadOnly ->
                _state.value = WriteState.Error("This tag is read-only (locked). Nothing written.")
            WriteResult.Unsupported ->
                _state.value = WriteState.Error("This tag does not support NDEF.")
            WriteResult.Formatted ->
                _state.value = WriteState.Error("Unexpected result while writing. Hold the tag still and try again.")
            is WriteResult.VerifyMismatch ->
                _state.value = WriteState.Error("Read-back differs from what was written. Nothing recorded — try again.")
            is WriteResult.Failed -> {
                r.cause?.let { Log.w(TAG, "write failed: ${r.reason}", it) }
                // `attempted` — not the reason text — says whether the radio was reached (I1).
                _state.value = WriteState.Error(
                    if (!r.attempted) "Nothing was written (${r.reason}). Hold the tag still and try again."
                    else "The write may not have finished (${r.reason}). Lift the tag off and hold it to the phone again.",
                )
            }
        }
    }

    private suspend fun finishWrite(row: TagBinding, uid: String?, locked: Boolean) {
        val completed = provisionTag.complete(row.id, uid)
        done = true
        _state.value = WriteState.Written(completed.id.value, locked)
    }

    /**
     * Deletes the provisioned row unless a verified write already claimed it. Returns the job so
     * a test can wait for it; the screen fires and forgets, on a scope that outlives it.
     */
    fun abandonIfUnwritten(): Job? {
        val row = pending
        if (done || row == null) return null
        return appScope.launch { provisionTag.abandon(row.id) }
    }

    private companion object {
        const val TAG = "TagWriteController"
        val InitialState = WriteState.Idle("Hold a blank or reusable tag to the back of the phone.")

        /** R70-6: a Room read never takes this long; the bound keeps "changes only the words" true if one did. */
        val LOOKUP_BOUND = 2.seconds
    }
}
