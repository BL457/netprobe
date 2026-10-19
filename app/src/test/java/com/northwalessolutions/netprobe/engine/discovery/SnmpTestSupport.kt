package com.northwalessolutions.netprobe.engine.discovery

object SnmpTestSupport {
    private val OID_DESCR = intArrayOf(1, 3, 6, 1, 2, 1, 1, 1, 0)
    private val OID_OBJID = intArrayOf(1, 3, 6, 1, 2, 1, 1, 2, 0)
    private val OID_NAME = intArrayOf(1, 3, 6, 1, 2, 1, 1, 5, 0)

    /** Produces a v2c GetResponse using the production Ber writer. */
    fun encodeResponse(requestId: Int, descr: String, name: String, oid: IntArray, errorStatus: Int = 0): ByteArray {
        val varbinds = Ber.seq(Ber.seq(Ber.oid(OID_DESCR), Ber.str(descr)), Ber.seq(Ber.oid(OID_NAME), Ber.str(name)), Ber.seq(Ber.oid(OID_OBJID), Ber.oid(oid)))
        val pdu = Ber.tlv(0xa2, Ber.int(requestId) + Ber.int(errorStatus) + Ber.int(0) + varbinds)
        return Ber.seq(Ber.int(1), Ber.str("public"), pdu)
    }
}
