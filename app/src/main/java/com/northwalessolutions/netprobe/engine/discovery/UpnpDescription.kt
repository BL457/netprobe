package com.northwalessolutions.netprobe.engine.discovery

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Proxy
import java.net.URL
import java.net.URLConnection
import javax.xml.parsers.SAXParserFactory

data class UpnpDevice(val friendlyName: String?, val manufacturer: String?, val modelName: String?,
                      val modelNumber: String?, val deviceType: String?, val udn: String?)

/**
 * SAX, not XmlPull: org.xmlpull.v1.XmlPullParserFactory is an android.jar stub on the JVM unit-test classpath.
 * The bytes come from an arbitrary LAN device, so DOCTYPE and external entities are refused.
 */
object UpnpDescriptionParser {
    private const val MAX_TEXT_CHARS = 512
    private const val DOCTYPE = "<!DOCTYPE"
    private val WANTED = setOf("friendlyName", "manufacturer", "modelName", "modelNumber", "deviceType", "UDN")

    /** Records the text of direct children of the first <device>; everything deeper (deviceList, serviceList) is ignored. */
    private class Handler : DefaultHandler() {
        val fields = HashMap<String, String>()
        private var depth = 0
        private var deviceDepth = -1
        private var done = false
        private var current: String? = null
        private val text = StringBuilder()

        override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes?) {
            depth++
            if (done) return
            val name = qName.substringAfter(':')
            if (name == "device" && deviceDepth == -1) { deviceDepth = depth; return }
            if (deviceDepth != -1 && depth == deviceDepth + 1 && name in WANTED) { current = name; text.setLength(0) }
        }
        /** Never resolve an external entity or DTD, whatever the parser's feature support. */
        override fun resolveEntity(publicId: String?, systemId: String?): InputSource = InputSource(StringReader(""))
        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (current != null && text.length < MAX_TEXT_CHARS) text.append(ch, start, length)
        }
        override fun endElement(uri: String?, localName: String?, qName: String) {
            if (!done) {
                val c = current
                if (c != null && depth == deviceDepth + 1) { fields.putIfAbsent(c, text.toString().trim()); current = null }
                else if (deviceDepth != -1 && depth == deviceDepth) done = true
            }
            depth--
        }
    }

    fun parse(xml: String): UpnpDevice? = parse(xml, allowDoctype = false)

    /** [allowDoctype] exists only so tests can prove resolveEntity blocks external entities on its own. */
    internal fun parse(xml: String, allowDoctype: Boolean): UpnpDevice? = try {
        // Refuse before parsing: Android's Expat ignores disallow-doctype-decl, and an entity-expansion document could exhaust memory.
        if (!allowDoctype && xml.contains(DOCTYPE, ignoreCase = true)) throw IllegalArgumentException("DOCTYPE refused")
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = false
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", !allowDoctype) }
        }
        val h = Handler()
        factory.newSAXParser().parse(InputSource(StringReader(xml)), h)   // any SAXException (truncated or binary input) lands in the catch
        val f = h.fields
        if (f.isEmpty()) null else UpnpDevice(f["friendlyName"], f["manufacturer"], f["modelName"], f["modelNumber"], f["deviceType"], f["UDN"])
    } catch (_: Exception) { null }
}

class UpnpFetcher(private val timeoutMs: Int = 2000, private val maxBytes: Int = 65536) {
    /** [opener] is the listener's Wi-Fi-bound connection (3C spec 3.4, controller ruling); discovery keeps the unbound NO_PROXY default. */
    suspend fun fetch(location: String, allowedHost: Inet4Address, opener: (URL) -> URLConnection = NO_PROXY_OPENER): UpnpDevice? {
        return try {
            val url = URL(location)
            if (url.host != allowedHost.hostAddress) return null
            runInterruptible(Dispatchers.IO) {
                val c = opener(url) as HttpURLConnection
                c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs; c.instanceFollowRedirects = false
                c.inputStream.use { ins ->
                    // Bounded manual read: InputStream.readNBytes needs API 33 and minSdk is 26.
                    // Overall deadline stops a device dripping bytes just inside the per-read timeout.
                    val deadline = System.currentTimeMillis() + timeoutMs
                    val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
                    while (out.size() < maxBytes && System.currentTimeMillis() <= deadline) {
                        val r = ins.read(buf, 0, minOf(buf.size, maxBytes - out.size()))
                        if (r < 0) break
                        out.write(buf, 0, r)
                    }
                    UpnpDescriptionParser.parse(String(out.toByteArray(), Charsets.UTF_8))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        /** NO_PROXY: a LAN fetch must never be handed to a Wi-Fi proxy or PAC outside the network. */
        val NO_PROXY_OPENER: (URL) -> URLConnection = { it.openConnection(Proxy.NO_PROXY) }
    }
}
