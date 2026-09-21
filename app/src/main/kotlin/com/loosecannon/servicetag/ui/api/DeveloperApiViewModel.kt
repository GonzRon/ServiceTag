package com.loosecannon.servicetag.ui.api

import android.util.Log
import androidx.lifecycle.ViewModel
import com.loosecannon.servicetag.api.ApiHandlers
import com.loosecannon.servicetag.api.ApiRouter
import com.loosecannon.servicetag.api.DEVELOPER_API_PORT
import com.loosecannon.servicetag.api.LoopbackApiServer
import com.loosecannon.servicetag.api.newPairingCode
import com.loosecannon.servicetag.di.AppGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
 */
internal class DeveloperApiViewModel(
    handlers: ApiHandlers,
    port: Int = DEVELOPER_API_PORT,
) : ViewModel() {

    constructor(graph: AppGraph, port: Int = DEVELOPER_API_PORT) : this(ApiHandlers(graph), port)

    /** Shown on the screen, and the only token the listener accepts. Never logged, never stored. */
    val pairingCode: String = newPairingCode()

    private val server = LoopbackApiServer(ApiRouter(handlers, pairingCode), port)

    /** Answers given this session, refusals included — the screen's third line. */
    val requests: StateFlow<Int> = server.requests

    private val _failedToStart = MutableStateFlow(false)

    /**
     * True when [listen] could not bind the port — the screen then shows **S6**.
     *
     * A flow and not a thrown exception: the caller is a Compose lifecycle effect, where an
     * escaping `SocketException` would be a crash on a screen *opening*, and a port already in use
     * is an ordinary thing that happens.
     */
    val failedToStart: StateFlow<Boolean> = _failedToStart.asStateFlow()

    /** The port actually bound, or 0 while stopped. Read by the proofs, not by the screen. */
    val boundPort: Int get() = server.boundPort

    fun listen() {
        val bound = server.start()
        _failedToStart.value = !bound
        if (!bound) {
            // The only line this feature logs, and all it says: no token, no path, no body.
            Log.w(TAG, "the developer API could not bind its port")
        }
    }

    fun stopListening() {
        server.stop()
        // The message belongs to a visit. Coming back is a fresh model, a fresh code and a fresh
        // attempt, so it must not arrive already complaining about the last one.
        _failedToStart.value = false
    }
}
