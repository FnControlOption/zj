package io.github.fncontroloption.zj

import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerBase
import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerFactoryBase
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.util.elementType
import com.intellij.psi.util.parents
import com.intellij.util.Consumer

private fun isNoreturnBuiltinCall(builtinCall: ZigBuiltinCall): Boolean {
    val project = builtinCall.project
    val name = builtinCall.builtinElement.text
    val builtin = ZigBuiltinFunctionsService.getBuiltinFnProto(project, name) ?: return false
    val returnType = builtin.returnTypeElement ?: return false
    return returnType.textMatches("noreturn")
}

/**
 * @see com.intellij.codeInsight.highlighting.HighlightExitPointsHandlerFactory
 * @see org.jetbrains.kotlin.idea.base.highlighting.AbstractKotlinHighlightExitPointsHandlerFactory
 */
class ZigHighlightExitPointsHandlerFactory : HighlightUsagesHandlerFactoryBase(), DumbAware {
    override fun createHighlightUsagesHandler(
        editor: Editor,
        psiFile: PsiFile,
        target: PsiElement,
    ): HighlightUsagesHandlerBase<*>? {
        return createFunctionExitPointsHandler(editor, psiFile, target)
            ?: createLabelableExitPointsHandler(editor, psiFile, target)
    }
}

private fun createFunctionExitPointsHandler(
    editor: Editor,
    psiFile: PsiFile,
    target: PsiElement,
): HighlightUsagesHandlerBase<*>? {
    val parent = target.parent
    val shouldHighlight = when (parent) {
        is ZigFnDecl -> target.elementType == ZigTokenType.KEYWORD_FN && parent.body != null
        is ZigReturn -> target.elementType == ZigTokenType.KEYWORD_RETURN
        is ZigTry -> target.elementType == ZigTokenType.KEYWORD_TRY
        is ZigBuiltinCall -> isNoreturnBuiltinCall(parent)
        else -> return null
    }
    if (!shouldHighlight) {
        return null
    }
    return ZigFunctionExitPointsHandler(editor, psiFile, parent)
}

private class ZigFunctionExitPointsHandler(
    editor: Editor,
    file: PsiFile,
    val originalElement: ZigElement,
) : HighlightUsagesHandlerBase<ZigFnDecl>(editor, file) {
    override fun getTargets(): List<ZigFnDecl> {
        if (originalElement is ZigFnDecl) {
            return listOf(originalElement)
        }
        val function =
            originalElement
                .parents(withSelf = false)
                .takeWhile { it !is ZigContainer }
                .filterIsInstance<ZigFnDecl>()
                .firstOrNull()
        return listOfNotNull(function)
    }

    override fun selectTargets(targets: List<ZigFnDecl>, selectionConsumer: Consumer<in List<ZigFnDecl>>) {
        selectionConsumer.consume(targets)
    }

    override fun computeUsages(targets: List<ZigFnDecl>) {
        for (function in targets) {
            addOccurrence(function.fnTokenElement)
            SyntaxTraverser
                .psiTraverser(function.body)
                .expand { it !is ZigContainer }
                .asSequence()
                .filter {
                    when (it) {
                        is ZigReturn -> true
                        is ZigTry -> true
                        is ZigBuiltinCall -> isNoreturnBuiltinCall(it)
                        else -> false
                    }
                }
                .forEach(::addOccurrence)
        }
    }
}

private fun createLabelableExitPointsHandler(
    editor: Editor,
    psiFile: PsiFile,
    target: PsiElement,
): HighlightUsagesHandlerBase<*>? {
    val parent = target.parent
    val shouldHighlight = when (parent) {
        is ZigWhile -> target.elementType == ZigTokenType.KEYWORD_WHILE
        is ZigFor -> target.elementType == ZigTokenType.KEYWORD_FOR
        is ZigSwitch -> target.elementType == ZigTokenType.KEYWORD_SWITCH && parent.labelDecl != null
        is ZigBlock -> ZigTokenSets.BRACES.contains(target.elementType) && parent.labelDecl != null
        is ZigBreak -> target.elementType == ZigTokenType.KEYWORD_BREAK
        is ZigContinue -> target.elementType == ZigTokenType.KEYWORD_CONTINUE
        else -> return null
    }
    if (!shouldHighlight) {
        return null
    }
    return ZigLabelableExitPointsHandler(editor, psiFile, parent)
}

private class ZigLabelableExitPointsHandler(
    editor: Editor,
    file: PsiFile,
    val originalElement: ZigElement,
) : HighlightUsagesHandlerBase<ZigLabelable>(editor, file) {
    override fun getTargets(): List<ZigLabelable> {
        return when (originalElement) {
            is ZigLabelable -> listOf(originalElement)
            is ZigBreakOrContinue -> listOfNotNull(originalElement.referencedLabelable)
            else -> throw IllegalStateException()
        }
    }

    override fun selectTargets(targets: List<ZigLabelable>, selectionConsumer: Consumer<in List<ZigLabelable>>) {
        selectionConsumer.consume(targets)
    }

    override fun computeUsages(targets: List<ZigLabelable>) {
        for (labelable in targets) {
            when (labelable) {
                is ZigBlock -> {
                    addOccurrence(labelable.leftBraceElement)
                    labelable.rightBraceElement?.let(::addOccurrence)
                }
                is ZigFor -> addOccurrence(labelable.forTokenElement)
                is ZigWhile -> addOccurrence(labelable.whileTokenElement)
                is ZigSwitch -> addOccurrence(labelable.switchTokenElement)
            }
            val label = parseZigIdentifier(labelable.labelDecl?.identifierElement)
            if (label != null) {
                SyntaxTraverser
                    .psiTraverser(labelable)
                    .expand { it !is ZigContainer }
                    .asSequence()
                    .filterIsInstance<ZigBreakOrContinue>()
                    .filter { label == parseZigIdentifier(it.labelRef?.identifierElement) }
                    .forEach(::addOccurrence)
            }
            if (labelable is ZigLoop) {
                SyntaxTraverser
                    .psiTraverser(labelable.thenElement)
                    .expand { descendant ->
                        when (descendant) {
                            is ZigContainer -> false
                            is ZigThenElement -> when (descendant.parent) {
                                labelable -> true
                                is ZigLoop -> false
                                else -> true
                            }
                            else -> true
                        }
                    }
                    .asSequence()
                    .filterIsInstance<ZigBreakOrContinue>()
                    .filter { it.labelRef == null }
                    .forEach(::addOccurrence)
            }
        }
    }
}
