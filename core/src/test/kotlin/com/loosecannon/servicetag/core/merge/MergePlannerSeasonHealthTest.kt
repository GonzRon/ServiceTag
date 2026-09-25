package com.loosecannon.servicetag.core.merge

import com.loosecannon.servicetag.core.backup.BackupData
import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetCondition
import com.loosecannon.servicetag.core.model.AssetReference
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.HealthAggregation
import com.loosecannon.servicetag.core.model.HealthSubject
import com.loosecannon.servicetag.core.model.HealthSubjectId
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ReferenceId
import com.loosecannon.servicetag.core.model.ReferenceKind
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonActivation
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.ConditionRepository
import com.loosecannon.servicetag.core.ports.HealthSubjectRepository
import com.loosecannon.servicetag.core.ports.ReferenceRepository
import com.loosecannon.servicetag.core.ports.SeasonActivationRepository
import com.loosecannon.servicetag.core.testing.FakeAttachmentStorage
import com.loosecannon.servicetag.core.testing.FakeUnitOfWork
import com.loosecannon.servicetag.core.testing.InMemoryAssetRepository
import com.loosecannon.servicetag.core.testing.InMemoryAttachmentRepository
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
import com.loosecannon.servicetag.core.testing.InMemorySeasonActivationRepository
import com.loosecannon.servicetag.core.testing.InMemoryTagRepository
import com.loosecannon.servicetag.core.testing.Rollbackable
import com.loosecannon.servicetag.core.testing.Witnessed
import com.loosecannon.servicetag.core.testing.activationOf
import com.loosecannon.servicetag.core.testing.archiveOf
import com.loosecannon.servicetag.core.testing.backupOf
import com.loosecannon.servicetag.core.testing.conditionOf
import com.loosecannon.servicetag.core.testing.plainAssetOf
import com.loosecannon.servicetag.core.testing.scheduleOf
import com.loosecannon.servicetag.core.testing.seasonalAssetOf
import com.loosecannon.servicetag.core.testing.subjectOf
import com.loosecannon.servicetag.core.usecase.ApplyBackupMergePlan
import com.loosecannon.servicetag.core.usecase.BuildBackupMergePlan
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * 1.4 (B03) — merge tables 12–14 (spec §8.4; master plan §6): two immutable fact tables that only
 * ever insert, match or differ, and the configuration table whose second identity — the schedule a
 * non-archived subject drives — declines rather than conflicts, in dependency position after
 * `REFERENCES`. The shipped rules are `MergePlannerTest`'s and its siblings'; this class adds one
 * case per way the three new passes could go wrong.
 */
class MergePlannerSeasonHealthTest {

    // --- fixture ---------------------------------------------------------------------------------

    private val a1 = seasonalAssetOf(healthPrimarySubjectId = null)
    private val s1 = scheduleOf(
        id = "s1", assetId = "a1", timeInterval = 1, timeUnit = RecurrenceUnit.MONTH, anchorOn = "2026-01-01",
    )

    private fun data(
        assets: List<Asset> = listOf(a1),
        schedules: List<MaintenanceSchedule> = emptyList(),
        references: List<AssetReference> = emptyList(),
        activations: List<SeasonActivation> = emptyList(),
        conditions: List<AssetCondition> = emptyList(),
        subjects: List<HealthSubject> = emptyList(),
    ) = BackupData(
        assets = assets.map { it.toDto() },
        nfcTags = emptyList(),
        externalLinks = emptyList(),
        maintenanceSchedules = schedules.map { it.toDto() },
        assetReferences = references.map { it.toDto() },
        seasonActivations = activations.map { it.toDto() },
        assetConditions = conditions.map { it.toDto() },
        healthSubjects = subjects.map { it.toDto() },
    )

    private fun snapshot(
        assets: List<Asset> = emptyList(),
        schedules: List<MaintenanceSchedule> = emptyList(),
        activations: List<SeasonActivation> = emptyList(),
        conditions: List<AssetCondition> = emptyList(),
        subjects: List<HealthSubject> = emptyList(),
    ) = MergeSnapshot(
        assets = assets,
        schedules = schedules,
        seasonActivations = activations,
        conditions = conditions,
        healthSubjects = subjects,
        attachmentStoreConfigured = true,
    )

    private fun plan(data: BackupData, snapshot: MergeSnapshot): MergePlan = mergePlanOf(backupOf(data), snapshot)

