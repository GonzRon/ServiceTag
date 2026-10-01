package com.loosecannon.servicetag.core.transfer

import com.loosecannon.servicetag.core.testing.successionOf
import com.loosecannon.servicetag.core.testing.InMemoryTransferRecordRepository
import com.loosecannon.servicetag.core.journal.SeedTemplates
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetCategory
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetLoanId
import com.loosecannon.servicetag.core.model.AssetReference
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.model.AttachmentId
import com.loosecannon.servicetag.core.model.AttachmentKind
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.CaseCoverage
import com.loosecannon.servicetag.core.model.CaseType
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.DefinitionKind
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.EventProfile
import com.loosecannon.servicetag.core.model.EventSource
import com.loosecannon.servicetag.core.model.ExternalLink
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.HealthAggregation
import com.loosecannon.servicetag.core.model.HealthDriver
import com.loosecannon.servicetag.core.model.HealthSubjectId
import com.loosecannon.servicetag.core.model.HealthSubjectKind
import com.loosecannon.servicetag.core.model.LinkId
import com.loosecannon.servicetag.core.model.LinkKind
import com.loosecannon.servicetag.core.model.LoanReminderMode
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.PayloadFormat
import com.loosecannon.servicetag.core.model.ProfileField
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ReferenceId
import com.loosecannon.servicetag.core.model.ReferenceKind
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServiceCaseId
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.model.TagTarget
import com.loosecannon.servicetag.core.model.ValueType
import com.loosecannon.servicetag.core.nfc.TagPayload
import com.loosecannon.servicetag.core.ports.ByteSource
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.SupplyItemRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.references.LinkLaunchPolicy
import com.loosecannon.servicetag.core.testing.BackupInstall
import com.loosecannon.servicetag.core.testing.InMemoryScheduleStateRepository
import com.loosecannon.servicetag.core.testing.SAMPLE_LOOKUP_URI
import com.loosecannon.servicetag.core.testing.assetSupplyOf
import com.loosecannon.servicetag.core.testing.caseEntryOf
import com.loosecannon.servicetag.core.testing.caseOf
import com.loosecannon.servicetag.core.testing.completionOf
import com.loosecannon.servicetag.core.testing.conditionOf
import com.loosecannon.servicetag.core.testing.dayMillis
import com.loosecannon.servicetag.core.testing.groupOf
import com.loosecannon.servicetag.core.testing.loanOf
import com.loosecannon.servicetag.core.testing.measurementOf
import com.loosecannon.servicetag.core.testing.plainAssetOf
import com.loosecannon.servicetag.core.testing.scheduleOf
import com.loosecannon.servicetag.core.testing.subjectOf
import com.loosecannon.servicetag.core.testing.supplyItemOf
import com.loosecannon.servicetag.core.testing.transferOf
import com.loosecannon.servicetag.core.usecase.AcceptImpairmentOffer
import com.loosecannon.servicetag.core.usecase.AcceptOperationalOffer
import com.loosecannon.servicetag.core.usecase.AcceptSeasonOffer
import com.loosecannon.servicetag.core.usecase.ActivationCommand
import com.loosecannon.servicetag.core.usecase.AddAttachment
import com.loosecannon.servicetag.core.usecase.AddAttachmentCommand
import com.loosecannon.servicetag.core.usecase.AddAssetSupply
import com.loosecannon.servicetag.core.usecase.AddAssetSupplyCommand
import com.loosecannon.servicetag.core.usecase.AddReference
import com.loosecannon.servicetag.core.usecase.AddReferenceCommand
import com.loosecannon.servicetag.core.usecase.AddServiceCaseEntry
import com.loosecannon.servicetag.core.usecase.ApplyTemplate
import com.loosecannon.servicetag.core.usecase.ArchiveAsset
import com.loosecannon.servicetag.core.usecase.ArchiveDefinition
import com.loosecannon.servicetag.core.usecase.ArchiveGroup
import com.loosecannon.servicetag.core.usecase.ArchiveHealthSubject
import com.loosecannon.servicetag.core.usecase.ArchiveProfile
import com.loosecannon.servicetag.core.usecase.ArchiveSchedule
import com.loosecannon.servicetag.core.usecase.ArchiveSupplyItem
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.AssetSettingsCommand
import com.loosecannon.servicetag.core.usecase.AssetSupplyProblem
import com.loosecannon.servicetag.core.usecase.AssetSupplyResult
import com.loosecannon.servicetag.core.usecase.BindTag
import com.loosecannon.servicetag.core.usecase.BreakCommand
import com.loosecannon.servicetag.core.usecase.CaseEntryCommand
import com.loosecannon.servicetag.core.usecase.CloseRound
import com.loosecannon.servicetag.core.usecase.CompleteGroupMembers
import com.loosecannon.servicetag.core.usecase.CompleteSchedule
import com.loosecannon.servicetag.core.usecase.CompletionCommand
import com.loosecannon.servicetag.core.usecase.ConditionCommand
import com.loosecannon.servicetag.core.usecase.CreateAsset
import com.loosecannon.servicetag.core.usecase.DefinitionCommand
import com.loosecannon.servicetag.core.usecase.DeleteAsset
import com.loosecannon.servicetag.core.usecase.DeleteAttachment
import com.loosecannon.servicetag.core.usecase.DeleteDefinition
import com.loosecannon.servicetag.core.usecase.DeleteEvent
import com.loosecannon.servicetag.core.usecase.DeleteProfile
import com.loosecannon.servicetag.core.usecase.EventCommand
import com.loosecannon.servicetag.core.usecase.GroupCommand
import com.loosecannon.servicetag.core.usecase.GroupMemberInput
import com.loosecannon.servicetag.core.usecase.HealthPolicyCommand
import com.loosecannon.servicetag.core.usecase.HealthSubjectCommand
import com.loosecannon.servicetag.core.usecase.LendAsset
import com.loosecannon.servicetag.core.usecase.LoanTerms
import com.loosecannon.servicetag.core.usecase.LogEvent
import com.loosecannon.servicetag.core.usecase.OpenServiceCase
import com.loosecannon.servicetag.core.usecase.PauseSchedule
import com.loosecannon.servicetag.core.usecase.PostponeSchedule
import com.loosecannon.servicetag.core.usecase.ProfileCommand
import com.loosecannon.servicetag.core.usecase.PromoteCategory
import com.loosecannon.servicetag.core.usecase.ProvisionTag
import com.loosecannon.servicetag.core.usecase.RecomputeSchedules
import com.loosecannon.servicetag.core.usecase.RecordCondition
import com.loosecannon.servicetag.core.usecase.RecordConditionWithIncident
import com.loosecannon.servicetag.core.usecase.RecordSeasonActivation
import com.loosecannon.servicetag.core.usecase.RelinkLoanContact
import com.loosecannon.servicetag.core.usecase.RemoveAssetSupply
import com.loosecannon.servicetag.core.usecase.RemoveReference
import com.loosecannon.servicetag.core.usecase.RenameCategory
import com.loosecannon.servicetag.core.usecase.ReorderDefinitions
import com.loosecannon.servicetag.core.usecase.ReorderProfiles
import com.loosecannon.servicetag.core.usecase.RepairScheduleProviders
import com.loosecannon.servicetag.core.usecase.Resolution
import com.loosecannon.servicetag.core.usecase.ResolveTag
import com.loosecannon.servicetag.core.usecase.RetireAsset
import com.loosecannon.servicetag.core.usecase.ReturnLoan
import com.loosecannon.servicetag.core.usecase.SaveAssetSettings
import com.loosecannon.servicetag.core.usecase.SaveDefinition
import com.loosecannon.servicetag.core.usecase.SaveGroup
import com.loosecannon.servicetag.core.usecase.SaveHealthSubject
import com.loosecannon.servicetag.core.usecase.SaveProfile
import com.loosecannon.servicetag.core.usecase.SaveSchedule
import com.loosecannon.servicetag.core.usecase.SaveSupplyItem
import com.loosecannon.servicetag.core.usecase.ScheduleCommand
import com.loosecannon.servicetag.core.usecase.SeasonModeCommand
import com.loosecannon.servicetag.core.usecase.ServiceCaseCommand
import com.loosecannon.servicetag.core.usecase.SetHealthPolicy
import com.loosecannon.servicetag.core.usecase.SetMaintenanceBreak
import com.loosecannon.servicetag.core.usecase.SetSeasonMode
import com.loosecannon.servicetag.core.usecase.SetWarrantyReminder
import com.loosecannon.servicetag.core.usecase.SupplyItemCommand
import com.loosecannon.servicetag.core.usecase.UpdateAsset
import com.loosecannon.servicetag.core.usecase.UpdateAssetSupply
import com.loosecannon.servicetag.core.usecase.UpdateAssetSupplyCommand
import com.loosecannon.servicetag.core.usecase.UpdateAttachment
import com.loosecannon.servicetag.core.usecase.UpdateAttachmentCommand
import com.loosecannon.servicetag.core.usecase.UpdateEvent
import com.loosecannon.servicetag.core.usecase.UpdateLoan
import com.loosecannon.servicetag.core.usecase.UpdateReference
import com.loosecannon.servicetag.core.usecase.UpdateReferenceCommand
import com.loosecannon.servicetag.core.usecase.UpdateServiceCase
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * #77 (C12, R77-4; R77-B2b-GUARD) — the one write guard, driven through **every** writing use case against an asset
 * transferred out from this phone: each answers [AssetTransferredOut] and commits nothing. Its ruled exceptions pass:
 * `DeleteAsset` (the records survive it), the category-only rewrite, derived state, an unstamped scan, an ordinary
 * archived asset's unarchive, and the provider repair, which skips held rows rather than failing on them (RM-1).
 *
 * The estate, fictional throughout: "Example Water Heater" (h1) and its component "Example Anode Rod" (h2, MANUAL
 * season) are held — each has an open OUT — and still read ACTIVE (merged history, R77-17), so nothing here leans on
 * ARCHIVED. h1 has a meter reading definition d1 (measured by its completion e1) and an unused one d9, a quick action
 * p1 (reading d1) and an unused one p9, a monthly schedule s1, documents at1 (the asset's) and at2 (e1's), a link r1,
 * a DOWN condition, an AGE subject hs1, an open service case sc1 with an entry, an open loan l9 (merged history — a
 * transfer refuses one), a tag t1 and an Incident e3; h2 has a SEASON_START event e2. The group G1 holds both, with a
 * schedule sg; the group G3 holds h1 and the staying "Example Compressor" (x1), which has its own reading dx, quick
 * action px and schedule sx. A spare unbound tag t5 and an archived, **not** held "Sample Garage Door Opener" (o1)
 * complete it. Every row is laid down before the OUTs, as marking writes them.
 */
