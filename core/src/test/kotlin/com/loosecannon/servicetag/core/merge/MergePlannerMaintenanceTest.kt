package com.loosecannon.servicetag.core.merge

import com.loosecannon.servicetag.core.backup.Backup
import com.loosecannon.servicetag.core.backup.BackupData
import com.loosecannon.servicetag.core.backup.BackupManifest
import com.loosecannon.servicetag.core.backup.MaintenanceScheduleDto
import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.model.AttachmentId
import com.loosecannon.servicetag.core.model.AttachmentKind
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.EventProfile
import com.loosecannon.servicetag.core.model.EventSource
import com.loosecannon.servicetag.core.model.ExternalLink
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.GroupMember
import com.loosecannon.servicetag.core.model.LinkId
import com.loosecannon.servicetag.core.model.LinkKind
import com.loosecannon.servicetag.core.model.MaintenanceGroup
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.MeasurementDefinition
import com.loosecannon.servicetag.core.model.OccurrenceClosure
import com.loosecannon.servicetag.core.model.PayloadFormat
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleProviderRow
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.SeasonBehavior
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.model.TagTarget
import com.loosecannon.servicetag.core.model.TimeBasis
import com.loosecannon.servicetag.core.model.ValueType
import com.loosecannon.servicetag.core.ports.StoredBytes
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The merge planner's 1.2 half: `GROUPS`, `SCHEDULES` and `CLOSURES`, the event table's new
 * occurrence index, and what the report says about all ten tables. Same shape as
 * `MergePlannerTest`: one archive and one snapshot in, one plan out, nothing written.
 *
 * Three cases are **archive-internal planner guards** and say so in their own KDoc: the two
 * membership codes and the provider-pair collision cannot be reached by two phones disagreeing,
 * because their unique keys all contain their parent's own row id, so a collision with a
 * destination row implies a collision on that id — which an earlier arm answers. They are reachable
 * from an archive that disagrees with itself, which is what they are constructed from here, and the
 * same case also pins what two genuinely divergent phones *do* produce.
 *
 * The end-to-end half — real bytes, real repositories, one transaction, the total rebuild — is
 * `ImportBackupMergeTest`.
 */
class MergePlannerMaintenanceTest {

    // --- fixtures ---------------------------------------------------------------------------

    private fun asset(id: String, name: String = "Orchard Pump $id") =
        Asset(id = AssetId(id), name = name, createdAt = 1L, updatedAt = 2L)

    private fun definition(id: String, assetId: String, isMeter: Boolean = true) =
        MeasurementDefinition(
            id = DefinitionId(id), assetId = AssetId(assetId), key = "key_$id",
            label = "Label $id", unit = "h", valueType = ValueType.NUMBER, decimals = 1,
            rangeLow = null, rangeHigh = null, isMeter = isMeter, sortOrder = 0,
            archivedAt = null, createdAt = 1L, updatedAt = 2L,
        )

    private fun profile(id: String, assetId: String) = EventProfile(
        id = ProfileId(id), assetId = AssetId(assetId), name = "Service $id",
        eventKind = EventKind.MAINTENANCE, defaultTitle = "Service", templateKey = null,
        sortOrder = 0, archivedAt = null, createdAt = 1L, updatedAt = 2L,
        fields = emptyList(), consumables = emptyList(),
    )

    private fun member(
        id: String,
        assetId: String,
        sortOrder: Int = 0,
        addedAt: Long = 1_000L,
        removedAt: Long? = null,
    ) = GroupMember(
        id = id, assetId = AssetId(assetId), sortOrder = sortOrder,
        addedAt = addedAt, removedAt = removedAt,
    )

    private fun group(
        id: String,
        name: String = "Aviary Feeders",
        members: List<GroupMember> = emptyList(),
        updatedAt: Long = 20L,
    ) = MaintenanceGroup(
        id = GroupId(id), name = name, description = "north run", archivedAt = null,
        createdAt = 10L, updatedAt = updatedAt, members = members,
    )

    private fun schedule(
        id: String,
        assetId: String? = null,
        groupId: String? = null,
        title: String = "Filter change",
        meterDefinitionId: String? = null,
        profileId: String? = null,
        postponedDueOn: String? = null,
        updatedAt: Long = 40L,
        providers: List<ScheduleProviderRow> = listOf(ScheduleProviderRow("LOCAL", enabled = true)),
    ) = MaintenanceSchedule(
        id = ScheduleId(id),
        target = assetId?.let { ScheduleTarget.AssetTarget(AssetId(it)) }
            ?: ScheduleTarget.GroupTarget(GroupId(groupId!!)),
        title = title, description = "", timeInterval = 3, timeUnit = RecurrenceUnit.MONTH,
        timeBasis = TimeBasis.COMPLETION, anchorOn = "2026-03-01", leadDays = 7,
        meterDefinitionId = meterDefinitionId?.let(::DefinitionId), meterInterval = null,
        anchorMeter = null, meterLead = null, seasonBehavior = SeasonBehavior.IGNORE,
        seasonReentry = null, seasonReentryOffsetDays = null,
        completionMode = CompletionMode.QUICK, profileId = profileId?.let(::ProfileId),
        remindersEnabled = true, status = ScheduleStatus.ACTIVE, postponedDueOn = postponedDueOn,
        createdAt = 30L, updatedAt = updatedAt, providers = providers,
    )

