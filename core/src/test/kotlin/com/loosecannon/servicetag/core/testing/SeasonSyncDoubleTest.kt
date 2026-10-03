package com.loosecannon.servicetag.core.testing

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.seasonsync.HaReadOutcome.NoDecision
import com.loosecannon.servicetag.core.seasonsync.HaReadOutcome.Observed
import com.loosecannon.servicetag.core.seasonsync.HaSwitchState
import com.loosecannon.servicetag.core.seasonsync.Secret
import com.loosecannon.servicetag.core.seasonsync.SyncErrorKind
import com.loosecannon.servicetag.core.seasonsync.SyncMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #16 (C7; row 9, N-10) — the core doubles behave as the schema does: an asset delete or wipe through [BackupInstall]
 * takes the asset's binding, a connection delete takes its bindings, the update is a compare-and-set on the revision,
 * and a throw inside a write restores both tables. Every name is fictional.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SeasonSyncDoubleTest {

    private val install = BackupInstall()
    private val bindings = install.seasonSyncBindings
    private val heater = AssetId("h1")
    private val cooler = AssetId("x1")

    private suspend fun seed() {
        install.assets.upsert(plainAssetOf("h1", "Example Heater"))
        install.assets.upsert(plainAssetOf("x1", "Example Cooler"))
        install.haConnections.upsert(haConnectionOf())
        bindings.insert(seasonSyncBindingOf("h1"))
        bindings.insert(seasonSyncBindingOf("x1", entityId = "input_boolean.example_cooler_in_season"))
    }

    @Test
    fun anAssetDeleteThroughBackupInstallTakesItsBinding() = runTest {
        seed()

        install.assets.delete(heater)

        assertNull(bindings.get(heater), "the heater's binding went with the heater")
        assertNull(bindings.observeFor(heater).first())
        assertEquals(listOf(cooler), bindings.all().map { it.assetId }, "the cooler keeps its own")

        install.assets.deleteAll()

        assertEquals(emptyList(), bindings.all(), "the wipe takes the last binding")
        assertFalse(bindings.anyEnabled())
        assertEquals("conn-1", install.haConnections.get()?.id, "an asset delete leaves the connection")
    }

    @Test
    fun aConnectionDeleteTakesItsBindings() = runTest {
        seed()
        bindings.insert(seasonSyncBindingOf("z1", connectionId = "conn-other"))

        install.haConnections.delete("conn-1")

        assertNull(install.haConnections.get())
        assertEquals(listOf(AssetId("z1")), bindings.all().map { it.assetId }, "only conn-1's bindings went")
        assertEquals(2, install.assets.all().size, "the assets stay")
    }

    @Test
    fun aStaleRevisionUpdateAnswersFalse() = runTest {
        seed()
        val linked = bindings.get(heater)!!

        assertTrue(bindings.update(linked.copy(mode = SyncMode.FORCE_IN, revision = 2)), "1 → 2 writes")
        assertFalse(bindings.update(linked.copy(mode = SyncMode.FORCE_OUT, revision = 2)), "a second 1 → 2 is stale")
        assertFalse(bindings.update(linked.copy(mode = SyncMode.FORCE_OUT, revision = 4)), "a skipped revision")
        assertFalse(bindings.update(seasonSyncBindingOf("nobody", revision = 2)), "no row")
        assertEquals(SyncMode.FORCE_IN to 2L, bindings.get(heater)!!.let { it.mode to it.revision })
        assertEquals(3, bindings.refusedUpdates)

        assertFailsWith<RiggedFailure>("one binding per asset") { bindings.insert(seasonSyncBindingOf("h1")) }
    }

    @Test
    fun aThrowInsideAWriteRestoresBindingsAndConnection() = runTest {
        seed()

        assertFailsWith<IllegalStateException> {
            install.uow.write {
                check(bindings.update(bindings.get(heater)!!.copy(enabled = false, revision = 2)))
                install.haConnections.upsert(haConnectionOf(baseUrl = "https://ha.example:8123"))
                install.haConnections.delete("conn-1")
                error("a later step failed")
            }
        }

        assertEquals(1, install.uow.rollbacks)
        assertEquals(true to 1L, bindings.get(heater)!!.let { it.enabled to it.revision }, "the binding as it was")
        assertEquals(2, bindings.all().size, "the cascade is undone too")
        assertEquals("http://192.168.0.10:8123", install.haConnections.get()?.baseUrl, "the connection as it was")
    }

    @Test
    fun theScriptedReaderRecordsEachCallAndCanHoldOne() = runTest {
        val reader = ScriptedHaStateReader()
        val gate = CompletableDeferred<Unit>()
        val token = Secret("fictional-token-1")
        reader.answer(Observed(HaSwitchState.ON, null))
        reader.answerWhen(gate, NoDecision(SyncErrorKind.UNREACHABLE, null))

        assertEquals(Observed(HaSwitchState.ON, null), reader.read("http://192.168.0.10:8123", "input_boolean.a", token))
        val held = async { reader.read("http://192.168.0.10:8123", "input_boolean.b", token) }
        runCurrent()

        assertEquals(listOf("input_boolean.a", "input_boolean.b"), reader.calls.map { it.entityId })
        assertEquals(token, reader.calls.last().token)
        assertFalse(held.isCompleted, "the second read waits for its gate")
        gate.complete(Unit)
        assertEquals(NoDecision(SyncErrorKind.UNREACHABLE, null), held.await())
        assertFailsWith<AssertionError> { reader.read("http://192.168.0.10:8123", "input_boolean.c", token) }
    }

    @Test
    fun theSecretStoreDoubleKeepsKeysAndNeverPrintsAValue() = runTest {
        val store = InMemorySecretStore()
        store.put("conn-1", Secret("fictional-token-1"))
        store.put("conn-2", Secret("fictional-token-2"))
        store.delete("conn-2")

        assertEquals(Secret("fictional-token-1"), store.get("conn-1"))
        assertTrue(store.has("conn-1"))
        assertFalse(store.has("conn-2"))
        assertNull(store.get("conn-2"))
        assertEquals(setOf("conn-1"), store.keys())
        assertFalse(store.toString().contains("fictional-token"), store.toString())
    }
}