class HeldWriteGuardTest {

    private val install = BackupInstall()
    private val guard = HeldWriteGuard(
        install.transfers, install.events, install.definitions, install.profiles, install.groups, install.schedules,
        install.serviceCases, install.links,
    )

    // The seventeen guarded ports, as AppGraph hands them to every use case (#15's applicability the seventeenth).
    private val assets = guard.assets(install.assets)
    private val tags = guard.tags(install.tags)
    private val definitions = guard.definitions(install.definitions)
    private val profiles = guard.profiles(install.profiles)
    private val events = guard.events(install.events)
    private val groups = guard.groups(install.groups)
    private val schedules = guard.schedules(install.schedules)
    private val closures = guard.closures(install.closures)
    private val attachments = guard.attachments(install.attachments)
    private val references = guard.references(install.references)
    private val activations = guard.activations(install.activations)
    private val conditions = guard.conditions(install.conditions)
    private val subjects = guard.subjects(install.subjects)
    private val cases = guard.cases(install.serviceCases)
    private val entries = guard.entries(install.caseEntries)
    private val loans = guard.loans(install.loans)
    private val assetSupplies = guard.assetSupplies(install.assetSupplies)

    private val uow = install.uow
    private val states = InMemoryScheduleStateRepository()
    private var seq = 0
    private val ids = IdGenerator { "id-%03d".format(++seq) }
    private val clock = Clock { dayMillis("2026-09-24") }
    private val today = Today { LocalDate.parse("2026-09-24") }
    private val recompute = RecomputeSchedules(
        schedules, states, events, closures, groups, assets, activations, today, clock,
    ) { ZoneOffset.UTC }
    private val promote = PromoteCategory(install.categories)
    private val applyTemplate = ApplyTemplate(definitions, profiles, assets, uow, ids, clock)
    private val recordCondition = RecordCondition(assets, events, conditions, uow, ids, clock, today)
    private val recordActivation = RecordSeasonActivation(assets, events, activations, uow, ids, clock, today, recompute)

    private val heater = AssetId("h1")
    private val anode = AssetId("h2")
    private val compressor = AssetId("x1")