    private fun MergePlan.decision(table: MergeTable, id: String): MergeDecision =
        decisions.single { it.table == table && it.id == id }

    private fun reference(id: String) = AssetReference(
        id = ReferenceId(id), assetId = AssetId("a1"), kind = ReferenceKind.WEB_URL,
        uri = "https://example-snowblower.invalid/$id", displayName = "Manual $id", description = "",
        scheme = "https", createdAt = 1L, updatedAt = 2L,
    )

    // --- order -----------------------------------------------------------------------------------

    /**
     * Hazard: merge order. The fourteen members are asserted as a list, the three new ones after
     * `REFERENCES`; and an archive whose asset, schedule, facts and subject arrive together inserts
     * every row, because each owner is an INSERT of this same plan by the time its row is decided.
     */
    @Test
    fun theFourteenTablesAreInWriteOrder() {
        assertEquals(
            listOf(
                MergeTable.ASSETS, MergeTable.GROUPS, MergeTable.DEFINITIONS, MergeTable.PROFILES,
                MergeTable.SCHEDULES, MergeTable.CLOSURES, MergeTable.LINKS, MergeTable.TAGS,
                MergeTable.EVENTS, MergeTable.ATTACHMENTS, MergeTable.REFERENCES,
                MergeTable.SEASON_ACTIVATIONS, MergeTable.CONDITIONS, MergeTable.HEALTH_SUBJECTS,
            ),
            MergeTable.entries.toList(),
        )

        val merged = plan(
            data(
                schedules = listOf(s1),
                activations = listOf(activationOf("act-1")),
                conditions = listOf(conditionOf("c-1")),
                subjects = listOf(subjectOf("h1", scheduleId = "s1")),
            ),
            snapshot(),
        )
        assertTrue(merged.applicable, "unexpected conflicts: ${merged.conflicts}")
        assertTrue(merged.decisions.all { it.verdict == MergeVerdict.INSERT }, "${merged.decisions}")
        assertEquals(listOf("act-1"), merged.writes.seasonActivations.map { it.id })
        assertEquals(listOf("c-1"), merged.writes.conditions.map { it.id })
        assertEquals(listOf("h1"), merged.writes.healthSubjects.map { it.id.value })
        // and the report carries the three tallies under the tables' own names
        val report = merged.report()
        assertEquals(MergeTally(1, 0, 0, 0), report.seasonActivations)
        assertEquals(MergeTally(1, 0, 0, 0), report.conditions)
        assertEquals(MergeTally(1, 0, 0, 0), report.healthSubjects)
    }

    // --- facts -----------------------------------------------------------------------------------

    /**
     * Facts merge only as INSERT, IDENTICAL or CONTENT_DIFFERS (inv. 126), plus the owner check:
     * a new row on a local asset or on one this plan inserts is an INSERT, a re-import is IDENTICAL,
     * the same id with other content is a conflict, and a row whose asset is nowhere is one too.
     */
    @Test
    fun factsInsertMatchOrDiffer() {
        val a2 = plainAssetOf("a2")
        val localActivation = activationOf("act-same")
        val localCondition = conditionOf("c-same")
        val merged = plan(
            data(
                assets = listOf(a1, a2),
                activations = listOf(
                    activationOf("act-new"),
                    activationOf("act-on-new-asset", assetId = "a2"),
                    localActivation,
                    activationOf("act-diff", action = SeasonAction.END),
                    activationOf("act-orphan", assetId = "a-gone"),
                ),
                conditions = listOf(
                    conditionOf("c-new"),
                    conditionOf("c-on-new-asset", assetId = "a2"),
                    localCondition,
                    conditionOf("c-diff", condition = OperationalCondition.DOWN),
                    conditionOf("c-orphan", assetId = "a-gone"),
                ),
            ),
            snapshot(
                assets = listOf(a1),
                activations = listOf(localActivation, activationOf("act-diff")),
                conditions = listOf(localCondition, conditionOf("c-diff")),
            ),
        )
        for ((table, prefix) in listOf(MergeTable.SEASON_ACTIVATIONS to "act", MergeTable.CONDITIONS to "c")) {
            assertEquals(MergeDecision(table, "$prefix-new", MergeVerdict.INSERT), merged.decision(table, "$prefix-new"))
            assertEquals(
                MergeDecision(table, "$prefix-on-new-asset", MergeVerdict.INSERT),
                merged.decision(table, "$prefix-on-new-asset"),
            )
            assertEquals(MergeDecision(table, "$prefix-same", MergeVerdict.IDENTICAL), merged.decision(table, "$prefix-same"))
            assertEquals(
                MergeDecision(table, "$prefix-diff", MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, "$prefix-diff"),
                merged.decision(table, "$prefix-diff"),
            )
            assertEquals(
                MergeDecision(table, "$prefix-orphan", MergeVerdict.CONFLICT, MergeReason.OWNER_NOT_AVAILABLE, "a-gone"),
                merged.decision(table, "$prefix-orphan"),
            )
        }
        // No other verdict is reachable for a fact.
        assertTrue(
            merged.decisions.filter { it.table == MergeTable.SEASON_ACTIVATIONS || it.table == MergeTable.CONDITIONS }
                .none { it.verdict == MergeVerdict.SKIPPED },
        )
    }

