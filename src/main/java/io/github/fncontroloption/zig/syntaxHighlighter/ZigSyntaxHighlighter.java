package io.github.fncontroloption.zig.syntaxHighlighter;

import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import io.github.fncontroloption.zig.lexer.ZigLexerAdapter;
import io.github.fncontroloption.zig.psi.ZigTokenSets;
import io.github.fncontroloption.zig.psi.ZigTypes;
import org.jetbrains.annotations.NotNull;

public class ZigSyntaxHighlighter extends SyntaxHighlighterBase {

  private static final TextAttributesKey[] IDENTIFIER_KEYS = new TextAttributesKey[]{ZigHighlightingColors.IDENTIFIER};
  private static final TextAttributesKey[] NUMBER_KEYS = new TextAttributesKey[]{ZigHighlightingColors.NUMBER};
  private static final TextAttributesKey[] KEYWORD_KEYS = new TextAttributesKey[]{ZigHighlightingColors.KEYWORD};
  private static final TextAttributesKey[] STRING_KEYS = new TextAttributesKey[]{ZigHighlightingColors.STRING};
  private static final TextAttributesKey[] LINE_COMMENT_KEYS = new TextAttributesKey[]{ZigHighlightingColors.LINE_COMMENT};
  private static final TextAttributesKey[] DOC_COMMENT_KEYS = new TextAttributesKey[]{ZigHighlightingColors.DOC_COMMENT};
  private static final TextAttributesKey[] OPERATION_SIGN_KEYS = new TextAttributesKey[]{ZigHighlightingColors.OPERATION_SIGN};
  private static final TextAttributesKey[] BRACES_KEYS = new TextAttributesKey[]{ZigHighlightingColors.BRACES};
  private static final TextAttributesKey[] DOT_KEYS = new TextAttributesKey[]{ZigHighlightingColors.DOT};
  private static final TextAttributesKey[] SEMICOLON_KEYS = new TextAttributesKey[]{ZigHighlightingColors.SEMICOLON};
  private static final TextAttributesKey[] COMMA_KEYS = new TextAttributesKey[]{ZigHighlightingColors.COMMA};
  private static final TextAttributesKey[] PARENTHESES_KEYS = new TextAttributesKey[]{ZigHighlightingColors.PARENTHESES};
  private static final TextAttributesKey[] BRACKETS_KEYS = new TextAttributesKey[]{ZigHighlightingColors.BRACKETS};
  private static final TextAttributesKey[] BUILTIN_KEYS = new TextAttributesKey[]{ZigHighlightingColors.BUILTIN};
  private static final TextAttributesKey[] BAD_CHARACTER_KEYS = new TextAttributesKey[]{ZigHighlightingColors.BAD_CHARACTER};
  private static final TextAttributesKey[] EMPTY_KEYS = new TextAttributesKey[0];

  @NotNull
  @Override
  public Lexer getHighlightingLexer() {
    return new ZigLexerAdapter();
  }

  @Override
  public TextAttributesKey @NotNull [] getTokenHighlights(IElementType tokenType) {
    if (tokenType.equals(ZigTypes.IDENTIFIER)) {
      return IDENTIFIER_KEYS;
    }
    if (tokenType.equals(ZigTypes.NUMBER_LITERAL)) {
      return NUMBER_KEYS;
    }
    if (ZigTokenSets.KEYWORDS.contains(tokenType)) {
      return KEYWORD_KEYS;
    }
    if (ZigTokenSets.STRINGS.contains(tokenType)) {
      return STRING_KEYS;
    }
    if (ZigTokenSets.DOC_COMMENTS.contains(tokenType)) {
      return DOC_COMMENT_KEYS;
    }
    if (ZigTokenSets.BRACES.contains(tokenType)) {
      return BRACES_KEYS;
    }
    if (ZigTokenSets.OPERATION_SIGNS.contains(tokenType)) {
      return OPERATION_SIGN_KEYS;
    }
    if (tokenType.equals(ZigTypes.PERIOD)) {
      return DOT_KEYS;
    }
    if (tokenType.equals(ZigTypes.SEMICOLON)) {
      return SEMICOLON_KEYS;
    }
    if (tokenType.equals(ZigTypes.COMMA)) {
      return COMMA_KEYS;
    }
    if (ZigTokenSets.PARENTHESES.contains(tokenType)) {
      return PARENTHESES_KEYS;
    }
    if (ZigTokenSets.BRACKETS.contains(tokenType)) {
      return BRACKETS_KEYS;
    }
    if (tokenType.equals(ZigTypes.LINE_COMMENT)) {
      return LINE_COMMENT_KEYS;
    }
    if (tokenType.equals(ZigTypes.BUILTIN)) {
      return BUILTIN_KEYS;
    }
    if (tokenType.equals(TokenType.BAD_CHARACTER)) {
      return BAD_CHARACTER_KEYS;
    }
    return EMPTY_KEYS;
  }

}
