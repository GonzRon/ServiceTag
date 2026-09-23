package com.loosecannon.servicetag.core.backup

import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.testing.FakeAttachmentStorage
import com.loosecannon.servicetag.core.testing.FakeUnitOfWork
import com.loosecannon.servicetag.core.testing.InMemoryAssetRepository
import com.loosecannon.servicetag.core.testing.InMemoryAttachmentRepository
import com.loosecannon.servicetag.core.testing.InMemoryClosureRepository
import com.loosecannon.servicetag.core.testing.InMemoryDefinitionRepository
import com.loosecannon.servicetag.core.testing.InMemoryEventRepository
import com.loosecannon.servicetag.core.testing.InMemoryGroupRepository
import com.loosecannon.servicetag.core.testing.InMemoryLinkRepository
import com.loosecannon.servicetag.core.testing.InMemoryProfileRepository
import com.loosecannon.servicetag.core.testing.InMemoryReferenceRepository
import com.loosecannon.servicetag.core.testing.InMemoryScheduleRepository
import com.loosecannon.servicetag.core.testing.InMemoryTagRepository
import com.loosecannon.servicetag.core.usecase.ExportBackupSet
import com.loosecannon.servicetag.core.usecase.ImportBackupReplace
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.descriptors.elementNames
import org.junit.jupiter.api.Test

/**
 * Backup **format 6**: three new top-level tables, three new event fields, five new `counts` keys,
 * the sorting that keeps the bytes deterministic, and the eager validation that refuses a bad row
 * before an import can begin. Everything format ≤5 already promised is `BackupCodecTest`'s and is
 * not repeated here; what this class adds is one case per way format 6 could go wrong.
 *
 * The fixture is one small estate written **in canonical order**, so a round trip can be asserted
 * as a value: top-level lists by id, a group's members by `(sortOrder, id)`, a schedule's providers
 * by `provider`. Every new field carries a **non-default** value, because a field that is only ever
 * tested at its default is a field a dropped mapper line cannot fail.
 */
class BackupFormat6Test {

    // --- fixture ---------------------------------------------------------------------------------

    private fun asset() = AssetDto("a1", "Orchard Pump", "east row", "pumps", "", "ACTIVE", 100L, 200L)

    private fun meterDefinition() = MeasurementDefinitionDto(
        id = "d1", assetId = "a1", key = "runtime_hours", label = "Runtime", unit = "h",
        valueType = "NUMBER", decimals = 1, rangeLow = null, rangeHigh = null, isMeter = true,
        sortOrder = 0, archivedAt = null, createdAt = 1L, updatedAt = 2L,
    )

    private fun profile() = EventProfileDto(
        id = "p1", assetId = "a1", name = "Service", eventKind = "MAINTENANCE",
        defaultTitle = "Service", templateKey = null, sortOrder = 0, archivedAt = null,
        createdAt = 1L, updatedAt = 2L,
        fields = listOf(ProfileFieldDto("pf1", "d1", required = true, sortOrder = 0)),
        consumables = emptyList(),
    )

    /**
     * Two windows for one asset: one closed, one open. That is remove-then-re-add, which is the
     * shape `UNIQUE(group_id, asset_id, added_at)` exists to allow and `UNIQUE(group_id, asset_id)`
     * would have refused. Members are listed in `(sortOrder, id)` order, the canonical one.
     */
    private fun group() = MaintenanceGroupDto(
        id = "g1", name = "Aviary Feeders", description = "north run", archivedAt = 4_000L,
        createdAt = 10L, updatedAt = 20L,
        members = listOf(
            GroupMemberDto(id = "gm2", assetId = "a1", sortOrder = 0, addedAt = 3_000L, removedAt = null),
            GroupMemberDto(id = "gm1", assetId = "a1", sortOrder = 1, addedAt = 1_000L, removedAt = 2_000L),
        ),
    )

    /** An asset-targeted schedule with **every** field set away from its default. */
    private fun assetSchedule() = MaintenanceScheduleDto(
        id = "s1", assetId = "a1", groupId = null, title = "Filter change",
        description = "quarterly, or on runtime", timeInterval = 3, timeUnit = "MONTH",
        timeBasis = "COMPLETION", anchorOn = "2026-03-01", leadDays = 7,
        meterDefinitionId = "d1", meterInterval = 250.0, anchorMeter = 100.0, meterLead = 25.0,
        seasonBehavior = "FOLLOW_ASSET", seasonReentry = "AT_START", seasonReentryOffsetDays = 14,
        completionMode = "FORM", profileId = "p1", remindersEnabled = true, status = "PAUSED",
        postponedDueOn = "2026-06-15", createdAt = 30L, updatedAt = 40L,
        providers = listOf(
            ScheduleProviderDto("ALMANAC", enabled = false),
            ScheduleProviderDto("LOCAL", enabled = true),
        ),
    )

