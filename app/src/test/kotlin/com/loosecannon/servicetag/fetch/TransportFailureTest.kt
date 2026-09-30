package com.loosecannon.servicetag.fetch

import com.loosecannon.servicetag.core.fetch.TransportFailure
import com.loosecannon.servicetag.core.fetch.TransportFailure.Kind.DENIED
import com.loosecannon.servicetag.core.fetch.TransportFailure.Kind.INTERRUPTED
import com.loosecannon.servicetag.core.fetch.TransportFailure.Kind.TIMED_OUT
import com.loosecannon.servicetag.core.fetch.TransportFailure.Kind.UNREACHABLE
import java.io.IOException
import java.net.ConnectException
import java.net.MalformedURLException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Row 25 (#85 C17, the #66 M21 shape; review m7): one pure classification, rule by rule. */
class TransportFailureTest {

    private fun classified(
        failure: Throwable,
        phase: FailurePhase,
        granted: Boolean = true,
        errnoDenied: Boolean = false,
    ): TransportFailure = transportFailureOf(failure, phase, { granted }, { errnoDenied }) as TransportFailure

    private fun kindOf(failure: Throwable, phase: FailurePhase, granted: Boolean = true, errnoDenied: Boolean = false) =
        classified(failure, phase, granted, errnoDenied).kind

    @Test
    fun aTimeoutIsTimedOutInEitherPhase() {
        assertEquals(TIMED_OUT, kindOf(SocketTimeoutException(), FailurePhase.CONNECT))
        assertEquals(TIMED_OUT, kindOf(SocketTimeoutException(), FailurePhase.BODY))
    }

    @Test
    fun aConnectFailureIsUnreachable() {
        listOf(
            UnknownHostException(), ConnectException(), NoRouteToHostException(), SSLException("handshake"),
            MalformedURLException(), SocketException(), IOException(),
        ).forEach { assertEquals(it.javaClass.simpleName, UNREACHABLE, kindOf(it, FailurePhase.CONNECT)) }
    }

    @Test
    fun aReadFailureIsInterrupted() {
        listOf(SocketException(), SSLException("record"), IOException()).forEach {
            assertEquals(it.javaClass.simpleName, INTERRUPTED, kindOf(it, FailurePhase.BODY))
        }
    }

    @Test
    fun aDenialWithoutThePermissionIsDenied() {
        assertEquals(DENIED, kindOf(SecurityException(), FailurePhase.CONNECT, granted = false))
        assertEquals(DENIED, kindOf(ConnectException(), FailurePhase.CONNECT, granted = false, errnoDenied = true))
        assertEquals(DENIED, kindOf(SocketException(), FailurePhase.BODY, granted = false, errnoDenied = true))
    }

    @Test
    fun aDenialWithThePermissionGrantedIsUnreachable() {
        assertEquals(UNREACHABLE, kindOf(SecurityException(), FailurePhase.CONNECT, granted = true))
        assertEquals(UNREACHABLE, kindOf(ConnectException(), FailurePhase.CONNECT, granted = true, errnoDenied = true))
        assertEquals(INTERRUPTED, kindOf(SocketException(), FailurePhase.BODY, granted = true, errnoDenied = true))
    }

    @Test
    fun withoutADenialAMissingPermissionChangesNothing() {
        assertEquals(UNREACHABLE, kindOf(ConnectException(), FailurePhase.CONNECT, granted = false))
        assertEquals(INTERRUPTED, kindOf(IOException(), FailurePhase.BODY, granted = false))
    }

    @Test
    fun aNonIoThrowableIsClassifiedWithoutItsMessage() {
        val secret = "https://files.example.invalid/manual.pdf?token=AB12"
        val atConnect = classified(IllegalArgumentException(secret), FailurePhase.CONNECT)
        assertEquals(UNREACHABLE, atConnect.kind)
        assertNull(atConnect.message)
        assertNull(atConnect.cause)
        assertEquals(INTERRUPTED, kindOf(IllegalStateException(secret), FailurePhase.BODY))
        assertFalse(secret in classified(IllegalStateException(secret), FailurePhase.BODY).toString())

        val cancel = CancellationException("left the sheet")
        val error = StackOverflowError()
        assertSame(cancel, transportFailureOf(cancel, FailurePhase.CONNECT, { true }))
        assertSame(cancel, transportFailureOf(cancel, FailurePhase.BODY, { true }))
        assertSame(error, transportFailureOf(error, FailurePhase.CONNECT, { true }))
    }

    @Test
    fun anAlreadyClassifiedFailureKeepsItsKind() {
        val refused = TransportFailure(UNREACHABLE)
        assertSame(refused, transportFailureOf(refused, FailurePhase.BODY, { false }, { true }))
    }

    @Test
    fun theErrnoHelperFindsNothingInAPlainChain() {
        assertFalse(errnoDeniedIn(ConnectException().apply { initCause(IOException(SecurityException())) }))
    }
}
