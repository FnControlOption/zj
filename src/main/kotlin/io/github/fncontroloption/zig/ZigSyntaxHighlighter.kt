package io.github.fncontroloption.zig

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

class ZigSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer() = ZigLexerAdapter()

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> {
        if (tokenType == ZigTokenType.IDENTIFIER) {
            return arrayOf(ZigHighlightingColors.IDENTIFIER)
        }
        if (tokenType == ZigTokenType.NUMBER_LITERAL) {
            return arrayOf(ZigHighlightingColors.NUMBER)
        }
        if (ZigTokenSets.KEYWORDS.contains(tokenType)) {
            return arrayOf(ZigHighlightingColors.KEYWORD)
        }
        if (ZigTokenSets.STRINGS.contains(tokenType) || tokenType == ZigTokenType.CHAR_LITERAL) {
            return arrayOf(ZigHighlightingColors.STRING)
        }
        if (ZigTokenSets.DOC_COMMENTS.contains(tokenType)) {
            return arrayOf(ZigHighlightingColors.DOC_COMMENT)
        }
        if (ZigTokenSets.BRACES.contains(tokenType)) {
            return arrayOf(ZigHighlightingColors.BRACES)
        }
        if (ZigTokenSets.OPERATION_SIGNS.contains(tokenType)) {
            return arrayOf(ZigHighlightingColors.OPERATION_SIGN)
        }
        if (tokenType == ZigTokenType.PERIOD) {
            return arrayOf(ZigHighlightingColors.DOT)
        }
        if (tokenType == ZigTokenType.SEMICOLON) {
            return arrayOf(ZigHighlightingColors.SEMICOLON)
        }
        if (tokenType == ZigTokenType.COMMA) {
            return arrayOf(ZigHighlightingColors.COMMA)
        }
        if (ZigTokenSets.PARENTHESES.contains(tokenType)) {
            return arrayOf(ZigHighlightingColors.PARENTHESES)
        }
        if (ZigTokenSets.BRACKETS.contains(tokenType)) {
            return arrayOf(ZigHighlightingColors.BRACKETS)
        }
        if (tokenType == ZigTokenType.LINE_COMMENT) {
            return arrayOf(ZigHighlightingColors.LINE_COMMENT)
        }
        if (tokenType == ZigTokenType.BUILTIN) {
            return arrayOf(ZigHighlightingColors.BUILTIN)
        }
        if (tokenType == TokenType.BAD_CHARACTER) {
            return arrayOf(ZigHighlightingColors.BAD_CHARACTER)
        }
        return emptyArray()
    }
}
