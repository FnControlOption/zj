package io.github.fncontroloption.zig.psi

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.PresentationData
import com.intellij.lang.ASTNode
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsSafe
import com.intellij.psi.FileViewProvider
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiReference
import com.intellij.psi.impl.source.resolve.reference.ReferenceProvidersRegistry
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import com.intellij.ui.RowIcon
import com.intellij.util.IncorrectOperationException
import io.github.fncontroloption.zig.ZigFileType
import io.github.fncontroloption.zig.ZigLanguage

interface ZigStructureElement : NavigatablePsiElement {
    fun isViewable(): Boolean = true
}

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

class ZigBlock(node: ASTNode) : ZigNamedElement(node) {
    val blockBody: ZigBlockBody?
        get() = findChildByClass(ZigBlockBody::class.java)
}

class ZigBlockBody(node: ASTNode) : ZigPsiElement(node) {
    private var cachedVarDeclProtos: Map<String, ZigVarDeclProto>? = null

    val varDeclProtos: Map<String, ZigVarDeclProto>
        get() {
            var decls = cachedVarDeclProtos
            if (decls != null) {
                return decls
            }
            decls = mutableMapOf()
            for (varDecl in childrenOfType<ZigVarDecl>(this)) {
                val name = varDecl.name
                if (name != null) {
                    decls[name] = varDecl.varDeclProto
                }
            }
            cachedVarDeclProtos = decls
            return decls
        }

    override fun subtreeChanged() {
        super.subtreeChanged()
        cachedVarDeclProtos = null
    }
}

class ZigContainerDecl(node: ASTNode) : ZigPsiElement(node), ZigStructureElement {
    val containerDeclBody: ZigContainerDeclBody?
        get() = findChildByClass(ZigContainerDeclBody::class.java)

    val containerDeclType: PsiElement
        get() = findNotNullChildByType(ZigTokenSets.CONTAINERS)

    fun isEnum() = findChildByType<PsiElement?>(ZigTokenTypes.KEYWORD_ENUM) != null

    fun isOpaque() = findChildByType<PsiElement?>(ZigTokenTypes.KEYWORD_OPAQUE) != null

    fun isStruct() = findChildByType<PsiElement?>(ZigTokenTypes.KEYWORD_STRUCT) != null

    fun isUnion() = findChildByType<PsiElement?>(ZigTokenTypes.KEYWORD_UNION) != null

    override fun getName(): String? = containerDeclType.text

    override fun getPresentation(): ItemPresentation {
        return PresentationData(containerDeclType.text, null, AllIcons.Nodes.Type, null)
    }
}

class ZigContainerDeclBody(node: ASTNode) : ZigPsiElement(node), ZigContainerBody {
    override var cachedMembers: Map<String, ZigNamedElement>? = null

    override fun subtreeChanged() {
        super.subtreeChanged()
        cachedMembers = null
    }
}

class ZigCallArgs(node: ASTNode) : ZigPsiElement(node) {
    val exprs: Sequence<ZigPsiElement>
        get() = childrenOfType(this)
}

class ZigBuiltinCall(node: ASTNode) : ZigPsiElement(node) {
    val builtin: PsiElement
        get() = findNotNullChildByType(ZigTokenTypes.BUILTIN)

    val builtinCallArgs: ZigCallArgs?
        get() = findChildByClass(ZigCallArgs::class.java)
}

class ZigIdentifier(node: ASTNode) : ZigPsiElement(node) {
    val identifierToken: PsiElement?
        get() = findChildByType(ZigTokenTypes.IDENTIFIER)
}

class ZigTestDecl(node: ASTNode) : ZigPsiElement(node), ZigStructureElement {
    val testName: PsiElement?
        get() = findChildByType(ZigTokenSets.TEST_NAME)

    override fun getName() = testName?.text

    override fun getPresentation(): ItemPresentation {
        val name = testName?.text
        val text = if (name != null) "test $name" else "test"
        return PresentationData(text, null, AllIcons.Nodes.Test, null)
    }
}

