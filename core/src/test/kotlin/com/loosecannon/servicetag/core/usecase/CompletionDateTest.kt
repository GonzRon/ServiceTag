package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.EventProfile
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.GroupMember
import com.loosecannon.servicetag.core.model.MaintenanceGroup
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.EventRepository
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.testing.BackupInstall
import com.loosecannon.servicetag.core.testing.FakeUnitOfWork
import com.loosecannon.servicetag.core.testing.InMemoryAssetRepository
import com.loosecannon.servicetag.core.testing.InMemoryClosureRepository
import com.loosecannon.servicetag.core.testing.InMemoryDefinitionRepository
import com.loosecannon.servicetag.core.testing.InMemoryEventRepository
import com.loosecannon.servicetag.core.testing.InMemoryGroupRepository
import com.loosecannon.servicetag.core.testing.InMemoryProfileRepository
import com.loosecannon.servicetag.core.testing.InMemoryScheduleRepository
import com.loosecannon.servicetag.core.testing.InMemoryScheduleStateRepository
import com.loosecannon.servicetag.core.testing.InMemorySeasonActivationRepository
import com.loosecannon.servicetag.core.testing.InMemorySupplyItemRepository
import com.loosecannon.servicetag.core.testing.completionOf
import com.loosecannon.servicetag.core.testing.dayMillis
import com.loosecannon.servicetag.core.testing.plainAssetOf
import com.loosecannon.servicetag.core.testing.scheduleOf
import com.loosecannon.servicetag.core.transfer.TransferInstall
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * O-16 as amended (C39, rows 123–126 and 131): a schedule's completion dated after the phone's today is refused by
 * the three commands that could write one — [CompleteSchedule], [CompleteGroupMembers] and an [UpdateEvent] whose
 * result names a schedule — before anything is written, while an ordinary journal entry keeps any date and no
 * importer rewrites a stored one.
 *
 * "Nothing written" is read three ways on every refusal: the event writes a counting double saw (a write the
 * transaction later rolled back is still counted), the tables before and after, and the transactions committed.
 */
class CompletionDateTest {

    private val assets = InMemoryAssetRepository()
    private val defs = InMemoryDefinitionRepository()
    private val profiles = InMemoryProfileRepository()
    private val stored = InMemoryEventRepository()
    private val groups = InMemoryGroupRepository()
    private val closures = InMemoryClosureRepository()
    private val states = InMemoryScheduleStateRepository()
    private val schedules = InMemoryScheduleRepository(closures, states)
    private val uow = FakeUnitOfWork(assets, defs, profiles, stored, groups, closures, schedules, states)

    /** Every event write, counted before it lands, so a write a rollback undid is still seen. */
    private var eventWrites = 0
    private val events = object : EventRepository by stored {
        override suspend fun upsert(event: AssetEvent) {
            eventWrites += 1
            stored.upsert(event)
        }
    }

    private var seq = 0
    private val ids = IdGenerator { "id-${++seq}" }
    private val clock = Clock { dayMillis("2026-04-15") }
    private var today = LocalDate.parse("2026-04-15")
    private val todayPort = Today { today }

    private val recompute = RecomputeSchedules(
        schedules, states, events, closures, groups, assets, InMemorySeasonActivationRepository(), todayPort, clock,
    ) { ZoneOffset.UTC }
    private val supplyItems = InMemorySupplyItemRepository()
    private val completeSchedule =
        CompleteSchedule(schedules, events, defs, profiles, supplyItems, uow, ids, clock, recompute, todayPort)
    private val completeMembers =
        CompleteGroupMembers(schedules, groups, events, closures, defs, profiles, supplyItems, uow, ids, clock, recompute, todayPort)
    private val updateEvent = UpdateEvent(events, defs, profiles, supplyItems, uow, ids, clock, recompute, todayPort)
    private val logEvent = LogEvent(events, defs, profiles, assets, supplyItems, uow, ids, clock, recompute)

    private val tomorrow: String get() = today.plusDays(1).toString()

    // --- fixtures ------------------------------------------------------------------------------------------------

