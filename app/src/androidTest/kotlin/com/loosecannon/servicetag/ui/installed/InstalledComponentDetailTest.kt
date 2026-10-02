package com.loosecannon.servicetag.ui.installed

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Dp
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.attachments.DocumentTreeRoot
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.InstalledComponent
import com.loosecannon.servicetag.core.model.ReferenceOwner
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.core.model.SupplyItem
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.model.TransferRecord
import com.loosecannon.servicetag.core.ports.ByteSource
import com.loosecannon.servicetag.core.usecase.AddAttachmentCommand
import com.loosecannon.servicetag.core.usecase.AddReferenceCommand
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.AttachmentResult
import com.loosecannon.servicetag.core.usecase.CompositionInput
import com.loosecannon.servicetag.core.usecase.InstallComponentCommand
import com.loosecannon.servicetag.core.usecase.InstalledComponentResult
import com.loosecannon.servicetag.core.usecase.ReferenceResult
import com.loosecannon.servicetag.core.usecase.SupplyItemCommand
import com.loosecannon.servicetag.ui.app
import com.loosecannon.servicetag.ui.asset.AssetDetailScreen
import com.loosecannon.servicetag.ui.awaitText
import com.loosecannon.servicetag.ui.clearInstall
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** How long a case waits for a Room flow to reach the screen, or for the screen to go back. */
private const val WAIT_MS = 10_000L

/**
 * #69 (C27; row 52): the installed-component screen drawn over the app's own graph. The state it draws is row 51's, on
 * the JVM; what only a device shows is the top bar's title, P69-2 over the component's own Documents and References,
 * each SupplyItem's group (P69-3, the quiet line P69-4, then its sections drawn with **no** add or edit action — the
 * composable passes `readOnly = true` there, which no JVM test can see), a held asset's own sections drawn read-only,
 * a group heading's tap, the way back when the component's asset is deleted, and the row sheet's P69-1 closing the
 * sheet before the screen opens, so Back finds the asset with no sheet over it.
 *
 * A section header draws its words upper-case, and every SupplyItem group repeats DOCUMENTS and REFERENCES, so a group
 * is found by its own heading and by the rows drawn between that heading and the next one, never by a header alone.
 * Every sentence comes from its one home (`InstalledComponentStrings.kt`), the section words from the sections.
 *
 * Every case reaches the store, so each wipes the install before and after itself; the cases that draw a file point the
 * attachment seam at a file-backed tree, as `SupplySurfacesTest` does. Fixtures are fictional. Emulator only, never a
 * phone.
 */
@RunWith(AndroidJUnit4::class)
class InstalledComponentDetailTest {

    @get:Rule val rule = createComposeRule()

    private val backs = AtomicInteger(0)
    private val openedSupply = AtomicReference<String?>(null)

    @Before fun freshInstall() = clearInstall()

    @After fun nothingLeftBehind() = clearInstall()

    private fun asset(): Asset = runBlocking { app.graph.createAsset.run(AssetCommand(name = "Example UPS")) }

    private fun item(name: String): SupplyItem = runBlocking {
        app.graph.saveSupplyItem.run(null, SupplyItemCommand(name, "", "", "", "", "", "", emptyList())).item
    }

    private fun install(
        asset: Asset,
        name: String,
        supplyId: SupplyId? = null,
        entries: List<SupplyItem> = emptyList(),
    ): InstalledComponent = runBlocking {
        val result = app.graph.installComponent.run(
            InstallComponentCommand(
                asset.id, null, name, supplyId,
                entries.map { CompositionInput(null, it.id, "1", "") },
                "", null, "", null,
            ),
        )
        check(result is InstalledComponentResult.Ok) { "the installed component is saved: $result" }
        result.row
    }

    private fun file(owner: AttachmentOwner, name: String) = runBlocking {
        val added = app.graph.addAttachment.run(
            owner,
            AddAttachmentCommand(
                displayName = name, mimeType = "application/pdf", sizeBytes = 4L, capturedOn = "2026-05-01",
            ),
            ByteSource { "%PDF".byteInputStream() },
        )
        check(added is AttachmentResult.Ok) { "the file is added: $added" }
    }

    private fun link(owner: ReferenceOwner, name: String, path: String) = runBlocking {
        val added = app.graph.addReference.run(
            owner,
            AddReferenceCommand(uri = "https://example.invalid/$path", displayName = name),
        )
        check(added is ReferenceResult.Ok) { "the link is added: $added" }
    }

    /** An OUT record on [asset]: it is held from here on (#77); `clearInstall` takes the record. */
    private fun hold(asset: Asset) = runBlocking {
        app.graph.transferRecords.append(
            TransferRecord(
                id = "out-installed-1", assetId = asset.id, kind = TransferKind.OUT,
                packId = "0f1e2d3c-4b5a-4968-8776-655443322110", lineage = emptyList(), at = 1_790_510_400_000L,
                packSha256 = "ab".repeat(32), nameSnapshot = asset.name, note = "",
            ),
        )
    }

