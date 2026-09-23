package com.loosecannon.servicetag.core.references

/**
 * I-9, as two plain strings: a byte share's stream URI is **`content://` or it is refused**, and
 * its authority is never one of ServiceTag's own (spec §3.3, §4.3).
 *
 * `ContentResolver.openInputStream` resolves `file:` with ServiceTag's own uid, and
 * `${applicationId}.files` is a non-exported FileProvider only ServiceTag can read, so either
 * would turn intake into a self-exfiltration primitive.
 *
 * Android-free by construction — it takes the scheme and the authority, never a `Uri` — so `:app`
 * can ask it **before** it constructs the `ByteSource` and a refused URI is never opened at all.
 * [ownAuthorities] is configuration rather than a constant because the authorities are built from
 * the build's own application id.
 *
 * **Each entry is matched as a namespace, not a string.** The merged manifest publishes more than
 * the one provider this app declares — androidx.startup injects `InitializationProvider` under
 * `<applicationId>.androidx-startup`, and the next library to want one will inject another — so the
 * graph passes the application id and everything under it is ours. The trailing dot is part of the
 * rule: an unrelated authority that merely begins with the same letters is not one of ours.
 */
class StreamSourcePolicy(ownAuthorities: Set<String>) {

    private val own: Set<String> = ownAuthorities
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .toSet()

    /** True only for a `content` scheme whose authority is present and is under none of ours. */
    fun accepts(scheme: String?, authority: String?): Boolean {
        if (scheme?.trim()?.lowercase() != CONTENT) return false
        val host = authority?.trim()?.lowercase()
        if (host.isNullOrEmpty()) return false
        return own.none { host == it || host.startsWith("$it.") }
    }

    private companion object {
        const val CONTENT = "content"
    }
}
