package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.engine.discovery.SnmpVarbind

object SnmpTestAgent {
    fun str(oid: String, s: String) = SnmpVarbind(oid, 0x04, s, null, s.toByteArray(), false, false)
    fun raw(oid: String, b: ByteArray) = SnmpVarbind(oid, 0x04, null, null, b, false, false)
    fun num(oid: String, v: Long, type: Int = 0x02) = SnmpVarbind(oid, type, null, v, null, false, false)
    fun oidv(oid: String, v: String) = SnmpVarbind(oid, 0x06, v, null, null, false, false)
    fun ip(oid: String, v: String) = SnmpVarbind(oid, 0x40, v, null, null, false, false)
    fun end(oid: String) = SnmpVarbind(oid, 0x82, null, null, null, endOfMib = true, exception = true)
    private fun parts(oid: String) = oid.split('.').map { it.toLong() }
    val byOid = Comparator<String> { a, b ->
        val x = parts(a); val y = parts(b)
        for (i in 0 until minOf(x.size, y.size)) if (x[i] != y[i]) return@Comparator x[i].compareTo(y[i])
        x.size.compareTo(y.size)
    }
    /** GETNEXT semantics over [mib]: the first entry strictly after each requested OID, else endOfMibView. */
    fun exchange(mib: List<SnmpVarbind>, onRequest: () -> Unit = {}): SnmpExchange {
        val sorted = mib.sortedWith { a, b -> byOid.compare(a.oid, b.oid) }
        return object : SnmpExchange {
            override suspend fun getNext(oids: List<String>): SnmpStep { onRequest(); return SnmpStep.Ok(oids.map { q -> sorted.firstOrNull { byOid.compare(it.oid, q) > 0 } ?: end(q) }) }
            override suspend fun get(oids: List<String>): SnmpStep { onRequest(); return SnmpStep.Ok(oids.map { q -> sorted.firstOrNull { it.oid == q } ?: end(q) }) }
        }
    }
}
