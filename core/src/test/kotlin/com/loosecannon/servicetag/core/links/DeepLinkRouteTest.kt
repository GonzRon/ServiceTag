package com.loosecannon.servicetag.core.links

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.nfc.TagPayload
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeepLinkRouteTest {
    private val id = "123e4567-e89b-12d3-a456-426614174000"
    @Test fun assetRoute() { assertEquals(DeepLink.Asset(AssetId(id)), DeepLinkRoute.parse("servicetag", "asset", listOf(id))) }
    /** 2.6 — the `link` host is withdrawn; it is now simply not a route this app answers. */
    @Test fun theLinkHostIsNoLongerOurs() {
        assertNull(DeepLinkRoute.parse("servicetag", "link", listOf(id)))
        assertNull(DeepLinkRoute.parse("servicetag", "link", emptyList()))
    }
    @Test fun tagRouteDelegatesToTagRoute() { assertEquals(DeepLink.Tag(TagPayload.V1(TagId(id))), DeepLinkRoute.parse("servicetag", "tag", listOf(id))) }
    @Test fun otherSchemesAndHostsAreNotOurs() {
        assertNull(DeepLinkRoute.parse("https", "asset", listOf(id)))
        assertNull(DeepLinkRoute.parse("servicetag", "health", emptyList()))   // Phase 3 route, not yet
        assertNull(DeepLinkRoute.parse(null, null, emptyList()))
    }
    @Test fun badIdsAreMalformedNeverExceptions() {
        assertIs<DeepLink.Malformed>(DeepLinkRoute.parse("servicetag", "asset", emptyList()))
        assertIs<DeepLink.Malformed>(DeepLinkRoute.parse("servicetag", "asset", listOf("nope")))
        assertIs<DeepLink.Malformed>(DeepLinkRoute.parse("servicetag", "asset", listOf(id, "schedule", id)))  // 1C: schedules not routed yet
        assertIs<DeepLink.Malformed>(DeepLinkRoute.parse("servicetag", "tag", listOf("nope")))
    }

    /** 1.2, D-17: one canonical uuid, one schedule, and nothing else added with it. */
    @Test fun scheduleRoute() {
        assertEquals(
            DeepLink.Schedule(ScheduleId(id)),
            DeepLinkRoute.parse("servicetag", "schedule", listOf(id)),
        )
    }

    /**
     * The new host is shape-checked by the **same** `single()` the two shipped hosts use, so a
     * non-canonical uuid, an extra segment, a missing segment and a foreign scheme each land where
     * they land for `asset` — a second, looser check is how a malformed id reaches a repository.
     */
    @Test fun aMalformedScheduleLinkFailsExactlyAsTheShippedHostsDo() {
        assertIs<DeepLink.Malformed>(DeepLinkRoute.parse("servicetag", "schedule", emptyList()))
        assertIs<DeepLink.Malformed>(DeepLinkRoute.parse("servicetag", "schedule", listOf("nope")))
        assertIs<DeepLink.Malformed>(DeepLinkRoute.parse("servicetag", "schedule", listOf(id.uppercase())))
        assertIs<DeepLink.Malformed>(DeepLinkRoute.parse("servicetag", "schedule", listOf(id, id)))
        assertNull(DeepLinkRoute.parse("https", "schedule", listOf(id)))
    }

    /**
     * D-17, structurally: **no `group` host exists**, in the parser or in its own source. No 1.2
     * surface needs one — Maintenance reaches every group in-app — and a host added now would be a
     * permanent `servicetag://` contract with no consumer. The source scan is what stops a later
     * brief adding the branch and nothing failing.
     */
    @Test fun thereIsNoGroupHostAndTheScheduleHostIsTheOnlyOneAdded() {
        assertNull(DeepLinkRoute.parse("servicetag", "group", listOf(id)))
        assertNull(DeepLinkRoute.parse("servicetag", "group", emptyList()))

        val source = deepLinkRouteSource().readText()
        assertTrue(
            "\"group\"" !in source.lowercase(),
            "DeepLinkRoute must never declare a group host (D-17) — the gate's own anchored grep",
        )
        assertEquals(
            1,
            source.lines().count { "\"schedule\"" in it },
            "the schedule host is declared exactly once",
        )
        assertEquals(
            1,
            source.lines().count { "Regex(" in it },
            "one shape regex for every host, never a second (the gate's anchored grep)",
        )
    }

    /** Gradle runs `:core`'s tests with the module directory as the working directory; an IDE may use the root. */
    private fun deepLinkRouteSource(): File {
        val relative = "src/main/kotlin/com/loosecannon/servicetag/core/links/DeepLinkRoute.kt"
        return listOf(File(relative), File("core/$relative")).firstOrNull { it.isFile }
            ?: error("cannot find $relative from ${File(".").absolutePath}")
    }
}
