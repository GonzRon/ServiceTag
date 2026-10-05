package com.loosecannon.servicetag.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.servicetag.R
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetSupply
import com.loosecannon.servicetag.core.model.AttachmentKind
import com.loosecannon.servicetag.core.model.AttachmentKinds
import com.loosecannon.servicetag.core.model.AttachmentProblem
import com.loosecannon.servicetag.core.model.DocumentRole
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.InstalledComponent
import com.loosecannon.servicetag.core.model.InstalledComponentId
import com.loosecannon.servicetag.core.model.ReferenceOwner
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.core.model.SupplyItem
import com.loosecannon.servicetag.core.model.asAttachmentOwner
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
import com.loosecannon.servicetag.l10n.localized
import com.loosecannon.servicetag.ui.attachments.ROLE_HEADER
import com.loosecannon.servicetag.ui.installed.INSTALLED_COMPONENTS_SECTION
import com.loosecannon.servicetag.ui.maintenance.ReminderReconcile
import com.loosecannon.servicetag.ui.nav.ASSETS_LABEL
import com.loosecannon.servicetag.ui.references.DUPLICATE_URI_ON_INSTALLED_COMPONENT
import com.loosecannon.servicetag.ui.references.DUPLICATE_URI_ON_SUPPLY
import com.loosecannon.servicetag.ui.supplies.SUPPLIES_SECTION
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
    val TITLE: String get() = localized(R.string.share_title)
    val RECEIVED: String get() = localized(R.string.share_received)
    val ATTACH_TO: String get() = localized(R.string.share_attach_to)
    val CHOOSE_ASSET: String get() = localized(R.string.share_choose_asset)
    val NAME: String get() = localized(R.string.share_name)
    val DESCRIPTION: String get() = localized(R.string.share_description)
    val TYPE: String get() = localized(R.string.share_type)

    /** #67 (P67-5): the Role header, from its one home beside the role labels — never re-spelled. */
    val ROLE: String get() = ROLE_HEADER
    val SAVE: String get() = localized(R.string.share_save)
    val CANCEL: String get() = localized(R.string.share_cancel)
    val CLOSE: String get() = localized(R.string.share_close)
    val SAVE_AS_NOTE: String get() = localized(R.string.share_save_as_note)
    val NOT_A_LINK: String get() = localized(R.string.share_not_a_link)
    val NO_ASSETS: String get() = localized(R.string.share_no_assets)
    val NO_FOLDER: String get() = localized(R.string.share_no_folder)
    val STREAM_REFUSED: String get() = localized(R.string.share_stream_refused)
    val UNREADABLE: String get() = localized(R.string.share_unreadable)
    val URI_TOO_LONG: String get() = localized(R.string.share_uri_too_long)
    val SCHEME_BLOCKED: String get() = localized(R.string.share_scheme_blocked)
    val DUPLICATE_URI: String get() = localized(R.string.share_duplicate_uri)
    val EMPTY_FILE: String get() = localized(R.string.share_empty_file)
    val TOO_LARGE: String get() = localized(R.string.share_too_large)
    val BLANK_FILE_NAME: String get() = localized(R.string.share_blank_file_name)
    val BLANK_REFERENCE_NAME: String get() = localized(R.string.share_blank_reference_name)
    val CONFIRM_TITLE: String get() = localized(R.string.share_confirm_title)

    /** #93 (R93-5, G1): the form's action back to the picker, trailing the chosen destination's line (#69 C29). */
    val CHANGE: String get() = localized(R.string.share_change)

    /** #69 (C30, C-4; P69-26): the Assets list's line when no asset is maintained here, before any filtering. */
    val NO_ACTIVE_ASSETS: String get() = localized(R.string.share_no_active_assets)

    /** #69 (C30 step 3; P69-24): the search box's hint and accessible name over the installed-component list. */
    val SEARCH_INSTALLED_COMPONENTS: String get() = localized(R.string.share_search_installed_components)

    /** #69 (C30 step 3; P69-25): the same, over the supply list. */
    val SEARCH_SUPPLIES: String get() = localized(R.string.share_search_supplies)

    /** #69 (C28, R69-13): the duplicate by the owner's kind — P69-11/-12 from their one home; an asset's stays. */
    fun duplicateUri(owner: ReferenceOwner): String = when (owner) {
        is ReferenceOwner.OfAsset -> DUPLICATE_URI
        is ReferenceOwner.OfSupplyItem -> DUPLICATE_URI_ON_SUPPLY
        is ReferenceOwner.OfInstalledComponent -> DUPLICATE_URI_ON_INSTALLED_COMPONENT
    }

    /** #69 (C29; P69-20): a path — the asset's name, then each installed component's — joined a pair at a time. */
    fun pathOf(names: List<String>): String =
        names.reduceOrNull { outer, inner -> localized(R.string.share_path_step, outer, inner) }.orEmpty()

    /** #69 (C30 step 5; P69-17): an asset level's first row, the asset itself as the destination. */
    val THIS_ASSET: String get() = localized(R.string.share_this_asset)

    /** #69 (C30 step 5; P69-18): the quiet line under each SupplyItem row on a browsing level. */
    val SUPPLY_SHARED: String get() = localized(R.string.share_supply_shared)

    /** #69 (C30 step 5; P69-19): a component row's click label on a browsing level; the tap opens its level. */
    fun showInside(componentName: String): String = localized(R.string.share_show_inside, componentName)

    /** #69 (C29; P69-21): under a supply destination on the save form, which is the confirmation (no dialog). */
    fun onSupply(supplyName: String): String = localized(R.string.share_on_supply, supplyName)

    /** #69 (C29; P69-22): the saved line after a supply save. */
    fun savedToSupply(supplyName: String): String = localized(R.string.share_saved_to_supply, supplyName)

    fun confirmBody(scheme: String): String = localized(R.string.share_confirm_body, scheme)

    fun savedTo(assetName: String): String = localized(R.string.share_saved_to, assetName)
}

