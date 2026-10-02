package com.loosecannon.servicetag.share

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AttachmentKind
import com.loosecannon.servicetag.core.model.DocumentRole
import com.loosecannon.servicetag.core.model.InstalledComponent
import com.loosecannon.servicetag.core.model.InstalledComponentId
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.core.model.SupplyItem
import com.loosecannon.servicetag.core.usecase.AddAssetSupplyCommand
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.AssetSupplyResult
import com.loosecannon.servicetag.core.usecase.CompositionInput
import com.loosecannon.servicetag.core.usecase.InstallComponentCommand
import com.loosecannon.servicetag.core.usecase.InstalledComponentResult
import com.loosecannon.servicetag.core.usecase.SupplyItemCommand
import com.loosecannon.servicetag.ui.app
import com.loosecannon.servicetag.ui.asset.AssetRow
import com.loosecannon.servicetag.ui.asset.AssetsState
import com.loosecannon.servicetag.ui.asset.AssetsViewModel
import com.loosecannon.servicetag.ui.asset.EmptyReason
import com.loosecannon.servicetag.ui.awaitText
import com.loosecannon.servicetag.ui.clearInstall
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The intake screen, rendered directly from a [ShareIntakeState] — it needs no `AppGraph`, no Room
 * and no intent, exactly as `AssetTagsSectionTest` renders `TagsSection` rather than standing up
 * the whole app. What is proved here is what the person sees in each state: which ratified
 * sentence, which buttons, and whether Save is offered at all.
 *
 * #69 (row 54): what only a held state shows — the type control switching the one query's list, a level opened and
 * left by Back, a scrolled list coming back — is driven through the real holders instead: the share's own view model
 * and the hosted Assets list over the app's own graph, wired as `ShareIntakeActivity` wires them ([hostTheIntake]).
 * Those cases wipe the install before and after themselves; the others still need none.
 *
 * Emulator only (`emulator-5554`), never a phone.
 */
@RunWith(AndroidJUnit4::class)
class ShareIntakeScreenTest {

    @get:Rule val rule = createComposeRule()

    private val mower = ShareDestination.Asset("asset-1", "Cub Cadet XT1")

    /** #93: the picker step's list — the same asset as one of the tab's rows. */
    private val pickerRows = AssetsState(
        items = listOf(
            AssetRow(asset = Asset(id = AssetId(mower.assetId), name = mower.assetName, createdAt = 1L, updatedAt = 1L)),
        ),
    )

    private var saved = 0
    private var cancelled = 0
    private var confirmed = 0
    private var changes = 0
    private val roles = mutableListOf<DocumentRole?>()
    private val choices = mutableListOf<ShareDestination>()
    private val queries = mutableListOf<String>()
    private val types = mutableListOf<ShareTargetType>()

    private fun form(
        path: IntakePath = IntakePath.LINK,
        received: String = "https://example-mower.invalid/xt1/manual.pdf",
        destination: ShareDestination? = mower,
        name: String = "OEM parts lookup",
        storeReady: Boolean = true,
        message: String? = null,
        confirming: String? = null,
    ) = ShareIntakeState(
        loading = false,
        path = path,
        received = received,
        assets = listOf(mower),
        destination = destination,
        name = name,
        kind = AttachmentKind.DOCUMENT,
        storeReady = storeReady,
        message = message,
        confirming = confirming,
    )

    private fun show(state: ShareIntakeState) = show(mutableStateOf(state))

    /**
     * The state is held, so a case can move the one composition from one share to another; so is the picker's list
     * (#93), so a case can move it from rows to an empty reason, and so is the box's text (#69), so a case can draw a
     * miss.
     */
    private fun show(
        state: MutableState<ShareIntakeState>,
        picker: MutableState<AssetsState> = mutableStateOf(pickerRows),
        query: MutableState<String> = mutableStateOf(""),
    ) {
        rule.setContent {
            ServiceTagTheme {
                ShareIntakeScreen(
                    state = state.value,
                    picker = picker.value,
                    pickerQuery = query.value,
                    onQueryChange = { queries += it },
                    onClearQuery = {},
                    onPickType = {},
                    onToggleComponents = {},
                    onToggleArchived = {},
                    onChooseType = { types += it },
                    onChoose = { choices += it },
                    onChangeAsset = { changes += 1 },
                    onName = {},
                    onDescribe = {},
                    onKind = {},
                    onRole = { roles += it },
                    onSave = { saved += 1 },
                    onConfirm = { confirmed += 1 },
                    onDismissConfirmation = {},
                    onCancel = { cancelled += 1 },
                )
            }
        }
        rule.waitForIdle()
    }

    /**
     * `SectionHeader` renders its title uppercased, so the section labels read as shouted. #93 (C5, C7, C8): the
     * picker step first — the title once, what arrived, "Choose asset", the tab's search box, controls and row, and
     * no Save and no Name — then, chosen, the form: the asset's name alone with "Change", which reaches its callback.
     * #69 (C30 steps 1–2, C-3; C-1): on a link the type control sits under ATTACH TO, and the picker has no Archived
     * chip, since every row it lists is maintained here; the form has no type control.
     */
    @Test fun theScreenDrawsTheRatifiedLabelsAndNothingElse() {
        val state = mutableStateOf(form(destination = null))
        show(state)

        rule.onAllNodesWithText("Save to ServiceTag").assertCountEquals(1)
        rule.onNodeWithText("Save to ServiceTag").assertIsDisplayed()
        rule.onNodeWithText("RECEIVED").assertIsDisplayed()
        rule.onNodeWithText("ATTACH TO").assertIsDisplayed()
        typeControl().assertIsDisplayed()
        rule.onNodeWithText("Choose asset").assertIsDisplayed()
        rule.onNodeWithText("Search assets").assertIsDisplayed()
        rule.onNodeWithText("Type").assertIsDisplayed()
        rule.onNodeWithText("Child assets").assertIsDisplayed()
        rule.onAllNodesWithText("Archived").assertCountEquals(0)
        rule.onNodeWithText("Cub Cadet XT1").assertIsDisplayed()
        rule.onNodeWithText("Cancel").assertIsDisplayed()
        rule.onAllNodesWithText("Save").assertCountEquals(0)
        rule.onAllNodesWithText("Name").assertCountEquals(0)
        rule.onAllNodesWithText("Change").assertCountEquals(0)

        state.value = form()
        rule.waitForIdle()

        rule.onAllNodesWithText("Save to ServiceTag").assertCountEquals(1)
        rule.onNodeWithText("Save to ServiceTag").assertIsDisplayed()
        rule.onNodeWithText("RECEIVED").assertIsDisplayed()
        rule.onNodeWithText("ATTACH TO").assertIsDisplayed()
        rule.onNodeWithText("Cub Cadet XT1").assertIsDisplayed()
        rule.onNodeWithText("Change").assertIsDisplayed()
        rule.onAllNodesWithText("Choose asset").assertCountEquals(0)
        rule.onAllNodesWithText("Search assets").assertCountEquals(0)
        rule.onAllNodesWithContentDescription("Attach to").assertCountEquals(0)
        rule.onNodeWithText("Name").assertIsDisplayed()
        rule.onNodeWithText("Description (optional)").assertIsDisplayed()
        rule.onNodeWithText("Save").assertIsEnabled()
        rule.onNodeWithText("Cancel").assertIsDisplayed()
        // The 2.6 wording the owner refused, and the one #43's mock used.
        rule.onAllNodesWithText("Share to ServiceTag").assertCountEquals(0)

        rule.onNodeWithText("Change").performClick()
        assertEquals(1, changes)
        assertEquals(0, saved)
    }

