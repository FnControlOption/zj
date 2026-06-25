package io.github.fncontroloption.zig.parser

import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import io.github.fncontroloption.zig.lexer.ZigLexerAdapter
import io.github.fncontroloption.zig.psi.ZigElementTypes
import io.github.fncontroloption.zig.psi.ZigFile
import io.github.fncontroloption.zig.psi.ZigFileElementType
import io.github.fncontroloption.zig.psi.ZigTokenSets

class ZigParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?) = ZigLexerAdapter()
    override fun getCommentTokens() = ZigTokenSets.COMMENTS
    override fun getStringLiteralElements() = ZigTokenSets.STRINGS
    override fun createParser(project: Project?) = ZigParserKt()
    override fun getFileNodeType() = ZigFileElementType
    override fun createFile(viewProvider: FileViewProvider) = ZigFile(viewProvider)
    override fun createElement(node: ASTNode) = ZigElementTypes.Factory.createElement(node)
}
