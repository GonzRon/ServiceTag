package com.loosecannon.servicetag.contacts

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Drives `:share-test-sender`'s `PickerActivity` (#72, C25; R72-24): a contact picker with **its own
 * package and UID** that seeds one fictional, account-less contact ("Example Rentals Ltd") and hands
 * its lookup URI back — with the one-shot read grant, or without it.
 *
 * ServiceTag's own pick seam (`rememberContactPick`) is aimed at it through [contract], the same
 * `ActivityResultContract<Void?, Uri?>` shape as `PickContact()`: whatever arrives crossed a real UID
 * boundary with whatever grant the sender attached, and nothing in this process builds the result.
 * Nothing is chosen, typed or pressed: the sender answers on its own.
 */
internal object TestContactSender {

    private const val PACKAGE = "com.loosecannon.servicetag.testsender"
    private const val PICKER = "$PACKAGE.PickerActivity"
    private const val INSTALL_TASK = ":share-test-sender:installDebug"

    /** How long the sender may take to answer. */
    private const val ANSWER_TIMEOUT_S = 10L

    /** The picker's three commands, as the strings its `command` extra takes. */
    enum class Command(val wire: String) {
        PICK_CONTACT("pick_contact"),
        PICK_CONTACT_NO_GRANT("pick_contact_no_grant"),
        FORGET_CONTACT("forget_contact"),
    }

    /**
     * The sender's two contacts permissions, granted to **its** package through the instrumentation's
     * own `UiAutomation` — no shell string names a permission, and ServiceTag is granted nothing.
     */
    fun grantItsPermissions() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.grantRuntimePermission(PACKAGE, Manifest.permission.READ_CONTACTS)
        automation.grantRuntimePermission(PACKAGE, Manifest.permission.WRITE_CONTACTS)
    }

    /** C15's seam aimed at the sender's picker: the explicit intent in, the result's URI out, as `PickContact()` parses it. */
    fun contract(command: Command): ActivityResultContract<Void?, Uri?> = object : ActivityResultContract<Void?, Uri?>() {
        override fun createIntent(context: Context, input: Void?): Intent = intentFor(command)

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
            intent.takeIf { resultCode == Activity.RESULT_OK }?.data
    }

    /**
     * `forget_contact`, waited for: the fixture is gone from the device before this returns, whatever
     * the case did. Registered on [activity]'s own registry, so the answer's arrival is the signal.
     */
    fun forget(activity: ComponentActivity) {
        val answered = CountDownLatch(1)
        var launcher: ActivityResultLauncher<Intent>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            launcher = activity.activityResultRegistry.register(
                "forget-contact",
                ActivityResultContracts.StartActivityForResult(),
            ) { answered.countDown() }
            launcher!!.launch(intentFor(Command.FORGET_CONTACT))
        }
        try {
            check(answered.await(ANSWER_TIMEOUT_S, TimeUnit.SECONDS)) {
                "The share test sender ($PACKAGE) did not answer forget_contact within $ANSWER_TIMEOUT_S s; " +
                    "`./gradlew :app:connectedDebugAndroidTest` installs it through $INSTALL_TASK."
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { launcher?.unregister() }
        }
    }

    private fun intentFor(command: Command): Intent =
        Intent().setClassName(PACKAGE, PICKER).putExtra("command", command.wire)
}