    @Test fun withNoAssetsTheOnlyThingOfferedIsClose() {
        show(
            ShareIntakeState(
                loading = false,
                deadEnd = "Add an asset in ServiceTag first, then share this again.",
            ),
        )

        rule.onNodeWithText("Add an asset in ServiceTag first, then share this again.")
            .assertIsDisplayed()
        rule.onNodeWithText("Close").assertIsDisplayed()
        rule.onAllNodesWithText("Save").assertCountEquals(0)
        rule.onAllNodesWithText("Choose asset").assertCountEquals(0)
        rule.onAllNodesWithText("Cancel").assertCountEquals(0)

        rule.onNodeWithText("Close").performClick()
        assertEquals(1, cancelled)
        assertEquals(0, saved)
    }

    @Test fun aRefusedStreamGetsItsOwnSentenceAndNotTheReadFailureOne() {
        show(
            ShareIntakeState(
                loading = false,
                deadEnd = "That file cannot be accepted from the app that shared it.",
            ),
        )

        rule.onNodeWithText("That file cannot be accepted from the app that shared it.")
            .assertIsDisplayed()
        rule.onAllNodesWithText("Could not read what was shared").assertCountEquals(0)
        rule.onAllNodesWithText("Save").assertCountEquals(0)
    }

    /** D-20: the byte path is gated by the folder and says so; Save is drawn and disabled. */
    @Test fun aByteShareWithNoFolderShowsTheSentenceAndCannotSave() {
        show(form(path = IntakePath.BYTES, received = "manual.pdf", storeReady = false))

        rule.onNodeWithText(
            "Choose an attachment folder in ServiceTag Settings, then share this again.",
        ).assertIsDisplayed()
        rule.onNodeWithText("Save").assertIsNotEnabled()
        rule.onNodeWithText("Close").assertIsDisplayed()
        rule.onAllNodesWithText("Cancel").assertCountEquals(0)

        rule.onNodeWithText("Save").performClick()
        assertEquals("a disabled Save cannot fire", 0, saved)
    }