    private suspend fun seed() {
        install.assets.upsert(
            plainAssetOf("h1", "Example Water Heater").copy(category = "Appliance", warrantyExpiresOn = "2027-03-01"),
        )
        install.assets.upsert(
            plainAssetOf("h2", "Example Anode Rod").copy(parentAssetId = heater, seasonMode = SeasonMode.MANUAL),
        )
        install.assets.upsert(plainAssetOf("x1", "Example Compressor"))
        install.assets.upsert(plainAssetOf("o1", "Sample Garage Door Opener").copy(status = AssetStatus.ARCHIVED))
        install.categories.upsert(AssetCategory("appliance", "Appliance", 100L, 100L))
        listOf("d1" to "h1", "d9" to "h1", "dx" to "x1").forEach { (id, asset) -> install.definitions.upsert(definitionOf(id, asset)) }
        install.profiles.upsert(profileOf("p1", "h1", listOf(ProfileField("pf1", DefinitionId("d1"), required = true, sortOrder = 0))))
        install.profiles.upsert(profileOf("p9", "h1", emptyList(), sortOrder = 1))
        install.profiles.upsert(profileOf("px", "x1", emptyList()))
        install.groups.upsert(
            groupOf("G1", "Example Flush Round", members = listOf(Triple("h1", "2026-01-01", null), Triple("h2", "2026-01-01", null))),
        )
        install.groups.upsert(
            groupOf("G3", "Example Mixed Round", members = listOf(Triple("x1", "2026-01-01", null), Triple("h1", "2026-01-01", null))),
        )
        install.schedules.upsert(monthly("s1", assetId = "h1"))
        install.schedules.upsert(monthly("sg", groupId = "G1"))
        install.schedules.upsert(monthly("sx", assetId = "x1"))
        install.events.upsert(
            completionOf("e1", "2026-02-01", "2026-02-01", assetId = "h1", scheduleId = "s1", meter = "d1" to 5.0)
                .copy(profileId = ProfileId("p1")),
        )
        install.events.upsert(plainEventOf("e2", "h2", EventKind.SEASON_START))
        install.events.upsert(plainEventOf("e3", "h1", EventKind.INCIDENT))
        install.events.upsert(plainEventOf("ex", "x1", EventKind.NOTE))
        install.attachments.upsert(attachmentOf("at1", AttachmentOwner.OfAsset(heater)))
        install.attachments.upsert(attachmentOf("at2", AttachmentOwner.OfEvent(EventId("e1"))))
        install.references.upsert(
            AssetReference(
                id = ReferenceId("r1"), assetId = heater, kind = ReferenceKind.WEB_URL, uri = "https://example.com/heater",
                displayName = "Example heater page", description = "", scheme = "https", createdAt = 100L, updatedAt = 100L,
            ),
        )
        install.conditions.insert(conditionOf("co1", "h1", OperationalCondition.DOWN, occurredOn = "2026-09-01"))
        install.subjects.upsert(subjectOf("hs1", "h1"))
        install.serviceCases.upsert(caseOf("sc1", "h1", incident = null))
        install.caseEntries.insert(caseEntryOf("n1", caseId = "sc1"))
        install.loans.upsert(loanOf("l9", "h1"))
        install.tags.upsert(tagOf("t1", "123e4567-e89b-12d3-a456-426614174001", TagTarget.AssetTarget(heater)))
        install.tags.upsert(tagOf("t5", "123e4567-e89b-12d3-a456-426614174005", TagTarget.None))
        install.transfers.append(transferOf("out-h1", assetId = "h1"))
        install.transfers.append(transferOf("out-h2", assetId = "h2", nameSnapshot = "Example Anode Rod"))
        assertEquals(setOf(heater, anode), install.transfers.heldIds())
        assertEquals(0, uow.commits)
    }

    /** Each use case in [cases] answers [AssetTransferredOut] naming [held], and nothing commits. */
    private suspend fun refused(held: AssetId, vararg cases: Pair<String, suspend () -> Any?>) {
        for ((name, write) in cases) {
            val before = uow.commits
            val refusal = runCatching { write() }.exceptionOrNull()
            assertIs<AssetTransferredOut>(refusal, "$name: ${refusal ?: "it wrote"}")
            assertEquals(held, refusal.assetId, name)
            assertEquals(before, uow.commits, "$name committed nothing")
        }
        assertEquals(0, uow.commits)
    }

    // ---- every writing use case, by family ------------------------------------------------------------------------

    @Test
    fun theAssetUseCasesAreRefused() = runTest {
        seed()
        val update = UpdateAsset(assets, schedules, uow, clock, recompute, promote)
        val archive = ArchiveAsset(assets, uow, clock) { recompute.forAsset(it) }
        val retire = RetireAsset(assets, uow, clock) { recompute.forAsset(it) }
        val settings = SaveAssetSettings(
            assets, schedules, subjects, activations, uow, ids, clock, today, recompute, applyTemplate, promote,
        )
        val create = CreateAsset(assets, uow, ids, clock, applyTemplate, promote)
        refused(
            heater,
            "UpdateAsset" to { update.run(heater, AssetCommand(name = "Example Water Heater 2", category = "Appliance", warrantyExpiresOn = "2027-03-01")) },
            "ArchiveAsset" to { archive.run(heater) },
            "RetireAsset" to { retire.retire(heater, "2026-09-01") },
            "SaveAssetSettings" to {
                settings.run(
                    heater,
                    AssetSettingsCommand(
                        asset = AssetCommand(name = "Example Water Heater 2", category = "Appliance", warrantyExpiresOn = "2027-03-01"),
                        seasonMode = SeasonModeCommand(SeasonMode.YEAR_ROUND),
                        maintenanceBreak = BreakCommand(null, null),
                        healthPolicy = HealthPolicyCommand(HealthAggregation.WORST),
                    ),
                )
            },
            "SetWarrantyReminder" to { SetWarrantyReminder(assets, uow, clock).run(heater, com.loosecannon.servicetag.core.usecase.WarrantyReminderCommand(14)) },
            "SetSeasonMode" to {
                SetSeasonMode(assets, schedules, activations, uow, ids, clock, today, recompute)
                    .run(heater, SeasonModeCommand(SeasonMode.CALENDAR, "11-01", "03-31"))
            },
            "SetMaintenanceBreak" to {
                SetMaintenanceBreak(assets, schedules, uow, clock, recompute).run(heater, BreakCommand("01-10", "01-20"))
            },
            "SetHealthPolicy" to { SetHealthPolicy(assets, subjects, uow, clock).run(heater, HealthPolicyCommand(HealthAggregation.WEIGHTED)) },
            "CreateAsset (a held parent, rm-6)" to { create.run(AssetCommand(name = "Example Thermostat", parentAssetId = heater)) },
        )
        // The other two directions, on the held heater as marking leaves it: archived, and here retired too.
        install.assets.upsert(install.assets.get(heater)!!.copy(status = AssetStatus.ARCHIVED, retiredOn = "2026-09-01"))
        refused(
            heater,
            "ArchiveAsset.unarchive" to { archive.unarchive(heater) },
            "RetireAsset.unretire" to { retire.unretire(heater) },
        )
    }

