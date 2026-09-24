package com.loosecannon.servicetag.share

import com.loosecannon.servicetag.core.references.LinkLaunchPolicy
import com.loosecannon.servicetag.core.references.StreamSourcePolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The share reader's rules, one case per hazard class (brief B03's matrix).
 *
 * `android.content.Intent` and `android.net.Uri` are stubs on this classpath
 * (`unitTests.isReturnDefaultValues`), so these drive [decideShare] — everything `readSharedItem`
 * does beyond lifting five values off an `Intent`. The two structural cases at the bottom cover
 * that remaining lift, which is exactly where the hostile-parcel and the logging rules live.
 */
class SharedItemReaderTest {

    private val streamPolicy = StreamSourcePolicy(
        setOf("com.loosecannon.servicetag", "com.loosecannon.servicetag.files"),
    )
    private val linkPolicy = LinkLaunchPolicy()

    /**
     * A stream a test drives by hand. [onCall] fires before every provider-side answer; [opened]
     * counts the reads, which are the only way this seam ever opens the stream.
     */
    private class FakeStream(
        override val scheme: String?,
        override val authority: String?,
        private val facts: FactsAnswer = StreamFacts(null, null, null),
        private val body: ByteArray = ByteArray(0),
        private val onCall: () -> Unit = {},
    ) : SharedStream {
        var opened = 0
            private set

        override fun facts(): FactsAnswer {
            onCall()
            return facts
        }

        override fun readAtMost(limit: Int): ByteArray {
            onCall()
            opened += 1
            return body.copyOf(minOf(limit, body.size))
        }
    }

    private fun decide(
        declaredType: String? = null,
        stream: SharedStream? = null,
        text: String? = null,
        subject: String? = null,
        title: String? = null,
    ): ShareContent = decideShare(
        declaredType = declaredType,
        stream = stream,
        text = text,
        subject = subject,
        title = title,
        streamPolicy = streamPolicy,
        linkPolicy = linkPolicy,
    )

    private fun downloads(
        name: String? = "manual.pdf",
        mime: String? = "application/pdf",
        size: Long? = 4_096L,
    ) = FakeStream(
        scheme = "content",
        authority = "com.android.providers.downloads.documents",
        facts = StreamFacts(name, mime, size),
    )

    // --- I-9, the call site ------------------------------------------------------------------

    @Test fun aFileStreamIsRefusedAndNeverOpened() {
        val item = decide(
            declaredType = "image/jpeg",
            stream = FakeStream("file", null) { error("a refused URI must never be opened") },
        )

        assertEquals(ShareContent.Refused(IntakeRefusal.STREAM_NOT_ACCEPTED), item)
    }

    @Test fun ourOwnProviderIsRefused() {
        val item = decide(
            declaredType = "image/jpeg",
            stream = FakeStream("content", "com.loosecannon.servicetag.files"),
        )

        assertEquals(ShareContent.Refused(IntakeRefusal.STREAM_NOT_ACCEPTED), item)
    }

    @Test fun anyOtherSchemeIsRefused() {
        val item = decide(
            declaredType = "application/pdf",
            stream = FakeStream("http", "example-mower.invalid"),
        )

        assertEquals(ShareContent.Refused(IntakeRefusal.STREAM_NOT_ACCEPTED), item)
    }

    /**
     * The hazard is the *order*, not the answer: a stream whose every provider-side call throws
     * still refuses cleanly, which is only true if the predicate ran before anything was asked.
     */
    @Test fun theRefusalHappensBeforeTheStreamIsTouchedAtAll() {
        val exploding = FakeStream("file", "anything") { error("nothing may be opened") }

        assertEquals(
            ShareContent.Refused(IntakeRefusal.STREAM_NOT_ACCEPTED),
            decide(declaredType = "application/pdf", stream = exploding),
        )
    }

    @Test fun aLegitimateDocumentProviderIsAccepted() {
        val item = decide(declaredType = "application/pdf", stream = downloads())

        assertEquals(ShareContent.Bytes("manual.pdf", "application/pdf", 4_096L), item)
    }

    // --- I-11: a stream nobody can be asked about (#63) --------------------------------------

