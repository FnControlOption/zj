package io.github.fncontroloption.zig

import org.junit.Assert.*
import org.junit.Test

import io.github.fncontroloption.zig.ZigStringLiteralParser.Companion.parse

class ZigStringLiteralParserTest {
    @Test
    fun testParse() {
        assertNull(parse("\"\\x6\""))
        assertEquals("foo\nbar", parse("\"foo\\nbar\""))
        assertEquals("\u0012foo", parse("\"\\x12foo\""))
        assertEquals("bytes\u1234foo", parse("\"bytes\\u{1234}foo\""))
        assertEquals("foo", parse("\"foo\""))
        assertEquals("foo", parse("\"f\u006f\u006f\""))
        assertEquals("f💯", parse("\"f💯\""))
    }
}
