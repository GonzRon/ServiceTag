package com.loosecannon.servicetag.ui.api

import android.util.Log
import androidx.lifecycle.ViewModel
import com.loosecannon.servicetag.api.ApiHandlers
import com.loosecannon.servicetag.api.ApiRouter
import com.loosecannon.servicetag.api.DEVELOPER_API_PORT
import com.loosecannon.servicetag.api.ListenerState
import com.loosecannon.servicetag.api.LoopbackApiServer
import com.loosecannon.servicetag.api.StartOutcome
import com.loosecannon.servicetag.api.newPairingCode
import com.loosecannon.servicetag.di.AppGraph
import javax.net.ServerSocketFactory
import kotlinx.coroutines.flow.StateFlow

private const val TAG = "ServiceTagApi"

/**
 * Owns one pairing code and one listener, for one visit to the Developer API screen.
 *
 * **The code is this model's, which is why it is fresh every time the screen opens.**
 * `ServiceTagRoot` decorates every nav entry with `rememberViewModelStoreNavEntryDecorator()` so
 * that a model lives for the visit and is cleared on the pop (`ServiceTagRoot.kt:88`–`95`). A new
 * visit is therefore a new model and a new code, and there is nowhere for the old one to be
 * remembered: it is deliberately not saved state, not a preference and not a `companion object`.
 *
 * **Starting and stopping is the screen's**, through `LifecycleResumeEffect` — the same primitive
 * the nav shell uses for reader mode (`ServiceTagRoot.kt:75`–`78`). This class exposes the two
 * calls and no lifecycle opinion of its own, and deliberately does **not** also stop in
 * `onCleared`: the effect's dispose has already run by the time the entry's store is cleared, and
 * two owners of one lifetime is how a socket ends up outliving a screen.
 *
 * **The permission probe has no default** (#66): it is consulted only after a bind has failed, to
 * tell a revoked network permission from anything else, and the screen builds it from
 * `ContextCompat.checkSelfPermission`. A default would let a caller forget the one input that
 * decides which sentence the owner reads.
 */
internal class DeveloperApiViewModel(
    handlers: ApiHandlers,
    networkPermissionGranted: () -> Boolean,
    port: Int = DEVELOPER_API_PORT,
    serverSocketFactory: ServerSocketFactory = ServerSocketFactory.getDefault(),
) : ViewModel() {

    constructor(
        graph: AppGraph,
        networkPermissionGranted: () -> Boolean,
        port: Int = DEVELOPER_API_PORT,
        serverSocketFactory: ServerSocketFactory = ServerSocketFactory.getDefault(),
    ) : this(ApiHandlers(graph), networkPermissionGranted, port, serverSocketFactory)

    /** Shown on the screen, and the only token the listener accepts. Never logged, never stored. */
    val pairingCode: String = newPairingCode()

    private val server = LoopbackApiServer(
        router = ApiRouter(handlers, pairingCode),
        networkPermissionGranted = networkPermissionGranted,
        port = port,
        serverSocketFactory = serverSocketFactory,
    )

    /** Answers given this session, refusals included — the screen's third line. */
    val requests: StateFlow<Int> = server.requests

    /**
     * The listener as the screen describes it ([developerApiNotice]): stopped, listening, could not
     * start and why, or died.
     *
     * A flow and not a thrown exception: the caller is a Compose lifecycle effect, where an
     * escaping `SocketException` would be a crash on a screen *opening*, and a port already in use
     * is an ordinary thing that happens.
     */
    val listener: StateFlow<ListenerState> = server.state

    /** The port actually bound, or 0 while stopped. Read by the proofs, not by the screen. */
    val boundPort: Int get() = server.boundPort

    fun listen() {
        if (server.start() != StartOutcome.Bound) {
            // The only line this feature logs, and all it says: no token, no path, no body.
            Log.w(TAG, "the developer API could not bind its port")
        }
    }

    /**
     * Stops the listener, which publishes [ListenerState.Stopped] even when nothing was bound. The
     * message belongs to a visit: coming back is a fresh model, a fresh code and a fresh attempt,
     * so it must not arrive already complaining about the last one.
     */
    fun stopListening() {
        server.stop()
    }
}

/** The one notice (or none) the Developer API screen draws about its listener. */
internal sealed interface DeveloperApiNotice {
    /** Stopped or listening: nothing to say. */
    data object None : DeveloperApiNotice

    /** 1.1.0's S6: a taken port, any other failure, or a listener that died (#51). */
    data object CouldNotStart : DeveloperApiNotice

    /** P1A-1, with the P1A-2 button to the app's settings (#66). */
    data object NetworkPermissionDenied : DeveloperApiNotice
}

/**
 * State to notice, and nothing else. The denial is reachable only from
 * [StartOutcome.NetworkPermissionDenied], which `classifyBindFailure` produces only when the
 * permission reads as denied.
 */
internal fun developerApiNotice(state: ListenerState): DeveloperApiNotice = when (state) {
    ListenerState.Stopped, ListenerState.Listening -> DeveloperApiNotice.None
    ListenerState.Died -> DeveloperApiNotice.CouldNotStart
    is ListenerState.CouldNotStart -> when (state.outcome) {
        StartOutcome.NetworkPermissionDenied -> DeveloperApiNotice.NetworkPermissionDenied
        // `Bound` never arrives here (the server publishes `Listening` for it); if it ever did, the
        // screen says "could not start" rather than pretend the port is healthy.
        StartOutcome.PortInUse, StartOutcome.Other, StartOutcome.Bound -> DeveloperApiNotice.CouldNotStart
    }
}
