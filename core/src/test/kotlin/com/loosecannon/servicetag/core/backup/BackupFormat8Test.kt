package com.loosecannon.servicetag.core.backup

import com.loosecannon.servicetag.core.model.HealthAggregation
import com.loosecannon.servicetag.core.model.HealthDriver
import com.loosecannon.servicetag.core.model.HealthSubjectKind
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.testing.activationOf
import com.loosecannon.servicetag.core.testing.archiveOf
import com.loosecannon.servicetag.core.testing.conditionOf
import com.loosecannon.servicetag.core.testing.dataJsonOf
import com.loosecannon.servicetag.core.testing.dataTreeOf
import com.loosecannon.servicetag.core.testing.dayMillis
import com.loosecannon.servicetag.core.testing.editRows
import com.loosecannon.servicetag.core.testing.plainAssetOf
import com.loosecannon.servicetag.core.testing.scheduleOf
import com.loosecannon.servicetag.core.testing.sealed
import com.loosecannon.servicetag.core.testing.seasonalAssetOf
import com.loosecannon.servicetag.core.testing.subjectOf
import com.loosecannon.servicetag.core.testing.with
import com.loosecannon.servicetag.core.testing.without
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Backup **format 8** (spec §8.3; master plan §5): three new lists, five new asset fields, the
 * schedule's policy in place of 1.3's triple, three new `counts` keys, the eager validation, the
 * owner checks and the soft links that are not checked. Formats 1–7 are `LegacyArchiveUpgradeTest`'s
 * and the shipped format classes'; what this class adds is one case per way format 8 could go wrong.
 *
 * The fixture carries **non-default values in every new field**, because a field only ever tested
 * at its default is one a dropped mapper line cannot fail — and a schedule whose `ruleChangedAt`
 * is **not** its `updatedAt`, because a round trip that re-seeded the floor from the edit stamp
 * would bring #64 back through a backup (B01 review, M6).
 */
class BackupFormat8Test {

    // --- fixture ---------------------------------------------------------------------------------

    /** CALENDAR with a break, WEIGHTED, a primary subject. */
    private val snowblower = seasonalAssetOf()

    /** MANUAL (no window, which inv. 88 requires), TRACK_ONE, no break, no primary. */
    private val generator = plainAssetOf("a2", "Generator").copy(
        seasonMode = SeasonMode.MANUAL, healthAggregation = HealthAggregation.TRACK_ONE,
    )

    /** PRE_SERVICE with a signed offset, and a floor that is not the edit stamp. */
    private val preService = scheduleOf(
        id = "s1", assetId = "a1", title = "Auger service", timeInterval = 1, timeUnit = RecurrenceUnit.YEAR,
        anchorOn = "2026-01-01", servicePolicy = ServicePolicy.PRE_SERVICE, policyOffsetDays = -14,
        createdOn = "2026-01-01", updatedOn = "2026-02-01", ruleChangedOn = "2026-01-05",
    )

    private val clamped = scheduleOf(
        id = "s2", assetId = "a2", title = "Load test", timeInterval = 6, timeUnit = RecurrenceUnit.MONTH,
        anchorOn = "2026-01-01", servicePolicy = ServicePolicy.IN_SERVICE_RESUME_CLAMPED,
        createdOn = "2026-01-01", updatedOn = "2026-03-01", ruleChangedOn = "2026-01-20",
    )

    private val activations = listOf(
        activationOf("act-1", assetId = "a2", action = SeasonAction.START, occurredOn = "2026-03-01", eventId = "e-9"),
        activationOf("act-2", assetId = "a2", action = SeasonAction.END, occurredOn = "2026-09-30", createdAt = 310L),
    )

    private val conditions = listOf(
        conditionOf("c-1", eventId = "e-8"),
        conditionOf(
            "c-2", condition = OperationalCondition.DOWN, occurredOn = "2026-02-03", occurredTime = null,
            tzId = "UTC", reason = "", createdAt = 410L,
        ),
        conditionOf("c-3", assetId = "a2", condition = OperationalCondition.OPERATIONAL, createdAt = 420L),
    )

