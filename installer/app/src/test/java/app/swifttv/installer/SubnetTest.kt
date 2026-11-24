package app.swifttv.installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubnetTest {

    @Test
    fun `slash 24 produces usable host range`() {
        val range = Subnet.localRange("192.168.1.23", 24)!!
        assertNotNull(range)
        assertEquals("192.168.1.0", range!!.networkAddress)
        assertEquals(24, range.prefixLength)
        assertEquals(254, range.hosts.size)
        assertEquals("192.168.1.1", range.hosts.first())
        assertEquals("192.168.1.254", range.hosts.last())
        assertTrue(range.hosts.contains("192.168.1.23"))
    }

    @Test
    fun `wide prefix is narrowed to a slash 24 around local address`() {
        val range = Subnet.localRange("10.10.8.5", 16)!!
        assertNotNull(range)
        assertEquals(24, range.prefixLength)
        assertEquals("10.10.8.0", range!!.networkAddress)
        assertEquals(254, range.hosts.size)
        assertTrue(range.narrowed)
    }

    @Test
    fun `slash 8 is narrowed without materialising millions of hosts`() {
        val range = Subnet.localRange("10.20.30.40", 8)!!
        assertEquals(24, range.prefixLength)
        assertEquals("10.20.30.0", range.networkAddress)
        assertEquals(254, range.hosts.size)
        assertTrue(range.narrowed)
    }

    @Test
    fun `invalid address returns null`() {
        assertNull(Subnet.localRange("not-an-ip", 24))
        assertNull(Subnet.localRange("192.168.1.300", 24))
        assertNull(Subnet.localRange("192.168.1", 24))
    }

    @Test
    fun `ip conversion round trips`() {
        val value = Subnet.ipv4ToLong("172.16.31.200")
        assertEquals("172.16.31.200", Subnet.longToIp(value!!))
    }
}

