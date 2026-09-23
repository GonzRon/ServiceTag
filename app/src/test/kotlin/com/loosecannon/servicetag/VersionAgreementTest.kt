package com.loosecannon.servicetag

import com.loosecannon.servicetag.api.ApiHandlers
import com.loosecannon.servicetag.api.ApiJson
import com.loosecannon.servicetag.api.ApiRequest
import com.loosecannon.servicetag.api.ApiRouter
import com.loosecannon.servicetag.api.StatusResponse
import com.loosecannon.servicetag.api.maintenanceHandlersFor
import com.loosecannon.servicetag.api.referenceHandlersFor
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
 * 1.3 carries four numbers that have to agree — the `versionName`, the `versionCode`, the Room
 * schema version and the backup format version — and they live in four different files. A schema
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
    @Test fun theReleaseIdentityIs130AndCode15() {
        assertEquals("1.3.0", BuildConfig.VERSION_NAME)
        assertEquals(15, BuildConfig.VERSION_CODE)
        assertEquals("servicetag-v1.3.0", "servicetag-v${BuildConfig.VERSION_NAME}")
    }

    /**
     * The schema and format numbers, and the two constants agreeing with each other. 1.3 bumps
     * both together — `asset_reference` and the archive that carries it — so a build where they
     * differ is a build where an export and an import disagree about what they are exchanging.
     *
     * The expected numbers are this test's own, deliberately: they are what the schema and the
     * format are at this tip, and they move only when a release changes them. They are **not**
     * derived from the versioning row of whatever `versionName` currently reads, which the
     * `versionName`/`versionCode` cases own — otherwise bumping the schema in one file only would
     * still pass here, which is the whole failure this class exists to catch.
     */
    @Test fun theSchemaAndTheFormatAreBothSeven() {
        assertEquals(7, AppGraph.SCHEMA_VERSION)
        assertEquals(7, BackupCodec.FORMAT_VERSION)
        assertEquals(AppGraph.SCHEMA_VERSION, BackupCodec.FORMAT_VERSION)
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
                graph.events, graph.attachments,
                graph.createAsset, graph.updateAsset, graph.retireAsset, graph.archiveAsset,
                graph.saveDefinition, graph.archiveDefinition, graph.saveProfile,
                graph.archiveProfile,
                graph.logEvent, graph.updateEvent, graph.deleteEvent, graph.importBackupMerge,
                maintenanceHandlersFor(graph),
                referenceHandlersFor(graph),
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
        assertEquals("1.3.0", status.appVersion)
        assertEquals(7, status.schemaVersion)
        assertEquals(7, status.backupFormatVersion)
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
     * Three primary destinations, in the visual-design document and in the README. A released
     * document describing the wrong navigation is a support cost, and both of these are read by
     * people who cannot check them against the code.
     */
    @Test fun theDocumentsDescribeThreePrimaryDestinations() {
        assertTrue(
            "D12 must record that primary navigation is Dashboard · Assets · Maintenance",
            repoFile("docs/design/12-visual-design-apollo-service-binder.md").readText()
                .contains("**Dashboard · Assets · Maintenance**"),
        )
        val readme = repoFile("README.md").readText()
        assertTrue(
            "the README must name the three destinations along the bottom",
            readme.contains("Dashboard, Assets and Maintenance along the bottom"),
        )
        assertTrue(
            "the README must name the capability and link the spec",
            readme.contains("**Maintenance schedules, maintenance groups and local reminders**") &&
                readme.contains(
                    "docs/superpowers/specs/2026-09-22-servicetag-1.2-operational-maintenance.md",
                ),
        )
    }

    /**
     * The README's own capability line for this release. A released document that describes the
     * wrong capability is a support cost, and the README is read by people who cannot check it
     * against the code. Anchored at the bullet, so a mention of the share intake anywhere else in
     * the file — the NoteTag section, for instance — can never satisfy it.
     */
    @Test fun theReadmeNamesTheShareIntakeAndLinksItsSpec() {
        val readme = repoFile("README.md").readText()
        assertTrue(
            "the README must carry a capability bullet for the share intake",
            Regex(
                """^- \*\*Share a link, a document, a photo or a note into an asset\*\* —""",
                RegexOption.MULTILINE,
            ).containsMatchIn(readme),
        )
        assertTrue(
            "that bullet must link the committed spec",
            readme.contains(
                "](docs/superpowers/specs/2026-09-23-servicetag-share-intake.md)",
            ),
        )
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
