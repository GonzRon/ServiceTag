package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.testing.InMemoryAssetSupplyRepository
import com.loosecannon.servicetag.core.testing.InMemoryProfileRepository
import com.loosecannon.servicetag.core.testing.InMemorySupplyItemRepository
import com.loosecannon.servicetag.core.testing.RecordingUnitOfWork
import com.loosecannon.servicetag.core.testing.SupplyEstate
import com.loosecannon.servicetag.core.testing.assetSupplyOf
import com.loosecannon.servicetag.core.testing.specificationOf
import com.loosecannon.servicetag.core.testing.supplyItemOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #15 (C15, C16; R15-5, R15-11) — the SupplyItem's save and archive. Every problem is collected before the one write,
 * every text is trimmed, a specification's key is the definition slug kept across label edits (`"spec"` when the label
 * slugs to nothing), a child id is kept only when this item owns it, and an edit that changes nothing writes nothing.
 * There is no delete. Fictional throughout ("Example Filters Co.").
 */
class SupplyItemUseCasesTest {

    private val applicability = InMemoryAssetSupplyRepository()
    private val items = InMemorySupplyItemRepository(applicability)
    private val profiles = InMemoryProfileRepository()
    private val uow = RecordingUnitOfWork(items, applicability, profiles)
    private var minted = 0
    private val ids = IdGenerator { "id-%02d".format(++minted) }
    private var now = 9_000L
    private val clock = Clock { now }
    private val save = SaveSupplyItem(items, uow, ids, clock)
    private val archive = ArchiveSupplyItem(items, uow, clock)

    private fun spec(label: String, value: String, unit: String = "", key: String = "", id: String? = null) =
        SpecificationInput(id = id, key = key, label = label, value = value, unit = unit)

    private fun command(
        name: String = "Example Prefilter Cartridge",
        specifications: List<SpecificationInput> = emptyList(),
        category: String = "Filter",
        manufacturer: String = "Example Filters Co.",
        model: String = "PF-10",
        partNumber: String = "EF-PF10-5",
        preferredUnit: String = "ea",
        notes: String = "",
    ) = SupplyItemCommand(name, category, manufacturer, model, partNumber, preferredUnit, notes, specifications)

    /** The stored s1, as an earlier save left it: two specifications, keys `length` and `micron_rating`. */
    private val stored = supplyItemOf(
        "s1",
        specifications = listOf(
            specificationOf("sp1", "length", "Length", "10", "in", sortOrder = 0),
            specificationOf("sp2", "micron_rating", "Micron rating", "5", "µm", sortOrder = 1),
        ),
    )

    /** [stored]'s own command: what an editor or a no-op PATCH sends back. */
    private fun storedCommand(specifications: List<SpecificationInput> = storedSpecs()) = command(specifications = specifications)
    private fun storedSpecs() = listOf(
        spec("Length", "10", "in", key = "length", id = "sp1"),
        spec("Micron rating", "5", "µm", key = "micron_rating", id = "sp2"),
    )

    private suspend fun problemsOf(id: SupplyId?, cmd: SupplyItemCommand): List<SupplyItemProblem> =
        assertFailsWith<SupplyItemValidation> { save.run(id, cmd) }.problems

    // ---- row 31: create, and the collected problems ---------------------------------------------------------------

    @Test
    fun createStoresTrimmedFieldsAndOrderedSpecs() = runTest {
        val result = save.run(
            null,
            SupplyItemCommand(
                name = "  Example Prefilter Cartridge ", category = " Filter ", manufacturer = " Example Filters Co. ",
                model = " PF-10 ", partNumber = " EF-PF10-5 ", preferredUnit = " ea ", notes = "  keep dry  ",
                specifications = listOf(
                    spec(" Length ", " 10 ", " in "),
                    spec(" Micron rating", "5 ", ""),
                    spec("Thread", "1/4 NPT", "  "),
                ),
            ),
        )

        assertFalse(result.unchanged)
        val item = result.item
        assertEquals(SupplyId("id-01"), item.id, "the item's id is minted after validation, first")
        assertEquals(
            listOf("Example Prefilter Cartridge", "Filter", "Example Filters Co.", "PF-10", "EF-PF10-5", "ea", "keep dry"),
            listOf(item.name, item.category, item.manufacturer, item.model, item.partNumber, item.preferredUnit, item.notes),
        )
        assertNull(item.archivedAt)
        assertEquals(9_000L, item.createdAt)
        assertEquals(9_000L, item.updatedAt)
        assertEquals(
            listOf(
                specificationOf("id-02", "length", "Length", "10", "in", sortOrder = 0),
                specificationOf("id-03", "micron_rating", "Micron rating", "5", "", sortOrder = 1),
                specificationOf("id-04", "thread", "Thread", "1/4 NPT", "", sortOrder = 2),
            ),
            item.specifications,
            "every text trimmed, the rows in command order, sortOrder the index",
        )
        assertEquals(item, items.get(item.id), "stored as answered")
        assertEquals(1, uow.writesEntered)
    }

