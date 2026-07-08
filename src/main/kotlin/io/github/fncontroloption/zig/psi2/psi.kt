package io.github.fncontroloption.zig.psi2

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.extapi.psi.StubBasedPsiElementBase
import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.PresentationData
import com.intellij.lang.ASTNode
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Iconable
import com.intellij.psi.FileViewProvider
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiReference
import com.intellij.psi.StubBasedPsiElement
import com.intellij.psi.stubs.StubElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.elementType
import com.intellij.psi.util.findParentInFile
import com.intellij.psi.util.siblings
import com.intellij.ui.IconManager
import com.intellij.util.BitUtil
import com.intellij.util.IncorrectOperationException
import io.github.fncontroloption.zig.ZigContainerFieldStub
import io.github.fncontroloption.zig.ZigFieldAccessReference
import io.github.fncontroloption.zig.ZigFileType
import io.github.fncontroloption.zig.ZigFnDeclStub
import io.github.fncontroloption.zig.ZigIdentifierReference
import io.github.fncontroloption.zig.ZigImportReference
import io.github.fncontroloption.zig.ZigLabelReference
import io.github.fncontroloption.zig.ZigLanguage
import io.github.fncontroloption.zig.ZigTestReference
import io.github.fncontroloption.zig.ZigVarDeclStub
import java.util.regex.Pattern
import javax.swing.Icon

sealed interface ZigElement : PsiElement

@Suppress("UnstableApiUsage")
sealed class ZigStubPsiElement<T : StubElement<*>> : StubBasedPsiElementBase<T>, StubBasedPsiElement<T>, ZigElement {
    constructor(stub: T, nodeType: IElementType) : super(stub, nodeType)
    constructor(node: ASTNode) : super(node)
    override fun getLanguage() = ZigLanguage
    override fun getIElementType() = elementTypeImpl
    override fun toString() = "${javaClass.simpleName}(${elementTypeImpl})"
}

sealed interface ZigNamedElement : ZigElement, PsiNameIdentifierOwner, NavigatablePsiElement

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
    override val mainTokenType = ZigTokenType.PLUS
}

class ZigAddSat(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType = ZigTokenType.PLUS_PIPE
}

class ZigAddWrap(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType = ZigTokenType.PLUS_PERCENT
}

class ZigAddressOf(node: ASTNode) : ASTWrapperPsiElement(node), ZigPrefixExpression

class ZigAddrspaceModifier(node: ASTNode) : ASTWrapperPsiElement(node), ZigModifier

class ZigAlignModifier(node: ASTNode) : ASTWrapperPsiElement(node), ZigModifier

