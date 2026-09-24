package io.github.fncontroloption.zig

import com.intellij.lang.cacheBuilder.DefaultWordsScanner
import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.TokenSet
import io.github.fncontroloption.zig.psi2.*

class ZigFindUsagesProvider : FindUsagesProvider {
    override fun getWordsScanner(): WordsScanner {
        return DefaultWordsScanner(
            ZigLexerAdapter(),
            TokenSet.create(ZigTokenType.IDENTIFIER),
            ZigTokenSets.COMMENTS,
            TokenSet.EMPTY, // TODO
        )
    }

    override fun canFindUsagesFor(psiElement: PsiElement): Boolean {
        return psiElement is ZigNamedElement
    }

    override fun getHelpId(psiElement: PsiElement): String? {
        return null // TODO
    }

    override fun getType(element: PsiElement): String {
        if (element !is ZigNamedElement) {
            return ""
        }
        return when (element) {
            is ZigContainerFieldImpl -> "field"
            is ZigFnDecl -> "function"
            is ZigLabelDecl -> "label"
            is ZigFnParam -> "parameter"
            is ZigPayloadItem -> "payload capture"
            is ZigVarDecl, is ZigVarProto -> when (element.mutability) {
                ZigMutability.CONST -> "constant"
                ZigMutability.VAR -> "variable"
            }
        }
    }

    override fun getDescriptiveName(element: PsiElement): String {
        return (element as? ZigNamedElement)?.name ?: "" // TODO
    }

    override fun getNodeText(element: PsiElement, useFullName: Boolean): String {
        return (element as? ZigNamedElement)?.name ?: "" // TODO
    }
}