    /** A group-targeted schedule: no meter rule, no profile, season ignored. */
    private fun groupSchedule() = MaintenanceScheduleDto(
        id = "s2", assetId = null, groupId = "g1", title = "Top up feeders", description = "",
        timeInterval = 1, timeUnit = "WEEK", timeBasis = "FIXED", anchorOn = "2026-04-06",
        leadDays = 1, meterDefinitionId = null, meterInterval = null, anchorMeter = null,
        meterLead = null, seasonBehavior = "IGNORE", seasonReentry = null,
        seasonReentryOffsetDays = null, completionMode = "QUICK", profileId = null,
        remindersEnabled = false, status = "ACTIVE", postponedDueOn = null,
        createdAt = 50L, updatedAt = 60L,
        providers = listOf(ScheduleProviderDto("LOCAL", enabled = true)),
    )

    private fun closure() = OccurrenceClosureDto(
        id = "oc1", scheduleId = "s2", occurrenceOn = "2026-05-04", closedOn = "2026-05-06",
        createdAt = 70L,
    )

    /** A completion: the three new event fields, all away from their defaults. */
    private fun completion() = AssetEventDto(
        id = "e1", assetId = "a1", kind = "MAINTENANCE", title = "Filter change",
        profileId = "p1", occurredOn = "2026-04-02", occurredTime = "09:15", tzId = "UTC",
        notes = "", source = "SCHEDULE_QUICK_COMPLETE", sourceRef = null,
        createdAt = 80L, updatedAt = 90L, measurements = emptyList(), consumables = emptyList(),
        scheduleId = "s1", occurrenceOn = "2026-04-01", detailsPending = true,
    )

    private fun fixture() = BackupData(
        assets = listOf(asset()),
        nfcTags = emptyList(),
        externalLinks = emptyList(),
        measurementDefinitions = listOf(meterDefinition()),
        eventProfiles = listOf(profile()),
        assetEvents = listOf(completion()),
        attachments = emptyList(),
        maintenanceGroups = listOf(group()),
        maintenanceSchedules = listOf(assetSchedule(), groupSchedule()),
        occurrenceClosures = listOf(closure()),
    )

    /** An estate with nothing maintenance-shaped in it, for the format-5 cases. */
    private fun formatFiveData() = BackupData(
        assets = listOf(asset()),
        nfcTags = emptyList(),
        externalLinks = emptyList(),
        measurementDefinitions = listOf(meterDefinition()),
        eventProfiles = listOf(profile()),
        assetEvents = listOf(
            completion().copy(scheduleId = null, occurrenceOn = null, detailsPending = false),
        ),
    )

    private fun encoded(data: BackupData, formatVersion: Int = BackupCodec.FORMAT_VERSION) =
        BackupCodec.encode(
            data, appVersion = "1.2.0", schemaVersion = 6, createdAt = 1_758_400_000_000L,
            backupSetId = "set-format-6", formatVersion = formatVersion,
        )

