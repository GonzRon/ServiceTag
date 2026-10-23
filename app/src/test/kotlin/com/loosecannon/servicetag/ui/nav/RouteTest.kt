package com.loosecannon.servicetag.ui.nav

import androidx.navigation3.runtime.NavKey
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.reminders.sourceFile
import com.loosecannon.servicetag.ui.condition.PendingCondition
import com.loosecannon.servicetag.ui.maintenance.CompletionOutcome
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

    /**
     * #117 RT1: a FORM schedule's hand-over keeps the schedule. `formEntry` is the one way a `NeedsForm` becomes a
     * route, and the entry it builds is a new one (no event), with no kind and no held condition — the two a
     * schedule-opened model refuses at construction (I5).
     */
    @Test fun aNeedsFormBecomesANewEntryCarryingItsSchedule() {
        assertEquals(
            Route.EventEntry("a1", "p1", null, scheduleId = "s1"),
            formEntry(CompletionOutcome.NeedsForm(ScheduleId("s1"), AssetId("a1"), ProfileId("p1"))),
        )

        val profileLess = formEntry(CompletionOutcome.NeedsForm(ScheduleId("s2"), AssetId("a1"), null))
        assertEquals(Route.EventEntry("a1", null, null, scheduleId = "s2"), profileLess)
        assertNull(profileLess.profileId)
        assertNull(profileLess.eventId)
        assertNull("no kind: the schedule's profile, else MAINTENANCE, decides", profileLess.kind)
        assertNull(profileLess.pending)
        assertFalse(profileLess.thenServiceCase)
    }

    /**
     * #117 RT2 (I10): a back stack stored by 1.8.0 has no `scheduleId`; it decodes as an ordinary entry. An ordinary
     * entry writes no such key, and one opened for a schedule keeps it through a stored back stack.
     */
    @Test fun anEventEntryForAScheduleRoundTripsAndA180OneDecodesAsBefore() {
        listOf(
            """{"assetId":"a1","profileId":"p1","eventId":null}""" to Route.EventEntry("a1", "p1", null),
            """{"assetId":"a1","profileId":null,"eventId":"e1"}""" to Route.EventEntry("a1", null, "e1"),
            """{"assetId":"a1","profileId":null,"eventId":null,"kind":"INCIDENT","thenServiceCase":true}""" to
                Route.EventEntry("a1", null, null, kind = "INCIDENT", thenServiceCase = true),
        ).forEach { (stored, expected) ->
            val restored = Json.decodeFromString(Route.EventEntry.serializer(), stored)
            assertEquals(stored, expected, restored)
            assertNull(stored, restored.scheduleId)
        }
        assertFalse(
            "an ordinary entry writes no scheduleId key",
            "scheduleId" in Json.encodeToString(Route.EventEntry.serializer(), Route.EventEntry("a1", "p1", null)),
        )

        val linked = Route.EventEntry("a1", "p1", null, scheduleId = "s1")
        val stored = Json.encodeToString(Route.EventEntry.serializer(), linked)
        assertTrue(stored, "\"scheduleId\":\"s1\"" in stored)
        assertEquals(linked, Json.decodeFromString(Route.EventEntry.serializer(), stored))
    }

    /**
     * #117 RT3 (I6): the root's three form hand-overs — the Log maintenance picker, the schedule detail and the scan
     * sheet — each open the form through `openForm`, never by hand and never with a plain push (F-1), and `openForm`
     * is the one place the root builds a route with `formEntry`. CI's only guard on the root wiring, read from the
     * source with anchored patterns: every line that opens an `onLogForm` lambda, and that lambda's body to its
     * closing brace. The detail's clears its arrival before the form opens, and its arrival's other ending clears it
     * too — the only two places `clearArrival` is called (§3.3).
     */
    @Test fun everyFormHandOverInTheRootGoesThroughFormEntry() {
        val source = sourceFile("kotlin/com/loosecannon/servicetag/ui/nav/ServiceTagRoot.kt").readText()
        val lines = source.lines()
        val opener = Regex("""^\s*onLogForm = \{""")
        val bodies = lines.indices.filter { opener.containsMatchIn(lines[it]) }.map { lambdaBody(lines, it) }

        assertEquals("the picker, the detail and the sheet", 3, bodies.size)
        bodies.forEach { body ->
            assertTrue(body, "backStack.openForm(" in body)
            assertFalse(body, "formEntry(" in body)
            assertFalse(body, "Route.EventEntry(" in body)
        }
        val openForm = Regex("""fun MutableList<NavKey>\.openForm\(form: CompletionOutcome\.NeedsForm\)""")
        assertEquals("one definition", 1, openForm.findAll(source).count())
        assertEquals("built in openForm alone", 1, Regex("""\bformEntry\(""").findAll(source).count())
        val built = lines.indexOfFirst { openForm.containsMatchIn(it) }
        assertTrue(lambdaBody(lines, built), "formEntry(form)" in lambdaBody(lines, built))

        val detail = bodies.filter { "clearArrival(" in it }
        assertEquals("only the detail's hand-over clears an arrival", 1, detail.size)
        val clear = detail.single().indexOf("backStack.clearArrival(key)")
        val push = detail.single().indexOf("backStack.openForm(")
        assertTrue(detail.single(), clear in 0 until push)

        val onEnded = Regex("""^\s*onArrivalEnded = \{ backStack\.clearArrival\(key\) },?$""")
        assertEquals("the arrival's other ending", 1, lines.count { onEnded.matches(it) })
        val calls = Regex("""\bclearArrival\(""").findAll(source).count()
        val definitions = Regex("""fun MutableList<NavKey>\.clearArrival\(""").findAll(source).count()
        assertEquals("one definition", 1, definitions)
        assertEquals("called from the detail's two handlers only", 2, calls - definitions)
    }

    /** The text from the `{` on [start]'s line to its matching `}`. */
    private fun lambdaBody(lines: List<String>, start: Int): String {
        val text = lines.drop(start).joinToString("\n")
        val open = text.indexOf('{')
        var depth = 0
        for (i in open until text.length) {
            if (text[i] == '{') depth++
            if (text[i] == '}' && --depth == 0) return text.substring(open, i + 1)
        }
        error("unclosed lambda at line ${start + 1}")
    }

    /**
     * #117 RT4 (§3.3): an arrival is used once. `clearArrival` turns that arrival's entry into the same schedule's
     * plain detail **in place** — same position, same size, nothing popped or pushed — and touches no other entry: the
     * same schedule's plain detail elsewhere, another schedule's arrival and everything else stay. Absent, or a key
     * that is no arrival, it does nothing.
     */
    @Test fun anArrivalIsClearedInPlaceAndNothingElseMoves() {
        val arrival = Route.ScheduleDetail("s1", complete = true)
        val stack = mutableListOf<NavKey>(
            Route.Maintenance,
            Route.ScheduleDetail("s1"),
            Route.ScheduleDetail("s2", complete = true),
            arrival,
        )
        stack.clearArrival(arrival)
        assertEquals(
            listOf(Route.Maintenance, Route.ScheduleDetail("s1"), Route.ScheduleDetail("s2", complete = true), Route.ScheduleDetail("s1")),
            stack,
        )

        val under = mutableListOf<NavKey>(Route.Dashboard, arrival, Route.AssetDetail("a1"))
        under.clearArrival(arrival)
        assertEquals(listOf(Route.Dashboard, Route.ScheduleDetail("s1"), Route.AssetDetail("a1")), under)

        val absent = listOf<NavKey>(Route.Dashboard, Route.ScheduleDetail("s2", complete = true), Route.ScheduleDetail("s1"))
        assertEquals("absent", absent, absent.toMutableList().apply { clearArrival(arrival) })

        val cleared = listOf<NavKey>(Route.Dashboard, arrival)
        assertEquals(
            "a key that is no arrival changes nothing",
            cleared,
            cleared.toMutableList().apply { clearArrival(Route.ScheduleDetail("s1")) },
        )
    }

    /**
     * #117 RT5 (F-1): a schedule's form is open once. Absent, `openForm` pushes it. Already on the stack, it returns
     * to it: it pops back to that entry, so everything above it goes — the arrival detail cleared on the way in among
     * it — and nothing is pushed, so saving cannot complete two occurrences. Another schedule's form, or an ordinary
     * entry on the same asset and profile, is a different key: the form is pushed and what is beneath stays.
     */
    @Test fun aSchedulesFormIsOpenOnceAndAnotherSchedulesIsPushed() {
        val blade = CompletionOutcome.NeedsForm(ScheduleId("s1"), AssetId("a1"), ProfileId("p1"))
        val deck = CompletionOutcome.NeedsForm(ScheduleId("s2"), AssetId("a1"), ProfileId("p1"))

        val absent = mutableListOf<NavKey>(Route.Dashboard, Route.ScheduleDetail("s1"))
        absent.openForm(blade)
        assertEquals(listOf(Route.Dashboard, Route.ScheduleDetail("s1"), formEntry(blade)), absent)

        // The reminder's Done again over the open form: its arrival is cleared in place, then the form is returned to.
        val arrival = Route.ScheduleDetail("s1", complete = true)
        val again = mutableListOf<NavKey>(Route.Dashboard, Route.ScheduleDetail("s1"), formEntry(blade), arrival)
        again.clearArrival(arrival)
        again.openForm(blade)
        assertEquals("no twin", listOf(Route.Dashboard, Route.ScheduleDetail("s1"), formEntry(blade)), again)

        // A re-scan's sheet over it: the sheet goes as well.
        val rescan = mutableListOf<NavKey>(Route.Dashboard, formEntry(blade), Route.MaintenanceSheet("a1", "t1"))
        rescan.openForm(blade)
        assertEquals(listOf(Route.Dashboard, formEntry(blade)), rescan)

        val onTop = mutableListOf<NavKey>(Route.Dashboard, formEntry(blade))
        onTop.openForm(blade)
        assertEquals("on top already: nothing moves", listOf(Route.Dashboard, formEntry(blade)), onTop)

        val other = mutableListOf<NavKey>(Route.Dashboard, formEntry(blade), Route.MaintenanceSheet("a1"))
        other.openForm(deck)
        assertEquals(listOf(Route.Dashboard, formEntry(blade), Route.MaintenanceSheet("a1"), formEntry(deck)), other)

        val ordinary = Route.EventEntry("a1", "p1", null)
        val unlinked = mutableListOf<NavKey>(Route.Dashboard, Route.AssetDetail("a1"), ordinary)
        unlinked.openForm(blade)
        assertEquals(listOf(Route.Dashboard, Route.AssetDetail("a1"), ordinary, formEntry(blade)), unlinked)
    }
}
