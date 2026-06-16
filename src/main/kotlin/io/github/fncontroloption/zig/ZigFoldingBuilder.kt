package io.github.fncontroloption.zig

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiRecursiveVisitor
import com.intellij.psi.PsiWalkingState
import com.intellij.psi.tree.TokenSet
import io.github.fncontroloption.zig.psi.ZigElementTypes
import io.github.fncontroloption.zig.psi.ZigTokenTypes

private val BRACE_ENCLOSED = TokenSet.create(
    ZigElementTypes.BLOCK,
    ZigElementTypes.CONTAINER_DECL_BODY,
    ZigElementTypes.ERROR_SET_DECL,
)

private val PAREN_ENCLOSED = TokenSet.create(
    ZigElementTypes.ADDRSPACE,
    ZigElementTypes.ALIGN,
    ZigElementTypes.CALL_ARGS,
    ZigElementTypes.CALLCONV,
    ZigElementTypes.CONTAINER_DECL_ARG,
    ZigElementTypes.FN_PARAMS,
    ZigElementTypes.LINKSECTION,
)

private val BLOCKS_COLLAPSED_BY_DEFAULT = TokenSet.create(
    ZigElementTypes.FN_DECL,
    ZigElementTypes.TEST_DECL,
)

class ZigFoldingBuilder : FoldingBuilderEx(), DumbAware {
    override fun buildFoldRegions(
        root: PsiElement,
        document: Document,
        quick: Boolean
    ): Array<out FoldingDescriptor> {
        val descriptors = mutableListOf<FoldingDescriptor>()
        root.accept(object : PsiElementVisitor(), PsiRecursiveVisitor {
            val walkingState = object : PsiWalkingState(this) {}
            override fun visitElement(element: PsiElement) {
                walkingState.elementStarted(element)
                val node = element.node ?: return
                when (node.elementType) {
                    in BRACE_ENCLOSED -> {
                        if (!node.textContains('\n')) {
                            return
                        }
                        val lbrace = node.findChildByType(ZigTokenTypes.L_BRACE) ?: return
                        val range = TextRange(lbrace.textRange.startOffset, node.textRange.endOffset)
                        descriptors.add(FoldingDescriptor(element, range))
                    }
                    in PAREN_ENCLOSED -> {
                        if (!node.textContains('\n')) {
                            return
                        }
                        val lparen = node.findChildByType(ZigTokenTypes.L_PAREN) ?: return
                        val range = TextRange(lparen.textRange.startOffset, node.textRange.endOffset)
                        descriptors.add(FoldingDescriptor(element, range))
                    }
                }
            }
        })
        return descriptors.toTypedArray()
    }

    override fun isCollapsedByDefault(node: ASTNode): Boolean {
        return node.elementType == ZigElementTypes.BLOCK && node.treeParent?.elementType in BLOCKS_COLLAPSED_BY_DEFAULT
    }

    override fun getPlaceholderText(node: ASTNode): String? {
        return when (node.elementType) {
            in BRACE_ENCLOSED -> "{...}"
            in PAREN_ENCLOSED -> "(...)"
            else -> null
        }
    }
}
