package com.loosecannon.servicetag.core.model

import com.loosecannon.servicetag.core.testing.installedComponentOf
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.function.ThrowingSupplier
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * #47 (B1a; rows 1–3): the typed tree over one Asset's installed components — parents first, the current
 * view in sibling order, and the history a replacement leaves behind. The names are fictional.
 */
class InstalledComponentTreeTest {

    private fun ids(rows: List<InstalledComponent>) = rows.map { it.id.value }
    private fun placed(view: List<InstalledComponentTree.Indented>) = view.map { it.row.id.value to it.depth }

    // --- row 1: parents first ---------------------------------------------------------------------------------

    @Test fun aShuffledThreeDeepTreeComesParentsFirst() {
        val tray = installedComponentOf("c1", name = "Example Battery Tray")
        val position = installedComponentOf("c2", name = "Position 1", parentId = "c1")
        val cover = installedComponentOf("c3", name = "Terminal cover", parentId = "c2")
        val fan = installedComponentOf("c4", name = "Example Fan")

        val ordered = InstalledComponentTree.parentsFirst(listOf(cover, fan, position, tray))

        // Roots c1 and c4 are ready; c1 comes out first, then c2 ("c2" < "c4"), then c3 ("c3" < "c4"), then c4.
        assertEquals(listOf("c1", "c2", "c3", "c4"), ids(ordered))
    }

    @Test fun aParentOutsideTheListIsARoot() {
        val root = installedComponentOf("c1")
        val orphan = installedComponentOf("c2", parentId = "not-in-this-list")
        val child = installedComponentOf("c3", parentId = "c2")

        assertEquals(listOf("c1", "c2", "c3"), ids(InstalledComponentTree.parentsFirst(listOf(child, orphan, root))))
    }

    @Test fun aCycleThrows() {
        val one = installedComponentOf("c1", parentId = "c2")
        val two = installedComponentOf("c2", parentId = "c1")
        val root = installedComponentOf("c3")
        assertFailsWith<IllegalStateException> { InstalledComponentTree.parentsFirst(listOf(root, one, two)) }

        val itsOwnParent = installedComponentOf("c4", parentId = "c4")
        assertFailsWith<IllegalStateException> { InstalledComponentTree.parentsFirst(listOf(itsOwnParent)) }
    }

    @Test fun readyRowsTieByIdNotBySiblingOrder() {
        val first = installedComponentOf("c1", name = "Zeta", sortOrder = 9)
        val second = installedComponentOf("c2", name = "Alpha", sortOrder = 0)
        val under = installedComponentOf("c0", name = "Alpha", sortOrder = 0, parentId = "c2")

        assertEquals(listOf("c1", "c2", "c0"), ids(InstalledComponentTree.parentsFirst(listOf(second, under, first))))
    }

    @Test fun descendantsTakeTheWholeSubtreeAndNothingElse() {
        val rows = listOf(
            installedComponentOf("c1"),
            installedComponentOf("c2", parentId = "c1"),
            installedComponentOf("c3", parentId = "c2", removedOn = "2026-09-01"),
            installedComponentOf("c4", parentId = "c3"),
            installedComponentOf("c5"),
            installedComponentOf("c6", parentId = "c5"),
        )

        assertEquals(setOf("c2", "c3", "c4"), InstalledComponentTree.descendants(rows, InstalledComponentId("c1")).map { it.value }.toSet())
        assertEquals(emptySet(), InstalledComponentTree.descendants(rows, InstalledComponentId("c4")))
    }

    // --- row 2: the current view and the sibling order ----------------------------------------------------------

    @Test fun currentDropsRemovedRowsAndAnnotatesDepth() {
        val rows = listOf(
            installedComponentOf("c4", name = "Cell", parentId = "c2"),
            installedComponentOf("c6", name = "Example Fan", sortOrder = 1),
            installedComponentOf("c3", name = "Position 2", parentId = "c1", sortOrder = 1, removedOn = "2026-09-01"),
            installedComponentOf("c1", name = "Example Battery Tray", sortOrder = 0),
            installedComponentOf("c5", name = "Old Fan", sortOrder = 2, removedOn = "2026-08-01"),
            installedComponentOf("c2", name = "Position 1", parentId = "c1", sortOrder = 0),
        )

        assertEquals(
            listOf("c1" to 0, "c2" to 1, "c4" to 2, "c6" to 0),
            placed(InstalledComponentTree.current(rows)),
        )
    }

