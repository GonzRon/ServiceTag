package com.loosecannon.servicetag.seasonsync

import com.loosecannon.servicetag.reminders.sourceFile
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.w3c.dom.Element

/**
 * Row 55 (#16 C20, R16-Q-B): the network security config and the manifest that names it, read from source. Cleartext
 * is permitted app-wide (a runtime address cannot be named in XML); the rule is the client's (C19). Trust is the
 * system's authorities only. Also C32's permission lines as the manifest declares them.
 */
class NetworkSecurityConfigTest {
    private val config: Element = parse("res/xml/network_security_config.xml")
    private val manifest: Element = parse("AndroidManifest.xml")

    @Test
    fun oneBaseConfigPermitsCleartextWithSystemAnchorsOnly() {
        assertEquals("network-security-config", config.tagName)
        val base = config.children().single()
        assertEquals("base-config", base.tagName)
        assertEquals("true", base.getAttribute("cleartextTrafficPermitted"))
        val anchors = base.children().single()
        assertEquals("trust-anchors", anchors.tagName)
        val certificates = anchors.children().single()
        assertEquals("certificates", certificates.tagName)
        assertEquals("system", certificates.getAttribute("src"))
    }

    @Test
    fun noUserCertificatesNoDomainConfigNoDebugOverrides() {
        val sources = config.descendants("certificates").map { it.getAttribute("src") }
        assertEquals("only the system's authorities", listOf("system"), sources)
        assertEquals(emptyList<Element>(), config.descendants("domain-config"))
        assertEquals(emptyList<Element>(), config.descendants("debug-overrides"))
        assertEquals(emptyList<Element>(), config.descendants("pin-set"))
    }

    @Test
    fun theApplicationNamesIt() {
        val application = manifest.descendants("application").single()
        assertEquals("@xml/network_security_config", application.getAttribute("android:networkSecurityConfig"))
        assertNull(
            "one rule: no separate cleartext attribute beside the config",
            application.getAttributeNode("android:usesCleartextTraffic"),
        )
    }

    @Test
    fun theHomeNetworkPermissionsAreDeclaredWithoutAnUpperBound() {
        val permissions = manifest.descendants("uses-permission").associateBy { it.getAttribute("android:name") }
        val c32 = listOf(
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.ACCESS_WIFI_STATE",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_BACKGROUND_LOCATION",
        )
        for (name in c32) {
            val line = requireNotNull(permissions[name]) { "$name is declared" }
            assertNull("$name has no upper API bound (C-1)", line.getAttributeNode("android:maxSdkVersion"))
        }
        val never = listOf("android.permission.NEARBY_WIFI_DEVICES", "android.permission.ACCESS_LOCAL_NETWORK")
        assertEquals(emptyList<String>(), never.filter { it in permissions })
    }

    private companion object {
        fun parse(relative: String): Element {
            val factory = DocumentBuilderFactory.newInstance()
            return factory.newDocumentBuilder().parse(sourceFile(relative)).documentElement
        }

        fun Element.children(): List<Element> =
            (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()

        fun Element.descendants(tag: String): List<Element> {
            val nodes = getElementsByTagName(tag)
            return (0 until nodes.length).map { nodes.item(it) as Element }
        }
    }
}
