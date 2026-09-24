package com.loosecannon.servicetag.testing

import com.loosecannon.nfc.tagcore.TagIdentity
import com.loosecannon.servicetag.BuildConfig
import com.loosecannon.servicetag.attachments.Thumbnails
import com.loosecannon.servicetag.core.model.EventProfile
import com.loosecannon.servicetag.core.nfc.NdefCodec
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.AttachmentRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.ClosureRepository
import com.loosecannon.servicetag.core.ports.ConditionRepository
import com.loosecannon.servicetag.core.ports.DefinitionRepository
import com.loosecannon.servicetag.core.ports.EventRepository
import com.loosecannon.servicetag.core.ports.GroupRepository
import com.loosecannon.servicetag.core.ports.HealthSubjectRepository
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.LinkRepository
import com.loosecannon.servicetag.core.ports.ProfileRepository
import com.loosecannon.servicetag.core.ports.ReferenceRepository
import com.loosecannon.servicetag.core.ports.ScheduleLocalDeliveryRepository
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.ScheduleStateRepository
import com.loosecannon.servicetag.core.ports.SeasonActivationRepository
import com.loosecannon.servicetag.core.ports.TagRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.ports.UnitOfWork
import com.loosecannon.servicetag.core.usecase.AcceptOperationalOffer
import com.loosecannon.servicetag.core.usecase.AcceptSeasonOffer
import com.loosecannon.servicetag.core.usecase.AddAttachment
import com.loosecannon.servicetag.core.usecase.ApplyTemplate
import com.loosecannon.servicetag.core.usecase.ApplyBackupMergePlan
import com.loosecannon.servicetag.core.usecase.ArchiveAsset
import com.loosecannon.servicetag.core.usecase.ArchiveDefinition
import com.loosecannon.servicetag.core.usecase.ArchiveGroup
import com.loosecannon.servicetag.core.usecase.ArchiveHealthSubject
import com.loosecannon.servicetag.core.usecase.ArchiveProfile
import com.loosecannon.servicetag.core.usecase.ArchiveSchedule
import com.loosecannon.servicetag.core.usecase.BuildBackupMergePlan
import com.loosecannon.servicetag.core.usecase.CloseRound
import com.loosecannon.servicetag.core.usecase.CompleteGroupMembers
import com.loosecannon.servicetag.core.usecase.CompleteSchedule
import com.loosecannon.servicetag.core.usecase.CreateAsset
import com.loosecannon.servicetag.core.usecase.DeleteAsset
import com.loosecannon.servicetag.core.usecase.DeleteAttachment
import com.loosecannon.servicetag.core.usecase.DeleteDefinition
import com.loosecannon.servicetag.core.usecase.DeleteEvent
import com.loosecannon.servicetag.core.usecase.DeleteProfile
import com.loosecannon.servicetag.core.usecase.ExportBackupSet
import com.loosecannon.servicetag.core.usecase.GetAssetSeason
import com.loosecannon.servicetag.core.usecase.ImportBackupMerge
import com.loosecannon.servicetag.core.usecase.ImportBackupReplace
import com.loosecannon.servicetag.core.usecase.LogEvent
import com.loosecannon.servicetag.core.usecase.PauseSchedule
import com.loosecannon.servicetag.core.usecase.PostponeSchedule
import com.loosecannon.servicetag.core.usecase.ProvisionTag
import com.loosecannon.servicetag.core.usecase.RecomputeSchedules
import com.loosecannon.servicetag.core.usecase.RecordCondition
import com.loosecannon.servicetag.core.usecase.RecordSeasonActivation
import com.loosecannon.servicetag.core.usecase.ReorderDefinitions
import com.loosecannon.servicetag.core.usecase.ReorderProfiles
import com.loosecannon.servicetag.core.usecase.RestoreArtifacts
import com.loosecannon.servicetag.core.usecase.RetireAsset
import com.loosecannon.servicetag.core.usecase.SaveAssetSettings
import com.loosecannon.servicetag.core.usecase.SaveDefinition
import com.loosecannon.servicetag.core.usecase.SaveGroup
import com.loosecannon.servicetag.core.usecase.SaveHealthSubject
import com.loosecannon.servicetag.core.usecase.SaveProfile
import com.loosecannon.servicetag.core.usecase.SaveSchedule
import com.loosecannon.servicetag.core.usecase.SetHealthPolicy
import com.loosecannon.servicetag.core.usecase.SetMaintenanceBreak
import com.loosecannon.servicetag.core.usecase.SetSeasonMode
import com.loosecannon.servicetag.core.usecase.UpdateAsset
import com.loosecannon.servicetag.core.usecase.UpdateAttachment
import com.loosecannon.servicetag.core.usecase.UpdateEvent
import com.loosecannon.servicetag.data.room.AppDatabase
import com.loosecannon.servicetag.data.room.RoomAssetRepository
import com.loosecannon.servicetag.data.room.RoomAttachmentRepository
import com.loosecannon.servicetag.data.room.RoomClosureRepository
import com.loosecannon.servicetag.data.room.RoomConditionRepository
import com.loosecannon.servicetag.data.room.RoomDefinitionRepository
import com.loosecannon.servicetag.data.room.RoomEventRepository
import com.loosecannon.servicetag.data.room.RoomGroupRepository
import com.loosecannon.servicetag.data.room.RoomHealthSubjectRepository
import com.loosecannon.servicetag.data.room.RoomLinkRepository
import com.loosecannon.servicetag.data.room.RoomProfileRepository
import com.loosecannon.servicetag.data.room.RoomReferenceRepository
import com.loosecannon.servicetag.data.room.RoomScheduleLocalDeliveryRepository
import com.loosecannon.servicetag.data.room.RoomScheduleRepository
import com.loosecannon.servicetag.data.room.RoomScheduleStateRepository
import com.loosecannon.servicetag.data.room.RoomSeasonActivationRepository
import com.loosecannon.servicetag.data.room.RoomTagRepository
import com.loosecannon.servicetag.data.room.RoomUnitOfWork
import com.loosecannon.servicetag.data.room.inMemoryDb
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.prefs.AppPrefs
import com.loosecannon.servicetag.ui.maintenance.DueReadModel
import com.loosecannon.servicetag.prefs.KeyValueStore
import com.loosecannon.servicetag.reminders.ReminderSnooze
import com.loosecannon.servicetag.reminders.ScheduleStateReader
import com.loosecannon.servicetag.ui.health.AssetHealthReadModel
import com.loosecannon.servicetag.ui.maintenance.AttentionReadModel
import com.loosecannon.servicetag.ui.maintenance.CompletionFlow
import com.loosecannon.servicetag.ui.maintenance.LastCompletionEventId
import com.loosecannon.servicetag.ui.maintenance.ScanRoundMembership
import com.loosecannon.servicetag.ui.maintenance.ScanSheetOffer
import com.loosecannon.servicetag.ui.maintenance.ScheduleSnooze
import com.loosecannon.servicetag.ui.maintenance.scanSheetContentFor
import java.io.File
import java.time.ZoneOffset
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers

