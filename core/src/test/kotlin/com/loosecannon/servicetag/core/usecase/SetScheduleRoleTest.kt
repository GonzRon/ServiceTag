package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.MaintenanceGroup
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleProviderRow
import com.loosecannon.servicetag.core.model.ScheduleRole
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.SeasonActivation
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.GroupRepository
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.SeasonActivationRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.season.Readiness
import com.loosecannon.servicetag.core.season.SeasonReadinessReader
import com.loosecannon.servicetag.core.season.seasonReadiness
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncGuard
import com.loosecannon.servicetag.core.testing.BackupInstall
import com.loosecannon.servicetag.core.testing.InMemoryPublishBindingRepository
import com.loosecannon.servicetag.core.testing.InMemoryPublishDeliveryRepository
import com.loosecannon.servicetag.core.testing.InMemoryScheduleStateRepository
import com.loosecannon.servicetag.core.testing.dayMillis
import com.loosecannon.servicetag.core.testing.groupOf
import com.loosecannon.servicetag.core.testing.scheduleOf
import com.loosecannon.servicetag.core.testing.transferOf
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/** The fictional "today" of every #114 command test. */
internal const val ROLE_TODAY = "2026-10-26"

/**
 * #114 (A2) — one install for the role command and the hooked shipped commands: every store in memory over one
 * [BackupInstall], the real recompute, and the five writers the hooks sit in, built as `AppGraph` builds them. [writes]
 * counts every asset, schedule, group and activation write, so "nothing written" is asserted as a count as well as a
 * row. Every name is fictional.
 */
internal class SeasonStartHarness(today: String = ROLE_TODAY) {
    val raw = BackupInstall()
    val states = InMemoryScheduleStateRepository()
    private var seq = 0
    val ids = IdGenerator { "new-${++seq}" }
    var now: Long = dayMillis(today) + 36_000_000L
    val clock = Clock { now }
    val day: LocalDate = LocalDate.parse(today)
    val todayPort = Today { day }

    /** Every write a refused command must not make. */
    var writes = 0

    val assets: AssetRepository = object : AssetRepository by raw.assets {
        override suspend fun upsert(asset: Asset) {
            writes++
            raw.assets.upsert(asset)
        }
    }
    val schedules: ScheduleRepository = object : ScheduleRepository by raw.schedules {
        override suspend fun upsert(schedule: MaintenanceSchedule) {
            writes++
            raw.schedules.upsert(schedule)
        }
    }
    val groups: GroupRepository = object : GroupRepository by raw.groups {
        override suspend fun upsert(group: MaintenanceGroup) {
            writes++
            raw.groups.upsert(group)
        }
    }
    val activations: SeasonActivationRepository = object : SeasonActivationRepository by raw.activations {
        override suspend fun insert(row: SeasonActivation) {
            writes++
            raw.activations.insert(row)
        }
    }

    val publishBindings = InMemoryPublishBindingRepository()
    val publishDeliveries = InMemoryPublishDeliveryRepository(publishBindings)

    /** #124 (C41): the one reader of C9's opening-service day, over these same stores. */
    val openingServiceReader = SeasonReadinessReader(
        assets, schedules, groups, raw.events, activations, publishBindings, publishDeliveries, raw.seasonSyncBindings,
        raw.haConnections, todayPort, clock,
    ) { ZoneOffset.UTC }

    val recompute = RecomputeSchedules(
        schedules, states, raw.events, raw.closures, groups, assets, activations, todayPort, clock, openingServiceReader,
    ) { ZoneOffset.UTC }
    private val guard = SeasonSyncGuard(raw.seasonSyncBindings)
    private val promote = PromoteCategory(raw.categories)

