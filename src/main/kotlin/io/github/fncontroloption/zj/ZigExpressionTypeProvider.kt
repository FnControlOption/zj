package io.github.fncontroloption.zj

import com.intellij.lang.ExpressionTypeProvider
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.util.parents

class ZigExpressionTypeProvider : ExpressionTypeProvider<ZigExpression>() {
    override fun getInformationHint(element: ZigExpression): String {
        val value = ZigAnalyzer.resolveValue(element)
        return StringUtil.escapeXmlEntities(value.typeOf.toPlainText())
    }

    override fun getErrorHint(): String {
        return "No expression found"
    }

    override fun getExpressionsAt(elementAt: PsiElement): List<ZigExpression> {
        val result = mutableListOf<ZigExpression>()
        for (element in elementAt.parents(withSelf = true)) {
            when (element) {
                is ZigBlock -> {
                    if (element.labelDecl != null) {
                        result.add(element)
                    }
                }
                is ZigContainerExpression -> {}
                is ZigBreak -> {}
                is ZigContinue -> {}
                is ZigReturn -> {}
                is ZigExpression -> result.add(element)
                else -> {}
            }
        }
        return result
    }
}
