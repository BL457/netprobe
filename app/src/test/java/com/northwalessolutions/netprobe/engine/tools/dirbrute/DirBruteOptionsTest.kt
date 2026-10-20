package com.northwalessolutions.netprobe.engine.tools.dirbrute

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/** spec 5.1: coercion, the two text fields, and the JSON round trip. */
class DirBruteOptionsTest {
    @Test fun `concurrency and timeout are coerced to range`() {
        assertEquals(16, DirBruteOptions(concurrency = 99).coerced().concurrency); assertEquals(1, DirBruteOptions(concurrency = 0).coerced().concurrency)
        assertEquals(10, DirBruteOptions(timeoutS = 50).coerced().timeoutS); assertEquals(1, DirBruteOptions(timeoutS = 0).coerced().timeoutS)
    }
    @Test fun `extensions are trimmed, dot stripped, validated, deduped and capped`() {
        assertEquals(Parsed.Ok(listOf("php", "html")), DirBruteParse.extensions(" .php , html , php "))
        assertEquals(Parsed.Bad(FieldError.EXT_CHARS), DirBruteParse.extensions("ph p"))
        assertEquals(Parsed.Bad(FieldError.EXT_CHARS), DirBruteParse.extensions("toolongext"))
        assertEquals(Parsed.Bad(FieldError.EXT_TOO_MANY), DirBruteParse.extensions("a,b,c,d,e,f,g,h,i"))
        assertEquals(Parsed.Ok(emptyList<String>()), DirBruteParse.extensions("  "))
    }
    @Test fun `status codes parse, range check and need at least one`() {
        assertEquals(Parsed.Ok(listOf(200, 301, 403)), DirBruteParse.statusCodes("200, 301, 403, 200"))
        assertEquals(Parsed.Bad(FieldError.CODE_RANGE), DirBruteParse.statusCodes("99"))
        assertEquals(Parsed.Bad(FieldError.CODE_RANGE), DirBruteParse.statusCodes("2xx"))
        assertEquals(Parsed.Bad(FieldError.CODE_EMPTY), DirBruteParse.statusCodes(" "))
    }
    @Test fun `options round trip through json`() {
        val o = DirBruteOptions(extensions = listOf("php"), method = DirMethod.GET_ONLY, concurrency = 4)
        assertEquals(o, Json.decodeFromString<DirBruteOptions>(Json.encodeToString(DirBruteOptions.serializer(), o)))
    }
}