    val setScheduleRole = SetScheduleRole(schedules, assets, groups, raw.transfers, raw.uow, clock, todayPort, recompute)
    val setSeasonMode =
        SetSeasonMode(assets, schedules, activations, raw.uow, ids, clock, todayPort, recompute, guard, groups)
    val updateAsset = UpdateAsset(assets, schedules, raw.uow, clock, recompute, promote, groups)
    val saveAssetSettings = SaveAssetSettings(
        assets, schedules, raw.subjects, activations, raw.uow, ids, clock, todayPort, recompute,
        ApplyTemplate(raw.definitions, raw.profiles, assets, raw.uow, ids, clock), promote, guard, groups,
    )
    val saveSchedule =
        SaveSchedule(schedules, assets, groups, raw.definitions, raw.profiles, raw.uow, ids, clock, recompute, raw.subjects)
    val saveGroup = SaveGroup(groups, assets, raw.uow, ids, clock, schedules, raw.transfers)
    val archiveSchedule = ArchiveSchedule(schedules, raw.uow, recompute, raw.subjects, assets, clock, groups, raw.transfers)
    val archiveGroup = ArchiveGroup(groups, raw.uow, clock, schedules, assets, raw.transfers)
    val updateEvent =
        UpdateEvent(raw.events, raw.definitions, raw.profiles, raw.supplyItems, raw.uow, ids, clock, recompute, todayPort)
    val logEvent = LogEvent(raw.events, raw.definitions, raw.profiles, assets, raw.supplyItems, raw.uow, ids, clock, recompute)
    val completeSchedule = CompleteSchedule(
        schedules, raw.events, raw.definitions, raw.profiles, raw.supplyItems, raw.uow, ids, clock, recompute, todayPort,
    )
    val deleteAsset = DeleteAsset(
        assets, raw.events, raw.attachments, raw.storage, raw.uow, groups, schedules, raw.closures,
        raw.installedComponents,
    )

    /** An asset stored as it is. */
    fun asset(
        id: String,
        name: String = "Example $id",
        mode: SeasonMode = SeasonMode.MANUAL,
        parent: String? = null,
        start: String? = null,
        end: String? = null,
        status: AssetStatus = AssetStatus.ACTIVE,
        retiredOn: String? = null,
    ): Asset = Asset(
        id = AssetId(id),
        name = name,
        createdAt = dayMillis("2024-01-01"),
        updatedAt = dayMillis("2024-01-01"),
        status = status,
        retiredOn = retiredOn,
        parentAssetId = parent?.let(::AssetId),
        seasonMode = mode,
        seasonStartMmdd = start,
        seasonEndMmdd = end,
    ).also { raw.assets.rows[it.id.value] = it }

    /** A yearly schedule stored as it is: FORM unless asked, with or without a role. */
    fun schedule(
        id: String,
        assetId: String? = "heater",
        groupId: String? = null,
        title: String = "Annual service $id",
        mode: CompletionMode = if (groupId != null) CompletionMode.QUICK else CompletionMode.FORM,
        status: ScheduleStatus = ScheduleStatus.ACTIVE,
        policy: ServicePolicy = ServicePolicy.CONTINUOUS,
        offset: Int? = null,
        role: ScheduleRole = ScheduleRole.NONE,
        since: String? = if (role == ScheduleRole.SEASON_START) "2026-09-01" else null,
    ): MaintenanceSchedule = scheduleOf(
        id = id,
        assetId = if (groupId != null) null else assetId,
        groupId = groupId,
        title = title,
        timeInterval = 1,
        timeUnit = RecurrenceUnit.YEAR,
        anchorOn = "2026-01-15",
        servicePolicy = policy,
        policyOffsetDays = offset,
        completionMode = mode,
        status = status,
        postponedDueOn = "2027-02-01",
        ruleChangedOn = "2026-03-01",
    ).copy(role = role, roleSinceOn = since, providers = listOf(ScheduleProviderRow("LOCAL", enabled = true)))
        .also { raw.schedules.rows[it.id.value] = it }

    /** A group stored as it is; each member is `(assetId, addedOn, removedOn)`. */
    fun group(
        id: String,
        vararg members: Triple<String, String, String?>,
        archivedAt: Long? = null,
    ): MaintenanceGroup = groupOf(id = id, name = "Example group $id", archivedAt = archivedAt, members = members.toList())
        .also { raw.groups.rows[it.id.value] = it }

    suspend fun hold(assetId: String) = raw.transfers.append(transferOf("out-$assetId", assetId = assetId))