    @Test
    fun theModelUseCasesAreRefused() = runTest {
        seed()
        val d9 = install.definitions.get(DefinitionId("d9"))!!
        refused(
            heater,
            "SaveDefinition" to {
                SaveDefinition(definitions, events, profiles, assets, uow, ids, clock).run(
                    d9.id,
                    DefinitionCommand(
                        assetId = heater, key = "", label = "Inlet temperature", unit = "C", kind = DefinitionKind.ENTERED,
                        valueType = ValueType.NUMBER, decimals = 1, rangeLow = null, rangeHigh = null, isMeter = false,
                        formula = null, sourceA = null, sourceB = null,
                    ),
                )
            },
            "ArchiveDefinition" to { ArchiveDefinition(definitions, uow, clock).run(d9.id, archived = true) },
            "DeleteDefinition" to { DeleteDefinition(definitions, events, profiles, uow).run(d9.id) },
            "ReorderDefinitions" to { ReorderDefinitions(definitions, uow, clock).run(heater, listOf(d9.id, DefinitionId("d1"))) },
            "SaveProfile" to {
                SaveProfile(profiles, definitions, assets, uow, ids, clock).run(
                    ProfileId("p9"),
                    ProfileCommand(heater, "Descale", EventKind.MAINTENANCE, "Descale", emptyList(), emptyList()),
                )
            },
            "ArchiveProfile" to { ArchiveProfile(profiles, uow, clock).run(ProfileId("p9"), archived = true) },
            "DeleteProfile" to { DeleteProfile(profiles, uow).run(ProfileId("p9")) },
            "ReorderProfiles" to { ReorderProfiles(profiles, uow, clock).run(heater, listOf(ProfileId("p9"), ProfileId("p1"))) },
        )
        // A template applies only to an asset with no model yet: the anode.
        refused(anode, "ApplyTemplate" to { applyTemplate.run(anode, SeedTemplates.byKey("generic")!!) })
    }

    @Test
    fun theJournalUseCasesAreRefused() = runTest {
        seed()
        val log = LogEvent(events, definitions, profiles, assets, uow, ids, clock, recompute)
        refused(
            heater,
            "LogEvent" to { log.run(note(heater)) },
            "UpdateEvent" to { UpdateEvent(events, definitions, profiles, uow, ids, clock, recompute).run(EventId("e3"), note(heater)) },
            "DeleteEvent" to { DeleteEvent(events, attachments, install.storage, uow, recompute).run(EventId("e3")) },
            "RecordCondition" to { recordCondition.run(heater, ConditionCommand(OperationalCondition.OPERATIONAL, tzId = "UTC")) },
            "RecordConditionWithIncident" to {
                RecordConditionWithIncident(
                    events, definitions, profiles, assets, uow, ids, clock, recompute, conditions, today, recordCondition,
                ).run(
                    heater, "c-held",
                    ConditionCommand(OperationalCondition.DOWN, occurredOn = "2026-09-24", tzId = "UTC", reason = "Leaking"),
                    note(heater).copy(kind = EventKind.INCIDENT, title = "Leaking"),
                )
            },
        )
        refused(anode, "RecordSeasonActivation" to { recordActivation.run(anode, ActivationCommand(SeasonAction.START)) })
    }

    @Test
    fun theDocumentUseCasesAreRefused() = runTest {
        seed()
        refused(
            heater,
            "AddAttachment" to {
                AddAttachment(attachments, assets, events, install.storage, uow, ids, clock).run(
                    AttachmentOwner.OfAsset(heater),
                    AddAttachmentCommand(displayName = "Example receipt.pdf", mimeType = "application/pdf"),
                    ByteSource { ByteArrayInputStream("Example receipt".toByteArray()) },
                ).also { check(it is com.loosecannon.servicetag.core.usecase.AttachmentResult.Ok) { "not added: $it" } }
            },
            "UpdateAttachment" to {
                UpdateAttachment(attachments, uow, clock).run(
                    AttachmentId("at1"), UpdateAttachmentCommand("Example manual.pdf", AttachmentKind.DOCUMENT, role = null),
                )
            },
            "DeleteAttachment" to { DeleteAttachment(attachments, install.storage, uow).run(AttachmentId("at2")) },
            "AddReference" to {
                AddReference(references, assets, LinkLaunchPolicy(), uow, ids, clock)
                    .run(heater, AddReferenceCommand("https://example.com/parts", "Example parts page"))
            },
            "UpdateReference" to {
                UpdateReference(references, uow, clock).run(ReferenceId("r1"), UpdateReferenceCommand("Example page", "", role = null))
            },
            "RemoveReference" to { RemoveReference(references, uow).run(ReferenceId("r1")) },
        )
    }

    @Test
    fun theMaintenanceUseCasesAreRefused() = runTest {
        seed()
        recompute.all()
        val save = SaveSchedule(schedules, assets, groups, definitions, profiles, uow, ids, clock, recompute, subjects)
        val archiveGroup = ArchiveGroup(groups, uow, clock)
        refused(
            heater,
            "SaveSchedule (create)" to { save.run(null, monthlyCommand(heater, null)) },
            "SaveSchedule (edit)" to { save.run(ScheduleId("s1"), monthlyCommand(heater, null).copy(title = "Flush and descale")) },
            "PauseSchedule" to { PauseSchedule(schedules, uow, recompute).run(ScheduleId("s1"), paused = true) },
            "PostponeSchedule" to { PostponeSchedule(schedules, uow, recompute).run(ScheduleId("s1"), "2026-12-01") },
            "ArchiveSchedule" to {
                ArchiveSchedule(schedules, uow, recompute, subjects, assets, clock).run(ScheduleId("s1"), archived = true)
            },
            "CompleteSchedule" to {
                CompleteSchedule(schedules, events, definitions, profiles, uow, ids, clock, recompute)
                    .run(ScheduleId("s1"), CompletionCommand(occurredOn = "2026-09-24", tzId = "UTC"))
            },
            "CompleteGroupMembers" to {
                CompleteGroupMembers(schedules, groups, events, closures, definitions, profiles, uow, ids, clock, recompute)
                    .run(ScheduleId("sg"), listOf(heater), CompletionCommand(occurredOn = "2026-09-24", tzId = "UTC"))
            },
            "CloseRound" to { CloseRound(schedules, closures, uow, ids, clock, today, recompute).run(ScheduleId("sg")) },
            "SaveGroup" to {
                SaveGroup(groups, assets, uow, ids, clock).run(
                    GroupId("G1"),
                    GroupCommand(
                        "Example Flush Round 2",
                        members = listOf(GroupMemberInput(heater, id = "G1-m1"), GroupMemberInput(anode, id = "G1-m2", sortOrder = 1)),
                    ),
                )
            },
            "ArchiveGroup" to { archiveGroup.run(GroupId("G1"), archived = true) },
        )
        install.groups.upsert(install.groups.get(GroupId("G1"))!!.copy(archivedAt = 5L))
        refused(heater, "ArchiveGroup (unarchive)" to { archiveGroup.run(GroupId("G1"), archived = false) })
    }

