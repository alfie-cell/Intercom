package dev.intercom

import dev.intercom.sender.KnownReceivers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KnownReceiversTest {
    @Test
    fun entryRoundTrips() {
        val e = KnownReceivers.Entry("Living Room Tab", "a1b2c3d4e5f60718", "192.168.1.20", 47474, 1_763_000_000_000)
        assertEquals(e, KnownReceivers.Entry.decode(e.encode()))
        val noGroup = e.copy(grpTag = null, ip = "fe80::6855:29ff:feca:82c6%wlan0")
        assertEquals(noGroup, KnownReceivers.Entry.decode(noGroup.encode()))
    }

    @Test
    fun rejectsMalformedAndHostnames() {
        assertNull(KnownReceivers.Entry.decode("garbage"))
        val e = KnownReceivers.Entry("Kitchen", null, "kitchen.local", 47474, 1)
        assertNull(KnownReceivers.Entry.decode(e.encode())) // would need a DNS lookup
    }
}
