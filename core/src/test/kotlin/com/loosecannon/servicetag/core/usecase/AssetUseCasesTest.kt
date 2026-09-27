package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.Season
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.testing.FakeUnitOfWork
import com.loosecannon.servicetag.core.testing.InMemoryAssetRepository
import com.loosecannon.servicetag.core.testing.InMemoryCategoryRepository
import com.loosecannon.servicetag.core.testing.InMemoryClosureRepository
import com.loosecannon.servicetag.core.testing.InMemoryEventRepository
import com.loosecannon.servicetag.core.testing.InMemoryGroupRepository
import com.loosecannon.servicetag.core.testing.InMemoryScheduleRepository
import com.loosecannon.servicetag.core.testing.InMemoryScheduleStateRepository
import com.loosecannon.servicetag.core.testing.InMemorySeasonActivationRepository
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two edit-side asset use cases. Archive-first (R-9): nothing here deletes a row, so an
 * archived asset keeps its id, its created date and every tag already bound to it.
 */
class AssetUseCasesTest {
    private val assets = InMemoryAssetRepository()
    private val categories = InMemoryCategoryRepository()
    private val uow = FakeUnitOfWork(assets, categories)
    private var now = 1_000L
    private val clock = Clock { now }
    // 1.4: an edit whose season pair changed checks the asset's schedules and rebuilds them, so the
    // use case takes both; none of these tests stores a schedule.
    private val closures = InMemoryClosureRepository()
    private val states = InMemoryScheduleStateRepository()
    private val schedules = InMemoryScheduleRepository(closures, states)
    private val recompute = RecomputeSchedules(
        schedules, states, InMemoryEventRepository(), closures, InMemoryGroupRepository(), assets,
        InMemorySeasonActivationRepository(), Today { LocalDate.parse("2026-01-01") }, clock,
    ) { ZoneOffset.UTC }
    private val update = UpdateAsset(assets, schedules, uow, clock, recompute, PromoteCategory(categories))
    // No scheduling fakes here, so the lifecycle rebuild is explicitly nothing: the seam has
    // no default, so a graph that forgot to wire it would not compile.
    private val archive = ArchiveAsset(assets, uow, clock) { }

    private suspend fun store(
        name: String = "Pool pump",
        status: AssetStatus = AssetStatus.ACTIVE,
    ): Asset {
        val asset = Asset(
            id = AssetId("a1"),
            name = name,
            description = "Pentair",
            category = "Water",
            notes = "",
            status = status,
            createdAt = 500L,
            updatedAt = 500L,
        )
        assets.upsert(asset)
        return asset
    }

    private suspend fun child(id: String, name: String, parent: AssetId?): Asset {
        val asset = Asset(
            id = AssetId(id),
            name = name,
            createdAt = 500L,
            updatedAt = 500L,
            parentAssetId = parent,
        )
        assets.upsert(asset)
        return asset
    }

    @Test fun updateTrimsEveryFieldAndBumpsUpdatedAt() = runTest {
        store()
        now = 9_000L
        val saved = update.run(
            AssetId("a1"),
            AssetCommand("  Hot tub  ", "  Water  ", "  Jacuzzi J-235  ", "  drains in Oct  "),
        )
        assertEquals("Hot tub", saved.name)
        assertEquals("Water", saved.category)
        assertEquals("Jacuzzi J-235", saved.description)
        assertEquals("drains in Oct", saved.notes)
        assertEquals(9_000L, saved.updatedAt)
        assertEquals(saved, assets.rows["a1"])
        assertEquals(1, uow.commits)
    }

    @Test fun updateKeepsTheCreatedDateAndTheArchivedStatus() = runTest {
        store(status = AssetStatus.ARCHIVED)
        now = 9_000L
        val saved = update.run(AssetId("a1"), AssetCommand("Hot tub"))
        assertEquals(500L, saved.createdAt)
        assertEquals(AssetStatus.ARCHIVED, saved.status)
    }

    @Test fun updateRefusesABlankNameAndWritesNothing() = runTest {
        val before = store()
        assertFailsWith<AssetValidation> { update.run(AssetId("a1"), AssetCommand("   ")) }
        assertEquals(before, assets.rows["a1"])
        assertEquals(0, uow.commits)
    }

    @Test fun updateRefusesAnUnknownId() = runTest {
        assertFailsWith<NoSuchAsset> { update.run(AssetId("nope"), AssetCommand("Hot tub")) }
        assertEquals(0, uow.commits)
    }

    @Test fun archiveFlipsTheStatusAndBumpsUpdatedAt() = runTest {
        store()
        now = 9_000L
        val archived = archive.run(AssetId("a1"))
        assertEquals(AssetStatus.ARCHIVED, archived.status)
        assertEquals(9_000L, archived.updatedAt)
        assertEquals(500L, archived.createdAt)
        assertEquals(archived, assets.rows["a1"])
        assertEquals(1, uow.commits)
    }

