package io.github.fncontroloption.zig.psi2

import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import io.github.fncontroloption.zig.ZigLanguage
import org.jetbrains.annotations.NonNls

class ZigTokenType(@NonNls debugName: String) : IElementType(debugName, ZigLanguage) {
    companion object {
        @JvmField val AMPERSAND = ZigTokenType("&")
        @JvmField val AMPERSAND_EQUAL = ZigTokenType("&=")
        @JvmField val ANGLE_BRACKET_ANGLE_BRACKET_LEFT = ZigTokenType("<<")
        @JvmField val ANGLE_BRACKET_ANGLE_BRACKET_LEFT_EQUAL = ZigTokenType("<<=")
        @JvmField val ANGLE_BRACKET_ANGLE_BRACKET_LEFT_PIPE = ZigTokenType("<<|")
        @JvmField val ANGLE_BRACKET_ANGLE_BRACKET_LEFT_PIPE_EQUAL = ZigTokenType("<<|=")
        @JvmField val ANGLE_BRACKET_ANGLE_BRACKET_RIGHT = ZigTokenType(">>")
        @JvmField val ANGLE_BRACKET_ANGLE_BRACKET_RIGHT_EQUAL = ZigTokenType(">>=")
        @JvmField val ANGLE_BRACKET_LEFT = ZigTokenType("<")
        @JvmField val ANGLE_BRACKET_LEFT_EQUAL = ZigTokenType("<=")
        @JvmField val ANGLE_BRACKET_RIGHT = ZigTokenType(">")
        @JvmField val ANGLE_BRACKET_RIGHT_EQUAL = ZigTokenType(">=")
        @JvmField val ARROW = ZigTokenType("->")
        @JvmField val ASTERISK = ZigTokenType("*")
        @JvmField val ASTERISK_EQUAL = ZigTokenType("*=")
        @JvmField val ASTERISK_PERCENT = ZigTokenType("*%")
        @JvmField val ASTERISK_PERCENT_EQUAL = ZigTokenType("*%=")
        @JvmField val ASTERISK_PIPE = ZigTokenType("*|")
        @JvmField val ASTERISK_PIPE_EQUAL = ZigTokenType("*|=")
        @JvmField val BANG = ZigTokenType("!")
        @JvmField val BANG_EQUAL = ZigTokenType("!=")
        @JvmField val BUILTIN = ZigTokenType("a builtin function")
        @JvmField val CARET = ZigTokenType("^")
        @JvmField val CARET_EQUAL = ZigTokenType("^=")
        @JvmField val CHAR_LITERAL = ZigTokenType("a character literal")
        @JvmField val COLON = ZigTokenType(":")
        @JvmField val COMMA = ZigTokenType(",")
        @JvmField val CONTAINER_DOC_COMMENT = ZigTokenType("a container document comment")
        @JvmField val DOC_COMMENT = ZigTokenType("a document comment")
        @JvmField val ELLIPSIS2 = ZigTokenType("..")
        @JvmField val ELLIPSIS3 = ZigTokenType("...")
        @JvmField val EQUAL = ZigTokenType("=")
        @JvmField val EQUAL_ANGLE_BRACKET_RIGHT = ZigTokenType("=>")
        @JvmField val EQUAL_EQUAL = ZigTokenType("==")
        @JvmField val IDENTIFIER = ZigTokenType("an identifier")
        @JvmField val KEYWORD_ADDRSPACE = ZigTokenType("addrspace")
        @JvmField val KEYWORD_ALIGN = ZigTokenType("align")
        @JvmField val KEYWORD_ALLOWZERO = ZigTokenType("allowzero")
        @JvmField val KEYWORD_AND = ZigTokenType("and")
        @JvmField val KEYWORD_ANYFRAME = ZigTokenType("anyframe")
        @JvmField val KEYWORD_ANYTYPE = ZigTokenType("anytype")
        @JvmField val KEYWORD_ASM = ZigTokenType("asm")
        @JvmField val KEYWORD_BREAK = ZigTokenType("break")
        @JvmField val KEYWORD_CALLCONV = ZigTokenType("callconv")
        @JvmField val KEYWORD_CATCH = ZigTokenType("catch")
        @JvmField val KEYWORD_COMPTIME = ZigTokenType("comptime")
        @JvmField val KEYWORD_CONST = ZigTokenType("const")
        @JvmField val KEYWORD_CONTINUE = ZigTokenType("continue")
        @JvmField val KEYWORD_DEFER = ZigTokenType("defer")
        @JvmField val KEYWORD_ELSE = ZigTokenType("else")
        @JvmField val KEYWORD_ENUM = ZigTokenType("enum")
        @JvmField val KEYWORD_ERRDEFER = ZigTokenType("errdefer")
        @JvmField val KEYWORD_ERROR = ZigTokenType("error")
        @JvmField val KEYWORD_EXPORT = ZigTokenType("export")
        @JvmField val KEYWORD_EXTERN = ZigTokenType("extern")
        @JvmField val KEYWORD_FN = ZigTokenType("fn")
        @JvmField val KEYWORD_FOR = ZigTokenType("for")
        @JvmField val KEYWORD_IF = ZigTokenType("if")
        @JvmField val KEYWORD_INLINE = ZigTokenType("inline")
        @JvmField val KEYWORD_LINKSECTION = ZigTokenType("linksection")
        @JvmField val KEYWORD_NOALIAS = ZigTokenType("noalias")
        @JvmField val KEYWORD_NOINLINE = ZigTokenType("noinline")
        @JvmField val KEYWORD_NOSUSPEND = ZigTokenType("nosuspend")
        @JvmField val KEYWORD_OPAQUE = ZigTokenType("opaque")
        @JvmField val KEYWORD_OR = ZigTokenType("or")
        @JvmField val KEYWORD_ORELSE = ZigTokenType("orelse")
        @JvmField val KEYWORD_PACKED = ZigTokenType("packed")
        @JvmField val KEYWORD_PUB = ZigTokenType("pub")
        @JvmField val KEYWORD_RESUME = ZigTokenType("resume")
        @JvmField val KEYWORD_RETURN = ZigTokenType("return")
        @JvmField val KEYWORD_STRUCT = ZigTokenType("struct")
        @JvmField val KEYWORD_SUSPEND = ZigTokenType("suspend")
        @JvmField val KEYWORD_SWITCH = ZigTokenType("switch")
        @JvmField val KEYWORD_TEST = ZigTokenType("test")
        @JvmField val KEYWORD_THREADLOCAL = ZigTokenType("threadlocal")
        @JvmField val KEYWORD_TRY = ZigTokenType("try")
        @JvmField val KEYWORD_UNION = ZigTokenType("union")
        @JvmField val KEYWORD_UNREACHABLE = ZigTokenType("unreachable")
        @JvmField val KEYWORD_VAR = ZigTokenType("var")
        @JvmField val KEYWORD_VOLATILE = ZigTokenType("volatile")
        @JvmField val KEYWORD_WHILE = ZigTokenType("while")
        @JvmField val LINE_COMMENT = ZigTokenType("a line comment")
        @JvmField val L_BRACE = ZigTokenType("{")
        @JvmField val L_BRACKET = ZigTokenType("[")
        @JvmField val L_PAREN = ZigTokenType("(")
        @JvmField val MINUS = ZigTokenType("-")
        @JvmField val MINUS_EQUAL = ZigTokenType("-=")
        @JvmField val MINUS_PERCENT = ZigTokenType("-%")
        @JvmField val MINUS_PERCENT_EQUAL = ZigTokenType("-%=")
        @JvmField val MINUS_PIPE = ZigTokenType("-|")
        @JvmField val MINUS_PIPE_EQUAL = ZigTokenType("-|=")
        @JvmField val MULTILINE_STRING_LITERAL_LINE = ZigTokenType("a multiline string literal line")
        @JvmField val NUMBER_LITERAL = ZigTokenType("a number literal")
        @JvmField val PERCENT = ZigTokenType("%")
        @JvmField val PERCENT_EQUAL = ZigTokenType("%=")
        @JvmField val PERIOD = ZigTokenType(".")
        @JvmField val PERIOD_ASTERISK = ZigTokenType(".*")
        @JvmField val PIPE = ZigTokenType("|")
        @JvmField val PIPE_EQUAL = ZigTokenType("|=")
        @JvmField val PIPE_PIPE = ZigTokenType("||")
        @JvmField val PLUS = ZigTokenType("+")
        @JvmField val PLUS_EQUAL = ZigTokenType("+=")
        @JvmField val PLUS_PERCENT = ZigTokenType("+%")
        @JvmField val PLUS_PERCENT_EQUAL = ZigTokenType("+%=")
        @JvmField val PLUS_PIPE = ZigTokenType("+|")
        @JvmField val PLUS_PIPE_EQUAL = ZigTokenType("+|=")
        @JvmField val PLUS_PLUS = ZigTokenType("++")
        @JvmField val QUESTION_MARK = ZigTokenType("?")
        @JvmField val R_BRACE = ZigTokenType("}")
        @JvmField val R_BRACKET = ZigTokenType("]")
        @JvmField val R_PAREN = ZigTokenType(")")
        @JvmField val SEMICOLON = ZigTokenType(";")
        @JvmField val SLASH = ZigTokenType("/")
        @JvmField val SLASH_EQUAL = ZigTokenType("/=")
        @JvmField val STRING_LITERAL = ZigTokenType("a string literal")
        @JvmField val TILDE = ZigTokenType("~")
    }
}

