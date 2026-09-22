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
import com.loosecannon.servicetag.core.ports.DefinitionRepository
import com.loosecannon.servicetag.core.ports.EventRepository
import com.loosecannon.servicetag.core.ports.GroupRepository
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.LinkRepository
import com.loosecannon.servicetag.core.ports.ProfileRepository
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.ScheduleStateRepository
import com.loosecannon.servicetag.core.ports.TagRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.ports.UnitOfWork
import com.loosecannon.servicetag.core.usecase.AddAttachment
import com.loosecannon.servicetag.core.usecase.ApplyTemplate
import com.loosecannon.servicetag.core.usecase.ApplyBackupMergePlan
import com.loosecannon.servicetag.core.usecase.ArchiveAsset
import com.loosecannon.servicetag.core.usecase.ArchiveDefinition
import com.loosecannon.servicetag.core.usecase.ArchiveProfile
import com.loosecannon.servicetag.core.usecase.BuildBackupMergePlan
import com.loosecannon.servicetag.core.usecase.CompleteGroupMembers
import com.loosecannon.servicetag.core.usecase.CreateAsset
import com.loosecannon.servicetag.core.usecase.DeleteAsset
import com.loosecannon.servicetag.core.usecase.DeleteAttachment
import com.loosecannon.servicetag.core.usecase.DeleteDefinition
import com.loosecannon.servicetag.core.usecase.DeleteEvent
import com.loosecannon.servicetag.core.usecase.DeleteProfile
import com.loosecannon.servicetag.core.usecase.ExportBackupSet
import com.loosecannon.servicetag.core.usecase.ImportBackupMerge
import com.loosecannon.servicetag.core.usecase.ImportBackupReplace
import com.loosecannon.servicetag.core.usecase.LogEvent
import com.loosecannon.servicetag.core.usecase.ProvisionTag
import com.loosecannon.servicetag.core.usecase.RecomputeSchedules
import com.loosecannon.servicetag.core.usecase.ReorderDefinitions
import com.loosecannon.servicetag.core.usecase.ReorderProfiles
import com.loosecannon.servicetag.core.usecase.RestoreArtifacts
import com.loosecannon.servicetag.core.usecase.RetireAsset
import com.loosecannon.servicetag.core.usecase.SaveDefinition
import com.loosecannon.servicetag.core.usecase.SaveProfile
import com.loosecannon.servicetag.core.usecase.UpdateAsset
import com.loosecannon.servicetag.core.usecase.UpdateAttachment
import com.loosecannon.servicetag.core.usecase.UpdateEvent
import com.loosecannon.servicetag.data.room.AppDatabase
import com.loosecannon.servicetag.data.room.RoomAssetRepository
import com.loosecannon.servicetag.data.room.RoomAttachmentRepository
import com.loosecannon.servicetag.data.room.RoomClosureRepository
import com.loosecannon.servicetag.data.room.RoomDefinitionRepository
import com.loosecannon.servicetag.data.room.RoomEventRepository
import com.loosecannon.servicetag.data.room.RoomGroupRepository
import com.loosecannon.servicetag.data.room.RoomLinkRepository
import com.loosecannon.servicetag.data.room.RoomProfileRepository
import com.loosecannon.servicetag.data.room.RoomScheduleRepository
import com.loosecannon.servicetag.data.room.RoomScheduleStateRepository
import com.loosecannon.servicetag.data.room.RoomTagRepository
import com.loosecannon.servicetag.data.room.RoomUnitOfWork
import com.loosecannon.servicetag.data.room.inMemoryDb
import com.loosecannon.servicetag.prefs.AppPrefs
import com.loosecannon.servicetag.prefs.KeyValueStore
import java.io.File
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
    val scheduleStates: ScheduleStateRepository = RoomScheduleStateRepository(db.scheduleStateDao())

    /** `T`, injected: a test says which day it is and the engine answers the same way every run. */
    var today: java.time.LocalDate = java.time.LocalDate.parse("2026-02-10")
    val todayPort: Today = Today { today }

    /** The real recompute over the real tables, so an event write in a test rebuilds for real. */
    val recomputeSchedules: RecomputeSchedules = RecomputeSchedules(
        schedules, scheduleStates, events, closures, groups, assets, todayPort, clock,
    )

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
    val updateAsset: UpdateAsset = UpdateAsset(assets, uow, clock)
    val archiveAsset: ArchiveAsset =
        ArchiveAsset(assets, uow, clock) { recomputeSchedules.forAsset(it) }
    val retireAsset: RetireAsset =
        RetireAsset(assets, uow, clock) { recomputeSchedules.forAsset(it) }
    val deleteAsset: DeleteAsset = DeleteAsset(assets, events, attachments, attachmentStorage, uow)

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
        attachments, uow, ids, clock, APP_VERSION, SCHEMA_VERSION,
    )
    val importBackupReplace: ImportBackupReplace = ImportBackupReplace(
        assets, groups, tags, links, definitions, profiles, schedules, closures, events,
        attachments, attachmentStorage, uow,
        // The real engine: "once, inside the transaction, after the last insert" is proved against
        // the seam in `:core`, so there is no counter to keep here.
        rebuildAll = { recomputeSchedules.all() },
    )
    val buildBackupMergePlan: BuildBackupMergePlan = BuildBackupMergePlan(
        assets, groups, tags, links, definitions, profiles, schedules, closures, events,
        attachments, attachmentStorage, uow,
    )

    /** How many times an apply asked for the total recompute. Mirrors `AppGraph`'s no-op seam. */
    var rebuilds = 0

    val applyBackupMergePlan: ApplyBackupMergePlan = ApplyBackupMergePlan(
        assets, groups, tags, links, definitions, profiles, schedules, closures, events,
        attachments, attachmentStorage, uow,
        rebuildAll = { rebuilds += 1 },
    )
    val importBackupMerge: ImportBackupMerge =
        ImportBackupMerge(buildBackupMergePlan, applyBackupMergePlan)

    /**
     * 1.2 — the group completion path, so a read-model test can mark members done through the
     * production use case rather than hand-writing an `occurrence_on`. Mirrors `AppGraph`'s field.
     */
    val completeGroupMembers: CompleteGroupMembers = CompleteGroupMembers(
        schedules, groups, events, closures, definitions, profiles, uow, ids, clock, recomputeSchedules,
    )

    fun close() = db.close()

    private companion object {
        const val APP_VERSION = "test"
        const val SCHEMA_VERSION = 6
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
