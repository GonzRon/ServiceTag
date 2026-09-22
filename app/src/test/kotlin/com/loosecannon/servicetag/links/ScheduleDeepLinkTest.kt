package com.loosecannon.servicetag.links

import com.loosecannon.servicetag.core.links.DeepLink
import com.loosecannon.servicetag.core.links.DeepLinkRoute
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.nfc.TagPayload
import com.loosecannon.servicetag.reminders.sourceFile
import com.loosecannon.servicetag.routeForDeepLink
import com.loosecannon.servicetag.ui.nav.Route
import com.loosecannon.servicetag.ui.scan.TagResultWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `servicetag://schedule/<uuid>` from the app's side: what it navigates to, and that navigating is
 * **all** it does (invariant 57).
 *
 * The parser's shape rules are `DeepLinkRouteTest`'s — one canonical uuid, checked by the same
 * `single()` the two shipped hosts use. What is here is the half that lives in `:app`: the link to
 * route mapping, the manifest filter that lets the link arrive at all, and the absence of any
 * mutation on the way.
 */
class ScheduleDeepLinkTest {

    private val id = "123e4567-e89b-12d3-a456-426614174000"

    /**
     * The link **navigates**: it names a destination and writes nothing. `routeForDeepLink` is the
     * whole of what a `servicetag://` link may do — a pure function with no port in reach, which is
     * why invariant 57 is a property of the type here rather than a reviewer's reading.
     */
    @Test
    fun theScheduleLinkNavigatesToTheScheduleDetail() {
        val link = DeepLinkRoute.parse("servicetag", "schedule", listOf(id))

        assertEquals(DeepLink.Schedule(ScheduleId(id)), link)
        assertEquals(Route.ScheduleDetail(id), routeForDeepLink(link))
    }

    /** The two shipped links are routed exactly as they were: the new host is additive. */
    @Test
    fun theShippedLinksAreRoutedAsTheyWere() {
        assertEquals(Route.AssetDetail(id), routeForDeepLink(DeepLink.Asset(AssetId(id))))
        assertEquals(
            Route.TagResult(TagResultWire.formatOf(TagPayload.V1(TagId(id))), id),
            routeForDeepLink(DeepLinkRoute.parse("servicetag", "tag", listOf(id))),
        )
    }

    /** A malformed link, and no link at all, each name **no** destination — and never an exception. */
    @Test
    fun aMalformedLinkNamesNoDestination() {
        assertNull(routeForDeepLink(DeepLinkRoute.parse("servicetag", "schedule", listOf("nope"))))
        assertNull(routeForDeepLink(DeepLinkRoute.parse("servicetag", "schedule", emptyList())))
        assertNull(routeForDeepLink(DeepLinkRoute.parse("servicetag", "group", listOf(id))))
        assertNull(routeForDeepLink(null))
    }

    /**
     * Invariant 57, structurally: the one file that turns an intent into a route names **no**
     * mutating operation. A link handler that "helpfully" completed a due schedule would turn a URL
     * into a write, which is precisely what #19's navigation-only contract forbids.
     */
    @Test
    fun theLinkHandlerNamesNoMutation() {
        val source = sourceFile("kotlin/com/loosecannon/servicetag/MainActivity.kt").readText()

        listOf(
            "CompleteSchedule",
            "CompleteGroupMembers",
            "ReminderSnooze",
            "PostponeSchedule",
            "CloseRound",
            "upsert",
            "delete",
        ).forEach { forbidden ->
            assertTrue("MainActivity must not name $forbidden (invariant 57)", forbidden !in source)
        }
    }

    /**
     * The manifest half: the launcher activity answers the new host, the shipped filter is the one
     * it was added to rather than a second one, and **no `group` host exists** (D-17) — a
     * `servicetag://` host is a permanent contract and 1.2 has no consumer for that one.
     */
    @Test
    fun theManifestAnswersTheScheduleHostAndNoGroupHost() {
        val manifest = sourceFile("AndroidManifest.xml").readText()

        assertEquals(
            "one schedule data line",
            1,
            manifest.lines().count { """android:host="schedule"""" in it },
        )
        assertEquals(
            "and no group host, ever",
            0,
            manifest.lowercase().lines().count { """android:host="group"""" in it },
        )
        assertEquals(
            "added to the shipped VIEW filter, not to a second one",
            7,
            manifest.lines().count { "<intent-filter>" in it },
        )
        assertEquals(
            "three servicetag hosts, all on the one filter",
            3,
            manifest.lines().count { """android:scheme="servicetag"""" in it },
        )
        assertTrue(
            "the deep-link filter still carries all three hosts",
            listOf("asset", "tag", "schedule").all { """android:host="$it"""" in manifest },
        )
    }
}
