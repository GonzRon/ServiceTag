package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.backup.BackupData
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.DefinitionKind
import com.loosecannon.servicetag.core.model.DerivedFormula
import com.loosecannon.servicetag.core.model.DerivedSpec
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.EventProfile
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.LinkId
import com.loosecannon.servicetag.core.model.MaintenanceGroup
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.MeasurementDefinition
import com.loosecannon.servicetag.core.model.PayloadFormat
import com.loosecannon.servicetag.core.model.ProfileConsumable
import com.loosecannon.servicetag.core.model.ProfileField
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonActivation
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.model.TagStatus
import com.loosecannon.servicetag.core.model.TagTarget
import com.loosecannon.servicetag.core.model.TimeBasis
import com.loosecannon.servicetag.core.model.ValueType
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.schedule.SeasonPhase
import com.loosecannon.servicetag.core.testing.BackupInstall
import com.loosecannon.servicetag.core.testing.FakeUnitOfWork
import com.loosecannon.servicetag.core.testing.InMemoryScheduleStateRepository
import com.loosecannon.servicetag.core.testing.RiggedFailure
import com.loosecannon.servicetag.core.testing.caseOf
import com.loosecannon.servicetag.core.testing.closureOf
import com.loosecannon.servicetag.core.testing.completionOf
import com.loosecannon.servicetag.core.testing.dayMillis
import com.loosecannon.servicetag.core.testing.groupOf
import com.loosecannon.servicetag.core.testing.loanOf
import com.loosecannon.servicetag.core.testing.scheduleOf
import com.loosecannon.servicetag.core.testing.successionOf
import com.loosecannon.servicetag.core.testing.transferOf
import com.loosecannon.servicetag.core.transfer.AssetTransferredOut
import com.loosecannon.servicetag.core.transfer.HeldWriteGuard
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #86 (B2; rows 8–14) — `ReplaceAsset`: the offer (C8), the plan (C9), the one write (C10) and the carry-forward
 * (C11–C14), over one install built as `AppGraph` builds it — every asset-owned port behind the held-write guard,
 * the real recompute, and the five use cases whose in-transaction bodies the write calls (C15). Every name, key and
 * date is fictional.
 */
class ReplaceAssetTest {
    private val h = ReplaceHarness()

    // ---------------------------------------------------------------------------------------------------------
    // Row 8 — C8, the offer
    // ---------------------------------------------------------------------------------------------------------

    @Test fun aHeldAssetIsNotEligible() = runTest {
        h.put(h.assetRow(PRED, "Example Water Heater"))
        h.put(h.assetRow(PUMP, "Sample Pool Pump"))
        h.hold(PRED)

        val held = h.replace.offer(AssetId(PRED))
        assertTrue(held.held, "a transferred-out asset is held here")
        assertFalse(held.eligible, "a held asset is never replaced")
        assertTrue(h.replace.offer(AssetId(PUMP)).eligible, "its twin, not held, is eligible")
    }

    @Test fun aReplacedAssetIsNotEligible() = runTest {
        h.put(h.assetRow(PRED, "Example Water Heater"))
        h.put(h.assetRow("heater-2", "Example Water Heater"))
        val row = successionOf("s0", predecessor = PRED, successor = "heater-2")
        h.raw.successions.rows[row.id] = row

        val offer = h.replace.offer(AssetId(PRED))
        assertEquals(row, offer.replacedBy)
        assertFalse(offer.eligible, "one successor per predecessor (I2)")
        assertTrue(h.replace.offer(AssetId("heater-2")).eligible, "the successor may itself be replaced: a chain is rows")
    }

    @Test fun archivedRetiredComponentLentAndCaseBearingAreEligible() = runTest {
        h.put(h.assetRow(HOUSE, "Example Plant Room"))
        h.put(h.assetRow("archived", "Example Old Softener").copy(status = AssetStatus.ARCHIVED))
        h.put(h.assetRow("retired", "Example Old Boiler").copy(retiredOn = "2026-05-01"))
        h.put(h.assetRow("component", "Example Expansion Vessel").copy(parentAssetId = AssetId(HOUSE)))
        h.put(h.assetRow("lent", "Example Pressure Washer"))
        h.raw.loans.rows["loan-1"] = loanOf("loan-1", assetId = "lent")
        h.put(h.assetRow("cased", "Example Heat Pump"))
        h.raw.serviceCases.rows["case-1"] = caseOf("case-1", assetId = "cased")

        for (id in listOf("archived", "retired", "component", "lent", "cased")) {
            val offer = h.replace.offer(AssetId(id))
            assertTrue(offer.eligible, "$id is eligible (R86-5)")
            assertFalse(offer.held)
            assertNull(offer.replacedBy)
        }
    }

    @Test fun offersNonArchivedAssetSchedulesOnly() = runTest {
        h.put(h.assetRow(PRED, "Example Water Heater"))
        h.put(h.assetRow(PUMP, "Sample Pool Pump"))
        h.put(groupOf("g1", members = listOf(Triple(PRED, "2026-01-01", null))))
        h.put(scheduleOf("s-active", assetId = PRED, title = "Annual flush", timeInterval = 1, timeUnit = RecurrenceUnit.YEAR, anchorOn = "2025-03-01"))
        h.put(scheduleOf("s-paused", assetId = PRED, title = "Burner check", timeInterval = 6, timeUnit = RecurrenceUnit.MONTH, anchorOn = "2025-03-01", status = ScheduleStatus.PAUSED))
        h.put(scheduleOf("s-archived", assetId = PRED, title = "Anode swap", timeInterval = 2, timeUnit = RecurrenceUnit.YEAR, anchorOn = "2025-03-01", status = ScheduleStatus.ARCHIVED))
        h.put(scheduleOf("s-group", assetId = null, groupId = "g1", title = "Round of the plant room", timeInterval = 1, timeUnit = RecurrenceUnit.MONTH, anchorOn = "2025-03-01"))
        h.put(scheduleOf("s-pump", assetId = PUMP, title = "Pump seal", timeInterval = 1, timeUnit = RecurrenceUnit.YEAR, anchorOn = "2025-03-01"))

        val offer = h.replace.offer(AssetId(PRED))
        assertEquals(listOf("s-active", "s-paused"), offer.schedules.map { it.id.value }, "PAUSED in; ARCHIVED, a group's and another asset's out")
    }

    @Test fun offersOpenWindowsOfUnarchivedGroupsWithNoHeldRow() = runTest {
        h.put(h.assetRow(PRED, "Example Water Heater"))
        h.put(h.assetRow(PUMP, "Sample Pool Pump"))
        h.put(h.assetRow(OPENER, "Example Garage Door Opener"))
        h.put(groupOf("g-open", name = "A run", members = listOf(Triple(PRED, "2026-01-01", null), Triple(PUMP, "2026-01-01", null))))
        h.put(groupOf("g-archived", name = "B run", archivedAt = dayMillis("2026-06-01"), members = listOf(Triple(PRED, "2026-01-01", null))))
        h.put(groupOf("g-held", name = "C run", members = listOf(Triple(PRED, "2026-01-01", null), Triple(OPENER, "2026-01-01", null))))
        h.put(groupOf("g-held-removed", name = "D run", members = listOf(Triple(PRED, "2026-01-01", null), Triple(OPENER, "2026-01-01", "2026-03-01"))))
        h.put(groupOf("g-closed", name = "E run", members = listOf(Triple(PRED, "2026-01-01", "2026-02-01"))))
        h.hold(OPENER)

        val offer = h.replace.offer(AssetId(PRED))
        assertEquals(listOf("g-open"), offer.groups.map { it.id.value }, "archived, held-row (current or removed) and closed-window groups are not offered")
    }