    private val subject = subjectOf(
        "h1", assetId = "a1", scheduleId = "s1", baselineProfileId = "p-9", archivedAt = 700L,
        kind = HealthSubjectKind.MEDIUM, driver = HealthDriver.MAINTENANCE_OVERDUE,
    )

    private fun fixture() = BackupData(
        assets = listOf(snowblower, generator).map { it.toDto() },
        nfcTags = emptyList(),
        externalLinks = emptyList(),
        maintenanceSchedules = listOf(preService, clamped).map { it.toDto() },
        seasonActivations = activations.map { it.toDto() },
        assetConditions = conditions.map { it.toDto() },
        healthSubjects = listOf(subject.toDto()),
    )

    private val SerialDescriptor.names: List<String> get() = elementNames.toList()

    // --- the round trip --------------------------------------------------------------------------

    /**
     * Hazard: a new field dropped in transit. Domain → DTO → bytes → DTO → domain, compared as
     * values, so a field left out of either half of a mapper comes back at its default and fails;
     * and the schedule's floor is asserted by name because it is the one field a mapper could
     * silently re-derive instead of dropping.
     */
    @Test
    fun everyNewFieldSurvivesARoundTrip() {
        val decoded = BackupCodec.decode(archiveOf(fixture())).data

        assertEquals(fixture(), decoded)
        assertEquals(listOf(snowblower, generator), decoded.assets.map { it.toDomain() })
        assertEquals(listOf(preService, clamped), decoded.maintenanceSchedules.map { it.toDomain() })
        assertEquals(activations, decoded.seasonActivations.map { it.toDomain() })
        assertEquals(conditions, decoded.assetConditions.map { it.toDomain() })
        assertEquals(listOf(subject), decoded.healthSubjects.map { it.toDomain() })

        // M6: the floor and the edit stamp both survive, and neither is derived from the other.
        val floor = decoded.maintenanceSchedules.single { it.id == "s1" }.toDomain()
        assertEquals(dayMillis("2026-01-05"), floor.ruleChangedAt)
        assertEquals(dayMillis("2026-02-01"), floor.updatedAt)
    }

    // --- the field sets --------------------------------------------------------------------------

    /**
     * Hazard: DTO field-set drift. The three new DTOs are pinned to master plan §5's lists in order;
     * the schedule's whole field set is pinned here (moved from `BackupFormat6Test`, whose pin format 8
     * retired); the asset's five new fields are pinned last; and **no new field has a default**, so
     * a format-8 row that omits one can never decode.
     */
    @Test
    fun theDtoFieldSetsAreTheMasterPlansLists() {
        assertEquals(
            listOf("id", "assetId", "action", "occurredOn", "eventId", "createdAt"),
            SeasonActivationDto.serializer().descriptor.names,
        )
        assertEquals(
            listOf("id", "assetId", "condition", "occurredOn", "occurredTime", "tzId", "reason", "eventId", "createdAt"),
            AssetConditionDto.serializer().descriptor.names,
        )
        assertEquals(
            listOf(
                "id", "assetId", "name", "kind", "driver", "scheduleId", "baselineProfileId",
                "nominalUntilDays", "warningFromDays", "criticalFromDays", "weight", "sortOrder",
                "archivedAt", "createdAt", "updatedAt",
            ),
            HealthSubjectDto.serializer().descriptor.names,
        )
        assertEquals(
            listOf(
                "id", "assetId", "groupId", "title", "description", "timeInterval", "timeUnit",
                "timeBasis", "anchorOn", "leadDays", "meterDefinitionId", "meterInterval",
                "anchorMeter", "meterLead", "servicePolicy", "policyOffsetDays", "completionMode",
                "profileId", "remindersEnabled", "status", "postponedDueOn", "createdAt", "updatedAt",
                "ruleChangedAt", "providers",
            ),
            MaintenanceScheduleDto.serializer().descriptor.names,
        )
        val assetFields = AssetDto.serializer().descriptor.names
        assertEquals(
            listOf("seasonMode", "blackoutStartMmdd", "blackoutEndMmdd", "healthAggregation", "healthPrimarySubjectId"),
            assetFields.takeLast(5),
        )
        assertEquals(29, assetFields.size)
        assertEquals(
            listOf("seasonActivations", "assetConditions", "healthSubjects"),
            BackupData.serializer().descriptor.names.takeLast(3),
        )

        // No new field carries a default (the new *lists* do, as the format's other lists do).
        val newFields = mapOf(
            SeasonActivationDto.serializer().descriptor to SeasonActivationDto.serializer().descriptor.names,
            AssetConditionDto.serializer().descriptor to AssetConditionDto.serializer().descriptor.names,
            HealthSubjectDto.serializer().descriptor to HealthSubjectDto.serializer().descriptor.names,
            AssetDto.serializer().descriptor to assetFields.takeLast(5),
            MaintenanceScheduleDto.serializer().descriptor to listOf("servicePolicy", "policyOffsetDays", "ruleChangedAt"),
        )
        for ((descriptor, fields) in newFields) {
            for (field in fields) {
                assertFalse(
                    descriptor.isElementOptional(descriptor.getElementIndex(field)),
                    "${descriptor.serialName}.$field must carry no default",
                )
            }
        }
    }

