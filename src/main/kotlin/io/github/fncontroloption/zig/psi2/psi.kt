package io.github.fncontroloption.zig.psi2

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.psi.FileViewProvider
import io.github.fncontroloption.zig.ZigFileType
import io.github.fncontroloption.zig.ZigLanguage

class ZigFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, ZigLanguage) {
    override fun getFileType() = ZigFileType
    override fun toString() = "ZigFile:$name"
}

class ZigAdd(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigAddSat(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigAddWrap(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigAddressOf(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigAddrspaceModifier(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigAlignModifier(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigAnyframeLiteral(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigAnyframeType(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigArrayAccess(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigArrayCat(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigArrayType(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigAsm(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigAsmClobbers(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigAsmInputItem(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigAsmOutputItem(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigAssign(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigAssignAdd(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigAssignAddSat(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigAssignAddWrap(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigAssignBitAnd(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigAssignBitOr(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigAssignBitXor(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigAssignDestructure(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigAssignDiv(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigAssignMod(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigAssignMul(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigAssignMulSat(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigAssignMulWrap(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigAssignShl(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigAssignShlSat(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigAssignShr(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigAssignSub(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigAssignSubSat(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigAssignSubWrap(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigBangEqual(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigBitAnd(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigBitNot(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigBitOr(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigBitXor(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigBlock(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigBoolAnd(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigBoolNot(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigBoolOr(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigBreak(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigBuiltinCall(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigCall(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigCallArgs(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigCallconvModifier(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigCatch(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigCharLiteral(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigComptime(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigConditionElement(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigContainerDecl(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigContainerField(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigContainerTag(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigContainerTagEnum(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigContinue(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigDefer(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigDeref(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigDiv(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigElseElement(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigEnumLiteral(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigEqualEqual(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigErrdefer(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigErrorSetDecl(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigErrorSetItem(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigErrorUnion(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigErrorValue(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigFieldAccess(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigFnDecl(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigFnProto(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigFor(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigForInput(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigGreaterOrEqual(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigGreaterThan(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigGroupedExpression(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigIdentifier(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigIf(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigIndexes(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigLessOrEqual(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigLessThan(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigLinksectionModifier(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigListInit(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigListInitItem(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigMergeErrorSets(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigMod(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigMul(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigMulSat(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigMulWrap(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigMultilineStringLiteral(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigNegation(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigNegationWrap(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigNosuspend(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigNumberLiteral(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigOptionalType(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigOrelse(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigParamDecl(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigPayloadItem(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigPointerType(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigResume(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigReturn(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigShl(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigShlSat(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigShr(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigSlice(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigSliceEnd(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigSliceSentinel(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigStringLiteral(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigSub(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigSubSat(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigSubWrap(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigSuspend(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigSwitch(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigSwitchItem(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigSwitchProng(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigTestDecl(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigThenElement(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigTry(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigTypeElement(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigTypeLength(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigTypeSentinel(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigUnreachableLiteral(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigUnwrapOptional(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigVarargs(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigVarDecl(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigVarProto(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigWhile(node: ASTNode) : ASTWrapperPsiElement(node) {
}

class ZigWhileContinueExpr(node: ASTNode) : ASTWrapperPsiElement(node) {
}

