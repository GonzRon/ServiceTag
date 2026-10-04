package com.loosecannon.servicetag

import com.loosecannon.servicetag.api.ApiHandlers
import com.loosecannon.servicetag.api.ApiJson
import com.loosecannon.servicetag.api.ApiRequest
import com.loosecannon.servicetag.api.ApiRouter
import com.loosecannon.servicetag.api.StatusResponse
import com.loosecannon.servicetag.api.attachmentHandlersFor
import com.loosecannon.servicetag.api.replaceHandlersFor
import com.loosecannon.servicetag.api.loanHandlersFor
import com.loosecannon.servicetag.api.maintenanceHandlersFor
import com.loosecannon.servicetag.api.referenceHandlersFor
import com.loosecannon.servicetag.api.seasonHealthHandlersFor
import com.loosecannon.servicetag.api.serviceCaseHandlersFor
import com.loosecannon.servicetag.api.installedComponentHandlersFor
import com.loosecannon.servicetag.api.supplyHandlersFor
import com.loosecannon.servicetag.api.warrantyHandlersFor
import com.loosecannon.servicetag.core.backup.BackupCodec
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.testing.FakeGraph
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The release's numbers, asserted where they can fail in CI instead of in a review's eyes.
 *
 * A release carries numbers that have to agree — the `versionName`, the `versionCode`, the Room
 * schema version and the backup format version, which `/v1/status` echoes — and they live in four
 * different files. A schema
 * bumped in one place and not the other makes an import refuse an archive it could read, or accept
 * one it cannot; a `versionName` that disagrees with the tag makes the release workflow refuse to
 * publish, which is the right failure but a late one. **Change any one of them in one place only
 * and a case here goes red.**
 *
 * The document cases are the same idea applied to prose. A design document that contradicts the
 * engine is what produced 1.2's first blocking finding, so the corrections B13 made are asserted
 * structurally rather than trusted: every pattern is **anchored**, so a comment or an amendment
 * note that merely *names* one can never satisfy it.
 *
 * Nothing here needs a device, a store or a clock.
 */
class VersionAgreementTest {

    private val graph = FakeGraph()

    @After fun close() = graph.close()

    // --- the four numbers ------------------------------------------------------------------

    /**
     * The release identity. Both values come from `app/build.gradle.kts` through the generated
     * `BuildConfig`, and the tag the release workflow checks the APK against is built from the
     * first of them.
     */
    @Test fun theReleaseIdentityIs170AndCode20() {
        assertEquals("1.7.0", BuildConfig.VERSION_NAME)
        assertEquals(20, BuildConfig.VERSION_CODE)
        assertEquals("servicetag-v1.7.0", "servicetag-v${BuildConfig.VERSION_NAME}")
    }

    /**
     * The schema and format numbers. 1.3 bumped both together; 1.4 moved them in two steps — schema
     * 8 landed first, with the migration, and format 8 followed with the archive that carries the
     * new tables. #74 moves them in two steps again: schema 9 (`asset_category` and its backfill)
     * lands first, and format 9 follows with the archive that carries the categories. #67 does the
     * same: schema 10 (`attachment.document_role`) lands first and format 10 follows with the archive
     * that carries the role. #79 does the same again: schema 11 (the warranty reminder's lead and the
     * device-local deadline table) lands first and format 11 follows with the archive that carries the
     * lead. #79b does it once more: schema 12 (the service case tables) lands first and format 12
     * follows with the archive that carries them. #72 does it again: schema 13 (the loan table) lands
     * first and format 13 follows with the archive that carries the loans. #77 moves both in one brief:
     * schema 14 (the transfer record table) and format 14 (the archive that carries the records) — so at
     * this tip the schema is 14 and the format is 14. #16 moves the schema alone: schema 21 adds two
     * device-local tables that no archive carries, so the format stays 20.
     *
     * The expected numbers are this test's own, deliberately: they are what the schema and the
     * format are at this tip, and they move only when a release changes them. They are **not**
     * derived from the versioning row of whatever `versionName` currently reads, which the
     * `versionName`/`versionCode` cases own — otherwise bumping the schema in one file only would
     * still pass here, which is the whole failure this class exists to catch.
     */
    @Test fun theSchemaIsTwentyOneAndTheFormatIsTwenty() {
        assertEquals(21, AppGraph.SCHEMA_VERSION)
        assertEquals(20, BackupCodec.FORMAT_VERSION)
    }

