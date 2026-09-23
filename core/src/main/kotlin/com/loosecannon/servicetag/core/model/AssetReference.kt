package com.loosecannon.servicetag.core.model

/**
 * What a reference points at — **three values, inferred from the scheme and never picked** (spec
 * §3.2). The byte-bearing kinds already exist as [AttachmentKind]; a second overlapping vocabulary
 * is how two catalogs start, so this one describes only the shape of the target and a reference's
 * subject ("OEM parts lookup") belongs in its description.
 *
 * Deliberately the opposite choice from the 2.6 tombstone's `LinkKind`
 * (`{JOPLIN, OBSIDIAN, LOGSEQ, WEB, OTHER}`), which was a per-app vocabulary that release retired.
 *
 * The rule that picks one from a scheme is not here: it is `ReferenceKinds.inferFrom`, beside the
 * other reference policies, because this file holds shapes and no rules over them.
 */
enum class ReferenceKind { WEB_URL, NOTE_LINK, OTHER }

/**
 * A URI on an asset: a pointer to something that lives somewhere else, with no bytes of its own
 * (I-3). It is never a tag target (I-4) and it can never change owner (I-6).
 *
 * [uri] is stored exactly as it was validated and is never rewritten (I-1); only [displayName],
 * [description] and [updatedAt] are mutable. [scheme] is derived from [uri] on every write, so the
 * two cannot disagree — it is the provider hint, never a provider account or a remote id.
 *
 * `(assetId, uri)` is unique (I-7); the same URI on two different assets is ordinary. There is
 * deliberately no `provenance` field: an in-app "Add link" and a share write identical rows,
 * because nothing in the product distinguishes them (D-21 C).
 */
data class AssetReference(
    val id: ReferenceId,
    val assetId: AssetId,
    val kind: ReferenceKind,
    val uri: String,
    val displayName: String,
    val description: String,
    val scheme: String,
    val createdAt: Long,
    val updatedAt: Long,
)
