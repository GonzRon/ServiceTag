package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.journal.SeedTemplates
import com.loosecannon.servicetag.core.model.*
import com.loosecannon.servicetag.core.ports.ByteSource
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.StoreState
import com.loosecannon.servicetag.core.testing.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class EventUseCasesTest {
    private val assets = InMemoryAssetRepository()
    private val defs = InMemoryDefinitionRepository()
    private val profiles = InMemoryProfileRepository()
    private val events = InMemoryEventRepository()
    private val attachments = InMemoryAttachmentRepository()
    private val storage = FakeAttachmentStorage()
    private val uow = FakeUnitOfWork(assets, defs, profiles, events, attachments)
    private var seq = 0
    private val ids = IdGenerator { "id-${++seq}" }
    private var now = 1_000L
    private val clock = Clock { now }

    private val apply = ApplyTemplate(defs, profiles, assets, uow, ids, clock)

    // 1.2: every event write rebuilds the schedules it can affect. This store holds no schedule,
    // so the rebuild is a sweep over nothing — which is the point: the wiring is real, and the
    // journal tests stay tests of the journal.
    private val groups = InMemoryGroupRepository()
    private val closures = InMemoryClosureRepository()
    private val states = InMemoryScheduleStateRepository()
    private val schedules = InMemoryScheduleRepository(closures, states)
    private val recompute = RecomputeSchedules(
        schedules, states, events, closures, groups, assets,
        com.loosecannon.servicetag.core.ports.Today { java.time.LocalDate.parse("2026-02-10") }, clock,
    )
    private val logEvent = LogEvent(events, defs, profiles, assets, uow, ids, clock, recompute)
    private val updateEvent = UpdateEvent(events, defs, profiles, uow, ids, clock, recompute)
    private val deleteEvent = DeleteEvent(events, attachments, storage, uow, recompute)

    private suspend fun asset(id: String, name: String): Asset =
        Asset(id = AssetId(id), name = name, createdAt = now, updatedAt = now).also { assets.upsert(it) }

    private suspend fun seedHotTub(): AssetId {
        val a = asset("a1", "Spa")
        apply.run(a.id, SeedTemplates.byKey("hot_tub")!!)
        return a.id
    }

    private suspend fun seedUps(): AssetId {
        val a = asset("a2", "UPS unit")
        apply.run(a.id, SeedTemplates.byKey("ups")!!)
        return a.id
    }

    private suspend fun seedRoWater(): AssetId {
        val a = asset("a3", "RO unit")
        apply.run(a.id, SeedTemplates.byKey("ro_water")!!)
        return a.id
    }

    private suspend fun defId(assetId: AssetId, key: String): DefinitionId =
        defs.forAsset(assetId).first { it.key == key }.id

    private suspend fun profileId(assetId: AssetId, name: String): ProfileId =
        profiles.forAsset(assetId).first { it.name == name }.id

    private fun cmd(
        assetId: AssetId,
        profileId: ProfileId? = null,
        kind: EventKind = EventKind.MEASUREMENT,
        title: String = "Test",
        occurredOn: String = "2026-09-15",
        occurredTime: String? = null,
        tzId: String = "UTC",
        notes: String = "",
        values: Map<DefinitionId, String> = emptyMap(),
        consumables: List<ConsumableInput> = emptyList(),
    ) = EventCommand(assetId, profileId, kind, title, occurredOn, occurredTime, tzId, notes, values, consumables)

    @Test fun logsAWaterTestWithSnapshotUnitsAndConsumables() = runTest {
        val assetId = seedHotTub()
        val waterTest = profileId(assetId, "Water test")
        val phId = defId(assetId, "ph")
        val clId = defId(assetId, "free_chlorine")

        val event = logEvent.run(
            cmd(
                assetId, profileId = waterTest,
                values = mapOf(phId to "7.8", clId to "0.8"),
                consumables = listOf(ConsumableInput("Chlorine", "1", "oz")),
            ),
        )

        assertEquals(2, event.measurements.size)
        val ph = event.measurements.first { it.definitionId == phId }
        val cl = event.measurements.first { it.definitionId == clId }
        assertEquals(7.8, ph.valueNum); assertEquals("", ph.unit)
        assertEquals(0.8, cl.valueNum); assertEquals("ppm", cl.unit)
        assertEquals(1, event.consumables.size)
        assertEquals(1.0, event.consumables[0].quantity)
        assertEquals(EventSource.MANUAL, event.source)
        assertEquals(now, event.createdAt); assertEquals(now, event.updatedAt)
    }

    @Test fun missingRequiredFieldIsReported() = runTest {
        val assetId = seedHotTub()
        val waterTest = profileId(assetId, "Water test")
        val clId = defId(assetId, "free_chlorine")
        val phId = defId(assetId, "ph")

        val ex = assertFailsWith<EventValidation> {
            logEvent.run(cmd(assetId, profileId = waterTest, values = mapOf(clId to "0.8")))
        }
        assertEquals(listOf(FieldProblem.Required(phId)), ex.problems)
        assertTrue(events.all().isEmpty())
    }

    @Test fun notANumberAndBadDateAreCollectedTogether() = runTest {
        val assetId = seedHotTub()
        val waterTest = profileId(assetId, "Water test")
        val phId = defId(assetId, "ph")
        val clId = defId(assetId, "free_chlorine")

        val ex = assertFailsWith<EventValidation> {
            logEvent.run(
                cmd(
                    assetId, profileId = waterTest, occurredOn = "15/09/2026",
                    values = mapOf(phId to "abc", clId to "1.0"),
                ),
            )
        }
        assertEquals(setOf<FieldProblem>(FieldProblem.NotANumber(phId), FieldProblem.BadDate()), ex.problems.toSet())
        assertEquals(2, ex.problems.size)
        assertTrue(events.all().isEmpty())
    }

    @Test fun nonFiniteNumbersAreNotNumbers() = runTest {
        val assetId = seedHotTub()
        val waterTest = profileId(assetId, "Water test")
        val phId = defId(assetId, "ph")
        val clId = defId(assetId, "free_chlorine")

        for (raw in listOf("NaN", "Infinity", "1e400")) {
            val ex = assertFailsWith<EventValidation> {
                logEvent.run(cmd(assetId, profileId = waterTest, values = mapOf(phId to raw, clId to "1.0")))
            }
            assertTrue(FieldProblem.NotANumber(phId) in ex.problems, "expected NotANumber($phId) for raw=$raw, got ${ex.problems}")
        }

        val ex = assertFailsWith<EventValidation> {
            logEvent.run(
                cmd(
                    assetId, profileId = waterTest,
                    values = mapOf(phId to "7.4", clId to "1.0"),
                    consumables = listOf(ConsumableInput("Chlorine", "Infinity", "oz")),
                ),
            )
        }
        assertTrue(ex.problems.any { it is FieldProblem.BadConsumable }, "expected BadConsumable, got ${ex.problems}")
    }

    @Test fun booleanParsesToZeroOrOne() = runTest {
        val assetId = seedUps()
        val loadTest = profileId(assetId, "Load test")
        val passedId = defId(assetId, "test_passed")

        val trueEvent = logEvent.run(cmd(assetId, profileId = loadTest, values = mapOf(passedId to "true")))
        assertEquals(1.0, trueEvent.measurements.single().valueNum)

        val falseEvent = logEvent.run(cmd(assetId, profileId = loadTest, values = mapOf(passedId to "0")))
        assertEquals(0.0, falseEvent.measurements.single().valueNum)

        val ex = assertFailsWith<EventValidation> {
            logEvent.run(cmd(assetId, profileId = loadTest, values = mapOf(passedId to "maybe")))
        }
        assertEquals(listOf(FieldProblem.NotANumber(passedId)), ex.problems)
    }

    @Test fun blankTitleDefaultsToProfileTitle() = runTest {
        val assetId = seedHotTub()
        val waterTest = profileId(assetId, "Water test")
        val phId = defId(assetId, "ph")
        val clId = defId(assetId, "free_chlorine")

        val event = logEvent.run(
            cmd(assetId, profileId = waterTest, title = "  ", values = mapOf(phId to "7.5", clId to "1.5")),
        )
        assertEquals("Water test", event.title)
    }

    @Test fun updateKeepsIdsAndCreatedAtAndSetsUpdatedAt() = runTest {
        val assetId = seedHotTub()
        val waterTest = profileId(assetId, "Water test")
        val phId = defId(assetId, "ph")
        val clId = defId(assetId, "free_chlorine")

        val logged = logEvent.run(
            cmd(assetId, profileId = waterTest, values = mapOf(phId to "7.4", clId to "1.2")),
        )
        val originalPhMeasurementId = logged.measurements.first { it.definitionId == phId }.id

        now = 2_000L
        val updated = updateEvent.run(
            logged.id,
            cmd(assetId, profileId = waterTest, values = mapOf(phId to "7.5", clId to "1.2")),
        )

        val updatedPh = updated.measurements.first { it.definitionId == phId }
        assertEquals(originalPhMeasurementId, updatedPh.id)
        assertEquals(7.5, updatedPh.valueNum)
        assertEquals(1_000L, updated.createdAt)
        assertEquals(2_000L, updated.updatedAt)
    }

    @Test fun updateMintsIdsForNewChildrenFromTheInjectedGenerator() = runTest {
        val assetId = seedHotTub()
        val treatment = profileId(assetId, "Treatment")
        val phId = defId(assetId, "ph")
        val clId = defId(assetId, "free_chlorine")

        val logged = logEvent.run(cmd(assetId, profileId = treatment, values = mapOf(phId to "7.4")))
        assertEquals(1, logged.measurements.size)
        val phMeasurementId = logged.measurements.single().id

        val updated = updateEvent.run(
            logged.id,
            cmd(
                assetId, profileId = treatment,
                values = mapOf(phId to "7.4", clId to "1.0"),
                consumables = listOf(ConsumableInput("Chlorine", "1", "oz")),
            ),
        )

        val sequentialId = Regex("^id-\\d+$")
        val updatedPh = updated.measurements.first { it.definitionId == phId }
        val newCl = updated.measurements.first { it.definitionId == clId }
        assertEquals(phMeasurementId, updatedPh.id)
        assertTrue(sequentialId.matches(newCl.id), "expected an id from the injected generator, got ${newCl.id}")
        assertNotEquals(phMeasurementId, newCl.id)
        assertEquals(1, updated.consumables.size)
        assertTrue(
            sequentialId.matches(updated.consumables[0].id),
            "expected an id from the injected generator, got ${updated.consumables[0].id}",
        )
    }

    @Test fun updateUnknownEventFails() = runTest {
        val assetId = seedHotTub()
        assertFailsWith<NoSuchEvent> {
            updateEvent.run(EventId("nope"), cmd(assetId, occurredOn = "2026-09-15"))
        }
    }

    @Test fun deleteRemovesTheAggregate() = runTest {
        val assetId = seedHotTub()
        val waterTest = profileId(assetId, "Water test")
        val phId = defId(assetId, "ph")
        val clId = defId(assetId, "free_chlorine")
        val logged = logEvent.run(cmd(assetId, profileId = waterTest, values = mapOf(phId to "7.4", clId to "1.2")))

        deleteEvent.run(logged.id)

        assertNull(events.get(logged.id))
    }

    @Test fun logAgainstUnknownAssetFails() = runTest {
        assertFailsWith<NoSuchAsset> {
            logEvent.run(cmd(AssetId("nope")))
        }
    }

    @Test fun updateCannotMoveAnEventToAnotherAsset() = runTest {
        val hotTubId = seedHotTub()
        val upsId = seedUps()
        val waterTest = profileId(hotTubId, "Water test")
        val phId = defId(hotTubId, "ph")
        val clId = defId(hotTubId, "free_chlorine")
        val logged = logEvent.run(
            cmd(hotTubId, profileId = waterTest, values = mapOf(phId to "7.4", clId to "1.2")),
        )

        assertFailsWith<EventOwnership> {
            updateEvent.run(logged.id, cmd(upsId, values = mapOf(phId to "7.6", clId to "1.2")))
        }
        assertEquals(logged, events.get(logged.id))
    }

    @Test fun profileMustBelongToTheCommandAsset() = runTest {
        val hotTubId = seedHotTub()
        val upsId = seedUps()
        val upsProfile = profileId(upsId, "Load test")

        assertFailsWith<EventOwnership> {
            logEvent.run(cmd(hotTubId, profileId = upsProfile))
        }
        assertTrue(events.all().isEmpty())
    }

    @Test fun definitionMustBelongToTheCommandAsset() = runTest {
        val hotTubId = seedHotTub()
        val upsId = seedUps()
        val batteryId = defId(upsId, "battery_voltage")

        assertFailsWith<EventOwnership> {
            logEvent.run(cmd(hotTubId, values = mapOf(batteryId to "12.5")))
        }
        assertTrue(events.all().isEmpty())
    }

    /**
     * Archiving a required reading takes it off every form, so nothing can satisfy its `required`
     * flag any more — demanding it would make the quick action unsaveable with no way to fix it.
     */
    @Test fun archivedRequiredFieldIsNotRequired() = runTest {
        val assetId = seedRoWater()
        val tdsTest = profileId(assetId, "TDS test")
        val prefilter = defId(assetId, "tds_prefilter")
        val post = defId(assetId, "tds_post_membrane")
        val output = defId(assetId, "tds_output")
        ArchiveDefinition(defs, uow, clock).run(prefilter, archived = true)

        val event = logEvent.run(
            cmd(assetId, profileId = tdsTest, values = mapOf(post to "12", output to "8")),
        )

        assertEquals(listOf(post, output), event.measurements.map { it.definitionId })
        assertEquals(event, events.get(event.id))
    }

    /** A derived definition is computed, never typed: a value posted for one is simply ignored. */
    @Test fun derivedDefinitionIsNeverAMeasurement() = runTest {
        val assetId = seedRoWater()
        val tdsTest = profileId(assetId, "TDS test")
        val rejection = defId(assetId, "rejection_percent")

        val event = logEvent.run(
            cmd(
                assetId, profileId = tdsTest,
                values = mapOf(
                    defId(assetId, "tds_prefilter") to "100",
                    defId(assetId, "tds_post_membrane") to "20",
                    defId(assetId, "tds_output") to "8",
                    rejection to "80",
                ),
            ),
        )

        assertEquals(3, event.measurements.size)
        assertNull(event.measurements.firstOrNull { it.definitionId == rejection })
    }

    @Test fun deletingAnEventRemovesItsAttachmentBytes() = runTest {
        // This file's seed helpers (`seedHotTub()` etc.) generate ids, so seed explicit ids here:
        val assetId = seedHotTub()
        events.upsert(
            AssetEvent(
                id = EventId("e1"), assetId = assetId, kind = EventKind.MAINTENANCE,
                title = "Filter change", profileId = null, occurredOn = "2026-09-15",
                occurredTime = null, tzId = "UTC", notes = "", source = EventSource.MANUAL,
                sourceRef = null, createdAt = 1L, updatedAt = 1L,
                measurements = emptyList(), consumables = emptyList(),
            ),
        )
        attachments.upsert(
            Attachment(
                id = AttachmentId("att-1"), owner = AttachmentOwner.OfEvent(EventId("e1")),
                kind = AttachmentKind.PHOTO, displayName = "before.jpg", mimeType = "image/jpeg",
                sizeBytes = 3L, sha256 = "0".repeat(64), storageLocator = "events/e1/att-1.jpg",
                capturedOn = null, createdAt = 1L, updatedAt = 1L,
            ),
        )
        // a row on a second event, which this delete must not touch
        events.upsert(
            AssetEvent(
                id = EventId("e2"), assetId = assetId, kind = EventKind.MAINTENANCE,
                title = "Drain", profileId = null, occurredOn = "2026-09-16",
                occurredTime = null, tzId = "UTC", notes = "", source = EventSource.MANUAL,
                sourceRef = null, createdAt = 1L, updatedAt = 1L,
                measurements = emptyList(), consumables = emptyList(),
            ),
        )
        attachments.upsert(
            Attachment(
                id = AttachmentId("att-2"), owner = AttachmentOwner.OfEvent(EventId("e2")),
                kind = AttachmentKind.PHOTO, displayName = "after.jpg", mimeType = "image/jpeg",
                sizeBytes = 3L, sha256 = "0".repeat(64), storageLocator = "events/e2/att-2.jpg",
                capturedOn = null, createdAt = 1L, updatedAt = 1L,
            ),
        )
        storage.store.put("events/e1/att-1.jpg", ByteSource { "abc".toByteArray().inputStream() })
        storage.store.put("events/e2/att-2.jpg", ByteSource { "abc".toByteArray().inputStream() })

        deleteEvent.run(EventId("e1"))

        assertFalse(storage.store.exists("events/e1/att-1.jpg"))
        assertTrue(storage.store.exists("events/e2/att-2.jpg"))
        assertEquals(1, storage.store.deletes)
        assertNull(events.rows["e1"])
        assertNotNull(events.rows["e2"])
    }

    @Test fun anAbsentStoreIsNotAReasonToKeepTheEvent() = runTest {
        val assetId = seedHotTub()
        events.upsert(
            AssetEvent(
                id = EventId("e1"), assetId = assetId, kind = EventKind.MAINTENANCE,
                title = "Filter change", profileId = null, occurredOn = "2026-09-15",
                occurredTime = null, tzId = "UTC", notes = "", source = EventSource.MANUAL,
                sourceRef = null, createdAt = 1L, updatedAt = 1L,
                measurements = emptyList(), consumables = emptyList(),
            ),
        )
        attachments.upsert(
            Attachment(
                id = AttachmentId("att-1"), owner = AttachmentOwner.OfEvent(EventId("e1")),
                kind = AttachmentKind.PHOTO, displayName = "before.jpg", mimeType = "image/jpeg",
                sizeBytes = 3L, sha256 = "0".repeat(64), storageLocator = "events/e1/att-1.jpg",
                capturedOn = null, createdAt = 1L, updatedAt = 1L,
            ),
        )
        storage.store.put("events/e1/att-1.jpg", ByteSource { "abc".toByteArray().inputStream() })
        storage.state = StoreState.AccessLost("Attachments")

        deleteEvent.run(EventId("e1"))

        assertNull(events.rows["e1"])
        assertEquals(0, storage.store.deletes)
        assertTrue(storage.store.exists("events/e1/att-1.jpg"))   // an orphan for 4B to sweep
    }
}
