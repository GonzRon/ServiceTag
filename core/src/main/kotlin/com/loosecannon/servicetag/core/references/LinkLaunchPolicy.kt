package com.loosecannon.servicetag.core.references

/**
 * What may be handed to the system for opening, and what may never be (spec §4.2).
 *
 * [Unknown] carries the scheme because the person is asked about it by name before an unfamiliar
 * link is saved; [Allowed] and [Blocked] carry nothing, there being nothing to say.
 */
sealed interface LinkDecision {
    data object Allowed : LinkDecision
    data object Blocked : LinkDecision
    data class Unknown(val scheme: String) : LinkDecision
}

/**
 * The three tiers, reintroduced as new `:core` code after the note-link feature took the original
 * away at 2.6 — both lists #35's shipped ones, verbatim (spec §4.2).
 *
 * **One instance answers both save and launch**, which is what makes "a URI that was legal when it
 * was saved and is not now is shown and refused, never launched" true by construction rather than
 * by two copies of a list agreeing.
 *
 * [classify] is total and says nothing about a URI's *shape*: structural validity (I-10) is a
 * separate rule, checked before the tier lookup by `AddReference`, because a URI with no scheme has
 * no tier to be judged by. So a schemeless string classifies [LinkDecision.Blocked] here — nothing
 * may ever be launched from one — while the save path refuses it as "not a link" instead.
 */
class LinkLaunchPolicy {

    /**
     * The scheme, lowercased, or null when the text does not begin with one. RFC 3986's production:
     * a letter, then letters, digits, `+`, `-` or `.`, then a colon — which is why `android-app`
     * is a scheme and `notaurl` is not.
     */
    fun schemeOf(uri: String): String? =
        SCHEME.find(uri.trim())?.groupValues?.get(1)?.lowercase()

    fun classify(uri: String): LinkDecision {
        val scheme = schemeOf(uri) ?: return LinkDecision.Blocked
        return when (scheme) {
            in ALLOWED -> LinkDecision.Allowed
            in BLOCKED -> LinkDecision.Blocked
            else -> LinkDecision.Unknown(scheme)
        }
    }

    companion object {
        private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.\\-]*):")

        /** Launched without asking: `http`/`https` plus the three note apps #35 named. */
        val ALLOWED: Set<String> = setOf("http", "https", "joplin", "obsidian", "logseq")

        /**
         * Refused at save and at launch, with no confirmation offered. `javascript` executes,
         * `file` and `content` are read with ServiceTag's own uid, `intent` and `android-app` are
         * component launches wearing a URI, and the last three are actions rather than documents.
         */
        val BLOCKED: Set<String> = setOf(
            "javascript", "file", "content", "intent", "android-app", "tel", "sms", "mailto",
        )
    }
}
