package io.github.fncontroloption.zig

import com.intellij.lang.BracePair
import com.intellij.lang.PairedBraceMatcher
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import io.github.fncontroloption.zig.psi.ZigTokenTypes

private val PAIRS = arrayOf(
    BracePair(ZigTokenTypes.L_PAREN, ZigTokenTypes.R_PAREN, false),
    BracePair(ZigTokenTypes.L_BRACE, ZigTokenTypes.R_BRACE, true),
    BracePair(ZigTokenTypes.L_BRACKET, ZigTokenTypes.R_BRACKET, false),
)

class ZigBraceMatcher : PairedBraceMatcher {
    override fun getPairs() = PAIRS
    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?) = false
    override fun getCodeConstructStart(file: PsiFile?, openingBraceOffset: Int) = openingBraceOffset // TODO
}
