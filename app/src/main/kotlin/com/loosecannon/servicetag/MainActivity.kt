package com.loosecannon.servicetag

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import com.loosecannon.servicetag.core.links.DeepLink
import com.loosecannon.servicetag.core.links.DeepLinkRoute
import com.loosecannon.servicetag.core.nfc.TagPayload
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.prefs.AppearanceMode
import com.loosecannon.servicetag.ui.nav.ServiceTagRoot
import com.loosecannon.servicetag.ui.nav.Route
import com.loosecannon.servicetag.ui.scan.TagResultWire
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * The one screen the app has (D12 §3). It translates intents into routes and nothing else: no
 * business logic lives here, and the two flows below are the only way an intent reaches Compose.
 */
class MainActivity : ComponentActivity() {

    private val graph: AppGraph get() = (application as ServiceTagApp).graph

    /**
     * `replay = 1`: `onCreate` emits before the first composition subscribes, and a shared flow
     * with no subscriber and no replay simply drops what it is handed — the cold-start deep link
     * would be lost. The flow is per-activity-instance, so a replayed value can never outlive the
     * intent that produced it.
     */
    private val deepLinks = MutableSharedFlow<Route>(replay = 1, extraBufferCapacity = 4)
    private val messages = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 4)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = when (graph.prefs.appearanceMode) {
                AppearanceMode.SYSTEM -> isSystemInDarkTheme()
                AppearanceMode.LIGHT -> false
                AppearanceMode.DARK -> true
            }
            ServiceTagTheme(darkTheme = dark) { ServiceTagRoot(graph, deepLinks, messages) }
        }
        // A restored instance already has its back stack; re-pushing the launch intent would
        // duplicate the destination the user is looking at.
        if (savedInstanceState == null) safeRouteFrom(intent)?.let { deepLinks.tryEmit(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        safeRouteFrom(intent)?.let { deepLinks.tryEmit(it) }
    }

    /**
     * The launcher activity is exported, so any app can aim any extras at it; on pre-33 devices
     * reading them unparcels whatever it is handed, and a hostile or simply wrong bundle throws
     * here rather than returning null. Treat it as "no route", exactly as the NFC trampoline does:
     * a bad intent from someone else must not take the app down on the way up.
     */
    private fun safeRouteFrom(intent: Intent): Route? = try {
        routeFrom(intent)
    } catch (e: Exception) {
        null
    }

    /**
     * Only the data URI (deep links) and the two trampoline extras are read; every other extra an
     * exported activity can be handed is ignored. Whether the id exists is the screen's question.
     */
    private fun routeFrom(intent: Intent): Route? {
        intent.getStringExtra(EXTRA_TAG_FORMAT)?.let { format ->
            return Route.TagResult(format, intent.getStringExtra(EXTRA_TAG_KEY).orEmpty())
        }
        if (intent.action != Intent.ACTION_VIEW) return null
        val uri = intent.data
        val link = DeepLinkRoute.parse(uri?.scheme, uri?.host, uri?.pathSegments.orEmpty())
        // A link this app answers but cannot make a destination of — a malformed id, or a tag
        // payload written by a newer format — says so. A link that is not ours at all says nothing.
        return routeForDeepLink(link) ?: if (link == null) null else malformed()
    }

    private fun malformed(): Route? {
        messages.tryEmit("That link doesn't point at anything here.")
        return null
    }

    companion object {
        const val EXTRA_TAG_FORMAT = "tag_format"
        const val EXTRA_TAG_KEY = "tag_key"
    }
}

/**
 * The destination a parsed [DeepLink] names, or null when it names none.
 *
 * **Pure, and file-level so a JVM test can drive it.** That is what makes invariant 57 — a scan, an
 * NFC dispatch and a `servicetag://` link never mutate — an assertable property rather than a
 * reading of `routeFrom`: there is no port in reach here, so there is nowhere for a write to hide.
 * `routeFrom` above adds one thing on top of this, and it is the "say so" for a link that is ours
 * but points at nothing.
 *
 * 1.2 adds the `schedule` host, routed to [Route.ScheduleDetail] exactly as the two shipped links
 * are routed and with **no** mutation on the way: it is what a notification's "Open" action, and
 * "Done" on a schedule whose completion needs the owner, both open.
 */
internal fun routeForDeepLink(link: DeepLink?): Route? = when (link) {
    is DeepLink.Asset -> Route.AssetDetail(link.id.value)
    is DeepLink.Schedule -> Route.ScheduleDetail(link.id.value)
    is DeepLink.Tag -> (link.payload as? TagPayload.V1)?.let { payload ->
        Route.TagResult(TagResultWire.formatOf(payload), payload.tagId.value)
    }
    is DeepLink.Malformed, null -> null
}
