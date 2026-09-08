package me.huanlin.gbuca

import me.huanlin.gbuca.data.remote.HostProbe
import me.huanlin.gbuca.data.remote.ProbeFailure
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class HostProbeTest {

    @Test
    fun `unknown host maps to host not found`() {
        assertEquals(ProbeFailure.HostNotFound, HostProbe.failureOf(UnknownHostException("nope")))
    }

    @Test
    fun `timeout maps to timeout`() {
        assertEquals(ProbeFailure.Timeout, HostProbe.failureOf(SocketTimeoutException("slow")))
    }

    @Test
    fun `tls failure maps to tls`() {
        assertEquals(ProbeFailure.Tls, HostProbe.failureOf(SSLHandshakeException("bad cert")))
    }

    @Test
    fun `connection refused maps to unreachable`() {
        assertEquals(ProbeFailure.Unreachable, HostProbe.failureOf(ConnectException("refused")))
    }

    @Test
    fun `no route maps to unreachable`() {
        assertEquals(ProbeFailure.Unreachable, HostProbe.failureOf(NoRouteToHostException("noroute")))
    }

    @Test
    fun `generic io failure maps to unreachable`() {
        assertEquals(ProbeFailure.Unreachable, HostProbe.failureOf(IOException("boom")))
    }
}
