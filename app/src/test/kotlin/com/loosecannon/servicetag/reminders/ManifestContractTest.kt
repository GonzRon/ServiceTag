package com.loosecannon.servicetag.reminders

import com.loosecannon.servicetag.core.references.LinkLaunchPolicy
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Structural proofs read straight off `app/src/main/AndroidManifest.xml` — no connected run
 * needed (master plan §12, spec §5.1/§5.4). The counts asserted here (exactly six receivers,
 * exactly the three named exported activities) are read off the **source** manifest, the
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
     * Invariant 54: every `<receiver>` is non-exported, and the declared set is exactly B05's four
     * platform receivers, B06's digest receiver and B07's quick-action receiver — **six**, which is
     * the count master plan §16's release proof states and the final one for 1.2.
     *
     * B06 added the fifth because an alarm-targeted receiver has to outlive the process that armed
     * it and so cannot be registered at runtime; B07 added the sixth for the same reason about a
     * notification, which outlives the process that posted it (controller ruling, 2026-09-22: each
     * of those two briefs adds exactly one manifest `<receiver>`).
     */
    @Test
    fun everyReceiverIsNonExportedAndThereAreSix() {
        val receivers = manifest.elements("receiver")
        assertEquals(6, receivers.size)
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
                "com.loosecannon.servicetag.reminders.DigestReceiver",
                "com.loosecannon.servicetag.reminders.QuickActionReceiver",
            ),
            declaredNames,
        )
    }

    /**
     * Invariant 54, and D-14 for 1.3.0: the exported component set is exactly three activities,
     * **by name**. It stays an exact set and is never made a count of three — the merged manifest
     * carries library-injected components, as this class's KDoc records, which would make an
     * exact-count assertion flaky.
     *
     * **`<activity-alias>` is read alongside `<activity>`**, because an exported alias is a fourth
     * exported component by any definition and the shipped assertion did not see one. None exists
     * today and 1.3.0 builds none; the point of the check is that a fourth would fail it.
     */
    @Test
    fun theExportedComponentSetIsExactlyTheThreeNamedActivities() {
        val exportedActivities = (manifest.elements("activity") + manifest.elements("activity-alias"))
            .filter { it.androidAttr("exported") == "true" }
            .map { it.androidAttr("name") }
            .toSet()
        assertEquals(
            setOf(
                "com.loosecannon.servicetag.MainActivity",
                "com.loosecannon.servicetag.nfc.NfcDispatchActivity",
                "com.loosecannon.servicetag.share.ShareIntakeActivity",
            ),
            exportedActivities,
        )

        // No other component kind carries exported="true" anywhere in the manifest.
        val otherExported = (manifest.elements("receiver") + manifest.elements("service") + manifest.elements("provider"))
            .filter { it.androidAttr("exported") == "true" }
        assertTrue(otherExported.isEmpty())
    }

    /**
     * D-6 and spec §4.1: the share target's declaration, attribute for attribute and type for
     * type. A type added or dropped by hand changes what the system share sheet offers with
     * nothing else noticing, and the four never-declared entries are each a wider door than
     * `ACTION_SEND` — `BROWSABLE` most of all, which would put this activity on the open web.
     *
     * `android:taskAffinity=""` is read raw: an empty attribute is present and empty, which is the
     * whole point of it, and the `androidAttr` helper reports an empty value as absent.
     */
    @Test
    fun theShareTargetDeclaresActionSendOverExactlyTheTenTypes() {
        val share = manifest.elements("activity")
            .single { it.androidAttr("name") == "com.loosecannon.servicetag.share.ShareIntakeActivity" }

        assertEquals("true", share.androidAttr("exported"))
        assertEquals("standard", share.androidAttr("launchMode"))
        assertEquals("true", share.androidAttr("excludeFromRecents"))
        assertTrue(
            "taskAffinity must be declared and empty",
            share.hasAttribute("android:taskAffinity") &&
                share.getAttribute("android:taskAffinity").isEmpty(),
        )

        val filters = share.getElementsByTagName("intent-filter")
        assertEquals(1, filters.length)
        val filter = filters.item(0) as Element

        assertEquals(
            listOf("android.intent.action.SEND"),
            filter.childNames("action"),
        )
        assertEquals(
            listOf("android.intent.category.DEFAULT"),
            filter.childNames("category"),
        )
        assertEquals(
            listOf(
                "text/plain",
                "text/uri-list",
                "image/*",
                "application/pdf",
                "text/markdown",
                "text/csv",
                "application/msword",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/zip",
            ),
            filter.childAttrs("data", "mimeType"),
        )

        val everyAction = manifest.elements("action").map { it.androidAttr("name") }
        listOf(
            "android.intent.action.SEND_MULTIPLE",
            "android.intent.action.PROCESS_TEXT",
        ).forEach { action ->
            assertTrue("$action must never be declared", action !in everyAction)
        }
        assertTrue(
            "the share target must declare neither ACTION_VIEW nor BROWSABLE",
            "android.intent.action.VIEW" !in filter.childNames("action") &&
                "android.intent.category.BROWSABLE" !in filter.childNames("category"),
        )
    }

    /**
     * API-30+ package visibility: one `<queries>` entry per allowed scheme. *Starting* an implicit
     * `ACTION_VIEW` is exempt from the filtering, so "Open" does not depend on these — what they
     * buy is a truthful `resolveActivity` / `queryIntentActivities` answer for anything that asks
     * before it fires. The spec asked for the entries; the shipped wildcard `content` entry, which
     * the shipped "Open with" does query, stays as it is.
     */
    @Test
    fun queriesCarryOneViewEntryPerAllowedScheme() {
        val queries = manifest.elements("queries").single()
        val intents = queries.getElementsByTagName("intent")
        val schemes = (0 until intents.length).map { at ->
            val intent = intents.item(at) as Element
            assertEquals(
                listOf("android.intent.action.VIEW"),
                intent.childNames("action"),
            )
            intent.childAttrs("data", "scheme").single()
        }

        // Read off the policy, not a second copy of it: the manifest follows `LinkLaunchPolicy`,
        // so a scheme added to the allow list and not to `<queries>` fails here.
        assertEquals(
            listOf("content") + LinkLaunchPolicy.ALLOWED,
            schemes,
        )
        assertEquals(
            "the shipped attachment entry keeps its wildcard mime type",
            listOf("*/*"),
            (intents.item(0) as Element).childAttrs("data", "mimeType"),
        )
    }

    /** Member extensions, not file-scoped: the helpers below this class are left as they are. */
    private fun Element.childElements(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.childNames(tag: String): List<String?> =
        childElements(tag).map { it.androidAttr("name") }

    private fun Element.childAttrs(tag: String, attribute: String): List<String?> =
        childElements(tag).map { it.androidAttr(attribute) }

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
     * Invariant 55's mechanism: no receiver declared in this brief may ever start an `Activity`
     * from `onReceive` — that door stays shut structurally, at the declaration, rather than
     * relying on it never being found in a later brief.
     *
     * Two clauses, two different scopes (B05 fix round 2, finding 19; the first fix round's
     * single package-wide scope was too broad for the second clause). `startActivity` stays
     * forbidden across the **whole** `reminders/` package: no file under it — not B06's alarm
     * code, not B07's quick actions — has any legitimate reason to call it. `Intent(` is scoped
     * to files that **declare a `BroadcastReceiver` subclass**: invariant 55 forbids a receiver
     * starting an activity, not an `Intent` existing in the package, and B06's `DigestAlarm.kt`
     * and B07's `QuickActions.kt` both legitimately build `Intent`s for their `PendingIntent`s —
     * the *correct*, non-trampoline shape — without themselves being receivers.
     */
    @Test
    fun noReceiverInThisPackageStartsAnActivity() {
        remindersSourceFiles().forEach { file ->
            val source = file.readText()
            assertTrue("${file.name} must not call startActivity", "startActivity" !in source)
            if ("BroadcastReceiver" in source) {
                assertTrue("${file.name} declares a BroadcastReceiver and must not construct an Intent(", "Intent(" !in source)
            }
        }
    }

    /**
     * S4 (B05 fix round 1, finding 6): the other half of the class→kind pairing
     * `ReminderReceiversTest.eachReceiverClassCarriesItsOwnKind` proves — here, each receiver
     * *name* is paired with the manifest `<action>`(s) it is actually invoked for. Together the
     * two ends of the wiring (which broadcast reaches a class, and which kind that class forwards)
     * are both asserted; neither alone would catch a class hard-coded against the wrong action.
     *
     * List-valued, not `.single()` (B05 fix round 2, finding 23): B07's `QuickActionReceiver` is
     * "none — explicit intent only", and `.single()` would throw `NoSuchElementException` on it
     * instead of reporting a clean expected-vs-actual diff. B05's four each carry exactly one
     * action; that is what the expected map's single-element lists say.
     */
    @Test
    fun eachReceiverNameFiltersItsOwnAction() {
        val expected = mapOf(
            "com.loosecannon.servicetag.reminders.BootCompletedReceiver" to listOf("android.intent.action.BOOT_COMPLETED"),
            "com.loosecannon.servicetag.reminders.TimeSetReceiver" to listOf("android.intent.action.TIME_SET"),
            "com.loosecannon.servicetag.reminders.TimezoneChangedReceiver" to listOf("android.intent.action.TIMEZONE_CHANGED"),
            "com.loosecannon.servicetag.reminders.DateChangedReceiver" to listOf("android.intent.action.DATE_CHANGED"),
            // B06's digest receiver: none. It is addressed by an explicit PendingIntent, and an
            // intent filter on it would be a public door onto the digest for no reason at all.
            "com.loosecannon.servicetag.reminders.DigestReceiver" to emptyList(),
            // B07's quick-action receiver: none either, and for a sharper version of the same
            // reason — an intent filter here would be the forgery door the nonce exists because of.
            "com.loosecannon.servicetag.reminders.QuickActionReceiver" to emptyList(),
        )

        val actual = manifest.elements("receiver").associate { receiver ->
            val name = receiver.androidAttr("name")!!
            val actionNodes = receiver.getElementsByTagName("action")
            val actions = (0 until actionNodes.length).map { (actionNodes.item(it) as Element).androidAttr("name") }
            name to actions
        }

        assertEquals(expected, actual)
    }
}