    /**
     * `AppDatabase`'s own `version`, which `AppGraph.SCHEMA_VERSION` only *claims* to match.
     * `@Database` has CLASS retention, so there is no runtime field to read; the two things that
     * do carry the compiled value are the annotation in the source and the schema Room exported
     * from it. Asserting both, against `SCHEMA_VERSION`, closes the loop: bump the annotation
     * alone and the exported schema is missing; bump `SCHEMA_VERSION` alone and both disagree.
     */
    @Test fun theRoomDatabaseCarriesTheSameVersionAsTheGraphConstant() {
        val source = repoFile("app/src/main/kotlin/com/loosecannon/servicetag/data/room/AppDatabase.kt")
            .readText()
        assertTrue(
            "AppDatabase must declare version = ${AppGraph.SCHEMA_VERSION}",
            Regex("""^\s*version = ${AppGraph.SCHEMA_VERSION},$""", RegexOption.MULTILINE)
                .containsMatchIn(source),
        )
        val exported = repoFile(
            "app/schemas/com.loosecannon.servicetag.data.room.AppDatabase/" +
                "${AppGraph.SCHEMA_VERSION}.json",
        )
        assertTrue("the exported schema ${exported.name} must exist", exported.isFile)
        assertTrue(
            "the exported schema must declare version ${AppGraph.SCHEMA_VERSION}",
            Regex(""""version":\s*${AppGraph.SCHEMA_VERSION}\b""").containsMatchIn(exported.readText()),
        )
    }

    /**
     * `GET /v1/status` echoes the schema and the format, so a workstation can tell what it is
     * talking to before it sends anything. The handlers are built with the **production**
     * constants, and the route is the production route.
     */
    @Test fun statusEchoesTheVersionsTheBuildCarries() {
        val router = ApiRouter(
            ApiHandlers(
                graph.assets, graph.tags, graph.links, graph.definitions, graph.profiles,
                graph.events, graph.attachments, graph.categories, graph.transferRecords, graph.assetSuccessions,
                graph.createAsset, graph.updateAsset, graph.retireAsset, graph.archiveAsset,
                graph.saveDefinition, graph.archiveDefinition, graph.saveProfile,
                graph.archiveProfile,
                graph.logEvent, graph.updateEvent, graph.deleteEvent, graph.importBackupMerge,
                maintenanceHandlersFor(graph),
                referenceHandlersFor(graph),
                seasonHealthHandlersFor(graph),
                warrantyHandlersFor(graph),
                serviceCaseHandlersFor(graph),
                loanHandlersFor(graph),
                attachmentHandlersFor(graph),
                replaceHandlersFor(graph),
                supplyHandlersFor(graph),
                installedComponentHandlersFor(graph),
                appVersion = BuildConfig.VERSION_NAME,
                schemaVersion = AppGraph.SCHEMA_VERSION,
            ),
            TOKEN,
        )
        val response = router.handle(
            ApiRequest(
                "GET", "/v1/status",
                mapOf("host" to "127.0.0.1", "authorization" to "Bearer $TOKEN"),
                ByteArray(0),
            ),
        )
        assertEquals(200, response.status)
        val status = ApiJson.decodeFromString(
            StatusResponse.serializer(), response.body.decodeToString(),
        )
        assertEquals("1.7.0", status.appVersion)
        assertEquals(21, status.schemaVersion)
        assertEquals(20, status.backupFormatVersion)
    }

    /**
     * The production wiring, which the case above can only exercise by injecting the same two
     * constants by hand: the `AppGraph` constructor has to pass *these* two and no others, or the
     * route would echo whatever the injection happened to say.
     */
    @Test fun theProductionHandlersAreWiredToTheBuildsOwnConstants() {
        val source = repoFile("app/src/main/kotlin/com/loosecannon/servicetag/api/ApiHandlers.kt")
            .readText()
        assertTrue(
            "the AppGraph constructor must pass BuildConfig.VERSION_NAME and AppGraph.SCHEMA_VERSION",
            Regex("""^\s*BuildConfig\.VERSION_NAME,\s*AppGraph\.SCHEMA_VERSION,$""", RegexOption.MULTILINE)
                .containsMatchIn(source),
        )
        assertTrue(
            "status() must report BackupCodec.FORMAT_VERSION",
            Regex("""^\s*backupFormatVersion = BackupCodec\.FORMAT_VERSION,$""", RegexOption.MULTILINE)
                .containsMatchIn(source),
        )
    }

    // --- the documents ---------------------------------------------------------------------

