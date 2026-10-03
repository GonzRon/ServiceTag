package com.loosecannon.servicetag.seasonsync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.security.NetworkSecurityPolicy
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import com.loosecannon.servicetag.MainActivity
import com.loosecannon.servicetag.ServiceTagApp
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.seasonsync.BackgroundChecks
import com.loosecannon.servicetag.core.seasonsync.CurrentNetwork
import com.loosecannon.servicetag.core.seasonsync.HaConnection
import com.loosecannon.servicetag.core.seasonsync.HaReadOutcome
import com.loosecannon.servicetag.core.seasonsync.HaSwitchState
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncBinding
import com.loosecannon.servicetag.core.seasonsync.Secret
import com.loosecannon.servicetag.core.seasonsync.SyncCadence
import com.loosecannon.servicetag.core.seasonsync.SyncErrorKind
import com.loosecannon.servicetag.core.seasonsync.SyncMode
import com.loosecannon.servicetag.fetch.InetHostResolver
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.URI
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.SecretKey
import javax.net.ServerSocketFactory
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509KeyManager
import kotlin.concurrent.thread
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * #16 C29 (row 71; R16-12, R16-20 as amended, R16-21) — the season sync's platform facts, the ones no JVM test can
 * see: WorkManager's record of the one `"season-sync"` work, the Android Keystore and the no-backup directory, the
 * platform's network security policy and HTTP stack against an in-process fake Home Assistant, and C32's real reader
 * naming the emulator's Wi-Fi. Each case's KDoc names the one OS boundary it proves (#62). The decisions themselves
 * (the address rule, the network match, the mapper, the applier, the 404 and `unknown`/`unavailable` answers) are the
 * JVM's (rows 1–60); a case here may run one of them through the platform, but nothing here is their proof.
 *
 * **The fake Home Assistant** listens in this instrumentation process on this device's own site-local IPv4 address,
 * read at run time (the default network's link addresses, else the interfaces), on port 0 — never loopback, which the
 * address rule refuses, and never beyond the device. The client cases hand the client a scripted eligible network, so
 * the GET never depends on the emulator's active network (the rule is JVM-proven); only the worker case reads the real
 * one, because that read is its point.
 *
 * **Order matters for the grants, so it is fixed.** [GrantPermissionRule] grants precise and approximate location to
 * every case (while in use). Background location is granted through `UiAutomation` and is never withdrawn here — a
 * withdrawal kills the process (§7's step) — so the two cases that need it not yet granted run first by name:
 * `foreground…` (the while-in-use read) and then `homeOn…` (nothing enqueued without the grant, then the grant), before
 * `worker…`. A truly backgrounded phone's worker is limit 19's, not this class's.
 *
 * **This class needs a fresh install** (the gate installs one per class): background location already granted when it
 * starts fails `foreground…` and `homeOn…` on purpose — uninstall the app and rerun. Before every case it waits, bounded,
 * for the app's start-up work on `appScope` (the token sweep and the schedule check, launched by `ServiceTagApp`), so
 * neither can race a case.
 *
 * **Teardown, every time (N-15):** the connections this class stored (their bindings go by the CASCADE), their tokens
 * and the Keystore cases' keys and files, the `"season-sync"` work, the asset it created, the fake listeners, the
 * worker's dispatch, the proxy properties and Location as found. It never touches a row it did not create: no case
 * starts when this install already holds a connection, and then the teardown leaves the work alone too.
 */
@RunWith(AndroidJUnit4::class)
// Load-bearing: `foreground…` and `homeOn…` need background location not yet granted, and their names sort before every
// case that grants it. A renamed or added case that grants it has to sort after them.
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SeasonSyncPlatformProofTest {

    @get:Rule
    val location: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val graph get() = (context.applicationContext as ServiceTagApp).graph

    private val listeners = mutableListOf<FakeHomeAssistant>()
    private val storedConnectionIds = linkedSetOf<String>()
    private val createdAssets = mutableListOf<AssetId>()
    private val savedProxy = linkedMapOf<String, String?>()
    private var savedDispatch: SeasonSyncRunner? = null
    private var locationWasOff = false
    private var startedClean = false

    @Before
    fun setUp() {
        savedDispatch = SeasonSyncDispatch.runner
        waitUntil("the app's start-up work on appScope (the token sweep and the schedule check) to finish") {
            graph.appScope.coroutineContext.job.children.none()
        }
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
        // The rows first, so a pass still in flight drops its result and a late schedule check finds nothing to keep.
        step { runBlocking { storedConnectionIds.forEach { graph.uow.write { graph.haConnections.delete(it) } } } }
        step { runBlocking { (storedConnectionIds + KEYSTORE_IDS).forEach { graph.secretStore.delete(it) } } }
        if (startedClean) {
            step {
                WorkManagerSeasonSync(context).cancel()
                waitUntil("no season sync work left pending") { pendingWork().isEmpty() }
            }
        }
        step { runBlocking { createdAssets.forEach { graph.deleteAsset.run(it) } } }
        listeners.forEach { step { it.close() } }
        step { restoreProxy() }
        step { if (locationWasOff) setLocation(enabled = false) }
        SeasonSyncDispatch.runner = savedDispatch
        failures.firstOrNull()?.let { throw it }
    }

    /**
     * OS boundary: the platform's `NetworkSecurityPolicy`, built from the merged manifest's
     * `android:networkSecurityConfig` (C20). Cleartext to this device's own RFC 1918 address is permitted by the
     * platform itself; confining http to a home-network RFC 1918 literal is the client's rule (C19), not the policy's.
     */
    @Test
    fun cleartextToThisDevicesSiteLocalAddressIsPermittedByThePlatform() {
        val host = checkNotNull(siteLocalAddress().hostAddress)
        val policy = NetworkSecurityPolicy.getInstance()

        assertTrue("the base config permits cleartext", policy.isCleartextTrafficPermitted)
        assertTrue("and so for this device's own address", policy.isCleartextTrafficPermitted(host))
    }

    /**
     * OS boundary: `AndroidKeyStore` — a key entry deleted under the store while its ciphertext file stays, as after a
     * Keystore reset: the harder case beside a platform restore (H5), which brings the Room rows back with neither the
     * key nor the file. [KeystoreSecretStore.get] answers null without a throw and `has` is false, so the binding reads
     * NEEDS_TOKEN (C18, row 48's device twin, §7).
     */
    @Test
    fun deletedKeystoreKeyReadsNullWhileItsFileStays() {
        runBlocking {
            val store = KeystoreSecretStore.onDevice(context)
            store.put(KEY_GONE_ID, Secret(TOKEN))
            val file = tokenFile(KEY_GONE_ID)
            assertTrue(file.isFile)

            androidKeyStore().deleteEntry(KeystoreSecretStore.ALIAS_PREFIX + KEY_GONE_ID)

            assertNull("a file without its key opens to nothing, never a throw", store.get(KEY_GONE_ID))
            assertFalse("the file alone is not a stored token", store.has(KEY_GONE_ID))
            assertTrue("the file stays until the store deletes it", file.isFile)
            store.delete(KEY_GONE_ID)
            assertFalse(file.exists())
        }
    }

    /**
     * OS boundary: `ConnectivityManager`'s location-gated Wi-Fi information (C32; `FLAG_INCLUDE_LOCATION_INFO` from
     * API 31, `WifiManager.getConnectionInfo` before) — read by the app's own reader from a foreground activity with
     * the while-in-use precise grant and Location on (N-2), naming the emulator's Wi-Fi. Expected `AndroidWifi`,
     * unverified until this run; a hidden name here is a stop, never a rerun.
     */
    @Test
    fun foregroundReadNamesTheEmulatorsWifi() {
        locationOn()
        val platform = AndroidNetworkPlatform(context)
        assertTrue("precondition: precise location granted", platform.preciseLocationGranted())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            assertFalse(
                "background location is already granted: this class needs a fresh install, uninstall the app and rerun",
                platform.backgroundLocationGranted(),
            )
        }

        ActivityScenario.launch(MainActivity::class.java).use {
            waitUntil("the app in the foreground") { platform.inForeground() }
            val reading = runBlocking { graph.currentNetworkReader.read() }

            val wifi = reading.network as? CurrentNetwork.Wifi
                ?: throw AssertionError("a Wi-Fi name was not read in the foreground (a stop, never a rerun): $reading")
            assertEquals(EMULATOR_WIFI, wifi.ssid)
            assertNull(reading.cause)
        }
    }

    /**
     * OS boundary: the runtime grant of `ACCESS_BACKGROUND_LOCATION` as the package manager answers it, and
     * WorkManager's record. The runner's `ensure()` applies C22's rule to a stored "Only on this home Wi-Fi" connection
     * with background checks On: without the grant nothing is enqueued (the fallback, P16-75's PAUSED); once
     * `UiAutomation` grants it, one `"season-sync"` work is enqueued at the cadence's period with the CONNECTED
     * constraint. The binding has no token, so the work's own run reads nothing.
     */
    @Test
    fun homeOnWorkIsEnqueuedOnlyOnceTheBackgroundGrantIsGiven() {
        val asset = newAsset()
        storeConnection(connection(HOME_ON_ID, FICTIONAL_HTTPS_ORIGIN, homeWifi = HOME_WIFI))
        storeBinding(binding(asset, HOME_ON_ID))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            assertFalse(
                "background location is already granted: this class needs a fresh install, uninstall the app and rerun",
                graph.seasonSyncBackgroundAllowed(),
            )
            runBlocking { graph.seasonSyncRunner.ensure() }
            assertEquals("without the background grant nothing is enqueued", emptyList<WorkInfo>(), pendingWork())
            grantBackgroundLocation()
        }
        assertTrue(graph.seasonSyncBackgroundAllowed())
        runBlocking { graph.seasonSyncRunner.ensure() }

        waitUntil("one season sync work") { pendingWork().size == 1 }
        val work = pendingWork().single()
        assertEquals(DAY_MILLIS, work.periodicityInfo?.repeatIntervalMillis)
        assertEquals(NetworkType.CONNECTED, work.constraints.requiredNetworkType)
    }

    /**
     * OS boundary: `AndroidKeyStore` and the app's `noBackupFilesDir`, through the device's own store
     * ([KeystoreSecretStore.onDevice]): `put`, `has`, `get` the same token; one sealed file under
     * [KeystoreSecretStore.DIRECTORY] that holds no plaintext byte run; the key a non-exportable Keystore AES key;
     * `delete` takes the key and the file.
     */
    @Test
    fun keystoreRoundTripLeavesOnlyCiphertextUnderTheNoBackupDirectory() {
        runBlocking {
            val store = KeystoreSecretStore.onDevice(context)
            val alias = KeystoreSecretStore.ALIAS_PREFIX + ROUND_TRIP_ID
            store.put(ROUND_TRIP_ID, Secret(TOKEN))

            assertTrue(store.has(ROUND_TRIP_ID))
            assertEquals(TOKEN, store.get(ROUND_TRIP_ID)?.value)
            val file = tokenFile(ROUND_TRIP_ID)
            assertTrue(file.isFile)
            val plaintext = TOKEN.toByteArray(Charsets.UTF_8)
            assertEquals("the IV, the ciphertext and the tag", IV_BYTES + plaintext.size + TAG_BYTES, file.length().toInt())
            File(context.noBackupFilesDir, KeystoreSecretStore.DIRECTORY).listFiles().orEmpty().filter { it.isFile }
                .forEach { assertFalse("no plaintext in ${it.name}", it.readBytes().holds(plaintext)) }
            val key = androidKeyStore().getKey(alias, null)
            assertTrue("a Keystore secret key", key is SecretKey)
            assertEquals("AES", key.algorithm)
            assertNull("a Keystore key never leaves the Keystore", key.encoded)

            store.delete(ROUND_TRIP_ID)
            assertFalse(store.has(ROUND_TRIP_ID))
            assertNull(store.get(ROUND_TRIP_ID))
            assertFalse(file.exists())
            assertFalse(androidKeyStore().containsAlias(alias))
        }
    }

    /**
     * OS boundary: the platform's HTTP stack answering a 401 on a real socket — surfaced as the status, never retried
     * through an authenticator — which the client records as `AUTH_REFUSED`.
     */
    @Test
    fun realClientRecordsA401AsAuthRefused() {
        val fake = listen(answer("401 Unauthorized", headers = "WWW-Authenticate: Bearer\r\n"))

        assertEquals(HaReadOutcome.NoDecision(SyncErrorKind.AUTH_REFUSED, null), read(fake.origin()))
        assertEquals(1, fake.requests.size)
    }

    /**
     * OS boundary: the platform's `ProxySelector` and `URLConnection`. With `http.proxyHost` naming a host that can
     * never resolve, the selector would send this URL to that proxy; the client's `Proxy.NO_PROXY` connection goes
     * straight to the fake Home Assistant (B5 review NOTE-6), whose request line is origin-form.
     */
    @Test
    fun realClientGoesDirectWhenTheSystemNamesAProxy() {
        val fake = listen(stateAnswer("on"))
        setProxy(BOGUS_PROXY_HOST, BOGUS_PROXY_PORT)
        val selected = ProxySelector.getDefault().select(URI(fake.origin() + STATES_PATH + ENTITY)).first()
        assertEquals("precondition: the system names the proxy for this URL", Proxy.Type.HTTP, selected.type())

        assertEquals(HaReadOutcome.Observed(HaSwitchState.ON, LAST_CHANGED), read(fake.origin()))
        assertEquals("GET $STATES_PATH$ENTITY HTTP/1.1", fake.requests.single().first())
    }

    /**
     * OS boundary: the platform's HTTP stack honouring `instanceFollowRedirects = false`. A 302 is `REDIRECTED`, and
     * the listener its `Location` names is never connected to.
     */
    @Test
    fun realClientDoesNotFollowA302AndItsTargetIsUntouched() {
        val target = listen(stateAnswer("on"))
        val fake = listen(answer("302 Found", headers = "Location: ${target.origin()}$STATES_PATH$ENTITY\r\n"))

        assertEquals(HaReadOutcome.NoDecision(SyncErrorKind.REDIRECTED, null), read(fake.origin()))
        assertEquals(1, fake.requests.size)
        assertEquals("the redirect's target is untouched", 0, target.accepted.get())
    }

    /**
     * OS boundary: the platform's TLS stack under the network security config's trust anchors (system only, C20):
     * a local listener that does present its self-signed certificate (asserted on its key manager) has **every**
     * handshake refused by the client, recorded as `TLS_FAILED` (R16-Q-B (d)), and no request reaches it. A client
     * that completed the handshake — a permissive trust manager failing only the host name check — fails here.
     */
    @Test
    fun realClientRefusesASelfSignedCertificateAsTlsFailed() {
        val fake = listen(stateAnswer("on"), selfSignedServerSockets())

        assertEquals(HaReadOutcome.NoDecision(SyncErrorKind.TLS_FAILED, null), read(fake.origin(scheme = "https")))
        waitUntil("every connection the listener accepted to finish") {
            fake.accepted.get() >= 1 && fake.finished.get() == fake.accepted.get()
        }
        assertEquals("every handshake refused, by the trust store", fake.accepted.get(), fake.refusedHandshakes.get())
        assertEquals("no request was sent over a refused handshake", 0, fake.requests.size)
    }

    /**
     * OS boundary: a real socket from the platform's HTTP stack, in cleartext under the platform's policy, to this
     * device's own address: one `GET /api/states/<entity_id>` carrying `Authorization: Bearer fictional-token-1`,
     * answered `on`, is `Observed(ON)` with HA's change text.
     */
    @Test
    fun realClientReadsOnFromTheFakeHomeAssistantWithTheBearer() {
        val fake = listen(stateAnswer("on"))

        assertEquals(HaReadOutcome.Observed(HaSwitchState.ON, LAST_CHANGED), read(fake.origin()))
        val request = fake.requests.single()
        assertEquals("GET $STATES_PATH$ENTITY HTTP/1.1", request.first())
        assertEquals("Bearer $TOKEN", request.header("Authorization"))
    }

    /**
     * OS boundary: WorkManager's record, through the real seam ([WorkManagerSeasonSync]): `ensure` at DAILY leaves one
     * `"season-sync"` work at a day's period with the CONNECTED constraint; `ensure` at WEEKLY (UPDATE) leaves the same
     * one work at a week's; `cancel` finishes it. The worker's dispatch is unassigned for this case, so a run while it
     * is read back does nothing (C22's unassigned dispatch) and cannot cancel what is being read.
     */
    @Test
    fun uniqueWorkTakesTheCadencePeriodAndUpdateKeepsOneWorkUntilCancelled() {
        SeasonSyncDispatch.runner = null
        val seam = WorkManagerSeasonSync(context)

        seam.ensure(SeasonSyncWorkRequest.forCadence(SyncCadence.DAILY))
        waitUntil("one work at a day") { pendingPeriod() == DAY_MILLIS }
        val daily = pendingWork().single()
        assertEquals(NetworkType.CONNECTED, daily.constraints.requiredNetworkType)
        assertTrue(seam.isEnqueued())

        seam.ensure(SeasonSyncWorkRequest.forCadence(SyncCadence.WEEKLY))
        waitUntil("one work at a week") { pendingPeriod() == WEEK_MILLIS }
        assertEquals("UPDATE keeps the one work", daily.id, pendingWork().single().id)
        assertEquals(NetworkType.CONNECTED, pendingWork().single().constraints.requiredNetworkType)

        seam.cancel()
        waitUntil("the work finished") { pendingWork().isEmpty() }
        val cancelled = WorkManager.getInstance(context).getWorkInfosForUniqueWork(SeasonSyncWorker.UNIQUE_NAME).get()
            .single { it.id == daily.id }
        assertEquals(WorkInfo.State.CANCELLED, cancelled.state)
        assertFalse(seam.isEnqueued())
    }

    /**
     * OS boundary: the location-gated Wi-Fi name as a worker sees it with the background grant — the real
     * [SeasonSyncWorker], run by `TestListenableWorkerBuilder` through the app's own dispatch, runner, client and
     * reader. On the Wi-Fi captured as "Use the network I'm on now" would, the GET goes to the fake Home Assistant with
     * the bearer and the observation is recorded; with another captured name nothing is sent to the address now
     * stored, and the binding records `NOT_ON_LOCAL_NETWORK` with no cause.
     */
    @Test
    fun workerBodyProceedsOnTheCapturedWifiAndSendsNothingOnAnother() {
        locationOn()
        grantBackgroundLocation()
        val captured = runBlocking { graph.currentNetworkReader.read() }
        val wifi = captured.network as? CurrentNetwork.Wifi
            ?: throw AssertionError("no Wi-Fi name with the background grant (a stop, never a rerun): $captured")
        assertNotEquals(HOME_WIFI, wifi.ssid)

        val home = listen(stateAnswer("on"))
        val asset = newAsset()
        storeConnection(connection(WORKER_ID, home.origin(), homeWifi = wifi.ssid))
        storeBinding(binding(asset, WORKER_ID))
        runBlocking { graph.secretStore.put(WORKER_ID, Secret(TOKEN)) }

        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertTrue("on the captured Wi-Fi the worker sent its GET", home.requests.isNotEmpty())
        home.requests.forEach { assertEquals("Bearer $TOKEN", it.header("Authorization")) }
        val observed = storedBinding(asset)
        assertEquals(HaSwitchState.ON, observed.observedState)
        assertNotNull(observed.lastSuccessAt)

        val away = listen(stateAnswer("on"))
        storeConnection(connection(WORKER_ID, away.origin(), homeWifi = HOME_WIFI))
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals("on another Wi-Fi nothing is sent", 0, away.accepted.get())
        val refused = storedBinding(asset)
        assertEquals(SyncErrorKind.NOT_ON_LOCAL_NETWORK, refused.errorKind)
        assertNull("another network names no cause", refused.errorDetail)
    }

    // --- the client, the rows and the platform --------------------------------------------------------------------

    /** The real client over a connection never stored, its network scripted as the captured one (C19 step 1a). */
    private fun read(origin: String): HaReadOutcome {
        val connection = connection(CLIENT_ID, origin, homeWifi = HOME_WIFI)
        val client = HomeAssistantStateClient(
            settings = { connection },
            networkPermissionGranted = ::internetGranted,
            currentNetwork = CurrentNetworkReader { NetworkReading(CurrentNetwork.Wifi(HOME_WIFI), null) },
            resolver = InetHostResolver(::internetGranted),
        )
        return runBlocking { client.read(origin, ENTITY, Secret(TOKEN)) }
    }

    private fun internetGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED

    private fun runWorker(): ListenableWorker.Result =
        runBlocking { TestListenableWorkerBuilder<SeasonSyncWorker>(context).build().doWork() }

    /** The one pending work's requested period, or null unless exactly one is pending. */
    private fun pendingPeriod(): Long? = pendingWork().singleOrNull()?.periodicityInfo?.repeatIntervalMillis

    private fun pendingWork(): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(SeasonSyncWorker.UNIQUE_NAME).get()
            .filter { !it.state.isFinished }

    private fun newAsset(): AssetId =
        runBlocking { graph.createAsset.run(name = ASSET_NAME) }.id.also { createdAssets += it }

    private fun storeConnection(connection: HaConnection) {
        storedConnectionIds += connection.id
        runBlocking { graph.uow.write { graph.haConnections.upsert(connection) } }
    }

    private fun storeBinding(binding: SeasonSyncBinding) {
        runBlocking { graph.uow.write { graph.seasonSyncBindings.insert(binding) } }
    }

    private fun storedBinding(asset: AssetId): SeasonSyncBinding =
        checkNotNull(runBlocking { graph.seasonSyncBindings.get(asset) })

    private fun connection(id: String, origin: String, homeWifi: String): HaConnection {
        val now = System.currentTimeMillis()
        return HaConnection(
            id = id,
            baseUrl = origin,
            cadence = SyncCadence.DAILY,
            networkEligibility = NetworkEligibility.HOME_NETWORK_ONLY,
            homeNetworkSsid = homeWifi,
            backgroundChecks = BackgroundChecks.ON,
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun binding(asset: AssetId, connectionId: String): SeasonSyncBinding {
        val now = System.currentTimeMillis()
        return SeasonSyncBinding(
            assetId = asset,
            connectionId = connectionId,
            entityId = ENTITY,
            mode = SyncMode.FOLLOW,
            enabled = true,
            revision = 1,
            observedState = null,
            observedChangedAt = null,
            lastSuccessAt = null,
            lastAttemptAt = null,
            errorKind = null,
            errorDetail = null,
            errorAt = null,
            appliedAction = null,
            appliedOn = null,
            appliedAt = null,
            lastAppliedSource = null,
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun tokenFile(id: String): File =
        File(File(context.noBackupFilesDir, KeystoreSecretStore.DIRECTORY), "$id.bin")

    private fun androidKeyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    private fun grantBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        waitUntil("the background grant") { graph.seasonSyncBackgroundAllowed() }
    }

    /** N-2: Location switched on through `UiAutomation` when it is off, and switched back in the teardown. */
    private fun locationOn() {
        val platform = AndroidNetworkPlatform(context)
        if (platform.locationOn()) return
        locationWasOff = true
        setLocation(enabled = true)
        waitUntil("Location on") { platform.locationOn() }
    }

    private fun setLocation(enabled: Boolean) {
        val command = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            "cmd location set-location-enabled $enabled"
        } else {
            "settings put secure location_mode ${if (enabled) LOCATION_MODE_ON else LOCATION_MODE_OFF}"
        }
        val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
    }

    private fun setProxy(host: String, port: String) {
        for (name in PROXY_PROPERTIES) if (name !in savedProxy) savedProxy[name] = System.getProperty(name)
        System.setProperty(HTTP_PROXY_HOST, host)
        System.setProperty(HTTP_PROXY_PORT, port)
        System.clearProperty(HTTP_NON_PROXY_HOSTS)
    }

    private fun restoreProxy() {
        savedProxy.forEach { (name, value) ->
            if (value == null) System.clearProperty(name) else System.setProperty(name, value)
        }
        savedProxy.clear()
    }

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

    private fun listen(answer: String, sockets: ServerSocketFactory = ServerSocketFactory.getDefault()): FakeHomeAssistant =
        FakeHomeAssistant(siteLocalAddress(), answer, sockets).also { listeners += it }

    /** A TLS listener presenting [SELF_SIGNED_CERTIFICATE] (CN `ha.example`, made for this class, trusted by nothing). */
    private fun selfSignedServerSockets(): ServerSocketFactory {
        val decoder = Base64.getDecoder()
        val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(decoder.decode(SELF_SIGNED_KEY)))
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(decoder.decode(SELF_SIGNED_CERTIFICATE)))
        val password = KEY_PASSWORD.toCharArray()
        val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry(TLS_ALIAS, key, password, arrayOf(certificate))
        }
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, password) }
        // Not vacuous: the listener really presents this certificate, so a refused handshake is the client's trust.
        val presented = managers.keyManagers.single() as X509KeyManager
        assertEquals(certificate, presented.getCertificateChain(TLS_ALIAS)?.single())
        assertNotNull(presented.getPrivateKey(TLS_ALIAS))
        return SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }.serverSocketFactory
    }

    private fun stateAnswer(state: String): String = answer(
        "200 OK",
        headers = "Content-Type: application/json\r\n",
        body = """{"entity_id":"$ENTITY","state":"$state","last_changed":"$LAST_CHANGED","attributes":{}}""",
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
        const val ENTITY = "input_boolean.example_heater_in_season"
        const val ASSET_NAME = "Example Heater"
        const val TOKEN = "fictional-token-1"
        const val HOME_WIFI = "ExampleHomeWifi"
        const val EMULATOR_WIFI = "AndroidWifi"
        const val FICTIONAL_HTTPS_ORIGIN = "https://ha.example:8123"
        const val LAST_CHANGED = "2026-10-01T06:00:00+00:00"
        const val STATES_PATH = "/api/states/"

        const val CLIENT_ID = "proof-client"
        const val HOME_ON_ID = "proof-home-on"
        const val WORKER_ID = "proof-worker"
        const val ROUND_TRIP_ID = "proof-keystore"
        const val KEY_GONE_ID = "proof-key-gone"
        val KEYSTORE_IDS = setOf(ROUND_TRIP_ID, KEY_GONE_ID)

        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val IV_BYTES = 12
        const val TAG_BYTES = 16

        val DAY_MILLIS: Long = Duration.ofHours(SyncCadence.DAILY.hours).toMillis()
        val WEEK_MILLIS: Long = Duration.ofHours(SyncCadence.WEEKLY.hours).toMillis()

        /** RFC 2606's `.invalid`: a proxy name that can never resolve. */
        const val BOGUS_PROXY_HOST = "proxy.invalid"
        const val BOGUS_PROXY_PORT = "3128"
        const val HTTP_PROXY_HOST = "http.proxyHost"
        const val HTTP_PROXY_PORT = "http.proxyPort"
        const val HTTP_NON_PROXY_HOSTS = "http.nonProxyHosts"
        val PROXY_PROPERTIES = listOf(HTTP_PROXY_HOST, HTTP_PROXY_PORT, HTTP_NON_PROXY_HOSTS)

        const val LOCATION_MODE_ON = 3
        const val LOCATION_MODE_OFF = 0
        const val WAIT_MILLIS = 10_000L
        const val POLL_MILLIS = 50L

        /** A throwaway P-256 key and its self-signed certificate (CN `ha.example`), made for this class alone. */
        const val SELF_SIGNED_KEY =
            "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgD/8axHr83Xxh1x6yo7YLO64wOcrMk6RVjbQkZtbZhQyhRANCAATEBxj6KOW/" +
                "xcSSk4njUBpc8mL3J4ASPYw2BVR0n+ZyO+5wj9wCsNVgsISodmijEqbobqCdK0ToFIqTPDhCfzRU"
        const val SELF_SIGNED_CERTIFICATE =
            "MIIBgjCCASegAwIBAgIUOA8Q5sTIbFcU5lQrlt5QTVfry6EwCgYIKoZIzj0EAwIwFTETMBEGA1UEAwwKaGEuZXhhbXBsZTAgFw0yNjEw" +
                "MDMxNDQzMTFaGA8yMTI2MDkwOTE0NDMxMVowFTETMBEGA1UEAwwKaGEuZXhhbXBsZTBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABMQH" +
                "GPoo5b/FxJKTieNQGlzyYvcngBI9jDYFVHSf5nI77nCP3AKw1WCwhKh2aKMSpuhuoJ0rROgUipM8OEJ/NFSjUzBRMB0GA1UdDgQWBBTL" +
                "VyB8eRv0a5sRd3H1zAta8XN1LTAfBgNVHSMEGDAWgBTLVyB8eRv0a5sRd3H1zAta8XN1LTAPBgNVHRMBAf8EBTADAQH/MAoGCCqGSM49" +
                "BAMCA0kAMEYCIQDa3P6ILc0JfJD/qComWazEeltzc4sqx4GC4jr4CIxnBwIhAIsyHcAGY7yVeD0dmf6jGzkGm2iF3d5UTBPQz2Sj3xqg"
        const val KEY_PASSWORD = "fictional"
        const val TLS_ALIAS = "fake-home-assistant"
    }
}