    // --- strict decode ---------------------------------------------------------------------------

    /**
     * Hazard: a legacy key accepted in format 8 (inv. 124). A format-8 schedule carrying any of 1.3's
     * three season fields is an unknown key, so the archive is corrupt — the legacy triple is read
     * only in a format ≤7 file, by `LegacyArchive`, and nowhere else.
     */
    @Test
    fun aLegacySeasonFieldInFormat8IsCorrupt() {
        val tree = dataTreeOf(archiveOf(fixture()))
        for ((key, value) in listOf(
            "seasonBehavior" to JsonPrimitive("IGNORE"),
            "seasonReentry" to JsonPrimitive("AT_START"),
            "seasonReentryOffsetDays" to JsonPrimitive(5),
        )) {
            val legacy = tree.editRows("maintenanceSchedules") { it.with(key, value) }
            assertFailsWith<BackupCorrupt>(key) { BackupCodec.decode(sealed(legacy, formatVersion = 8)) }
        }
        // The unedited tree, sealed the same way, decodes: it is the key and nothing else.
        BackupCodec.decode(sealed(tree, formatVersion = 8))
    }

    /** Hazard: a new field defaulted in — a format-8 row that omits one must not decode. */
    @Test
    fun aMissingNewFieldInFormat8IsCorrupt() {
        val tree = dataTreeOf(archiveOf(fixture()))
        val cases = listOf(
            "assets" to "seasonMode", "assets" to "healthAggregation", "assets" to "blackoutStartMmdd",
            "maintenanceSchedules" to "servicePolicy", "maintenanceSchedules" to "policyOffsetDays",
            "maintenanceSchedules" to "ruleChangedAt",
            "seasonActivations" to "eventId", "assetConditions" to "occurredTime", "healthSubjects" to "weight",
        )
        for ((table, field) in cases) {
            val stripped = tree.editRows(table) { it.without(field) }
            assertFailsWith<BackupCorrupt>("$table.$field") { BackupCodec.decode(sealed(stripped, formatVersion = 8)) }
        }
    }

    // --- direction -------------------------------------------------------------------------------

    /**
     * Hazard: a newer archive half-read. A format-9 manifest over a `data.json` that no format could
     * read is refused as **newer**, not as corrupt — so the gate ran before a single row was parsed.
     */
    @Test
    fun formatNineIsRefusedBeforeAnyRow() {
        val unreadable = dataTreeOf(archiveOf(fixture())).editRows("healthSubjects") { it.with("weight", JsonPrimitive("heavy")) }
        val bytes = sealed(unreadable, formatVersion = 9)

        val refusal = assertFailsWith<BackupNewerFormat> { BackupCodec.decode(bytes) }
        assertEquals(9, refusal.found)
        assertEquals(8, refusal.supported)
        // The same tree at a format this build reads *is* parsed — and refused as corrupt.
        assertFailsWith<BackupCorrupt> { BackupCodec.decode(sealed(unreadable, formatVersion = 8)) }
    }

