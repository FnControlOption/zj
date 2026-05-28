package io.github.fncontroloption.zig.parser

import com.intellij.lang.ASTNode
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.psi.tree.IElementType

// Based on https://codeberg.org/ziglang/zig/src/commit/72d954e/lib/std/zig/Parse.zig
class ZigParserKt : PsiParser {
    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        builder.mark().done(root)
        return builder.treeBuilt
    }
}
