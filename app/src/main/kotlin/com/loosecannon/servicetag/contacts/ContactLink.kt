package com.loosecannon.servicetag.contacts

/**
 * #72 (C15; R72-4, M3): the one place a picked contact's row becomes the link a loan stores. Pure
 * Kotlin with no Android type, because the JVM tests run on the stub `android.jar`, where
 * `Contacts.getLookupUri` and `Uri` answer null (`isReturnDefaultValues`); `ContactLinkContractTest`
 * holds this codec against the framework's own `Contacts.getLookupUri` on the emulator.
 *
 * The lookup key is appended **verbatim**, exactly as `Contacts.getLookupUri(id, key)` does: that call
 * builds its path with `Uri.withAppendedPath`, which treats the key as already encoded. Encoding it
 * here would turn a `%2F` a key may legitimately hold into `%252F`, and the stored link would stop
 * matching the one the platform hands out.
 *
 * A key this function would have to encode to keep the rule is refused instead (null): an empty key,
 * or one holding `/`, `?`, `#`, whitespace, a control character or anything outside ASCII. So every
 * string it answers matches `CONTACT_LOOKUP_URI`, the one link rule the loan commands, the backup
 * check and the open policy share.
 */
object ContactLink {

    /** `ContactsContract.Contacts.CONTENT_LOOKUP_URI` with its trailing `/`, as the platform spells it. */
    const val LOOKUP_BASE = "content://com.android.contacts/contacts/lookup/"

    /**
     * `content://com.android.contacts/contacts/lookup/<key>/<id>` for the contact row [id] with
     * [lookupKey], the key appended unchanged; null when the key cannot be carried verbatim, or for a
     * negative id, which no contact row has.
     */
    fun lookupUriOf(id: Long, lookupKey: String): String? {
        if (id < 0 || !carriesVerbatim(lookupKey)) return null
        return "$LOOKUP_BASE$lookupKey/$id"
    }

    /** Printable ASCII only — no space, no control character, nothing past `~` — and none of `/ ? #`. */
    private fun carriesVerbatim(key: String): Boolean =
        key.isNotEmpty() && key.all { it in '!'..'~' && it !in RESERVED }

    private const val RESERVED = "/?#"
}