    /** The screen as the root draws it; its Back and group taps are recorded. Returns once P69-2 is drawn. */
    private fun drawScreen(component: InstalledComponent) {
        rule.setContent {
            ServiceTagTheme {
                InstalledComponentDetailScreen(
                    graph = app.graph,
                    componentId = component.id.value,
                    onBack = { backs.incrementAndGet() },
                    onOpenSupply = { openedSupply.set(it) },
                    onOpenSettings = {},
                )
            }
        }
        rule.awaitText(THIS_INSTALLED_COMPONENT)
    }

    private fun top(text: String): Dp = rule.onNodeWithText(text).getUnclippedBoundsInRoot().top

    private fun quietLineTop(index: Int): Dp =
        rule.onAllNodesWithText(OPEN_THE_SUPPLY_TO_CHANGE)[index].getUnclippedBoundsInRoot().top

    /** A reference row's overflow, found by the row whose name it sits beside. */
    private fun overflowOf(linkName: String) =
        rule.onNode(hasContentDescription("More") and hasParent(hasText(linkName)))

    /** C27: the top bar's title is the component's own name, above P69-2; nothing names its asset. */
    @Test fun theTitleIsTheComponentsName() {
        val tray = install(asset(), "Example Battery Tray")
        drawScreen(tray)
        rule.awaitText("Example Battery Tray")

        rule.onNodeWithText("Example Battery Tray").assertIsDisplayed()
        rule.onNodeWithContentDescription("Back").assertIsDisplayed()
        rule.onAllNodesWithText("Example UPS").assertCountEquals(0)
        check(top("Example Battery Tray") < top(THIS_INSTALLED_COMPONENT)) { "the title is above P69-2" }
    }

    /**
     * C27 (1): P69-2, then DOCUMENTS, then REFERENCES, writable on an asset that is not held — with no folder, the
     * Documents card's "Open settings" and References' "Add link" are each drawn once.
     */
    @Test fun itsOwnHeadingComesFirstThenDocumentsThenReferences() {
        val tray = install(asset(), "Example Battery Tray")
        drawScreen(tray)
        rule.awaitText("Open settings")

        val heading = top(THIS_INSTALLED_COMPONENT)
        val documents = top("DOCUMENTS")
        val references = top("REFERENCES")
        check(heading < documents && documents < references) {
            "P69-2 ($heading), then Documents ($documents), then References ($references)"
        }
        rule.onAllNodesWithText("Open settings").assertCountEquals(1)
        rule.onAllNodesWithText("Add link").assertCountEquals(1)
    }

    /** C27 (1): with a folder, the empty own sections say the shipped lines and offer every add action. */
    @Test fun emptyOwnSectionsSayTheShippedLinesAndOfferTheAddActions() {
        useAFileBackedTree()
        val tray = install(asset(), "Example Battery Tray")
        drawScreen(tray)
        rule.awaitText("Add file")

        rule.onNodeWithText("No documents yet").assertExists()
        rule.onNodeWithText("No references yet").assertExists()
        rule.onNodeWithText("Take photo").assertExists()
        rule.onNodeWithText("Add link").assertExists()
        rule.onAllNodesWithText("Open settings").assertCountEquals(0)
    }