    /**
     * `docs/versioning.md`: the struck reservation, the new row and the clarification D-1 and D-2
     * require. Two rows claiming `versionCode` 13 is how the next release comes to have no
     * number, so the absence is asserted as hard as the presence.
     */
    @Test fun versioningRecordsThisReleaseAndNoLongerReservesItsCode() {
        val text = repoFile("docs/versioning.md").readText()
        assertFalse(
            "the 1.1.1 / code 13 reservation row must be struck, not amended",
            Regex("""^\|[^|\n]*\|[^|\n]*\|\s*1\.1\.1\s*\|""", RegexOption.MULTILINE)
                .containsMatchIn(text),
        )
        assertTrue(
            "the supported release history must carry a 1.3.0 / 15 row",
            Regex("""^\|\s*1\.3\.0\s*\|\s*15\s*\|""", RegexOption.MULTILINE).containsMatchIn(text),
        )
        assertTrue(
            "the forward-only clarification must be present verbatim",
            text.contains(
                "A **forward-only** backup-format bump — where the new app reads every older " +
                    "archive and an older app safely refuses a newer one rather than dropping " +
                    "rows — is a **MINOR**. A change that makes the app unable to read data it " +
                    "previously could is a **MAJOR**.",
            ),
        )
        assertTrue(
            "the row must name the schema and the format it ships",
            Regex("""^\|\s*1\.3\.0\s*\|\s*15\s*\|.*schema \*\*7\*\*.*format \*\*7\*\*""", RegexOption.MULTILINE)
                .containsMatchIn(text),
        )
        assertFalse(
            "the row carries no placeholder",
            Regex("""^\|\s*1\.3\.0\s*\|\s*15\s*\|.*PLACEHOLDER""", RegexOption.MULTILINE)
                .containsMatchIn(text),
        )
    }

    /**
     * The 1.4.0 / 16 row. Its reason for being a MINOR is the forward-only rule above, so the row
     * has to say the three things that make it one: the schema and the format it ships, that the
     * new app reads every older archive, and that 1.3.x refuses a format-8 one loudly. Anchored at
     * the start of the row, so a mention of 1.4.0 in prose or in the reservation table never
     * satisfies it.
     *
     * `versionCode` 16 is claimed by this row and by nothing else — a second row or a reservation
     * naming 16 is how two releases come to claim one number.
     */
    @Test fun versioningRecordsThisRelease() {
        val text = repoFile("docs/versioning.md").readText()
        val rows = Regex("""^\|\s*1\.4\.0\s*\|\s*16\s*\|.*$""", RegexOption.MULTILINE).findAll(text)
            .map { it.value }.toList()
        assertEquals("the supported release history must carry exactly one 1.4.0 / 16 row", 1, rows.size)
        val row = rows.single()
        assertTrue(
            "the row must name the schema and the format it ships",
            Regex("""schema \*\*8\*\*.*format \*\*8\*\*""").containsMatchIn(row),
        )
        assertTrue(
            "the row must give the forward-only reason it is a MINOR",
            row.contains("`BackupNewerFormat`") && row.contains("MINOR by the rule above"),
        )
        for (capability in listOf(
            "operating seasons (calendar and manual)",
            "maintenance service policy and a maintenance break",
            "operational condition and derived health",
        )) {
            assertTrue("the row must name \"$capability\" in the spec's words", row.contains(capability))
        }
        assertTrue(
            "the row must name the contracts",
            row.contains("`docs/api/v1.md`") &&
                row.contains("`docs/superpowers/specs/2026-09-24-servicetag-1.4-seasons-policy-condition-health.md`"),
        )
        assertFalse(
            "the row carries no placeholder",
            row.contains("PLACEHOLDER"),
        )
        assertEquals(
            "no other row, and no reservation, may claim versionCode 16",
            1,
            Regex("""^\|[^\n]*\|\s*16\s*\|""", RegexOption.MULTILINE).findAll(text).count(),
        )
    }

    /**
     * The 1.4.1 / 17 row: a PATCH, so it names its two fixes by issue, says the schema and the
     * format are unchanged, and names the contract. Anchored at the start of the row, as the 1.4.0
     * case is; `versionCode` 17 is claimed by this row and by nothing else.
     */
    @Test fun versioningRecords141() {
        val text = repoFile("docs/versioning.md").readText()
        val rows = Regex("""^\|\s*1\.4\.1\s*\|\s*17\s*\|.*$""", RegexOption.MULTILINE).findAll(text)
            .map { it.value }.toList()
        assertEquals("the supported release history must carry exactly one 1.4.1 / 17 row", 1, rows.size)
        val row = rows.single()
        assertTrue("the row must say it is a PATCH", row.contains("PATCH"))
        assertTrue(
            "the row must name the schema and the format, unchanged",
            Regex("""schema \*\*8\*\*.*format \*\*8\*\*""").containsMatchIn(row) && row.contains("unchanged"),
        )
        assertTrue("the row must name both fixes by issue", row.contains("#80") && row.contains("#81"))
        assertTrue("the row must name the contract", row.contains("`docs/api/v1.md`"))
        assertFalse("the row carries no placeholder", row.contains("PLACEHOLDER"))
        assertEquals(
            "no other row, and no reservation, may claim versionCode 17",
            1,
            Regex("""^\|[^\n]*\|\s*17\s*\|""", RegexOption.MULTILINE).findAll(text).count(),
        )
    }