    fun stored(id: String): MaintenanceSchedule = raw.schedules.rows.getValue(id)

    /** [schedule] as the command that would store it: the editor's round trip. */
    fun commandOf(schedule: MaintenanceSchedule): ScheduleCommand = ScheduleCommand(
        targetAssetId = (schedule.target as? ScheduleTarget.AssetTarget)?.assetId,
        targetGroupId = (schedule.target as? ScheduleTarget.GroupTarget)?.groupId,
        title = schedule.title,
        description = schedule.description,
        timeInterval = schedule.timeInterval,
        timeUnit = schedule.timeUnit,
        timeBasis = schedule.timeBasis,
        anchorOn = schedule.anchorOn,
        leadDays = schedule.leadDays,
        servicePolicy = schedule.servicePolicy,
        policyOffsetDays = schedule.policyOffsetDays,
        completionMode = schedule.completionMode,
        profileId = schedule.profileId,
        remindersEnabled = schedule.remindersEnabled,
        providers = schedule.providers,
    )

    /** [asset]'s form as the editor sends it back, with [parent] in place of its own. */
    fun assetCommandOf(asset: Asset, parent: AssetId? = asset.parentAssetId): AssetCommand = AssetCommand(
        name = asset.name,
        parentAssetId = parent,
        seasonStartMmdd = asset.seasonStartMmdd,
        seasonEndMmdd = asset.seasonEndMmdd,
    )

    /** C9 over everything stored, today and UTC. */
    suspend fun readiness(assetId: String): Readiness = seasonReadiness(
        raw.assets.rows.getValue(assetId), raw.assets.all(), raw.schedules.all(), raw.groups.all(), raw.events.all(),
        raw.activations.all(), day, ZoneOffset.UTC,
    )
}

/** The day after [ROLE_TODAY]: a `sinceOn` C10 refuses. */
private const val TOMORROW = "2026-10-27"

/**
 * #114 (A2; C10, C10a; rows 13–17, 112) — `SetScheduleRole`: who may take the passive Season Start role, the
 * refusals of C10's table with nothing written, the one-role-per-asset rule across asset and group targets, a
 * group's open members, the write's exact footprint and the QUICK election.
 */
class SetScheduleRoleTest {
    private val h = SeasonStartHarness()

    private suspend fun refused(id: String, sinceOn: String? = null, quickElected: Boolean = false): SeasonStartRefusal {
        val before = h.raw.schedules.rows.toMap()
        val refusal = assertFailsWith<SeasonStartRefused> {
            h.setScheduleRole.run(ScheduleId(id), ScheduleRole.SEASON_START, sinceOn, quickElected)
        }.reason
        assertEquals(0, h.writes, "a refusal writes nothing")
        assertEquals(before, h.raw.schedules.rows.toMap(), "every schedule as it was")
        return refusal
    }

    // ---- Row 13 — C10, AC114-1, O-4: who takes it, and its date -------------------------------------------------

    @Test fun aTopLevelManualAssetTakesTheRoleDatedToday() = runTest {
        h.asset("heater")
        h.schedule("s1")

        val saved = h.setScheduleRole.run(ScheduleId("s1"), ScheduleRole.SEASON_START)

        assertEquals(ScheduleRole.SEASON_START, saved.role)
        assertEquals(ROLE_TODAY, saved.roleSinceOn, "the boundary defaults to today")
        assertEquals(saved, h.stored("s1"))
    }

    @Test fun aParentCalendarAssetTakesIt() = runTest {
        h.asset("house", mode = SeasonMode.CALENDAR, start = "11-01", end = "03-31")
        h.asset("pump", mode = SeasonMode.YEAR_ROUND, parent = "house")
        h.schedule("s1", assetId = "house")

        val saved = h.setScheduleRole.run(ScheduleId("s1"), ScheduleRole.SEASON_START)
        assertEquals(ScheduleRole.SEASON_START, saved.role)
    }

