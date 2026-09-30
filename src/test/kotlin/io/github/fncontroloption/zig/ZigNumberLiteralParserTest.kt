package io.github.fncontroloption.zig

import org.junit.Assert.*
import org.junit.Test

import io.github.fncontroloption.zig.ZigNumberLiteralParser.FailureResult
import io.github.fncontroloption.zig.ZigNumberLiteralParser.FloatBase
import io.github.fncontroloption.zig.ZigNumberLiteralParser.FloatResult
import io.github.fncontroloption.zig.ZigNumberLiteralParser.IntResult
import io.github.fncontroloption.zig.ZigNumberLiteralParser.parse

class ZigNumberLiteralParserTest {
    @Test
    fun testParse() {
        assertEquals(FloatResult(FloatBase.DECIMAL), parse("3E2"))
        assertEquals(IntResult(0x3E2), parse("0x3E2"))
        assertEquals(FloatResult(FloatBase.HEX), parse("0x3p2"))
        assertEquals(FailureResult, parse("3E2.5"))
        assertEquals(FailureResult, parse("3E.5"))
        assertEquals(FailureResult, parse("3E1."))
        assertEquals(FailureResult, parse("0o3.1"))
        assertEquals(FailureResult, parse("0o3e1"))
    }
}
