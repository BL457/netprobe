package com.northwalessolutions.netprobe.netenv

private const val UNSIGNED_MASK = 0xFFFFFFFFL
private const val BITS = 32
private const val UNIT_PREFIX = 24
private const val UNIT_SHIFT = 8

/** Unsigned bounds, so 192.168.x and 172.x (negative as Int) compare correctly. */
fun Subnet.start(): Long = network.toLong() and UNSIGNED_MASK
fun Subnet.end(): Long = start() + (1L shl (BITS - prefix)) - 1
fun Subnet.overlaps(o: Subnet): Boolean = start() <= o.end() && o.start() <= end()
fun Subnet.containsSubnet(o: Subnet): Boolean = start() <= o.start() && o.end() <= end()

/** A /20 to /23 becomes its /24 units (each one cap slot); a /24 or narrower is swept as given (extended spec 4.4). */
fun Subnet.slash24Units(): List<Subnet> =
    if (prefix >= UNIT_PREFIX) listOf(this) else (0 until (1 shl (UNIT_PREFIX - prefix))).map { Subnet(network + (it shl UNIT_SHIFT), UNIT_PREFIX) }