    @Test fun offersActiveTagsOnly() = runTest {
        h.put(h.assetRow(PRED, "Example Water Heater"))
        h.put(h.assetRow(PUMP, "Sample Pool Pump"))
        h.put(h.tagRow("TEST-a-written"))
        h.put(h.tagRow("TEST-b-unwritten", writtenAt = null))
        h.put(h.tagRow("TEST-c-lost", status = TagStatus.LOST))
        h.put(h.tagRow("TEST-d-retired", status = TagStatus.RETIRED))
        h.put(h.tagRow("TEST-e-unbound", status = TagStatus.UNBOUND))
        h.put(h.tagRow("TEST-f-link", target = TagTarget.LinkTarget(LinkId("link-1"))))
        h.put(h.tagRow("TEST-g-pump", target = TagTarget.AssetTarget(AssetId(PUMP))))

        val offer = h.replace.offer(AssetId(PRED))
        assertEquals(
            listOf("TEST-a-written", "TEST-b-unwritten"),
            offer.tags.map { it.id.value },
            "ACTIVE rows, provisioned-unwritten included; never LOST, RETIRED, UNBOUND or a link tombstone",
        )
    }

    @Test fun theParentPrefillDropsAHeldParent() = runTest {
        h.put(h.assetRow(HOUSE, "Example Plant Room"))
        h.put(
            h.assetRow(PRED, "Example Water Heater").copy(
                category = "Water heater", location = "Utility room", parentAssetId = AssetId(HOUSE),
                manufacturer = "Example Works", serialNumber = "SN-TEST-1", purchaseOn = "2019-04-01",
            ),
        )
        h.put(h.assetRow(ANODE, "Example Anode Rod").copy(parentAssetId = AssetId(PRED)))
        h.put(h.assetRow("rod-cap", "Example Rod Cap").copy(parentAssetId = AssetId(ANODE)))
        h.put(h.assetRow(PUMP, "Sample Pool Pump"))

        val free = h.replace.offer(AssetId(PRED))
        assertEquals(
            AssetCommand(name = "Example Water Heater", category = "Water heater", location = "Utility room", parentAssetId = AssetId(HOUSE)),
            free.prefill,
            "name, category, location and a valid parent; identity and purchase facts blank (R86-8)",
        )
        assertEquals(listOf(HOUSE, PUMP), free.parentChoices.map { it.id.value }, "never the old asset or anything under it")

        h.hold(HOUSE)
        val held = h.replace.offer(AssetId(PRED))
        assertNull(held.prefill.parentAssetId, "a held parent is not prefilled")
        assertEquals("Example Water Heater", held.prefill.name)
        assertEquals(listOf(PUMP), held.parentChoices.map { it.id.value }, "a held asset is never a parent choice")
    }

    @Test fun childrenAndAnOpenLoanAreNamed() = runTest {
        h.put(h.assetRow(PRED, "Example Water Heater"))
        h.put(h.assetRow("thermo", "Example Thermostat").copy(parentAssetId = AssetId(PRED)))
        h.put(h.assetRow(ANODE, "Example Anode Rod").copy(parentAssetId = AssetId(PRED)))
        h.put(h.assetRow("rod-cap", "Example Rod Cap").copy(parentAssetId = AssetId(ANODE)))
        h.raw.loans.rows["loan-1"] = loanOf("loan-1", assetId = PRED)
        h.put(h.assetRow(PUMP, "Sample Pool Pump"))
        h.raw.loans.rows["loan-2"] = loanOf("loan-2", assetId = PUMP, returnedOn = "2026-09-01")

        val offer = h.replace.offer(AssetId(PRED))
        assertEquals(listOf("Example Anode Rod", "Example Thermostat"), offer.childNames, "the direct children, by name")
        assertTrue(offer.openLoan, "the open loan is named (R86-6)")
        val pump = h.replace.offer(AssetId(PUMP))
        assertEquals(emptyList(), pump.childNames)
        assertFalse(pump.openLoan, "a returned loan is not open")
    }

    @Test fun setupSeasonAndNotesAreOfferedOnlyWhenThereIsSomethingToCarry() = runTest {
        h.put(h.assetRow(PRED, "Example Water Heater"))
        h.put(h.definitionRow("d-old", key = "old", archivedAt = 5_000L))
        val bare = h.replace.offer(AssetId(PRED))
        assertFalse(bare.setupOffered, "an archived definition alone is nothing to carry")
        assertFalse(bare.seasonOffered, "YEAR_ROUND with no break")
        assertFalse(bare.notesOffered, "no description, no notes")

        h.put(h.profileRow("p-flush", fields = emptyList()))
        h.put(h.assetRow(PRED, "Example Water Heater").copy(blackoutStartMmdd = "07-01", blackoutEndMmdd = "07-14", notes = "Anode checked every spring"))
        val some = h.replace.offer(AssetId(PRED))
        assertTrue(some.setupOffered)
        assertTrue(some.seasonOffered, "a break alone is a season item")
        assertTrue(some.notesOffered)

        h.put(h.assetRow(PUMP, "Sample Pool Pump").copy(seasonMode = SeasonMode.MANUAL, description = "Variable speed"))
        val manual = h.replace.offer(AssetId(PUMP))
        assertTrue(manual.seasonOffered, "MANUAL is a season item")
        assertTrue(manual.notesOffered, "a description alone is a notes item")
    }

    // ---------------------------------------------------------------------------------------------------------
    // Row 9 — C9, the plan
    // ---------------------------------------------------------------------------------------------------------

    @Test fun planWritesNothing() = runTest {
        h.richEstate()
        val before = h.snapshot()

        h.replace.offer(AssetId(PRED))
        val plan = h.replace.plan(h.fullDraft())

        assertEquals(emptyList(), plan.problems)
        assertEquals(0, h.uow.commits, "the offer and the plan open no write")
        assertEquals(0, h.uow.rollbacks)
        assertEquals(before, h.snapshot(), "the store is exactly as it was")
    }

    @Test fun aScheduleNamingAMeterOrProfileNeedsSetup() = runTest {
        h.put(h.assetRow(PRED, "Example Water Heater"))
        h.put(h.definitionRow("d-hours", key = "hours", isMeter = true))
        h.put(h.definitionRow("d-in", key = "inlet"))
        h.put(h.profileRow("p-flush", fields = listOf("d-in")))
        h.put(scheduleOf("s-meter", assetId = PRED, title = "Burner hours", meterDefinitionId = "d-hours", meterInterval = 500.0))
        h.put(scheduleOf("s-form", assetId = PRED, title = "Flush", timeInterval = 6, timeUnit = RecurrenceUnit.MONTH, anchorOn = "2025-03-01", profileId = "p-flush"))
        h.put(scheduleOf("s-plain", assetId = PRED, title = "Look over", timeInterval = 1, timeUnit = RecurrenceUnit.MONTH, anchorOn = "2025-03-01"))
        val ticked = setOf("s-meter", "s-form", "s-plain")

        val without = h.replace.plan(h.draft(scheduleIds = ticked, scheduleStartOn = "2026-09-15"))
        assertEquals(
            listOf(ReplaceProblem.NeedsSetup(ScheduleId("s-form")), ReplaceProblem.NeedsSetup(ScheduleId("s-meter"))),
            without.problems,
            "a profile-only and a meter schedule each need Readings & actions; no silent auto-tick",
        )
        val with = h.replace.plan(h.draft(scheduleIds = ticked, scheduleStartOn = "2026-09-15", carrySetup = true))
        assertEquals(emptyList(), with.problems)
    }

