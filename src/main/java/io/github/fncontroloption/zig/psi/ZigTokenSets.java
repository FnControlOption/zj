package io.github.fncontroloption.zig.psi;

import com.intellij.psi.tree.TokenSet;

public interface ZigTokenSets {

  // *** Syntax highlighter ***

  TokenSet KEYWORDS = TokenSet.create(
          ZigTokenTypes.KEYWORD_ADDRSPACE,
          ZigTokenTypes.KEYWORD_ALIGN,
          ZigTokenTypes.KEYWORD_ALLOWZERO,
          ZigTokenTypes.KEYWORD_AND,
          ZigTokenTypes.KEYWORD_ANYFRAME,
          ZigTokenTypes.KEYWORD_ANYTYPE,
          ZigTokenTypes.KEYWORD_ASM,
          ZigTokenTypes.KEYWORD_BREAK,
          ZigTokenTypes.KEYWORD_CALLCONV,
          ZigTokenTypes.KEYWORD_CATCH,
          ZigTokenTypes.KEYWORD_COMPTIME,
          ZigTokenTypes.KEYWORD_CONST,
          ZigTokenTypes.KEYWORD_CONTINUE,
          ZigTokenTypes.KEYWORD_DEFER,
          ZigTokenTypes.KEYWORD_ELSE,
          ZigTokenTypes.KEYWORD_ENUM,
          ZigTokenTypes.KEYWORD_ERRDEFER,
          ZigTokenTypes.KEYWORD_ERROR,
          ZigTokenTypes.KEYWORD_EXPORT,
          ZigTokenTypes.KEYWORD_EXTERN,
          ZigTokenTypes.KEYWORD_FN,
          ZigTokenTypes.KEYWORD_FOR,
          ZigTokenTypes.KEYWORD_IF,
          ZigTokenTypes.KEYWORD_INLINE,
          ZigTokenTypes.KEYWORD_LINKSECTION,
          ZigTokenTypes.KEYWORD_NOALIAS,
          ZigTokenTypes.KEYWORD_NOINLINE,
          ZigTokenTypes.KEYWORD_NOSUSPEND,
          ZigTokenTypes.KEYWORD_OPAQUE,
          ZigTokenTypes.KEYWORD_OR,
          ZigTokenTypes.KEYWORD_ORELSE,
          ZigTokenTypes.KEYWORD_PACKED,
          ZigTokenTypes.KEYWORD_PUB,
          ZigTokenTypes.KEYWORD_RESUME,
          ZigTokenTypes.KEYWORD_RETURN,
          ZigTokenTypes.KEYWORD_STRUCT,
          ZigTokenTypes.KEYWORD_SUSPEND,
          ZigTokenTypes.KEYWORD_SWITCH,
          ZigTokenTypes.KEYWORD_TEST,
          ZigTokenTypes.KEYWORD_THREADLOCAL,
          ZigTokenTypes.KEYWORD_TRY,
          ZigTokenTypes.KEYWORD_UNION,
          ZigTokenTypes.KEYWORD_UNREACHABLE,
          ZigTokenTypes.KEYWORD_VAR,
          ZigTokenTypes.KEYWORD_VOLATILE,
          ZigTokenTypes.KEYWORD_WHILE
  );

  TokenSet STRINGS = TokenSet.create(ZigTokenTypes.STRING_LITERAL, ZigTokenTypes.MULTILINE_STRING_LITERAL_LINE);

  TokenSet DOC_COMMENTS = TokenSet.create(ZigTokenTypes.DOC_COMMENT, ZigTokenTypes.CONTAINER_DOC_COMMENT);

  TokenSet OPERATION_SIGNS = TokenSet.create(
          ZigTokenTypes.BANG,
          ZigTokenTypes.PIPE,
          ZigTokenTypes.PIPE_PIPE,
          ZigTokenTypes.EQUAL_EQUAL,
          ZigTokenTypes.BANG_EQUAL,
          ZigTokenTypes.PERCENT,
          ZigTokenTypes.CARET,
          ZigTokenTypes.PLUS,
          ZigTokenTypes.PLUS_PLUS,
          ZigTokenTypes.PLUS_PERCENT,
          ZigTokenTypes.PLUS_PIPE,
          ZigTokenTypes.MINUS,
          ZigTokenTypes.MINUS_PERCENT,
          ZigTokenTypes.MINUS_PIPE,
          ZigTokenTypes.ASTERISK,
          ZigTokenTypes.ASTERISK_PERCENT,
          ZigTokenTypes.ASTERISK_PIPE,
          ZigTokenTypes.SLASH,
          ZigTokenTypes.AMPERSAND,
          ZigTokenTypes.ANGLE_BRACKET_LEFT,
          ZigTokenTypes.ANGLE_BRACKET_LEFT_EQUAL,
          ZigTokenTypes.ANGLE_BRACKET_ANGLE_BRACKET_LEFT,
          ZigTokenTypes.ANGLE_BRACKET_ANGLE_BRACKET_LEFT_PIPE,
          ZigTokenTypes.ANGLE_BRACKET_RIGHT,
          ZigTokenTypes.ANGLE_BRACKET_RIGHT_EQUAL,
          ZigTokenTypes.ANGLE_BRACKET_ANGLE_BRACKET_RIGHT
  );

  TokenSet BRACES = TokenSet.create(ZigTokenTypes.L_BRACE, ZigTokenTypes.R_BRACE);

  TokenSet PARENTHESES = TokenSet.create(ZigTokenTypes.L_PAREN, ZigTokenTypes.R_PAREN);

  TokenSet BRACKETS = TokenSet.create(ZigTokenTypes.L_BRACKET, ZigTokenTypes.R_BRACKET);

  // *** Parser ***

  TokenSet COMMENTS = TokenSet.orSet(DOC_COMMENTS, TokenSet.create(ZigTokenTypes.LINE_COMMENT));

  TokenSet CONTAINERS = TokenSet.create(ZigTokenTypes.KEYWORD_STRUCT, ZigTokenTypes.KEYWORD_OPAQUE, ZigTokenTypes.KEYWORD_ENUM, ZigTokenTypes.KEYWORD_UNION);

  TokenSet MUT_TOKENS = TokenSet.create(ZigTokenTypes.KEYWORD_VAR, ZigTokenTypes.KEYWORD_CONST);

  TokenSet EXPORT_INLINE = TokenSet.create(ZigTokenTypes.KEYWORD_EXPORT, ZigTokenTypes.KEYWORD_INLINE, ZigTokenTypes.KEYWORD_NOINLINE);

  TokenSet SEMICOLON_BRACE = TokenSet.create(ZigTokenTypes.SEMICOLON, ZigTokenTypes.L_BRACE);

  TokenSet TEST_NAME = TokenSet.create(ZigTokenTypes.STRING_LITERAL, ZigTokenTypes.IDENTIFIER);

  TokenSet NOALIAS_COMPTIME = TokenSet.create(ZigTokenTypes.KEYWORD_NOALIAS, ZigTokenTypes.KEYWORD_COMPTIME);

  TokenSet EXTERN_PACKED = TokenSet.create(ZigTokenTypes.KEYWORD_EXTERN, ZigTokenTypes.KEYWORD_PACKED);

  TokenSet ASTERISK_BRACKET_PAREN = TokenSet.create(ZigTokenTypes.ASTERISK, ZigTokenTypes.R_BRACKET, ZigTokenTypes.R_PAREN);

  TokenSet COMMA_BRACE = TokenSet.create(ZigTokenTypes.COMMA, ZigTokenTypes.R_BRACE);

}
