package com.loosecannon.servicetag.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertFalse(Route.Maintenance.readsTags())
        assertFalse(Route.ScheduleDetail("s1").readsTags())
        assertFalse(Route.ScheduleEdit(null, targetAssetId = "a1").readsTags())
        assertFalse(Route.GroupDetail("g1").readsTags())
        assertFalse(Route.GroupEdit(null).readsTags())
        assertFalse(Route.ReminderHealth.readsTags())
        assertFalse(Route.MaintenanceSheet("a1", "t1").readsTags())
    }
}