    /**
     * A sharer that has never granted this app anything is invisible to it under package
     * visibility, and the resolver's query answers null without throwing. That is a read failure
     * here, before a byte form with an empty Received line is ever drawn, and nothing is opened.
     */
    @Test fun aStreamWhoseProviderCannotBeQueriedIsUnreadableBeforeAnyOpen() {
        val invisible = foreign(FactsAnswer.NoCursor)

        assertEquals(
            ShareContent.Refused(IntakeRefusal.UNREADABLE),
            decide(declaredType = "application/pdf", stream = invisible),
        )
        assertEquals("an unreadable stream is never opened", 0, invisible.opened)
    }

    /**
     * The other way of saying nothing: a cursor with no row in it. The refusal is about the
     * missing row and not about missing columns, so a row that answers without a size still takes
     * the byte arm, and the size is probed later as it always was.
     */
    @Test fun aStreamWhoseFactsComeBackEmptyIsUnreadable() {
        val empty = foreign(FactsAnswer.NoRow)

        assertEquals(
            ShareContent.Refused(IntakeRefusal.UNREADABLE),
            decide(declaredType = "application/pdf", stream = empty),
        )
        assertEquals("an unreadable stream is never opened", 0, empty.opened)
        assertEquals(
            ShareContent.Bytes("mower-manual.pdf", "application/pdf", null),
            decide(
                declaredType = "application/pdf",
                stream = foreign(StreamFacts("mower-manual.pdf", "application/pdf", null)),
            ),
        )
    }

    // --- the two text extras, and a provider's own filename ----------------------------------

    @Test fun theTwoTextExtrasAreCappedAndSanitised() {
        val fromSubject = decide(
            declaredType = "application/pdf",
            stream = downloads(name = null),
            subject = "\u0000" + "a".repeat(399),
        )
        val fromTitle = decide(
            declaredType = "application/pdf",
            stream = downloads(name = null),
            title = "\u0007" + "b".repeat(399),
        )

        val subjectName = (fromSubject as ShareContent.Bytes).suggestedName
        val titleName = (fromTitle as ShareContent.Bytes).suggestedName
        assertEquals(200, subjectName.length)
        assertEquals(200, titleName.length)
        assertTrue(subjectName.none { it.isISOControl() })
        assertTrue(titleName.none { it.isISOControl() })
    }

    @Test fun aProviderFilenameCannotCarryAPath() {
        val escaping = decide(
            declaredType = "text/plain",
            stream = downloads(name = "../../etc/passwd", mime = "text/plain"),
        )
        val noisy = decide(
            declaredType = "text/plain",
            stream = downloads(name = "log\u0000.\ntxt", mime = "text/plain"),
        )

        assertEquals("passwd", (escaping as ShareContent.Bytes).suggestedName)
        val cleaned = (noisy as ShareContent.Bytes).suggestedName
        assertTrue(cleaned, '/' !in cleaned && '\\' !in cleaned)
        assertTrue(cleaned, cleaned.none { it.isISOControl() })
    }

    // --- the text arm -------------------------------------------------------------------------

    @Test fun proseWithNoUriIsNeverGuessedAtAsALink() {
        val item = decide(declaredType = "text/plain", text = "Replaced the drive belt today")

        assertEquals(ShareContent.PlainText("Replaced the drive belt today"), item)
    }

    /** Neither a stream nor any text: there is nothing to offer as a note, and saying so is a
     * better answer than an empty "Received" line under "That is not a link." */
    @Test fun aShareCarryingNothingAtAllIsAReadFailureAndNotProse() {
        assertEquals(
            ShareContent.Refused(IntakeRefusal.UNREADABLE),
            decide(declaredType = "text/plain"),
        )
        assertEquals(
            ShareContent.Refused(IntakeRefusal.UNREADABLE),
            decide(declaredType = "text/plain", text = "   "),
        )
    }

    @Test fun aBlockedSchemeIsRefusedBeforeTheScreenOffersAnything() {
        val item = decide(declaredType = "text/plain", text = "javascript:alert(1)")

        assertEquals(ShareContent.Refused(IntakeRefusal.SCHEME_BLOCKED), item)
    }

    /** An unknown scheme is a link here; the confirmation is the screen's, not the reader's. */
    @Test fun anUnknownSchemeIsStillALink() {
        val item = decide(declaredType = "text/plain", text = "zotero://select/items/0")

        assertEquals(ShareContent.Link("zotero://select/items/0", null), item)
    }

