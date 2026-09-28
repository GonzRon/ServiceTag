package com.loosecannon.servicetag.contacts

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable

/**
 * #72 (C15): the Android half of a pick. It reads `_ID`, `LOOKUP_KEY` and `DISPLAY_NAME` at the picked
 * URI through the one-shot grant the picker attached, and hands them on as primitives. ServiceTag
 * holds no contacts permission (AC 12), so this read works only while that grant lasts — which is why
 * it runs once, straight after the pick, and never again.
 */
class ResolverContactRowQuery(private val resolver: ContentResolver) : ContactRowQuery {
    override fun row(uri: String): ContactRow? =
        resolver.query(Uri.parse(uri), COLUMNS, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val id = cursor.getColumnIndex(ContactsContract.Contacts._ID)
            val key = cursor.getColumnIndex(ContactsContract.Contacts.LOOKUP_KEY)
            val name = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
            if (id < 0 || cursor.isNull(id)) return@use null
            ContactRow(
                id = cursor.getLong(id),
                lookupKey = key.takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getString),
                displayName = name.takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getString),
            )
        }

    private companion object {
        val COLUMNS = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.LOOKUP_KEY,
            ContactsContract.Contacts.DISPLAY_NAME,
        )
    }
}

/** #72 (C15): one launch of the contact picker; built by [rememberContactPick]. */
class ContactPick internal constructor(val launch: () -> Unit)

/**
 * #72 (C15; C25's seam): the system contact picker, launched through [contract] — `PickContact()` on
 * the phone; `ContactGrantBoundaryTest` aims it at the test-only sender's picker instead, so the grant
 * really crosses from another UID. The result's string reaches [onPicked] in the result callback and
 * nowhere else; a cancelled pick says nothing. A phone with no picker at all is [onNoPicker] (P72-45).
 *
 * No persistable grant is taken: the name is read once, now, and only the link and the name are kept.
 */
@Composable
fun rememberContactPick(
    onPicked: (uri: String) -> Unit,
    onNoPicker: () -> Unit,
    contract: ActivityResultContract<Void?, Uri?> = ActivityResultContracts.PickContact(),
): ContactPick {
    val launcher = rememberLauncherForActivityResult(contract) { uri -> uri?.toString()?.let(onPicked) }
    return ContactPick(
        launch = {
            try {
                launcher.launch(null)
            } catch (missing: ActivityNotFoundException) {
                onNoPicker()
            }
        },
    )
}

/**
 * #72 (C15): "Open contact". `Intent(ACTION_VIEW, …)` is built only once [ContactOpenPolicy] has
 * allowed the link; a phone with no handler, or a handler that refuses the call, answers false, and
 * the section says P72-20 ([contactOpenFailure]). A link that no longer resolves opens Contacts' own
 * "not found", which this app cannot see without a contacts permission (R72-4).
 */
object ContactOpener {
    fun open(activity: Activity, uri: String?): Boolean = openContact(uri) { allowed ->
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(allowed)))
            true
        } catch (missing: ActivityNotFoundException) {
            false
        } catch (refused: SecurityException) {
            false
        }
    }
}
