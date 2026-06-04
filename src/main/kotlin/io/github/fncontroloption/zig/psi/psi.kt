package io.github.fncontroloption.zig.psi

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.openapi.util.NlsSafe
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiReference
import com.intellij.psi.impl.source.resolve.reference.ReferenceProvidersRegistry
import com.intellij.psi.util.PsiTreeUtil
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
        get() = findNotNullChildByType(ZigTokenTypes.BUILTIN)
}

sealed class ZigNamedElement(node: ASTNode) : ZigPsiElement(node), PsiNameIdentifierOwner {
    override fun getNameIdentifier(): PsiElement? {
        return findChildByType(ZigTokenTypes.IDENTIFIER)
    }

    override fun getName(): String? {
        return nameIdentifier?.text
    }

    override fun setName(name: @NlsSafe String): PsiElement? {
        throw IncorrectOperationException()
    }
}

class ZigVarDecl(node: ASTNode) : ZigNamedElement(node) {
    enum class Type {
        LOCAL_CONST,
        LOCAL_VAR,
        GLOBAL_CONST,
        GLOBAL_VAR,
    }

    val declType: Type
        get() {
            val scope = PsiTreeUtil.findFirstParent(this, true) {
                it is ZigBlock || it is ZigContainerDecl
            }

            return when (scope) {
                is ZigBlock -> when {
                    isConst() -> Type.LOCAL_CONST
                    else -> Type.LOCAL_VAR
                }
                else -> when {
                    isConst() -> Type.GLOBAL_CONST
                    else -> Type.GLOBAL_VAR
                }
            }
        }

    fun isVar() = findChildByType<PsiElement?>(ZigTokenTypes.KEYWORD_VAR) != null

    fun isConst() = findChildByType<PsiElement?>(ZigTokenTypes.KEYWORD_CONST) != null
}

class ZigFnDecl(node: ASTNode) : ZigNamedElement(node)
