package com.northwalessolutions.netprobe.data

import com.northwalessolutions.netprobe.engine.identify.DeviceType
import org.junit.Assert.*
import org.junit.Test

/** spec 9.1 tests 22 and 24. */
class OverridesTest {
    private val detected = Detected(name = "CHNAS", type = DeviceType.NAS, vendor = "Synology", model = null)

    @Test fun `an override wins per field and null falls back to the detected value`() {
        val h = TestHosts.host(type = DeviceType.SERVER, vendor = "QNAP", model = "TS-453")
        assertEquals(DeviceType.SERVER, Effective.type(h)); assertEquals("QNAP", Effective.vendor(h)); assertEquals("TS-453", Effective.model(h))
        val o = h.copy(userType = DeviceType.NAS, userModel = "TS-453D")
        assertEquals(DeviceType.NAS, Effective.type(o)); assertEquals("QNAP", Effective.vendor(o)); assertEquals("TS-453D", Effective.model(o))
    }
    @Test fun `values are trimmed and blanks store nothing`() {
        assertEquals(DeviceEdits(name = "DiskStation", model = "DS220+"), DeviceEdits.of(EditDraft("  DiskStation ", DeviceType.NAS, " ", " DS220+ "), detected))
    }
    @Test fun `a value equal to the detected value stores nothing`() {
        assertEquals(DeviceEdits(), DeviceEdits.of(EditDraft("CHNAS", DeviceType.NAS, "Synology", ""), detected))
    }
    @Test fun `a type equal to the detected type stores nothing and another type is stored`() {
        assertNull(DeviceEdits.of(EditDraft("CHNAS", DeviceType.NAS, "", ""), detected).type)
        assertEquals(DeviceType.SERVER, DeviceEdits.of(EditDraft("CHNAS", DeviceType.SERVER, "", ""), detected).type)
        assertNull(DeviceEdits.of(EditDraft("CHNAS", null, "", ""), detected).type)
    }
    @Test fun `text is cut to 80 characters`() {
        assertEquals(80, DeviceEdits.of(EditDraft("n".repeat(200), null, "", ""), detected).name!!.length)
        assertEquals(80, DeviceEdits.MAX_LEN)
    }
    @Test fun `the own phone's dialog saved unchanged stores no name`() {
        val phone = TestHosts.host(ip = "10.0.3.242", mdns = "Pixel-7-Pro")
        val draft = EditDraft.initial(phone)
        assertEquals("Pixel-7-Pro", draft.name)                             // the detected name, never "This phone"
        assertEquals(DeviceEdits(), DeviceEdits.of(draft, Detected.of(phone)))
    }
    @Test fun `the draft starts from the effective values and detected from the scan`() {
        val h = TestHosts.host(type = DeviceType.NAS, vendor = "Synology", mdns = "CHNAS").copy(customName = "DiskStation", userModel = "DS220+")
        assertEquals(EditDraft("DiskStation", DeviceType.NAS, "Synology", "DS220+"), EditDraft.initial(h))
        assertEquals(Detected("CHNAS", DeviceType.NAS, "Synology", null), Detected.of(h))
        assertNull("UNKNOWN shows as Not set", EditDraft.initial(TestHosts.host()).type)
    }
}