    @Test fun preServiceNeedsTheSeason() = runTest {
        h.put(h.assetRow(PRED, "Example Water Heater").copy(seasonMode = SeasonMode.CALENDAR, seasonStartMmdd = "04-01", seasonEndMmdd = "10-31"))
        h.put(h.preService("s-pre", PRED))

        assertEquals(
            listOf(ReplaceProblem.NeedsSeason(ScheduleId("s-pre"))),
            h.replace.plan(h.draft(scheduleIds = setOf("s-pre"), scheduleStartOn = "2026-09-15")).problems,
        )
        assertEquals(
            emptyList(),
            h.replace.plan(h.draft(scheduleIds = setOf("s-pre"), scheduleStartOn = "2026-09-15", carrySeason = true)).problems,
        )

        // NT-3: a boundary-less predecessor's PRE_SERVICE schedule (reachable only by merge) can never be carried.
        h.put(h.assetRow(PUMP, "Sample Pool Pump"))
        h.put(h.preService("s-pump-pre", PUMP))
        assertFalse(h.replace.offer(AssetId(PUMP)).seasonOffered)
        assertEquals(
            listOf(ReplaceProblem.NeedsSeason(ScheduleId("s-pump-pre"))),
            h.replace.plan(
                h.draft(predecessor = PUMP, name = "Sample Pool Pump", scheduleIds = setOf("s-pump-pre"), scheduleStartOn = "2026-09-15", carrySeason = true),
            ).problems,
        )
    }

    @Test fun aManualSeasonNeedsTheAnswer() = runTest {
        h.put(h.assetRow(PRED, "Example Water Heater").copy(seasonMode = SeasonMode.MANUAL))

        assertEquals(listOf(ReplaceProblem.PhaseRequired), h.replace.plan(h.draft(carrySeason = true)).problems, "no default phase")
        assertEquals(emptyList(), h.replace.plan(h.draft(carrySeason = true, manualPhase = SeasonPhase.OUT_OF_SEASON)).problems)
        assertEquals(emptyList(), h.replace.plan(h.draft(carrySeason = false)).problems, "unticked, nothing to answer")
    }

    @Test fun aTimeRuleNeedsTheStartDate() = runTest {
        h.put(h.assetRow(PRED, "Example Water Heater"))
        h.put(scheduleOf("s-time", assetId = PRED, title = "Annual flush", timeInterval = 1, timeUnit = RecurrenceUnit.YEAR, anchorOn = "2025-03-01"))

        for (start in listOf(null, " ", "2026-13-01")) {
            assertEquals(
                listOf(ReplaceProblem.BadDate("scheduleStartOn")),
                h.replace.plan(h.draft(scheduleIds = setOf("s-time"), scheduleStartOn = start)).problems,
                "start date \"$start\"",
            )
        }
        assertEquals(emptyList(), h.replace.plan(h.draft(scheduleIds = setOf("s-time"), scheduleStartOn = "2026-09-15")).problems)
        assertEquals(emptyList(), h.replace.plan(h.draft(scheduleStartOn = null)).problems, "no time rule ticked, no date asked")
    }

    @Test fun aNameIsRequired() = runTest {
        h.put(h.assetRow(PRED, "Example Water Heater"))
        assertEquals(listOf(ReplaceProblem.NameRequired), h.replace.plan(h.draft(name = "  ")).problems)
    }

    @Test fun aRetirementDateIsRequiredUnlessAlreadyRetired() = runTest {
        h.put(h.assetRow(PRED, "Example Water Heater"))
        for (typed in listOf(null, "", "15/09/2026")) {
            assertEquals(listOf(ReplaceProblem.BadDate("retiredOn")), h.replace.plan(h.draft(retiredOn = typed)).problems, "retiredOn \"$typed\"")
        }
        h.put(h.assetRow(PRED, "Example Water Heater").copy(retiredOn = "2026-08-01"))
        val retired = h.replace.plan(h.draft(retiredOn = null))
        assertEquals(emptyList(), retired.problems, "an already-retired asset needs no date")
        assertEquals("2026-08-01", retired.replacedOn)
    }

    @Test fun anIdTheOfferDoesNotHoldIsNotOffered() = runTest {
        h.put(h.assetRow(PRED, "Example Water Heater"))
        h.put(h.assetRow(ANODE, "Example Anode Rod").copy(parentAssetId = AssetId(PRED)))
        h.put(h.assetRow(OPENER, "Example Garage Door Opener"))
        h.hold(OPENER)
        h.put(scheduleOf("s-archived", assetId = PRED, title = "Anode swap", timeInterval = 2, timeUnit = RecurrenceUnit.YEAR, anchorOn = "2025-03-01", status = ScheduleStatus.ARCHIVED))
        h.put(groupOf("g-archived", archivedAt = dayMillis("2026-06-01"), members = listOf(Triple(PRED, "2026-01-01", null))))
        h.put(h.tagRow("TEST-lost", status = TagStatus.LOST))

        val ids = h.replace.plan(
            h.draft(
                scheduleIds = setOf("s-archived"), scheduleStartOn = "2026-09-15",
                groupIds = setOf("g-archived"), movedTagIds = setOf("TEST-lost"),
            ),
        )
        assertEquals(
            listOf(
                ReplaceProblem.NotOffered("s-archived"),
                ReplaceProblem.NotOffered("g-archived"),
                ReplaceProblem.NotOffered("TEST-lost"),
            ),
            ids.problems,
        )
        for (parent in listOf(PRED, ANODE, OPENER, "gone")) {
            assertEquals(
                listOf(ReplaceProblem.NotOffered(parent)),
                h.replace.plan(h.draft(parent = parent)).problems,
                "parent $parent fails the parent rule",
            )
        }
    }

    @Test fun aFutureReplacementDateIsRefused() = runTest {
        h.put(h.assetRow(PRED, "Example Water Heater"))
        val tomorrow = h.replace.plan(h.draft(retiredOn = "2026-09-21"))
        assertEquals(listOf(ReplaceProblem.ReplacedOnAfterToday), tomorrow.problems, "R86-13a: whatever is ticked")
        assertEquals("2026-09-21", tomorrow.replacedOn)
        assertEquals(emptyList(), h.replace.plan(h.draft(retiredOn = REPLACE_TODAY)).problems, "today itself passes")

        h.put(h.assetRow(PRED, "Example Water Heater").copy(retiredOn = "2026-12-01"))
        val stored = h.replace.plan(h.draft(retiredOn = null))
        assertEquals(listOf(ReplaceProblem.ReplacedOnAfterToday), stored.problems, "a stored future retirement is refused the same way")
        assertEquals("2026-12-01", stored.replacedOn)
    }

    // ---------------------------------------------------------------------------------------------------------
    // Row 10 — C10, the one write
    // ---------------------------------------------------------------------------------------------------------

    @Test fun replacesInOneWrite() = runTest {
        h.richEstate()
        val result = h.replaceWith(h.fullDraft())

        assertEquals(1, h.uow.commits, "one write")
        assertEquals(0, h.uow.rollbacks)
        assertNotEquals(AssetId(PRED), result.successor.id, "a new identity (R86-C1)")
        assertEquals(result.successor, h.raw.assets.rows[result.successor.id.value])
        assertEquals(result.succession, h.raw.successions.rows[result.succession.id])
    }

    @Test fun aRefusalWritesNothing() = runTest {
        h.richEstate()
        val before = h.snapshot()

        // A plan with a problem is never written.
        val nameless = h.fullDraft().copy(successor = h.fullDraft().successor.copy(name = " "))
        assertFailsWith<ReplaceStale> { h.replace.run(nameless, h.replace.plan(nameless)) }
        // A mid-write failure takes every step back with it.
        h.raw.successions.onAppend = { throw RiggedFailure("rigged succession append") }
        assertFailsWith<RiggedFailure> { h.replaceWith(h.fullDraft()) }
        h.raw.successions.onAppend = null
        assertEquals(before, h.snapshot(), "nothing written")
        // A predecessor held since the review.
        val plan = h.replace.plan(h.fullDraft())
        h.hold(PRED)
        assertFailsWith<AssetTransferredOut> { h.replace.run(h.fullDraft(), plan) }

        assertEquals(0, h.uow.commits, "each refusal writes nothing")
        assertEquals(3, h.uow.rollbacks)
        assertEquals(before.copy(transferRecords = h.snapshot().transferRecords), h.snapshot(), "nothing but the hold")
    }

