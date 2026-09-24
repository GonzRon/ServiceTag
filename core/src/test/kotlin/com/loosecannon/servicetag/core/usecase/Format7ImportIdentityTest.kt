package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.backup.BackupCodec
import com.loosecannon.servicetag.core.backup.toDomain
import com.loosecannon.servicetag.core.merge.MergeReason
import com.loosecannon.servicetag.core.merge.MergeVerdict
import com.loosecannon.servicetag.core.merge.MergeWrites
import com.loosecannon.servicetag.core.model.HealthAggregation
import com.loosecannon.servicetag.core.model.HealthSubjectKind
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.testing.FakeAttachmentStorage
import com.loosecannon.servicetag.core.testing.FakeUnitOfWork
import com.loosecannon.servicetag.core.testing.GOLDEN_FORMAT_7
import com.loosecannon.servicetag.core.testing.GOLDEN_FORMAT_7_EXPECTED
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
import com.loosecannon.servicetag.core.testing.activationOf
import com.loosecannon.servicetag.core.testing.conditionOf
import com.loosecannon.servicetag.core.testing.dataTreeOf
import com.loosecannon.servicetag.core.testing.dayMillis
import com.loosecannon.servicetag.core.testing.plainAssetOf
import com.loosecannon.servicetag.core.testing.resourceBytes
import com.loosecannon.servicetag.core.testing.scheduleOf
import com.loosecannon.servicetag.core.testing.seasonalAssetOf
import com.loosecannon.servicetag.core.testing.subjectOf
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Test

/**
 * 1.4 (B03) — the upgrade identity (inv. 125) on **B01's committed golden format-7 archive**, and a
 * format-8 replace restore that keeps every new row.
 *
 * The first case is the sole JVM proof of inv. 125's last clause (controller ruling on I2): a
 * format-7 export taken before the upgrade, planned against the store the 7 → 8 migration makes of
 * the very same rows, is IDENTICAL table for table. The store is built from the archive's own rows
 * with the policies, offsets and modes the committed expected file names — written by hand from
 * spec §4.1, not from the code — the floor at each row's own `updatedAt`, and every stamp read off
 * the raw tree, so the decoder's mapping is compared with the spec's rather than with itself.
 */
class Format7ImportIdentityTest {

    /**
     * An asset store that clears the three 1.4 tables when the assets go, as Room's `ON DELETE
     * CASCADE` does. The shared fakes do not model that cascade; a restore over an install that
     * already holds facts needs it, and it is what "the wipe needs nothing new" rests on.
     */
    private class CascadingAssets(
        private val activations: InMemorySeasonActivationRepository,
        private val conditions: InMemoryConditionRepository,
        private val subjects: InMemoryHealthSubjectRepository,
    ) : InMemoryAssetRepository() {
        override suspend fun deleteAll() {
            super.deleteAll()
            activations.rows.clear()
            conditions.rows.clear()
            subjects.rows.clear()
        }
    }

    /** One install: every store, and the four backup use cases over them. */
    private class Install {
        val activations = InMemorySeasonActivationRepository()
        val conditions = InMemoryConditionRepository()
        val subjects = InMemoryHealthSubjectRepository()
        val assets = CascadingAssets(activations, conditions, subjects)
        val tags = InMemoryTagRepository()
        val links = InMemoryLinkRepository()
        val definitions = InMemoryDefinitionRepository()
        val profiles = InMemoryProfileRepository()
        val events = InMemoryEventRepository()
        val attachments = InMemoryAttachmentRepository()
        val groups = InMemoryGroupRepository()
        val closures = InMemoryClosureRepository()
        val schedules = InMemoryScheduleRepository(closures)
        val references = InMemoryReferenceRepository()
        val storage = FakeAttachmentStorage()
        val uow = FakeUnitOfWork(
            assets, groups, tags, links, definitions, profiles, schedules, closures, events,
            attachments, references, activations, conditions, subjects,
        )
        val export = ExportBackupSet(
            assets, groups, tags, links, definitions, profiles, schedules, closures, events,
            attachments, references, activations, conditions, subjects,
            uow, IdGenerator { "set-format-8" }, Clock { 1_758_700_000_000L },
            appVersion = "1.4.0", schemaVersion = 8,
        )
        val replace = ImportBackupReplace(
            assets, groups, tags, links, definitions, profiles, schedules, closures, events,
            attachments, references, activations, conditions, subjects, storage, uow, rebuildAll = { },
        )
        val build = BuildBackupMergePlan(
            assets, groups, tags, links, definitions, profiles, schedules, closures, events,
            attachments, references, activations, conditions, subjects, storage, uow,
        )

        fun everything(): List<Any> = runBlocking {
            listOf(
                assets.all().sortedBy { it.id.value },
                definitions.all().sortedBy { it.id.value },
                schedules.all().sortedBy { it.id.value },
                activations.all(),
                conditions.all(),
                subjects.all(),
            )
        }
    }

    // --- the upgrade identity --------------------------------------------------------------------

