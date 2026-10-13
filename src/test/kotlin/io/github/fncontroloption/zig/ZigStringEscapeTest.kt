package io.github.fncontroloption.zig

import org.junit.Assert.*
import org.junit.Test

class ZigStringEscapeTest {
    @Test
    fun testEscape() {
        assertEquals("foo\\nbar", escapeZigString("foo\nbar"))
        assertEquals("\\x12foo", escapeZigString("\u0012foo"))
        assertEquals("bytes\u1234foo", escapeZigString("bytes\u1234foo"))
        assertEquals("foo", escapeZigString("foo"))
        assertEquals("f💯", escapeZigString("f💯"))
        assertEquals("\\t", escapeZigString("\t"))
        assertEquals("\\n", escapeZigString("\n"))
        assertEquals("\\r", escapeZigString("\r"))
        assertEquals("\\\\", escapeZigString("\\"))
        assertEquals("\\\"", escapeZigString("\""))
        assertEquals("\\x00", escapeZigString("\u0000"))
        assertEquals("\\x08", escapeZigString("\u0008"))
        assertEquals("\\x0b", escapeZigString("\u000b"))
        assertEquals("\\x0c", escapeZigString("\u000c"))
        assertEquals("\\x0e", escapeZigString("\u000e"))
        assertEquals("\\x1f", escapeZigString("\u001f"))
        assertEquals("\\xef\\xbb\\xbf", escapeZigString("\ufeff"))
        assertEquals("\\xc2\\x85", escapeZigString("\u0085"))
        assertEquals("\\xe2\\x80\\xa8", escapeZigString("\u2028"))
        assertEquals("\\xe2\\x80\\xa9", escapeZigString("\u2029"))
    }
}