    @Test fun thePredecessorChangesOnlyItsRetirement() = runTest {
        h.richEstate()
        val before = rowsOf(h.snapshot())

        val result = h.replaceWith(h.fullDraft())
        val after = rowsOf(h.snapshot())
        val successor = result.successor.id.value

        val allowed = mapOf(
            "assets" to mapOf(PRED to setOf("retiredOn", "updatedAt")),
            "nfcTags" to mapOf("TEST-side" to setOf("assetId", "updatedAt")),
            "maintenanceGroups" to mapOf("g-north" to setOf("updatedAt", "members")),
        )
        before.forEach { (table, rows) ->
            rows.forEach { (id, row) ->
                val now = assertNotNull(after.getValue(table)[id], "$table $id is still there")
                val free = allowed[table]?.get(id).orEmpty()
                assertEquals(row.filterKeys { it !in free }, now.filterKeys { it !in free }, "$table $id is byte-equal but for $free")
            }
        }
        assertEquals("\"2026-09-15\"", after.getValue("assets").getValue(PRED)["retiredOn"].toString())
        assertEquals("\"$successor\"", after.getValue("nfcTags").getValue("TEST-side")["assetId"].toString())
        val membersBefore = before.getValue("maintenanceGroups").getValue("g-north")["members"]!!.jsonArray.toList()
        val membersAfter = after.getValue("maintenanceGroups").getValue("g-north")["members"]!!.jsonArray.toList()
        assertTrue(membersAfter.containsAll(membersBefore), "every window byte-equal")
        assertEquals(membersBefore.size + 1, membersAfter.size, "plus one new window")
    }

    @Test fun anAlreadyRetiredPredecessorKeepsItsDate() = runTest {
        h.put(h.assetRow(PRED, "Example Water Heater").copy(retiredOn = "2026-08-01"))
        val before = h.raw.assets.rows.getValue(PRED)

        val result = h.replaceWith(h.draft(retiredOn = "2026-09-15"))

        assertEquals(before, h.raw.assets.rows.getValue(PRED), "its row is not written at all (R86-3)")
        assertEquals("2026-08-01", result.succession.replacedOn, "the kept date is the succession date")
    }

    @Test fun aHeldPredecessorIsRefused() = runTest {
        h.richEstate()
        val plan = h.replace.plan(h.fullDraft())
        h.hold(PRED)
        val before = h.snapshot()

        assertFailsWith<AssetTransferredOut> { h.replace.run(h.fullDraft(), plan) }
        assertEquals(0, h.uow.commits)
        assertEquals(before, h.snapshot())
    }

    @Test fun anAlreadyReplacedPredecessorIsStale() = runTest {
        h.richEstate()
        val plan = h.replace.plan(h.fullDraft())
        h.put(h.assetRow("heater-2", "Example Water Heater"))
        h.raw.successions.rows["s-elsewhere"] = successionOf("s-elsewhere", predecessor = PRED, successor = "heater-2")
        val before = h.snapshot()

        assertFailsWith<ReplaceStale> { h.replace.run(h.fullDraft(), plan) }
        assertEquals(0, h.uow.commits)
        assertEquals(before, h.snapshot())
    }

    @Test fun aStaleDraftIsRefused() = runTest {
        val changes: List<Pair<String, ReplaceHarness.() -> Unit>> = listOf(
            "a ticked schedule edited" to {
                raw.schedules.rows["s-flush"] = raw.schedules.rows.getValue("s-flush").copy(title = "Flush and descale", updatedAt = 9_000L)
            },
            "a moved tag scanned" to {
                raw.tags.rows["TEST-side"] = raw.tags.rows.getValue("TEST-side").copy(lastScannedAt = 9_000L)
            },
            "the predecessor retired elsewhere" to {
                raw.assets.rows[PRED] = raw.assets.rows.getValue(PRED).copy(retiredOn = "2026-09-10", updatedAt = 9_000L)
            },
            "a ticked group gained a member" to {
                val group = raw.groups.rows.getValue("g-north")
                raw.groups.rows["g-north"] = group.copy(members = group.members.map { if (it.assetId == AssetId(OPENER)) it.copy(removedAt = null) else it })
            },
            "a carried definition edited" to {
                raw.definitions.rows["d-in"] = raw.definitions.rows.getValue("d-in").copy(label = "Inlet temperature")
            },
        )
        for ((what, change) in changes) {
            val x = ReplaceHarness()
            x.richEstate()
            val plan = x.replace.plan(x.fullDraft())
            assertEquals(emptyList(), plan.problems)
            x.change()
            val before = x.snapshot()

            assertFailsWith<ReplaceStale>(what) { x.replace.run(x.fullDraft(), plan) }
            assertEquals(0, x.uow.commits, what)
            assertEquals(before, x.snapshot(), what)
        }
    }

    @Test fun theRowCarriesTheRetirementDate() = runTest {
        h.put(h.assetRow(PRED, "Example Water Heater"))
        val result = h.replaceWith(h.draft(retiredOn = "2026-09-15"))

        val row = result.succession
        assertEquals(AssetId(PRED), row.predecessorAssetId)
        assertEquals(result.successor.id, row.successorAssetId)
        assertEquals("2026-09-15", row.replacedOn, "replacedOn == retiredOn (R86-3)")
        assertEquals("2026-09-15", h.raw.assets.rows.getValue(PRED).retiredOn)
        assertEquals(h.now, row.createdAt)
        assertEquals(listOf(row), h.raw.successions.rows.values.toList())
    }

    // ---------------------------------------------------------------------------------------------------------
    // Row 11 — C11, schedules
    // ---------------------------------------------------------------------------------------------------------

    @Test fun aCopiedScheduleHasANewIdAndNoHistory() = runTest {
        h.put(h.assetRow(PRED, "Example Water Heater"))
        val source = scheduleOf(
            "s-flush", assetId = PRED, title = "Annual flush", timeInterval = 1, timeUnit = RecurrenceUnit.YEAR,
            timeBasis = TimeBasis.COMPLETION, anchorOn = "2025-03-01", leadDays = 21, status = ScheduleStatus.PAUSED,
            postponedDueOn = "2026-10-01", createdOn = "2025-02-01", ruleChangedOn = "2025-06-01",
        ).copy(description = "Drain a bucket from the valve", remindersEnabled = false)
        h.put(source)
        h.raw.events.rows["e-done"] = completionOf("e-done", occurredOn = "2026-03-01", occurrenceOn = "2026-03-01", assetId = PRED, scheduleId = "s-flush")
        h.raw.closures.rows["c-1"] = closureOf("c-1", occurrenceOn = "2025-03-01", closedOn = "2025-03-10", scheduleId = "s-flush")

        val result = h.replaceWith(h.draft(scheduleIds = setOf("s-flush"), scheduleStartOn = "2026-09-15"))
        val copy = h.copiesFor(result.successor.id).single()

        assertNotEquals(source.id, copy.id, "a new id")
        assertEquals(ScheduleStatus.ACTIVE, copy.status, "the create's status, not PAUSED")
        assertNull(copy.postponedDueOn, "no postponement")
        assertEquals(h.now, copy.ruleChangedAt, "the create's own floor")
        assertEquals(h.now, copy.createdAt)
        assertEquals(
            source.copy(
                id = copy.id, target = ScheduleTarget.AssetTarget(result.successor.id), anchorOn = "2026-09-15",
                status = ScheduleStatus.ACTIVE, postponedDueOn = null, createdAt = h.now, updatedAt = h.now, ruleChangedAt = h.now,
            ),
            copy,
            "the configuration carried, the history not",
        )
        assertTrue(h.raw.events.rows.values.none { it.scheduleId == copy.id }, "no completion event")
        assertTrue(h.raw.closures.rows.values.none { it.scheduleId == copy.id }, "no closure")
        val state = h.states.rows[copy.id.value]
        assertNull(state?.lastCompletedOn, "no completion in its state")
        assertNull(state?.lastCompletionEventId)
    }

