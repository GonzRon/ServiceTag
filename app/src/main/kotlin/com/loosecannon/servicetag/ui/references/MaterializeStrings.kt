package com.loosecannon.servicetag.ui.references

import com.loosecannon.servicetag.core.model.ReferenceOwner
import com.loosecannon.servicetag.ui.attachments.asFileSize
import com.loosecannon.servicetag.ui.condition.displayDate
import java.time.LocalDate

/**
 * #85 §6 (RATIFIED 2026-09-29): **the one home of every Save-as-document literal**, P85-1 to P85-19 and #69's
 * already-have twins P69-15 and P69-16, verbatim, one constant or template per line. The reused lines (Cancel, Save, Close, the attachment sentences…) stay in their own
 * homes and are never quoted here. A sentence this object does not carry is a string-gate question, never a
 * screen's to invent.
 */
internal object MaterializeStrings {
    const val SAVE_AS_DOCUMENT = "Save as document" // P85-1
    fun downloadingFrom(host: String): String = "Downloading from $host…" // P85-2

    /** P85-3: `{done} of {total}`, or `{done}` alone when the size is unknown. */
    fun progress(done: Long, total: Long?): String =
        if (total == null) done.asFileSize() else "${done.asFileSize()} of ${total.asFileSize()}"
    fun fromHost(host: String): String = "From $host" // P85-4

    /** P85-5: the proven type's ratified label (§19's table) and the size. */
    fun typeLine(mimeType: String, sizeBytes: Long): String = "${typeLabel(mimeType)} · ${sizeBytes.asFileSize()}"
    const val SAVED_AS_DOCUMENT = "Saved as document" // P85-6
    const val SAVED_TO_DOCUMENTS = "Saved to Documents" // P85-7
    fun downloadedFrom(host: String, date: LocalDate): String = "Downloaded from $host on ${displayDate(date)}" // P85-8
    const val OPEN_SOURCE_LINK = "Open source link" // P85-9
    const val NETWORK_DENIED = "ServiceTag is not allowed to use the network, so it cannot download this file. Allow network access in the app settings, then close ServiceTag and open it again." // P85-10
    fun unreachable(host: String): String = "Could not reach $host. Check the connection and try again." // P85-11
    const val TIMED_OUT = "The download took too long and was stopped." // P85-12
    const val NOT_A_DOCUMENT = "That link did not lead to a supported document type. It stays a link." // P85-13
    const val NEEDS_SIGN_IN = "That file needs a sign-in, so ServiceTag cannot download it. It stays a link." // P85-14
    fun serverError(code: Int): String = "The server did not send the file (error $code)." // P85-15
    const val REDIRECT_REFUSED = "That link redirects somewhere ServiceTag will not follow. It stays a link." // P85-16
    fun alreadyHave(name: String): String = "This asset already has this file: $name." // P85-17
    fun alreadyHaveOnSupply(name: String): String = "This supply already has this file: $name." // P69-15
    fun alreadyHaveOnInstalledComponent(name: String): String =
        "This installed component already has this file: $name." // P69-16

    /** #69 (C28, R69-13): P85-17 by the owner's kind; an asset keeps its shipped wording. */
    fun alreadyHave(owner: ReferenceOwner, name: String): String = when (owner) {
        is ReferenceOwner.OfAsset -> alreadyHave(name)
        is ReferenceOwner.OfSupplyItem -> alreadyHaveOnSupply(name)
        is ReferenceOwner.OfInstalledComponent -> alreadyHaveOnInstalledComponent(name)
    }
    const val INTERRUPTED = "The download could not be completed. Try again." // P85-18
    const val LOCAL_ADDRESS = "That link resolves to a local-network address, so ServiceTag will not download it. It stays a link." // P85-19

    /**
     * P85-5's `{TYPE}`, keyed by the MIME type the sniff stores (§19's table): the nineteen ratified labels, and
     * nothing else. The sniff proves exactly these, so a type missing here is a programming error, not a line.
     */
    private fun typeLabel(mimeType: String): String = checkNotNull(TYPE_LABELS[mimeType]) { "no ratified label" }

    private val TYPE_LABELS = mapOf(
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
}
