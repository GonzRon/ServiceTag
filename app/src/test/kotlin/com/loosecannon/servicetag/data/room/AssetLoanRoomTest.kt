package com.loosecannon.servicetag.data.room

import androidx.sqlite.SQLiteException
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetLoan
import com.loosecannon.servicetag.core.model.AssetLoanId
import com.loosecannon.servicetag.core.model.LoanReminderMode
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.usecase.AssetAlreadyLent
import com.loosecannon.servicetag.core.usecase.LendAsset
import com.loosecannon.servicetag.core.usecase.LoanTerms
import com.loosecannon.servicetag.core.usecase.ReturnLoan
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #72's loan table through the production adapter (C2 ii, C5; R72-2): the schema's own word on one open
 * loan per asset. `open_marker` is 1 while a loan is open and NULL once it is returned, under
 * `UNIQUE(asset_id, open_marker)`: a second open row for one asset aborts at the index, while returned
 * rows — NULLs, which SQLite keeps distinct — pile up as history. The mapper sets the marker, so no caller
 * can set it wrong. A loan goes with its asset, and no write here takes one by a REPLACE's cascade. The
 * use case over Room meets its own refusal first, never the index. The names and links are fictional.
 */
class AssetLoanRoomTest {

    private val db = inMemoryDb()
    private val assets = RoomAssetRepository(db.assetDao())
    private val loans = RoomAssetLoanRepository(db.assetLoanDao())

    @After fun close() = db.close()

    private fun loanOf(
        id: String,
        assetId: String = "a1",
        lentOn: String = "2026-09-20",
        returnedOn: String? = null,
        link: String? = "content://com.android.contacts/contacts/lookup/0r1-EXAMPLEKEY/7",
    ) = AssetLoan(
        id = AssetLoanId(id), assetId = AssetId(assetId), borrowerName = "Sample Borrower", contactLookupUri = link,
        lentOn = lentOn, dueOn = "2026-10-04", returnedOn = returnedOn, reminderMode = LoanReminderMode.UNTIL_RETURNED,
        notes = "With the spare battery", createdAt = 100L, updatedAt = 150L,
    )

    private suspend fun twoAssets() {
        assets.upsert(Asset(id = AssetId("a1"), name = "Example Drill", createdAt = 1L, updatedAt = 1L))
        assets.upsert(Asset(id = AssetId("a2"), name = "Example Ladder", createdAt = 1L, updatedAt = 1L))
    }

    @Test
    fun aLoanRoundTripsFieldForFieldInItsOrders() = runTest {
        twoAssets()
        val nameOnly = loanOf("l3", lentOn = "2026-09-22", link = null).copy(dueOn = null, reminderMode = LoanReminderMode.NONE, notes = "")
        listOf(loanOf("l1", returnedOn = "2026-09-21"), loanOf("l2", lentOn = "2026-08-01", returnedOn = "2026-08-02"), nameOnly, loanOf("l4", assetId = "a2"))
            .forEach { loans.upsert(it) }

        assertEquals("stored as written, the null link included", nameOnly, loans.get(AssetLoanId("l3")))
        assertEquals("newest lent first", listOf("l3", "l1", "l2"), loans.forAsset(AssetId("a1")).map { it.id.value })
        assertEquals(listOf("l3", "l1", "l2"), loans.observeForAsset(AssetId("a1")).first().map { it.id.value })
        assertEquals(nameOnly, loans.openFor(AssetId("a1")))
        assertEquals(listOf("l3", "l4"), loans.open().map { it.id.value })
        assertEquals(listOf("l3", "l4"), loans.observeOpen().first().map { it.id.value })
        assertEquals(listOf("l1", "l2", "l3", "l4"), loans.all().map { it.id.value })
        assertNull(loans.get(AssetLoanId("l9")))
    }

    @Test
    fun aSecondOpenRowForOneAssetAbortsAtTheIndex() = runTest {
        twoAssets()
        loans.upsert(loanOf("l1"))

        val refused = runCatching { loans.upsert(loanOf("l2")) }

        assertTrue("the index refuses a second open loan for a1: ${refused.exceptionOrNull()}", refused.exceptionOrNull() is SQLiteException)
        assertEquals("the store keeps the one open loan", listOf("l1"), loans.all().map { it.id.value })
        loans.upsert(loanOf("l3", assetId = "a2"))
        assertEquals("another asset's open loan is its own", listOf("l1", "l3"), loans.open().map { it.id.value })
    }