    /**
     * Inv. 63's shape, against the shipped gate: with the supported format held at **7** — what
     * 1.3.x ships — a format-8 archive is `BackupNewerFormat(8, 7)` before any row is read, so 1.3.x
     * refuses a 1.4 export loudly instead of dropping its new rows.
     */
    @Test
    fun aSevenBuildRefusesFormatEight() {
        val bytes = archiveOf(fixture())
        val refusal = assertFailsWith<BackupNewerFormat> { BackupCodec.decode(bytes, supportedFormat = 7) }
        assertEquals(8, refusal.found)
        assertEquals(7, refusal.supported)
    }

    // --- the manifest ----------------------------------------------------------------------------

    /** Hazard: counts drift. Three keys, three different row counts, so a swapped source fails. */
    @Test
    fun countsCarryTheThreeNewKeysEqualToTheRows() {
        val counts = BackupCodec.decode(archiveOf(fixture())).manifest.counts
        assertEquals(2, counts["seasonActivations"])
        assertEquals(3, counts["assetConditions"])
        assertEquals(1, counts["healthSubjects"])
        assertEquals(20, counts.size)
    }

    // --- determinism -----------------------------------------------------------------------------

    /** Hazard: non-deterministic bytes. Every new list, reversed, encodes to the identical archive. */
    @Test
    fun shuffledListsEncodeToIdenticalBytes() {
        val ordered = fixture()
        val shuffled = ordered.copy(
            seasonActivations = ordered.seasonActivations.reversed(),
            assetConditions = ordered.assetConditions.reversed(),
            healthSubjects = (ordered.healthSubjects + subjectOf("h0", assetId = "a2").toDto()).reversed(),
        )
        val sorted = ordered.copy(healthSubjects = listOf(subjectOf("h0", assetId = "a2").toDto()) + ordered.healthSubjects)
        assertContentEquals(archiveOf(sorted), archiveOf(shuffled))
    }

    // --- the graph -------------------------------------------------------------------------------

    /** Hazard: a broken graph imported. Each new row's asset is a real reference. */
    @Test
    fun aFactOrSubjectOnAnAbsentAssetIsCorrupt() {
        val base = fixture()
        for (broken in listOf(
            base.copy(seasonActivations = base.seasonActivations + activationOf("act-x", assetId = "a-gone").toDto()),
            base.copy(assetConditions = base.assetConditions + conditionOf("c-x", assetId = "a-gone").toDto()),
            base.copy(healthSubjects = base.healthSubjects + subjectOf("h-x", assetId = "a-gone").toDto()),
        )) {
            val refusal = assertFailsWith<BackupCorrupt> { BackupCodec.decode(archiveOf(broken)) }
            assertTrue("a-gone" in refusal.message.orEmpty(), refusal.message)
        }
    }

    /** A subject's schedule is a real reference too (its foreign key cascades). */
    @Test
    fun aSubjectOnAnAbsentScheduleIsCorrupt() {
        val base = fixture()
        val broken = base.copy(healthSubjects = listOf(subjectOf("h-x", scheduleId = "s-gone").toDto()))
        val refusal = assertFailsWith<BackupCorrupt> { BackupCodec.decode(archiveOf(broken)) }
        assertTrue("s-gone" in refusal.message.orEmpty(), refusal.message)
    }

    /**
     * Soft links are not validated (inv. 109): a fact naming an event that is nowhere, a subject
     * naming a profile that is nowhere, and an asset naming a primary subject that is nowhere all
     * decode. And two non-archived subjects on one schedule decode too — that is the planner's to
     * name, as `HEALTH_SUBJECT_SCHEDULE_DUPLICATED_IN_ARCHIVE`.
     */
    @Test
    fun aDanglingEventLinkIsNotCorrupt() {
        val base = fixture()
        val dangling = base.copy(
            assets = base.assets.map { if (it.id == "a2") it.copy(healthPrimarySubjectId = "h-nowhere") else it },
            seasonActivations = base.seasonActivations.map { it.copy(eventId = "e-nowhere") },
            assetConditions = base.assetConditions.map { it.copy(eventId = "e-nowhere") },
            healthSubjects = listOf(
                subjectOf("h1", scheduleId = "s1", baselineProfileId = "p-nowhere").toDto(),
                subjectOf("h2", scheduleId = "s1").toDto(),
            ),
        )
        assertEquals(dangling, BackupCodec.decode(archiveOf(dangling)).data)
    }