    /**
     * Soft links are never owners (inv. 109): facts naming an event that is nowhere, a subject naming
     * a profile that is nowhere, and an asset naming a primary subject that is nowhere all INSERT.
     */
    @Test
    fun aSoftLinkIsNeverAnOwner() {
        val merged = plan(
            data(
                assets = listOf(a1.copy(healthPrimarySubjectId = HealthSubjectId("h-nowhere"))),
                activations = listOf(activationOf("act-1", eventId = "e-nowhere")),
                conditions = listOf(conditionOf("c-1", eventId = "e-nowhere")),
                subjects = listOf(subjectOf("h1", baselineProfileId = "p-nowhere")),
            ),
            snapshot(),
        )
        assertTrue(merged.applicable, "unexpected conflicts: ${merged.conflicts}")
        assertEquals(4, merged.decisions.count { it.verdict == MergeVerdict.INSERT })
        assertEquals(1, merged.writes.seasonActivations.size)
        assertEquals(1, merged.writes.conditions.size)
        assertEquals(1, merged.writes.healthSubjects.size)
    }

    // --- the subject's second identity -----------------------------------------------------------

    /**
     * A schedule drives at most one non-archived subject (inv. 120). An incoming non-archived
     * subject on a schedule a local non-archived subject under **another** id already drives
     * **declines** — `SKIPPED`, naming the local row — so the archive still applies and the local
     * subject is left exactly as it was (D-18).
     */
    @Test
    fun aSubjectForAScheduleHeldLocallyIsSkipped() {
        val merged = plan(
            data(schedules = listOf(s1), subjects = listOf(subjectOf("h-in", scheduleId = "s1"))),
            snapshot(
                assets = listOf(a1), schedules = listOf(s1),
                subjects = listOf(subjectOf("h-local", scheduleId = "s1")),
            ),
        )
        assertEquals(
            MergeDecision(
                MergeTable.HEALTH_SUBJECTS, "h-in", MergeVerdict.SKIPPED,
                MergeReason.HEALTH_SUBJECT_SCHEDULE_HELD_BY_A_LOCAL_ROW, "h-local",
            ),
            merged.decision(MergeTable.HEALTH_SUBJECTS, "h-in"),
        )
        assertTrue(merged.applicable)
        assertEquals(emptyList(), merged.writes.healthSubjects)
        assertEquals(MergeTally(0, 0, 0, 1), merged.report().healthSubjects)
    }

    /**
     * The subject's own id (inv. 66: an import modifies no local row). A local subject re-imported
     * unchanged is IDENTICAL and writes nothing; the same id with other content — a new weight, or
     * the row archived since — is `CONTENT_DIFFERS`, never an INSERT that the apply's upsert would
     * turn into a silent UPDATE. The subject is the one new table written by upsert, so this is the
     * arm that stands between a re-import and an overwrite.
     */
    @Test
    fun aSubjectReimportIsIdenticalAndADivergedSubjectConflicts() {
        val local = subjectOf("h1", scheduleId = "s1")
        val base = snapshot(assets = listOf(a1), schedules = listOf(s1), subjects = listOf(local))

        val same = plan(data(schedules = listOf(s1), subjects = listOf(local)), base)
        assertEquals(
            MergeDecision(MergeTable.HEALTH_SUBJECTS, "h1", MergeVerdict.IDENTICAL),
            same.decision(MergeTable.HEALTH_SUBJECTS, "h1"),
        )
        assertTrue(same.applicable)
        assertEquals(emptyList(), same.writes.healthSubjects)

        for ((label, diverged) in listOf(
            "weight" to local.copy(weight = 7),
            "archived" to local.copy(archivedAt = 900L),
        )) {
            val merged = plan(data(schedules = listOf(s1), subjects = listOf(diverged)), base)
            assertEquals(
                MergeDecision(MergeTable.HEALTH_SUBJECTS, "h1", MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, "h1"),
                merged.decision(MergeTable.HEALTH_SUBJECTS, "h1"),
                label,
            )
            assertEquals(MergeWrites(), merged.writes, label)
        }
    }