    @Test fun unarchiveRestoresActiveAndAnUnknownIdIsRefused() = runTest {
        store(status = AssetStatus.ARCHIVED)
        now = 9_000L
        assertEquals(AssetStatus.ACTIVE, archive.unarchive(AssetId("a1")).status)
        assertFailsWith<NoSuchAsset> { archive.unarchive(AssetId("nope")) }
        assertFailsWith<NoSuchAsset> { archive.run(AssetId("nope")) }
        assertEquals(1, uow.commits)
    }

    // --- full-field edits (2B-2) ------------------------------------------------------------

    @Test fun updateWithAllFieldsRoundTrips() = runTest {
        store()
        now = 9_000L
        val saved = update.run(
            AssetId("a1"),
            AssetCommand(
                name = "  Hot tub  ", category = " Water ", description = " Jacuzzi ", notes = " drains ",
                manufacturer = " Jacuzzi ", model = " J-235 ", serialNumber = " SN-7 ",
                purchaseOn = "2021-04-02", inServiceOn = "2021-04-09",
                purchasePriceMinor = 899_900L, currency = "USD",
                vendor = " Spa Barn ", location = " Back deck ",
                warrantyExpiresOn = "2026-04-02", warrantyNotes = " pump only ",
                seasonStartMmdd = "05-01", seasonEndMmdd = "09-30",
            ),
        )
        assertEquals("Hot tub", saved.name)
        assertEquals("Water", saved.category)
        assertEquals("Jacuzzi", saved.description)
        assertEquals("drains", saved.notes)
        assertEquals("Jacuzzi", saved.manufacturer)
        assertEquals("J-235", saved.model)
        assertEquals("SN-7", saved.serialNumber)
        assertEquals("2021-04-02", saved.purchaseOn)
        assertEquals("2021-04-09", saved.inServiceOn)
        assertEquals(899_900L, saved.purchasePriceMinor)
        assertEquals("USD", saved.currency)
        assertEquals("Spa Barn", saved.vendor)
        assertEquals("Back deck", saved.location)
        assertEquals("2026-04-02", saved.warrantyExpiresOn)
        assertEquals("pump only", saved.warrantyNotes)
        assertEquals("05-01", saved.seasonStartMmdd)
        assertEquals("09-30", saved.seasonEndMmdd)
        assertNull(saved.parentAssetId)
        // identity and lifecycle survive the edit
        assertEquals(500L, saved.createdAt)
        assertEquals(9_000L, saved.updatedAt)
        assertEquals(AssetStatus.ACTIVE, saved.status)
        assertNull(saved.retiredOn)
        assertEquals(saved, assets.rows["a1"])
        assertEquals(1, uow.commits)
    }

    @Test fun currencyMustBeThreeUpperLetters() = runTest {
        store()
        val lower = assertFailsWith<AssetValidation> {
            update.run(AssetId("a1"), AssetCommand("Hot tub", purchasePriceMinor = 100L, currency = "usd"))
        }
        assertEquals(listOf(AssetProblem.BadCurrency), lower.problems)
        val short = assertFailsWith<AssetValidation> {
            update.run(AssetId("a1"), AssetCommand("Hot tub", purchasePriceMinor = 100L, currency = "US"))
        }
        assertEquals(listOf(AssetProblem.BadCurrency), short.problems)
        // three upper letters that no currency table knows, with a price: unresolvable
        val bogus = assertFailsWith<AssetValidation> {
            update.run(AssetId("a1"), AssetCommand("Hot tub", purchasePriceMinor = 100L, currency = "QQQ"))
        }
        assertEquals(listOf(AssetProblem.BadCurrency), bogus.problems)
        // a price with no currency at all, and a negative price
        assertEquals(
            listOf(AssetProblem.CurrencyRequired),
            assertFailsWith<AssetValidation> {
                update.run(AssetId("a1"), AssetCommand("Hot tub", purchasePriceMinor = 100L))
            }.problems,
        )
        assertEquals(
            listOf(AssetProblem.NegativePrice),
            assertFailsWith<AssetValidation> {
                update.run(AssetId("a1"), AssetCommand("Hot tub", purchasePriceMinor = -1L, currency = "USD"))
            }.problems,
        )
        // the three-letter shape is enough without a price
        assertEquals("QQQ", update.run(AssetId("a1"), AssetCommand("Hot tub", currency = "QQQ")).currency)
        assertEquals(1, uow.commits)
    }

    @Test fun datesMustParse() = runTest {
        store()
        val bad = assertFailsWith<AssetValidation> {
            update.run(
                AssetId("a1"),
                AssetCommand("Hot tub", purchaseOn = "04/02/2021", inServiceOn = "2021-13-01", warrantyExpiresOn = "nope"),
            )
        }
        assertEquals(
            listOf(
                AssetProblem.BadDate("purchaseOn"),
                AssetProblem.BadDate("inServiceOn"),
                AssetProblem.BadDate("warrantyExpiresOn"),
            ),
            bad.problems,
        )
        assertEquals(0, uow.commits)
        // blank is the same as absent
        val saved = update.run(AssetId("a1"), AssetCommand("Hot tub", purchaseOn = "  ", inServiceOn = ""))
        assertNull(saved.purchaseOn)
        assertNull(saved.inServiceOn)
    }