    private suspend fun seedAsset(id: String): AssetId {
        assets.upsert(Asset(id = AssetId(id), name = "Example Boiler $id", createdAt = 1L, updatedAt = 1L))
        return AssetId(id)
    }

    /** A monthly FIXED schedule on [assetId], postponed, so a refusal that cleared the postponement would show. */
    private suspend fun assetSchedule(
        id: String,
        assetId: AssetId,
        mode: CompletionMode = CompletionMode.QUICK,
        profileId: ProfileId? = null,
    ): MaintenanceSchedule {
        val schedule = scheduleOf(
            id, assetId = assetId.value, timeInterval = 1, timeUnit = RecurrenceUnit.MONTH, anchorOn = "2026-01-01",
            completionMode = mode, postponedDueOn = "2026-05-01",
        ).copy(profileId = profileId)
        schedules.upsert(schedule)
        recompute.forSchedule(schedule.id)
        return schedule
    }

    private suspend fun profileOn(assetId: AssetId): ProfileId {
        val profile = EventProfile(
            id = ProfileId("p-${assetId.value}"), assetId = assetId, name = "Annual service",
            eventKind = EventKind.MAINTENANCE, defaultTitle = "Serviced", templateKey = null,
            sortOrder = 0, archivedAt = null, createdAt = 1L, updatedAt = 1L,
            fields = emptyList(), consumables = emptyList(),
        )
        profiles.upsert(profile)
        return profile.id
    }

    /** A monthly group round over [members], postponed, its open round the one the engine computes. */
    private suspend fun groupSchedule(members: List<AssetId>): MaintenanceSchedule {
        val group = MaintenanceGroup(
            id = GroupId("g1"), name = "Example pre-season check", description = "", archivedAt = null,
            createdAt = 1L, updatedAt = 1L,
            members = members.mapIndexed { i, assetId ->
                GroupMember(id = "g1-m$i", assetId = assetId, sortOrder = i, addedAt = 1L, removedAt = null)
            },
        )
        groups.upsert(group)
        val schedule = scheduleOf(
            "sg", assetId = null, groupId = "g1", timeInterval = 1, timeUnit = RecurrenceUnit.MONTH,
            anchorOn = "2026-01-01", postponedDueOn = "2026-05-01",
        )
        schedules.upsert(schedule)
        recompute.forSchedule(schedule.id)
        return schedule
    }

    private fun completion(on: String, viaForm: Boolean = false, assetId: AssetId? = null) =
        CompletionCommand(occurredOn = on, tzId = "UTC", viaForm = viaForm, assetId = assetId)

    private fun entry(assetId: AssetId, on: String, scheduleId: ScheduleId? = null) = EventCommand(
        assetId = assetId, profileId = null, kind = EventKind.NOTE, title = "Example note", occurredOn = on,
        occurredTime = null, tzId = "UTC", notes = "", values = emptyMap(), consumables = emptyList(),
        scheduleId = scheduleId,
    )

    /** Every table a completion could touch, as it stands. */
    private fun snapshot(): List<Any> = listOf(
        stored.rows.toMap(), schedules.rows.toMap(), states.rows.toMap(), closures.rows.toMap(),
    )

    /** [block] is refused with [CompletionDateInFuture] naming [on], and writes nothing at all. */
    private suspend fun refusedWritingNothing(on: String, block: suspend () -> Unit): CompletionDateInFuture {
        val before = snapshot()
        val writes = eventWrites
        val commits = uow.commits
        val refused = assertFailsWith<CompletionDateInFuture> { block() }
        assertEquals(on, refused.occurredOn)
        assertEquals(today.toString(), refused.today)
        assertEquals(writes, eventWrites, "no event write was attempted")
        assertEquals(commits, uow.commits, "no transaction committed")
        assertEquals(before, snapshot(), "no event, schedule, state, postponement or closure changed")
        return refused
    }

    // --- row 123: CompleteSchedule -------------------------------------------------------------------------------