    /** rm-6: the three accepts an event offers write a condition or an activation of a held asset. */
    @Test
    fun theOfferAcceptorsAreRefused() = runTest {
        seed()
        val incident = install.events.get(EventId("e3"))!!
        refused(
            heater,
            "AcceptImpairmentOffer" to {
                AcceptImpairmentOffer(conditions, recordCondition, uow).run(heater, incident, OperationalCondition.DEGRADED)
            },
            "AcceptOperationalOffer" to {
                AcceptOperationalOffer(conditions, recordCondition, uow).run(heater, install.events.get(EventId("e1"))!!)
            },
        )
        refused(
            anode,
            "AcceptSeasonOffer" to {
                AcceptSeasonOffer(activations, recordActivation, uow, today).run(anode, install.events.get(EventId("e2"))!!, SeasonAction.START)
            },
        )
    }

    @Test
    fun theHealthUseCasesAreRefused() = runTest {
        seed()
        val save = SaveHealthSubject(subjects, assets, schedules, profiles, uow, ids, clock)
        val age = HealthSubjectCommand(
            name = "Example anode age", kind = HealthSubjectKind.PART, driver = HealthDriver.AGE,
            nominalUntilDays = 100, warningFromDays = 200, criticalFromDays = 300,
        )
        refused(
            heater,
            "SaveHealthSubject.create" to { save.create(heater, age) },
            "SaveHealthSubject.update" to { save.update(HealthSubjectId("hs1"), age) },
            "ArchiveHealthSubject" to {
                ArchiveHealthSubject(subjects, assets, schedules, uow, clock).run(HealthSubjectId("hs1"), archived = true)
            },
        )
    }

    @Test
    fun theCaseUseCasesAreRefused() = runTest {
        seed()
        val command = ServiceCaseCommand(
            title = "Example heater claim", type = CaseType.WARRANTY_SERVICE, openedOn = "2026-09-24",
            coverage = CaseCoverage.IN_WARRANTY, resolutionEventId = null,
        )
        refused(
            heater,
            "OpenServiceCase" to { OpenServiceCase(assets, events, cases, uow, ids, clock, today).run(heater, command, null) },
            "UpdateServiceCase" to { UpdateServiceCase(events, cases, uow, clock, today).run(ServiceCaseId("sc1"), command) },
            "AddServiceCaseEntry" to {
                AddServiceCaseEntry(cases, entries, uow, ids, clock, today).run(
                    ServiceCaseId("sc1"), CaseEntryCommand("2026-09-24", null, "UTC", "Courier booked", null),
                )
            },
        )
    }

    @Test
    fun theCustodyUseCasesAreRefused() = runTest {
        seed()
        val terms = LoanTerms(lentOn = "2026-09-24", dueOn = "2026-10-04", reminderMode = LoanReminderMode.ONCE)
        refused(anode, "LendAsset" to { LendAsset(assets, loans, uow, ids, clock, today).run(anode, "Sample Borrower", terms) })
        refused(
            heater,
            "UpdateLoan" to { UpdateLoan(loans, uow, clock, today).run(AssetLoanId("l9"), terms.copy(lentOn = "2026-09-20")) },
            "ReturnLoan" to { ReturnLoan(loans, uow, clock, today).run(AssetLoanId("l9"), "2026-09-24") },
            "RelinkLoanContact" to {
                RelinkLoanContact(loans, uow, clock).run(AssetLoanId("l9"), SAMPLE_LOOKUP_URI.replace("/7", "/8"), "Example Borrower")
            },
        )
    }

    @Test
    fun theTagUseCasesAreRefused() = runTest {
        seed()
        refused(
            heater,
            "BindTag (a spare tag to a held asset)" to {
                BindTag(tags, assets, uow, clock).run(PayloadFormat.V1, "123e4567-e89b-12d3-a456-426614174005", TagTarget.AssetTarget(heater))
            },
            "ProvisionTag" to { ProvisionTag(tags, assets, uow, ids, clock).begin(TagTarget.AssetTarget(heater), "lid") },
        )
    }

    // ---- the named cases ------------------------------------------------------------------------------------------

    /** MJ-3: a save that keeps only the staying member soft-removes the held one — a write on the held asset. */
    @Test
    fun aGroupSaveOmittingHeldMembersIsRefused() = runTest {
        seed()
        refused(
            heater,
            "SaveGroup (omitting h1)" to {
                SaveGroup(groups, assets, uow, ids, clock).run(GroupId("G3"), GroupCommand("Example Mixed Round", members = listOf(GroupMemberInput(compressor, id = "G3-m1"))))
            },
        )
        assertTrue(install.groups.get(GroupId("G3"))!!.members.all { it.removedAt == null }, "nothing was stamped")
    }

    /** A tag's current target counts: moving a held asset's tag onto a staying asset is a write on the held asset. */
    @Test
    fun rebindingATagWhoseCurrentTargetIsHeldIsRefused() = runTest {
        seed()
        refused(
            heater,
            "BindTag (t1 away from h1)" to {
                BindTag(tags, assets, uow, clock).run(PayloadFormat.V1, "123e4567-e89b-12d3-a456-426614174001", TagTarget.AssetTarget(compressor))
            },
        )
        assertEquals(TagTarget.AssetTarget(heater), install.tags.get(TagId("t1"))!!.target)
    }

    /**
     * The catalog command rewrites every asset of a category, held ones included: only the category and stamp move,
     * and only through its own port (mn-1), which `AppGraph` hands to `RenameCategory` alone.
     */
    @Test
    fun aCategoryOnlyRewritePasses() = runTest {
        seed()
        RenameCategory(install.categories, guard.catalogAssets(install.assets), uow, clock).run("appliance", "Appliances")
        assertEquals("Appliances", install.assets.get(heater)!!.category)
        assertEquals(1, uow.commits)
    }

    /**
     * mn-1 (fix round 1; R77-17 "every mutation answers 409"): the catalog exception is the catalog command's only.
     * An ordinary edit whose one change is the category is still a mutation of the held asset.
     */
    @Test
    fun aCategoryOnlyEditOfAHeldAssetIsRefused() = runTest {
        seed()
        val update = UpdateAsset(assets, schedules, uow, clock, recompute, promote)
        refused(
            heater,
            "UpdateAsset (the category alone)" to {
                update.run(heater, AssetCommand(name = "Example Water Heater", category = "Garage", warrantyExpiresOn = "2027-03-01"))
            },
        )
        assertEquals("Appliance", install.assets.get(heater)!!.category)
    }

