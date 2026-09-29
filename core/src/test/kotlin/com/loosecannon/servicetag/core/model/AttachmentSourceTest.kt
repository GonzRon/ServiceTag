package com.loosecannon.servicetag.core.model

import com.loosecannon.servicetag.core.references.MAX_REFERENCE_NAME_CHARS
import com.loosecannon.servicetag.core.references.MAX_REFERENCE_URI_CHARS
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** C1 (#85, R85-2, R85-3, R85-14): the one shape rule for an attachment's four source fields. */
class AttachmentSourceTest {

    private val uri = "https://manuals.example.invalid/pump/manual.pdf"
    private val resolved = "https://cdn.example.invalid/files/manual.pdf"

    private fun problem(
        u: String? = uri,
        r: String? = null,
        at: Long? = 1_758_900_000_000L,
        n: String? = "Example Pool Pump manual",
    ) = attachmentSourceProblem(u, r, at, n)

    @Test fun allNullIsNoSource() {
        assertNull(attachmentSourceProblem(null, null, null, null))
    }

    @Test fun aWellFormedSourceHasNoProblem() {
        assertNull(problem())
        assertNull(problem(r = resolved, n = null))
        assertNull(problem(u = "HTTPS://Manuals.Example.Invalid/a.pdf"))
    }

    @Test fun aUriWithoutADateIsABreach() {
        assertNotNull(problem(at = null))
        assertNotNull(problem(at = 0L))
        assertNotNull(problem(at = -5L))
    }

    @Test fun aDateOrAnyOtherFieldWithoutAUriIsABreach() {
        assertNotNull(attachmentSourceProblem(null, null, 5L, null))
        assertNotNull(attachmentSourceProblem(null, resolved, null, null))
        assertNotNull(attachmentSourceProblem(null, null, null, "Example manual"))
    }

    @Test fun httpIsRefused() {
        assertNotNull(problem(u = "http://manuals.example.invalid/a.pdf"))
        assertNotNull(problem(u = "ftp://manuals.example.invalid/a.pdf"))
        assertNotNull(problem(r = "http://cdn.example.invalid/a.pdf"))
    }

    @Test fun aUriWithWhitespaceOrAControlCharacterIsRefused() {
        assertNotNull(problem(u = "https://manuals.example.invalid/a b.pdf"))
        assertNotNull(problem(u = "https://manuals.example.invalid/a\n.pdf"))
        assertNotNull(problem(u = "https://manuals.example.invalid/a\u0000.pdf"))
    }

    @Test fun aResolvedUriWithAQueryFragmentPathParameterOrUserinfoIsRefused() {
        assertNotNull(problem(r = "https://cdn.example.invalid/a.pdf?token=abc"))
        assertNotNull(problem(r = "https://cdn.example.invalid/a.pdf#page=2"))
        assertNotNull(problem(r = "https://cdn.example.invalid/a.pdf;jsessionid=abc"))
        assertNotNull(problem(r = "https://cdn.example.invalid/a;x=1/b.pdf"))
        assertNotNull(problem(r = "https://user@cdn.example.invalid/a.pdf"))
        assertNotNull(problem(r = "https://user:pw@cdn.example.invalid/a.pdf"))
    }

    @Test fun theOriginalUriMayCarryAQueryAFragmentAndUserinfoBecauseItIsVerbatim() {
        assertNull(problem(u = "https://manuals.example.invalid/a.pdf?id=7#top"))
    }

    @Test fun aResolvedUriEqualToTheSourceIsRefused() {
        assertNotNull(problem(u = resolved, r = resolved))
    }

    @Test fun lengthsAt2048And200PassAnd2049And201Fail() {
        val prefix = "https://manuals.example.invalid/"
        fun ofLength(n: Int) = prefix + "a".repeat(n - prefix.length)
        assertNull(problem(u = ofLength(MAX_REFERENCE_URI_CHARS)))
        assertNotNull(problem(u = ofLength(MAX_REFERENCE_URI_CHARS + 1)))
        assertNull(problem(r = "https://cdn.example.invalid/" + "b".repeat(MAX_REFERENCE_URI_CHARS - 28)))
        assertNotNull(problem(r = "https://cdn.example.invalid/" + "b".repeat(MAX_REFERENCE_URI_CHARS - 27)))
        assertNull(problem(n = "n".repeat(MAX_REFERENCE_NAME_CHARS)))
        assertNotNull(problem(n = "n".repeat(MAX_REFERENCE_NAME_CHARS + 1)))
    }

    @Test fun aBlankNameIsRefused() {
        assertNotNull(problem(n = ""))
        assertNotNull(problem(n = "   "))
    }
}
