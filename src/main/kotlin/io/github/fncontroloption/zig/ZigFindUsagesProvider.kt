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
        return psiElement is ZigNameIdentifierOwner
    }

    override fun getHelpId(psiElement: PsiElement): String? {
        return null // TODO
    }

    override fun getType(element: PsiElement): String {
        val namedElement = element as? ZigNameIdentifierOwner ?: return ""
        return when (namedElement) {
            is ZigContainerField -> "field"
            is ZigFnDecl -> "function"
            is ZigLabelDecl -> "label"
            is ZigFnParam -> "function parameter"
            is ZigPayloadItem -> "capture"
            is ZigVarDeclOrProto -> when (namedElement.mutability) {
                ZigMutability.CONST -> "constant"
                ZigMutability.VAR -> "variable"
            }
        }
    }

    override fun getDescriptiveName(element: PsiElement): String {
        return (element as? ZigNameIdentifierOwner)?.name ?: "" // TODO
    }

    override fun getNodeText(element: PsiElement, useFullName: Boolean): String {
        return (element as? ZigNameIdentifierOwner)?.name ?: "" // TODO
    }
}