    /** mn-1: archiving a held asset that marking already archived writes only a stamp — still a mutation, still refused. */
    @Test
    fun archivingAnAlreadyArchivedHeldAssetIsRefused() = runTest {
        seed()
        val archived = install.assets.get(heater)!!.copy(status = AssetStatus.ARCHIVED)
        install.assets.upsert(archived)
        refused(heater, "ArchiveAsset (already archived)" to { ArchiveAsset(assets, uow, clock) { recompute.forAsset(it) }.run(heater) })
        assertEquals(archived, install.assets.get(heater))
    }

    /** R77-4: DeleteAsset stays the explicit "forget this local history"; the transfer records outlive it. */
    @Test
    fun deleteAssetPassesAndKeepsRecords() = runTest {
        seed()
        DeleteAsset(assets, events, attachments, install.storage, uow, groups, schedules, closures).run(anode)
        assertNull(install.assets.get(anode))
        assertEquals(listOf("out-h2"), install.transfers.forAsset(anode).map { it.id })
        assertEquals(setOf(heater, anode), install.transfers.heldIds(), "still held: the records are the custody facts")
    }

    /** Derived state is rebuilt for a held asset like any other: schedule state is not one of the seventeen ports. */
    @Test
    fun derivedStateIsUnguarded() = runTest {
        seed()
        recompute.forAsset(heater)
        assertNotNull(states.get(ScheduleId("s1")), "the held schedule's state row was written")
    }

    /**
     * A scan of a held asset's tag resolves without stamping it and without a refusal. The resolver is handed an empty
     * record view (B4 made `transfers` required), so this case keeps proving the guard's backstop on the stamp; B4's
     * `ResolveTagTest` proves the resolver's own `TransferredOut`.
     */
    @Test
    fun aHeldTagScanIsNotStamped() = runTest {
        seed()
        val scan = ResolveTag(tags, assets, uow, clock, InMemoryTransferRecordRepository())
        val tagId = TagId("123e4567-e89b-12d3-a456-426614174001")
        val resolution = scan.run(TagPayload.V1(tagId))
        assertIs<Resolution.OpenAsset>(resolution)
        assertNull(install.tags.get(TagId("t1"))!!.lastScannedAt, "not stamped")
    }

    /**
     * mn-2 (fix round 1; R77-11): a 2.6 link tag whose tombstone link names a held asset scans as `PreSplitLink`,
     * unstamped and without a refusal — ownership through the guard's one rule, the tombstones untouched.
     */
    @Test
    fun aHeldAssetsPreSplitLinkTagScansUnstamped() = runTest {
        seed()
        install.links.upsert(
            ExternalLink(
                id = LinkId("L1"), assetId = heater, kind = LinkKind.WEB, label = "Example notes",
                uri = "https://example.com/notes", createdAt = 100L, updatedAt = 100L,
            ),
        )
        install.tags.upsert(tagOf("t4", "123e4567-e89b-12d3-a456-426614174004", TagTarget.LinkTarget(LinkId("L1"))))
        val scan = ResolveTag(tags, assets, uow, clock, InMemoryTransferRecordRepository())

        val resolution = scan.run(TagPayload.V1(TagId("123e4567-e89b-12d3-a456-426614174004")))

        assertIs<Resolution.PreSplitLink>(resolution)
        assertNull(install.tags.get(TagId("t4"))!!.lastScannedAt, "not stamped")
        assertEquals(heater, install.links.get(LinkId("L1"))!!.assetId, "the tombstone is untouched")
    }

    /** AC 11: an ordinary archive stays reversible — an archived asset that is not held unarchives as before. */
    @Test
    fun anOrdinaryArchivedAssetUnarchives() = runTest {
        seed()
        ArchiveAsset(assets, uow, clock) { recompute.forAsset(it) }.unarchive(AssetId("o1"))
        assertEquals(AssetStatus.ACTIVE, install.assets.get(AssetId("o1"))!!.status)
        assertEquals(1, uow.commits)
    }

    /** RM-1: a held, providerless ACTIVE schedule is skipped by the repair — never matched, never written, no refusal. */
    @Test
    fun theProviderRepairSkipsHeldSchedules() = runTest {
        seed()
        install.schedules.upsert(install.schedules.get(ScheduleId("s1"))!!.copy(providers = emptyList()))
        install.schedules.upsert(install.schedules.get(ScheduleId("sx"))!!.copy(providers = emptyList()))
        val repair = RepairScheduleProviders(schedules, assets, groups, install.transfers, uow, clock)

        assertEquals(listOf(ScheduleId("sx")), repair.plan().entries.map { it.scheduleId })
        assertEquals(listOf(ScheduleId("sx")), repair.apply().repaired)
        assertTrue(install.schedules.get(ScheduleId("s1"))!!.providers.isEmpty(), "the held row is untouched")
        assertEquals(1, uow.commits)
    }

    // ---- R77-B2b-GUARD: a staying row never gains a hard reference into a held graph ------------------------------

    private suspend fun written(name: String, write: suspend () -> Unit): Throwable? {
        val before = uow.commits
        val refusal = runCatching { uow.write { write() } }.exceptionOrNull()
        if (refusal != null) assertEquals(before, uow.commits, "$name committed nothing")
        return refusal
    }

    @Test
    fun aStayingRowGainingAReferenceIntoAHeldGraphIsRefused() = runTest {
        seed()
        val staying = install.events.get(EventId("ex"))!!
        val stayingSchedule = install.schedules.get(ScheduleId("sx"))!!
        val stayingProfile = install.profiles.get(ProfileId("px"))!!
        val cases = listOf<Pair<String, suspend () -> Unit>>(
            "an event naming a held schedule" to { events.upsert(staying.copy(scheduleId = ScheduleId("s1"), occurrenceOn = "2026-02-01")) },
            "an event naming a held asset's quick action" to { events.upsert(staying.copy(profileId = ProfileId("p1"))) },
            "an event measuring a held asset's reading" to { events.upsert(staying.copy(measurements = listOf(measurementOf("d1", 9.0)))) },
            "a subject naming a held schedule" to { subjects.upsert(subjectOf("hs-x", "x1", scheduleId = "s1")) },
            "an event naming a wholly held group's schedule" to { events.upsert(staying.copy(scheduleId = ScheduleId("sg"), occurrenceOn = "2026-02-01")) },
            // NOTE 2 (fix round 1): the schedule and quick-action ports carry references too.
            "a staying schedule metering a held reading" to {
                schedules.upsert(stayingSchedule.copy(meterDefinitionId = DefinitionId("d1"), meterInterval = 100.0, anchorMeter = 0.0))
            },
            "a staying schedule naming a held quick action" to { schedules.upsert(stayingSchedule.copy(profileId = ProfileId("p1"))) },
            "a staying quick action reading a held definition" to {
                profiles.upsert(stayingProfile.copy(fields = listOf(ProfileField("pfx", DefinitionId("d1"), required = false, sortOrder = 0))))
            },
        )
        for ((name, write) in cases) {
            val refusal = written(name, write)
            assertIs<AssetTransferredOut>(refusal, "$name: ${refusal ?: "it wrote"}")
            assertEquals(heater, refusal.assetId, name)
        }
        assertEquals(0, uow.commits)
        assertEquals(staying, install.events.get(EventId("ex")), "the staying event is unchanged")
        assertNull(install.subjects.get(HealthSubjectId("hs-x")))
        assertEquals(stayingSchedule, install.schedules.get(ScheduleId("sx")), "the staying schedule is unchanged")
        assertEquals(stayingProfile, install.profiles.get(ProfileId("px")), "the staying quick action is unchanged")
    }