    @Test fun seasonProblemsSurface() = runTest {
        store()
        val half = assertFailsWith<AssetValidation> {
            update.run(AssetId("a1"), AssetCommand("Hot tub", seasonStartMmdd = "05-01"))
        }
        assertEquals(listOf(AssetProblem.Season(Season.Problem.BothOrNeither)), half.problems)
        val badDay = assertFailsWith<AssetValidation> {
            update.run(AssetId("a1"), AssetCommand("Hot tub", seasonStartMmdd = "05-01", seasonEndMmdd = "09-31"))
        }
        assertEquals(listOf(AssetProblem.Season(Season.Problem.BadDate("end"))), badDay.problems)
        assertEquals(0, uow.commits)
    }

    @Test fun unknownParentRefused() = runTest {
        store()
        val boom = assertFailsWith<AssetValidation> {
            update.run(AssetId("a1"), AssetCommand("Hot tub", parentAssetId = AssetId("ghost")))
        }
        assertEquals(listOf(AssetProblem.UnknownParent), boom.problems)
        assertEquals(0, uow.commits)
    }

    @Test fun reparentUnderOwnDescendantIsACycle() = runTest {
        store()
        child("a2", "Heater", parent = AssetId("a1"))
        child("a3", "Element", parent = AssetId("a2"))
        val before = assets.rows.toMap()

        val cycle = assertFailsWith<AssetCycle> {
            update.run(AssetId("a1"), AssetCommand("Pool pump", parentAssetId = AssetId("a3")))
        }
        assertEquals(AssetId("a1"), cycle.assetId)
        assertEquals(AssetId("a3"), cycle.parentId)
        // parenting an asset to itself is the degenerate case of the same cycle
        assertFailsWith<AssetCycle> {
            update.run(AssetId("a1"), AssetCommand("Pool pump", parentAssetId = AssetId("a1")))
        }
        assertEquals(before, assets.rows.toMap())
        assertEquals(0, uow.commits)
    }

    @Test fun reparentUnderSiblingPasses() = runTest {
        store()
        child("a2", "Heater", parent = AssetId("a1"))
        child("a3", "Blower", parent = AssetId("a1"))
        now = 9_000L
        val saved = update.run(AssetId("a3"), AssetCommand("Blower", parentAssetId = AssetId("a2")))
        assertEquals(AssetId("a2"), saved.parentAssetId)
        assertEquals(saved, assets.rows["a3"])
        assertEquals(1, uow.commits)
        assertTrue(assets.rows.containsKey("a1"))
    }

    // --- #79 (C2, K4; R79-11, R79-12b): the lead lives outside the form's command -----------------

    private suspend fun storeWithLead(expiresOn: String = "2027-03-01", lead: Int = 30): Asset =
        store().copy(warrantyExpiresOn = expiresOn, warrantyReminderLeadDays = lead).also { assets.upsert(it) }

    /** A full-replace edit that says nothing about the lead keeps it while the warranty date stays. */
    @Test fun anUpdateKeepsTheLeadWhileTheDateStays() = runTest {
        storeWithLead()
        now = 9_000L
        val renamed = update.run(AssetId("a1"), AssetCommand("Pool pump, deck", warrantyExpiresOn = "2027-03-01"))
        assertEquals(30, renamed.warrantyReminderLeadDays)
        assertEquals(renamed, assets.rows["a1"])
        assertEquals(9_000L, renamed.updatedAt)

        val moved = update.run(AssetId("a1"), AssetCommand("Pool pump, deck", warrantyExpiresOn = " 2029-12-31 "))
        assertEquals("2029-12-31" to 30, moved.warrantyExpiresOn to moved.warrantyReminderLeadDays, "a new date keeps it too")
    }

    /** R79-12b: an edit that clears the date clears the lead, so a lead never outlives its date. */
    @Test fun anUpdateThatClearsTheDateClearsTheLead() = runTest {
        storeWithLead()
        val cleared = update.run(AssetId("a1"), AssetCommand("Pool pump"))
        assertEquals(null to null, cleared.warrantyExpiresOn to cleared.warrantyReminderLeadDays)
        assertEquals(cleared, assets.rows["a1"])

        storeWithLead()
        val blank = update.run(AssetId("a1"), AssetCommand("Pool pump", warrantyExpiresOn = "   "))
        assertEquals(null to null, blank.warrantyExpiresOn to blank.warrantyReminderLeadDays, "blank is no date")
    }

    /**
     * K4: the form's command has no lead, so a 1.4-era full-replace PATCH — which clears an omitted
     * optional — can never clear it. Read reflectively, so a field added later fails here.
     */
    @Test fun assetCommandHasNoLeadProperty() {
        val names = AssetCommand::class.java.declaredFields.map { it.name }
        assertTrue("warrantyExpiresOn" in names && "warrantyNotes" in names, "the reflection reads the command: $names")
        assertTrue(
            names.none { "lead" in it.lowercase() || "reminder" in it.lowercase() },
            "AssetCommand must not carry the warranty reminder: $names",
        )
    }
}