/**
 * Which of the three save paths this share is on. The screen's button text follows from it. #77 (C16, R77-2):
 * [TRANSFER_PACK] is a ZIP whose first local entry is `transfer-manifest.json` — not a save path at all: the intake
 * hosts the Transfer Pack import screen over its copy instead of the form.
 */
internal enum class IntakePath { LINK, BYTES, NOTE, TRANSFER_PACK }

/**
 * #69 (C29, R69-3): where this share is saved — the final selection, the one thing every save arm reads. Each arm
 * carries only what the save form draws and nothing about how it was reached (ownership is the final selection), so a
 * supply picked from its list and one picked while browsing an asset are the same value and write the same row. Ids
 * are strings so the state holds no value class. #93's tapped asset row is the [Asset] arm (C4).
 */
internal sealed interface ShareDestination {
    data class Asset(val assetId: String, val assetName: String) : ShareDestination
    /** [path]: the asset's name, each ancestor component's, then this one's (display only). */
    data class Component(val assetId: String, val componentId: String, val path: List<String>) : ShareDestination
    /** [productLine]: manufacturer · model · part number as C30 builds it (display only). */
    data class Supply(val supplyId: String, val supplyName: String, val productLine: String) : ShareDestination
}

/** #69 (C29): the one owner a destination names; a link is saved on it, a file on its attachment twin (C12). */
internal val ShareDestination.owner: ReferenceOwner get() = when (this) {
    is ShareDestination.Asset -> ReferenceOwner.OfAsset(AssetId(assetId))
    is ShareDestination.Component -> ReferenceOwner.OfInstalledComponent(InstalledComponentId(componentId))
    is ShareDestination.Supply -> ReferenceOwner.OfSupplyItem(SupplyId(supplyId))
}

/** #69 (C29): the save form's destination line — an asset's name, a component's path (P69-20), a supply's name. */
internal val ShareDestination.label: String get() = when (this) {
    is ShareDestination.Asset -> assetName
    is ShareDestination.Component -> IntakeStrings.pathOf(path)
    is ShareDestination.Supply -> supplyName
}

/** #69 (C29): the quiet lines under it — a supply's product line, when it has one, then P69-21; none otherwise. */
internal val ShareDestination.notes: List<String> get() = when (this) {
    is ShareDestination.Asset, is ShareDestination.Component -> emptyList()
    is ShareDestination.Supply -> listOfNotNull(productLine.ifBlank { null }, IntakeStrings.onSupply(supplyName))
}

/** #69 (C29): what the screen says once saved — "Saved to %s" with the destination line, or P69-22 for a supply. */
internal val ShareDestination.savedLine: String get() = when (this) {
    is ShareDestination.Asset, is ShareDestination.Component -> IntakeStrings.savedTo(label)
    is ShareDestination.Supply -> IntakeStrings.savedToSupply(supplyName)
}

/**
 * #69 (C30 step 2): the picker's three lists, Assets the default — a mode, not a filter. Each label is the shipped word
 * for that list, imported from its one home.
 */
