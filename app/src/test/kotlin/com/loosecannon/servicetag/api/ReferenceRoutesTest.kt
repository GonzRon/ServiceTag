package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.backup.BackupCodec
import com.loosecannon.servicetag.core.model.ReferenceKind
import com.loosecannon.servicetag.core.usecase.ReferenceProblem
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.testing.FakeGraph
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The code this suite pairs with. Invented, and the only token these requests carry. */
private const val TOKEN = "ABCD2345"

private const val FIRST_DAY = 1_770_000_000_000L
private const val SECOND_DAY = FIRST_DAY + 86_400_000L

/**
 * 1.3.0 (#43, B05) — the three `/v1` reference rows, the five codes and the `/v1/status` count,
 * over `FakeGraph`, on the JVM, with no socket and no emulator. `ApiRouterTest`'s shape exactly:
 * the production router, the production handlers, the production use cases and the production
 * serializers over an in-memory Room database.
 *
 * **Nothing here asserts a rule this layer owns**, because it owns none: every refusal below is
 * `AddReference`'s or `UpdateReference`'s, proved as a domain rule in `:core`'s own suites. What is
 * proved here is the wire — the status, the code, the shape, and the fields a command may and may
 * not carry — which is the only thing a client can see.
 */
class ReferenceRoutesTest {

    private val graph = FakeGraph().apply { now = FIRST_DAY }

    @After fun close() = graph.close()

    private fun router(): ApiRouter = ApiRouter(
        ApiHandlers(
            graph.assets, graph.tags, graph.links, graph.definitions, graph.profiles,
            graph.events, graph.attachments,
            graph.createAsset, graph.updateAsset, graph.retireAsset, graph.archiveAsset,
            graph.saveDefinition, graph.archiveDefinition, graph.saveProfile, graph.archiveProfile,
            graph.logEvent, graph.updateEvent, graph.deleteEvent, graph.importBackupMerge,
            maintenanceHandlersFor(graph),
            referenceHandlersFor(graph),
            appVersion = "1.3.0",
            schemaVersion = AppGraph.SCHEMA_VERSION,
        ),
        TOKEN,
    )

    private fun call(
        method: String,
        path: String,
        body: String = "",
        contentType: String = "application/json",
    ): ApiResponse {
        val headers = buildMap {
            put("host", "127.0.0.1")
            put("authorization", "Bearer $TOKEN")
            if (body.isNotEmpty()) put("content-type", contentType)
        }
        return router().handle(ApiRequest(method, path, headers, body.toByteArray()))
    }

    private fun ApiResponse.text(): String = body.decodeToString()

    private fun ApiResponse.error() =
        ApiJson.decodeFromString(ApiErrorBody.serializer(), text()).error

    private fun referenceIn(response: ApiResponse) =
        ApiJson.decodeFromString(ReferenceResponse.serializer(), response.text()).reference

    private fun referencesOn(assetId: String) = ApiJson.decodeFromString(
        ReferenceListResponse.serializer(),
        call("GET", "/v1/assets/$assetId/references").text(),
    ).references

    // --- fixtures ------------------------------------------------------------------------------

    private fun createAsset(name: String = "Cub Cadet XT1"): String = ApiJson.decodeFromString(
        AssetResponse.serializer(),
        call("POST", "/v1/assets", """{"name":"$name","category":"Grounds"}""").text(),
    ).asset.id

    private fun createReference(
        assetId: String,
        uri: String,
        displayName: String,
        description: String = "",
    ): ApiResponse = call(
        "POST",
        "/v1/references",
        """{"assetId":"$assetId","uri":"$uri","displayName":"$displayName","description":"$description"}""",
    )

    // --- the list route ------------------------------------------------------------------------