    @Test fun anOverLongUriIsRefused() {
        val uri = "https://example-mower.invalid/" + "x".repeat(2_049 - "https://example-mower.invalid/".length)

        assertEquals(2_049, uri.length)
        assertEquals(
            ShareContent.Refused(IntakeRefusal.URI_TOO_LONG),
            decide(declaredType = "text/plain", text = uri),
        )
    }

    @Test fun aMarkdownLabelBecomesTheSuggestedName() {
        val item = decide(
            declaredType = "text/plain",
            text = "[Mower maintenance](joplin://x-callback-url/openNote?id=0f1e2d3c4b5a6978)",
            subject = "Shared from a note app",
        )

        assertEquals(
            ShareContent.Link(
                "joplin://x-callback-url/openNote?id=0f1e2d3c4b5a6978",
                "Mower maintenance",
            ),
            item,
        )
    }

    // --- text/uri-list, the one type the declared type decides (plan §18.15, §18.20) -----------

    @Test fun aUriListStreamIsATextShareAndNeverADocument() {
        val item = decide(
            declaredType = "text/uri-list",
            stream = uriList("# a comment\n\nhttps://example-mower.invalid/xt1/manual.pdf\r\n"),
        )

        assertEquals(
            ShareContent.Link("https://example-mower.invalid/xt1/manual.pdf", null),
            item,
        )
    }

    @Test fun aUriListWithTheTextExtraTakesTheExtra() {
        val item = decide(
            declaredType = "text/uri-list",
            stream = uriList("https://example-mower.invalid/from-the-stream") {
                error("the extra was present, so the stream must not be read")
            },
            text = "https://example-mower.invalid/from-the-extra",
        )

        assertEquals(ShareContent.Link("https://example-mower.invalid/from-the-extra", null), item)
    }

    @Test fun aUriListLongerThanSixtyFourKibIsUnreadable() {
        val body = "https://example-mower.invalid/a\n".padEnd(MAX_URI_LIST_BYTES + 1, 'x')

        assertEquals(MAX_URI_LIST_BYTES + 1, body.length)
        assertEquals(
            ShareContent.Refused(IntakeRefusal.UNREADABLE),
            decide(declaredType = "text/uri-list", stream = uriList(body)),
        )
    }

    @Test fun aUriListThatIsNotUtf8IsUnreadable() {
        val stream = FakeStream(
            scheme = "content",
            authority = "com.android.providers.downloads.documents",
            body = byteArrayOf(0xC3.toByte(), 0x28, 0xA0.toByte(), 0xA1.toByte()),
        )

        assertEquals(
            ShareContent.Refused(IntakeRefusal.UNREADABLE),
            decide(declaredType = "text/uri-list", stream = stream),
        )
    }

    /** The two caps are independent: a long *list* is ordinary, a long *URI* is not. */
    @Test fun aThreeKibUriListWithAnOrdinaryFirstUriSucceeds() {
        val uri = "https://example-mower.invalid/" + "a".repeat(300 - "https://example-mower.invalid/".length)
        val body = uri + "\n" + "# padding\n".repeat(280)

        assertEquals(300, uri.length)
        assertTrue(body.length > 3_000 && body.length < MAX_URI_LIST_BYTES)
        assertEquals(
            ShareContent.Link(uri, null),
            decide(declaredType = "text/uri-list", stream = uriList(body)),
        )
    }

    @Test fun aUriListWhoseFirstLineIsOverLongIsRefusedOnLength() {
        val uri = "https://example-mower.invalid/" + "a".repeat(2_049 - "https://example-mower.invalid/".length)

        assertEquals(
            ShareContent.Refused(IntakeRefusal.URI_TOO_LONG),
            decide(declaredType = "text/uri-list", stream = uriList("$uri\n")),
        )
    }

    /**
     * The other half of plan §18.15: widening the uri-list arm to `text/plain` would decode a
     * shared maintenance log, find no URI, offer it as a note and never store the document.
     */
    @Test fun aTextPlainStreamIsADocumentAndNotProse() {
        val item = decide(
            declaredType = "text/plain",
            stream = downloads(name = "service-log.txt", mime = "text/plain", size = 120L),
        )

        assertEquals(ShareContent.Bytes("service-log.txt", "text/plain", 120L), item)
    }

    @Test fun aTextPlainShareWithNoStreamTakesTheTextArm() {
        val item = decide(
            declaredType = "text/plain",
            text = "https://example-mower.invalid/xt1/manual.pdf",
        )

        assertEquals(
            ShareContent.Link("https://example-mower.invalid/xt1/manual.pdf", null),
            item,
        )
    }