object ZigTokenSets {
    // *** Syntax highlighter ***
    @JvmField val KEYWORDS = TokenSet.create(
        ZigTokenType.KEYWORD_ADDRSPACE,
        ZigTokenType.KEYWORD_ALIGN,
        ZigTokenType.KEYWORD_ALLOWZERO,
        ZigTokenType.KEYWORD_AND,
        ZigTokenType.KEYWORD_ANYFRAME,
        ZigTokenType.KEYWORD_ANYTYPE,
        ZigTokenType.KEYWORD_ASM,
        ZigTokenType.KEYWORD_BREAK,
        ZigTokenType.KEYWORD_CALLCONV,
        ZigTokenType.KEYWORD_CATCH,
        ZigTokenType.KEYWORD_COMPTIME,
        ZigTokenType.KEYWORD_CONST,
        ZigTokenType.KEYWORD_CONTINUE,
        ZigTokenType.KEYWORD_DEFER,
        ZigTokenType.KEYWORD_ELSE,
        ZigTokenType.KEYWORD_ENUM,
        ZigTokenType.KEYWORD_ERRDEFER,
        ZigTokenType.KEYWORD_ERROR,
        ZigTokenType.KEYWORD_EXPORT,
        ZigTokenType.KEYWORD_EXTERN,
        ZigTokenType.KEYWORD_FN,
        ZigTokenType.KEYWORD_FOR,
        ZigTokenType.KEYWORD_IF,
        ZigTokenType.KEYWORD_INLINE,
        ZigTokenType.KEYWORD_LINKSECTION,
        ZigTokenType.KEYWORD_NOALIAS,
        ZigTokenType.KEYWORD_NOINLINE,
        ZigTokenType.KEYWORD_NOSUSPEND,
        ZigTokenType.KEYWORD_OPAQUE,
        ZigTokenType.KEYWORD_OR,
        ZigTokenType.KEYWORD_ORELSE,
        ZigTokenType.KEYWORD_PACKED,
        ZigTokenType.KEYWORD_PUB,
        ZigTokenType.KEYWORD_RESUME,
        ZigTokenType.KEYWORD_RETURN,
        ZigTokenType.KEYWORD_STRUCT,
        ZigTokenType.KEYWORD_SUSPEND,
        ZigTokenType.KEYWORD_SWITCH,
        ZigTokenType.KEYWORD_TEST,
        ZigTokenType.KEYWORD_THREADLOCAL,
        ZigTokenType.KEYWORD_TRY,
        ZigTokenType.KEYWORD_UNION,
        ZigTokenType.KEYWORD_UNREACHABLE,
        ZigTokenType.KEYWORD_VAR,
        ZigTokenType.KEYWORD_VOLATILE,
        ZigTokenType.KEYWORD_WHILE
    )