    @Test fun aMidLevelAssetQualifiesAsAParent() = runTest {
        h.asset("house", mode = SeasonMode.YEAR_ROUND)
        h.asset("heater", parent = "house")
        h.asset("burner", mode = SeasonMode.YEAR_ROUND, parent = "heater")
        h.schedule("s1", assetId = "heater")

        val saved = h.setScheduleRole.run(ScheduleId("s1"), ScheduleRole.SEASON_START)
        assertEquals(ScheduleRole.SEASON_START, saved.role, "a child with a child row of its own is a parent (#114 point 1)")
    }

    @Test fun aChosenPastDateIsKept() = runTest {
        h.asset("heater")
        h.schedule("s1")

        val saved = h.setScheduleRole.run(ScheduleId("s1"), ScheduleRole.SEASON_START, sinceOn = "2026-09-01")
        assertEquals("2026-09-01", saved.roleSinceOn)
        assertEquals("2026-09-01", h.stored("s1").roleSinceOn)
    }

    @Test fun aFutureDateIsRefused() = runTest {
        h.asset("heater")
        h.schedule("s1")

        assertEquals(SeasonStartRefusal.DateInFuture, refused("s1", sinceOn = TOMORROW))
    }

    // ---- Row 14 — C10's refusals, each with nothing written ------------------------------------------------------

    @Test fun anArchivedScheduleIsRefused() = runTest {
        h.asset("heater")
        h.schedule("s1", status = ScheduleStatus.ARCHIVED)
        assertEquals(SeasonStartRefusal.ScheduleArchived, refused("s1"))
    }

    @Test fun anAssetNotMaintainedHereIsRefused() = runTest {
        h.asset("archived", status = AssetStatus.ARCHIVED)
        h.schedule("s-archived", assetId = "archived")
        h.asset("retired", retiredOn = "2026-05-01")
        h.schedule("s-retired", assetId = "retired")
        h.asset("held")
        h.schedule("s-held", assetId = "held")
        h.hold("held")

        assertEquals(SeasonStartRefusal.NotMaintainedHere(AssetId("archived")), refused("s-archived"))
        assertEquals(SeasonStartRefusal.NotMaintainedHere(AssetId("retired")), refused("s-retired"))
        assertEquals(SeasonStartRefusal.NotMaintainedHere(AssetId("held")), refused("s-held"), "transferred out")
    }

    @Test fun aYearRoundAssetIsRefused() = runTest {
        h.asset("heater", mode = SeasonMode.YEAR_ROUND)
        h.schedule("s1")
        assertEquals(SeasonStartRefusal.YearRound(AssetId("heater")), refused("s1"))
    }

    @Test fun aLeafChildIsRefused() = runTest {
        h.asset("house")
        h.asset("burner", parent = "house")
        h.schedule("s1", assetId = "burner")
        assertEquals(SeasonStartRefusal.LeafChild(AssetId("burner")), refused("s1"))
    }

    @Test fun anArchivedGroupIsRefused() = runTest {
        h.asset("heater")
        h.group("g1", Triple("heater", "2026-01-01", null), archivedAt = dayMillis("2026-06-01"))
        h.schedule("sg", groupId = "g1")
        assertEquals(SeasonStartRefusal.GroupArchived, refused("sg", quickElected = true))
    }

    @Test fun aMalformedDateIsABadDateAndWritesNothing() = runTest {
        h.asset("heater")
        h.schedule("s1")

        val bad = assertFailsWith<SeasonStartBadDate> {
            h.setScheduleRole.run(ScheduleId("s1"), ScheduleRole.SEASON_START, sinceOn = "2026-13-01")
        }
        assertEquals("sinceOn", bad.field)
        assertEquals(0, h.writes)
        assertEquals(ScheduleRole.NONE, h.stored("s1").role)
    }

    @Test fun anUnknownScheduleIsNoSuchSchedule() = runTest {
        assertFailsWith<NoSuchSchedule> { h.setScheduleRole.run(ScheduleId("missing"), ScheduleRole.SEASON_START) }
        assertFailsWith<NoSuchSchedule> { h.setScheduleRole.run(ScheduleId("missing"), ScheduleRole.NONE) }
    }

    // ---- Row 15 — one role per asset across asset and group targets (K5) ----------------------------------------