    @Test fun theAnchorIsTheReviewedDate() = runTest {
        h.put(h.assetRow(PRED, "Example Water Heater"))
        h.put(scheduleOf("s-annual", assetId = PRED, title = "Annual flush", timeInterval = 1, timeUnit = RecurrenceUnit.YEAR, anchorOn = "2025-03-01"))
        h.put(scheduleOf("s-monthly", assetId = PRED, title = "Look over", timeInterval = 1, timeUnit = RecurrenceUnit.MONTH, anchorOn = "2024-01-10"))
        h.raw.events.rows["e-done"] = completionOf("e-done", occurredOn = "2026-03-01", occurrenceOn = "2026-03-01", assetId = PRED, scheduleId = "s-annual")

        val result = h.replaceWith(h.draft(scheduleIds = setOf("s-annual", "s-monthly"), scheduleStartOn = "2026-09-12"))

        assertEquals(
            listOf("2026-09-12", "2026-09-12"),
            h.copiesFor(result.successor.id).map { it.anchorOn },
            "one reviewed anchor for every copied time rule, never the old anchor or last completion",
        )
    }

    @Test fun aMeterAnchorIsEmpty() = runTest {
        h.put(h.assetRow(PRED, "Example Water Heater"))
        h.put(h.definitionRow("d-hours", key = "hours", isMeter = true))
        h.put(scheduleOf("s-hours", assetId = PRED, title = "Burner hours", meterDefinitionId = "d-hours", meterInterval = 500.0, anchorMeter = 1_200.0, meterLead = 50.0))

        val result = h.replaceWith(h.draft(scheduleIds = setOf("s-hours"), carrySetup = true))
        val copy = h.copiesFor(result.successor.id).single()

        assertNull(copy.anchorMeter, "the new asset's first reading anchors it")
        assertEquals(500.0, copy.meterInterval)
        assertEquals(50.0, copy.meterLead)
        assertNull(copy.anchorOn, "no time rule, no anchor")
    }

    @Test fun profileAndMeterAreRemappedToTheClone() = runTest {
        h.put(h.assetRow(PRED, "Example Water Heater"))
        h.put(h.definitionRow("d-hours", key = "hours", isMeter = true))
        h.put(h.definitionRow("d-in", key = "inlet", sortOrder = 1))
        h.put(h.profileRow("p-flush", fields = listOf("d-in")))
        h.put(scheduleOf("s-hours", assetId = PRED, title = "Burner hours", meterDefinitionId = "d-hours", meterInterval = 500.0))
        h.put(scheduleOf("s-flush", assetId = PRED, title = "Flush", timeInterval = 6, timeUnit = RecurrenceUnit.MONTH, anchorOn = "2025-03-01", completionMode = CompletionMode.FORM, profileId = "p-flush"))

        val result = h.replaceWith(h.draft(scheduleIds = setOf("s-hours", "s-flush"), scheduleStartOn = "2026-09-15", carrySetup = true))
        val successor = result.successor.id
        val hours = h.raw.definitions.rows.values.single { it.assetId == successor && it.key == "hours" }
        val flush = h.raw.profiles.rows.values.single { it.assetId == successor }
        val copies = h.copiesFor(successor).associateBy { it.title }

        assertEquals(hours.id, copies.getValue("Burner hours").meterDefinitionId, "the meter remapped by key")
        assertNotEquals(DefinitionId("d-hours"), hours.id)
        assertEquals(flush.id, copies.getValue("Flush").profileId, "the profile remapped through the clone's id map")
        assertNotEquals(ProfileId("p-flush"), flush.id)
    }

    @Test fun thePredecessorsSchedulesAreUntouched() = runTest {
        h.richEstate()
        val before = h.raw.schedules.rows.values.filter { it.target == ScheduleTarget.AssetTarget(AssetId(PRED)) }

        h.replaceWith(h.fullDraft())

        assertEquals(before, h.raw.schedules.rows.values.filter { it.target == ScheduleTarget.AssetTarget(AssetId(PRED)) })
    }

    // ---------------------------------------------------------------------------------------------------------
    // Row 12 — C12, C13: set-up, season, groups, notes
    // ---------------------------------------------------------------------------------------------------------

    @Test fun setupIsClonedWithNewIdsAndRemappedSources() = runTest {
        h.put(h.assetRow(PRED, "Example Water Heater").copy(templateKey = "water-heater"))
        h.put(h.definitionRow("d-in", key = "inlet", sortOrder = 0))
        h.put(h.definitionRow("d-out", key = "outlet", sortOrder = 1))
        h.put(h.definitionRow("d-drop", key = "drop", sortOrder = 2, derivedFrom = "d-in" to "d-out"))
        h.put(h.profileRow("p-flush", fields = listOf("d-in", "d-drop"), consumables = listOf("Descaler")))
        val oldDefinitions = h.raw.definitions.rows.toMap()
        val oldProfiles = h.raw.profiles.rows.toMap()

        val result = h.replaceWith(h.draft(carrySetup = true))
        val successor = result.successor.id
        val clones = h.raw.definitions.rows.values.filter { it.assetId == successor }.associateBy { it.key }

        assertEquals(setOf("inlet", "outlet", "drop"), clones.keys)
        clones.values.forEach { clone ->
            assertTrue(clone.id.value !in oldDefinitions, "${clone.key} has a new id")
            val old = oldDefinitions.values.single { it.key == clone.key }
            assertEquals(old.copy(id = clone.id, assetId = successor, createdAt = h.now, updatedAt = h.now, derived = clone.derived), clone)
        }
        assertEquals(DerivedSpec(DerivedFormula.PERCENT_DROP, clones.getValue("inlet").id, clones.getValue("outlet").id), clones.getValue("drop").derived)

        val profile = h.raw.profiles.rows.values.single { it.assetId == successor }
        assertTrue(profile.id.value !in oldProfiles)
        assertEquals(listOf(clones.getValue("inlet").id, clones.getValue("drop").id), profile.fields.map { it.definitionId })
        val old = oldProfiles.getValue("p-flush")
        assertTrue(profile.fields.none { f -> old.fields.any { it.id == f.id } }, "new field ids")
        assertTrue(profile.consumables.none { c -> old.consumables.any { it.id == c.id } }, "new consumable ids")
        assertEquals(old.consumables.map { it.copy(id = "") }, profile.consumables.map { it.copy(id = "") })
        assertEquals("water-heater", profile.templateKey, "the profile's provenance kept")
        assertEquals(old.sortOrder, profile.sortOrder)
        assertEquals("water-heater", h.raw.assets.rows.getValue(successor.value).templateKey, "the asset's provenance, as a template stamps it")
        assertEquals(oldDefinitions, h.raw.definitions.rows.filterValues { it.assetId == AssetId(PRED) }, "the old set-up untouched")
    }

    @Test fun anArchivedSourceOfACarriedDerivedDefinitionIsClonedArchived() = runTest {
        h.put(h.assetRow(PRED, "Example Water Heater"))
        h.put(h.definitionRow("d-in", key = "inlet", sortOrder = 0))
        h.put(h.definitionRow("d-out", key = "outlet", sortOrder = 1, archivedAt = 5_000L))
        h.put(h.definitionRow("d-drop", key = "drop", sortOrder = 2, derivedFrom = "d-in" to "d-out"))
        h.put(h.definitionRow("d-old", key = "old", sortOrder = 3, archivedAt = 5_000L))

        val result = h.replaceWith(h.draft(carrySetup = true))
        val clones = h.raw.definitions.rows.values.filter { it.assetId == result.successor.id }.associateBy { it.key }

        assertEquals(setOf("inlet", "outlet", "drop"), clones.keys, "an unreferenced archived row stays behind")
        assertNotNull(clones.getValue("outlet").archivedAt, "the referenced archived source is cloned archived")
        assertNull(clones.getValue("inlet").archivedAt)
        assertNull(clones.getValue("drop").archivedAt)
        assertEquals(clones.getValue("outlet").id, clones.getValue("drop").derived!!.sourceB)
    }

