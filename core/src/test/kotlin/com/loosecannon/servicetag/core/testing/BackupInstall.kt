package com.loosecannon.servicetag.core.testing

import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.usecase.ApplyBackupMergePlan
import com.loosecannon.servicetag.core.usecase.BuildBackupMergePlan
import com.loosecannon.servicetag.core.usecase.ExportBackupSet
import com.loosecannon.servicetag.core.usecase.ImportBackupReplace

/**
 * #74 (B2) — one install for the backup use cases: every canonical store in memory, the category
 * catalog among them, one [FakeUnitOfWork] over all of them, and the export, the replace, the merge
 * plan and the merge apply built over them in `AppGraph`'s collaborator order. [rebuilds] counts the
 * total recompute seam, which the replace and the apply share.
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
    /** #79's case aggregate; the asset double's delete takes a case and its timeline, as the schema does. */
    val caseEntries = InMemoryServiceCaseEntryRepository()
    val serviceCases = InMemoryServiceCaseRepository(caseEntries).also { assets.cascadesTo(it::cascadeFromAsset) }
    /** #72's loans; the asset double's delete takes them, as the schema does. */
    val loans = InMemoryAssetLoanRepository().also { assets.cascadesTo(it::cascadeFromAsset) }
    /** #77's transfer records; no cascade — a record outlives its asset. */
    val transfers = InMemoryTransferRecordRepository()
    /** #86's successions; the asset double's delete takes a row naming it at either end, as the schema does. */
    val successions = InMemoryAssetSuccessionRepository().also { assets.cascadesTo(it::cascadeFromAsset) }
    /** #15's applicability; the asset double's delete takes it, as the schema's CASCADE does. */
    val assetSupplies = InMemoryAssetSupplyRepository().also { assets.cascadesTo(it::cascadeFromAsset) }
    /** #15's catalog; its wipe is refused while applicability remains, as the schema's RESTRICT is. */
    val supplyItems = InMemorySupplyItemRepository(assetSupplies)
    /** #47's installed components; the asset double's delete takes them and their entries, as the schema's CASCADE does. */
    val installedComponents = InMemoryInstalledComponentRepository(supplyItems).also { assets.cascadesTo(it::cascadeFromAsset) }
    val storage = FakeAttachmentStorage()
    val uow = FakeUnitOfWork(
        assets, groups, tags, links, definitions, profiles, schedules, closures, events, attachments,
        references, activations, conditions, subjects, categories, serviceCases, caseEntries, loans, transfers,
        successions, supplyItems, assetSupplies, installedComponents,
    )

    var rebuilds = 0

    val export = ExportBackupSet(
        assets, groups, tags, links, definitions, profiles, schedules, closures, events, attachments,
        references, activations, conditions, subjects, categories, serviceCases, caseEntries, loans, transfers,
        successions, supplyItems, assetSupplies, installedComponents,
        uow, IdGenerator { setId }, Clock { now }, appVersion = "1.4.1", schemaVersion = 9,
    )
    val replace = ImportBackupReplace(
        assets, groups, tags, links, definitions, profiles, schedules, closures, events, attachments,
        references, activations, conditions, subjects, categories, serviceCases, caseEntries, loans, transfers,
        successions, supplyItems, assetSupplies, installedComponents, storage, uow,
        rebuildAll = { rebuilds += 1 },
    )
    val build = BuildBackupMergePlan(
        assets, groups, tags, links, definitions, profiles, schedules, closures, events, attachments,
        references, activations, conditions, subjects, categories, serviceCases, caseEntries, loans, transfers,
        successions, supplyItems, assetSupplies, installedComponents, storage, uow,
    )
    val apply = ApplyBackupMergePlan(
        assets, groups, tags, links, definitions, profiles, schedules, closures, events, attachments,
        references, activations, conditions, subjects, categories, serviceCases, caseEntries, loans, transfers,
        successions, supplyItems, assetSupplies, installedComponents, storage, uow,
        rebuildAll = { rebuilds += 1 },
    )
}
