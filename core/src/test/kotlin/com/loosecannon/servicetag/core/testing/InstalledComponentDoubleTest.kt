package com.loosecannon.servicetag.core.testing

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.InstalledComponentId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #47 (B1a; row 57, C-2): the core double refuses what the schema refuses — a parent not stored or stored on
 * another asset, a SupplyItem it was not handed, an entry id held twice, a `replacesId` held twice — and its rows
 * and entries leave with their asset through [BackupInstall]'s registration, as the schema's CASCADE takes them.
 * The names are fictional.
 */
class InstalledComponentDoubleTest {

    private val battery = supplyItemOf("s1", "Example 12 V Battery")
    private val pack = supplyItemOf("s2", "Example Battery Pack")

    private suspend fun install(): BackupInstall = BackupInstall().also {
        it.assets.upsert(plainAssetOf("x1", "Example UPS"))
        it.assets.upsert(plainAssetOf("x2", "Example RO System"))
        it.supplyItems.upsert(battery)
        it.supplyItems.upsert(pack)
    }

    @Test fun aParentNotStoredOrOnAnotherAssetThrows() = runTest {
        val store = install().installedComponents
        store.insert(installedComponentOf("c1", assetId = "x1", name = "Example Battery Tray"))

        assertFailsWith<IllegalStateException> {
            store.insert(installedComponentOf("c2", assetId = "x1", name = "Position 1", parentId = "not-stored"))
        }
        assertFailsWith<IllegalStateException> {
            store.insert(installedComponentOf("c3", assetId = "x2", name = "Position 1", parentId = "c1"))
        }
        store.insert(installedComponentOf("c4", assetId = "x1", name = "Position 1", parentId = "c1"))
        assertFailsWith<IllegalStateException> {
            store.update(installedComponentOf("c4", assetId = "x1", name = "Position 1", parentId = "not-stored"))
        }

        assertEquals(listOf("c1", "c4"), store.all().map { it.id.value })
        assertEquals("c1", store.get(InstalledComponentId("c4"))?.parentId?.value)
    }

    @Test fun anUnknownDirectOrEntrySupplyItemThrows() = runTest {
        val store = install().installedComponents

        assertFailsWith<IllegalStateException> {
            store.insert(installedComponentOf("c1", assetId = "x1", supplyId = "s9"))
        }
        assertFailsWith<IllegalStateException> {
            store.insert(
                installedComponentOf(
                    "c1", assetId = "x1",
                    composition = listOf(compositionEntryOf("e1", "s1"), compositionEntryOf("e2", "s9", sortOrder = 1)),
                ),
            )
        }
        store.insert(installedComponentOf("c1", assetId = "x1", supplyId = "s2", composition = listOf(compositionEntryOf("e1", "s1"))))
        assertFailsWith<IllegalStateException> {
            store.update(installedComponentOf("c1", assetId = "x1", supplyId = "s2", composition = listOf(compositionEntryOf("e1", "s9"))))
        }

        assertEquals(listOf("e1"), store.get(InstalledComponentId("c1"))?.composition?.map { it.id })
        assertEquals(setOf("e1"), store.entries.keys)
    }

    @Test fun aRepeatedEntryIdAcrossRowsThrows() = runTest {
        val store = install().installedComponents
        store.insert(installedComponentOf("c1", assetId = "x1", composition = listOf(compositionEntryOf("e1", "s1", quantity = 4.0))))

        assertFailsWith<IllegalStateException> {
            store.insert(installedComponentOf("c2", assetId = "x1", composition = listOf(compositionEntryOf("e1", "s1"))))
        }
        assertFailsWith<IllegalStateException> {
            store.insert(
                installedComponentOf(
                    "c2", assetId = "x1",
                    composition = listOf(compositionEntryOf("e2", "s1"), compositionEntryOf("e2", "s2", sortOrder = 1)),
                ),
            )
        }
        store.insert(installedComponentOf("c2", assetId = "x1", composition = listOf(compositionEntryOf("e2", "s1"))))
        assertFailsWith<IllegalStateException> {
            store.update(installedComponentOf("c2", assetId = "x1", composition = listOf(compositionEntryOf("e1", "s1"))))
        }
        // A row's own entry ids are its own to keep on an update.
        store.update(installedComponentOf("c1", assetId = "x1", composition = listOf(compositionEntryOf("e1", "s1", quantity = 6.0))))

        assertEquals(mapOf("e1" to "c1", "e2" to "c2"), store.entries.mapValues { it.value.componentId.value })
        assertEquals(6.0, store.get(InstalledComponentId("c1"))?.composition?.single()?.quantity)
    }

