package com.loosecannon.servicetag.contacts

/**
 * #72 (C15): the three columns a picked contact is read for, as primitives — the contact row's `_ID`,
 * its `LOOKUP_KEY` and its `DISPLAY_NAME`. Nothing else is ever read: no number, no address, no
 * account.
 */
data class ContactRow(val id: Long, val lookupKey: String?, val displayName: String?)

/**
 * #72 (C15): the boundary that reads one [ContactRow] at a picked URI — `ResolverContactRowQuery` on
 * the phone, a fake in a JVM test. Null when the provider has no row there; it may throw, a
 * `SecurityException` above all when no grant came with the pick.
 */
fun interface ContactRowQuery {
    fun row(uri: String): ContactRow?
}

/** What one pick reads as (C15). */
sealed interface PickedContact {
    /** The link a loan stores ([ContactLink]) and the display-name snapshot, trimmed. */
    data class Picked(val lookupUri: String, val displayName: String) : PickedContact

    /** The contact has no name to show — P72-30. */
    data object NoName : PickedContact

    /** No row, a refused read, or a key the codec cannot carry — P72-46. */
    data object Unreadable : PickedContact
}

/**
 * #72 (C15; R72-3, R72-4): what a picked contact reads as, from the one query the pick's grant covers.
 * Pure: the Android half is the [ContactRowQuery] it is handed.
 *
 * A read that throws — no grant, above all — or finds no row, or a key [ContactLink] would have to
 * encode, is [PickedContact.Unreadable] and is [log]ged; a blank display name is
 * [PickedContact.NoName]. A company-only contact's display name is its organisation (AC 2), which the
 * provider answers itself. Only the link and the name leave this class: nothing else is kept.
 *
 * **The log never carries the URI**: a lookup key can embed a contact's normalised name. So [log]
 * takes a fixed reason and nothing else — never the exception, and never its message: the platform's
 * permission denial and a provider's "Unknown URI" both name the URI they refused (B3 review MAJOR-1).
 * A failed read says only which kind of exception it was, by its class's simple name.
 */
class PickedContactReader(
    private val query: ContactRowQuery,
    private val log: (reason: String) -> Unit = {},
) {
    fun read(uri: String): PickedContact {
        val row = try {
            query.row(uri)
        } catch (denied: SecurityException) {
            log("a picked contact could not be read: the read was refused (${denied.javaClass.simpleName})") // l10n-ok: log line
            return PickedContact.Unreadable
        } catch (failed: RuntimeException) {
            log("a picked contact could not be read (${failed.javaClass.simpleName})") // l10n-ok: log line
            return PickedContact.Unreadable
        }
        if (row == null) {
            log("a picked contact could not be read: no row") // l10n-ok: log line
            return PickedContact.Unreadable
        }
        val link = row.lookupKey?.let { ContactLink.lookupUriOf(row.id, it) }
        if (link == null) {
            log("a picked contact could not be read: its lookup key cannot be stored") // l10n-ok: log line
            return PickedContact.Unreadable
        }
        val name = row.displayName?.trim().orEmpty()
        if (name.isEmpty()) return PickedContact.NoName
        return PickedContact.Picked(lookupUri = link, displayName = name)
    }
}