    /**
     * The 1.5.0 / 18 row: a MINOR, so it names its six issues, the schema and the format it ships,
     * and the forward-only reason it is a MINOR, as the 1.4.0 row does. Anchored at the start of
     * the row; `versionCode` 18 is claimed by this row and by nothing else, and the 1.4.1 row it
     * follows is still there, once (`versioningRecords141` holds what that row says).
     */
    @Test fun versioningRecords150() {
        val text = repoFile("docs/versioning.md").readText()
        val rows = Regex("""^\|\s*1\.5\.0\s*\|\s*18\s*\|.*$""", RegexOption.MULTILINE).findAll(text)
            .map { it.value }.toList()
        assertEquals("the supported release history must carry exactly one 1.5.0 / 18 row", 1, rows.size)
        val row = rows.single()
        for (issue in listOf("#72", "#77", "#84", "#86", "#85", "#87")) {
            assertTrue("the row must name $issue", Regex("""$issue\b""").containsMatchIn(row))
        }
        assertTrue(
            "the row must name the schema and the format it ships",
            Regex("""schema \*\*16\*\*.*format \*\*16\*\*""").containsMatchIn(row),
        )
        assertTrue("the row must say the format bump is forward-only", row.contains("forward-only"))
        assertTrue(
            "the row must give the forward-only reason it is a MINOR",
            row.contains("`BackupNewerFormat`") && row.contains("MINOR by the rule above"),
        )
        assertTrue(
            "the row must say Save as document is the one outbound use of INTERNET",
            row.contains("INTERNET") && row.contains("Save as document"),
        )
        assertFalse("the row carries no placeholder", row.contains("PLACEHOLDER"))
        assertEquals(
            "no other row, and no reservation, may claim versionCode 18",
            1,
            Regex("""^\|[^\n]*\|\s*18\s*\|""", RegexOption.MULTILINE).findAll(text).count(),
        )
        val previous = Regex("""^\|\s*1\.4\.1\s*\|\s*17\s*\|""", RegexOption.MULTILINE).findAll(text).toList()
        assertEquals("the 1.4.1 / 17 row must still be there, once", 1, previous.size)
        assertTrue(
            "the 1.5.0 row must follow the 1.4.1 row",
            previous.single().range.first < text.indexOf(rows.single()),
        )
    }

    /**
     * The 1.6.0 / 19 row: a MINOR, so it names its three issues, the schema and the format it
     * ships, and the forward-only reason it is a MINOR, as the 1.5.0 row does. It also names the
     * one `/v1` change a script could meet — a body that is not strict JSON is now 400 on the two
     * reference routes — and links release notes that exist. Anchored at the start of the row;
     * `versionCode` 19 is claimed by this row and by nothing else, and the 1.5.0 row it follows is
     * still there, once (`versioningRecords150` holds what that row says).
     */
    @Test fun versioningRecords160() {
        val text = repoFile("docs/versioning.md").readText()
        val rows = Regex("""^\|\s*1\.6\.0\s*\|\s*19\s*\|.*$""", RegexOption.MULTILINE).findAll(text)
            .map { it.value }.toList()
        assertEquals("the supported release history must carry exactly one 1.6.0 / 19 row", 1, rows.size)
        val row = rows.single()
        for (issue in listOf("#92", "#91", "#93")) {
            assertTrue("the row must name $issue", Regex("""$issue\b""").containsMatchIn(row))
        }
        assertTrue(
            "the row must name the schema and the format it ships",
            Regex("""schema \*\*17\*\*.*format \*\*17\*\*""").containsMatchIn(row),
        )
        assertTrue("the row must say the format bump is forward-only", row.contains("forward-only"))
        assertTrue(
            "the row must give the forward-only reason it is a MINOR",
            row.contains("`BackupNewerFormat`") && row.contains("MINOR by the rule above"),
        )
        assertTrue(
            "the row must name the strict-JSON 400 on the two reference routes",
            row.contains("`PATCH /v1/references/{id}`") &&
                row.contains("`POST /v1/references/{id}/materialize`") &&
                row.contains("not strict JSON") && row.contains("400"),
        )
        assertTrue(
            "the row must link the 1.6.0 release notes, which must exist",
            row.contains("`docs/releases/1.6.0.md`") && repoFile("docs/releases/1.6.0.md").isFile,
        )
        assertFalse("the row carries no placeholder", row.contains("PLACEHOLDER"))
        assertEquals(
            "no other row, and no reservation, may claim versionCode 19",
            1,
            Regex("""^\|[^\n]*\|\s*19\s*\|""", RegexOption.MULTILINE).findAll(text).count(),
        )
        val previous = Regex("""^\|\s*1\.5\.0\s*\|\s*18\s*\|""", RegexOption.MULTILINE).findAll(text).toList()
        assertEquals("the 1.5.0 / 18 row must still be there, once", 1, previous.size)
        assertTrue(
            "the 1.6.0 row must follow the 1.5.0 row",
            previous.single().range.first < text.indexOf(rows.single()),
        )
    }

