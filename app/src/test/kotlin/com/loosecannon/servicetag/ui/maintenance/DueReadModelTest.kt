package com.loosecannon.servicetag.ui.maintenance

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.SeasonBehavior
import com.loosecannon.servicetag.core.schedule.DueStatus
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.CompletionCommand
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.groupOf
import com.loosecannon.servicetag.testing.meterDefinitionOf
import com.loosecannon.servicetag.testing.readingOf
import com.loosecannon.servicetag.testing.scheduleOf
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one projection behind the dashboard, the Maintenance shell, B09's sheet and `/v1/due`
 * (decision 27). Every case here writes real rows through the production repositories and lets the
 * real engine produce the `schedule_state` row, so "the status the dashboard shows" is the status
 * `statusOf` computes and never a value this test made up.
 *
 * `T` is 2026-04-15 throughout: far enough past the 2026-01-01 anchors that a quarterly schedule
 * has an unambiguous side of the line to be on.
 */
class DueReadModelTest {

    private val graph = FakeGraph().also { it.today = LocalDate.parse("2026-04-15") }

    @After fun tearDown() = graph.close()

    private fun readModel(): DueReadModel = DueReadModel(
        schedules = graph.schedules,
        states = graph.scheduleStates,
        assets = graph.assets,
        groups = graph.groups,
        definitions = graph.definitions,
        recompute = graph.recomputeSchedules,
        today = graph.todayPort,
        snoozedUntilOf = { null },
    )

    /** Writes the schedule and lets the engine derive its state, exactly as a save would. */
    private suspend fun seed(schedule: MaintenanceSchedule) {
        graph.schedules.upsert(schedule)
        graph.recomputeSchedules.forSchedule(schedule.id)
    }

    private suspend fun asset(name: String, category: String = "Yard", parent: AssetId? = null) =
        graph.createAsset.run(AssetCommand(name = name, category = category, parentAssetId = parent))

    // ---------------------------------------------------------------- a state with no home

    /**
     * Matrix row "a state with no home". All seven statuses at once: `OVERDUE`, `DUE` and the
     * repairable `NO_DATA` in ATTENTION, `DUE_SOON` in UPCOMING, `OK` in CURRENT,
     * `INACTIVE_SEASON` in OUT OF SEASON, and `PAUSED` in **no section at all** — while the paused
     * schedule is still in the projection, which is what lets Maintenance → Schedules list it
     * (master plan §11.1, T8).
     */
    @Test fun everyStatusLandsInItsOwnSectionAndPausedLandsInNone() = runTest {
        val mower = asset("Mower")
        // A season that is shut on 15 April: November through February.
        val blower = graph.createAsset.run(
            AssetCommand(name = "Snowblower", category = "Yard", seasonStartMmdd = "11-01", seasonEndMmdd = "02-28"),
        )

        seed(scheduleOf("s-overdue", assetId = mower.id.value, title = "Overdue one", anchorOn = "2026-01-01", leadDays = 0))
        seed(scheduleOf("s-due", assetId = mower.id.value, title = "Due one", anchorOn = "2026-04-15", timeInterval = 1, timeUnit = RecurrenceUnit.YEAR, leadDays = 0))
        seed(scheduleOf("s-soon", assetId = mower.id.value, title = "Soon one", anchorOn = "2026-04-20", timeInterval = 1, timeUnit = RecurrenceUnit.YEAR, leadDays = 14))
        seed(scheduleOf("s-ok", assetId = mower.id.value, title = "Ok one", anchorOn = "2026-12-01", timeInterval = 1, timeUnit = RecurrenceUnit.YEAR, leadDays = 0))
        // A meter rule with a definition but no baseline at all: neither a completion nor an anchor.
        graph.definitions.upsert(meterDefinitionOf("d-hours", assetId = mower.id.value))
        seed(
            scheduleOf(
                "s-nodata", assetId = mower.id.value, title = "No baseline one",
                timeInterval = null, timeUnit = null, anchorOn = null,
                meterDefinitionId = "d-hours", meterInterval = 100.0,
            ),
        )
        seed(
            scheduleOf(
                "s-season", assetId = blower.id.value, title = "Season one",
                anchorOn = "2026-01-01", seasonBehavior = SeasonBehavior.FOLLOW_ASSET,
            ),
        )
        seed(scheduleOf("s-paused", assetId = mower.id.value, title = "Paused one", status = ScheduleStatus.PAUSED))

        val items = readModel().items().associateBy { it.scheduleId.value }
        assertEquals(7, items.size)

        assertEquals(DueStatus.OVERDUE, items.getValue("s-overdue").status)
        assertEquals(AttentionSection.ATTENTION, items.getValue("s-overdue").section)
        assertEquals(DueStatus.DUE, items.getValue("s-due").status)
        assertEquals(AttentionSection.ATTENTION, items.getValue("s-due").section)
        // The repairable missing-meter-baseline NO_DATA is actionable and belongs in ATTENTION.
        assertEquals(DueStatus.NO_DATA, items.getValue("s-nodata").status)
        assertEquals(AttentionSection.ATTENTION, items.getValue("s-nodata").section)
        assertFalse("a meter baseline is missing, not a required set", items.getValue("s-nodata").requiredSetEmpty)

        assertEquals(DueStatus.DUE_SOON, items.getValue("s-soon").status)
        assertEquals(AttentionSection.UPCOMING, items.getValue("s-soon").section)
        assertEquals(DueStatus.OK, items.getValue("s-ok").status)
        assertEquals(AttentionSection.CURRENT, items.getValue("s-ok").section)
        assertEquals(DueStatus.INACTIVE_SEASON, items.getValue("s-season").status)
        assertEquals(AttentionSection.OUT_OF_SEASON, items.getValue("s-season").section)

        // PAUSED: present, so the Schedules list can carry it; in no dashboard section at all.
        assertEquals(DueStatus.PAUSED, items.getValue("s-paused").status)
        assertNull("a paused schedule needs nothing, so it is in no section", items.getValue("s-paused").section)
    }

