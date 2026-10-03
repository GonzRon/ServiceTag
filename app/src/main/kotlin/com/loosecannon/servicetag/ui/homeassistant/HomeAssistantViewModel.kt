package com.loosecannon.servicetag.ui.homeassistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.servicetag.core.seasonsync.BackgroundChecks
import com.loosecannon.servicetag.core.seasonsync.CurrentNetwork
import com.loosecannon.servicetag.core.seasonsync.ForgetHaConnection
import com.loosecannon.servicetag.core.seasonsync.HaConnection
import com.loosecannon.servicetag.core.seasonsync.HaConnectionRepository
import com.loosecannon.servicetag.core.seasonsync.HaEndpointPolicy
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.core.seasonsync.SaveHaConnection
import com.loosecannon.servicetag.core.seasonsync.SaveHaConnectionRefusal
import com.loosecannon.servicetag.core.seasonsync.SaveHaConnectionRefused
import com.loosecannon.servicetag.core.seasonsync.Secret
import com.loosecannon.servicetag.core.seasonsync.SecretStore
import com.loosecannon.servicetag.core.seasonsync.SyncCadence
import com.loosecannon.servicetag.core.seasonsync.SyncErrorKind
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.seasonsync.ConnectionTestOutcome
import com.loosecannon.servicetag.seasonsync.CurrentNetworkReader
import com.loosecannon.servicetag.seasonsync.NetworkPlatform
import com.loosecannon.servicetag.seasonsync.UnconfirmedCause
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** #16 (C26, R16-Q-D): the four cadences with their words, in the order drawn; [SyncCadence.DAILY] is preselected. */
internal val HA_CADENCE_CHOICES: List<Pair<SyncCadence, String>> = SyncCadence.entries.map { it to cadenceLabel(it) }

/** #16 (C26, C32): what the screen asks Android for, on the owner's choice only; it reports back when answered. */
internal enum class PermissionRequest {
    /** Precise and approximate location together (API 31+ asks them as a pair; approximate alone counts as no). */
    PRECISE_LOCATION,

    /** API 29: background location through the system dialog. */
    BACKGROUND_LOCATION,

    /** The app's settings page: background location on API 30+, or a change to Precise. */
    APP_SETTINGS,
}

/** The button a line carries: P16-76 re-running the matching flow, or "Open app settings". */
internal enum class NoticeAction { ALLOW_PRECISE_AGAIN, ALLOW_BACKGROUND_AGAIN, OPEN_APP_SETTINGS }

/** One ratified sentence the screen draws, and its button if it has one. */
internal data class Notice(val text: String, val action: NoticeAction? = null)

/**
 * #16 (C26) — the Home Assistant screen as drawn. The form holds the stored connection's settings until the owner
 * changes them; the access token is never here: [tokenPresent] says only whether the store holds one (the typed text
 * lives in [HomeAssistantViewModel.tokenField] until Save stores it). The grants are read, never asked, on entry.
 */
