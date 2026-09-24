package com.loosecannon.servicetag.share

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage

/**
 * Drives `:share-test-sender`, the test-only sharer with **its own package and UID** (#62), and
 * hands back the [ShareIntakeActivity] its share reached.
 *
 * The sender is started through the target context; it fires one explicit `ACTION_SEND` at the
 * share target from its own process and finishes. Whatever arrives here therefore crossed a real
 * UID boundary, with whatever grant the sender attached: nothing in this process builds the share.
 *
 * It never assumes: an absent sender is a failure that names the Gradle task which installs it.
 */
internal object TestSender {

    private const val PACKAGE = "com.loosecannon.servicetag.testsender"
    private const val ACTIVITY = "$PACKAGE.SenderActivity"
    private const val INSTALL_TASK = ":share-test-sender:installDebug"

    /**
     * How long Android may take to deliver the share and bring the intake up to RESUMED, and how
     * long [finish] waits for the intake to be destroyed.
     */
    private const val DELIVERY_TIMEOUT_MS = 10_000L
    private const val POLL_MS = 50L

    /** The sender's three commands, as the strings its `command` extra takes. */
    enum class Command(val wire: String) {
        SEND_TEXT("send_text"),
        SEND_FILE("send_file"),
        SEND_BAD_GRANT("send_bad_grant"),
    }

    /**
     * Asks the sender for one share and waits for the [ShareIntakeActivity] it reaches, in
     * `RESUMED`. The Compose rule sees every root in the process, so an intake still alive from an
     * earlier share is a named failure here rather than a two-match error in an assertion.
     */
    fun share(command: Command, text: String? = null): ShareIntakeActivity {
        val before = onMain { intakes(Stage.entries.filter { it != Stage.DESTROYED }) }
        check(before.isEmpty()) {
            "An earlier ShareIntakeActivity is still alive (${before.size}); an earlier share's " +
                "intake was not finished, so this share's assertions could read the wrong screen."
        }
        val request = Intent()
            .setClassName(PACKAGE, ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("command", command.wire)
        text?.let { request.putExtra("text", it) }

        try {
            InstrumentationRegistry.getInstrumentation().targetContext.startActivity(request)
        } catch (e: ActivityNotFoundException) {
            throw AssertionError(
                "The share test sender ($PACKAGE) is not installed on this device. " +
                    "`./gradlew :app:connectedDebugAndroidTest` installs it through $INSTALL_TASK; " +
                    "run that task first when a connected class is started any other way.",
                e,
            )
        }

        return awaitMain(
            "a ShareIntakeActivity in RESUMED within ${DELIVERY_TIMEOUT_MS / 1000} s of the " +
                "sender's ${command.wire}: Android did not deliver the share from the sender's UID",
        ) { intakes(listOf(Stage.RESUMED)).firstOrNull() }
    }

    /** True while [activity] is resumed and not on its way out. */
    fun isAlive(activity: ShareIntakeActivity): Boolean = onMain {
        !activity.isFinishing &&
            ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(activity) ==
            Stage.RESUMED
    }

    /** Finishes [activity] and waits until it is destroyed, so no case leaks into the next. */
    fun finish(activity: ShareIntakeActivity) {
        onMain { activity.finish() }
        awaitMain("the ShareIntakeActivity destroyed after finish()") {
            activity.takeIf { it.isDestroyed }
        }
    }

    /** The lifecycle registry is main-thread only. */
    private fun intakes(stages: List<Stage>): List<ShareIntakeActivity> {
        val registry = ActivityLifecycleMonitorRegistry.getInstance()
        return stages.flatMap { registry.getActivitiesInStage(it) }.filterIsInstance<ShareIntakeActivity>()
    }

    private fun <T : Any> awaitMain(what: String, probe: () -> T?): T {
        val deadline = SystemClock.uptimeMillis() + DELIVERY_TIMEOUT_MS
        while (true) {
            onMain(probe)?.let { return it }
            if (SystemClock.uptimeMillis() >= deadline) throw AssertionError("Expected $what.")
            SystemClock.sleep(POLL_MS)
        }
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
}
