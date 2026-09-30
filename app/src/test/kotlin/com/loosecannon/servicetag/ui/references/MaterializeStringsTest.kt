package com.loosecannon.servicetag.ui.references

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #85 row 32: every Save-as-document literal (§6, RATIFIED 2026-09-29) pinned verbatim, and each template's
 * placeholders filled where the ratified text puts them. A pin, so there is no natural RED: a changed word here is
 * a string-gate decision, never a test fix.
 */
class MaterializeStringsTest {

    private companion object {
        const val HOST = "manuals.example.invalid"
    }

    @Test fun everyConstantIsTheRatifiedText() {
        assertEquals("Save as document", MaterializeStrings.SAVE_AS_DOCUMENT)
        assertEquals("Saved as document", MaterializeStrings.SAVED_AS_DOCUMENT)
        assertEquals("Saved to Documents", MaterializeStrings.SAVED_TO_DOCUMENTS)
        assertEquals("Open source link", MaterializeStrings.OPEN_SOURCE_LINK)
        assertEquals(
            "ServiceTag is not allowed to use the network, so it cannot download this file. Allow network access " +
                "in the app settings, then close ServiceTag and open it again.",
            MaterializeStrings.NETWORK_DENIED,
        )
        assertEquals("The download took too long and was stopped.", MaterializeStrings.TIMED_OUT)
        assertEquals(
            "That link did not lead to a supported document type. It stays a link.",
            MaterializeStrings.NOT_A_DOCUMENT,
        )
        assertEquals(
            "That file needs a sign-in, so ServiceTag cannot download it. It stays a link.",
            MaterializeStrings.NEEDS_SIGN_IN,
        )
        assertEquals(
            "That link redirects somewhere ServiceTag will not follow. It stays a link.",
            MaterializeStrings.REDIRECT_REFUSED,
        )
        assertEquals("The download could not be completed. Try again.", MaterializeStrings.INTERRUPTED)
        assertEquals(
            "That link resolves to a local-network address, so ServiceTag will not download it. It stays a link.",
            MaterializeStrings.LOCAL_ADDRESS,
        )
    }

    @Test fun everyTemplatePutsItsPlaceholdersWhereTheRatifiedTextDoes() {
        assertEquals("Downloading from manuals.example.invalid…", MaterializeStrings.downloadingFrom(HOST))
        assertEquals("1.5 KB of 2.0 MB", MaterializeStrings.progress(1_536L, 2_097_152L))
        assertEquals("the size unknown: {done} alone", "512 B", MaterializeStrings.progress(512L, null))
        assertEquals("From manuals.example.invalid", MaterializeStrings.fromHost(HOST))
        assertEquals("PDF · 39 B", MaterializeStrings.typeLine("application/pdf", 39L))
        assertEquals(
            "Downloaded from manuals.example.invalid on 29 Sep 2026",
            MaterializeStrings.downloadedFrom(HOST, LocalDate.of(2026, 9, 29)),
        )
        assertEquals(
            "Could not reach manuals.example.invalid. Check the connection and try again.",
            MaterializeStrings.unreachable(HOST),
        )
        assertEquals("The server did not send the file (error 503).", MaterializeStrings.serverError(503))
        assertEquals(
            "This asset already has this file: Example Pool Pump manual.",
            MaterializeStrings.alreadyHave("Example Pool Pump manual"),
        )
    }

    /** P85-5's `{TYPE}`: the nineteen ratified labels (§19's table), each for the MIME type the sniff proves. */
    @Test fun everyProvenTypeHasItsRatifiedLabel() {
        val labels = mapOf(
            "application/pdf" to "PDF",
            "image/png" to "PNG",
            "image/jpeg" to "JPEG",
            "image/gif" to "GIF",
            "image/webp" to "WebP",
            "application/rtf" to "RTF",
            "application/msword" to "DOC",
            "application/vnd.ms-excel" to "XLS",
            "application/vnd.ms-powerpoint" to "PPT",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "DOCX",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "XLSX",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation" to "PPTX",
            "application/vnd.oasis.opendocument.text" to "ODT",
            "application/vnd.oasis.opendocument.spreadsheet" to "ODS",
            "application/vnd.oasis.opendocument.presentation" to "ODP",
            "text/plain" to "TXT",
            "text/markdown" to "Markdown",
            "text/csv" to "CSV",
            "text/tab-separated-values" to "TSV",
        )
        assertEquals(19, labels.size)
        labels.forEach { (mime, label) ->
            assertEquals(mime, "$label · 2.0 MB", MaterializeStrings.typeLine(mime, 2_097_152L))
        }
    }
}
