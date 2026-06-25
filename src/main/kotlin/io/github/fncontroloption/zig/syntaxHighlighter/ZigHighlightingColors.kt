package io.github.fncontroloption.zig.syntaxHighlighter

import com.intellij.ide.highlighter.JavaHighlightingColors
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey

object ZigHighlightingColors {

    // *** Highlighter ***

    val IDENTIFIER = TextAttributesKey.createTextAttributesKey("ZIG_IDENTIFIER", DefaultLanguageHighlighterColors.IDENTIFIER)
    val NUMBER = TextAttributesKey.createTextAttributesKey("ZIG_NUMBER", DefaultLanguageHighlighterColors.NUMBER)
    val KEYWORD = TextAttributesKey.createTextAttributesKey("ZIG_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)
    val STRING = TextAttributesKey.createTextAttributesKey("ZIG_STRING", DefaultLanguageHighlighterColors.STRING)
    val LINE_COMMENT = TextAttributesKey.createTextAttributesKey("ZIG_LINE_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT)
    val DOC_COMMENT = TextAttributesKey.createTextAttributesKey("ZIG_DOC_COMMENT", DefaultLanguageHighlighterColors.DOC_COMMENT)
    val OPERATION_SIGN = TextAttributesKey.createTextAttributesKey("ZIG_OPERATION_SIGN", DefaultLanguageHighlighterColors.OPERATION_SIGN)
    val BRACES = TextAttributesKey.createTextAttributesKey("ZIG_BRACES", DefaultLanguageHighlighterColors.BRACES)
    val DOT = TextAttributesKey.createTextAttributesKey("ZIG_DOT", DefaultLanguageHighlighterColors.DOT)
    val SEMICOLON = TextAttributesKey.createTextAttributesKey("ZIG_SEMICOLON", DefaultLanguageHighlighterColors.SEMICOLON)
    val COMMA = TextAttributesKey.createTextAttributesKey("ZIG_COMMA", DefaultLanguageHighlighterColors.COMMA)
    val PARENTHESES = TextAttributesKey.createTextAttributesKey("ZIG_PARENTHESES", DefaultLanguageHighlighterColors.PARENTHESES)
    val BRACKETS = TextAttributesKey.createTextAttributesKey("ZIG_BRACKETS", DefaultLanguageHighlighterColors.BRACKETS)
    val BUILTIN = TextAttributesKey.createTextAttributesKey("ZIG_BUILTIN", DefaultLanguageHighlighterColors.METADATA)
    val BAD_CHARACTER = TextAttributesKey.createTextAttributesKey("ZIG_BAD_CHARACTER", HighlighterColors.BAD_CHARACTER)

    // *** Highlight visitor ***

    val INSTANCE_METHOD = TextAttributesKey.createTextAttributesKey("ZIG_INSTANCE_METHOD", DefaultLanguageHighlighterColors.INSTANCE_METHOD)
    val INSTANCE_FIELD = TextAttributesKey.createTextAttributesKey("ZIG_INSTANCE_FIELD", DefaultLanguageHighlighterColors.INSTANCE_FIELD)
    val STATIC_METHOD = TextAttributesKey.createTextAttributesKey("ZIG_STATIC_METHOD", DefaultLanguageHighlighterColors.STATIC_METHOD)
    val STATIC_FIELD = TextAttributesKey.createTextAttributesKey("ZIG_STATIC_FIELD", DefaultLanguageHighlighterColors.STATIC_FIELD)
    val CONSTANT = TextAttributesKey.createTextAttributesKey("ZIG_CONSTANT", DefaultLanguageHighlighterColors.CONSTANT)
    val LOCAL_VARIABLE = TextAttributesKey.createTextAttributesKey("ZIG_LOCAL_VARIABLE", DefaultLanguageHighlighterColors.LOCAL_VARIABLE)
    val REASSIGNED_LOCAL_VARIABLE = TextAttributesKey.createTextAttributesKey("ZIG_REASSIGNED_LOCAL_VARIABLE", DefaultLanguageHighlighterColors.REASSIGNED_LOCAL_VARIABLE)
    val PARAMETER = TextAttributesKey.createTextAttributesKey("ZIG_PARAMETER", DefaultLanguageHighlighterColors.PARAMETER)
    val TYPE_PARAMETER = TextAttributesKey.createTextAttributesKey("ZIG_TYPE_PARAMETER", JavaHighlightingColors.TYPE_PARAMETER_NAME_ATTRIBUTES)
}