    /** Two non-archived subjects of one archive on one schedule: the second is a CONFLICT. */
    @Test
    fun twoArchiveSubjectsForOneScheduleConflict() {
        val merged = plan(
            data(
                schedules = listOf(s1),
                subjects = listOf(subjectOf("h1", scheduleId = "s1"), subjectOf("h2", scheduleId = "s1")),
            ),
            snapshot(),
        )
        assertEquals(MergeVerdict.INSERT, merged.decision(MergeTable.HEALTH_SUBJECTS, "h1").verdict)
        assertEquals(
            MergeDecision(
                MergeTable.HEALTH_SUBJECTS, "h2", MergeVerdict.CONFLICT,
                MergeReason.HEALTH_SUBJECT_SCHEDULE_DUPLICATED_IN_ARCHIVE, "h1",
            ),
            merged.decision(MergeTable.HEALTH_SUBJECTS, "h2"),
        )
        assertFalse(merged.applicable)
        assertEquals(MergeWrites(), merged.writes)
    }

    /**
     * An **archived** subject claims no schedule, on either side: a local archived one does not
     * hold its schedule, an incoming archived one neither takes it nor is refused for it.
     */
    @Test
    fun anArchivedSubjectClaimsNoSchedule() {
        val localArchived = plan(
            data(schedules = listOf(s1), subjects = listOf(subjectOf("h-new", scheduleId = "s1"))),
            snapshot(
                assets = listOf(a1), schedules = listOf(s1),
                subjects = listOf(subjectOf("h-old", scheduleId = "s1", archivedAt = 900L)),
            ),
        )
        assertEquals(MergeVerdict.INSERT, localArchived.decision(MergeTable.HEALTH_SUBJECTS, "h-new").verdict)

        val bothIncoming = plan(
            data(
                schedules = listOf(s1),
                subjects = listOf(
                    subjectOf("h-a-archived", scheduleId = "s1", archivedAt = 900L),
                    subjectOf("h-b-live", scheduleId = "s1"),
                ),
            ),
            snapshot(),
        )
        assertTrue(bothIncoming.applicable, "unexpected conflicts: ${bothIncoming.conflicts}")
        assertEquals(2, bothIncoming.writes.healthSubjects.size)

        val incomingArchived = plan(
            data(schedules = listOf(s1), subjects = listOf(subjectOf("h-archived", scheduleId = "s1", archivedAt = 900L))),
            snapshot(
                assets = listOf(a1), schedules = listOf(s1),
                subjects = listOf(subjectOf("h-local", scheduleId = "s1")),
            ),
        )
        assertEquals(MergeVerdict.INSERT, incomingArchived.decision(MergeTable.HEALTH_SUBJECTS, "h-archived").verdict)
    }

    /** A subject's owners are its asset and, when it names one, its schedule. */
    @Test
    fun aSubjectOnAnAbsentScheduleIsOwnerNotAvailable() {
        val merged = plan(
            data(
                subjects = listOf(
                    subjectOf("h-no-schedule", scheduleId = "s-gone"),
                    subjectOf("h-no-asset", assetId = "a-gone"),
                ),
            ),
            snapshot(),
        )
        assertEquals(
            MergeDecision(
                MergeTable.HEALTH_SUBJECTS, "h-no-schedule", MergeVerdict.CONFLICT,
                MergeReason.OWNER_NOT_AVAILABLE, "s-gone",
            ),
            merged.decision(MergeTable.HEALTH_SUBJECTS, "h-no-schedule"),
        )
        assertEquals(
            MergeDecision(
                MergeTable.HEALTH_SUBJECTS, "h-no-asset", MergeVerdict.CONFLICT,
                MergeReason.OWNER_NOT_AVAILABLE, "a-gone",
            ),
            merged.decision(MergeTable.HEALTH_SUBJECTS, "h-no-asset"),
        )
    }

