package io.github.fncontroloption.zig.psi2

import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import io.github.fncontroloption.zig.ZigLanguage
import org.jetbrains.annotations.NonNls

class ZigTokenType(@NonNls debugName: String) : IElementType(debugName, ZigLanguage) {
    companion object {
        @JvmField val AMPERSAND = ZigElementType("&")
        @JvmField val AMPERSAND_EQUAL = ZigElementType("&=")
        @JvmField val ANGLE_BRACKET_ANGLE_BRACKET_LEFT = ZigElementType("<<")
        @JvmField val ANGLE_BRACKET_ANGLE_BRACKET_LEFT_EQUAL = ZigElementType("<<=")
        @JvmField val ANGLE_BRACKET_ANGLE_BRACKET_LEFT_PIPE = ZigElementType("<<|")
        @JvmField val ANGLE_BRACKET_ANGLE_BRACKET_LEFT_PIPE_EQUAL = ZigElementType("<<|=")
        @JvmField val ANGLE_BRACKET_ANGLE_BRACKET_RIGHT = ZigElementType(">>")
        @JvmField val ANGLE_BRACKET_ANGLE_BRACKET_RIGHT_EQUAL = ZigElementType(">>=")
        @JvmField val ANGLE_BRACKET_LEFT = ZigElementType("<")
        @JvmField val ANGLE_BRACKET_LEFT_EQUAL = ZigElementType("<=")
        @JvmField val ANGLE_BRACKET_RIGHT = ZigElementType(">")
        @JvmField val ANGLE_BRACKET_RIGHT_EQUAL = ZigElementType(">=")
        @JvmField val ARROW = ZigElementType("->")
        @JvmField val ASTERISK = ZigElementType("*")
        @JvmField val ASTERISK_EQUAL = ZigElementType("*=")
        @JvmField val ASTERISK_PERCENT = ZigElementType("*%")
        @JvmField val ASTERISK_PERCENT_EQUAL = ZigElementType("*%=")
        @JvmField val ASTERISK_PIPE = ZigElementType("*|")
        @JvmField val ASTERISK_PIPE_EQUAL = ZigElementType("*|=")
        @JvmField val BANG = ZigElementType("!")
        @JvmField val BANG_EQUAL = ZigElementType("!=")
        @JvmField val BUILTIN = ZigElementType("a builtin function")
        @JvmField val CARET = ZigElementType("^")
        @JvmField val CARET_EQUAL = ZigElementType("^=")
        @JvmField val CHAR_LITERAL = ZigElementType("a character literal")
        @JvmField val COLON = ZigElementType(":")
        @JvmField val COMMA = ZigElementType(",")
        @JvmField val CONTAINER_DOC_COMMENT = ZigElementType("a container document comment")
        @JvmField val DOC_COMMENT = ZigElementType("a document comment")
        @JvmField val ELLIPSIS2 = ZigElementType("..")
        @JvmField val ELLIPSIS3 = ZigElementType("...")
        @JvmField val EQUAL = ZigElementType("=")
        @JvmField val EQUAL_ANGLE_BRACKET_RIGHT = ZigElementType("=>")
        @JvmField val EQUAL_EQUAL = ZigElementType("==")
        @JvmField val IDENTIFIER = ZigElementType("an identifier")
        @JvmField val KEYWORD_ADDRSPACE = ZigElementType("addrspace")
        @JvmField val KEYWORD_ALIGN = ZigElementType("align")
        @JvmField val KEYWORD_ALLOWZERO = ZigElementType("allowzero")
        @JvmField val KEYWORD_AND = ZigElementType("and")
        @JvmField val KEYWORD_ANYFRAME = ZigElementType("anyframe")
        @JvmField val KEYWORD_ANYTYPE = ZigElementType("anytype")
        @JvmField val KEYWORD_ASM = ZigElementType("asm")
        @JvmField val KEYWORD_BREAK = ZigElementType("break")
        @JvmField val KEYWORD_CALLCONV = ZigElementType("callconv")
        @JvmField val KEYWORD_CATCH = ZigElementType("catch")
        @JvmField val KEYWORD_COMPTIME = ZigElementType("comptime")
        @JvmField val KEYWORD_CONST = ZigElementType("const")
        @JvmField val KEYWORD_CONTINUE = ZigElementType("continue")
        @JvmField val KEYWORD_DEFER = ZigElementType("defer")
        @JvmField val KEYWORD_ELSE = ZigElementType("else")
        @JvmField val KEYWORD_ENUM = ZigElementType("enum")
        @JvmField val KEYWORD_ERRDEFER = ZigElementType("errdefer")
        @JvmField val KEYWORD_ERROR = ZigElementType("error")
        @JvmField val KEYWORD_EXPORT = ZigElementType("export")
        @JvmField val KEYWORD_EXTERN = ZigElementType("extern")
        @JvmField val KEYWORD_FN = ZigElementType("fn")
        @JvmField val KEYWORD_FOR = ZigElementType("for")
        @JvmField val KEYWORD_IF = ZigElementType("if")
        @JvmField val KEYWORD_INLINE = ZigElementType("inline")
        @JvmField val KEYWORD_LINKSECTION = ZigElementType("linksection")
        @JvmField val KEYWORD_NOALIAS = ZigElementType("noalias")
        @JvmField val KEYWORD_NOINLINE = ZigElementType("noinline")
        @JvmField val KEYWORD_NOSUSPEND = ZigElementType("nosuspend")
        @JvmField val KEYWORD_OPAQUE = ZigElementType("opaque")
        @JvmField val KEYWORD_OR = ZigElementType("or")
        @JvmField val KEYWORD_ORELSE = ZigElementType("orelse")
        @JvmField val KEYWORD_PACKED = ZigElementType("packed")
        @JvmField val KEYWORD_PUB = ZigElementType("pub")
        @JvmField val KEYWORD_RESUME = ZigElementType("resume")
        @JvmField val KEYWORD_RETURN = ZigElementType("return")
        @JvmField val KEYWORD_STRUCT = ZigElementType("struct")
        @JvmField val KEYWORD_SUSPEND = ZigElementType("suspend")
        @JvmField val KEYWORD_SWITCH = ZigElementType("switch")
        @JvmField val KEYWORD_TEST = ZigElementType("test")
        @JvmField val KEYWORD_THREADLOCAL = ZigElementType("threadlocal")
        @JvmField val KEYWORD_TRY = ZigElementType("try")
        @JvmField val KEYWORD_UNION = ZigElementType("union")
        @JvmField val KEYWORD_UNREACHABLE = ZigElementType("unreachable")
        @JvmField val KEYWORD_VAR = ZigElementType("var")
        @JvmField val KEYWORD_VOLATILE = ZigElementType("volatile")
        @JvmField val KEYWORD_WHILE = ZigElementType("while")
        @JvmField val LINE_COMMENT = ZigElementType("a line comment")
        @JvmField val L_BRACE = ZigElementType("{")
        @JvmField val L_BRACKET = ZigElementType("[")
        @JvmField val L_PAREN = ZigElementType("(")
        @JvmField val MINUS = ZigElementType("-")
        @JvmField val MINUS_EQUAL = ZigElementType("-=")
        @JvmField val MINUS_PERCENT = ZigElementType("-%")
        @JvmField val MINUS_PERCENT_EQUAL = ZigElementType("-%=")
        @JvmField val MINUS_PIPE = ZigElementType("-|")
        @JvmField val MINUS_PIPE_EQUAL = ZigElementType("-|=")
        @JvmField val MULTILINE_STRING_LITERAL_LINE = ZigElementType("a multiline string literal line")
        @JvmField val NUMBER_LITERAL = ZigElementType("a number literal")
        @JvmField val PERCENT = ZigElementType("%")
        @JvmField val PERCENT_EQUAL = ZigElementType("%=")
        @JvmField val PERIOD = ZigElementType(".")
        @JvmField val PERIOD_ASTERISK = ZigElementType(".*")
        @JvmField val PIPE = ZigElementType("|")
        @JvmField val PIPE_EQUAL = ZigElementType("|=")
        @JvmField val PIPE_PIPE = ZigElementType("||")
        @JvmField val PLUS = ZigElementType("+")
        @JvmField val PLUS_EQUAL = ZigElementType("+=")
        @JvmField val PLUS_PERCENT = ZigElementType("+%")
        @JvmField val PLUS_PERCENT_EQUAL = ZigElementType("+%=")
        @JvmField val PLUS_PIPE = ZigElementType("+|")
        @JvmField val PLUS_PIPE_EQUAL = ZigElementType("+|=")
        @JvmField val PLUS_PLUS = ZigElementType("++")
        @JvmField val QUESTION_MARK = ZigElementType("?")
        @JvmField val R_BRACE = ZigElementType("}")
        @JvmField val R_BRACKET = ZigElementType("]")
        @JvmField val R_PAREN = ZigElementType(")")
        @JvmField val SEMICOLON = ZigElementType(";")
        @JvmField val SLASH = ZigElementType("/")
        @JvmField val SLASH_EQUAL = ZigElementType("/=")
        @JvmField val STRING_LITERAL = ZigElementType("a string literal")
        @JvmField val TILDE = ZigElementType("~")
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
