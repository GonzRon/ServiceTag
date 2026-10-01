package com.loosecannon.servicetag.testing

import com.loosecannon.nfc.tagcore.TagIdentity
import com.loosecannon.servicetag.BuildConfig
import com.loosecannon.servicetag.attachments.Thumbnails
import com.loosecannon.servicetag.core.condition.needsIncident
import com.loosecannon.servicetag.core.model.EventProfile
import com.loosecannon.servicetag.core.nfc.NdefCodec
import com.loosecannon.servicetag.core.ports.AssetLoanRepository
import com.loosecannon.servicetag.core.ports.AssetSuccessionRepository
import com.loosecannon.servicetag.core.ports.AssetSupplyRepository
import com.loosecannon.servicetag.core.ports.SupplyItemRepository
import com.loosecannon.servicetag.core.ports.TransferRecordRepository
import com.loosecannon.servicetag.core.model.lineageFor
import com.loosecannon.servicetag.core.transfer.HeldWriteGuard
import com.loosecannon.servicetag.core.usecase.BackupRepositories
import com.loosecannon.servicetag.core.usecase.CreateTransferPack
import com.loosecannon.servicetag.core.usecase.MarkTransferredOut
import com.loosecannon.servicetag.core.usecase.WithdrawTransferRecord
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.AttachmentRepository
import com.loosecannon.servicetag.core.ports.CategoryRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.ClosureRepository
import com.loosecannon.servicetag.core.ports.ConditionRepository
import com.loosecannon.servicetag.core.ports.DeadlineLocalDeliveryRepository
import com.loosecannon.servicetag.core.ports.DefinitionRepository
import com.loosecannon.servicetag.core.ports.EventRepository
import com.loosecannon.servicetag.core.ports.GroupRepository
import com.loosecannon.servicetag.core.ports.HealthSubjectRepository
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.InstalledComponentRepository
import com.loosecannon.servicetag.core.ports.LinkRepository
import com.loosecannon.servicetag.core.ports.ProfileRepository
import com.loosecannon.servicetag.core.ports.ReferenceRepository
import com.loosecannon.servicetag.core.ports.ScheduleLocalDeliveryRepository
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.ScheduleStateRepository
import com.loosecannon.servicetag.core.ports.SeasonActivationRepository
import com.loosecannon.servicetag.core.ports.ServiceCaseEntryRepository
import com.loosecannon.servicetag.core.ports.ServiceCaseRepository
import com.loosecannon.servicetag.core.ports.TagRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.ports.UnitOfWork
import com.loosecannon.servicetag.core.usecase.AcceptImpairmentOffer
import com.loosecannon.servicetag.core.usecase.AcceptOperationalOffer
import com.loosecannon.servicetag.core.usecase.AcceptSeasonOffer
import com.loosecannon.servicetag.core.usecase.AddAssetSupply
import com.loosecannon.servicetag.core.usecase.AddAttachment
import com.loosecannon.servicetag.core.usecase.AddServiceCaseEntry
import com.loosecannon.servicetag.core.usecase.ApplyTemplate
import com.loosecannon.servicetag.core.usecase.ApplyBackupMergePlan
import com.loosecannon.servicetag.core.usecase.ArchiveAsset
import com.loosecannon.servicetag.core.usecase.ArchiveDefinition
import com.loosecannon.servicetag.core.usecase.ArchiveGroup
import com.loosecannon.servicetag.core.usecase.ArchiveHealthSubject
import com.loosecannon.servicetag.core.usecase.ArchiveProfile
import com.loosecannon.servicetag.core.usecase.ArchiveSchedule
import com.loosecannon.servicetag.core.usecase.ArchiveSupplyItem
import com.loosecannon.servicetag.core.usecase.BuildBackupMergePlan
import com.loosecannon.servicetag.core.usecase.CloseRound
import com.loosecannon.servicetag.core.usecase.CompleteGroupMembers
import com.loosecannon.servicetag.core.usecase.CompleteSchedule
import com.loosecannon.servicetag.core.usecase.CreateAsset
import com.loosecannon.servicetag.core.usecase.DeleteAsset
import com.loosecannon.servicetag.core.usecase.DeleteAttachment
import com.loosecannon.servicetag.core.usecase.DeleteCategory
import com.loosecannon.servicetag.core.usecase.DeleteDefinition
import com.loosecannon.servicetag.core.usecase.DeleteEvent
import com.loosecannon.servicetag.core.usecase.DeleteProfile
import com.loosecannon.servicetag.core.usecase.ExportBackupSet
import com.loosecannon.servicetag.core.usecase.GetAssetSeason
import com.loosecannon.servicetag.core.usecase.ImportBackupMerge
import com.loosecannon.servicetag.core.usecase.ImportTransferPack
import com.loosecannon.servicetag.ui.transfer.`import`.CacheTransferPackInbox
import com.loosecannon.servicetag.core.usecase.ImportBackupReplace
import com.loosecannon.servicetag.core.usecase.InstallComponent
import com.loosecannon.servicetag.core.usecase.LendAsset
import com.loosecannon.servicetag.core.usecase.LogEvent
import com.loosecannon.servicetag.core.usecase.OpenServiceCase
import com.loosecannon.servicetag.core.usecase.PauseSchedule
import com.loosecannon.servicetag.core.usecase.PostponeSchedule
import com.loosecannon.servicetag.core.usecase.PromoteCategory
import com.loosecannon.servicetag.core.usecase.ProvisionTag
import com.loosecannon.servicetag.core.usecase.RecomputeSchedules
import com.loosecannon.servicetag.core.usecase.RecordCondition
import com.loosecannon.servicetag.core.usecase.RecordConditionWithIncident
import com.loosecannon.servicetag.core.usecase.RecordSeasonActivation
import com.loosecannon.servicetag.core.usecase.RemoveAssetSupply
import com.loosecannon.servicetag.core.usecase.RemoveInstalledComponent
import com.loosecannon.servicetag.core.usecase.RenameCategory
import com.loosecannon.servicetag.core.usecase.ReorderDefinitions
import com.loosecannon.servicetag.core.usecase.ReorderProfiles
import com.loosecannon.servicetag.core.usecase.RestoreArtifacts
import com.loosecannon.servicetag.core.usecase.RelinkLoanContact
import com.loosecannon.servicetag.core.usecase.RetireAsset
import com.loosecannon.servicetag.core.usecase.ReturnLoan
import com.loosecannon.servicetag.core.usecase.SaveAssetSettings
import com.loosecannon.servicetag.core.usecase.SaveDefinition
import com.loosecannon.servicetag.core.usecase.SaveGroup
import com.loosecannon.servicetag.core.usecase.SaveHealthSubject
import com.loosecannon.servicetag.core.usecase.SaveProfile
import com.loosecannon.servicetag.core.usecase.RepairScheduleProviders
import com.loosecannon.servicetag.core.usecase.ReplaceAsset
import com.loosecannon.servicetag.core.usecase.ReplaceInstalledComponent
import com.loosecannon.servicetag.core.usecase.BindTag
import com.loosecannon.servicetag.core.usecase.SaveSchedule
import com.loosecannon.servicetag.core.usecase.SaveSupplyItem
import com.loosecannon.servicetag.core.usecase.SetHealthPolicy
import com.loosecannon.servicetag.core.usecase.SetMaintenanceBreak
import com.loosecannon.servicetag.core.usecase.SetSeasonMode
import com.loosecannon.servicetag.core.usecase.SetWarrantyReminder
import com.loosecannon.servicetag.core.usecase.UpdateAsset
import com.loosecannon.servicetag.core.usecase.UpdateAssetSupply
import com.loosecannon.servicetag.core.usecase.UpdateAttachment
import com.loosecannon.servicetag.core.usecase.UpdateEvent
import com.loosecannon.servicetag.core.usecase.UpdateInstalledComponent
import com.loosecannon.servicetag.core.usecase.UpdateLoan
import com.loosecannon.servicetag.core.usecase.UpdateServiceCase
import com.loosecannon.servicetag.data.room.AppDatabase
import com.loosecannon.servicetag.data.room.RoomAssetLoanRepository
import com.loosecannon.servicetag.data.room.RoomAssetSuccessionRepository
import com.loosecannon.servicetag.data.room.RoomAssetSupplyRepository
import com.loosecannon.servicetag.data.room.RoomSupplyItemRepository
import com.loosecannon.servicetag.data.room.RoomTransferRecordRepository
import com.loosecannon.servicetag.data.room.RoomAssetRepository
import com.loosecannon.servicetag.data.room.RoomAttachmentRepository
import com.loosecannon.servicetag.data.room.RoomCategoryRepository
import com.loosecannon.servicetag.data.room.RoomClosureRepository
import com.loosecannon.servicetag.data.room.RoomConditionRepository
import com.loosecannon.servicetag.data.room.RoomDeadlineLocalDeliveryRepository
import com.loosecannon.servicetag.data.room.RoomDefinitionRepository
import com.loosecannon.servicetag.data.room.RoomEventRepository
import com.loosecannon.servicetag.data.room.RoomGroupRepository
import com.loosecannon.servicetag.data.room.RoomHealthSubjectRepository
import com.loosecannon.servicetag.data.room.RoomInstalledComponentRepository
import com.loosecannon.servicetag.data.room.RoomLinkRepository
import com.loosecannon.servicetag.data.room.RoomProfileRepository
import com.loosecannon.servicetag.data.room.RoomReferenceRepository
import com.loosecannon.servicetag.data.room.RoomScheduleLocalDeliveryRepository
import com.loosecannon.servicetag.data.room.RoomScheduleRepository
import com.loosecannon.servicetag.data.room.RoomScheduleStateRepository
import com.loosecannon.servicetag.data.room.RoomSeasonActivationRepository
import com.loosecannon.servicetag.data.room.RoomServiceCaseEntryRepository
import com.loosecannon.servicetag.data.room.RoomServiceCaseRepository
import com.loosecannon.servicetag.data.room.RoomTagRepository
import com.loosecannon.servicetag.data.room.RoomUnitOfWork
import com.loosecannon.servicetag.data.room.inMemoryDb
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.prefs.AppPrefs
import com.loosecannon.servicetag.prefs.InstallationIdentity
import com.loosecannon.servicetag.ui.condition.EventOffers
import com.loosecannon.servicetag.ui.condition.ImpairmentOffers
import com.loosecannon.servicetag.ui.condition.OperationalOffers
import com.loosecannon.servicetag.ui.condition.SeasonOffers
import com.loosecannon.servicetag.ui.maintenance.DueReadModel
import com.loosecannon.servicetag.prefs.KeyValueStore
import com.loosecannon.servicetag.reminders.ReminderSnooze
import com.loosecannon.servicetag.reminders.ScheduleStateReader
import com.loosecannon.servicetag.ui.health.AssetHealthReadModel
import com.loosecannon.servicetag.ui.health.inService
import com.loosecannon.servicetag.ui.journal.CaseLinks
import com.loosecannon.servicetag.ui.journal.caseLinksOf
import com.loosecannon.servicetag.ui.maintenance.AttentionReadModel
import com.loosecannon.servicetag.ui.maintenance.CompletionFlow
import com.loosecannon.servicetag.ui.maintenance.IncidentNeed
import com.loosecannon.servicetag.ui.maintenance.LastCompletionEventId
import com.loosecannon.servicetag.ui.maintenance.ScanRoundMembership
import com.loosecannon.servicetag.ui.maintenance.ScanSheetOffer
import com.loosecannon.servicetag.ui.maintenance.ScheduleSnooze
import com.loosecannon.servicetag.ui.maintenance.scanSheetContentFor
import com.loosecannon.servicetag.core.fetch.FetchDocument
import com.loosecannon.servicetag.core.fetch.HopPolicy
import com.loosecannon.servicetag.core.fetch.HostResolver
import com.loosecannon.servicetag.core.references.LinkLaunchPolicy
import com.loosecannon.servicetag.core.usecase.MaterializeReference
import com.loosecannon.servicetag.fetch.CacheStagingArea
import java.io.File
import java.time.ZoneOffset
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex

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

    private val countingUow = CountingUnitOfWork(RoomUnitOfWork(db))
    val uow: UnitOfWork = countingUow

    /** #92: the write transactions committed through [uow], so a route test can say "wrote nothing" as a number. */
    val commits: Int get() = countingUow.commits

    /** #92 (C5a): mirroring `AppGraph`'s field by name, over a fresh temporary directory in place of the no-backup one. */
    val installationIdentity: InstallationIdentity =
        InstallationIdentity(kotlin.io.path.createTempDirectory("installation").toFile())

    val links: LinkRepository = RoomLinkRepository(db.externalLinkDao())
    /** #77's transfer records, mirroring `AppGraph`'s field by name; before the ports, which its guard reads. */
    val transferRecords: TransferRecordRepository = RoomTransferRecordRepository(db.transferRecordDao())

    // #77 (C12): the write guard over the eighteen asset-owned ports, wired exactly as `AppGraph` wires it, so a
    // view-model or route test writes through the same refusal the app does.
    private val roomEvents = RoomEventRepository(db.eventDao())
    private val roomDefinitions = RoomDefinitionRepository(db.definitionDao())
    private val roomProfiles = RoomProfileRepository(db.profileDao())
    private val roomGroups = RoomGroupRepository(db.maintenanceGroupDao())
    private val roomSchedules = RoomScheduleRepository(db.maintenanceScheduleDao())
    private val roomServiceCases = RoomServiceCaseRepository(db.serviceCaseDao())
    private val heldWriteGuard = HeldWriteGuard(
        transferRecords, roomEvents, roomDefinitions, roomProfiles, roomGroups, roomSchedules, roomServiceCases, links,
    )

    private val roomAssets = RoomAssetRepository(db.assetDao())
    val assets: AssetRepository = heldWriteGuard.assets(roomAssets)
    val tags: TagRepository = heldWriteGuard.tags(RoomTagRepository(db.nfcTagDao()))
    val definitions: DefinitionRepository = heldWriteGuard.definitions(roomDefinitions)
    val profiles: ProfileRepository = heldWriteGuard.profiles(roomProfiles)
    val events: EventRepository = heldWriteGuard.events(roomEvents)
    val attachments: AttachmentRepository = heldWriteGuard.attachments(RoomAttachmentRepository(db.attachmentDao()))
    val groups: GroupRepository = heldWriteGuard.groups(roomGroups)
    val schedules: ScheduleRepository = heldWriteGuard.schedules(roomSchedules)
    val closures: ClosureRepository = heldWriteGuard.closures(RoomClosureRepository(db.occurrenceClosureDao()))
    val references: ReferenceRepository = heldWriteGuard.references(RoomReferenceRepository(db.assetReferenceDao()))
    // 1.4's three data ports, mirroring `AppGraph`'s fields by name.
    val seasonActivations: SeasonActivationRepository =
        heldWriteGuard.activations(RoomSeasonActivationRepository(db.seasonActivationDao()))
    val conditions: ConditionRepository = heldWriteGuard.conditions(RoomConditionRepository(db.assetConditionDao()))
    val healthSubjects: HealthSubjectRepository =
        heldWriteGuard.subjects(RoomHealthSubjectRepository(db.healthSubjectDao()))
    /** #74's catalog rows, mirroring `AppGraph`'s field by name. */
    val categories: CategoryRepository = RoomCategoryRepository(db.assetCategoryDao())
    /** #79's two case ports, mirroring `AppGraph`'s fields by name. */
    val serviceCases: ServiceCaseRepository = heldWriteGuard.cases(roomServiceCases)
    val serviceCaseEntries: ServiceCaseEntryRepository =
        heldWriteGuard.entries(RoomServiceCaseEntryRepository(db.serviceCaseEntryDao()))
    /** #72's loan port, mirroring `AppGraph`'s field by name. */
    val loans: AssetLoanRepository = heldWriteGuard.loans(RoomAssetLoanRepository(db.assetLoanDao()))
    /** #86 (C6, MJ-2) — the guarded port for every consumer; the raw one for the merge apply alone, as `AppGraph`. */
    private val roomAssetSuccessions = RoomAssetSuccessionRepository(db.assetSuccessionDao())
    val assetSuccessions: AssetSuccessionRepository = heldWriteGuard.successions(roomAssetSuccessions)
    /** #15 — the catalog, unwrapped, and its applicability behind the guard (C14), as `AppGraph`'s. */
    val supplyItems: SupplyItemRepository = RoomSupplyItemRepository(db.supplyItemDao())
    val assetSupplies: AssetSupplyRepository =
        heldWriteGuard.assetSupplies(RoomAssetSupplyRepository(db.assetSupplyDao()))
    /** #47 — installed components behind the guard (C14), the merge apply's included, as `AppGraph`'s. */
    val installedComponents: InstalledComponentRepository =
        heldWriteGuard.installedComponents(RoomInstalledComponentRepository(db.installedComponentDao()))
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
        assets, healthSubjects, schedules, scheduleStates, events, profiles, seasonActivations, conditions,
        recomputeSchedules, todayPort, zone = { ZoneOffset.UTC }, transfers = transferRecords,
    )
    val attentionReadModel: AttentionReadModel =
        AttentionReadModel(assets, assetHealthReadModel, todayPort, transferRecords)

    /**
     * 1.2 — the one due projection, mirroring `AppGraph`'s field so a view-model test takes the
     * same collaborator the app does: states through `readState`, `DueItem.health` from
     * [assetHealthReadModel], and the snooze from the device-local table, as `AppGraph` wires it.
     */
    val dueReadModel: DueReadModel = DueReadModel(
        schedules, assets, groups, definitions, recomputeSchedules, todayPort, assetHealthReadModel,
        snoozedUntilOf = { scheduleLocalDelivery.get(it)?.snoozedUntilAt },
        transfers = transferRecords,
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

    /** #82 — the scan sheet's Incident flag, mirroring `AppGraph`'s field (C11). */
    val incidentNeed: IncidentNeed = IncidentNeed { assetId ->
        val asset = assets.get(assetId) ?: return@IncidentNeed false
        needsIncident(asset.inService, conditions.forAsset(assetId), events.forAsset(assetId))
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

    /** #74 — promotion, rename and delete, mirroring `AppGraph`'s three fields by name (C14). */
    val promoteCategory: PromoteCategory = PromoteCategory(categories)
    val renameCategory: RenameCategory = RenameCategory(categories, heldWriteGuard.catalogAssets(roomAssets), uow, clock)
    val deleteCategory: DeleteCategory = DeleteCategory(categories, assets, uow)

    val createAsset: CreateAsset = CreateAsset(assets, uow, ids, clock, applyTemplate, promoteCategory)
    val updateAsset: UpdateAsset = UpdateAsset(assets, schedules, uow, clock, recomputeSchedules, promoteCategory)

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
    // #82: the combined write (Workflow A) and the impairment offer's accept (Workflow B), as `AppGraph` wires them.
    val recordConditionWithIncident: RecordConditionWithIncident = RecordConditionWithIncident(
        events, definitions, profiles, assets, supplyItems,
        uow, ids, clock, recomputeSchedules, conditions, todayPort, recordCondition,
    )
    val acceptImpairmentOffer: AcceptImpairmentOffer = AcceptImpairmentOffer(conditions, recordCondition, uow)
    val saveHealthSubject: SaveHealthSubject =
        SaveHealthSubject(healthSubjects, assets, schedules, profiles, uow, ids, clock)
    val archiveHealthSubject: ArchiveHealthSubject =
        ArchiveHealthSubject(healthSubjects, assets, schedules, uow, clock)
    val setHealthPolicy: SetHealthPolicy = SetHealthPolicy(assets, healthSubjects, uow, clock)
    val setWarrantyReminder: SetWarrantyReminder = SetWarrantyReminder(assets, uow, clock)
    // #79 — the three case writers, mirroring `AppGraph`'s fields by name.
    val openServiceCase: OpenServiceCase = OpenServiceCase(assets, events, serviceCases, uow, ids, clock, todayPort)
    val updateServiceCase: UpdateServiceCase = UpdateServiceCase(events, serviceCases, uow, clock, todayPort)
    val addServiceCaseEntry: AddServiceCaseEntry =
        AddServiceCaseEntry(serviceCases, serviceCaseEntries, uow, ids, clock, todayPort)
    val caseLinks: CaseLinks = caseLinksOf(events, serviceCases)
    // #72 — the four loan writers, mirroring `AppGraph`'s fields by name.
    val lendAsset: LendAsset = LendAsset(assets, loans, uow, ids, clock, todayPort)
    val updateLoan: UpdateLoan = UpdateLoan(loans, uow, clock, todayPort)
    val returnLoan: ReturnLoan = ReturnLoan(loans, uow, clock, todayPort)
    val relinkLoanContact: RelinkLoanContact = RelinkLoanContact(loans, uow, clock)
    val saveAssetSettings: SaveAssetSettings = SaveAssetSettings(
        assets, schedules, healthSubjects, seasonActivations, uow, ids, clock, todayPort, recomputeSchedules,
        applyTemplate, promoteCategory,
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
        LogEvent(events, definitions, profiles, assets, supplyItems, uow, ids, clock, recomputeSchedules)
    val updateEvent: UpdateEvent =
        UpdateEvent(events, definitions, profiles, supplyItems, uow, ids, clock, recomputeSchedules)
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

    val saveProfile: SaveProfile = SaveProfile(gatedProfilesForSave, definitions, assets, supplyItems, uow, ids, clock)
    val archiveProfile: ArchiveProfile = ArchiveProfile(profiles, uow, clock)
    val deleteProfile: DeleteProfile = DeleteProfile(profiles, uow)
    val reorderProfiles: ReorderProfiles = ReorderProfiles(profiles, uow, clock)

    // Phase 4A — attachments.
    val addAttachment: AddAttachment =
        AddAttachment(attachments, assets, events, attachmentStorage, uow, ids, clock)
    val updateAttachment: UpdateAttachment = UpdateAttachment(attachments, uow, clock)
    val deleteAttachment: DeleteAttachment = DeleteAttachment(attachments, attachmentStorage, uow)
    val restoreArtifacts: RestoreArtifacts = RestoreArtifacts(attachments, attachmentStorage)

    /**
     * #85 (C19) — Save as document, mirroring `AppGraph.materializeReference` over app-side fakes: [documentTransport]
     * answers each URL a test serves, every host resolves to `203.0.113.10` (a documentation address, so the host rule
     * allows it), and staging is the real [CacheStagingArea] in a temporary directory. [networkGranted] is the
     * permission check. The fetch runs on [queryContext], so a fixture on a test scheduler keeps it there.
     */
    val documentTransport: FakeDocumentTransport = FakeDocumentTransport()
    var networkGranted: Boolean = true
    /** #92: the staging directory itself, so a route test can say "staging is empty" as a listing. */
    val materializeStagingDir: File = kotlin.io.path.createTempDirectory("materialize").toFile()
    val materializeStaging: CacheStagingArea = CacheStagingArea(materializeStagingDir, ids)

    /** #92 (C33): mirroring `AppGraph`'s process-wide lock for the API's long writes, one per graph. */
    val apiLongWrites: Mutex = Mutex()
    val materializeReference: MaterializeReference =
        HopPolicy(HostResolver { listOf(byteArrayOf(203.toByte(), 0, 113, 10)) }).let { hops ->
            MaterializeReference(
                references, attachments, attachmentStorage, LinkLaunchPolicy(), hops,
                FetchDocument(documentTransport, hops, materializeStaging, io = queryContext),
                addAttachment, { networkGranted }, clock,
            )
        }

    /** Device-local preferences, in a map: a test can read back exactly what the UI wrote. */
    val prefs: AppPrefs = AppPrefs(InMemoryKeyValueStore())

    /** Both halves of a set: `run().data` for the data archive, `run().plan` for the other one. */
    val exportBackupSet: ExportBackupSet = ExportBackupSet(
        assets, groups, tags, links, definitions, profiles, schedules, closures, events,
        attachments, references, seasonActivations, conditions, healthSubjects, categories,
        serviceCases, serviceCaseEntries, loans, transferRecords,
        assetSuccessions, supplyItems, assetSupplies, installedComponents, uow, ids, clock, APP_VERSION, SCHEMA_VERSION,
    )
    val importBackupReplace: ImportBackupReplace = ImportBackupReplace(
        assets, groups, tags, links, definitions, profiles, schedules, closures, events,
        attachments, references, seasonActivations, conditions, healthSubjects, categories,
        serviceCases, serviceCaseEntries, loans, transferRecords, assetSuccessions, supplyItems, assetSupplies,
        installedComponents, attachmentStorage, uow,
        // The real engine: "once, inside the transaction, after the last insert" is proved against
        // the seam in `:core`, so there is no counter to keep here.
        rebuildAll = { recomputeSchedules.all() },
    )
    val buildBackupMergePlan: BuildBackupMergePlan = BuildBackupMergePlan(
        assets, groups, tags, links, definitions, profiles, schedules, closures, events,
        attachments, references, seasonActivations, conditions, healthSubjects, categories,
        serviceCases, serviceCaseEntries, loans, transferRecords, assetSuccessions, supplyItems, assetSupplies,
        installedComponents, attachmentStorage, uow,
    )

    /** How many times an apply asked for the total recompute. Mirrors `AppGraph`'s no-op seam. */
    var rebuilds = 0

    val applyBackupMergePlan: ApplyBackupMergePlan = ApplyBackupMergePlan(
        assets, groups, tags, links, definitions, profiles, schedules, closures, events,
        attachments, references, seasonActivations, conditions, healthSubjects, categories,
        serviceCases, serviceCaseEntries, loans, transferRecords, roomAssetSuccessions, supplyItems, assetSupplies,
        installedComponents, attachmentStorage, uow,
        rebuildAll = { rebuilds += 1 },
    )
    val importBackupMerge: ImportBackupMerge =
        ImportBackupMerge(buildBackupMergePlan, applyBackupMergePlan)

    /** #77 (C5, C8) — creation and marking over the same stores, mirroring `AppGraph`'s fields by name. */
    val backupRepositories = BackupRepositories(
        assets, groups, tags, links, definitions, profiles, schedules, closures, events, attachments, references,
        seasonActivations, conditions, healthSubjects, categories, serviceCases, serviceCaseEntries, loans,
        transferRecords, assetSuccessions, supplyItems, assetSupplies, installedComponents,
    )
    val createTransferPack: CreateTransferPack = CreateTransferPack(
        backupRepositories, uow, ids, clock, APP_VERSION, SCHEMA_VERSION,
        lineageOf = { id -> lineageFor(transferRecords.all(), id) },
    )
    val markTransferredOut: MarkTransferredOut = MarkTransferredOut(backupRepositories, uow, ids, clock) {
        recomputeSchedules.forAsset(it)
    }

    /** #77 (C23, R77-WITHDRAW) — the phone-only, pack-wide withdrawal, mirroring `AppGraph`'s. */
    val withdrawTransferRecord: WithdrawTransferRecord = WithdrawTransferRecord(backupRepositories, uow, ids, clock)

    /** #77 (C14, C15) — the Transfer Pack import over the same guarded stores, mirroring `AppGraph`'s fields by name. */
    val importTransferPack: ImportTransferPack = ImportTransferPack(
        buildBackupMergePlan, applyBackupMergePlan, transferRecords, assets, tags, attachmentStorage, uow, clock,
    )

    /** `cache/transfer-in/`, in a temporary directory of its own. */
    val transferPackInbox: CacheTransferPackInbox =
        CacheTransferPackInbox(kotlin.io.path.createTempDirectory("transfer-in").toFile(), ids)

    /**
     * 1.2 — the group completion path, so a read-model or view-model test marks members done
     * through the production use case rather than hand-writing an `occurrence_on`. Mirrors
     * `AppGraph`'s field; the schedule and group use cases it sits beside are declared below.
     */
    val completeGroupMembers: CompleteGroupMembers = CompleteGroupMembers(
        schedules, groups, events, closures, definitions, profiles, supplyItems, uow, ids, clock, recomputeSchedules,
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

    /** 1.4.1 (#80) — the provider repair, from exactly the members `AppGraph` builds it from. */
    val repairScheduleProviders: RepairScheduleProviders =
        RepairScheduleProviders(schedules, assets, groups, transferRecords, uow, clock)
    val completeSchedule: CompleteSchedule =
        CompleteSchedule(schedules, events, definitions, profiles, supplyItems, uow, ids, clock, recomputeSchedules)
    val postponeSchedule: PostponeSchedule = PostponeSchedule(schedules, uow, recomputeSchedules)
    val pauseSchedule: PauseSchedule = PauseSchedule(schedules, uow, recomputeSchedules)
    val archiveSchedule: ArchiveSchedule =
        ArchiveSchedule(schedules, uow, recomputeSchedules, healthSubjects, assets, clock)
    val closeRound: CloseRound =
        CloseRound(schedules, closures, uow, ids, clock, todayPort, recomputeSchedules)
    val saveGroup: SaveGroup = SaveGroup(groups, assets, uow, ids, clock)

    /** #86 (C18): the tag retarget `ReplaceAsset` calls in-transaction, from exactly the members `AppGraph` builds it from. */
    val bindTag: BindTag = BindTag(tags, assets, uow, clock)

    /**
     * #86 (C18): Replace asset — one write over the guarded ports (the successions' included), calling the five
     * in-transaction bodies of the graph's own use cases, never their `run`.
     */
    val replaceAsset: ReplaceAsset = ReplaceAsset(
        assets, schedules, groups, tags, definitions, profiles, loans, transferRecords, assetSuccessions,
        uow, ids, clock, todayPort, retireAsset, saveAssetSettings, saveSchedule, saveGroup, bindTag,
    )
    val archiveGroup: ArchiveGroup = ArchiveGroup(groups, uow, clock)

    /** #15 (C15–C17): the SupplyItem and applicability use cases, from exactly the members `AppGraph` builds them from. */
    val saveSupplyItem: SaveSupplyItem = SaveSupplyItem(supplyItems, uow, ids, clock)
    val archiveSupplyItem: ArchiveSupplyItem = ArchiveSupplyItem(supplyItems, uow, clock)
    val addAssetSupply: AddAssetSupply = AddAssetSupply(assets, supplyItems, assetSupplies, uow, ids, clock)
    val updateAssetSupply: UpdateAssetSupply = UpdateAssetSupply(assetSupplies, uow, clock)
    val removeAssetSupply: RemoveAssetSupply = RemoveAssetSupply(assetSupplies, uow)

    /** #47 (C16, C17): install and remove, from exactly the members `AppGraph` builds them from. */
    val installComponent: InstallComponent =
        InstallComponent(assets, supplyItems, installedComponents, uow, ids, clock, todayPort)
    val removeInstalledComponent: RemoveInstalledComponent =
        RemoveInstalledComponent(installedComponents, uow, clock, todayPort)

    /** #47 (C18, C19): replace and edit, from exactly the members `AppGraph` builds them from. */
    val replaceInstalledComponent: ReplaceInstalledComponent =
        ReplaceInstalledComponent(supplyItems, installedComponents, uow, ids, clock, todayPort)
    val updateInstalledComponent: UpdateInstalledComponent =
        UpdateInstalledComponent(supplyItems, installedComponents, uow, ids, clock, todayPort)

    /**
     * 1.4 — the offers an event makes (spec §3.3, §5.4): "Mark operational?" and the season offer,
     * each written only by its accept (B06's `AcceptOperationalOffer`, B04's `AcceptSeasonOffer`) —
     * and #82's impairment offer after a new Incident, written only by "Mark down" or "Mark
     * degraded" (`AcceptImpairmentOffer`). Built here once; the completion flow and the journal
     * entry both ask through it.
     */
    val eventOffers: EventOffers = EventOffers(
        OperationalOffers(assets, conditions, acceptOperationalOffer, todayPort),
        SeasonOffers(assets, seasonActivations, acceptSeasonOffer, todayPort),
        ImpairmentOffers(assets, conditions, acceptImpairmentOffer, todayPort),
    )

    /** The one completion mechanism, over the real use cases — always UTC, so a `tzId` is stable. */
    val completionFlow: CompletionFlow = CompletionFlow(
        schedules, definitions, completeSchedule, completeGroupMembers, todayPort,
        eventOffers,
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

    /** #79: the device-local deadline stamp, over the real table, as `AppGraph` builds it. */
    val deadlineLocalDelivery: DeadlineLocalDeliveryRepository =
        RoomDeadlineLocalDeliveryRepository(db.deadlineLocalDeliveryDao())
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

/** #92: [delegate], counting each write transaction that committed. Reads are not counted. */
private class CountingUnitOfWork(private val delegate: UnitOfWork) : UnitOfWork {
    private val committed = java.util.concurrent.atomic.AtomicInteger()
    val commits: Int get() = committed.get()

    override suspend fun <T> write(block: suspend () -> T): T = delegate.write(block).also { committed.incrementAndGet() }
    override suspend fun <T> read(block: suspend () -> T): T = delegate.read(block)
}
