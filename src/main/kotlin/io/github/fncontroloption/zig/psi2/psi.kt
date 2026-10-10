package io.github.fncontroloption.zig.psi2

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.extapi.psi.StubBasedPsiElementBase
import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.PresentationData
import com.intellij.lang.ASTNode
import com.intellij.lang.Language
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Iconable
import com.intellij.psi.*
import com.intellij.psi.impl.light.LightElement
import com.intellij.psi.stubs.StubElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.*
import com.intellij.ui.IconManager
import com.intellij.util.BitUtil
import com.intellij.util.IncorrectOperationException
import io.github.fncontroloption.zig.*
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

sealed interface ZigNavigatableElement : ZigElement, NavigatablePsiElement {
    override fun getPresentation(): ItemPresentation
}

sealed interface ZigNamedElement : ZigNavigatableElement, PsiNamedElement

sealed interface ZigNameIdentifierOwner : ZigNamedElement, PsiNameIdentifierOwner

sealed interface ZigExpression : ZigElement

sealed interface ZigUnaryExpression : ZigExpression {
    val operand: ZigExpression?
        get() = firstChild.siblings().firstNotNullOfOrNull { it as? ZigExpression }
}

sealed interface ZigPrefixExpression : ZigUnaryExpression

sealed interface ZigPostfixExpression : ZigUnaryExpression

sealed interface ZigBinaryExpression : ZigExpression {
    val leftOperand: ZigExpression
        get() =
            firstChild
                .siblings()
                .takeWhile { it.elementType !== mainTokenType }
                .firstNotNullOf { it as? ZigExpression }

    val mainTokenType: ZigTokenType

    val mainTokenElement: PsiElement
        get() = firstChild.siblings().first { it.elementType === mainTokenType }

    val rightOperand: ZigExpression?
        get() =
            lastChild
                .siblings(forward = false)
                .takeWhile { it.elementType !== mainTokenType }
                .firstNotNullOfOrNull { it as? ZigExpression }
}

sealed interface ZigAssignElement : ZigElement {
    val leftExpression: ZigExpression
        get() =
            firstChild
                .siblings()
                .takeWhile { it.elementType !== mainTokenType }
                .firstNotNullOf { it as? ZigExpression }

    val mainTokenType: ZigTokenType

    val mainTokenElement: PsiElement
        get() = firstChild.siblings().first { it.elementType === mainTokenType }

    val rightExpression: ZigExpression?
        get() =
            lastChild
                .siblings(forward = false)
                .takeWhile { it.elementType !== mainTokenType }
                .firstNotNullOfOrNull { it as? ZigExpression }
}

sealed interface ZigLabelable : ZigExpression {
    val labelDecl: ZigLabelDecl?
        get() = firstChild.siblings().firstNotNullOfOrNull { it as? ZigLabelDecl }
}

sealed interface ZigLoop : ZigLabelable {
    val thenElement: ZigThenElement
        get() = firstChild.siblings().firstNotNullOf { it as? ZigThenElement }

    val elseElement: ZigElseElement?
        get() = firstChild.siblings().firstNotNullOfOrNull { it as? ZigElseElement }
}

sealed interface ZigModifier : ZigElement {
    val expression: ZigExpression?
        get() = firstChild.siblings().firstNotNullOfOrNull { it as? ZigExpression }
}

sealed interface ZigPayload : ZigElement {
    val items: Sequence<ZigPayloadItem>
        get() = firstChild.siblings().filterIsInstance<ZigPayloadItem>()
}

enum class ZigContainerKind { STRUCT, OPAQUE, ENUM, UNION }

sealed interface ZigContainerMember : ZigElement {
    val container: ZigContainer?
}

sealed interface ZigNamedContainerMember : ZigNameIdentifierOwner, ZigContainerMember

sealed interface ZigContainerDecl : ZigContainerMember

sealed interface ZigNamedContainerDecl : ZigNamedContainerMember, ZigContainerDecl

sealed interface ZigContainerField : ZigNamedContainerMember

sealed interface ZigContainer : ZigElement {
    val containerKind: ZigContainerKind

    val containerArgElement: ZigContainerArg?

    val isTaggedUnion: Boolean
        get() = containerKind == ZigContainerKind.UNION && containerArgElement != null

    val members: Sequence<ZigContainerMember>
}

class ZigFile(
    viewProvider: FileViewProvider,
    language: Language,
) : PsiFileBase(viewProvider, language), ZigContainer, ZigNamedElement {
    override val containerKind: ZigContainerKind
        get() = ZigContainerKind.STRUCT

    override val containerArgElement: ZigContainerArg?
        get() = null

    override val members: Sequence<ZigContainerMember>
        get() = firstChild?.siblings()?.filterIsInstance<ZigContainerMember>() ?: emptySequence()

    override fun getPresentation(): ItemPresentation = super<PsiFileBase>.presentation!!

    override fun getFileType() = ZigFileType
    override fun toString() = "ZigFile:$name"
}

class ZonFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, ZonLanguage) {
    val expression: ZigExpression?
        get() = findChildByClass(ZigExpression::class.java)

    override fun getFileType() = ZonFileType
    override fun toString() = "ZonFile:$name"
}

class ZigAdd(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: ZigTokenType
        get() = ZigTokenType.PLUS
}

class ZigAddSat(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: ZigTokenType
        get() = ZigTokenType.PLUS_PIPE
}

class ZigAddWrap(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: ZigTokenType
        get() = ZigTokenType.PLUS_PERCENT
}

class ZigAddressOf(node: ASTNode) : ASTWrapperPsiElement(node), ZigPrefixExpression

class ZigAddrspaceModifier(node: ASTNode) : ASTWrapperPsiElement(node), ZigModifier

class ZigAlignModifier(node: ASTNode) : ASTWrapperPsiElement(node), ZigModifier

class ZigAnyframeLiteral(node: ASTNode) : ASTWrapperPsiElement(node), ZigExpression {
}

class ZigAnyframeType(node: ASTNode) : ASTWrapperPsiElement(node), ZigExpression {
}

class ZigArrayAccess(node: ASTNode) : ASTWrapperPsiElement(node), ZigExpression {
    val leftOperand: ZigExpression
        get() =
            firstChild
                .siblings()
                .takeWhile { it.elementType !== ZigTokenType.L_BRACKET }
                .firstNotNullOf { it as? ZigExpression }

    val indexElement: ZigArrayIndex?
        get() =
            lastChild
                .siblings(forward = false)
                .takeWhile { it.elementType !== ZigTokenType.L_BRACKET }
                .firstNotNullOfOrNull { it as? ZigArrayIndex }
}

class ZigArrayCat(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: ZigTokenType
        get() = ZigTokenType.PLUS_PLUS
}

class ZigArrayIndex(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
    val expression: ZigExpression
        get() = findNotNullChildByClass(ZigExpression::class.java)
}