internal data class HomeAssistantState(
    val loaded: Boolean = false,
    val connected: Boolean = false,
    val tokenPresent: Boolean = false,
    val address: String = "",
    val cadence: SyncCadence = SyncCadence.DAILY,
    val eligibility: NetworkEligibility = NetworkEligibility.ANY_NETWORK,
    val homeWifi: String? = null,
    val backgroundChecks: BackgroundChecks = BackgroundChecks.OFF,
    val preciseGranted: Boolean = false,
    val approximateGranted: Boolean = false,
    val backgroundGranted: Boolean = false,
    val busy: Boolean = false,
    /** The last action's answer: a refusal, Test connection's outcome, a capture or a permission flow's line. */
    val notices: List<Notice> = emptyList(),
    /** P16-73, while it waits for the owner's Open app settings or Cancel. */
    val backgroundAsk: String? = null,
    /** P16-9, while the Disconnect confirmation is open. */
    val disconnectAsk: String? = null,
    /** A home-network save refused for want of a captured name: the screen raises P16-62's button. */
    val promptCapture: Boolean = false,
    /** The stored connection as last read (no token in it): what an empty token field may test. */
    val stored: HaConnection? = null,
) {
    /**
     * The form still describes [stored] — its canonical address, its network choice and, under "Only on this home
     * Wi-Fi", its captured name — so Test connection with an empty token field may use the stored token for that one
     * request. Any other form never receives it (I10, R16-6): the screen keeps the button off, the model refuses.
     */
    val testsStoredConnection: Boolean get() = stored?.let(::describes) == true

    /** [connection]'s origin and network rule, as this form would send them. */
    fun describes(connection: HaConnection): Boolean {
        val homeOnly = eligibility == NetworkEligibility.HOME_NETWORK_ONLY
        return HaEndpointPolicy.canonical(address.trim()) == connection.baseUrl &&
            eligibility == connection.networkEligibility &&
            homeWifi.takeIf { homeOnly } == connection.homeNetworkSsid
    }

    /** The derived status line: P16-10 with no connection, P16-11 when this phone has lost the token (C17). */
    val statusLine: String?
        get() = when {
            !loaded -> null
            !connected -> HA_NOT_CONNECTED
            !tokenPresent -> HA_ENTER_TOKEN_AGAIN
            else -> null
        }

    /** The capture button and the background checks appear under "Only on this home Wi-Fi" only. */
    val homeNetworkChosen: Boolean get() = eligibility == NetworkEligibility.HOME_NETWORK_ONLY

    /** P16-63 once a name is captured. */
    val homeWifiLine: String? get() = homeWifi?.takeIf { homeNetworkChosen }?.let(::haHomeWifi)

    /**
     * Under the home-network choice: the recovery when precise location is gone (P16-79 with Open app settings when
     * only approximate is left, else P16-78 with Allow again), then the background checks' line: P16-69 while off;
     * P16-74 while they run; while On cannot run, P16-69, after P16-75 with Allow again when only the background grant
     * is missing (C22's fallback).
     */
    val locationLines: List<Notice>
        get() = if (!homeNetworkChosen) emptyList() else buildList {
            if (!preciseGranted) {
                add(
                    if (approximateGranted) {
                        Notice(HA_APPROXIMATE_ONLY, NoticeAction.OPEN_APP_SETTINGS)
                    } else {
                        Notice(HA_PRECISE_LOCATION_WITHDRAWN, NoticeAction.ALLOW_PRECISE_AGAIN)
                    },
                )
            }
            when (backgroundChecks) {
                BackgroundChecks.OFF -> add(Notice(HA_BACKGROUND_OFF))
                BackgroundChecks.ON -> if (preciseGranted && backgroundGranted) {
                    add(Notice(HA_BACKGROUND_RUNNING))
                } else {
                    if (preciseGranted) add(Notice(HA_BACKGROUND_PAUSED, NoticeAction.ALLOW_BACKGROUND_AGAIN))
                    add(Notice(HA_BACKGROUND_OFF))
                }
            }
        }
}

/**
 * #16 (C26; R16-13, R16-14, R16-Q-D, R16-20 as amended, R16-21) — the Home Assistant screen's model, the Developer
 * API screen's shape: the writes are [SaveHaConnection] and [ForgetHaConnection], the reads the stored connection,
 * whether the store holds its token, the network reader and the location grants. Nothing here re-checks a rule the
 * use case owns: a refusal comes back as a code and is drawn as its ratified sentence.
 *
 * **Permissions are the owner's choice (C32):** precise location is asked only when the owner picks "Only on this
 * home Wi-Fi" (or taps Allow again under P16-78), after P16-65; background location only when Background checks is
 * turned On after that grant — P16-73 and the app's settings page on API 30+, the system dialog on API 29 (which has
 * no option label to quote), nothing on 26–28. Nothing is asked on entry or for "Any network". The screen launches
 * each [requests] item and reports back through [onPermissionAnswered] or [onResumed], which read the grants again.
 *
 * **The token** is trimmed and sent to the use case, which puts it in the store after its commit; [tokenField] is
 * emptied once Save has stored it or failed past its refusals; a refusal stores nothing and keeps it, since Home
 * Assistant shows a token once. Test connection uses the typed token, or the stored one for that one request while
 * the form still describes the stored connection ([HomeAssistantState.testsStoredConnection]), and never keeps it.
 */
