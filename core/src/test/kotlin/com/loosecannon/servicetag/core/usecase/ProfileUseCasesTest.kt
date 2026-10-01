package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.journal.SeedTemplates
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.EventProfile
import com.loosecannon.servicetag.core.model.EventSource
import com.loosecannon.servicetag.core.model.Measurement
import com.loosecannon.servicetag.core.model.MeasurementDefinition
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.testing.FakeUnitOfWork
import com.loosecannon.servicetag.core.testing.InMemoryAssetRepository
import com.loosecannon.servicetag.core.testing.InMemoryDefinitionRepository
import com.loosecannon.servicetag.core.testing.InMemoryEventRepository
import com.loosecannon.servicetag.core.testing.InMemoryProfileRepository
import com.loosecannon.servicetag.core.testing.InMemorySupplyItemRepository
import com.loosecannon.servicetag.core.testing.supplyItemOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class ProfileUseCasesTest {
    private val assets = InMemoryAssetRepository()
    private val defs = InMemoryDefinitionRepository()
    private val profiles = InMemoryProfileRepository()
    private val events = InMemoryEventRepository()
    private val supplyItems = InMemorySupplyItemRepository()
    private val uow = FakeUnitOfWork(assets, defs, profiles, events)
    private var seq = 0
    private val ids = IdGenerator { "id-${++seq}" }
    private val clock = Clock { 5_000L }
    private val save = SaveProfile(profiles, defs, assets, supplyItems, uow, ids, clock)
    private val archive = ArchiveProfile(profiles, uow, clock)
    private val delete = DeleteProfile(profiles, uow)
    private val reorder = ReorderProfiles(profiles, uow, clock)
    private val archiveDefinition = ArchiveDefinition(defs, uow, clock)
    private val a1 = AssetId("a1")

    private suspend fun seed(templateKey: String, assetId: AssetId = a1) {
        assets.upsert(Asset(id = assetId, name = "Spa ${assetId.value}", createdAt = 1L, updatedAt = 1L))
        ApplyTemplate(defs, profiles, assets, uow, ids, clock)
            .run(assetId, SeedTemplates.byKey(templateKey)!!)
    }

    private suspend fun byKey(key: String, assetId: AssetId = a1): MeasurementDefinition =
        defs.forAsset(assetId).first { it.key == key }

    private suspend fun profile(name: String, assetId: AssetId = a1): EventProfile =
        profiles.forAsset(assetId).first { it.name == name }

    private fun cmd(
        name: String,
        eventKind: EventKind = EventKind.MEASUREMENT,
        defaultTitle: String = "",
        fields: List<ProfileFieldInput> = emptyList(),
        consumables: List<ProfileConsumableInput> = emptyList(),
        assetId: AssetId = a1,
    ) = ProfileCommand(assetId, name, eventKind, defaultTitle, fields, consumables)

    /** Runs [block] expecting it to be refused, and hands back every problem it collected. */
    private suspend fun problemsOf(block: suspend () -> Unit): List<ProfileProblem> =
        try {
            block()
            fail("expected ProfileValidation")
        } catch (e: ProfileValidation) {
            e.problems
        }

    @Test fun nameUniquePerAssetCaseInsensitive() = runTest {
        seed("hot_tub")
        seed("ups", AssetId("a2"))   // no "Water test" of its own
        assertTrue(problemsOf { save.run(null, cmd("  water TEST ")) }.contains(ProfileProblem.NameTaken))
        // archived profiles keep their name reserved
        archive.run(profile("Treatment").id, archived = true)
        assertTrue(problemsOf { save.run(null, cmd("treatment")) }.contains(ProfileProblem.NameTaken))
        // a profile may keep its own name across an edit
        val waterTest = profile("Water test")
        assertEquals("Water test", save.run(waterTest.id, cmd("Water test")).name)
        // the same name on another asset is that asset's business
        val other = save.run(null, cmd("Water test", assetId = AssetId("a2")))
        assertEquals(AssetId("a2"), other.assetId)
        assertTrue(problemsOf { save.run(null, cmd("   ")) }.contains(ProfileProblem.NameRequired))
        // a name that is free is accepted, and the default title falls back to it
        val fresh = save.run(null, cmd("Shock treatment"))
        assertEquals("Shock treatment", fresh.defaultTitle)
        assertEquals(EventKind.MEASUREMENT, fresh.eventKind)
        assertNull(fresh.templateKey)
    }

    @Test fun fieldMustBeEnteredUnarchivedOnAsset() = runTest {
        seed("ro_water")
        seed("hot_tub", AssetId("a2"))
        val prefilter = byKey("tds_prefilter")
        val output = byKey("tds_output")
        val rejection = byKey("rejection_percent")
        val foreign = byKey("ph", AssetId("a2"))
        val ghost = DefinitionId("no-such-definition")
        archiveDefinition.run(output.id, archived = true)

        val problems = problemsOf {
            save.run(
                null,
                cmd(
                    "Odd test",
                    fields = listOf(
                        ProfileFieldInput(rejection.id, required = false),
                        ProfileFieldInput(output.id, required = false),
                        ProfileFieldInput(foreign.id, required = true),
                        ProfileFieldInput(ghost, required = false),
                        ProfileFieldInput(prefilter.id, required = true),
                        ProfileFieldInput(prefilter.id, required = false),
                    ),
                ),
            )
        }
        val bad = problems.filterIsInstance<ProfileProblem.BadField>()
        assertEquals(
            listOf(rejection.id, output.id, foreign.id, ghost, prefilter.id),
            bad.map { it.id },
        )
        assertTrue(bad.all { it.reason.isNotBlank() }, "$bad")
        assertEquals(0, profiles.forAsset(a1).count { it.name == "Odd test" })

        // a bad consumable is reported by its row index
        assertEquals(
            listOf(ProfileProblem.BadConsumable(1)),
            problemsOf {
                save.run(
                    null,
                    cmd(
                        "TDS retest",
                        consumables = listOf(
                            ProfileConsumableInput(null, "Filter", 1.0, "pcs", supplyId = null),
                            ProfileConsumableInput(null, " ", null, "pcs", supplyId = null),
                        ),
                    ),
                )
            },
        )

        // an ENTERED, unarchived definition of this asset is fine
        val ok = save.run(
            null,
            cmd("TDS retest", fields = listOf(ProfileFieldInput(prefilter.id, required = true))),
        )
        assertEquals(listOf(prefilter.id), ok.fields.map { it.definitionId })
        assertTrue(ok.fields.single().required)
    }

    @Test fun existingArchivedFieldSurvivesProfileResave() = runTest {
        seed("ro_water")
        val tdsTest = profile("TDS test")
        val prefilter = byKey("tds_prefilter")
        val postMembrane = byKey("tds_post_membrane")
        val output = byKey("tds_output")
        val outputField = tdsTest.fields.first { it.definitionId == output.id }
        archiveDefinition.run(output.id, archived = true)

        // renaming the profile keeps the field whose definition was archived under it, id and all
        val saved = save.run(
            tdsTest.id,
            cmd(
                "TDS check",
                fields = tdsTest.fields.map { ProfileFieldInput(it.definitionId, it.required) },
            ),
        )
        assertEquals("TDS check", saved.name)
        assertEquals(listOf(prefilter.id, postMembrane.id, output.id), saved.fields.map { it.definitionId })
        assertEquals(outputField.id, saved.fields.last().id)
        assertEquals(saved, profiles.get(tdsTest.id))

        // but it cannot be added to a profile that didn't already have it
        val problems = problemsOf {
            save.run(null, cmd("TDS retest", fields = listOf(ProfileFieldInput(output.id, required = false))))
        }
        assertEquals(listOf(ProfileProblem.BadField(output.id, "archived")), problems)
    }

    @Test fun updateKeepsChildIds() = runTest {
        seed("hot_tub")
        val waterTest = profile("Water test")
        val ph = byKey("ph")
        val freeChlorine = byKey("free_chlorine")
        val waterTemp = byKey("water_temp")
        val phField = waterTest.fields.first { it.definitionId == ph.id }
        val chlorineField = waterTest.fields.first { it.definitionId == freeChlorine.id }
        val chlorineConsumable = waterTest.consumables.first { it.name == "Chlorine" }

        val saved = save.run(
            waterTest.id,
            cmd(
                "Water test",
                defaultTitle = "Water test",
                fields = listOf(
                    ProfileFieldInput(freeChlorine.id, required = false),
                    ProfileFieldInput(ph.id, required = true),
                    ProfileFieldInput(waterTemp.id, required = false),
                ),
                consumables = listOf(
                    ProfileConsumableInput(chlorineConsumable.id, "Chlorine", 2.0, "oz", supplyId = null),
                    ProfileConsumableInput(null, "Clarifier", null, "oz", supplyId = null),
                ),
            ),
        )
        assertEquals(waterTest.id, saved.id)
        assertEquals(waterTest.createdAt, saved.createdAt)
        assertEquals("hot_tub", saved.templateKey)
        assertEquals(waterTest.sortOrder, saved.sortOrder)
        // ids of fields whose definition is still on the profile survive the edit
        assertEquals(chlorineField.id, saved.fields[0].id)
        assertEquals(phField.id, saved.fields[1].id)
        assertNotEquals(phField.id, saved.fields[2].id)
        assertEquals(listOf(0, 1, 2), saved.fields.map { it.sortOrder })
        assertEquals(listOf(false, true, false), saved.fields.map { it.required })
        // consumable ids are kept when the editor passes them back, minted when it doesn't
        assertEquals(chlorineConsumable.id, saved.consumables[0].id)
        assertEquals(2.0, saved.consumables[0].defaultQuantity)
        assertTrue(saved.consumables[1].id.isNotBlank())
        assertEquals(listOf(0, 1), saved.consumables.map { it.sortOrder })
        assertEquals(saved, profiles.get(waterTest.id))
        assertFailsWith<NoSuchProfile> { save.run(ProfileId("nope"), cmd("Ghost")) }
    }

    @Test fun foreignConsumableIdIsNotTrusted() = runTest {
        seed("hot_tub")
        val waterTest = profile("Water test")
        val treatment = profile("Treatment")
        val ownId = waterTest.consumables.first { it.name == "Chlorine" }.id
        val foreignId = treatment.consumables.first { it.name == "pH reducer" }.id

        val saved = save.run(
            waterTest.id,
            cmd(
                "Water test",
                consumables = listOf(
                    ProfileConsumableInput(ownId, "Chlorine", 1.0, "oz", supplyId = null),
                    ProfileConsumableInput(foreignId, "pH reducer", null, "oz", supplyId = null),
                    ProfileConsumableInput("made-up", "Clarifier", null, "oz", supplyId = null),
                ),
            ),
        )
        assertEquals(ownId, saved.consumables[0].id)
        assertNotEquals(foreignId, saved.consumables[1].id)
        assertNotEquals("made-up", saved.consumables[2].id)
        assertEquals(3, saved.consumables.map { it.id }.toSet().size)
        // the other profile keeps its own row untouched
        assertEquals(treatment.consumables, profiles.get(treatment.id)!!.consumables)
    }

    @Test fun deleteProfileLeavesEventsWithProfileCleared() = runTest {
        seed("hot_tub")
        val waterTest = profile("Water test")
        val ph = byKey("ph")
        events.upsert(
            AssetEvent(
                id = EventId("e1"), assetId = a1, kind = EventKind.MEASUREMENT, title = "Water test",
                profileId = waterTest.id, occurredOn = "2026-09-15", occurredTime = null, tzId = "UTC",
                notes = "", source = EventSource.MANUAL, sourceRef = null, createdAt = 1L, updatedAt = 1L,
                measurements = listOf(Measurement("m1", ph.id, 7.4, null, "", 0)),
                consumables = emptyList(),
            ),
        )
        // the schema SET NULLs event.profile_id; the fake does it through this hook
        profiles.onDeleted = { events.clearProfile(it) }

        val commits = uow.commits
        delete.run(waterTest.id)
        assertNull(profiles.get(waterTest.id))
        assertEquals(commits + 1, uow.commits)
        val kept = assertNotNull(events.get(EventId("e1")))
        assertNull(kept.profileId)
        assertEquals(1, kept.measurements.size)
        assertEquals("Water test", kept.title)
        assertFailsWith<NoSuchProfile> { delete.run(waterTest.id) }
    }

    @Test fun reorderProfiles() = runTest {
        seed("hot_tub")
        seed("ups", AssetId("a2"))
        val original = profiles.forAsset(a1).sortedBy { it.sortOrder }.map { it.name }
        assertEquals(listOf("Water test", "Treatment"), original)
        val commits = uow.commits
        reorder.run(a1, profiles.forAsset(a1).sortedByDescending { it.sortOrder }.map { it.id })
        assertEquals(original.reversed(), profiles.forAsset(a1).sortedBy { it.sortOrder }.map { it.name })
        assertEquals(listOf(0, 1), profiles.forAsset(a1).map { it.sortOrder }.sorted())
        assertEquals(commits + 1, uow.commits)
        assertFailsWith<EventOwnership> {
            reorder.run(a1, listOf(profiles.forAsset(AssetId("a2")).first().id))
        }
        assertEquals(listOf("Treatment", "Water test"), profiles.forAsset(a1).sortedBy { it.sortOrder }.map { it.name })
    }

    @Test fun archiveAndUnarchiveProfile() = runTest {
        seed("hot_tub")
        val waterTest = profile("Water test")
        archive.run(waterTest.id, archived = true)
        assertEquals(5_000L, profiles.get(waterTest.id)!!.archivedAt)
        assertEquals(5_000L, profiles.get(waterTest.id)!!.updatedAt)
        archive.run(waterTest.id, archived = false)
        assertNull(profiles.get(waterTest.id)!!.archivedAt)
        assertFailsWith<NoSuchProfile> { archive.run(ProfileId("nope"), archived = true) }
    }

    // ---------------------------------------------------------------------------------------------------------
    // #15 (B4a) — rows 39 and 40: a quick action's line carries the SupplyItem it names (C19, C20). The line's
    // name and unit stay its own readable snapshot; the link only says which SupplyItem the line is.
    // ---------------------------------------------------------------------------------------------------------

    private suspend fun prefilterQuickAction(supplyId: SupplyId?): EventProfile {
        assets.upsert(Asset(id = a1, name = "Example RO System", createdAt = 1L, updatedAt = 1L))
        return save.run(
            null,
            cmd(
                "Change prefilter",
                eventKind = EventKind.MAINTENANCE,
                consumables = listOf(
                    ProfileConsumableInput(null, "Prefilter cartridge", 1.0, "", supplyId = supplyId),
                    ProfileConsumableInput(null, "O-ring grease", null, "g", supplyId = null),
                ),
            ),
        )
    }

    @Test fun aLinkedLineIsStored() = runTest {
        supplyItems.upsert(supplyItemOf("s-pf", "Example Prefilter Cartridge"))

        val saved = prefilterQuickAction(SupplyId("s-pf"))

        assertEquals(listOf(SupplyId("s-pf"), null), saved.consumables.map { it.supplyId })
        // The snapshot is the line's own: never the item's name, and a blank unit is not filled from the
        // item's preferred unit ("ea") — the phone pre-fills a blank, the core never does.
        assertEquals(listOf("Prefilter cartridge", "O-ring grease"), saved.consumables.map { it.name })
        assertEquals(listOf("", "g"), saved.consumables.map { it.unit })
        assertEquals(saved, profiles.get(saved.id))
    }

    @Test fun anEditCarryingTheLinkKeepsIt() = runTest {
        supplyItems.upsert(supplyItemOf("s-pf", "Example Prefilter Cartridge"))
        val created = prefilterQuickAction(SupplyId("s-pf"))
        val line = created.consumables.first()

        val edited = save.run(
            created.id,
            cmd(
                "Change the prefilter",
                eventKind = EventKind.MAINTENANCE,
                consumables = listOf(ProfileConsumableInput(line.id, "Prefilter cartridge", 2.0, "ea", supplyId = line.supplyId)),
            ),
        )

        assertEquals(line.id, edited.consumables.single().id)
        assertEquals(SupplyId("s-pf"), edited.consumables.single().supplyId)
        assertEquals(edited, profiles.get(created.id))
    }

    @Test fun anEditWithoutTheLinkClearsIt() = runTest {
        // The request is a full replace (limit 1): a line sent with no supplyId is an unlinked line.
        supplyItems.upsert(supplyItemOf("s-pf", "Example Prefilter Cartridge"))
        val created = prefilterQuickAction(SupplyId("s-pf"))
        val line = created.consumables.first()

        val edited = save.run(
            created.id,
            cmd(
                "Change prefilter",
                eventKind = EventKind.MAINTENANCE,
                consumables = listOf(ProfileConsumableInput(line.id, line.name, line.defaultQuantity, line.unit, supplyId = null)),
            ),
        )

        assertEquals(line.id, edited.consumables.single().id, "the row keeps its identity; only the link went")
        assertNull(edited.consumables.single().supplyId)
        assertNull(profiles.get(created.id)!!.consumables.single().supplyId)
    }

    @Test fun anUnknownSupplyIdIsRefusedAndNothingIsWritten() = runTest {
        supplyItems.upsert(supplyItemOf("s-pf", "Example Prefilter Cartridge"))
        val created = prefilterQuickAction(SupplyId("s-pf"))
        val commits = uow.commits

        // A new quick action: every problem collected, each by its row; a blank name and a dangling link are two.
        assertEquals(
            listOf(ProfileProblem.BadConsumable(0), ProfileProblem.UnknownSupplyItem(1)),
            problemsOf {
                save.run(
                    null,
                    cmd(
                        "Change membrane",
                        consumables = listOf(
                            ProfileConsumableInput(null, " ", null, "ea", supplyId = null),
                            ProfileConsumableInput(null, "Membrane", 1.0, "ea", supplyId = SupplyId("s-gone")),
                        ),
                    ),
                )
            },
        )
        // An edit: refused before any write, the stored quick action byte-equal.
        assertEquals(
            listOf(ProfileProblem.UnknownSupplyItem(0)),
            problemsOf {
                save.run(
                    created.id,
                    cmd(
                        "Change prefilter",
                        consumables = listOf(
                            ProfileConsumableInput(created.consumables.first().id, "Prefilter cartridge", 1.0, "ea", supplyId = SupplyId("s-gone")),
                        ),
                    ),
                )
            },
        )
        assertEquals(commits, uow.commits, "nothing written")
        assertEquals(listOf(created), profiles.forAsset(a1))
    }

    @Test fun anArchivedItemMayBeLinked() = runTest {
        // R15-6: an archived SupplyItem is left out of every picker, but a line may still name it — an edit
        // re-sends the links it loaded.
        supplyItems.upsert(supplyItemOf("s-old", "Example Prefilter Cartridge", archivedAt = 3_000L))

        val saved = prefilterQuickAction(SupplyId("s-old"))

        assertEquals(SupplyId("s-old"), saved.consumables.first().supplyId)
        assertEquals(saved, profiles.get(saved.id))
    }
}
