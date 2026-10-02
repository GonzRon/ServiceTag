package com.loosecannon.servicetag.ui.references

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetReference
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.AttachmentSource
import com.loosecannon.servicetag.core.model.DocumentRole
import com.loosecannon.servicetag.core.model.InstalledComponent
import com.loosecannon.servicetag.core.model.InstalledComponentId
import com.loosecannon.servicetag.core.fetch.HopPolicy
import com.loosecannon.servicetag.core.fetch.HostResolver
import com.loosecannon.servicetag.core.model.ReferenceId
import com.loosecannon.servicetag.core.model.ReferenceKind
import com.loosecannon.servicetag.core.model.ReferenceOwner
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.core.model.SupplyItem
import com.loosecannon.servicetag.core.model.asAttachmentOwner
import com.loosecannon.servicetag.core.ports.ByteSource
import com.loosecannon.servicetag.core.references.LinkLaunchPolicy
import com.loosecannon.servicetag.core.references.takesRole
import com.loosecannon.servicetag.core.usecase.AddAttachmentCommand
import com.loosecannon.servicetag.core.usecase.AddReference
import com.loosecannon.servicetag.core.usecase.AddReferenceCommand
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.AttachmentResult
import com.loosecannon.servicetag.core.usecase.ReferenceResult
import com.loosecannon.servicetag.core.usecase.RemoveReference
import com.loosecannon.servicetag.core.usecase.UpdateReference
import com.loosecannon.servicetag.core.usecase.UpdateReferenceCommand
import com.loosecannon.servicetag.reminders.sourceFile
import com.loosecannon.servicetag.share.IntakeStrings
import com.loosecannon.servicetag.testing.FakeGraph
import kotlin.coroutines.CoroutineContext
import kotlin.properties.Delegates
import kotlinx.coroutines.CoroutineDispatcher
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
import kotlinx.coroutines.test.advanceUntilIdle
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

    private companion object {
        const val MANUAL = "https://manuals.example.invalid/pool-pump/manual.pdf"

        /** #69 row 49: one id string an asset, a SupplyItem and an installed component all carry. */
        const val SAME = "example-ups-1"
        const val DATA_SHEET = "https://example.invalid/battery/datasheet"
    }

    private val scheduler = TestCoroutineScheduler()
    private lateinit var graph: FakeGraph

    /** `AssetId` is a value class, so `lateinit` is not allowed on it; this is the same contract. */
    private var assetId: AssetId by Delegates.notNull()

    private val store = ViewModelStore()
    private val policy = LinkLaunchPolicy()
    private val hops = HopPolicy(HostResolver { listOf(byteArrayOf(203.toByte(), 0, 113, 10)) })

    private lateinit var addReference: AddReference
    private lateinit var updateReference: UpdateReference
    private lateinit var removeReference: RemoveReference

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        graph = FakeGraph(queryContext = StandardTestDispatcher(scheduler))
        addReference =
            AddReference(
                graph.references, graph.assets, graph.supplyItems, graph.installedComponents, policy, graph.uow, graph.ids,
                graph.clock,
            )
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

    /**
     * Cleared as the last line of every case that builds a model, inside `runTest`, so the work
     * behind a signal a case waited for is drained on the test's own clock (the #65 shape).
     */
    private fun TestScope.clearModels() {
        store.clear()
        advanceUntilIdle()
    }

    /** A thin counter over the test's scheduler: what `io` is, so every dispatch is seen. */
    private class CountingDispatcher(private val inner: CoroutineDispatcher) : CoroutineDispatcher() {
        var count = 0
            private set

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            count++
            inner.dispatch(context, block)
        }
    }

    private fun model(
        owner: ReferenceOwner = ReferenceOwner.OfAsset(assetId),
        io: CoroutineContext = StandardTestDispatcher(scheduler),
    ): ReferencesSectionViewModel {
        val factory = viewModelFactory {
            initializer {
                ReferencesSectionViewModel(
                    owner, graph.references, addReference, updateReference, removeReference, policy,
                    graph.attachments, hops,
                    io = io,
                )
            }
        }
        return ViewModelProvider.create(store, factory)[referencesModelKey(owner), ReferencesSectionViewModel::class]
    }

    /** A row written straight to the table, the way a restore or a merge puts one there. */
    private suspend fun stored(
        id: String,
        uri: String,
        kind: ReferenceKind,
        name: String,
        role: DocumentRole? = null,
        owner: ReferenceOwner = ReferenceOwner.OfAsset(assetId),
    ) {
        graph.uow.write {
            graph.references.upsert(
                AssetReference(
                    id = ReferenceId(id),
                    owner = owner,
                    kind = kind,
                    uri = uri,
                    displayName = name,
                    description = "",
                    scheme = uri.substringBefore(':'),
                    createdAt = 10L,
                    updatedAt = 10L,
                    role = role,
                ),
            )
        }
    }

    /** #85 (C20): a document saved from [uri] onto [owner], as `MaterializeReference.commit` writes one. */
    private suspend fun sourced(owner: AssetId, uri: String) = sourced(AttachmentOwner.OfAsset(owner), uri)

    /** #69: the same, onto any owner's files. */
    private suspend fun sourced(owner: AttachmentOwner, uri: String) = (
        graph.addAttachment.run(
            owner,
            AddAttachmentCommand(
                displayName = "Example Pool Pump manual", mimeType = "application/pdf", sizeBytes = 3L,
                source = AttachmentSource(uri, null, 5_000L, "Example Pool Pump manual"),
            ),
            ByteSource { "pdf".toByteArray().inputStream() },
        ) as AttachmentResult.Ok
        ).value

    @Test fun materializableForAnHttpsWebLink() = runTest {
        hotTub()
        stored("r1", MANUAL, ReferenceKind.WEB_URL, "Example Pool Pump manual")
        val vm = model()
        backgroundScope.launch { vm.state.collect() }

        val row = vm.state.first { it.rows.isNotEmpty() }.rows.single()

        assertTrue(row.materializable)
        assertFalse(row.savedAsDocument)
        clearModels()
    }

    /** R85-4, R85-15: no item for these, and each reference is listed exactly as it was stored. */
    @Test fun notForHttpNoteBlockedUserinfoOrANonAsciiHost() = runTest {
        hotTub()
        val stored = mapOf(
            "http" to ("http://manuals.example.invalid/manual.pdf" to ReferenceKind.WEB_URL),
            "note" to ("joplin://x-callback-url/openNote?id=0123" to ReferenceKind.NOTE_LINK),
            "blocked" to ("javascript:alert(1)" to ReferenceKind.OTHER),
            "userinfo" to ("https://owner@manuals.example.invalid/manual.pdf" to ReferenceKind.WEB_URL),
            "idn" to ("https://handb\u00fccher.example.invalid/manual.pdf" to ReferenceKind.WEB_URL),
            "local" to ("https://localhost/manual.pdf" to ReferenceKind.WEB_URL),
        )
        stored.forEach { (id, link) -> stored(id, link.first, link.second, "Example $id link") }
        val vm = model()
        backgroundScope.launch { vm.state.collect() }

        val rows = vm.state.first { it.rows.size == stored.size }.rows

        assertEquals(emptyList<String>(), rows.filter { it.materializable }.map { it.id })
        assertEquals(stored.mapValues { it.value.first }, rows.associate { it.id to it.uri })
        clearModels()
    }

    /** R85-3: the second identity is `(assetId, uri)` — another reference with the same name is not marked. */
    @Test fun savedAsDocumentWhenASourcedAttachmentNamesTheUri() = runTest {
        hotTub()
        stored("r1", MANUAL, ReferenceKind.WEB_URL, "Example Pool Pump manual")
        stored("r2", "https://manuals.example.invalid/pool-pump/parts.pdf", ReferenceKind.WEB_URL, "Example Pool Pump manual")
        sourced(assetId, MANUAL)
        val vm = model()
        backgroundScope.launch { vm.state.collect() }

        val rows = vm.state.first { s -> s.rows.size == 2 && s.rows.any { it.savedAsDocument } }.rows

        assertEquals(mapOf("r1" to true, "r2" to false), rows.associate { it.id to it.savedAsDocument })
        clearModels()
    }

    @Test fun clearedWhenThatAttachmentIsDeleted() = runTest {
        hotTub()
        stored("r1", MANUAL, ReferenceKind.WEB_URL, "Example Pool Pump manual")
        val saved = sourced(assetId, MANUAL)
        val vm = model()
        backgroundScope.launch { vm.state.collect() }
        vm.state.first { s -> s.rows.singleOrNull()?.savedAsDocument == true }

        graph.deleteAttachment.run(saved.id)

        val row = vm.state.first { s -> s.rows.singleOrNull()?.savedAsDocument == false }.rows.single()
        assertEquals("the reference stays (R85-1)", MANUAL, row.uri)
        clearModels()
    }

    @Test fun anotherAssetsSourceDoesNotCount() = runTest {
        hotTub()
        stored("r1", MANUAL, ReferenceKind.WEB_URL, "Example Pool Pump manual")
        val heater = graph.createAsset.run(AssetCommand(name = "Sample Water Heater"))
        sourced(heater.id, MANUAL)
        val vm = model()
        backgroundScope.launch { vm.state.collect() }

        val row = vm.state.first { it.rows.isNotEmpty() }.rows.single()

        assertFalse(row.savedAsDocument)
        assertTrue(row.materializable)
        clearModels()
    }

    /**
     * Row 29 (#91, C15): each row carries its stored role, so the edit sheet's `role = row.role`
     * keeps it on a rename; a row with none carries null. Nothing draws it until B3b.
     */
    @Test fun aRowCarriesItsStoredRole() = runTest {
        hotTub()
        stored("r1", MANUAL, ReferenceKind.WEB_URL, "Example Pool Pump manual", DocumentRole.SERVICE_MANUAL)
        stored("r2", "https://manuals.example.invalid/pool-pump/parts.pdf", ReferenceKind.WEB_URL, "Example parts list")
        stored("r3", "joplin://x-callback-url/openNote?id=example", ReferenceKind.NOTE_LINK, "Example note")
        val vm = model()
        backgroundScope.launch { vm.state.collect() }

        val rows = vm.state.first { it.rows.size == 3 }.rows

        assertEquals(
            mapOf("r1" to DocumentRole.SERVICE_MANUAL, "r2" to null, "r3" to null),
            rows.associate { it.id to it.role },
        )
        clearModels()
    }

    /** Row 43 (#91, C23): Add link's role reaches `AddReferenceCommand`, and so the stored row. */
    @Test fun addLinkPassesTheRole() = runTest {
        hotTub()
        val vm = model()
        backgroundScope.launch { vm.state.collect() }

        vm.addLink(MANUAL, "Example Pool Pump manual", "", DocumentRole.USER_MANUAL)
        val row = vm.state.first { it.rows.isNotEmpty() }.rows.single()

        assertEquals(DocumentRole.USER_MANUAL, row.role)
        assertEquals(DocumentRole.USER_MANUAL, graph.references.get(ReferenceId(row.id))!!.role)
        clearModels()
    }

    /** Row 43 (#91, C22): the edit sheet's command carries its role through `save` — set, then cleared. */
    @Test fun saveCarriesTheCommandsRole() = runTest {
        hotTub()
        stored("r1", MANUAL, ReferenceKind.WEB_URL, "Example Pool Pump manual")
        val vm = model()
        backgroundScope.launch { vm.state.collect() }
        vm.state.first { it.rows.isNotEmpty() }

        vm.save("r1", UpdateReferenceCommand("Example Pool Pump manual", "", role = DocumentRole.SERVICE_MANUAL))
        vm.state.first { it.rows.single().role == DocumentRole.SERVICE_MANUAL }
        assertEquals(DocumentRole.SERVICE_MANUAL, graph.references.get(ReferenceId("r1"))!!.role)

        vm.save("r1", UpdateReferenceCommand("Example Pool Pump manual", "", role = null))
        vm.state.first { it.rows.single().role == null }
        assertNull(graph.references.get(ReferenceId("r1"))!!.role)
        clearModels()
    }

    /**
     * Row 43 (#91, C23, C-4): Add link's chips follow exactly the kind `AddReference` derives from
     * the same text. Each text is saved through the use case and its row's kind asked the one
     * question, so the view model's answer and the stored row's can never disagree.
     */
    @Test fun roleOfferedIsTheKindAddReferenceWouldDerive() = runTest {
        hotTub()
        val vm = model()
        val expected = linkedMapOf(
            "https://manuals.example.invalid/water-heater/manual.pdf" to true,
            "http://manuals.example.invalid/water-heater" to true,
            "  HTTPS://manuals.example.invalid/water-heater/service.pdf" to true,
            "joplin://x-callback-url/openNote?id=example" to false,
            "obsidian://open?vault=example" to false,
            "zotero://select/items/0" to false,
            "httpx://manuals.example.invalid/water-heater" to false,
        )

        expected.forEach { (text, offered) ->
            assertEquals("roleOffered('$text')", offered, vm.roleOffered(text))
            val saved = addReference.run(
                ReferenceOwner.OfAsset(assetId),
                AddReferenceCommand(uri = text, displayName = "Example link", confirmedUnknownScheme = true),
            )
            assertEquals(
                "the kind AddReference derived for '$text'",
                offered,
                (saved as ReferenceResult.Ok).value.kind.takesRole,
            )
        }
        clearModels()
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
            setOf("id", "displayName", "description", "uri", "kind", "launchable", "materializable", "savedAsDocument", "role"),
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

        clearModels()
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

        vm.addLink("https://example-mower.invalid/manual", "Deck manual", "Section 4", role = null)
        val inApp = vm.state.first { it.rows.isNotEmpty() }.rows.single()

        // The share path, called directly: the same command object, the same use case.
        val shared = addReference.run(
            ReferenceOwner.OfAsset(other.id),
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
                owner = addedRow.owner,
                createdAt = addedRow.createdAt,
                updatedAt = addedRow.updatedAt,
            ),
            addedRow,
        )

        clearModels()
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

        vm.addLink("zotero://select/items/0", "Pump teardown", "", role = null)
        assertEquals("zotero", vm.state.first { it.pendingConfirmation != null }.pendingConfirmation)
        assertTrue(graph.references.forOwner(ReferenceOwner.OfAsset(assetId)).isEmpty())

        vm.confirmUnknownScheme()
        val row = vm.state.first { it.rows.isNotEmpty() }.rows.single()
        assertNull(vm.state.value.pendingConfirmation)
        assertEquals(ReferenceKind.OTHER, row.kind)
        assertEquals("zotero://select/items/0", row.uri)

        clearModels()
    }

    /** A hard-blocked scheme is refused where it is saved, and the save-time line is the one said. */
    @Test fun aBlockedSchemeIsRefusedWithTheSaveTimeLineAndWritesNothing() = runTest {
        hotTub()
        val vm = model()
        backgroundScope.launch { vm.state.collect() }

        val said = async(Dispatchers.Main) { vm.messages.first() }
        vm.addLink("javascript:alert(1)", "Not happening", "", role = null)

        assertEquals("ServiceTag will not save that kind of link.", said.await())
        assertTrue(graph.references.forOwner(ReferenceOwner.OfAsset(assetId)).isEmpty())
        assertNull(vm.state.value.pendingConfirmation)

        clearModels()
    }

    /** `UNIQUE(asset_id, uri)` is a recoverable state, so it gets its own sentence (I-7). */
    @Test fun aUriAlreadyOnThisAssetIsRefusedByName() = runTest {
        hotTub()
        val vm = model()
        backgroundScope.launch { vm.state.collect() }
        vm.addLink("https://example-mower.invalid/manual", "Deck manual", "", role = null)
        vm.state.first { it.rows.size == 1 }

        val said = async(Dispatchers.Main) { vm.messages.first() }
        vm.addLink("https://example-mower.invalid/manual", "Deck manual again", "", role = null)

        assertEquals("That link is already on this asset", said.await())
        assertEquals(1, graph.references.forOwner(ReferenceOwner.OfAsset(assetId)).size)

        clearModels()
    }

    /** A row the list cannot label is not savable; the sheet stays open on the refusal. */
    @Test fun clearingTheNameOnTheEditSheetIsRefusedAndWritesNothing() = runTest {
        hotTub()
        stored("r1", "https://example-mower.invalid/manual", ReferenceKind.WEB_URL, "Deck manual")
        val vm = model()
        backgroundScope.launch { vm.state.collect() }
        vm.state.first { it.rows.isNotEmpty() }

        val said = async(Dispatchers.Main) { vm.messages.first() }
        vm.save("r1", UpdateReferenceCommand(displayName = "   ", description = "anything", role = null))

        assertEquals("Give the reference a name", said.await())
        assertEquals("Deck manual", graph.references.get(ReferenceId("r1"))!!.displayName)
        assertEquals("", graph.references.get(ReferenceId("r1"))!!.description)

        clearModels()
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

        vm.save("r1", UpdateReferenceCommand("Deck manual (2026)", "Section 4 covers the seal", role = null))
        vm.state.first { it.rows.single().displayName == "Deck manual (2026)" }

        val after = graph.references.get(ReferenceId("r1"))!!
        assertEquals(before.uri, after.uri)
        assertEquals(before.kind, after.kind)
        assertEquals(before.scheme, after.scheme)
        assertEquals(before.createdAt, after.createdAt)
        assertEquals("Section 4 covers the seal", after.description)
        assertEquals(5_000L, after.updatedAt)

        clearModels()
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

        clearModels()
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

        clearModels()
    }

    /** C1: the writes run on the injected context, so none of them outlives the test that began it. */
    @Test fun everyWriteRunsOnTheInjectedContext() = runTest {
        hotTub()
        stored("r1", "https://example-mower.invalid/manual", ReferenceKind.WEB_URL, "Deck manual")
        stored("r2", "https://example-mower.invalid/parts", ReferenceKind.WEB_URL, "Parts list")
        val io = CountingDispatcher(StandardTestDispatcher(scheduler))
        val vm = model(io = io)
        backgroundScope.launch { vm.state.collect() }
        vm.state.first { it.rows.size == 2 }

        advanceUntilIdle()
        val beforeAdd = io.count
        vm.addLink("https://example-mower.invalid/new", "New link", "", role = null)
        vm.state.first { it.rows.size == 3 }
        assertTrue("an add dispatches on io", io.count > beforeAdd)

        advanceUntilIdle()
        val beforeEdit = io.count
        vm.save("r1", UpdateReferenceCommand("Deck manual (2026)", "", role = null))
        vm.state.first { s -> s.rows.any { it.displayName == "Deck manual (2026)" } }
        assertTrue("an edit dispatches on io", io.count > beforeEdit)

        advanceUntilIdle()
        val beforeRemove = io.count
        vm.remove("r2")
        vm.state.first { it.rows.size == 2 }
        assertTrue("a remove dispatches on io", io.count > beforeRemove)

        clearModels()
    }

    // --- #69 (C25, C28; row 49): the section keyed by its owner ---------------------------------------------------

    /**
     * Row 49's fixture: an asset, a SupplyItem and an installed component carrying one id string, so a read keyed
     * by the id alone, or by the asset, would show another owner's row.
     */
    private suspend fun threeOwners(): List<ReferenceOwner> {
        val asset = AssetId(SAME)
        graph.uow.write {
            graph.assets.upsert(Asset(id = asset, name = "Example UPS", createdAt = 1_000L, updatedAt = 1_000L))
            graph.supplyItems.upsert(
                SupplyItem(
                    id = SupplyId(SAME), name = "Example 12 V Battery", category = "Battery",
                    manufacturer = "Example Power Co.", model = "EP-12", partNumber = "EP-12-7", preferredUnit = "ea",
                    notes = "", archivedAt = null, createdAt = 1_000L, updatedAt = 1_000L, specifications = emptyList(),
                ),
            )
            graph.installedComponents.insert(
                InstalledComponent(
                    id = InstalledComponentId(SAME), assetId = asset, parentId = null, name = "Example Battery Tray",
                    supplyId = null, composition = emptyList(), serialOrLot = "", installedOn = "2026-01-10",
                    removedOn = null, replacesId = null, sortOrder = 0, notes = "", createdAt = 1_000L,
                    updatedAt = 1_000L,
                ),
            )
        }
        assetId = asset
        return listOf(
            ReferenceOwner.OfAsset(asset),
            ReferenceOwner.OfSupplyItem(SupplyId(SAME)),
            ReferenceOwner.OfInstalledComponent(InstalledComponentId(SAME)),
        )
    }

    @Test fun stateIsTheOwnersRows() = runTest {
        val (asset, supply, component) = threeOwners()
        stored("r-asset", "https://example.invalid/ups/manual", ReferenceKind.WEB_URL, "UPS manual", owner = asset)
        stored("r-supply", DATA_SHEET, ReferenceKind.WEB_URL, "Battery data sheet", owner = supply)
        stored(
            "r-component", "https://example.invalid/tray/guide", ReferenceKind.WEB_URL, "Tray guide", owner = component,
        )

        val listed = listOf(asset, supply, component).associateWith { owner ->
            val vm = model(owner)
            backgroundScope.launch { vm.state.collect() }
            vm.state.first { it.rows.isNotEmpty() }.rows.map { it.id }
        }

        assertEquals(
            mapOf(asset to listOf("r-asset"), supply to listOf("r-supply"), component to listOf("r-component")),
            listed,
        )
        assertEquals(
            "the production key is one per owner, never the id string alone",
            3,
            setOf(
                referencesModelKey(ReferenceOwner.OfAsset(AssetId("x"))),
                referencesModelKey(ReferenceOwner.OfSupplyItem(SupplyId("x"))),
                referencesModelKey(ReferenceOwner.OfInstalledComponent(InstalledComponentId("x"))),
            ).size,
        )
        clearModels()
    }

    /**
     * H4: the saved mark reads the owner's own files. An asset's file from the same source never marks a
     * SupplyItem's or a component's link, though all three share one id string; the SupplyItem's own file does,
     * and marks only its own link.
     */
    @Test fun theSavedMarkReadsTheOwnersOwnFiles() = runTest {
        val (asset, supply, component) = threeOwners()
        stored("r-supply", MANUAL, ReferenceKind.WEB_URL, "Example Pool Pump manual", owner = supply)
        stored("r-component", MANUAL, ReferenceKind.WEB_URL, "Example Pool Pump manual", owner = component)
        sourced(asset.asAttachmentOwner(), MANUAL)
        val onSupply = model(supply)
        val onComponent = model(component)
        backgroundScope.launch { onSupply.state.collect() }
        backgroundScope.launch { onComponent.state.collect() }

        assertFalse(
            "an asset's file never marks a SupplyItem's link",
            onSupply.state.first { it.rows.isNotEmpty() }.rows.single().savedAsDocument,
        )
        assertFalse(
            "an asset's file never marks a component's link",
            onComponent.state.first { it.rows.isNotEmpty() }.rows.single().savedAsDocument,
        )

        sourced(supply.asAttachmentOwner(), MANUAL)

        onSupply.state.first { it.rows.single().savedAsDocument }
        // Drain the component's flow too, so a read of every owner's files would have reached it by now.
        advanceUntilIdle()
        assertFalse(
            "the SupplyItem's file never marks the component's link",
            onComponent.state.value.rows.single().savedAsDocument,
        )
        clearModels()
    }

    @Test fun addPassesTheOwner() = runTest {
        val (asset, supply, component) = threeOwners()
        listOf(supply, component).forEach { owner ->
            val vm = model(owner)
            backgroundScope.launch { vm.state.collect() }
            vm.addLink(DATA_SHEET, "Battery data sheet", "", role = null)
            vm.state.first { it.rows.size == 1 }
        }

        assertEquals(
            mapOf(asset to 0, supply to 1, component to 1),
            listOf(asset, supply, component).associateWith { graph.references.forOwner(it).size },
        )
        assertEquals(setOf(supply, component), graph.references.all().map { it.owner }.toSet())
        clearModels()
    }

    /**
     * C28 (R69-13): the duplicate is said in the owner's own words; an asset's stays the shipped sentence, and
     * Share's declaration answers each owner the same way from the same home.
     */
    @Test fun theDuplicateSentenceIsTheOwnersTwin() = runTest {
        val (asset, supply, component) = threeOwners()
        val said = listOf(supply, component).associateWith { owner ->
            val vm = model(owner)
            backgroundScope.launch { vm.state.collect() }
            vm.addLink(DATA_SHEET, "Battery data sheet", "", role = null)
            vm.state.first { it.rows.size == 1 }
            val line = async(Dispatchers.Main) { vm.messages.first() }
            vm.addLink(DATA_SHEET, "Battery data sheet again", "", role = null)
            line.await()
        }

        assertEquals(
            mapOf(
                supply to "That link is already on this supply",
                component to "That link is already on this installed component",
            ),
            said,
        )
        assertEquals("That link is already on this asset", duplicateUriOn(asset))
        listOf(asset, supply, component).forEach { owner ->
            assertEquals(duplicateUriOn(owner), IntakeStrings.duplicateUri(owner))
        }
        clearModels()
    }

    /** C28: the remove confirmation's body, one twin per owner kind; an asset's is the shipped sentence. */
    @Test fun theRemoveConfirmationIsTheOwnersTwin() {
        assertEquals(
            listOf(
                "The link is removed from this asset. Nothing in the other app is changed.",
                "The link is removed from this supply. Nothing in the other app is changed.",
                "The link is removed from this installed component. Nothing in the other app is changed.",
            ),
            listOf(
                ReferenceOwner.OfAsset(AssetId(SAME)),
                ReferenceOwner.OfSupplyItem(SupplyId(SAME)),
                ReferenceOwner.OfInstalledComponent(InstalledComponentId(SAME)),
            ).map(::removedFrom),
        )
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
