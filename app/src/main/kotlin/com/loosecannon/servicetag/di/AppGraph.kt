package com.loosecannon.servicetag.di

import android.content.Context
import android.net.Uri
import androidx.annotation.VisibleForTesting
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.loosecannon.nfc.tagcore.TagIdentity
import com.loosecannon.servicetag.BuildConfig
import com.loosecannon.servicetag.attachments.AttachmentRoot
import com.loosecannon.servicetag.attachments.DocumentTreeRoot
import com.loosecannon.servicetag.attachments.SafAttachmentStorage
import com.loosecannon.servicetag.attachments.Thumbnails
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.nfc.NdefCodec
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.AttachmentRepository
import com.loosecannon.servicetag.core.ports.CategoryRepository
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
import com.loosecannon.servicetag.core.ports.UuidGenerator
import com.loosecannon.servicetag.core.references.LinkLaunchPolicy
import com.loosecannon.servicetag.core.references.StreamSourcePolicy
import com.loosecannon.servicetag.core.reminders.BuildReminderSubjects
import com.loosecannon.servicetag.core.usecase.AcceptOperationalOffer
import com.loosecannon.servicetag.core.usecase.AcceptSeasonOffer
import com.loosecannon.servicetag.core.usecase.AddAttachment
import com.loosecannon.servicetag.core.usecase.AddReference
import com.loosecannon.servicetag.core.usecase.ApplyTemplate
import com.loosecannon.servicetag.core.usecase.ApplyBackupMergePlan
import com.loosecannon.servicetag.core.usecase.ArchiveAsset
import com.loosecannon.servicetag.core.usecase.ArchiveDefinition
import com.loosecannon.servicetag.core.usecase.ArchiveProfile
import com.loosecannon.servicetag.core.usecase.ArchiveGroup
import com.loosecannon.servicetag.core.usecase.ArchiveHealthSubject
import com.loosecannon.servicetag.core.usecase.ArchiveSchedule
import com.loosecannon.servicetag.core.usecase.BindTag
import com.loosecannon.servicetag.core.usecase.BuildBackupMergePlan
import com.loosecannon.servicetag.core.usecase.CloseRound
import com.loosecannon.servicetag.core.usecase.CompleteGroupMembers
import com.loosecannon.servicetag.core.usecase.CompleteSchedule
import com.loosecannon.servicetag.core.usecase.CompletionCommand
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
import com.loosecannon.servicetag.core.usecase.ImportBackupReplace
import com.loosecannon.servicetag.core.usecase.LogEvent
import com.loosecannon.servicetag.core.usecase.PauseSchedule
import com.loosecannon.servicetag.core.usecase.PostponeSchedule
import com.loosecannon.servicetag.core.usecase.PromoteCategory
import com.loosecannon.servicetag.core.usecase.ProvisionTag
import com.loosecannon.servicetag.core.usecase.RecomputeSchedules
import com.loosecannon.servicetag.core.usecase.RecordCondition
import com.loosecannon.servicetag.core.usecase.RecordSeasonActivation
import com.loosecannon.servicetag.core.usecase.RemoveReference
import com.loosecannon.servicetag.core.usecase.RenameCategory
import com.loosecannon.servicetag.core.usecase.ReorderDefinitions
import com.loosecannon.servicetag.core.usecase.ReorderProfiles
import com.loosecannon.servicetag.core.usecase.ResolveTag
import com.loosecannon.servicetag.core.usecase.RestoreArtifacts
import com.loosecannon.servicetag.core.usecase.RetireAsset
import com.loosecannon.servicetag.core.usecase.SaveDefinition
import com.loosecannon.servicetag.core.usecase.SaveProfile
import com.loosecannon.servicetag.core.usecase.SaveGroup
import com.loosecannon.servicetag.core.usecase.SaveAssetSettings
import com.loosecannon.servicetag.core.usecase.SaveHealthSubject
import com.loosecannon.servicetag.core.usecase.RepairScheduleProviders
import com.loosecannon.servicetag.core.usecase.SaveSchedule
import com.loosecannon.servicetag.core.usecase.SetHealthPolicy
import com.loosecannon.servicetag.core.usecase.SetMaintenanceBreak
import com.loosecannon.servicetag.core.usecase.SetSeasonMode
import com.loosecannon.servicetag.core.usecase.StoreIsEmpty
import com.loosecannon.servicetag.core.usecase.UpdateAsset
import com.loosecannon.servicetag.core.usecase.UpdateAttachment
import com.loosecannon.servicetag.core.usecase.UpdateReference
import com.loosecannon.servicetag.core.usecase.UpdateEvent
import com.loosecannon.servicetag.data.room.AppDatabase
import com.loosecannon.servicetag.data.room.MIGRATION_1_2
import com.loosecannon.servicetag.data.room.MIGRATION_2_3
import com.loosecannon.servicetag.data.room.MIGRATION_3_4
import com.loosecannon.servicetag.data.room.MIGRATION_4_5
import com.loosecannon.servicetag.data.room.MIGRATION_5_6
import com.loosecannon.servicetag.data.room.MIGRATION_6_7
import com.loosecannon.servicetag.data.room.MIGRATION_7_8
import com.loosecannon.servicetag.data.room.MIGRATION_8_9
import com.loosecannon.servicetag.data.room.RoomAssetRepository
import com.loosecannon.servicetag.data.room.RoomAttachmentRepository
import com.loosecannon.servicetag.data.room.RoomCategoryRepository
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
import com.loosecannon.servicetag.prefs.AppPrefs
import com.loosecannon.servicetag.prefs.SharedPrefsStore
import com.loosecannon.servicetag.reminders.AndroidDigestAlarm
import com.loosecannon.servicetag.reminders.AndroidNotificationPermission
import com.loosecannon.servicetag.reminders.AndroidPlatformState
import com.loosecannon.servicetag.reminders.AndroidQuickActionIntents
import com.loosecannon.servicetag.reminders.AndroidReminderNotifications
import com.loosecannon.servicetag.reminders.BackstopWork
import com.loosecannon.servicetag.reminders.DigestAlarm
import com.loosecannon.servicetag.reminders.LocalReminderProvider
import com.loosecannon.servicetag.reminders.NonceStore
import com.loosecannon.servicetag.reminders.NotificationPermission
import com.loosecannon.servicetag.reminders.PlatformState
import com.loosecannon.servicetag.reminders.QuickActionRuns
import com.loosecannon.servicetag.reminders.QuickActionShape
import com.loosecannon.servicetag.reminders.QuickActionShapeSource
import com.loosecannon.servicetag.reminders.QuickActions
import com.loosecannon.servicetag.reminders.ReconcileWorker
import com.loosecannon.servicetag.reminders.ReminderHealthCheck
import com.loosecannon.servicetag.reminders.ReminderNotifications
import com.loosecannon.servicetag.reminders.ReminderRuns
import com.loosecannon.servicetag.reminders.ReminderSnooze
import com.loosecannon.servicetag.reminders.ScheduleCompletion
import com.loosecannon.servicetag.reminders.ScheduleDeliveryFacts
import com.loosecannon.servicetag.reminders.ScheduleStateReader
import com.loosecannon.servicetag.reminders.WorkManagerBackstop
import com.loosecannon.servicetag.ui.condition.EventOffers
import com.loosecannon.servicetag.ui.condition.OperationalOffers
import com.loosecannon.servicetag.ui.condition.SeasonOffers
import com.loosecannon.servicetag.ui.health.AssetHealthReadModel
import com.loosecannon.servicetag.ui.maintenance.AttentionReadModel
import com.loosecannon.servicetag.ui.maintenance.CompletionFlow
import com.loosecannon.servicetag.ui.maintenance.DueReadModel
import com.loosecannon.servicetag.ui.maintenance.HealthSummary
import com.loosecannon.servicetag.ui.maintenance.LastCompletionEventId
import com.loosecannon.servicetag.ui.maintenance.LastCompletionReadings
import com.loosecannon.servicetag.ui.maintenance.ReminderHealth
import com.loosecannon.servicetag.ui.maintenance.ReminderReconcile
import com.loosecannon.servicetag.ui.maintenance.ScanRoundMembership
import com.loosecannon.servicetag.ui.maintenance.ScanSheetOffer
import com.loosecannon.servicetag.ui.maintenance.ScheduleClosures
import com.loosecannon.servicetag.ui.maintenance.ScheduleCompletions
import com.loosecannon.servicetag.ui.maintenance.ScheduleSnooze
import com.loosecannon.servicetag.ui.maintenance.scanSheetContentFor
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Hand-rolled composition root. No DI framework in Phase 1 (D3 §5). */
class AppGraph(private val context: Context) {
    val db: AppDatabase = Room
        .databaseBuilder<AppDatabase>(
            context = context.applicationContext,
            name = context.applicationContext.getDatabasePath(DB_NAME).absolutePath,
        )
        .setDriver(AndroidSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .addMigrations(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
            MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
        )
        .build()

    val clock: Clock = Clock { System.currentTimeMillis() }
    val ids: IdGenerator = UuidGenerator
    val uow: UnitOfWork = RoomUnitOfWork(db)
    val assets: AssetRepository = RoomAssetRepository(db.assetDao())
    val tags: TagRepository = RoomTagRepository(db.nfcTagDao())
    // The tombstone port: backup export and restore, and nothing else (2.6).
    val links: LinkRepository = RoomLinkRepository(db.externalLinkDao())
    val definitions: DefinitionRepository = RoomDefinitionRepository(db.definitionDao())
    val profiles: ProfileRepository = RoomProfileRepository(db.profileDao())
    val events: EventRepository = RoomEventRepository(db.eventDao())
    val attachments: AttachmentRepository = RoomAttachmentRepository(db.attachmentDao())
    // The three v6 data ports the backup and merge paths read and write. The queries the engine and
    // the group screens need are added to these ports, and to their adapters, together.
    val groups: GroupRepository = RoomGroupRepository(db.maintenanceGroupDao())
    val schedules: ScheduleRepository = RoomScheduleRepository(db.maintenanceScheduleDao())
    val closures: ClosureRepository = RoomClosureRepository(db.occurrenceClosureDao())

    /** 1.3's one new data port: the URIs on an asset. Its rules live in the use cases. */
    val references: ReferenceRepository = RoomReferenceRepository(db.assetReferenceDao())

    // 1.4's three data ports. The two fact stores are insert and query only; the subjects are
    // configuration, upserted and never deleted. Their rules live in the use cases that write them.
    val seasonActivations: SeasonActivationRepository = RoomSeasonActivationRepository(db.seasonActivationDao())
    val conditions: ConditionRepository = RoomConditionRepository(db.assetConditionDao())
    val healthSubjects: HealthSubjectRepository = RoomHealthSubjectRepository(db.healthSubjectDao())

    /**
     * #74's one data port: the owner's own categories, beside the compiled built-ins. Written by a
     * successful asset save's promotion, a rename, an unused delete, the replace import and the merge
     * apply — the last two promote the assets they insert — and once by `MIGRATION_8_9`'s backfill.
     * Never derived from assets.
     */
    val categories: CategoryRepository = RoomCategoryRepository(db.assetCategoryDao())

    /** Derived due state. Its one writer is [recomputeSchedules]; nothing else may reach it. */
    val scheduleStates: ScheduleStateRepository = RoomScheduleStateRepository(db.scheduleStateDao())

    /**
     * `T`, the device-local date. [clock] answers in milliseconds and every due date in 1.2 is a
     * date: the screens, the digest run and the backstop all read this one value, and the engine
     * itself never reads it — it is handed `T` as an argument.
     */
    val today: Today = Today { LocalDate.now() }

    /**
     * The one path into derived state (invariant 17), and the closure every event write asks for:
     * this Asset's schedules plus every group schedule that requires it. The group half of that
     * closure arrives with the groups work; until then the seam answers with nothing.
     */
    val recomputeSchedules: RecomputeSchedules = RecomputeSchedules(
        schedules, scheduleStates, events, closures, groups, assets, seasonActivations, today, clock,
        zone = { ZoneId.systemDefault() },
    )

    /**
     * The desired state of every reminder provider's list, derived from schedule state alone. The
     * one provider that delivers is [localReminderProvider] below; what is here is the question
     * every provider is asked.
     */
    val buildReminderSubjects: BuildReminderSubjects = BuildReminderSubjects(schedules, groups, recomputeSchedules)
    val prefs: AppPrefs = AppPrefs(SharedPrefsStore(context))

    // #24 — the platform-ownership seams B06, B07, B10 and B14 compile against (master plan §12).
    val platformState: PlatformState = AndroidPlatformState(context)
    val notificationPermission: NotificationPermission = AndroidNotificationPermission(context)

    // 1.2 (#21) — the local delivery path: device-local bookkeeping, the provider, and the one run
    // every entry point shares.
    /** Never exported and never merged (invariants 64, 65). Losing it costs one repeated notification. */
    val scheduleLocalDelivery: ScheduleLocalDeliveryRepository =
        RoomScheduleLocalDeliveryRepository(db.scheduleLocalDeliveryDao())

    /**
     * Derived state as a **read**, so the delivery path cannot reach the one write method the
     * recompute owns (invariant 17) — and through `readState`, so a row left behind by a day
     * boundary is derived for today in memory rather than trusted (master plan §8.6, plan decision
     * 47). `ScheduleDeliveryFacts` and `ReminderHealthCheck` read fresh state even between sweeps.
     */
    val scheduleStateReader: ScheduleStateReader = ScheduleStateReader { id ->
        schedules.get(id)?.let { recomputeSchedules.readState(it) }
    }

    val digestAlarm: DigestAlarm = AndroidDigestAlarm(context.applicationContext, prefs, today, clock)

    /** B07's "Snooze 1 day" and B09's "Snooze"; and the nonce column's single owner (D-21). */
    val reminderSnooze: ReminderSnooze = ReminderSnooze(scheduleLocalDelivery, clock)
    val nonceStore: NonceStore = NonceStore(scheduleLocalDelivery, ids, clock)

    /**
     * #11 — the actions on a reminder notification. Declared **above** the notification seam and
     * the provider because both hold it, and a Kotlin property initialised after its reader would
     * be null when the reader ran.
     *
     * The shape seam is three facts read off the schedule row, which is all §12.1's table needs to
     * decide which actions a notification offers (D-7's group clause, the `FORM` rule and the meter
     * carve-out). Nothing about it can reach derived state (invariant 17).
     */
    val quickActions: QuickActions = QuickActions(
        shapes = QuickActionShapeSource { id ->
            schedules.get(id)?.let { schedule ->
                QuickActionShape(
                    groupTargeted = schedule.target is ScheduleTarget.GroupTarget,
                    completionMode = schedule.completionMode,
                    meterRule = schedule.meterDefinitionId != null,
                )
            }
        },
        nonces = nonceStore,
    )

    val reminderNotifications: ReminderNotifications =
        AndroidReminderNotifications(context.applicationContext, AndroidQuickActionIntents(context.applicationContext))

    val localReminderProvider: LocalReminderProvider = LocalReminderProvider(
        facts = ScheduleDeliveryFacts(schedules, scheduleStateReader, assets, groups, definitions, today),
        delivery = scheduleLocalDelivery,
        notifications = reminderNotifications,
        permission = notificationPermission,
        platform = platformState,
        alarm = digestAlarm,
        prefs = prefs,
        clock = clock,
        quickActions = quickActions,
    )

    /**
     * The one run behind the four platform receivers, the digest alarm's receiver and the backstop
     * worker. `ServiceTagApp.onCreate` assigns it to both dispatch seams, synchronously.
     */
    val reminderRuns: ReminderRuns = ReminderRuns(
        rebuildAll = { recomputeSchedules.all() },
        subjectsFor = { provider, on -> buildReminderSubjects.forProvider(provider, on) },
        provider = localReminderProvider,
        alarm = digestAlarm,
        today = today,
        // A receiver has roughly ten seconds and the sweep is a rebuild of every schedule plus a
        // post per subject, so the receivers enqueue this and return (B06 fix round 1, finding 4).
        // A seam rather than a `Context` field: nothing about the run itself is Android-shaped.
        enqueueReconcile = { ReconcileWorker.enqueue(context.applicationContext) },
    )

    /**
     * #11 — one tapped quick action: the nonce gate, then the use case, then a reconcile.
     * `ServiceTagApp.onCreate` assigns it to [QuickActionDispatch], synchronously, because the
     * worker behind a notification tap is never constructed through this graph.
     *
     * The completion is [completeSchedule] — the canonical use case, with `occurredOn` today and
     * the device zone, which is exactly what a one-tap completion means (invariant 17: nothing in
     * the delivery path goes near derived state, and the recompute inside the use case is the one
     * writer). It is a seam rather than the use case itself so the nonce gate can be proved without
     * constructing eight ports.
     */
    val quickActionRuns: QuickActionRuns = QuickActionRuns(
        nonces = nonceStore,
        completion = ScheduleCompletion { id ->
            completeSchedule.run(
                id,
                CompletionCommand(occurredOn = today.localDate().toString(), tzId = ZoneId.systemDefault().id),
            )
        },
        snooze = reminderSnooze,
        // The gate and the write commit together (D-21, fix round 1, finding 1): `CompleteSchedule`
        // opens a write of its own and Room joins this one rather than starting a second, so a
        // crash between the clear and the event rolls both back and the tap survives as a retry.
        uow = uow,
        reconcile = { reminderRuns.reconcileAll() },
        clock = clock,
    )

    /**
     * Swapped only by the instrumented suite, which has no SAF picker to drive and no persisted
     * grant to check (spec §12): it points these at `DocumentFile.fromFile` on an app-external
     * directory. Production never reassigns them.
     */
    @VisibleForTesting
    var attachmentRootResolver: (String) -> AttachmentRoot? = { treeUri ->
        DocumentFile.fromTreeUri(context.applicationContext, treeUri.toUri())
            ?.let { DocumentTreeRoot(it, context.applicationContext.contentResolver) }
    }

    @VisibleForTesting
    var attachmentGrantCheck: (String) -> Boolean = { treeUri ->
        context.applicationContext.contentResolver.persistedUriPermissions.any {
            it.uri.toString() == treeUri && it.isReadPermission && it.isWritePermission
        }
    }

    /** The one gate every attachment path passes through: a folder, and the right to write in it. */
    val attachmentStorage: SafAttachmentStorage = SafAttachmentStorage(
        prefs = prefs,
        rootResolver = { uri -> attachmentRootResolver(uri) },
        grantCheck = { uri -> attachmentGrantCheck(uri) },
    )

    val thumbnails: Thumbnails = Thumbnails(context.applicationContext.cacheDir, attachmentStorage)

    // Phase 4A — attachments.
    val addAttachment: AddAttachment =
        AddAttachment(attachments, assets, events, attachmentStorage, uow, ids, clock)
    val updateAttachment: UpdateAttachment = UpdateAttachment(attachments, uow, clock)
    val deleteAttachment: DeleteAttachment = DeleteAttachment(attachments, attachmentStorage, uow)
    val restoreArtifacts: RestoreArtifacts = RestoreArtifacts(attachments, attachmentStorage)

    // 1.3.0 — references. One policy instance answers both save and launch (spec §4.2), and the
    // stream predicate is told ServiceTag's own authorities rather than guessing at them (I-9).
    // The application id covers every authority the merged manifest publishes under it — the
    // FileProvider this app declares and the ones libraries inject, androidx.startup's among them.
    val linkLaunchPolicy: LinkLaunchPolicy = LinkLaunchPolicy()
    val streamSourcePolicy: StreamSourcePolicy = StreamSourcePolicy(
        setOf(BuildConfig.APPLICATION_ID, "${BuildConfig.APPLICATION_ID}.files"),
    )
    val addReference: AddReference =
        AddReference(references, assets, linkLaunchPolicy, uow, ids, clock)
    val updateReference: UpdateReference = UpdateReference(references, uow, clock)
    val removeReference: RemoveReference = RemoveReference(references, uow)

    /** A cache file the camera can write into through the FileProvider (spec §9.3). */
    fun cameraCaptureUri(): Uri {
        val file = File(File(context.applicationContext.cacheDir, "camera"), "${ids.newId()}.jpg")
        file.parentFile?.mkdirs()
        return FileProvider.getUriForFile(
            context.applicationContext,
            "${BuildConfig.APPLICATION_ID}.files",
            file,
        )
    }

    /**
     * Produces a backup *set*: the data archive's bytes plus the plan for the artifacts archive
     * beside them. `BackupViewModel.exportSet` writes both files into the folder the owner picks.
     */
    val exportBackupSet: ExportBackupSet = ExportBackupSet(
        assets, groups, tags, links, definitions, profiles, schedules, closures, events,
        attachments, references, seasonActivations, conditions, healthSubjects, categories,
        uow, ids, clock, BuildConfig.VERSION_NAME, SCHEMA_VERSION,
    )

    /** Wipe-and-load import. Replace is the only mode Phase 1A ships (D7 1A). */
    val importBackupReplace: ImportBackupReplace = ImportBackupReplace(
        assets, groups, tags, links, definitions, profiles, schedules, closures, events,
        attachments, references, seasonActivations, conditions, healthSubjects, categories,
        attachmentStorage, uow,
        // Derived state is rebuilt after any import, and the wipe took it with the schedule rows.
        rebuildAll = { recomputeSchedules.all() },
    )

    /**
     * 1.1.0 (#46), semantics from #44 — the additive merge, planned before it writes. `plan`
     * decides and writes nothing; `run` applies only a conflict-free plan, which the apply rebuilds
     * inside its own transaction. Reachable only from the Developer API screen's loopback listener;
     * there is no UI for it.
     */
    val buildBackupMergePlan: BuildBackupMergePlan = BuildBackupMergePlan(
        assets, groups, tags, links, definitions, profiles, schedules, closures, events,
        attachments, references, seasonActivations, conditions, healthSubjects, categories,
        attachmentStorage, uow,
    )
    val applyBackupMergePlan: ApplyBackupMergePlan = ApplyBackupMergePlan(
        assets, groups, tags, links, definitions, profiles, schedules, closures, events,
        attachments, references, seasonActivations, conditions, healthSubjects, categories,
        attachmentStorage, uow,
        // The total post-apply recompute, wired to the engine: an imported event, membership row,
        // closure or meter reading can each move a due date, and rebuilding every schedule inside
        // the apply's own transaction is cheaper than enumerating which.
        rebuildAll = { recomputeSchedules.all() },
    )
    val importBackupMerge: ImportBackupMerge =
        ImportBackupMerge(buildBackupMergePlan, applyBackupMergePlan)

    /**
     * #40 — is there anything on this phone a restore would replace? The Backup screen asks once,
     * per picked file, and the answer chooses the confirmation. Definitions and profiles are not
     * read: neither can exist without its asset, so `assets` answers for both. A category row can
     * (#74: it outlives its assets), so `categories` is the sixth kind.
     */
    val storeIsEmpty: StoreIsEmpty = StoreIsEmpty(assets, tags, events, attachments, links, categories)

    /** Process-wide scope for work that must outlive a finishing activity (e.g. abandoning a row). */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Phase 1B — NFC identity
    /**
     * This product's wire identity, built from the Gradle-owned value and nothing else: the same
     * three fields produce the manifest's NDEF filter path, so the filter and the bytes we write
     * cannot drift apart (C9, target §4.8).
     */
    val tagIdentity: TagIdentity = TagIdentity(
        externalDomain = BuildConfig.NDEF_EXTERNAL_DOMAIN,
        typeName = BuildConfig.NDEF_TYPE_NAME,
        aarPackage = BuildConfig.NDEF_AAR_PACKAGE,
    )
    val ndefCodec: NdefCodec = NdefCodec(tagIdentity)

    val resolveTag: ResolveTag = ResolveTag(tags, assets, uow, clock)
    val bindTag: BindTag = BindTag(tags, assets, uow, clock)
    val provisionTag: ProvisionTag = ProvisionTag(tags, assets, uow, ids, clock)
    val applyTemplate: ApplyTemplate = ApplyTemplate(definitions, profiles, assets, uow, ids, clock)

    // #74 — durable categories (C5–C7). The three asset commands share one promotion, which stores the
    // catalog's spelling and writes a first-use row beside the asset, after every refusal; rename and
    // delete are the Categories screen's two writes.
    val promoteCategory: PromoteCategory = PromoteCategory(categories)
    val renameCategory: RenameCategory = RenameCategory(categories, assets, uow, clock)
    val deleteCategory: DeleteCategory = DeleteCategory(categories, assets, uow)

    val createAsset: CreateAsset = CreateAsset(assets, uow, ids, clock, applyTemplate, promoteCategory)

    // Phase 1C — the asset form. Archive-first: no hard delete for an asset in Phase 1 (R-9).
    // 1.4: a changed season pair goes through the season-mode rules, which read the asset's schedules
    // (the strands rule) and rebuild them (spec §3.2).
    val updateAsset: UpdateAsset = UpdateAsset(assets, schedules, uow, clock, recomputeSchedules, promoteCategory)

    // 1.4 — the season model's commands (master plan §7.2). One use case per write, so the editors,
    // the API and the offers refuse the same things; each season or break write rebuilds the asset's
    // schedules, and an activation writes its row and no asset column.
    val setSeasonMode: SetSeasonMode =
        SetSeasonMode(assets, schedules, seasonActivations, uow, ids, clock, today, recomputeSchedules)
    val setMaintenanceBreak: SetMaintenanceBreak =
        SetMaintenanceBreak(assets, schedules, uow, clock, recomputeSchedules)
    val recordSeasonActivation: RecordSeasonActivation =
        RecordSeasonActivation(assets, events, seasonActivations, uow, ids, clock, today, recomputeSchedules)
    val getAssetSeason: GetAssetSeason = GetAssetSeason(assets, seasonActivations, uow, today)
    val acceptSeasonOffer: AcceptSeasonOffer = AcceptSeasonOffer(seasonActivations, recordSeasonActivation, uow, today)

    // 1.4 — condition and health configuration (master plan §9, §10.1). A condition write inserts one
    // immutable row and nothing else; the offer writes only when accepted. The subject and policy
    // commands write configuration only — health is computed at read time. The settings save is the
    // asset editor's one transaction over the asset, its season, its break and its policy.
    val recordCondition: RecordCondition = RecordCondition(assets, events, conditions, uow, ids, clock, today)
    val acceptOperationalOffer: AcceptOperationalOffer = AcceptOperationalOffer(conditions, recordCondition, uow)
    val saveHealthSubject: SaveHealthSubject =
        SaveHealthSubject(healthSubjects, assets, schedules, profiles, uow, ids, clock)
    val archiveHealthSubject: ArchiveHealthSubject =
        ArchiveHealthSubject(healthSubjects, assets, schedules, uow, clock)
    val setHealthPolicy: SetHealthPolicy = SetHealthPolicy(assets, healthSubjects, uow, clock)
    val saveAssetSettings: SaveAssetSettings = SaveAssetSettings(
        assets, schedules, healthSubjects, seasonActivations, uow, ids, clock, today, recomputeSchedules, applyTemplate,
        promoteCategory,
    )

    val archiveAsset: ArchiveAsset =
        ArchiveAsset(assets, uow, clock) { recomputeSchedules.forAsset(it) }

    // Phase 2B-2 — retirement is a date the person picks, not a status (spec §7), and delete is
    // the one destructive asset action: it refuses a parent that still has children.
    val retireAsset: RetireAsset =
        RetireAsset(assets, uow, clock) { recomputeSchedules.forAsset(it) }
    val deleteAsset: DeleteAsset =
        DeleteAsset(assets, events, attachments, attachmentStorage, uow, groups, schedules, closures)

    // Phase 2A — the maintenance journal.
    val logEvent: LogEvent =
        LogEvent(events, definitions, profiles, assets, uow, ids, clock, recomputeSchedules)
    val updateEvent: UpdateEvent =
        UpdateEvent(events, definitions, profiles, uow, ids, clock, recomputeSchedules)
    val deleteEvent: DeleteEvent =
        DeleteEvent(events, attachments, attachmentStorage, uow, recomputeSchedules)

    // Phase 2B-1 — the definition and profile editors. Archive is the ordinary retirement; delete
    // exists only for a row nothing references yet, and each use case checks that before writing.
    val saveDefinition: SaveDefinition =
        SaveDefinition(definitions, events, profiles, assets, uow, ids, clock)
    val archiveDefinition: ArchiveDefinition = ArchiveDefinition(definitions, uow, clock)
    val deleteDefinition: DeleteDefinition = DeleteDefinition(definitions, events, profiles, uow)
    val reorderDefinitions: ReorderDefinitions = ReorderDefinitions(definitions, uow, clock)
    val saveProfile: SaveProfile = SaveProfile(profiles, definitions, assets, uow, ids, clock)
    val archiveProfile: ArchiveProfile = ArchiveProfile(profiles, uow, clock)
    val deleteProfile: DeleteProfile = DeleteProfile(profiles, uow)
    val reorderProfiles: ReorderProfiles = ReorderProfiles(profiles, uow, clock)

    // 1.2 — the schedule operations. Each does exactly what it says and no two of them collapse
    // into a generic reschedule: only `saveSchedule` writes a rule column, only it moves the pin's
    // floor, and a completion touches the schedule row only to clear a postponement.
    // 1.4: the save and the archive guard a health subject the schedule drives (inv. 130).
    val saveSchedule: SaveSchedule = SaveSchedule(
        schedules, assets, groups, definitions, profiles, uow, ids, clock, recomputeSchedules, healthSubjects,
    )
    // 1.4.1 (#80) — the one provider repair: `/v1` and the MCP call it through `MaintenanceHandlers`,
    // Reminder Health on an explicit tap. Never an automatic repair, never from the backstop.
    val repairScheduleProviders: RepairScheduleProviders = RepairScheduleProviders(schedules, uow, clock)
    val completeSchedule: CompleteSchedule =
        CompleteSchedule(schedules, events, definitions, profiles, uow, ids, clock, recomputeSchedules)
    val postponeSchedule: PostponeSchedule = PostponeSchedule(schedules, uow, recomputeSchedules)
    val pauseSchedule: PauseSchedule = PauseSchedule(schedules, uow, recomputeSchedules)
    val archiveSchedule: ArchiveSchedule =
        ArchiveSchedule(schedules, uow, recomputeSchedules, healthSubjects, assets, clock)

    // 1.2 — the group operations. `saveGroup` is the only writer of a membership window, and the
    // only place `removed_at` is ever stamped; nothing anywhere clears one.
    val saveGroup: SaveGroup = SaveGroup(groups, assets, uow, ids, clock)
    val archiveGroup: ArchiveGroup = ArchiveGroup(groups, uow, clock)
    val completeGroupMembers: CompleteGroupMembers = CompleteGroupMembers(
        schedules, groups, events, closures, definitions, profiles, uow, ids, clock, recomputeSchedules,
    )
    val closeRound: CloseRound =
        CloseRound(schedules, closures, uow, ids, clock, today, recomputeSchedules)

    /**
     * 1.4 — an asset's health beside its condition and its DOWN or DEGRADED components: the one
     * source for `/v1/assets/{id}/health`, the scan sheet and asset detail (master plan §13.1,
     * inv. 119). It reads states through `readState` and writes nothing (inv. 105); it reads no
     * snooze (inv. 131). The zone is the recompute's, so the pin floor it reads is the same day.
     */
    val assetHealthReadModel: AssetHealthReadModel = AssetHealthReadModel(
        assets, healthSubjects, schedules, events, profiles, seasonActivations, conditions,
        recomputeSchedules, today, zone = { ZoneId.systemDefault() },
    )

    /** 1.4 — the asset-level attention rows (DOWN, DEGRADED, independent health), `/v1/attention`'s. */
    val attentionReadModel: AttentionReadModel =
        AttentionReadModel(assets, assetHealthReadModel, today)

    /**
     * 1.2 — the one due projection behind the dashboard, the Maintenance destination, the scan
     * sheet and `/v1/due` (master plan decision 27). It **reads** derived state and never writes
     * it: every state comes through `readState` (master plan §8.6), [recomputeSchedules] is here
     * for that and its occurrence derivation, and `rebuild` stays the only writer of
     * `schedule_state` (invariants 17, 105). [today] is the same port `readState` reads.
     *
     * The snooze source is B06's `schedule_local_delivery`, wired here by B07: the row's own
     * instant, read and never written, which is what makes the ratified "Snoozed until \<date\>"
     * true of the schedule the notification's "Snooze 1 day" acted on.
     */
    val dueReadModel: DueReadModel = DueReadModel(
        schedules, assets, groups, definitions, recomputeSchedules, today, assetHealthReadModel,
        snoozedUntilOf = { scheduleLocalDelivery.get(it)?.snoozedUntilAt },
    )

    /**
     * 1.2 (#27) — the backstop's unique work as a question, which is the half `BackstopWorker` does
     * not answer: it can enqueue, and asking first is what makes the repair idempotent.
     */
    val backstopWork: BackstopWork = WorkManagerBackstop(context.applicationContext)

    /**
     * #27 — the seven findings, and the two repairs that are safe to run unasked.
     *
     * The three provider-side findings come from [localReminderProvider]'s own `health()` rather
     * than being re-derived here, so each ratified sentence is written once in the repository.
     * Derived state arrives through [scheduleStateReader], which has no write method, so no health
     * run can reach `schedule_state` (invariant 17).
     */
    val reminderHealthCheck: ReminderHealthCheck = ReminderHealthCheck(
        provider = localReminderProvider,
        platform = platformState,
        backstop = backstopWork,
        alarm = digestAlarm,
        schedules = schedules,
        states = scheduleStateReader,
        assets = assets,
        groups = groups,
    )

    /**
     * Reminder health, as the dashboard's badge asks about it (master plan decision 28) — the real
     * check now, with the cache decision 32 requires: both badge surfaces read this on every
     * emission of their own flows and the check reads the standby bucket, so it answers from the
     * last refresh rather than doing platform work per keystroke. Launch, the backstop worker and
     * the Health screen are what refresh it.
     *
     * A `val`, deliberately. Both view models read it in their `AppGraph` constructor, so the value
     * is captured when the view model is built and a later assignment would be silently ignored by
     * any view model already alive. A test that needs the badge drawn against a known answer passes
     * its own summary to the screen instead (see `DashboardScreen`'s `health` parameter), which is
     * the seam that cannot be raced.
     */
    val reminderHealth: ReminderHealth = ReminderHealth(reminderHealthCheck)
    val healthSummary: HealthSummary = reminderHealth

    /**
     * 1.4 — the offers an event makes (spec §3.3, §5.4): "Mark operational?" and the season offer,
     * each written only by its accept (B06's `AcceptOperationalOffer`, B04's `AcceptSeasonOffer`).
     * Built here once; the completion flow and the journal entry both ask through it.
     */
    val eventOffers: EventOffers = EventOffers(
        OperationalOffers(assets, conditions, acceptOperationalOffer, today),
        SeasonOffers(assets, seasonActivations, acceptSeasonOffer, today),
    )

    /**
     * 1.2 — **the only completion mechanism** (master plan decision 36, #50). The schedule detail
     * screen, the scan completion sheet and the Maintenance destination's "Log maintenance" quick
     * action all drive this one instance, so the three cannot ask "when was this done" in three
     * different ways or write an event by three different paths.
     *
     * One instance for the process, not one per screen: the flow holds the open prompt, and a
     * second instance would be a second prompt over one store.
     */
    val completionFlow: CompletionFlow = CompletionFlow(
        schedules, definitions, completeSchedule, completeGroupMembers, today,
        eventOffers,
    )

    /**
     * The schedule detail screen's two **read-only** history seams (master plan decision 41's
     * pattern): the screen has to show its completions and its closed rounds, and a one-method read
     * apiece is what makes "it cannot write one" a fact about the types rather than a promise.
     */
    val scheduleCompletions: ScheduleCompletions = ScheduleCompletions { scheduleId ->
        events.all().filter { it.scheduleId == scheduleId }
    }
    val scheduleClosures: ScheduleClosures = ScheduleClosures { scheduleId ->
        closures.forSchedule(scheduleId)
    }

    /**
     * 1.2 — the in-app snooze: the **device-local instant only**, no `*_on` column and no event
     * (invariant 20).
     *
     * `schedule_local_delivery` and its port are B06's (master plan decision 25), and B06's
     * [reminderSnooze] is that use case — so the seam B14's operations take is satisfied by a method
     * reference to it rather than by a second writer of one column. The notification action's
     * "Snooze 1 day" and the in-app "Snooze" are therefore not merely the same length: they are the
     * same code.
     */
    val scheduleSnooze: ScheduleSnooze = ScheduleSnooze(reminderSnooze::snooze)

    /**
     * 1.2 (#50) — the scan completion sheet's three **read-only** seams and its routing question.
     *
     * [lastCompletionReadings] and [lastCompletionEventId] are master plan decision 41: D5 §7A
     * makes the last completion's key readings a display fact, `DueItem` cannot carry heterogeneous
     * profile values, and handing the sheet `EventRepository` or `ScheduleStateRepository` would
     * hand it a write surface — so each is one read, satisfied here by a method the shipped ports
     * already offer. [reminderReconcile] is the sweep a completion runs so the standing notification
     * is quiesced **by canonical state** and never by deleting a notification (#50 AC 8).
     *
     * [scanSheetOffer] is the routing half of spec §10.1: it is `scanSheetContent(...).opens` over
     * the **same** projection and health view the sheet reads, so "does this scan open the sheet"
     * and "what does the sheet show" are one answer and cannot drift (inv. 123).
     */
    val lastCompletionReadings: LastCompletionReadings = LastCompletionReadings { eventId ->
        events.get(eventId)?.measurements.orEmpty()
    }
    val lastCompletionEventId: LastCompletionEventId = LastCompletionEventId { scheduleId ->
        // Through the one read accessor (master plan §8.6), so a schedule with no stored row yet —
        // right after the 7 → 8 migration — still shows its last completion's readings.
        schedules.get(scheduleId)?.let { recomputeSchedules.readState(it) }?.lastCompletionEventId
    }
    val reminderReconcile: ReminderReconcile = ReminderReconcile { reminderRuns.reconcileAll() }
    /**
     * The group round a schedule is currently on, **derived and read** — B09's third read seam
     * (review blocking 2). `occurrenceOf` derives without upserting, so the scan path stays a pure
     * read and `rebuild` remains the only writer of `schedule_state` (invariant 17).
     */
    val scanRoundMembership: ScanRoundMembership = ScanRoundMembership { scheduleId ->
        schedules.get(scheduleId)?.let { recomputeSchedules.occurrenceOf(it) }
    }
    val scanSheetOffer: ScanSheetOffer = ScanSheetOffer { assetId ->
        // The **same** call the sheet itself makes, so "does this scan open the sheet" and "what
        // does the sheet show" are one answer (the one predicate; O-8).
        scanSheetContentFor(assetId, dueReadModel, scanRoundMembership, assetHealthReadModel).opens
    }

    internal companion object {
        const val DB_NAME = "servicetag.db"

        /** Room's `@Database(version = ...)`; recorded in the manifest so an import can refuse. */
        const val SCHEMA_VERSION = 9
    }
}
