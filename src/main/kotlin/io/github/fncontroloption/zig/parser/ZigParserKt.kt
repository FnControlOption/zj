package io.github.fncontroloption.zig.parser

import com.intellij.lang.ASTNode
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.psi.tree.IElementType
import io.github.fncontroloption.zig.psi.ZigTypes

// Based on https://codeberg.org/ziglang/zig/src/commit/72d954e/lib/std/zig/Parse.zig
/** @see com.intellij.java.syntax.parser.JavaParser */
class ZigParserKt : PsiParser {
    lateinit var builder: PsiBuilder

    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        this.builder = builder
        parseRoot(root)
        return builder.treeBuilt
    }

    private fun parseRoot(root: IElementType) {
        val rootMarker = builder.mark()
        parseContainerMembers()
        rootMarker.done(root)
    }

    private fun parseContainerMembers() {
        var invalidElements: PsiBuilder.Marker? = null
        while (!builder.eof()) {
            if (builder.tokenType === ZigTypes.R_BRACE) {
                break
            }

            val member = parseContainerMember()
            if (member != null) {
                if (invalidElements != null) {
                    invalidElements.errorBefore("Expected container member", member)
                    invalidElements = null
                }
                continue
            }

            if (invalidElements == null) {
                invalidElements = builder.mark()
            }
            builder.advanceLexer()
        }

        invalidElements?.error("Expected container member")
    }

    private fun parseContainerMember(): PsiBuilder.Marker? {
        val testDecl = parseTestDecl()
        if (testDecl != null) return testDecl
        return null // TODO
    }

    private fun parseTestDecl(): PsiBuilder.Marker? {
        return null // TODO
    }
}
