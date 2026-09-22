package com.loosecannon.servicetag.reminders

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Structural proofs read straight off `app/src/main/AndroidManifest.xml` — no connected run
 * needed (master plan §12, spec §5.1/§5.4). This brief's own counts (exactly four receivers,
 * exactly the two shipped exported activities) are asserted against the **source** manifest, the
 * same file the review gate's own greps name: the *merged* manifest this repository's Gradle
 * setup does produce for `testDebugUnitTest` also carries library-injected components — a
 * `ProfileInstallReceiver` and a synthetic `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, confirmed
 * by inspecting `app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml`
 * — that are none of this brief's business and would make an exact-count assertion flaky. The one
 * fact the brief asks be checked against both files — no exact-alarm permission — is checked
 * against the merged manifest too, separately, below.
 */
class ManifestContractTest {

    private val manifest = readSourceManifest()

    /** Invariant 52, #24 AC 2: neither exact-alarm permission, ever; `POST_NOTIFICATIONS` exactly once. */
    @Test
    fun neitherExactAlarmPermissionAndPostNotificationsExactlyOnce() {
        val permissionNames = manifest.elements("uses-permission").map { it.androidAttr("name") }

        assertTrue(
            "SCHEDULE_EXACT_ALARM must never be declared (ledger A12)",
            "android.permission.SCHEDULE_EXACT_ALARM" !in permissionNames,
        )
        assertTrue(
            "USE_EXACT_ALARM must never be declared (ledger A12)",
            "android.permission.USE_EXACT_ALARM" !in permissionNames,
        )
        assertEquals(
            1,
            permissionNames.count { it == "android.permission.POST_NOTIFICATIONS" },
        )
    }

    /**
     * Invariant 54: every `<receiver>` is non-exported, and this brief declares the four platform
     * receivers only — the quick-action receiver is B07's own manifest addition (its own brief),
     * not this one's, so four is the right count at this brief's own gate.
     */
    @Test
    fun everyReceiverIsNonExportedAndThereAreFour() {
        val receivers = manifest.elements("receiver")
        assertEquals(4, receivers.size)
        receivers.forEach { receiver ->
            assertEquals(
                "every receiver must be android:exported=\"false\": ${receiver.androidAttr("name")}",
                "false",
                receiver.androidAttr("exported"),
            )
        }

        val declaredNames = receivers.map { it.androidAttr("name") }.toSet()
        assertEquals(
            setOf(
                "com.loosecannon.servicetag.reminders.BootCompletedReceiver",
                "com.loosecannon.servicetag.reminders.TimeSetReceiver",
                "com.loosecannon.servicetag.reminders.TimezoneChangedReceiver",
                "com.loosecannon.servicetag.reminders.DateChangedReceiver",
            ),
            declaredNames,
        )
    }

    /** Invariant 54: the exported component set is exactly the two shipped activities — nothing new. */
    @Test
    fun theExportedComponentSetIsExactlyTheTwoShippedActivities() {
        val exportedActivities = manifest.elements("activity")
            .filter { it.androidAttr("exported") == "true" }
            .map { it.androidAttr("name") }
            .toSet()
        assertEquals(
            setOf(
                "com.loosecannon.servicetag.MainActivity",
                "com.loosecannon.servicetag.nfc.NfcDispatchActivity",
            ),
            exportedActivities,
        )

        // No other component kind carries exported="true" anywhere in the manifest.
        val otherExported = (manifest.elements("receiver") + manifest.elements("service") + manifest.elements("provider"))
            .filter { it.androidAttr("exported") == "true" }
        assertTrue(otherExported.isEmpty())
    }

    /**
     * #24 AC 1: requesting at launch is the default reflex this brief forbids. B14's editor is
     * the only intended caller of `NotificationPermission.request`; this asserts nothing in the
     * app shell even references the type.
     */
    @Test
    fun notificationPermissionHasNoCallSiteInTheAppShell() {
        val excluded = listOf(
            sourceFile("kotlin/com/loosecannon/servicetag/MainActivity.kt"),
            sourceFile("kotlin/com/loosecannon/servicetag/ServiceTagApp.kt"),
        ) + navDirectory().walkTopDown().filter { it.isFile && it.extension == "kt" }

        excluded.forEach { file ->
            assertTrue(
                "${file.path} must not reference NotificationPermission",
                "NotificationPermission" !in file.readText(),
            )
        }
    }

    /**
     * Invariant 55's mechanism: a receiver declared in this brief must never construct an
     * `Activity` `Intent` or call `startActivity` — that door stays shut structurally, at the
     * declaration, rather than relying on it never being found in a later brief.
     */
    @Test
    fun noReceiverInThisBriefStartsAnActivity() {
        val source = sourceFile("kotlin/com/loosecannon/servicetag/reminders/ReminderReceivers.kt").readText()
        assertTrue("startActivity" !in source)
        assertTrue("Intent(" !in source)
    }

    private fun Element.androidAttr(name: String): String? {
        val value = getAttribute("android:$name")
        return value.ifEmpty { null }
    }
}

/**
 * The one review-gate assertion the brief names against both files: the merged manifest, when a
 * prior build has produced one, carries neither exact-alarm permission either. Skipped rather than
 * failed if no merged manifest is on disk — this repository's `testDebugUnitTest` does produce one
 * today, but a test must not depend on another task's output existing to pass.
 */
class MergedManifestContractTest {

    @Test
    fun neitherExactAlarmPermissionInTheMergedManifestEither() {
        val file = mergedManifestCandidates()
            .flatMap { listOf(File(it), File("app/$it")) }
            .firstOrNull { it.isFile }
        assumeTrue("no merged manifest on disk to check", file != null)

        val text = file!!.readText()
        assertFalse(
            "SCHEDULE_EXACT_ALARM must never be declared (ledger A12)",
            text.contains("android.permission.SCHEDULE_EXACT_ALARM"),
        )
        assertFalse(
            "USE_EXACT_ALARM must never be declared (ledger A12)",
            text.contains("android.permission.USE_EXACT_ALARM"),
        )
    }

    private fun mergedManifestCandidates() = listOf(
        "build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml",
        "build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml",
    )
}

private fun org.w3c.dom.Document.elements(tag: String): List<Element> {
    val nodes = getElementsByTagName(tag)
    return (0 until nodes.length).map { nodes.item(it) as Element }
}

/**
 * `app/src/main/AndroidManifest.xml`. Gradle runs JVM unit tests with the module directory as the
 * working directory, but an IDE run configuration may use the repository root, so both are tried
 * (the same convention `MigrationTestSupport.kt` uses).
 */
private fun readSourceManifest(): org.w3c.dom.Document {
    val relative = "src/main/AndroidManifest.xml"
    val file = listOf(File(relative), File("app/$relative")).firstOrNull { it.isFile }
        ?: error("cannot find AndroidManifest.xml from ${File(".").absolutePath}")

    val factory = DocumentBuilderFactory.newInstance()
    return factory.newDocumentBuilder().parse(file)
}

private fun sourceFile(relative: String): File {
    val underMain = "src/main/$relative"
    return listOf(File(underMain), File("app/$underMain")).firstOrNull { it.isFile }
        ?: error("cannot find $underMain from ${File(".").absolutePath}")
}

private fun navDirectory(): File {
    val relative = "src/main/kotlin/com/loosecannon/servicetag/ui/nav"
    return listOf(File(relative), File("app/$relative")).firstOrNull { it.isDirectory }
        ?: error("cannot find $relative from ${File(".").absolutePath}")
}
