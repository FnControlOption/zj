package io.github.fncontroloption.zig.syntaxHighlighter

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import io.github.fncontroloption.zig.lexer.ZigLexerAdapter
import io.github.fncontroloption.zig.psi.ZigTokenSets
import io.github.fncontroloption.zig.psi.ZigTokenTypes

private val IDENTIFIER_KEYS = arrayOf(ZigHighlightingColors.IDENTIFIER)
private val NUMBER_KEYS = arrayOf(ZigHighlightingColors.NUMBER)
private val KEYWORD_KEYS = arrayOf(ZigHighlightingColors.KEYWORD)
private val STRING_KEYS = arrayOf(ZigHighlightingColors.STRING)
private val LINE_COMMENT_KEYS = arrayOf(ZigHighlightingColors.LINE_COMMENT)
private val DOC_COMMENT_KEYS = arrayOf(ZigHighlightingColors.DOC_COMMENT)
private val OPERATION_SIGN_KEYS = arrayOf(ZigHighlightingColors.OPERATION_SIGN)
private val BRACES_KEYS = arrayOf(ZigHighlightingColors.BRACES)
private val DOT_KEYS = arrayOf(ZigHighlightingColors.DOT)
private val SEMICOLON_KEYS = arrayOf(ZigHighlightingColors.SEMICOLON)
private val COMMA_KEYS = arrayOf(ZigHighlightingColors.COMMA)
private val PARENTHESES_KEYS = arrayOf(ZigHighlightingColors.PARENTHESES)
private val BRACKETS_KEYS = arrayOf(ZigHighlightingColors.BRACKETS)
private val BUILTIN_KEYS = arrayOf(ZigHighlightingColors.BUILTIN)
private val BAD_CHARACTER_KEYS = arrayOf(ZigHighlightingColors.BAD_CHARACTER)
private val EMPTY_KEYS = arrayOf<TextAttributesKey>()

class ZigSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer() = ZigLexerAdapter()

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> {
        if (tokenType == ZigTokenTypes.IDENTIFIER) {
            return IDENTIFIER_KEYS
        }
        if (tokenType == ZigTokenTypes.NUMBER_LITERAL) {
            return NUMBER_KEYS
        }
        if (ZigTokenSets.KEYWORDS.contains(tokenType)) {
            return KEYWORD_KEYS
        }
        if (ZigTokenSets.STRINGS.contains(tokenType) || tokenType == ZigTokenTypes.CHAR_LITERAL) {
            return STRING_KEYS
        }
        if (ZigTokenSets.DOC_COMMENTS.contains(tokenType)) {
            return DOC_COMMENT_KEYS
        }
        if (ZigTokenSets.BRACES.contains(tokenType)) {
            return BRACES_KEYS
        }
        if (ZigTokenSets.OPERATION_SIGNS.contains(tokenType)) {
            return OPERATION_SIGN_KEYS
        }
        if (tokenType == ZigTokenTypes.PERIOD) {
            return DOT_KEYS
        }
        if (tokenType == ZigTokenTypes.SEMICOLON) {
            return SEMICOLON_KEYS
        }
        if (tokenType == ZigTokenTypes.COMMA) {
            return COMMA_KEYS
        }
        if (ZigTokenSets.PARENTHESES.contains(tokenType)) {
            return PARENTHESES_KEYS
        }
        if (ZigTokenSets.BRACKETS.contains(tokenType)) {
            return BRACKETS_KEYS
        }
        if (tokenType == ZigTokenTypes.LINE_COMMENT) {
            return LINE_COMMENT_KEYS
        }
        if (tokenType == ZigTokenTypes.BUILTIN) {
            return BUILTIN_KEYS
        }
        if (tokenType == TokenType.BAD_CHARACTER) {
            return BAD_CHARACTER_KEYS
        }
        return EMPTY_KEYS
    }
}