/**
 * `AppGraph` without a `Context`: the same members, built on `inMemoryDb()` and the real Room
 * repositories, so a ViewModel test exercises the production query, the production mapper and the
 * production invalidation flow rather than a hand-written fake that agrees with itself.
 *
 * The clock is a `var` a test moves by hand and the ids count up, so an assertion can name both.
 *
 * [queryContext] only matters when [db] is left to its default: a ViewModel or controller fixture
 * that put a test dispatcher on `Dispatchers.Main` should pass a `StandardTestDispatcher` on that
 * fixture's own `TestCoroutineScheduler` here (see `inMemoryDb`'s KDoc), so Room settles on the
 * same virtual clock the test drives rather than a real thread pool. A test that builds its own
 * [db] — because it needs a file-backed database, say — wires the dispatcher straight into that
 * builder instead and this parameter is moot.
 */
class FakeGraph(
    queryContext: CoroutineContext = Dispatchers.Default,
    val db: AppDatabase = inMemoryDb(queryContext),
) {

    /** Move this before a call to give the write a timestamp the test can assert on. */
    var now: Long = 1_000L

    val clock: Clock = Clock { now }

    private var seq = 0
    val ids: IdGenerator = IdGenerator { "00000000-0000-4000-8000-%012d".format(++seq) }

    val uow: UnitOfWork = RoomUnitOfWork(db)
    val assets: AssetRepository = RoomAssetRepository(db.assetDao())
    val tags: TagRepository = RoomTagRepository(db.nfcTagDao())
    val links: LinkRepository = RoomLinkRepository(db.externalLinkDao())
    val definitions: DefinitionRepository = RoomDefinitionRepository(db.definitionDao())
    val profiles: ProfileRepository = RoomProfileRepository(db.profileDao())
    val events: EventRepository = RoomEventRepository(db.eventDao())
    val attachments: AttachmentRepository = RoomAttachmentRepository(db.attachmentDao())
    val groups: GroupRepository = RoomGroupRepository(db.maintenanceGroupDao())
    val schedules: ScheduleRepository = RoomScheduleRepository(db.maintenanceScheduleDao())
    val closures: ClosureRepository = RoomClosureRepository(db.occurrenceClosureDao())
    val references: ReferenceRepository = RoomReferenceRepository(db.assetReferenceDao())
    // 1.4's three data ports, mirroring `AppGraph`'s fields by name.
    val seasonActivations: SeasonActivationRepository = RoomSeasonActivationRepository(db.seasonActivationDao())
    val conditions: ConditionRepository = RoomConditionRepository(db.assetConditionDao())
    val healthSubjects: HealthSubjectRepository = RoomHealthSubjectRepository(db.healthSubjectDao())
    val scheduleStates: ScheduleStateRepository = RoomScheduleStateRepository(db.scheduleStateDao())

    /** `T`, injected: a test says which day it is and the engine answers the same way every run. */
    var today: java.time.LocalDate = java.time.LocalDate.parse("2026-02-10")
    val todayPort: Today = Today { today }

    /** The real recompute over the real tables, so an event write in a test rebuilds for real. */
    val recomputeSchedules: RecomputeSchedules = RecomputeSchedules(
        schedules, scheduleStates, events, closures, groups, assets, seasonActivations, todayPort, clock,
        zone = { ZoneOffset.UTC },
    )

    /**
     * 1.4 — the asset health view and the asset-level attention rows, mirroring `AppGraph`'s two
     * fields by name (master plan §1). UTC, as the recompute above, so the pin floor is stable.
     */
    val assetHealthReadModel: AssetHealthReadModel = AssetHealthReadModel(
        assets, healthSubjects, schedules, events, profiles, seasonActivations, conditions,
        recomputeSchedules, todayPort, zone = { ZoneOffset.UTC },
    )
    val attentionReadModel: AttentionReadModel =
        AttentionReadModel(assets, assetHealthReadModel, todayPort)

    /**
     * 1.2 — the one due projection, mirroring `AppGraph`'s field so a view-model test takes the
     * same collaborator the app does: states through `readState`, `DueItem.health` from
     * [assetHealthReadModel], and the snooze from the device-local table, as `AppGraph` wires it.
     */
    val dueReadModel: DueReadModel = DueReadModel(
        schedules, assets, groups, definitions, recomputeSchedules, todayPort, assetHealthReadModel,
        snoozedUntilOf = { scheduleLocalDelivery.get(it)?.snoozedUntilAt },
    )

    /**
     * The delivery seam, mirroring `AppGraph`'s re-wiring (plan decision 47): derived state through
     * `readState`, so a stale row is derived for today and nothing is written.
     */
    val scheduleStateReader: ScheduleStateReader = ScheduleStateReader { id ->
        schedules.get(id)?.let { recomputeSchedules.readState(it) }
    }

    /** The sheet's last-completion seam, mirroring `AppGraph`'s: through `readState` (review M2). */
    val lastCompletionEventId: LastCompletionEventId = LastCompletionEventId { scheduleId ->
        schedules.get(scheduleId)?.let { recomputeSchedules.readState(it) }?.lastCompletionEventId
    }

    /** The scan's round seam and routing question, mirroring `AppGraph`'s two fields. */
    val scanRoundMembership: ScanRoundMembership = ScanRoundMembership { scheduleId ->
        schedules.get(scheduleId)?.let { recomputeSchedules.occurrenceOf(it) }
    }
    val scanSheetOffer: ScanSheetOffer = ScanSheetOffer { assetId ->
        scanSheetContentFor(assetId, dueReadModel, scanRoundMembership, assetHealthReadModel).opens
    }

    /**
     * The store a test drives by hand: `state` is a `var` and the bytes are a map, so a refusal
     * and a successful write are both one line away. `SafAttachmentStorage` itself is proved by
     * `SafAttachmentStorageTest` and on the emulator.
     */
    val attachmentStorage: FakeAttachmentStorage = FakeAttachmentStorage()

    /**
     * Mirrors `AppGraph.thumbnails` so a ViewModel test can take the same collaborators. The
     * decode itself needs `BitmapFactory`, so nothing on the JVM asks this for a real thumbnail
     * and the cache directory below is a path that is never created.
     */
    val thumbnails: Thumbnails =
        Thumbnails(File(System.getProperty("java.io.tmpdir"), "servicetag-jvm-thumbs"), attachmentStorage)

    val applyTemplate: ApplyTemplate = ApplyTemplate(definitions, profiles, assets, uow, ids, clock)
    val createAsset: CreateAsset = CreateAsset(assets, uow, ids, clock, applyTemplate)
    val updateAsset: UpdateAsset = UpdateAsset(assets, schedules, uow, clock, recomputeSchedules)

    /** 1.4 — the season model's commands, mirroring `AppGraph`'s five fields by name (master plan §1). */
    val setSeasonMode: SetSeasonMode =
        SetSeasonMode(assets, schedules, seasonActivations, uow, ids, clock, todayPort, recomputeSchedules)
    val setMaintenanceBreak: SetMaintenanceBreak =
        SetMaintenanceBreak(assets, schedules, uow, clock, recomputeSchedules)
    val recordSeasonActivation: RecordSeasonActivation =
        RecordSeasonActivation(assets, events, seasonActivations, uow, ids, clock, todayPort, recomputeSchedules)
    val getAssetSeason: GetAssetSeason = GetAssetSeason(assets, seasonActivations, uow, todayPort)
    val acceptSeasonOffer: AcceptSeasonOffer = AcceptSeasonOffer(seasonActivations, recordSeasonActivation, uow, todayPort)

    /** 1.4 — condition and health configuration, mirroring `AppGraph`'s six fields by name (master plan §1). */
    val recordCondition: RecordCondition = RecordCondition(assets, events, conditions, uow, ids, clock, todayPort)
    val acceptOperationalOffer: AcceptOperationalOffer = AcceptOperationalOffer(conditions, recordCondition, uow)
    val saveHealthSubject: SaveHealthSubject =
        SaveHealthSubject(healthSubjects, assets, schedules, profiles, uow, ids, clock)
    val archiveHealthSubject: ArchiveHealthSubject =
        ArchiveHealthSubject(healthSubjects, assets, schedules, uow, clock)
    val setHealthPolicy: SetHealthPolicy = SetHealthPolicy(assets, healthSubjects, uow, clock)
    val saveAssetSettings: SaveAssetSettings = SaveAssetSettings(
        assets, schedules, healthSubjects, seasonActivations, uow, ids, clock, todayPort, recomputeSchedules,
        applyTemplate,
    )
    val archiveAsset: ArchiveAsset =
        ArchiveAsset(assets, uow, clock) { recomputeSchedules.forAsset(it) }
    val retireAsset: RetireAsset =
        RetireAsset(assets, uow, clock) { recomputeSchedules.forAsset(it) }
    val deleteAsset: DeleteAsset =
        DeleteAsset(assets, events, attachments, attachmentStorage, uow, groups, schedules, closures)

    /** The same identity the app builds, read from the same BuildConfig fields (C9). */
    val tagIdentity: TagIdentity = TagIdentity(
        externalDomain = BuildConfig.NDEF_EXTERNAL_DOMAIN,
        typeName = BuildConfig.NDEF_TYPE_NAME,
        aarPackage = BuildConfig.NDEF_AAR_PACKAGE,
    )
    val ndefCodec: NdefCodec = NdefCodec(tagIdentity)

    val provisionTag: ProvisionTag = ProvisionTag(tags, assets, uow, ids, clock)
    val logEvent: LogEvent =
        LogEvent(events, definitions, profiles, assets, uow, ids, clock, recomputeSchedules)
    val updateEvent: UpdateEvent =
        UpdateEvent(events, definitions, profiles, uow, ids, clock, recomputeSchedules)
    val deleteEvent: DeleteEvent =
        DeleteEvent(events, attachments, attachmentStorage, uow, recomputeSchedules)
    val saveDefinition: SaveDefinition =
        SaveDefinition(definitions, events, profiles, assets, uow, ids, clock)
    val archiveDefinition: ArchiveDefinition = ArchiveDefinition(definitions, uow, clock)
    val deleteDefinition: DeleteDefinition = DeleteDefinition(definitions, events, profiles, uow)
    val reorderDefinitions: ReorderDefinitions = ReorderDefinitions(definitions, uow, clock)
    /**
     * Set from a test to gate [saveProfile]'s write: while non-null, the row it upserts parks on
     * this deferred before it reaches the table, so a test can prove a second `save()` call really
     * lands while the first one is still in flight rather than assuming it from frame timing.
     */
    var saveGate: CompletableDeferred<Unit>? = null

    private val gatedProfilesForSave: ProfileRepository = object : ProfileRepository by profiles {
        override suspend fun upsert(p: EventProfile) {
            saveGate?.await()
            profiles.upsert(p)
        }
    }

    val saveProfile: SaveProfile = SaveProfile(gatedProfilesForSave, definitions, assets, uow, ids, clock)
    val archiveProfile: ArchiveProfile = ArchiveProfile(profiles, uow, clock)
    val deleteProfile: DeleteProfile = DeleteProfile(profiles, uow)
    val reorderProfiles: ReorderProfiles = ReorderProfiles(profiles, uow, clock)

    // Phase 4A — attachments.
    val addAttachment: AddAttachment =
        AddAttachment(attachments, assets, events, attachmentStorage, uow, ids, clock)
    val updateAttachment: UpdateAttachment = UpdateAttachment(attachments, uow, clock)
    val deleteAttachment: DeleteAttachment = DeleteAttachment(attachments, attachmentStorage, uow)
    val restoreArtifacts: RestoreArtifacts = RestoreArtifacts(attachments, attachmentStorage)

    /** Device-local preferences, in a map: a test can read back exactly what the UI wrote. */
    val prefs: AppPrefs = AppPrefs(InMemoryKeyValueStore())

    /** Both halves of a set: `run().data` for the data archive, `run().plan` for the other one. */
    val exportBackupSet: ExportBackupSet = ExportBackupSet(
        assets, groups, tags, links, definitions, profiles, schedules, closures, events,
        attachments, references, seasonActivations, conditions, healthSubjects,
        uow, ids, clock, APP_VERSION, SCHEMA_VERSION,
    )
    val importBackupReplace: ImportBackupReplace = ImportBackupReplace(
        assets, groups, tags, links, definitions, profiles, schedules, closures, events,
        attachments, references, seasonActivations, conditions, healthSubjects, attachmentStorage, uow,
        // The real engine: "once, inside the transaction, after the last insert" is proved against
        // the seam in `:core`, so there is no counter to keep here.
        rebuildAll = { recomputeSchedules.all() },
    )
    val buildBackupMergePlan: BuildBackupMergePlan = BuildBackupMergePlan(
        assets, groups, tags, links, definitions, profiles, schedules, closures, events,
        attachments, references, seasonActivations, conditions, healthSubjects, attachmentStorage, uow,
    )

    /** How many times an apply asked for the total recompute. Mirrors `AppGraph`'s no-op seam. */
    var rebuilds = 0

    val applyBackupMergePlan: ApplyBackupMergePlan = ApplyBackupMergePlan(
        assets, groups, tags, links, definitions, profiles, schedules, closures, events,
        attachments, references, seasonActivations, conditions, healthSubjects, attachmentStorage, uow,
        rebuildAll = { rebuilds += 1 },
    )
    val importBackupMerge: ImportBackupMerge =
        ImportBackupMerge(buildBackupMergePlan, applyBackupMergePlan)

    /**
     * 1.2 — the group completion path, so a read-model or view-model test marks members done
     * through the production use case rather than hand-writing an `occurrence_on`. Mirrors
     * `AppGraph`'s field; the schedule and group use cases it sits beside are declared below.
     */
    val completeGroupMembers: CompleteGroupMembers = CompleteGroupMembers(
        schedules, groups, events, closures, definitions, profiles, uow, ids, clock, recomputeSchedules,
    )

    /**
     * 1.2 — the schedule editor's and the detail screen's own use cases, mirroring `AppGraph`'s
     * fields, so a view-model test drives the production rule rather than a fake that agrees with
     * it. `saveGroup` is here for the same reason: a group-targeted schedule needs a real group
     * with real membership windows behind it.
     */
    val saveSchedule: SaveSchedule = SaveSchedule(
        schedules, assets, groups, definitions, profiles, uow, ids, clock, recomputeSchedules, healthSubjects,
    )
    val completeSchedule: CompleteSchedule =
        CompleteSchedule(schedules, events, definitions, profiles, uow, ids, clock, recomputeSchedules)
    val postponeSchedule: PostponeSchedule = PostponeSchedule(schedules, uow, recomputeSchedules)
    val pauseSchedule: PauseSchedule = PauseSchedule(schedules, uow, recomputeSchedules)
    val archiveSchedule: ArchiveSchedule =
        ArchiveSchedule(schedules, uow, recomputeSchedules, healthSubjects, assets, clock)
    val closeRound: CloseRound =
        CloseRound(schedules, closures, uow, ids, clock, todayPort, recomputeSchedules)
    val saveGroup: SaveGroup = SaveGroup(groups, assets, uow, ids, clock)
    val archiveGroup: ArchiveGroup = ArchiveGroup(groups, uow, clock)

    /** The one completion mechanism, over the real use cases — always UTC, so a `tzId` is stable. */
    val completionFlow: CompletionFlow = CompletionFlow(
        schedules, definitions, completeSchedule, completeGroupMembers, todayPort,
    ) { java.time.ZoneOffset.UTC }

    /**
     * The in-app snooze, over **B06's real use case and the real device-local table**: the seam
     * B14's detail screen takes is satisfied by `ReminderSnooze::snooze`, so the notification
     * action's "Snooze 1 day" and the in-app "Snooze" are the same code and not merely the same
     * length. A snooze test therefore reads the row back out of [scheduleLocalDelivery] and can
     * still assert the two things that matter: no `*_on` column moved, and no event was written.
     */
    val scheduleLocalDelivery: ScheduleLocalDeliveryRepository =
        RoomScheduleLocalDeliveryRepository(db.scheduleLocalDeliveryDao())
    val reminderSnooze: ReminderSnooze = ReminderSnooze(scheduleLocalDelivery, clock)
    val scheduleSnooze: ScheduleSnooze = ScheduleSnooze(reminderSnooze::snooze)

    fun close() = db.close()

    private companion object {
        const val APP_VERSION = "test"

        /**
         * **The production constant, not a copy of its current value.** A fake that stamped its own
         * number into every archive it exports would go on claiming the old schema after a bump,
         * and the archives these tests round-trip would describe a database that no longer exists.
         */
        const val SCHEMA_VERSION = AppGraph.SCHEMA_VERSION
    }
}

/** The `SharedPreferences` side of [AppPrefs] without Android under it. */
private class InMemoryKeyValueStore : KeyValueStore {
    private val longs = mutableMapOf<String, Long>()
    private val strings = mutableMapOf<String, String>()

    override fun getLong(key: String): Long? = longs[key]
    override fun putLong(key: String, value: Long) { longs[key] = value }
    override fun getString(key: String): String? = strings[key]
    override fun putString(key: String, value: String) { strings[key] = value }
}
