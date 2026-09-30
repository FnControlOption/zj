package io.github.fncontroloption.zig

// Based on https://codeberg.org/ziglang/zig/src/commit/69a2697/lib/std/zig/number_literal.zig
object ZigNumberLiteralParser {
    enum class Base(val radix: Int) {
        DECIMAL(10), HEX(16), BINARY(2), OCTAL(8)
    }
    enum class FloatBase(val radix: Int) {
        DECIMAL(10), HEX(16)
    }

    sealed interface Result
    data class IntResult(val value: Int) : Result
    data class BigIntResult(val base: Base) : Result
    data class FloatResult(val base: FloatBase) : Result
    object FailureResult : Result

    fun parse(chars: CharSequence): Result {
        if (chars.isEmpty() || !chars[0].isDigit()) {
            return FailureResult
        }
        var start = 0
        var base = Base.DECIMAL
        if (chars.length >= 2 && chars[0] == '0') when (chars[1]) {
            'b' -> {
                base = Base.BINARY
                start = 2
            }
            'o' -> {
                base = Base.OCTAL
                start = 2
            }
            'x' -> {
                base = Base.HEX
                start = 2
            }
            'B', 'O', 'X' -> return FailureResult
            '.', 'e', 'E' -> {}
            else -> return FailureResult
        }
        if (chars.length == 2 && base != Base.DECIMAL) return FailureResult

        var x = 0
        var overflow = false
        var underscore = false
        var period = false
        var special: Char? = null
        var exponent = false
        var float = false
        for (i in start until chars.length) {
            val c = chars[i]
            when (c) {
                '_' -> {
                    if (i == 2 && base != Base.DECIMAL) return FailureResult
                    if (special != null) return FailureResult
                    if (underscore) return FailureResult
                    underscore = true
                    continue
                }
                'e', 'E' -> if (base == Base.DECIMAL) {
                    float = true
                    if (exponent) return FailureResult
                    if (underscore) return FailureResult
                    special = c
                    exponent = true
                    continue
                }
                'p', 'P' -> if (base == Base.HEX) {
                    if (i == 2) return FailureResult
                    float = true
                    if (exponent) return FailureResult
                    if (underscore) return FailureResult
                    special = c
                    exponent = true
                    continue
                }
                '.' -> {
                    if (exponent) return FailureResult
                    float = true
                    if (base != Base.DECIMAL && base != Base.HEX) return FailureResult
                    if (period) return FailureResult
                    period = true
                    if (underscore) return FailureResult
                    special = c
                    continue
                }
                '+', '-' -> {
                    when (special) {
                        'p', 'P' -> {}
                        'e', 'E' -> if (base != Base.DECIMAL) return FailureResult
                        else -> return FailureResult
                    }
                    special = c
                    continue
                }
                else -> {}
            }
            val digit = when (c) {
                in '0'..'9' -> c - '0'
                in 'A'..'Z' -> c - 'A' + 10
                in 'a'..'z' -> c - 'a' + 10
                else -> return FailureResult
            }
            if (digit >= base.radix) return FailureResult
            if (exponent && digit >= 10) return FailureResult
            underscore = false
            special = null

            if (float || overflow) continue
            if (x != 0) {
                try {
                    x = Math.multiplyExact(x, base.radix)
                } catch (_: ArithmeticException) {
                    overflow = true
                }
            }
            try {
                x = Math.addExact(x, digit)
            } catch (_: ArithmeticException) {
                overflow = true
            }
        }
        if (underscore) return FailureResult
        if (special != null) return FailureResult

        if (float) {
            val floatBase = when (base) {
                Base.DECIMAL -> FloatBase.DECIMAL
                Base.HEX -> FloatBase.HEX
                Base.BINARY, Base.OCTAL -> throw IllegalStateException()
            }
            return FloatResult(floatBase)
        }
        if (overflow) return BigIntResult(base)
        return IntResult(x)
    }
}