    // --- structural: the two rules that live in the Android lift ------------------------------

    /**
     * A hostile parcel names whatever class it likes, and the deprecated overload returns it. Every
     * call site in the package therefore passes `Uri::class.java`, asserted on the source because
     * the overload that would break this cannot be reached from a JVM test.
     */
    @Test fun everyParcelableExtraReadIsTypedToUri() {
        var sites = 0
        shareSources().forEach { file ->
            // KDoc in this package quotes the call by name, and a comment is not a call site: a
            // floor that a sentence could satisfy would prove nothing at all.
            val source = file.readText().lines().filterNot { it.trimStart().startsWith("*") }
                .joinToString("\n")
            var from = 0
            while (true) {
                val at = source.indexOf(CALL, from)
                if (at < 0) break
                sites += 1
                from = at + CALL.length
                val close = source.indexOf(')', from)
                assertTrue(
                    "${file.name}: every getParcelableExtra call must pass Uri::class.java",
                    close > 0 && "Uri::class.java" in source.substring(from, close),
                )
            }
        }
        assertEquals("exactly one real call site reads EXTRA_STREAM", 1, sites)
    }

    /**
     * Spec §4.1: the activity reads **only** `EXTRA_STREAM`, `EXTRA_TEXT`, `EXTRA_SUBJECT` and
     * `EXTRA_TITLE`, all as data, and every other extra is ignored. It holds by construction
     * today; this is what keeps it true when someone reaches for a fifth.
     */
    @Test fun onlyTheFourNamedExtrasAreEverRead() {
        val allowed = setOf("EXTRA_STREAM", "EXTRA_TEXT", "EXTRA_SUBJECT", "EXTRA_TITLE")
        val named = mutableSetOf<String>()
        shareSources().forEach { file ->
            val source = file.readText().lines().filterNot { it.trimStart().startsWith("*") }
                .joinToString("\n")
            Regex("Intent\\.(EXTRA_[A-Z_]+)").findAll(source).forEach { named += it.groupValues[1] }
            Regex("get[A-Za-z]*Extra\\(\\s*\"([^\"]+)\"").findAll(source).forEach { raw ->
                throw AssertionError("${file.name} reads an extra by literal name: ${raw.value}")
            }
        }
        assertEquals(allowed, named)
    }

    /**
     * §4.4's one control with no other check at any level: a debug line left behind puts a
     * person's URL, filename or shared text into logcat.
     */
    @Test fun nothingInTheSharePackageLogs() {
        val forbidden = listOf("Log.i(", "Log.w(", "Log.e(", "println(", "System.out")
        shareSources().forEach { file ->
            val source = file.readText()
            forbidden.forEach { call ->
                assertTrue("${file.name} must not call $call", call !in source)
            }
        }
    }

    /**
     * The finish-back-to-the-sharer contract, at the declaration: nothing in the package names the
     * shell or starts an activity, so intake cannot navigate into `MainActivity`'s stack.
     */
    @Test fun nothingInTheSharePackageStartsTheShell() {
        shareSources().forEach { file ->
            val source = file.readText()
            assertTrue("${file.name} must not start an activity", "startActivity" !in source)
            assertTrue("${file.name} must not name MainActivity", "MainActivity" !in source)
            assertTrue(
                "${file.name} must not take a persistable grant",
                "takePersistableUriPermission" !in source,
            )
        }
    }

    private companion object {
        const val CALL = "getParcelableExtra("

        fun shareSources(): List<File> {
            val relative = "src/main/kotlin/com/loosecannon/servicetag/share"
            val directory = listOf(File(relative), File("app/$relative")).firstOrNull { it.isDirectory }
                ?: error("cannot find $relative from ${File(".").absolutePath}")
            return directory.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }
    }

    /** Another app's own provider, which is exactly the authority I-9 lets through. */
    private fun foreign(answer: FactsAnswer) = FakeStream(
        scheme = "content",
        authority = "org.example.mowerlog.files",
        facts = answer,
    )

    private fun uriList(body: String, onCall: () -> Unit = {}) = FakeStream(
        scheme = "content",
        authority = "com.android.providers.downloads.documents",
        body = body.toByteArray(Charsets.UTF_8),
        onCall = onCall,
    )
}
