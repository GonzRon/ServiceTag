package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleRole
import com.loosecannon.servicetag.core.season.NotReadyReason
import com.loosecannon.servicetag.core.season.Readiness
import com.loosecannon.servicetag.core.testing.completionOf
import com.loosecannon.servicetag.core.testing.dayMillis
import com.loosecannon.servicetag.core.testing.scheduleOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * #114 (A2; C7, C10, C12, B; rows 21, 21a) — what a role and an event's `imported` marker survive: a full-replace
 * schedule save keeps the role, a Replace asset copy never inherits one, the role moves no computed due, an edit
 * never clears the marker, and clearing and setting the role again never re-admits an imported service.
 */
class ScheduleRoleSurvivesTest {
    private val h = SeasonStartHarness()

    // ---- Row 21 --------------------------------------------------------------------------------------------------

    @Test fun aFullReplaceSaveKeepsTheRole() = runTest {
        h.asset("heater")
        val s1 = h.schedule("s1", title = "Annual service", role = ScheduleRole.SEASON_START, since = "2026-09-01")

        val saved = h.saveSchedule.run(ScheduleId("s1"), h.commandOf(s1).copy(title = "Annual service and clean", leadDays = 21))

        assertEquals(ScheduleRole.SEASON_START, saved.role, "K11: the form carries no role, and the save keeps it")
        assertEquals("2026-09-01", saved.roleSinceOn)
        assertEquals(saved, h.stored("s1"))
    }

    @Test fun aReplaceAssetCopyHasNoRole() = runTest {
        val r = ReplaceHarness()
        r.put(r.assetRow(PRED, "Example Water Heater"))
        r.put(
            scheduleOf("s-flush", assetId = PRED, title = "Annual flush", timeInterval = 1, timeUnit = RecurrenceUnit.YEAR, anchorOn = "2025-03-01")
                .copy(role = ScheduleRole.SEASON_START, roleSinceOn = "2026-09-01"),
        )

        val result = r.replaceWith(r.draft(scheduleIds = setOf("s-flush"), scheduleStartOn = "2026-09-15"))
        val copy = r.copiesFor(result.successor.id).single()

        assertEquals(ScheduleRole.NONE, copy.role, "#114 point 6: a successor never inherits the role")
        assertNull(copy.roleSinceOn, "nor its boundary")
    }

    @Test fun theRoleChangesNoComputedDue() = runTest {
        h.asset("heater")
        h.schedule("s1")
        h.raw.events.rows["e1"] = completionOf("e1", occurredOn = "2026-03-10", occurrenceOn = "2026-01-15", assetId = "heater")
        h.recompute.forSchedule(ScheduleId("s1"))
        val before = h.states.rows.getValue("s1")

        h.setScheduleRole.run(ScheduleId("s1"), ScheduleRole.SEASON_START, sinceOn = "2026-09-01")
        h.recompute.forSchedule(ScheduleId("s1"))
        assertEquals(before, h.states.rows.getValue("s1"), "C7: the engine never reads the role")

        h.setScheduleRole.run(ScheduleId("s1"), ScheduleRole.NONE)
        h.recompute.forSchedule(ScheduleId("s1"))
        assertEquals(before, h.states.rows.getValue("s1"))
    }

    // ---- Row 21a — the imported marker (B) --------------------------------------------------------------------------

    private fun importedService(id: String, on: String) = completionOf(id, occurredOn = on, occurrenceOn = null, assetId = "heater")
        .copy(imported = true, updatedAt = dayMillis(on) + 1_000L)

    @Test fun anEditOfAnImportedEventKeepsTheMarker() = runTest {
        h.asset("heater")
        h.schedule("s1")
        val stored = importedService("e1", "2026-10-01")
        h.raw.events.rows["e1"] = stored

        val edited = h.updateEvent.run(
            EventId("e1"),
            EventCommand(
                assetId = AssetId("heater"), profileId = null, kind = EventKind.MAINTENANCE, title = stored.title,
                occurredOn = stored.occurredOn, occurredTime = null, tzId = "UTC", notes = "Cleaned the burner too",
                values = emptyMap(), consumables = emptyList(),
            ),
        )

        assertEquals("Cleaned the burner too", edited.notes)
        assertTrue(edited.imported, "an edit never clears the marker")
        assertTrue(h.raw.events.rows.getValue("e1").imported)
    }

    @Test fun clearingAndSettingTheRoleAgainNeverReAdmitsAnImportedService() = runTest {
        h.asset("heater")
        h.schedule("s1", role = ScheduleRole.SEASON_START, since = "2026-09-01")
        h.raw.events.rows["e1"] = importedService("e1", "2026-10-01")
        val first = h.readiness("heater")
        assertIs<Readiness.NotReady>(first)
        assertEquals(NotReadyReason.INHERITED_SERVICE_ONLY, first.reason)

        h.setScheduleRole.run(ScheduleId("s1"), ScheduleRole.NONE)
        h.setScheduleRole.run(ScheduleId("s1"), ScheduleRole.SEASON_START, sinceOn = "2026-09-01")

        val again = h.readiness("heater")
        assertIs<Readiness.NotReady>(again)
        assertEquals(NotReadyReason.INHERITED_SERVICE_ONLY, again.reason, "no role write touches an event")
        assertTrue(h.raw.events.rows.getValue("e1").imported)
    }

    @Test fun aNewCompletionIsNotImported() = runTest {
        h.asset("heater")
        h.schedule("s1")

        val done = h.completeSchedule.run(ScheduleId("s1"), CompletionCommand(occurredOn = ROLE_TODAY, tzId = "UTC"))
        assertFalse(done.imported)
        assertFalse(h.raw.events.rows.getValue(done.id.value).imported)

        val logged = h.logEvent.run(
            EventCommand(
                assetId = AssetId("heater"), profileId = null, kind = EventKind.NOTE, title = "Looked it over",
                occurredOn = ROLE_TODAY, occurredTime = null, tzId = "UTC", notes = "", values = emptyMap(),
                consumables = emptyList(),
            ),
        )
        assertFalse(logged.imported)
    }
}
