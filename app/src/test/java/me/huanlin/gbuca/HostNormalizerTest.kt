package me.huanlin.gbuca

import me.huanlin.gbuca.data.remote.HostNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostNormalizerTest {

    @Test fun `bare hostname is kept`() =
        assertEquals("jwxt.example.edu.cn", HostNormalizer.normalize("jwxt.example.edu.cn"))

    @Test fun `whitespace trimmed`() =
        assertEquals("jwxt.example.edu.cn", HostNormalizer.normalize("  jwxt.example.edu.cn  "))

    @Test fun `scheme and path stripped`() =
        assertEquals("jwxt.example.edu.cn", HostNormalizer.normalize("https://jwxt.example.edu.cn/xsxk/zyxk"))

    @Test fun `port stripped`() =
        assertEquals("jwxt.example.edu.cn", HostNormalizer.normalize("jwxt.example.edu.cn:8443"))

    @Test fun `lowercased`() =
        assertEquals("jwxt.example.edu.cn", HostNormalizer.normalize("JWXT.Example.EDU.CN"))

    @Test fun `ipv4 accepted`() =
        assertEquals("192.168.1.10", HostNormalizer.normalize("192.168.1.10"))

    @Test fun `blank rejected`() =
        assertNull(HostNormalizer.normalize("   "))

    @Test fun `dotless host rejected`() =
        assertNull(HostNormalizer.normalize("jwxt"))

    @Test fun `garbage rejected`() =
        assertNull(HostNormalizer.normalize("ht tp://bad host"))
}
