package com.northwalessolutions.netprobe.engine.discovery

import com.northwalessolutions.netprobe.engine.common.BindFailedException
import com.northwalessolutions.netprobe.engine.common.bindOrFail
import com.northwalessolutions.netprobe.engine.common.currentBinder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.nio.ByteBuffer

data class NetBiosInfo(val computerName: String?, val workgroup: String?, val mac: String?)

object NetBios {
    private const val NBNS_PORT = 137
    private const val REQUEST_SIZE = 50
    private const val QTYPE_NBSTAT = 0x21
    private const val HEADER_SIZE = 12
    private const val NAME_ENTRY_SIZE = 18
    private const val MAC_SIZE = 6
    private const val MAX_NAMES = 64
    private const val RECV_BUFFER_SIZE = 1024
    private const val GROUP_FLAG = 0x8000
    private const val RESPONSE_FLAG = 0x8000

    fun buildNodeStatusRequest(txId: Int = 0x1234): ByteArray {
        val b = ByteBuffer.allocate(REQUEST_SIZE)
        b.putShort(txId.toShort()); b.putShort(0); b.putShort(1); b.putShort(0); b.putShort(0); b.putShort(0)
        b.put(0x20)
        // "*" followed by fifteen 0x00 bytes, first-level encoded: each nibble + 'A'
        val raw = ByteArray(16).also { it[0] = '*'.code.toByte() }
        for (x in raw) { val v = x.toInt() and 0xff; b.put(('A'.code + (v shr 4)).toByte()); b.put(('A'.code + (v and 0x0f)).toByte()) }
        b.put(0); b.putShort(QTYPE_NBSTAT.toShort()); b.putShort(1)
        return b.array()
    }

    fun parseNodeStatus(resp: ByteArray): NetBiosInfo? {
        return try {
            if (resp.size < HEADER_SIZE) return null
            val b = ByteBuffer.wrap(resp)
            b.position(2); val flags = b.short.toInt() and 0xffff
            if (flags and RESPONSE_FLAG == 0) return null                 // not a response
            b.position(HEADER_SIZE)
            // RR name: length-prefixed labels until 0
            while (true) { val l = b.get().toInt() and 0xff; if (l == 0) break; b.position(b.position() + l) }
            val type = b.short.toInt() and 0xffff; if (type != QTYPE_NBSTAT) return null
            b.short; b.int; b.short                                       // class, ttl, rdlength
            val n = b.get().toInt() and 0xff
            if (n == 0 || n > MAX_NAMES || b.remaining() < n * NAME_ENTRY_SIZE + MAC_SIZE) return null
            var computer: String? = null; var group: String? = null
            repeat(n) {
                val nameBytes = ByteArray(15); b.get(nameBytes); val suffix = b.get().toInt() and 0xff; val f = b.short.toInt() and 0xffff
                val name = String(nameBytes, Charsets.ISO_8859_1).trimEnd()
                if (name.startsWith("\u0001\u0002") || suffix != 0x00) return@repeat
                if (f and GROUP_FLAG != 0) { if (group == null) group = name } else if (computer == null) computer = name
            }
            val mac = ByteArray(MAC_SIZE); b.get(mac)
            val macStr = mac.joinToString(":") { "%02x".format(it) }.takeUnless { it == "00:00:00:00:00:00" }
            NetBiosInfo(computer, group, macStr)
        } catch (_: Exception) { null }
    }

    suspend fun query(ip: Inet4Address, timeoutMs: Int = 1000): NetBiosInfo? {
        val binder = currentBinder()   // set only for extra-range hosts in the extended phase
        return try {
            runInterruptible(Dispatchers.IO) {
                DatagramSocket().use { s ->
                    binder?.bindOrFail(s)
                    s.soTimeout = timeoutMs
                    val req = buildNodeStatusRequest()
                    s.send(DatagramPacket(req, req.size, ip, NBNS_PORT))
                    val buf = ByteArray(RECV_BUFFER_SIZE); val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    parseNodeStatus(buf.copyOf(p.length))
                }
            }
        } catch (e: CancellationException) { throw e } catch (e: BindFailedException) { throw e } catch (_: Exception) { null }
    }
}
