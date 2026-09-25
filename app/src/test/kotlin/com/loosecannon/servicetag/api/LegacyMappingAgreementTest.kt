package com.loosecannon.servicetag.api

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.loosecannon.servicetag.core.backup.BackupCodec
import com.loosecannon.servicetag.core.backup.BackupManifest
import com.loosecannon.servicetag.data.room.createSchemaVersion
import com.loosecannon.servicetag.data.room.openMigrated
import com.loosecannon.servicetag.data.room.withConnection
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.dayMillis
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Spec §4.1, "one test proves all four agree" (master plan §4; inv. 125's agreement half, 128):
 * every case of the committed golden file `docs/api/legacy-season-mapping.json` — written from the
 * spec's table, not from the code — travels the **three Kotlin paths and the API's legacy form** in
 * one test and lands on the **same policy and offset**:
 *
 * 1. `MIGRATION_7_8`, on a v7 `maintenance_schedule` row;
 * 2. the format ≤7 decoder, on a format-7 archive's schedule;
 * 3. `POST /v1/schedules` in the legacy form.
 *
 * The MCP never translates — it forwards its deprecated arguments in a legacy-form body — so path 3
 * is its path too (B11's pytest proves the forwarding). A case whose `seasonBehavior` is absent is
 * not a row a v7 file can hold (`season_behavior` was NOT NULL), so it travels paths 2 and 3 only;
 * every other case travels all three.
 */
class LegacyMappingAgreementTest {

    private val graph = FakeGraph().apply {
        now = dayMillis("2026-02-01")
        today = LocalDate.parse("2026-02-10")
    }
    private val api = V1Client(graph)

    @After fun close() = graph.close()

    private data class Case(
        val behavior: String?,
        val reentry: String?,
        val offset: Int?,
        val hasTimeRule: Boolean,
        val policy: String,
        val policyOffset: Int?,
    ) {
        /** Where the three paths agree: the file's own expectation. */
        val expected: Pair<String, Int?> get() = policy to policyOffset
    }

    @Test fun everyGoldenCaseAgreesAcrossMigrationDecoderAndApi() {
        val cases = goldenCases()
        assertTrue("the golden file lost its cases: ${cases.size}", cases.size >= 20)
        assertTrue("an absent-behaviour case", cases.any { it.behavior == null })
        assertTrue("an MM-DD re-entry", cases.any { it.reentry?.matches(Regex("\\d\\d-\\d\\d")) == true })

        val migrated = throughTheMigration(cases.filter { it.behavior != null })
        val decoded = throughTheDecoder(cases)
        val posted = throughTheApi(cases)

        var three = 0
        cases.forEachIndexed { i, case ->
            assertEquals("decoder: $case", case.expected, decoded[i])
            assertEquals("api: $case", case.expected, posted[i])
            migrated[i]?.let {
                assertEquals("migration: $case", case.expected, it)
                three++
            }
        }
        assertEquals("every case a v7 row can hold went through the migration", cases.count { it.behavior != null }, three)
    }

    // --- path 1: MIGRATION_7_8 on a v7 row --------------------------------------------------------

    /** Each case as a v7 schedule row `g<index>`, migrated by opening the file through Room. */
    private fun throughTheMigration(cases: List<Case>): Map<Int, Pair<String, Int?>> {
        val all = goldenCases()
        val dir = File("build/tmp/legacy-mapping-agreement").apply { mkdirs() }
        val file = File(dir, "v7-${System.nanoTime()}.db")
        try {
            createSchemaVersion(7, file) { c ->
                c.execSQL(
                    "INSERT INTO asset (id,name,description,category,notes,status,template_key,created_at," +
                        "updated_at,manufacturer,model,serial_number,purchase_on,in_service_on," +
                        "purchase_price_minor,currency,vendor,location,warranty_expires_on,warranty_notes," +
                        "retired_on,parent_asset_id,season_start_mmdd,season_end_mmdd) VALUES " +
                        "('a-snow','Snowblower','','Yard','','ACTIVE',NULL,1000,1100,'','','',NULL,NULL,NULL," +
                        "NULL,'','',NULL,'',NULL,NULL,'11-01','03-31')",
                )
                c.execSQL(
                    "INSERT INTO measurement_definition VALUES ('d-hours','a-snow','engine_hours','Engine hours'," +
                        "'h','NUMBER',1,NULL,NULL,1,0,NULL,1,2,'ENTERED',NULL,NULL,NULL)",
                )
                all.forEachIndexed { i, case -> if (case in cases) insertV7Schedule(c, "g$i", case) }
            }
            openMigrated(file).let { db ->
                try {
                    runBlocking { db.maintenanceScheduleDao().all() }
                } finally {
                    db.close()
                }
            }
            return withConnection(file) { c ->
                buildMap {
                    c.prepare("SELECT id, service_policy, policy_offset_days FROM maintenance_schedule").use { s ->
                        while (s.step()) {
                            val offset = if (s.isNull(2)) null else s.getLong(2).toInt()
                            put(s.getText(0).removePrefix("g").toInt(), s.getText(1) to offset)
                        }
                    }
                }
            }
        } finally {
            file.delete()
        }
    }

    private fun insertV7Schedule(c: SQLiteConnection, id: String, case: Case) {
        fun q(v: String?) = v?.let { "'$it'" } ?: "NULL"
        val time = if (case.hasTimeRule) "6,'MONTH','FIXED','2026-01-15'" else "NULL,NULL,'FIXED',NULL"
        val meter = if (case.hasTimeRule) "NULL,NULL,NULL,NULL" else "'d-hours',25.0,10.0,2.5"
        c.execSQL(
            "INSERT INTO maintenance_schedule (id,asset_id,group_id,title,description,time_interval," +
                "time_unit,time_basis,anchor_on,lead_days,meter_definition_id,meter_interval," +
                "anchor_meter,meter_lead,season_behavior,season_reentry,season_reentry_offset_days," +
                "completion_mode,profile_id,reminders_enabled,status,postponed_due_on,created_at," +
                "updated_at) VALUES ('$id','a-snow',NULL,'Job $id','',$time,7,$meter,'${case.behavior}'," +
                "${q(case.reentry)},${case.offset ?: "NULL"},'QUICK',NULL,1,'ACTIVE',NULL,1,100)",
        )
    }

    // --- path 2: the format ≤7 decoder --------------------------------------------------------

    /**
     * Each case as the schedule of a format-7 archive: a format-8 export of a real asset and its two
     * schedules (a time rule and a meter-only one), taken back to 1.3's shape — the three 1.4 lists
     * gone, the asset's five 1.4 fields gone, each schedule's policy, offset and rule floor replaced
     * by the case's triple (an absent behaviour as an absent key) — and resealed as format 7.
     */
    private fun throughTheDecoder(cases: List<Case>): List<Pair<String, Int?>> {
        val donor = FakeGraph().apply { now = dayMillis("2026-01-01") }
        try {
            val donorApi = V1Client(donor)
            val asset = donorApi.asset("Snowblower")
            val meter = meterOn(donorApi, asset)
            val time = donorApi.ok(ScheduleResponse.serializer(), "POST", "/v1/schedules", timeBody(asset, "Belt"), status = 201).schedule.id
            val meterOnly = donorApi.ok(ScheduleResponse.serializer(), "POST", "/v1/schedules", meterBody(asset, meter, "Oil"), status = 201).schedule.id
            val tree = dataTree(runBlocking { donor.exportBackupSet.run().data })

            return cases.map { case ->
                val archive = sealed(asFormat7(tree, case))
                val rows = BackupCodec.decode(archive).data.maintenanceSchedules.associateBy { it.id }
                val row = rows.getValue(if (case.hasTimeRule) time else meterOnly)
                row.servicePolicy to row.policyOffsetDays
            }
        } finally {
            donor.close()
        }
    }

    private fun asFormat7(tree: JsonObject, case: Case): JsonObject {
        fun JsonObject.without(vararg keys: String) = JsonObject(filterKeys { it !in keys })
        fun JsonObject.rows(table: String, edit: (JsonObject) -> JsonObject) =
            JsonObject(this + (table to JsonArray(getValue(table).jsonArray.map { edit(it.jsonObject) })))
        return tree.without("seasonActivations", "assetConditions", "healthSubjects")
            .rows("assets") {
                it.without("seasonMode", "blackoutStartMmdd", "blackoutEndMmdd", "healthAggregation", "healthPrimarySubjectId")
            }
            .rows("maintenanceSchedules") { row ->
                val legacy = row.without("servicePolicy", "policyOffsetDays", "ruleChangedAt") +
                    listOfNotNull(
                        case.behavior?.let { "seasonBehavior" to JsonPrimitive(it) },
                        "seasonReentry" to (case.reentry?.let(::JsonPrimitive) ?: JsonNull),
                        "seasonReentryOffsetDays" to (case.offset?.let(::JsonPrimitive) ?: JsonNull),
                    )
                JsonObject(legacy)
            }
    }

    private fun dataTree(archive: ByteArray): JsonObject {
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == BackupCodec.DATA_ENTRY) {
                    return Json.parseToJsonElement(zip.readBytes().decodeToString()).jsonObject
                }
            }
        }
        error("no ${BackupCodec.DATA_ENTRY} in the export")
    }

    /** [tree] as a 1.3 writer would have sealed it: format 7, with a manifest whose hash is the data's. */
    private fun sealed(tree: JsonObject): ByteArray {
        val data = tree.toString().toByteArray(Charsets.UTF_8)
        val sha = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        val manifest = BackupManifest(
            formatVersion = 7, appVersion = "1.3.0", schemaVersion = 7, createdAt = dayMillis("2026-01-20"),
            counts = emptyMap(), dataSha256 = sha, backupSetId = "set-agreement",
        )
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in listOf(
                BackupCodec.MANIFEST_ENTRY to Json.encodeToString(BackupManifest.serializer(), manifest).toByteArray(),
                BackupCodec.DATA_ENTRY to data,
            )) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    // --- path 3: the API's legacy form ----------------------------------------------------------

    /** Each case as a legacy-form create — only legacy keys, an absent behaviour as an absent key. */
    private fun throughTheApi(cases: List<Case>): List<Pair<String, Int?>> {
        val asset = api.asset("Snowblower")
        val meter = meterOn(api, asset)
        return cases.mapIndexed { i, case ->
            val base = if (case.hasTimeRule) timeBody(asset, "Job $i") else meterBody(asset, meter, "Job $i")
            val legacy = JsonObject(
                ApiJson.parseToJsonElement(base).jsonObject +
                    listOfNotNull(
                        case.behavior?.let { "seasonBehavior" to JsonPrimitive(it) },
                        "seasonReentry" to (case.reentry?.let(::JsonPrimitive) ?: JsonNull),
                        "seasonReentryOffsetDays" to (case.offset?.let(::JsonPrimitive) ?: JsonNull),
                    ),
            )
            val row = api.ok(ScheduleResponse.serializer(), "POST", "/v1/schedules", legacy.toString(), status = 201).schedule
            row.servicePolicy to row.policyOffsetDays
        }
    }

    // --- fixtures -----------------------------------------------------------------------------

    private fun meterOn(client: V1Client, asset: String): String = client.ok(
        DefinitionResponse.serializer(), "POST", "/v1/definitions",
        """{"assetId":"$asset","label":"Engine hours","unit":"h","isMeter":true}""",
    ).definition.id

    private fun timeBody(asset: String, title: String) =
        """{"title":"$title","targetAssetId":"$asset","timeInterval":6,"timeUnit":"MONTH","timeBasis":"FIXED","anchorOn":"2026-01-15"}"""

    private fun meterBody(asset: String, meter: String, title: String) =
        """{"title":"$title","targetAssetId":"$asset","meterDefinitionId":"$meter","meterInterval":25.0,"anchorMeter":10.0}"""

    private fun goldenCases(): List<Case> {
        fun JsonElement.text() = if (this is JsonNull) null else jsonPrimitive.contentOrNull
        fun JsonElement.int() = if (this is JsonNull) null else jsonPrimitive.intOrNull
        return Json.parseToJsonElement(repoFile("docs/api/legacy-season-mapping.json").readText()).jsonArray.map {
            val row = it.jsonObject
            Case(
                behavior = row.getValue("seasonBehavior").text(),
                reentry = row.getValue("seasonReentry").text(),
                offset = row.getValue("seasonReentryOffsetDays").int(),
                hasTimeRule = checkNotNull(row.getValue("hasTimeRule").jsonPrimitive.booleanOrNull),
                policy = checkNotNull(row.getValue("servicePolicy").text()),
                policyOffset = row.getValue("policyOffsetDays").int(),
            )
        }
    }
}