    @Test fun aRepeatedReplacesIdThrows() = runTest {
        val store = install().installedComponents
        store.insert(installedComponentOf("c1", assetId = "x1", removedOn = "2026-09-01"))
        store.insert(installedComponentOf("c2", assetId = "x1", replacesId = "c1"))

        assertFailsWith<IllegalStateException> {
            store.insert(installedComponentOf("c3", assetId = "x1", replacesId = "c1"))
        }
        store.insert(installedComponentOf("c3", assetId = "x1"))
        assertFailsWith<IllegalStateException> {
            store.update(installedComponentOf("c3", assetId = "x1", replacesId = "c1"))
        }
        // Nulls repeat, as SQLite's unique index admits them; a row keeps its own replacesId on an update.
        store.insert(installedComponentOf("c4", assetId = "x1"))
        store.update(installedComponentOf("c2", assetId = "x1", replacesId = "c1", notes = "Example note"))

        assertEquals(listOf(null, "c1", null, null), store.all().map { it.replacesId?.value })
    }

    @Test fun aRepeatedRowIdThrows() = runTest {
        val store = install().installedComponents
        store.insert(installedComponentOf("c1", assetId = "x1"))

        assertFailsWith<IllegalStateException> { store.insert(installedComponentOf("c1", assetId = "x1", name = "Another")) }
        assertEquals("Example Battery Tray", store.get(InstalledComponentId("c1"))?.name)
    }

    @Test fun anAssetDeleteThroughBackupInstallTakesItsRowsAndEntries() = runTest {
        val install = install()
        val store = install.installedComponents
        store.insert(installedComponentOf("c1", assetId = "x1", composition = listOf(compositionEntryOf("e1", "s1", quantity = 4.0))))
        store.insert(installedComponentOf("c2", assetId = "x1", parentId = "c1", composition = listOf(compositionEntryOf("e2", "s1"))))
        store.insert(installedComponentOf("c3", assetId = "x1", parentId = "c2"))
        store.insert(installedComponentOf("c4", assetId = "x2", composition = listOf(compositionEntryOf("e3", "s2"))))

        install.assets.delete(AssetId("x1"))

        assertEquals(listOf("c4"), store.all().map { it.id.value })
        assertEquals(setOf("e3"), store.entries.keys)
        assertEquals(emptyList(), store.forAsset(AssetId("x1")))

        install.assets.deleteAll()

        assertEquals(emptyList(), store.all())
        assertTrue(store.entries.isEmpty())
    }

    @Test fun readsAssembleEachRowWithItsEntriesBySortOrderThenId() = runTest {
        val store = install().installedComponents
        store.insert(
            installedComponentOf(
                "c2", assetId = "x1", supplyId = "s2",
                composition = listOf(
                    compositionEntryOf("e9", "s1", quantity = 2.0, sortOrder = 1),
                    compositionEntryOf("e3", "s1", quantity = 4.0, unit = "ea", sortOrder = 0),
                    compositionEntryOf("e1", "s2", quantity = 1.0, sortOrder = 1),
                ),
            ),
        )
        store.insert(installedComponentOf("c1", assetId = "x1", removedOn = "2026-09-01"))
        store.insert(installedComponentOf("c3", assetId = "x2"))

        assertEquals(listOf("e3", "e1", "e9"), store.get(InstalledComponentId("c2"))?.composition?.map { it.id })
        assertEquals(listOf("c1", "c2"), store.forAsset(AssetId("x1")).map { it.id.value })
        assertEquals(listOf("c1", "c2", "c3"), store.all().map { it.id.value })
        assertEquals(listOf("c1", "c2"), store.observeForAsset(AssetId("x1")).first().map { it.id.value })
        assertEquals(3, store.forAsset(AssetId("x1")).single { it.id.value == "c2" }.composition.size)
        assertNull(store.get(InstalledComponentId("c9")))
    }

    @Test fun anUpdateReplacesTheCompositionWhole() = runTest {
        val store = install().installedComponents
        store.insert(
            installedComponentOf(
                "c1", assetId = "x1",
                composition = listOf(compositionEntryOf("e1", "s1", quantity = 4.0), compositionEntryOf("e2", "s2", sortOrder = 1)),
            ),
        )

        store.update(
            installedComponentOf(
                "c1", assetId = "x1", notes = "Example note",
                composition = listOf(compositionEntryOf("e2", "s2"), compositionEntryOf("e5", "s1", quantity = 3.0, sortOrder = 1)),
            ),
        )

        assertEquals(setOf("e2", "e5"), store.entries.keys)
        assertEquals(listOf("e2", "e5"), store.get(InstalledComponentId("c1"))?.composition?.map { it.id })

        store.update(installedComponentOf("c1", assetId = "x1", composition = emptyList()))
        assertTrue(store.entries.isEmpty())
    }

    @Test fun aRolledBackWriteRestoresRowsAndEntries() = runTest {
        val install = install()
        val store = install.installedComponents
        val uow = FakeUnitOfWork(store)
        store.insert(installedComponentOf("c1", assetId = "x1", composition = listOf(compositionEntryOf("e1", "s1"))))

        assertFailsWith<IllegalStateException> {
            uow.write {
                store.update(installedComponentOf("c1", assetId = "x1", composition = listOf(compositionEntryOf("e2", "s1"))))
                store.insert(installedComponentOf("c2", assetId = "x1", parentId = "not-stored"))
            }
        }

        assertEquals(setOf("e1"), store.entries.keys)
        assertEquals(listOf("c1"), store.all().map { it.id.value })
    }
}