    @Test
    fun returnedRowsForOneAssetCoexist() = runTest {
        twoAssets()
        loans.upsert(loanOf("l1", lentOn = "2026-06-01", returnedOn = "2026-06-10"))
        loans.upsert(loanOf("l2", lentOn = "2026-07-01", returnedOn = "2026-07-10"))
        loans.upsert(loanOf("l3", lentOn = "2026-08-01", returnedOn = "2026-08-10"))
        loans.upsert(loanOf("l4", lentOn = "2026-09-01"))

        assertEquals(listOf("l4", "l3", "l2", "l1"), loans.forAsset(AssetId("a1")).map { it.id.value })
        assertEquals(listOf("l4"), loans.open().map { it.id.value })
    }

    @Test
    fun theMarkerIsOneOpenAndNullReturned() = runTest {
        twoAssets()
        loans.upsert(loanOf("l1"))
        assertEquals(1, db.assetLoanDao().byId("l1")!!.openMarker)

        loans.upsert(loanOf("l1", returnedOn = "2026-09-23"))
        assertNull("a return clears the marker in place", db.assetLoanDao().byId("l1")!!.openMarker)
        assertEquals(loanOf("l1", returnedOn = "2026-09-23"), loans.get(AssetLoanId("l1")))

        loans.upsert(loanOf("l2"))
        assertEquals("the next loan is open beside the returned one", 1, db.assetLoanDao().byId("l2")!!.openMarker)
    }

    @Test
    fun deletingTheAssetCascadesItsLoans() = runTest {
        twoAssets()
        loans.upsert(loanOf("l1", returnedOn = "2026-09-21"))
        loans.upsert(loanOf("l2", lentOn = "2026-09-22"))
        loans.upsert(loanOf("l3", assetId = "a2"))

        assets.delete(AssetId("a1"))

        assertEquals("an open loan and its history go with their asset", listOf("l3"), loans.all().map { it.id.value })
    }

    /**
     * The #79 lesson (a REPLACE deletes the row first, and its children's CASCADE goes with it): the asset
     * written again and a loan written again leave every loan where it was, because both upserts are
     * update-then-insert.
     */
    @Test
    fun rewritingTheAssetOrALoanKeepsEveryLoan() = runTest {
        twoAssets()
        val before = listOf(loanOf("l1", returnedOn = "2026-09-21"), loanOf("l2", lentOn = "2026-09-22"))
        before.forEach { loans.upsert(it) }

        assets.upsert(Asset(id = AssetId("a1"), name = "Example Drill, renamed", createdAt = 1L, updatedAt = 9L))
        loans.upsert(before[1].copy(notes = "Charger too", updatedAt = 200L))

        assertEquals(listOf("l2", "l1"), loans.forAsset(AssetId("a1")).map { it.id.value })
        assertEquals("Charger too", loans.get(AssetLoanId("l2"))!!.notes)
    }

    /**
     * C2 (ii): through the real use cases over Room, a second lend is the use case's `AssetAlreadyLent` —
     * read in the same write transaction — and never the index's constraint error; a return then frees
     * the asset for the next loan.
     */
    @Test
    fun aSecondLendOverRoomIsAssetAlreadyLentNeverAConstraintError() = runTest {
        twoAssets()
        var n = 0
        val today = Today { LocalDate.parse("2026-09-24") }
        val lend = LendAsset(assets, loans, RoomUnitOfWork(db), IdGenerator { "loan-${++n}" }, Clock { 500L }, today)
        val giveBack = ReturnLoan(loans, RoomUnitOfWork(db), Clock { 600L }, today)
        val terms = LoanTerms("2026-09-20", "2026-10-04", LoanReminderMode.ONCE, "")
        val first = lend.run(AssetId("a1"), "Sample Borrower", terms)

        val refused = runCatching { lend.run(AssetId("a1"), "Example Rentals Ltd", terms) }

        assertTrue("${refused.exceptionOrNull()}", refused.exceptionOrNull() is AssetAlreadyLent)
        assertEquals(first.id, (refused.exceptionOrNull() as AssetAlreadyLent).openLoanId)
        assertEquals(listOf(first), loans.all())

        giveBack.run(first.id, "2026-09-23")
        val next = lend.run(AssetId("a1"), "Example Rentals Ltd", terms)
        assertEquals(next, loans.openFor(AssetId("a1")))
        assertEquals(2, loans.forAsset(AssetId("a1")).size)
    }
}
