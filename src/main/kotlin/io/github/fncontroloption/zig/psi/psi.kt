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
import com.intellij.psi.util.elementType
import com.intellij.util.IncorrectOperationException
import io.github.fncontroloption.zig.ZigFileType
import io.github.fncontroloption.zig.ZigLanguage

interface ZigContainerBody : PsiElement {
    var cachedMembers: Map<String, ZigNamedElement>?

    val containerMembers: Map<String, ZigNamedElement>
        get() {
            var protos = cachedMembers
            if (protos != null) {
                return protos
            }
            protos = mutableMapOf()
            var child = firstChild
            while (child != null) {
                val proto = when (child) {
                    is ZigVarDecl -> child.varDeclProto
                    is ZigFnDecl -> child.fnProto
                    is ZigContainerField -> child
                    else -> null
                }
                if (proto != null) {
                    val name = proto.name
                    if (name != null) {
                        protos[name] = proto
                    }
                }
                child = child.nextSibling
            }
            cachedMembers = protos
            return protos
        }
}

class ZigFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, ZigLanguage.INSTANCE), ZigContainerBody {
    override var cachedMembers: Map<String, ZigNamedElement>? = null

    override fun subtreeChanged() {
        super.subtreeChanged()
        cachedMembers = null
    }

    override fun getFileType(): FileType = ZigFileType.INSTANCE

    override fun toString() = "ZigFile:$name"

    override fun getReferences() = ReferenceProvidersRegistry.getReferencesFromProviders(this)
}

open class ZigPsiElement(node: ASTNode) : ASTWrapperPsiElement(node) {
    override fun getReferences(): Array<out PsiReference> {
        return ReferenceProvidersRegistry.getReferencesFromProviders(this)
    }
}

class ZigBlock(node: ASTNode) : ZigPsiElement(node) {
    private var cachedVarDeclProtos: Map<String, ZigVarDeclProto>? = null

    val varDeclProtos: Map<String, ZigVarDeclProto>
        get() {
            var decls = cachedVarDeclProtos
            if (decls != null) {
                return decls
            }
            decls = mutableMapOf()
            var child = firstChild
            while (child != null) {
                if (child is ZigVarDecl) {
                    val name = child.name
                    if (name != null) {
                        decls[name] = child.varDeclProto
                    }
                }
                child = child.nextSibling
            }
            cachedVarDeclProtos = decls
            return decls
        }

    override fun subtreeChanged() {
        super.subtreeChanged()
        cachedVarDeclProtos = null
    }
}

class ZigContainerDecl(node: ASTNode) : ZigPsiElement(node) {
    val containerDeclBody: ZigContainerDeclBody?
        get() = findChildByClass(ZigContainerDeclBody::class.java)
}

class ZigContainerDeclBody(node: ASTNode) : ZigPsiElement(node), ZigContainerBody {
    override var cachedMembers: Map<String, ZigNamedElement>? = null

    override fun subtreeChanged() {
        super.subtreeChanged()
        cachedMembers = null
    }
}

class ZigCallArgs(node: ASTNode) : ZigPsiElement(node)

class ZigBuiltinCall(node: ASTNode) : ZigPsiElement(node) {
    val builtin: PsiElement
        get() = findNotNullChildByType(ZigTokenTypes.BUILTIN)

    val builtinCallArgs: ZigCallArgs?
        get() = findChildByClass(ZigCallArgs::class.java)
}

class ZigIdentifier(node: ASTNode) : ZigPsiElement(node)

class ZigTestDecl(node: ASTNode) : ZigPsiElement(node) {
    val testName: PsiElement?
        get() = findChildByType(ZigTokenSets.TEST_NAME)
}

class ZigFieldAccess(node: ASTNode) : ZigPsiElement(node) {
    val lhs: ZigPsiElement?
        get() = firstChild as? ZigPsiElement

    val fieldName: PsiElement?
        get() = findChildByType(ZigTokenTypes.IDENTIFIER)
}

class ZigTypeExpr(node: ASTNode) : ZigPsiElement(node) {
    val expr: ZigPsiElement?
        get() = firstChild as? ZigPsiElement
}

class ZigInitExpr(node: ASTNode) : ZigPsiElement(node) {
    val expr: ZigPsiElement?
        get() = firstChild as? ZigPsiElement
}

class ZigPtrType(node: ASTNode) : ZigPsiElement(node) {
    enum class Size {
        ONE,
        MANY,
        SLICE,
        C,
    }

    val size: Size?
        get() {
            var child = firstChild ?: return null
            return when (child.elementType) {
                ZigTokenTypes.ASTERISK -> Size.ONE
                ZigTokenTypes.L_BRACKET -> {
                    child = child.nextSibling ?: return null
                    when (child.elementType) {
                        ZigTokenTypes.ASTERISK -> {
                            child = child.nextSibling ?: return Size.MANY
                            if (child.textMatches("c")) Size.C else Size.MANY
                        }
                        else -> Size.SLICE
                    }
                }
                else -> null
            }
        }

    val child: ZigPsiElement?
        get() = lastChild as? ZigPsiElement

    fun isConst() = findChildByType<PsiElement?>(ZigTokenTypes.KEYWORD_CONST) != null
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

class ZigVarDeclProto(node: ASTNode) : ZigNamedElement(node) {
    enum class Type {
        LOCAL_CONST,
        LOCAL_VAR,
        GLOBAL_CONST,
        GLOBAL_VAR,
    }

    val declType: Type
        get() {
            val scope = PsiTreeUtil.findFirstParent(this, true) {
                it is ZigBlock || it is ZigContainerDeclBody
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

    val declTypeExpr: ZigTypeExpr?
        get() = findChildByClass(ZigTypeExpr::class.java)


    fun isVar() = findChildByType<PsiElement?>(ZigTokenTypes.KEYWORD_VAR) != null

    fun isConst() = findChildByType<PsiElement?>(ZigTokenTypes.KEYWORD_CONST) != null
}

class ZigVarDecl(node: ASTNode) : ZigPsiElement(node) {
    val varDeclProto: ZigVarDeclProto
        get() = findNotNullChildByClass(ZigVarDeclProto::class.java)

    val varDeclInitExpr: ZigInitExpr?
        get() = findChildByClass(ZigInitExpr::class.java)

    override fun getName() = varDeclProto.name

    override fun getTextOffset() = varDeclProto.textOffset
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

    val fnParamDecls: Map<String, ZigFnParamDecl>
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
        super.subtreeChanged()
        cachedFnParamDecls = null
    }
}

class ZigFnParamDecl(node: ASTNode) : ZigNamedElement(node) {
    val fnParamTypeExpr: ZigTypeExpr?
        get() = findChildByClass(ZigTypeExpr::class.java)

    fun isAnytype() = findChildByType<PsiElement?>(ZigTokenTypes.KEYWORD_ANYTYPE) != null
}

class ZigContainerField(node: ASTNode) : ZigNamedElement(node) {
    val containerFieldTypeExpr: ZigTypeExpr?
        get() = findChildByClass(ZigTypeExpr::class.java)
}