    // --- the asset row ---------------------------------------------------------------------------

    /**
     * Inv. 126: a fact write never touches the asset row. An install holding an archive's rows
     * records a START, an END and a condition — through [record], the fixture's write, which inserts
     * the fact and nothing else — and re-importing the original archive still plans the asset
     * IDENTICAL, with the new local facts simply not in the archive.
     */
    @Test
    fun recordingStartEndAndAConditionLeavesTheAssetIdentical() = runBlocking<Unit> {
        val manual = plainAssetOf("a1").copy(seasonMode = SeasonMode.MANUAL)
        val archive = data(assets = listOf(manual), activations = listOf(activationOf("act-0")))
        val assets = InMemoryAssetRepository().apply { upsert(manual) }
        val activations = InMemorySeasonActivationRepository().apply { insert(activationOf("act-0")) }
        val conditions = InMemoryConditionRepository()

        record(assets, activations, activationOf("act-start", action = SeasonAction.START, occurredOn = "2026-04-01"))
        record(assets, activations, activationOf("act-end", action = SeasonAction.END, occurredOn = "2026-10-01"))
        conditions.insert(conditionOf("c-down", condition = OperationalCondition.DOWN))

        val merged = plan(
            archive,
            snapshot(assets = assets.all(), activations = activations.all(), conditions = conditions.all()),
        )
        assertEquals(MergeDecision(MergeTable.ASSETS, "a1", MergeVerdict.IDENTICAL), merged.decision(MergeTable.ASSETS, "a1"))
        assertEquals(MergeVerdict.IDENTICAL, merged.decision(MergeTable.SEASON_ACTIVATIONS, "act-0").verdict)
        assertTrue(merged.applicable)
    }

    /** The fixture's activation write: the fact row, and nothing else. */
    private suspend fun record(assets: AssetRepository, activations: SeasonActivationRepository, row: SeasonActivation) {
        check(assets.get(row.assetId) != null) { "no asset ${row.assetId.value}" }
        activations.insert(row)
    }

    /**
     * Configuration, unlike a fact, *is* the asset row: a divergence in the mode, the break, the
     * aggregation or the primary subject is `CONTENT_DIFFERS` on `ASSETS`, with no new code.
     */
    @Test
    fun aModeBreakOrHealthPolicyDifferenceIsContentDiffersOnAssets() {
        for ((label, incoming) in listOf(
            "mode" to a1.copy(seasonMode = SeasonMode.MANUAL, seasonStartMmdd = null, seasonEndMmdd = null),
            "break" to a1.copy(blackoutStartMmdd = "02-01", blackoutEndMmdd = "02-10"),
            "no break" to a1.copy(blackoutStartMmdd = null, blackoutEndMmdd = null),
            "aggregation" to a1.copy(healthAggregation = HealthAggregation.AVERAGE),
            "primary" to a1.copy(healthPrimarySubjectId = HealthSubjectId("h9")),
        )) {
            val merged = plan(data(assets = listOf(incoming)), snapshot(assets = listOf(a1)))
            assertEquals(
                MergeDecision(MergeTable.ASSETS, "a1", MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, "a1"),
                merged.decision(MergeTable.ASSETS, "a1"),
                label,
            )
        }
    }

    // --- no partial merge ------------------------------------------------------------------------

    /** One conflict anywhere empties every write list, the three new ones included. */
    @Test
    fun oneConflictWritesNothing() {
        val merged = plan(
            data(
                assets = listOf(a1, plainAssetOf("a2")),
                schedules = listOf(s1),
                activations = listOf(activationOf("act-1", assetId = "a2")),
                conditions = listOf(conditionOf("c-1", assetId = "a2"), conditionOf("c-diff", reason = "other words")),
                subjects = listOf(subjectOf("h1", assetId = "a2")),
            ),
            snapshot(assets = listOf(a1), schedules = listOf(s1), conditions = listOf(conditionOf("c-diff"))),
        )
        assertEquals(1, merged.conflicts.size)
        assertEquals(MergeReason.CONTENT_DIFFERS, merged.conflicts.single().reason)
        assertEquals(MergeWrites(), merged.writes)
    }