    @Test
    fun aCompletionDatedTomorrowIsRefusedInEveryModeAndNothingIsWritten() = runTest {
        val boiler = seedAsset("a1")
        val quick = assetSchedule("s-quick", boiler)
        val form = assetSchedule("s-form", boiler, CompletionMode.FORM, profileOn(boiler))

        refusedWritingNothing(tomorrow) { completeSchedule.run(quick.id, completion(tomorrow)) }
        // A FORM schedule's minimal completion (the notification's one tap, a bodyless API call).
        refusedWritingNothing(tomorrow) { completeSchedule.run(form.id, completion(tomorrow)) }
        // #119's form completion, whose meter rule is untouched.
        refusedWritingNothing(tomorrow) { completeSchedule.run(form.id, completion(tomorrow, viaForm = true)) }
        // Far ahead is the same refusal.
        refusedWritingNothing("2099-01-01") { completeSchedule.run(quick.id, completion("2099-01-01")) }
    }

    @Test
    fun todayAndYesterdayAreAcceptedAndAMalformedDateIsStillBadDate() = runTest {
        val boiler = seedAsset("a1")
        val quick = assetSchedule("s-quick", boiler)
        val form = assetSchedule("s-form", boiler, CompletionMode.FORM, profileOn(boiler))

        val onToday = completeSchedule.run(quick.id, completion(today.toString()))
        assertEquals(today.toString(), onToday.occurredOn)
        val yesterday = completeSchedule.run(form.id, completion(today.minusDays(1).toString(), viaForm = true))
        assertEquals(today.minusDays(1).toString(), yesterday.occurredOn)
        assertEquals(2, stored.rows.size)

        for (malformed in listOf("2026-13-01", "next week", "2026-4-16")) {
            val refused = assertFailsWith<EventValidation> { completeSchedule.run(quick.id, completion(malformed)) }
            assertEquals(listOf<FieldProblem>(FieldProblem.BadDate()), refused.problems)
        }
        assertEquals(2, stored.rows.size)
    }

    // --- row 124: CompleteGroupMembers ---------------------------------------------------------------------------

    @Test
    fun aGroupBatchDatedTomorrowIsRefusedWholeAndTodayWritesEveryMember() = runTest {
        val a1 = seedAsset("a1")
        val a2 = seedAsset("a2")
        val round = groupSchedule(listOf(a1, a2))

        refusedWritingNothing(tomorrow) { completeMembers.run(round.id, listOf(a1, a2), completion(tomorrow)) }
        refusedWritingNothing(tomorrow) { completeMembers.all(round.id, completion(tomorrow)) }

        val written = completeMembers.all(round.id, completion(today.toString()))
        assertEquals(setOf(a1, a2), written.map { it.assetId }.toSet())
        assertTrue(written.all { it.occurredOn == today.toString() && it.scheduleId == round.id })
        assertEquals(null, schedules.get(round.id)!!.postponedDueOn, "the finishing write still clears the postponement")
    }

    // --- row 125: UpdateEvent ------------------------------------------------------------------------------------

    @Test
    fun anEditThatLeavesACompletionDatedAfterTodayIsRefusedAndAnOrdinaryEntryIsNot() = runTest {
        val boiler = seedAsset("a1")
        val quick = assetSchedule("s-quick", boiler)
        val done = completeSchedule.run(quick.id, completion(today.toString()))

        // A completion's date moved to tomorrow: refused, the stored row unchanged. The command names no schedule;
        // the link the stored row keeps is what makes it a completion.
        refusedWritingNothing(tomorrow) { updateEvent.run(done.id, entry(boiler, tomorrow)) }
        assertEquals(done, stored.get(done.id))

        // An ordinary entry moved to tomorrow keeps today's freedom.
        val note = logEvent.run(entry(boiler, today.toString()))
        assertEquals(tomorrow, updateEvent.run(note.id, entry(boiler, tomorrow)).occurredOn)

        // A future-dated entry re-pointed onto a schedule: refused, the entry as it was.
        val ahead = stored.get(note.id)!!
        refusedWritingNothing(tomorrow) { updateEvent.run(note.id, entry(boiler, tomorrow, scheduleId = quick.id)) }
        assertEquals(ahead, stored.get(note.id))
    }