    /**
     * C27 (2): each SupplyItem's group is P69-3 naming it, then P69-4, then its own file and link, below the
     * component's own sections; only the component's own sections offer an add action or a file's overflow, and a
     * group's link offers "Open" and nothing else.
     */
    @Test fun eachGroupSaysFromItsNameAndTheQuietLineAndOffersNoAddOrEdit() {
        useAFileBackedTree()
        val battery = item("Example 12 V Battery")
        val strap = item("Example Battery Strap")
        val tray = install(asset(), "Example Battery Tray", supplyId = battery.id, entries = listOf(strap))
        file(AttachmentOwner.OfInstalledComponent(tray.id), "Example tray photo.pdf")
        link(ReferenceOwner.OfInstalledComponent(tray.id), "Example tray fitting notes", "tray-fitting")
        file(AttachmentOwner.OfSupplyItem(battery.id), "Example battery manual.pdf")
        link(ReferenceOwner.OfSupplyItem(battery.id), "Example battery data sheet", "battery-data")
        file(AttachmentOwner.OfSupplyItem(strap.id), "Example strap guide.pdf")
        drawScreen(tray)
        listOf(
            "Example tray photo.pdf", "Example tray fitting notes", "Example battery manual.pdf",
            "Example battery data sheet", "Example strap guide.pdf", "Add file",
        ).forEach { rule.awaitText(it) }

        val fromBattery = fromSupply("Example 12 V Battery")
        val fromStrap = fromSupply("Example Battery Strap")
        rule.onAllNodesWithText(fromBattery).assertCountEquals(1)
        rule.onAllNodesWithText(fromStrap).assertCountEquals(1)
        rule.onAllNodesWithText(OPEN_THE_SUPPLY_TO_CHANGE).assertCountEquals(2)
        val tops = listOf(
            top("Example tray fitting notes"), top(fromBattery), quietLineTop(0), top("Example battery manual.pdf"),
            top("Example battery data sheet"), top(fromStrap), quietLineTop(1), top("Example strap guide.pdf"),
        )
        check(tops.zipWithNext().all { (above, below) -> above < below }) {
            "own rows, then each group's heading, quiet line and rows, top to bottom: $tops"
        }

        rule.onAllNodesWithText("Add file").assertCountEquals(1)
        rule.onAllNodesWithText("Take photo").assertCountEquals(1)
        rule.onAllNodesWithText("Add link").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("More for Example tray photo.pdf").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("More for Example battery manual.pdf").assertCountEquals(0)
        rule.onAllNodesWithContentDescription("More for Example strap guide.pdf").assertCountEquals(0)

        overflowOf("Example battery data sheet").performScrollTo().performClick()
        rule.onNodeWithText("Open").assertIsDisplayed()
        rule.onAllNodesWithText("Edit").assertCountEquals(0)
        rule.onAllNodesWithText("Remove").assertCountEquals(0)
    }

    /**
     * C27 (2), R69-8: one group per SupplyItem, the direct link's first, then the composition's as it lists them,
     * each drawn once however often it is named; with no folder only the component's own Documents draws the card.
     */
    @Test fun theLinksGroupComesFirstThenTheEntriesEachSupplyOnce() {
        val battery = item("Example 12 V Battery")
        val strap = item("Example Battery Strap")
        val cover = item("Example Terminal Cover")
        val pack = install(
            asset(), "Example Battery Pack", supplyId = battery.id, entries = listOf(strap, battery, cover, strap),
        )
        drawScreen(pack)
        rule.awaitText(fromSupply("Example Terminal Cover"))

        val headings = listOf(battery, strap, cover).map { fromSupply(it.name) }
        headings.forEach { rule.onAllNodesWithText(it).assertCountEquals(1) }
        rule.onAllNodesWithText(OPEN_THE_SUPPLY_TO_CHANGE).assertCountEquals(3)
        val tops = listOf(top(THIS_INSTALLED_COMPONENT)) + headings.map(::top)
        check(tops.zipWithNext().all { (above, below) -> above < below }) {
            "P69-2, then the link's group, then the entries' groups: $tops"
        }
        rule.onAllNodesWithText("Open settings").assertCountEquals(1)
        rule.onAllNodesWithText("Add link").assertCountEquals(1)
    }

    /** C27 (2): an archived SupplyItem's group is drawn, with P69-4 and its link under its heading. */
    @Test fun anArchivedSupplyItemsGroupIsDrawn() {
        val battery = item("Example 12 V Battery")
        link(ReferenceOwner.OfSupplyItem(battery.id), "Example battery data sheet", "battery-data")
        val tray = install(asset(), "Example Battery Tray", entries = listOf(battery))
        runBlocking { app.graph.archiveSupplyItem.run(battery.id, true) }
        drawScreen(tray)
        rule.awaitText("Example battery data sheet")

        val heading = fromSupply("Example 12 V Battery")
        rule.onAllNodesWithText(heading).assertCountEquals(1)
        rule.onAllNodesWithText(OPEN_THE_SUPPLY_TO_CHANGE).assertCountEquals(1)
        check(top(heading) < quietLineTop(0) && quietLineTop(0) < top("Example battery data sheet")) {
            "the archived item's heading, then P69-4, then its link"
        }
    }

    /** C27 (2): a group heading's tap opens that SupplyItem, the second group's its own, and the screen stays. */
    @Test fun aGroupHeadingsTapOpensThatSupplyItem() {
        val battery = item("Example 12 V Battery")
        val strap = item("Example Battery Strap")
        val tray = install(asset(), "Example Battery Tray", supplyId = battery.id, entries = listOf(strap))
        drawScreen(tray)
        rule.awaitText(fromSupply("Example Battery Strap"))

        rule.onNodeWithText(fromSupply("Example Battery Strap")).performScrollTo().performClick()
        assertEquals(strap.id.value, openedSupply.get())
        rule.onNodeWithText(fromSupply("Example 12 V Battery")).performScrollTo().performClick()
        assertEquals(battery.id.value, openedSupply.get())
        assertEquals(0, backs.get())
    }

