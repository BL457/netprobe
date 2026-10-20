package com.northwalessolutions.netprobe.engine.tools.smb

import org.junit.Assert.*
import org.junit.Test

class Md4Test {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    @Test fun `rfc 1320 test suite`() {
        assertEquals("31d6cfe0d16ae931b73c59d7e0c089c0", hex(Md4.digest("".toByteArray())))
        assertEquals("bde52cb31de33e46245e05fbdbd6fb24", hex(Md4.digest("a".toByteArray())))
        assertEquals("a448017aaf21d8525fc10ae87aa6729d", hex(Md4.digest("abc".toByteArray())))
        assertEquals("d9130a8164549fe818874806e1c7014b", hex(Md4.digest("message digest".toByteArray())))
        assertEquals("d79e1c308aa5bbcdeea8ed63df412da9", hex(Md4.digest("abcdefghijklmnopqrstuvwxyz".toByteArray())))
        assertEquals("043f8582f241db351ce627e153e7f0e4", hex(Md4.digest("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toByteArray())))
        assertEquals("e33b4ddc9c38f2199c3e7b164fcc0536", hex(Md4.digest("1234567890".repeat(8).toByteArray())))
    }
    @Test fun `ntowfv1 of Password is the ms-nlmp value`() {
        assertEquals("a4f49c406510bdcab6824ee7c30fd852", hex(Md4.digest("Password".toByteArray(Charsets.UTF_16LE))))
    }
}