/**
 * The in-process stand-in for Home Assistant (C29): one listener on [address] — this device's own — at port 0,
 * answering every connection with [answer] once and keeping each non-empty request head (the request line, then the
 * header lines). [accepted] counts connections, [finished] those it is done with, and [refusedHandshakes] the TLS
 * handshakes that failed (an `SSLSocket`'s handshake is started explicitly, before anything is read).
 */
private class FakeHomeAssistant(
    private val address: Inet4Address,
    private val answer: String,
    sockets: ServerSocketFactory,
) : Closeable {
    private val listener: ServerSocket = sockets.createServerSocket(0, BACKLOG, address)
    val accepted = AtomicInteger()
    val finished = AtomicInteger()
    val refusedHandshakes = AtomicInteger()
    val requests: MutableList<List<String>> = CopyOnWriteArrayList()
    private val worker = thread(isDaemon = true, name = "fake-home-assistant") { serve() }

    fun origin(scheme: String = "http"): String = "$scheme://${address.hostAddress}:${listener.localPort}"

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
                    if (it is SSLSocket) {
                        try {
                            it.startHandshake()
                        } catch (_: IOException) {
                            refusedHandshakes.incrementAndGet()
                            return@use
                        }
                    }
                    val head = headOf(it.getInputStream())
                    if (head.isNotEmpty()) requests += head
                    it.getOutputStream().apply {
                        write(answer.toByteArray(Charsets.UTF_8))
                        flush()
                    }
                }
            } catch (_: Exception) {
                // a client that hung up: nothing to answer
            } finally {
                finished.incrementAndGet()
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
    }
}

/** A request head's header value, by name without regard to case. */
private fun List<String>.header(name: String): String? =
    drop(1).firstOrNull { it.substringBefore(':').trim().equals(name, ignoreCase = true) }?.substringAfter(':')?.trim()

/** Whether [needle] occurs anywhere in these bytes. */
private fun ByteArray.holds(needle: ByteArray): Boolean =
    (0..size - needle.size).any { start -> needle.indices.all { this[start + it] == needle[it] } }
