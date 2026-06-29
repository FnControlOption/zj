package io.github.fncontroloption.zig.psi2

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.PresentationData
import com.intellij.lang.ASTNode
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.util.elementType
import com.intellij.psi.util.siblings
import com.intellij.ui.RowIcon
import com.intellij.util.IncorrectOperationException
import io.github.fncontroloption.zig.ZigFileType
import io.github.fncontroloption.zig.ZigIdentifierReference
import io.github.fncontroloption.zig.ZigLanguage

sealed class ZigElement(node: ASTNode) : ASTWrapperPsiElement(node)

sealed interface ZigPayload : PsiElement {
    val items: Sequence<ZigPayloadItem>
        get() = firstChild.siblings().filterIsInstance<ZigPayloadItem>()
}

class ZigFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, ZigLanguage) {
    val members: Sequence<ZigElement>
        get() =
            firstChild
                ?.siblings()
                ?.mapNotNull { it as? ZigElement }
                ?: emptySequence()

    override fun getFileType() = ZigFileType
    override fun toString() = "ZigFile:$name"
}

class ZigAdd(node: ASTNode) : ZigElement(node) {
}

class ZigAddSat(node: ASTNode) : ZigElement(node) {
}

class ZigAddWrap(node: ASTNode) : ZigElement(node) {
}

class ZigAddressOf(node: ASTNode) : ZigElement(node) {
}

class ZigAddrspaceModifier(node: ASTNode) : ZigElement(node) {
}

class ZigAlignModifier(node: ASTNode) : ZigElement(node) {
}

class ZigAnyframeLiteral(node: ASTNode) : ZigElement(node) {
}

class ZigAnyframeType(node: ASTNode) : ZigElement(node) {
}

class ZigArrayAccess(node: ASTNode) : ZigElement(node) {
}

class ZigArrayCat(node: ASTNode) : ZigElement(node) {
}

class ZigArrayIndex(node: ASTNode) : ZigElement(node) {
}

class ZigArrayType(node: ASTNode) : ZigElement(node) {
}

class ZigAsm(node: ASTNode) : ZigElement(node) {
}

class ZigAsmClobbers(node: ASTNode) : ZigElement(node) {
}

class ZigAsmInputItem(node: ASTNode) : ZigElement(node) {
}

class ZigAsmOutputItem(node: ASTNode) : ZigElement(node) {
}

class ZigAssign(node: ASTNode) : ZigElement(node) {
}

class ZigAssignAdd(node: ASTNode) : ZigElement(node) {
}

class ZigAssignAddSat(node: ASTNode) : ZigElement(node) {
}

class ZigAssignAddWrap(node: ASTNode) : ZigElement(node) {
}

class ZigAssignBitAnd(node: ASTNode) : ZigElement(node) {
}

class ZigAssignBitOr(node: ASTNode) : ZigElement(node) {
}

class ZigAssignBitXor(node: ASTNode) : ZigElement(node) {
}

class ZigAssignDestructure(node: ASTNode) : ZigElement(node) {
    val variables: Sequence<ZigElement>
        get() =
            firstChild
                .siblings()
                .takeWhile { it.elementType !== ZigTokenType.EQUAL }
                .mapNotNull { it as? ZigElement }

    val equalTokenElement: PsiElement?
        get() = findChildByType(ZigTokenType.EQUAL)

    val initializer: ZigElement?
        get() =
            equalTokenElement
                ?.siblings(forward = true, withSelf = false)
                ?.firstNotNullOfOrNull { it as? ZigElement }
}

class ZigAssignDiv(node: ASTNode) : ZigElement(node) {
}

class ZigAssignMod(node: ASTNode) : ZigElement(node) {
}

class ZigAssignMul(node: ASTNode) : ZigElement(node) {
}

class ZigAssignMulSat(node: ASTNode) : ZigElement(node) {
}

class ZigAssignMulWrap(node: ASTNode) : ZigElement(node) {
}

class ZigAssignShl(node: ASTNode) : ZigElement(node) {
}

class ZigAssignShlSat(node: ASTNode) : ZigElement(node) {
}

class ZigAssignShr(node: ASTNode) : ZigElement(node) {
}

class ZigAssignSub(node: ASTNode) : ZigElement(node) {
}

class ZigAssignSubSat(node: ASTNode) : ZigElement(node) {
}

class ZigAssignSubWrap(node: ASTNode) : ZigElement(node) {
}

class ZigBangEqual(node: ASTNode) : ZigElement(node) {
}

class ZigBitAnd(node: ASTNode) : ZigElement(node) {
}

