package com.loosecannon.servicetag.share

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat
import com.loosecannon.servicetag.core.model.MimeTypes
import com.loosecannon.servicetag.core.ports.ByteSource
import com.loosecannon.servicetag.core.references.LinkDecision
import com.loosecannon.servicetag.core.references.LinkLaunchPolicy
import com.loosecannon.servicetag.core.references.MAX_REFERENCE_URI_CHARS
import com.loosecannon.servicetag.core.references.ReferenceText
import com.loosecannon.servicetag.core.references.ShareTextParser
import com.loosecannon.servicetag.core.references.StreamSourcePolicy
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** What one `ACTION_SEND` turned out to be carrying (spec §2, §4.3, §4.4). */
sealed interface SharedItem {
    /** A URI-only share: EXTRA_TEXT or EXTRA_STREAM that parsed to a link. */
    data class Link(val uri: String, val suggestedName: String?) : SharedItem

    /** A byte share whose stream passed I-9. `size` is null when the provider reports none. */
    data class Bytes(
        val uri: Uri,
        val suggestedName: String,
        val mimeType: String,
        val size: Long?,
    ) : SharedItem

    /** Text that holds no URI (#43 AC 5, D-5). */
    data class PlainText(val text: String) : SharedItem

    /** Refused before anything was opened, or unreadable. */
    data class Refused(val reason: IntakeRefusal) : SharedItem
}

/**
 * Why intake stopped before a screen could offer anything. Each one maps to exactly one ratified
 * sentence (spec §10); none of them names a URI, a scheme, an authority or a path.
 */
enum class IntakeRefusal { STREAM_NOT_ACCEPTED, UNREADABLE, URI_TOO_LONG, SCHEME_BLOCKED }

/**
 * The Android lift's whole result: what the share turned out to be, the `Uri` the stream arrived
 * on (for [SharedItem]'s declared shape) and the one byte source a share ever builds.
 *
 * One value, so nothing round-trips: [readShare] builds this once and both the declared
 * [SharedItem] and the state machine's input are read straight off it. A JVM test can construct
 * one with `streamUri = null`, which is every case that does not need a real `Uri`.
 */
internal data class SharedShare(
    val content: ShareContent,
    val streamUri: Uri?,
    val bytes: ByteSource?,
)

/**
 * The exported activity's one reader. **Only `EXTRA_STREAM`, `EXTRA_TEXT`, `EXTRA_SUBJECT` and
 * `EXTRA_TITLE` are read, all as data; every other extra is ignored** (spec §4.1).
 *
 * The stream extra is taken through [IntentCompat], which is the API-33+ typed
 * `getParcelableExtra(name, Uri::class.java)` where the platform has it and a checked read below
 * it — **never the deprecated overload, which returns whatever a hostile parcel names**. This
 * activity is exported, so the whole extras read sits inside one guard: a bundle that throws on
 * unparcelling is "could not read what was shared", not a crash on the way up — the same
 * treatment the launcher activity already gives its own extras.
 *
 * **This blocks**: on the byte arm it is two binder round trips to a provider that may be remote,
 * and on the uri-list arm up to 64 KiB of stream. It is called from the view model on an IO
 * context and never from `onCreate`, so a cloud-backed provider cannot hold the first frame.
 *
 * Every rule below this line is in [decideShare], which takes no Android type at all.
 */
internal fun readShare(
    intent: Intent,
    resolver: ContentResolver,
    streamPolicy: StreamSourcePolicy,
    linkPolicy: LinkLaunchPolicy,
): SharedShare {
    val streamUri: Uri?
    val declaredType: String?
    val text: String?
    val subject: String?
    val title: String?
    try {
        streamUri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        declaredType = intent.type
        text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        title = intent.getCharSequenceExtra(Intent.EXTRA_TITLE)?.toString()
    } catch (_: Exception) {
        return SharedShare(ShareContent.Refused(IntakeRefusal.UNREADABLE), null, null)
    }

    val content = decideShare(
        declaredType = declaredType,
        stream = streamUri?.let { ResolverStream(it, resolver) },
        text = text,
        subject = subject,
        title = title,
        streamPolicy = streamPolicy,
        linkPolicy = linkPolicy,
    )
    // The source is built only for an accepted byte share, so a refused or textual share has
    // nothing that could open a stream even by accident.
    val bytes = streamUri
        ?.takeIf { content is ShareContent.Bytes }
        ?.let { resolver.byteSourceFor(it) }
    return SharedShare(content, streamUri, bytes)
}