    /**
     * Hazard: the list route is missing, or hands back insertion order. A client diffing what it
     * read last time against what it reads now needs a stable order, and `displayName` is the one
     * the References section draws — one order for both, so the two cannot disagree.
     */
    @Test fun theListRouteIsOrderedByNameAndEmptyWhenThereIsNothing() {
        val asset = createAsset()
        val other = createAsset("Snow blower")
        createReference(asset, "https://example.invalid/zeta", "Zebra manual")
        createReference(asset, "https://example.invalid/alpha", "Alpha manual")
        createReference(asset, "https://example.invalid/mango", "Mango manual")

        val listed = call("GET", "/v1/assets/$asset/references")
        assertEquals(200, listed.status)
        assertEquals(
            listOf("Alpha manual", "Mango manual", "Zebra manual"),
            ApiJson.decodeFromString(ReferenceListResponse.serializer(), listed.text())
                .references.map { it.displayName },
        )

        // An asset with none is an empty list and not a 404 — and it is the *key* that must be
        // there, so a client never has to tell "no references" from "no such field".
        assertEquals("""{"references":[]}""", call("GET", "/v1/assets/$other/references").text())
        assertEquals(404, call("GET", "/v1/assets/nope/references").status)
    }

    // --- create --------------------------------------------------------------------------------

    /**
     * Hazard: a route added to the `when` but not wired to a handler compiles and 404s; and a
     * create that answers 200 breaks every client branching on the status every other create uses.
     */
    @Test fun creatingIs201WithTheWholeRowAndTheRowIsReadableBack() {
        val asset = createAsset()
        val created = createReference(
            asset, "https://example.invalid/mower?page=3#oil", "Mower manual", "the PDF",
        )
        assertEquals(201, created.status)

        val row = referenceIn(created)
        assertTrue(row.id.isNotEmpty())
        assertEquals(asset, row.assetId)
        assertEquals(ReferenceKind.WEB_URL.name, row.kind)
        // Query and fragment survive byte for byte (I-1).
        assertEquals("https://example.invalid/mower?page=3#oil", row.uri)
        assertEquals("Mower manual", row.displayName)
        assertEquals("the PDF", row.description)
        assertEquals("https", row.scheme)
        assertEquals(FIRST_DAY, row.createdAt)
        assertEquals(FIRST_DAY, row.updatedAt)

        assertEquals(listOf(row), referencesOn(asset))
    }

    /**
     * Hazard: `kind` is accepted and then ignored. It is **derived from the scheme** and read-only
     * on the way out (master plan §18.18), so a command carrying one is a 400 naming it on both
     * verbs — a field the server took and discarded would read as settable and never take effect,
     * and nothing would catch the divergence.
     */
    @Test fun kindIsRefusedOnBothCommandsAndDerivedOnTheWayOut() {
        val asset = createAsset()
        val refusedOnCreate = call(
            "POST",
            "/v1/references",
            """{"assetId":"$asset","uri":"https://example.invalid/a","displayName":"A","kind":"OTHER"}""",
        )
        assertEquals(400, refusedOnCreate.status)
        assertTrue(refusedOnCreate.text(), "kind" in refusedOnCreate.error().message)

        val web = referenceIn(createReference(asset, "https://example.invalid/b", "Web"))
        assertEquals(ReferenceKind.WEB_URL.name, web.kind)
        val note = referenceIn(
            createReference(asset, "joplin://x-callback-url/openNote?id=abc", "Note"),
        )
        assertEquals(ReferenceKind.NOTE_LINK.name, note.kind)
        assertEquals("joplin", note.scheme)

        val refusedOnPatch = call(
            "PATCH", "/v1/references/${web.id}", """{"displayName":"B","kind":"WEB_URL"}""",
        )
        assertEquals(400, refusedOnPatch.status)
        assertTrue(refusedOnPatch.text(), "kind" in refusedOnPatch.error().message)
    }

    /** Hazard: a widened create shape. `ignoreUnknownKeys = false` names the field it refused. */
    @Test fun anUnknownFieldOnCreateIs400NamingIt() {
        val asset = createAsset()
        val refused = call(
            "POST",
            "/v1/references",
            """{"assetId":"$asset","uri":"https://example.invalid/a","displayName":"A","provenance":"share"}""",
        )
        assertEquals(400, refused.status)
        assertTrue(refused.text(), "provenance" in refused.error().message)
    }

