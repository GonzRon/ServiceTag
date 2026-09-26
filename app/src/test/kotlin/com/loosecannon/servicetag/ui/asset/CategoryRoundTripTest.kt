package com.loosecannon.servicetag.ui.asset

import com.loosecannon.servicetag.core.merge.MergeVerdict
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.assetRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #74 AC 4 end to end on the JVM, across the three briefs: a category saved on one store survives
 * export and a replace onto an empty one, and reaches the picker there; and a replace that
 * canonicalises spellings leaves a store the same archive re-plans against with nothing to insert.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CategoryRoundTripTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var source: FakeGraph
    private lateinit var target: FakeGraph

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        source = FakeGraph(queryContext = StandardTestDispatcher(scheduler))
        target = FakeGraph(queryContext = StandardTestDispatcher(scheduler))
    }

    @After fun tearDown() {
        source.close()
        target.close()
        Dispatchers.resetMain()
    }

    @Test fun aSavedCategoryAndAnUnusedOneSurviveExportAndReplaceIntoThePicker() = runTest {
        source.createAsset.run(AssetCommand(name = "Pump 3", category = "Appliance"))
        // Spare is promoted by a save and then left unused by the next one: a row with no asset.
        val unit = source.createAsset.run(AssetCommand(name = "Unit A", category = "Spare"))
        source.updateAsset.run(unit.id, AssetCommand(name = "Unit A", category = "appliance"))

        target.importBackupReplace.run(source.exportBackupSet.run().data)

        assertEquals(listOf("appliance" to "Appliance", "spare" to "Spare"), target.categories.all().map { it.key to it.display })
        val picker = AssetEditViewModel(
            target.assets, target.healthSubjects, target.saveAssetSettings, target.schedules, target.categories, null,
        )
        backgroundScope.launch { picker.categoryChoices.collect() }
        advanceUntilIdle()
        val choices = picker.categoryChoices.value
        assertEquals(listOf("Appliance", "Spare"), choices.drop(12).map { it.display })
        assertTrue(choices.take(12).all { it.builtIn } && choices.drop(12).none { it.builtIn })
    }

    @Test fun aReplaceCanonicalisesSpellingsAndTheSameArchiveReplansWithNothingToInsert() = runTest {
        // Spellings as a store from before the catalog held them: three of one key, one of a built-in's.
        listOf("a1" to "Appliance", "a2" to "appliance", "a3" to "APPLIANCE", "a4" to "generator").forEachIndexed { i, (id, typed) ->
            source.assets.upsert(assetRow(id).copy(category = typed, createdAt = 1L + i, updatedAt = 100L + i))
        }
        val bytes = source.exportBackupSet.run().data

        target.importBackupReplace.run(bytes)

        assertEquals(listOf("appliance" to "Appliance"), target.categories.all().map { it.key to it.display })
        val restored = target.assets.all().sortedBy { it.id.value }
        assertEquals(listOf("Appliance", "Appliance", "Appliance", "Generator"), restored.map { it.category })
        assertEquals(listOf(100L, 101L, 102L, 103L), restored.map { it.updatedAt })

        val plan = target.buildBackupMergePlan.run(bytes)
        assertTrue(plan.applicable)
        assertEquals(0, plan.decisions.count { it.verdict == MergeVerdict.INSERT })
        assertEquals(List(4) { MergeVerdict.IDENTICAL }, plan.decisions.map { it.verdict })
    }
}
