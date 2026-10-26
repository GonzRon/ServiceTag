package com.loosecannon.servicetag.backup

import com.loosecannon.servicetag.core.backup.BackupCodec
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.seasonsync.BackgroundChecks
import com.loosecannon.servicetag.core.seasonsync.HaConnection
import com.loosecannon.servicetag.core.seasonsync.HaSwitchState
import com.loosecannon.servicetag.core.seasonsync.LastAppliedSource
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncBinding
import com.loosecannon.servicetag.core.seasonsync.SyncCadence
import com.loosecannon.servicetag.core.seasonsync.SyncErrorKind
import com.loosecannon.servicetag.core.seasonsync.SyncMode
import com.loosecannon.servicetag.core.usecase.CreateTransferPackResult
import com.loosecannon.servicetag.data.room.entities.HaPublishBindingEntity
import com.loosecannon.servicetag.data.room.entities.HaPublishDecommissionEntity
import com.loosecannon.servicetag.data.room.entities.HaPublishDeliveryEntity
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
 *
 * #113/#114 S1 (C6, G5, G7; row 12) extends the shape to the three publishing tables — `ha_publish_binding`,
 * `ha_publish_delivery` and `ha_publish_decommission`: no export, merge, Transfer Pack or replace restore names them,
 * a merge leaves every row and revision, and a replace restore's wipe leaves them for the projector (the binding's
 * asset is a soft link, C34). Tripwires: schema 22's own RED is row 22's.
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
        lastAppliedSource = LastAppliedSource.FORCED_IN, createdAt = 2_500L, updatedAt = 3_500L,
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

    // --- #113/#114 S1 (C6, G5, G7; row 12): the three publishing tables travel nowhere ----------------

    private val statusBinding = HaPublishBindingEntity(
        id = "example-binding-status", assetId = heater.value, connectionId = CONNECTION, kind = "EQUIPMENT_STATUS",
        entityId = STATUS_ENTITY, enabled = true, revision = 4, activatedInstallationId = INSTALLATION,
        validatedAt = 4_000L, validatedRevision = 4, boundaryEntityId = null, createdAt = 4_000L, updatedAt = 4_000L,
    )
    private val dateBinding = statusBinding.copy(
        id = "example-binding-date", kind = "SEASON_SERVICE_DATE", entityId = DATE_ENTITY, revision = 7,
        validatedRevision = 7, boundaryEntityId = BOUNDARY_ENTITY,
    )

    /** A delivery with every optional field set, so any column that leaked would carry a marker. */
    private fun publishDelivery(bindingId: String, value: String) = HaPublishDeliveryEntity(
        bindingId = bindingId, desiredValue = value, desiredRevision = 9, desiredAt = 4_100L, lastPublishedValue = value,
        lastPublishedAt = 4_200L, publishedDesiredRevision = 9, publishedBindingRevision = 4, lastVerifiedValue = value,
        lastVerifiedAt = 4_300L, verifiedBindingRevision = 4, verifiedDesiredRevision = 9, remoteValue = value,
        remoteReadAt = 4_300L, errorKind = "OUTCOME_UNKNOWN", errorDetail = "publish-detail-x", errorAt = 4_400L,
        errorBindingRevision = 4, errorDesiredRevision = 9, outcomeUnknownSince = 4_400L, needsDelivery = true,
        attemptsAtRevision = 3, lastAttemptAt = 4_400L, boundaryValue = "2026-05-02", boundaryReadAt = 4_500L,
        boundaryBindingRevision = 7, reconcilePending = true, lastRequestId = "example-request-1", lastRequestAt = 4_600L,
    )

    private val publishTombstone = HaPublishDecommissionEntity(
        id = "example-tombstone-1", connectionId = CONNECTION, entityId = OLD_DATE_ENTITY, assetId = heater.value,
        installationId = INSTALLATION, state = "UNDELIVERABLE", requestedAt = 4_700L, errorKind = "AUTH_REFUSED",
        errorAt = 4_800L, revision = 2,
    )

    /** A status and a date binding on the heater, their deliveries with every marker set, and one tombstone. */
    private suspend fun publish(into: FakeGraph) {
        into.haConnections.upsert(connection)
        into.db.haPublishBindingDao().insert(statusBinding)
        into.db.haPublishBindingDao().insert(dateBinding)
        into.db.haPublishDeliveryDao().insert(publishDelivery(statusBinding.id, "DEGRADED"))
        into.db.haPublishDeliveryDao().insert(publishDelivery(dateBinding.id, "2026-09-20"))
        into.db.haPublishDecommissionDao().insertOrMerge(publishTombstone)
    }

    /** Every row of the three tables, so a move of any field — a revision above all — shows. */
    private suspend fun publishRows(of: FakeGraph): List<Any> =
        of.db.haPublishBindingDao().all() + of.db.haPublishDeliveryDao().all() + of.db.haPublishDecommissionDao().all()

    @Test
    fun anExportCarriesNoPublishBindingDeliveryTombstoneOrEntityByte() = runTest {
        graph.assets.upsert(heaterRow)
        graph.haConnections.upsert(connection)
        val unpublished = graph.exportBackupSet.run().data
        publish(graph)

        val published = graph.exportBackupSet.run().data

        assertNoMarker("the export", textOf(published), PUBLISH_MARKERS)
        assertEquals(
            "publishing changes no byte of the data",
            entryOf(unpublished, BackupCodec.DATA_ENTRY).decodeToString(),
            entryOf(published, BackupCodec.DATA_ENTRY).decodeToString(),
        )
        assertEquals(setOf(BackupCodec.MANIFEST_ENTRY, BackupCodec.DATA_ENTRY), entriesOf(published).keys)
    }

    @Test
    fun aMergeLeavesThemAndTheirRevisions() = runTest {
        val dehumidifier = Asset(id = AssetId("asset-dehumidifier"), name = "Example Dehumidifier", createdAt = 1_000L, updatedAt = 1_000L)
        source.assets.upsert(heaterRow)
        source.assets.upsert(dehumidifier)
        graph.assets.upsert(heaterRow)
        publish(graph)
        val before = publishRows(graph)

        graph.importBackupMerge.run(source.exportBackupSet.run().data)

        assertEquals("the merge applied", dehumidifier, graph.assets.get(dehumidifier.id))
        assertEquals(5, before.size)
        assertEquals(before, publishRows(graph))
    }

    @Test
    fun aTransferPackCarriesNone() = runTest {
        graph.assets.upsert(heaterRow)
        publish(graph)
        val before = publishRows(graph)

        val created = graph.createTransferPack.run(listOf(heater), "")

        val draft = (created as CreateTransferPackResult.Created).draft
        assertEquals(listOf(heater.value), draft.assetIds)
        assertNoMarker("the pack's data", textOf(draft.data), PUBLISH_MARKERS)
        assertNoMarker("the pack's manifest", draft.manifest("0".repeat(64)).toString(), PUBLISH_MARKERS)
        assertNoMarker("the pack's counts", draft.counts.keys.joinToString(","), PUBLISH_MARKERS)
        assertEquals("the rows stay here", before, publishRows(graph))
    }

    @Test
    fun aReplaceRestoreLeavesThemForTheProjector() = runTest {
        graph.assets.upsert(heaterRow)
        val archive = graph.exportBackupSet.run().data
        publish(graph)
        val before = publishRows(graph)

        graph.importBackupReplace.run(archive)

        assertEquals("the asset is restored", heaterRow, graph.assets.get(heater))
        // the binding's asset is a soft link (C4.2, C34): the wipe leaves every row for the projector to retire
        assertEquals(before, publishRows(graph))
        assertEquals("the connection stays", connection, graph.haConnections.get())
    }

    // --- the bytes -----------------------------------------------------------------------------

    private fun assertNoMarker(what: String, text: String, markers: List<String> = MARKERS) {
        assertTrue("$what is read", text.isNotEmpty())
        for (marker in markers) assertTrue("$what carries '$marker'", marker !in text)
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
            "2026-10-01T05:00:00", "networkEligibility", "backgroundChecks", "WEEKLY", "last_applied_source",
            "lastAppliedSource", "FORCED_IN",
        )

        const val STATUS_ENTITY = "input_select.example_heater_equipment_status"
        const val DATE_ENTITY = "input_datetime.example_heater_service_done"
        const val BOUNDARY_ENTITY = "input_datetime.example_heater_season_ended"
        const val OLD_DATE_ENTITY = "input_datetime.example_old_service_done"
        const val INSTALLATION = "example-installation-1"

        /** #113/#114 (C6): every name and value the three publishing tables hold that no other row of the fixture does. */
        val PUBLISH_MARKERS = MARKERS + listOf(
            "ha_publish", "haPublish", "HaPublish", "desired_value", "desiredValue", "activated_installation_id",
            "activatedInstallationId", "reconcile_pending", "reconcilePending", "claim_entity_id", "last_request_id",
            "input_select", "input_datetime", STATUS_ENTITY, DATE_ENTITY, BOUNDARY_ENTITY, OLD_DATE_ENTITY, INSTALLATION,
            "example-binding-", "example-tombstone-1", "example-request-1", "publish-detail-x", "DEGRADED",
            "2026-09-20", "2026-05-02", "OUTCOME_UNKNOWN", "UNDELIVERABLE", "AUTH_REFUSED", "SEASON_SERVICE_DATE",
            "EQUIPMENT_STATUS",
        )
    }
}