    @JvmField val STRINGS = TokenSet.create(ZigTokenType.STRING_LITERAL, ZigTokenType.MULTILINE_STRING_LITERAL_LINE)

    @JvmField val DOC_COMMENTS = TokenSet.create(ZigTokenType.DOC_COMMENT, ZigTokenType.CONTAINER_DOC_COMMENT)

    @JvmField val OPERATION_SIGNS = TokenSet.create(
        ZigTokenType.BANG,
        ZigTokenType.PIPE,
        ZigTokenType.PIPE_PIPE,
        ZigTokenType.EQUAL_EQUAL,
        ZigTokenType.BANG_EQUAL,
        ZigTokenType.PERCENT,
        ZigTokenType.CARET,
        ZigTokenType.PLUS,
        ZigTokenType.PLUS_PLUS,
        ZigTokenType.PLUS_PERCENT,
        ZigTokenType.PLUS_PIPE,
        ZigTokenType.MINUS,
        ZigTokenType.MINUS_PERCENT,
        ZigTokenType.MINUS_PIPE,
        ZigTokenType.ASTERISK,
        ZigTokenType.ASTERISK_PERCENT,
        ZigTokenType.ASTERISK_PIPE,
        ZigTokenType.SLASH,
        ZigTokenType.AMPERSAND,
        ZigTokenType.ANGLE_BRACKET_LEFT,
        ZigTokenType.ANGLE_BRACKET_LEFT_EQUAL,
        ZigTokenType.ANGLE_BRACKET_ANGLE_BRACKET_LEFT,
        ZigTokenType.ANGLE_BRACKET_ANGLE_BRACKET_LEFT_PIPE,
        ZigTokenType.ANGLE_BRACKET_RIGHT,
        ZigTokenType.ANGLE_BRACKET_RIGHT_EQUAL,
        ZigTokenType.ANGLE_BRACKET_ANGLE_BRACKET_RIGHT
    )