/**
 * The declared boundary shape (brief B03, *Interfaces*), which no later brief may duplicate. It is
 * **one mapping, in one direction, with named arguments**, so a field added to either hierarchy is
 * a compile error here rather than a value silently dropped; `SharedItemShapeTest` pins the two
 * field sets against each other as well.
 */
fun readSharedItem(
    intent: Intent,
    resolver: ContentResolver,
    streamPolicy: StreamSourcePolicy,
    linkPolicy: LinkLaunchPolicy,
): SharedItem = readShare(intent, resolver, streamPolicy, linkPolicy).asSharedItem()

internal fun SharedShare.asSharedItem(): SharedItem = when (content) {
    is ShareContent.Link -> SharedItem.Link(
        uri = content.uri,
        suggestedName = content.suggestedName,
    )
    is ShareContent.Bytes -> SharedItem.Bytes(
        // Non-null by construction: the bytes arm is only reached with a stream in hand.
        uri = checkNotNull(streamUri),
        suggestedName = content.suggestedName,
        mimeType = content.mimeType,
        size = content.size,
    )
    is ShareContent.PlainText -> SharedItem.PlainText(text = content.text)
    is ShareContent.Refused -> SharedItem.Refused(reason = content.reason)
}

/**
 * [SharedItem] without the `Uri`, which is the whole of it that has a rule: `android.net.Uri` and
 * `Intent` are stubs on the JVM unit-test classpath (`unitTests.isReturnDefaultValues`), so a
 * precedence rule expressed over them could only ever be proved on a device. The reader above
 * pairs the accepted stream back with its `Uri`.
 */
internal sealed interface ShareContent {
    data class Link(val uri: String, val suggestedName: String?) : ShareContent
    data class Bytes(val suggestedName: String, val mimeType: String, val size: Long?) : ShareContent
    data class PlainText(val text: String) : ShareContent
    data class Refused(val reason: IntakeRefusal) : ShareContent
}

/**
 * What a provider said when it was asked about a stream, as plain values (I-11, #63). The two ways
 * of saying nothing are named rather than arriving as a row of nulls, because a stream nobody can
 * be asked about is a read failure at read time, not a form with an empty Received line.
 */
internal sealed interface FactsAnswer {
    /**
     * The query returned no cursor at all, and did not throw: there is no such provider, or package
     * visibility hides it from this app because that package has never granted it anything.
     */
    data object NoCursor : FactsAnswer

    /** A cursor came back, and it holds no row. */
    data object NoRow : FactsAnswer
}

/** The provider's answers about a stream, as plain values. Read only after I-9 has accepted. */
internal data class StreamFacts(
    val displayName: String?,
    val mimeType: String?,
    val size: Long?,
) : FactsAnswer

/**
 * What the reader needs of an `EXTRA_STREAM`. [scheme] and [authority] are the two strings I-9 is
 * asked about and **cost no provider call**, so the predicate can run before anything is opened.
 */
internal interface SharedStream {
    val scheme: String?
    val authority: String?
    fun facts(): FactsAnswer
    fun readAtMost(limit: Int): ByteArray
}

/**
 * How much of a `text/uri-list` stream is read before the first line is taken (plan §18.20). It is
 * **not** `MAX_SHARE_TEXT_CHARS`, which is `:core`'s cap on scanning an `EXTRA_TEXT`: this one
 * bounds a *stream*, in bytes, and a uri-list is a list that may legitimately be longer than any
 * one URI in it — so the 2,048-character cap applies to the extracted URI alone.
 */
internal const val MAX_URI_LIST_BYTES = 65_536

private const val URI_LIST = "text/uri-list"

/**
 * **The precedence rule, and the declared type comes first for exactly one type** (plan §18.15).
 *
 * 1. `text/uri-list` is a list of URIs by definition, so it is **text** whichever extra carries
 *    it. A sharer may legitimately deliver one as a stream, and an unconditional
 *    `EXTRA_STREAM`-first rule would file it in the attachment folder as a document.
 * 2. Otherwise an `EXTRA_STREAM` is **bytes** — including a `text/plain` stream, which is an
 *    ordinary document: `MimeTypes.EXTENSIONS` maps `text/plain` to `txt` for exactly that case,
 *    and a shared maintenance log decoded as prose would find no URI, be offered as a note and
 *    never be stored.
 * 3. Otherwise `EXTRA_TEXT` is parsed as text. That is the arm a `text/plain` share with no stream
 *    takes, which is the URL-share case D-6 put `text/plain` in the filter for.
 */