    @Test
    fun aLegacyCompletionStoredInAdvanceIsEditableOnlyToADayNotAfterToday() = runTest {
        val boiler = seedAsset("a1")
        val quick = assetSchedule("s-quick", boiler)
        // Stored directly: an older release's write, or a merge's, dated ahead of today. Nothing rewrites it.
        val legacy = completionOf("e-legacy", occurredOn = "2026-04-20", occurrenceOn = "2026-04-01",
            scheduleId = quick.id.value, createdAt = dayMillis("2026-04-10"))
        stored.upsert(legacy)

        refusedWritingNothing("2026-04-20") { updateEvent.run(legacy.id, entry(boiler, "2026-04-20")) }
        assertEquals(legacy, stored.get(legacy.id), "kept as it was stored")

        val moved = updateEvent.run(legacy.id, entry(boiler, today.toString()))
        assertEquals(today.toString(), moved.occurredOn)
        assertEquals(quick.id, moved.scheduleId, "the link is kept")
    }

    // --- row 126: history is never rewritten ---------------------------------------------------------------------

    @Test
    fun aMergeARestoreAndAPackInsertAnAdvanceCompletionVerbatim() = runTest {
        // Dated after every importer's day (the installs' clocks read 2025-09), with an `updatedAt` of its own.
        val advance = completionOf("e-advance", occurredOn = "2026-12-01", occurrenceOn = "2026-11-01",
            assetId = "a1", scheduleId = "s1", createdAt = 1_700_000_000_000L).copy(updatedAt = 1_700_000_500_000L)
        val source = BackupInstall("set-source")
        source.assets.upsert(plainAssetOf("a1", "Example Heater"))
        source.schedules.upsert(scheduleOf("s1", assetId = "a1", timeInterval = 1, timeUnit = RecurrenceUnit.YEAR,
            anchorOn = "2025-11-01"))
        source.events.upsert(advance)
        val bytes = source.export.run().data

        val merged = BackupInstall("set-merged")
        val plan = merged.build.run(bytes)
        assertTrue(plan.applicable, plan.conflicts.toString())
        merged.apply.run(plan)
        assertEquals(advance, merged.events.get(EventId("e-advance")), "a merge inserts it verbatim")

        val restored = BackupInstall("set-restored")
        restored.replace.run(bytes)
        assertEquals(advance, restored.events.get(EventId("e-advance")), "a replace restore inserts it verbatim")

        val sender = TransferInstall("set-sender")
        sender.raw.assets.upsert(plainAssetOf("a1", "Example Heater"))
        sender.raw.schedules.upsert(scheduleOf("s1", assetId = "a1", timeInterval = 1, timeUnit = RecurrenceUnit.YEAR,
            anchorOn = "2025-11-01"))
        sender.raw.events.upsert(advance)
        val pack = sender.pack("pack-1", "a1")
        val recipient = TransferInstall("set-recipient")
        assertIs<TransferImportResult.Imported>(recipient.import(pack.bytes))
        val arrived = assertNotNull(recipient.raw.events.get(EventId("e-advance")))
        assertEquals(advance.occurredOn, arrived.occurredOn, "a pack import keeps its date")
        assertEquals(advance.updatedAt, arrived.updatedAt, "and its updatedAt")
    }

    // --- row 131: the boundary -----------------------------------------------------------------------------------

    @Test
    fun theBoundaryIsTodayInclusiveAndALeapDayResolves() {
        val leap = LocalDate.parse("2028-02-29")
        refuseFutureCompletion("2028-02-29", leap)
        refuseFutureCompletion("2028-02-28", leap)
        val refused = assertFailsWith<CompletionDateInFuture> { refuseFutureCompletion("2028-03-01", leap) }
        assertEquals("2028-03-01", refused.occurredOn)
        assertEquals("2028-02-29", refused.today)

        // The day before a leap day: the 29th is tomorrow, a real date, and refused.
        assertFailsWith<CompletionDateInFuture> { refuseFutureCompletion("2028-02-29", LocalDate.parse("2028-02-28")) }
        // A 29 February that does not exist is not a date at all: left to buildEvent's BadDate.
        refuseFutureCompletion("2027-02-29", LocalDate.parse("2027-02-01"))
        refuseFutureCompletion("not a date", leap)
    }
}