    /**
     * The 1.7.0 / 20 row: a MINOR, so it names its four issues, the schema and the format it ships,
     * and the forward-only reason it is a MINOR, as the 1.6.0 row does. It is the first release whose
     * schema and format differ — #16's two tables are device-local — so the row must say why the
     * format stays 20, and it names the two things a script could meet: a line sent without
     * `supplyId` clears its link, and 409 `SEASON_SYNC_ENABLED`. It links release notes that exist.
     * Anchored at the start of the row; `versionCode` 20 is claimed by this row and by nothing else,
     * and the 1.6.0 row it follows is still there, once (`versioningRecords160` holds what that row
     * says).
     */
    @Test fun versioningRecords170() {
        val text = repoFile("docs/versioning.md").readText()
        val rows = Regex("""^\|\s*1\.7\.0\s*\|\s*20\s*\|.*$""", RegexOption.MULTILINE).findAll(text)
            .map { it.value }.toList()
        assertEquals("the supported release history must carry exactly one 1.7.0 / 20 row", 1, rows.size)
        val row = rows.single()
        for (issue in listOf("#15", "#47", "#69", "#16")) {
            assertTrue("the row must name $issue", Regex("""$issue\b""").containsMatchIn(row))
        }
        assertTrue(
            "the row must name the schema and the format it ships",
            Regex("""schema \*\*21\*\*.*format \*\*20\*\*""").containsMatchIn(row),
        )
        assertTrue(
            "the row must say why the format stays 20 under schema 21",
            row.contains("device-local") && row.contains("format stays 20"),
        )
        assertTrue("the row must say the format bump is forward-only", row.contains("forward-only"))
        assertTrue(
            "the row must give the forward-only reason it is a MINOR",
            row.contains("`BackupNewerFormat`") && row.contains("MINOR by the rule above"),
        )
        assertTrue(
            "the row must name the two things a script could meet",
            row.contains("`supplyId`") && row.contains("409 `SEASON_SYNC_ENABLED`"),
        )
        assertTrue(
            "the row must link the 1.7.0 release notes, which must exist",
            row.contains("`docs/releases/1.7.0.md`") && repoFile("docs/releases/1.7.0.md").isFile,
        )
        assertTrue("the row must name its tag", row.contains("`servicetag-v1.7.0`"))
        assertFalse("the row carries no placeholder", row.contains("PLACEHOLDER"))
        assertEquals(
            "no other row, and no reservation, may claim versionCode 20",
            1,
            Regex("""^\|[^\n]*\|\s*20\s*\|""", RegexOption.MULTILINE).findAll(text).count(),
        )
        val previous = Regex("""^\|\s*1\.6\.0\s*\|\s*19\s*\|""", RegexOption.MULTILINE).findAll(text).toList()
        assertEquals("the 1.6.0 / 19 row must still be there, once", 1, previous.size)
        assertTrue(
            "the 1.7.0 row must follow the 1.6.0 row",
            previous.single().range.first < text.indexOf(rows.single()),
        )
    }

    /**
     * D5's two corrections. The old `prevDue` reconstruction and the old recurrence-edit answer
     * are **kept** for the record, so the assertion is not that the sentences are gone — it is
     * that each is marked superseded, which is what stops a reader taking either as the rule.
     */
    @Test fun theSchedulingDocumentMarksItsSupersededRules() {
        val text = repoFile("docs/design/05-scheduling-semantics.md").readText()
        assertTrue(
            "§5's prevDue reconstruction must be marked superseded by the spec and invariant 70",
            Regex("""^> \*\*Superseded by the 1\.2 spec's §2\.2 and invariant 70\.""", RegexOption.MULTILINE)
                .containsMatchIn(text),
        )
        assertTrue(
            "§5 must state that prevDue is read from the terminating row's occurrence_on",
            text.contains("is **read, not reconstructed**"),
        )
        assertTrue(
            "the NULL occurrence_on fallback must be documented as approximate",
            text.contains("**approximate, in exactly one way**"),
        )
        assertTrue(
            "§10.5's recurrence-edit line must be marked superseded by D-27 and the edit-date floor",
            Regex(
                """^> \*\*The last sentence is superseded by ruling D-27 and the edit-date floor""",
                RegexOption.MULTILINE,
            ).containsMatchIn(text),
        )
        assertTrue(
            "the D-16 known limits must be recorded",
            text.contains("ruling D-16's two known limits"),
        )

        // 1.4: two §6 sentences stated rules the 1.4 spec retired. They stay, marked, so the
        // assertion is on the marker — anchored, dated, naming the spec's two sections — and on the
        // old words still being there, never on their absence.
        val section6 = text.substringAfter("\n## 6. Seasonal activation\n").substringBefore("\n## 7. ")
        assertTrue(
            "§6's MANUAL_STARTUP deferral must be kept for the record",
            section6.contains("Deferred (not MVP): `MANUAL_STARTUP`"),
        )
        assertTrue(
            "§6's MANUAL_STARTUP deferral must be marked superseded by the 1.4 spec, dated",
            Regex(
                """^> \*\*The `MANUAL_STARTUP` deferral above is superseded by the 1\.4 spec \(2026-09-24; """ +
                    """§3\.3 activation facts, §4 service policy\)\.""",
                RegexOption.MULTILINE,
            ).containsMatchIn(section6),
        )
        assertTrue(
            "§6's season-end sentence must be kept for the record",
            section6.contains("nothing is stored at \"season end\""),
        )
        assertTrue(
            "§6's season-end sentence must be marked superseded by the 1.4 spec, dated",
            Regex(
                """^> \*\*The "nothing is stored at season end" sentence above is superseded by the 1\.4 spec """ +
                    """\(2026-09-24; §3\.3 activation facts, §4 service policy\)\.""",
                RegexOption.MULTILINE,
            ).containsMatchIn(section6),
        )
        val section104 = text.substringAfter("\n### 10.4 ").substringBefore("\n### 10.5 ")
        assertTrue(
            "§10.4's winter example must carry the 1.4 answer, dated, citing O-5 and Finding A-1",
            Regex(
                """^> \*\*The 1\.4 answer \(2026-09-24; spec §4\.3, O-5; archaeology Finding A-1\)\.""",
                RegexOption.MULTILINE,
            ).containsMatchIn(section104),
        )
        assertTrue(
            "the annotation must say the first in-season day reads DUE, not OVERDUE",
            section104.contains("**DUE** on the first in-season day, not **OVERDUE**"),
        )
    }

