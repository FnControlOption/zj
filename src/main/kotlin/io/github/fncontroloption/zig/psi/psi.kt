package io.github.fncontroloption.zig.psi

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.util.NlsSafe
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiReference
import com.intellij.psi.impl.source.resolve.reference.ReferenceProvidersRegistry
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.IncorrectOperationException
import io.github.fncontroloption.zig.ZigFileType
import io.github.fncontroloption.zig.ZigLanguage

interface ZigContainer : PsiElement {
    var cachedDecls: Map<String, PsiElement>?

    val decls: Map<String, PsiElement>
        get() {
            var decls = cachedDecls
            if (decls != null) {
                return decls
            }
            decls = mutableMapOf()
            var child = firstChild
            while (child != null) {
                val name = when (child) {
                    is ZigVarDecl -> child.name
                    is ZigFnDecl -> child.name
                    else -> null
                }
                if (name != null) {
                    decls[name] = child
                }
                child = child.nextSibling
            }
            cachedDecls = decls
            return decls
        }
}

class ZigFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, ZigLanguage.INSTANCE), ZigContainer {
    override var cachedDecls: Map<String, PsiElement>? = null

    override fun subtreeChanged() {
        cachedDecls = null
    }

    override fun getFileType(): FileType = ZigFileType.INSTANCE

    override fun toString() = "ZigFile:$name"

    override fun getReferences() = ReferenceProvidersRegistry.getReferencesFromProviders(this)
}

open class ZigPsiElement(node: ASTNode) : ASTWrapperPsiElement(node) {
    override fun getReferences(): Array<out PsiReference?> {
        return ReferenceProvidersRegistry.getReferencesFromProviders(this)
    }
}

class ZigBlock(node: ASTNode) : ZigPsiElement(node)

class ZigContainerDecl(node: ASTNode) : ZigPsiElement(node), ZigContainer {
    override var cachedDecls: Map<String, PsiElement>? = null

    override fun subtreeChanged() {
        cachedDecls = null
    }
}

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

    override fun getTextOffset(): Int {
        return nameIdentifier?.textOffset ?: super.getTextOffset()
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

class ZigFnDecl(node: ASTNode) : ZigPsiElement(node) {
    val fnProto: ZigFnProto
        get() = findNotNullChildByClass(ZigFnProto::class.java)

    override fun getName() = fnProto.name

    override fun getTextOffset() = fnProto.textOffset
}

class ZigFnProto(node: ASTNode) : ZigNamedElement(node) {
    val fnParams: ZigFnParams?
        get() = findChildByClass(ZigFnParams::class.java)
}

class ZigFnParams(node: ASTNode) : ZigPsiElement(node) {
    private var cachedFnParamDecls: Map<String, ZigFnParamDecl>? = null

    val fnParamDecls: Map<String, PsiElement>
        get() {
            var decls = cachedFnParamDecls
            if (decls != null) {
                return decls
            }
            decls = mutableMapOf()
            var child = firstChild
            while (child != null) {
                if (child is ZigFnParamDecl) {
                    val name = child.name
                    if (name != null) {
                        decls[name] = child
                    }
                }
                child = child.nextSibling
            }
            cachedFnParamDecls = decls
            return decls
        }

    override fun subtreeChanged() {
        cachedFnParamDecls = null
    }
}

class ZigFnParamDecl(node: ASTNode) : ZigNamedElement(node)