    @Test fun aManualSuccessorHasOneActivationDatedTheReplacementDate() = runTest {
        h.put(h.assetRow(PRED, "Example Water Heater").copy(seasonMode = SeasonMode.MANUAL))
        h.put(h.activationRow("act-1", SeasonAction.START, "2026-04-01"))
        h.put(h.activationRow("act-2", SeasonAction.END, "2026-09-01"))
        val oldActivations = h.raw.activations.rows.toMap()

        val result = h.replaceWith(h.draft(retiredOn = "2026-09-15", carrySeason = true, manualPhase = SeasonPhase.IN_SEASON))
        val successor = result.successor

        assertEquals(SeasonMode.MANUAL, successor.seasonMode)
        val activations = h.raw.activations.rows.values.filter { it.assetId == successor.id }
        assertEquals(1, activations.size, "exactly one, never a copied row")
        assertEquals(SeasonAction.START, activations.single().action, "IN_SEASON starts the season")
        assertEquals("2026-09-15", activations.single().occurredOn, "the replacement date, never today (R86-13 amended)")
        assertEquals(oldActivations, h.raw.activations.rows.filterValues { it.assetId == AssetId(PRED) })
    }

    @Test fun calendarWindowAndBreakCarry() = runTest {
        h.put(
            h.assetRow(PRED, "Example Water Heater").copy(
                seasonMode = SeasonMode.CALENDAR, seasonStartMmdd = "04-15", seasonEndMmdd = "10-15",
                blackoutStartMmdd = "07-20", blackoutEndMmdd = "08-10",
            ),
        )

        val successor = h.replaceWith(h.draft(carrySeason = true)).successor

        assertEquals(SeasonMode.CALENDAR, successor.seasonMode)
        assertEquals("04-15" to "10-15", successor.seasonStartMmdd to successor.seasonEndMmdd)
        assertEquals("07-20" to "08-10", successor.blackoutStartMmdd to successor.blackoutEndMmdd)
        assertTrue(h.raw.activations.rows.values.none { it.assetId == successor.id }, "no activation outside MANUAL")
    }

    @Test fun aGroupGainsANewWindowAndThePredecessorsStaysOpen() = runTest {
        h.put(h.assetRow(PRED, "Example Water Heater"))
        h.put(h.assetRow(PUMP, "Sample Pool Pump"))
        h.put(groupOf("g-north", members = listOf(Triple(PRED, "2026-01-01", null), Triple(PUMP, "2026-02-01", null))))
        val before = h.raw.groups.rows.getValue("g-north")

        val successor = h.replaceWith(h.draft(groupIds = setOf("g-north"))).successor.id
        val after = h.raw.groups.rows.getValue("g-north")

        val predecessorWindow = after.members.single { it.assetId == AssetId(PRED) }
        assertEquals(before.members.single { it.assetId == AssetId(PRED) }, predecessorWindow, "the old window stays open and untouched")
        assertNull(predecessorWindow.removedAt)
        val added = after.members.single { it.assetId == successor }
        assertTrue(added.id !in before.members.map { it.id }, "a new window, a new id")
        assertEquals(h.now, added.addedAt)
        assertNull(added.removedAt)
        assertEquals(2, added.sortOrder, "after the last")
        assertEquals(h.now, after.updatedAt)
    }

    @Test fun aGroupGainsANewWindowAndEveryOtherWindowIsByteEqual() = runTest {
        h.put(h.assetRow(PRED, "Example Water Heater"))
        h.put(h.assetRow(PUMP, "Sample Pool Pump"))
        h.put(h.assetRow(OPENER, "Example Garage Door Opener"))
        h.put(h.assetRow(ANODE, "Example Anode Rod"))
        h.put(
            groupOf(
                "g-north",
                members = listOf(
                    Triple(PRED, "2026-01-01", null),
                    Triple(PUMP, "2026-01-01", null),
                    Triple(OPENER, "2026-01-01", "2026-05-01"),
                    Triple(ANODE, "2026-03-01", null),
                ),
            ),
        )
        val before = h.raw.groups.rows.getValue("g-north")

        val successor = h.replaceWith(h.draft(groupIds = setOf("g-north"))).successor.id
        val after = h.raw.groups.rows.getValue("g-north")

        assertEquals(before.members, after.members.filterNot { it.assetId == successor }, "every other window, open or closed, byte-equal (MN-2)")
        assertEquals(1, after.members.count { it.assetId == successor })
        assertEquals(before.copy(updatedAt = h.now, members = after.members), after, "the group row changes only its stamp")
    }

    @Test fun notesOnlyWhenTicked() = runTest {
        h.put(h.assetRow(PRED, "Example Water Heater").copy(description = "Fifty-litre tank", notes = "Anode checked every spring"))

        val carried = h.replaceWith(h.draft(carryNotes = true)).successor
        assertEquals("Fifty-litre tank" to "Anode checked every spring", carried.description to carried.notes)

        val x = ReplaceHarness()
        x.put(x.assetRow(PRED, "Example Water Heater").copy(description = "Fifty-litre tank", notes = "Anode checked every spring"))
        val typed = x.draft(carryNotes = false).let { it.copy(successor = it.successor.copy(description = "typed", notes = "typed")) }
        val blank = x.replaceWith(typed).successor
        assertEquals("" to "", blank.description to blank.notes, "unticked: blank")
    }

    @Test fun nothingUntickedIsCopied() = runTest {
        h.richEstate()
        val tagsBefore = h.raw.tags.rows.toMap()

        val result = h.replaceWith(h.draft(retiredOn = "2026-09-15"))
        val successor = result.successor

        assertEquals(SeasonMode.YEAR_ROUND, successor.seasonMode)
        assertNull(successor.seasonStartMmdd)
        assertNull(successor.blackoutStartMmdd)
        assertEquals("" to "", successor.description to successor.notes)
        assertNull(successor.templateKey, "no template seeded")
        assertTrue(h.raw.definitions.rows.values.none { it.assetId == successor.id }, "no definition")
        assertTrue(h.raw.profiles.rows.values.none { it.assetId == successor.id }, "no profile")
        assertEquals(emptyList(), h.copiesFor(successor.id), "no schedule")
        assertTrue(h.raw.groups.rows.values.none { g -> g.members.any { it.assetId == successor.id } }, "no window")
        assertTrue(h.raw.activations.rows.values.none { it.assetId == successor.id }, "no activation")
        assertEquals(tagsBefore, h.raw.tags.rows.toMap(), "every tag left")
        assertEquals(listOf(result.succession), h.raw.successions.rows.values.filter { it.successorAssetId == successor.id })
    }

    // ---------------------------------------------------------------------------------------------------------
    // Row 13 — C14, tags
    // ---------------------------------------------------------------------------------------------------------

    @Test fun aMovedTagKeepsItsIdKeyLabelAndWrittenAt() = runTest {
        h.put(h.assetRow(PRED, "Example Water Heater"))
        val before = h.tagRow("TEST-side")
        h.put(before)

        val successor = h.replaceWith(h.draft(movedTagIds = setOf("TEST-side"))).successor.id

        assertEquals(
            before.copy(target = TagTarget.AssetTarget(successor), updatedAt = h.now),
            h.raw.tags.rows.getValue("TEST-side"),
            "only target and updatedAt differ",
        )
        assertEquals(1, h.raw.tags.rows.size, "no tag row minted")
    }

