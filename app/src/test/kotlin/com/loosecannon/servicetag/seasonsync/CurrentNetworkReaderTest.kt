package com.loosecannon.servicetag.seasonsync

import com.loosecannon.servicetag.core.seasonsync.CurrentNetwork
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Row 54c (#16 C32; R16-21; C-1, C-3, N-1): the reader's per-API-level logic over a scripted platform — the Wi-Fi
 * service's connection info on 26–30, the one-shot default-network callback on 31+. The real platform's answers are
 * B9's device facts. Names are fictional.
 */
class CurrentNetworkReaderTest {

    @Test
    fun aQuotedNameIsUnquoted() = runBlocking {
        for (api in listOf(29, 33)) {
            assertEquals(wifi("ExampleHomeWifi"), read(ScriptedPlatform(api, name = "\"ExampleHomeWifi\"")))
            assertEquals("a name the platform gives as hex stays as given", wifi("4578616d706c65"),
                read(ScriptedPlatform(api, name = "4578616d706c65")))
            assertEquals("a name that is the marker, quoted, is a name", wifi("<unknown ssid>"),
                read(ScriptedPlatform(api, name = "\"<unknown ssid>\"")))
        }
    }

    @Test
    fun aBlankOrWhitespaceNameIsWifiUnnamed() = runBlocking {
        for (api in listOf(29, 33)) {
            for (blank in listOf("\"\"", "\"   \"", "", "\" \t\"")) {
                assertEquals("'$blank' on $api", NetworkReading(CurrentNetwork.WifiUnnamed, null),
                    read(ScriptedPlatform(api, name = blank)))
            }
        }
    }

    @Test
    fun unknownSsidIsWifiUnnamedWithItsCause() = runBlocking {
        for (api in listOf(29, 33)) {
            suspend fun hidden(configure: ScriptedPlatform.() -> Unit) =
                read(ScriptedPlatform(api, name = PlatformNetworkReader.UNKNOWN_NAME).apply(configure))

            assertEquals(unnamed(UnconfirmedCause.LOCATION_OFF), hidden { location = false })
            assertEquals(unnamed(UnconfirmedCause.APPROXIMATE_ONLY), hidden { precise = false })
            assertEquals(unnamed(UnconfirmedCause.PERMISSION_MISSING), hidden { precise = false; approximate = false })
            assertEquals(unnamed(UnconfirmedCause.NOT_FOREGROUND), hidden { foreground = false; background = false })
            assertEquals(
                "every precondition met: the platform's own account of a hidden name",
                unnamed(UnconfirmedCause.PERMISSION_MISSING),
                hidden { },
            )
            assertEquals(
                "with the background grant, a background read is not the cause",
                unnamed(UnconfirmedCause.PERMISSION_MISSING),
                hidden { foreground = false },
            )
            assertEquals(unnamed(UnconfirmedCause.APPROXIMATE_ONLY), hidden { precise = false; foreground = false })
        }
        assertEquals(
            "API 26–28 have no background rule",
            unnamed(UnconfirmedCause.PERMISSION_MISSING),
            read(ScriptedPlatform(28, name = null).apply { foreground = false; background = false }),
        )
    }

    @Test
    fun ethernetIsWired() = runBlocking {
        for (api in listOf(26, 33)) {
            val platform = ScriptedPlatform(api, kind = DefaultNetworkKind.ETHERNET)
            assertEquals(NetworkReading(CurrentNetwork.Wired, UnconfirmedCause.WIRED), read(platform))
            assertEquals("no Wi-Fi name is read for Ethernet", 0, platform.connectionInfoReads)
        }
    }

    @Test
    fun cellularIsOther() = runBlocking {
        for (api in listOf(26, 33)) {
            assertEquals(NetworkReading(CurrentNetwork.Other, null),
                read(ScriptedPlatform(api, kind = DefaultNetworkKind.OTHER)))
        }
    }

    @Test
    fun aVpnDefaultNetworkIsOther() = runBlocking {
        for (api in listOf(29, 33)) {
            val platform = ScriptedPlatform(api, kind = DefaultNetworkKind.VPN)
            assertEquals(NetworkReading(CurrentNetwork.Other, null), read(platform))
            assertEquals("the Wi-Fi beneath a VPN is not read", 0, platform.connectionInfoReads)
        }
    }

    @Test
    fun noNetworkIsNone() = runBlocking {
        for (api in listOf(26, 33)) {
            val platform = ScriptedPlatform(api, kind = null)
            assertEquals(NetworkReading(CurrentNetwork.None, null), read(platform))
            assertEquals("nothing registered without a network", 0, platform.registrations)
        }
    }

    @Test
    fun api29And30ReadGetConnectionInfo() = runBlocking {
        for (api in listOf(26, 29, 30)) {
            val platform = ScriptedPlatform(api)
            assertEquals(wifi("ExampleHomeWifi"), read(platform))
            assertEquals("$api: the connection info, once", 1 to 0, platform.connectionInfoReads to platform.registrations)
        }
        for (api in listOf(31, 37)) {
            val platform = ScriptedPlatform(api)
            assertEquals(wifi("ExampleHomeWifi"), read(platform))
            assertEquals("$api: the callback, once", 0 to 1, platform.connectionInfoReads to platform.registrations)
        }
    }

    @Test
    fun theCallbackIsUnregisteredOnItsFirstAnswerOrTimeout() = runTest {
        val answering = ScriptedPlatform(33).apply {
            answers = listOf(
                DefaultNetworkAnswer(DefaultNetworkKind.WIFI, "\"ExampleHomeWifi\""),
                DefaultNetworkAnswer(DefaultNetworkKind.WIFI, "\"NeighbourWifi\""),
            )
        }
        assertEquals("the first answer wins", wifi("ExampleHomeWifi"), read(answering))
        assertEquals(1 to 1, answering.registrations to answering.unregistrations)

        val silent = ScriptedPlatform(33).apply { answers = emptyList() }
        assertEquals(NetworkReading(CurrentNetwork.None, null), read(silent))
        assertEquals("two seconds, in virtual time", 2_000L, testScheduler.currentTime)
        assertEquals(1 to 1, silent.registrations to silent.unregistrations)
    }

    private suspend fun read(platform: ScriptedPlatform) = PlatformNetworkReader(platform).read()

    private fun wifi(name: String) = NetworkReading(CurrentNetwork.Wifi(name), null)

    private fun unnamed(cause: UnconfirmedCause) = NetworkReading(CurrentNetwork.WifiUnnamed, cause)
}

/** The platform as a script: a default network, the name as reported, and the preconditions, all granted by default. */
private class ScriptedPlatform(
    override val apiLevel: Int,
    var kind: DefaultNetworkKind? = DefaultNetworkKind.WIFI,
    var name: String? = "\"ExampleHomeWifi\"",
) : NetworkPlatform {
    var answers: List<DefaultNetworkAnswer>? = null
    var location = true
    var precise = true
    var approximate = true
    var background = true
    var foreground = true
    var connectionInfoReads = 0
    var registrations = 0
    var unregistrations = 0

    override fun defaultNetworkKind(): DefaultNetworkKind? = kind

    override fun connectedWifiName(): String? {
        connectionInfoReads++
        return name
    }

    override fun listenToDefaultNetwork(onAnswer: (DefaultNetworkAnswer) -> Unit): () -> Unit {
        registrations++
        val delivered = answers ?: listOfNotNull(kind?.let { DefaultNetworkAnswer(it, name) })
        delivered.forEach(onAnswer)
        return { unregistrations++ }
    }

    override fun locationOn() = location

    override fun preciseLocationGranted() = precise

    override fun approximateLocationGranted() = approximate

    override fun backgroundLocationGranted() = background

    override fun inForeground() = foreground
}