class ZigBitNot(node: ASTNode) : ZigElement(node) {
}

class ZigBitOr(node: ASTNode) : ZigElement(node) {
}

class ZigBitXor(node: ASTNode) : ZigElement(node) {
}

class ZigBlock(node: ASTNode) : ZigElement(node) {
    val statements: Sequence<ZigElement>
        get() =
            firstChild
                .siblings()
                .mapNotNull { it as? ZigElement }
                .filter { it !is ZigLabelDecl }
}

class ZigBoolAnd(node: ASTNode) : ZigElement(node) {
}

class ZigBoolNot(node: ASTNode) : ZigElement(node) {
}

class ZigBoolOr(node: ASTNode) : ZigElement(node) {
}

class ZigBreak(node: ASTNode) : ZigElement(node) {
}

class ZigBuiltinCall(node: ASTNode) : ZigElement(node) {
}

class ZigCall(node: ASTNode) : ZigElement(node) {
}

class ZigCallArg(node: ASTNode) : ZigElement(node) {
}

class ZigCallconvModifier(node: ASTNode) : ZigElement(node) {
}

class ZigCatch(node: ASTNode) : ZigElement(node) {
    val leftOperand: ZigElement?
        get() = firstChild as? ZigElement

    val payload: ZigCatchPayload?
        get() = findChildByClass(ZigCatchPayload::class.java)

    val rightOperand: ZigElement?
        get() = if (lastChild !is ZigCatchPayload) lastChild as? ZigElement else null
}

class ZigCatchPayload(node: ASTNode) : ZigElement(node), ZigPayload {
}

class ZigCharLiteral(node: ASTNode) : ZigElement(node) {
}

class ZigComptime(node: ASTNode) : ZigElement(node) {
}

class ZigConditionElement(node: ASTNode) : ZigElement(node) {
}

enum class ZigContainerKind { STRUCT, OPAQUE, ENUM, UNION }

class ZigContainerDecl(node: ASTNode) : ZigElement(node) {
    val containerKindElement: PsiElement
        get() = findNotNullChildByType(ZigTokenSets.CONTAINERS)

    val containerKind: ZigContainerKind
        get() = when (containerKindElement.elementType) {
            ZigTokenType.KEYWORD_STRUCT -> ZigContainerKind.STRUCT
            ZigTokenType.KEYWORD_OPAQUE -> ZigContainerKind.OPAQUE
            ZigTokenType.KEYWORD_ENUM -> ZigContainerKind.ENUM
            ZigTokenType.KEYWORD_UNION -> ZigContainerKind.UNION
            else -> throw IllegalStateException()
        }

    val members: Sequence<ZigElement>
        get() =
            firstChild
                .siblings()
                .mapNotNull { it as? ZigElement }
                .filter { it !is ZigContainerTag }

    override fun getPresentation(): ItemPresentation {
        val kind = containerKindElement.text
        return PresentationData(kind, null, AllIcons.Nodes.Type, null)
    }
}

class ZigContainerField(node: ASTNode) : ZigElement(node), PsiNameIdentifierOwner {
    val typeElement: ZigTypeElement?
        get() = findChildByClass(ZigTypeElement::class.java)

    override fun getNameIdentifier() = findChildByType<PsiElement?>(ZigTokenType.IDENTIFIER)
    override fun getName() = nameIdentifier?.text
    override fun setName(name: String) = replaceZigIdentifierElement(this, nameIdentifier, name)
    override fun getTextOffset() = nameIdentifier?.textOffset ?: super.getTextOffset()

    override fun getPresentation(): ItemPresentation {
        val name = nameIdentifier?.text
        val type = typeElement?.text
        val text = if (name != null && type != null) "$name: $type" else name ?: type
        return PresentationData(text, null, AllIcons.Nodes.Field, null)
    }
}

class ZigContainerTag(node: ASTNode) : ZigElement(node) {
}

class ZigContainerTagEnum(node: ASTNode) : ZigElement(node) {
}

class ZigContinue(node: ASTNode) : ZigElement(node) {
}

class ZigDefer(node: ASTNode) : ZigElement(node) {
}

class ZigDeref(node: ASTNode) : ZigElement(node) {
}

class ZigDiv(node: ASTNode) : ZigElement(node) {
}

class ZigElseElement(node: ASTNode) : ZigElement(node) {
    val payload: ZigElsePayload?
        get() = when (val parent = parent) {
            is ZigIf -> parent.elsePayload
            is ZigWhile -> parent.elsePayload
            else -> null
        }
}

class ZigElsePayload(node: ASTNode) : ZigElement(node), ZigPayload {
}

