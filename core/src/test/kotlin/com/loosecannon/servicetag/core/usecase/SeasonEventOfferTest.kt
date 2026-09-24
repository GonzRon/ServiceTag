package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.EventSource
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.schedule.SeasonPhase
import com.loosecannon.servicetag.core.testing.dayMillis
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/**
 * The season event offer (spec §3.3; master plan §7.2; inv. 93): `seasonOfferFor` offers START or END
 * only where accepting would be allowed, `AcceptSeasonOffer` clamps and links, and a journal event on
 * its own never changes a phase. Today is 2026-06-10 throughout.
 */
class SeasonEventOfferTest {

    private val a1 = AssetId("a1")
    private val today = LocalDate.parse("2026-06-10")

    private fun event(id: String, kind: EventKind, on: String, assetId: String = "a1"): AssetEvent = AssetEvent(
        id = EventId(id),
        assetId = AssetId(assetId),
        kind = kind,
        title = "Opened for the summer",
        profileId = null,
        occurredOn = on,
        occurredTime = null,
        tzId = "UTC",
        notes = "",
        source = EventSource.MANUAL,
        sourceRef = null,
        createdAt = dayMillis(on),
        updatedAt = dayMillis(on),
        measurements = emptyList(),
        consumables = emptyList(),
    )

    @Test
    fun anOfferOnlyInTheOppositePhaseOfAManualAsset() = runTest {
        val h = SeasonCommandHarness()
        val manual = h.asset(mode = SeasonMode.MANUAL)
        val start = event("e1", EventKind.SEASON_START, "2026-06-10")
        val end = event("e2", EventKind.SEASON_END, "2026-06-10")

        // No row: out of season. A season start is offered; a season end is not.
        assertEquals(SeasonAction.START, seasonOfferFor(manual, emptyList(), start, today))
        assertNull(seasonOfferFor(manual, emptyList(), end, today))

        // In season after a START: the other way round.
        h.activation("act-1", SeasonAction.START, "2026-05-01")
        assertEquals(SeasonAction.END, seasonOfferFor(manual, h.rows(), end, today))
        assertNull(seasonOfferFor(manual, h.rows(), start, today))

        // Any other kind of event, another asset's event, and every other mode: no offer.
        assertNull(seasonOfferFor(manual, h.rows(), event("e3", EventKind.MAINTENANCE, "2026-06-10"), today))
        assertNull(seasonOfferFor(manual, h.rows(), event("e4", EventKind.SEASON_END, "2026-06-10", assetId = "a2"), today))
        val calendarOut = h.asset(id = "c1", mode = SeasonMode.CALENDAR, seasonStart = "09-01", seasonEnd = "03-31")
        assertNull(seasonOfferFor(calendarOut, emptyList(), event("e5", EventKind.SEASON_START, "2026-06-10", assetId = "c1"), today))
        val yearRound = h.asset(id = "y1")
        assertNull(seasonOfferFor(yearRound, emptyList(), event("e6", EventKind.SEASON_END, "2026-06-10", assetId = "y1"), today))
    }

    /** The row is dated on the event's day clamped to `[the latest row's date, today]`, and linked to it. */
    @Test
    fun acceptingClampsTheDateAndLinksTheEvent() = runTest {
        val h = SeasonCommandHarness()
        h.asset(mode = SeasonMode.MANUAL)
        h.activation("act-1", SeasonAction.START, "2026-03-01")
        h.activation("act-2", SeasonAction.END, "2026-05-01")

        val backdated = event("e1", EventKind.SEASON_START, "2026-04-20")
        h.events.rows["e1"] = backdated
        val first = h.acceptSeasonOffer.run(a1, backdated, SeasonAction.START)
        assertEquals("2026-05-01" to EventId("e1"), first.occurredOn to first.eventId, "raised to the latest row's date")

        val inRange = event("e2", EventKind.SEASON_END, "2026-05-20")
        h.events.rows["e2"] = inRange
        val second = h.acceptSeasonOffer.run(a1, inRange, SeasonAction.END)
        assertEquals("2026-05-20" to EventId("e2"), second.occurredOn to second.eventId, "kept when in range")

        val ahead = event("e3", EventKind.SEASON_START, "2026-06-20")
        h.events.rows["e3"] = ahead
        val third = h.acceptSeasonOffer.run(a1, ahead, SeasonAction.START)
        assertEquals("2026-06-10" to EventId("e3"), third.occurredOn to third.eventId, "lowered to today")
        assertEquals(SeasonPhase.IN_SEASON, h.getAssetSeason.run(a1).phase)
    }

    /**
     * The row's action is the event's own, never the caller's say-so, and the phase it is checked against
     * is the one stored when the accept runs: an END event cannot be accepted as a START, a journal note
     * cannot be accepted at all, and a second accept of one START meets the stored START.
     */
    @Test
    fun acceptingWritesTheEventsOwnActionAgainstTheStoredPhase() = runTest {
        val h = SeasonCommandHarness()
        h.asset(mode = SeasonMode.MANUAL)
        val end = event("e1", EventKind.SEASON_END, "2026-06-10")
        val note = event("e2", EventKind.MAINTENANCE, "2026-06-10")
        val start = event("e3", EventKind.SEASON_START, "2026-06-10")
        listOf(end, note, start).forEach { h.events.rows[it.id.value] = it }

        val mismatched = assertFailsWith<IllegalArgumentException> { h.acceptSeasonOffer.run(a1, end, SeasonAction.START) }
        assertEquals(IllegalArgumentException::class, mismatched::class, "a programmer error, not a refusal code")
        assertEquals(
            IllegalArgumentException::class,
            assertFailsWith<IllegalArgumentException> { h.acceptSeasonOffer.run(a1, note, SeasonAction.START) }::class,
        )
        assertEquals(emptyList(), h.rows(), "nothing written")

        h.acceptSeasonOffer.run(a1, start, SeasonAction.START)
        assertFailsWith<SeasonAlreadyStarted> { h.acceptSeasonOffer.run(a1, start, SeasonAction.START) }
        assertEquals(listOf(SeasonAction.START to start.id), h.rows().map { it.action to it.eventId })
    }

    /** Inv. 93: logging a season event writes no activation; only accepting the offer does. */
    @Test
    fun noEventChangesAPhaseByItself() = runTest {
        val h = SeasonCommandHarness()
        h.asset(mode = SeasonMode.MANUAL)

        val logged = h.logEvent.run(
            EventCommand(
                assetId = a1, profileId = null, kind = EventKind.SEASON_START, title = "Opened",
                occurredOn = "2026-06-10", occurredTime = null, tzId = "UTC", notes = "",
                values = emptyMap(), consumables = emptyList(),
            ),
        )

        assertEquals(emptyList(), h.rows(), "the event wrote no activation")
        assertEquals(SeasonPhase.OUT_OF_SEASON, h.getAssetSeason.run(a1).phase)
        assertEquals(SeasonAction.START, seasonOfferFor(h.stored(), h.rows(), logged, today), "it is only offered")
        assertEquals(emptyList(), h.rows(), "and offering writes nothing either")

        h.acceptSeasonOffer.run(a1, logged, SeasonAction.START)
        assertEquals(listOf(SeasonAction.START to logged.id), h.rows().map { it.action to it.eventId })
        assertEquals(SeasonPhase.IN_SEASON, h.getAssetSeason.run(a1).phase)
    }
}