    /**
     * D4 §15's version plan had schedules at v3 and attachments at v4. Reading it is what almost
     * put 1.2's tables on a version that already exists.
     */
    @Test fun theDataModelDocumentNamesTheShippedVersions() {
        val text = repoFile("docs/design/04-domain-data-model.md").readText()
        assertTrue(
            "§15's stale version plan must be marked superseded",
            Regex("""^> \*\*Superseded by what shipped\.""", RegexOption.MULTILINE).containsMatchIn(text),
        )
        assertTrue(
            "it must say attachments shipped at v5 and schedules land at v6",
            text.contains(
                "So **attachments shipped at v5, not v4, and schedules land at v6, not v3**",
            ),
        )
        assertTrue(
            "the v6 row must name this release",
            Regex("""^> \| v6 \| \*\*1\.2\.0\*\* \|""", RegexOption.MULTILINE).containsMatchIn(text),
        )
    }

    /**
     * Three primary destinations, in the visual-design document and in the public documentation. A
     * released document describing the wrong navigation is a support cost, and both of these are
     * read by people who cannot check them against the code.
     *
     * **Where the capability lines live now.** The README stopped describing the navigation bar in
     * its 2026-09-25 rewrite, and its 2026-10-04 rewrite for progressive disclosure took every
     * capability bullet out of it: the capabilities moved to `docs/capabilities.md`, which the README
     * links, and the specs are reached through the release record (`docs/versioning.md`) rather
     * than from the README. This case and the four below it are anchored there since (the 1.7.1
     * train's pre-wave reconciliation); the 2026-10-04 rewrite had broken all five, as `966aa91`
     * once broke three.
     */
    @Test fun theDocumentsDescribeThreePrimaryDestinations() {
        assertTrue(
            "D12 must record that primary navigation is Dashboard · Assets · Maintenance",
            repoFile("docs/design/12-visual-design-apollo-service-binder.md").readText()
                .contains("**Dashboard · Assets · Maintenance**"),
        )
        assertTrue(
            "the README must link the capabilities page",
            repoFile("README.md").readText().contains("](docs/capabilities.md)"),
        )
        val capabilities = repoFile("docs/capabilities.md").readText()
        assertTrue(
            "the capabilities page must carry the maintenance section",
            Regex("""^## Maintenance schedules, seasons, condition, and health$""", RegexOption.MULTILINE)
                .containsMatchIn(capabilities),
        )
        assertTrue(
            "that section must name local reminders and reminder health",
            Regex("""^- local reminders and notification actions;""", RegexOption.MULTILINE).containsMatchIn(capabilities) &&
                Regex("""^- reminder-health diagnostics and repair flows\.""", RegexOption.MULTILINE).containsMatchIn(capabilities),
        )
        assertTrue(
            "the release record must name the 1.2 spec",
            repoFile("docs/versioning.md").readText()
                .contains("docs/superpowers/specs/2026-09-22-servicetag-1.2-operational-maintenance.md"),
        )
    }