    /**
     * Inv. 88, the decoder's half: the `MM-DD` window is set exactly when the mode is CALENDAR; and
     * the break is both-or-neither, like the window. Each broken asset is refused before any import.
     */
    @Test
    fun anAssetWhoseWindowDisagreesWithItsModeIsCorrupt() {
        val base = fixture()
        fun withAsset(asset: AssetDto) = base.copy(assets = base.assets.map { if (it.id == asset.id) asset else it })
        val snowblowerDto = snowblower.toDto()
        val generatorDto = generator.toDto()
        for ((label, broken) in listOf(
            "YEAR_ROUND with a window" to withAsset(snowblowerDto.copy(seasonMode = "YEAR_ROUND")),
            "MANUAL with a window" to withAsset(snowblowerDto.copy(seasonMode = "MANUAL")),
            "CALENDAR without a window" to withAsset(generatorDto.copy(seasonMode = "CALENDAR")),
            "half a break" to withAsset(snowblowerDto.copy(blackoutEndMmdd = null)),
            "a break that is not a date" to withAsset(snowblowerDto.copy(blackoutStartMmdd = "13-40")),
        )) {
            assertFailsWith<BackupCorrupt>(label) { BackupCodec.decode(archiveOf(broken)) }
        }
    }

    // --- the tombstones and the derived --------------------------------------------------------

    /**
     * The tombstones move nowhere: a fixture's `externalLinks` array encodes to exactly the bytes a
     * 1.3 build wrote for it — field order, indentation and all — beside the three new lists.
     */
    @Test
    fun theExternalLinksArrayEncodesAsIn1_3() {
        val link = ExternalLinkDto(
            id = "l1", assetId = "a1", kind = "JOPLIN", label = "Mower notes", uri = "joplin://l1",
            createdAt = 5L, lastOpenedAt = null, updatedAt = 6L,
        )
        val json = dataJsonOf(archiveOf(fixture().copy(externalLinks = listOf(link))))
        val slice = json.substring(json.indexOf("\"externalLinks\""), json.indexOf("\"measurementDefinitions\""))
        val as13 = listOf(
            "\"externalLinks\": [",
            "        {",
            "            \"id\": \"l1\",",
            "            \"assetId\": \"a1\",",
            "            \"kind\": \"JOPLIN\",",
            "            \"label\": \"Mower notes\",",
            "            \"uri\": \"joplin://l1\",",
            "            \"createdAt\": 5,",
            "            \"lastOpenedAt\": null,",
            "            \"updatedAt\": 6",
            "        }",
            "    ],",
            "    ",
        ).joinToString("\n")
        assertEquals(as13, slice)
    }

    /**
     * Hazard: derived state exported (inv. 111; spec 1.2 inv. 64). No element anywhere in
     * `BackupData`'s descriptor tree names schedule state, a score, a band, an aggregate value, a
     * current condition or delivery state.
     */
    @Test
    fun noDerivedOrHealthFieldIsInTheFormat() {
        val forbidden = Regex(
            "(schedulestate|score|band|aggregate|computed|actionabledue|effectivedue|policyphase|" +
                "policyreason|quiet|seasonactive|delivery|healthvalue|currentcondition)",
        )
        // The tree is finite (no type contains itself), so it is walked whole, with no pruning: a
        // shared descriptor such as a list's would otherwise hide every list after the first.
        val seen = mutableSetOf<String>()
        val offenders = mutableListOf<String>()
        fun walk(descriptor: SerialDescriptor, path: String) {
            seen += descriptor.serialName
            if (forbidden.containsMatchIn(descriptor.serialName.substringAfterLast('.').lowercase())) offenders += path
            for (i in 0 until descriptor.elementsCount) {
                val name = descriptor.getElementName(i)
                if (forbidden.containsMatchIn(name.lowercase())) offenders += "$path.$name"
                walk(descriptor.getElementDescriptor(i), "$path.$name")
            }
        }
        walk(BackupData.serializer().descriptor, "BackupData")
        assertEquals(emptyList(), offenders)
        // and the walk really reached the new rows
        assertTrue(seen.any { it.endsWith("HealthSubjectDto") } && seen.any { it.endsWith("AssetConditionDto") })
    }
}