internal fun decideShare(
    declaredType: String?,
    stream: SharedStream?,
    text: String?,
    subject: String?,
    title: String?,
    streamPolicy: StreamSourcePolicy,
    linkPolicy: LinkLaunchPolicy,
): ShareContent {
    val carried = text?.takeIf { it.isNotBlank() }
    if (MimeTypes.normalise(declaredType.orEmpty()) == URI_LIST) {
        return if (carried != null) {
            textContent(carried, subject, title, linkPolicy)
        } else {
            uriListStream(stream, subject, title, streamPolicy, linkPolicy)
        }
    }
    if (stream != null) {
        return byteStream(stream, declaredType, subject, title, streamPolicy)
    }
    // No stream and no text at all: there is nothing to offer as a note, and a blank "Received"
    // line under "That is not a link." is a worse answer than the read failure it actually is.
    if (carried == null) return ShareContent.Refused(IntakeRefusal.UNREADABLE)
    return textContent(carried, subject, title, linkPolicy)
}

/**
 * **The order inside the bytes arm is the contract.** I-9 answers first, on two strings; only a
 * `true` answer buys a provider query, so a refused URI is never opened at all — that is the call
 * site the invariant names, and a reader that asked for the name and size first would open
 * something it then refused. The query's answer comes second (I-11, #63): no cursor, or a cursor
 * with no row, is a read failure before any open, never a form for a stream that cannot be read.
 */
private fun byteStream(
    stream: SharedStream,
    declaredType: String?,
    subject: String?,
    title: String?,
    streamPolicy: StreamSourcePolicy,
): ShareContent {
    if (!streamPolicy.accepts(stream.scheme, stream.authority)) {
        return ShareContent.Refused(IntakeRefusal.STREAM_NOT_ACCEPTED)
    }
    val answer = try {
        stream.facts()
    } catch (_: Exception) {
        return ShareContent.Refused(IntakeRefusal.UNREADABLE)
    }
    // I-11: a stream whose facts cannot be read is unreadable now, so the first frame after the
    // read is already the dead end. Only a row buys a form; a row may still lack a size.
    val facts = when (answer) {
        FactsAnswer.NoCursor -> return ShareContent.Refused(IntakeRefusal.UNREADABLE)
        FactsAnswer.NoRow -> return ShareContent.Refused(IntakeRefusal.UNREADABLE)
        is StreamFacts -> answer
    }
    return ShareContent.Bytes(
        // A provider names its own files, so the name is a path-free basename before anything
        // else looks at it; the two text extras are capped and sanitised the same way.
        suggestedName = suggestedName(
            null,
            subject,
            title,
            ReferenceText.sanitiseFilename(facts.displayName.orEmpty()),
        ).orEmpty(),
        mimeType = MimeTypes.normalise(facts.mimeType ?: declaredType.orEmpty()),
        size = facts.size,
    )
}

/**
 * A `text/uri-list` that arrived as a stream, under **two separate caps** (plan §18.20): at most
 * [MAX_URI_LIST_BYTES] are read and decoded as UTF-8, the first line that is neither blank nor a
 * `#` comment is taken, and only that extracted URI is measured against
 * [MAX_REFERENCE_URI_CHARS]. Nothing here ever reaches the attachment path.
 */
private fun uriListStream(
    stream: SharedStream?,
    subject: String?,
    title: String?,
    streamPolicy: StreamSourcePolicy,
    linkPolicy: LinkLaunchPolicy,
): ShareContent {
    if (stream == null) return ShareContent.Refused(IntakeRefusal.UNREADABLE)
    if (!streamPolicy.accepts(stream.scheme, stream.authority)) {
        return ShareContent.Refused(IntakeRefusal.STREAM_NOT_ACCEPTED)
    }
    val bytes = try {
        stream.readAtMost(MAX_URI_LIST_BYTES + 1)
    } catch (_: Exception) {
        return ShareContent.Refused(IntakeRefusal.UNREADABLE)
    }
    if (bytes.size > MAX_URI_LIST_BYTES) return ShareContent.Refused(IntakeRefusal.UNREADABLE)
    val body = decodeUtf8(bytes) ?: return ShareContent.Refused(IntakeRefusal.UNREADABLE)
    // The format's own line endings are CRLF, so every line is trimmed before it is read.
    val first = body.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
        ?: return ShareContent.Refused(IntakeRefusal.UNREADABLE)
    if (first.length > MAX_REFERENCE_URI_CHARS) {
        return ShareContent.Refused(IntakeRefusal.URI_TOO_LONG)
    }
    return textContent(first, subject, title, linkPolicy)
}

