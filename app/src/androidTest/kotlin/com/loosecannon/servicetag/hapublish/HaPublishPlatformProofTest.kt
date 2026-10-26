package com.loosecannon.servicetag.hapublish

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.loosecannon.servicetag.ServiceTagApp
import com.loosecannon.servicetag.core.fetch.DocumentTransport
import com.loosecannon.servicetag.core.hapublish.HaServiceAction
import com.loosecannon.servicetag.core.hapublish.INVALID_SERVICE_DATE
import com.loosecannon.servicetag.core.hapublish.PublishBinding
import com.loosecannon.servicetag.core.hapublish.PublishDelivery
import com.loosecannon.servicetag.core.hapublish.PublishErrorKind
import com.loosecannon.servicetag.core.hapublish.PublishKind
import com.loosecannon.servicetag.core.hapublish.PublishState
import com.loosecannon.servicetag.core.hapublish.RemoteEntity
import com.loosecannon.servicetag.core.hapublish.RemoteRead
import com.loosecannon.servicetag.core.hapublish.STATUS_DEGRADED
import com.loosecannon.servicetag.core.hapublish.STATUS_DOWN
import com.loosecannon.servicetag.core.hapublish.STATUS_UP
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.seasonsync.BackgroundChecks
import com.loosecannon.servicetag.core.seasonsync.CurrentNetwork
import com.loosecannon.servicetag.core.seasonsync.HaConnection
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.core.seasonsync.Secret
import com.loosecannon.servicetag.core.seasonsync.SyncCadence
import com.loosecannon.servicetag.fetch.InetHostResolver
import com.loosecannon.servicetag.prefs.InstallationIdentity
import com.loosecannon.servicetag.seasonsync.CurrentNetworkReader
import com.loosecannon.servicetag.seasonsync.KeystoreSecretStore
import com.loosecannon.servicetag.seasonsync.NetworkReading
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.security.KeyStore
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * #113/#114 C37 (row 92) — publishing's platform facts, the ones no JVM test can see: the real
 * [HomeAssistantPublishClient] over the platform's HTTP stack to an in-process fake Home Assistant (the POST's method,
 * path, headers and body, the read-back, and a redirect not followed), WorkManager's records of the two unique works
 * through the real seam ([WorkManagerPublishWork]), the Android Keystore behind the real runner, and the no-backup
 * directory that holds this installation's id. Each case's KDoc names the one OS boundary it proves (#62). The
 * decisions themselves — the allowlist, the outcome table, the delivery steps, the work rule — are the JVM's; a case
 * here may run one of them through the platform, but nothing here is their proof.
 *
 * **The fake Home Assistant** listens in this instrumentation process on this device's own site-local IPv4 address,
 * read at run time, on port 0 — never loopback, which the address rule refuses, and never beyond the device. It reads
 * each request's head and its `Content-Length` body, answers it with the case's script, and keeps both. The client
 * cases hand the client a stored connection of their own and a scripted home Wi-Fi, so the request never depends on
 * the emulator's active network (the rule is JVM-proven, #16's proof ran it on the platform).
 *
 * **Order is fixed** ([FixMethodOrder]): the runner case stores a connection and a binding, and the app's own observer
 * answers that by asking its dispatch for delivery, whose works' rule cancels both works. Its name sorts last, so no
 * such request can land inside a work case. It never sends: the token's Keystore key is deleted before any row exists.
 *
 * Unlike #16's proof this class never waits for `appScope` to have no children: since #113/#114 the publish triggers'
 * observer and midnight tick live on it for the life of the process. The work cases settle the dispatch's works' rule
 * once, synchronously, before they enqueue.
 *
 * **Teardown, every time:** the connection this class stored (its binding and delivery go by the CASCADE), its token
 * and key, the asset it created, the fake listeners, the works it enqueued and the workers' dispatch as found. It never
 * touches a row it did not create: no case starts when this install already holds a connection.
 */
