package com.loosecannon.servicetag.backup

import com.loosecannon.servicetag.core.backup.BackupCodec
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.seasonsync.BackgroundChecks
import com.loosecannon.servicetag.core.seasonsync.HaConnection
import com.loosecannon.servicetag.core.seasonsync.HaSwitchState
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncBinding
import com.loosecannon.servicetag.core.seasonsync.SyncCadence
import com.loosecannon.servicetag.core.seasonsync.SyncErrorKind
import com.loosecannon.servicetag.core.seasonsync.SyncMode
import com.loosecannon.servicetag.core.usecase.CreateTransferPackResult
import com.loosecannon.servicetag.testing.FakeGraph
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #16 (C9, I11, R16-Q-E; row 20) — the Home Assistant connection and the bindings are device-local: no export, merge,
 * replace restore or Transfer Pack names them, so none carries one, and the format stays 20 with no manifest key and
 * no count for them. A replace restore removes every binding with the assets it wipes (the CASCADE) and keeps the
 * connection; a merge leaves a binding and its revision as they were. Over Room, through the Room-backed graph. The
 * asset, entity, address and Wi-Fi name are fictional.
 */
class SeasonSyncNeverTravelsTest {

    private val graph = FakeGraph()
    private val source = FakeGraph()

    @After fun close() {
        graph.close()
        source.close()
    }

    private val heater = AssetId("asset-heater")
    private val heaterRow = Asset(
        id = heater, name = "Example Heater", seasonMode = SeasonMode.MANUAL, createdAt = 1_000L, updatedAt = 1_000L,
    )

    private val connection = HaConnection(
        id = CONNECTION, baseUrl = "http://192.168.0.10:8123", cadence = SyncCadence.WEEKLY,
        networkEligibility = NetworkEligibility.HOME_NETWORK_ONLY, homeNetworkSsid = "ExampleHomeWifi",
        backgroundChecks = BackgroundChecks.ON, createdAt = 2_000L, updatedAt = 2_000L,
    )

    /** A binding with every optional field set, so any column that leaked would carry a marker. */
    private val binding = SeasonSyncBinding(
        assetId = heater, connectionId = CONNECTION, entityId = ENTITY, mode = SyncMode.FOLLOW, enabled = true,
        revision = 5, observedState = HaSwitchState.ON, observedChangedAt = "2026-10-01T05:00:00+00:00",
        lastSuccessAt = 3_000L, lastAttemptAt = 3_500L, errorKind = SyncErrorKind.UNREACHABLE, errorDetail = "fetch-detail-x",
        errorAt = 3_500L, appliedAction = SeasonAction.START, appliedOn = "2026-10-01", appliedAt = 3_000L,
        createdAt = 2_500L, updatedAt = 3_500L,
    )

    private suspend fun link(into: FakeGraph) {
        into.haConnections.upsert(connection)
        into.seasonSyncBindings.insert(binding)
    }

    @Test
    fun anExportCarriesNoConnectionBindingEntityOrAddressByte() = runTest {
        graph.assets.upsert(heaterRow)
        val unlinked = graph.exportBackupSet.run().data
        link(graph)

        val linked = graph.exportBackupSet.run().data

        assertNoMarker("the export", textOf(linked))
        assertEquals(
            "linking changes no byte of the data",
            entryOf(unlinked, BackupCodec.DATA_ENTRY).decodeToString(),
            entryOf(linked, BackupCodec.DATA_ENTRY).decodeToString(),
        )
        assertTrue(BackupCodec.MANIFEST_ENTRY in entriesOf(linked))
        assertEquals(setOf(BackupCodec.MANIFEST_ENTRY, BackupCodec.DATA_ENTRY), entriesOf(linked).keys)
    }

    @Test
    fun aReplaceRestoreRemovesBindingsAndKeepsTheConnection() = runTest {
        graph.assets.upsert(heaterRow)
        val archive = graph.exportBackupSet.run().data
        link(graph)

        graph.importBackupReplace.run(archive)

        assertEquals("the asset is restored", heaterRow, graph.assets.get(heater))
        assertNull("its binding went with the wiped asset", graph.seasonSyncBindings.get(heater))
        assertEquals(emptyList<SeasonSyncBinding>(), graph.seasonSyncBindings.all())
        assertEquals("the connection stays", connection, graph.haConnections.get())
    }

    @Test
    fun aMergeLeavesTheBindingAndItsRevision() = runTest {
        val dehumidifier = Asset(id = AssetId("asset-dehumidifier"), name = "Example Dehumidifier", createdAt = 1_000L, updatedAt = 1_000L)
        source.assets.upsert(heaterRow)
        source.assets.upsert(dehumidifier)
        graph.assets.upsert(heaterRow)
        link(graph)

        graph.importBackupMerge.run(source.exportBackupSet.run().data)

        assertEquals("the merge applied", dehumidifier, graph.assets.get(dehumidifier.id))
        assertEquals(heaterRow, graph.assets.get(heater))
        assertEquals(binding, graph.seasonSyncBindings.get(heater))
        assertEquals(listOf(binding), graph.seasonSyncBindings.all())
        assertEquals(connection, graph.haConnections.get())
    }

    @Test
    fun aTransferPackCarriesNeither() = runTest {
        graph.assets.upsert(heaterRow)
        link(graph)

        val created = graph.createTransferPack.run(listOf(heater), "")

        val draft = (created as CreateTransferPackResult.Created).draft
        assertEquals(listOf(heater.value), draft.assetIds)
        assertNoMarker("the pack's data", textOf(draft.data))
        assertNoMarker("the pack's manifest", draft.manifest("0".repeat(64)).toString())
        assertNoMarker("the pack's counts", draft.counts.keys.joinToString(","))
        assertEquals("the binding stays here", binding, graph.seasonSyncBindings.get(heater))
    }

    // --- the bytes -----------------------------------------------------------------------------

    private fun assertNoMarker(what: String, text: String) {
        assertTrue("$what is read", text.isNotEmpty())
        for (marker in MARKERS) assertTrue("$what carries '$marker'", marker !in text)
    }

    private fun entriesOf(archive: ByteArray): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        return entries
    }

    private fun entryOf(archive: ByteArray, name: String): ByteArray = entriesOf(archive).getValue(name)

    /** Every entry inflated, and the raw bytes too, so neither a compressed nor a stored byte hides a marker. */
    private fun textOf(archive: ByteArray): String =
        entriesOf(archive).entries.joinToString("\n") { (name, bytes) -> name + "\n" + bytes.decodeToString() } +
            "\n" + String(archive, Charsets.ISO_8859_1)

    private companion object {
        const val CONNECTION = "ha-connection-example"
        const val ENTITY = "input_boolean.example_heater_in_season"

        /** Every name and value the two tables hold that no other row of the fixture does. */
        val MARKERS = listOf(
            "ha_", "season_sync", "seasonSync", "haConnection", "SeasonSync", "HaConnection", CONNECTION, ENTITY,
            "input_boolean", "192.168.0.10", ":8123", "ExampleHomeWifi", "homeNetwork", "fetch-detail-x",
            "2026-10-01T05:00:00", "networkEligibility", "backgroundChecks", "WEEKLY",
        )
    }
}