    // --- amend ---------------------------------------------------------------------------------

    /**
     * Hazard: an overlay that sends every field back re-writes values the caller never named. Only
     * the name, the description and `updated_at` are mutable (I-1).
     */
    @Test fun amendingTouchesOnlyTheFieldsItNames() {
        val asset = createAsset()
        val before = referenceIn(
            createReference(asset, "https://example.invalid/mower", "Mower manual", "the PDF"),
        )

        graph.now = SECOND_DAY
        val patched = call("PATCH", "/v1/references/${before.id}", """{"displayName":"Manual"}""")
        assertEquals(200, patched.status)

        val after = referenceIn(patched)
        assertEquals("Manual", after.displayName)
        assertEquals(before.description, after.description)
        assertEquals(before.uri, after.uri)
        assertEquals(before.assetId, after.assetId)
        assertEquals(before.kind, after.kind)
        assertEquals(before.scheme, after.scheme)
        assertEquals(before.createdAt, after.createdAt)
        assertEquals(SECOND_DAY, after.updatedAt)
        assertNotEquals(before.updatedAt, after.updatedAt)
    }

    /**
     * Hazard: `null` read as a value. An MCP client bridging to strict function calling sends
     * `null` for every optional argument its caller did not set, so `null` meaning "clear" would
     * make a one-field rename wipe the rest. Blanking is by value, `""`.
     */
    @Test fun nullLeavesAFieldAloneAndAnEmptyStringClearsIt() {
        val asset = createAsset()
        val row = referenceIn(
            createReference(asset, "https://example.invalid/mower", "Mower manual", "the PDF"),
        )

        val kept = referenceIn(
            call("PATCH", "/v1/references/${row.id}", """{"description":null}"""),
        )
        assertEquals("the PDF", kept.description)
        assertEquals("Mower manual", kept.displayName)

        val cleared = referenceIn(call("PATCH", "/v1/references/${row.id}", """{"description":""}"""))
        assertEquals("", cleared.description)
        assertEquals("Mower manual", cleared.displayName)
    }

    /**
     * Hazard: the URI or the owner becomes amendable. Both are unknown fields on the PATCH (I-1,
     * I-6), so naming one is a 400 and never a silent ignore — a rename that quietly rewrote a
     * link would leave a row whose name and target disagree with no trace of when they parted.
     */
    @Test fun theUriAndTheOwnerAreNotAmendable() {
        val asset = createAsset()
        val row = referenceIn(createReference(asset, "https://example.invalid/mower", "Mower manual"))

        for (field in listOf("uri", "assetId")) {
            val refused = call(
                "PATCH", "/v1/references/${row.id}", """{"$field":"https://example.invalid/other"}""",
            )
            assertEquals(field, 400, refused.status)
            assertTrue(refused.text(), field in refused.error().message)
        }
    }

    /**
     * Hazard: a no-op amend writes anyway. `IDENTICAL` compares every backup-format field,
     * `updatedAt` included, so a write on every call would make a re-imported archive
     * `CONTENT_DIFFERS` on the next merge (master plan §18.13). Over the wire it is a **200 with
     * the stored row**, not an error.
     */
    @Test fun anAmendThatChangesNothingIs200WithTheStoredRowAndDoesNotMoveUpdatedAt() {
        val asset = createAsset()
        val row = referenceIn(
            createReference(asset, "https://example.invalid/mower", "Mower manual", "the PDF"),
        )

        graph.now = SECOND_DAY
        val again = call("PATCH", "/v1/references/${row.id}", """{"displayName":"Mower manual"}""")
        assertEquals(200, again.status)
        assertEquals(row, referenceIn(again))
        assertEquals(FIRST_DAY, referenceIn(again).updatedAt)
    }

    // --- the shapes ----------------------------------------------------------------------------

