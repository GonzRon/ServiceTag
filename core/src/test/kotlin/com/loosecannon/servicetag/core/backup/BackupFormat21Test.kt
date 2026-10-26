package com.loosecannon.servicetag.core.backup

import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleRole
import com.loosecannon.servicetag.core.testing.archiveOf
import com.loosecannon.servicetag.core.testing.completionOf
import com.loosecannon.servicetag.core.testing.dataTreeOf
import com.loosecannon.servicetag.core.testing.editRows
import com.loosecannon.servicetag.core.testing.plainAssetOf
import com.loosecannon.servicetag.core.testing.scheduleOf
import com.loosecannon.servicetag.core.testing.sealed
import com.loosecannon.servicetag.core.testing.with
import com.loosecannon.servicetag.core.testing.without
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Backup format 21 (#114, A3 row 46; C18, G6, B): every `maintenanceSchedules[]` row appends `role` and `roleSinceOn`,
 * every `assetEvents[]` row appends `imported`, all explicit. No shipped writer put any of them into a format ≤20
 * archive, so a role, a boundary or a `true` marker there was built by hand and is refused; the defaults there, or no
 * key at all, decode as no role and no marker. The names are fictional.
 */
class BackupFormat21Test {

    private val heater = plainAssetOf("heater", "Example Heater")

    private val annual: MaintenanceSchedule = scheduleOf(
        id = "s-role", assetId = "heater", title = "Example annual service", timeInterval = 1,
        timeUnit = RecurrenceUnit.YEAR, anchorOn = "2025-10-01",
    ).copy(role = ScheduleRole.SEASON_START, roleSinceOn = "2025-10-01")

    private val filter = scheduleOf(
        id = "s-plain", assetId = "heater", title = "Example filter check", timeInterval = 3,
        timeUnit = RecurrenceUnit.MONTH, anchorOn = "2025-10-01",
    )

    private val ownService = completionOf("e-own", "2025-10-05", "2025-10-01", assetId = "heater", scheduleId = "s-role")
    private val packService = completionOf("e-pack", "2025-11-05", "2026-01-01", assetId = "heater", scheduleId = "s-plain")
        .copy(imported = true)

    private fun data(): BackupData = BackupData(
        listOf(heater.toDto()), emptyList(), emptyList(),
        maintenanceSchedules = listOf(annual.toDto(), filter.toDto()),
        assetEvents = listOf(ownService.toDto(), packService.toDto()),
    )

    private fun JsonObject.editRow(list: String, id: String, edit: (JsonObject) -> JsonObject): JsonObject =
        editRows(list) { row -> if (row["id"]?.jsonPrimitive?.content == id) edit(row) else row }

    private fun refusalOf(bytes: ByteArray): String = assertFailsWith<BackupCorrupt> { BackupCodec.decode(bytes) }.message!!

    /** The three new keys, stripped from every row: what a format-20 writer put out. */
    private fun format20Tree(): JsonObject = dataTreeOf(archiveOf(data()))
        .editRows("maintenanceSchedules") { it.without("role", "roleSinceOn") }
        .editRows("assetEvents") { it.without("imported") }

    @Test
    fun theRoleRoundTrips() {
        val bytes = archiveOf(data())
        val tree = dataTreeOf(bytes)

        val decoded = BackupCodec.decode(bytes)

        assertEquals(21, decoded.manifest.formatVersion)
        assertEquals(setOf(annual, filter), decoded.data.maintenanceSchedules.map { it.toDomain() }.toSet())
        // Explicit values on every row: the role's name and date, and the plain row's NONE and null.
        val plain = (tree["maintenanceSchedules"] as kotlinx.serialization.json.JsonArray)
            .map { it as JsonObject }.single { it["id"]!!.jsonPrimitive.content == "s-plain" }
        assertEquals(JsonPrimitive("NONE"), plain["role"])
        assertEquals(JsonNull, plain["roleSinceOn"])
        val role = (tree["maintenanceSchedules"] as kotlinx.serialization.json.JsonArray)
            .map { it as JsonObject }.single { it["id"]!!.jsonPrimitive.content == "s-role" }
        assertEquals(JsonPrimitive("SEASON_START"), role["role"])
        assertEquals(JsonPrimitive("2025-10-01"), role["roleSinceOn"])
        assertEquals(listOf("providers", "role", "roleSinceOn"), role.keys.toList().takeLast(3))
    }

    @Test
    fun aFormat20ArchiveDecodesAsNone() {
        val decoded = BackupCodec.decode(sealed(format20Tree(), formatVersion = 20))

        assertEquals(listOf(ScheduleRole.NONE, ScheduleRole.NONE), decoded.data.maintenanceSchedules.map { it.toDomain().role })
        assertTrue(decoded.data.maintenanceSchedules.all { it.roleSinceOn == null })
        assertTrue(decoded.data.assetEvents.none { it.toDomain().imported })
        // The explicit defaults are what this build reads anyway.
        val explicit = format20Tree()
            .editRows("maintenanceSchedules") { it.with("role", JsonPrimitive("NONE")).with("roleSinceOn", JsonNull) }
            .editRows("assetEvents") { it.with("imported", JsonPrimitive(false)) }
        assertEquals(decoded.data, BackupCodec.decode(sealed(explicit, formatVersion = 20)).data)
    }

    @Test
    fun aFormat20ArchiveCarryingARoleIsRefused() {
        val withRole = format20Tree().editRow("maintenanceSchedules", "s-role") {
            it.with("role", JsonPrimitive("SEASON_START")).with("roleSinceOn", JsonPrimitive("2025-10-01"))
        }
        val boundaryOnly = format20Tree().editRow("maintenanceSchedules", "s-plain") {
            it.with("roleSinceOn", JsonPrimitive("2025-10-01"))
        }

        for (tree in listOf(withRole, boundaryOnly)) {
            val message = refusalOf(sealed(tree, formatVersion = 20))
            assertTrue("maintenanceSchedules: a format 20 archive cannot carry a schedule role" in message, message)
        }
        assertTrue(refusalOf(sealed(withRole, formatVersion = 20)).endsWith("(schedule s-role)"))
        assertTrue(refusalOf(sealed(boundaryOnly, formatVersion = 20)).endsWith("(schedule s-plain)"))
        // The same rows at format 21 are what this build writes.
        BackupCodec.decode(sealed(withRole, formatVersion = 21))
    }

    @Test
    fun anUnknownRoleOrABrokenR1IsCorrupt() {
        val tree = dataTreeOf(archiveOf(data()))
        val broken = mapOf(
            "an unknown role" to tree.editRow("maintenanceSchedules", "s-role") { it.with("role", JsonPrimitive("OPENING")) },
            "a role with no boundary" to tree.editRow("maintenanceSchedules", "s-role") { it.with("roleSinceOn", JsonNull) },
            "a boundary with no role" to tree.editRow("maintenanceSchedules", "s-plain") {
                it.with("roleSinceOn", JsonPrimitive("2025-10-01"))
            },
            "a boundary that is no date" to tree.editRow("maintenanceSchedules", "s-role") {
                it.with("roleSinceOn", JsonPrimitive("1 October"))
            },
        )

        for ((case, edited) in broken) {
            val message = refusalOf(sealed(edited, formatVersion = 21))
            assertTrue("schedule s-role" in message || "schedule s-plain" in message, "$case: $message")
        }
    }

    @Test
    fun aFormat22ArchiveIsNewer() {
        val unreadable = dataTreeOf(archiveOf(data()))
            .editRow("maintenanceSchedules", "s-role") { it.with("role", JsonObject(mapOf("name" to JsonPrimitive("x")))) }

        val refusal = assertFailsWith<BackupNewerFormat> { BackupCodec.decode(sealed(unreadable, formatVersion = 22)) }

        assertEquals(22, refusal.found)
        assertEquals(21, refusal.supported)
        // The same tree at this build's format is parsed — and refused as corrupt.
        assertFailsWith<BackupCorrupt> { BackupCodec.decode(sealed(unreadable, formatVersion = 21)) }
    }

    @Test
    fun theEventsImportedMarkerRoundTrips() {
        val bytes = archiveOf(data())
        val events = (dataTreeOf(bytes)["assetEvents"] as kotlinx.serialization.json.JsonArray).map { it as JsonObject }

        val decoded = BackupCodec.decode(bytes).data.assetEvents.map { it.toDomain() }

        assertEquals(setOf(ownService, packService), decoded.toSet())
        assertEquals(JsonPrimitive(false), events.single { it["id"]!!.jsonPrimitive.content == "e-own" }["imported"])
        assertEquals(JsonPrimitive(true), events.single { it["id"]!!.jsonPrimitive.content == "e-pack" }["imported"])
        assertEquals("imported", events.first().keys.last())
    }

    @Test
    fun aFormat20EventMarkedImportedIsRefused() {
        val marked = format20Tree().editRow("assetEvents", "e-pack") { it.with("imported", JsonPrimitive(true)) }

        val message = refusalOf(sealed(marked, formatVersion = 20))

        assertEquals("assetEvents: a format 20 archive cannot carry an imported marker (event e-pack)", message)
        assertFalse(BackupCodec.decode(sealed(format20Tree(), formatVersion = 20)).data.assetEvents.any { it.imported })
    }
}
