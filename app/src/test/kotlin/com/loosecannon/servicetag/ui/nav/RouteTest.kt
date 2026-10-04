package com.loosecannon.servicetag.ui.nav

import androidx.navigation3.runtime.NavKey
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.ui.condition.PendingCondition
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `TopLevelRoutes` is what the bottom bar iterates over (2B-2, D12 §16 correction; 1.2's third
 * member): Scan is still not in the list, so a regression that re-adds it — or reorders the three
 * that are there — shows up here on the JVM without a device.
 */
class RouteTest {

    @Test fun topLevelRoutesIsDashboardThenAssetsThenMaintenance() {
        assertEquals(listOf(Route.Dashboard, Route.Assets, Route.Maintenance), TopLevelRoutes)
    }

    /** The stack a deep link leaves behind: a plain list, exactly as the shell's collector hands it. */
    private fun afterLink(link: Route, vararg stack: NavKey): List<NavKey> =
        mutableListOf<NavKey>(*stack).apply { openDeepLink(link) }

    /**
     * #87 (C6): a top-level link — the summary body's Dashboard — replaces the stack as its tab
     * does, so a cold start's `[Dashboard]` never gains a second, identical key. Every other link is
     * pushed exactly as shipped.
     */
    @Test fun aTopLevelLinkReplacesTheStackAndEveryOtherLinkIsPushed() {
        assertEquals(listOf(Route.Dashboard), afterLink(Route.Dashboard, Route.Dashboard))
        assertEquals(listOf(Route.Dashboard), afterLink(Route.Dashboard, Route.Assets, Route.AssetDetail("a1")))
        assertEquals(
            listOf(Route.Dashboard),
            afterLink(Route.Dashboard, Route.Maintenance, Route.ScheduleDetail("s1"), Route.ScheduleEdit("s1")),
        )

        listOf(
            Route.ScheduleDetail("s1"),
            Route.ScheduleDetail("s1", complete = true),
            Route.AssetDetail("a1"),
            Route.TagResult("V1", "k1"),
        ).forEach { link ->
            assertEquals("$link is pushed", listOf(Route.Dashboard, link), afterLink(link, Route.Dashboard))
        }
    }

    /**
     * #87 (C6, R87-2 (b)): a summary tap must not circumvent the protections the four write flows
     * build in. With any of them anywhere on the stack — on top or buried, idle or writing — the
     * Dashboard link is ignored and the stack is left exactly as it was: no reset, no second
     * Dashboard. Without one, it resets as its tab does; and with one, the asset, tag and schedule
     * links still push, because a push pops nothing.
     */
    @Test fun aSummaryLinkNeverResetsAStackHoldingAWriteFlow() {
        listOf(
            Route.TransferImport("c1"),
            Route.AssetEdit(null),
            Route.AssetEdit("a1"),
            Route.ReplaceAsset("a1"),
            Route.TransferAssets(),
        ).forEach { flow ->
            val onTop = arrayOf(Route.Dashboard, Route.AssetDetail("a1"), flow)
            assertEquals("$flow on top", onTop.toList(), afterLink(Route.Dashboard, *onTop))
            val buried = arrayOf(Route.Assets, flow, Route.AssetDetail("a1"))
            assertEquals("$flow buried", buried.toList(), afterLink(Route.Dashboard, *buried))
        }

        assertEquals(listOf(Route.Dashboard), afterLink(Route.Dashboard, Route.Assets, Route.AssetDetail("a1")))

        val editing = arrayOf<NavKey>(Route.Dashboard, Route.AssetEdit("a1"))
        listOf(Route.ScheduleDetail("s1"), Route.AssetDetail("a2"), Route.TagResult("V1", "k1")).forEach { link ->
            assertEquals("$link still pushes", editing.toList() + link, afterLink(link, *editing))
        }
    }