class ZigEnumLiteral(node: ASTNode) : ZigElement(node) {
    val identifierElement: PsiElement?
        get() = findChildByType(ZigTokenType.IDENTIFIER)
}

class ZigEqualEqual(node: ASTNode) : ZigElement(node) {
}

class ZigErrdefer(node: ASTNode) : ZigElement(node) {
}

class ZigErrorSetDecl(node: ASTNode) : ZigElement(node) {
}

class ZigErrorSetItem(node: ASTNode) : ZigElement(node) {
}

class ZigErrorUnion(node: ASTNode) : ZigElement(node) {
}

class ZigErrorValue(node: ASTNode) : ZigElement(node) {
}

class ZigFieldAccess(node: ASTNode) : ZigElement(node) {
}

class ZigFnDecl(node: ASTNode) : ZigElement(node), PsiNameIdentifierOwner {
    val pubTokenElement: PsiElement?
        get() = findChildByType(ZigTokenType.KEYWORD_PUB)

    val parameters: Sequence<ZigParamDecl>
        get() =
            firstChild
                .siblings()
                .mapNotNull { it as? ZigParamDecl }

    val ellipsisTokenElement: PsiElement?
        get() = findChildByType(ZigTokenType.ELLIPSIS3)

    val bangTokenElement: PsiElement?
        get() = findChildByType(ZigTokenType.BANG)

    val returnTypeElement: ZigTypeElement?
        get() = findChildByClass(ZigTypeElement::class.java)

    val body: ZigBlock?
        get() = findChildByClass(ZigBlock::class.java)

    override fun getNameIdentifier() = findChildByType<PsiElement?>(ZigTokenType.IDENTIFIER)
    override fun getName() = nameIdentifier?.text
    override fun setName(name: String) = replaceZigIdentifierElement(this, nameIdentifier, name)
    override fun getTextOffset() = nameIdentifier?.textOffset ?: super.getTextOffset()

    override fun getPresentation(): ItemPresentation {
        val text = buildString {
            val name = nameIdentifier?.text
            if (name != null) {
                append(name)
            }
            append('(')
            var needComma = false
            for (paramDecl in parameters) {
                if (needComma) {
                    append(", ")
                }
                val paramType = paramDecl.typeElement?.text
                if (paramType != null) {
                    append(paramType)
                }
                needComma = true
            }
            if (ellipsisTokenElement != null) {
                if (needComma) {
                    append(", ")
                }
                append("...")
            }
            append(')')
            append(' ')
            if (bangTokenElement != null) {
                append('!')
            }
            val returnType = returnTypeElement?.text
            if (returnType != null) {
                append(returnType)
            }
        }
        val visibility = if (pubTokenElement != null) AllIcons.Nodes.Public else AllIcons.Nodes.Private
        val rowIcon = RowIcon(AllIcons.Nodes.Function, visibility)
        return PresentationData(text, null, rowIcon, null)
    }
}

class ZigFnProto(node: ASTNode) : ZigElement(node) {
}

class ZigFor(node: ASTNode) : ZigElement(node) {
    val payload: ZigForPayload?
        get() = findChildByClass(ZigForPayload::class.java)
}

class ZigForInput(node: ASTNode) : ZigElement(node) {
}

class ZigForPayload(node: ASTNode) : ZigElement(node), ZigPayload {
}

class ZigGreaterOrEqual(node: ASTNode) : ZigElement(node) {
}

class ZigGreaterThan(node: ASTNode) : ZigElement(node) {
}

class ZigGroupedExpression(node: ASTNode) : ZigElement(node) {
}

class ZigIdentifier(node: ASTNode) : ZigElement(node) {
    val identifierElement: PsiElement?
        get() = findChildByType(ZigTokenType.IDENTIFIER)

    override fun getReferences() = arrayOf(ZigIdentifierReference(this))
}

class ZigIf(node: ASTNode) : ZigElement(node) {
    val thenPayload: ZigThenPayload?
        get() = findChildByClass(ZigThenPayload::class.java)

    val elsePayload: ZigElsePayload?
        get() = findChildByClass(ZigElsePayload::class.java)
}

class ZigLabelDecl(node: ASTNode) : ZigElement(node) {
}

class ZigLabelRef(node: ASTNode) : ZigElement(node) {
}

class ZigLessOrEqual(node: ASTNode) : ZigElement(node) {
}

class ZigLessThan(node: ASTNode) : ZigElement(node) {
}

class ZigLinksectionModifier(node: ASTNode) : ZigElement(node) {
}

class ZigListInit(node: ASTNode) : ZigElement(node) {
}

