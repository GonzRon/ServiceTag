package com.loosecannon.servicetag.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AttachmentKind
import com.loosecannon.servicetag.core.model.AttachmentKinds
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.AttachmentProblem
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.AttachmentStorage
import com.loosecannon.servicetag.core.ports.StoreState
import com.loosecannon.servicetag.core.references.MAX_REFERENCE_DESCRIPTION_CHARS
import com.loosecannon.servicetag.core.references.MAX_REFERENCE_NAME_CHARS
import com.loosecannon.servicetag.core.references.ReferenceText
import com.loosecannon.servicetag.core.usecase.AddAttachment
import com.loosecannon.servicetag.core.usecase.AddAttachmentCommand
import com.loosecannon.servicetag.core.usecase.AddReference
import com.loosecannon.servicetag.core.usecase.AddReferenceCommand
import com.loosecannon.servicetag.core.usecase.AttachmentResult
import com.loosecannon.servicetag.core.usecase.EventCommand
import com.loosecannon.servicetag.core.usecase.LogEvent
import com.loosecannon.servicetag.core.usecase.NoSuchAsset
import com.loosecannon.servicetag.core.usecase.ReferenceProblem
import com.loosecannon.servicetag.core.usecase.ReferenceResult
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **Every user-visible word the intake screen draws, in one place and verbatim from spec §10.**
 * Nothing here is composed out of fragments and nothing is paraphrased: a sentence this object
 * does not carry is a finding for the controller, never a screen's to invent.
 */
internal object IntakeStrings {
    const val TITLE = "Save to ServiceTag"
    const val RECEIVED = "Received"
    const val ATTACH_TO = "Attach to"
    const val CHOOSE_ASSET = "Choose asset"
    const val NAME = "Name"
    const val DESCRIPTION = "Description (optional)"
    const val TYPE = "Type"
    const val SAVE = "Save"
    const val CANCEL = "Cancel"
    const val CLOSE = "Close"
    const val SAVE_AS_NOTE = "Save as a note"
    const val NOT_A_LINK = "That is not a link."
    const val NO_ASSETS = "Add an asset in ServiceTag first, then share this again."
    const val NO_FOLDER = "Choose an attachment folder in ServiceTag Settings, then share this again."
    const val STREAM_REFUSED = "That file cannot be accepted from the app that shared it."
    const val UNREADABLE = "Could not read what was shared"
    const val URI_TOO_LONG = "That link is too long to save."
    const val SCHEME_BLOCKED = "ServiceTag will not save that kind of link."
    const val DUPLICATE_URI = "That link is already on this asset"
    const val EMPTY_FILE = "That file is empty"
    const val TOO_LARGE = "That file is larger than 256 MB"
    const val BLANK_FILE_NAME = "Give the file a name"
    const val BLANK_REFERENCE_NAME = "Give the reference a name"
    const val CONFIRM_TITLE = "Save this link?"

    fun confirmBody(scheme: String): String =
        "ServiceTag does not recognise \"$scheme\" links. It will be saved as written and opened " +
            "with whatever app claims it."

    fun savedTo(assetName: String): String = "Saved to $assetName"
}

/** Which of the three save paths this share is on. The screen's button text follows from it. */
internal enum class IntakePath { LINK, BYTES, NOTE }

/** One chooser row. The id is carried as a string so the state holds no value class. */
internal data class AssetChoice(val id: String, val name: String)

/**
 * The one state the intake screen draws. [deadEnd] and the form are mutually exclusive; the
 * no-folder case is deliberately **not** a dead end, because the form is still drawn with Save
 * disabled (D-20).
 */
internal data class ShareIntakeState(
    val loading: Boolean = true,
    val path: IntakePath = IntakePath.LINK,
    /** What arrived, drawn under "Received". */
    val received: String = "",
    val assets: List<AssetChoice> = emptyList(),
    val chosen: String? = null,
    val name: String = "",
    val description: String = "",
    val kind: AttachmentKind = AttachmentKind.OTHER,
    val storeReady: Boolean = true,
    /** A ratified sentence drawn beside the form; the person can still act. */
    val message: String? = null,
    /** A ratified sentence with "Close" and nothing else offered. */
    val deadEnd: String? = null,
    /** The scheme the person is being asked about by name, or null. */
    val confirming: String? = null,
    val saving: Boolean = false,
    /** "Saved to \<asset\>". The activity finishes once this is set. */
    val saved: String? = null,
    val cancelled: Boolean = false,
) {
    /** A byte share on a phone with no attachment folder: the sentence, and Save disabled. */
    val noFolder: Boolean get() = path == IntakePath.BYTES && !storeReady

    /**
     * **Disabling Save is the intake behaviour** (spec §7), which is why no blank-name sentence is
     * ever drawn on this screen: an asset is chosen, the sanitised name is non-blank, and on a byte
     * share the folder is there.
     */
    val saveEnabled: Boolean get() = !loading &&
        deadEnd == null &&
        !saving &&
        saved == null &&
        chosen != null &&
        ReferenceText.sanitiseName(name).isNotEmpty() &&
        (path != IntakePath.BYTES || storeReady)

    val finished: Boolean get() = saved != null || cancelled
}