    @Test fun siblingsBySortOrderThenNameThenId() {
        val rows = listOf(
            installedComponentOf("c1", name = "Example Battery Tray"),
            installedComponentOf("c2", name = "Position 10", parentId = "c1", sortOrder = 2),
            installedComponentOf("c3", name = "Position 2", parentId = "c1", sortOrder = 1),
            installedComponentOf("c4", name = "b side", parentId = "c1", sortOrder = 3),
            installedComponentOf("c5", name = "A side", parentId = "c1", sortOrder = 3),
            installedComponentOf("c8", name = "Spare", parentId = "c1", sortOrder = 4),
            installedComponentOf("c7", name = "spare", parentId = "c1", sortOrder = 4),
        )

        // "Position 10" after "Position 2" by sortOrder; "A side" before "b side" case-insensitively; "Spare" and
        // "spare" fold to one name, so the id decides.
        assertEquals(
            listOf("c1" to 0, "c3" to 1, "c2" to 1, "c5" to 1, "c4" to 1, "c7" to 1, "c8" to 1),
            placed(InstalledComponentTree.current(rows)),
        )
        assertEquals(
            listOf("c3", "c2", "c5", "c4", "c7", "c8"),
            ids(rows.filter { it.parentId != null }.shuffled(java.util.Random(47)).sortedWith(InstalledComponentTree.siblingOrder)),
        )
    }

    @Test fun aCurrentRowWhoseParentIsNotCurrentIsARootOfTheView() {
        val rows = listOf(
            installedComponentOf("c1", name = "Example Battery Tray", removedOn = "2026-09-01"),
            installedComponentOf("c2", name = "Position 1", parentId = "c1"),
            installedComponentOf("c3", name = "Position 2", parentId = "not-in-this-list", sortOrder = 1),
        )

        assertEquals(listOf("c2" to 0, "c3" to 0), placed(InstalledComponentTree.current(rows)))
    }

    // --- row 3: history --------------------------------------------------------------------------------------------

    private val first = installedComponentOf("h1", name = "Position 3", installedOn = "2024-05-01", removedOn = "2025-06-01")
    private val second = installedComponentOf("h2", name = "Position 3", installedOn = "2025-06-01", removedOn = "2026-09-01", replacesId = "h1")
    private val third = installedComponentOf("h3", name = "Position 3", installedOn = "2026-09-01", replacesId = "h2")
    private val unrelated = installedComponentOf("h0", name = "Position 4", installedOn = "2024-05-01")

    @Test fun historyFollowsReplacesIdBackNewestFirst() {
        val rows = listOf(first, unrelated, third, second)

        assertEquals(listOf("h3", "h2", "h1"), ids(InstalledComponentTree.history(rows, InstalledComponentId("h3"))))
        assertEquals(listOf("h2", "h1"), ids(InstalledComponentTree.history(rows, InstalledComponentId("h2"))))
        assertEquals(listOf("h0"), ids(InstalledComponentTree.history(rows, InstalledComponentId("h0"))))
        assertEquals(emptyList(), InstalledComponentTree.history(rows, InstalledComponentId("not-in-this-list")))
    }

    @Test fun successorOfFindsTheRowNamingIt() {
        val rows = listOf(first, second, third, unrelated)

        assertEquals("h2", InstalledComponentTree.successorOf(rows, InstalledComponentId("h1"))?.id?.value)
        assertEquals("h3", InstalledComponentTree.successorOf(rows, InstalledComponentId("h2"))?.id?.value)
        assertNull(InstalledComponentTree.successorOf(rows, InstalledComponentId("h3")))
        assertNull(InstalledComponentTree.successorOf(rows, InstalledComponentId("h0")))
    }

    @Test fun removedUnreplacedListsOnlyRowsWithNoSuccessor() {
        val rows = listOf(
            first, second, third, unrelated,
            installedComponentOf("r1", name = "b fan", removedOn = "2026-01-05"),
            installedComponentOf("r2", name = "Z fan", removedOn = "2026-03-01"),
            installedComponentOf("r4", name = "a fan", removedOn = "2026-01-05"),
            installedComponentOf("r3", name = "A fan", removedOn = "2026-01-05"),
        )

        // h1 and h2 are replaced, h3 and h0 are current. The newest removal first, then the name folded, then the id.
        assertEquals(listOf("r2", "r3", "r4", "r1"), ids(InstalledComponentTree.removedUnreplaced(rows)))
    }

    @Test fun aReplacesIdCycleTerminates() {
        val one = installedComponentOf("h1", removedOn = "2026-01-01", replacesId = "h2")
        val two = installedComponentOf("h2", removedOn = "2026-02-01", replacesId = "h1")
        val itself = installedComponentOf("h3", removedOn = "2026-03-01", replacesId = "h3")

        val walked = assertTimeoutPreemptively(
            Duration.ofSeconds(2),
            ThrowingSupplier { InstalledComponentTree.history(listOf(one, two), InstalledComponentId("h1")) },
        )
        assertEquals(listOf("h1", "h2"), ids(walked))

        val alone = assertTimeoutPreemptively(
            Duration.ofSeconds(2),
            ThrowingSupplier { InstalledComponentTree.history(listOf(itself), InstalledComponentId("h3")) },
        )
        assertEquals(listOf("h3"), ids(alone))
    }
}
