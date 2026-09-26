package com.loosecannon.servicetag.core.testing

import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.usecase.ExportBackupSet
import com.loosecannon.servicetag.core.usecase.ImportBackupReplace

/**
 * #74 (B2) — one install for the backup use cases: every canonical store in memory, the category
 * catalog among them, one [FakeUnitOfWork] over all of them, and the export and the replace built
 * over them in `AppGraph`'s collaborator order. [rebuilds] counts the total recompute seam.
 */
class BackupInstall(setId: String = "set-install", now: Long = 1_758_900_000_000L) {
    val assets = InMemoryAssetRepository()
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
    val activations = InMemorySeasonActivationRepository()
    val conditions = InMemoryConditionRepository()
    val subjects = InMemoryHealthSubjectRepository()
    val categories = InMemoryCategoryRepository()
    val storage = FakeAttachmentStorage()
    val uow = FakeUnitOfWork(
        assets, groups, tags, links, definitions, profiles, schedules, closures, events, attachments,
        references, activations, conditions, subjects, categories,
    )

    var rebuilds = 0

    val export = ExportBackupSet(
        assets, groups, tags, links, definitions, profiles, schedules, closures, events, attachments,
        references, activations, conditions, subjects, categories,
        uow, IdGenerator { setId }, Clock { now }, appVersion = "1.4.1", schemaVersion = 9,
    )
    val replace = ImportBackupReplace(
        assets, groups, tags, links, definitions, profiles, schedules, closures, events, attachments,
        references, activations, conditions, subjects, categories, storage, uow,
        rebuildAll = { rebuilds += 1 },
    )
}
