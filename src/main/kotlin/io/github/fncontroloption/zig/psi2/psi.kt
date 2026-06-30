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
import com.intellij.psi.PsiReference
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.elementType
import com.intellij.psi.util.siblings
import com.intellij.ui.RowIcon
import com.intellij.util.IncorrectOperationException
import io.github.fncontroloption.zig.ZigFileType
import io.github.fncontroloption.zig.ZigIdentifierReference
import io.github.fncontroloption.zig.ZigLabelReference
import io.github.fncontroloption.zig.ZigLanguage
import io.github.fncontroloption.zig.ZigTestReference

sealed interface ZigElement : PsiElement

sealed interface ZigUnaryExpression : ZigElement {
    val operand: ZigElement?
        get() = firstChild.siblings().firstNotNullOfOrNull { it as? ZigElement }
}

sealed interface ZigPrefixExpression : ZigUnaryExpression

sealed interface ZigPostfixExpression : ZigUnaryExpression

sealed interface ZigBinaryExpression : ZigElement {
    val leftOperand: ZigElement
        get() =
            firstChild
                .siblings()
                .takeWhile { it.elementType !== mainTokenType }
                .firstNotNullOf { it as? ZigElement }

    val mainTokenType: IElementType

    val mainTokenElement: PsiElement
        get() = firstChild.siblings().first { it.elementType === mainTokenType }

    val rightOperand: ZigElement?
        get() =
            lastChild
                .siblings(forward = false)
                .takeWhile { it.elementType !== mainTokenType }
                .firstNotNullOfOrNull { it as? ZigElement }
}

sealed interface ZigAssignElement : ZigElement {
    val leftElement: ZigElement
        get() =
            firstChild
                .siblings()
                .takeWhile { it.elementType !== mainTokenType }
                .firstNotNullOf { it as? ZigElement }

    val mainTokenType: IElementType

    val mainTokenElement: PsiElement
        get() = firstChild.siblings().first { it.elementType === mainTokenType }

    val rightElement: ZigElement?
        get() =
            lastChild
                .siblings(forward = false)
                .takeWhile { it.elementType !== mainTokenType }
                .firstNotNullOfOrNull { it as? ZigElement }
}

sealed interface ZigLabelable : ZigElement {
    val labelDecl: ZigLabelDecl?
        get() = firstChild.siblings().firstNotNullOfOrNull { it as? ZigLabelDecl }
}

sealed interface ZigModifier : ZigElement {
    val expression: ZigElement?
        get() = firstChild.siblings().firstNotNullOfOrNull { it as? ZigElement }
}

sealed interface ZigPayload : ZigElement {
    val items: Sequence<ZigPayloadItem>
        get() = firstChild.siblings().filterIsInstance<ZigPayloadItem>()
}

enum class ZigContainerKind { STRUCT, OPAQUE, ENUM, UNION }

sealed interface ZigContainer : ZigElement {
    val containerKind: ZigContainerKind

    val members: Sequence<ZigElement>
}

class ZigFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, ZigLanguage), ZigContainer {
    override val containerKind = ZigContainerKind.STRUCT

    override val members: Sequence<ZigElement>
        get() = firstChild?.siblings()?.filterIsInstance<ZigElement>() ?: emptySequence()

    override fun getFileType() = ZigFileType
    override fun toString() = "ZigFile:$name"
}

class ZigAdd(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: IElementType
        get() = ZigTokenType.PLUS
}

class ZigAddSat(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: IElementType
        get() = ZigTokenType.PLUS_PIPE
}

class ZigAddWrap(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: IElementType
        get() = ZigTokenType.PLUS_PERCENT
}

class ZigAddressOf(node: ASTNode) : ASTWrapperPsiElement(node), ZigPrefixExpression

class ZigAddrspaceModifier(node: ASTNode) : ASTWrapperPsiElement(node), ZigModifier

class ZigAlignModifier(node: ASTNode) : ASTWrapperPsiElement(node), ZigModifier

