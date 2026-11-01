package com.lyvt.files

import com.lyvt.files.core.network.NearbyDiscovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class NearbyTest {
    @Test fun beaconRoundTripAndNoToken() {
        val b = NearbyDiscovery.beacon("abc123", "pc", 50123, "Luca|PC")
        val p = NearbyDiscovery.parse(b, "192.168.1.5")!!
        assertEquals("abc123", p.id); assertEquals("pc", p.platform); assertEquals(50123, p.port); assertEquals("Luca PC", p.name); assertEquals("192.168.1.5", p.host)
        assertFalse(b.contains("http"))
    }
    @Test fun rejectsGarbage() {
        assertNull(NearbyDiscovery.parse("hello", "1.2.3.4"))
        assertNull(NearbyDiscovery.parse("ASTRUM1|a|pc|99999|x", "1.2.3.4"))
        assertNull(NearbyDiscovery.parse("OTHER|a|pc|80|x", "1.2.3.4"))
    }
}