    /**
     * NOTE 1 (fix round 1) — the ruling's "would **gain**": a staying event that already names a held schedule (merged
     * history, laid down below the guard) is edited through `UpdateEvent`, which always keeps its schedule link. The
     * edit gains nothing, so it passes; the reference it keeps is the export's to report (P77-58), not a new one.
     */
    @Test
    fun anEditKeepingAnEarlierReferencePasses() = runTest {
        seed()
        install.events.upsert(install.events.get(EventId("ex"))!!.copy(scheduleId = ScheduleId("s1"), occurrenceOn = "2026-02-01"))
        val edited = UpdateEvent(events, definitions, profiles, uow, ids, clock, recompute).run(
            EventId("ex"), note(compressor).copy(title = "Example edited note", occurredOn = "2026-09-20"),
        )
        assertEquals("Example edited note", install.events.get(EventId("ex"))!!.title)
        assertEquals(ScheduleId("s1"), edited.scheduleId, "the earlier reference is kept, not gained")
        assertEquals(1, uow.commits)
    }

    @Test
    fun aReferenceToAnUnheldRowPasses() = runTest {
        seed()
        val staying = install.events.get(EventId("ex"))!!
        assertNull(written("an event naming the staying schedule") {
            events.upsert(staying.copy(scheduleId = ScheduleId("sx"), occurrenceOn = "2026-02-01", profileId = ProfileId("px")))
        })
        assertNull(written("an event measuring the staying reading") {
            events.upsert(staying.copy(measurements = listOf(measurementOf("dx", 3.0))))
        })
        assertNull(written("a subject naming the staying schedule") { subjects.upsert(subjectOf("hs-x", "x1", scheduleId = "sx")) })
        assertEquals(3, uow.commits)
    }

    // ---- fixtures ---------------------------------------------------------------------------------------------------

    private fun definitionOf(id: String, assetId: String) = com.loosecannon.servicetag.core.model.MeasurementDefinition(
        id = DefinitionId(id), assetId = AssetId(assetId), key = "hours_$id", label = "Hours", unit = "h",
        valueType = ValueType.NUMBER, decimals = 1, rangeLow = null, rangeHigh = null, isMeter = true,
        sortOrder = 0, archivedAt = null, createdAt = 100L, updatedAt = 100L,
    )

    private fun profileOf(id: String, assetId: String, fields: List<ProfileField>, sortOrder: Int = 0) = EventProfile(
        id = ProfileId(id), assetId = AssetId(assetId), name = "Flush $id", eventKind = EventKind.MAINTENANCE,
        defaultTitle = "Flush", templateKey = null, sortOrder = sortOrder, archivedAt = null, createdAt = 100L,
        updatedAt = 100L, fields = fields, consumables = emptyList(),
    )

    private fun monthly(id: String, assetId: String? = null, groupId: String? = null) = scheduleOf(
        id = id, assetId = assetId, groupId = groupId, timeInterval = 1, timeUnit = RecurrenceUnit.MONTH, anchorOn = "2026-01-01",
    )

    private fun monthlyCommand(assetId: AssetId?, groupId: GroupId?) = ScheduleCommand(
        targetAssetId = assetId, targetGroupId = groupId, title = "Flush the tank", timeInterval = 1,
        timeUnit = RecurrenceUnit.MONTH, anchorOn = "2026-01-01",
    )

    private fun plainEventOf(id: String, assetId: String, kind: EventKind) = AssetEvent(
        id = EventId(id), assetId = AssetId(assetId), kind = kind, title = "Example $id", profileId = null,
        occurredOn = "2026-09-20", occurredTime = null, tzId = "UTC", notes = "", source = EventSource.MANUAL,
        sourceRef = null, createdAt = 100L, updatedAt = 100L, measurements = emptyList(), consumables = emptyList(),
    )

    private fun note(assetId: AssetId) = EventCommand(
        assetId = assetId, profileId = null, kind = EventKind.NOTE, title = "Example note", occurredOn = "2026-09-24",
        occurredTime = null, tzId = "UTC", notes = "", values = emptyMap(), consumables = emptyList(),
    )

    private fun attachmentOf(id: String, owner: AttachmentOwner) = Attachment(
        id = AttachmentId(id), owner = owner, kind = AttachmentKind.DOCUMENT, displayName = "$id file",
        mimeType = "application/pdf", sizeBytes = 4L, sha256 = "ab".repeat(32), storageLocator = "assets/h1/$id.pdf",
        capturedOn = null, createdAt = 100L, updatedAt = 100L,
    )

    private fun tagOf(id: String, key: String, target: TagTarget) = com.loosecannon.servicetag.core.model.TagBinding(
        id = TagId(id), payloadFormat = PayloadFormat.V1, payloadKey = key, target = target,
        status = if (target == TagTarget.None) com.loosecannon.servicetag.core.model.TagStatus.UNBOUND else com.loosecannon.servicetag.core.model.TagStatus.ACTIVE,
        createdAt = 100L, updatedAt = 100L,
    )

    /**
     * #86 (C6, I8; R86-16): no **new** succession may name a held asset, at either end — refused before the port
     * writes, and nothing commits. A row naming neither writes as before.
     */
    @Test
    fun aSuccessionNamingAHeldAssetIsRefused() = runTest {
        seed()
        val successions = guard.successions(install.successions)

        refused(
            heater,
            "a held predecessor" to { uow.write { successions.append(successionOf("s1", predecessor = "h1", successor = "x1")) } },
        )
        refused(
            anode,
            "a held successor" to { uow.write { successions.append(successionOf("s2", predecessor = "o1", successor = "h2")) } },
        )
        assertEquals(emptyList(), install.successions.all())

        uow.write { successions.append(successionOf("s3", predecessor = "o1", successor = "x1")) }
        assertEquals(listOf("s3"), install.successions.all().map { it.id })
    }

    // ---- #15 (C14, row 30): applicability is the asset's; the SupplyItem catalog is global ---------------------------