    /**
     * Hazard: the two shapes collapsed into one. `/v1/assets/{id}/references` is the **ninth**
     * `/v1/assets/{id}/…` sub-resource, whose `when`'s `else` is `notFound`, so a verb it does not
     * take is a **404**; `/v1/references` and `/v1/references/{id}` are path shapes, so a verb they
     * do not take is a **405**. That asymmetry is the shipped convention and `docs/api/v1.md`'s
     * 405 row records it.
     */
    @Test fun theSubResourceIs404ForAWrongVerbWhileTheCollectionIs405() {
        val asset = createAsset()
        val row = referenceIn(createReference(asset, "https://example.invalid/mower", "Mower manual"))

        assertEquals(404, call("POST", "/v1/assets/$asset/references", "{}").status)
        assertEquals(404, call("PATCH", "/v1/assets/$asset/references", "{}").status)
        assertEquals(405, call("GET", "/v1/references").status)
        assertEquals(405, call("GET", "/v1/references/${row.id}").status)
        assertEquals(405, call("POST", "/v1/references/${row.id}", "{}").status)
        // Not a shape at all, which is what keeps a byte path from ever being added by accident.
        assertEquals(404, call("GET", "/v1/references/${row.id}/bytes").status)
    }

    // --- the five codes ------------------------------------------------------------------------

    /**
     * Hazard: a code documented in `docs/api/v1.md` and never emitted. One case per code, five of
     * them, each read off a live route.
     *
     * Two of them carry a note. **`REFERENCE_NAME_REQUIRED`** is its own code and not
     * `REFERENCE_URI_INVALID`, which would be wrong on its face — the URI is valid, the name is
     * not. And **`file:///etc/passwd` is `REFERENCE_URI_INVALID`, not `REFERENCE_SCHEME_BLOCKED`**:
     * the structural check (I-10) refuses an empty host before the scheme tier is consulted, and
     * only a `file://` URI that *does* carry a host reaches the block list.
     */
    @Test fun everyReferenceCodeIsReachableOverTheWire() {
        val asset = createAsset()
        val row = referenceIn(createReference(asset, "https://example.invalid/mower", "Mower manual"))

        val missing = call("PATCH", "/v1/references/no-such-row", """{"displayName":"x"}""")
        assertEquals(404, missing.status)
        assertEquals("NO_SUCH_REFERENCE", missing.error().code)

        val tooLong = createReference(
            asset, "https://example.invalid/" + "a".repeat(2_049), "Long",
        )
        assertEquals(422, tooLong.status)
        assertEquals("REFERENCE_URI_INVALID", tooLong.error().code)

        for (notALink in listOf("notaurl", "file:///etc/passwd")) {
            val refused = createReference(asset, notALink, "Not a link")
            assertEquals(notALink, 422, refused.status)
            assertEquals(notALink, "REFERENCE_URI_INVALID", refused.error().code)
        }

        for (blocked in listOf("javascript:alert(1)", "file://localhost/etc/passwd")) {
            val refused = createReference(asset, blocked, "Blocked")
            assertEquals(blocked, 422, refused.status)
            assertEquals(blocked, "REFERENCE_SCHEME_BLOCKED", refused.error().code)
        }

        val duplicate = createReference(asset, row.uri, "The same link again")
        assertEquals(409, duplicate.status)
        assertEquals("REFERENCE_URI_TAKEN", duplicate.error().code)

        val blankOnCreate = createReference(asset, "https://example.invalid/blank", "   ")
        assertEquals(422, blankOnCreate.status)
        assertEquals("REFERENCE_NAME_REQUIRED", blankOnCreate.error().code)

        val blankOnPatch = call("PATCH", "/v1/references/${row.id}", """{"displayName":"   "}""")
        assertEquals(422, blankOnPatch.status)
        assertEquals("REFERENCE_NAME_REQUIRED", blankOnPatch.error().code)
    }

