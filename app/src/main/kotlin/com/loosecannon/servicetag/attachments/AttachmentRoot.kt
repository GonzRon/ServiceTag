package com.loosecannon.servicetag.attachments

import android.content.ContentResolver
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.loosecannon.servicetag.R
import com.loosecannon.servicetag.core.ports.AttachmentStore
import com.loosecannon.servicetag.l10n.localized

/**
 * One chosen tree, as [SafAttachmentStorage] needs to see it.
 *
 * This interface exists so the storage resolver is testable at all: spec §5.3 asks for a
 * `rootResolver: (treeUri) -> DocumentFile?` seam, and a `DocumentFile` cannot be constructed in
 * a plain JVM unit test (it is abstract, and every implementation wants a `Context`). The seam is
 * therefore one level up — a resolver hands back an `AttachmentRoot`, production's is a
 * [DocumentTreeRoot] over `DocumentFile.fromTreeUri`, the device-proof suite's is one over
 * `DocumentFile.fromFile`, and a JVM test's is a hand-written fake.
 */
interface AttachmentRoot {
    val displayName: String
    val authority: String
    fun canWrite(): Boolean
    fun store(): AttachmentStore
    /** The `content://` URI of a stored file, for `ACTION_VIEW`; null when it is not there. */
    fun viewUri(locator: String): Uri?
}

class DocumentTreeRoot(
    private val tree: DocumentFile,
    private val resolver: ContentResolver,
) : AttachmentRoot {
    override val displayName: String get() = tree.name ?: tree.uri.lastPathSegment ?: localized(R.string.attachments_folder_fallback)
    override val authority: String get() = tree.uri.authority ?: ""
    override fun canWrite(): Boolean = tree.canWrite()
    override fun store(): AttachmentStore = SafTreeAttachmentStore(tree, resolver)
    override fun viewUri(locator: String): Uri? =
        SafTreeAttachmentStore(tree, resolver).documentFor(locator)?.uri
}