class ZigAnyframeLiteral(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigAnyframeType(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigArrayAccess(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigArrayCat(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: IElementType
        get() = ZigTokenType.PLUS_PLUS
}

class ZigArrayIndex(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigArrayType(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigAsm(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigAsmClobbers(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigAsmInputItem(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigAsmOutputItem(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigAssign(node: ASTNode) : ASTWrapperPsiElement(node), ZigAssignElement {
    override val mainTokenType: IElementType
        get() = ZigTokenType.EQUAL
}

class ZigAssignAdd(node: ASTNode) : ASTWrapperPsiElement(node), ZigAssignElement {
    override val mainTokenType: IElementType
        get() = ZigTokenType.PLUS_EQUAL
}

class ZigAssignAddSat(node: ASTNode) : ASTWrapperPsiElement(node), ZigAssignElement {
    override val mainTokenType: IElementType
        get() = ZigTokenType.PLUS_PIPE_EQUAL
}

class ZigAssignAddWrap(node: ASTNode) : ASTWrapperPsiElement(node), ZigAssignElement {
    override val mainTokenType: IElementType
        get() = ZigTokenType.PLUS_PERCENT_EQUAL
}

class ZigAssignBitAnd(node: ASTNode) : ASTWrapperPsiElement(node), ZigAssignElement {
    override val mainTokenType: IElementType
        get() = ZigTokenType.AMPERSAND_EQUAL
}

class ZigAssignBitOr(node: ASTNode) : ASTWrapperPsiElement(node), ZigAssignElement {
    override val mainTokenType: IElementType
        get() = ZigTokenType.PIPE_EQUAL
}

class ZigAssignBitXor(node: ASTNode) : ASTWrapperPsiElement(node), ZigAssignElement {
    override val mainTokenType: IElementType
        get() = ZigTokenType.CARET_EQUAL
}

class ZigAssignDestructure(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
    val variables: Sequence<ZigElement>
        get() =
            firstChild
                .siblings()
                .takeWhile { it.elementType !== ZigTokenType.EQUAL }
                .filterIsInstance<ZigElement>()

    val equalTokenElement: PsiElement?
        get() = findChildByType(ZigTokenType.EQUAL)

    val initializer: ZigElement?
        get() =
            equalTokenElement
                ?.siblings(forward = true, withSelf = false)
                ?.firstNotNullOfOrNull { it as? ZigElement }
}

class ZigAssignDiv(node: ASTNode) : ASTWrapperPsiElement(node), ZigAssignElement {
    override val mainTokenType: IElementType
        get() = ZigTokenType.SLASH_EQUAL
}

class ZigAssignMod(node: ASTNode) : ASTWrapperPsiElement(node), ZigAssignElement {
    override val mainTokenType: IElementType
        get() = ZigTokenType.PERCENT_EQUAL
}

class ZigAssignMul(node: ASTNode) : ASTWrapperPsiElement(node), ZigAssignElement {
    override val mainTokenType: IElementType
        get() = ZigTokenType.ASTERISK_EQUAL
}

class ZigAssignMulSat(node: ASTNode) : ASTWrapperPsiElement(node), ZigAssignElement {
    override val mainTokenType: IElementType
        get() = ZigTokenType.ASTERISK_PIPE_EQUAL
}

class ZigAssignMulWrap(node: ASTNode) : ASTWrapperPsiElement(node), ZigAssignElement {
    override val mainTokenType: IElementType
        get() = ZigTokenType.ASTERISK_PERCENT_EQUAL
}

class ZigAssignShl(node: ASTNode) : ASTWrapperPsiElement(node), ZigAssignElement {
    override val mainTokenType: IElementType
        get() = ZigTokenType.ANGLE_BRACKET_ANGLE_BRACKET_LEFT_EQUAL
}

class ZigAssignShlSat(node: ASTNode) : ASTWrapperPsiElement(node), ZigAssignElement {
    override val mainTokenType: IElementType
        get() = ZigTokenType.ANGLE_BRACKET_ANGLE_BRACKET_LEFT_PIPE_EQUAL
}

class ZigAssignShr(node: ASTNode) : ASTWrapperPsiElement(node), ZigAssignElement {
    override val mainTokenType: IElementType
        get() = ZigTokenType.ANGLE_BRACKET_ANGLE_BRACKET_RIGHT_EQUAL
}

class ZigAssignSub(node: ASTNode) : ASTWrapperPsiElement(node), ZigAssignElement {
    override val mainTokenType: IElementType
        get() = ZigTokenType.MINUS_EQUAL
}

class ZigAssignSubSat(node: ASTNode) : ASTWrapperPsiElement(node), ZigAssignElement {
    override val mainTokenType: IElementType
        get() = ZigTokenType.MINUS_PIPE_EQUAL
}

class ZigAssignSubWrap(node: ASTNode) : ASTWrapperPsiElement(node), ZigAssignElement {
    override val mainTokenType: IElementType
        get() = ZigTokenType.MINUS_PERCENT_EQUAL
}

class ZigBangEqual(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: IElementType
        get() = ZigTokenType.BANG_EQUAL
}

class ZigBitAnd(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: IElementType
        get() = ZigTokenType.AMPERSAND
}

class ZigBitNot(node: ASTNode) : ASTWrapperPsiElement(node), ZigPrefixExpression

class ZigBitOr(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: IElementType
        get() = ZigTokenType.PIPE
}

class ZigBitXor(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: IElementType
        get() = ZigTokenType.CARET
}

class ZigBlock(node: ASTNode) : ASTWrapperPsiElement(node), ZigLabelable {
    val statements: Sequence<ZigElement>
        get() =
            firstChild
                .siblings()
                .filterIsInstance<ZigElement>()
                .filter { it !is ZigLabelDecl }
}

class ZigBoolAnd(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: IElementType
        get() = ZigTokenType.KEYWORD_AND
}

class ZigBoolNot(node: ASTNode) : ASTWrapperPsiElement(node), ZigPrefixExpression

class ZigBoolOr(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: IElementType
        get() = ZigTokenType.KEYWORD_OR
}

class ZigBreak(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigBuiltinCall(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigCall(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigCallArg(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigCallconvModifier(node: ASTNode) : ASTWrapperPsiElement(node), ZigModifier

class ZigCatch(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: IElementType
        get() = ZigTokenType.KEYWORD_CATCH

    val payload: ZigCatchPayload?
        get() = findChildByClass(ZigCatchPayload::class.java)

    override val rightOperand: ZigElement?
        get() =
            mainTokenElement
                .siblings(forward = true, withSelf = false)
                .firstNotNullOfOrNull { if (lastChild !is ZigCatchPayload) lastChild as? ZigElement else null }
}

class ZigCatchPayload(node: ASTNode) : ASTWrapperPsiElement(node), ZigPayload {
}

class ZigCharLiteral(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigComptime(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigConditionElement(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigContainerDecl(node: ASTNode) : ASTWrapperPsiElement(node), ZigContainer {
    val containerKindElement: PsiElement
        get() = findNotNullChildByType(ZigTokenSets.CONTAINERS)

    override val containerKind: ZigContainerKind
        get() = when (containerKindElement.elementType) {
            ZigTokenType.KEYWORD_STRUCT -> ZigContainerKind.STRUCT
            ZigTokenType.KEYWORD_OPAQUE -> ZigContainerKind.OPAQUE
            ZigTokenType.KEYWORD_ENUM -> ZigContainerKind.ENUM
            ZigTokenType.KEYWORD_UNION -> ZigContainerKind.UNION
            else -> throw IllegalStateException()
        }

    override val members: Sequence<ZigElement>
        get() =
            firstChild
                .siblings()
                .filterIsInstance<ZigElement>()
                .filter { it !is ZigContainerTag }

    override fun getPresentation(): ItemPresentation {
        val kind = containerKindElement.text
        return PresentationData(kind, null, AllIcons.Nodes.Type, null)
    }
}

class ZigContainerField(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement, PsiNameIdentifierOwner {
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

class ZigContainerTag(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigContainerTagEnum(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigContinue(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigDefer(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigDeref(node: ASTNode) : ASTWrapperPsiElement(node), ZigPostfixExpression

class ZigDiv(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: IElementType
        get() = ZigTokenType.SLASH
}

class ZigElseElement(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
    val payload: ZigElsePayload?
        get() = when (val parent = parent) {
            is ZigIf -> parent.elsePayload
            is ZigWhile -> parent.elsePayload
            else -> null
        }

    val innerElement: ZigElement?
        get() = findChildByClass(ZigElement::class.java)
}

class ZigElsePayload(node: ASTNode) : ASTWrapperPsiElement(node), ZigPayload {
}

class ZigEnumLiteral(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
    val identifierElement: PsiElement?
        get() = findChildByType(ZigTokenType.IDENTIFIER)
}

class ZigEqualEqual(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: IElementType
        get() = ZigTokenType.EQUAL_EQUAL
}

class ZigErrdefer(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigErrorSetDecl(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigErrorSetItem(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigErrorUnion(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigErrorValue(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigFieldAccess(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
    val leftOperand: ZigElement
        get() =
            firstChild
                .siblings()
                .takeWhile { it.elementType !== ZigTokenType.PERIOD }
                .firstNotNullOf { it as? ZigElement }
}

class ZigFnDecl(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement, PsiNameIdentifierOwner {
    val pubTokenElement: PsiElement?
        get() = findChildByType(ZigTokenType.KEYWORD_PUB)

    val parameters: Sequence<ZigParamDecl>
        get() = firstChild.siblings().filterIsInstance<ZigParamDecl>()

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

class ZigFnProto(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigFor(node: ASTNode) : ASTWrapperPsiElement(node), ZigLabelable {
    val payload: ZigForPayload?
        get() = findChildByClass(ZigForPayload::class.java)
}

class ZigForInput(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigForPayload(node: ASTNode) : ASTWrapperPsiElement(node), ZigPayload {
}

class ZigGreaterOrEqual(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: IElementType
        get() = ZigTokenType.ANGLE_BRACKET_RIGHT_EQUAL
}

class ZigGreaterThan(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: IElementType
        get() = ZigTokenType.ANGLE_BRACKET_RIGHT
}

class ZigGroupedExpression(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigIdentifier(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
    val identifierElement: PsiElement?
        get() = findChildByType(ZigTokenType.IDENTIFIER)

    override fun getReferences() = arrayOf(ZigIdentifierReference(this))
}

class ZigIf(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
    val thenPayload: ZigThenPayload?
        get() = findChildByClass(ZigThenPayload::class.java)

    val elsePayload: ZigElsePayload?
        get() = findChildByClass(ZigElsePayload::class.java)
}

class ZigLabelDecl(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement, PsiNameIdentifierOwner {
    val identifierElement: PsiElement
        get() = findNotNullChildByType(ZigTokenType.IDENTIFIER)

    override fun getNameIdentifier() = identifierElement
    override fun getName() = identifierElement.text!!
    override fun setName(name: String) = replaceZigIdentifierElement(this, identifierElement, name)
    override fun getTextOffset() = identifierElement.textOffset
}

class ZigLabelRef(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
    val identifierElement: PsiElement
        get() = findNotNullChildByType(ZigTokenType.IDENTIFIER)

    override fun getReferences() = arrayOf(ZigLabelReference(this))
}

class ZigLessOrEqual(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: IElementType
        get() = ZigTokenType.ANGLE_BRACKET_LEFT_EQUAL
}

class ZigLessThan(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: IElementType
        get() = ZigTokenType.ANGLE_BRACKET_LEFT
}

class ZigLinksectionModifier(node: ASTNode) : ASTWrapperPsiElement(node), ZigModifier

class ZigListInit(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigListInitItem(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigMergeErrorSets(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: IElementType
        get() = ZigTokenType.PIPE_PIPE
}

class ZigMod(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: IElementType
        get() = ZigTokenType.PERCENT
}

class ZigMul(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: IElementType
        get() = ZigTokenType.ASTERISK
}

class ZigMulSat(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: IElementType
        get() = ZigTokenType.ASTERISK_PIPE
}

class ZigMulWrap(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: IElementType
        get() = ZigTokenType.ASTERISK_PERCENT
}

class ZigMultilineStringLiteral(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigNegation(node: ASTNode) : ASTWrapperPsiElement(node), ZigPrefixExpression

class ZigNegationWrap(node: ASTNode) : ASTWrapperPsiElement(node), ZigPrefixExpression

class ZigNosuspend(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigNumberLiteral(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigOptionalType(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigOrelse(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: IElementType
        get() = ZigTokenType.KEYWORD_ORELSE
}

class ZigParamDecl(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement, PsiNameIdentifierOwner {
    val typeElement: ZigTypeElement?
        get() = findChildByClass(ZigTypeElement::class.java)

    override fun getNameIdentifier() = findChildByType<PsiElement?>(ZigTokenType.IDENTIFIER)
    override fun getName() = nameIdentifier?.text
    override fun setName(name: String) = replaceZigIdentifierElement(this, nameIdentifier, name)
    override fun getTextOffset() = nameIdentifier?.textOffset ?: super.getTextOffset()
}

class ZigPayloadItem(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement, PsiNameIdentifierOwner {
    override fun getNameIdentifier() = findChildByType<PsiElement?>(ZigTokenType.IDENTIFIER)
    override fun getName() = nameIdentifier?.text
    override fun setName(name: String) = replaceZigIdentifierElement(this, nameIdentifier, name)
    override fun getTextOffset() = nameIdentifier?.textOffset ?: super.getTextOffset()
}

class ZigPointerType(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigResume(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigReturn(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigShl(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: IElementType
        get() = ZigTokenType.ANGLE_BRACKET_ANGLE_BRACKET_LEFT
}

class ZigShlSat(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: IElementType
        get() = ZigTokenType.ANGLE_BRACKET_ANGLE_BRACKET_LEFT_PIPE
}

class ZigShr(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: IElementType
        get() = ZigTokenType.ANGLE_BRACKET_ANGLE_BRACKET_RIGHT
}

class ZigSlice(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigSliceIndexes(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigSliceSentinel(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigStringLiteral(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigSub(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: IElementType
        get() = ZigTokenType.MINUS
}

class ZigSubSat(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: IElementType
        get() = ZigTokenType.MINUS_PIPE
}

class ZigSubWrap(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: IElementType
        get() = ZigTokenType.MINUS_PERCENT
}

class ZigSuspend(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigSwitch(node: ASTNode) : ASTWrapperPsiElement(node), ZigLabelable {
}

class ZigSwitchItem(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigSwitchPayload(node: ASTNode) : ASTWrapperPsiElement(node), ZigPayload {
}

class ZigSwitchProng(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
    val payload: ZigSwitchPayload?
        get() = findChildByClass(ZigSwitchPayload::class.java)
}

class ZigTestDecl(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
    val nameElement: PsiElement?
        get() = findChildByType(ZigTokenSets.TEST_NAME)

    override fun getPresentation(): ItemPresentation {
        val name = nameElement?.text
        val text = if (name != null) "test $name" else "test"
        return PresentationData(text, null, AllIcons.Nodes.Test, null)
    }

    override fun getReferences(): Array<out PsiReference> {
        val name = nameElement
        if (name?.elementType === ZigTokenType.IDENTIFIER) {
            return arrayOf(ZigTestReference(this, name))
        }
        return emptyArray()
    }
}

class ZigThenElement(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
    val payload: ZigPayload?
        get() = when (val parent = parent) {
            is ZigIf -> parent.thenPayload
            is ZigWhile -> parent.thenPayload
            is ZigFor -> parent.payload
            else -> null
        }

    val innerElement: ZigElement?
        get() = findChildByClass(ZigElement::class.java)
}

class ZigThenPayload(node: ASTNode) : ASTWrapperPsiElement(node), ZigPayload {
}

class ZigTry(node: ASTNode) : ASTWrapperPsiElement(node), ZigPrefixExpression

class ZigTypeElement(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
    val innerElement: ZigElement?
        get() = findChildByClass(ZigElement::class.java)
}

class ZigTypeLength(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigTypeSentinel(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigUnreachableLiteral(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigUnwrapOptional(node: ASTNode) : ASTWrapperPsiElement(node), ZigPostfixExpression

enum class ZigMutability { CONST, VAR }

sealed interface ZigVarDeclOrProto : ZigElement, PsiNameIdentifierOwner {
    val mutabilityElement: PsiElement
        get() = firstChild.siblings().first { it.elementType in ZigTokenSets.MUT_TOKENS }

    val mutability: ZigMutability
        get() = when (mutabilityElement.elementType) {
            ZigTokenType.KEYWORD_CONST -> ZigMutability.CONST
            ZigTokenType.KEYWORD_VAR -> ZigMutability.VAR
            else -> throw IllegalStateException()
        }

    val typeElement: ZigTypeElement?
        get() = firstChild.siblings().firstNotNullOfOrNull { it as? ZigTypeElement }
}

class ZigVarDecl(node: ASTNode) : ASTWrapperPsiElement(node), ZigVarDeclOrProto {
    val pubTokenElement: PsiElement?
        get() = findChildByType(ZigTokenType.KEYWORD_PUB)

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

class ZigVarProto(node: ASTNode) : ASTWrapperPsiElement(node), ZigVarDeclOrProto {
    override fun getNameIdentifier() = findChildByType<PsiElement?>(ZigTokenType.IDENTIFIER)
    override fun getName() = nameIdentifier?.text
    override fun setName(name: String) = replaceZigIdentifierElement(this, nameIdentifier, name)
    override fun getTextOffset() = nameIdentifier?.textOffset ?: super.getTextOffset()
}

class ZigWhile(node: ASTNode) : ASTWrapperPsiElement(node), ZigLabelable {
    val thenPayload: ZigThenPayload?
        get() = findChildByClass(ZigThenPayload::class.java)

    val elsePayload: ZigElsePayload?
        get() = findChildByClass(ZigElsePayload::class.java)
}

class ZigWhileContinueExpr(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
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
