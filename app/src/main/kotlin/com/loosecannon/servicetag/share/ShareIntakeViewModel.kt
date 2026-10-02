package com.loosecannon.servicetag.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AttachmentKind
import com.loosecannon.servicetag.core.model.AttachmentKinds
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.AttachmentProblem
import com.loosecannon.servicetag.core.model.DocumentRole
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.ReferenceOwner
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.AttachmentStorage
import com.loosecannon.servicetag.core.ports.ByteSource
import com.loosecannon.servicetag.core.ports.StoreState
import com.loosecannon.servicetag.core.references.LinkLaunchPolicy
import com.loosecannon.servicetag.core.references.MAX_REFERENCE_DESCRIPTION_CHARS
import com.loosecannon.servicetag.core.references.MAX_REFERENCE_NAME_CHARS
import com.loosecannon.servicetag.core.references.ReferenceKinds
import com.loosecannon.servicetag.core.references.ReferenceText
import com.loosecannon.servicetag.core.references.takesRole
import com.loosecannon.servicetag.core.transfer.AssetTransferredOut
import com.loosecannon.servicetag.core.transfer.TransferPack
import com.loosecannon.servicetag.core.usecase.AddAttachment
import com.loosecannon.servicetag.core.usecase.AddAttachmentCommand
import com.loosecannon.servicetag.core.usecase.AddReference
import com.loosecannon.servicetag.core.usecase.AddReferenceCommand
import com.loosecannon.servicetag.core.usecase.AttachmentResult
import com.loosecannon.servicetag.core.usecase.EventCommand
import com.loosecannon.servicetag.core.usecase.ImportTransferPack
import com.loosecannon.servicetag.core.usecase.LogEvent
import com.loosecannon.servicetag.core.usecase.NoSuchAsset
import com.loosecannon.servicetag.core.usecase.ReferenceProblem
import com.loosecannon.servicetag.core.usecase.ReferenceResult
import com.loosecannon.servicetag.ui.attachments.ROLE_HEADER
import com.loosecannon.servicetag.ui.maintenance.ReminderReconcile
import com.loosecannon.servicetag.ui.references.DUPLICATE_URI_ON_INSTALLED_COMPONENT
import com.loosecannon.servicetag.ui.references.DUPLICATE_URI_ON_SUPPLY
import com.loosecannon.servicetag.ui.transfer.`import`.TransferImportStrings
import com.loosecannon.servicetag.ui.transfer.`import`.TransferImportViewModel
import com.loosecannon.servicetag.ui.transfer.`import`.TransferPackInbox
import java.io.File
import java.util.zip.ZipInputStream
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
 * **Every user-visible word the intake's own composables draw, in one place and verbatim from spec
 * §10.** Nothing here is composed out of fragments and nothing is paraphrased: a sentence this object
 * does not carry is a finding for the controller, never a screen's to invent. #93: the picker step's
 * search box, controls, rows and empty sentences are drawn by `ui/asset`'s reused composables in their
 * own words (ratified with #73 and #71), never re-spelled here.
 */
internal object IntakeStrings {
    const val TITLE = "Save to ServiceTag"
    const val RECEIVED = "Received"
    const val ATTACH_TO = "Attach to"
    const val CHOOSE_ASSET = "Choose asset"
    const val NAME = "Name"
    const val DESCRIPTION = "Description (optional)"
    const val TYPE = "Type"

    /** #67 (P67-5): the Role header, from its one home beside the role labels — never re-spelled. */
    const val ROLE = ROLE_HEADER
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

    /** #69 (C28, R69-13): the duplicate by the owner's kind — P69-11/-12 from their one home; an asset's stays. */
    fun duplicateUri(owner: ReferenceOwner): String = when (owner) {
        is ReferenceOwner.OfAsset -> DUPLICATE_URI
        is ReferenceOwner.OfSupplyItem -> DUPLICATE_URI_ON_SUPPLY
        is ReferenceOwner.OfInstalledComponent -> DUPLICATE_URI_ON_INSTALLED_COMPONENT
    }
    const val EMPTY_FILE = "That file is empty"
    const val TOO_LARGE = "That file is larger than 256 MB"
    const val BLANK_FILE_NAME = "Give the file a name"
    const val BLANK_REFERENCE_NAME = "Give the reference a name"
    const val CONFIRM_TITLE = "Save this link?"

    /** #93 (R93-5, G1): the form's action back to the picker, trailing the chosen asset's name. */
    const val CHANGE = "Change"

    fun confirmBody(scheme: String): String =
        "ServiceTag does not recognise \"$scheme\" links. It will be saved as written and opened " +
            "with whatever app claims it."

    fun savedTo(assetName: String): String = "Saved to $assetName"
}

/**
 * Which of the three save paths this share is on. The screen's button text follows from it. #77 (C16, R77-2):
 * [TRANSFER_PACK] is a ZIP whose first local entry is `transfer-manifest.json` — not a save path at all: the intake
 * hosts the Transfer Pack import screen over its copy instead of the form.
 */
internal enum class IntakePath { LINK, BYTES, NOTE, TRANSFER_PACK }

/**
 * The chosen asset: the tapped picker row's id and name (#93, C4), and a row of the read-time snapshot. The id is
 * carried as a string so the state holds no value class.
 */
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
    /**
     * The read-time eligible set — every asset not transferred out, read once with the share. #93 (C4): it decides
     * the no-assets dead end and nothing else; it is not drawn, and a choice is never looked up in it.
     */
    val assets: List<AssetChoice> = emptyList(),
    /** #93 (C4): the tapped row's `(id, name)`, exactly what every save arm reads; null on the picker step. */
    val chosen: AssetChoice? = null,
    val name: String = "",
    val description: String = "",
    val kind: AttachmentKind = AttachmentKind.OTHER,
    /**
     * #67 (R67-9), #91 (R91-4): the chosen document role, on a byte share or a web-link share only
     * ([roleOffered]); null is the no-role chip.
     */
    val role: DocumentRole? = null,
    /**
     * #91 (R91-4): whether the shared link is one that takes a role — decided once, when the share is
     * classified, by the shipped scheme classifier and nothing else. False off the link path.
     */
    val linkTakesRole: Boolean = false,
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
    /** #77 (C14 (0)): the pack's copy in `cache/transfer-in/`, made while the share's grant lived. */
    val packCopy: File? = null,
) {
    /** A byte share on a phone with no attachment folder: the sentence, and Save disabled. */
    val noFolder: Boolean get() = path == IntakePath.BYTES && !storeReady

    /** #67 (R67-9), #91 (R91-4): the Role control is offered on a byte share and on a web-link share. */
    val roleOffered: Boolean get() = path == IntakePath.BYTES || (path == IntakePath.LINK && linkTakesRole)

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
        (path != IntakePath.BYTES || storeReady) &&
        path != IntakePath.TRANSFER_PACK

    val finished: Boolean get() = saved != null || cancelled

    /** #93 (C5): the picker step is drawn — a loaded, live share with nothing chosen yet; the form otherwise. */
    val picking: Boolean get() = !loading &&
        deadEnd == null &&
        saved == null &&
        chosen == null &&
        path != IntakePath.TRANSFER_PACK

    /**
     * #93 (R93-4): Back on the form returns to the picker. Off while saving — Back mid-save finishes the activity as
     * today — and while confirming, where the dialog takes Back.
     */
    val backChangesAsset: Boolean get() = !loading &&
        chosen != null &&
        !saving &&
        saved == null &&
        deadEnd == null &&
        confirming == null
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
    /** #77 (rm-5): the assets transferred out from this phone, never offered in "Attach to". */
    private val heldIds: suspend () -> Set<AssetId> = { emptySet() },
    /** #77 (C14 (0)): where a shared Transfer Pack is copied; null leaves every ZIP on the byte form. */
    private val packInbox: TransferPackInbox? = null,
    /** #91 (C-4): reads a shared link's scheme for [ShareIntakeState.linkTakesRole]; stateless. */
    private val linkPolicy: LinkLaunchPolicy = LinkLaunchPolicy(),
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
                    // #77 (C16): a ZIP whose first local entry is the pack manifest is a Transfer Pack, whatever
                    // it is called; it is copied now, while the share's grant lives, and never offered as a file.
                    val copy = packInbox
                        ?.takeIf { found.content is ShareContent.Bytes }
                        ?.let { inbox -> found.bytes?.takeIf(::isTransferPack)?.let { inbox.copyIn(it) } }
                    if (copy != null) {
                        return@withContext ShareIntakeState(
                            loading = false,
                            path = IntakePath.TRANSFER_PACK,
                            received = (found.content as ShareContent.Bytes).suggestedName,
                            packCopy = copy,
                        )
                    }
                    // rm-5: an asset transferred out from this phone is never offered.
                    val held = heldIds()
                    loadedState(
                        content = found.content,
                        choices = assets.all()
                            .filter { it.id !in held }
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

    /** #93 (C4): the tapped row, carried whole; nothing is looked up. */
    fun choose(assetId: String, name: String) =
        _state.update { it.copy(chosen = AssetChoice(assetId, name), message = null) }

    /**
     * #93 (C6): back to the picker — the choice and any refusal cleared, Name, Description, Type and Role kept (each
     * came from the share, not the asset). "That is not a link." is not restored. A no-op unless
     * [ShareIntakeState.backChangesAsset].
     */
    fun changeAsset() = _state.update {
        if (it.backChangesAsset) it.copy(chosen = null, message = null) else it
    }

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

    /**
     * #67 (R67-9), amended by #91 (R91-4): a role is taken where it is offered — a byte share or a
     * web-link share. A note link or an unfamiliar scheme cannot carry one and a note is a journal
     * entry, so on those paths the choice is not recorded at all.
     */
    fun role(value: DocumentRole?) = _state.update {
        if (it.roleOffered) it.copy(role = value, message = null) else it
    }

    /** Cancel, Close and Back on the picker are the same fact: nothing was written and nothing will be (Back on the form is `changeAsset()`, after which a choice and a save are still possible). */
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
        val choice = current.chosen
        if (choice == null) {
            _state.update { it.copy(saving = false, deadEnd = IntakeStrings.NO_ASSETS) }
            return
        }
        when (current.path) {
            IntakePath.LINK -> saveLink(current, choice, confirmedUnknownScheme)
            IntakePath.BYTES -> saveBytes(current, choice)
            IntakePath.NOTE -> saveNote(current, choice)
            IntakePath.TRANSFER_PACK -> Unit
        }
    }

    private suspend fun saveLink(
        current: ShareIntakeState,
        choice: AssetChoice,
        confirmedUnknownScheme: Boolean,
    ) {
        val uri = (share?.content as? ShareContent.Link)?.uri
            ?: return refuse(IntakeStrings.UNREADABLE)
        val result = try {
            addReference.run(
                ReferenceOwner.OfAsset(AssetId(choice.id)),
                AddReferenceCommand(
                    uri = uri,
                    displayName = current.name,
                    description = current.description,
                    confirmedUnknownScheme = confirmedUnknownScheme,
                    role = current.role.takeIf { current.roleOffered },
                ),
            )
        } catch (_: AssetTransferredOut) {
            return refuse(TransferImportStrings.ASSET_TRANSFERRED_OUT)
        }
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
                // #91 (R91-14): the intake never sends a role a link cannot take.
                ReferenceProblem.RoleNotAllowed,
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
            // **A provider need not declare a size at all**, and then there is no zero to test:
            // the shipped `AddAttachment` measures only *after* it has copied, so an undeclared
            // empty stream would land as a 0-byte row with a locator. One byte settles it before
            // the store is asked. Re-opening is safe by construction — a `ByteSource` is a
            // factory and the share's own calls `openInputStream` on every `open()` — so the copy
            // below still reads from the start; a probe that cannot read is the same read failure
            // the copy would have hit, and it lands in the catches below for the same sentence.
            if (bytes.size == null && withContext(io) { open.open().use { it.read() < 0 } }) {
                return refuse(IntakeStrings.EMPTY_FILE)
            }
            addAttachment.run(
                AttachmentOwner.OfAsset(AssetId(choice.id)),
                AddAttachmentCommand(
                    displayName = current.name,
                    mimeType = bytes.mimeType,
                    sizeBytes = bytes.size,
                    kind = current.kind,
                    notes = current.description,
                    role = current.role,
                ),
                open,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: AssetTransferredOut) {
            // #77 (C12): the asset was transferred out after the chooser listed it; nothing was written.
            return refuse(TransferImportStrings.ASSET_TRANSFERRED_OUT)
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
        } catch (_: AssetTransferredOut) {
            return refuse(TransferImportStrings.ASSET_TRANSFERRED_OUT)
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
                // The suggested name feeds the name only, never the role (#91, C25).
                name = content.suggestedName.orEmpty().take(MAX_REFERENCE_NAME_CHARS),
                // #91 (R91-4): the kind `AddReference` will derive, asked whether it takes a role.
                linkTakesRole = ReferenceKinds.inferFrom(linkPolicy.schemeOf(content.uri.trim())).takesRole,
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

/**
 * #77 (C16, R77-2) — the import screen the intake hosts for a shared Transfer Pack: the same state machine as the
 * Backup screen's door, with the intake's own folder sentence.
 */
internal fun shareTransferImport(
    importPack: ImportTransferPack,
    inbox: TransferPackInbox,
    copy: File?,
    reconcile: ReminderReconcile,
    io: CoroutineContext = Dispatchers.IO,
): TransferImportViewModel = TransferImportViewModel(importPack, inbox, copy, IntakeStrings.NO_FOLDER, reconcile, io = io)

/**
 * #77 (C16): whether a shared stream is a Transfer Pack — a ZIP whose **first local entry** is `transfer-manifest.json`.
 * The content decides, never the file name; anything unreadable as a ZIP is not a pack.
 */
internal fun isTransferPack(source: ByteSource): Boolean = try {
    source.open().use { input -> ZipInputStream(input).nextEntry?.name == TransferPack.MANIFEST_ENTRY }
} catch (_: IOException) {
    false
} catch (_: IllegalArgumentException) {
    false
}
