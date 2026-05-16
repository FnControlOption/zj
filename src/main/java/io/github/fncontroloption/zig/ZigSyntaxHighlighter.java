package io.github.fncontroloption.zig;

import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.HighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import io.github.fncontroloption.zig.psi.ZigTokenSets;
import io.github.fncontroloption.zig.psi.ZigTypes;
import org.jetbrains.annotations.NotNull;

import static com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey;

public class ZigSyntaxHighlighter extends SyntaxHighlighterBase {

  public static final TextAttributesKey IDENTIFIER =
          createTextAttributesKey("ZIG_IDENTIFIER", DefaultLanguageHighlighterColors.IDENTIFIER);
  private static final TextAttributesKey[] IDENTIFIER_KEYS = new TextAttributesKey[]{IDENTIFIER};

  public static final TextAttributesKey NUMBER =
          createTextAttributesKey("ZIG_NUMBER", DefaultLanguageHighlighterColors.NUMBER);
  private static final TextAttributesKey[] NUMBER_KEYS = new TextAttributesKey[]{NUMBER};

  public static final TextAttributesKey KEYWORD =
          createTextAttributesKey("ZIG_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD);
  private static final TextAttributesKey[] KEYWORD_KEYS = new TextAttributesKey[]{KEYWORD};

  public static final TextAttributesKey STRING =
          createTextAttributesKey("ZIG_STRING", DefaultLanguageHighlighterColors.STRING);
  private static final TextAttributesKey[] STRING_KEYS = new TextAttributesKey[]{STRING};

  public static final TextAttributesKey LINE_COMMENT =
          createTextAttributesKey("ZIG_LINE_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT);
  private static final TextAttributesKey[] LINE_COMMENT_KEYS = new TextAttributesKey[]{LINE_COMMENT};

  public static final TextAttributesKey DOC_COMMENT =
          createTextAttributesKey("ZIG_DOC_COMMENT", DefaultLanguageHighlighterColors.DOC_COMMENT);
  private static final TextAttributesKey[] DOC_COMMENT_KEYS = new TextAttributesKey[]{DOC_COMMENT};

  public static final TextAttributesKey OPERATION_SIGN =
          createTextAttributesKey("ZIG_OPERATION_SIGN", DefaultLanguageHighlighterColors.OPERATION_SIGN);
  private static final TextAttributesKey[] OPERATION_SIGN_KEYS = new TextAttributesKey[]{OPERATION_SIGN};

  public static final TextAttributesKey BRACES =
          createTextAttributesKey("ZIG_BRACES", DefaultLanguageHighlighterColors.BRACES);
  private static final TextAttributesKey[] BRACES_KEYS = new TextAttributesKey[]{BRACES};

  public static final TextAttributesKey DOT =
          createTextAttributesKey("ZIG_DOT", DefaultLanguageHighlighterColors.DOT);
  private static final TextAttributesKey[] DOT_KEYS = new TextAttributesKey[]{DOT};

  public static final TextAttributesKey SEMICOLON =
          createTextAttributesKey("ZIG_SEMICOLON", DefaultLanguageHighlighterColors.SEMICOLON);
  private static final TextAttributesKey[] SEMICOLON_KEYS = new TextAttributesKey[]{SEMICOLON};

  public static final TextAttributesKey COMMA =
          createTextAttributesKey("ZIG_COMMA", DefaultLanguageHighlighterColors.COMMA);
  private static final TextAttributesKey[] COMMA_KEYS = new TextAttributesKey[]{COMMA};

  public static final TextAttributesKey PARENTHESES =
          createTextAttributesKey("ZIG_PARENTHESES", DefaultLanguageHighlighterColors.PARENTHESES);
  private static final TextAttributesKey[] PARENTHESES_KEYS = new TextAttributesKey[]{PARENTHESES};

  public static final TextAttributesKey BRACKETS =
          createTextAttributesKey("ZIG_BRACKETS", DefaultLanguageHighlighterColors.BRACKETS);
  private static final TextAttributesKey[] BRACKETS_KEYS = new TextAttributesKey[]{BRACKETS};

  public static final TextAttributesKey BAD_CHARACTER =
          createTextAttributesKey("ZIG_BAD_CHARACTER", HighlighterColors.BAD_CHARACTER);
  private static final TextAttributesKey[] BAD_CHARACTER_KEYS = new TextAttributesKey[]{BAD_CHARACTER};

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
    if (tokenType.equals(TokenType.BAD_CHARACTER)) {
      return BAD_CHARACTER_KEYS;
    }
    return EMPTY_KEYS;
  }

}
