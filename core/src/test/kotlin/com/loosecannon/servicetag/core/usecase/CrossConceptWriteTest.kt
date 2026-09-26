package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.backup.BackupData
import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.merge.MergePlan
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetCategory
import com.loosecannon.servicetag.core.model.AssetCondition
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetReference
import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.model.AttachmentId
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.EventProfile
import com.loosecannon.servicetag.core.model.ExternalLink
import com.loosecannon.servicetag.core.model.HealthAggregation
import com.loosecannon.servicetag.core.model.HealthDriver
import com.loosecannon.servicetag.core.model.HealthSubject
import com.loosecannon.servicetag.core.model.HealthSubjectId
import com.loosecannon.servicetag.core.model.HealthSubjectKind
import com.loosecannon.servicetag.core.model.MaintenanceGroup
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.MeasurementDefinition
import com.loosecannon.servicetag.core.model.OccurrenceClosure
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.ReferenceId
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleState
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonActivation
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.AttachmentRepository
import com.loosecannon.servicetag.core.ports.CategoryRepository
import com.loosecannon.servicetag.core.ports.ClosureRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.ConditionRepository
import com.loosecannon.servicetag.core.ports.DefinitionRepository
import com.loosecannon.servicetag.core.ports.EventRepository
import com.loosecannon.servicetag.core.ports.GroupRepository
import com.loosecannon.servicetag.core.ports.HealthSubjectRepository
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.LinkRepository
import com.loosecannon.servicetag.core.ports.ProfileRepository
import com.loosecannon.servicetag.core.ports.ReferenceRepository
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.ScheduleStateRepository
import com.loosecannon.servicetag.core.ports.SeasonActivationRepository
import com.loosecannon.servicetag.core.ports.TagRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.schedule.SeasonPhase
import com.loosecannon.servicetag.core.testing.FakeAttachmentStorage
import com.loosecannon.servicetag.core.testing.FakeUnitOfWork
import com.loosecannon.servicetag.core.testing.HealthFixtures
import com.loosecannon.servicetag.core.testing.InMemoryAssetRepository
import com.loosecannon.servicetag.core.testing.InMemoryAttachmentRepository
import com.loosecannon.servicetag.core.testing.InMemoryCategoryRepository
import com.loosecannon.servicetag.core.testing.InMemoryClosureRepository
import com.loosecannon.servicetag.core.testing.InMemoryConditionRepository
import com.loosecannon.servicetag.core.testing.InMemoryDefinitionRepository
import com.loosecannon.servicetag.core.testing.InMemoryEventRepository
import com.loosecannon.servicetag.core.testing.InMemoryGroupRepository
import com.loosecannon.servicetag.core.testing.InMemoryHealthSubjectRepository
import com.loosecannon.servicetag.core.testing.InMemoryLinkRepository
import com.loosecannon.servicetag.core.testing.InMemoryProfileRepository
import com.loosecannon.servicetag.core.testing.InMemoryReferenceRepository
import com.loosecannon.servicetag.core.testing.InMemoryScheduleRepository
import com.loosecannon.servicetag.core.testing.InMemoryScheduleStateRepository
import com.loosecannon.servicetag.core.testing.InMemorySeasonActivationRepository
import com.loosecannon.servicetag.core.testing.InMemoryTagRepository
import com.loosecannon.servicetag.core.testing.SeasonFixtures
import com.loosecannon.servicetag.core.testing.archiveOf
import com.loosecannon.servicetag.core.testing.dayMillis
import com.loosecannon.servicetag.core.testing.plainAssetOf
import com.loosecannon.servicetag.core.testing.scheduleOf
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * **Which tables each 1.4 use case writes** (master plan §14; inv. 81–83, 86). Every store is wrapped
 * in a recorder that notes the table of each write call — an upsert, an insert or a delete, whether
 * or not it changes a value — and each use case is run on its own, so its set is exactly what it
 * wrote. The real recompute sits behind them, so "derived state" is `schedule_state`, written by the
 * rebuild and by nothing else.
 *
 * - The two condition writers write `asset_condition` and nothing else, and nothing else writes it.
 * - No activation, season, break, policy, subject or guarded schedule write touches `asset_event` or
 *   `occurrence_closure`; a policy write touches only the asset row; a subject write only its own table.
 * - The journal's own writers — an event and a completion — write no condition (inv. 81).
 * - #74: `asset_category` is written by the three Asset commands only when they save a category
 *   nobody has saved before, by a rename and a delete, and by the two imports of backup format 9 —
 *   the merge apply (the rows its plan accepted and synthesised) and the replace (its wipe, the
 *   archive's rows and the promotion); the merge **plan** writes nothing at all, and nothing else
 *   here writes `asset_category`. The imports' other stores are recorded too, so their sets are
 *   whole.
 */
class CrossConceptWriteTest {

    /** Every write, one entry per call, so a count is asserted as well as a table. */
    private val writes = mutableListOf<String>()

    private val assetRows = InMemoryAssetRepository()
    private val eventRows = InMemoryEventRepository()
    private val activationRows = InMemorySeasonActivationRepository()
    private val conditionRows = InMemoryConditionRepository()
    private val subjectRows = InMemoryHealthSubjectRepository()
    private val closureRows = InMemoryClosureRepository()
    private val stateRows = InMemoryScheduleStateRepository()
    private val scheduleRows = InMemoryScheduleRepository(closureRows, stateRows)
    private val groupRows = InMemoryGroupRepository()
    private val definitionRows = InMemoryDefinitionRepository()
    private val profileRows = InMemoryProfileRepository()
    private val categoryRows = InMemoryCategoryRepository()
    private val tagRows = InMemoryTagRepository()
    private val linkRows = InMemoryLinkRepository()
    private val attachmentRows = InMemoryAttachmentRepository()
    private val referenceRows = InMemoryReferenceRepository()
    private val uow = FakeUnitOfWork(
        assetRows, eventRows, activationRows, conditionRows, subjectRows, closureRows, stateRows, scheduleRows,
        groupRows, definitionRows, profileRows, categoryRows, tagRows, linkRows, attachmentRows, referenceRows,
    )

    private val assets = object : AssetRepository by assetRows {
        override suspend fun upsert(asset: Asset) = assetRows.upsert(asset).also { writes += "asset" }
        override suspend fun delete(id: AssetId) = assetRows.delete(id).also { writes += "asset" }
        override suspend fun deleteAll() = assetRows.deleteAll().also { writes += "asset" }
    }
    private val events = object : EventRepository by eventRows {
        override suspend fun upsert(e: AssetEvent) = eventRows.upsert(e).also { writes += "asset_event" }
        override suspend fun delete(id: EventId) = eventRows.delete(id).also { writes += "asset_event" }
        override suspend fun deleteAll() = eventRows.deleteAll().also { writes += "asset_event" }
    }
    private val activations = object : SeasonActivationRepository by activationRows {
        override suspend fun insert(row: SeasonActivation) =
            activationRows.insert(row).also { writes += "asset_season_activation" }
    }
    private val conditions = object : ConditionRepository by conditionRows {
        override suspend fun insert(row: AssetCondition) = conditionRows.insert(row).also { writes += "asset_condition" }
    }
    private val subjects = object : HealthSubjectRepository by subjectRows {
        override suspend fun upsert(subject: HealthSubject) =
            subjectRows.upsert(subject).also { writes += "health_subject" }
    }
    private val closures = object : ClosureRepository by closureRows {
        override suspend fun insert(closure: OccurrenceClosure) =
            closureRows.insert(closure).also { writes += "occurrence_closure" }
    }
    private val states = object : ScheduleStateRepository by stateRows {
        override suspend fun upsert(state: ScheduleState) = stateRows.upsert(state).also { writes += "schedule_state" }
        override suspend fun deleteAll() = stateRows.deleteAll().also { writes += "schedule_state" }
    }
    private val schedules = object : ScheduleRepository by scheduleRows {
        override suspend fun upsert(schedule: MaintenanceSchedule) =
            scheduleRows.upsert(schedule).also { writes += "maintenance_schedule" }
        override suspend fun deleteAll() = scheduleRows.deleteAll().also { writes += "maintenance_schedule" }
    }
    private val groups = object : GroupRepository by groupRows {
        override suspend fun upsert(group: MaintenanceGroup) = groupRows.upsert(group).also { writes += "maintenance_group" }
        override suspend fun deleteAll() = groupRows.deleteAll().also { writes += "maintenance_group" }
    }
    private val definitions = object : DefinitionRepository by definitionRows {
        override suspend fun upsert(d: MeasurementDefinition) =
            definitionRows.upsert(d).also { writes += "measurement_definition" }
        override suspend fun delete(id: DefinitionId) =
            definitionRows.delete(id).also { writes += "measurement_definition" }
        override suspend fun deleteAll() = definitionRows.deleteAll().also { writes += "measurement_definition" }
    }
    private val categories = object : CategoryRepository by categoryRows {
        override suspend fun upsert(row: AssetCategory) = categoryRows.upsert(row).also { writes += "asset_category" }
        override suspend fun delete(key: String) = categoryRows.delete(key).also { writes += "asset_category" }
        override suspend fun deleteAll() = categoryRows.deleteAll().also { writes += "asset_category" }
    }
    private val tags = object : TagRepository by tagRows {
        override suspend fun upsert(tag: TagBinding) = tagRows.upsert(tag).also { writes += "nfc_tag" }
        override suspend fun delete(id: TagId) = tagRows.delete(id).also { writes += "nfc_tag" }
        override suspend fun deleteAll() = tagRows.deleteAll().also { writes += "nfc_tag" }
    }
    private val links = object : LinkRepository by linkRows {
        override suspend fun upsert(link: ExternalLink) = linkRows.upsert(link).also { writes += "external_link" }
        override suspend fun deleteAll() = linkRows.deleteAll().also { writes += "external_link" }
    }
    private val attachments = object : AttachmentRepository by attachmentRows {
        override suspend fun upsert(a: Attachment) = attachmentRows.upsert(a).also { writes += "attachment" }
        override suspend fun delete(id: AttachmentId) = attachmentRows.delete(id).also { writes += "attachment" }
        override suspend fun deleteAll() = attachmentRows.deleteAll().also { writes += "attachment" }
    }
    private val references = object : ReferenceRepository by referenceRows {
        override suspend fun upsert(reference: AssetReference) =
            referenceRows.upsert(reference).also { writes += "asset_reference" }
        override suspend fun delete(id: ReferenceId) = referenceRows.delete(id).also { writes += "asset_reference" }
        override suspend fun deleteAll() = referenceRows.deleteAll().also { writes += "asset_reference" }
    }
    private val profiles = object : ProfileRepository by profileRows {
        override suspend fun upsert(p: EventProfile) = profileRows.upsert(p).also { writes += "event_profile" }
        override suspend fun delete(id: ProfileId) = profileRows.delete(id).also { writes += "event_profile" }
        override suspend fun deleteAll() = profileRows.deleteAll().also { writes += "event_profile" }
    }

    private var seq = 0
    private val ids = IdGenerator { "id-%03d".format(++seq) }
    private var now = dayMillis("2026-09-24") + 1_000L
    private val clock = Clock { now++ }
    private val today = Today { LocalDate.parse("2026-09-24") }
    private val recompute = RecomputeSchedules(
        schedules, states, events, closures, groups, assets, activations, today, clock,
    ) { ZoneOffset.UTC }

    private val recordCondition = RecordCondition(assets, events, conditions, uow, ids, clock, today)
    private val acceptOperationalOffer = AcceptOperationalOffer(conditions, recordCondition, uow)
    private val recordSeasonActivation =
        RecordSeasonActivation(assets, events, activations, uow, ids, clock, today, recompute)
    private val acceptSeasonOffer = AcceptSeasonOffer(activations, recordSeasonActivation, uow, today)
    private val setSeasonMode = SetSeasonMode(assets, schedules, activations, uow, ids, clock, today, recompute)
    private val setMaintenanceBreak = SetMaintenanceBreak(assets, schedules, uow, clock, recompute)
    private val setHealthPolicy = SetHealthPolicy(assets, subjects, uow, clock)
    private val saveHealthSubject = SaveHealthSubject(subjects, assets, schedules, profiles, uow, ids, clock)
    private val archiveHealthSubject = ArchiveHealthSubject(subjects, assets, schedules, uow, clock)
    private val saveSchedule =
        SaveSchedule(schedules, assets, groups, definitions, profiles, uow, ids, clock, recompute, subjects)
    private val archiveSchedule = ArchiveSchedule(schedules, uow, recompute, subjects, assets, clock)
    private val promoteCategory = PromoteCategory(categories)
    private val saveAssetSettings = SaveAssetSettings(
        assets, schedules, subjects, activations, uow, ids, clock, today, recompute,
        ApplyTemplate(definitions, profiles, assets, uow, ids, clock), promoteCategory,
    )
    private val createAsset =
        CreateAsset(assets, uow, ids, clock, ApplyTemplate(definitions, profiles, assets, uow, ids, clock), promoteCategory)
    private val updateAsset = UpdateAsset(assets, schedules, uow, clock, recompute, promoteCategory)
    private val renameCategory = RenameCategory(categories, assets, uow, clock)
    private val deleteCategory = DeleteCategory(categories, assets, uow)
    private val logEvent = LogEvent(events, definitions, profiles, assets, uow, ids, clock, recompute)
    private val completeSchedule = CompleteSchedule(schedules, events, definitions, profiles, uow, ids, clock, recompute)
    private val storage = FakeAttachmentStorage()
    private val buildMergePlan = BuildBackupMergePlan(
        assets, groups, tags, links, definitions, profiles, schedules, closures, events, attachments, references,
        activations, conditions, subjects, categories, storage, uow,
    )
    private val applyMergePlan = ApplyBackupMergePlan(
        assets, groups, tags, links, definitions, profiles, schedules, closures, events, attachments, references,
        activations, conditions, subjects, categories, storage, uow, rebuildAll = { recompute.all() },
    )
    private val importBackupReplace = ImportBackupReplace(
        assets, groups, tags, links, definitions, profiles, schedules, closures, events, attachments, references,
        activations, conditions, subjects, categories, storage, uow, rebuildAll = { recompute.all() },
    )

    /** A pre-upgrade (format 8) archive with one asset under a category this install has never saved. */
    private val donorArchive = archiveOf(
        BackupData(
            assets = listOf(plainAssetOf("d1").copy(category = "Test gear").toDto()),
            nfcTags = emptyList(),
            externalLinks = emptyList(),
        ),
        formatVersion = 8,
    )

    private fun seed() {
        for ((id, mode) in listOf("a1" to SeasonMode.YEAR_ROUND, "a2" to SeasonMode.MANUAL, "a3" to SeasonMode.YEAR_ROUND)) {
            SeasonFixtures.assetOf(id = id, name = "Generator $id", mode = mode).also { assetRows.rows[id] = it }
        }
        for ((id, assetId) in listOf("s1" to "a1", "s2" to "a2", "s3" to "a1", "s4" to "a1", "s5" to "a3")) {
            scheduleOf(
                id = id, assetId = assetId, title = "Service $id", timeInterval = 1, timeUnit = RecurrenceUnit.MONTH,
                anchorOn = "2026-01-15",
            ).also { scheduleRows.rows[id] = it }
        }
        activationRows.rows["act-0"] =
            SeasonFixtures.activationOf("act-0", "a2", SeasonAction.END, "2026-05-01", dayMillis("2026-05-01"))
        for ((id, scheduleId) in listOf("h1" to "s3", "h2" to "s4")) {
            subjectRows.rows[id] = HealthSubject(
                id = HealthSubjectId(id), assetId = AssetId("a1"), name = "Battery $id", kind = HealthSubjectKind.PART,
                driver = HealthDriver.MAINTENANCE_OVERDUE, scheduleId = ScheduleId(scheduleId), baselineProfileId = null,
                nominalUntilDays = 14, warningFromDays = 45, criticalFromDays = 120, weight = 1, sortOrder = 0,
                archivedAt = null, createdAt = 0L, updatedAt = 0L,
            )
        }
        conditionRows.rows["c-0"] = AssetCondition(
            "c-0", AssetId("a1"), OperationalCondition.DOWN, "2026-09-20", null, "UTC", "Battery failed", null, 0L,
        )
        eventRows.rows["e-0"] = HealthFixtures.eventOf("e-0", "a1", EventKind.REPLACEMENT, "Battery replaced", "2026-09-22")
        eventRows.rows["e-season"] = HealthFixtures.eventOf("e-season", "a2", EventKind.SEASON_START, "Opened", "2026-09-23")
    }

    /** Each use case's writes, table by table, with how many each table took. */
    private val counts = mutableMapOf<String, Map<String, Int>>()

    private suspend fun wrote(what: String, run: suspend () -> Any?): Pair<String, Set<String>> {
        writes.clear()
        run()
        counts[what] = writes.groupingBy { it }.eachCount()
        return what to writes.toSet()
    }

    @Test
    fun eachUseCaseWritesOnlyItsOwnTables() = runBlocking<Unit> {
        seed()
        val subject = HealthSubjectCommand(
            name = "Battery age", kind = HealthSubjectKind.PART, driver = HealthDriver.AGE,
            nominalUntilDays = 365, warningFromDays = 1095, criticalFromDays = 1460,
        )
        var created: HealthSubject? = null
        var compressor: Asset? = null
        var planned: MergePlan? = null
        var keysBeforeTheImports: List<String> = emptyList()
        val s1 = scheduleRows.rows.getValue("s1")
        val cmdS1 = ScheduleCommand(
            targetAssetId = AssetId("a1"), targetGroupId = null, title = s1.title, timeInterval = s1.timeInterval,
            timeUnit = s1.timeUnit, anchorOn = s1.anchorOn, leadDays = s1.leadDays, providers = s1.providers,
        )

        val cases = listOf(
            wrote("RecordCondition") {
                recordCondition.run(AssetId("a1"), ConditionCommand(OperationalCondition.DEGRADED, tzId = "UTC"))
            },
            wrote("AcceptOperationalOffer") { acceptOperationalOffer.run(AssetId("a1"), eventRows.rows.getValue("e-0")) },
            wrote("RecordSeasonActivation") {
                recordSeasonActivation.run(AssetId("a2"), ActivationCommand(SeasonAction.START, "2026-09-20"))
            },
            wrote("AcceptSeasonOffer") {
                recordSeasonActivation.run(AssetId("a2"), ActivationCommand(SeasonAction.END, "2026-09-21"))
                writes.clear()
                acceptSeasonOffer.run(AssetId("a2"), eventRows.rows.getValue("e-season"), SeasonAction.START)
            },
            wrote("SetSeasonMode into MANUAL") {
                setSeasonMode.run(AssetId("a3"), SeasonModeCommand(SeasonMode.MANUAL, manualPhase = SeasonPhase.IN_SEASON))
            },
            wrote("SetSeasonMode to CALENDAR") {
                setSeasonMode.run(AssetId("a1"), SeasonModeCommand(SeasonMode.CALENDAR, "04-15", "10-31"))
            },
            wrote("SetMaintenanceBreak") { setMaintenanceBreak.run(AssetId("a1"), BreakCommand("07-01", "07-10")) },
            wrote("SetHealthPolicy") {
                setHealthPolicy.run(AssetId("a1"), HealthPolicyCommand(HealthAggregation.AVERAGE))
            },
            wrote("SaveHealthSubject.create") { created = saveHealthSubject.create(AssetId("a1"), subject) },
            wrote("SaveHealthSubject.update") {
                saveHealthSubject.update(created!!.id, subject.copy(name = "Battery age, pack two"))
            },
            wrote("ArchiveHealthSubject") { archiveHealthSubject.run(HealthSubjectId("h1"), archived = true) },
            wrote("ArchiveHealthSubject restore") { archiveHealthSubject.run(HealthSubjectId("h1"), archived = false) },
            wrote("SaveSchedule unlinking") {
                saveSchedule.run(
                    ScheduleId("s3"),
                    cmdS1.copy(title = "Service s3", targetAssetId = AssetId("a3")),
                    unlinkHealthSubject = true,
                )
            },
            wrote("ArchiveSchedule unlinking") {
                archiveSchedule.run(ScheduleId("s4"), archived = true, unlinkHealthSubject = true)
            },
            wrote("SaveAssetSettings") {
                saveAssetSettings.run(
                    AssetId("a1"),
                    AssetSettingsCommand(
                        AssetCommand(name = "Standby generator a1"),
                        SeasonModeCommand(SeasonMode.MANUAL, manualPhase = SeasonPhase.OUT_OF_SEASON),
                        BreakCommand("07-01", "07-10"),
                        HealthPolicyCommand(HealthAggregation.WORST),
                    ),
                )
            },
            wrote("LogEvent") {
                logEvent.run(
                    EventCommand(
                        AssetId("a1"), null, EventKind.MAINTENANCE, "Oil change", "2026-09-24", null, "UTC", "",
                        emptyMap(), emptyList(),
                    ),
                )
            },
            wrote("CompleteSchedule") { completeSchedule.run(ScheduleId("s1"), CompletionCommand("2026-09-24", tzId = "UTC")) },
            wrote("CreateAsset, a new category") { compressor = createAsset.run(AssetCommand(name = "Compressor", category = "Appliance")) },
            wrote("CreateAsset, a built-in") { createAsset.run(AssetCommand(name = "Spare mower", category = "lawn  MOWER")) },
            wrote("SaveAssetSettings, a new category") {
                saveAssetSettings.run(
                    compressor!!.id,
                    AssetSettingsCommand(
                        AssetCommand(name = "Compressor", category = "Backup power"),
                        SeasonModeCommand(SeasonMode.YEAR_ROUND),
                        BreakCommand(null, null),
                        HealthPolicyCommand(HealthAggregation.WORST),
                    ),
                )
            },
            wrote("UpdateAsset, a saved category") {
                updateAsset.run(AssetId("a3"), AssetCommand(name = "Generator a3", category = " APPLIANCE "))
            },
            wrote("UpdateAsset, a new category") {
                updateAsset.run(AssetId("a3"), AssetCommand(name = "Generator a3", category = "Water heater"))
            },
            wrote("RenameCategory") { renameCategory.run("backup power", "Standby power") },
            wrote("DeleteCategory") { deleteCategory.run("appliance") },
            wrote("BuildBackupMergePlan") {
                keysBeforeTheImports = categoryRows.rows.keys.sorted()
                planned = buildMergePlan.run(donorArchive)
            },
            wrote("ApplyBackupMergePlan, a new category") { applyMergePlan.run(planned!!) },
            wrote("ImportBackupReplace") { importBackupReplace.run(donorArchive) },
        )

        val derived = "schedule_state"
        val expected = mapOf(
            "RecordCondition" to setOf("asset_condition"),
            "AcceptOperationalOffer" to setOf("asset_condition"),
            "RecordSeasonActivation" to setOf("asset_season_activation", derived),
            "AcceptSeasonOffer" to setOf("asset_season_activation", derived),
            "SetSeasonMode into MANUAL" to setOf("asset", "asset_season_activation", derived),
            "SetSeasonMode to CALENDAR" to setOf("asset", derived),
            "SetMaintenanceBreak" to setOf("asset", derived),
            "SetHealthPolicy" to setOf("asset"),
            "SaveHealthSubject.create" to setOf("health_subject"),
            "SaveHealthSubject.update" to setOf("health_subject"),
            "ArchiveHealthSubject" to setOf("health_subject"),
            "ArchiveHealthSubject restore" to setOf("health_subject"),
            "SaveSchedule unlinking" to setOf("maintenance_schedule", "health_subject", derived),
            "ArchiveSchedule unlinking" to setOf("maintenance_schedule", "health_subject", derived),
            "SaveAssetSettings" to setOf("asset", "asset_season_activation", derived),
            "LogEvent" to setOf("asset_event", derived),
            "CompleteSchedule" to setOf("asset_event", derived),
            "CreateAsset, a new category" to setOf("asset", "asset_category"),
            "CreateAsset, a built-in" to setOf("asset"),
            "SaveAssetSettings, a new category" to setOf("asset", "asset_category"),
            "UpdateAsset, a saved category" to setOf("asset"),
            "UpdateAsset, a new category" to setOf("asset", "asset_category"),
            "RenameCategory" to setOf("asset_category", "asset"),
            "DeleteCategory" to setOf("asset_category"),
            "BuildBackupMergePlan" to emptySet(),
            "ApplyBackupMergePlan, a new category" to setOf("asset_category", "asset", derived),
            "ImportBackupReplace" to REPLACE_WRITES,
        )
        assertEquals(expected, cases.toMap())
        assertEquals(
            setOf(
                "CreateAsset, a new category", "SaveAssetSettings, a new category", "UpdateAsset, a new category",
                "RenameCategory", "DeleteCategory", "ApplyBackupMergePlan, a new category", "ImportBackupReplace",
            ),
            cases.filter { (_, tables) -> "asset_category" in tables }.map { it.first }.toSet(),
            "only a new category's save, a rename, a delete, a merge apply and a replace write asset_category",
        )
        for (what in listOf(
            "CreateAsset, a new category", "SaveAssetSettings, a new category", "UpdateAsset, a new category",
            "ApplyBackupMergePlan, a new category",
        )) {
            assertEquals(1, counts.getValue(what)["asset_category"], "$what writes exactly one category row")
        }
        // The replace's two: the wipe, then the one row its promotion adds.
        assertEquals(2, counts.getValue("ImportBackupReplace")["asset_category"])
        assertEquals(
            listOf("standby power", "water heater"),
            keysBeforeTheImports,
            "the rename moved the key; the delete took the unused row",
        )
        assertEquals(listOf("test gear"), categoryRows.rows.keys.sorted(), "the replace left the archive's one category")

        // The replace is the one case that wipes the journal: it is every table's writer by definition.
        val onePointFour = cases.filter { (what, _) -> what !in setOf("LogEvent", "CompleteSchedule", "ImportBackupReplace") }
        onePointFour.forEach { (what, tables) ->
            assertTrue("asset_event" !in tables && "occurrence_closure" !in tables, "$what wrote $tables")
        }
        assertEquals(
            setOf("RecordCondition", "AcceptOperationalOffer"),
            cases.filter { (_, tables) -> "asset_condition" in tables }.map { it.first }.toSet(),
            "only the two condition writers write asset_condition",
        )
        val activationWriters =
            listOf("SetSeasonMode into MANUAL", "SaveAssetSettings", "RecordSeasonActivation", "AcceptSeasonOffer")
        for (what in activationWriters) {
            assertEquals(1, counts.getValue(what)["asset_season_activation"], "$what writes exactly one activation")
        }
        for (what in listOf("RecordCondition", "AcceptOperationalOffer")) {
            assertEquals(1, counts.getValue(what)["asset_condition"], "$what writes exactly one condition")
        }
    }

    private companion object {
        /**
         * Every canonical store a replace wipes, and the two it loads here (an asset and its promoted
         * category). No `schedule_state`: the wipe clears it through the schedule rows, and the
         * archive brings no schedule for the rebuild to write a state for.
         */
        val REPLACE_WRITES = setOf(
            "attachment", "asset_event", "maintenance_schedule", "maintenance_group", "event_profile",
            "measurement_definition", "nfc_tag", "external_link", "asset_reference", "asset", "asset_category",
        )
    }
}