    private fun closure(
        id: String,
        scheduleId: String,
        occurrenceOn: String = "2026-05-04",
        closedOn: String = "2026-05-06",
        createdAt: Long = 70L,
    ) = OccurrenceClosure(
        id = id, scheduleId = ScheduleId(scheduleId), occurrenceOn = occurrenceOn,
        closedOn = closedOn, createdAt = createdAt,
    )

    private fun event(
        id: String,
        assetId: String,
        scheduleId: String? = null,
        occurrenceOn: String? = null,
        title: String = "Filter change",
        updatedAt: Long = 8L,
    ) = AssetEvent(
        id = EventId(id), assetId = AssetId(assetId), kind = EventKind.MAINTENANCE, title = title,
        profileId = null, occurredOn = "2026-04-02", occurredTime = null, tzId = "UTC", notes = "",
        source = if (scheduleId == null) EventSource.MANUAL else EventSource.SCHEDULE_QUICK_COMPLETE,
        sourceRef = null, createdAt = 7L, updatedAt = updatedAt,
        measurements = emptyList(), consumables = emptyList(),
        scheduleId = scheduleId?.let(::ScheduleId), occurrenceOn = occurrenceOn,
        detailsPending = false,
    )

    private fun tag(id: String, assetId: String, label: String? = null) = TagBinding(
        id = TagId(id), payloadFormat = PayloadFormat.V1, payloadKey = "key-$id",
        target = TagTarget.AssetTarget(AssetId(assetId)), label = label,
        physicalUid = null, createdAt = 3L, updatedAt = 4L,
    )

    private fun link(id: String, assetId: String, label: String = "note $id") = ExternalLink(
        id = LinkId(id), assetId = AssetId(assetId), kind = LinkKind.JOPLIN, label = label,
        uri = "joplin://$id", createdAt = 5L, updatedAt = 6L,
    )

    private val bytesSha = "9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a"

    private fun attachment(id: String, assetId: String, notes: String = "") = Attachment(
        id = AttachmentId(id), owner = AttachmentOwner.OfAsset(AssetId(assetId)),
        kind = AttachmentKind.DOCUMENT, displayName = "Manual.pdf", mimeType = "application/pdf",
        sizeBytes = 4L, sha256 = bytesSha, storageLocator = "assets/$assetId/$id.pdf",
        capturedOn = null, notes = notes, createdAt = 9L, updatedAt = 10L,
    )

    private fun backupOf(
        assets: List<Asset> = emptyList(),
        groups: List<MaintenanceGroup> = emptyList(),
        definitions: List<MeasurementDefinition> = emptyList(),
        profiles: List<EventProfile> = emptyList(),
        schedules: List<MaintenanceSchedule> = emptyList(),
        scheduleDtos: List<MaintenanceScheduleDto> = schedules.map { it.toDto() },
        closures: List<OccurrenceClosure> = emptyList(),
        links: List<ExternalLink> = emptyList(),
        tags: List<TagBinding> = emptyList(),
        events: List<AssetEvent> = emptyList(),
        attachments: List<Attachment> = emptyList(),
    ) = Backup(
        manifest = BackupManifest(
            formatVersion = 6, appVersion = "1.2.0", schemaVersion = 6, createdAt = 1L,
            counts = emptyMap(), dataSha256 = "a".repeat(64), backupSetId = "set-incoming",
        ),
        data = BackupData(
            assets = assets.map { it.toDto() },
            nfcTags = tags.map { it.toDto() },
            externalLinks = links.map { it.toDto() },
            measurementDefinitions = definitions.map { it.toDto() },
            eventProfiles = profiles.map { it.toDto() },
            assetEvents = events.map { it.toDto() },
            attachments = attachments.map { it.toDto() },
            maintenanceGroups = groups.map { it.toDto() },
            maintenanceSchedules = scheduleDtos,
            occurrenceClosures = closures.map { it.toDto() },
        ),
    )

    private fun snapshotOf(
        assets: List<Asset> = emptyList(),
        groups: List<MaintenanceGroup> = emptyList(),
        definitions: List<MeasurementDefinition> = emptyList(),
        profiles: List<EventProfile> = emptyList(),
        schedules: List<MaintenanceSchedule> = emptyList(),
        closures: List<OccurrenceClosure> = emptyList(),
        links: List<ExternalLink> = emptyList(),
        tags: List<TagBinding> = emptyList(),
        events: List<AssetEvent> = emptyList(),
        attachments: List<Attachment> = emptyList(),
        storedBytes: Map<String, StoredBytes> = emptyMap(),
    ) = MergeSnapshot(
        assets = assets,
        groups = groups,
        tags = tags,
        links = links,
        definitions = definitions,
        profiles = profiles,
        schedules = schedules,
        closures = closures,
        events = events,
        attachments = attachments,
        storedBytes = storedBytes,
        attachmentStoreConfigured = true,
    )