    @Test fun anUnmovedTagStaysOnThePredecessor() = runTest {
        h.put(h.assetRow(PRED, "Example Water Heater"))
        h.put(h.tagRow("TEST-side"))
        val left = h.tagRow("TEST-back", writtenAt = null)
        h.put(left)

        h.replaceWith(h.draft(movedTagIds = setOf("TEST-side")))

        assertEquals(left, h.raw.tags.rows.getValue("TEST-back"), "Leave is the default (R86-14)")
    }

    @Test fun aLinkTombstoneIsNeverRead() = runTest {
        h.put(h.assetRow(PRED, "Example Water Heater"))
        h.put(h.tagRow("TEST-side"))
        val tombstone = h.tagRow("TEST-link", target = TagTarget.LinkTarget(LinkId("link-1")))
        h.put(tombstone)

        assertTrue(h.replace.offer(AssetId(PRED)).tags.none { it.id == tombstone.id })
        assertEquals(listOf(ReplaceProblem.NotOffered("TEST-link")), h.replace.plan(h.draft(movedTagIds = setOf("TEST-link"))).problems)
        h.replaceWith(h.draft(movedTagIds = setOf("TEST-side")))
        assertEquals(tombstone, h.raw.tags.rows.getValue("TEST-link"))
    }
}

// -------------------------------------------------------------------------------------------------------------
// The install
// -------------------------------------------------------------------------------------------------------------

internal const val REPLACE_TODAY = "2026-09-20"
internal const val REPLACE_NOW = 1_758_700_000_000L

internal const val PRED = "heater"
internal const val PUMP = "pump"
internal const val OPENER = "opener"
internal const val ANODE = "anode"
internal const val HOUSE = "plant-room"

private val encoder = Json { encodeDefaults = true }

/** Every canonical row, by table and id (a category by its key), as the archive encodes it. */
private fun rowsOf(data: BackupData): Map<String, Map<String, JsonObject>> =
    encoder.encodeToJsonElement(BackupData.serializer(), data).jsonObject.mapValues { (_, rows) ->
        rows.jsonArray.associate { row ->
            val o = row.jsonObject
            (o["id"] ?: o.getValue("key")).jsonPrimitive.content to o
        }
    }

/**
 * #86 (B2) — one installation as `AppGraph` builds it for Replace: the canonical stores of [BackupInstall] behind
 * the held-write guard, the real recompute over them, and `ReplaceAsset` over the five use cases it calls into.
 * `T` is [REPLACE_TODAY] and the clock stands at [now]; ids are minted `new-1`, `new-2`, ….
 */
internal class ReplaceHarness(today: String = REPLACE_TODAY) {
    val raw = BackupInstall()
    val states = InMemoryScheduleStateRepository()
    private var seq = 0
    val ids = IdGenerator { "new-${++seq}" }
    var now: Long = REPLACE_NOW
    val clock = Clock { now }
    val todayPort = Today { LocalDate.parse(today) }

    private val guard = HeldWriteGuard(
        raw.transfers, raw.events, raw.definitions, raw.profiles, raw.groups, raw.schedules, raw.serviceCases, raw.links,
    )
    val assets = guard.assets(raw.assets)
    val tags = guard.tags(raw.tags)
    val definitions = guard.definitions(raw.definitions)
    val profiles = guard.profiles(raw.profiles)
    val events = guard.events(raw.events)
    val groups = guard.groups(raw.groups)
    val schedules = guard.schedules(raw.schedules)
    val closures = guard.closures(raw.closures)
    val attachments = guard.attachments(raw.attachments)
    val references = guard.references(raw.references)
    val activations = guard.activations(raw.activations)
    val conditions = guard.conditions(raw.conditions)
    val subjects = guard.subjects(raw.subjects)
    val cases = guard.cases(raw.serviceCases)
    val entries = guard.entries(raw.caseEntries)
    val loans = guard.loans(raw.loans)
    val successions = guard.successions(raw.successions)
    val uow: FakeUnitOfWork get() = raw.uow

    val recompute = RecomputeSchedules(
        schedules, states, events, closures, groups, assets, activations, todayPort, clock,
    ) { ZoneOffset.UTC }
    val replace = ReplaceAsset(
        assets, schedules, groups, tags, definitions, profiles, loans, raw.transfers, successions,
        raw.uow, ids, clock, todayPort,
        RetireAsset(assets, raw.uow, clock) { recompute.forAsset(it) },
        SaveAssetSettings(
            assets, schedules, subjects, activations, raw.uow, ids, clock, todayPort, recompute,
            ApplyTemplate(definitions, profiles, assets, raw.uow, ids, clock), PromoteCategory(raw.categories),
        ),
        SaveSchedule(schedules, assets, groups, definitions, profiles, raw.uow, ids, clock, recompute, subjects),
        SaveGroup(groups, assets, raw.uow, ids, clock),
        BindTag(tags, assets, raw.uow, clock),
    )

    private val repos = BackupRepositories(
        assets, groups, tags, raw.links, definitions, profiles, schedules, closures, events, attachments, references,
        activations, conditions, subjects, raw.categories, cases, entries, loans, raw.transfers, successions,
    )

    /** Every canonical row here, as an archive names it. */
    suspend fun snapshot(): BackupData = raw.uow.read { readSnapshot(repos) }

    /** Plans [draft], expects no problem, and confirms it. */
    suspend fun replaceWith(draft: ReplaceDraft): ReplaceResult {
        val plan = replace.plan(draft)
        assertEquals(emptyList(), plan.problems, "the draft plans clean")
        return replace.run(draft, plan)
    }

    /** The schedules targeting [assetId], by title. */
    fun copiesFor(assetId: AssetId): List<MaintenanceSchedule> =
        raw.schedules.rows.values.filter { it.target == ScheduleTarget.AssetTarget(assetId) }.sortedBy { it.title }

    /** Marks [assetId] transferred out from here, by an open OUT. */
    suspend fun hold(assetId: String) = raw.transfers.append(transferOf("out-$assetId", assetId = assetId))

    fun put(asset: Asset) { raw.assets.rows[asset.id.value] = asset }
    fun put(schedule: MaintenanceSchedule) { raw.schedules.rows[schedule.id.value] = schedule }
    fun put(group: MaintenanceGroup) { raw.groups.rows[group.id.value] = group }
    fun put(tag: TagBinding) { raw.tags.rows[tag.id.value] = tag }
    fun put(definition: MeasurementDefinition) { raw.definitions.rows[definition.id.value] = definition }
    fun put(profile: EventProfile) { raw.profiles.rows[profile.id.value] = profile }
    fun put(activation: SeasonActivation) { raw.activations.rows[activation.id] = activation }

    fun assetRow(id: String, name: String): Asset = Asset(id = AssetId(id), name = name, createdAt = 1_000L, updatedAt = 2_000L)

    fun tagRow(
        id: String,
        target: TagTarget = TagTarget.AssetTarget(AssetId(PRED)),
        status: TagStatus = TagStatus.ACTIVE,
        writtenAt: Long? = 3_000L,
    ) = TagBinding(
        id = TagId(id), payloadFormat = PayloadFormat.V1, payloadKey = id, target = target, status = status,
        label = "Side panel", physicalUid = "04:A1:B2:C3", writtenAt = writtenAt, lastScannedAt = 4_000L,
        createdAt = 1_000L, updatedAt = 2_000L,
    )