    /**
     * 2.6 — the write route still carries a target *kind* as a string, because a serialised back
     * stack can hold one written by 2.5. "link" is no longer a kind this app writes, and it is
     * refused here, at the boundary, rather than inside a screen that would have to decide what a
     * link write means.
     */
    @Test fun aLinkKindedWriteRouteIsNotSupported() {
        assertFalse(Route.WriteTag("link", "l1", null).isSupported())
        assertTrue(Route.WriteTag("asset", "a1", null).isSupported())
        assertTrue(Route.WriteTag("none", null, null).isSupported())
    }

    /**
     * 2.7 (#37) — reader mode belongs to the two screens that read tags, and is held while one of
     * them is on top. `TagResult` is not one of them: it reads nothing, it is where the ambient
     * trampoline lands, and the inspect screen no longer pushes it. Adding it here would put
     * reader mode on over an ambient result and release it again the moment the sheet opened its
     * asset — with the tag still on the phone, which is the dispatch this release removed.
     *
     * A "link" write route is not one of them either: the shell draws no screen for one and pops it
     * a frame later, so a hold over it would turn reader mode on and off with no sink ever
     * installed.
     *
     * Neither is the Developer API screen (1.1.0, #46): it holds a socket, not a tag, and adding it
     * here would turn reader mode on over a screen with no sink.
     *
     * Nor is Categories (#74): a list and a rename dialog, with nothing to read.
     *
     * Nor is any of 1.2's destinations. `Maintenance` and its four surfaces are lists,
     * `ScheduleDetail` and `ScheduleEdit` are a screen and a form, and `MaintenanceSheet` opens
     * *after* a read has resolved — the hold for that read belongs to `Route.Scan`. Adding one
     * would hold reader mode over a screen with no sink.
     */
    @Test fun onlyTheTagScreensHoldReaderMode() {
        assertTrue(Route.Scan.readsTags())
        assertTrue(Route.WriteTag("asset", "a1", null).readsTags())
        assertTrue(Route.WriteTag("none", null, null).readsTags())
        assertFalse(Route.WriteTag("link", "l1", null).readsTags())
        assertFalse(Route.TagResult("V1", "k").readsTags())
        assertFalse(Route.Dashboard.readsTags())
        assertFalse(Route.Assets.readsTags())
        assertFalse(Route.AssetDetail("a1").readsTags())
        assertFalse(Route.Settings.readsTags())
        assertFalse(Route.DeveloperApi.readsTags())
        assertFalse(Route.Categories.readsTags())
        assertFalse(Route.Maintenance.readsTags())
        assertFalse(Route.ScheduleDetail("s1").readsTags())
        assertFalse(Route.ScheduleEdit(null, targetAssetId = "a1").readsTags())
        assertFalse(Route.GroupDetail("g1").readsTags())
        assertFalse(Route.GroupEdit(null).readsTags())
        assertFalse(Route.ReminderHealth.readsTags())
        // #103 (1.7.1): the two pushed lists under the Maintenance tab's grouped section.
        assertFalse(Route.MaintenanceGroups.readsTags())
        assertFalse(Route.InstalledComponents.readsTags())
        assertFalse(Route.MaintenanceSheet("a1", "t1").readsTags())
    }

    /**
     * #82 (C7): the combined flow's Incident entry carries the held condition on its route, so a
     * restored back stack reopens the same entry and its Save still commits the held row's own id.
     */
    @Test fun anEventEntryWithAPendingConditionRoundTrips() {
        val held = PendingCondition("c-held", OperationalCondition.DOWN, "2026-04-15", "Will not start\nStarter clicks")
        val route = Route.EventEntry("a1", null, null, kind = "INCIDENT", pending = PendingConditionArgs.of(held))

        val stored = Json.encodeToString(Route.EventEntry.serializer(), route)
        val restored = Json.decodeFromString(Route.EventEntry.serializer(), stored)

        assertEquals(route, restored)
        assertEquals(held, restored.pending!!.toPending())
    }

