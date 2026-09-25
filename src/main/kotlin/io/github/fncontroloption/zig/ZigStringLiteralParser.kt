package io.github.fncontroloption.zig

// Based on https://codeberg.org/ziglang/zig/src/commit/150169f/lib/std/zig/string_literal.zig
class ZigStringLiteralParser private constructor(
    private val chars: CharSequence,
    private val builder: StringBuilder,
) {
    private var index = -1

    private fun parse(): Result {
        if (chars.length < 2 || chars.first() != '"' || chars.last() != '"') {
            return Result.FAILURE
        }
        index = 1
        while (true) {
            when (val c = chars[index]) {
                '\\' -> when (parseEscapeSequence()) {
                    Result.SUCCESS -> {}
                    Result.FAILURE -> return Result.FAILURE
                }
                '\n' -> return Result.FAILURE
                '"' -> return Result.SUCCESS
                else -> {
                    builder.append(c)
                    index += 1
                }
            }
        }
    }

    private fun parseEscapeSequence(): Result {
        assert(chars.length > index)
        assert(chars[index] == '\\')
        if (chars.length == index + 1) {
            return Result.FAILURE
        }
        index += 2
        when (chars[index - 1]) {
            'n' -> {
                builder.append('\n')
                return Result.SUCCESS
            }
            'r' -> {
                builder.append('\r')
                return Result.SUCCESS
            }
            '\\' -> {
                builder.append('\\')
                return Result.SUCCESS
            }
            't' -> {
                builder.append('\t')
                return Result.SUCCESS
            }
            '\'' -> {
                builder.append('\'')
                return Result.SUCCESS
            }
            '"' -> {
                builder.append('"')
                return Result.SUCCESS
            }
            'x' -> {
                if (index + 2 >= chars.length) {
                    return Result.FAILURE
                }
                var value = 0
                repeat(2) {
                    when (val c = chars[index]) {
                        in '0'..'9' -> {
                            value *= 16
                            value += c - '0'
                            index += 1
                        }
                        in 'a'..'f' -> {
                            value *= 16
                            value += c - 'a' + 10
                            index += 1
                        }
                        in 'A'..'F' -> {
                            value *= 16
                            value += c - 'A' + 10
                            index += 1
                        }
                        else -> return Result.FAILURE
                    }
                }
                builder.append(value.toChar())
                return Result.SUCCESS
            }
            'u' -> {
                if (index >= chars.length || chars[index] != '{') {
                    return Result.FAILURE
                }
                index += 1
                if (index >= chars.length || chars[index] == '}') {
                    return Result.FAILURE
                }
                var value = 0
                while (index < chars.length) {
                    when (val c = chars[index]) {
                        in '0'..'9' -> {
                            value *= 16
                            value += c - '0'
                            index += 1
                        }
                        in 'a'..'f' -> {
                            value *= 16
                            value += c - 'a' + 10
                            index += 1
                        }
                        in 'A'..'F' -> {
                            value *= 16
                            value += c - 'A' + 10
                            index += 1
                        }
                        '}' -> {
                            index += 1
                            builder.appendCodePoint(value)
                            return Result.SUCCESS
                        }
                        else -> return Result.FAILURE
                    }
                    if (value > 0x10ffff) {
                        return Result.FAILURE
                    }
                }
                return Result.FAILURE
            }
            else -> return Result.FAILURE
        }
    }

    enum class Result {
        SUCCESS,
        FAILURE,
    }

    companion object {
        fun parse(chars: CharSequence): String? {
            val builder = StringBuilder()
            return when (ZigStringLiteralParser(chars, builder).parse()) {
                Result.SUCCESS -> builder.toString()
                Result.FAILURE -> null
            }
        }
    }
}
