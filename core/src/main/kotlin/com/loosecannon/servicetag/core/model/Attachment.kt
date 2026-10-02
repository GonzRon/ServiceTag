package com.loosecannon.servicetag.core.model

import com.loosecannon.servicetag.core.references.MAX_REFERENCE_NAME_CHARS
import com.loosecannon.servicetag.core.references.MAX_REFERENCE_URI_CHARS

/** Bigger than this is a mistaken pick, not a product limit (spec §11.11): 256 MiB. */
const val MAX_ATTACHMENT_BYTES: Long = 268_435_456L

enum class AttachmentKind { PHOTO, LABEL_PHOTO, RECEIPT, MANUAL, WARRANTY, DOCUMENT, OTHER }

/** REFERENCE is 4B's `SAF_DOCUMENT` pointer; 4A writes MANAGED rows only. */
enum class AttachmentMode { MANAGED, REFERENCE }

/** No LOCAL member by the owner's ruling (spec §11.8): absent, not reserved. */
enum class StorageProvider { SAF_TREE, SAF_DOCUMENT }

/** Who a file belongs to. The owner model is stated once, in D14 (#69). */
sealed interface AttachmentOwner {
    data class OfAsset(val assetId: AssetId) : AttachmentOwner
    data class OfEvent(val eventId: EventId) : AttachmentOwner
    /** #69: a SupplyItem's own file, archived or not. */
    data class OfSupplyItem(val supplyId: SupplyId) : AttachmentOwner
    /** #69: an installed component's own file, current or removed. */
    data class OfInstalledComponent(val componentId: InstalledComponentId) : AttachmentOwner
}

/**
 * R67-11 as widened by R69-6, stated once: no role at all, or a file that is not an entry's. The use cases and the
 * codec ask this.
 *
 * A [DocumentRole] is allowed on an asset's, a SupplyItem's or an installed component's attachment, and never on an
 * event's. There is deliberately no `init` rule on [Attachment]: the use cases refuse a role on an event owner before
 * they write anything, and the backup reader refuses one in a file, which are the two places such a row could come
 * from.
 */
fun AttachmentOwner.accepts(role: DocumentRole?): Boolean = role == null || this !is AttachmentOwner.OfEvent

data class Attachment(
    val id: AttachmentId,
    val owner: AttachmentOwner,
    val kind: AttachmentKind,
    val mode: AttachmentMode = AttachmentMode.MANAGED,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,                 // lowercase hex, 64 chars
    val storageProvider: StorageProvider = StorageProvider.SAF_TREE,
    val storageLocator: String,         // provider-relative, see AttachmentLocator
    val capturedOn: String?,            // ISO date, user-editable
    val notes: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    /** Null is "no role" — every row written before #67. Metadata only: it never moves bytes. */
    val role: DocumentRole? = null,
    /** Null for every row that was not saved from a reference (#85). Write-once: never edited afterwards. */
    val source: AttachmentSource? = null,
)

/**
 * Where a saved document came from (#85, R85-2, R85-3): a snapshot taken when the fetch finished, with no
 * `reference_id` and no key — the reference may change or go, and this stays what it was.
 */
data class AttachmentSource(
    val uri: String,            // the reference's URI, verbatim (R85-2)
    val resolvedUri: String?,   // the redirect destination, scheme + authority + path; null when it did not move
    val retrievedAt: Long,      // epoch millis the fetch finished
    val name: String?,          // the reference's display name at that moment
)

/** The one home of the shape rule: null when the four fields are well formed (or all null), else the breach. */
fun attachmentSourceProblem(uri: String?, resolvedUri: String?, retrievedAt: Long?, name: String?): String? {
    if (uri == null) {
        return if (resolvedUri != null || retrievedAt != null || name != null) "a source with no uri" else null
    }
    if (retrievedAt == null || retrievedAt <= 0L) return "a source needs a positive retrieval time"
    if (!uri.startsWith(HTTPS, ignoreCase = true)) return "a source uri must be https"
    if (uri.length > MAX_REFERENCE_URI_CHARS) return "a source uri is over $MAX_REFERENCE_URI_CHARS characters"
    if (uri.any { it.isWhitespace() || it.isISOControl() }) return "a source uri has whitespace or a control character"
    if (resolvedUri != null) {
        if (!resolvedUri.startsWith(HTTPS, ignoreCase = true)) return "a resolved uri must be https"
        if (resolvedUri.length > MAX_REFERENCE_URI_CHARS) return "a resolved uri is over $MAX_REFERENCE_URI_CHARS characters"
        if (resolvedUri.any { it.isWhitespace() || it.isISOControl() }) return "a resolved uri has whitespace or a control character"
        if (resolvedUri.any { it == '?' || it == '#' || it == ';' }) return "a resolved uri keeps no query, fragment or path parameter"
        if ('@' in resolvedUri.substring(HTTPS.length).substringBefore('/')) return "a resolved uri keeps no userinfo"
        if (resolvedUri == uri) return "a resolved uri is stored only when it differs"
    }
    if (name != null) {
        if (name.isBlank()) return "a source name is not blank"
        if (name.length > MAX_REFERENCE_NAME_CHARS) return "a source name is over $MAX_REFERENCE_NAME_CHARS characters"
    }
    return null
}

private const val HTTPS = "https://"

/** The only question the thumbnail path asks. */
val Attachment.isImage: Boolean get() = mimeType.startsWith("image/")

