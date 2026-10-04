package com.loosecannon.servicetag.seasonsync

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.loosecannon.servicetag.core.seasonsync.CurrentNetwork
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * #16 (C32; R16-21; C-1, C-3, N-1) — which network this phone is on now, read once per request by C19 step 1a.
 * Only the Wi-Fi name identifies the home network; Ethernet has none, and a VPN default network carries no Wi-Fi
 * information, so both can never match.
 */
fun interface CurrentNetworkReader {
    suspend fun read(): NetworkReading
}

/**
 * The network, plus why the check cannot be confirmed when the Wi-Fi name was hidden ([CurrentNetwork.WifiUnnamed])
 * or there is no name to read ([CurrentNetwork.Wired]). Null otherwise: another name, mobile data or no network, or a
 * blank name (never a captured one). The `toString` names the kind only, never the Wi-Fi name.
 */
data class NetworkReading(val network: CurrentNetwork, val cause: UnconfirmedCause?) {
    override fun toString(): String = "NetworkReading(${network::class.simpleName}, $cause)"
}

/** C-3: the reader's causes, recorded verbatim as `NOT_ON_LOCAL_NETWORK`'s `detail` (no new error kind). */
enum class UnconfirmedCause { LOCATION_OFF, PERMISSION_MISSING, APPROXIMATE_ONLY, NOT_FOREGROUND, WIRED }

/** What the default network is, from its transports; a VPN first, since it hides the Wi-Fi beneath it (N-1). */
enum class DefaultNetworkKind { WIFI, ETHERNET, VPN, OTHER }

/** One answer from the default network: its kind and, for Wi-Fi, the name exactly as the platform reports it. */
class DefaultNetworkAnswer(val kind: DefaultNetworkKind, val reportedName: String?) {
    override fun toString(): String = "DefaultNetworkAnswer($kind)"
}

/** The platform facts the reader asks; [AndroidNetworkPlatform] answers them on a phone, a script on the JVM. */
interface NetworkPlatform {
    val apiLevel: Int

    /** The default network's kind now, or null when there is none. */
    fun defaultNetworkKind(): DefaultNetworkKind?

    /** API 26–30: the connected Wi-Fi's name from the Wi-Fi service (quoted, or the platform's unknown marker). */
    fun connectedWifiName(): String?

    /**
     * API 31+: registers the one-shot default-network callback that asks for location information; [onAnswer] may be
     * called more than once. Answers the action that unregisters it.
     */
    fun listenToDefaultNetwork(onAnswer: (DefaultNetworkAnswer) -> Unit): () -> Unit

    fun locationOn(): Boolean

    fun preciseLocationGranted(): Boolean

    fun approximateLocationGranted(): Boolean

    fun backgroundLocationGranted(): Boolean

    fun inForeground(): Boolean
}

/**
 * C32's per-API-level read: the Wi-Fi service's connection info on 26–30; on 31+ the default network's first callback
 * answer, the callback unregistered on that answer or after [callbackWaitMillis] (no answer reads as no network).
 * A reported name in double quotes is unquoted; the unknown marker is a hidden name, never a name; a blank name is
 * unnamed. A hidden name's cause is the first missing precondition: Location, then precise location, then the
 * background grant for a read outside the foreground (API 29+). With every precondition met it is
 * [UnconfirmedCause.PERMISSION_MISSING], the platform's own account of a hidden name.
 */