@RunWith(AndroidJUnit4::class)
// Load-bearing: `runner…` stores a connection and an enabled binding; its name has to sort after every work case.
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class HaPublishPlatformProofTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val graph get() = (context.applicationContext as ServiceTagApp).graph

    private val listeners = mutableListOf<FakeHomeAssistant>()
    private val storedConnectionIds = linkedSetOf<String>()
    private val createdAssets = mutableListOf<AssetId>()
    private var savedDispatch: PublishDispatch? = null
    private var touchedWorks = false
    private var startedClean = false

    @Before
    fun setUp() {
        savedDispatch = HaPublishDispatch.dispatch
        assertNull(
            "this install already holds a Home Assistant connection, and this class never overwrites one",
            runBlocking { graph.haConnections.get() },
        )
        startedClean = true
    }

    @After
    fun tearDown() {
        val failures = mutableListOf<Throwable>()
        fun step(block: () -> Unit) = try {
            block()
        } catch (t: Throwable) {
            failures += t
        }
        // The rows first, so nothing this class stored is left for a pass to find.
        step { runBlocking { storedConnectionIds.forEach { graph.uow.write { graph.haConnections.delete(it) } } } }
        step { runBlocking { storedConnectionIds.forEach { graph.secretStore.delete(it) } } }
        if (startedClean && touchedWorks) {
            step {
                WorkManagerPublishWork(context).cancel()
                waitUntil("no publish work left pending") {
                    pending(PublishWorker.ONCE_NAME).isEmpty() && pending(PublishWorker.RECONCILE_NAME).isEmpty()
                }
            }
        }
        step { runBlocking { createdAssets.forEach { graph.deleteAsset.run(it) } } }
        listeners.forEach { step { it.close() } }
        HaPublishDispatch.dispatch = savedDispatch
        failures.firstOrNull()?.let { throw it }
    }

    // --- the client and the fake Home Assistant ---------------------------------------------------------------------

    /**
     * OS boundary: a real socket from the platform's HTTP stack, in cleartext under the platform's policy, to this
     * device's own address. Set to `UP`, the client sends exactly one `POST /api/services/input_select/select_option`
     * with the bearer, the JSON content type and accept, identity encoding, the fixed user agent, a fixed-length body
     * and the body `{"entity_id", "option"}` (G2); HA's 200 is `Accepted`. The read-back is one
     * `GET /api/states/<entity_id>` with the bearer and no body, whose options come back as the helper's.
     */
    @Test
    fun clientPostsTheStatusOptionWithTheBearerThenReadsTheHelperBack() {
        val fake = listen { request ->
            when (request.method) {
                POST -> serviceAnswer()
                else -> stateAnswer(STATUS_ENTITY, STATUS_UP, """{"options":["$STATUS_UP","$STATUS_DEGRADED","$STATUS_DOWN"]}""")
            }
        }
        val connection = connection(CLIENT_ID, fake.origin())
        val client = client(connection)

        val outcome = runBlocking { client.call(connection, HaServiceAction.SELECT_OPTION, STATUS_ENTITY, STATUS_UP, Secret(TOKEN)) }
        assertEquals(CallOutcome.Accepted, outcome)
        val read = runBlocking { client.read(connection, STATUS_ENTITY, Secret(TOKEN)) }
        assertEquals(
            RemoteRead.Answered(RemoteEntity(STATUS_ENTITY, STATUS_UP, listOf(STATUS_UP, STATUS_DEGRADED, STATUS_DOWN), null, null)),
            read,
        )

        assertEquals("one POST, then one GET", 2, fake.requests.size)
        val post = fake.requests[0]
        val body = """{"entity_id":"$STATUS_ENTITY","option":"$STATUS_UP"}"""
        assertEquals("$POST $SERVICES_PATH$SELECT_OPTION_PATH HTTP/1.1", post.line)
        assertEquals("Bearer $TOKEN", post.header("Authorization"))
        assertEquals(JSON, post.header("Content-Type"))
        assertEquals(JSON, post.header("Accept"))
        assertEquals("identity", post.header("Accept-Encoding"))
        assertEquals(DocumentTransport.USER_AGENT, post.header("User-Agent"))
        assertEquals("a fixed-length body", body.toByteArray(Charsets.UTF_8).size.toString(), post.header("Content-Length"))
        assertNull("never chunked", post.header("Transfer-Encoding"))
        assertNull("no cookie", post.header("Cookie"))
        assertEquals(body, post.body)

        val get = fake.requests[1]
        assertEquals("$GET $STATES_PATH$STATUS_ENTITY HTTP/1.1", get.line)
        assertEquals("Bearer $TOKEN", get.header("Authorization"))
        assertNull("a GET carries no content type", get.header("Content-Type"))
        assertEquals("", get.body)
    }

    /**
     * OS boundary: the same stack for the date kind. `1970-01-01` (the invalid date, C1) goes in one
     * `POST /api/services/input_datetime/set_datetime` with the body `{"entity_id", "date"}`, and the read-back of a
     * date-only helper carries `has_date` and `has_time` as the platform's JSON delivered them.
     */
    @Test
    fun clientPostsTheInvalidDateToTheDateHelperThenReadsItBack() {
        val fake = listen { request ->
            when (request.method) {
                POST -> serviceAnswer()
                else -> stateAnswer(DATE_ENTITY, INVALID_SERVICE_DATE, """{"has_date":true,"has_time":false}""")
            }
        }
        val connection = connection(CLIENT_ID, fake.origin())
        val client = client(connection)

        val outcome = runBlocking {
            client.call(connection, HaServiceAction.SET_DATE, DATE_ENTITY, INVALID_SERVICE_DATE, Secret(TOKEN))
        }
        assertEquals(CallOutcome.Accepted, outcome)
        val read = runBlocking { client.read(connection, DATE_ENTITY, Secret(TOKEN)) }
        assertEquals(RemoteRead.Answered(RemoteEntity(DATE_ENTITY, INVALID_SERVICE_DATE, null, true, false)), read)

        assertEquals(2, fake.requests.size)
        val post = fake.requests[0]
        assertEquals("$POST $SERVICES_PATH$SET_DATETIME_PATH HTTP/1.1", post.line)
        assertEquals("Bearer $TOKEN", post.header("Authorization"))
        assertEquals(JSON, post.header("Content-Type"))
        assertEquals("""{"entity_id":"$DATE_ENTITY","date":"$INVALID_SERVICE_DATE"}""", post.body)
        assertEquals("$GET $STATES_PATH$DATE_ENTITY HTTP/1.1", fake.requests[1].line)
    }

    /**
     * OS boundary: the platform's HTTP stack honouring `instanceFollowRedirects = false` on a POST. A 302 is
     * `Refused(REDIRECTED)` (C23: HA refused it before acting), and the listener its `Location` names is never
     * connected to, so neither the bearer nor the body leaves for it.
     */
    @Test
    fun clientDoesNotFollowA302AndItsTargetIsUntouched() {
        val target = listen { serviceAnswer() }
        val fake = listen {
            answer("302 Found", headers = "Location: ${target.origin()}$SERVICES_PATH$SELECT_OPTION_PATH\r\n")
        }
        val connection = connection(CLIENT_ID, fake.origin())

        val outcome = runBlocking {
            client(connection).call(connection, HaServiceAction.SELECT_OPTION, STATUS_ENTITY, STATUS_DOWN, Secret(TOKEN))
        }

        assertEquals(CallOutcome.Refused(PublishErrorKind.REDIRECTED, null), outcome)
        assertEquals(1, fake.requests.size)
        assertEquals("$POST $SERVICES_PATH$SELECT_OPTION_PATH HTTP/1.1", fake.requests.single().line)
        assertEquals("the redirect's target is untouched", 0, target.accepted.get())
    }

    // --- the installation ------------------------------------------------------------------------------------------

    /**
     * OS boundary: the app's `noBackupFilesDir`, which neither Auto Backup nor a device-to-device transfer copies (C27:
     * a platform restore that brings the publish rows back cannot bring this id with them). The graph's identity is
     * one well-formed file, [InstallationIdentity.FILE_NAME], directly under that directory, whose content is the id
     * every Enable stamps; a second read in this process answers the same id.
     */
    @Test
    fun installationIdLivesInOneFileUnderTheNoBackupDirectory() {
        val id = graph.installationIdentity.id()

        val file = File(context.noBackupFilesDir, InstallationIdentity.FILE_NAME)
        assertTrue("the id is a file under the no-backup directory", file.isFile)
        assertEquals(context.noBackupFilesDir.canonicalPath, file.canonicalFile.parentFile?.canonicalPath)
        assertEquals("the file's content is the id", id, file.readText())
        assertTrue(ID_SHAPE.matches(id))
        assertEquals(id, graph.installationIdentity.id())
        assertFalse(
            "nothing of that name under the backed-up files directory",
            File(context.filesDir, InstallationIdentity.FILE_NAME).exists(),
        )
    }

    // --- the works -------------------------------------------------------------------------------------------------

    /**
     * OS boundary: WorkManager's record, through the real seam. One `enqueueOnce` adds exactly one work under the
     * unique name `"ha-publish"`, constrained to a connected network (C32). The workers' dispatch is unassigned for
     * this case, so the work's own run does nothing (`PublishWorkerBody`: success, nothing done).
     */
    @Test
    fun onceWorkIsEnqueuedOnceUnderItsUniqueNameWithTheConnectedConstraint() {
        settleTheWorksRule()
        HaPublishDispatch.dispatch = null
        touchedWorks = true
        val before = all(PublishWorker.ONCE_NAME).map { it.id }.toSet()

        WorkManagerPublishWork(context).enqueueOnce()

        waitUntil("the one-time work recorded") { all(PublishWorker.ONCE_NAME).any { it.id !in before } }
        val added = all(PublishWorker.ONCE_NAME).filter { it.id !in before }
        assertEquals("one enqueue, one work", 1, added.size)
        assertEquals(NetworkType.CONNECTED, added.single().constraints.requiredNetworkType)
        assertEquals("a one-time work has no period", null, added.single().periodicityInfo)
    }

    /**
     * OS boundary: WorkManager's record of the periodic reconcile, through the real seam: `ensureReconcile` at DAILY
     * leaves one `"ha-publish-reconcile"` work at a day's period with the CONNECTED constraint; at WEEKLY (UPDATE) the
     * same one work at a week's; `cancel` finishes it. The dispatch is unassigned, so a run does nothing.
     */
    @Test
    fun reconcileWorkTakesTheCadencePeriodAndUpdateKeepsOneWorkUntilCancelled() {
        settleTheWorksRule()
        HaPublishDispatch.dispatch = null
        touchedWorks = true
        val seam = WorkManagerPublishWork(context)

        seam.ensureReconcile(PublishReconcileRequest.forCadence(SyncCadence.DAILY))
        waitUntil("one reconcile work at a day") { period(PublishWorker.RECONCILE_NAME) == DAY_MILLIS }
        val daily = pending(PublishWorker.RECONCILE_NAME).single()
        assertEquals(NetworkType.CONNECTED, daily.constraints.requiredNetworkType)

        seam.ensureReconcile(PublishReconcileRequest.forCadence(SyncCadence.WEEKLY))
        waitUntil("one reconcile work at a week") { period(PublishWorker.RECONCILE_NAME) == WEEK_MILLIS }
        assertEquals("UPDATE keeps the one work", daily.id, pending(PublishWorker.RECONCILE_NAME).single().id)

        seam.cancel()
        waitUntil("the work finished") { pending(PublishWorker.RECONCILE_NAME).isEmpty() }
        val cancelled = all(PublishWorker.RECONCILE_NAME).single { it.id == daily.id }
        assertEquals(WorkInfo.State.CANCELLED, cancelled.state)
    }

    // --- the Keystore, behind the real runner ------------------------------------------------------------------------

    /**
     * OS boundary: `AndroidKeyStore`, read by the graph's own runner through the device's store. A token is stored for
     * the connection and its Keystore key is then deleted, as after a Keystore reset, while its ciphertext file stays;
     * only then are the connection and an enabled, validated status binding of this installation stored. Publish now
     * for the asset reads the binding as `NEEDS_TOKEN` (C17's gate) and the fake Home Assistant the connection names
     * is never connected to.
     */
    @Test
    fun runnerReadsADeletedKeystoreKeyAsNeedsTokenAndSendsNothing() {
        val fake = listen { serviceAnswer() }
        runBlocking { graph.secretStore.put(RUNNER_ID, Secret(TOKEN)) }
        storedConnectionIds += RUNNER_ID
        androidKeyStore().deleteEntry(KeystoreSecretStore.ALIAS_PREFIX + RUNNER_ID)
        assertFalse("the key is gone, so the store holds no token", runBlocking { graph.secretStore.has(RUNNER_ID) })
        assertTrue(
            "the ciphertext file stays",
            File(File(context.noBackupFilesDir, KeystoreSecretStore.DIRECTORY), "$RUNNER_ID.bin").isFile,
        )

        val asset = runBlocking { graph.createAsset.run(name = ASSET_NAME) }.id.also { createdAssets += it }
        val binding = statusBinding(asset, RUNNER_ID)
        runBlocking {
            graph.uow.write {
                graph.haConnections.upsert(connection(RUNNER_ID, fake.origin()))
                graph.publishBindings.insert(binding)
                graph.publishDeliveries.upsert(PublishDelivery(binding.id, desiredValue = null, desiredRevision = 1, desiredAt = null))
            }
        }

        val pass = runBlocking { graph.publishRunner.publishNow(asset) }

        val outcome = pass.outcomes.single { it.bindingId == binding.id }
        assertEquals(PassResult.Skipped(PublishState.NEEDS_TOKEN), outcome.result)
        assertFalse(pass.readCompleted)
        assertEquals("nothing was sent", 0, fake.accepted.get())
    }

    // --- fixtures --------------------------------------------------------------------------------------------------

    /** The real client over [connection], its own stored one, the network scripted as the captured home Wi-Fi. */
    private fun client(connection: HaConnection): HomeAssistantPublishClient = HomeAssistantPublishClient(
        settings = { connection },
        networkPermissionGranted = ::internetGranted,
        currentNetwork = CurrentNetworkReader { NetworkReading(CurrentNetwork.Wifi(HOME_WIFI), null) },
        resolver = InetHostResolver(::internetGranted),
    )

    private fun internetGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED

    /** An http connection to this device's own address, home Wi-Fi only (the rule for any http address), checked by hand. */
    private fun connection(id: String, origin: String): HaConnection {
        val now = System.currentTimeMillis()
        return HaConnection(
            id = id,
            baseUrl = origin,
            cadence = SyncCadence.DAILY,
            networkEligibility = NetworkEligibility.HOME_NETWORK_ONLY,
            homeNetworkSsid = HOME_WIFI,
            backgroundChecks = BackgroundChecks.OFF,
            createdAt = now,
            updatedAt = now,
        )
    }

    /** An enabled status binding activated on this installation and validated at its revision. */
    private fun statusBinding(asset: AssetId, connectionId: String): PublishBinding {
        val now = System.currentTimeMillis()
        return PublishBinding(
            id = "proof-binding-" + UUID.randomUUID(),
            assetId = asset,
            connectionId = connectionId,
            kind = PublishKind.EQUIPMENT_STATUS,
            entityId = STATUS_ENTITY,
            enabled = true,
            revision = 1,
            activatedInstallationId = graph.installationIdentity.id(),
            validatedAt = now,
            validatedRevision = 1,
            boundaryEntityId = null,
            createdAt = now,
            updatedAt = now,
        )
    }

    /** The dispatch's works' rule applied once, now, with nothing stored: both works cancelled, so no late cancel lands. */
    private fun settleTheWorksRule() {
        runBlocking { graph.publishDispatch.reschedule() }
    }

    private fun all(name: String): List<WorkInfo> = WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get()

    private fun pending(name: String): List<WorkInfo> = all(name).filter { !it.state.isFinished }

    /** The one pending work's requested period, or null unless exactly one is pending. */
    private fun period(name: String): Long? = pending(name).singleOrNull()?.periodicityInfo?.repeatIntervalMillis

    private fun androidKeyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    /** This device's own site-local IPv4 address, read at run time; none found fails the case, never skips it. */
    private fun siteLocalAddress(): Inet4Address {
        val connectivity = checkNotNull(context.getSystemService(ConnectivityManager::class.java))
        val onDefault = connectivity.activeNetwork?.let { connectivity.getLinkProperties(it) }?.linkAddresses.orEmpty()
            .map { it.address }
        val onInterfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
        return (onDefault + onInterfaces).filterIsInstance<Inet4Address>().firstOrNull { it.isSiteLocalAddress }
            ?: throw AssertionError("no site-local IPv4 address here: the fake Home Assistant has nowhere to listen")
    }

    private fun listen(respond: (FakeRequest) -> String): FakeHomeAssistant =
        FakeHomeAssistant(siteLocalAddress(), respond).also { listeners += it }

    /** HA's answer to a service call: 200 and the states it changed, which the client reads and drops. */
    private fun serviceAnswer(): String = answer("200 OK", headers = "Content-Type: $JSON\r\n", body = "[]")

    private fun stateAnswer(entity: String, state: String, attributes: String): String = answer(
        "200 OK",
        headers = "Content-Type: $JSON\r\n",
        body = """{"entity_id":"$entity","state":"$state","last_changed":"$LAST_CHANGED","attributes":$attributes}""",
    )

    private fun answer(status: String, headers: String = "", body: String = ""): String {
        val length = body.toByteArray(Charsets.UTF_8).size
        return "HTTP/1.1 $status\r\n${headers}Content-Length: $length\r\nConnection: close\r\n\r\n$body"
    }

    /** Bounded, never a bare sleep: polls [condition] until it holds or [WAIT_MILLIS] pass, then fails naming [what]. */
    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + WAIT_MILLIS
        while (!condition()) {
            if (SystemClock.uptimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            SystemClock.sleep(POLL_MILLIS)
        }
    }

    private companion object {
        const val STATUS_ENTITY = "input_select.example_heater_equipment_status"
        const val DATE_ENTITY = "input_datetime.example_heater_service_done"
        const val ASSET_NAME = "Example Heater"
        const val TOKEN = "fictional-token-1"
        const val HOME_WIFI = "ExampleHomeWifi"
        const val LAST_CHANGED = "2026-10-01T06:00:00+00:00"

        const val POST = "POST"
        const val GET = "GET"
        const val JSON = "application/json"
        const val STATES_PATH = "/api/states/"
        const val SERVICES_PATH = "/api/services/"
        const val SELECT_OPTION_PATH = "input_select/select_option"
        const val SET_DATETIME_PATH = "input_datetime/set_datetime"

        const val CLIENT_ID = "proof-publish-client"
        const val RUNNER_ID = "proof-publish-runner"

        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        val ID_SHAPE = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        val DAY_MILLIS: Long = Duration.ofHours(SyncCadence.DAILY.hours).toMillis()
        val WEEK_MILLIS: Long = Duration.ofHours(SyncCadence.WEEKLY.hours).toMillis()

        const val WAIT_MILLIS = 10_000L
        const val POLL_MILLIS = 50L
    }
}