internal enum class ShareTargetType {
    ASSETS,
    INSTALLED_COMPONENTS,
    SUPPLIES,
    ;

    /** #102: read when drawn, never held by the enum, so it follows the current language. */
    val label: String get() = when (this) {
        ASSETS -> ASSETS_LABEL
        INSTALLED_COMPONENTS -> INSTALLED_COMPONENTS_SECTION
        SUPPLIES -> SUPPLIES_SECTION
    }
}

/**
 * #69 (C30 step 4): what the installed-component and supply lists are built from, read once with the share — every
 * asset, the held set, every installed component and every SupplyItem. Who is offered is `ShareTargets`' to decide.
 * Read once, while the Assets list beside them stays live: nothing inside the share activity can add, archive or
 * remove a component or a supply, so the read cannot go stale under the person's own hand.
 */
internal data class ShareSources(
    val assets: List<Asset> = emptyList(),
    val held: Set<AssetId> = emptySet(),
    val components: List<InstalledComponent> = emptyList(),
    val supplyItems: List<SupplyItem> = emptyList(),
    /** #69 (C30 step 5): every asset link to a SupplyItem — what an asset's level offers besides its components. */
    val assetSupplies: List<AssetSupply> = emptyList(),
)

/**
 * #69 (C30 step 5): one browsing level, opened from an asset row or, on a level, from a component row. [self] is the
 * level's own destination — P69-17 for an asset, P69-2 for an installed component — exactly the value the direct
 * route gives the same row; [path] is its breadcrumb's segments (P69-20). A level writes nothing: only a chosen
 * destination and Save do.
 */
internal sealed interface ShareLevel {
    val self: ShareDestination
    val path: List<String>

    data class OfAsset(override val self: ShareDestination.Asset) : ShareLevel {
        override val path: List<String> get() = listOf(self.assetName)
    }

    data class OfComponent(override val self: ShareDestination.Component) : ShareLevel {
        override val path: List<String> get() = self.path
    }
}

/** #69 (C29, C30 step 5): a tapped installed-component row is the final selection, carried whole. */
internal val ComponentTarget.destination: ShareDestination.Component
    get() = ShareDestination.Component(assetId.value, componentId.value, path)