    // ---------------------------------------------------------------- the order

    /**
     * Matrix row "a non-deterministic order". Two runs over the same rows produce identical `rank`
     * values, and two items sharing a due date break the tie by title and then by id — which is
     * what makes `rank` mean anything to `/v1/due`'s clients and to a repeatable test.
     */
    @Test fun rankIsTotalAndStableAcrossRuns() = runTest {
        val mower = asset("Mower")
        // Two overdue schedules on the same date, deliberately inserted title-descending.
        seed(scheduleOf("s-b", assetId = mower.id.value, title = "Zebra check", anchorOn = "2026-01-01", leadDays = 0))
        seed(scheduleOf("s-a", assetId = mower.id.value, title = "alpha check", anchorOn = "2026-01-01", leadDays = 0))
        seed(scheduleOf("s-soon", assetId = mower.id.value, title = "Soon", anchorOn = "2026-04-20", timeInterval = 1, timeUnit = RecurrenceUnit.YEAR))
        seed(scheduleOf("s-paused", assetId = mower.id.value, title = "Paused", status = ScheduleStatus.PAUSED))

        val first = readModel().items()
        val second = readModel().items()

        assertEquals(first.map { it.scheduleId.value }, second.map { it.scheduleId.value })
        assertEquals(first.map { it.rank }, second.map { it.rank })
        assertEquals(first.indices.toList(), first.map { it.rank })

        // Case-insensitively by title on an equal due date: "alpha check" before "Zebra check".
        assertEquals(
            listOf("s-a", "s-b", "s-soon", "s-paused"),
            first.map { it.scheduleId.value },
        )
    }

    /** Decision 30's third key: a null `effectiveDueOn` sorts last inside its own section. */
    @Test fun aRowWithNoDueDateSortsLastInsideItsSection() = runTest {
        val mower = asset("Mower")
        graph.definitions.upsert(meterDefinitionOf("d-hours", assetId = mower.id.value))
        seed(
            scheduleOf(
                "s-nodata", assetId = mower.id.value, title = "AAA no baseline",
                timeInterval = null, timeUnit = null, anchorOn = null,
                meterDefinitionId = "d-hours", meterInterval = 100.0,
            ),
        )
        seed(scheduleOf("s-overdue", assetId = mower.id.value, title = "ZZZ overdue", anchorOn = "2026-01-01", leadDays = 0))

        // Both are in ATTENTION. The dated one comes first although its title sorts later, because
        // the date is the second key and a null date is last.
        assertEquals(listOf("s-overdue", "s-nodata"), readModel().items().map { it.scheduleId.value })
    }

    // ---------------------------------------------------------------- the two kinds of NO_DATA