/**
 * The facts the brief asks be checked against the *merged*, not the source, manifest: no
 * exact-alarm permission, and (B05 fix round 1, finding 3/S1) the exact `uses-permission` set —
 * named rather than merely counted, so a library that adds a permission later, including an exact
 * alarm one arriving from a dependency rather than this brief's own source, fails this row.
 *
 * A missing merged manifest is a **failure**, not a skip (finding 4/S2: the brief's own gate
 * forbids skips) — `app/build.gradle.kts` now makes `testDebugUnitTest` depend on
 * `processDebugManifest`, so the file this reads is always the one built from the current
 * `AndroidManifest.xml`, never a stale one left over from an earlier build.
 */
class MergedManifestContractTest {

    private val mergedManifestFile: File = mergedManifestCandidates()
        .flatMap { listOf(File(it), File("app/$it")) }
        .firstOrNull { it.isFile }
        ?: error(
            "no merged manifest on disk at any of ${mergedManifestCandidates()} from " +
                "${File(".").absolutePath} — testDebugUnitTest should depend on processDebugManifest",
        )

    @Test
    fun neitherExactAlarmPermissionInTheMergedManifestEither() {
        val text = mergedManifestFile.readText()
        assertFalse(
            "SCHEDULE_EXACT_ALARM must never be declared (ledger A12)",
            text.contains("android.permission.SCHEDULE_EXACT_ALARM"),
        )
        assertFalse(
            "USE_EXACT_ALARM must never be declared (ledger A12)",
            text.contains("android.permission.USE_EXACT_ALARM"),
        )
    }

