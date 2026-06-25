package io.github.fncontroloption.zig

import com.intellij.lang.BracePair
import com.intellij.lang.PairedBraceMatcher
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import io.github.fncontroloption.zig.psi2.ZigTokenType

private val PAIRS = arrayOf(
    BracePair(ZigTokenType.L_PAREN, ZigTokenType.R_PAREN, false),
    BracePair(ZigTokenType.L_BRACE, ZigTokenType.R_BRACE, true),
    BracePair(ZigTokenType.L_BRACKET, ZigTokenType.R_BRACKET, false),
)

class ZigBraceMatcher : PairedBraceMatcher {
    override fun getPairs() = PAIRS
    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?) = false
    override fun getCodeConstructStart(file: PsiFile?, openingBraceOffset: Int) = openingBraceOffset // TODO
}
