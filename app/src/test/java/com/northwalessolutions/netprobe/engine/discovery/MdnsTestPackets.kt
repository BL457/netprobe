package com.northwalessolutions.netprobe.engine.discovery

import java.io.ByteArrayOutputStream
import java.net.InetAddress

/** Authored mDNS packets for the listener tests (3C spec 6: nothing captured, nothing downloaded). */
object MdnsTestPackets {
    const val A = 1; const val PTR = 12; const val TXT = 16; const val AAAA = 28; const val SRV = 33
    const val HEADER = 12
    /** Type, class, TTL and RDLENGTH after an owner name. */
    const val RR_FIXED = 10
    private const val CLASS_IN_FLUSH = 0x8001
    private const val CLASS_IN = 1
    private const val TTL = 120
    private const val FLAGS_RESPONSE = 0x8400
    private const val POINTER_HIGH = 0xC0

    fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
    private fun u32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    private fun labels(out: ByteArrayOutputStream, dotted: String) {
        dotted.split('.').filter { it.isNotEmpty() }.forEach { l -> val b = l.toByteArray(); out.write(b.size); out.write(b) }
    }
    fun name(dotted: String): ByteArray = ByteArrayOutputStream().apply { labels(this, dotted); write(0) }.toByteArray()
    fun pointer(at: Int) = byteArrayOf((POINTER_HIGH or (at shr 8)).toByte(), (at and 0xFF).toByte())
    /** [dotted] labels, then a compression pointer to [at] instead of the terminating zero. */
    fun nameThenPointer(dotted: String, at: Int): ByteArray = ByteArrayOutputStream().apply { labels(this, dotted); write(pointer(at)) }.toByteArray()
    fun srv(port: Int, target: ByteArray) = u16(0) + u16(0) + u16(port) + target
    fun txt(vararg pairs: String): ByteArray = ByteArrayOutputStream().apply { pairs.forEach { val b = it.toByteArray(); write(b.size); write(b) } }.toByteArray()
    fun a(ip: String): ByteArray = InetAddress.getByName(ip).address

    class Builder(private val response: Boolean = true) {
        private val body = ByteArrayOutputStream()
        private var questions = 0
        private var answers = 0
        /** Absolute offset the next written byte will have, for compression pointers. */
        val offset: Int get() = HEADER + body.size()
        fun question(dotted: String, type: Int) = apply { body.write(name(dotted)); body.write(u16(type)); body.write(u16(CLASS_IN)); questions++ }
        fun record(owner: ByteArray, type: Int, rdata: ByteArray) = apply {
            body.write(owner); body.write(u16(type)); body.write(u16(CLASS_IN_FLUSH)); body.write(u32(TTL)); body.write(u16(rdata.size)); body.write(rdata); answers++
        }
        fun record(owner: String, type: Int, rdata: ByteArray) = record(name(owner), type, rdata)
        fun build(): ByteArray = u16(0) + u16(if (response) FLAGS_RESPONSE else 0) + u16(questions) + u16(answers) + u16(0) + u16(0) + body.toByteArray()
    }

    /** PTR, SRV, TXT and A (and AAAA) for one instance: the shape a responder announces. */
    fun announce(b: Builder, instance: String, type: String, host: String, ip: String, port: Int, txtPairs: Array<String> = emptyArray(), v6: String? = null): Builder {
        val full = "$instance.$type.local"
        b.record("$type.local", PTR, name(full))
        b.record(full, SRV, srv(port, name("$host.local")))
        b.record(full, TXT, txt(*txtPairs))
        b.record("$host.local", A, a(ip))
        v6?.let { b.record("$host.local", AAAA, InetAddress.getByName(it).address) }
        return b
    }
}