/**
 * The share intake state machine. **Cancel, back and every refusal write nothing** (I-8): the only
 * writes in this class are inside [save], behind [ShareIntakeState.saveEnabled], and nothing is
 * committed on dispose.
 *
 * **The intent is read here, not in `onCreate`**: [readShare] does provider IPC and may pull up to
 * 64 KiB of stream, so it runs on [io] and the screen draws nothing actionable until the state
 * lands. Everything past that point is [ShareContent], which takes no Android type, so the whole
 * machine can be driven on the JVM.
 *
 * **I-3 holds by construction**: a `LINK` never reaches `AddAttachment` and a `BYTES` never reaches
 * `AddReference`, because the path is decided once, by the reader, and each arm calls one use case.
 */
internal class ShareIntakeViewModel(
    private val readShare: suspend () -> SharedShare,
    private val assets: AssetRepository,
    private val storage: AttachmentStorage,
    private val addReference: AddReference,
    private val addAttachment: AddAttachment,
    private val logEvent: LogEvent,
    private val today: () -> String,
    private val zoneId: () -> String,
    private val io: CoroutineContext = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(ShareIntakeState())
    val state: StateFlow<ShareIntakeState> = _state.asStateFlow()

    /** Set once, by the one read below. Nothing reads it before [ShareIntakeState.loading] clears. */
    private var share: SharedShare? = null

    init {
        viewModelScope.launch {
            // One hop: the provider IPC, the stream read, the asset list and the store's state are
            // all off the main thread, and the screen commits to nothing until they land together.
            //
            // **Guarded, because a failure here has nowhere else to go.** Before the read moved off
            // `onCreate` a throw at least ended the activity; from inside `viewModelScope.launch`
            // it would leave the blank loading screen with no Close on it. The same discipline the
            // save paths use: cancellation travels, the documented failure types land as the
            // ratified read-failure dead end, and nothing is staged on the way.
            //
            // **The stranger's process is guarded where it is called, not here.** Every
            // provider-facing call inside `readShare` already answers `Refused(UNREADABLE)` on its
            // own — the extras read, `stream.facts()`, the bounded `readAtMost` and the UTF-8
            // decode each carry their own guard, `byteSourceFor` only builds a lambda, and reading
            // a `Uri`'s scheme and authority does no IPC. So one `try` is enough here, and it
            // catches what a read can still legitimately fail with on its way out of that net; a
            // `RuntimeException` could only come from this app's own pure code, and swallowing it
            // would dress a `:core` defect as a ratified refusal — the trade the save paths
            // already declined.
            val loaded = try {
                withContext(io) {
                    val found = readShare()
                    share = found
                    loadedState(
                        content = found.content,
                        choices = assets.all()
                            .map { AssetChoice(it.id.value, it.name) }
                            .sortedBy { it.name.lowercase() },
                        store = storage.state(),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                unreadable()
            } catch (_: SecurityException) {
                unreadable()
            }
            _state.update { current -> if (current.cancelled) current else loaded }
        }
    }

    /** Nothing could be read and nothing was staged: one ratified sentence, and a way out. */
    private fun unreadable() =
        ShareIntakeState(loading = false, deadEnd = IntakeStrings.UNREADABLE)

    fun choose(assetId: String) = _state.update { it.copy(chosen = assetId, message = null) }

    /**
     * **The field stops at its cap** rather than refusing after the fact, which is what spec §10
     * ratifies in place of a sentence. The shipped attachment edit sheet caps neither of its
     * fields, so both paths use `:core`'s two reference constants and therefore agree.
     */
    fun name(value: String) =
        _state.update { it.copy(name = value.take(MAX_REFERENCE_NAME_CHARS), message = null) }

    fun describe(value: String) = _state.update {
        it.copy(description = value.take(MAX_REFERENCE_DESCRIPTION_CHARS), message = null)
    }

    fun kind(value: AttachmentKind) = _state.update { it.copy(kind = value, message = null) }

    /** Cancel, back and Close are the same fact: nothing was written and nothing will be. */
    fun cancel() = _state.update { it.copy(confirming = null, cancelled = true) }

    fun dismissConfirmation() = _state.update { it.copy(confirming = null, saving = false) }

    fun save() {
        if (!_state.value.saveEnabled) return
        _state.update { it.copy(saving = true, message = null) }
        viewModelScope.launch { commit(confirmedUnknownScheme = false) }
    }

    /** The person answered "Save this link?" by name; this is the only caller that may confirm. */
    fun confirmUnknownScheme() {
        val current = _state.value
        if (current.confirming == null) return
        _state.update { it.copy(confirming = null, saving = true, message = null) }
        viewModelScope.launch { commit(confirmedUnknownScheme = true) }
    }

    private suspend fun commit(confirmedUnknownScheme: Boolean) {
        val current = _state.value
        val choice = current.assets.firstOrNull { it.id == current.chosen }
        if (choice == null) {
            _state.update { it.copy(saving = false, deadEnd = IntakeStrings.NO_ASSETS) }
            return
        }
        when (current.path) {
            IntakePath.LINK -> saveLink(current, choice, confirmedUnknownScheme)
            IntakePath.BYTES -> saveBytes(current, choice)
            IntakePath.NOTE -> saveNote(current, choice)
        }
    }

    private suspend fun saveLink(
        current: ShareIntakeState,
        choice: AssetChoice,
        confirmedUnknownScheme: Boolean,
    ) {
        val uri = (share?.content as? ShareContent.Link)?.uri
            ?: return refuse(IntakeStrings.UNREADABLE)
        val result = addReference.run(
            AssetId(choice.id),
            AddReferenceCommand(
                uri = uri,
                displayName = current.name,
                description = current.description,
                confirmedUnknownScheme = confirmedUnknownScheme,
            ),
        )
        when (result) {
            is ReferenceResult.Ok -> succeed(choice)
            is ReferenceResult.Refused -> when (val problem = result.problem) {
                // The one refusal that is a question rather than an answer: the person is asked
                // about the scheme by name, and only their "Save" sets the flag (plan §18.2).
                is ReferenceProblem.UnknownSchemeNeedsConfirmation ->
                    _state.update { it.copy(saving = false, confirming = problem.scheme) }
                ReferenceProblem.DuplicateUri -> refuse(IntakeStrings.DUPLICATE_URI)
                ReferenceProblem.UriTooLong -> refuse(IntakeStrings.URI_TOO_LONG)
                ReferenceProblem.SchemeBlocked -> refuse(IntakeStrings.SCHEME_BLOCKED)
                ReferenceProblem.NotALink -> refuse(IntakeStrings.NOT_A_LINK)
                ReferenceProblem.BlankName -> refuse(IntakeStrings.BLANK_REFERENCE_NAME)
                ReferenceProblem.OwnerMissing,
                ReferenceProblem.NoSuchReference,
                ReferenceProblem.Unchanged,
                -> ownerGone()
            }
        }
    }

    /**
     * **The empty file is refused here and the over-cap one is not.** `AttachmentProblem`'s list is
     * closed — a new member would change the shipped camera and picker paths and the exhaustive
     * `when` in `AttachmentsSectionViewModel.say` — so spec §4.3 puts the zero-length refusal in
     * this layer and its sentence with it. The declared over-size is already refused by the shipped
     * `AddAttachment` **before** it opens the source, with the same ratified sentence, so a second
     * copy of that rule here would be two places to keep in step for no behaviour at all.
     */
    private suspend fun saveBytes(current: ShareIntakeState, choice: AssetChoice) {
        val content = share?.content
        val bytes = (content as? ShareContent.Bytes) ?: return refuse(IntakeStrings.UNREADABLE)
        val open = share?.bytes ?: return refuse(IntakeStrings.UNREADABLE)
        if (bytes.size == 0L) return refuse(IntakeStrings.EMPTY_FILE)

        val result = try {
            addAttachment.run(
                AttachmentOwner.OfAsset(AssetId(choice.id)),
                AddAttachmentCommand(
                    displayName = current.name,
                    mimeType = bytes.mimeType,
                    sizeBytes = bytes.size,
                    kind = current.kind,
                    notes = current.description,
                ),
                open,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            // A source that dies mid-copy, or a store that cannot write: the store removes what it
            // half-wrote and no row was ever built, so this is a read failure and nothing else.
            // `StoreIoException` is an `IOException`, so the store's own failures land here too.
            return refuse(IntakeStrings.UNREADABLE)
        } catch (_: SecurityException) {
            // The grant is gone — a recreation long after the sharing task finished. Same fact to
            // the person, and still nothing written.
            return refuse(IntakeStrings.UNREADABLE)
        }
        when (result) {
            is AttachmentResult.Ok -> succeed(choice)
            is AttachmentResult.Refused -> when (val problem = result.problem) {
                AttachmentProblem.NoStore, AttachmentProblem.StoreUnavailable ->
                    _state.update { it.copy(saving = false, storeReady = false) }
                is AttachmentProblem.TooLarge -> refuse(IntakeStrings.TOO_LARGE)
                AttachmentProblem.BlankName -> refuse(IntakeStrings.BLANK_FILE_NAME)
                AttachmentProblem.OwnerMissing, AttachmentProblem.Unchanged -> ownerGone()
            }
        }
    }

    /** #43 AC 5 and D-5: prose becomes a journal note through the shipped `EventKind.NOTE`. */
    private suspend fun saveNote(current: ShareIntakeState, choice: AssetChoice) {
        val prose = (share?.content as? ShareContent.PlainText)?.text.orEmpty()
        try {
            logEvent.run(
                EventCommand(
                    assetId = AssetId(choice.id),
                    profileId = null,
                    kind = EventKind.NOTE,
                    title = ReferenceText.sanitiseName(current.name),
                    occurredOn = today(),
                    occurredTime = null,
                    tzId = zoneId(),
                    notes = listOf(current.description, prose)
                        .filter { it.isNotBlank() }
                        .joinToString("\n\n"),
                    values = emptyMap(),
                    consumables = emptyList(),
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: NoSuchAsset) {
            // The one fact the no-assets sentence names: the chosen asset was deleted between the
            // chooser listing it and Save. Nothing else is told to go and create an asset.
            return ownerGone()
        } catch (_: IOException) {
            return refuse(IntakeStrings.UNREADABLE)
        } catch (_: SecurityException) {
            return refuse(IntakeStrings.UNREADABLE)
        }
        succeed(choice)
    }

    private fun succeed(choice: AssetChoice) = _state.update {
        it.copy(saving = false, saved = IntakeStrings.savedTo(choice.name))
    }

    private fun refuse(sentence: String) = _state.update {
        it.copy(saving = false, message = sentence)
    }

    private fun ownerGone() = _state.update {
        it.copy(saving = false, deadEnd = IntakeStrings.NO_ASSETS)
    }

    /**
     * The one state the read produces, so a cold start and a warm one cannot differ: it is a pure
     * function of what arrived, the asset list and the store's state, with nothing read from a
     * field that a second process might have set differently.
     */
    private fun loadedState(
        content: ShareContent,
        choices: List<AssetChoice>,
        store: StoreState,
    ): ShareIntakeState {
        val base = when (content) {
            is ShareContent.Link -> ShareIntakeState(
                path = IntakePath.LINK,
                received = content.uri,
                name = content.suggestedName.orEmpty().take(MAX_REFERENCE_NAME_CHARS),
            )
            is ShareContent.Bytes -> ShareIntakeState(
                path = IntakePath.BYTES,
                received = content.suggestedName,
                name = content.suggestedName.take(MAX_REFERENCE_NAME_CHARS),
                kind = AttachmentKinds.inferFrom(content.mimeType, fromCamera = false),
                storeReady = store is StoreState.Ready,
            )
            is ShareContent.PlainText -> ShareIntakeState(
                path = IntakePath.NOTE,
                received = content.text,
                name = ReferenceText.sanitiseName(content.text).take(MAX_REFERENCE_NAME_CHARS),
                message = IntakeStrings.NOT_A_LINK,
            )
            is ShareContent.Refused -> ShareIntakeState(
                deadEnd = when (content.reason) {
                    IntakeRefusal.STREAM_NOT_ACCEPTED -> IntakeStrings.STREAM_REFUSED
                    IntakeRefusal.UNREADABLE -> IntakeStrings.UNREADABLE
                    IntakeRefusal.URI_TOO_LONG -> IntakeStrings.URI_TOO_LONG
                    IntakeRefusal.SCHEME_BLOCKED -> IntakeStrings.SCHEME_BLOCKED
                },
            )
        }
        return base.copy(
            loading = false,
            assets = choices,
            deadEnd = base.deadEnd ?: IntakeStrings.NO_ASSETS.takeIf { choices.isEmpty() },
        )
    }
}