/** One request as the fake read it: the request line, the header lines, and the `Content-Length` body as text. */
private class FakeRequest(val line: String, val headers: List<String>, val body: String) {
    val method: String get() = line.substringBefore(' ')

    /** A header's value, by name without regard to case. */
    fun header(name: String): String? =
        headers.firstOrNull { it.substringBefore(':').trim().equals(name, ignoreCase = true) }?.substringAfter(':')?.trim()
}

/**
 * The in-process stand-in for Home Assistant: one listener on [address] — this device's own — at port 0. For each
 * connection it reads the head (the request line, then the header lines) and exactly `Content-Length` bytes of body,
 * keeps the request, writes [respond]'s answer once and closes. [accepted] counts connections.
 */
private class FakeHomeAssistant(
    private val address: Inet4Address,
    private val respond: (FakeRequest) -> String,
) : Closeable {
    private val listener: ServerSocket = ServerSocket(0, BACKLOG, address)
    val accepted = AtomicInteger()
    val requests: MutableList<FakeRequest> = CopyOnWriteArrayList()
    private val worker = thread(isDaemon = true, name = "fake-home-assistant-publish") { serve() }

    fun origin(): String = "http://${address.hostAddress}:${listener.localPort}"

    private fun serve() {
        while (true) {
            val socket = try {
                listener.accept()
            } catch (_: Exception) {
                return // closed
            }
            accepted.incrementAndGet()
            try {
                socket.use {
                    it.soTimeout = SOCKET_TIMEOUT_MILLIS
                    val input = it.getInputStream()
                    val head = headOf(input)
                    if (head.isEmpty()) return@use
                    val headers = head.drop(1)
                    val length = headers.firstOrNull { line -> line.substringBefore(':').trim().equals("Content-Length", true) }
                        ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
                    val request = FakeRequest(head.first(), headers, bodyOf(input, length))
                    requests += request
                    it.getOutputStream().apply {
                        write(respond(request).toByteArray(Charsets.UTF_8))
                        flush()
                    }
                }
            } catch (_: Exception) {
                // a client that hung up: nothing to answer
            }
        }
    }

    override fun close() {
        listener.close()
        worker.join(JOIN_MILLIS)
    }

    private companion object {
        const val BACKLOG = 8
        const val SOCKET_TIMEOUT_MILLIS = 10_000
        const val JOIN_MILLIS = 5_000L
        const val MAX_HEAD_BYTES = 16 * 1024
        const val MAX_BODY_BYTES = 16 * 1024
        const val END_OF_HEAD = 0x0D0A0D0A

        fun headOf(input: InputStream): List<String> {
            val head = ByteArrayOutputStream()
            var last4 = 0
            while (head.size() < MAX_HEAD_BYTES) {
                val b = input.read()
                if (b < 0) break
                head.write(b)
                last4 = (last4 shl 8) or b
                if (last4 == END_OF_HEAD) break
            }
            return head.toString("ISO-8859-1").split("\r\n").filter { it.isNotEmpty() }
        }

        fun bodyOf(input: InputStream, length: Int): String {
            val wanted = length.coerceIn(0, MAX_BODY_BYTES)
            val body = ByteArray(wanted)
            var read = 0
            while (read < wanted) {
                val n = input.read(body, read, wanted - read)
                if (n < 0) break
                read += n
            }
            return String(body, 0, read, Charsets.UTF_8)
        }
    }
}
