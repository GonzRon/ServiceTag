package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.journal.SeedTemplates
import com.loosecannon.servicetag.core.model.*
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.testing.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ApplyTemplateTest {
    private val assets = InMemoryAssetRepository()
    private val defs = InMemoryDefinitionRepository()
    private val profiles = InMemoryProfileRepository()
    private val categories = InMemoryCategoryRepository()
    private val uow = FakeUnitOfWork(assets, defs, profiles, categories)
    private var seq = 0
    private val ids = IdGenerator { "id-${++seq}" }
    private val apply = ApplyTemplate(defs, profiles, assets, uow, ids, Clock { 1_000L })
    private val hotTub = SeedTemplates.byKey("hot_tub")!!

    private suspend fun asset(templateKey: String? = null) = Asset(
        id = AssetId("a1"), name = "Spa", createdAt = 1L, updatedAt = 1L, templateKey = templateKey,
    ).also { assets.upsert(it) }

    @Test fun appliesDefinitionsProfilesAndProvenanceOnce() = runTest {
        asset()
        val r = apply.run(AssetId("a1"), hotTub) as ApplyResult.Applied
        assertEquals(5, r.definitions.size); assertEquals(2, r.profiles.size)
        assertEquals("hot_tub", assets.get(AssetId("a1"))!!.templateKey)
        val waterTest = profiles.forAsset(AssetId("a1")).first { it.name == "Water test" }
        assertEquals(5, waterTest.fields.size)
        assertEquals(setOf("ph", "free_chlorine"),
            waterTest.fields.filter { it.required }.map { f -> defs.get(f.definitionId)!!.key }.toSet())
        assertEquals("hot_tub", waterTest.templateKey)
        // child rows have durable ids of their own
        assertEquals(5, waterTest.fields.map { it.id }.toSet().size)
        assertEquals(4, waterTest.consumables.map { it.id }.toSet().size)
    }

    @Test fun secondApplicationIsANoOp() = runTest {
        asset()
        apply.run(AssetId("a1"), hotTub)
        val before = defs.forAsset(AssetId("a1")) to profiles.forAsset(AssetId("a1"))
        assertEquals(ApplyResult.AlreadySetUp, apply.run(AssetId("a1"), SeedTemplates.byKey("ups")!!))
        assertEquals(before, defs.forAsset(AssetId("a1")) to profiles.forAsset(AssetId("a1")))
        assertEquals("hot_tub", assets.get(AssetId("a1"))!!.templateKey)
    }

    @Test fun keepsAnExistingProvenanceKey() = runTest {
        asset(templateKey = "generic")
        apply.run(AssetId("a1"), hotTub)
        assertEquals("generic", assets.get(AssetId("a1"))!!.templateKey)
    }

    @Test fun unknownAssetFails() = runTest {
        assertFailsWith<NoSuchAsset> { apply.run(AssetId("nope"), hotTub) }
    }

    @Test fun derivedSourcesResolveToMintedIds() = runTest {
        asset()
        val roWater = SeedTemplates.byKey("ro_water")!!
        val r = apply.run(AssetId("a1"), roWater) as ApplyResult.Applied
        val prefilter = r.definitions.first { it.key == "tds_prefilter" }
        val postMembrane = r.definitions.first { it.key == "tds_post_membrane" }
        val rejection = r.definitions.first { it.key == "rejection_percent" }
        assertEquals(DefinitionKind.DERIVED, rejection.kind)
        assertEquals(DerivedSpec(DerivedFormula.PERCENT_DROP, prefilter.id, postMembrane.id), rejection.derived)
        r.definitions.filter { it.key != "rejection_percent" }.forEach {
            assertEquals(DefinitionKind.ENTERED, it.kind); assertNull(it.derived)
        }
        // ENTERED definitions inserted before DERIVED ones
        assertEquals(r.definitions.size - 1, r.definitions.indexOfFirst { it.key == "rejection_percent" })
    }

    @Test fun createAssetWithTemplateSeedsInTheSameTransaction() = runTest {
        val create = CreateAsset(assets, uow, ids, Clock { 1_000L }, apply, PromoteCategory(categories))
        val a = create.run("UPS", templateKey = "ups")
        assertEquals(4, defs.forAsset(a.id).size)
        assertEquals("ups", assets.get(a.id)!!.templateKey)
        val plain = create.run("Plain")                       // templateKey = null: no template
        assertEquals(0, defs.forAsset(plain.id).size); assertEquals(0, profiles.forAsset(plain.id).size)
        assertNull(assets.get(plain.id)!!.templateKey)
        assertTrue(apply.run(plain.id, hotTub) is ApplyResult.Applied)   // can be set up later
    }
}