    private fun MergePlan.decision(table: MergeTable, id: String): MergeDecision =
        decisions.single { it.table == table && it.id == id }

    private fun MergePlan.reasons(table: MergeTable): List<MergeReason> =
        decisions.filter { it.table == table }.map { it.reason }

    // --- write order ------------------------------------------------------------------------

    /**
     * Hazard: merge order breaks a reference. The ten members are asserted **as a list**, and a
     * plan over an archive whose group, schedule, closure and event all arrive together writes them
     * in that order.
     *
     * A member appended at the end instead of in dependency position would have a schedule decided
     * before its group, and the group would then be neither local nor accepted — so the schedule's
     * verdict would invert from INSERT to `OWNER_NOT_AVAILABLE`, which the last assertion pins.
     */
    @Test
    fun `the ten tables are in dependency order and a whole estate merges in it`() {
        assertEquals(
            listOf(
                MergeTable.ASSETS, MergeTable.GROUPS, MergeTable.DEFINITIONS, MergeTable.PROFILES,
                MergeTable.SCHEDULES, MergeTable.CLOSURES, MergeTable.LINKS, MergeTable.TAGS,
                MergeTable.EVENTS, MergeTable.ATTACHMENTS,
            ),
            MergeTable.entries.toList(),
        )

        val plan = mergePlanOf(
            backupOf(
                assets = listOf(asset("a1")),
                groups = listOf(group("g1", members = listOf(member("gm1", "a1")))),
                definitions = listOf(definition("d1", "a1")),
                profiles = listOf(profile("p1", "a1")),
                schedules = listOf(
                    schedule("s1", assetId = "a1", meterDefinitionId = "d1", profileId = "p1"),
                    schedule("s2", groupId = "g1"),
                ),
                closures = listOf(closure("oc1", "s2")),
                events = listOf(event("e1", "a1", scheduleId = "s1", occurrenceOn = "2026-04-01")),
            ),
            snapshotOf(),
        )

        assertTrue(plan.applicable, "unexpected conflicts: ${plan.conflicts}")
        assertEquals(listOf("g1"), plan.writes.groups.map { it.id.value })
        assertEquals(listOf("s1", "s2"), plan.writes.schedules.map { it.id.value })
        assertEquals(listOf("oc1"), plan.writes.closures.map { it.id })
        assertEquals(listOf("e1"), plan.writes.events.map { it.id.value })
        assertEquals(MergeTally(1, 0, 0, 0), plan.tally(MergeTable.GROUPS))
        assertEquals(MergeTally(2, 0, 0, 0), plan.tally(MergeTable.SCHEDULES))
        assertEquals(MergeTally(1, 0, 0, 0), plan.tally(MergeTable.CLOSURES))

        // The inversion the order exists to prevent: without the group, the same schedule is a
        // conflict about its owner rather than an insert.
        val orphaned = mergePlanOf(
            backupOf(
                assets = listOf(asset("a1")),
                schedules = listOf(schedule("s2", groupId = "g1")),
            ),
            snapshotOf(),
        )
        assertEquals(
            MergeDecision(
                MergeTable.SCHEDULES, "s2", MergeVerdict.CONFLICT,
                MergeReason.OWNER_NOT_AVAILABLE, "g1",
            ),
            orphaned.decision(MergeTable.SCHEDULES, "s2"),
        )
    }

    // --- idempotence ------------------------------------------------------------------------

    /**
     * Hazard: an unchanged re-import stops being a no-op. The archive a phone produced, planned
     * back into that same phone, is all `IDENTICAL` across the three new tables and the events.
     *
     * The second half is the one that carries the conditional-write rule from the data side: after
     * a completion and a closure have advanced an **un-postponed** schedule, the *original* archive
     * still plans that schedule row `IDENTICAL` — because advancing wrote a new event row and a new
     * closure row and touched no column of the schedule. A termination that bumped `updated_at`
     * would make the local row differ and the verdict would become `CONTENT_DIFFERS`.
     */
    @Test
    fun `re-importing an archive of this phone is IDENTICAL, and stays so after its rounds advance`() {
        val assets = listOf(asset("a1"))
        val groups = listOf(group("g1", members = listOf(member("gm1", "a1"))))
        val schedules = listOf(schedule("s2", groupId = "g1"))
        val closures = listOf(closure("oc1", "s2"))
        val events = listOf(event("e1", "a1", scheduleId = "s2", occurrenceOn = "2026-05-04"))

        val same = mergePlanOf(
            backupOf(assets = assets, groups = groups, schedules = schedules, closures = closures, events = events),
            snapshotOf(assets = assets, groups = groups, schedules = schedules, closures = closures, events = events),
        )
        assertTrue(same.applicable)
        assertEquals(MergeTally(0, 1, 0, 0), same.tally(MergeTable.GROUPS))
        assertEquals(MergeTally(0, 1, 0, 0), same.tally(MergeTable.SCHEDULES))
        assertEquals(MergeTally(0, 1, 0, 0), same.tally(MergeTable.CLOSURES))
        assertEquals(MergeTally(0, 1, 0, 0), same.tally(MergeTable.EVENTS))
        assertEquals(MergeWrites(), same.writes)

        // The original archive — before the completion and the closure existed — re-imported into
        // the phone that has since advanced twice.
        val original = backupOf(assets = assets, groups = groups, schedules = schedules)
        val advanced = mergePlanOf(
            original,
            snapshotOf(
                assets = assets, groups = groups, schedules = schedules,
                closures = closures, events = events,
            ),
        )
        assertTrue(advanced.applicable, "unexpected conflicts: ${advanced.conflicts}")
        assertEquals(
            MergeDecision(MergeTable.SCHEDULES, "s2", MergeVerdict.IDENTICAL),
            advanced.decision(MergeTable.SCHEDULES, "s2"),
        )
        assertEquals(MergeWrites(), advanced.writes)
    }

