package io.github.fncontroloption.zig.psi;

import com.intellij.psi.tree.TokenSet;

public interface ZigTokenSets {

  // *** Syntax highlighter ***

  TokenSet KEYWORDS = TokenSet.create(
          ZigTypes.KEYWORD_ADDRSPACE,
          ZigTypes.KEYWORD_ALIGN,
          ZigTypes.KEYWORD_ALLOWZERO,
          ZigTypes.KEYWORD_AND,
          ZigTypes.KEYWORD_ANYFRAME,
          ZigTypes.KEYWORD_ANYTYPE,
          ZigTypes.KEYWORD_ASM,
          ZigTypes.KEYWORD_BREAK,
          ZigTypes.KEYWORD_CALLCONV,
          ZigTypes.KEYWORD_CATCH,
          ZigTypes.KEYWORD_COMPTIME,
          ZigTypes.KEYWORD_CONST,
          ZigTypes.KEYWORD_CONTINUE,
          ZigTypes.KEYWORD_DEFER,
          ZigTypes.KEYWORD_ELSE,
          ZigTypes.KEYWORD_ENUM,
          ZigTypes.KEYWORD_ERRDEFER,
          ZigTypes.KEYWORD_ERROR,
          ZigTypes.KEYWORD_EXPORT,
          ZigTypes.KEYWORD_EXTERN,
          ZigTypes.KEYWORD_FN,
          ZigTypes.KEYWORD_FOR,
          ZigTypes.KEYWORD_IF,
          ZigTypes.KEYWORD_INLINE,
          ZigTypes.KEYWORD_LINKSECTION,
          ZigTypes.KEYWORD_NOALIAS,
          ZigTypes.KEYWORD_NOINLINE,
          ZigTypes.KEYWORD_NOSUSPEND,
          ZigTypes.KEYWORD_OPAQUE,
          ZigTypes.KEYWORD_OR,
          ZigTypes.KEYWORD_ORELSE,
          ZigTypes.KEYWORD_PACKED,
          ZigTypes.KEYWORD_PUB,
          ZigTypes.KEYWORD_RESUME,
          ZigTypes.KEYWORD_RETURN,
          ZigTypes.KEYWORD_STRUCT,
          ZigTypes.KEYWORD_SUSPEND,
          ZigTypes.KEYWORD_SWITCH,
          ZigTypes.KEYWORD_TEST,
          ZigTypes.KEYWORD_THREADLOCAL,
          ZigTypes.KEYWORD_TRY,
          ZigTypes.KEYWORD_UNION,
          ZigTypes.KEYWORD_UNREACHABLE,
          ZigTypes.KEYWORD_VAR,
          ZigTypes.KEYWORD_VOLATILE,
          ZigTypes.KEYWORD_WHILE
  );

  TokenSet STRINGS = TokenSet.create(ZigTypes.STRING_LITERAL, ZigTypes.MULTILINE_STRING_LITERAL_LINE);

  TokenSet DOC_COMMENTS = TokenSet.create(ZigTypes.DOC_COMMENT, ZigTypes.CONTAINER_DOC_COMMENT);

  TokenSet OPERATION_SIGNS = TokenSet.create(
          ZigTypes.BANG,
          ZigTypes.PIPE,
          ZigTypes.PIPE_PIPE,
          ZigTypes.EQUAL_EQUAL,
          ZigTypes.BANG_EQUAL,
          ZigTypes.PERCENT,
          ZigTypes.CARET,
          ZigTypes.PLUS,
          ZigTypes.PLUS_PLUS,
          ZigTypes.PLUS_PERCENT,
          ZigTypes.PLUS_PIPE,
          ZigTypes.MINUS,
          ZigTypes.MINUS_PERCENT,
          ZigTypes.MINUS_PIPE,
          ZigTypes.ASTERISK,
          ZigTypes.ASTERISK_PERCENT,
          ZigTypes.ASTERISK_PIPE,
          ZigTypes.SLASH,
          ZigTypes.AMPERSAND,
          ZigTypes.ANGLE_BRACKET_LEFT,
          ZigTypes.ANGLE_BRACKET_LEFT_EQUAL,
          ZigTypes.ANGLE_BRACKET_ANGLE_BRACKET_LEFT,
          ZigTypes.ANGLE_BRACKET_ANGLE_BRACKET_LEFT_PIPE,
          ZigTypes.ANGLE_BRACKET_RIGHT,
          ZigTypes.ANGLE_BRACKET_RIGHT_EQUAL,
          ZigTypes.ANGLE_BRACKET_ANGLE_BRACKET_RIGHT
  );

  TokenSet BRACES = TokenSet.create(ZigTypes.L_BRACE, ZigTypes.R_BRACE);

  TokenSet PARENTHESES = TokenSet.create(ZigTypes.L_PAREN, ZigTypes.R_PAREN);

  TokenSet BRACKETS = TokenSet.create(ZigTypes.L_BRACKET, ZigTypes.R_BRACKET);

  // *** Parser ***

  TokenSet COMMENTS = TokenSet.orSet(DOC_COMMENTS, TokenSet.create(ZigTypes.LINE_COMMENT));

  TokenSet TEST_NAME = TokenSet.create(ZigTypes.STRING_LITERAL, ZigTypes.IDENTIFIER);

}
