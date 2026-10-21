package com.northwalessolutions.netprobe.data

import com.northwalessolutions.netprobe.ui.home.TestEnvs
import org.junit.Assert.assertEquals
import org.junit.Test

/** A network row and the env it was made from share one identity key (3C spec 3.4 rule 1). */
class NetworkKeyTest {
    private val row = NetworkEntity(signature = "s", displayName = "Home", gatewayIp = "192.168.1.1", prefixLength = 24, ssid = null, lastSeen = 0)
    @Test fun `a network row's key equals its env's identity key`() {
        assertEquals(NetworkSignature.identityKey(TestEnvs.home), NetworkSignature.identityKey(row))
    }
    @Test fun `a row with no gateway keys on the prefix alone, as an env with no gateway does`() {
        assertEquals(NetworkSignature.identityKey(TestEnvs.home.copy(gateway = null)), NetworkSignature.identityKey(row.copy(gatewayIp = null)))
    }
}