    // --- divergence -------------------------------------------------------------------------

    /**
     * Hazard: a changed row is silently overwritten. A locally edited group, schedule and closure
     * are each `CONFLICT / CONTENT_DIFFERS`, and one conflict anywhere leaves the write set empty
     * **in every field** — so the emptiness is a property of the value and not a discipline the
     * apply has to remember.
     */
    @Test
    fun `a locally edited group, schedule or closure is a CONFLICT and nothing is written`() {
        val assets = listOf(asset("a1"))

        val groupEdited = mergePlanOf(
            backupOf(assets = assets, groups = listOf(group("g1", name = "Aviary Feeders"))),
            snapshotOf(assets = assets, groups = listOf(group("g1", name = "Aviary Feeders East"))),
        )
        assertEquals(
            MergeDecision(MergeTable.GROUPS, "g1", MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, "g1"),
            groupEdited.decision(MergeTable.GROUPS, "g1"),
        )

        val groups = listOf(group("g1"))
        val scheduleEdited = mergePlanOf(
            backupOf(assets = assets, groups = groups, schedules = listOf(schedule("s2", groupId = "g1"))),
            snapshotOf(
                assets = assets, groups = groups,
                schedules = listOf(schedule("s2", groupId = "g1", title = "Top up feeders")),
            ),
        )
        assertEquals(
            MergeDecision(MergeTable.SCHEDULES, "s2", MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, "s2"),
            scheduleEdited.decision(MergeTable.SCHEDULES, "s2"),
        )

        val schedules = listOf(schedule("s2", groupId = "g1"))
        val closureEdited = mergePlanOf(
            backupOf(
                assets = assets, groups = groups, schedules = schedules,
                closures = listOf(closure("oc1", "s2", createdAt = 70L)),
            ),
            snapshotOf(
                assets = assets, groups = groups, schedules = schedules,
                closures = listOf(closure("oc1", "s2", createdAt = 71L)),
            ),
        )
        assertEquals(
            MergeDecision(MergeTable.CLOSURES, "oc1", MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, "oc1"),
            closureEdited.decision(MergeTable.CLOSURES, "oc1"),
        )

        for (plan in listOf(groupEdited, scheduleEdited, closureEdited)) {
            assertFalse(plan.applicable)
            assertEquals(MergeWrites(), plan.writes)
        }
    }

    // --- the closure's second identity ------------------------------------------------------

