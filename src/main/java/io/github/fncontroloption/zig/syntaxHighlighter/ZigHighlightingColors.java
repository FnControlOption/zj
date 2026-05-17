package io.github.fncontroloption.zig.syntaxHighlighter;

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.HighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;

public interface ZigHighlightingColors {

    TextAttributesKey IDENTIFIER = TextAttributesKey.createTextAttributesKey("ZIG_IDENTIFIER", DefaultLanguageHighlighterColors.IDENTIFIER);
    TextAttributesKey NUMBER = TextAttributesKey.createTextAttributesKey("ZIG_NUMBER", DefaultLanguageHighlighterColors.NUMBER);
    TextAttributesKey KEYWORD = TextAttributesKey.createTextAttributesKey("ZIG_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD);
    TextAttributesKey STRING = TextAttributesKey.createTextAttributesKey("ZIG_STRING", DefaultLanguageHighlighterColors.STRING);
    TextAttributesKey LINE_COMMENT = TextAttributesKey.createTextAttributesKey("ZIG_LINE_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT);
    TextAttributesKey DOC_COMMENT = TextAttributesKey.createTextAttributesKey("ZIG_DOC_COMMENT", DefaultLanguageHighlighterColors.DOC_COMMENT);
    TextAttributesKey OPERATION_SIGN = TextAttributesKey.createTextAttributesKey("ZIG_OPERATION_SIGN", DefaultLanguageHighlighterColors.OPERATION_SIGN);
    TextAttributesKey BRACES = TextAttributesKey.createTextAttributesKey("ZIG_BRACES", DefaultLanguageHighlighterColors.BRACES);
    TextAttributesKey DOT = TextAttributesKey.createTextAttributesKey("ZIG_DOT", DefaultLanguageHighlighterColors.DOT);
    TextAttributesKey SEMICOLON = TextAttributesKey.createTextAttributesKey("ZIG_SEMICOLON", DefaultLanguageHighlighterColors.SEMICOLON);
    TextAttributesKey COMMA = TextAttributesKey.createTextAttributesKey("ZIG_COMMA", DefaultLanguageHighlighterColors.COMMA);
    TextAttributesKey PARENTHESES = TextAttributesKey.createTextAttributesKey("ZIG_PARENTHESES", DefaultLanguageHighlighterColors.PARENTHESES);
    TextAttributesKey BRACKETS = TextAttributesKey.createTextAttributesKey("ZIG_BRACKETS", DefaultLanguageHighlighterColors.BRACKETS);
    TextAttributesKey BUILTIN = TextAttributesKey.createTextAttributesKey("ZIG_BUILTIN", DefaultLanguageHighlighterColors.METADATA);
    TextAttributesKey BAD_CHARACTER = TextAttributesKey.createTextAttributesKey("ZIG_BAD_CHARACTER", HighlighterColors.BAD_CHARACTER);

}