    @Test
    fun theGoldenFormat7ArchiveMergesIdentical() = runBlocking {
        val bytes = resourceBytes(GOLDEN_FORMAT_7)
        val expected = Json.parseToJsonElement(resourceBytes(GOLDEN_FORMAT_7_EXPECTED).decodeToString()).jsonObject
        val expectedAssets = expected.getValue("assets").jsonObject
        val expectedSchedules = expected.getValue("schedules").jsonObject
        // The stamps, off the raw format-7 tree rather than off anything the decoder produced.
        val raw = dataTreeOf(bytes)
        fun rawUpdatedAt(table: String, id: String): Long = raw.getValue(table).jsonArray
            .map { it.jsonObject }.single { it.getValue("id").jsonPrimitive.content == id }
            .getValue("updatedAt").jsonPrimitive.long

        val backup = BackupCodec.decode(bytes)
        assertEquals(7, backup.manifest.formatVersion)

        // The store the migration makes of the same rows (spec §8.2): CALENDAR iff both MM-DD, no
        // break, WORST, no primary; every schedule through §4.1; the floor at `updatedAt`, which
        // itself never moves.
        val install = Install()
        for (dto in backup.data.assets) {
            val mode = expectedAssets.getValue(dto.id).jsonObject.getValue("seasonMode").jsonPrimitive.content
            install.assets.upsert(
                dto.toDomain().copy(
                    seasonMode = SeasonMode.valueOf(mode), blackoutStartMmdd = null, blackoutEndMmdd = null,
                    healthAggregation = HealthAggregation.WORST, healthPrimarySubjectId = null,
                    updatedAt = rawUpdatedAt("assets", dto.id),
                ),
            )
        }
        backup.data.measurementDefinitions.forEach { install.definitions.upsert(it.toDomain()) }
        for (dto in backup.data.maintenanceSchedules) {
            val want = expectedSchedules.getValue(dto.id).jsonObject
            val stamp = rawUpdatedAt("maintenanceSchedules", dto.id)
            install.schedules.upsert(
                dto.toDomain().copy(
                    servicePolicy = ServicePolicy.valueOf(want.getValue("servicePolicy").jsonPrimitive.content),
                    policyOffsetDays = want.getValue("policyOffsetDays").let { if (it is JsonNull) null else it.jsonPrimitive.intOrNull },
                    updatedAt = stamp,
                    ruleChangedAt = stamp,
                ),
            )
        }
        assertEquals(2, install.assets.all().size)
        assertEquals(8, install.schedules.all().size)

        val plan = install.build.run(bytes)

        assertEquals(11, plan.decisions.size, "two assets, one meter, eight schedules")
        val notIdentical = plan.decisions.filter { it.verdict != MergeVerdict.IDENTICAL || it.reason != MergeReason.NONE }
        assertEquals(emptyList(), notIdentical)
        assertTrue(plan.applicable)
        assertEquals(MergeWrites(), plan.writes)
    }

    // --- a format-8 replace restore --------------------------------------------------------------

    /**
     * Hazard: a restore that loses 1.4's rows. An install exports its facts, subjects and a floor
     * that is not its edit stamp; a second install that already held other 1.4 rows under the same
     * ids replaces itself with the export — and afterwards holds exactly the first install's rows,
     * the floor included (B01 review, M6).
     */
    @Test
    fun aReplaceRestoreOfFormat8KeepsFactsAndSubjects() = runBlocking {
        val source = Install()
        source.assets.upsert(seasonalAssetOf())
        source.assets.upsert(plainAssetOf("a2").copy(seasonMode = SeasonMode.MANUAL))
        source.schedules.upsert(
            scheduleOf(
                id = "s1", assetId = "a1", timeInterval = 1, timeUnit = RecurrenceUnit.YEAR, anchorOn = "2026-01-01",
                servicePolicy = ServicePolicy.PRE_SERVICE, policyOffsetDays = -14,
                createdOn = "2026-01-01", updatedOn = "2026-02-01", ruleChangedOn = "2026-01-05",
            ),
        )
        source.activations.insert(activationOf("act-1", assetId = "a2", eventId = "e-9"))
        source.activations.insert(activationOf("act-2", assetId = "a2", action = SeasonAction.END, occurredOn = "2026-09-30"))
        source.conditions.insert(conditionOf("c-1"))
        source.conditions.insert(conditionOf("c-2", assetId = "a2", condition = OperationalCondition.DOWN, occurredTime = null))
        source.subjects.upsert(subjectOf("h1", scheduleId = "s1", kind = HealthSubjectKind.ASSET))
        source.subjects.upsert(subjectOf("h2", assetId = "a2", archivedAt = 800L))
        val bytes = source.export.run().data

        val target = Install()
        target.assets.upsert(plainAssetOf("a1"))
        target.activations.insert(activationOf("act-1", occurredOn = "2020-01-01"))
        target.conditions.insert(conditionOf("c-1", reason = "an older reading"))
        target.subjects.upsert(subjectOf("h-old"))

        val report = target.replace.run(bytes)

        assertEquals(8, report.formatVersion)
        assertEquals(source.everything(), target.everything())
        val floor = target.schedules.all().single()
        assertEquals(dayMillis("2026-01-05"), floor.ruleChangedAt)
        assertEquals(dayMillis("2026-02-01"), floor.updatedAt)
        assertEquals(2, target.subjects.all().size)
        assertEquals(1, target.uow.commits)
    }
}