    /**
     * C27 (1), #77: a held asset's component draws its own file and link and nothing that writes — no add action, no
     * folder card, no file overflow, and a link that offers "Open" only.
     */
    @Test fun aHeldAssetsOwnSectionsAreReadOnlyToo() {
        useAFileBackedTree()
        val ups = asset()
        val battery = item("Example 12 V Battery")
        val tray = install(ups, "Example Battery Tray", supplyId = battery.id)
        file(AttachmentOwner.OfInstalledComponent(tray.id), "Example tray photo.pdf")
        link(ReferenceOwner.OfInstalledComponent(tray.id), "Example tray fitting notes", "tray-fitting")
        hold(ups)
        drawScreen(tray)
        listOf("Example tray photo.pdf", "Example tray fitting notes", fromSupply("Example 12 V Battery"))
            .forEach { rule.awaitText(it) }

        listOf("Add file", "Take photo", "Add link", "Open settings").forEach {
            rule.onAllNodesWithText(it).assertCountEquals(0)
        }
        rule.onAllNodesWithContentDescription("More for Example tray photo.pdf").assertCountEquals(0)

        overflowOf("Example tray fitting notes").performScrollTo().performClick()
        rule.onNodeWithText("Open").assertIsDisplayed()
        rule.onAllNodesWithText("Edit").assertCountEquals(0)
        rule.onAllNodesWithText("Remove").assertCountEquals(0)
    }

    /** C27, N-8: the component's asset deleted while the screen is open takes the row, and the screen goes back. */
    @Test fun aComponentWhoseAssetIsDeletedWhileOpenGoesBack() {
        val ups = asset()
        val tray = install(ups, "Example Battery Tray")
        drawScreen(tray)
        assertEquals(0, backs.get())

        runBlocking { app.graph.deleteAsset.run(ups.id) }

        rule.waitUntil(WAIT_MS) { backs.get() > 0 }
    }

    /**
     * C27, #47 E-30: the row sheet's P69-1 closes the sheet, then opens this screen for that row; Back lands on the
     * asset with no sheet over it. The host swaps the two screens as the root's back stack does.
     */
    @Test fun theRowSheetsActionClosesTheSheetAndBackFindsNoSheet() {
        val ups = asset()
        val tray = install(ups, "Example Battery Tray")
        val opened = AtomicReference<String?>(null)
        rule.setContent {
            ServiceTagTheme {
                var shown by remember { mutableStateOf<String?>(null) }
                val component = shown
                if (component == null) {
                    AssetDetailScreen(
                        graph = app.graph,
                        assetId = ups.id.value,
                        onBack = {}, onEdit = {}, onSetup = {}, onWriteTag = {}, onBackup = {},
                        onLogEvent = { _, _ -> }, onOpenEvent = {}, onOpenAsset = {}, onAddComponent = {},
                        onAddSchedule = {}, onLogOutcome = { _, _ -> }, onOpenSettings = {},
                        onOpenSchedule = {}, onOpenGroup = {},
                        onOpenInstalledComponent = { opened.set(it); shown = it },
                    )
                } else {
                    InstalledComponentDetailScreen(
                        graph = app.graph,
                        componentId = component,
                        onBack = { shown = null },
                        onOpenSupply = {},
                        onOpenSettings = {},
                    )
                }
            }
        }
        rule.awaitText("Example Battery Tray")

        rule.onNodeWithText("Example Battery Tray").performScrollTo().performClick()
        rule.awaitText(DOCUMENTS_AND_REFERENCES)
        rule.onNodeWithText(DOCUMENTS_AND_REFERENCES).performScrollTo().performClick()
        rule.awaitText(THIS_INSTALLED_COMPONENT)
        assertEquals(tray.id.value, opened.get())

        rule.onNodeWithContentDescription("Back").performClick()
        rule.awaitText(INSTALLED_COMPONENTS_SECTION.uppercase())
        rule.awaitText("Example Battery Tray")
        rule.waitForIdle()
        rule.onAllNodesWithText(DOCUMENTS_AND_REFERENCES).assertCountEquals(0)
        rule.onAllNodesWithText(THIS_INSTALLED_COMPONENT).assertCountEquals(0)
        rule.onAllNodesWithText("Example Battery Tray").assertCountEquals(1)
    }
}

/** Points the graph's attachment seams at an ordinary directory, as `SupplySurfacesTest` does. */
private fun useAFileBackedTree() {
    val context: Context = ApplicationProvider.getApplicationContext()
    val root = File(context.getExternalFilesDir(null), "installed-component-detail-proof").also {
        it.deleteRecursively()
        it.mkdirs()
    }
    val graph = app.graph
    graph.attachmentRootResolver = { _ ->
        DocumentTreeRoot(DocumentFile.fromFile(root), context.contentResolver)
    }
    graph.attachmentGrantCheck = { true }
    graph.prefs.attachmentTreeUri = "file://" + root.absolutePath
}
