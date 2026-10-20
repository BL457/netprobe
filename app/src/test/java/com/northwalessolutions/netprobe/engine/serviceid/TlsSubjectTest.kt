package com.northwalessolutions.netprobe.engine.serviceid

import org.junit.Assert.*
import org.junit.Test

/** spec 9.1 test 15: CN and O from representative RFC 2253 subjects, escaped commas included. */
class TlsSubjectTest {
    private val unifi = "CN=unifi.local,OU=UniFi,O=Ubiquiti Inc.,L=New York,ST=New York,C=US"
    @Test fun `cn and o from a unifi console`() {
        assertEquals("unifi.local", TlsPeek.subjectField(unifi, "CN")); assertEquals("Ubiquiti Inc.", TlsPeek.subjectField(unifi, "O"))
    }
    @Test fun `an escaped comma stays inside the value`() {
        assertEquals("Ubiquiti, Inc.", TlsPeek.subjectField("CN=x,O=Ubiquiti\\, Inc.,C=US", "O"))
        assertEquals("a,b", TlsPeek.subjectField("CN=a\\,b,O=Org", "CN"))
    }
    @Test fun `ou is not o and a missing attribute is null`() {
        assertNull(TlsPeek.subjectField("CN=x,OU=Only Unit", "O"))
        assertEquals("Proxmox Virtual Environment", TlsPeek.subjectField("CN=pve.lan,OU=PVE Cluster Node,O=Proxmox Virtual Environment", "O"))
        assertNull(TlsPeek.subjectField("", "CN"))
    }
    @Test fun `values are trimmed and cut to 120 characters`() {
        assertEquals("Synology Inc.", TlsPeek.subjectField("CN=synology, O= Synology Inc. ,C=TW", "O"))
        assertEquals(120, TlsPeek.subjectField("CN=" + "n".repeat(300), "CN")!!.length)
    }
}