internal class HomeAssistantViewModel(
    private val connections: HaConnectionRepository,
    private val secrets: SecretStore,
    private val saveConnection: SaveHaConnection,
    private val forgetConnection: ForgetHaConnection,
    private val testConnection: suspend (HaConnection, Secret) -> ConnectionTestOutcome,
    private val currentNetwork: CurrentNetworkReader,
    private val platform: NetworkPlatform,
    /** Android's own label for the background option, asked on API 30+ only. */
    private val backgroundOptionLabel: () -> String,
) : ViewModel() {

    constructor(graph: AppGraph, platform: NetworkPlatform, backgroundOptionLabel: () -> String) : this(
        graph.haConnections,
        graph.secretStore,
        graph.saveHaConnection,
        graph.forgetHaConnection,
        graph.haStateClient::testConnection,
        graph.currentNetworkReader,
        platform,
        backgroundOptionLabel,
    )

    private val mutableState = MutableStateFlow(HomeAssistantState())
    val state: StateFlow<HomeAssistantState> = mutableState.asStateFlow()

    private val typedToken = MutableStateFlow("")

    /** The masked field's text: held only while the owner types, emptied once Save stores it (a refusal keeps it). */
    val tokenField: StateFlow<String> = typedToken.asStateFlow()

    private val requestQueue = Channel<PermissionRequest>(Channel.BUFFERED)
    val requests: Flow<PermissionRequest> = requestQueue.receiveAsFlow()

    private var awaitingPrecise = false

    init {
        viewModelScope.launch { reload() }
    }

    fun onAddressChange(text: String) = mutableState.update { it.copy(address = text) }

    fun onTokenChange(text: String) {
        typedToken.value = text
    }

    fun chooseCadence(cadence: SyncCadence) = mutableState.update { it.copy(cadence = cadence) }

    /** "Any network" asks for nothing (R16-Q-D). */
    fun chooseAnyNetwork() {
        awaitingPrecise = false
        mutableState.update { it.copy(eligibility = NetworkEligibility.ANY_NETWORK, notices = emptyList()) }
    }

    /** "Only on this home Wi-Fi": chosen at once when precise location is held, else P16-65 and the request. */
    fun chooseHomeWifi() {
        val read = withGrants(mutableState.value)
        if (read.preciseGranted) {
            mutableState.value = read.copy(eligibility = NetworkEligibility.HOME_NETWORK_ONLY, notices = emptyList())
        } else {
            mutableState.value = read
            askPrecise()
        }
    }

    /** Off asks nothing; On runs the background flow when the grant is missing. */
    fun chooseBackgroundChecks(choice: BackgroundChecks) {
        mutableState.update { withGrants(it).copy(backgroundChecks = choice, backgroundAsk = null) }
        when (choice) {
            BackgroundChecks.OFF -> Unit
            BackgroundChecks.ON -> askBackground()
        }
    }

    /** P16-73's Open app settings: Android's settings page, where the owner grants it (API 30+). */
    fun acceptBackgroundAsk() {
        mutableState.update { it.copy(backgroundAsk = null) }
        requestQueue.trySend(PermissionRequest.APP_SETTINGS)
    }

    /** P16-73's Cancel: On stays chosen and paused, P16-75 says so, and checks run as Off's (C22). */
    fun declineBackgroundAsk() = mutableState.update { it.copy(backgroundAsk = null) }

    /** A line's button: Allow again re-runs the matching flow; Open app settings opens the app's page. */
    fun act(action: NoticeAction) = when (action) {
        NoticeAction.ALLOW_PRECISE_AGAIN -> askPrecise()
        NoticeAction.ALLOW_BACKGROUND_AGAIN -> askBackground()
        NoticeAction.OPEN_APP_SETTINGS -> {
            requestQueue.trySend(PermissionRequest.APP_SETTINGS)
            Unit
        }
    }

    /** The system request came back: precise granted chooses the home network; refused or approximate, P16-66. */
    fun onPermissionAnswered() {
        val read = withGrants(mutableState.value)
        mutableState.value = if (!awaitingPrecise) {
            read
        } else if (read.preciseGranted) {
            read.copy(eligibility = NetworkEligibility.HOME_NETWORK_ONLY, notices = emptyList())
        } else {
            read.copy(notices = listOf(Notice(HA_PRECISE_LOCATION_REFUSED)))
        }
        awaitingPrecise = false
    }

    /** Back on screen, from the settings page or anywhere: the grants are read again (a withdrawal shows here). */
    fun onResumed() {
        awaitingPrecise = false // the platform delivers a permission answer before the resume
        mutableState.update(::withGrants)
    }

    /** "Use the network I'm on now" (P16-62): the reader's answer, once; only a named Wi-Fi is captured. */
    fun captureNetwork() {
        viewModelScope.launch {
            val reading = currentNetwork.read()
            mutableState.update { state ->
                when (val network = reading.network) {
                    is CurrentNetwork.Wifi ->
                        state.copy(homeWifi = network.ssid, notices = emptyList(), promptCapture = false)
                    CurrentNetwork.WifiUnnamed -> state.copy(notices = listOfNotNull(reading.cause?.let(::remedyFor)))
                    CurrentNetwork.Wired -> state.copy(notices = listOf(Notice(HA_ON_ETHERNET)))
                    CurrentNetwork.Other, CurrentNetwork.None ->
                        state.copy(notices = listOf(Notice(HA_CONNECT_TO_HOME_WIFI)))
                }
            }
        }
    }

    /**
     * Save: the trimmed address and token and the form's settings, to [SaveHaConnection]. A token-only re-entry
     * resends the stored settings unchanged, so the row is updated in place. A refusal is its sentence; any other
     * failure reloads from the stores, whose status line then tells the truth (a token the store did not keep: P16-11).
     */
    fun save() {
        val form = mutableState.value
        val token = typedToken.value.trim()
        mutableState.update { it.copy(busy = true, notices = emptyList(), promptCapture = false) }
        viewModelScope.launch {
            try {
                val saved = saveConnection.run(
                    baseUrl = form.address.trim(),
                    token = token.takeIf { it.isNotEmpty() }?.let(::Secret),
                    cadence = form.cadence,
                    networkEligibility = form.eligibility,
                    homeNetworkSsid = form.homeWifi,
                    backgroundChecks = form.backgroundChecks,
                )
                typedToken.value = ""
                mutableState.value = loaded(saved, tokenHeld(saved.id))
            } catch (refused: SaveHaConnectionRefused) { // nothing was stored: the typed token stays
                mutableState.update {
                    it.copy(
                        busy = false,
                        notices = refusalNotices(refused.reason),
                        promptCapture = refused.reason == SaveHaConnectionRefusal.HOME_NETWORK_NOT_SET,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                typedToken.value = ""
                reload()
            }
        }
    }

    /**
     * Test connection (R16-13): the form's address and network setting, the typed token or else the stored one — the
     * stored one only while the form still describes the stored connection (I10); otherwise nothing is sent.
     */
    fun testConnection() {
        val form = mutableState.value
        val typed = typedToken.value.trim()
        mutableState.update { it.copy(busy = true, notices = emptyList()) }
        viewModelScope.launch {
            val stored = connections.get()
            val lent = stored?.takeIf(form::describes)
            val token = if (typed.isNotEmpty()) Secret(typed) else lent?.let { secrets.get(it.id) }
            var noName = false
            val notices = if (token == null) {
                // A changed form never borrows the stored token; its button is off, so no sentence is drawn here.
                if (stored != null && lent == null) {
                    emptyList()
                } else {
                    listOf(Notice(if (stored == null) HA_NOT_CONNECTED else HA_ENTER_TOKEN_AGAIN))
                }
            } else {
                val homeOnly = form.eligibility == NetworkEligibility.HOME_NETWORK_ONLY
                val candidate = HaConnection(
                    id = stored?.id.orEmpty(),
                    baseUrl = form.address.trim(),
                    cadence = form.cadence,
                    networkEligibility = form.eligibility,
                    homeNetworkSsid = form.homeWifi.takeIf { homeOnly },
                    backgroundChecks = form.backgroundChecks,
                    createdAt = 0L,
                    updatedAt = 0L,
                )
                val outcome = testConnection(candidate, token)
                // A home-network form with nothing captured: P16-62's prompt, as Save's HOME_NETWORK_NOT_SET.
                noName = homeOnly && candidate.homeNetworkSsid == null &&
                    outcome == ConnectionTestOutcome.Failed(SyncErrorKind.NOT_ON_LOCAL_NETWORK, null)
                if (noName) emptyList() else testNotices(outcome, candidate.networkEligibility)
            }
            mutableState.update { it.copy(busy = false, notices = notices, promptCapture = noName) }
        }
    }

    /** Disconnect asks P16-9 first. */
    fun askDisconnect() = mutableState.update { it.copy(disconnectAsk = HA_DISCONNECT_BODY) }

    fun dismissDisconnect() = mutableState.update { it.copy(disconnectAsk = null) }

    /** Confirmed: [ForgetHaConnection] deletes the connection, its bindings and the token; the screen reloads. */
    fun confirmDisconnect() {
        mutableState.update { it.copy(disconnectAsk = null, busy = true) }
        viewModelScope.launch {
            try {
                forgetConnection.run()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The connection row is gone whenever the store alone failed; the reload shows what is left.
            }
            typedToken.value = ""
            reload()
        }
    }

    private fun askPrecise() {
        if (awaitingPrecise) return // one request in flight: a second would be answered empty at once
        awaitingPrecise = true
        mutableState.update { it.copy(notices = listOf(Notice(HA_PRECISE_LOCATION_WHY))) }
        requestQueue.trySend(PermissionRequest.PRECISE_LOCATION)
    }

    /** After the foreground grant only; nothing when the grant is held (26–28 always hold it). */
    private fun askBackground() {
        val read = withGrants(mutableState.value)
        mutableState.value = read
        if (!read.preciseGranted || read.backgroundGranted) return
        if (platform.apiLevel < SETTINGS_PAGE_API) {
            requestQueue.trySend(PermissionRequest.BACKGROUND_LOCATION)
        } else {
            mutableState.value = read.copy(backgroundAsk = haBackgroundLocationWhy(backgroundOptionLabel()))
        }
    }

    private suspend fun reload() {
        val stored = connections.get()
        mutableState.value = loaded(stored, stored != null && tokenHeld(stored.id))
    }

    /** B4: the store passes a Keystore failure to its caller; here it reads as no token (P16-11), never a crash. */
    private suspend fun tokenHeld(id: String): Boolean = try {
        secrets.has(id)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private fun loaded(stored: HaConnection?, tokenPresent: Boolean): HomeAssistantState = withGrants(
        if (stored == null) {
            HomeAssistantState(loaded = true)
        } else {
            HomeAssistantState(
                loaded = true,
                connected = true,
                tokenPresent = tokenPresent,
                address = stored.baseUrl,
                cadence = stored.cadence,
                eligibility = stored.networkEligibility,
                homeWifi = stored.homeNetworkSsid,
                backgroundChecks = stored.backgroundChecks,
                stored = stored,
            )
        },
    )

    private fun withGrants(state: HomeAssistantState): HomeAssistantState = state.copy(
        preciseGranted = platform.preciseLocationGranted(),
        approximateGranted = platform.approximateLocationGranted(),
        backgroundGranted = platform.backgroundLocationGranted(),
    )

    private companion object {
        /** API 30: background location is granted on the app's settings page, no longer in a dialog (C32). */
        const val SETTINGS_PAGE_API = 30
    }
}

/** The save refusals' sentences (C17's codes); a missing captured name raises P16-62's button instead of a line. */
internal fun refusalNotices(reason: SaveHaConnectionRefusal): List<Notice> = when (reason) {
    SaveHaConnectionRefusal.ENDPOINT_REFUSED -> listOf(Notice(HA_ADDRESS_NOT_ALLOWED))
    SaveHaConnectionRefusal.HTTP_NEEDS_HOME_NETWORK -> listOf(Notice(HA_HTTP_NEEDS_HOME_WIFI))
    SaveHaConnectionRefusal.HOME_NETWORK_NOT_SET -> emptyList()
}

/** C-3: the remedy for a Wi-Fi name the reader could not confirm, by its cause, at capture and after P16-77. */
internal fun remedyFor(cause: UnconfirmedCause): Notice = when (cause) {
    UnconfirmedCause.LOCATION_OFF -> Notice(HA_TURN_ON_LOCATION)
    UnconfirmedCause.PERMISSION_MISSING -> Notice(HA_PRECISE_LOCATION_WITHDRAWN, NoticeAction.ALLOW_PRECISE_AGAIN)
    UnconfirmedCause.APPROXIMATE_ONLY -> Notice(HA_APPROXIMATE_ONLY, NoticeAction.OPEN_APP_SETTINGS)
    UnconfirmedCause.NOT_FOREGROUND -> Notice(HA_NOT_READ_IN_BACKGROUND)
    UnconfirmedCause.WIRED -> Notice(HA_ON_ETHERNET)
}

/**
 * Test connection's answer as drawn: P16-7, or the kind's sentence — an exhaustive `when`, no `else`. The kinds that
 * need an entity or an asset never come from the API root (N-3's map), so they draw nothing here; the card has them.
 * [tested] is the candidate's eligibility: under "Any network" the client checks the network only for an http
 * address (C19 step 1a, fail closed), so that answer is P16-67, with no remedy and no button.
 */
internal fun testNotices(outcome: ConnectionTestOutcome, tested: NetworkEligibility): List<Notice> = when (outcome) {
    ConnectionTestOutcome.Ok -> listOf(Notice(HA_CONNECTION_WORKS))
    is ConnectionTestOutcome.Failed -> when (outcome.kind) {
        SyncErrorKind.ENDPOINT_REFUSED -> listOf(Notice(HA_ADDRESS_NOT_ALLOWED))
        SyncErrorKind.DENIED -> listOf(Notice(HA_NETWORK_NOT_ALLOWED, NoticeAction.OPEN_APP_SETTINGS))
        SyncErrorKind.UNREACHABLE -> listOf(Notice(HA_COULD_NOT_REACH))
        SyncErrorKind.TIMED_OUT -> listOf(Notice(HA_TOOK_TOO_LONG))
        SyncErrorKind.AUTH_REFUSED -> listOf(Notice(HA_TOKEN_REFUSED))
        SyncErrorKind.REDIRECTED -> listOf(Notice(HA_ANSWERED_WITH_REDIRECT))
        SyncErrorKind.HTTP_ERROR -> listOfNotNull(outcome.detail?.let { Notice(haAnsweredWithError(it)) })
        SyncErrorKind.MALFORMED -> listOf(Notice(HA_ANSWER_UNREADABLE))
        SyncErrorKind.NEEDS_TOKEN -> listOf(Notice(HA_ENTER_TOKEN_AGAIN))
        SyncErrorKind.NOT_ON_LOCAL_NETWORK -> when (tested) {
            NetworkEligibility.ANY_NETWORK -> listOf(Notice(HA_HTTP_NEEDS_HOME_WIFI))
            NetworkEligibility.HOME_NETWORK_ONLY -> {
                val cause = UnconfirmedCause.entries.firstOrNull { it.name == outcome.detail }
                if (cause == null) {
                    listOf(Notice(HA_NOT_ON_HOME_WIFI))
                } else {
                    listOf(Notice(HA_COULD_NOT_CONFIRM_WIFI), remedyFor(cause))
                }
            }
        }
        SyncErrorKind.NAME_NOT_LOCAL -> listOf(Notice(HA_NAME_NOT_PRIVATE))
        SyncErrorKind.TLS_FAILED -> listOf(Notice(HA_CERTIFICATE_NOT_VERIFIED))
        SyncErrorKind.ENTITY_NOT_FOUND,
        SyncErrorKind.UNSUPPORTED_STATE,
        SyncErrorKind.NOT_MAINTAINED_HERE,
        SyncErrorKind.NOT_MANUAL,
        SyncErrorKind.DATE_BEFORE_HISTORY,
        -> emptyList()
    }
}