    /**
     * The capabilities page's own section for the share intake. Anchored at the heading and at the
     * sentence, so a mention of sharing anywhere else in the file can never satisfy it; the spec is
     * named by the release record, as every spec is since the 2026-10-04 rewrite.
     */
    @Test fun theCapabilitiesPageNamesTheShareIntakeAndTheRecordNamesItsSpec() {
        val capabilities = repoFile("docs/capabilities.md").readText()
        assertTrue(
            "the capabilities page must carry the documents, references and share intake section",
            Regex("""^## Documents, references, and share intake$""", RegexOption.MULTILINE).containsMatchIn(capabilities),
        )
        assertTrue(
            "that section must say what the share intake takes",
            Regex(
                """^Android Share intake can attach a document, image, or URL to the appropriate owner\.""",
                RegexOption.MULTILINE,
            ).containsMatchIn(capabilities),
        )
        assertTrue(
            "the release record must name the committed share-intake spec",
            repoFile("docs/versioning.md").readText()
                .contains("docs/superpowers/specs/2026-09-23-servicetag-share-intake.md"),
        )
    }

    /**
     * The capabilities page's lines for 1.4: seasons, policy, condition and health. Each is anchored
     * at its own sentence, so a mention of seasons or health anywhere else in the file can never
     * satisfy it; the spec is named by the release record by its path, not a section anchor, so a
     * later revision of the spec cannot break it.
     */
    @Test fun theCapabilitiesPageNamesSeasonsConditionAndHealthAndTheRecordNamesTheSpec() {
        val capabilities = repoFile("docs/capabilities.md").readText()
        assertTrue(
            "the capabilities page must carry the 1.4 sentences: seasons and policy, condition, health",
            listOf(
                """^Operating season is distinct from maintenance policy\.""",
                """^Condition records whether an Asset is \*\*operational, degraded, or down\*\*\.""",
                """Health is a separate derived view""",
            ).all { Regex(it, RegexOption.MULTILINE).containsMatchIn(capabilities) },
        )
        assertTrue(
            "the release record must name the committed 1.4 spec",
            repoFile("docs/versioning.md").readText()
                .contains("docs/superpowers/specs/2026-09-24-servicetag-1.4-seasons-policy-condition-health.md"),
        )
    }

    /**
     * The capabilities page's sections for 1.5.0: lending (custody), Transfer Packs, Replace asset and
     * Save as document. Each is read as its own section — from its heading to the next heading of
     * any level — so a mention anywhere else in the file can never satisfy it. The two sentences
     * that promised lending and Transfer Packs as future work must be gone from the README and from
     * the page, and the release record links the release notes, which must exist.
     */
    @Test fun theCapabilitiesPageNamesCustodyTransfersReplaceAndSaveAsDocument() {
        val readme = repoFile("README.md").readText()
        val capabilities = repoFile("docs/capabilities.md").readText()
        for ((heading, words) in listOf(
            "### Lending" to "due-back reminder",
            "### Transfer Packs" to "transferred out",
            "### Replace Asset" to "successor",
            "### Save as document" to "HTTPS",
        )) {
            assertTrue(
                "the capabilities page must carry the 1.5.0 section \"$heading\", naming \"$words\"",
                sectionOf(capabilities, heading).contains(words),
            )
        }
        for (text in listOf(readme, capabilities)) {
            assertFalse(
                "the documentation must no longer promise Transfer Packs as future work",
                text.contains("Transfer Packs are intended for"),
            )
            assertFalse(
                "the documentation must no longer promise lending and a Transfer Pack workflow as post-1.4 work",
                text.contains("finally a **Transfer Pack** workflow"),
            )
        }
        assertTrue(
            "the release record must link the 1.5.0 release notes",
            repoFile("docs/versioning.md").readText().contains("docs/releases/1.5.0.md"),
        )
        assertTrue("the 1.5.0 release notes must exist", repoFile("docs/releases/1.5.0.md").isFile)
    }

    /**
     * The capabilities page's sections for 1.7.0: supply items and installed components, each
     * anchored at its own heading. The two sentences that promised supplies and installed-component
     * tracking as future work must be gone from the README and from the page, and both the README
     * and the page link the release notes, which must exist.
     */
    @Test fun theCapabilitiesPageNamesSupplyItemsAndInstalledComponents() {
        val readme = repoFile("README.md").readText()
        val capabilities = repoFile("docs/capabilities.md").readText()
        for (heading in listOf("Supply items", "Installed components")) {
            assertTrue(
                "the capabilities page must carry the 1.7.0 section \"$heading\"",
                Regex("""^## $heading$""", RegexOption.MULTILINE).containsMatchIn(capabilities),
            )
        }
        for (text in listOf(readme, capabilities)) {
            assertFalse(
                "the documentation must no longer promise supplies as the next product phase",
                text.contains("The next product phase is **supplies"),
            )
            assertFalse(
                "the documentation must no longer list installed-component tracking as later work",
                text.contains("installed-component tracking"),
            )
        }
        assertTrue("the README must link the 1.7.0 release notes", readme.contains("](docs/releases/1.7.0.md)"))
        assertTrue("the capabilities page must link the 1.7.0 release notes", capabilities.contains("](releases/1.7.0.md)"))
        assertTrue("the 1.7.0 release notes must exist", repoFile("docs/releases/1.7.0.md").isFile)
    }