    /** One SupplyItem (fictional), taken by the held heater (as1) and by the staying compressor (asx), laid down raw. */
    private suspend fun seedSupplies() {
        install.supplyItems.upsert(supplyItemOf("s1", "Example Anode Kit"))
        install.assetSupplies.insert(assetSupplyOf("as1", "h1", "s1", "Anode kit"))
        install.assetSupplies.insert(assetSupplyOf("asx", "x1", "s1", "Spare kit"))
    }

    /** A new row on a held asset, a re-role of its row, and a staying row moved onto it: each a write on it. */
    @Test
    fun addingApplicabilityToAHeldAssetThrows() = runTest {
        seed()
        seedSupplies()
        val held = install.assetSupplies.get("as1")!!
        val staying = install.assetSupplies.get("asx")!!

        refused(
            heater,
            "insert on h1" to { uow.write { assetSupplies.insert(assetSupplyOf("as2", "h1", "s1", "Spare kit")) } },
            "re-role h1's row" to { uow.write { assetSupplies.update(held.copy(role = "Other kit", updatedAt = 3_000L)) } },
            "move x1's row onto h1" to { uow.write { assetSupplies.update(staying.copy(assetId = heater)) } },
        )
        refused(anode, "insert on h2, a child asset" to { uow.write { assetSupplies.insert(assetSupplyOf("as3", "h2", "s1")) } })
        assertEquals(listOf(held, staying), install.assetSupplies.all(), "nothing was written")

        uow.write { assetSupplies.insert(assetSupplyOf("as4", "x1", "s1", "Other kit")) }
        assertEquals(listOf("as1", "as4", "asx"), install.assetSupplies.all().map { it.id }, "a staying asset's row writes as before")
    }

    /**
     * Removing a held asset's row, and moving it off onto a staying asset: the stored row's asset counts as well as the
     * row written (B1's review — `update` writes the whole row, so a row moved off a held asset is a write on it).
     */
    @Test
    fun removingAHeldAssetsApplicabilityThrows() = runTest {
        seed()
        seedSupplies()
        val held = install.assetSupplies.get("as1")!!

        refused(
            heater,
            "delete h1's row" to { uow.write { assetSupplies.delete("as1") } },
            "move h1's row onto x1" to { uow.write { assetSupplies.update(held.copy(assetId = compressor, updatedAt = 3_000L)) } },
        )
        assertEquals(held, install.assetSupplies.get("as1"), "the held row is as it was")

        uow.write { assetSupplies.delete("asx") }
        assertEquals(listOf("as1"), install.assetSupplies.all().map { it.id }, "a staying asset's row deletes as before")
    }

    /**
     * The catalog is global, not asset-owned: the guard offers no SupplyItem port, and archiving, unarchiving or editing
     * an item a held asset's row names writes no held row, so it passes — through the unwrapped port `AppGraph` hands out.
     */
    @Test
    fun archivingAnItemAHeldAssetUsesIsAllowed() = runTest {
        seed()
        seedSupplies()
        val held = install.assetSupplies.get("as1")!!
        assertTrue(
            HeldWriteGuard::class.java.declaredMethods.none { m -> m.parameterTypes.any { it == SupplyItemRepository::class.java } },
            "no SupplyItem port is wrapped (C14)",
        )

        uow.write { install.supplyItems.setArchived(SupplyId("s1"), archivedAt = 3_000L, updatedAt = 3_000L) }
        assertEquals(3_000L, install.supplyItems.get(SupplyId("s1"))!!.archivedAt)
        uow.write { install.supplyItems.upsert(install.supplyItems.get(SupplyId("s1"))!!.copy(name = "Example Anode Kit, long", archivedAt = null)) }

        assertEquals("Example Anode Kit, long", install.supplyItems.get(SupplyId("s1"))!!.name)
        assertEquals(held, install.assetSupplies.get("as1"), "the held asset's row is untouched")
        assertEquals(setOf(heater, anode), install.transfers.heldIds(), "still held")
    }

    /**
     * B3 (C17 over C14): the applicability use cases on the guarded port. Each answers its own checks first and the
     * write then meets the guard, so a held asset's add, re-role and remove throw and commit nothing — a child asset's
     * add included. A re-role to the role the row already holds is `Unchanged` and writes nothing, so it never reaches
     * the guard (the `UpdateReference` precedent). The catalog's own use cases take the unwrapped port and pass.
     */
    @Test
    fun theSupplyUseCasesMeetTheGuardOnlyAtAHeldAssetsWrite() = runTest {
        seed()
        seedSupplies()
        val add = AddAssetSupply(assets, install.supplyItems, assetSupplies, uow, ids, clock)
        val update = UpdateAssetSupply(assetSupplies, uow, clock)
        val remove = RemoveAssetSupply(assetSupplies, uow)
        val held = install.assetSupplies.get("as1")!!

        refused(
            heater,
            "AddAssetSupply on h1" to { add.run(AddAssetSupplyCommand(heater, SupplyId("s1"), "Spare kit")) },
            "UpdateAssetSupply re-roles h1's row" to { update.run("as1", UpdateAssetSupplyCommand("Other kit")) },
            "RemoveAssetSupply on h1's row" to { remove.run("as1") },
        )
        refused(anode, "AddAssetSupply on h2, a child asset" to { add.run(AddAssetSupplyCommand(anode, SupplyId("s1"), "Anode kit")) })
        assertEquals(
            AssetSupplyResult.Refused(AssetSupplyProblem.Unchanged),
            update.run("as1", UpdateAssetSupplyCommand(" Anode  kit ")),
            "a no-op re-role on a held asset is Unchanged, never the guard's refusal",
        )
        assertEquals(0, uow.commits)
        assertEquals(held, install.assetSupplies.get("as1"), "the held row is as it was")

        assertIs<AssetSupplyResult.Ok>(add.run(AddAssetSupplyCommand(compressor, SupplyId("s1"), "Other kit")), "a staying asset adds")
        val save = SaveSupplyItem(install.supplyItems, uow, ids, clock)
        val renamed = save.run(
            SupplyId("s1"),
            SupplyItemCommand("Example Anode Kit, long", "Filter", "Example Filters Co.", "PF-10", "EF-PF10-5", "ea", "", emptyList()),
        )
        assertEquals("Example Anode Kit, long", renamed.item.name)
        ArchiveSupplyItem(install.supplyItems, uow, clock).run(SupplyId("s1"), archived = true)
        assertNotNull(install.supplyItems.get(SupplyId("s1"))!!.archivedAt, "the item a held row names archives")
        assertEquals(held, install.assetSupplies.get("as1"), "and the held row is still as it was")
        assertEquals(setOf(heater, anode), install.transfers.heldIds(), "still held")
    }
}
