package io.github.fncontroloption.zig.psi

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.openapi.util.NlsSafe
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiReference
import com.intellij.psi.impl.source.resolve.reference.ReferenceProvidersRegistry
import com.intellij.util.IncorrectOperationException

open class ZigPsiElement(node: ASTNode) : ASTWrapperPsiElement(node) {
    override fun getReferences(): Array<out PsiReference?> {
        return ReferenceProvidersRegistry.getReferencesFromProviders(this)
    }
}

class ZigBlock(node: ASTNode) : ZigPsiElement(node)

class ZigContainerDecl(node: ASTNode) : ZigPsiElement(node)

class ZigCallArgs(node: ASTNode) : ZigPsiElement(node)

class ZigBuiltinCall(node: ASTNode) : ZigPsiElement(node) {
    val builtin: PsiElement
        get() = findNotNullChildByType(ZigTypes.BUILTIN)
}

sealed class ZigNamedElement(node: ASTNode) : ZigPsiElement(node), PsiNameIdentifierOwner {
    override fun getNameIdentifier(): PsiElement? {
        return findChildByType(ZigTypes.IDENTIFIER)
    }

    override fun setName(name: @NlsSafe String): PsiElement? {
        throw IncorrectOperationException()
    }
}

class ZigVarDecl(node: ASTNode) : ZigNamedElement(node) {
    fun isVar() = findChildByType<PsiElement?>(ZigTypes.KEYWORD_VAR) != null
    fun isConst() = findChildByType<PsiElement?>(ZigTypes.KEYWORD_CONST) != null
}

class ZigFnDecl(node: ASTNode) : ZigNamedElement(node)