    /**
     * Hazard: the API gives an unknown scheme the confirmation spec §6 refuses it. There is nobody
     * on the wire to answer "Save this link?" and this layer never sets `confirmedUnknownScheme`
     * (master plan §18.2, §18.12), so an unfamiliar scheme is refused with the same code a hard
     * block gets — and **nothing is written**.
     *
     * The refusal also names no scheme, no URI, no authority and no path (spec §4.4), which is why
     * `problems` is empty on every reference refusal rather than carrying the problem's own
     * `toString()` as the validation rows above it do.
     */
    @Test fun anUnknownSchemeIsARefusalOverTheWireAndWritesNothing() {
        val asset = createAsset()
        val refused = createReference(asset, "zotero://select/items/0", "A citation")

        assertEquals(422, refused.status)
        assertEquals("REFERENCE_SCHEME_BLOCKED", refused.error().code)
        assertEquals(emptyList<String>(), refused.error().problems)
        assertFalse(refused.text(), "zotero" in refused.text())
        assertEquals(emptyList<Any>(), referencesOn(asset))
    }

    /**
     * Hazard: an unmapped `OwnerMissing` becomes the catch-all 500. It answers the **shipped**
     * 1.1.0 `no_such_asset` (master plan §18.10) rather than a second `UPPER_SNAKE` code for a
     * fact this API already names.
     */
    @Test fun creatingAgainstAnAbsentAssetIs404NoSuchAsset() {
        val refused = createReference("no-such-asset", "https://example.invalid/a", "A")
        assertEquals(404, refused.status)
        assertEquals("no_such_asset", refused.error().code)
    }

    /**
     * Hazard: a problem added to `ReferenceProblem` later ships as a refusal with a code nobody
     * documented. [referenceProblemCode] is a `when` over a sealed interface with **no `else`**, so
     * that is a compile error rather than a surprise; this case pins what each member maps to,
     * including the one no route can reach.
     */
    @Test fun everyReferenceProblemHasItsOwnCode() {
        assertEquals(
            mapOf(
                "NoSuchReference" to "NO_SUCH_REFERENCE",
                "OwnerMissing" to "no_such_asset",
                "BlankName" to "REFERENCE_NAME_REQUIRED",
                "NotALink" to "REFERENCE_URI_INVALID",
                "UriTooLong" to "REFERENCE_URI_INVALID",
                "SchemeBlocked" to "REFERENCE_SCHEME_BLOCKED",
                "UnknownSchemeNeedsConfirmation" to "REFERENCE_SCHEME_BLOCKED",
                "DuplicateUri" to "REFERENCE_URI_TAKEN",
                // Never emitted: a no-op amend is a 200 with the stored row, proved above.
                "Unchanged" to "REFERENCE_UNCHANGED",
            ),
            listOf(
                ReferenceProblem.NoSuchReference,
                ReferenceProblem.OwnerMissing,
                ReferenceProblem.BlankName,
                ReferenceProblem.NotALink,
                ReferenceProblem.UriTooLong,
                ReferenceProblem.SchemeBlocked,
                ReferenceProblem.UnknownSchemeNeedsConfirmation("zotero"),
                ReferenceProblem.DuplicateUri,
                ReferenceProblem.Unchanged,
            ).associate { it::class.simpleName!! to referenceProblemCode(it) },
        )
    }

    // --- status and the archive ------------------------------------------------------------

    /**
     * Hazard: the count lands in the wrong map, or a shipped key is renamed — either breaks a
     * client's readiness check without failing anything else.
     */
    @Test fun statusCarriesTheReferenceCountBesideEveryShippedKey() {
        val asset = createAsset()
        createReference(asset, "https://example.invalid/a", "A")
        createReference(asset, "https://example.invalid/b", "B")

        val counts = ApiJson.decodeFromString(
            StatusResponse.serializer(), call("GET", "/v1/status").text(),
        ).counts
        assertEquals(2, counts["assetReferences"])
        assertEquals(
            setOf(
                "assets", "tags", "links", "definitions", "profiles", "events", "attachments",
                "groups", "schedules", "closures", "assetReferences",
            ),
            counts.keys,
        )
    }

