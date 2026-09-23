package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetReference
import com.loosecannon.servicetag.core.model.ReferenceId
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.ReferenceRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork
import com.loosecannon.servicetag.core.references.LinkDecision
import com.loosecannon.servicetag.core.references.LinkLaunchPolicy
import com.loosecannon.servicetag.core.references.MAX_REFERENCE_DESCRIPTION_CHARS
import com.loosecannon.servicetag.core.references.MAX_REFERENCE_URI_CHARS
import com.loosecannon.servicetag.core.references.ReferenceKinds
import com.loosecannon.servicetag.core.references.ReferenceText

/**
 * The only way a reference is ever created — a share, the "Add link" sheet, the loopback API and
 * MCP all arrive here — which is why **every refusal lives in this class** (I-2). A path that
 * carried its own copy of the scheme rule could be the one that forgot it.
 *
 * The step order is the contract, because each refusal has to stay reachable: structural validity
 * (I-10), the length cap, the tier, the name, the owner, then the second identity `(assetId, uri)`.
 * Nothing is generated or written before the last of them answers, so a refusal leaves no row, no
 * id and no transaction behind (I-8).
 *
 * [AddReferenceCommand.confirmedUnknownScheme] is set only by a UI that asked the person about an
 * unfamiliar scheme by name. The API and MCP never set it (plan §18.2), so over the wire an unknown
 * scheme is a refusal and never a confirmation — true by construction rather than by remembering.
 */
class AddReference(
    private val references: ReferenceRepository,
    private val assets: AssetRepository,
    private val policy: LinkLaunchPolicy,
    private val uow: UnitOfWork,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    suspend fun run(assetId: AssetId, cmd: AddReferenceCommand): ReferenceResult<AssetReference> {
        val uri = cmd.uri.trim()
        val scheme = policy.schemeOf(uri)
        if (scheme == null || !isStructurallyValid(uri, scheme)) {
            return ReferenceResult.Refused(ReferenceProblem.NotALink)
        }
        if (uri.length > MAX_REFERENCE_URI_CHARS) {
            return ReferenceResult.Refused(ReferenceProblem.UriTooLong)
        }
        when (val decision = policy.classify(uri)) {
            LinkDecision.Blocked -> return ReferenceResult.Refused(ReferenceProblem.SchemeBlocked)
            is LinkDecision.Unknown -> if (!cmd.confirmedUnknownScheme) {
                return ReferenceResult.Refused(
                    ReferenceProblem.UnknownSchemeNeedsConfirmation(decision.scheme),
                )
            }
            LinkDecision.Allowed -> Unit
        }
        val displayName = ReferenceText.sanitiseName(cmd.displayName)
        if (displayName.isEmpty()) return ReferenceResult.Refused(ReferenceProblem.BlankName)
        if (assets.get(assetId) == null) {
            return ReferenceResult.Refused(ReferenceProblem.OwnerMissing)
        }
        if (references.findByUri(assetId, uri) != null) {
            return ReferenceResult.Refused(ReferenceProblem.DuplicateUri)
        }

        val now = clock.nowMillis()
        val row = AssetReference(
            id = ReferenceId(ids.newId()),
            assetId = assetId,
            kind = ReferenceKinds.inferFrom(scheme),
            // Trimmed and nothing else: never re-encoded, never lowercased, never given a scheme
            // it did not arrive with (I-1). A query and a fragment survive byte for byte.
            uri = uri,
            displayName = displayName,
            description = cmd.description.trim().take(MAX_REFERENCE_DESCRIPTION_CHARS),
            scheme = scheme,
            createdAt = now,
            updatedAt = now,
        )
        uow.write { references.upsert(row) }
        return ReferenceResult.Ok(row)
    }

    /**
     * I-10, and it lives here rather than in `ShareTextParser` because two of the three entry
     * points never see that parser: "Add link" and `POST /v1/references` hand a URI straight to
     * this use case (plan §18.19). A rule enforced only where text is parsed out of a share would
     * be absent from exactly the paths a client drives.
     *
     * Three conditions. It parses as a single URI — no whitespace, no control characters, and
     * something after the scheme. It carries a scheme, which the caller has already read. And for
     * a **hierarchical** scheme — `http`, `https`, or any scheme written with a `//` authority —
     * it carries a non-empty host, so `https://` and `http:///path` are refused while
     * `joplin:x-callback-url/openNote?id=…` is not.
     *
     * Deliberately not `java.net.URI`: that parser answers "no host" for an authority it cannot
     * read as a server name — an underscore in a hostname is enough — which would refuse URIs the
     * phone opens perfectly well.
     */
    private fun isStructurallyValid(uri: String, scheme: String): Boolean {
        if (uri.any { it.isWhitespace() || it.isISOControl() }) return false
        val rest = uri.substring(scheme.length + 1)
        if (rest.isEmpty()) return false
        val hierarchical = rest.startsWith("//") || scheme == "http" || scheme == "https"
        return !hierarchical || hostOf(rest) != null
    }

    /** The authority's host, if the scheme-specific part declares one at all. */
    private fun hostOf(rest: String): String? {
        if (!rest.startsWith("//")) return null
        val afterSlashes = rest.substring(2)
        val end = afterSlashes.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = if (end < 0) afterSlashes else afterSlashes.substring(0, end)
        if (authority.isEmpty()) return null
        val hostAndPort = authority.substringAfterLast('@')          // userinfo is not the host
        val host = if (hostAndPort.startsWith("[")) {
            hostAndPort.substringBefore(']').removePrefix("[")       // an IPv6 literal
        } else {
            hostAndPort.substringBefore(':')                         // the port is not the host
        }
        return host.ifEmpty { null }
    }
}