    /**
     * Hazard: the closure's second identity treated as the row id alone. One case per row of the
     * contract table, in its order.
     *
     * The equivalent-local-row case is the load-bearing one: matching on the row id alone would
     * make it an `INSERT` that `UNIQUE(schedule_id, occurrence_on)` then refuses at apply time,
     * with rows already written. Two devices that closed the same round on the same day merge
     * cleanly; a disagreement about *when* is a conflict for a human, and a date never picks a
     * winner.
     */
    @Test
    fun `a closure is identified by its pair as well as by its id`() {
        val assets = listOf(asset("a1"))
        val groups = listOf(group("g1"))
        val schedules = listOf(schedule("s2", groupId = "g1"))

        fun planWith(
            archive: List<OccurrenceClosure>,
            local: List<OccurrenceClosure>,
            localSchedules: List<MaintenanceSchedule> = schedules,
        ) = mergePlanOf(
            backupOf(assets = assets, groups = groups, schedules = schedules, closures = archive),
            snapshotOf(assets = assets, groups = groups, schedules = localSchedules, closures = local),
        )

        // unclaimed id, unclaimed pair
        assertEquals(
            MergeDecision(MergeTable.CLOSURES, "oc1", MergeVerdict.INSERT),
            planWith(listOf(closure("oc1", "s2")), emptyList()).decision(MergeTable.CLOSURES, "oc1"),
        )
        // the id is here and every field matches
        assertEquals(
            MergeDecision(MergeTable.CLOSURES, "oc1", MergeVerdict.IDENTICAL),
            planWith(listOf(closure("oc1", "s2")), listOf(closure("oc1", "s2")))
                .decision(MergeTable.CLOSURES, "oc1"),
        )
        // the id is here and a field differs
        assertEquals(
            MergeDecision(MergeTable.CLOSURES, "oc1", MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, "oc1"),
            planWith(listOf(closure("oc1", "s2")), listOf(closure("oc1", "s2", closedOn = "2026-05-07")))
                .decision(MergeTable.CLOSURES, "oc1"),
        )
        // a local row under a different id is this closure, field for field
        assertEquals(
            MergeDecision(
                MergeTable.CLOSURES, "oc1", MergeVerdict.IDENTICAL,
                MergeReason.CLOSURE_HELD_BY_AN_EQUIVALENT_LOCAL_ROW, "oc-local",
            ),
            planWith(listOf(closure("oc1", "s2")), listOf(closure("oc-local", "s2")))
                .decision(MergeTable.CLOSURES, "oc1"),
        )
        // a local row under a different id holds the pair and closed it on another day
        assertEquals(
            MergeDecision(
                MergeTable.CLOSURES, "oc1", MergeVerdict.CONFLICT,
                MergeReason.CLOSURE_DIVERGED, "oc-local",
            ),
            planWith(listOf(closure("oc1", "s2")), listOf(closure("oc-local", "s2", closedOn = "2026-05-09")))
                .decision(MergeTable.CLOSURES, "oc1"),
        )
        // two rows of one archive claim one pair
        val duplicated = planWith(
            listOf(closure("oc1", "s2"), closure("oc2", "s2")),
            emptyList(),
        )
        assertEquals(
            MergeDecision(
                MergeTable.CLOSURES, "oc2", MergeVerdict.CONFLICT,
                MergeReason.CLOSURE_DUPLICATED_IN_ARCHIVE, "oc1",
            ),
            duplicated.decision(MergeTable.CLOSURES, "oc2"),
        )
        // the schedule it names is neither here nor being inserted
        assertEquals(
            MergeDecision(
                MergeTable.CLOSURES, "oc9", MergeVerdict.CONFLICT,
                MergeReason.OWNER_NOT_AVAILABLE, "s-nowhere",
            ),
            mergePlanOf(
                backupOf(
                    assets = assets, groups = groups, schedules = schedules,
                    closures = listOf(closure("oc9", "s-nowhere")),
                ),
                snapshotOf(assets = assets, groups = groups, schedules = schedules),
            ).decision(MergeTable.CLOSURES, "oc9"),
        )
        // and a closure is never coalesced by date: one closed on the same day for a *different*
        // schedule is its own row, not a match.
        val otherSchedule = schedule("s3", groupId = "g1")
        assertEquals(
            MergeDecision(MergeTable.CLOSURES, "oc1", MergeVerdict.INSERT),
            mergePlanOf(
                backupOf(
                    assets = assets, groups = groups, schedules = schedules + otherSchedule,
                    closures = listOf(closure("oc1", "s2")),
                ),
                snapshotOf(
                    assets = assets, groups = groups, schedules = schedules + otherSchedule,
                    closures = listOf(closure("oc-other", "s3")),
                ),
            ).decision(MergeTable.CLOSURES, "oc1"),
        )
    }

    // --- references -------------------------------------------------------------------------

    /**
     * Hazard: an orphan written instead of a conflict reported. A group whose member names an
     * absent asset, and a schedule whose target, meter definition or profile is neither local nor
     * being inserted, are each `OWNER_NOT_AVAILABLE` — a conflict, never an orphan. Without the
     * reference pass the apply would fail on a foreign key with rows already written.
     */
    @Test
    fun `a group or schedule whose owner is nowhere is OWNER_NOT_AVAILABLE`() {
        val assets = listOf(asset("a1"))
        val groups = listOf(group("g1"))

        assertEquals(
            MergeDecision(
                MergeTable.GROUPS, "g1", MergeVerdict.CONFLICT,
                MergeReason.OWNER_NOT_AVAILABLE, "a-nowhere",
            ),
            mergePlanOf(
                backupOf(groups = listOf(group("g1", members = listOf(member("gm1", "a-nowhere"))))),
                snapshotOf(),
            ).decision(MergeTable.GROUPS, "g1"),
        )

        val cases = listOf(
            "a-nowhere" to schedule("s1", assetId = "a-nowhere"),
            "g-nowhere" to schedule("s1", groupId = "g-nowhere"),
            "d-nowhere" to schedule("s1", assetId = "a1", meterDefinitionId = "d-nowhere"),
            "p-nowhere" to schedule("s1", assetId = "a1", profileId = "p-nowhere"),
        )
        for ((missing, row) in cases) {
            assertEquals(
                MergeDecision(
                    MergeTable.SCHEDULES, "s1", MergeVerdict.CONFLICT,
                    MergeReason.OWNER_NOT_AVAILABLE, missing,
                ),
                mergePlanOf(
                    backupOf(assets = assets, groups = groups, schedules = listOf(row)),
                    snapshotOf(assets = assets, groups = groups),
                ).decision(MergeTable.SCHEDULES, "s1"),
                "missing $missing",
            )
        }

        // An event naming a schedule that is nowhere is the same answer, for the same reason.
        assertEquals(
            MergeDecision(
                MergeTable.EVENTS, "e1", MergeVerdict.CONFLICT,
                MergeReason.OWNER_NOT_AVAILABLE, "s-nowhere",
            ),
            mergePlanOf(
                backupOf(
                    assets = assets,
                    events = listOf(event("e1", "a1", scheduleId = "s-nowhere", occurrenceOn = "2026-04-01")),
                ),
                snapshotOf(assets = assets),
            ).decision(MergeTable.EVENTS, "e1"),
        )
    }