    /** The text of [heading]'s section in [text]: from the heading line to the next heading of any level, or the end. */
    private fun sectionOf(text: String, heading: String): String {
        val start = Regex("""^${Regex.escape(heading)}$""", RegexOption.MULTILINE).find(text) ?: return ""
        val rest = text.substring(start.range.last + 1)
        val next = Regex("""^#""", RegexOption.MULTILINE).find(rest)
        return rest.substring(0, next?.range?.first ?: rest.length)
    }

    /**
     * D9's Attachments row claimed two controls this app has never had: sniffing the type on
     * import, and a configurable size cap. Spec §4.3 retires both. A stated control that does not
     * exist is worse than an absent one, so the claims go and the row says instead why trusting
     * the declared type is defensible and what the share path checks before it opens a stream.
     *
     * Anchored on the row, not on the file: an amendment note somewhere else in the document
     * would satisfy a bare `contains` while the row still read the old way.
     */
    @Test fun theSecurityDocumentNoLongerClaimsMimeSniffing() {
        val text = repoFile("docs/design/09-security-privacy.md").readText()
        assertFalse(
            "the retired sniffing claim must be gone, not merely annotated",
            text.contains("MIME sniffed on import"),
        )
        assertFalse(
            "the retired configurable-cap claim must be gone too",
            text.contains("size cap configurable"),
        )
        assertTrue(
            "the Attachments row must carry the dated amendment",
            Regex(
                """^\| \*\*Attachments\*\* \|.*\*\*Amended 2026-09-23 \(ServiceTag 1\.3\.0, spec §4\.3\)""",
                RegexOption.MULTILINE,
            ).containsMatchIn(text),
        )
        assertTrue(
            "the same row must record I-9's check on a shared stream",
            Regex(
                """^\| \*\*Attachments\*\* \|.*must not name one of ServiceTag's own authorities""",
                RegexOption.MULTILINE,
            ).containsMatchIn(text),
        )
    }

    /**
     * D4 §15's per-version table, one row further on. v7 is `asset_reference`, and the sentence
     * under the table that reserved "v7 upward" for supplies and projections is corrected to v8,
     * dated — reading a stale version plan is what almost put 1.2's schedules on v3.
     *
     * The rest of that superseded block is `theDataModelDocumentNamesTheShippedVersions`' and is
     * left exactly as it is, marker, attachments sentence and v6 row alike.
     */
    @Test fun theDataModelDocumentNamesV7() {
        val text = repoFile("docs/design/04-domain-data-model.md").readText()
        assertTrue(
            "the v7 row must name this release and its table",
            Regex("""^> \| v7 \| \*\*1\.3\.0\*\* \|""", RegexOption.MULTILINE).containsMatchIn(text),
        )
        assertTrue(
            "what is still unnumbered must now be said to take v9 upward, dated (v8 is 1.4.0's)",
            Regex(
                """^> .*will take \*\*v9 upward\*\* \(amended 2026-09-24, ServiceTag 1\.4\.0""",
                RegexOption.MULTILINE,
            ).containsMatchIn(text),
        )
        assertFalse(
            "the v8 reservation must not survive beside the v9 one",
            text.contains("will take **v8 upward**"),
        )
    }

    /**
     * D4 §15's table, one row further again: v8 is 1.4.0's three new tables and the two it
     * recreates. Anchored at the row, so the reservation sentence under the table cannot stand in
     * for it.
     */
    @Test fun theDataModelDocumentNamesV8() {
        val text = repoFile("docs/design/04-domain-data-model.md").readText()
        val rows = Regex("""^> \| v8 \| \*\*1\.4\.0\*\* \|.*$""", RegexOption.MULTILINE).findAll(text)
            .map { it.value }.toList()
        assertEquals("the v8 row must name this release, once", 1, rows.size)
        for (table in listOf(
            "`asset_season_activation`", "`asset_condition`", "`health_subject`",
            "`maintenance_schedule`", "`schedule_state`",
        )) {
            assertTrue("the v8 row must name $table", rows.single().contains(table))
        }
    }

    private companion object {
        const val TOKEN = "ABCD2345"

        /** The repository root, found by walking up to the settings script. */
        fun repoFile(path: String): File {
            var dir = File(".").absoluteFile
            while (!File(dir, "settings.gradle.kts").isFile) {
                dir = dir.parentFile ?: error("no settings.gradle.kts above ${File(".").absolutePath}")
            }
            return File(dir, path)
        }
    }
}