    @Test
    fun aBlankNameIsRefusedAndNothingIsWritten() = runTest {
        for (name in listOf("", "   ", "\t\n")) {
            assertEquals(listOf(SupplyItemProblem.NameRequired), problemsOf(null, command(name = name)))
        }
        assertEquals(0, uow.writesEntered, "no transaction")
        assertEquals(0, minted, "no id minted")
        assertTrue(items.rows.isEmpty())
    }

    @Test
    fun everyRowProblemIsCollectedInOneCall() = runTest {
        val problems = problemsOf(
            null,
            command(
                name = " ",
                specifications = listOf(
                    spec("Length", "10", "in"),
                    spec(" ", "5"),
                    spec("Thread", "  "),
                    spec("Capacity", "75", key = "Capacity"),
                    spec("Rating", "5", key = "rating"),
                    spec("Grade", "A", key = "rating"),
                ),
            ),
        )

        assertEquals(
            listOf(
                SupplyItemProblem.NameRequired,
                SupplyItemProblem.SpecLabelRequired(1),
                SupplyItemProblem.SpecValueRequired(2),
                SupplyItemProblem.SpecKeyInvalid(3),
                SupplyItemProblem.SpecKeyTaken(4),
                SupplyItemProblem.SpecKeyTaken(5),
            ),
            problems,
            "every problem, in row order, from one call",
        )
        assertEquals(0, uow.writesEntered)
        assertEquals(0, minted)
    }

    // ---- row 32: the key rule (R15-11) ----------------------------------------------------------------------------

    @Test
    fun aBlankKeyIsTheLabelsSlug() = runTest {
        val item = save.run(null, command(specifications = listOf(spec("Micron rating (nominal)", "5"), spec("2nd seal", "O-ring", key = "  ")))).item

        assertEquals(listOf("micron_rating_nominal", "k_2nd_seal"), item.specifications.map { it.key }, "slugify, the one key rule")
    }

    @Test
    fun twoLabelsWithOneSlugAreDeduped() = runTest {
        val item = save.run(
            null,
            command(specifications = listOf(spec("Length", "10", "in"), spec("length", "254", "mm"), spec("LENGTH!", "0.25", "m"))),
        ).item

        assertEquals(listOf("length", "length_2", "length_3"), item.specifications.map { it.key })
    }

    @Test
    fun anUnsluggableLabelGetsSpec() = runTest {
        // "Ø" and "±" slug to nothing, so each is "spec", deduped in row order; "Spec" slugs to "spec" and steps past both.
        val item = save.run(null, command(specifications = listOf(spec("Ø", "63", "mm"), spec("±", "0.5", "mm"), spec("Spec", "x")))).item

        assertEquals(listOf("spec", "spec_2", "spec_3"), item.specifications.map { it.key })
        assertEquals(listOf("Ø", "±", "Spec"), item.specifications.map { it.label }, "the label is kept as typed")
    }

    @Test
    fun aTypedKeyOutsideThePatternIsRefused() = runTest {
        for (bad in listOf("Length", "1length", "_length", "length-mm", "l".repeat(41))) {
            assertEquals(
                listOf(SupplyItemProblem.SpecKeyInvalid(0)),
                problemsOf(null, command(specifications = listOf(spec("Length", "10", key = bad)))),
                bad,
            )
        }
        val item = save.run(null, command(specifications = listOf(spec("Length", "10", key = " length_mm ")))).item
        assertEquals("length_mm", item.specifications.single().key, "a typed key is trimmed, then matched")
    }