    // --- the apply -------------------------------------------------------------------------------

    /**
     * The apply writes the three tables after `REFERENCES`, in `MergeTable` order, inside its one
     * transaction, and rebuilds every schedule **once**, after the last of them.
     */
    @Test
    fun applyWritesTheThreeTablesInOrderThenRebuildsOnce() = runBlocking<Unit> {
        val log = mutableListOf<String>()
        val assets = InMemoryAssetRepository()
        val groups = InMemoryGroupRepository()
        val tags = InMemoryTagRepository()
        val links = InMemoryLinkRepository()
        val definitions = InMemoryDefinitionRepository()
        val profiles = InMemoryProfileRepository()
        val events = InMemoryEventRepository()
        val attachments = InMemoryAttachmentRepository()
        val closures = InMemoryClosureRepository()
        val schedules = InMemoryScheduleRepository(closures)
        val references = LoggingReferences(InMemoryReferenceRepository(), log)
        val activations = LoggingActivations(InMemorySeasonActivationRepository(), log)
        val conditions = LoggingConditions(InMemoryConditionRepository(), log)
        val subjects = LoggingSubjects(InMemoryHealthSubjectRepository(), log)
        val storage = FakeAttachmentStorage()
        val uow = FakeUnitOfWork(
            assets, groups, tags, links, definitions, profiles, schedules, closures, events, attachments,
            references, activations, conditions, subjects,
        )
        val build = BuildBackupMergePlan(
            assets, groups, tags, links, definitions, profiles, schedules, closures, events, attachments,
            references, activations, conditions, subjects, storage, uow,
        )
        val apply = ApplyBackupMergePlan(
            assets, groups, tags, links, definitions, profiles, schedules, closures, events, attachments,
            references, activations, conditions, subjects, storage, uow,
            rebuildAll = { log += "rebuild" },
        )
        val bytes = archiveOf(
            data(
                schedules = listOf(s1),
                references = listOf(reference("r1"), reference("r2")),
                activations = listOf(activationOf("act-1"), activationOf("act-2", action = SeasonAction.END)),
                conditions = listOf(conditionOf("c-1"), conditionOf("c-2")),
                subjects = listOf(subjectOf("h1", scheduleId = "s1"), subjectOf("h2")),
            ),
        )

        val report = apply.run(build.run(bytes))

        assertEquals(
            listOf(
                "reference:r1", "reference:r2", "activation:act-1", "activation:act-2",
                "condition:c-1", "condition:c-2", "subject:h1", "subject:h2", "rebuild",
            ),
            log,
        )
        assertEquals(MergeTally(2, 0, 0, 0), report.healthSubjects)
        assertEquals(2, activations.all().size)
        assertEquals(1, uow.commits)
    }

    // --- recording doubles: the shared fakes, with each write logged ------------------------------

    private class LoggingReferences(
        private val inner: InMemoryReferenceRepository,
        private val log: MutableList<String>,
    ) : ReferenceRepository by inner, Rollbackable by inner, Witnessed by inner {
        override suspend fun upsert(reference: AssetReference) {
            log += "reference:${reference.id.value}"
            inner.upsert(reference)
        }
    }

    private class LoggingActivations(
        private val inner: InMemorySeasonActivationRepository,
        private val log: MutableList<String>,
    ) : SeasonActivationRepository by inner, Rollbackable by inner, Witnessed by inner {
        override suspend fun insert(row: SeasonActivation) {
            log += "activation:${row.id}"
            inner.insert(row)
        }
    }

    private class LoggingConditions(
        private val inner: InMemoryConditionRepository,
        private val log: MutableList<String>,
    ) : ConditionRepository by inner, Rollbackable by inner, Witnessed by inner {
        override suspend fun insert(row: AssetCondition) {
            log += "condition:${row.id}"
            inner.insert(row)
        }
    }

    private class LoggingSubjects(
        private val inner: InMemoryHealthSubjectRepository,
        private val log: MutableList<String>,
    ) : HealthSubjectRepository by inner, Rollbackable by inner, Witnessed by inner {
        override suspend fun upsert(subject: HealthSubject) {
            log += "subject:${subject.id.value}"
            inner.upsert(subject)
        }
    }
}