class ZigArrayTypeExpression(node: ASTNode) : ASTWrapperPsiElement(node), ZigExpression {
    val childExpression: ZigExpression?
        get() =
            lastChild
                .siblings(forward = false)
                .firstNotNullOfOrNull { it as? ZigExpression }

    val lengthElement: ZigTypeLength
        get() = findNotNullChildByClass(ZigTypeLength::class.java)

    val sentinelElement: ZigTypeSentinel?
        get() = findChildByClass(ZigTypeSentinel::class.java)
}

class ZigAsm(node: ASTNode) : ASTWrapperPsiElement(node), ZigExpression {
}

class ZigAsmClobbers(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigAsmInputItem(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigAsmOutputItem(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigAssign(node: ASTNode) : ASTWrapperPsiElement(node), ZigAssignElement {
    override val mainTokenType: ZigTokenType
        get() = ZigTokenType.EQUAL
}

class ZigAssignAdd(node: ASTNode) : ASTWrapperPsiElement(node), ZigAssignElement {
    override val mainTokenType: ZigTokenType
        get() = ZigTokenType.PLUS_EQUAL
}

class ZigAssignAddSat(node: ASTNode) : ASTWrapperPsiElement(node), ZigAssignElement {
    override val mainTokenType: ZigTokenType
        get() = ZigTokenType.PLUS_PIPE_EQUAL
}

class ZigAssignAddWrap(node: ASTNode) : ASTWrapperPsiElement(node), ZigAssignElement {
    override val mainTokenType: ZigTokenType
        get() = ZigTokenType.PLUS_PERCENT_EQUAL
}

class ZigAssignBitAnd(node: ASTNode) : ASTWrapperPsiElement(node), ZigAssignElement {
    override val mainTokenType: ZigTokenType
        get() = ZigTokenType.AMPERSAND_EQUAL
}

class ZigAssignBitOr(node: ASTNode) : ASTWrapperPsiElement(node), ZigAssignElement {
    override val mainTokenType: ZigTokenType
        get() = ZigTokenType.PIPE_EQUAL
}

class ZigAssignBitXor(node: ASTNode) : ASTWrapperPsiElement(node), ZigAssignElement {
    override val mainTokenType: ZigTokenType
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

    val initializer: ZigExpression?
        get() =
            equalTokenElement
                ?.siblings(forward = true, withSelf = false)
                ?.firstNotNullOfOrNull { it as? ZigExpression }
}

class ZigAssignDiv(node: ASTNode) : ASTWrapperPsiElement(node), ZigAssignElement {
    override val mainTokenType: ZigTokenType
        get() = ZigTokenType.SLASH_EQUAL
}

class ZigAssignMod(node: ASTNode) : ASTWrapperPsiElement(node), ZigAssignElement {
    override val mainTokenType: ZigTokenType
        get() = ZigTokenType.PERCENT_EQUAL
}

class ZigAssignMul(node: ASTNode) : ASTWrapperPsiElement(node), ZigAssignElement {
    override val mainTokenType: ZigTokenType
        get() = ZigTokenType.ASTERISK_EQUAL
}

class ZigAssignMulSat(node: ASTNode) : ASTWrapperPsiElement(node), ZigAssignElement {
    override val mainTokenType: ZigTokenType
        get() = ZigTokenType.ASTERISK_PIPE_EQUAL
}

class ZigAssignMulWrap(node: ASTNode) : ASTWrapperPsiElement(node), ZigAssignElement {
    override val mainTokenType: ZigTokenType
        get() = ZigTokenType.ASTERISK_PERCENT_EQUAL
}

class ZigAssignShl(node: ASTNode) : ASTWrapperPsiElement(node), ZigAssignElement {
    override val mainTokenType: ZigTokenType
        get() = ZigTokenType.ANGLE_BRACKET_ANGLE_BRACKET_LEFT_EQUAL
}

class ZigAssignShlSat(node: ASTNode) : ASTWrapperPsiElement(node), ZigAssignElement {
    override val mainTokenType: ZigTokenType
        get() = ZigTokenType.ANGLE_BRACKET_ANGLE_BRACKET_LEFT_PIPE_EQUAL
}

class ZigAssignShr(node: ASTNode) : ASTWrapperPsiElement(node), ZigAssignElement {
    override val mainTokenType: ZigTokenType
        get() = ZigTokenType.ANGLE_BRACKET_ANGLE_BRACKET_RIGHT_EQUAL
}

class ZigAssignSub(node: ASTNode) : ASTWrapperPsiElement(node), ZigAssignElement {
    override val mainTokenType: ZigTokenType
        get() = ZigTokenType.MINUS_EQUAL
}

class ZigAssignSubSat(node: ASTNode) : ASTWrapperPsiElement(node), ZigAssignElement {
    override val mainTokenType: ZigTokenType
        get() = ZigTokenType.MINUS_PIPE_EQUAL
}

class ZigAssignSubWrap(node: ASTNode) : ASTWrapperPsiElement(node), ZigAssignElement {
    override val mainTokenType: ZigTokenType
        get() = ZigTokenType.MINUS_PERCENT_EQUAL
}

class ZigBangEqual(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: ZigTokenType
        get() = ZigTokenType.BANG_EQUAL
}

class ZigBitAnd(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: ZigTokenType
        get() = ZigTokenType.AMPERSAND
}

class ZigBitNot(node: ASTNode) : ASTWrapperPsiElement(node), ZigPrefixExpression

class ZigBitOr(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: ZigTokenType
        get() = ZigTokenType.PIPE
}

class ZigBitXor(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: ZigTokenType
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
    override val mainTokenType: ZigTokenType
        get() = ZigTokenType.KEYWORD_AND
}

class ZigBoolNot(node: ASTNode) : ASTWrapperPsiElement(node), ZigPrefixExpression

class ZigBoolOr(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: ZigTokenType
        get() = ZigTokenType.KEYWORD_OR
}

class ZigBreak(node: ASTNode) : ASTWrapperPsiElement(node), ZigExpression {
    val labelRef: ZigLabelRef?
        get() = firstChild.siblings().firstNotNullOfOrNull { it as? ZigLabelRef }

    val breakOperand: ZigExpression?
        get() =
            lastChild
                .siblings(forward = false)
                .firstNotNullOfOrNull { it as? ZigExpression }
}

sealed interface ZigCallOrBuiltinCall : ZigExpression {
    val leftParenElement: PsiElement
        get() = firstChild.siblings().first { it.elementType == ZigTokenType.L_PAREN }

    val argumentElements: Sequence<ZigCallArg>
        get() = firstChild.siblings().filterIsInstance<ZigCallArg>()

    val arguments: Sequence<ZigExpression>
        get() = argumentElements.map { it.expression }

    val hasTrailingComma: Boolean
        get() =
            lastChild
                .siblings(forward = false)
                .firstOrNull { it is ZigCallArg || it.elementType === ZigTokenType.COMMA }
                ?.elementType === ZigTokenType.COMMA
}

class ZigBuiltinCall(node: ASTNode) : ASTWrapperPsiElement(node), ZigCallOrBuiltinCall {
    val builtinElement: PsiElement
        get() = findNotNullChildByType(ZigTokenType.BUILTIN)

    override fun getReferences(): Array<out PsiReference> {
        return arrayOf(ZigBuiltinCallReference(this))
    }
}

class ZigCall(node: ASTNode) : ASTWrapperPsiElement(node), ZigCallOrBuiltinCall {
    val functionExpression: ZigExpression
        get() =
            firstChild
                .siblings()
                .firstNotNullOf { it as? ZigExpression }
}

class ZigCallArg(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
    val containingCall: ZigCall?
        get() = parent as? ZigCall

    val containingBuiltinCall: ZigBuiltinCall?
        get() = parent as? ZigBuiltinCall

    val expression: ZigExpression
        get() = findNotNullChildByClass(ZigExpression::class.java)
}

class ZigCallconvModifier(node: ASTNode) : ASTWrapperPsiElement(node), ZigModifier

class ZigCatch(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: ZigTokenType
        get() = ZigTokenType.KEYWORD_CATCH

    val payload: ZigCatchPayload?
        get() = findChildByClass(ZigCatchPayload::class.java)
}

class ZigCatchPayload(node: ASTNode) : ASTWrapperPsiElement(node), ZigPayload {
    val containingCatch: ZigCatch
        get() = parent as ZigCatch
}

class ZigCharLiteral(node: ASTNode) : ASTWrapperPsiElement(node), ZigExpression {
}

class ZigComptime(node: ASTNode) : ASTWrapperPsiElement(node), ZigContainerDecl, ZigExpression {
    override val container: ZigContainer?
        get() = parent as? ZigContainer

    val innerElement: ZigElement?
        get() = findChildByClass(ZigElement::class.java)
}

class ZigConditionElement(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
    val expression: ZigExpression?
        get() = findChildByClass(ZigExpression::class.java)
}

class ZigContainerArg(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
    val enumTokenElement: PsiElement?
        get() = findChildByType(ZigTokenType.KEYWORD_ENUM)

    val isInferredEnum: Boolean
        get() = enumTokenElement != null

    val expression: ZigExpression?
        get() = findChildByClass(ZigExpression::class.java)
}

class ZigContainerExpression(node: ASTNode) :
    ASTWrapperPsiElement(node), ZigContainer, ZigExpression, ZigNavigatableElement {

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

    override val containerArgElement: ZigContainerArg?
        get() = findChildByClass(ZigContainerArg::class.java)

    override val members: Sequence<ZigContainerMember>
        get() = firstChild.siblings().filterIsInstance<ZigContainerMember>()

    override fun isVisibilitySupported() = false
    override fun getElementIcon(flags: Int) = baseIcon
    override fun getBaseIcon() = AllIcons.Nodes.Type

    override fun getPresentation(): ItemPresentation {
        val kind = containerKindElement.text
        val location = "(in ${containingFile.name})"
        return PresentationData(kind, location, baseIcon, null)
    }
}

class ZigContainerFieldImpl : ZigStubPsiElement<ZigContainerFieldStub>, ZigContainerField {
    constructor(stub: ZigContainerFieldStub) : super(stub, ZigElementType.CONTAINER_FIELD)
    constructor(node: ASTNode) : super(node)

    override val container: ZigContainer
        get() = parent as ZigContainer

    val typeElement: ZigTypeElement?
        get() = findChildByClass(ZigTypeElement::class.java)

    val equalTokenElement: PsiElement?
        get() = findChildByType(ZigTokenType.EQUAL)

    val initializer: ZigExpression?
        get() =
            equalTokenElement
                ?.siblings(forward = true, withSelf = false)
                ?.firstNotNullOfOrNull { it as? ZigExpression }

    override fun getNameIdentifier() = findChildByType<PsiElement?>(ZigTokenType.IDENTIFIER)
    override fun getTextOffset() = nameIdentifier?.textOffset ?: super.getTextOffset()
    override fun getName() = parseZigIdentifier(nameIdentifier)
    override fun setName(name: String): PsiElement {
        val quote = when (container.containerKind) {
            ZigContainerKind.ENUM -> ZigIdQuoteBehavior.EAGERLY_UNQUOTE_EXCEPT_UNDERSCORE
            else -> ZigIdQuoteBehavior.EAGERLY_UNQUOTE
        }
        return replaceZigIdentifierElement(this, nameIdentifier, name, quote)
    }

    override fun isVisibilitySupported() = false
    override fun getElementIcon(flags: Int) = baseIcon
    override fun getBaseIcon() = AllIcons.Nodes.Field

    override fun getPresentation(): ItemPresentation {
        val location = "(in ${containingFile.name})"
        return PresentationData(name, location, baseIcon, null)
    }
}

class ZigContinue(node: ASTNode) : ASTWrapperPsiElement(node), ZigExpression {
    val labelRef: ZigLabelRef?
        get() = firstChild.siblings().firstNotNullOfOrNull { it as? ZigLabelRef }

    val continueOperand: ZigExpression?
        get() =
            lastChild
                .siblings(forward = false)
                .firstNotNullOfOrNull { it as? ZigExpression }
}

class ZigDefer(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigDeref(node: ASTNode) : ASTWrapperPsiElement(node), ZigPostfixExpression

class ZigDiv(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: ZigTokenType
        get() = ZigTokenType.SLASH
}

class ZigElseElement(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
    val payload: ZigElsePayload?
        get() = when (val parent = parent) {
            is ZigIf -> parent.elsePayload
            is ZigWhile -> parent.elsePayload
            else -> null
        }

    val expression: ZigExpression?
        get() = findChildByClass(ZigExpression::class.java)
}

class ZigElsePayload(node: ASTNode) : ASTWrapperPsiElement(node), ZigPayload {
    val containingIf: ZigIf?
        get() = parent as? ZigIf

    val containingWhile: ZigWhile?
        get() = parent as? ZigWhile
}

class ZigEnumLiteral(node: ASTNode) : ASTWrapperPsiElement(node), ZigExpression {
    val identifierElement: PsiElement?
        get() = findChildByType(ZigTokenType.IDENTIFIER)

    override fun getReferences(): Array<out PsiReference> {
        val identifier = identifierElement ?: return emptyArray()
        return arrayOf(ZigEnumLiteralReference(this, identifier))
    }
}

class ZigEqualEqual(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: ZigTokenType
        get() = ZigTokenType.EQUAL_EQUAL
}

class ZigErrdefer(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigErrorSet(node: ASTNode) : ASTWrapperPsiElement(node), ZigExpression {
    val setItems: Sequence<ZigErrorSetItem>
        get() = firstChild.siblings().filterIsInstance<ZigErrorSetItem>()
}

class ZigErrorSetItem(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
    val identifierElement: PsiElement
        get() = findNotNullChildByType(ZigTokenType.IDENTIFIER)
}

class ZigErrorUnion(node: ASTNode) : ASTWrapperPsiElement(node), ZigExpression {
    val errorSet: ZigExpression
        get() =
            firstChild
                .siblings()
                .takeWhile { it.elementType !== ZigTokenType.BANG }
                .firstNotNullOf { it as? ZigExpression }

    val payload: ZigExpression?
        get() =
            lastChild
                .siblings(forward = false)
                .takeWhile { it.elementType !== ZigTokenType.BANG }
                .firstNotNullOfOrNull { it as? ZigExpression }
}

class ZigErrorValueExpression(node: ASTNode) : ASTWrapperPsiElement(node), ZigExpression {
    val identifierElement: PsiElement?
        get() = findChildByType(ZigTokenType.IDENTIFIER)
}

class ZigFieldAccess(node: ASTNode) : ASTWrapperPsiElement(node), ZigExpression {
    val leftOperand: ZigExpression
        get() =
            firstChild
                .siblings()
                .takeWhile { it.elementType !== ZigTokenType.PERIOD }
                .firstNotNullOf { it as? ZigExpression }

    val identifierElement: PsiElement?
        get() = findChildByType(ZigTokenType.IDENTIFIER)

    override fun getReferences(): Array<out PsiReference> {
        val identifier = identifierElement ?: return emptyArray()
        return arrayOf(ZigFieldAccessReference(this, identifier))
    }
}

sealed interface ZigFnOrBuiltinFnDeclOrProto : ZigElement {
    val parameters: Sequence<ZigFnParam>
        get() = firstChild.siblings().filterIsInstance<ZigFnParam>()

    val ellipsisTokenElement: PsiElement?
        get() = firstChild.siblings().firstOrNull { it.elementType === ZigTokenType.ELLIPSIS3 }

    val hasVarargs: Boolean
        get() = ellipsisTokenElement != null

    val bangTokenElement: PsiElement?
        get() = lastChild.siblings(forward = false).firstOrNull { it.elementType === ZigTokenType.BANG }

    val returnTypeElement: ZigTypeElement?
        get() = lastChild.siblings(forward = false).firstNotNullOfOrNull { it as? ZigTypeElement }

    val body: ZigBlock?
}

sealed interface ZigFnDeclOrProto : ZigFnOrBuiltinFnDeclOrProto

class ZigFnDecl : ZigStubPsiElement<ZigFnDeclStub>, ZigFnDeclOrProto, ZigNamedContainerDecl {
    constructor(stub: ZigFnDeclStub) : super(stub, ZigElementType.FN_DECL)
    constructor(node: ASTNode) : super(node)

    override val container: ZigContainer
        get() = parent as ZigContainer

    val pubTokenElement: PsiElement?
        get() = findChildByType(ZigTokenType.KEYWORD_PUB)

    val isPub: Boolean
        get() = pubTokenElement != null

    override val body: ZigBlock?
        get() = findChildByClass(ZigBlock::class.java)

    override fun getNameIdentifier() = findChildByType<PsiElement?>(ZigTokenType.IDENTIFIER)
    override fun getTextOffset() = nameIdentifier?.textOffset ?: super.getTextOffset()
    override fun getName() = parseZigIdentifier(nameIdentifier)
    override fun setName(name: String) =
        replaceZigIdentifierElement(this, nameIdentifier, name, ZigIdQuoteBehavior.PRESERVE_WHEN_SHADOWING)

    override fun isVisibilitySupported() = true
    override fun getElementIcon(flags: Int) =
        addVisibilityIcon(flags, baseIcon) {
            if (isPub) AllIcons.Nodes.Public else AllIcons.Nodes.Private
        }

    override fun getBaseIcon() = AllIcons.Nodes.Function

    override fun getPresentation(): ItemPresentation {
        val text = name?.let { "$it()" }
        val location = "(in ${containingFile.name})"
        val icon = getElementIcon(ICON_FLAG_VISIBILITY)
        return PresentationData(text, location, icon, null)
    }
}

class ZigFnProto(node: ASTNode) : ASTWrapperPsiElement(node), ZigFnDeclOrProto, ZigExpression {
    override val body: ZigBlock?
        get() = null
}

class ZigBuiltinFnProto(node: ASTNode) : ASTWrapperPsiElement(node), ZigFnOrBuiltinFnDeclOrProto, ZigNavigatableElement {
    val builtinElement: PsiElement
        get() = findNotNullChildByType(ZigTokenType.BUILTIN)

    val anytypeTokenElement: PsiElement?
        get() = findChildByType(ZigTokenType.KEYWORD_ANYTYPE)

    val returnsAnytype: Boolean
        get() = anytypeTokenElement != null

    override val body: ZigBlock?
        get() = null

    override fun isVisibilitySupported() = false
    override fun getElementIcon(flags: Int) = baseIcon
    override fun getBaseIcon() = AllIcons.Nodes.Function

    override fun getPresentation(): ItemPresentation {
        val name = builtinElement.text
        return PresentationData(name, null, baseIcon, null)
    }

    override fun navigate(requestFocus: Boolean) {}
    override fun canNavigate() = false
    override fun canNavigateToSource() = false
}

class ZigFor(node: ASTNode) : ASTWrapperPsiElement(node), ZigLoop {
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
    val start: ZigExpression
        get() =
            firstChild
                .siblings()
                .takeWhile { it.elementType !== ZigTokenType.ELLIPSIS2 }
                .firstNotNullOf { it as? ZigExpression }

    val end: ZigExpression?
        get() =
            lastChild
                .siblings(forward = false)
                .takeWhile { it.elementType !== ZigTokenType.ELLIPSIS2 }
                .firstNotNullOfOrNull { it as? ZigExpression }
}

class ZigGreaterOrEqual(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: ZigTokenType
        get() = ZigTokenType.ANGLE_BRACKET_RIGHT_EQUAL
}

class ZigGreaterThan(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: ZigTokenType
        get() = ZigTokenType.ANGLE_BRACKET_RIGHT
}

class ZigGroupedExpression(node: ASTNode) : ASTWrapperPsiElement(node), ZigExpression {
    val innerExpression: ZigExpression?
        get() = findChildByClass(ZigExpression::class.java)
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

fun isZigPrimitiveText(chars: CharSequence): Boolean {
    return PRIMITIVES.any { chars.contentEquals(it) } || INT_PATTERN.matcher(chars).matches()
}

class ZigIdentifier(node: ASTNode) : ASTWrapperPsiElement(node), ZigExpression {
    val identifierElement: PsiElement
        get() = findNotNullChildByType(ZigTokenType.IDENTIFIER)

    override fun getReferences() = arrayOf(ZigIdentifierReference(this))
}

class ZigIf(node: ASTNode) : ASTWrapperPsiElement(node), ZigExpression {
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

class ZigLabelDecl(node: ASTNode) : ASTWrapperPsiElement(node), ZigNameIdentifierOwner {
    val identifierElement: PsiElement
        get() = findNotNullChildByType(ZigTokenType.IDENTIFIER)

    override fun getNameIdentifier() = identifierElement
    override fun getTextOffset() = identifierElement.textOffset
    override fun getName() = parseZigIdentifier(identifierElement)
    override fun setName(name: String) =
        replaceZigIdentifierElement(this, identifierElement, name, ZigIdQuoteBehavior.EAGERLY_UNQUOTE)

    override fun getPresentation(): ItemPresentation {
        val location = "(in ${containingFile.name})"
        return PresentationData(name, location, null, null)
    }
}

class ZigLabelRef(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
    val identifierElement: PsiElement
        get() = findNotNullChildByType(ZigTokenType.IDENTIFIER)

    override fun getReferences() = arrayOf(ZigLabelReference(this))
}

class ZigLessOrEqual(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: ZigTokenType
        get() = ZigTokenType.ANGLE_BRACKET_LEFT_EQUAL
}

class ZigLessThan(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: ZigTokenType
        get() = ZigTokenType.ANGLE_BRACKET_LEFT
}

class ZigLinksectionModifier(node: ASTNode) : ASTWrapperPsiElement(node), ZigModifier

class ZigListInit(node: ASTNode) : ASTWrapperPsiElement(node), ZigExpression {
    val typeExpression: ZigExpression?
        get() =
            firstChild
                .siblings()
                .takeWhile { it.elementType !== ZigTokenType.L_BRACE }
                .firstNotNullOfOrNull { it as? ZigExpression }

    val initItems: Sequence<ZigListInitItem>
        get() = firstChild.siblings().filterIsInstance<ZigListInitItem>()
}

class ZigListInitItem(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
    val containingListInit: ZigListInit
        get() = parent as ZigListInit

    val identifierElement: PsiElement?
        get() = findChildByType(ZigTokenType.IDENTIFIER)

    val isStructField: Boolean
        get() = identifierElement != null

    val expression: ZigExpression?
        get() = findChildByClass(ZigExpression::class.java)

    override fun getReferences(): Array<out PsiReference> {
        val identifier = identifierElement ?: return emptyArray()
        return arrayOf(ZigListInitItemReference(this, identifier))
    }
}

class ZigMergeErrorSets(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: ZigTokenType
        get() = ZigTokenType.PIPE_PIPE
}

class ZigMod(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: ZigTokenType
        get() = ZigTokenType.PERCENT
}

class ZigMul(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: ZigTokenType
        get() = ZigTokenType.ASTERISK
}

class ZigMulSat(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: ZigTokenType
        get() = ZigTokenType.ASTERISK_PIPE
}

class ZigMulWrap(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: ZigTokenType
        get() = ZigTokenType.ASTERISK_PERCENT
}

class ZigMultilineStringLiteral(node: ASTNode) : ASTWrapperPsiElement(node), ZigStringExpression {
    override val value: String?
        get() = null // TODO
}

class ZigNegation(node: ASTNode) : ASTWrapperPsiElement(node), ZigPrefixExpression

class ZigNegationWrap(node: ASTNode) : ASTWrapperPsiElement(node), ZigPrefixExpression

class ZigNosuspend(node: ASTNode) : ASTWrapperPsiElement(node), ZigExpression {
}

class ZigNumberLiteral(node: ASTNode) : ASTWrapperPsiElement(node), ZigExpression {
}

class ZigOptionalTypeExpression(node: ASTNode) : ASTWrapperPsiElement(node), ZigExpression {
    val childExpression: ZigExpression?
        get() = findChildByClass(ZigExpression::class.java)
}

class ZigOrelse(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: ZigTokenType
        get() = ZigTokenType.KEYWORD_ORELSE
}

class ZigFnParam(node: ASTNode) : ASTWrapperPsiElement(node), ZigNameIdentifierOwner {
    val containingFunction: ZigFnOrBuiltinFnDeclOrProto
        get() = parent as ZigFnOrBuiltinFnDeclOrProto

    val comptimeTokenElement: PsiElement?
        get() = findChildByType(ZigTokenType.KEYWORD_COMPTIME)

    val isComptime: Boolean
        get() = comptimeTokenElement != null

    val typeElement: ZigTypeElement?
        get() = findChildByClass(ZigTypeElement::class.java)

    val anytypeTokenElement: PsiElement?
        get() = findChildByType(ZigTokenType.KEYWORD_ANYTYPE)

    val isAnytype: Boolean
        get() = anytypeTokenElement != null

    override fun getNameIdentifier() = findChildByType<PsiElement?>(ZigTokenType.IDENTIFIER)
    override fun getTextOffset() = nameIdentifier?.textOffset ?: super.getTextOffset()
    override fun getName() = parseZigIdentifier(nameIdentifier)
    override fun setName(name: String) =
        replaceZigIdentifierElement(this, nameIdentifier, name, ZigIdQuoteBehavior.PRESERVE_WHEN_SHADOWING)

    override fun isVisibilitySupported() = true
    override fun getElementIcon(flags: Int) =
        addVisibilityIcon(flags, baseIcon) { AllIcons.Nodes.PackageLocal }

    override fun getBaseIcon() = AllIcons.Nodes.Parameter

    override fun getPresentation(): ItemPresentation {
        val location = "(in ${containingFile.name})"
        val icon = getElementIcon(ICON_FLAG_VISIBILITY)
        return PresentationData(name, location, icon, null)
    }
}

class ZigPayloadItem(node: ASTNode) : ASTWrapperPsiElement(node), ZigNameIdentifierOwner {
    val asteriskTokenElement: PsiElement?
        get() = findChildByType(ZigTokenType.ASTERISK)

    val isPointer: Boolean
        get() = asteriskTokenElement != null

    val containingPayload: ZigPayload
        get() = parent as ZigPayload

    override fun getNameIdentifier() = findChildByType<PsiElement?>(ZigTokenType.IDENTIFIER)
    override fun getTextOffset() = nameIdentifier?.textOffset ?: super.getTextOffset()
    override fun getName() = parseZigIdentifier(nameIdentifier)
    override fun setName(name: String) =
        replaceZigIdentifierElement(this, nameIdentifier, name, ZigIdQuoteBehavior.PRESERVE_WHEN_SHADOWING)

    override fun isVisibilitySupported() = true
    override fun getElementIcon(flags: Int) =
        addVisibilityIcon(flags, baseIcon) { AllIcons.Nodes.PackageLocal }

    override fun getBaseIcon() = AllIcons.Nodes.Constant

    override fun getPresentation(): ItemPresentation {
        val location = "(in ${containingFile.name})"
        val icon = getElementIcon(ICON_FLAG_VISIBILITY)
        return PresentationData(name, location, icon, null)
    }
}

enum class ZigPointerSize { ONE, MANY, SLICE, C }

class ZigPointerTypeExpression(node: ASTNode) : ASTWrapperPsiElement(node), ZigExpression {
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

    val sentinelElement: ZigTypeSentinel?
        get() = findChildByClass(ZigTypeSentinel::class.java)

    val constTokenElement: PsiElement?
        get() = findChildByType(ZigTokenType.KEYWORD_CONST)

    val isConst: Boolean
        get() = constTokenElement != null

    val mutability: ZigMutability
        get() = if (isConst) ZigMutability.CONST else ZigMutability.VAR

    val childExpression: ZigExpression?
        get() =
            lastChild
                .siblings(forward = false)
                .firstNotNullOfOrNull { it as? ZigExpression }
}

class ZigResume(node: ASTNode) : ASTWrapperPsiElement(node), ZigExpression {
}

class ZigReturn(node: ASTNode) : ASTWrapperPsiElement(node), ZigExpression {
    val returnOperand: ZigExpression?
        get() = findChildByClass(ZigExpression::class.java)
}

class ZigShl(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: ZigTokenType
        get() = ZigTokenType.ANGLE_BRACKET_ANGLE_BRACKET_LEFT
}

class ZigShlSat(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: ZigTokenType
        get() = ZigTokenType.ANGLE_BRACKET_ANGLE_BRACKET_LEFT_PIPE
}

class ZigShr(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: ZigTokenType
        get() = ZigTokenType.ANGLE_BRACKET_ANGLE_BRACKET_RIGHT
}

class ZigSlice(node: ASTNode) : ASTWrapperPsiElement(node), ZigExpression {
    val sliced: ZigExpression
        get() =
            firstChild
                .siblings()
                .takeWhile { it.elementType !== ZigTokenType.L_BRACKET }
                .firstNotNullOf { it as? ZigExpression }

    val indexesElement: ZigSliceIndexes
        get() = findNotNullChildByClass(ZigSliceIndexes::class.java)

    val sentinelElement: ZigSliceSentinel?
        get() = findChildByClass(ZigSliceSentinel::class.java)
}

class ZigSliceIndexes(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
    val start: ZigExpression
        get() =
            firstChild
                .siblings()
                .takeWhile { it.elementType !== ZigTokenType.ELLIPSIS2 }
                .firstNotNullOf { it as? ZigExpression }

    val end: ZigExpression?
        get() =
            lastChild
                .siblings(forward = false)
                .takeWhile { it.elementType !== ZigTokenType.ELLIPSIS2 }
                .firstNotNullOfOrNull { it as? ZigExpression }
}

class ZigSliceSentinel(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
    val expression: ZigExpression?
        get() = findChildByClass(ZigExpression::class.java)
}

sealed interface ZigStringExpression : ZigExpression {
    val value: String?
}

class ZigStringLiteral(node: ASTNode) : ASTWrapperPsiElement(node), ZigStringExpression {
    override val value: String?
        get() = ZigStringLiteralParser.parse(text)

    override fun getReferences(): Array<out PsiReference> {
        val callArg = parent as? ZigCallArg ?: return emptyArray()
        val builtinCall = callArg.containingBuiltinCall ?: return emptyArray()
        return when (builtinCall.builtinElement.text) {
            "@embedFile" -> arrayOf(ZigEmbedFileReference(this))
            "@import" -> arrayOf(ZigImportReference(this))
            else -> emptyArray()
        }
    }
}

class ZigSub(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: ZigTokenType
        get() = ZigTokenType.MINUS
}

class ZigSubSat(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: ZigTokenType
        get() = ZigTokenType.MINUS_PIPE
}

class ZigSubWrap(node: ASTNode) : ASTWrapperPsiElement(node), ZigBinaryExpression {
    override val mainTokenType: ZigTokenType
        get() = ZigTokenType.MINUS_PERCENT
}

class ZigSuspend(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigSwitch(node: ASTNode) : ASTWrapperPsiElement(node), ZigLabelable {
    val conditionElement: ZigConditionElement
        get() = findNotNullChildByClass(ZigConditionElement::class.java)

    val prongs: Sequence<ZigSwitchProng>
        get() = firstChild.siblings().filterIsInstance<ZigSwitchProng>()
}

class ZigSwitchItem(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
    val containingSwitchProng: ZigSwitchProng
        get() = parent as ZigSwitchProng

    val expression: ZigExpression
        get() = findNotNullChildByClass(ZigExpression::class.java)
}

class ZigSwitchPayload(node: ASTNode) : ASTWrapperPsiElement(node), ZigPayload {
    val containingSwitchProng: ZigSwitchProng
        get() = parent as ZigSwitchProng
}

class ZigSwitchProng(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
    val containingSwitch: ZigSwitch
        get() = parent as ZigSwitch

    val inlineTokenElement: PsiElement?
        get() = findChildByType(ZigTokenType.KEYWORD_INLINE)

    val isInline: Boolean
        get() = inlineTokenElement != null

    val elseTokenElement: PsiElement?
        get() = findChildByType(ZigTokenType.KEYWORD_ELSE)

    val isElse: Boolean
        get() = elseTokenElement != null

    val items: Sequence<ZigSwitchItem>
        get() = firstChild.siblings().filterIsInstance<ZigSwitchItem>()

    val payload: ZigSwitchPayload?
        get() = findChildByClass(ZigSwitchPayload::class.java)

    val expression: ZigExpression?
        get() =
            lastChild
                .siblings(forward = false)
                .firstNotNullOfOrNull { it as? ZigExpression }
}

class ZigSwitchRange(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

class ZigTestDecl(node: ASTNode) : ASTWrapperPsiElement(node), ZigContainerDecl {
    override val container: ZigContainer
        get() = parent as ZigContainer

    val nameElement: PsiElement?
        get() = findChildByType(ZigTokenSets.TEST_NAME)

    override fun isVisibilitySupported() = false
    override fun getElementIcon(flags: Int) = baseIcon
    override fun getBaseIcon() = AllIcons.Nodes.Test

    override fun getPresentation(): ItemPresentation {
        val name = nameElement?.text
        val text = if (name != null) "test $name" else "test"
        val location = "(in ${containingFile.name})"
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

    val expression: ZigExpression?
        get() = findChildByClass(ZigExpression::class.java)
}

class ZigThenPayload(node: ASTNode) : ASTWrapperPsiElement(node), ZigPayload {
    val containingIf: ZigIf?
        get() = parent as? ZigIf

    val containingWhile: ZigWhile?
        get() = parent as? ZigWhile
}

class ZigTry(node: ASTNode) : ASTWrapperPsiElement(node), ZigPrefixExpression

class ZigTypeElement(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
    val expression: ZigExpression?
        get() = findChildByClass(ZigExpression::class.java)
}

class ZigTypeLength(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
    val expression: ZigExpression
        get() = findNotNullChildByClass(ZigExpression::class.java)
}

class ZigTypeSentinel(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
    val expression: ZigExpression?
        get() = findChildByClass(ZigExpression::class.java)
}

class ZigUnreachableLiteral(node: ASTNode) : ASTWrapperPsiElement(node), ZigExpression {
}

class ZigUnwrapOptional(node: ASTNode) : ASTWrapperPsiElement(node), ZigPostfixExpression

enum class ZigMutability { CONST, VAR }

sealed interface ZigVarDeclOrProto : ZigNameIdentifierOwner {
    val isLocal: Boolean

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

class ZigVarDecl : ZigStubPsiElement<ZigVarDeclStub>, ZigVarDeclOrProto, ZigNamedContainerDecl {
    constructor(stub: ZigVarDeclStub) : super(stub, ZigElementType.VAR_DECL)
    constructor(node: ASTNode) : super(node)

    override val container: ZigContainer?
        get() = parent as? ZigContainer

    override val isLocal: Boolean
        get() = findParentInFile { it is ZigBlock || it is ZigContainer } is ZigBlock

    val pubTokenElement: PsiElement?
        get() = findChildByType(ZigTokenType.KEYWORD_PUB)

    val isPub: Boolean
        get() = pubTokenElement != null

    val equalTokenElement: PsiElement?
        get() = findChildByType(ZigTokenType.EQUAL)

    val initializer: ZigExpression?
        get() =
            equalTokenElement
                ?.siblings(forward = true, withSelf = false)
                ?.firstNotNullOfOrNull { it as? ZigExpression }

    override fun getNameIdentifier() = findChildByType<PsiElement?>(ZigTokenType.IDENTIFIER)
    override fun getTextOffset() = nameIdentifier?.textOffset ?: super.getTextOffset()
    override fun getName() = parseZigIdentifier(nameIdentifier)
    override fun setName(name: String) =
        replaceZigIdentifierElement(this, nameIdentifier, name, ZigIdQuoteBehavior.PRESERVE_WHEN_SHADOWING)

    override fun isVisibilitySupported() = true
    override fun getElementIcon(flags: Int) =
        addVisibilityIcon(flags, baseIcon) {
            if (isLocal)
                AllIcons.Nodes.PackageLocal
            else if (isPub)
                AllIcons.Nodes.Public
            else
                AllIcons.Nodes.Private
        }

    override fun getBaseIcon() = when (mutability) {
        ZigMutability.CONST -> AllIcons.Nodes.Constant
        ZigMutability.VAR -> AllIcons.Nodes.Variable
    }

    override fun getPresentation(): ItemPresentation {
        val location = "(in ${containingFile.name})"
        val icon = getElementIcon(ICON_FLAG_VISIBILITY)
        return PresentationData(name, location, icon, null)
    }
}

class ZigVarProto(node: ASTNode) : ASTWrapperPsiElement(node), ZigVarDeclOrProto {
    val containingAssignDestructure: ZigAssignDestructure
        get() = parent as ZigAssignDestructure

    override val isLocal: Boolean
        get() = true

    override fun getNameIdentifier() = findChildByType<PsiElement?>(ZigTokenType.IDENTIFIER)
    override fun getTextOffset() = nameIdentifier?.textOffset ?: super.getTextOffset()
    override fun getName() = parseZigIdentifier(nameIdentifier)
    override fun setName(name: String) =
        replaceZigIdentifierElement(this, nameIdentifier, name, ZigIdQuoteBehavior.PRESERVE_WHEN_SHADOWING)

    override fun isVisibilitySupported() = true
    override fun getElementIcon(flags: Int) =
        addVisibilityIcon(flags, baseIcon) { AllIcons.Nodes.PackageLocal }

    override fun getBaseIcon() = when (mutability) {
        ZigMutability.CONST -> AllIcons.Nodes.Constant
        ZigMutability.VAR -> AllIcons.Nodes.Variable
    }

    override fun getPresentation(): ItemPresentation {
        val location = "(in ${containingFile.name})"
        val icon = getElementIcon(ICON_FLAG_VISIBILITY)
        return PresentationData(name, location, icon, null)
    }
}

class ZigWhile(node: ASTNode) : ASTWrapperPsiElement(node), ZigLoop {
    val conditionElement: ZigConditionElement
        get() = findNotNullChildByClass(ZigConditionElement::class.java)

    val thenPayload: ZigThenPayload?
        get() = findChildByClass(ZigThenPayload::class.java)

    val whileContinueExpr: ZigWhileContinueExpr?
        get() = findChildByClass(ZigWhileContinueExpr::class.java)

    val elsePayload: ZigElsePayload?
        get() = findChildByClass(ZigElsePayload::class.java)
}

class ZigWhileContinueExpr(node: ASTNode) : ASTWrapperPsiElement(node), ZigElement {
}

sealed class LightZigElement(manager: PsiManager) : LightElement(manager, ZigLanguage), ZigElement {
    override fun toString() = this::class.simpleName ?: "LightZigElement"
}

class LightZigTuple(
    manager: PsiManager,
    val fieldTypes: List<ZigValue>,
) : LightZigElement(manager), ZigContainer {
    val fields = fieldTypes.indices.map { LightZigTupleField(this, it) }

    override val containerKind: ZigContainerKind
        get() = ZigContainerKind.STRUCT

    override val containerArgElement: ZigContainerArg?
        get() = null

    override val members: Sequence<LightZigTupleField>
        get() = fields.asSequence()

    override fun equals(other: Any?) = other is LightZigTuple && other.fieldTypes == fieldTypes
    override fun hashCode() = fieldTypes.hashCode()
}

data class LightZigTupleField(
    override val container: LightZigTuple,
    val fieldIndex: Int,
) : LightZigElement(container.manager), ZigContainerField {
    override fun getNameIdentifier() = null
    override fun getName() = fieldIndex.toString()
    override fun setName(name: String) = throw IncorrectOperationException()

    override fun isVisibilitySupported() = false
    override fun getElementIcon(flags: Int) = baseIcon
    override fun getBaseIcon() = AllIcons.Nodes.Field

    override fun getPresentation(): ItemPresentation {
        return PresentationData(name, null, baseIcon, null)
    }
}

fun createZigFile(project: Project, text: String): ZigFile {
    val fileFactory = PsiFileFactory.getInstance(project)
    val file = fileFactory.createFileFromText("dummy.zig", ZigLanguage, text)
    return file as ZigFile
}

fun createZigIdentifier(project: Project, name: String, quote: ZigIdQuoteBehavior): ZigIdentifier {
    val text = buildString {
        append("const dummy = ")
        renderZigIdentifier(name, quote, this)
        append(';')
    }
    val file = createZigFile(project, text)
    val varDecl = file.members.first() as ZigVarDecl
    return varDecl.initializer as ZigIdentifier
}

fun createZigIdentifierElement(project: Project, name: String, quote: ZigIdQuoteBehavior): PsiElement {
    val identifier = createZigIdentifier(project, name, quote)
    val element = identifier.identifierElement
    assert(element.elementType == ZigTokenType.IDENTIFIER)
    return element
}

fun replaceZigIdentifierElement(
    parent: PsiElement,
    oldElement: PsiElement?,
    newName: String,
    quote: ZigIdQuoteBehavior,
): PsiElement {
    if (oldElement == null) {
        throw IncorrectOperationException()
    }
    val newElement = createZigIdentifierElement(parent.project, newName, quote)
    oldElement.replace(newElement)
    return parent
}

fun createZigStringLiteral(project: Project, value: String): ZigStringLiteral {
    val text = buildString {
        append("const dummy = ")
        escapeZigString(value, this)
        append(";")
    }
    val file = createZigFile(project, text)
    val varDecl = file.members.first() as ZigVarDecl
    return varDecl.initializer as ZigStringLiteral
}

// Based on std.zig.stringEscape
// https://codeberg.org/ziglang/zig/src/commit/e19a73c/lib/std/zig.zig#L540-L585
fun escapeZigString(chars: CharSequence, builder: StringBuilder) {
    builder.append('"')
    for (c in chars) {
        when (c) {
            '\t' -> builder.append("\\t")
            '\n' -> builder.append("\\n")
            '\r' -> builder.append("\\r")
            '\\' -> builder.append("\\\\")
            '"' -> builder.append("\\\"")
            else -> when (val code = c.code) {
                in 0..8, 11, 12, in 14..0x1f, 0x7f -> {
                    builder.append("\\x")
                    builder.append(code.toString(16).padStart(2, '0'))
                }
                else -> builder.append(c)
            }
        }
    }
    builder.append('"')
}

fun parseZigIdentifier(element: PsiElement?): String? {
    if (element == null) return null
    return CachedValuesManager.getCachedValue(element) {
        val value = parseZigIdentifierUncached(element)
        CachedValueProvider.Result.create(value, element)
    }
}

private fun parseZigIdentifierUncached(element: PsiElement): String? {
    assert(
        element.elementType === ZigTokenType.IDENTIFIER
                || element.elementType === ZigElementType.IDENTIFIER
    )
    val text = element.text
    return if (text.startsWith('@')) {
        ZigStringLiteralParser.parse(text.subSequence(1, text.length))
    } else {
        text
    }
}

fun escapeZigIdentifier(chars: CharSequence): String {
    val builder = StringBuilder()
    escapeZigIdentifier(chars, builder)
    return builder.toString()
}

fun escapeZigIdentifier(chars: CharSequence, builder: StringBuilder) {
    builder.append('@')
    escapeZigString(chars, builder)
}

enum class ZigIdQuoteBehavior {
    EAGERLY_UNQUOTE,
    EAGERLY_UNQUOTE_EXCEPT_UNDERSCORE,
    PRESERVE_WHEN_SHADOWING,
}

fun renderZigIdentifier(chars: CharSequence, quote: ZigIdQuoteBehavior): String {
    val builder = StringBuilder()
    renderZigIdentifier(chars, quote, builder)
    return builder.toString()
}

// Based on std.zig.Ast.Render.renderIdentifier
// https://codeberg.org/ziglang/zig/src/commit/5b9147e/lib/std/zig/Ast/Render.zig#L2806-L2909
fun renderZigIdentifier(chars: CharSequence, quote: ZigIdQuoteBehavior, builder: StringBuilder) {
    if (chars.isEmpty()) {
        escapeZigIdentifier(chars, builder)
        return
    }
    if (chars.length == 1 && chars[0] == '_') {
        when (quote) {
            ZigIdQuoteBehavior.EAGERLY_UNQUOTE -> {
                builder.append(chars)
                return
            }
            ZigIdQuoteBehavior.EAGERLY_UNQUOTE_EXCEPT_UNDERSCORE,
            ZigIdQuoteBehavior.PRESERVE_WHEN_SHADOWING -> {
                escapeZigIdentifier(chars, builder)
                return
            }
        }
    }
    if (!isValidZigIdentifier(chars)) {
        escapeZigIdentifier(chars, builder)
        return
    }
    if (isZigPrimitiveText(chars)) {
        when (quote) {
            ZigIdQuoteBehavior.EAGERLY_UNQUOTE,
            ZigIdQuoteBehavior.EAGERLY_UNQUOTE_EXCEPT_UNDERSCORE -> {
                builder.append(chars)
                return
            }
            ZigIdQuoteBehavior.PRESERVE_WHEN_SHADOWING -> {
                escapeZigIdentifier(chars, builder)
                return
            }
        }
    }
    builder.append(chars)
}

internal val KEYWORDS = setOf(
    "addrspace",
    "align",
    "allowzero",
    "and",
    "anyframe",
    "anytype",
    "asm",
    "break",
    "callconv",
    "catch",
    "comptime",
    "const",
    "continue",
    "defer",
    "else",
    "enum",
    "errdefer",
    "error",
    "export",
    "extern",
    "fn",
    "for",
    "if",
    "inline",
    "linksection",
    "noalias",
    "noinline",
    "nosuspend",
    "opaque",
    "or",
    "orelse",
    "packed",
    "pub",
    "resume",
    "return",
    "struct",
    "suspend",
    "switch",
    "test",
    "threadlocal",
    "try",
    "union",
    "unreachable",
    "var",
    "volatile",
    "while",
)

private fun isValidZigIdentifier(chars: CharSequence): Boolean {
    if (chars.isEmpty()) {
        return false
    }
    for ((i, c) in chars.withIndex()) {
        when (c) {
            '_', in 'a'..'z', in 'A'..'Z' -> {}
            in '0'..'9' -> if (i == 0) return false
            else -> return false
        }
    }
    return KEYWORDS.none { chars.contentEquals(it) }
}

private inline fun addVisibilityIcon(
    flags: Int,
    baseIcon: Icon,
    visibility: () -> Icon,
): Icon {
    if (BitUtil.isSet(flags, Iconable.ICON_FLAG_VISIBILITY)) {
        return IconManager.getInstance().createRowIcon(baseIcon, visibility())
    }
    return baseIcon
}