class ZigFieldAccess(node: ASTNode) : ZigPsiElement(node) {
    val lhs: ZigPsiElement?
        get() = findChildByClass(ZigPsiElement::class.java)

    val fieldName: PsiElement?
        get() = findChildByType(ZigTokenTypes.IDENTIFIER)
}

class ZigEnumLiteral(node: ASTNode) : ZigPsiElement(node) {
    val fieldName: PsiElement?
        get() = findChildByType(ZigTokenTypes.IDENTIFIER)
}

class ZigTypeExpr(node: ASTNode) : ZigPsiElement(node) {
    val expr: ZigPsiElement?
        get() = findChildByClass(ZigPsiElement::class.java)
}

class ZigInitExpr(node: ASTNode) : ZigPsiElement(node) {
    val expr: ZigPsiElement?
        get() = findChildByClass(ZigPsiElement::class.java)
}

class ZigOptionalType(node: ASTNode) : ZigPsiElement(node) {
    val child: ZigPsiElement?
        get() = findChildByClass(ZigPsiElement::class.java)
}

class ZigPointerType(node: ASTNode) : ZigPsiElement(node) {
    val child: ZigPsiElement?
        get() = lastChild as? ZigPsiElement

    fun isOne() = findChildByType<PsiElement?>(ZigTokenTypes.ASTERISK) != null
}

class ZigArrayType(node: ASTNode) : ZigPsiElement(node) {
    val child: ZigPsiElement?
        get() = lastChild as? ZigPsiElement
}

class ZigErrorUnion(node: ASTNode) : ZigPsiElement(node) {
    val lhs: ZigPsiElement?
        get() = firstChild as? ZigPsiElement

    val rhs: ZigPsiElement?
        get() = lastChild as? ZigPsiElement
}

class ZigCall(node: ASTNode) : ZigPsiElement(node) {
    val lhs: ZigPsiElement?
        get() = findChildByClass(ZigPsiElement::class.java)

    val callArgs: ZigCallArgs?
        get() = findChildByClass(ZigCallArgs::class.java)
}

class ZigStringLiteral(node: ASTNode) : ZigPsiElement(node) {
    // TODO: unescape string literal
    val literalValue: String
        get() = text.substring(1, textLength - 1)
}

class ZigReturn(node: ASTNode) : ZigPsiElement(node) {
    val expr: ZigPsiElement?
        get() = findChildByClass(ZigPsiElement::class.java)
}

class ZigArrayInit(node: ASTNode) : ZigPsiElement(node) {
    val initTypeExpr: ZigTypeExpr?
        get() = findChildByClass(ZigTypeExpr::class.java)

    val initList: ZigInitList?
        get() = findChildByClass(ZigInitList::class.java)

    fun isTypeInferred() = findChildByType<PsiElement?>(ZigTokenTypes.PERIOD) != null
}

class ZigInitList(node: ASTNode) : ZigPsiElement(node)

class ZigInitListEntry(node: ASTNode) : ZigPsiElement(node) {
    val fieldName: PsiElement?
        get() = findChildByType(ZigTokenTypes.IDENTIFIER)

    val expr: ZigPsiElement?
        get() = findChildByClass(ZigPsiElement::class.java)
}

class ZigDeref(node: ASTNode) : ZigPsiElement(node) {
    val lhs: ZigPsiElement?
        get() = findChildByClass(ZigPsiElement::class.java)
}

class ZigArrayAccess(node: ASTNode) : ZigPsiElement(node) {
    val lhs: ZigPsiElement?
        get() = findChildByClass(ZigPsiElement::class.java)
}

class ZigSlice(node: ASTNode) : ZigPsiElement(node) {
    val lhs: ZigPsiElement?
        get() = findChildByClass(ZigPsiElement::class.java)
}

class ZigUnwrapOptional(node: ASTNode) : ZigPsiElement(node) {
    val lhs: ZigPsiElement?
        get() = findChildByClass(ZigPsiElement::class.java)
}