    /**
     * Hazard: a schedule with a broken target inserted anyway. Both targets set, and neither set,
     * are each `SCHEDULE_TARGET_INVALID` — read off the wire columns, because the domain cannot
     * hold such a row and its mapper refuses it. Without the check the row inserts and the domain
     * has a schedule it has no value for.
     */
    @Test
    fun `a schedule naming both targets or neither is SCHEDULE_TARGET_INVALID`() {
        val assets = listOf(asset("a1"))
        val groups = listOf(group("g1"))
        val both = schedule("s1", assetId = "a1").toDto().copy(groupId = "g1")
        val neither = schedule("s1", assetId = "a1").toDto().copy(assetId = null)

        for (dto in listOf(both, neither)) {
            val plan = mergePlanOf(
                backupOf(assets = assets, groups = groups, scheduleDtos = listOf(dto)),
                snapshotOf(assets = assets, groups = groups),
            )
            assertEquals(
                MergeDecision(
                    MergeTable.SCHEDULES, "s1", MergeVerdict.CONFLICT,
                    MergeReason.SCHEDULE_TARGET_INVALID, "s1",
                ),
                plan.decision(MergeTable.SCHEDULES, "s1"),
            )
            assertEquals(MergeWrites(), plan.writes)
        }
    }

    // --- child rows -------------------------------------------------------------------------

    /**
     * Hazard: two aggregates sharing one child row id. A membership row whose `id` is already held
     * by another group's member is `CHILD_ROW_ID_TAKEN` — the durable-id rule, which a backup
     * round-trips verbatim rather than minting replacements.
     *
     * A provider row's key is `(schedule_id, provider)` and it has no id of its own, so its
     * collision is a statement about the schedule's content: it is reported as `CONTENT_DIFFERS`.
     * That arm is an **archive-internal guard** — the key carries the schedule's own id, so a
     * destination collision implies an id collision that an earlier arm answers — and it is
     * constructed here from one schedule listing one provider twice.
     */
    @Test
    fun `a child row id already held is a conflict, and so is a duplicated provider row`() {
        val assets = listOf(asset("a1"))
        val local = group("g-local", members = listOf(member("gm1", "a1")))

        assertEquals(
            MergeDecision(
                MergeTable.GROUPS, "g1", MergeVerdict.CONFLICT,
                MergeReason.CHILD_ROW_ID_TAKEN, "gm1",
            ),
            mergePlanOf(
                backupOf(
                    assets = assets,
                    groups = listOf(group("g1", members = listOf(member("gm1", "a1", addedAt = 5_000L)))),
                ),
                snapshotOf(assets = assets, groups = listOf(local)),
            ).decision(MergeTable.GROUPS, "g1"),
        )

        val twice = schedule(
            "s1", assetId = "a1",
            providers = listOf(
                ScheduleProviderRow("LOCAL", enabled = true),
                ScheduleProviderRow("LOCAL", enabled = false),
            ),
        )
        assertEquals(
            MergeDecision(
                MergeTable.SCHEDULES, "s1", MergeVerdict.CONFLICT,
                MergeReason.CONTENT_DIFFERS, "LOCAL",
            ),
            mergePlanOf(
                backupOf(assets = assets, schedules = listOf(twice)),
                snapshotOf(assets = assets),
            ).decision(MergeTable.SCHEDULES, "s1"),
        )
    }