class ZigListInitItem(node: ASTNode) : ZigElement(node) {
}

class ZigMergeErrorSets(node: ASTNode) : ZigElement(node) {
}

class ZigMod(node: ASTNode) : ZigElement(node) {
}

class ZigMul(node: ASTNode) : ZigElement(node) {
}

class ZigMulSat(node: ASTNode) : ZigElement(node) {
}

class ZigMulWrap(node: ASTNode) : ZigElement(node) {
}

class ZigMultilineStringLiteral(node: ASTNode) : ZigElement(node) {
}

class ZigNegation(node: ASTNode) : ZigElement(node) {
}

class ZigNegationWrap(node: ASTNode) : ZigElement(node) {
}

class ZigNosuspend(node: ASTNode) : ZigElement(node) {
}

class ZigNumberLiteral(node: ASTNode) : ZigElement(node) {
}

class ZigOptionalType(node: ASTNode) : ZigElement(node) {
}

class ZigOrelse(node: ASTNode) : ZigElement(node) {
}

class ZigParamDecl(node: ASTNode) : ZigElement(node), PsiNameIdentifierOwner {
    val typeElement: ZigTypeElement?
        get() = findChildByClass(ZigTypeElement::class.java)

    override fun getNameIdentifier() = findChildByType<PsiElement?>(ZigTokenType.IDENTIFIER)
    override fun getName() = nameIdentifier?.text
    override fun setName(name: String) = replaceZigIdentifierElement(this, nameIdentifier, name)
    override fun getTextOffset() = nameIdentifier?.textOffset ?: super.getTextOffset()
}

class ZigPayloadItem(node: ASTNode) : ZigElement(node), PsiNameIdentifierOwner {
    override fun getNameIdentifier() = findChildByType<PsiElement?>(ZigTokenType.IDENTIFIER)
    override fun getName() = nameIdentifier?.text
    override fun setName(name: String) = replaceZigIdentifierElement(this, nameIdentifier, name)
    override fun getTextOffset() = nameIdentifier?.textOffset ?: super.getTextOffset()
}

class ZigPointerType(node: ASTNode) : ZigElement(node) {
}

class ZigResume(node: ASTNode) : ZigElement(node) {
}

class ZigReturn(node: ASTNode) : ZigElement(node) {
}

class ZigShl(node: ASTNode) : ZigElement(node) {
}

class ZigShlSat(node: ASTNode) : ZigElement(node) {
}

class ZigShr(node: ASTNode) : ZigElement(node) {
}

class ZigSlice(node: ASTNode) : ZigElement(node) {
}

class ZigSliceIndexes(node: ASTNode) : ZigElement(node) {
}

class ZigSliceSentinel(node: ASTNode) : ZigElement(node) {
}

class ZigStringLiteral(node: ASTNode) : ZigElement(node) {
}

class ZigSub(node: ASTNode) : ZigElement(node) {
}

class ZigSubSat(node: ASTNode) : ZigElement(node) {
}

class ZigSubWrap(node: ASTNode) : ZigElement(node) {
}

class ZigSuspend(node: ASTNode) : ZigElement(node) {
}

class ZigSwitch(node: ASTNode) : ZigElement(node) {
}

class ZigSwitchItem(node: ASTNode) : ZigElement(node) {
}

class ZigSwitchPayload(node: ASTNode) : ZigElement(node), ZigPayload {
}

class ZigSwitchProng(node: ASTNode) : ZigElement(node) {
    val payload: ZigSwitchPayload?
        get() = findChildByClass(ZigSwitchPayload::class.java)
}

class ZigTestDecl(node: ASTNode) : ZigElement(node) {
    val nameElement: PsiElement?
        get() = findChildByType(ZigTokenSets.TEST_NAME)

    override fun getPresentation(): ItemPresentation {
        val name = nameElement?.text
        val text = if (name != null) "test $name" else "test"
        return PresentationData(text, null, AllIcons.Nodes.Test, null)
    }
}

class ZigThenElement(node: ASTNode) : ZigElement(node) {
    val payload: ZigPayload?
        get() = when (val parent = parent) {
            is ZigIf -> parent.thenPayload
            is ZigWhile -> parent.thenPayload
            is ZigFor -> parent.payload
            else -> null
        }
}

class ZigThenPayload(node: ASTNode) : ZigElement(node), ZigPayload {
}

class ZigTry(node: ASTNode) : ZigElement(node) {
}

class ZigTypeElement(node: ASTNode) : ZigElement(node) {
    val innerElement: ZigElement?
        get() = findChildByClass(ZigElement::class.java)
}

