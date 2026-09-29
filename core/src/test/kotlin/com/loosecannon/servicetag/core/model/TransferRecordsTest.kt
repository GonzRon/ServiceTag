package com.loosecannon.servicetag.core.model

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * #77 (C6; R77-12) — the record rules, pure. An OUT(q) of an asset is **open** unless an IN of that asset
 * here lists q in its **lineage**, or a WITHDRAWN of that asset here names q; an IN(p) is **current** unless
 * an OUT of that asset here lists p in its lineage. `heldIds` is every asset with an open OUT; `lineageFor`
 * is the current IN's lineage and its pack (the latest `at`, then the id, among several), else `[]`.
 * `returnsHere` (R77-13, C15, rm-8): a lineage returns an asset iff it names an OUT of it here that no IN
 * has closed — open, or withdrawn. The pack ids are fictional.
 */
class TransferRecordsTest {

    private fun out(id: String, pack: String, lineage: List<String> = emptyList(), asset: String = "a1", at: Long = 10) =
        record(id, asset, TransferKind.OUT, pack, lineage, at)

    private fun into(id: String, pack: String, lineage: List<String> = emptyList(), asset: String = "a1", at: Long = 20) =
        record(id, asset, TransferKind.IN, pack, lineage, at)

    private fun withdrawn(id: String, pack: String, asset: String = "a1", at: Long = 30) =
        record(id, asset, TransferKind.WITHDRAWN, pack, emptyList(), at)

    private fun record(id: String, asset: String, kind: TransferKind, pack: String, lineage: List<String>, at: Long) =
        TransferRecord(id, AssetId(asset), kind, pack, lineage, at, "ab".repeat(32), "Example Water Heater", "")

    private val a1 = AssetId("a1")
    private val a2 = AssetId("a2")

    @Test
    fun aLoneOutIsHeldAndTheOriginHasNoLineage() {
        val records = listOf(out("r1", "q"))

        assertEquals(setOf(a1), heldIds(records))
        assertEquals(emptyList(), lineageFor(records, a1), "an asset that never arrived here starts here")
        assertEquals(emptySet(), heldIds(emptyList()))
    }

    /** A return: the IN's lineage names the OUT's pack, so the OUT closes and the asset is here again. */
    @Test
    fun anInListingTheOutsPackInItsLineageClosesIt() {
        val records = listOf(out("r1", "q"), into("r2", "p", lineage = listOf("q", "r")))

        assertEquals(emptySet(), heldIds(records))
        assertEquals(listOf("q", "r", "p"), lineageFor(records, a1))
    }

    /**
     * R77-B2a-MJ1: the recipient's IN(q) means "q arrived here"; where it meets the sender's OUT(q) — a merge
     * between the two ends of the transfer — the arrival cancels the departure for custody. A different pack's
     * IN closes nothing.
     */
    @Test
    fun anInOfTheSamePackClosesTheOut() {
        assertEquals(emptySet(), heldIds(listOf(out("r1", "q"), into("r2", "q"))))
        assertEquals(setOf(a1), heldIds(listOf(out("r1", "q"), into("r2", "p"))))
        assertEquals(setOf(AssetId("a2")), heldIds(listOf(out("r1", "q", asset = "a2"), into("r2", "q"))), "per asset")
    }

    @Test
    fun aWithdrawalClosesItsOutAndOnlyItsOut() {
        assertEquals(emptySet(), heldIds(listOf(out("r1", "q"), withdrawn("r2", "q"))))
        assertEquals(setOf(a1), heldIds(listOf(out("r1", "q"), withdrawn("r2", "other"))))
    }

    /** One pack carries several assets: a record closes only its own asset's OUT. */
    @Test
    fun closingIsPerAsset() {
        val records = listOf(
            out("r1", "q"), out("r2", "q", asset = "a2"),
            into("r3", "p", lineage = listOf("q")), withdrawn("r4", "q", asset = "a3"),
        )

        assertEquals(setOf(a2), heldIds(records))
    }

    /** Arrived here in q, left again in r (whose lineage lists q): q is no longer current, nothing is. */
    @Test
    fun anOutListingTheInsPackEndsItsCurrency() {
        val arrived = listOf(into("r1", "q", lineage = listOf("o")))
        assertEquals(listOf("o", "q"), lineageFor(arrived, a1))

        val leftAgain = arrived + out("r2", "r", lineage = listOf("o", "q"))
        assertEquals(emptyList(), lineageFor(leftAgain, a1))
        assertEquals(setOf(a1), heldIds(leftAgain))
    }

    /** X → Y (q), Y → Z (r), Z → X (p, lineage [q, r]), then X → W (s, lineage [q, r, p]). */
    @Test
    fun aMultiHopChain() {
        val back = listOf(out("r1", "q"), into("r2", "p", lineage = listOf("q", "r")))
        assertEquals(emptySet(), heldIds(back))
        assertEquals(listOf("q", "r", "p"), lineageFor(back, a1))

        val again = back + out("r3", "s", lineage = listOf("q", "r", "p"))
        assertEquals(setOf(a1), heldIds(again))
        assertEquals(emptyList(), lineageFor(again, a1))
    }

    /** rm-12: two current INs (two installs imported different packs) — the latest `at`, then the id. */
    @Test
    fun twoCurrentInsTakeTheLatestThenTheLargestId() {
        val older = into("r1", "p1", lineage = listOf("x"), at = 20)
        val newer = into("r2", "p2", lineage = listOf("y"), at = 30)
        assertEquals(listOf("y", "p2"), lineageFor(listOf(newer, older), a1))

        val tieA = into("r3", "p3", at = 40)
        val tieB = into("r4", "p4", at = 40)
        assertEquals(listOf("p4"), lineageFor(listOf(tieB, tieA, older), a1))
    }

    /** R77-13 and C15 (rm-8): open or withdrawn OUTs can be returned; one an IN already closed cannot. */
    @Test
    fun aLineageReturnsAnOutNoInHasClosed() {
        val open = listOf(out("r1", "q"))
        assertTrue(returnsHere(open, a1, listOf("q", "r")))
        assertFalse(returnsHere(open, a1, listOf("r")), "a foreign pack")
        assertFalse(returnsHere(open, a2, listOf("q")), "another asset's lineage")

        val withdrawnOut = listOf(out("r1", "q"), withdrawn("r2", "q"))
        assertTrue(returnsHere(withdrawnOut, a1, listOf("q")), "rm-8: a mistaken withdrawal never strands a return")

        val closedThenLeft = listOf(out("r1", "q"), into("r2", "p", listOf("q")), out("r3", "s", listOf("q", "p")))
        assertFalse(returnsHere(closedThenLeft, a1, listOf("q")), "a stale lineage naming an OUT an IN closed")
        assertTrue(returnsHere(closedThenLeft, a1, listOf("q", "p", "s")))
    }

    @Test
    fun theShortPackIdIsTheFileNamesEightCharacters() {
        assertEquals("0f1e2d3c", shortPackId("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"))
        assertEquals("abc", shortPackId("abc"))
    }
}
