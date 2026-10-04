package com.loosecannon.servicetag.ui.references

import com.loosecannon.servicetag.R
import com.loosecannon.servicetag.core.model.ReferenceOwner
import com.loosecannon.servicetag.l10n.localized
import com.loosecannon.servicetag.l10n.localizedDate
import com.loosecannon.servicetag.ui.attachments.asFileSize
import java.time.LocalDate

/**
 * #85 §6 (RATIFIED 2026-09-29): **the one home of every Save-as-document literal**, P85-1 to P85-19 and #69's
 * already-have twins P69-15 and P69-16, verbatim, one constant or template per line. The reused lines (Cancel, Save, Close, the attachment sentences…) stay in their own
 * homes and are never quoted here. A sentence this object does not carry is a string-gate question, never a
 * screen's to invent.
 */
internal object MaterializeStrings {
    val SAVE_AS_DOCUMENT: String get() = localized(R.string.references_save_as_document) // P85-1
    fun downloadingFrom(host: String): String = localized(R.string.references_downloading_from, host) // P85-2

    /** P85-3: `{done} of {total}`, or `{done}` alone when the size is unknown. */
    fun progress(done: Long, total: Long?): String =
        if (total == null) done.asFileSize()
        else localized(R.string.references_download_progress, done.asFileSize(), total.asFileSize())
    fun fromHost(host: String): String = localized(R.string.references_from_host, host) // P85-4

    /** P85-5: the proven type's ratified label (§19's table) and the size. */
    fun typeLine(mimeType: String, sizeBytes: Long): String =
        localized(R.string.references_type_line, typeLabel(mimeType), sizeBytes.asFileSize())
    val SAVED_AS_DOCUMENT: String get() = localized(R.string.references_saved_as_document) // P85-6
    val SAVED_TO_DOCUMENTS: String get() = localized(R.string.references_saved_to_documents) // P85-7
    fun downloadedFrom(host: String, date: LocalDate): String =
        localized(R.string.references_downloaded_from, host, localizedDate(date)) // P85-8
    val OPEN_SOURCE_LINK: String get() = localized(R.string.references_open_source_link) // P85-9
    val NETWORK_DENIED: String get() = localized(R.string.references_network_denied) // P85-10
    fun unreachable(host: String): String = localized(R.string.references_unreachable, host) // P85-11
    val TIMED_OUT: String get() = localized(R.string.references_timed_out) // P85-12
    val NOT_A_DOCUMENT: String get() = localized(R.string.references_not_a_document) // P85-13
    val NEEDS_SIGN_IN: String get() = localized(R.string.references_needs_sign_in) // P85-14
    fun serverError(code: Int): String = localized(R.string.references_server_error, code) // P85-15
    val REDIRECT_REFUSED: String get() = localized(R.string.references_redirect_refused) // P85-16
    fun alreadyHave(name: String): String = localized(R.string.references_already_have_on_asset, name) // P85-17
    fun alreadyHaveOnSupply(name: String): String = localized(R.string.references_already_have_on_supply, name) // P69-15
    fun alreadyHaveOnInstalledComponent(name: String): String =
        localized(R.string.references_already_have_on_installed_component, name) // P69-16

    /** #69 (C28, R69-13): P85-17 by the owner's kind; an asset keeps its shipped wording. */
    fun alreadyHave(owner: ReferenceOwner, name: String): String = when (owner) {
        is ReferenceOwner.OfAsset -> alreadyHave(name)
        is ReferenceOwner.OfSupplyItem -> alreadyHaveOnSupply(name)
        is ReferenceOwner.OfInstalledComponent -> alreadyHaveOnInstalledComponent(name)
    }
    val INTERRUPTED: String get() = localized(R.string.references_interrupted) // P85-18
    val LOCAL_ADDRESS: String get() = localized(R.string.references_local_address) // P85-19

    /**
     * P85-5's `{TYPE}`, keyed by the MIME type the sniff stores (§19's table): the nineteen ratified labels, and
     * nothing else. The sniff proves exactly these, so a type missing here is a programming error, not a line.
     * #102: file-format names, the same in every language, so they stay here rather than in the string resources.
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
        "text/markdown" to "Markdown", // l10n-ok: file-format name
        "text/csv" to "CSV",
        "text/tab-separated-values" to "TSV",
    )
}