    /** The same phone, the same missing folder: a reference needs none, and nothing is said. */
    @Test fun aUriShareOnTheSamePhoneSaysNothingAboutStorage() {
        show(form(path = IntakePath.LINK, storeReady = false))

        rule.onAllNodesWithText(
            "Choose an attachment folder in ServiceTag Settings, then share this again.",
        ).assertCountEquals(0)
        rule.onNodeWithText("Save").assertIsEnabled()
        rule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    /**
     * #93 (R93-9's qualification): a byte share starts on the picker, which draws no "TYPE"; with no folder the picker
     * says D-20's sentence and offers "Close", never "Cancel" or Save (C-2). Once an asset is chosen the form draws the
     * Type control — the assertion relocated from `ShareBoundaryTest`'s byte case.
     */
    @Test fun theTypeControlIsDrawnOnBytesAndNeverOnALink() {
        val state = mutableStateOf(form(path = IntakePath.BYTES, received = "manual.pdf", destination = null))
        show(state)

        rule.onNodeWithText("Choose asset").assertIsDisplayed()
        rule.onAllNodesWithText("TYPE").assertCountEquals(0)

        state.value = form(path = IntakePath.BYTES, received = "manual.pdf", destination = null, storeReady = false)
        rule.waitForIdle()
        rule.onNodeWithText(
            "Choose an attachment folder in ServiceTag Settings, then share this again.",
        ).assertIsDisplayed()
        rule.onNodeWithText("Close").assertIsDisplayed()
        rule.onAllNodesWithText("Cancel").assertCountEquals(0)
        rule.onAllNodesWithText("Save").assertCountEquals(0)

        state.value = form(path = IntakePath.BYTES, received = "manual.pdf")
        rule.waitForIdle()
        rule.onNodeWithText("TYPE").assertIsDisplayed()
        // The seven shipped labels, reused and not re-spelled.
        listOf("Photo", "Label photo", "Receipt", "Manual", "Warranty", "Document", "Other")
            .forEach { rule.onNodeWithText(it).assertIsDisplayed() }
    }

    /**
     * #67, C7 (R67-9), amended by #91 (R91-4): a byte share draws the Role control — the header and
     * the four chips from their one home, "No role" chosen until the person picks — and a pick
     * reaches `onRole`. The same composition moved onto a web link draws it too, on "No role" and
     * with no Type control; moved onto a link that takes no role (`linkTakesRole` false, the
     * default) it draws none of it.
     */
    @Test fun theRoleControlIsDrawnOnBytesAndOnAWebLinkAndNeverOnANoteLink() {
        val state = mutableStateOf(form(path = IntakePath.BYTES, received = "manual.pdf"))
        show(state)

        rule.onNodeWithText("ROLE").performScrollTo().assertIsDisplayed()
        ROLE_CHIPS.forEach { rule.onNodeWithText(it).performScrollTo().assertIsDisplayed() }
        rule.onNodeWithText("No role").assertIsSelected()
        rule.onNodeWithText("Service manual").performScrollTo().performClick()
        assertEquals(listOf<DocumentRole?>(DocumentRole.SERVICE_MANUAL), roles)

        state.value = form(path = IntakePath.LINK).copy(linkTakesRole = true)
        rule.waitForIdle()
        rule.onAllNodesWithText("TYPE").assertCountEquals(0)
        rule.onNodeWithText("ROLE").performScrollTo().assertIsDisplayed()
        ROLE_CHIPS.forEach { rule.onNodeWithText(it).performScrollTo().assertIsDisplayed() }
        rule.onNodeWithText("No role").assertIsSelected()

        state.value = form(path = IntakePath.LINK, received = "joplin://x-callback-url/openNote?id=example")
        rule.waitForIdle()
        rule.onAllNodesWithText("ROLE").assertCountEquals(0)
        ROLE_CHIPS.forEach { rule.onAllNodesWithText(it).assertCountEquals(0) }
    }

    @Test fun aLinkShareHasNoTypeControl() {
        show(form(path = IntakePath.LINK))

        rule.onAllNodesWithText("TYPE").assertCountEquals(0)
        rule.onAllNodesWithText("Manual").assertCountEquals(0)
    }

    /**
     * Disabling Save *is* the intake behaviour, so neither blank-name sentence is ever drawn here:
     * they belong to the use-case layer, which still answers the picker, camera, API and MCP paths.
     */
    @Test fun aBlankNameDisablesSaveAndDrawsNoSentence() {
        show(form(name = "   "))

        rule.onNodeWithText("Save").assertIsNotEnabled()
        rule.onAllNodesWithText("Give the reference a name").assertCountEquals(0)
        rule.onAllNodesWithText("Give the file a name").assertCountEquals(0)
    }

    /**
     * #93 (C3, C7): with nothing chosen the picker is drawn and Save is not; a row's tap carries its id and name, a
     * keystroke reaches the query; its empty states carry no "Add asset" (SPEC:80-81). #69 (C30, C-1, C-4): with no
     * asset maintained here the line is P69-26 alone — no archived count and no "Show archived", since Share lists no
     * archived row — and a miss is the tab's own sentence.
     */
    @Test fun noAssetChosenOffersThePickerAndNoSave() {
        val picker = mutableStateOf(pickerRows)
        show(mutableStateOf(form(destination = null)), picker)

        rule.onAllNodesWithText("Save").assertCountEquals(0)
        rule.onNodeWithText("Cub Cadet XT1").performClick()
        assertEquals(listOf<ShareDestination>(mower), choices)
        rule.onNode(hasSetTextAction()).performTextInput("cub")
        assertEquals(listOf("cub"), queries)

        picker.value = AssetsState(emptyReason = EmptyReason.NO_ASSETS)
        rule.waitForIdle()
        rule.onNodeWithText("No active assets").assertIsDisplayed()
        rule.onAllNodesWithText("No active assets · 2 archived").assertCountEquals(0)
        rule.onAllNodesWithText("Show archived").assertCountEquals(0)
        rule.onAllNodesWithText("Add asset").assertCountEquals(0)

        picker.value = AssetsState(query = "zzz", emptyReason = EmptyReason.NOTHING_MATCHES)
        rule.waitForIdle()
        rule.onNodeWithText("Nothing matches that.").assertIsDisplayed()
        rule.onAllNodesWithText("Add asset").assertCountEquals(0)
        rule.onAllNodesWithText("Save").assertCountEquals(0)
    }

    @Test fun anUnknownSchemeIsAskedAboutByNameBeforeItIsSaved() {
        show(form(received = "zotero://select/items/0", confirming = "zotero"))

        rule.onNodeWithText("Save this link?").assertIsDisplayed()
        rule.onNodeWithText(
            "ServiceTag does not recognise \"zotero\" links. It will be saved as written and " +
                "opened with whatever app claims it.",
        ).assertIsDisplayed()

        // Two "Save" nodes now: the form's and the dialog's. The dialog's is the last one drawn.
        val saves = rule.onAllNodesWithText("Save")
        saves.assertCountEquals(2)
        saves[1].performClick()

        assertEquals(1, confirmed)
        assertEquals("the confirmation never goes through the ordinary Save", 0, saved)
    }

    @Test fun proseIsOfferedAsANoteAndNeverAsALink() {
        show(
            form(
                path = IntakePath.NOTE,
                received = "Replaced the drive belt, took an hour",
                name = "Replaced the drive belt, took an hour",
                message = "That is not a link.",
            ),
        )

        rule.onNodeWithText("That is not a link.").assertIsDisplayed()
        rule.onNodeWithText("Save as a note").assertIsEnabled()
        rule.onAllNodesWithText("Save").assertCountEquals(0)
        rule.onNodeWithText("Cancel").assertIsDisplayed()

        rule.onNodeWithText("Save as a note").performClick()
        assertEquals(1, saved)
    }

    @Test fun theSavedLineNamesTheAssetItWentTo() {
        show(form().copy(saved = "Saved to Cub Cadet XT1"))

        rule.onNodeWithText("Saved to Cub Cadet XT1").assertIsDisplayed()
        rule.onAllNodesWithText("Save").assertCountEquals(0)
        rule.onAllNodesWithText("Choose asset").assertCountEquals(0)
    }

    // --- #69 (B7b; C29, C30, row 54): the type control, the three lists, browsing, the form's destination ----------
    //
    // Fictional fixtures only ("Example …", example.invalid). The cases drawn from a state build their rows here; the
    // cases about what a held state does host the real holders over the app's own graph ([hostTheIntake]).

    private val generator =
        Asset(id = AssetId("asset-generator"), name = "Example Generator", createdAt = 1L, updatedAt = 1L)

    /** The picker's Assets list over [generator] alone, as the hosted list would build it. */
    private val generatorRows = AssetsState(items = listOf(AssetRow(asset = generator)))

    /** A current row fitted in [generator]; positional past the name: no supply, no composition, nothing else set. */
    private fun fitted(id: String, name: String, parent: String? = null) = InstalledComponent(
        InstalledComponentId(id), generator.id, parent?.let(::InstalledComponentId), name, null, emptyList(), "", null,
        null, null, 0, "", 1L, 1L,
    )

    private val batteryItem = SupplyItem(
        id = SupplyId("supply-battery"), name = "Example 12 V Battery", category = "",
        manufacturer = "Example Power Co.", model = "EB-12", partNumber = "EB-12-AGM", preferredUnit = "", notes = "",
        archivedAt = null, createdAt = 1L, updatedAt = 1L, specifications = emptyList(),
    )

    /** "Example Alternator" fitted inside "Example Engine Bay" in [generator]; one supply, "Example 12 V Battery". */
    private val drawnSources = ShareSources(
        assets = listOf(generator),
        components = listOf(
            fitted("component-bay", "Example Engine Bay"),
            fitted("component-alternator", "Example Alternator", parent = "component-bay"),
        ),
        supplyItems = listOf(batteryItem),
    )

    /** The picker step of a share on [path], with [type]'s list chosen over [sources]. */
    private fun picking(
        type: ShareTargetType,
        path: IntakePath = IntakePath.LINK,
        sources: ShareSources = drawnSources,
    ) = form(
        path = path,
        received = if (path == IntakePath.BYTES) "generator-manual.pdf" else aLink.uri,
        destination = null,
    ).copy(
        assets = listOf(ShareDestination.Asset("asset-generator", "Example Generator")),
        type = type,
        sources = sources,
    )

    /** The type control: the clickable node named ATTACH TO (P69-5), whichever list it shows. */
    private fun typeControl(): SemanticsNodeInteraction =
        rule.onNode(hasContentDescription("Attach to") and hasClickAction())

    private fun <T> typeControlValue(key: SemanticsPropertyKey<T>): T? =
        typeControl().fetchSemanticsNode().config.getOrNull(key)

    /** One row of the type control's menu, by its word: clickable, inside the popup. */
    private fun menuItem(label: String): SemanticsNodeInteraction =
        rule.onNode(hasText(label) and hasAnyAncestor(isPopup()) and hasClickAction())

    /** Through the drawn control: open its menu, pick [label]. */
    private fun chooseType(label: String) {
        typeControl().performClick()
        rule.waitForIdle()
        menuItem(label).performClick()
        rule.waitForIdle()
    }

    /** The picker's one search box. */
    private fun searchBox(): SemanticsNodeInteraction = rule.onNode(hasSetTextAction())

    /** A box whose text is exactly [query], whatever spans the field draws it with. */
    private fun holding(query: String): SemanticsMatcher = SemanticsMatcher("the box holds \"$query\"") {
        it.config.getOrNull(SemanticsProperties.EditableText)?.text == query
    }

    /** A node whose tap TalkBack announces as [label] (P69-19's words). */
    private fun hasClickLabel(label: String): SemanticsMatcher = SemanticsMatcher("click label \"$label\"") {
        it.config.getOrNull(SemanticsActions.OnClick)?.label == label
    }

    /** The picker's or the level's one lazy list, scrolled until [matcher] is on screen. */
    private fun onTheList(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(matcher)
        return rule.onNode(matcher)
    }

    /** The top edge of the one node [matcher] names, in the window. */
    private fun top(matcher: SemanticsMatcher): Float = rule.onNode(matcher).fetchSemanticsNode().boundsInRoot.top

    private fun assertTopToBottom(vararg rows: SemanticsMatcher) {
        rows.map(::top).zipWithNext().forEachIndexed { i, (above, below) ->
            assertTrue("${rows[i]} is drawn above ${rows[i + 1]}", above < below)
        }
    }

    // The real holders (C-2's hand-off from B7c2's review): the share's own view model and the hosted Assets list.

    /** The hosted share model, read back by the cases about what a tap left chosen or cancelled. */
    private lateinit var hosted: ShareIntakeViewModel
    private lateinit var focus: FocusManager

    /** The activity's dispatcher: a system Back press arrives here. */
    private lateinit var backs: OnBackPressedDispatcher

    private val aLink = ShareContent.Link("https://example.invalid/generator/manual.pdf", "Example generator manual")

    /** A fresh install around [block], and none left behind for a later class: the cases over the app's graph. */
    private fun onAFreshInstall(block: () -> Unit) {
        clearInstall()
        try {
            block()
        } finally {
            clearInstall()
        }
    }

    /**
     * The intake over the app's own graph, wired as `ShareIntakeActivity` wires it: the share's view model and the
     * hosted `AssetsViewModel(activeOnly = true)` (the one query's holder), and every callback the activity passes —
     * `onChooseType`, `onPickAsset`, `onOpenComponent` and `onLevelUp` among them. The activity's Back rule is composed
     * here verbatim, standing in for its own line, so a system Back press reaches the same model call. [content] stands
     * in for the intent the activity reads.
     */
    private fun hostTheIntake(content: ShareContent) {
        val graph = app.graph
        rule.setContent {
            ServiceTagTheme {
                focus = LocalFocusManager.current
                backs = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
                val model = viewModel(key = "share-intake") {
                    ShareIntakeViewModel(
                        readShare = { SharedShare(content, streamUri = null, bytes = null) },
                        assets = graph.assets,
                        storage = graph.attachmentStorage,
                        addReference = graph.addReference,
                        addAttachment = graph.addAttachment,
                        logEvent = graph.logEvent,
                        today = { "2026-10-02" },
                        zoneId = { "UTC" },
                        heldIds = { graph.transferRecords.heldIds() },
                        supplyItems = { graph.supplyItems.all() },
                        installedComponents = { graph.installedComponents.all() },
                        assetSupplies = { graph.assetSupplies.all() },
                    )
                }
                hosted = model
                val state by model.state.collectAsStateWithLifecycle()
                val picker = viewModel(key = "share-asset-picker") { AssetsViewModel(graph, activeOnly = true) }
                val pickerState by picker.state.collectAsStateWithLifecycle()
                val pickerQuery by picker.query.collectAsStateWithLifecycle()
                BackHandler(enabled = state.backChangesAsset || state.browsing) {
                    if (state.browsing) model.levelUp() else model.changeAsset()
                }
                ShareIntakeScreen(
                    state = state,
                    picker = pickerState,
                    pickerQuery = pickerQuery,
                    onQueryChange = picker::onQueryChange,
                    onClearQuery = picker::clearQuery,
                    onPickType = picker::pickType,
                    onToggleComponents = picker::toggleComponents,
                    onToggleArchived = picker::toggleArchived,
                    onChooseType = model::chooseType,
                    onChoose = model::choose,
                    onPickAsset = model::pickAsset,
                    onOpenComponent = model::openComponent,
                    onLevelUp = model::levelUp,
                    onChangeAsset = model::changeAsset,
                    onName = model::name,
                    onDescribe = model::describe,
                    onKind = model::kind,
                    onRole = model::role,
                    onSave = model::save,
                    onConfirm = model::confirmUnknownScheme,
                    onDismissConfirmation = model::dismissConfirmation,
                    onCancel = model::cancel,
                )
            }
        }
    }

    /** Types [query] into the drawn box, then lets go of the field so the keyboard is out of the way. */
    private fun typeQuery(query: String) {
        searchBox().performTextInput(query)
        rule.runOnIdle { focus.clearFocus(force = true) }
        rule.waitForIdle()
    }

    /**
     * "Example Generator", with "Example Battery Tray" (made of one "Example Tray Bracket", with "Example Fuse Block"
     * fitted inside it) and "Example Alternator" fitted, and linked to "Example 12 V Battery"; "Example UPS" beside it,
     * with nothing. Through the production use cases.
     */
    private fun seedTheGenerator(): Unit = runBlocking {
        val graph = app.graph
        val asset = graph.createAsset.run(AssetCommand(name = "Example Generator"))
        graph.createAsset.run(AssetCommand(name = "Example UPS"))
        val battery = graph.saveSupplyItem.run(
            null,
            SupplyItemCommand(
                "Example 12 V Battery", "", "Example Power Co.", "EB-12", "EB-12-AGM", "", "", emptyList(),
            ),
        ).item
        val bracket = graph.saveSupplyItem.run(
            null,
            SupplyItemCommand("Example Tray Bracket", "", "Example Power Co.", "TB-2", "", "", "", emptyList()),
        ).item
        val linked = graph.addAssetSupply.run(AddAssetSupplyCommand(asset.id, battery.id, "Battery"))
        check(linked is AssetSupplyResult.Ok) { "the supply is linked: $linked" }
        val tray = fit(
            InstallComponentCommand(
                asset.id, null, "Example Battery Tray", null, listOf(CompositionInput(null, bracket.id, "1", "")),
                "", null, "", null,
            ),
        )
        fit(InstallComponentCommand(asset.id, tray.id, "Example Fuse Block", null, emptyList(), "", null, "", null))
        fit(InstallComponentCommand(asset.id, null, "Example Alternator", null, emptyList(), "", null, "", null))
    }

    private suspend fun fit(command: InstallComponentCommand): InstalledComponent {
        val result = app.graph.installComponent.run(command)
        check(result is InstalledComponentResult.Ok) { "the installed component is saved: $result" }
        return result.row
    }

    /** I-8: no link and no file exists on any owner — nothing a link or a file share writes. */
    private fun assertNothingWritten() = runBlocking {
        assertEquals("no link on any owner", 0, app.graph.references.all().size)
        assertEquals("no file on any owner", 0, app.graph.attachments.all().size)
    }

    /**
     * Row 54 (1), C30 steps 1–2 (C-3): a link's or a file's picker stacks the title, RECEIVED, ATTACH TO, the type
     * control, "Choose asset", the search box and the rows, top to bottom. The control is a dropdown named ATTACH TO
     * (P69-5, no word of its own) with Assets chosen, and its menu holds the three lists' words; a pick reaches
     * `onChooseType`. With another list chosen "Choose asset" goes and the control says which.
     */
    @Test fun aLinkOrAFileStacksTheTypeControlUnderAttachToAndChooseAssetOnlyOnAssets() {
        val state = mutableStateOf(picking(ShareTargetType.ASSETS))
        show(state, mutableStateOf(generatorRows))

        onTheList(hasText("Example Generator")).assertIsDisplayed()
        typeControl().assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.DropdownList))
        assertEquals(listOf("Attach to"), typeControlValue(SemanticsProperties.ContentDescription))
        assertEquals("Assets", typeControlValue(SemanticsProperties.StateDescription))
        assertEquals(listOf("Assets"), typeControlValue(SemanticsProperties.Text)?.map { it.text })
        assertTopToBottom(
            hasText("Save to ServiceTag"),
            hasText("RECEIVED"),
            hasText("ATTACH TO"),
            hasContentDescription("Attach to") and hasClickAction(),
            hasText("Choose asset"),
            hasSetTextAction(),
            hasText("Example Generator"),
        )