/** #69 (C29, C30 step 5): a tapped supply row is the final selection, carried whole. */
internal val SupplyTarget.destination: ShareDestination.Supply
    get() = ShareDestination.Supply(supplyId.value, name, productLine)

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
     * The read-time eligible set — every asset maintained here (#69, C30), read once with the share. #93 (C4): it
     * decides the no-assets dead end and nothing else; it is not drawn, and a choice is never looked up in it.
     */
    val assets: List<ShareDestination.Asset> = emptyList(),
    /** #93 (C4), #69 (C29): the final selection, exactly what every save arm reads; null on the picker step. */
    val destination: ShareDestination? = null,
    /** #69 (C30 step 2): the list the picker shows; a "Change" keeps it, so the person returns to where they chose. */
    val type: ShareTargetType = ShareTargetType.ASSETS,
    /** #69 (C30 step 4): the component and supply lists' rows, read with the share; not drawn as they are. */
    val sources: ShareSources = ShareSources(),
    /**
     * #69 (C30 step 5): the browsing levels open, outermost first — an asset's, then each component's below it; empty
     * on the list. Back takes one off; a chosen destination keeps them, so "Change" returns to the level chosen from.
     */
    val levels: List<ShareLevel> = emptyList(),
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

    /** #69 (C30 step 1): a link or a file may go to a component or a supply; prose has no type control. */
    val typeOffered: Boolean get() = path == IntakePath.LINK || path == IntakePath.BYTES

    /**
     * #69 (C30 steps 3–4): the installed-component list under [query] — the one query, the hosted Assets list's own,
     * passed in, so a type switch never touches it.
     */
    fun componentRows(query: String): ShareTargetList<ComponentTarget> =
        componentList(sources.assets, sources.held, sources.components, query)

    /** #69 (C30 steps 3–4): the supply list under the same [query]. */
    fun supplyRows(query: String): ShareTargetList<SupplyTarget> = supplyList(sources.supplyItems, query)

    /** #69 (C30 step 5): the level the picker draws, or null for the list. */
    val level: ShareLevel? get() = levels.lastOrNull()

    /** #69 (C30 step 5): a browsing level is drawn — Back goes up one level, then to the list. */
    val browsing: Boolean get() = picking && levels.isNotEmpty()

    /** #69 (C30 step 5): what [level] offers under its own destination, from the rows read with the share. */
    fun levelRows(level: ShareLevel): LevelRows = when (level) {
        is ShareLevel.OfAsset -> assetLevelRows(
            AssetId(level.self.assetId), sources.assets, sources.held, sources.components, sources.supplyItems,
            sources.assetSupplies,
        )
        is ShareLevel.OfComponent -> componentLevelRows(
            AssetId(level.self.assetId), InstalledComponentId(level.self.componentId), sources.assets, sources.held,
            sources.components, sources.supplyItems,
        )
    }

    /**
     * **Disabling Save is the intake behaviour** (spec §7), which is why no blank-name sentence is
     * ever drawn on this screen: a destination is chosen, the sanitised name is non-blank, and on a
     * byte share the folder is there.
     */
    val saveEnabled: Boolean get() = !loading &&
        deadEnd == null &&
        !saving &&
        saved == null &&
        destination != null &&
        ReferenceText.sanitiseName(name).isNotEmpty() &&
        (path != IntakePath.BYTES || storeReady) &&
        path != IntakePath.TRANSFER_PACK

    val finished: Boolean get() = saved != null || cancelled

    /** #93 (C5): the picker step is drawn — a loaded, live share with nothing chosen yet; the form otherwise. */
    val picking: Boolean get() = !loading &&
        deadEnd == null &&
        saved == null &&
        destination == null &&
        path != IntakePath.TRANSFER_PACK

    /**
     * #93 (R93-4): Back on the form returns to the picker. Off while saving — Back mid-save finishes the activity as
     * today — and while confirming, where the dialog takes Back.
     */
    val backChangesAsset: Boolean get() = !loading &&
        destination != null &&
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
    /**
     * #69 (C30, C-5): every SupplyItem, read once with the share — an unarchived one keeps a link or a file with no
     * active asset off the no-assets dead end. Empty supplies none.
     */
    private val supplyItems: suspend () -> List<SupplyItem> = { emptyList() },
    /** #69 (C30 step 4): every installed component, read once with the share; the list keeps the current ones. */
    private val installedComponents: suspend () -> List<InstalledComponent> = { emptyList() },
    /** #69 (C30 step 5): every asset link to a SupplyItem, read once with the share, for an asset's level. */
    private val assetSupplies: suspend () -> List<AssetSupply> = { emptyList() },
) : ViewModel() {

    private val _state = MutableStateFlow(ShareIntakeState())
    val state: StateFlow<ShareIntakeState> = _state.asStateFlow()

    /** Set once, by the one read below. Nothing reads it before [ShareIntakeState.loading] clears. */
    private var share: SharedShare? = null

    init {
        viewModelScope.launch {
            // One hop: the provider IPC, the stream read, the asset list, the held set, the installed components,
            // the SupplyItems (the dead end's unarchived count among them) and the store's state are all off the main
            // thread, and the screen commits to nothing until they land together.
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
                    // #69 (C30, R69-3): only an asset maintained here is offered — never an archived or retired one, nor
                    // one transferred out from this phone (rm-5).
                    val held = heldIds()
                    val everyAsset = assets.all()
                    loadedState(
                        content = found.content,
                        choices = shareableAssets(everyAsset, held)
                            .map { ShareDestination.Asset(it.id.value, it.name) }
                            .sortedBy { it.assetName.lowercase() },
                        sources = ShareSources(everyAsset, held, installedComponents(), supplyItems(), assetSupplies()),
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

    /**
     * #93 (C4), #69 (C29): the final selection, carried whole; nothing is looked up. Prose is a journal note, which
     * belongs to an asset, so on the note path only an [ShareDestination.Asset] is taken.
     */
    fun choose(destination: ShareDestination) = _state.update {
        if (it.path == IntakePath.NOTE && destination !is ShareDestination.Asset) it
        else it.copy(destination = destination, message = null)
    }

    /**
     * #69 (C30 step 5): an asset row on the list. On a link or a file it opens the asset's level, where the asset
     * itself is one destination among its supplies and components; prose is an asset's note, so there the row is the
     * choice. Opening a level chooses nothing and writes nothing.
     */
    fun pickAsset(asset: ShareDestination.Asset) {
        if (!_state.value.typeOffered) return choose(asset)
        _state.update { if (it.picking) it.copy(levels = listOf(ShareLevel.OfAsset(asset))) else it }
    }

    /** #69 (C30 step 5): a component row on a level opens its own level, one down. Nothing is chosen or written. */
    fun openComponent(target: ComponentTarget) = _state.update {
        if (it.browsing) it.copy(levels = it.levels + ShareLevel.OfComponent(target.destination)) else it
    }

    /** #69 (C30 step 5): Back on a level — one level up, and from the outermost to the list, as it was left. */
    fun levelUp() = _state.update { if (it.browsing) it.copy(levels = it.levels.dropLast(1)) else it }

    /** #69 (C30 step 2): the type control, on a link or a file only; the query and any choice are left as they are. */
    fun chooseType(type: ShareTargetType) = _state.update { if (it.typeOffered) it.copy(type = type) else it }

    /**
     * #93 (C6): back to the picker — the choice and any refusal cleared, Name, Description, Type and Role kept (each
     * came from the share, not the asset), and #69 (C29) the list type and the levels kept, so the person is back where
     * the destination was chosen. "That is not a link." is not restored. A no-op unless
     * [ShareIntakeState.backChangesAsset].
     */
    fun changeAsset() = _state.update {
        if (it.backChangesAsset) it.copy(destination = null, message = null) else it
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
        val destination = current.destination
        if (destination == null) {
            _state.update { it.copy(saving = false, deadEnd = IntakeStrings.NO_ASSETS) }
            return
        }
        when (current.path) {
            IntakePath.LINK -> saveLink(current, destination, confirmedUnknownScheme)
            IntakePath.BYTES -> saveBytes(current, destination)
            // #69 (C29, N-5): a note is an asset's journal entry; [choose] never takes another kind on this path.
            IntakePath.NOTE -> when (destination) {
                is ShareDestination.Asset -> saveNote(current, destination)
                is ShareDestination.Component, is ShareDestination.Supply ->
                    error("a shared note is saved on an asset only")
            }
            IntakePath.TRANSFER_PACK -> Unit
        }
    }

    private suspend fun saveLink(
        current: ShareIntakeState,
        destination: ShareDestination,
        confirmedUnknownScheme: Boolean,
    ) {
        val uri = (share?.content as? ShareContent.Link)?.uri
            ?: return refuse(IntakeStrings.UNREADABLE)
        val result = try {
            addReference.run(
                destination.owner,
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
            is ReferenceResult.Ok -> succeed(destination)
            is ReferenceResult.Refused -> when (val problem = result.problem) {
                // The one refusal that is a question rather than an answer: the person is asked
                // about the scheme by name, and only their "Save" sets the flag (plan §18.2).
                is ReferenceProblem.UnknownSchemeNeedsConfirmation ->
                    _state.update { it.copy(saving = false, confirming = problem.scheme) }
                ReferenceProblem.DuplicateUri -> refuse(IntakeStrings.duplicateUri(destination.owner))
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
    private suspend fun saveBytes(current: ShareIntakeState, destination: ShareDestination) {
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
                destination.owner.asAttachmentOwner(),
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
            is AttachmentResult.Ok -> succeed(destination)
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
    private suspend fun saveNote(current: ShareIntakeState, destination: ShareDestination.Asset) {
        val prose = (share?.content as? ShareContent.PlainText)?.text.orEmpty()
        try {
            logEvent.run(
                EventCommand(
                    assetId = AssetId(destination.assetId),
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
        succeed(destination)
    }

    private fun succeed(destination: ShareDestination) = _state.update {
        it.copy(saving = false, saved = destination.savedLine)
    }

    private fun refuse(sentence: String) = _state.update {
        it.copy(saving = false, message = sentence)
    }

    private fun ownerGone() = _state.update {
        it.copy(saving = false, deadEnd = IntakeStrings.NO_ASSETS)
    }

    /**
     * The one state the read produces, so a cold start and a warm one cannot differ: it is a pure
     * function of what arrived, the asset list, the rows the other two lists are built from (the
     * unarchived SupplyItem count among them) and the store's state, with nothing read from a field
     * that a second process might have set differently.
     */
    private fun loadedState(
        content: ShareContent,
        choices: List<ShareDestination.Asset>,
        sources: ShareSources,
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
        // #69 (C30, C-5): prose needs an active asset; a link or a file can also go to an unarchived supply.
        val supplies = sources.supplyItems.count { it.isShareable }
        val nowhere = choices.isEmpty() && (base.path == IntakePath.NOTE || supplies == 0)
        return base.copy(
            loading = false,
            assets = choices,
            sources = sources,
            deadEnd = base.deadEnd ?: IntakeStrings.NO_ASSETS.takeIf { nowhere },
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