    private fun dataJson(bytes: ByteArray): String {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                if (entry.name == BackupCodec.DATA_ENTRY) return String(zin.readBytes(), Charsets.UTF_8)
                zin.closeEntry()
            }
        }
        error("no ${BackupCodec.DATA_ENTRY} in the archive")
    }

    /** One install, enough of it to restore an archive into. */
    private class Fakes {
        val assets = InMemoryAssetRepository()
        val tags = InMemoryTagRepository()
        val links = InMemoryLinkRepository()
        val definitions = InMemoryDefinitionRepository()
        val profiles = InMemoryProfileRepository()
        val events = InMemoryEventRepository()
        val attachments = InMemoryAttachmentRepository()
        val groups = InMemoryGroupRepository()
        val closures = InMemoryClosureRepository()
        val references = InMemoryReferenceRepository()
        val schedules = InMemoryScheduleRepository(closures)
        val storage = FakeAttachmentStorage()
        val uow = FakeUnitOfWork(
            assets, groups, tags, links, definitions, profiles, schedules, closures,
            events, attachments, references,
        )
        val export = ExportBackupSet(
            assets, groups, tags, links, definitions, profiles, schedules, closures,
            events, attachments, references, uow, IdGenerator { "set-format-6" },
            Clock { 1_758_400_000_000L }, appVersion = "1.2.0", schemaVersion = 6,
        )
        val restore = ImportBackupReplace(
            assets, groups, tags, links, definitions, profiles, schedules, closures,
            events, attachments, references, storage, uow, rebuildAll = { },
        )
    }

    // --- the round trip --------------------------------------------------------------------------

    /**
     * Hazard: a new field silently dropped in transit. Every new field of every new DTO, and the
     * three new `AssetEventDto` fields, carry a non-default value here, so a field omitted from a
     * DTO or from either half of its mapper comes back at its default and this fails.
     *
     * The value comparison is the whole assertion: `BackupData` is a data class, so it covers every
     * field of every row and every child row without naming them one at a time.
     */
    @Test
    fun `every new field survives encode and decode with a non-default value`() {
        val data = fixture()
        val decoded = BackupCodec.decode(encoded(data))

        assertEquals(data, decoded.data)
        // and the domain sees the same values, which is what the mappers are for
        val schedule = decoded.data.maintenanceSchedules.single { it.id == "s1" }.toDomain()
        assertEquals("2026-06-15", schedule.postponedDueOn)
        assertEquals(250.0, schedule.meterInterval)
        assertEquals("AT_START", schedule.seasonReentry)
        assertEquals(14, schedule.seasonReentryOffsetDays)
        assertEquals(2, schedule.providers.size)
        val event = decoded.data.assetEvents.single().toDomain()
        assertEquals("s1", event.scheduleId?.value)
        assertEquals("2026-04-01", event.occurrenceOn)
        assertTrue(event.detailsPending)
        val members = decoded.data.maintenanceGroups.single().toDomain().members
        assertEquals(listOf<Long?>(null, 2_000L), members.map { it.removedAt })
    }

    /**
     * Hazard: DTO field-set drift. Each of the five new DTOs is pinned to its contracted element
     * names — count *and* names — so a field added later, or misnamed, fails here rather than
     * drifting past a generator or a client. `AssetEventDto` and `BackupData` are pinned to their
     * new members for the same reason.
     */
    @Test
    fun `each new DTO's field set is exactly the contract`() {
        assertEquals(
            listOf("id", "name", "description", "archivedAt", "createdAt", "updatedAt", "members"),
            MaintenanceGroupDto.serializer().descriptor.elementNames.toList(),
        )
        assertEquals(
            listOf("id", "assetId", "sortOrder", "addedAt", "removedAt"),
            GroupMemberDto.serializer().descriptor.elementNames.toList(),
        )
        assertEquals(
            listOf(
                "id", "assetId", "groupId", "title", "description", "timeInterval", "timeUnit",
                "timeBasis", "anchorOn", "leadDays", "meterDefinitionId", "meterInterval",
                "anchorMeter", "meterLead", "seasonBehavior", "seasonReentry",
                "seasonReentryOffsetDays", "completionMode", "profileId", "remindersEnabled",
                "status", "postponedDueOn", "createdAt", "updatedAt", "providers",
            ),
            MaintenanceScheduleDto.serializer().descriptor.elementNames.toList(),
        )
        assertEquals(
            listOf("provider", "enabled"),
            ScheduleProviderDto.serializer().descriptor.elementNames.toList(),
        )
        assertEquals(
            listOf("id", "scheduleId", "occurrenceOn", "closedOn", "createdAt"),
            OccurrenceClosureDto.serializer().descriptor.elementNames.toList(),
        )
        // the counts the contract states, so a silent addition cannot hide inside a long list
        assertEquals(7, MaintenanceGroupDto.serializer().descriptor.elementsCount)
        assertEquals(5, GroupMemberDto.serializer().descriptor.elementsCount)
        assertEquals(25, MaintenanceScheduleDto.serializer().descriptor.elementsCount)
        assertEquals(2, ScheduleProviderDto.serializer().descriptor.elementsCount)
        assertEquals(5, OccurrenceClosureDto.serializer().descriptor.elementsCount)
        // a closure has no `updatedAt`, because the row is immutable
        assertFalse("updatedAt" in OccurrenceClosureDto.serializer().descriptor.elementNames)

        val eventFields = AssetEventDto.serializer().descriptor.elementNames.toList()
        assertEquals(listOf("scheduleId", "occurrenceOn", "detailsPending"), eventFields.takeLast(3))
        assertEquals(18, eventFields.size)
        val tables = BackupData.serializer().descriptor.elementNames.toList()
        // By position, not `takeLast`: format 7 appends `assetReferences` after these three, and
        // where format 6's tables sit is the claim this line makes.
        assertEquals(
            listOf("maintenanceGroups", "maintenanceSchedules", "occurrenceClosures"),
            tables.subList(7, 10),
        )
        assertEquals(11, tables.size)
        // and neither derived nor delivery state is a table of this format
        assertTrue(tables.none { it.startsWith("scheduleState") || it.startsWith("scheduleLocal") })
    }

    // --- direction -------------------------------------------------------------------------------

    /**
     * Hazard: an old archive stops working. A hand-built **format-5** archive decodes with three
     * empty lists and its event's three new fields at their defaults, and restoring it leaves this
     * install with zero groups, zero schedules and zero closures — **no schedule is invented**.
     *
     * Without the `= emptyList()` defaults the decode would throw on the missing keys; with a
     * mapper that fabricated a schedule the restore counts would not be zero.
     */
    @Test
    fun `a format-5 archive still decodes and restores, and invents no schedule`() {
        val bytes = encoded(formatFiveData(), formatVersion = 5)
        val decoded = BackupCodec.decode(bytes)

        assertEquals(5, decoded.manifest.formatVersion)
        assertEquals(emptyList(), decoded.data.maintenanceGroups)
        assertEquals(emptyList(), decoded.data.maintenanceSchedules)
        assertEquals(emptyList(), decoded.data.occurrenceClosures)
        val event = decoded.data.assetEvents.single()
        assertNull(event.scheduleId)
        assertNull(event.occurrenceOn)
        assertFalse(event.detailsPending)

        val f = Fakes()
        val report = runBlocking { f.restore.run(bytes) }

        assertEquals(5, report.formatVersion)
        runBlocking {
            assertEquals(1, f.assets.all().size)
            assertEquals(1, f.events.all().size)
            assertEquals(emptyList(), f.groups.all())
            assertEquals(emptyList(), f.schedules.all())
            assertEquals(emptyList(), f.closures.all())
            val restored = f.events.all().single()
            assertNull(restored.scheduleId)
            assertNull(restored.occurrenceOn)
            assertFalse(restored.detailsPending)
        }
    }

    /**
     * Hazard: a newer archive read by an older build. The shipped gate refuses a manifest whose
     * `formatVersion` exceeds what this build supports **before a single row is read** — asserted
     * here with an archive whose own schedule row names both targets, so a `BackupCorrupt` would
     * prove the rows *were* read.
     *
     * The 1.1.x direction is the same gate with `FORMAT_VERSION` held at 5: a format-6 archive
     * exceeds it, so a 1.1.x build refuses it loudly instead of dropping the rows it cannot see.
     */
    @Test
    fun `an archive from a newer format is refused before any row is read`() {
        val unreadable = fixture().let { f ->
            f.copy(maintenanceSchedules = listOf(f.maintenanceSchedules.first().copy(groupId = "g1")))
        }
        val bytes = encoded(unreadable, formatVersion = BackupCodec.FORMAT_VERSION + 1)

        val refusal = assertFailsWith<BackupNewerFormat> { BackupCodec.decode(bytes) }
        assertEquals(BackupCodec.FORMAT_VERSION + 1, refusal.found)
        assertEquals(BackupCodec.FORMAT_VERSION, refusal.supported)

        // The same row, at a version this build does support, *is* read — and refused as corrupt.
        // That is what makes the assertion above a statement about ordering and not about the row.
        assertFailsWith<BackupCorrupt> { BackupCodec.decode(encoded(unreadable)) }

        // What a 1.1.x build sees: this format is greater than the 5 it supported, so its gate
        // fires too.
        assertTrue(BackupCodec.FORMAT_VERSION > LAST_1_1_X_FORMAT)
    }

    // --- the manifest ----------------------------------------------------------------------------

    /**
     * Hazard: counts drift from rows. The manifest carries exactly sixteen keys, and the five new
     * ones are the row counts of an archive with nested members and providers — so a missing key,
     * or a `sumOf` over the wrong list, gives the wrong number here.
     */
    @Test
    fun `the manifest carries the five new counts and they equal the rows`() {
        val manifest = BackupCodec.decode(encoded(fixture())).manifest

        assertEquals(
            mapOf(
                "assets" to 1, "nfcTags" to 0, "externalLinks" to 0,
                "measurementDefinitions" to 1, "eventProfiles" to 1, "assetEvents" to 1,
                "profileFields" to 1, "profileConsumables" to 0,
                "measurements" to 0, "consumableUsages" to 0,
                "attachments" to 0,
                "maintenanceGroups" to 1, "groupMembers" to 2,
                "maintenanceSchedules" to 2, "scheduleProviders" to 3,
                "occurrenceClosures" to 1,
                // Format 7's own key, counted here because this class pins the whole map.
                "assetReferences" to 0,
            ),
            manifest.counts,
        )
        assertEquals(17, manifest.counts.size)
    }

    // --- determinism -----------------------------------------------------------------------------

    /**
     * Hazard: non-deterministic bytes. The same data with every list and every child list shuffled
     * encodes to the same bytes, and the child lists come out on their contracted keys — members on
     * `(sortOrder, id)`, providers on `provider`.
     *
     * The members tie-break on `id` is the part that matters: `sortOrder` is not promised unique
     * within a parent, and an unsorted list or a sort on a non-total key makes the two byte arrays
     * differ. The two members here share no `sortOrder`, so the order is also checked directly on a
     * pair that only a `(sortOrder, id)` sort can produce.
     */
    @Test
    fun `encoding is deterministic however the lists and child lists arrive`() {
        // Two rows in every new list, so a missing top-level sort has somewhere to show up.
        val wide = fixture().let { f ->
            f.copy(
                maintenanceGroups = f.maintenanceGroups +
                    group().copy(id = "g2", name = "Orchard Row", members = emptyList()),
                occurrenceClosures = f.occurrenceClosures +
                    closure().copy(id = "oc2", occurrenceOn = "2026-05-11", closedOn = "2026-05-12"),
            )
        }
        val canonical = encoded(wide)
        val shuffled = wide.copy(
            maintenanceGroups = wide.maintenanceGroups.reversed().map { g -> g.copy(members = g.members.reversed()) },
            maintenanceSchedules = wide.maintenanceSchedules.reversed().map { s ->
                s.copy(providers = s.providers.reversed())
            },
            occurrenceClosures = wide.occurrenceClosures.reversed(),
        )

        assertContentEquals(canonical, encoded(shuffled))

        val json = dataJson(encoded(shuffled))
        val groupsJson = json.substring(json.indexOf("\"maintenanceGroups\""), json.indexOf("\"maintenanceSchedules\""))
        assertTrue(groupsJson.indexOf("\"g1\"") < groupsJson.indexOf("\"g2\""), "groups not sorted by id")
        assertTrue(groupsJson.indexOf("\"gm2\"") < groupsJson.indexOf("\"gm1\""), "members not in (sortOrder, id)")
        val schedulesJson = json.substring(json.indexOf("\"maintenanceSchedules\""), json.indexOf("\"occurrenceClosures\""))
        assertTrue(schedulesJson.indexOf("\"s1\"") < schedulesJson.indexOf("\"s2\""), "schedules not sorted by id")
        assertTrue(
            schedulesJson.indexOf("\"ALMANAC\"") < schedulesJson.indexOf("\"LOCAL\""),
            "providers not sorted by provider",
        )
        val closuresJson = json.substring(json.indexOf("\"occurrenceClosures\""))
        assertTrue(closuresJson.indexOf("\"oc1\"") < closuresJson.indexOf("\"oc2\""), "closures not sorted by id")
    }

    // --- eager validation ------------------------------------------------------------------------

    /**
     * Hazard: a bad row found halfway through a destructive import. Every new row is named in the
     * domain and every new reference is resolved **inside the file** before `decode` returns, so a
     * replace import cannot get partway in and then fail on a foreign key with the owner's data
     * already gone.
     *
     * One case per new reference and per new shape rule, asserted together per the one-test-per-
     * hazard rule; each is the fixture with exactly one thing wrong.
     */
    @Test
    fun `a bad new row is refused before any import begins`() {
        val f = fixture()

        fun refuses(label: String, mutate: (BackupData) -> BackupData) {
            val e = assertFailsWith<BackupCorrupt>(label) { BackupCodec.decode(encoded(mutate(f))) }
            assertTrue(e.message!!.isNotBlank(), "$label: unhelpful message")
        }

        refuses("a member naming an absent asset") { d ->
            d.copy(
                maintenanceGroups = d.maintenanceGroups.map { g ->
                    g.copy(members = g.members.map { it.copy(assetId = "a-nowhere") })
                },
            )
        }
        refuses("a group id used twice") { d ->
            d.copy(maintenanceGroups = d.maintenanceGroups + d.maintenanceGroups)
        }
        refuses("a member id used twice") { d ->
            d.copy(
                maintenanceGroups = d.maintenanceGroups.map { g ->
                    g.copy(members = g.members.map { it.copy(id = "gm1") })
                },
            )
        }
        refuses("a schedule naming both targets") { d ->
            d.copy(maintenanceSchedules = listOf(assetSchedule().copy(groupId = "g1")))
        }
        refuses("a schedule naming neither target") { d ->
            d.copy(maintenanceSchedules = listOf(assetSchedule().copy(assetId = null)))
        }
        refuses("a schedule naming an absent group") { d ->
            d.copy(maintenanceSchedules = listOf(groupSchedule().copy(groupId = "g-nowhere")))
        }
        refuses("a schedule naming an absent meter definition") { d ->
            d.copy(maintenanceSchedules = listOf(assetSchedule().copy(meterDefinitionId = "d-nowhere")))
        }
        refuses("a schedule naming an absent profile") { d ->
            d.copy(maintenanceSchedules = listOf(assetSchedule().copy(profileId = "p-nowhere")))
        }
        refuses("a schedule with an unknown time unit") { d ->
            d.copy(maintenanceSchedules = listOf(assetSchedule().copy(timeUnit = "FORTNIGHT")))
        }
        refuses("a schedule with an unknown lifecycle status") { d ->
            d.copy(maintenanceSchedules = listOf(assetSchedule().copy(status = "OVERDUE")))
        }
        refuses("a meter-only schedule carrying a postponement") { d ->
            // `assetSchedule()` already carries `postponedDueOn`; stripping its time rule leaves a
            // schedule with no calendar occurrence for a postponement to replace, which would make
            // `effectiveDueOn` non-null with no time rule. The postpone operation refuses it; without
            // this the import is the way in.
            //
            // Mapped over the list rather than replacing it, deliberately: replacing it would drop
            // the other schedule and this file's event and closure would then name an absent one, so
            // the row would pass on somebody else's refusal.
            d.copy(
                maintenanceSchedules = d.maintenanceSchedules.map { schedule ->
                    if (schedule.id == "s1") {
                        schedule.copy(timeInterval = null, timeUnit = null, anchorOn = null)
                    } else {
                        schedule
                    }
                },
            )
        }
        refuses("a closure naming an absent schedule") { d ->
            d.copy(occurrenceClosures = listOf(closure().copy(scheduleId = "s-nowhere")))
        }
        refuses("a closure id used twice") { d ->
            d.copy(occurrenceClosures = d.occurrenceClosures + d.occurrenceClosures)
        }
        refuses("an event naming an absent schedule") { d ->
            d.copy(assetEvents = listOf(completion().copy(scheduleId = "s-nowhere")))
        }
    }

    // --- export ----------------------------------------------------------------------------------

    /**
     * The export's half of the contract: the three new tables are read, and the two that are
     * derived or device-local are not exportable at all — there is no port on this use case that
     * could read them, which is the structural half of that invariant, and the grep in the gate is
     * the other half.
     */
    @Test
    fun `an export carries the new tables and round-trips through a restore`() {
        val source = Fakes()
        runBlocking {
            source.restore.run(encoded(fixture()))
            val bytes = source.export.run().data
            val decoded = BackupCodec.decode(bytes)

            assertEquals(BackupCodec.FORMAT_VERSION, decoded.manifest.formatVersion)
            assertEquals(fixture().maintenanceGroups, decoded.data.maintenanceGroups)
            assertEquals(fixture().maintenanceSchedules, decoded.data.maintenanceSchedules)
            assertEquals(fixture().occurrenceClosures, decoded.data.occurrenceClosures)
            assertEquals(fixture().assetEvents, decoded.data.assetEvents)

            // and a second install restored from those bytes holds exactly the same rows
            val target = Fakes()
            target.restore.run(bytes)
            assertEquals(source.groups.all(), target.groups.all())
            assertEquals(source.schedules.all(), target.schedules.all())
            assertEquals(source.closures.all(), target.closures.all())
        }
    }

    private companion object {
        /** The last format a 1.1.x build could read. Named so the direction claim is explicit. */
        const val LAST_1_1_X_FORMAT = 5
    }
}
