package com.loosecannon.servicetag.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttachmentRulesTest {

    private val id = AttachmentId("att-1")
    private val ofAsset = AttachmentOwner.OfAsset(AssetId("a1"))
    private val ofEvent = AttachmentOwner.OfEvent(EventId("e1"))

    @Test fun locatorUsesTheOwnerDirectoryAndTheAttachmentId() {
        assertEquals(
            "assets/a1/att-1.pdf",
            AttachmentLocator.forOwner(ofAsset, id, "Owners Manual.pdf", "application/pdf"),
        )
        assertEquals(
            "events/e1/att-1.jpg",
            AttachmentLocator.forOwner(ofEvent, id, "IMG_0042.JPG", "image/jpeg"),
        )
        // the display name is never in the path (privacy, and a rename must not move bytes)
        assertFalse("Manual" in AttachmentLocator.forOwner(ofAsset, id, "Manual.pdf", "application/pdf"))
    }

    @Test fun theExtensionComesFromTheNameThenTheMimeTypeThenBin() {
        assertEquals("docx", AttachmentLocator.extension("Chemistry.docx", "application/pdf"))
        // a name with no usable extension falls through to the mime table
        assertEquals("pdf", AttachmentLocator.extension("scan", "application/pdf"))
        assertEquals("jpg", AttachmentLocator.extension("photo", "image/jpeg"))
        // nine characters, or anything that is not [a-z0-9], is not an extension
        assertEquals("bin", AttachmentLocator.extension("thing.abcdefghi", "application/unknown"))
        assertEquals("bin", AttachmentLocator.extension("thing.tar gz", "application/unknown"))
        assertEquals("bin", AttachmentLocator.extension("noextension", "application/unknown"))
    }

    @Test fun mimeTypesKnowsTheSevenExtensionsAndTheFourCompressedTypes() {
        assertEquals("zip", MimeTypes.extensionFor("application/zip"))
        assertEquals("txt", MimeTypes.extensionFor("text/plain"))
        assertEquals("xlsx", MimeTypes.extensionFor(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        ))
        assertEquals("jpg", MimeTypes.extensionFor("IMAGE/JPEG; charset=binary"))
        assertNull(MimeTypes.extensionFor("application/x-nothing"))
        assertTrue(MimeTypes.isCompressed("application/pdf"))
        assertTrue(MimeTypes.isCompressed("image/png"))
        assertFalse(MimeTypes.isCompressed("text/plain"))
        assertEquals("application/octet-stream", MimeTypes.normalise("  "))
    }

    @Test fun locatorShapeIsCheckedAgainstItsOwnerAndId() {
        assertTrue(AttachmentLocator.matchesShape("assets/a1/att-1.pdf", ofAsset, id))
        assertTrue(AttachmentLocator.matchesShape("events/e1/att-1.bin", ofEvent, id))
        assertFalse(AttachmentLocator.matchesShape("assets/a1/att-1.pdf", ofEvent, id))
        assertFalse(AttachmentLocator.matchesShape("assets/a2/att-1.pdf", ofAsset, id))
        assertFalse(AttachmentLocator.matchesShape("assets/a1/other.pdf", ofAsset, id))
        assertFalse(AttachmentLocator.matchesShape("assets/a1/att-1", ofAsset, id))
        assertFalse(AttachmentLocator.matchesShape("../assets/a1/att-1.pdf", ofAsset, id))
    }

    @Test fun kindIsInferredAndIsOnlyADefault() {
        assertEquals(AttachmentKind.PHOTO, AttachmentKinds.inferFrom("application/pdf", fromCamera = true))
        assertEquals(AttachmentKind.PHOTO, AttachmentKinds.inferFrom("image/heic", fromCamera = false))
        assertEquals(AttachmentKind.DOCUMENT, AttachmentKinds.inferFrom("application/pdf", fromCamera = false))
        assertEquals(AttachmentKind.OTHER, AttachmentKinds.inferFrom("application/zip", fromCamera = false))
    }

    @Test fun isImageIsTheOnlyThingTheThumbnailPathAsks() {
        val row = Attachment(
            id = id, owner = ofAsset, kind = AttachmentKind.PHOTO, displayName = "a.jpg",
            mimeType = "image/jpeg", sizeBytes = 10L, sha256 = "0".repeat(64),
            storageLocator = "assets/a1/att-1.jpg", capturedOn = null, createdAt = 1L, updatedAt = 1L,
        )
        assertTrue(row.isImage)
        assertFalse(row.copy(mimeType = "application/pdf").isImage)
        assertEquals(268_435_456L, MAX_ATTACHMENT_BYTES)
    }

    @Test fun mimeForExtensionIsTheInverseOfExtensionFor() {
        assertEquals("image/jpeg", MimeTypes.mimeForExtension("jpg"))
        assertEquals(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            MimeTypes.mimeForExtension("docx"),
        )
        // the case a provider hands back, and the one it never heard of
        assertEquals("image/png", MimeTypes.mimeForExtension("PNG"))
        assertEquals("application/octet-stream", MimeTypes.mimeForExtension("wat"))
        assertEquals("application/octet-stream", MimeTypes.mimeForExtension(""))
        // derived from the one table, so every extension the model can emit round-trips
        listOf("image/jpeg", "image/png", "application/pdf", "application/zip", "text/plain")
            .forEach { mime ->
                assertEquals(mime, MimeTypes.mimeForExtension(MimeTypes.extensionFor(mime)!!))
            }
    }

    /**
     * The other spellings the same files arrive with. `extensionFor` still emits exactly one
     * extension per type, so these widen the lookup without widening what the model writes.
     */
    @Test fun theCommonAlternativeSpellingsAreNotUnknownPayloads() {
        assertEquals("image/jpeg", MimeTypes.mimeForExtension("jpeg"))
        assertEquals("image/jpeg", MimeTypes.mimeForExtension("JPEG"))
        assertEquals("image/tiff", MimeTypes.mimeForExtension("tif"))
        assertEquals("text/html", MimeTypes.mimeForExtension("htm"))
        // and the aliases do not leak back the other way: one extension per type still
        assertEquals("jpg", MimeTypes.extensionFor("image/jpeg"))
        assertNull(MimeTypes.extensionFor("image/tiff"))
        assertNull(MimeTypes.extensionFor("text/html"))
    }

    /** Row 43 (#85 C31): every widened "Save as document" type is named by its one extension, both ways. */
    @Test fun everyWidenedDocumentTypeHasItsOneExtension() {
        val widened = mapOf(
            "image/gif" to "gif", "image/webp" to "webp", "application/rtf" to "rtf",
            "application/msword" to "doc", "application/vnd.ms-excel" to "xls", "application/vnd.ms-powerpoint" to "ppt",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation" to "pptx",
            "application/vnd.oasis.opendocument.text" to "odt",
            "application/vnd.oasis.opendocument.spreadsheet" to "ods",
            "application/vnd.oasis.opendocument.presentation" to "odp",
            "text/markdown" to "md", "text/csv" to "csv", "text/tab-separated-values" to "tsv",
        )
        for ((mime, ext) in widened) {
            assertEquals(ext, MimeTypes.extensionFor(mime), mime)
            assertEquals(mime, MimeTypes.mimeForExtension(ext), ext)
        }
        // the shipped pairs are unchanged
        val shipped = mapOf(
            "image/jpeg" to "jpg", "image/png" to "png", "application/pdf" to "pdf", "application/zip" to "zip",
            "text/plain" to "txt",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
        )
        shipped.forEach { (mime, ext) -> assertEquals(ext, MimeTypes.extensionFor(mime), mime) }
        // the kind at review is the shipped default: GIF and WebP a photo, the text types other (§19)
        assertEquals(AttachmentKind.PHOTO, AttachmentKinds.inferFrom("image/webp", fromCamera = false))
        assertEquals(AttachmentKind.OTHER, AttachmentKinds.inferFrom("text/markdown", fromCamera = false))
    }
}