    /**
     * Hazard: `FORMAT_VERSION` not carried through leaves the API refusing an archive the app can
     * read, or accepting one it cannot. That a **format-7** archive is accepted on a live route is
     * proved by `MaintenanceRoutesTest.theMergeReportWireMirrorCarriesEveryTallyInWriteOrder`; this
     * is the other side — a manifest claiming a format this build does not know is a 409, and the
     * gate fires before a single row is read.
     */
    @Test fun anArchiveFromANewerFormatIs409ArchiveNewerFormat() {
        val refused = router().handle(
            ApiRequest(
                "POST", IMPORT_MERGE_PLAN_PATH,
                mapOf("authorization" to "Bearer $TOKEN", "content-type" to "application/zip"),
                newerThanThisBuild(),
            ),
        )
        assertEquals(409, refused.status)
        assertEquals("archive_newer_format", refused.error().code)
    }

    /**
     * A manifest claiming one format past this build's, and **no `data.json` behind it at all** —
     * which is the point: the version gate fires before the archive's contents are touched, so an
     * archive this thin is enough to reach it and a `BackupCorrupt` would prove it did not.
     */
    private fun newerThanThisBuild(): ByteArray {
        val manifest = """{"formatVersion":${BackupCodec.FORMAT_VERSION + 1},""" +
            """"appVersion":"9.9.9","schemaVersion":99,"createdAt":0,"counts":{},""" +
            """"dataSha256":"","backupSetId":"not-a-real-set"}"""
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(manifest.toByteArray())
            zos.closeEntry()
        }
        return out.toByteArray()
    }

    // --- the document ------------------------------------------------------------------------

    /**
     * Hazard: the document contradicts the router, which is a support cost paid by somebody who
     * cannot check it. Every pattern below is **anchored** to the line shape it lives on, so a
     * comment or a sentence mentioning a code can never satisfy one.
     */
    @Test fun theApiDocumentAgreesWithTheRouter() {
        val text = repoFile("docs/api/v1.md").readText()

        assertFalse("the merge report is eleven tables now", "ten tables" in text)
        assertTrue("the merge report must say eleven tables", "eleven tables" in text)
        // The bare string, both sites: the document spells the emphasis two ways, and a pattern
        // pinned to one asterisk placement would leave the other stale and still report clean.
        assertFalse("the import endpoints read format 1–7 now", "1–6" in text)
        assertTrue("the import endpoints must say 1–7", "1–7" in text)

        assertFalse(
            "there are nine asset sub-resources now",
            "eight `/v1/assets/{id}/…` sub-resources" in text,
        )
        assertTrue(
            "the 405 row must name nine asset sub-resources",
            "nine `/v1/assets/{id}/…` sub-resources" in text,
        )

        for (row in listOf(
            """^\| `GET` \| `/v1/assets/\{id\}/references` \|""",
            """^\| `POST` \| `/v1/references` \|""",
            """^\| `PATCH` \| `/v1/references/\{id\}` \|""",
            """^\| 404 \| `NO_SUCH_REFERENCE` \|""",
            """^\| 422 \| `REFERENCE_NAME_REQUIRED` \|""",
            """^\| 422 \| `REFERENCE_URI_INVALID` \|""",
            """^\| 422 \| `REFERENCE_SCHEME_BLOCKED` \|""",
            """^\| 409 \| `REFERENCE_URI_TAKEN` \|""",
        )) {
            assertTrue(
                "docs/api/v1.md is missing a row matching $row",
                Regex(row, RegexOption.MULTILINE).containsMatchIn(text),
            )
        }
    }

    /** The repository root, found the way `VersionAgreementTest` finds it, without borrowing it. */
    private fun repoFile(path: String): File {
        var dir = File(".").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("no settings.gradle.kts above ${File(".").absolutePath}")
        }
        return File(dir, path)
    }
}
