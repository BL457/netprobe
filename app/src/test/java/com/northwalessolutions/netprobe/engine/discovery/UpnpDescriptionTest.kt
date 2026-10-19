package com.northwalessolutions.netprobe.engine.discovery

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class UpnpDescriptionTest {
    private fun fixture(n: String) = javaClass.getResource("/fixtures/$n")!!.readText()
    @Test fun `router description`() {
        val d = UpnpDescriptionParser.parse(fixture("upnp_desc_router.xml"))!!
        assertEquals("TP-Link", d.manufacturer); assertEquals("Archer AX55", d.modelName); assertEquals("Archer AX55", d.friendlyName)
        assertTrue(d.deviceType!!.contains("InternetGatewayDevice")); assertEquals("uuid:12345678-1234-1234-1234-aabbccddeeff", d.udn)
    }
    @Test fun `takes the root device not the embedded one`() {
        assertEquals("Archer AX55", UpnpDescriptionParser.parse(fixture("upnp_desc_router.xml"))!!.friendlyName)
    }
    @Test fun `tv description`() {
        val d = UpnpDescriptionParser.parse(fixture("upnp_desc_tv.xml"))!!
        assertEquals("Samsung Electronics", d.manufacturer); assertTrue(d.deviceType!!.contains("MediaRenderer"))
    }
    @Test fun `garbage yields null not an exception`() {
        assertNull(UpnpDescriptionParser.parse(fixture("upnp_desc_garbage.xml"))); assertNull(UpnpDescriptionParser.parse(""))
    }

    @Test fun `external entities are never resolved`() {
        val connected = AtomicBoolean(false)
        val ss = serve(connected) { it.write(okXml("SECRET")) }
        try {
            val xml = "<?xml version=\"1.0\"?><!DOCTYPE root [<!ENTITY x SYSTEM \"http://127.0.0.1:${ss.localPort}/x\">]>" +
                "<root><device><friendlyName>&x;</friendlyName></device></root>"
            // Doctype refusal off, so only resolveEntity stands between the parser and the network.
            val d = UpnpDescriptionParser.parse(xml, allowDoctype = true)
            Thread.sleep(300)
            assertFalse(connected.get())
            assertTrue(d == null || d.friendlyName.isNullOrEmpty())
            assertNull(UpnpDescriptionParser.parse(xml))   // production path refuses the DOCTYPE outright
        } finally { ss.close() }
    }

    private val loop = InetAddress.getByName("127.0.0.1") as Inet4Address
    private val crlf = "\r\n"
    private val other = InetAddress.getByName("127.0.0.2") as Inet4Address

    /** One-shot loopback HTTP server. [respond] writes the reply after the request line is read. */
    private fun serve(connected: AtomicBoolean, respond: (java.io.OutputStream) -> Unit): ServerSocket {
        val ss = ServerSocket(0, 1, loop)
        thread(isDaemon = true) {
            runCatching {
                ss.accept().use { s ->
                    connected.set(true)
                    s.getInputStream().bufferedReader().readLine()
                    respond(s.getOutputStream())
                }
            }
        }
        return ss
    }

    @Test fun `fetch refuses a location on another host`() = runBlocking {
        val connected = AtomicBoolean(false)
        val ss = serve(connected) { it.write(okXml("<root><device><friendlyName>X</friendlyName></device></root>")) }
        try {
            assertNull(UpnpFetcher().fetch("http://127.0.0.1:${ss.localPort}/d.xml", other))
            Thread.sleep(200)
            assertFalse(connected.get())
        } finally { ss.close() }
    }

    private fun okXml(body: String) = ("HTTP/1.1 200 OK" + crlf + "Connection: close" + crlf + crlf + body).toByteArray()

    @Test fun `fetch caps the body at maxBytes`() = runBlocking {
        fun big() = serve(AtomicBoolean(false)) { o ->
            o.write(okXml("<root><device><friendlyName>Big</friendlyName><manufacturer>"))
            runCatching {
                repeat(200) { o.write(ByteArray(1024) { 'a'.code.toByte() }) }
                o.write("</manufacturer></device></root>".toByteArray()); o.flush()
            }
        }
        val capped = big()
        try { assertNull(UpnpFetcher(maxBytes = 4096).fetch("http://127.0.0.1:${capped.localPort}/d.xml", loop)) } finally { capped.close() }
        val open = big()
        try {
            assertEquals("Big", UpnpFetcher(maxBytes = 300_000).fetch("http://127.0.0.1:${open.localPort}/d.xml", loop)?.friendlyName)
        } finally { open.close() }
    }

    @Test fun `fetch does not follow redirects`() = runBlocking {
        val targetHit = AtomicBoolean(false)
        val target = serve(targetHit) { it.write(okXml("<root><device><friendlyName>Evil</friendlyName></device></root>")) }
        val ss = serve(AtomicBoolean(false)) { o ->
            o.write(("HTTP/1.1 302 Found" + crlf + "Location: http://127.0.0.1:${target.localPort}/x.xml" + crlf + "Connection: close" + crlf + crlf).toByteArray())
            o.flush()
        }
        try {
            assertNull(UpnpFetcher().fetch("http://127.0.0.1:${ss.localPort}/d.xml", loop))
            Thread.sleep(200)
            assertFalse(targetHit.get())
        } finally { ss.close(); target.close() }
    }
}
