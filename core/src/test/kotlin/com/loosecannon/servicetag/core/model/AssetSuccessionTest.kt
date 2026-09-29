package com.loosecannon.servicetag.core.model

import com.loosecannon.servicetag.core.testing.BackupInstall
import com.loosecannon.servicetag.core.testing.plainAssetOf
import com.loosecannon.servicetag.core.testing.successionOf
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * #86 (B1, row 1; C1, C2; R86-1, R86-15) — [successionProblems], the one home of I1–I4, and the core fake's CASCADE.
 * A self-link is refused; a chain is rows and no problem; a second row naming a taken predecessor or successor names
 * the earlier holder; every row on a cycle of two or of three is named, sorted; deleting either endpoint removes the
 * row, as the schema's two CASCADE keys do. The names are fictional.
 */
class AssetSuccessionTest {

    @Test
    fun aSelfLinkIsRefused() {
        assertEquals(
            listOf<SuccessionProblem>(SuccessionProblem.SelfLink("s1")),
            successionProblems(listOf(successionOf("s1", predecessor = "a1", successor = "a1"))),
        )
    }

    /** I3: A → B → C is two rows, each with its own date, and no problem. */
    @Test
    fun aChainOfThreeIsValid() {
        val chain = listOf(
            successionOf("s1", predecessor = "a1", successor = "a2", replacedOn = "2025-04-01"),
            successionOf("s2", predecessor = "a2", successor = "a3", replacedOn = "2026-09-20"),
        )
        assertEquals(emptyList(), successionProblems(chain))
        assertEquals(emptyList(), successionProblems(chain.reversed()), "whatever the order")
    }

    @Test
    fun aSecondSuccessorOfOnePredecessorIsTaken() {
        assertEquals(
            listOf<SuccessionProblem>(SuccessionProblem.PredecessorTaken("s2", holder = "s1")),
            successionProblems(
                listOf(
                    successionOf("s1", predecessor = "a1", successor = "a2"),
                    successionOf("s2", predecessor = "a1", successor = "a3"),
                ),
            ),
        )
    }

    @Test
    fun aSecondPredecessorOfOneSuccessorIsTaken() {
        assertEquals(
            listOf<SuccessionProblem>(SuccessionProblem.SuccessorTaken("s2", holder = "s1")),
            successionProblems(
                listOf(
                    successionOf("s1", predecessor = "a1", successor = "a3"),
                    successionOf("s2", predecessor = "a2", successor = "a3"),
                ),
            ),
        )
    }

    /** I4: each cycle once, naming every row on it, by id — whatever order the rows come in. */
    @Test
    fun cyclesOfTwoAndThreeAreRefusedNamingEveryRow() {
        val two = listOf(
            successionOf("s2", predecessor = "a2", successor = "a1"),
            successionOf("s1", predecessor = "a1", successor = "a2"),
        )
        assertEquals(listOf<SuccessionProblem>(SuccessionProblem.Cycle(listOf("s1", "s2"))), successionProblems(two))

        val three = listOf(
            successionOf("t3", predecessor = "b3", successor = "b1"),
            successionOf("t1", predecessor = "b1", successor = "b2"),
            successionOf("t2", predecessor = "b2", successor = "b3"),
        )
        assertEquals(
            listOf<SuccessionProblem>(SuccessionProblem.Cycle(listOf("t1", "t2", "t3"))),
            successionProblems(three),
        )

        // An unrelated row is on no cycle; both cycles are named, by their first id.
        val both = three + two + successionOf("u1", predecessor = "c1", successor = "c2")
        assertEquals(
            listOf<SuccessionProblem>(
                SuccessionProblem.Cycle(listOf("s1", "s2")),
                SuccessionProblem.Cycle(listOf("t1", "t2", "t3")),
            ),
            successionProblems(both),
        )
    }

    /** C2: the core fake mirrors the schema's CASCADE at either end, as `BackupInstall` wires it. */
    @Test
    fun theFakeCascadesLikeRoom() = runTest {
        val install = BackupInstall()
        listOf("a1", "a2", "a3").forEach { install.assets.upsert(plainAssetOf(it, "Example Water Heater $it")) }
        val first = successionOf("s1", predecessor = "a1", successor = "a2")
        val second = successionOf("s2", predecessor = "a2", successor = "a3")
        install.successions.append(first)
        install.successions.append(second)
        assertEquals(first, install.successions.replacedBy(AssetId("a1")))
        assertEquals(second, install.successions.replaces(AssetId("a3")))

        install.assets.delete(AssetId("a1"))
        assertEquals(listOf(second), install.successions.all(), "the predecessor's delete takes its row")

        install.assets.delete(AssetId("a3"))
        assertEquals(emptyList(), install.successions.all(), "and so does the successor's")
        assertEquals(listOf("a2"), install.assets.all().map { it.id.value }, "nothing re-links around it")
    }
}