/**
 * The text arm, and the one place raw text may become a link. `null` from the parser means the
 * share holds no URI at all, and #35's rule is that raw text is **never** stored as one — so it
 * becomes prose the screen offers as a journal note, never a URI with a scheme guessed onto it.
 */
private fun textContent(
    raw: String,
    subject: String?,
    title: String?,
    linkPolicy: LinkLaunchPolicy,
): ShareContent {
    val parsed = ShareTextParser.firstUri(raw) ?: return ShareContent.PlainText(raw.trim())
    if (parsed.uri.length > MAX_REFERENCE_URI_CHARS) {
        return ShareContent.Refused(IntakeRefusal.URI_TOO_LONG)
    }
    if (linkPolicy.classify(parsed.uri) == LinkDecision.Blocked) {
        return ShareContent.Refused(IntakeRefusal.SCHEME_BLOCKED)
    }
    return ShareContent.Link(parsed.uri, suggestedName(parsed.label, subject, title, null))
}

/**
 * The Markdown label, then `EXTRA_SUBJECT`, then `EXTRA_TITLE`, then the stream's display name —
 * **each sanitised and capped at 200 characters** before it can become a display name, because
 * none of the four was written by anyone here.
 */
private fun suggestedName(
    label: String?,
    subject: String?,
    title: String?,
    streamName: String?,
): String? = sequenceOf(label, subject, title, streamName)
    .mapNotNull { candidate -> candidate?.let(ReferenceText::sanitiseName)?.takeIf { it.isNotEmpty() } }
    .firstOrNull()

/** Strict: a byte sequence that is not UTF-8 is not a uri-list, and is never guessed at. */
private fun decodeUtf8(bytes: ByteArray): String? = try {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (_: CharacterCodingException) {
    null
}

/**
 * The one Android implementation of [SharedStream]. [scheme] and [authority] are read off the
 * `Uri` itself and touch no provider, so [readShare] can hand this to the predicate before a
 * single resolver call has been made.
 */
private class ResolverStream(
    private val uri: Uri,
    private val resolver: ContentResolver,
) : SharedStream {

    override val scheme: String? get() = uri.scheme
    override val authority: String? get() = uri.authority

    /**
     * A transcription and nothing more: what the resolver handed back, in [FactsAnswer]'s terms.
     * What each answer means for the share is [decideShare]'s, where a JVM test can reach it.
     */
    override fun facts(): FactsAnswer {
        var displayName: String? = null
        var size: Long? = null
        val cursor = resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        ) ?: return FactsAnswer.NoCursor
        cursor.use {
            if (!it.moveToFirst()) return FactsAnswer.NoRow
            val nameAt = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameAt >= 0 && !it.isNull(nameAt)) displayName = it.getString(nameAt)
            val sizeAt = it.getColumnIndex(OpenableColumns.SIZE)
            if (sizeAt >= 0 && !it.isNull(sizeAt)) size = it.getLong(sizeAt)
        }
        return StreamFacts(displayName, resolver.getType(uri), size)
    }

    override fun readAtMost(limit: Int): ByteArray =
        openStream().use { input -> input.readAtMost(limit) }

    private fun openStream(): InputStream =
        resolver.openInputStream(uri) ?: throw IOException("the provider returned no stream")
}

/** `InputStream.readNBytes` is API 33; this is the same loop, and the floor here is API 26. */
private fun InputStream.readAtMost(limit: Int): ByteArray {
    val buffer = ByteArray(limit)
    var filled = 0
    while (filled < limit) {
        val read = read(buffer, filled, limit - filled)
        if (read < 0) break
        filled += read
    }
    return buffer.copyOf(filled)
}

/**
 * The one byte source a share ever builds, from the `Uri` the reader accepted. **A share always
 * copies**: no persistable grant is ever asked for, because the platform refuses one on an
 * `ACTION_SEND` grant regardless, so nothing that expires is retained and ServiceTag stays clear
 * of the 512 persisted-grant cap. The grant itself lives until the receiving task finishes, so a
 * recreation mid-intake either still reads it or fails cleanly and writes nothing.
 */
internal fun ContentResolver.byteSourceFor(uri: Uri): ByteSource = ByteSource {
    openInputStream(uri) ?: throw IOException("the provider returned no stream")
}