    /**
     * Matrix row "the two kinds of `NO_DATA` conflated", on a **component** asset. The repairable
     * meter-baseline form is promoted and counted; the empty-required-set form is in no section, is
     * not counted, and carries the flag each surface applies its own predicate to (invariant 74,
     * decision 27). The projection carries no single `isActionable` boolean.
     */
    @Test fun theTwoKindsOfNoDataAreNotTheSameRow() = runTest {
        val tub = asset("Hot tub", category = "Water")
        val pump = asset("Circulation pump", category = "Water", parent = tub.id)
        graph.definitions.upsert(meterDefinitionOf("d-hours", assetId = pump.id.value))

        seed(
            scheduleOf(
                "s-baseline", assetId = pump.id.value, title = "Meter baseline missing",
                timeInterval = null, timeUnit = null, anchorOn = null,
                meterDefinitionId = "d-hours", meterInterval = 100.0,
            ),
        )
        // A group with no open membership window at the round's open instant: the required set is
        // empty, which is not the same fact as a missing meter reading.
        graph.groups.upsert(groupOf("g-empty", name = "Emptied run", members = emptyList()))
        seed(scheduleOf("s-empty", assetId = null, groupId = "g-empty", title = "Empty group round", anchorOn = "2026-01-01"))

        val items = readModel().items().associateBy { it.scheduleId.value }

        val repairable = items.getValue("s-baseline")
        assertEquals(DueStatus.NO_DATA, repairable.status)
        assertEquals(AttentionSection.ATTENTION, repairable.section)
        assertFalse(repairable.requiredSetEmpty)
        assertEquals("Hot tub", repairable.parentName)

        val vacuous = items.getValue("s-empty")
        assertEquals(DueStatus.NO_DATA, vacuous.status)
        assertNull("emptiness is not actionable, so it has no section", vacuous.section)
        assertTrue(vacuous.requiredSetEmpty)
        assertFalse("and it is never counted as due", vacuous.countsAsDue)
        assertEquals(0, vacuous.membersRequired)
    }

    // ---------------------------------------------------------------- the group row

    /**
     * Matrix rows "a group row fanning out or double-counting" and "an empty group counted". Five
     * required members, three of them done, is **one** row carrying (3, 5) and counted once; the
     * counts come from `RecomputeSchedules.occurrenceOf`, never from a raw membership list.
     */
    @Test fun aGroupScheduleIsOneRowCountedOnce() = runTest {
        val members = (1..5).map { asset("Sprinkler $it", category = "Irrigation") }
        graph.groups.upsert(
            groupOf(
                "g1",
                name = "North run",
                members = members.map { Triple(it.id.value, "2026-01-01", null) },
            ),
        )
        seed(scheduleOf("s-group", assetId = null, groupId = "g1", title = "Head check", anchorOn = "2026-01-01", leadDays = 0))

        // Three of the five did the work for the open round, through the production use case: the
        // occurrence key it stamps is the one the engine says is open (carry-forward (c)).
        graph.completeGroupMembers.run(
            ScheduleId("s-group"),
            members.take(3).map { it.id },
            CompletionCommand(occurredOn = "2026-04-14", tzId = "UTC"),
        )

        val rows = readModel().items().filter { it.scheduleId.value == "s-group" }
        assertEquals("one row, however many members are outstanding", 1, rows.size)
        val row = rows.single()
        assertEquals(5, row.membersRequired)
        assertEquals(3, row.membersComplete)
        assertEquals("North run", row.assetName)
        assertTrue("one row means one contribution to any due total", row.countsAsDue)
    }

    // ---------------------------------------------------------------- lifecycle

    /**
     * Matrix rows "archived data" and carry-forward (a). An ARCHIVED **schedule** appears nowhere —
     * `listedForDue()` is where every query starts. An archived **asset** is out of the default
     * listing but its schedules are intact when the asset is asked about directly (#5 AC 3).
     */
    @Test fun archivedSchedulesAndArchivedAssetsLeaveTheDefaultListing() = runTest {
        val mower = asset("Mower")
        val retiredTub = asset("Old tub", category = "Water")

        seed(scheduleOf("s-live", assetId = mower.id.value, title = "Live", anchorOn = "2026-01-01"))
        seed(scheduleOf("s-archived", assetId = mower.id.value, title = "Retired work", status = ScheduleStatus.ARCHIVED))
        seed(scheduleOf("s-on-archived-asset", assetId = retiredTub.id.value, title = "History", anchorOn = "2026-01-01"))
        graph.archiveAsset.run(retiredTub.id)

        val listed = readModel().items().map { it.scheduleId.value }
        assertEquals(listOf("s-live"), listed)

        // Opened directly, the archived asset still has its schedule — the history is intact.
        val direct = readModel().forAsset(retiredTub.id).map { it.scheduleId.value }
        assertEquals(listOf("s-on-archived-asset"), direct)
        assertTrue("an archived schedule is out of that answer too", "s-archived" !in direct)
    }

    /**
     * Matrix row "a paused or out-of-season schedule in a due total" (invariant 22): neither
     * contributes to a due count, whatever else they do.
     */
    @Test fun pausedAndOutOfSeasonNeverCountAsDue() = runTest {
        val blower = graph.createAsset.run(
            AssetCommand(name = "Snowblower", category = "Yard", seasonStartMmdd = "11-01", seasonEndMmdd = "02-28"),
        )
        seed(scheduleOf("s-paused", assetId = blower.id.value, title = "Paused", anchorOn = "2026-01-01", status = ScheduleStatus.PAUSED))
        seed(scheduleOf("s-season", assetId = blower.id.value, title = "Season", anchorOn = "2026-01-01", seasonBehavior = SeasonBehavior.FOLLOW_ASSET))

        assertEquals(emptyList<String>(), readModel().items().filter { it.countsAsDue }.map { it.scheduleId.value })
    }