    @Test fun aSecondRoleOnTheAssetIsAlreadyCovered() = runTest {
        h.asset("heater")
        h.schedule("s1", title = "Annual service", role = ScheduleRole.SEASON_START)
        h.schedule("s2", title = "Burner clean")

        assertEquals(
            SeasonStartRefusal.AlreadyCovered(AssetId("heater"), ScheduleId("s1"), "Annual service"),
            refused("s2"),
        )
    }

    @Test fun aGroupRoleOverACoveredMemberIsAlreadyCovered() = runTest {
        h.asset("heater")
        h.asset("boiler")
        h.schedule("s1", title = "Annual service", role = ScheduleRole.SEASON_START)
        h.group("g1", Triple("heater", "2026-01-01", null), Triple("boiler", "2026-01-01", null))
        h.schedule("sg", groupId = "g1", title = "Pre-season check")

        assertEquals(
            SeasonStartRefusal.AlreadyCovered(AssetId("heater"), ScheduleId("s1"), "Annual service"),
            refused("sg", quickElected = true),
        )
    }

    @Test fun anAssetRoleOverAGroupCoveredAssetIsAlreadyCovered() = runTest {
        h.asset("heater")
        h.group("g1", Triple("heater", "2026-01-01", null))
        h.schedule("sg", groupId = "g1", title = "Pre-season check", role = ScheduleRole.SEASON_START)
        h.schedule("s1", title = "Annual service")

        assertEquals(
            SeasonStartRefusal.AlreadyCovered(AssetId("heater"), ScheduleId("sg"), "Pre-season check"),
            refused("s1"),
        )
    }

    // ---- Row 16 — C1: a group target's open members ---------------------------------------------------------------

    @Test fun aGroupWhoseOpenMembersAllQualifyTakesIt() = runTest {
        h.asset("heater")
        h.asset("boiler", mode = SeasonMode.CALENDAR, start = "10-01", end = "04-30")
        h.group("g1", Triple("heater", "2026-01-01", null), Triple("boiler", "2026-01-01", null))
        h.schedule("sg", groupId = "g1")

        val saved = h.setScheduleRole.run(ScheduleId("sg"), ScheduleRole.SEASON_START, quickElected = true)
        assertEquals(ScheduleRole.SEASON_START, saved.role)
        assertEquals(ROLE_TODAY, saved.roleSinceOn, "one boundary for every member (O-4)")
    }

    @Test fun anIneligibleOpenMemberIsNamed() = runTest {
        // By name, Alpha heater fails first; by id, a-yr would.
        h.asset("a-yr", name = "Zeta boiler", mode = SeasonMode.YEAR_ROUND)
        h.asset("house")
        h.asset("z-leaf", name = "Alpha heater", parent = "house")
        h.asset("mid", name = "Middle heater")
        h.group(
            "g1",
            Triple("a-yr", "2026-01-01", null),
            Triple("mid", "2026-01-01", null),
            Triple("z-leaf", "2026-01-01", null),
        )
        h.schedule("sg", groupId = "g1")

        assertEquals(
            SeasonStartRefusal.LeafChild(AssetId("z-leaf")),
            refused("sg", quickElected = true),
            "the first failing member by name, not by id",
        )
    }

    @Test fun aRemovedMemberIsNotChecked() = runTest {
        h.asset("heater")
        h.asset("old", mode = SeasonMode.YEAR_ROUND)
        h.group("g1", Triple("old", "2025-01-01", "2026-01-01"), Triple("heater", "2026-01-01", null))
        h.schedule("sg", groupId = "g1")

        val saved = h.setScheduleRole.run(ScheduleId("sg"), ScheduleRole.SEASON_START, quickElected = true)
        assertEquals(ScheduleRole.SEASON_START, saved.role, "a closed window is history, never checked")
    }

    @Test fun aGroupWithNoOpenMemberIsRefused() = runTest {
        h.asset("heater")
        h.group("g1", Triple("heater", "2025-01-01", "2026-01-01"))
        h.schedule("sg", groupId = "g1")
        assertEquals(SeasonStartRefusal.GroupHasNoMembers, refused("sg", quickElected = true))
    }

    // ---- Row 17 — C10's write ------------------------------------------------------------------------------------