    fun definitionRow(
        id: String,
        key: String,
        sortOrder: Int = 0,
        isMeter: Boolean = false,
        archivedAt: Long? = null,
        derivedFrom: Pair<String, String>? = null,
        assetId: String = PRED,
    ) = MeasurementDefinition(
        id = DefinitionId(id), assetId = AssetId(assetId), key = key, label = key.replaceFirstChar { it.uppercase() },
        unit = if (isMeter) "h" else "°C", valueType = ValueType.NUMBER, decimals = 1, rangeLow = null, rangeHigh = null,
        isMeter = isMeter, sortOrder = sortOrder, archivedAt = archivedAt, createdAt = 1_000L, updatedAt = 2_000L,
        kind = if (derivedFrom != null) DefinitionKind.DERIVED else DefinitionKind.ENTERED,
        derived = derivedFrom?.let { (a, b) -> DerivedSpec(DerivedFormula.PERCENT_DROP, DefinitionId(a), DefinitionId(b)) },
    )

    fun profileRow(
        id: String,
        fields: List<String>,
        consumables: List<String> = emptyList(),
        archivedAt: Long? = null,
        assetId: String = PRED,
    ) = EventProfile(
        id = ProfileId(id), assetId = AssetId(assetId), name = "Flush", eventKind = EventKind.MAINTENANCE,
        defaultTitle = "Flushed", templateKey = "water-heater", sortOrder = 0, archivedAt = archivedAt,
        createdAt = 1_000L, updatedAt = 2_000L,
        fields = fields.mapIndexed { j, d -> ProfileField("$id-f$j", DefinitionId(d), required = true, sortOrder = j) },
        consumables = consumables.mapIndexed { j, n -> ProfileConsumable("$id-c$j", n, 1.0, "L", j) },
    )

    fun activationRow(id: String, action: SeasonAction, on: String, assetId: String = PRED) =
        SeasonActivation(id, AssetId(assetId), action, on, eventId = null, createdAt = dayMillis(on))

    fun preService(id: String, assetId: String) = scheduleOf(
        id, assetId = assetId, title = "Pre-season service", timeInterval = 1, timeUnit = RecurrenceUnit.YEAR,
        anchorOn = "2025-03-01", servicePolicy = ServicePolicy.PRE_SERVICE, policyOffsetDays = -14,
    )

    /** A draft on [predecessor] with nothing ticked, a back-dated retirement and the prefill's fields. */
    fun draft(
        predecessor: String = PRED,
        retiredOn: String? = "2026-09-15",
        name: String = "Example Water Heater",
        parent: String? = null,
        carrySeason: Boolean = false,
        manualPhase: SeasonPhase? = null,
        carrySetup: Boolean = false,
        carryNotes: Boolean = false,
        scheduleIds: Set<String> = emptySet(),
        scheduleStartOn: String? = null,
        groupIds: Set<String> = emptySet(),
        movedTagIds: Set<String> = emptySet(),
    ) = ReplaceDraft(
        predecessorId = AssetId(predecessor),
        retiredOn = retiredOn,
        successor = AssetCommand(name = name, location = "Utility room", parentAssetId = parent?.let(::AssetId)),
        carrySeason = carrySeason,
        manualPhase = manualPhase,
        carrySetup = carrySetup,
        carryNotes = carryNotes,
        scheduleIds = scheduleIds.map(::ScheduleId).toSet(),
        scheduleStartOn = scheduleStartOn,
        groupIds = groupIds.map(::GroupId).toSet(),
        movedTagIds = movedTagIds.map(::TagId).toSet(),
    )

    /** [richEstate]'s draft with every item ticked, one tag moved and one group joined. */
    fun fullDraft() = draft(
        carrySeason = true, carrySetup = true, carryNotes = true,
        scheduleIds = setOf("s-flush", "s-hours", "s-pre"), scheduleStartOn = "2026-09-15",
        groupIds = setOf("g-north"), movedTagIds = setOf("TEST-side"),
    ).let { it.copy(successor = it.successor.copy(category = "Water heater", parentAssetId = AssetId(HOUSE))) }

    /**
     * An old asset with every kind of row around it: a parent and a child, a chain behind it, set-up with a derived
     * definition over an archived source, three schedules (a form, a meter and a pre-season one) with history, an
     * archived schedule, two groups, tags of every status, a loan, a case, a completion and a closure.
     */
    suspend fun richEstate() {
        put(assetRow(HOUSE, "Example Plant Room"))
        put(assetRow("old-heater", "Example Water Heater").copy(retiredOn = "2019-04-01"))
        put(
            assetRow(PRED, "Example Water Heater").copy(
                category = "Water heater", location = "Utility room", parentAssetId = AssetId(HOUSE),
                description = "Fifty-litre tank", notes = "Anode checked every spring", templateKey = "water-heater",
                manufacturer = "Example Works", serialNumber = "SN-TEST-1", purchaseOn = "2019-04-01",
                seasonMode = SeasonMode.CALENDAR, seasonStartMmdd = "04-01", seasonEndMmdd = "10-31",
                blackoutStartMmdd = "07-20", blackoutEndMmdd = "08-10", warrantyExpiresOn = "2027-04-01",
            ),
        )
        put(assetRow(ANODE, "Example Anode Rod").copy(parentAssetId = AssetId(PRED)))
        put(assetRow(PUMP, "Sample Pool Pump"))
        put(assetRow(OPENER, "Example Garage Door Opener"))
        raw.successions.rows["s-chain"] = successionOf("s-chain", predecessor = "old-heater", successor = PRED, replacedOn = "2019-04-01")

        put(definitionRow("d-hours", key = "hours", isMeter = true, sortOrder = 0))
        put(definitionRow("d-in", key = "inlet", sortOrder = 1))
        put(definitionRow("d-out", key = "outlet", sortOrder = 2, archivedAt = 5_000L))
        put(definitionRow("d-drop", key = "drop", sortOrder = 3, derivedFrom = "d-in" to "d-out"))
        put(profileRow("p-flush", fields = listOf("d-in", "d-drop"), consumables = listOf("Descaler")))

        put(
            scheduleOf(
                "s-flush", assetId = PRED, title = "Flush", timeInterval = 6, timeUnit = RecurrenceUnit.MONTH,
                anchorOn = "2025-03-01", completionMode = CompletionMode.FORM, profileId = "p-flush",
                postponedDueOn = "2026-10-01",
            ),
        )
        put(scheduleOf("s-hours", assetId = PRED, title = "Burner hours", meterDefinitionId = "d-hours", meterInterval = 500.0, anchorMeter = 1_200.0, meterLead = 50.0))
        put(preService("s-pre", PRED))
        put(scheduleOf("s-archived", assetId = PRED, title = "Anode swap", timeInterval = 2, timeUnit = RecurrenceUnit.YEAR, anchorOn = "2025-03-01", status = ScheduleStatus.ARCHIVED))
        raw.events.rows["e-done"] = completionOf("e-done", occurredOn = "2026-03-01", occurrenceOn = "2026-03-01", assetId = PRED, scheduleId = "s-flush")
        raw.closures.rows["c-1"] = closureOf("c-1", occurrenceOn = "2025-09-01", closedOn = "2025-09-10", scheduleId = "s-flush")

        put(
            groupOf(
                "g-north",
                members = listOf(
                    Triple(PRED, "2026-01-01", null),
                    Triple(PUMP, "2026-01-01", null),
                    Triple(OPENER, "2026-01-01", "2026-05-01"),
                    Triple(ANODE, "2026-03-01", null),
                ),
            ),
        )
        put(groupOf("g-south", name = "South run", members = listOf(Triple(PRED, "2026-01-01", null))))

        put(tagRow("TEST-side"))
        put(tagRow("TEST-back", writtenAt = null))
        put(tagRow("TEST-lost", status = TagStatus.LOST))
        put(tagRow("TEST-link", target = TagTarget.LinkTarget(LinkId("link-1"))))

        raw.loans.rows["loan-1"] = loanOf("loan-1", assetId = PRED)
        raw.serviceCases.rows["case-1"] = caseOf("case-1", assetId = PRED)
    }
}