    // ---------------------------------------------------------------- forAsset

    /**
     * B09's sheet and B15's asset section ask one asset's question, and the answer includes the
     * group schedules the asset is a required member of — not only the schedules targeting it.
     */
    @Test fun forAssetIncludesTheGroupSchedulesItIsRequiredFor() = runTest {
        val head = asset("Sprinkler 1", category = "Irrigation")
        val other = asset("Sprinkler 2", category = "Irrigation")
        graph.groups.upsert(groupOf("g1", members = listOf(Triple(head.id.value, "2026-01-01", null))))
        seed(scheduleOf("s-own", assetId = head.id.value, title = "Own work", anchorOn = "2026-01-01"))
        seed(scheduleOf("s-group", assetId = null, groupId = "g1", title = "Group work", anchorOn = "2026-01-01"))

        assertEquals(
            listOf("s-group", "s-own"),
            readModel().forAsset(head.id).map { it.scheduleId.value }.sorted(),
        )
        assertEquals(emptyList<String>(), readModel().forAsset(other.id).map { it.scheduleId.value })
    }

    // ---------------------------------------------------------------- F3's numbers

    /**
     * F3's data half: the meter threshold, the current reading and the unit all come off rows that
     * exist, and a row with no meter rule carries none of them. What the line reads is asserted on
     * the composed row (`DashboardAttentionTest`); what it is allowed to say is asserted here.
     */
    @Test fun aMeterRowCarriesItsThresholdItsReadingAndItsUnit() = runTest {
        val mower = asset("Mower")
        graph.definitions.upsert(meterDefinitionOf("d-hours", assetId = mower.id.value, unit = "h"))
        graph.events.upsert(readingOf("e-hours", mower.id.value, "d-hours", value = 480.0))
        seed(
            scheduleOf(
                "s-meter", assetId = mower.id.value, title = "Oil change",
                timeInterval = null, timeUnit = null, anchorOn = null,
                meterDefinitionId = "d-hours", meterInterval = 100.0, anchorMeter = 400.0,
            ),
        )
        seed(scheduleOf("s-time", assetId = mower.id.value, title = "Time only", anchorOn = "2026-01-01"))

        val items = readModel().items().associateBy { it.scheduleId.value }
        val meter = items.getValue("s-meter")
        assertEquals(500.0, meter.computedDueMeter!!, 0.0001)
        assertEquals(480.0, meter.currentMeter!!, 0.0001)
        assertEquals("h", meter.meterUnit)

        val time = items.getValue("s-time")
        assertNull("no meter rule, so no threshold to state", time.computedDueMeter)
        assertNull(time.meterUnit)
    }

    // ---------------------------------------------------------------- invariant 18, structurally

    /**
     * Matrix row "status cached" (invariant 18). Caching a section for scroll performance is the
     * plausible, well-meant change that would put a derived status on disk, and no runtime
     * assertion can see it — so the tree is read instead. `DueStatus` appears in neither the
     * preference layer nor the Room layer, and the projection itself upserts no derived state
     * (invariant 17); `:core`'s own structural test covers the backup format and the merge.
     */
    @Test fun noDerivedStatusReachesAPreferenceOrAColumn() {
        val persistence = listOf("app/src/main/kotlin/com/loosecannon/servicetag/prefs")
            .flatMap { kotlinFilesUnder(it) }
        assertTrue("the preference layer should not be empty", persistence.isNotEmpty())
        assertEquals(
            emptyList<String>(),
            persistence.filter { Regex("""\bDueStatus\b""").containsMatchIn(it.readText()) }.map { it.name },
        )

        // And the read model reads derived state without ever writing it back.
        val projection = sourceFile(
            "app/src/main/kotlin/com/loosecannon/servicetag/ui/maintenance/DueReadModel.kt",
        ).readText()
        assertTrue("it has to hold the port to read it", "ScheduleStateRepository" in projection)
        assertEquals(
            emptyList<String>(),
            Regex("""\bstates\.upsert\(""").findAll(projection).map { it.value }.toList(),
        )
    }

    private companion object {
        /** The repository root, found by walking up to the settings script. */
        fun repoRoot(): java.io.File {
            var dir = java.io.File(".").absoluteFile
            while (!java.io.File(dir, "settings.gradle.kts").isFile) {
                dir = dir.parentFile ?: error("no settings.gradle.kts above ${java.io.File(".").absolutePath}")
            }
            return dir
        }

        fun sourceFile(path: String): java.io.File = java.io.File(repoRoot(), path)

        fun kotlinFilesUnder(path: String): List<java.io.File> =
            sourceFile(path).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }
}