class ZigAnyframeLiteral(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigAnyframeType(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigArrayAccess(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
    val leftOperand: ZigElement
        get() =
            firstChild
                .siblings()
                .takeWhile { it.elementType !== ZigTokenType.L_BRACKET }
                .firstNotNullOf { it as? ZigElement }

    val indexElement: ZigArrayIndex?
        get() =
            lastChild
                .siblings(forward = false)
                .takeWhile { it.elementType !== ZigTokenType.L_BRACKET }
                .firstNotNullOfOrNull { it as? ZigArrayIndex }
}

class ZigArrayCat(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType = ZigTokenType.PLUS_PLUS
}

class ZigArrayIndex(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
    val innerElement: ZigElement
        get() = findNotNullChildByClass(ZigElement::class.java)
}

class ZigArrayTypeElement(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
    val arrayChildElement: ZigElement?
        get() =
            lastChild
                .siblings(forward = false)
                .filterIsInstance<ZigElement>()
                .takeWhile { it !is ZigTypeLength && it !is ZigTypeSentinel }
                .firstOrNull()

    val lengthElement: ZigTypeLength
        get() = findNotNullChildByClass(ZigTypeLength::class.java)

    val sentinelElement: ZigTypeSentinel?
        get() = findChildByClass(ZigTypeSentinel::class.java)
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
    override val mainTokenType = ZigTokenType.EQUAL
}

class ZigAssignAdd(node: ASTNode) : ASTWrapperPsiElement(node), ZigAssignElement {
    override val mainTokenType = ZigTokenType.PLUS_EQUAL
}

class ZigAssignAddSat(node: ASTNode) : ASTWrapperPsiElement(node), ZigAssignElement {
    override val mainTokenType = ZigTokenType.PLUS_PIPE_EQUAL
}

class ZigAssignAddWrap(node: ASTNode) : ASTWrapperPsiElement(node), ZigAssignElement {
    override val mainTokenType = ZigTokenType.PLUS_PERCENT_EQUAL
}

class ZigAssignBitAnd(node: ASTNode) : ASTWrapperPsiElement(node), ZigAssignElement {
    override val mainTokenType = ZigTokenType.AMPERSAND_EQUAL
}

class ZigAssignBitOr(node: ASTNode) : ASTWrapperPsiElement(node), ZigAssignElement {
    override val mainTokenType = ZigTokenType.PIPE_EQUAL
}

class ZigAssignBitXor(node: ASTNode) : ASTWrapperPsiElement(node), ZigAssignElement {
    override val mainTokenType = ZigTokenType.CARET_EQUAL
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
    override val mainTokenType = ZigTokenType.SLASH_EQUAL
}

class ZigAssignMod(node: ASTNode) : ASTWrapperPsiElement(node), ZigAssignElement {
    override val mainTokenType = ZigTokenType.PERCENT_EQUAL
}

class ZigAssignMul(node: ASTNode) : ASTWrapperPsiElement(node), ZigAssignElement {
    override val mainTokenType = ZigTokenType.ASTERISK_EQUAL
}

class ZigAssignMulSat(node: ASTNode) : ASTWrapperPsiElement(node), ZigAssignElement {
    override val mainTokenType = ZigTokenType.ASTERISK_PIPE_EQUAL
}

class ZigAssignMulWrap(node: ASTNode) : ASTWrapperPsiElement(node), ZigAssignElement {
    override val mainTokenType = ZigTokenType.ASTERISK_PERCENT_EQUAL
}

class ZigAssignShl(node: ASTNode) : ASTWrapperPsiElement(node), ZigAssignElement {
    override val mainTokenType = ZigTokenType.ANGLE_BRACKET_ANGLE_BRACKET_LEFT_EQUAL
}

class ZigAssignShlSat(node: ASTNode) : ASTWrapperPsiElement(node), ZigAssignElement {
    override val mainTokenType = ZigTokenType.ANGLE_BRACKET_ANGLE_BRACKET_LEFT_PIPE_EQUAL
}

class ZigAssignShr(node: ASTNode) : ASTWrapperPsiElement(node), ZigAssignElement {
    override val mainTokenType = ZigTokenType.ANGLE_BRACKET_ANGLE_BRACKET_RIGHT_EQUAL
}

class ZigAssignSub(node: ASTNode) : ASTWrapperPsiElement(node), ZigAssignElement {
    override val mainTokenType = ZigTokenType.MINUS_EQUAL
}

class ZigAssignSubSat(node: ASTNode) : ASTWrapperPsiElement(node), ZigAssignElement {
    override val mainTokenType = ZigTokenType.MINUS_PIPE_EQUAL
}

class ZigAssignSubWrap(node: ASTNode) : ASTWrapperPsiElement(node), ZigAssignElement {
    override val mainTokenType = ZigTokenType.MINUS_PERCENT_EQUAL
}

class ZigBangEqual(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType = ZigTokenType.BANG_EQUAL
}

class ZigBitAnd(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType = ZigTokenType.AMPERSAND
}

class ZigBitNot(node: ASTNode) : ASTWrapperPsiElement(node), ZigPrefixExpression

class ZigBitOr(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType = ZigTokenType.PIPE
}

class ZigBitXor(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType = ZigTokenType.CARET
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
    override val mainTokenType = ZigTokenType.KEYWORD_AND
}

class ZigBoolNot(node: ASTNode) : ASTWrapperPsiElement(node), ZigPrefixExpression

class ZigBoolOr(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType = ZigTokenType.KEYWORD_OR
}

class ZigBreak(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
    val labelRef: ZigLabelRef?
        get() = firstChild.siblings().firstNotNullOfOrNull { it as? ZigLabelRef }

    val breakValue: ZigElement?
        get() =
            lastChild
                .siblings(forward = false)
                .filterIsInstance<ZigElement>()
                .takeWhile { it !is ZigLabelRef }
                .firstOrNull()
}

class ZigBuiltinCall(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
    val builtinElement: PsiElement
        get() = findNotNullChildByType(ZigTokenType.BUILTIN)

    val argumentElements: Sequence<ZigCallArg>
        get() = firstChild.siblings().filterIsInstance<ZigCallArg>()

    val hasTrailingComma: Boolean
        get() =
            lastChild
                .siblings(forward = false)
                .firstOrNull { it is ZigCallArg || it.elementType === ZigTokenType.COMMA }
                ?.elementType === ZigTokenType.COMMA
}

class ZigCall(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
    val functionElement: ZigElement
        get() =
            firstChild
                .siblings()
                .filterIsInstance<ZigElement>()
                .takeWhile { it !is ZigCallArg }
                .first()

    val argumentElements: Sequence<ZigCallArg>
        get() = firstChild.siblings().filterIsInstance<ZigCallArg>()

    val arguments: Sequence<ZigElement>
        get() = argumentElements.map { it.innerElement }

    val hasTrailingComma: Boolean
        get() =
            lastChild
                .siblings(forward = false)
                .firstOrNull { it is ZigCallArg || it.elementType === ZigTokenType.COMMA }
                ?.elementType === ZigTokenType.COMMA
}

class ZigCallArg(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
    val containingCall: ZigCall?
        get() = parent as? ZigCall

    val containingBuiltinCall: ZigBuiltinCall?
        get() = parent as? ZigBuiltinCall

    val innerElement: ZigElement
        get() = findNotNullChildByClass(ZigElement::class.java)
}

class ZigCallconvModifier(node: ASTNode) : ASTWrapperPsiElement(node), ZigModifier

class ZigCatch(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType = ZigTokenType.KEYWORD_CATCH

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
    val innerElement: ZigElement?
        get() = findChildByClass(ZigElement::class.java)
}

class ZigConditionElement(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
    val innerElement: ZigElement?
        get() = findChildByClass(ZigElement::class.java)
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

    val containerTagElement: ZigContainerTag?
        get() = findChildByClass(ZigContainerTag::class.java)

    override val members: Sequence<ZigElement>
        get() =
            firstChild
                .siblings()
                .filterIsInstance<ZigElement>()
                .filter { it !is ZigContainerTag }

    override fun isVisibilitySupported() = false
    override fun getElementIcon(flags: Int) = baseIcon
    override fun getBaseIcon() = AllIcons.Nodes.Type

    override fun getPresentation(): ItemPresentation {
        val kind = containerKindElement.text
        val location = "in ${containingFile.name}"
        return PresentationData(kind, location, baseIcon, null)
    }
}

class ZigContainerField : ZigStubPsiElement<ZigContainerFieldStub>, ZigNamedElement {
    constructor(stub: ZigContainerFieldStub) : super(stub, ZigElementType.CONTAINER_FIELD)
    constructor(node: ASTNode) : super(node)

    val typeElement: ZigTypeElement?
        get() = findChildByClass(ZigTypeElement::class.java)

    override fun getNameIdentifier() = findChildByType<PsiElement?>(ZigTokenType.IDENTIFIER)
    override fun getName() = nameIdentifier?.text
    override fun setName(name: String) = replaceZigIdentifierElement(this, nameIdentifier, name)
    override fun getTextOffset() = nameIdentifier?.textOffset ?: super.getTextOffset()

    override fun isVisibilitySupported() = false
    override fun getElementIcon(flags: Int) = baseIcon
    override fun getBaseIcon() = AllIcons.Nodes.Field

    override fun getPresentation(): ItemPresentation {
        val name = nameIdentifier?.text
        val location = "in ${containingFile.name}"
        return PresentationData(name, location, baseIcon, null)
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
    override val mainTokenType = ZigTokenType.SLASH
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
    val containingIf: ZigIf?
        get() = parent as? ZigIf

    val containingWhile: ZigWhile?
        get() = parent as? ZigWhile
}

class ZigEnumLiteral(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
    val identifierElement: PsiElement?
        get() = findChildByType(ZigTokenType.IDENTIFIER)
}

class ZigEqualEqual(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType = ZigTokenType.EQUAL_EQUAL
}

class ZigErrdefer(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigErrorSetDecl(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
    val setItems: Sequence<ZigErrorSetItem>
        get() = firstChild.siblings().filterIsInstance<ZigErrorSetItem>()
}

class ZigErrorSetItem(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
    val identifierElement: PsiElement
        get() = findNotNullChildByType(ZigTokenType.IDENTIFIER)
}

class ZigErrorUnion(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
    val errorSet: ZigElement
        get() =
            firstChild
                .siblings()
                .takeWhile { it.elementType !== ZigTokenType.BANG }
                .filterIsInstance<ZigElement>()
                .first()

    val payload: ZigElement?
        get() =
            lastChild
                .siblings(forward = false)
                .takeWhile { it.elementType !== ZigTokenType.BANG }
                .filterIsInstance<ZigElement>()
                .firstOrNull()
}

class ZigErrorValueElement(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
    val identifierElement: PsiElement?
        get() = findChildByType(ZigTokenType.IDENTIFIER)
}

class ZigFieldAccess(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
    val leftOperand: ZigElement
        get() =
            firstChild
                .siblings()
                .takeWhile { it.elementType !== ZigTokenType.PERIOD }
                .firstNotNullOf { it as? ZigElement }

    val identifierElement: PsiElement?
        get() = findChildByType(ZigTokenType.IDENTIFIER)

    override fun getReferences(): Array<out PsiReference> {
        val identifier = identifierElement ?: return emptyArray()
        return arrayOf(ZigFieldAccessReference(this, identifier))
    }
}

sealed interface ZigFnDeclOrProto : ZigElement {
    val parameters: Sequence<ZigParamDecl>
        get() = firstChild.siblings().filterIsInstance<ZigParamDecl>()

    val ellipsisTokenElement: PsiElement?
        get() = firstChild.siblings().firstOrNull { it.elementType === ZigTokenType.ELLIPSIS3 }

    val bangTokenElement: PsiElement?
        get() = firstChild.siblings().firstOrNull { it.elementType === ZigTokenType.BANG }

    val returnTypeElement: ZigTypeElement?
        get() = firstChild.siblings().filterIsInstance<ZigTypeElement>().firstOrNull()
}

class ZigFnDecl : ZigStubPsiElement<ZigFnDeclStub>, ZigFnDeclOrProto, ZigNamedElement {
    constructor(stub: ZigFnDeclStub) : super(stub, ZigElementType.FN_DECL)
    constructor(node: ASTNode) : super(node)

    val pubTokenElement: PsiElement?
        get() = findChildByType(ZigTokenType.KEYWORD_PUB)

    val isPub: Boolean
        get() = pubTokenElement != null

    val body: ZigBlock?
        get() = findChildByClass(ZigBlock::class.java)

    override fun getNameIdentifier() = findChildByType<PsiElement?>(ZigTokenType.IDENTIFIER)
    override fun getName() = nameIdentifier?.text
    override fun setName(name: String) = replaceZigIdentifierElement(this, nameIdentifier, name)
    override fun getTextOffset() = nameIdentifier?.textOffset ?: super.getTextOffset()

    override fun isVisibilitySupported() = true
    override fun getElementIcon(flags: Int) = addVisibilityIcon(isPub, flags, baseIcon)
    override fun getBaseIcon() = AllIcons.Nodes.Function

    override fun getPresentation(): ItemPresentation {
        val name = nameIdentifier?.text
        val text = if (name != null) "$name()" else null
        val location = "in ${containingFile.name}"
        val icon = getElementIcon(ICON_FLAG_VISIBILITY)
        return PresentationData(text, location, icon, null)
    }
}

class ZigFnProto(node: ASTNode) : ASTWrapperPsiElement(node), ZigFnDeclOrProto

class ZigFor(node: ASTNode) : ASTWrapperPsiElement(node), ZigLabelable {
    val inputs: Sequence<ZigForInput>
        get() = firstChild.siblings().filterIsInstance<ZigForInput>()

    val payload: ZigForPayload?
        get() = findChildByClass(ZigForPayload::class.java)
}

class ZigForInput(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
    val innerElement: ZigElement
        get() = findNotNullChildByClass(ZigElement::class.java)
}

class ZigForPayload(node: ASTNode) : ASTWrapperPsiElement(node), ZigPayload {
    val containingFor: ZigFor
        get() = parent as ZigFor
}

class ZigForRange(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
    val start: ZigElement
        get() =
            firstChild
                .siblings()
                .takeWhile { it.elementType !== ZigTokenType.ELLIPSIS2 }
                .firstNotNullOf { it as? ZigElement }

    val end: ZigElement?
        get() =
            lastChild
                .siblings(forward = false)
                .takeWhile { it.elementType !== ZigTokenType.ELLIPSIS2 }
                .firstNotNullOfOrNull { it as? ZigElement }
}

class ZigGreaterOrEqual(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType = ZigTokenType.ANGLE_BRACKET_RIGHT_EQUAL
}

class ZigGreaterThan(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType = ZigTokenType.ANGLE_BRACKET_RIGHT
}

class ZigGroupedExpression(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
    val innerElement: ZigElement?
        get() = findChildByClass(ZigElement::class.java)
}

private val PRIMITIVES = setOf(
    "anyerror",
    "anyframe",
    "anyopaque",
    "bool",
    "c_int",
    "c_long",
    "c_longdouble",
    "c_longlong",
    "c_char",
    "c_short",
    "c_uint",
    "c_ulong",
    "c_ulonglong",
    "c_ushort",
    "comptime_float",
    "comptime_int",
    "f128",
    "f16",
    "f32",
    "f64",
    "f80",
    "false",
    "isize",
    "noreturn",
    "null",
    "true",
    "type",
    "undefined",
    "usize",
    "void"
)

private val INT_PATTERN: Pattern = Pattern.compile("\\A[iu]\\d+\\Z")

class ZigIdentifier(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
    val identifierElement: PsiElement
        get() = findNotNullChildByType(ZigTokenType.IDENTIFIER)

    val isPrimitive: Boolean
        get() = text.let { it in PRIMITIVES || INT_PATTERN.matcher(it).matches() }

    override fun getReferences() = arrayOf(ZigIdentifierReference(this))
}

class ZigIf(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
    val conditionElement: ZigConditionElement
        get() = findNotNullChildByClass(ZigConditionElement::class.java)

    val thenPayload: ZigThenPayload?
        get() = findChildByClass(ZigThenPayload::class.java)

    val thenElement: ZigThenElement
        get() = findNotNullChildByClass(ZigThenElement::class.java)

    val elseTokenElement: PsiElement?
        get() = findChildByType(ZigTokenType.KEYWORD_ELSE)

    val elsePayload: ZigElsePayload?
        get() = findChildByClass(ZigElsePayload::class.java)

    val elseElement: ZigElseElement?
        get() = findChildByClass(ZigElseElement::class.java)
}

class ZigLabelDecl(node: ASTNode) : ASTWrapperPsiElement(node), ZigNamedElement {
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
    override val mainTokenType = ZigTokenType.ANGLE_BRACKET_LEFT_EQUAL
}

class ZigLessThan(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType = ZigTokenType.ANGLE_BRACKET_LEFT
}

class ZigLinksectionModifier(node: ASTNode) : ASTWrapperPsiElement(node), ZigModifier

class ZigListInit(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
    val typeExpression: ZigElement?
        get() =
            firstChild
                .siblings()
                .takeWhile { it.elementType !== ZigTokenType.L_BRACE }
                .firstNotNullOfOrNull { it as? ZigElement }

    val initItems: Sequence<ZigListInitItem>
        get() = firstChild.siblings().filterIsInstance<ZigListInitItem>()
}

class ZigListInitItem(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
    val identifierElement: PsiElement?
        get() = findChildByType(ZigTokenType.IDENTIFIER)

    val expression: ZigElement?
        get() = findChildByClass(ZigElement::class.java)
}

class ZigMergeErrorSets(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType = ZigTokenType.PIPE_PIPE
}

class ZigMod(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType = ZigTokenType.PERCENT
}

class ZigMul(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType = ZigTokenType.ASTERISK
}

class ZigMulSat(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType = ZigTokenType.ASTERISK_PIPE
}

class ZigMulWrap(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType = ZigTokenType.ASTERISK_PERCENT
}

class ZigMultilineStringLiteral(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigNegation(node: ASTNode) : ASTWrapperPsiElement(node), ZigPrefixExpression

class ZigNegationWrap(node: ASTNode) : ASTWrapperPsiElement(node), ZigPrefixExpression

class ZigNosuspend(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigNumberLiteral(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigOptionalTypeElement(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
    val optionalChildElement: ZigElement?
        get() = findChildByClass(ZigElement::class.java)
}

class ZigOrelse(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType = ZigTokenType.KEYWORD_ORELSE
}

class ZigParamDecl(node: ASTNode) : ASTWrapperPsiElement(node), ZigNamedElement {
    val typeElement: ZigTypeElement?
        get() = findChildByClass(ZigTypeElement::class.java)

    override fun getNameIdentifier() = findChildByType<PsiElement?>(ZigTokenType.IDENTIFIER)
    override fun getName() = nameIdentifier?.text
    override fun setName(name: String) = replaceZigIdentifierElement(this, nameIdentifier, name)
    override fun getTextOffset() = nameIdentifier?.textOffset ?: super.getTextOffset()
}

class ZigPayloadItem(node: ASTNode) : ASTWrapperPsiElement(node), ZigNamedElement {
    val asteriskTokenElement: PsiElement?
        get() = findChildByType(ZigTokenType.ASTERISK)

    val isPointer: Boolean
        get() = asteriskTokenElement != null

    val containingPayload: ZigPayload
        get() = parent as ZigPayload

    override fun getNameIdentifier() = findChildByType<PsiElement?>(ZigTokenType.IDENTIFIER)
    override fun getName() = nameIdentifier?.text
    override fun setName(name: String) = replaceZigIdentifierElement(this, nameIdentifier, name)
    override fun getTextOffset() = nameIdentifier?.textOffset ?: super.getTextOffset()
}

enum class ZigPointerSize { ONE, MANY, SLICE, C }

class ZigPointerTypeElement(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
    val leftBracketTokenElement: PsiElement?
        get() = findChildByType(ZigTokenType.L_BRACKET)

    val asteriskTokenElement: PsiElement?
        get() = findChildByType(ZigTokenType.ASTERISK)

    val identifierElement: PsiElement?
        get() = findChildByType(ZigTokenType.IDENTIFIER)

    val pointerSize: ZigPointerSize
        get() = when (leftBracketTokenElement) {
            null -> ZigPointerSize.ONE
            else -> when (asteriskTokenElement) {
                null -> ZigPointerSize.SLICE
                else -> when (identifierElement) {
                    null -> ZigPointerSize.MANY
                    else -> ZigPointerSize.C
                }
            }
        }

    val pointerChildElement: ZigElement?
        get() =
            lastChild
                .siblings(forward = false)
                .filterIsInstance<ZigElement>()
                .takeWhile { it !is ZigTypeSentinel }
                .firstOrNull()
}

class ZigResume(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigReturn(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
    val returnValue: ZigElement?
        get() = findChildByClass(ZigElement::class.java)
}

class ZigShl(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType = ZigTokenType.ANGLE_BRACKET_ANGLE_BRACKET_LEFT
}

class ZigShlSat(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType = ZigTokenType.ANGLE_BRACKET_ANGLE_BRACKET_LEFT_PIPE
}

class ZigShr(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType = ZigTokenType.ANGLE_BRACKET_ANGLE_BRACKET_RIGHT
}

class ZigSlice(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigSliceIndexes(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigSliceSentinel(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigStringLiteral(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
    val value: String
        get() = text.substring(1, textLength - 1) // TODO: unescape string literal

    override fun getReferences(): Array<out PsiReference> {
        val callArg = parent as? ZigCallArg ?: return emptyArray()
        val builtinCall = callArg.containingBuiltinCall ?: return emptyArray()
        return when (builtinCall.builtinElement.text) {
            "@embedFile" -> emptyArray() // TODO
            "@import" -> arrayOf(ZigImportReference(this))
            else -> emptyArray()
        }
    }
}

class ZigSub(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType = ZigTokenType.MINUS
}

class ZigSubSat(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType = ZigTokenType.MINUS_PIPE
}

class ZigSubWrap(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType = ZigTokenType.MINUS_PERCENT
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

    override fun isVisibilitySupported() = false
    override fun getElementIcon(flags: Int) = baseIcon
    override fun getBaseIcon() = AllIcons.Nodes.Test

    override fun getPresentation(): ItemPresentation {
        val name = nameElement?.text
        val text = if (name != null) "test $name" else "test"
        val location = "in ${containingFile.name}"
        return PresentationData(text, location, baseIcon, null)
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
    val containingIf: ZigIf?
        get() = parent as? ZigIf

    val containingWhile: ZigWhile?
        get() = parent as? ZigWhile
}

class ZigTry(node: ASTNode) : ASTWrapperPsiElement(node), ZigPrefixExpression

class ZigTypeElement(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
    val innerElement: ZigElement?
        get() = findChildByClass(ZigElement::class.java)
}

class ZigTypeLength(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
    val innerElement: ZigElement
        get() = findNotNullChildByClass(ZigElement::class.java)
}

class ZigTypeSentinel(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
    val innerElement: ZigElement?
        get() = findChildByClass(ZigElement::class.java)
}

class ZigUnreachableLiteral(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigUnwrapOptional(node: ASTNode) : ASTWrapperPsiElement(node), ZigPostfixExpression

enum class ZigMutability { CONST, VAR }

sealed interface ZigVarDeclOrProto : ZigNamedElement {
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

class ZigVarDecl : ZigStubPsiElement<ZigVarDeclStub>, ZigVarDeclOrProto {
    constructor(stub: ZigVarDeclStub) : super(stub, ZigElementType.VAR_DECL)
    constructor(node: ASTNode) : super(node)

    val isLocal: Boolean
        get() = findParentInFile { it is ZigBlock || it is ZigContainer } is ZigBlock

    val pubTokenElement: PsiElement?
        get() = findChildByType(ZigTokenType.KEYWORD_PUB)

    val isPub: Boolean
        get() = pubTokenElement != null

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

    override fun isVisibilitySupported() = true
    override fun getElementIcon(flags: Int) = addVisibilityIcon(isPub, flags, baseIcon)
    override fun getBaseIcon() = when (mutability) {
        ZigMutability.CONST -> AllIcons.Nodes.Constant
        ZigMutability.VAR -> AllIcons.Nodes.Variable
    }

    override fun getPresentation(): ItemPresentation {
        val name = nameIdentifier?.text
        val location = "in ${containingFile.name}"
        val icon = getElementIcon(ICON_FLAG_VISIBILITY)
        return PresentationData(name, location, icon, null)
    }
}

class ZigVarProto(node: ASTNode) : ASTWrapperPsiElement(node), ZigVarDeclOrProto {
    override fun getNameIdentifier() = findChildByType<PsiElement?>(ZigTokenType.IDENTIFIER)
    override fun getName() = nameIdentifier?.text
    override fun setName(name: String) = replaceZigIdentifierElement(this, nameIdentifier, name)
    override fun getTextOffset() = nameIdentifier?.textOffset ?: super.getTextOffset()
}

class ZigWhile(node: ASTNode) : ASTWrapperPsiElement(node), ZigLabelable {
    val conditionElement: ZigConditionElement
        get() = findNotNullChildByClass(ZigConditionElement::class.java)

    val thenPayload: ZigThenPayload?
        get() = findChildByClass(ZigThenPayload::class.java)

    val elsePayload: ZigElsePayload?
        get() = findChildByClass(ZigElsePayload::class.java)
}

class ZigWhileContinueExpr(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

fun createZigFile(project: Project, text: String): ZigFile {
    val fileFactory = PsiFileFactory.getInstance(project)
    val file = fileFactory.createFileFromText("dummy.zig", ZigLanguage, text)
    return file as ZigFile
}

fun createZigIdentifier(project: Project, name: String): ZigIdentifier {
    val file = createZigFile(project, "const dummy = $name;")
    val varDecl = file.members.first() as ZigVarDecl
    return varDecl.initializer as ZigIdentifier
}

fun createZigIdentifierElement(project: Project, name: String): PsiElement {
    val identifier = createZigIdentifier(project, name)
    val element = identifier.identifierElement
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

fun createZigStringLiteral(project: Project, value: String): ZigStringLiteral {
    val file = createZigFile(project, "const dummy = \"$value\";") // TODO: escape string literal
    val varDecl = file.members.first() as ZigVarDecl
    return varDecl.initializer as ZigStringLiteral
}

private fun addVisibilityIcon(isPub: Boolean, flags: Int, baseIcon: Icon): Icon {
    if (BitUtil.isSet(flags, Iconable.ICON_FLAG_VISIBILITY)) {
        val visibility = if (isPub) AllIcons.Nodes.Public else AllIcons.Nodes.Private
        return IconManager.getInstance().createRowIcon(baseIcon, visibility)
    }
    return baseIcon
}