    @Test fun clearingWritesNoneAndNull() = runTest {
        h.asset("heater")
        h.schedule("s1", role = ScheduleRole.SEASON_START)

        val saved = h.setScheduleRole.run(ScheduleId("s1"), ScheduleRole.NONE)
        assertEquals(ScheduleRole.NONE, saved.role)
        assertNull(saved.roleSinceOn)
        assertEquals(saved, h.stored("s1"))
    }

    @Test fun clearingAnArchivedScheduleIsAllowed() = runTest {
        h.asset("heater")
        h.schedule("s1", status = ScheduleStatus.ARCHIVED, role = ScheduleRole.SEASON_START)

        val saved = h.setScheduleRole.run(ScheduleId("s1"), ScheduleRole.NONE)
        assertEquals(ScheduleRole.NONE, saved.role)
        assertEquals(ScheduleStatus.ARCHIVED, saved.status, "the archive stands")
    }

    @Test fun theWriteMovesRoleSinceOnAndUpdatedAtOnly() = runTest {
        h.asset("heater")
        val before = h.schedule("s1")

        val set = h.setScheduleRole.run(ScheduleId("s1"), ScheduleRole.SEASON_START, sinceOn = "2026-09-01")
        assertEquals(
            before.copy(role = ScheduleRole.SEASON_START, roleSinceOn = "2026-09-01", updatedAt = h.now),
            set,
            "the rule, the policy, ruleChangedAt, postponedDueOn and the providers as they were",
        )

        h.now += 60_000L
        val moved = h.setScheduleRole.run(ScheduleId("s1"), ScheduleRole.SEASON_START, sinceOn = "2026-10-01")
        assertEquals(set.copy(roleSinceOn = "2026-10-01", updatedAt = h.now), moved, "a new date moves only roleSinceOn")

        h.now += 60_000L
        val cleared = h.setScheduleRole.run(ScheduleId("s1"), ScheduleRole.NONE)
        assertEquals(before.copy(updatedAt = h.now), cleared)

        h.writes = 0
        assertEquals(cleared, h.setScheduleRole.run(ScheduleId("s1"), ScheduleRole.NONE))
        assertEquals(0, h.writes, "clearing a NONE writes nothing")
    }

    // ---- Row 112 — C10a, O-2 over the API: the QUICK election ------------------------------------------------------

    @Test fun aQuickScheduleNeedsTheElection() = runTest {
        h.asset("heater")
        h.schedule("s1", mode = CompletionMode.QUICK)

        assertEquals(SeasonStartRefusal.QuickNotElected, refused("s1"))

        val saved = h.setScheduleRole.run(ScheduleId("s1"), ScheduleRole.SEASON_START, quickElected = true)
        assertEquals(ScheduleRole.SEASON_START, saved.role, "the election admits it")
    }

    @Test fun theElectionIsCheckedAfterTheArchiveAndBeforeTheAssetRules() = runTest {
        h.asset("heater", mode = SeasonMode.YEAR_ROUND)
        h.schedule("s-archived", mode = CompletionMode.QUICK, status = ScheduleStatus.ARCHIVED)
        h.schedule("s-quick", mode = CompletionMode.QUICK)

        assertEquals(SeasonStartRefusal.ScheduleArchived, refused("s-archived"))
        assertEquals(SeasonStartRefusal.QuickNotElected, refused("s-quick"))
        assertEquals(SeasonStartRefusal.YearRound(AssetId("heater")), refused("s-quick", quickElected = true))
    }

    @Test fun aFormScheduleIgnoresTheFlag() = runTest {
        h.asset("heater")
        h.schedule("s1")

        val set = h.setScheduleRole.run(ScheduleId("s1"), ScheduleRole.SEASON_START, quickElected = false)
        assertEquals(ScheduleRole.SEASON_START, set.role, "FORM needs no election")

        val cleared = h.setScheduleRole.run(ScheduleId("s1"), ScheduleRole.NONE, quickElected = true)
        assertEquals(ScheduleRole.NONE, cleared.role, "NONE clears whatever the flag says")
        assertNull(cleared.roleSinceOn)
    }
}