        typeControl().performClick()
        rule.waitForIdle()
        listOf("Assets", "Installed components", "Supplies").forEach { menuItem(it).assertIsDisplayed() }
        menuItem("Supplies").performClick()
        rule.waitForIdle()
        assertEquals(listOf(ShareTargetType.SUPPLIES), types)

        state.value = picking(ShareTargetType.SUPPLIES)
        rule.waitForIdle()
        onTheList(hasText("Example 12 V Battery")).assertIsDisplayed()
        assertEquals("Supplies", typeControlValue(SemanticsProperties.StateDescription))
        rule.onAllNodesWithText("Choose asset").assertCountEquals(0)

        state.value = picking(ShareTargetType.ASSETS, path = IntakePath.BYTES)
        rule.waitForIdle()
        onTheList(hasText("Example Generator")).assertIsDisplayed()
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        typeControl().assertIsDisplayed()
        assertEquals("Assets", typeControlValue(SemanticsProperties.StateDescription))
        rule.onNodeWithText("Choose asset").assertIsDisplayed()
        rule.onNodeWithText("generator-manual.pdf").assertIsDisplayed()
    }

    /**
     * Row 54 (2), C30 step 4: an installed component's row is its name over a quiet line naming where it sits — the
     * asset, then each ancestor, joined by P69-20 — one row read whole; a top-level row's line is the asset alone. Only
     * installed components are listed, and a tap is the final selection, carried whole (C29).
     */
    @Test fun anInstalledComponentsRowNamesWhereItSitsAndIsTheChoice() {
        show(mutableStateOf(picking(ShareTargetType.INSTALLED_COMPONENTS)), mutableStateOf(generatorRows))

        onTheList(hasText("Example Alternator") and hasText("Example Generator › Example Engine Bay"))
            .assertIsDisplayed()
        onTheList(hasText("Example Engine Bay") and hasText("Example Generator")).assertIsDisplayed()
        rule.onAllNodesWithText("Example 12 V Battery").assertCountEquals(0)
        rule.onAllNodesWithText("Choose asset").assertCountEquals(0)

        rule.onNodeWithText("Example Alternator").performClick()
        assertEquals(
            listOf<ShareDestination>(
                ShareDestination.Component(
                    "asset-generator", "component-alternator",
                    listOf("Example Generator", "Example Engine Bay", "Example Alternator"),
                ),
            ),
            choices,
        )
    }

    /**
     * Row 54 (3), C30 step 4: a SupplyItem's row is the shipped supply row — its name over its product line,
     * manufacturer · model · part number — never P69-18, which is a level's line. A tap is the final selection.
     */
    @Test fun aSupplysRowCarriesItsProductLineAndIsTheChoice() {
        show(mutableStateOf(picking(ShareTargetType.SUPPLIES)), mutableStateOf(generatorRows))

        onTheList(hasText("Example 12 V Battery") and hasText(BATTERY_LINE))
            .assertIsDisplayed()
        rule.onAllNodesWithText("Supply — shared across uses").assertCountEquals(0)
        rule.onAllNodesWithText("Example Engine Bay").assertCountEquals(0)

        rule.onNodeWithText("Example 12 V Battery").performClick()
        assertEquals(
            listOf<ShareDestination>(
                ShareDestination.Supply("supply-battery", "Example 12 V Battery", BATTERY_LINE),
            ),
            choices,
        )
    }

    /**
     * Row 54 (4), C30 step 3 (C-6, C-7), driven through the real holders: each list's box has its own hint and
     * accessible name (Search assets, P69-25, P69-24), and the one query, typed on Supplies, narrows every list and is
     * still in the box after each switch through the drawn control, back to Assets.
     */
    @Test fun eachListHasItsOwnHintAndTheOneQuerySurvivesASwitchThroughTheControl() = onAFreshInstall {
        seedTheGenerator()
        hostTheIntake(aLink)

        rule.awaitText("Example Generator")
        rule.onNodeWithText("Search assets").assertIsDisplayed()
        searchBox().assert(hasContentDescription("Search assets"))

        chooseType("Supplies")
        rule.awaitText("Search supplies")
        searchBox().assert(hasContentDescription("Search supplies"))
        assertEquals("Supplies", typeControlValue(SemanticsProperties.StateDescription))
        rule.awaitText("Example Tray Bracket")

        typeQuery("bat")
        searchBox().assert(holding("bat")).assert(hasContentDescription("Search supplies"))
        rule.awaitText("Example 12 V Battery")
        rule.onAllNodesWithText("Example Tray Bracket").assertCountEquals(0)

        chooseType("Installed components")
        rule.awaitText("Example Battery Tray")
        searchBox().assert(holding("bat")).assert(hasContentDescription("Search installed components"))
        assertEquals("Installed components", typeControlValue(SemanticsProperties.StateDescription))
        rule.onAllNodesWithText("Example Alternator").assertCountEquals(0)

        chooseType("Assets")
        rule.awaitText("Nothing matches that.")
        searchBox().assert(holding("bat")).assert(hasContentDescription("Search assets"))
        assertEquals("Assets", typeControlValue(SemanticsProperties.StateDescription))
        rule.onNodeWithText("Choose asset").assertIsDisplayed()
        rule.onAllNodesWithText("Example Generator").assertCountEquals(0)
        assertNothingWritten()
    }

    /**
     * Row 54 (5), C30 step 4 (C-4): with nothing of a type there before any query, that list's own line — P69-26 for
     * Assets (never the tab's "No assets yet"), P47-2, the supplies' line; a query that hits nothing is the tab's miss
     * line on every list, and only then.
     */
    @Test fun eachListSaysItsOwnEmptyLineAndOnlyAMissSaysNothingMatches() {
        val state = mutableStateOf(picking(ShareTargetType.ASSETS, sources = ShareSources()))
        val query = mutableStateOf("")
        show(state, mutableStateOf(AssetsState(emptyReason = EmptyReason.NO_ASSETS)), query)

        onTheList(hasText("No active assets")).assertIsDisplayed()
        rule.onAllNodesWithText("No assets yet").assertCountEquals(0)
        rule.onAllNodesWithText("Add asset").assertCountEquals(0)
        rule.onAllNodesWithText("Nothing matches that.").assertCountEquals(0)

        state.value = picking(ShareTargetType.INSTALLED_COMPONENTS, sources = ShareSources())
        rule.waitForIdle()
        onTheList(hasText("No installed components")).assertIsDisplayed()
        rule.onAllNodesWithText("Nothing matches that.").assertCountEquals(0)

        state.value = picking(ShareTargetType.SUPPLIES, sources = ShareSources())
        rule.waitForIdle()
        onTheList(hasText("No supply items yet. Add one under Maintenance › Supplies.")).assertIsDisplayed()
        rule.onAllNodesWithText("Nothing matches that.").assertCountEquals(0)

        query.value = "zzz"
        state.value = picking(ShareTargetType.SUPPLIES)
        rule.waitForIdle()
        onTheList(hasText("Nothing matches that.")).assertIsDisplayed()
        rule.onAllNodesWithText("No supply items yet. Add one under Maintenance › Supplies.").assertCountEquals(0)
        rule.onAllNodesWithText("Example 12 V Battery").assertCountEquals(0)

        state.value = picking(ShareTargetType.INSTALLED_COMPONENTS)
        rule.waitForIdle()
        onTheList(hasText("Nothing matches that.")).assertIsDisplayed()
        rule.onAllNodesWithText("No installed components").assertCountEquals(0)
        rule.onAllNodesWithText("Example Alternator").assertCountEquals(0)
    }

    /**
     * Row 54 (6), C30 step 5, through the real holders: an asset row on a link share opens its level, writing nothing
     * and choosing nothing — ATTACH TO, the drawn Back beside the breadcrumb (the asset's name), then P69-17 first,
     * then the asset's own SupplyItem links over P69-18, then its top-level installed components, each opening its
     * level (P69-19). No title, no type control and no list on a level (C30 step 1).
     */
    @Test fun anAssetRowOpensItsLevelWithItselfFirstThenItsSuppliesThenItsComponents() = onAFreshInstall {
        seedTheGenerator()
        hostTheIntake(aLink)
        rule.awaitText("Example Generator")

        rule.onNodeWithText("Example Generator").performClick()
        rule.awaitText("This asset")

        rule.onNodeWithText("ATTACH TO").assertIsDisplayed()
        rule.onNodeWithContentDescription("Back").assertIsDisplayed()
        rule.onNodeWithText("Example Generator").assertIsDisplayed()
        val own = hasText("This asset")
        val battery = hasText("Example 12 V Battery") and hasText("Supply — shared across uses")
        val tray = hasText("Example Battery Tray") and hasClickLabel("Show what is inside Example Battery Tray")
        val alternator = hasText("Example Alternator") and hasClickLabel("Show what is inside Example Alternator")
        listOf(own, battery, tray, alternator).forEach { onTheList(it).assertIsDisplayed() }
        assertTopToBottom(own, battery, tray)
        assertTopToBottom(battery, alternator)

        rule.onAllNodesWithText("Example Tray Bracket").assertCountEquals(0)
        rule.onAllNodesWithText("Example Fuse Block").assertCountEquals(0)
        rule.onAllNodesWithText(BATTERY_LINE).assertCountEquals(0)
        rule.onAllNodesWithText("Example UPS").assertCountEquals(0)
        rule.onAllNodesWithText("Save to ServiceTag").assertCountEquals(0)
        rule.onAllNodesWithText("Choose asset").assertCountEquals(0)
        rule.onAllNodesWithContentDescription("Attach to").assertCountEquals(0)
        assertNull("opening a level chooses nothing", hosted.state.value.destination)
        assertNothingWritten()
    }

    /**
     * Row 54 (7), C30 step 5, through the real holders: a component row on a level opens the level inside it — P47-5
     * over P69-2 first, then the SupplyItems its link and composition name (P69-18), then its current children — under
     * the breadcrumb joined by P69-20. The drawn Back goes up one level at a time, and from the outermost to the list
     * with its type and its query as they were left.
     */
    @Test fun aComponentRowOnALevelOpensTheLevelInsideItAndBackGoesUpOneAtATime() = onAFreshInstall {
        seedTheGenerator()
        hostTheIntake(aLink)
        rule.awaitText("Example UPS")
        typeQuery("gen")
        rule.waitUntil(SETTLE_MS) { rule.onAllNodesWithText("Example UPS").fetchSemanticsNodes().isEmpty() }
        rule.onAllNodesWithText("Example UPS").assertCountEquals(0)

        rule.onNodeWithText("Example Generator").performClick()
        rule.awaitText("This asset")
        onTheList(hasText("Example Battery Tray")).performClick()
        rule.awaitText("Inside Example Battery Tray")

        rule.onNodeWithText("ATTACH TO").assertIsDisplayed()
        rule.onNodeWithText("Example Generator › Example Battery Tray").assertIsDisplayed()
        rule.onNodeWithText("Inside Example Battery Tray").assertIsDisplayed()
        val own = hasText("This installed component")
        val bracket = hasText("Example Tray Bracket") and hasText("Supply — shared across uses")
        val fuse = hasText("Example Fuse Block") and hasClickLabel("Show what is inside Example Fuse Block")
        listOf(own, bracket, fuse).forEach { onTheList(it).assertIsDisplayed() }
        assertTopToBottom(hasText("Inside Example Battery Tray"), own, bracket, fuse)
        rule.onAllNodesWithText("This asset").assertCountEquals(0)
        rule.onAllNodesWithText("Example Alternator").assertCountEquals(0)
        rule.onAllNodesWithText("Example 12 V Battery").assertCountEquals(0)

        rule.onNodeWithContentDescription("Back").performClick()
        rule.awaitText("This asset")
        rule.onNodeWithText("Example Generator").assertIsDisplayed()
        rule.onAllNodesWithText("Inside Example Battery Tray").assertCountEquals(0)

        rule.onNodeWithContentDescription("Back").performClick()
        rule.awaitText("Choose asset")
        searchBox().assert(holding("gen"))
        assertEquals("Assets", typeControlValue(SemanticsProperties.StateDescription))
        rule.awaitText("Example Generator")
        rule.onAllNodesWithText("Example UPS").assertCountEquals(0)
        rule.onAllNodesWithText("This asset").assertCountEquals(0)
        assertNothingWritten()
    }

    /**
     * Row 54 (8), C29, through the real holders: "This installed component" on a level opens the save form — no dialog
     * — on the component's path joined by P69-20, with "Change" and no supply sentence; Cancel there ends the share and
     * nothing is written (#43's I-8), as no tap on the way did.
     */
    @Test fun choosingThisInstalledComponentOpensTheFormOnItsPathAndCancelWritesNothing() = onAFreshInstall {
        seedTheGenerator()
        hostTheIntake(aLink)
        rule.awaitText("Example Generator")
        rule.onNodeWithText("Example Generator").performClick()
        rule.awaitText("This asset")
        onTheList(hasText("Example Battery Tray")).performClick()
        rule.awaitText("This installed component")
        onTheList(hasText("This installed component")).performClick()
        rule.awaitText("Change")

        rule.onNodeWithText("Example Generator › Example Battery Tray").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Change").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Save").performScrollTo().assertIsEnabled()
        rule.onAllNodesWithText("This will be saved on the supply", substring = true).assertCountEquals(0)
        rule.onAllNodes(isDialog()).assertCountEquals(0)
        val chosen = hosted.state.value.destination
        assertTrue("the level's own row is the component: $chosen", chosen is ShareDestination.Component)
        assertEquals(listOf("Example Generator", "Example Battery Tray"), (chosen as ShareDestination.Component).path)

        rule.onNodeWithText("Cancel").performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue("Cancel ends the share", hosted.state.value.cancelled)
        assertNothingWritten()
    }

    /**
     * Row 54 (9), C29: a SupplyItem destination's form says where the file or link goes — the supply's name with
     * "Change", its product line, then P69-21 — and that is the confirmation: no dialog. Once saved, P69-22.
     */
    @Test fun aSupplyDestinationsFormSaysItIsSavedOnTheSupplyWithNoDialog() {
        val supply = ShareDestination.Supply("supply-battery", "Example 12 V Battery", BATTERY_LINE)
        val sheet = form(
            received = "https://example.invalid/generator/battery-data-sheet.pdf",
            destination = supply,
            name = "Example battery data sheet",
        )
        val state = mutableStateOf(sheet)
        show(state, mutableStateOf(generatorRows))

        val onSupply =
            "This will be saved on the supply Example 12 V Battery and available wherever that supply is used."
        rule.onNodeWithText("Example 12 V Battery").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Change").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(BATTERY_LINE).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(onSupply).performScrollTo().assertIsDisplayed()
        assertTopToBottom(hasText("Example 12 V Battery"), hasText(BATTERY_LINE), hasText(onSupply))
        rule.onNodeWithText("Save").performScrollTo().assertIsEnabled()
        rule.onAllNodes(isDialog()).assertCountEquals(0)
        rule.onAllNodesWithText("Save this link?").assertCountEquals(0)

        state.value = sheet.copy(saved = supply.savedLine)
        rule.waitForIdle()
        rule.onNodeWithText("Saved to supply Example 12 V Battery.").assertIsDisplayed()
        rule.onAllNodesWithText("Save").assertCountEquals(0)
        rule.onAllNodesWithText("Saved to Example 12 V Battery").assertCountEquals(0)
    }

    /**
     * Row 54 (10), C30 step 1, through the real holders: prose keeps today's step — ATTACH TO, "Choose asset", the
     * search box and the rows, no type control — and an asset row is the choice: the note form opens, no level.
     */
    @Test fun proseKeepsTodaysStepAndAnAssetRowOpensTheNoteForm() = onAFreshInstall {
        seedTheGenerator()
        hostTheIntake(ShareContent.PlainText("Ran the generator for ten minutes"))

        rule.awaitText("Example Generator")
        rule.onNodeWithText("ATTACH TO").assertIsDisplayed()
        rule.onNodeWithText("Choose asset").assertIsDisplayed()
        rule.onNodeWithText("That is not a link.").assertIsDisplayed()
        rule.onNodeWithText("Search assets").assertIsDisplayed()
        rule.onAllNodesWithContentDescription("Attach to").assertCountEquals(0)
        rule.onAllNodesWithText("Installed components").assertCountEquals(0)
        rule.onAllNodesWithText("Supplies").assertCountEquals(0)

        rule.onNodeWithText("Example Generator").performClick()
        rule.awaitText("Save as a note")
        rule.onNodeWithText("Save as a note").performScrollTo().assertIsEnabled()
        rule.onNodeWithText("Example Generator").performScrollTo().assertIsDisplayed()
        rule.onAllNodesWithText("This asset").assertCountEquals(0)
        val chosen = hosted.state.value.destination
        assertTrue("prose chooses the asset itself: $chosen", chosen is ShareDestination.Asset)
        assertEquals("Example Generator", (chosen as ShareDestination.Asset).assetName)
    }

    /**
     * Row 54 (11), C30 step 4 (C-1), through the hosted Assets list: Share offers only assets maintained here, so it
     * draws no Archived chip — the Type and Child assets chips stay — and lists neither an archived nor a retired
     * asset.
     */
    @Test fun theSharePickerHasNoArchivedChipAndOffersNoArchivedOrRetiredAsset() = onAFreshInstall {
        runBlocking {
            val graph = app.graph
            graph.createAsset.run(AssetCommand(name = "Example Generator"))
            val archived = graph.createAsset.run(AssetCommand(name = "Example Old Generator"))
            graph.archiveAsset.run(archived.id)
            val retired = graph.createAsset.run(AssetCommand(name = "Example Retired Generator"))
            graph.retireAsset.retire(retired.id, "2026-09-01")
        }
        hostTheIntake(aLink)

        rule.awaitText("Example Generator")
        rule.onNodeWithText("Type").assertIsDisplayed()
        rule.onNodeWithText("Child assets").assertIsDisplayed()
        rule.onAllNodesWithText("Archived").assertCountEquals(0)
        rule.onAllNodesWithText("Show archived").assertCountEquals(0)
        rule.onAllNodesWithText("Example Old Generator").assertCountEquals(0)
        rule.onAllNodesWithText("Example Retired Generator").assertCountEquals(0)
        rule.onAllNodesWithText("Retired").assertCountEquals(0)
    }

    /**
     * Row 54 (12), C30 step 5 (C-8) — the one proof of it, since the JVM cannot see a scroll: scrolled far down the
     * Assets list under a query, an asset's level and back — by the drawn Back, then by a system Back press into the
     * same holders — finds the same rows where they were, and at the top the same type and the same query.
     */
    @Test fun aScrolledListComesBackFromALevelWhereItWasLeftWithItsTypeAndQuery() = onAFreshInstall {
        val names = (1..60).map { "Example Asset %02d".format(it) }
        runBlocking {
            app.graph.createAsset.run(AssetCommand(name = "Example Air Compressor"))
            names.forEach { app.graph.createAsset.run(AssetCommand(name = it)) }
        }
        hostTheIntake(aLink)
        rule.awaitText("Example Air Compressor")
        typeQuery("asset")
        rule.waitUntil(SETTLE_MS) { rule.onAllNodesWithText("Example Air Compressor").fetchSemanticsNodes().isEmpty() }
        rule.onAllNodesWithText("Example Air Compressor").assertCountEquals(0)

        val far = "Example Asset 30"
        onTheList(hasText(far)).assertIsDisplayed()
        val left = visibleRows(names)
        assertFalse("the list is scrolled down: $left", names.first() in left)
        val gone = left.keys.first { it != far }

        val drawnBack = { rule.onNodeWithContentDescription("Back").performClick(); Unit }
        val systemBack = { rule.runOnUiThread { backs.onBackPressed() } }
        listOf(drawnBack, systemBack).forEach { back ->
            rule.onNodeWithText(far).performClick()
            rule.awaitText("This asset")
            rule.onNodeWithText(far).assertIsDisplayed()
            rule.onAllNodesWithText(gone).assertCountEquals(0)

            back()
            rule.waitUntil(SETTLE_MS) { rule.onAllNodesWithText("This asset").fetchSemanticsNodes().isEmpty() }
            rule.waitForIdle()
            val found = visibleRows(names)
            assertEquals("the same first row is showing", left.keys.first(), found.keys.first())
            val both = left.keys.intersect(found.keys)
            assertTrue("rows showing before and after: $left / $found", both.size >= 2)
            both.forEach { assertEquals("$it has not moved", left.getValue(it), found.getValue(it), 1f) }
            rule.onNodeWithText(far).assertIsDisplayed()
        }

        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        rule.awaitText(names.first())
        searchBox().assert(holding("asset"))
        assertEquals("Assets", typeControlValue(SemanticsProperties.StateDescription))
        rule.onNodeWithText("Choose asset").assertIsDisplayed()
        rule.onAllNodesWithText("Example Air Compressor").assertCountEquals(0)
        assertNothingWritten()
    }

    /** The rows of [names] the picker's list shows, top to bottom, each with its top edge in the window. */
    private fun visibleRows(names: List<String>): LinkedHashMap<String, Float> {
        val window = rule.onNode(hasScrollToIndexAction()).fetchSemanticsNode().boundsInRoot
        return names.mapNotNull { name ->
            rule.onAllNodesWithText(name).fetchSemanticsNodes().singleOrNull()?.boundsInRoot?.let { name to it }
        }
            .filter { (_, bounds) -> bounds.height > 0f && bounds.bottom > window.top && bounds.top < window.bottom }
            .sortedBy { (_, bounds) -> bounds.top }
            .associateTo(LinkedHashMap()) { (name, bounds) -> name to bounds.top }
    }
}

/** "Example 12 V Battery"'s product line as Share draws it: manufacturer · model · part number (C30 step 4). */
private const val BATTERY_LINE = "Example Power Co. · EB-12 · EB-12-AGM"

/** How long a case waits for a level to leave the screen after Back. */
private const val SETTLE_MS = 10_000L

/** The four Role chips (P67-6, P67-2/3/4), reused from their one home and never re-spelled. */
private val ROLE_CHIPS = listOf("No role", "Purchase invoice or receipt", "User manual", "Service manual")
