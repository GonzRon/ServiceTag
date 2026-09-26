package com.loosecannon.servicetag.ui.settings

import com.loosecannon.servicetag.core.journal.CategorySuggestions
import com.loosecannon.servicetag.core.model.AssetCategory
import com.loosecannon.servicetag.core.ports.CategoryRepository
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.DeleteCategory
import com.loosecannon.servicetag.core.usecase.RenameCategory
import com.loosecannon.servicetag.testing.FakeGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Settings → Categories (#74, C17) against a Room-backed [FakeGraph]: the two lists and their counts,
 * the held Rename, where each refusal is said — under the rename field, on the snackbar, or nowhere —
 * and that a delete asks only when nothing uses the category. The sentences are hard-coded here, in
 * their ratified words (plan §6), so a paraphrase in the model is a failure and not a pass.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CategoriesViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var graph: FakeGraph

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        graph = FakeGraph(queryContext = StandardTestDispatcher(scheduler))
    }

    @After fun tearDown() {
        graph.close()
        Dispatchers.resetMain()
    }

    /**
     * Three of the owner's categories: Appliance used by two assets, one of them archived; Machine by
     * one, retired; Spare by none — a row that outlived its assets, which is what makes it durable.
     */
    private suspend fun seed() {
        graph.createAsset.run(AssetCommand(name = "Pump 3", category = "Appliance"))
        val archived = graph.createAsset.run(AssetCommand(name = "Unit A", category = "appliance"))
        graph.archiveAsset.run(archived.id)
        val retired = graph.createAsset.run(AssetCommand(name = "Unit B", category = "Machine"))
        graph.retireAsset.retire(retired.id, "2026-04-02")
        graph.categories.upsert(AssetCategory("spare", "Spare", 1L, 1L))
    }

    /** The model with its state collected, settled; and what it has said on the snackbar so far. */
    private class Open(val model: CategoriesViewModel, val said: List<String>) {
        fun row(display: String): OwnCategory = model.state.value!!.own.single { it.display == display }
    }

    private fun TestScope.open(
        rename: RenameCategory = graph.renameCategory,
        delete: DeleteCategory = graph.deleteCategory,
    ): Open {
        val model = CategoriesViewModel(graph.categories, graph.assets, rename, delete)
        val said = mutableListOf<String>()
        backgroundScope.launch { model.state.collect() }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.messages.collect { said += it } }
        advanceUntilIdle()
        return Open(model, said)
    }

    private suspend fun stored(): List<Pair<String, String>> = graph.categories.all().map { it.key to it.display }

    /** The store, except that writing a row fails the way a full or broken disk would. */
    private class UpsertFails(real: CategoryRepository) : CategoryRepository by real {
        override suspend fun upsert(row: AssetCategory): Unit = throw IllegalStateException("disk I/O error")
    }

    /** The store, except that deleting a row fails the way a full or broken disk would. */
    private class DeleteFails(real: CategoryRepository) : CategoryRepository by real {
        override suspend fun delete(key: String): Unit = throw IllegalStateException("disk I/O error")
    }

    @Test fun theOwnersRowsCarryTheirUsageArchivedAndRetiredIncludedAndTheBuiltInsFollow() = runTest {
        seed()
        val screen = open()
        val state = screen.model.state.value!!

        assertEquals(
            listOf(
                OwnCategory("appliance", "Appliance", 2),
                OwnCategory("machine", "Machine", 1),
                OwnCategory("spare", "Spare", 0),
            ),
            state.own,
        )
        assertEquals(
            listOf("Used by 2 assets", "Used by 1 asset", "Not used"),
            state.own.map { usageLine(it.usage) },
        )
        assertEquals(CategorySuggestions.all.map { it.label }, state.builtIns)

        // The list follows the store: a category saved elsewhere is here without reopening the screen.
        graph.createAsset.run(AssetCommand(name = "Unit C", category = "Tooling"))
        advanceUntilIdle()
        assertEquals(
            listOf("Appliance", "Machine", "Spare", "Tooling"),
            screen.model.state.value!!.own.map(OwnCategory::display),
        )
    }

    @Test fun anEmptyCatalogListsNoRowsOfItsOwnAndStillTheBuiltIns() = runTest {
        val state = open().model.state.value!!
        assertTrue(state.own.isEmpty())
        assertEquals(12, state.builtIns.size)
    }

    @Test fun renameIsHeldWhileTheTextIsBlankOrUnchanged() = runTest {
        seed()
        val screen = open()
        screen.model.startRename(screen.row("Appliance"))
        val draft = screen.model.rename.value!!
        assertEquals("Appliance", draft.text)
        assertFalse("unchanged", draft.canRename)

        fun heldFor(text: String): Boolean {
            screen.model.onRenameText(text)
            return !screen.model.rename.value!!.canRename
        }
        assertTrue("empty", heldFor(""))
        assertTrue("blank", heldFor("   "))
        assertTrue("the same display once trimmed and collapsed", heldFor("  Appliance "))
        assertFalse("a spelling change is a change", heldFor("appliance"))
        assertFalse("a new name", heldFor("Appliances"))
    }

    @Test fun aRenameRewritesTheRowAndItsAssetsAndClosesWithNoLine() = runTest {
        seed()
        val screen = open()
        screen.model.startRename(screen.row("Appliance"))
        screen.model.onRenameText("Appliances")
        screen.model.confirmRename()
        advanceUntilIdle()

        assertNull(screen.model.rename.value)
        assertEquals(listOf("appliances" to "Appliances", "machine" to "Machine", "spare" to "Spare"), stored())
        assertEquals(
            listOf("Appliances", "Appliances", "Machine"),
            graph.assets.all().map { it.category }.sorted(),
        )
        assertEquals(OwnCategory("appliances", "Appliances", 2), screen.row("Appliances"))
        assertTrue(screen.said.isEmpty())
    }

    @Test fun aTakenNameIsRefusedUnderTheFieldAndTheDialogStays() = runTest {
        seed()
        val screen = open()
        screen.model.startRename(screen.row("Spare"))
        screen.model.onRenameText("APPLIANCE")
        screen.model.confirmRename()
        advanceUntilIdle()

        val draft = screen.model.rename.value
        assertNotNull("the dialog stays", draft)
        assertEquals("A category named Appliance already exists.", draft!!.refusal)
        assertEquals("APPLIANCE", draft.text)
        assertTrue("the Rename can be tried again", draft.canRename)
        assertTrue("never the snackbar", screen.said.isEmpty())
        assertEquals(listOf("appliance" to "Appliance", "machine" to "Machine", "spare" to "Spare"), stored())

        // Typing takes the refusal down; the next answer is about the next name.
        screen.model.onRenameText("Spares")
        assertNull(screen.model.rename.value!!.refusal)
    }

    @Test fun aBuiltInNameIsRefusedInTheBuiltInsOwnSpelling() = runTest {
        seed()
        val screen = open()
        screen.model.startRename(screen.row("Spare"))
        screen.model.onRenameText("  GENERATOR ")
        screen.model.confirmRename()
        advanceUntilIdle()

        assertEquals("Generator is a built-in category.", screen.model.rename.value!!.refusal)
        assertTrue(screen.said.isEmpty())
        assertEquals("Spare", graph.categories.get("spare")!!.display)
    }

    @Test fun aRenameOfARowThatIsGoneClosesWithoutAWord() = runTest {
        seed()
        val screen = open()
        screen.model.startRename(screen.row("Spare"))
        graph.deleteCategory.run("spare")
        screen.model.onRenameText("Spares")
        screen.model.confirmRename()
        advanceUntilIdle()

        assertNull(screen.model.rename.value)
        assertTrue(screen.said.isEmpty())
        assertEquals(listOf("Appliance", "Machine"), screen.model.state.value!!.own.map(OwnCategory::display))
    }

    /**
     * The follow-ups' F1 (P74-18, owner 2026-09-26): a failure that is none of the named refusals keeps
     * the dialog as typed, says so under the field — never on the snackbar — and typing takes it down.
     */
    @Test fun anUnexpectedRenameFailureKeepsTheDialogAndSaysSo() = runTest {
        seed()
        val screen = open(rename = RenameCategory(UpsertFails(graph.categories), graph.assets, graph.uow, graph.clock))
        screen.model.startRename(screen.row("Spare"))
        screen.model.onRenameText("Spares")
        screen.model.confirmRename()
        advanceUntilIdle()

        val draft = screen.model.rename.value
        assertNotNull("the dialog stays", draft)
        assertEquals("Could not rename that category.", draft!!.refusal)
        assertFalse(draft.renaming)
        assertEquals("Spares", draft.text)
        assertTrue("the Rename can be tried again", draft.canRename)
        assertTrue("never the snackbar", screen.said.isEmpty())
        assertEquals(listOf("appliance" to "Appliance", "machine" to "Machine", "spare" to "Spare"), stored())

        screen.model.onRenameText("Spare parts")
        assertNull(screen.model.rename.value!!.refusal)
    }

    @Test fun anUnusedDeleteAsksFirstThenRemovesTheRow() = runTest {
        seed()
        val screen = open()
        screen.model.requestDelete(screen.row("Spare"))

        assertEquals(DeleteAsk("spare", "Spare"), screen.model.deleting.value)
        assertEquals("Spare is not used by any asset.", notUsedByAnyAsset(screen.model.deleting.value!!.display))
        assertNotNull("asking writes nothing", graph.categories.get("spare"))

        screen.model.confirmDelete()
        advanceUntilIdle()
        assertNull(screen.model.deleting.value)
        assertNull(graph.categories.get("spare"))
        assertEquals(listOf("Appliance", "Machine"), screen.model.state.value!!.own.map(OwnCategory::display))
        assertTrue(screen.said.isEmpty())
    }

    @Test fun aDeleteInUseAsksNothingSaysSoAndRemovesNothing() = runTest {
        seed()
        val screen = open()
        screen.model.requestDelete(screen.row("Appliance"))
        screen.model.requestDelete(screen.row("Machine"))
        advanceUntilIdle()

        assertNull("no dialog", screen.model.deleting.value)
        assertEquals(
            listOf(
                "Appliance is used by 2 assets. Change their category first.",
                "Machine is used by 1 asset. Change its category first.",
            ),
            screen.said,
        )
        assertEquals(listOf("appliance" to "Appliance", "machine" to "Machine", "spare" to "Spare"), stored())
    }

    /** The confirmation is not the last word: the use case counts again, and an asset that arrived wins. */
    @Test fun aDeleteThatBecameInUseAfterTheQuestionIsRefusedWithTheCount() = runTest {
        seed()
        val screen = open()
        screen.model.requestDelete(screen.row("Spare"))
        graph.createAsset.run(AssetCommand(name = "Unit C", category = "spare"))
        screen.model.confirmDelete()
        advanceUntilIdle()

        assertNull(screen.model.deleting.value)
        assertEquals(listOf("Spare is used by 1 asset. Change its category first."), screen.said)
        assertNotNull(graph.categories.get("spare"))
    }

    /**
     * The follow-ups' F2 (P74-19, owner 2026-09-26): a failure that is none of the named refusals closes
     * the confirmation, keeps the row, and says so on the snackbar.
     */
    @Test fun anUnexpectedDeleteFailureSaysSoOnTheSnackbar() = runTest {
        seed()
        val screen = open(delete = DeleteCategory(DeleteFails(graph.categories), graph.assets, graph.uow))
        screen.model.requestDelete(screen.row("Spare"))
        screen.model.confirmDelete()
        advanceUntilIdle()

        assertNull(screen.model.deleting.value)
        assertEquals(listOf("Could not delete that category."), screen.said)
        assertEquals(listOf("Appliance", "Machine", "Spare"), screen.model.state.value!!.own.map(OwnCategory::display))
        assertEquals(listOf("appliance" to "Appliance", "machine" to "Machine", "spare" to "Spare"), stored())
    }

    @Test fun aDeleteOfARowThatIsGoneClosesWithoutAWord() = runTest {
        seed()
        val screen = open()
        screen.model.requestDelete(screen.row("Spare"))
        graph.deleteCategory.run("spare")
        screen.model.confirmDelete()
        advanceUntilIdle()

        assertNull(screen.model.deleting.value)
        assertTrue(screen.said.isEmpty())
    }

    @Test fun cancellingEitherDialogWritesNothing() = runTest {
        seed()
        val screen = open()
        screen.model.startRename(screen.row("Spare"))
        screen.model.onRenameText("Spares")
        screen.model.dismissRename()
        screen.model.requestDelete(screen.row("Spare"))
        screen.model.dismissDelete()
        advanceUntilIdle()

        assertNull(screen.model.rename.value)
        assertNull(screen.model.deleting.value)
        assertEquals(listOf("appliance" to "Appliance", "machine" to "Machine", "spare" to "Spare"), stored())
        assertTrue(screen.said.isEmpty())
    }
}