    /**
     * Hazard: a membership guard tested as a two-phone story, which would pass vacuously or fail
     * for the wrong reason.
     *
     * `GROUP_MEMBER_WINDOW_TAKEN` and `GROUP_MEMBER_ALREADY_OPEN` are **archive-internal**: both
     * keys carry the group's own id, so they are produced from an archive that disagrees with
     * itself — one group carrying the same `(asset, addedAt)` twice, and one group carrying two
     * open windows for one asset. The second half of this case is the other direction: two phones
     * with genuinely divergent membership produce `CONTENT_DIFFERS` on the **group row** and
     * neither membership code, because members are child rows of the group aggregate.
     */
    @Test
    fun `the two membership guards come from a self-contradicting archive, not from two phones`() {
        val assets = listOf(asset("a1"))

        val sameWindowTwice = group(
            "g1",
            members = listOf(
                member("gm1", "a1", sortOrder = 0, addedAt = 1_000L, removedAt = 2_000L),
                member("gm2", "a1", sortOrder = 1, addedAt = 1_000L, removedAt = 2_000L),
            ),
        )
        assertEquals(
            MergeDecision(
                MergeTable.GROUPS, "g1", MergeVerdict.CONFLICT,
                MergeReason.GROUP_MEMBER_WINDOW_TAKEN, "a1",
            ),
            mergePlanOf(backupOf(assets = assets, groups = listOf(sameWindowTwice)), snapshotOf())
                .decision(MergeTable.GROUPS, "g1"),
        )

        val twoOpenWindows = group(
            "g1",
            members = listOf(
                member("gm1", "a1", sortOrder = 0, addedAt = 1_000L),
                member("gm2", "a1", sortOrder = 1, addedAt = 3_000L),
            ),
        )
        assertEquals(
            MergeDecision(
                MergeTable.GROUPS, "g1", MergeVerdict.CONFLICT,
                MergeReason.GROUP_MEMBER_ALREADY_OPEN, "a1",
            ),
            mergePlanOf(backupOf(assets = assets, groups = listOf(twoOpenWindows)), snapshotOf())
                .decision(MergeTable.GROUPS, "g1"),
        )

        // The legitimate shape those two must not reject: remove, then re-add later.
        val removedThenReadded = group(
            "g1",
            members = listOf(
                member("gm1", "a1", sortOrder = 0, addedAt = 1_000L, removedAt = 2_000L),
                member("gm2", "a1", sortOrder = 1, addedAt = 3_000L),
            ),
        )
        val legitimate = mergePlanOf(
            backupOf(assets = assets, groups = listOf(removedThenReadded)),
            snapshotOf(assets = assets),
        )
        assertTrue(legitimate.applicable, "unexpected conflicts: ${legitimate.conflicts}")

        // Two phones that disagree about membership: the group row's content differs, and neither
        // membership code appears anywhere in the plan.
        val divergent = mergePlanOf(
            backupOf(
                assets = assets,
                groups = listOf(group("g1", members = listOf(member("gm1", "a1")))),
            ),
            snapshotOf(assets = assets, groups = listOf(group("g1", members = emptyList()))),
        )
        assertEquals(
            MergeDecision(MergeTable.GROUPS, "g1", MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, "g1"),
            divergent.decision(MergeTable.GROUPS, "g1"),
        )
        assertEquals(listOf(MergeReason.CONTENT_DIFFERS), divergent.reasons(MergeTable.GROUPS))
    }

    /**
     * Hazard: a group or a closure coalesced by something that is not its identity. A group's name
     * is descriptive and never identity, so two groups that share one insert as two; and the
     * closure pass matches on `(schedule_id, occurrence_on)` and never on the date a round was
     * closed, so two rounds closed on the same day stay two rows.
     *
     * This is the negative control for the second-identity rules above: they must be exactly as
     * wide as the unique index and no wider.
     */
    @Test
    fun `a name and a closing date are never identity`() {
        val assets = listOf(asset("a1"))
        val local = group("g-local", name = "Aviary Feeders")
        val plan = mergePlanOf(
            backupOf(assets = assets, groups = listOf(group("g1", name = "Aviary Feeders"))),
            snapshotOf(assets = assets, groups = listOf(local)),
        )
        assertTrue(plan.applicable, "unexpected conflicts: ${plan.conflicts}")
        assertEquals(
            MergeDecision(MergeTable.GROUPS, "g1", MergeVerdict.INSERT),
            plan.decision(MergeTable.GROUPS, "g1"),
        )

        val groups = listOf(group("g1"))
        val schedules = listOf(schedule("s1", groupId = "g1"), schedule("s2", groupId = "g1"))
        val sameDay = mergePlanOf(
            backupOf(
                assets = assets, groups = groups, schedules = schedules,
                closures = listOf(closure("oc1", "s1", occurrenceOn = "2026-05-04", closedOn = "2026-05-06")),
            ),
            snapshotOf(
                assets = assets, groups = groups, schedules = schedules,
                closures = listOf(closure("oc-local", "s2", occurrenceOn = "2026-05-11", closedOn = "2026-05-06")),
            ),
        )
        assertTrue(sameDay.applicable, "unexpected conflicts: ${sameDay.conflicts}")
        assertEquals(
            MergeDecision(MergeTable.CLOSURES, "oc1", MergeVerdict.INSERT),
            sameDay.decision(MergeTable.CLOSURES, "oc1"),
        )
    }

    // --- the occurrence index ---------------------------------------------------------------