    /**
     * §15.7's release-gate sentence: `NFC` + `INTERNET` + `POST_NOTIFICATIONS` + the AndroidX
     * app-private receiver permission — amended at B05's fix round to include
     * `RECEIVE_BOOT_COMPLETED` (S1), and amended again by B06 for the three
     * `androidx.work:work-runtime` injects. A set, not a count: naming every member is what catches
     * an addition the count-only style would silently tolerate.
     *
     * **B06's three (`WAKE_LOCK`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`)** arrive from
     * WorkManager's own library manifest, not from this app's source, and every one of them is a
     * **normal, install-time** permission with no runtime prompt. They do not contradict spec
     * §5.1: the owner's amendment reads "only" there as the only new *runtime-requested*
     * permission, which is still exactly `POST_NOTIFICATIONS`. Neither exact-alarm permission is
     * among them, which the row above asserts separately. The appearance of all three in the
     * shipped manifest is recorded in B06's report as a finding for the controller, because the
     * release-gate sentence enumerates this set.
     */
    @Test
    fun theMergedManifestPermissionSetIsExactly() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(mergedManifestFile)
        val declared = doc.elements("uses-permission").map { it.androidAttr("name") }.toSet()

        assertEquals(
            setOf(
                "android.permission.NFC",
                "android.permission.INTERNET",
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.RECEIVE_BOOT_COMPLETED",
                "android.permission.WAKE_LOCK",
                "android.permission.ACCESS_NETWORK_STATE",
                "android.permission.FOREGROUND_SERVICE",
                "com.loosecannon.servicetag.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
            ),
            declared,
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

/** File-scoped (not a class member): both `ManifestContractTest` and `MergedManifestContractTest` read it. */
private fun Element.androidAttr(name: String): String? {
    val value = getAttribute("android:$name")
    return value.ifEmpty { null }
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

/** `internal`, not `private` — `PlatformStateTest` reuses this to scan production source too. */
internal fun sourceFile(relative: String): File {
    val underMain = "src/main/$relative"
    return listOf(File(underMain), File("app/$underMain")).firstOrNull { it.isFile }
        ?: error("cannot find $underMain from ${File(".").absolutePath}")
}

private fun navDirectory(): File {
    val relative = "src/main/kotlin/com/loosecannon/servicetag/ui/nav"
    return listOf(File(relative), File("app/$relative")).firstOrNull { it.isDirectory }
        ?: error("cannot find $relative from ${File(".").absolutePath}")
}

private fun remindersDirectory(): File {
    val relative = "src/main/kotlin/com/loosecannon/servicetag/reminders"
    return listOf(File(relative), File("app/$relative")).firstOrNull { it.isDirectory }
        ?: error("cannot find $relative from ${File(".").absolutePath}")
}

internal fun remindersSourceFiles(): List<File> =
    remindersDirectory().listFiles { f -> f.isFile && f.extension == "kt" }?.toList().orEmpty()