sealed class ZigPrefixOp(node: ASTNode) : ZigPsiElement(node) {
    val expr: ZigPsiElement?
        get() = findChildByClass(ZigPsiElement::class.java)
}

class ZigBoolNot(node: ASTNode) : ZigPrefixOp(node)

class ZigNegation(node: ASTNode) : ZigPrefixOp(node)

class ZigBitNot(node: ASTNode) : ZigPrefixOp(node)

class ZigNegationWrap(node: ASTNode) : ZigPrefixOp(node)

class ZigAddressOf(node: ASTNode) : ZigPrefixOp(node)

class ZigTry(node: ASTNode) : ZigPrefixOp(node)

class ZigIf(node: ASTNode) : ZigPsiElement(node) {
    val ifCondition: ZigCondition?
        get() = findChildByClass(ZigCondition::class.java)

    val thenPayload: ZigPayload?
        get() = findChildByClass(ZigPayload::class.java)

    val thenBranch: ZigThen?
        get() = findChildByClass(ZigThen::class.java)

    val elseBranch: ZigElse?
        get() = findChildByClass(ZigElse::class.java)
}

class ZigThen(node: ASTNode) : ZigPsiElement(node) {
    val expr: ZigPsiElement?
        get() = lastChild as? ZigPsiElement
}

class ZigElse(node: ASTNode) : ZigPsiElement(node) {
    val elsePayload: ZigPayload?
        get() = findChildByClass(ZigPayload::class.java)

    val expr: ZigPsiElement?
        get() = lastChild as? ZigPsiElement
}

class ZigCondition(node: ASTNode) : ZigPsiElement(node) {
    val expr: ZigPsiElement?
        get() = findChildByClass(ZigPsiElement::class.java)
}

class ZigSwitch(node: ASTNode) : ZigNamedElement(node) {
    val switchCondition: ZigCondition?
        get() = findChildByClass(ZigCondition::class.java)
}

class ZigSwitchProngList(node: ASTNode) : ZigPsiElement(node)

class ZigSwitchProng(node: ASTNode) : ZigPsiElement(node) {
    val switchItems: Sequence<ZigSwitchItem>
        get() = childrenOfType(this)

    val switchProngPayload: ZigPayload?
        get() = findChildByClass(ZigPayload::class.java)
}

class ZigSwitchItem(node: ASTNode) : ZigPsiElement(node) {
    val switchItemExpr: ZigPsiElement?
        get() = findChildByClass(ZigPsiElement::class.java)
}

class ZigSwitchResult(node: ASTNode) : ZigPsiElement(node)

class ZigCatch(node: ASTNode) : ZigPsiElement(node) {
    val catchLhs: ZigPsiElement?
        get() = firstChild as? ZigPsiElement

    val catchPayload: ZigPayload?
        get() = findChildByClass(ZigPayload::class.java)

    val catchRhs: ZigPsiElement?
        get() = lastChild as? ZigPsiElement
}

class ZigWhile(node: ASTNode) : ZigNamedElement(node) {
    val whileCondition: ZigCondition?
        get() = findChildByClass(ZigCondition::class.java)

    val whilePayload: ZigPayload?
        get() = findChildByClass(ZigPayload::class.java)
}

class ZigFor(node: ASTNode) : ZigNamedElement(node) {
    val forInputs: ZigForInputs?
        get() = findChildByClass(ZigForInputs::class.java)

    val forPayload: ZigPayload?
        get() = findChildByClass(ZigPayload::class.java)
}

class ZigForInputs(node: ASTNode) : ZigPsiElement(node) {
    val forInputExprs: Sequence<ZigPsiElement>
        get() = childrenOfType(this)
}

class ZigBreak(node: ASTNode) : ZigPsiElement(node) {
    val breakLabel: PsiElement?
        get() = findChildByType(ZigTokenTypes.IDENTIFIER)

    val breakExpr: ZigPsiElement?
        get() = findChildByClass(ZigPsiElement::class.java)
}

class ZigContinue(node: ASTNode) : ZigPsiElement(node) {
    val continueLabel: PsiElement?
        get() = findChildByType(ZigTokenTypes.IDENTIFIER)
}