    /**
     * Hazard: two devices recording one member's completion of one occurrence under different
     * event ids. `UNIQUE(schedule_id, occurrence_on, asset_id)` is a real index, so without this
     * check the apply would fail on it after writing other rows.
     *
     * The same case pins the NULL-distinct half: events with no occurrence link have no key at all,
     * so any number of them coexist and the index stays inert for every non-completion.
     */
    @Test
    fun `one occurrence completed twice across devices is SCHEDULE_OCCURRENCE_TAKEN`() {
        val assets = listOf(asset("a1"))
        val schedules = listOf(schedule("s1", assetId = "a1"))
        val local = event("e-local", "a1", scheduleId = "s1", occurrenceOn = "2026-04-01")

        assertEquals(
            MergeDecision(
                MergeTable.EVENTS, "e1", MergeVerdict.CONFLICT,
                MergeReason.SCHEDULE_OCCURRENCE_TAKEN, "e-local",
            ),
            mergePlanOf(
                backupOf(
                    assets = assets, schedules = schedules,
                    events = listOf(event("e1", "a1", scheduleId = "s1", occurrenceOn = "2026-04-01")),
                ),
                snapshotOf(assets = assets, schedules = schedules, events = listOf(local)),
            ).decision(MergeTable.EVENTS, "e1"),
        )

        // A different member of the same occurrence is a different key, and inserts.
        val plan = mergePlanOf(
            backupOf(
                assets = listOf(asset("a1"), asset("a2")), schedules = schedules,
                events = listOf(event("e2", "a2", scheduleId = "s1", occurrenceOn = "2026-04-01")),
            ),
            snapshotOf(assets = listOf(asset("a1"), asset("a2")), schedules = schedules, events = listOf(local)),
        )
        assertEquals(
            MergeDecision(MergeTable.EVENTS, "e2", MergeVerdict.INSERT),
            plan.decision(MergeTable.EVENTS, "e2"),
        )

        // NULLs are distinct: three ordinary journal events with no occurrence link all insert.
        val plain = mergePlanOf(
            backupOf(
                assets = assets,
                events = listOf(event("j1", "a1"), event("j2", "a1"), event("j3", "a1")),
            ),
            snapshotOf(assets = assets, events = listOf(event("j0", "a1"))),
        )
        assertTrue(plain.applicable, "unexpected conflicts: ${plain.conflicts}")
        assertEquals(MergeTally(3, 0, 0, 0), plain.tally(MergeTable.EVENTS))
    }

    // --- the report -------------------------------------------------------------------------

    /**
     * Hazard: a report a client cannot rely on. Conflicts across **all ten** tables come back
     * sorted by table ordinal and then by id, and the three new tallies are present and correct.
     * Sorting by id alone, or a missing tally, changes what a client reads.
     *
     * Every incoming row here diverges from a local row of the same id, which is why the owners all
     * still resolve: a conflicting row is still a *local* row, so nothing downstream of it degrades
     * into `OWNER_NOT_AVAILABLE` and each table reports the conflict that is really its own.
     */
    @Test
    fun `conflicts across all ten tables are reported in table then id order`() {
        val incoming = backupOf(
            assets = listOf(asset("a1", name = "Orchard Pump")),
            groups = listOf(group("g1", name = "Aviary Feeders")),
            definitions = listOf(definition("d1", "a1")),
            profiles = listOf(profile("p1", "a1")),
            schedules = listOf(schedule("s1", assetId = "a1", title = "Filter change")),
            closures = listOf(closure("oc1", "s1", closedOn = "2026-05-06")),
            links = listOf(link("l1", "a1", label = "notes")),
            tags = listOf(tag("t1", "a1", label = "pump")),
            events = listOf(event("e1", "a1", title = "Filter change")),
            attachments = listOf(attachment("att1", "a1")),
        )
        val plan = mergePlanOf(
            incoming,
            snapshotOf(
                assets = listOf(asset("a1", name = "Orchard Pump West")),
                groups = listOf(group("g1", name = "Aviary Feeders East")),
                definitions = listOf(definition("d1", "a1", isMeter = false)),
                profiles = listOf(profile("p1", "a1").copy(name = "Service p1 revised")),
                schedules = listOf(schedule("s1", assetId = "a1", title = "Filter swap")),
                closures = listOf(closure("oc1", "s1", closedOn = "2026-05-07")),
                links = listOf(link("l1", "a1", label = "notes revised")),
                tags = listOf(tag("t1", "a1", label = "pump west")),
                events = listOf(event("e1", "a1", title = "Filter swap")),
                attachments = listOf(attachment("att1", "a1", notes = "revised")),
            ),
        )

        val report = plan.report()
        assertFalse(report.applicable)
        assertEquals(
            listOf(
                MergeTable.ASSETS to "a1",
                MergeTable.GROUPS to "g1",
                MergeTable.DEFINITIONS to "d1",
                MergeTable.PROFILES to "p1",
                MergeTable.SCHEDULES to "s1",
                MergeTable.CLOSURES to "oc1",
                MergeTable.LINKS to "l1",
                MergeTable.TAGS to "t1",
                MergeTable.EVENTS to "e1",
                MergeTable.ATTACHMENTS to "att1",
            ),
            report.conflicts.map { it.table to it.id },
        )
        assertTrue(report.conflicts.all { it.reason == MergeReason.CONTENT_DIFFERS })
        assertEquals(MergeTally(0, 0, 1, 0), report.groups)
        assertEquals(MergeTally(0, 0, 1, 0), report.schedules)
        assertEquals(MergeTally(0, 0, 1, 0), report.closures)
        assertEquals(MergeWrites(), plan.writes)

        // and the sort is by table ordinal first: ids alone would have put "a1" beside "att1".
        val ids = report.conflicts.map { it.id }
        assertTrue(ids.indexOf("g1") < ids.indexOf("d1"), "GROUPS must sort before DEFINITIONS")
        assertTrue(ids.indexOf("s1") < ids.indexOf("oc1"), "SCHEDULES must sort before CLOSURES")
        assertTrue(ids.indexOf("oc1") < ids.indexOf("l1"), "CLOSURES must sort before LINKS")
    }
}