/** One thing wrong with an attachment command. The list is closed (spec §4). */
sealed interface AttachmentProblem {
    data object BlankName : AttachmentProblem
    data object NoStore : AttachmentProblem
    data object StoreUnavailable : AttachmentProblem
    data class TooLarge(val limit: Long) : AttachmentProblem
    /** The thing you named is not there: the owner row, or the attachment row itself. */
    data object OwnerMissing : AttachmentProblem
    data object Unchanged : AttachmentProblem
}

/**
 * Where an attachment's bytes live, relative to the store's root. The display name is never in
 * the path: a rename must not move bytes, and a file listing must not read as a private label.
 */
object AttachmentLocator {
    private val EXTENSION = Regex("^[a-z0-9]{1,8}$")

    /**
     * The per-owner directory, and its one home: `assets/<asset-id>`, `events/<event-id>`,
     * `supply-items/<supply-id>` or `installed-components/<component-id>` (#69, H6: permanent names).
     */
    fun dirFor(owner: AttachmentOwner): String = when (owner) {
        is AttachmentOwner.OfAsset -> "assets/${owner.assetId.value}"
        is AttachmentOwner.OfEvent -> "events/${owner.eventId.value}"
        is AttachmentOwner.OfSupplyItem -> "supply-items/${owner.supplyId.value}"
        is AttachmentOwner.OfInstalledComponent -> "installed-components/${owner.componentId.value}"
    }

    fun forOwner(
        owner: AttachmentOwner,
        id: AttachmentId,
        displayName: String,
        mimeType: String,
    ): String = "${dirFor(owner)}/${id.value}.${extension(displayName, mimeType)}"

    /** The name's own extension wins; then what [MimeTypes] knows; then `bin`. */
    fun extension(displayName: String, mimeType: String): String {
        val fromName = displayName.substringAfterLast('.', "").lowercase()
        if (fromName.isNotEmpty() && EXTENSION.matches(fromName)) return fromName
        return MimeTypes.extensionFor(mimeType) ?: "bin"
    }

    /** What the backup reader checks: this locator could only have been built for this row. */
    fun matchesShape(locator: String, owner: AttachmentOwner, id: AttachmentId): Boolean =
        Regex("^${Regex.escape(dirFor(owner))}/${Regex.escape(id.value)}\\.[a-z0-9]{1,8}$")
            .matches(locator)
}

object MimeTypes {
    private const val DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    private const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    private val EXTENSIONS = mapOf(
        "image/jpeg" to "jpg",
        "image/png" to "png",
        "application/pdf" to "pdf",
        "application/zip" to "zip",
        "text/plain" to "txt",
        DOCX to "docx",
        XLSX to "xlsx",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" to "pptx",
        "application/vnd.oasis.opendocument.text" to "odt",
        "application/vnd.oasis.opendocument.spreadsheet" to "ods",
        "application/vnd.oasis.opendocument.presentation" to "odp",
        "application/msword" to "doc",
        "application/vnd.ms-excel" to "xls",
        "application/vnd.ms-powerpoint" to "ppt",
        "image/gif" to "gif",
        "image/webp" to "webp",
        "application/rtf" to "rtf",
        "text/markdown" to "md",
        "text/csv" to "csv",
        "text/tab-separated-values" to "tsv",
    )

    /** Already-compressed payloads, which the artifacts archive STOREs rather than deflating. */
    private val COMPRESSED = setOf("image/jpeg", "image/png", "application/pdf", "application/zip")

    /** Lowercased and stripped of parameters: `image/jpeg; charset=x` is `image/jpeg`. */
    fun normalise(mimeType: String): String =
        mimeType.substringBefore(';').trim().lowercase().ifEmpty { "application/octet-stream" }

    /** Derived from [EXTENSIONS], so the forward and inverse lookups cannot drift apart. */
    private val MIME_BY_EXTENSION = EXTENSIONS.entries.associate { (mime, ext) -> ext to mime }

    /**
     * Spellings [EXTENSIONS] does not carry, because [extensionFor] has to pick exactly one
     * extension per type and these are the other ones people's files are actually called.
     *
     * `jpeg` is the same payload as the table's `jpg`; `tif` and `htm` name types the model needs no
     * extension *for*, because nothing here ever writes one. A locator can still arrive spelled any
     * of those ways — the person's own file was — and telling a document provider
     * `application/octet-stream` for a file it could have shown is worse than telling it the truth.
     *
     * Consulted only after [MIME_BY_EXTENSION], so the table stays the one place a type is named
     * and an alias can never contradict it.
     */
    private val ALIASES = mapOf(
        "jpeg" to "image/jpeg",
        "tif" to "image/tiff",
        "htm" to "text/html",
    )

    fun extensionFor(mimeType: String): String? = EXTENSIONS[normalise(mimeType)]

    /**
     * The inverse of [extensionFor], widened by [ALIASES]: what to tell a document provider when a
     * locator's extension is all that is on hand. An extension neither table names is
     * `application/octet-stream`, which is what an unknown payload is.
     */
    fun mimeForExtension(ext: String): String = ext.lowercase()
        .let { key -> MIME_BY_EXTENSION[key] ?: ALIASES[key] }
        ?: "application/octet-stream"

    fun isCompressed(mimeType: String): Boolean = normalise(mimeType) in COMPRESSED
}

/** A default the person may override; never a constraint (spec §4). */
object AttachmentKinds {
    fun inferFrom(mimeType: String, fromCamera: Boolean): AttachmentKind = when {
        fromCamera -> AttachmentKind.PHOTO
        MimeTypes.normalise(mimeType).startsWith("image/") -> AttachmentKind.PHOTO
        MimeTypes.normalise(mimeType) == "application/pdf" -> AttachmentKind.DOCUMENT
        else -> AttachmentKind.OTHER
    }
}