    @Test
    fun aTypedKeyTakenIsRefused() = runTest {
        items.upsert(stored)

        assertEquals(
            listOf(SupplyItemProblem.SpecKeyTaken(2)),
            problemsOf(SupplyId("s1"), storedCommand(storedSpecs() + spec("Width", "4", key = "length"))),
            "a typed key a kept row holds",
        )
        assertEquals(
            listOf(SupplyItemProblem.SpecKeyTaken(0), SupplyItemProblem.SpecKeyTaken(1)),
            problemsOf(null, command(specifications = listOf(spec("Length", "10", key = "size"), spec("Width", "4", key = "size")))),
            "two typed rows holding one key",
        )
        val typedBeatsDerived = save.run(null, command(specifications = listOf(spec("Length", "10"), spec("Width", "4", key = "length")))).item
        assertEquals(listOf("length_2", "length"), typedBeatsDerived.specifications.map { it.key }, "a derived key steps around a typed one")
        assertEquals(stored, items.get(SupplyId("s1")), "the refused edits wrote nothing")
    }

    @Test
    fun aKeptRowKeepsItsKeyWhenItsLabelChanges() = runTest {
        items.upsert(stored)

        val item = save.run(
            SupplyId("s1"),
            storedCommand(
                listOf(
                    spec("Overall length", "10", "in", key = "", id = "sp1"),
                    spec("Micron rating", "5", "µm", key = "micron_rating", id = "sp2"),
                    spec("Length", "9", "in"),
                ),
            ),
        ).item

        assertEquals(listOf("length", "micron_rating", "length_2"), item.specifications.map { it.key })
        assertEquals("Overall length", item.specifications[0].label)
        val reKeyed = save.run(SupplyId("s1"), storedCommand(listOf(spec("Overall length", "10", "in", key = "overall_length", id = "sp1")))).item
        assertEquals(listOf("sp1" to "overall_length"), reKeyed.specifications.map { it.id to it.key }, "only a typed key re-keys a kept row")
    }

    // ---- row 33: the child ids ------------------------------------------------------------------------------------

    @Test
    fun aKeptRowKeepsItsId() = runTest {
        items.upsert(stored)

        val item = save.run(
            SupplyId("s1"),
            storedCommand(listOf(spec("Micron rating", "1", "µm", id = "sp2"), spec("Length", "10", "in", id = "sp1"))),
        ).item

        assertEquals(listOf("sp2" to 0, "sp1" to 1), item.specifications.map { it.id to it.sortOrder }, "kept, moved, sortOrder the index")
        assertEquals(listOf("micron_rating", "length"), item.specifications.map { it.key })
        assertEquals(0, minted, "nothing minted for kept rows")
    }

    @Test
    fun anIdThisItemDoesNotOwnIsMintedFresh() = runTest {
        items.upsert(stored)
        items.upsert(supplyItemOf("s9", "Example Post-filter", specifications = listOf(specificationOf("sp9", "length", "Length", "6", "in"))))

        val item = save.run(
            SupplyId("s1"),
            storedCommand(
                listOf(
                    spec("Length", "10", "in", id = "sp1"),
                    spec("Width", "4", "in", id = "sp9"),
                    spec("Depth", "2", "in", id = "made-up"),
                    spec("Length again", "10", "in", id = "sp1"),
                ),
            ),
        ).item

        assertEquals(listOf("sp1", "id-01", "id-02", "id-03"), item.specifications.map { it.id }, "another item's id, a made-up one and a repeat are minted")
        assertEquals(listOf("length", "width", "depth", "length_again"), item.specifications.map { it.key }, "a minted row's key is derived")
        assertEquals(listOf("sp9"), items.get(SupplyId("s9"))!!.specifications.map { it.id }, "the other item is untouched")
    }

    // ---- row 34: Unchanged ----------------------------------------------------------------------------------------

    @Test
    fun anEditChangingNothingWritesNothing() = runTest {
        items.upsert(stored)

        val result = save.run(
            SupplyId("s1"),
            SupplyItemCommand(
                " Example Prefilter Cartridge ", "Filter ", "Example Filters Co.", " PF-10", "EF-PF10-5", "ea", "",
                listOf(spec(" Length", "10", "in ", id = "sp1"), spec("Micron rating", " 5", "µm", key = "micron_rating", id = "sp2")),
            ),
        )

        assertTrue(result.unchanged)
        assertEquals(stored, result.item, "the stored item, updatedAt held")
        assertEquals(0, uow.writesEntered, "no transaction")
        assertEquals(0, minted)
        assertEquals(stored, items.get(SupplyId("s1")))

        now = 9_500L
        val notes = save.run(SupplyId("s1"), storedCommand().copy(notes = "keep dry"))
        assertFalse(notes.unchanged)
        assertEquals(9_500L, notes.item.updatedAt, "a real change moves updatedAt")
        assertEquals(stored.createdAt, notes.item.createdAt)
        assertEquals(1, uow.writesEntered)
    }

