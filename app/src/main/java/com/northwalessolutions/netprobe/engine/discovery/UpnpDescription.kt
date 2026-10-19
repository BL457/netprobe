package com.northwalessolutions.netprobe.engine.discovery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import javax.xml.parsers.SAXParserFactory

data class UpnpDevice(val friendlyName: String?, val manufacturer: String?, val modelName: String?,
                      val modelNumber: String?, val deviceType: String?, val udn: String?)

/**
 * SAX, not XmlPull: org.xmlpull.v1.XmlPullParserFactory is an android.jar stub on the JVM unit-test classpath.
 * The bytes come from an arbitrary LAN device, so DOCTYPE and external entities are refused.
 */
object UpnpDescriptionParser {
    private const val MAX_TEXT_CHARS = 512
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

    fun parse(xml: String): UpnpDevice? = try {
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = false
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        val h = Handler()
        factory.newSAXParser().parse(InputSource(StringReader(xml)), h)   // any SAXException (truncated or binary input) lands in the catch
        val f = h.fields
        if (f.isEmpty()) null else UpnpDevice(f["friendlyName"], f["manufacturer"], f["modelName"], f["modelNumber"], f["deviceType"], f["UDN"])
    } catch (_: Exception) { null }
}

class UpnpFetcher(private val timeoutMs: Int = 2000, private val maxBytes: Int = 65536) {
    suspend fun fetch(location: String, allowedHost: Inet4Address): UpnpDevice? {
      return try {
        val url = URL(location)
        if (url.host != allowedHost.hostAddress) return null
        runInterruptible(Dispatchers.IO) {
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs; c.instanceFollowRedirects = false
            c.inputStream.use { ins ->
                // Bounded manual read: InputStream.readNBytes needs API 33 and minSdk is 26.
                val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
                while (out.size() < maxBytes) {
                    val r = ins.read(buf, 0, minOf(buf.size, maxBytes - out.size()))
                    if (r < 0) break
                    out.write(buf, 0, r)
                }
                UpnpDescriptionParser.parse(String(out.toByteArray(), Charsets.UTF_8))
            }
        }
    } catch (e: InterruptedException) { throw kotlinx.coroutines.CancellationException() } catch (_: Exception) { null }
    }
}