class PlatformNetworkReader(
    private val platform: NetworkPlatform,
    private val callbackWaitMillis: Long = CALLBACK_WAIT_MILLIS,
) : CurrentNetworkReader {

    override suspend fun read(): NetworkReading {
        val kind = platform.defaultNetworkKind() ?: return NetworkReading(CurrentNetwork.None, null)
        if (platform.apiLevel < CALLBACK_API) {
            val name = when (kind) {
                DefaultNetworkKind.WIFI -> platform.connectedWifiName()
                DefaultNetworkKind.ETHERNET, DefaultNetworkKind.VPN, DefaultNetworkKind.OTHER -> null
            }
            return readingOf(DefaultNetworkAnswer(kind, name))
        }
        val first = CompletableDeferred<DefaultNetworkAnswer>()
        val unregister = platform.listenToDefaultNetwork { first.complete(it) }
        val answer = try {
            withTimeoutOrNull(callbackWaitMillis) { first.await() }
        } finally {
            unregister()
        }
        return answer?.let(::readingOf) ?: NetworkReading(CurrentNetwork.None, null)
    }

    private fun readingOf(answer: DefaultNetworkAnswer): NetworkReading = when (answer.kind) {
        DefaultNetworkKind.WIFI -> wifiReading(answer.reportedName)
        DefaultNetworkKind.ETHERNET -> NetworkReading(CurrentNetwork.Wired, UnconfirmedCause.WIRED)
        DefaultNetworkKind.VPN, DefaultNetworkKind.OTHER -> NetworkReading(CurrentNetwork.Other, null)
    }

    private fun wifiReading(reported: String?): NetworkReading {
        if (reported == null || reported == UNKNOWN_NAME) {
            return NetworkReading(CurrentNetwork.WifiUnnamed, hiddenCause())
        }
        val name = if (reported.length >= 2 && reported.startsWith('"') && reported.endsWith('"')) {
            reported.substring(1, reported.length - 1)
        } else {
            reported
        }
        if (name.isBlank()) return NetworkReading(CurrentNetwork.WifiUnnamed, null)
        return NetworkReading(CurrentNetwork.Wifi(name), null)
    }

    private fun hiddenCause(): UnconfirmedCause {
        if (!platform.locationOn()) return UnconfirmedCause.LOCATION_OFF
        if (!platform.preciseLocationGranted()) {
            return if (platform.approximateLocationGranted()) {
                UnconfirmedCause.APPROXIMATE_ONLY
            } else {
                UnconfirmedCause.PERMISSION_MISSING
            }
        }
        val backgroundRule = platform.apiLevel >= BACKGROUND_RULE_API && !platform.backgroundLocationGranted()
        if (backgroundRule && !platform.inForeground()) return UnconfirmedCause.NOT_FOREGROUND
        return UnconfirmedCause.PERMISSION_MISSING
    }

    companion object {
        /** C32: the one-shot callback waits at most this long for its first answer. */
        const val CALLBACK_WAIT_MILLIS = 2_000L

        /** The platform's marker for a name it will not reveal (`WifiManager.UNKNOWN_SSID`). */
        const val UNKNOWN_NAME = "<unknown ssid>" // l10n-ok: platform marker, compared and never shown

        private const val CALLBACK_API = 31
        private const val BACKGROUND_RULE_API = 29
    }
}

/** The phone's answers (C32's table). A platform-only fact: B9's device class proves it; no JVM test reaches it. */
class AndroidNetworkPlatform(context: Context) : NetworkPlatform {
    private val app = context.applicationContext
    private val connectivity: ConnectivityManager? = app.getSystemService(ConnectivityManager::class.java)

    override val apiLevel: Int get() = Build.VERSION.SDK_INT

    override fun defaultNetworkKind(): DefaultNetworkKind? {
        val manager = connectivity ?: return null
        val network = manager.activeNetwork ?: return null
        return manager.getNetworkCapabilities(network)?.let(::kindOf)
    }

    @Suppress("DEPRECATION") // C-1: on 26–30 the name rides only this call
    override fun connectedWifiName(): String? = app.getSystemService(WifiManager::class.java)?.getConnectionInfo()?.ssid

    override fun listenToDefaultNetwork(onAnswer: (DefaultNetworkAnswer) -> Unit): () -> Unit {
        val manager = connectivity ?: return {}
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return {}
        val withLocation = ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO
        val callback = object : ConnectivityManager.NetworkCallback(withLocation) {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                onAnswer(DefaultNetworkAnswer(kindOf(capabilities), (capabilities.transportInfo as? WifiInfo)?.ssid))
            }
        }
        try {
            manager.registerDefaultNetworkCallback(callback)
        } catch (_: RuntimeException) {
            return {} // refused by the platform: no answer, so the reader's wait ends as no network
        }
        return {
            try {
                manager.unregisterNetworkCallback(callback)
            } catch (_: IllegalArgumentException) {
                // Already unregistered.
            }
        }
    }

    override fun locationOn(): Boolean =
        app.getSystemService(LocationManager::class.java)?.let(LocationManagerCompat::isLocationEnabled) == true

    override fun preciseLocationGranted(): Boolean = granted(Manifest.permission.ACCESS_FINE_LOCATION)

    override fun approximateLocationGranted(): Boolean = granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    override fun backgroundLocationGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    override fun inForeground(): Boolean {
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        return state.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        fun kindOf(capabilities: NetworkCapabilities): DefaultNetworkKind {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return DefaultNetworkKind.VPN
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return DefaultNetworkKind.WIFI
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return DefaultNetworkKind.ETHERNET
            return DefaultNetworkKind.OTHER
        }
    }
}