    @Test
    fun anEditKeepsCreatedAtAndArchivedAt() = runTest {
        items.upsert(stored.copy(archivedAt = 3_000L))

        val item = save.run(SupplyId("s1"), storedCommand().copy(name = "Example Prefilter Cartridge, 10 in")).item

        assertEquals(3_000L, item.archivedAt, "a save neither archives nor unarchives")
        assertEquals(1_000L, item.createdAt)
        assertEquals(9_000L, item.updatedAt)
    }

    @Test
    fun anEditOfAMissingIdIsNoSuchSupplyItem() = runTest {
        assertFailsWith<NoSuchSupplyItem> { save.run(SupplyId("s404"), command()) }
        assertEquals(0, uow.writesEntered)
        assertEquals(0, minted)
    }

    @Test
    fun anEmptyListRemovesEverySpecification() = runTest {
        items.upsert(stored)

        val item = save.run(SupplyId("s1"), storedCommand(emptyList())).item

        assertEquals(emptyList(), item.specifications)
        assertEquals(emptyList(), items.get(SupplyId("s1"))!!.specifications)
    }

    @Test
    fun theSaveInTransactionWritesInsideTheCallersWrite() = runTest {
        val item = uow.write { save.saveInTransaction(null, command()) }.item

        assertEquals(item, items.get(item.id))
        assertEquals(1, uow.writesEntered, "the caller's one transaction, none of its own")
    }

    // ---- row 35: archive (C16) ------------------------------------------------------------------------------------

    @Test
    fun archiveAndUnarchiveWriteOneColumn() = runTest {
        items.upsert(stored)

        now = 9_100L
        val archived = archive.run(SupplyId("s1"), archived = true)
        assertEquals(stored.copy(archivedAt = 9_100L, updatedAt = 9_100L), archived)
        assertEquals(archived, items.get(SupplyId("s1")))

        now = 9_200L
        val back = archive.run(SupplyId("s1"), archived = false)
        assertEquals(stored.copy(archivedAt = null, updatedAt = 9_200L), back, "reversible: everything else as it was")
        assertEquals(back, items.get(SupplyId("s1")))
        assertEquals(2, uow.writesEntered)
        assertFailsWith<NoSuchSupplyItem> { archive.run(SupplyId("s404"), archived = true) }
        assertEquals(2, uow.writesEntered)
    }

    @Test
    fun archivingLeavesApplicabilityAndLinksIntact() = runTest {
        items.upsert(SupplyEstate.prefilter)
        applicability.insert(SupplyEstate.prefilterOnSystem)
        applicability.insert(assetSupplyOf("as9", "x2", "s1", "Spare prefilter"))
        profiles.upsert(SupplyEstate.quickAction)
        val rows = applicability.all()

        archive.run(SupplyId("s1"), archived = true)

        assertEquals(rows, applicability.all(), "every applicability row naming it stays")
        assertEquals(SupplyEstate.quickAction, profiles.rows.getValue("p1"), "the linked line keeps its link")
        assertEquals(SupplyId("s1"), profiles.rows.getValue("p1").consumables.first().supplyId)
        assertTrue(items.get(SupplyId("s1"))!!.archivedAt != null)
    }

    /** R15-5: archive-only, by construction — no use case in this package deletes a SupplyItem. */
    @Test
    fun nothingDeletesASupplyItem() {
        val types: List<Class<out Any>> = listOf(SaveSupplyItem::class.java, ArchiveSupplyItem::class.java)
        types.forEach { type ->
            assertTrue(type.declaredMethods.none { it.name.contains("delete", ignoreCase = true) }, type.simpleName)
        }
        assertTrue(
            runCatching { Class.forName("com.loosecannon.servicetag.core.usecase.DeleteSupplyItem") }.isFailure,
            "there is no DeleteSupplyItem",
        )
    }
}
