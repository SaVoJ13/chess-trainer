// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.learning.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class JsonTest {
    @Test
    fun parsesNestedValues() {
        val v = Json.parse("""{"a":[1,2.5,"x\nA",true,null],"b":{}}""") as Map<*, *>
        assertEquals(listOf(1L, 2.5, "x\nA", true, null), v["a"])
        assertEquals(emptyMap<String, Any?>(), v["b"])
    }

    @Test
    fun rejectsTrailingDataDuplicateKeysAndTruncation() {
        assertThrows(ContentFormatException::class.java) { Json.parse("{} x") }
        assertThrows(ContentFormatException::class.java) { Json.parse("""{"a":1,"a":2}""") }
        assertThrows(ContentFormatException::class.java) { Json.parse("""{"a":[1,2""") }
        assertThrows(ContentFormatException::class.java) { Json.parse("") }
    }

    @Test
    fun quoteRoundTrips() {
        val s = "a\"b\\c\n\u0001 Привіт"
        assertEquals(s, Json.parse(Json.quote(s)))
    }

    @Test
    fun codecRoundTripsPack() {
        val pack = PuzzlePack("1.0", "p", "CT-001", mapOf("seed" to "s", "total" to "1"), listOf(ContentTestSupport.valid("a")))
        assertEquals(pack, ContentCodec.decodePack(ContentCodec.encodePack(pack)))
    }
}
