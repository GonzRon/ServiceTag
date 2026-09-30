package com.loosecannon.servicetag.fetch

import com.loosecannon.servicetag.core.fetch.TransportFailure
import java.net.InetAddress
import java.net.UnknownHostException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Row 26 (#85 C18; R85-7): the resolver adapter, always through an injected lookup — no test here asks DNS. The
 * addresses are the documentation ranges (`203.0.113.0/24`, `2001:db8::/32`) and the loopback literal.
 */
class InetHostResolverTest {
    private val asked = mutableListOf<String>()

    private fun resolver(granted: Boolean = true, answer: (String) -> Array<InetAddress>) =
        InetHostResolver(networkPermissionGranted = { granted }, lookup = { host -> asked += host; answer(host) })

    private fun failureOf(resolver: InetHostResolver, host: String): TransportFailure =
        runCatching { runBlocking { resolver.resolve(host) } }.exceptionOrNull() as? TransportFailure
            ?: throw AssertionError("no TransportFailure")

    private val v4 = byteArrayOf(203.toByte(), 0, 113, 10)
    private val v6 = ByteArray(16).apply {
        this[0] = 0x20; this[1] = 0x01; this[2] = 0x0d; this[3] = 0xb8.toByte(); this[15] = 0x10
    }

    @Test
    fun everyAddressIsAnswered() {
        val answer = runBlocking {
            resolver { host ->
                arrayOf(InetAddress.getByAddress(host, v4), InetAddress.getByAddress(host, v6))
            }.resolve("files.example.invalid.")
        }

        assertEquals("asked as given: lowercased, the trailing dot kept", listOf("files.example.invalid."), asked)
        assertEquals(2, answer.size)
        assertArrayEquals(v4, answer[0])
        assertArrayEquals(v6, answer[1])
    }

    @Test
    fun aLiteralResolvesToItself() {
        // The injected lookup is the platform's own, asked only for a literal, which it parses without DNS.
        val answer = runBlocking {
            resolver { host ->
                check(host == "127.0.0.1")
                InetAddress.getAllByName(host)
            }.resolve("127.0.0.1")
        }

        assertEquals(1, answer.size)
        assertArrayEquals(byteArrayOf(127, 0, 0, 1), answer[0])
    }

    @Test
    fun unknownHostIsUnreachable() {
        val failure = failureOf(resolver { throw UnknownHostException("files.example.invalid") }, "files.example.invalid")

        assertEquals(TransportFailure.Kind.UNREACHABLE, failure.kind)
        assertNull("the platform's message, which names the host, goes nowhere", failure.message)
    }

    @Test
    fun emptyIsUnreachable() {
        assertEquals(TransportFailure.Kind.UNREACHABLE, failureOf(resolver { emptyArray() }, "files.example.invalid").kind)
    }

    @Test
    fun aDenialIsDeniedOnlyWithoutThePermission() {
        val denied = failureOf(resolver(granted = false) { throw SecurityException() }, "files.example.invalid")
        val granted = failureOf(resolver(granted = true) { throw SecurityException() }, "files.example.invalid")

        assertEquals(TransportFailure.Kind.DENIED, denied.kind)
        assertEquals(TransportFailure.Kind.UNREACHABLE, granted.kind)
    }
}