    @JvmField val BRACES = TokenSet.create(ZigTokenType.L_BRACE, ZigTokenType.R_BRACE)

    @JvmField val PARENTHESES = TokenSet.create(ZigTokenType.L_PAREN, ZigTokenType.R_PAREN)

    @JvmField val BRACKETS = TokenSet.create(ZigTokenType.L_BRACKET, ZigTokenType.R_BRACKET)

    // *** Parser ***

    @JvmField val COMMENTS = TokenSet.orSet(DOC_COMMENTS, TokenSet.create(ZigTokenType.LINE_COMMENT))

    @JvmField val CONTAINERS = TokenSet.create(
        ZigTokenType.KEYWORD_STRUCT,
        ZigTokenType.KEYWORD_OPAQUE,
        ZigTokenType.KEYWORD_ENUM,
        ZigTokenType.KEYWORD_UNION
    )

    @JvmField val MUT_TOKENS = TokenSet.create(ZigTokenType.KEYWORD_VAR, ZigTokenType.KEYWORD_CONST)

    @JvmField val EXPORT_INLINE = TokenSet.create(
        ZigTokenType.KEYWORD_EXPORT,
        ZigTokenType.KEYWORD_INLINE,
        ZigTokenType.KEYWORD_NOINLINE
    )

    @JvmField val SEMICOLON_BRACE = TokenSet.create(ZigTokenType.SEMICOLON, ZigTokenType.L_BRACE)

    @JvmField val TEST_NAME = TokenSet.create(ZigTokenType.STRING_LITERAL, ZigTokenType.IDENTIFIER)

    @JvmField val NOALIAS_COMPTIME = TokenSet.create(ZigTokenType.KEYWORD_NOALIAS, ZigTokenType.KEYWORD_COMPTIME)

    @JvmField val EXTERN_PACKED = TokenSet.create(ZigTokenType.KEYWORD_EXTERN, ZigTokenType.KEYWORD_PACKED)

    @JvmField val ASTERISK_BRACKET_PAREN =
        TokenSet.create(ZigTokenType.ASTERISK, ZigTokenType.R_BRACKET, ZigTokenType.R_PAREN)

    @JvmField val COMMA_BRACE = TokenSet.create(ZigTokenType.COMMA, ZigTokenType.R_BRACE)

    @JvmField val POINTER_MODIFIERS = TokenSet.create(
        ZigTokenType.KEYWORD_ALIGN,
        ZigTokenType.KEYWORD_CONST,
        ZigTokenType.KEYWORD_VOLATILE,
        ZigTokenType.KEYWORD_ALLOWZERO,
        ZigTokenType.KEYWORD_ADDRSPACE
    )
}
