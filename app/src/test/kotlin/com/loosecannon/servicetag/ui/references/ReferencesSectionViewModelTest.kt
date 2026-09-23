package com.loosecannon.servicetag.ui.references

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetReference
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.ReferenceId
import com.loosecannon.servicetag.core.model.ReferenceKind
import com.loosecannon.servicetag.core.ports.ByteSource
import com.loosecannon.servicetag.core.references.LinkLaunchPolicy
import com.loosecannon.servicetag.core.usecase.AddAttachmentCommand
import com.loosecannon.servicetag.core.usecase.AddReference
import com.loosecannon.servicetag.core.usecase.AddReferenceCommand
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.ReferenceResult
import com.loosecannon.servicetag.core.usecase.RemoveReference
import com.loosecannon.servicetag.core.usecase.UpdateReference
import com.loosecannon.servicetag.core.usecase.UpdateReferenceCommand
import com.loosecannon.servicetag.reminders.sourceFile
import com.loosecannon.servicetag.testing.FakeGraph
import kotlin.properties.Delegates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The REFERENCES section's one ViewModel, against a Room-backed [FakeGraph] so the observe flow,
 * the mapper and the three use cases are the production ones — the same fixture and the same
 * awaiting style `AttachmentsSectionViewModelTest` uses, for the same reason: `viewModelScope`
 * dispatches on `Dispatchers.Main`, so the main dispatcher is an unconfined test one for the
 * length of each case and every assertion waits for a state rather than reading `value` after a
 * write.
 *
 * `messages` has no replay by design, so a case that wants a line subscribes before the call that
 * produces it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReferencesSectionViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var graph: FakeGraph

    /** `AssetId` is a value class, so `lateinit` is not allowed on it; this is the same contract. */
    private var assetId: AssetId by Delegates.notNull()

    private val store = ViewModelStore()
    private val policy = LinkLaunchPolicy()

    private lateinit var addReference: AddReference
    private lateinit var updateReference: UpdateReference
    private lateinit var removeReference: RemoveReference

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        graph = FakeGraph(queryContext = StandardTestDispatcher(scheduler))
        addReference =
            AddReference(graph.references, graph.assets, policy, graph.uow, graph.ids, graph.clock)
        updateReference = UpdateReference(graph.references, graph.uow, graph.clock)
        removeReference = RemoveReference(graph.references, graph.uow)
    }

    @After fun tearDown() {
        store.clear()
        graph.close()
        Dispatchers.resetMain()
    }

    /** The one asset every case but the pure ones needs, seeded inside the test's own scope. */
    private suspend fun TestScope.hotTub(): Asset {
        val asset = graph.createAsset.run(AssetCommand(name = "Hot tub"))
        assetId = asset.id
        return asset
    }

    private fun model(owner: AssetId = assetId): ReferencesSectionViewModel {
        val factory = viewModelFactory {
            initializer {
                ReferencesSectionViewModel(
                    owner, graph.references, addReference, updateReference, removeReference, policy,
                )
            }
        }
        return ViewModelProvider.create(store, factory)[
            "references-${owner.value}",
            ReferencesSectionViewModel::class,
        ]
    }

    /** A row written straight to the table, the way a restore or a merge puts one there. */
    private suspend fun stored(id: String, uri: String, kind: ReferenceKind, name: String) {
        graph.uow.write {
            graph.references.upsert(
                AssetReference(
                    id = ReferenceId(id),
                    assetId = assetId,
                    kind = kind,
                    uri = uri,
                    displayName = name,
                    description = "",
                    scheme = uri.substringBefore(':'),
                    createdAt = 10L,
                    updatedAt = 10L,
                ),
            )
        }
    }

    /**
     * I-3, asserted on the shape rather than on a drawing of it: a reference has no bytes, so the
     * row state may not grow a locator, a size, a sha256, a presence flag or a thumbnail. Copying
     * `AttachmentRowState` wholesale is exactly how it would.
     */
    @Test fun theRowStateCarriesNothingThatBelongsToBytes() {
        val fields = ReferenceRowState::class.java.declaredFields
            .map { it.name }
            .filterNot { it.startsWith("$") }
            .toSet()

        assertEquals(
            setOf("id", "displayName", "description", "uri", "kind", "launchable"),
            fields,
        )
    }

    /**
     * The policy is applied again at launch, not only at save: a URI that was legal when it was
     * written and is not now — a restored archive, a block list that grew — is listed and marked
     * unlaunchable. An unknown scheme is not blocked and stays launchable, so the two cannot be
     * confused for one another.
     */
    @Test fun aStoredUriTheBlockListNowRefusesIsListedAndNotLaunchable() = runTest {
        hotTub()
        stored("r1", "javascript:alert(1)", ReferenceKind.OTHER, "Was legal once")
        stored("r2", "zotero://select/items/0", ReferenceKind.OTHER, "Zotero item")
        val vm = model()
        backgroundScope.launch { vm.state.collect() }

        val rows = vm.state.first { it.rows.size == 2 }.rows
        assertFalse(rows.single { it.id == "r1" }.launchable)
        assertTrue(rows.single { it.id == "r2" }.launchable)
    }

    /**
     * D-21 C: "Add link" calls the same use case a share does, so the two rows differ only in the
     * three things that cannot be the same — the id, the asset and the clock. A `provenance` field,
     * or a screen that set one field its own way, is exactly what this forbids.
     */
    @Test fun aLinkAddedInAppIsFieldForFieldWhatAShareWrites() = runTest {
        hotTub()
        val other = graph.createAsset.run(AssetCommand(name = "Ride-on mower"))
        val vm = model()
        backgroundScope.launch { vm.state.collect() }

        vm.addLink("https://example-mower.invalid/manual", "Deck manual", "Section 4")
        val inApp = vm.state.first { it.rows.isNotEmpty() }.rows.single()

        // The share path, called directly: the same command object, the same use case.
        val shared = addReference.run(
            other.id,
            AddReferenceCommand(
                uri = "https://example-mower.invalid/manual",
                displayName = "Deck manual",
                description = "Section 4",
            ),
        )
        val sharedRow = (shared as ReferenceResult.Ok).value
        val addedRow = graph.references.get(ReferenceId(inApp.id))!!

        assertEquals(sharedRow.uri, addedRow.uri)
        assertEquals(sharedRow.displayName, addedRow.displayName)
        assertEquals(sharedRow.description, addedRow.description)
        assertEquals(sharedRow.kind, addedRow.kind)
        assertEquals(sharedRow.scheme, addedRow.scheme)
        assertEquals(
            "only the id, the owner and the timestamps may differ",
            sharedRow.copy(
                id = addedRow.id,
                assetId = addedRow.assetId,
                createdAt = addedRow.createdAt,
                updatedAt = addedRow.updatedAt,
            ),
            addedRow,
        )
    }

    /**
     * The confirmation is asked once and answered once. Until Save the table is empty — a screen
     * that passed `confirmedUnknownScheme = true` unconditionally would have written on the first
     * call and never asked at all.
     */
    @Test fun anUnknownSchemeIsAskedAboutAndWritesNothingUntilItIsAnswered() = runTest {
        hotTub()
        val vm = model()
        backgroundScope.launch { vm.state.collect() }

        vm.addLink("zotero://select/items/0", "Pump teardown", "")
        assertEquals("zotero", vm.state.first { it.pendingConfirmation != null }.pendingConfirmation)
        assertTrue(graph.references.forAsset(assetId).isEmpty())

        vm.confirmUnknownScheme()
        val row = vm.state.first { it.rows.isNotEmpty() }.rows.single()
        assertNull(vm.state.value.pendingConfirmation)
        assertEquals(ReferenceKind.OTHER, row.kind)
        assertEquals("zotero://select/items/0", row.uri)
    }

    /** A hard-blocked scheme is refused where it is saved, and the save-time line is the one said. */
    @Test fun aBlockedSchemeIsRefusedWithTheSaveTimeLineAndWritesNothing() = runTest {
        hotTub()
        val vm = model()
        backgroundScope.launch { vm.state.collect() }

        val said = async(Dispatchers.Main) { vm.messages.first() }
        vm.addLink("javascript:alert(1)", "Not happening", "")

        assertEquals("ServiceTag will not save that kind of link.", said.await())
        assertTrue(graph.references.forAsset(assetId).isEmpty())
        assertNull(vm.state.value.pendingConfirmation)
    }

    /** `UNIQUE(asset_id, uri)` is a recoverable state, so it gets its own sentence (I-7). */
    @Test fun aUriAlreadyOnThisAssetIsRefusedByName() = runTest {
        hotTub()
        val vm = model()
        backgroundScope.launch { vm.state.collect() }
        vm.addLink("https://example-mower.invalid/manual", "Deck manual", "")
        vm.state.first { it.rows.size == 1 }

        val said = async(Dispatchers.Main) { vm.messages.first() }
        vm.addLink("https://example-mower.invalid/manual", "Deck manual again", "")

        assertEquals("That link is already on this asset", said.await())
        assertEquals(1, graph.references.forAsset(assetId).size)
    }

    /** A row the list cannot label is not savable; the sheet stays open on the refusal. */
    @Test fun clearingTheNameOnTheEditSheetIsRefusedAndWritesNothing() = runTest {
        hotTub()
        stored("r1", "https://example-mower.invalid/manual", ReferenceKind.WEB_URL, "Deck manual")
        val vm = model()
        backgroundScope.launch { vm.state.collect() }
        vm.state.first { it.rows.isNotEmpty() }

        val said = async(Dispatchers.Main) { vm.messages.first() }
        vm.save("r1", UpdateReferenceCommand(displayName = "   ", description = "anything"))

        assertEquals("Give the reference a name", said.await())
        assertEquals("Deck manual", graph.references.get(ReferenceId("r1"))!!.displayName)
        assertEquals("", graph.references.get(ReferenceId("r1"))!!.description)
    }

    /** I-1: an edit moves the name, the description and `updated_at`, and nothing else at all. */
    @Test fun anEditLeavesTheUriTheKindTheSchemeAndTheCreatedAtWhereTheyWere() = runTest {
        hotTub()
        stored("r1", "https://example-mower.invalid/manual", ReferenceKind.WEB_URL, "Deck manual")
        val before = graph.references.get(ReferenceId("r1"))!!
        graph.now = 5_000L
        val vm = model()
        backgroundScope.launch { vm.state.collect() }
        vm.state.first { it.rows.isNotEmpty() }

        vm.save("r1", UpdateReferenceCommand("Deck manual (2026)", "Section 4 covers the seal"))
        vm.state.first { it.rows.single().displayName == "Deck manual (2026)" }

        val after = graph.references.get(ReferenceId("r1"))!!
        assertEquals(before.uri, after.uri)
        assertEquals(before.kind, after.kind)
        assertEquals(before.scheme, after.scheme)
        assertEquals(before.createdAt, after.createdAt)
        assertEquals("Section 4 covers the seal", after.description)
        assertEquals(5_000L, after.updatedAt)
    }

    /** A delete keyed on the wrong id would take a sibling, or the asset's bytes, with it. */
    @Test fun removingOneReferenceLeavesTheOtherAndEveryAttachment() = runTest {
        hotTub()
        stored("r1", "https://example-mower.invalid/manual", ReferenceKind.WEB_URL, "Deck manual")
        stored("r2", "https://example-mower.invalid/parts", ReferenceKind.WEB_URL, "Parts list")
        graph.addAttachment.run(
            AttachmentOwner.OfAsset(assetId),
            AddAttachmentCommand(displayName = "Receipt.pdf", mimeType = "application/pdf"),
            ByteSource { "x".byteInputStream() },
        )
        val vm = model()
        backgroundScope.launch { vm.state.collect() }
        vm.state.first { it.rows.size == 2 }

        vm.remove("r1")

        val left = vm.state.first { it.rows.size == 1 }.rows.single()
        assertEquals("r2", left.id)
        assertNull(graph.references.get(ReferenceId("r1")))
        assertEquals(1, graph.attachments.forOwner(AttachmentOwner.OfAsset(assetId)).size)
    }

    /** A lifecycle filter on this section would hide rows the owner put on the asset themselves. */
    @Test fun aReferenceOnAnArchivedAssetStillListsAndStillOpens() = runTest {
        hotTub()
        stored("r1", "https://example-mower.invalid/manual", ReferenceKind.WEB_URL, "Deck manual")
        graph.archiveAsset.run(assetId)
        val vm = model()
        backgroundScope.launch { vm.state.collect() }

        val row = vm.state.first { it.rows.isNotEmpty() }.rows.single()
        assertEquals("Deck manual", row.displayName)
        assertTrue(row.launchable)
    }

    /**
     * D-10's ordering, read off the screen's own source: pointers go **below** the bytes they are
     * not, and above the notes. Nothing else in `AssetDetailScreen` can say where a section sits,
     * and standing the whole screen up on a device to find out would prove the same one fact at a
     * hundred times the cost.
     */
    @Test fun theReferencesSectionIsDrawnBelowDocumentsAndAboveNotes() {
        val screen = sourceFile("kotlin/com/loosecannon/servicetag/ui/asset/AssetDetailScreen.kt")
            .readText()

        val documents = screen.indexOf("AttachmentsSection(\n")
        val references = screen.indexOf("ReferencesSection(\n")
        val notes = screen.indexOf("NotesSection(current.asset.notes)")

        assertTrue("AttachmentsSection is called", documents > 0)
        assertTrue("ReferencesSection is called", references > 0)
        assertTrue("NotesSection is called", notes > 0)
        assertTrue("References must come after Documents", documents < references)
        assertTrue("References must come before Notes", references < notes)
    }
}
