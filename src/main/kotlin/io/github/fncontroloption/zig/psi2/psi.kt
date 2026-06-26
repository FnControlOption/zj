package io.github.fncontroloption.zig.psi2

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.util.elementType
import com.intellij.util.IncorrectOperationException
import io.github.fncontroloption.zig.ZigFileType
import io.github.fncontroloption.zig.ZigLanguage

sealed class ZigElement(node: ASTNode) : ASTWrapperPsiElement(node)

class ZigFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, ZigLanguage) {
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
}

class ZigContainerField(node: ASTNode) : ZigElement(node), PsiNameIdentifierOwner {
    override fun getNameIdentifier() = findChildByType<PsiElement?>(ZigTokenType.IDENTIFIER)
    override fun getName() = nameIdentifier?.text
    override fun setName(name: String) = throw IncorrectOperationException() // TODO
    override fun getTextOffset() = nameIdentifier?.textOffset ?: super.getTextOffset()
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
    override fun getNameIdentifier() = findChildByType<PsiElement?>(ZigTokenType.IDENTIFIER)
    override fun getName() = nameIdentifier?.text
    override fun setName(name: String) = throw IncorrectOperationException() // TODO
    override fun getTextOffset() = nameIdentifier?.textOffset ?: super.getTextOffset()
}

class ZigFnProto(node: ASTNode) : ZigElement(node) {
}

class ZigFor(node: ASTNode) : ZigElement(node) {
}

class ZigForInput(node: ASTNode) : ZigElement(node) {
}

class ZigGreaterOrEqual(node: ASTNode) : ZigElement(node) {
}

class ZigGreaterThan(node: ASTNode) : ZigElement(node) {
}

class ZigGroupedExpression(node: ASTNode) : ZigElement(node) {
}

class ZigIdentifier(node: ASTNode) : ZigElement(node) {
}

class ZigIf(node: ASTNode) : ZigElement(node) {
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
    override fun setName(name: String) = throw IncorrectOperationException() // TODO
    override fun getTextOffset() = nameIdentifier?.textOffset ?: super.getTextOffset()
}

class ZigPayloadItem(node: ASTNode) : ZigElement(node) {
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

class ZigSwitchProng(node: ASTNode) : ZigElement(node) {
}

class ZigTestDecl(node: ASTNode) : ZigElement(node) {
}

class ZigThenElement(node: ASTNode) : ZigElement(node) {
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

class ZigVarargs(node: ASTNode) : ZigElement(node) {
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
}

class ZigVarDecl(node: ASTNode) : ZigElement(node), ZigVarDeclOrProto {
    override val mutabilityElement: PsiElement
        get() = findNotNullChildByType(ZigTokenSets.MUT_TOKENS)

    override fun getNameIdentifier() = findChildByType<PsiElement?>(ZigTokenType.IDENTIFIER)
    override fun getName() = nameIdentifier?.text
    override fun setName(name: String) = throw IncorrectOperationException() // TODO
    override fun getTextOffset() = nameIdentifier?.textOffset ?: super.getTextOffset()
}

class ZigVarProto(node: ASTNode) : ZigElement(node), ZigVarDeclOrProto {
    override val mutabilityElement: PsiElement
        get() = findNotNullChildByType(ZigTokenSets.MUT_TOKENS)

    override fun getNameIdentifier() = findChildByType<PsiElement?>(ZigTokenType.IDENTIFIER)
    override fun getName() = nameIdentifier?.text
    override fun setName(name: String) = throw IncorrectOperationException() // TODO
    override fun getTextOffset() = nameIdentifier?.textOffset ?: super.getTextOffset()
}

class ZigWhile(node: ASTNode) : ZigElement(node) {
}

class ZigWhileContinueExpr(node: ASTNode) : ZigElement(node) {
}