class ZigTypeLength(node: ASTNode) : ZigElement(node) {
}

class ZigTypeSentinel(node: ASTNode) : ZigElement(node) {
}

class ZigUnreachableLiteral(node: ASTNode) : ZigElement(node) {
}

class ZigUnwrapOptional(node: ASTNode) : ZigElement(node) {
}

enum class ZigMutability { CONST, VAR }

interface ZigVarDeclOrProto : PsiNameIdentifierOwner {
    val mutabilityElement: PsiElement

    val mutability: ZigMutability
        get() = when (mutabilityElement.elementType) {
            ZigTokenType.KEYWORD_CONST -> ZigMutability.CONST
            ZigTokenType.KEYWORD_VAR -> ZigMutability.VAR
            else -> throw IllegalStateException()
        }

    val typeElement: ZigTypeElement?
}

class ZigVarDecl(node: ASTNode) : ZigElement(node), ZigVarDeclOrProto {
    val pubTokenElement: PsiElement?
        get() = findChildByType(ZigTokenType.KEYWORD_PUB)

    override val mutabilityElement: PsiElement
        get() = findNotNullChildByType(ZigTokenSets.MUT_TOKENS)

    override val typeElement: ZigTypeElement?
        get() = findChildByClass(ZigTypeElement::class.java)

    val equalTokenElement: PsiElement?
        get() = findChildByType(ZigTokenType.EQUAL)

    val initializer: ZigElement?
        get() =
            equalTokenElement
                ?.siblings(forward = true, withSelf = false)
                ?.firstNotNullOfOrNull { it as? ZigElement }

    override fun getNameIdentifier() = findChildByType<PsiElement?>(ZigTokenType.IDENTIFIER)
    override fun getName() = nameIdentifier?.text
    override fun setName(name: String) = replaceZigIdentifierElement(this, nameIdentifier, name)
    override fun getTextOffset() = nameIdentifier?.textOffset ?: super.getTextOffset()

    override fun getPresentation(): ItemPresentation {
        val name = nameIdentifier?.text
        val type = typeElement?.text
        val text = if (name != null && type != null) "$name: $type" else name ?: type
        val mutability = if (mutability == ZigMutability.CONST) AllIcons.Nodes.Constant else AllIcons.Nodes.Variable
        val visibility = if (pubTokenElement != null) AllIcons.Nodes.Public else AllIcons.Nodes.Private
        val rowIcon = RowIcon(mutability, visibility)
        return PresentationData(text, null, rowIcon, null)
    }
}

class ZigVarProto(node: ASTNode) : ZigElement(node), ZigVarDeclOrProto {
    override val mutabilityElement: PsiElement
        get() = findNotNullChildByType(ZigTokenSets.MUT_TOKENS)

    override val typeElement: ZigTypeElement?
        get() = findChildByClass(ZigTypeElement::class.java)

    override fun getNameIdentifier() = findChildByType<PsiElement?>(ZigTokenType.IDENTIFIER)
    override fun getName() = nameIdentifier?.text
    override fun setName(name: String) = replaceZigIdentifierElement(this, nameIdentifier, name)
    override fun getTextOffset() = nameIdentifier?.textOffset ?: super.getTextOffset()
}

class ZigWhile(node: ASTNode) : ZigElement(node) {
    val thenPayload: ZigThenPayload?
        get() = findChildByClass(ZigThenPayload::class.java)

    val elsePayload: ZigElsePayload?
        get() = findChildByClass(ZigElsePayload::class.java)
}

class ZigWhileContinueExpr(node: ASTNode) : ZigElement(node) {
}

fun createZigFile(project: Project, text: String): ZigFile {
    val fileFactory = PsiFileFactory.getInstance(project)
    val file = fileFactory.createFileFromText("dummy.zig",ZigLanguage, text)
    return file as ZigFile
}

fun createZigIdentifier(project: Project, name: String): ZigIdentifier {
    val file = createZigFile(project, "const dummy = $name;")
    val varDecl = file.members.first() as ZigVarDecl
    return varDecl.initializer as ZigIdentifier
}

fun createZigIdentifierElement(project: Project, name: String): PsiElement {
    val identifier = createZigIdentifier(project, name)
    val element = identifier.identifierElement!!
    assert(element.elementType == ZigTokenType.IDENTIFIER)
    return element
}

fun replaceZigIdentifierElement(parent: PsiElement, oldElement: PsiElement?, newName: String): PsiElement {
    if (oldElement == null) {
        throw IncorrectOperationException()
    }
    val newElement = createZigIdentifierElement(parent.project, newName)
    oldElement.replace(newElement)
    return parent
}
