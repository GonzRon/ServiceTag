package com.loosecannon.servicetag.testsender

import android.app.Activity
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import android.util.Log

/**
 * A contact picker with its own UID (#72, C25; R72-24). ServiceTag's connected
 * `ContactGrantBoundaryTest` aims ServiceTag's own pick seam at this activity instead of the system
 * picker, so the contact a pick hands over really crosses from another UID, with whatever grant this
 * activity attaches. It reads the `command` extra, answers with one result and finishes; it draws
 * nothing and asks for nothing back.
 *
 * | command                 | what it does                                                             |
 * |-------------------------|--------------------------------------------------------------------------|
 * | `pick_contact`          | seeds the fixture, then `RESULT_OK` with its lookup URI and `FLAG_GRANT_READ_URI_PERMISSION` |
 * | `pick_contact_no_grant` | seeds the fixture, then `RESULT_OK` with its lookup URI and no grant     |
 * | `forget_contact`        | deletes the fixture, then `RESULT_OK` with no data                       |
 *
 * **The fixture** is one fictional, organisation-only raw contact, "Example Rentals Ltd", with **no
 * account** — the platform provider keeps it on the device, so nothing syncs and no account is needed.
 * It is written by `ContentResolver.applyBatch` from this app's own UID, which holds the contacts
 * permissions the test grants it; a company-only contact's display name is its organisation, which is
 * what ServiceTag must read (AC 2). Seeding forgets any earlier copy first, so there is only ever one.
 *
 * The lookup URI is the provider's own (`RawContacts.getContactLookupUri`), exactly what the system
 * picker returns: `content://com.android.contacts/contacts/lookup/<key>/<id>`.
 */
class PickerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A recreation is not a second request: one start, one answer.
        if (savedInstanceState == null) answer(intent.getStringExtra(EXTRA_COMMAND))
        finish()
    }

    private fun answer(command: String?) {
        when (command) {
            PICK_CONTACT -> setResult(
                RESULT_OK,
                Intent().setData(seed()).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
            // No grant flag: the URI is real, but ServiceTag may not read it.
            PICK_CONTACT_NO_GRANT -> setResult(RESULT_OK, Intent().setData(seed()))
            FORGET_CONTACT -> {
                forget()
                setResult(RESULT_OK)
            }
            else -> {
                Log.w(TAG, "unknown command '$command'; nothing was picked")
                setResult(RESULT_CANCELED)
            }
        }
    }

    /** The fixture, fresh: any earlier copy forgotten, one account-less raw contact written. */
    private fun seed(): Uri {
        forget()
        val operations = arrayListOf(
            ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                .withValue(RawContacts.ACCOUNT_TYPE, null)
                .withValue(RawContacts.ACCOUNT_NAME, null)
                .build(),
            ContentProviderOperation.newInsert(Data.CONTENT_URI)
                .withValueBackReference(Data.RAW_CONTACT_ID, 0)
                .withValue(Data.MIMETYPE, Organization.CONTENT_ITEM_TYPE)
                .withValue(Organization.COMPANY, FIXTURE_NAME)
                .build(),
        )
        val rawContact = contentResolver.applyBatch(ContactsContract.AUTHORITY, operations)[0].uri
            ?: error("the provider wrote no raw contact")
        return RawContacts.getContactLookupUri(contentResolver, rawContact)
            ?: error("the provider has no lookup URI for the fixture")
    }

    /** Every account-less raw contact whose organisation is the fixture's name, deleted outright. */
    private fun forget() {
        fixtureRawContacts().forEach { id ->
            val uri = ContentUris.withAppendedId(RawContacts.CONTENT_URI, id).buildUpon()
                .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
                .build()
            contentResolver.delete(uri, null, null)
        }
    }

    private fun fixtureRawContacts(): List<Long> =
        contentResolver.query(
            Data.CONTENT_URI,
            arrayOf(Data.RAW_CONTACT_ID),
            "${Data.MIMETYPE} = ? AND ${Organization.COMPANY} = ? AND ${RawContacts.ACCOUNT_TYPE} IS NULL",
            arrayOf(Organization.CONTENT_ITEM_TYPE, FIXTURE_NAME),
            null,
        )?.use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) } }.orEmpty()

    private companion object {
        const val TAG = "ShareTestSenderPicker"

        const val EXTRA_COMMAND = "command"

        const val PICK_CONTACT = "pick_contact"
        const val PICK_CONTACT_NO_GRANT = "pick_contact_no_grant"
        const val FORGET_CONTACT = "forget_contact"

        /** Fictional; never a real person's or business's name. */
        const val FIXTURE_NAME = "Example Rentals Ltd"
    }
}