    /** #82 (C7): a back stack stored before #82 has no `pending`; it decodes as before, with none. */
    @Test fun anEventEntryWithoutPendingDecodesAsBefore() {
        val stored = """{"assetId":"a1","profileId":null,"eventId":null,"kind":"INCIDENT"}"""

        val restored = Json.decodeFromString(Route.EventEntry.serializer(), stored)

        assertEquals(Route.EventEntry("a1", null, null, kind = "INCIDENT"), restored)
        assertNull(restored.pending)
        assertFalse(
            "an entry without one writes no pending key",
            "pending" in Json.encodeToString(Route.EventEntry.serializer(), Route.EventEntry("a1", "p1", null)),
        )
        assertNull(
            "an unknown condition name opens a plain entry",
            PendingConditionArgs("c1", "BROKEN", "2026-04-15", "").toPending(),
        )
    }

    /**
     * #79 (C20–C22; §3 row 46): the case screen and the case editor survive a stored back stack, and
     * P79-19 goes to the editor on the current failure's Incident — or, with none, to a new INCIDENT
     * entry first (R79-3). Neither case screen reads a tag.
     */
    @Test fun theServiceCaseRoutesRoundTrip() {
        val case = Route.ServiceCase("c1")
        assertEquals(case, Json.decodeFromString(Route.ServiceCase.serializer(), Json.encodeToString(Route.ServiceCase.serializer(), case)))
        listOf(Route.ServiceCaseEdit("a1", incidentId = "e1"), Route.ServiceCaseEdit("a1", caseId = "c1")).forEach { edit ->
            val stored = Json.encodeToString(Route.ServiceCaseEdit.serializer(), edit)
            assertEquals(edit, Json.decodeFromString(Route.ServiceCaseEdit.serializer(), stored))
            assertFalse("$edit reads no tag", edit.readsTags())
        }
        assertFalse(case.readsTags())

        assertEquals(Route.ServiceCaseEdit("a1", incidentId = "e1"), newServiceCaseRoute("a1", currentIncidentId = "e1"))
        assertEquals(
            "no current Incident: one is logged first, and its save hands over to the editor",
            Route.EventEntry("a1", null, null, kind = "INCIDENT", thenServiceCase = true),
            newServiceCaseRoute("a1", currentIncidentId = null),
        )
    }

    /**
     * #72 (C17; §3 row 34): the lend form's route keeps its asset and, on an edit, its loan through a
     * stored back stack; a new loan's route stores no loan, and it reads no tag.
     */
    @Test fun theLoanEditRouteRoundTrips() {
        listOf(Route.LoanEdit("a1"), Route.LoanEdit("a1", loanId = "l1")).forEach { route ->
            val stored = Json.encodeToString(Route.LoanEdit.serializer(), route)
            assertEquals(route, Json.decodeFromString(Route.LoanEdit.serializer(), stored))
            assertFalse("$route reads no tag", route.readsTags())
        }
        assertEquals(null, Route.LoanEdit("a1").loanId)
        assertFalse("a new loan's route stores no loan", Json.encodeToString(Route.LoanEdit.serializer(), Route.LoanEdit("a1")).contains("loanId"))
    }

    /**
     * #79 (C20): the entry that hands over to the case editor keeps that on a stored back stack; one
     * stored before #79 has no `thenServiceCase` and decodes as a plain entry, and a plain entry
     * writes no such key.
     */
    @Test fun anEventEntryThenServiceCaseRoundTripsAndAnOldOneDecodes() {
        val route = Route.EventEntry("a1", null, null, kind = "INCIDENT", thenServiceCase = true)
        val stored = Json.encodeToString(Route.EventEntry.serializer(), route)
        assertEquals(route, Json.decodeFromString(Route.EventEntry.serializer(), stored))

        val old = """{"assetId":"a1","profileId":null,"eventId":null,"kind":"INCIDENT"}"""
        val restored = Json.decodeFromString(Route.EventEntry.serializer(), old)
        assertFalse("an entry stored before #79 hands over to nothing", restored.thenServiceCase)
        assertFalse(
            "a plain entry writes no thenServiceCase key",
            "thenServiceCase" in Json.encodeToString(Route.EventEntry.serializer(), Route.EventEntry("a1", null, null)),
        )
    }
}