class ZigAssign(node: ASTNode) : ZigPsiElement(node) {
    val lhs: ZigPsiElement?
        get() = firstChild as? ZigPsiElement
}

class ZigOrelse(node: ASTNode) : ZigPsiElement(node) {
    val orelseLhs: ZigPsiElement?
        get() = firstChild as? ZigPsiElement

    val orelseRhs: ZigPsiElement?
        get() = lastChild as? ZigPsiElement
}

class ZigGroupedExpression(node: ASTNode) : ZigPsiElement(node) {
    val expr: ZigPsiElement?
        get() = findChildByClass(ZigPsiElement::class.java)
}

sealed class ZigNamedElement(node: ASTNode) : ZigPsiElement(node), PsiNameIdentifierOwner {
    override fun getNameIdentifier(): PsiElement? {
        return findChildByType(ZigTokenTypes.IDENTIFIER)
    }

    override fun getName(): String? {
        return nameIdentifier?.text
    }

    override fun setName(name: @NlsSafe String): PsiElement? {
        val oldIdent = nameIdentifier ?: throw IncorrectOperationException()
        val newIdent = createZigIdentifierToken(project, name)
        oldIdent.replace(newIdent)
        return this
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
            return when {
                isLocal() -> when {
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

    val scope: PsiElement?
        get() = PsiTreeUtil.findFirstParent(this, true) {
            it is ZigBlock || it is ZigContainerDecl
        }

    fun isLocal() = scope is ZigBlock

    fun isGlobal() = when (scope) {
        null, is ZigContainerDecl -> true
        else -> false
    }

    fun isVar() = findChildByType<PsiElement?>(ZigTokenTypes.KEYWORD_VAR) != null

    fun isConst() = findChildByType<PsiElement?>(ZigTokenTypes.KEYWORD_CONST) != null
}

class ZigVarDecl(node: ASTNode) : ZigPsiElement(node), ZigStructureElement {
    val varDeclProto: ZigVarDeclProto
        get() = findNotNullChildByClass(ZigVarDeclProto::class.java)

    val varDeclInitExpr: ZigInitExpr?
        get() = findChildByClass(ZigInitExpr::class.java)

    fun isPub() = findChildByType<PsiElement?>(ZigTokenTypes.KEYWORD_PUB) != null

    override fun getName() = varDeclProto.name

    override fun getTextOffset() = varDeclProto.textOffset

    override fun isViewable() = varDeclProto.isGlobal()

    override fun getPresentation(): ItemPresentation {
        val name = varDeclProto.name
        val type = varDeclProto.declTypeExpr?.text
        val text = if (name != null && type != null) "$name: $type" else name ?: type
        val mutability = if (varDeclProto.isConst()) AllIcons.Nodes.Constant else AllIcons.Nodes.Variable
        val visibility = if (isPub()) AllIcons.Nodes.Public else AllIcons.Nodes.Private
        val rowIcon = RowIcon(mutability, visibility)
        return PresentationData(text, null, rowIcon, null)
    }
}

class ZigFnDecl(node: ASTNode) : ZigPsiElement(node), ZigStructureElement {
    val fnProto: ZigFnProto
        get() = findNotNullChildByClass(ZigFnProto::class.java)

    val fnBody: ZigBlock?
        get() = findChildByClass(ZigBlock::class.java)

    fun isPub() = findChildByType<PsiElement?>(ZigTokenTypes.KEYWORD_PUB) != null

    override fun getName() = fnProto.name

    override fun getTextOffset() = fnProto.textOffset

    override fun getPresentation(): ItemPresentation {
        val name = fnProto.name
        val paramDecls = fnProto.fnParams?.fnParamDecls?.values
        val returnType = fnProto.fnReturnTypeExpr?.text
        val text = buildString {
            if (name != null) {
                append(name)
            }
            append('(')
            if (paramDecls != null) {
                var needComma = false
                for (param in paramDecls) {
                    if (needComma) {
                        append(", ")
                    }
                    val paramType = param.fnParamTypeExpr?.text
                    if (paramType != null) {
                        append(paramType)
                    }
                    needComma = true
                }
            }
            append(')')
            append(' ')
            if (fnProto.hasInferredError()) {
                append('!')
            }
            if (returnType != null) {
                append(returnType)
            }
        }
        val visibility = if (isPub()) AllIcons.Nodes.Public else AllIcons.Nodes.Private
        val rowIcon = RowIcon(AllIcons.Nodes.Function, visibility)
        return PresentationData(text, null, rowIcon, null)
    }
}

class ZigFnProto(node: ASTNode) : ZigNamedElement(node) {
    val fnParams: ZigFnParams?
        get() = findChildByClass(ZigFnParams::class.java)

    val fnReturnTypeExpr: ZigTypeExpr?
        get() = findChildByClass(ZigTypeExpr::class.java)

    fun hasInferredError() = findChildByType<PsiElement?>(ZigTokenTypes.BANG) != null
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
            for (paramDecl in childrenOfType<ZigFnParamDecl>(this)) {
                val name = paramDecl.name
                if (name != null) {
                    decls[name] = paramDecl
                }
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

class ZigContainerField(node: ASTNode) : ZigNamedElement(node), ZigStructureElement {
    val containerFieldTypeExpr: ZigTypeExpr?
        get() = findChildByClass(ZigTypeExpr::class.java)

    override fun getPresentation(): ItemPresentation {
        val name = name
        val type = containerFieldTypeExpr?.text
        val text = if (name != null && type != null) "$name: $type" else name ?: type
        return PresentationData(text, null, AllIcons.Nodes.Field, null)
    }
}

class ZigPayload(node: ASTNode) : ZigPsiElement(node) {
    private var cachedPayloadEntries: Map<String, ZigPayloadEntry>? = null

    val payloadEntries: Map<String, ZigPayloadEntry>
        get() {
            var entries = cachedPayloadEntries
            if (entries != null) {
                return entries
            }
            entries = mutableMapOf()
            for (entry in childrenOfType<ZigPayloadEntry>(this)) {
                val name = entry.name
                if (name != null) {
                    entries[name] = entry
                }
            }
            cachedPayloadEntries = entries
            return entries
        }

    override fun subtreeChanged() {
        super.subtreeChanged()
        cachedPayloadEntries = null
    }
}

class ZigPayloadEntry(node: ASTNode) : ZigNamedElement(node) {
    fun isPointer() = findChildByType<PsiElement?>(ZigTokenTypes.ASTERISK) != null
}

fun createZigFile(project: Project, text: String): ZigFile {
    val fileFactory = PsiFileFactory.getInstance(project)
    val file = fileFactory.createFileFromText("dummy.zig",ZigLanguage.INSTANCE, text)
    return file as ZigFile
}

fun createZigIdentifier(project: Project, name: String): ZigIdentifier {
    val file = createZigFile(project, "const dummy = $name;")
    val varDecl = file.firstChild as ZigVarDecl
    return varDecl.varDeclInitExpr?.expr as ZigIdentifier
}

fun createZigIdentifierToken(project: Project, name: String): PsiElement {
    val element = createZigIdentifier(project, name).firstChild
    assert(element.elementType == ZigTokenTypes.IDENTIFIER)
    return element
}

fun createZigStringLiteral(project: Project, value: String): ZigStringLiteral {
    // TODO: escape string literal
    val file = createZigFile(project, "const dummy = \"$value\";")
    val varDecl = file.firstChild as ZigVarDecl
    return varDecl.varDeclInitExpr?.expr as ZigStringLiteral
}

inline fun <reified T : PsiElement> childrenOfType(element: PsiElement): Sequence<T> {
    var nextChild = element.firstChild
    return generateSequence {
        while (nextChild != null) {
            val element = nextChild
            nextChild = nextChild.nextSibling
            if (element is T) {
                return@generateSequence element
            }
        }
        null
    }
}
